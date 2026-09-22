# RikkaMinis 开发档案 · 主题索引（SAGAS）

> **这是档案的「主题视角」**。同一批条目，换一根轴来读：
> - `rikkaminis-dev-history.md` — 时间轴（这天发生了什么）
> - `rikkaminis-dev-history-INDEX.md` — 按天索引（快速跳转）
> - **本文件** — 主题轴（这件事的完整历史，跨 51 天）
>
> 由 `skills/dev-history-sync/scripts/build_sagas.py` 从档案自动生成，可重复执行。

- 覆盖范围：2026-08-03 ～ 2026-09-22，共 51 天
- 条目总数：1204（源文件：rikkaminis-dev-history.md）
- 主题数：23
- 未归入任何主题：78 条（6%，列在文末）
- 归入 ≥4 个主题（说明规则偏松）：74 条
- 标 `↳` 的条目是**推断归属**：标题里没有主题词（「X 完成 / X 收尾」这类收尾条目，主题词在**上一条**里），由「12 小时内最近一条有主题的条目」继承而来，共 88 条。

### 怎么读

- **想知道某件事的完整历史** → 找对应主题，表格按时间列出全部相关条目。
- **想知道某天发生了什么** → 回到 `rikkaminis-dev-history.md` 或 `-INDEX.md`。
- **每条主题的「叙事」是我对这条线的一次性概括**（人工写的，不是生成的）——
  如果读下来觉得某条线总结错了，说明该重读那批条目。
- 条目可以同时属于多个主题，这是**刻意的**：
  「provider-exec-concurrency + 预算墙」既是流式中断线也是 provider 线。

## 目录

| # | 主题 | 条数 | 跨度 | 状态 |
|---|------|------|------|------|
| 1 | 滚动跟随 / 流式跳动 | 67 | 08-05 ～ 09-19 | 观察中 |
| 2 | 输入框 / 键盘 / 粘贴 | 27 | 08-04 ～ 09-20 | 已闭环 |
| 3 | native 内存 / OOM / 崩溃 | 115 | 08-03 ～ 09-22 | 持续跟踪 |
| 4 | 沙箱 / rootfs / PRoot / 终端 | 104 | 08-03 ～ 09-21 | 稳定 |
| 5 | 流式回答中断 / 恢复 / 取消 | 120 | 08-03 ～ 09-21 | 已闭环 |
| 6 | 思考 / 推理泄漏 | 57 | 08-08 ～ 09-22 | 已闭环 |
| 7 | 上下文压缩 / 记忆 / 预算 | 107 | 08-06 ～ 09-21 | 稳定 |
| 8 | Provider / 模型组 / 负载均衡 | 116 | 08-04 ～ 09-22 | 已闭环 |
| 9 | 备份 / 多端同步 | 102 | 08-03 ～ 09-21 | 收敛 |
| 10 | 渲染性能 / Markdown / 长会话卡顿 | 100 | 08-05 ～ 09-21 | 已闭环 + 持续加固 |
| 11 | 审计 / 整改（多轮） | 226 | 08-03 ～ 09-22 | 常态机制 |
| 12 | 多会话并行协作 / 派发 / 交接 | 202 | 08-04 ～ 09-22 | 成熟 |
| 13 | CI / 构建 / 发布流水线 | 180 | 08-03 ～ 09-22 | 稳定 |
| 14 | 开发档案 / 记忆 / 工具链 | 92 | 08-03 ～ 09-21 | 常态维护 |
| 15 | 会话 / 导航 / 抽屉交互 | 81 | 08-03 ～ 09-22 | 已闭环 |
| 16 | 平台适配 / 通知 / 图标 / 权限 | 71 | 08-03 ～ 09-20 | 部分放弃 |
| 17 | 国际化 / 文案 / 本地化 | 25 | 08-04 ～ 09-19 | 已闭环 |
| 18 | 上游 / 生态吸收 / 开源 | 58 | 08-03 ～ 09-21 | 常态 |
| 19 | UI 组件 / 设置页 / 交互微调 | 83 | 08-04 ～ 09-21 | 持续 |
| 20 | Token 用量 / 成本统计 | 54 | 08-05 ～ 09-22 | 已闭环 |
| 21 | 人格 / Soul / 提示词 / 技能体系 | 60 | 08-04 ～ 09-21 | 稳定 |
| 22 | 语音 / 多模态输入 | 22 | 08-03 ～ 09-21 | 低优先级维护 |
| 23 | 被否掉的方向（决策记录） | 37 | 08-04 ～ 09-20 | 持续累积 |

---

## 1. 滚动跟随 / 流式跳动

**跨度** 2026-08-05 ～ 2026-09-19 · **67 条** · **状态** 观察中（09-13 第 5 轮已合并 main；真机复测窗口内未再复发）

**叙事**：08-05 流式输出 UI 跳动 → 08-07 定位 USER_SEND 无条件滚动漏网、触底触发器重构 → 08-08 单锚点守护重构 → 08-15/16 animateContentSize 冲突 + 跳顶死区 → 08-25~27 rikkahub 流畅性吸收 E、历史对话回底部连翻两轮 → 09-01 place-storm 钳位 → 09-13 打开会话「差一段」第 5 轮。**39 天里至少 5 次「以为修好了」**，最终收敛为「单锚点守护 + 显式跟随条件 + 回底部 catch-up」。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-05 19:48 | RikkaMinis 流式输出时 UI 跳动 bug — 定位+修复（2026-08-05） | 用户报：AI 工作时(Ui)界面偶尔跳动，读某段时突然跳到另一段。用户提供了 minis-2026-08-05.log（5k 行，含 ScrollSrc + ScrollFAB2 日志）。 |
| 08-05 20:01 | RikkaMinis 流式输出时 UI 跳动 bug — 修复已合并 main（2026-08-05 收尾） | 用户报：AI 工作时 Ui 界面偶尔跳动，读某段时突然跳到另一段。用户提供了 minis-2026-08-05.log（5073 行，含 ScrollSrc + ScrollFAB2 日志）。 |
| 08-05 21:40 | ↳ RikkaMinis — partsJson 一次解析优化已合并 main（2026-08-05） | 分支 feat/reduce-partsjson-parsing → main（5ac68b8），CI run 31010674739 success。 |
| 08-05 23:15 | RikkaMinis 全量代码审查（2026-08-05 晚，clone /tmp/rikkaminis-review） | 结论 |
| 08-06 10:15 | RikkaMinis — 经验记忆详情 dialog 滚动修复（2026-08-06，commit 5d20e49） | 用户报：经验详情 dialog 里长 reply 内容"下面还有但滑不动"。真机验证后修复。 |
| 08-06 10:15 | RikkaMinis — 经验记忆详情 dialog 滚动修复详情（2026-08-06，commit 5d20e49，feat/episode-viewer） | 分支 feat/episode-viewer 第二个提交 5d20e49（前一个 b25a806），CI run 31065068027。 |
| 08-06 10:36 | RikkaMinis 经验记忆滚动修复合并 — 编译失败与修复（2026-08-06 下午） | 事件 |
| 08-06 20:35 | RikkaMinis auto-follow 又跳 — 漏网路径 LE(messages.size)（2026-08-06 复现定位） | 用户报告：auto-follow 的"跳"在高速调用大模型期间又出现（之前 884d9f1 修过）。 |
| 08-06 20:45 | ↳ RikkaMinis 今日收尾确认（2026-08-06 22:45） | 已合并 main（两个 bug 修复全部完成） |
| 08-06 21:09 | ↳ 收尾对齐 — 最终状态确认（2026-08-06 续） | main 当前状态 = 08531ea |
| 08-06 23:28 | RikkaMinis — 输入框光标跳修复 + 大模型回答跳诊断（2026-08-06 晚） | 问题一：输入框光标跳（已改，CI 31115770832 验证中） |
| 08-06 23:38 | RikkaMinis — 输入框光标跳 修复完成并合并 main（2026-08-06 晚 收口） | 状态 |
| 08-06 23:44 | RikkaMinis — 大模型回答时跳 修复完成（分支 fix/agent-anchor-retain，验证中） | 状态 |
| 08-07 00:25 | RikkaMinis 滚动/光标问题交接（2026-08-07 凌晨，交接给新会话） | 完整交接文档：/var/minis/workspace/handover-chat-jump-issues.md |
| 08-07 08:59 | RikkaMinis 收尾阶段 — 三个"不爽点"待办清单（2026-08-07） | 用户决定把三个结构性隐患全部清掉，逐个来，本次先做第 1 个。已记入待办，改完一个勾一个。 |
| 08-07 09:12 | RikkaMinis 待办 #1 滚动决策函数 — 已实现并推送（2026-08-07） | 完成状态 |
| 08-07 09:29 | 待办 #1 滚动决策函数 — 系统 self-review + 类型修复（2026-08-07） | 系统检查结论（逐门控对照原始实现，9 条路径全部语义等价） |
| 08-07 09:45 | RikkaMinis 滚动"跳"根因定位：USER_SEND 无条件滚动漏网（2026-08-07 上午） | 用户报"看历史时 AI 在底下操作，页面被拽到底部（跳）"，并给出关键特征：在底部跟流顺畅、离开底部(看历史)才跳。 |
| 08-07 10:01 | RikkaMinis 滚动"跳"根因确诊 + 修复（2026-08-07，已验证 CI #196 success） | 背景 |
| 08-07 10:06 | RikkaMinis 滚动跳修复 用户真机验证（2026-08-07） | 用户装了 #196（68250c1, fix/force-scroll-respect-viewport）后，在我继续做第 2 项（setInputText-caret-intent）工作期间观察：没有出现跳的问题了，大… |
| 08-07 13:51 | RikkaMinis 双修复方案 A+B 完成并推送 CI（2026-08-07 下午） | 用户报(13:17)又出现 1) 读历史被拽回底部(bug 复现) 2) 一次 native SIGABRT 闪退。诊断后用方案A+B 修复，推分支 fix/proot-rss-monitor（2 commits 8d3… |
| 08-07 14:21 | RikkaMinis USER_SEND 拽回 bug 第三轮修复（2026-08-07 14:20） | beta.203 上用户仍看到一次跳动。日志实锤： |
| 08-07 14:35 | RikkaMinis 滚动「触底触发器」重构方向（2026-08-07 交接） | 用户提出全新滚动模型：不再用位置 gate 判断是否跟随，而是以「用户手势滑到底部尽头」为唯一触发器。 |
| 08-07 14:35 | RikkaMinis「触底触发器」重构 — 确认点已定（2026-08-07 收尾） | 补充前一条交接记忆的最后待确认项，现已拍板： |
| 08-07 14:36 | 2026-08-07 对话框交接（scroll-proot-诊断会话） | 因对话框内容将满，滚动「触底触发器」重构交给新对话框。交接完成： |
| 08-07 15:23 | RikkaMinis 触底触发器重构 — stickToBottom 状态机落地（2026-08-07 下午） | 分支 fix/proot-rss-monitor，commit 560d484 + 修复 8128248，CI run 31156625289 success（单测全过 + APK 编译过，Publish 因非 main… |
| 08-07 17:32 | 多个对话框并行处理中（2026-08-07）— 三条活跃工作线 | 用户提示：其他对话框正在处理事情，不要冲突/干扰。当前并行在做： |
| 08-07 18:16 | RikkaMinis 稳定性回归审计 — HEAD 75cd067（2026-08-07） | 用户想确认当前版本是否有回归、过去修的问题会不会复发。做了系统性回归审计（代码 + CI + release 三重验证）。 |
| 08-08 00:07 | RikkaMinis: 滚动跳动根治 — 单锚点守护重构（2026-08-07，分支 feat/anchor-guard-single-follow） | 背景与目标 |
| 08-08 00:10 | 关键坑：Kotlin 前向引用导致 compileReleaseKotlin FAILED（CI 单测组合步骤误报） | 现象 |
| 08-08 00:10 | 收尾指令：锚点守护暂不合 main，与其他并行改动一起编译合并（2026-08-08 用户指示） | 用户明确说：「在其他的页面中还在改其他的，到时候一起编译合并。」 |
| 08-08 02:35 | RikkaMinis 流式结束滚动跳修复 — 分支 fix/drag-stop-disengage-follow (2026-08-08) | 用户报"看着A段内容，震动时刻(输出结束)跳到B段"。日志实锤 anchor-guard 反复 firstIdx=1→scrollToItem(0,0)·11次。 |
| 08-08 09:46 | RikkaMinis main 分支完整合并梳理（2026-08-08） | 远程状态 |
| 08-10 11:10 | 图标自动跟随系统主题修复 + 通知横幅精简（2026-08-10） | 分支 fix/icon-auto-follow-system → 已合并 main，CI success |
| 08-13 15:29 | 思考+工具两条杠移到回答下方（fix/reorder-think-tool-bottom → main cdc72f4） | 用户需求：AI 生成回答时，thinking 折叠条和 tool-run 折叠条原来在回答文本上方（model block 顺序 thinking→tool_use→text 导致），回答太长时自动滚动把两条杠顶出视口。… |
| 08-14 11:04 | 滚动体验三问题：A+B 施工中，D 已交接（2026-08-14 上午） | 任务来源：用户反馈聊天界面三问题：①滚动必须"很直"才能滑 ②内容上下跳 ③思考栏/工具栏形态。 |
| 08-14 11:11 | ↳ A+B 已合入 main 3c95878（2026-08-14 上午，更新） | A（表格折叠）+ B（工具行动画）分支 fix/scroll-ux-table-fold-animate 已合入 main（3c95878），分支已删（远端 204 + 本地 -D） |
| 08-15 20:30 | 修复：流式回答内容"跳动"（ToolCallRunGroup animateContentSize 冲突）（2026-08-15 晚） | 用户现象：大模型回答期间，渲染内容"跳动一下"（可复现）。 |
| 08-15 21:31 | 流式回答"跳动"修复方案（2026-08-15 晚，两路修复） | 修复 A：去掉 animateContentSize（ce5580d，已合并，release 已绿） |
| 08-15 22:49 | 滚动「跳顶」根因定位 + 修复方案（2026-08-15 深夜） | 关键发现：用户报「发消息跳到会话最早一条」+「流式跳动」，实际设备装的 beta.779 = commit dd277ef，来自实验分支 fix/simple-auto-follow（run 779），不是 main。 |
| 08-15 23:16 | 锚点守护死区修复完成（2026-08-15 深夜） | 用户确认修复有效，情况大大改善，认可"过好就行了"。 |
| 08-16 17:23 | RikkaMinis 滚动跳动最终施工方案定案（2026-08-16） | 用户确认要具体施工方案。经当前 main@2fcc96c 源码、rikkahub 6d407fb、两次独立模型反方审阅综合，最终不再给 reverseLayout 叠滚动补偿，定案为：①聊天主 LazyColumn 改正… |
| 08-16 18:54 | 滚动修复施工完成：分支 CI 绿，待真机验证（2026-08-16 晚） | 分支：fix/forward-stable-chat-scroll（main@2fcc96c 之后） |
| 08-16 19:15 | ✅ 滚动修复闭环完成：已合并 main 1ef0e49（2026-08-16 晚） | 用户真机验证「符合预期，问题解决」→ ff 合并 main（2fcc96c→1ef0e49，+1651/-461，9 文件）→ main release CI run 31943023247 success → 远端+本… |
| 08-16 19:21 | rikkaminis-dev-history.md 四次重建（补 08-16 下午/晚条目）+ 解析器 bug 修复（2026-08-16 晚） | 用户要求把今天下午/晚的新条目也补进 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md（BiliRoamingX 安全分析/编译失败、rikkahub 流式剖析、滚动跳动施工方案定案… |
| 08-26 00:08 | 会话 E 完成：滚动跟随回归简单显式（fix/scroll-follow-simplify → main 4829e67） | 任务：rikkahub 平滑吸收 stage E — 聚类后把滚动跟随从「钝器守卫」回归到 rikkahub 式 isAtBottom && isStreaming → requestScrollToItem 简单显式协… |
| 08-26 00:13 | rikkahub 流畅性吸收 — A/B/C/D/E 全部完成并合入 main（2026-08-25 búi 收口） | 5 阶段全部合入 main（当前 main = 4829e67），收口会话独立核实（拉取 origin/main + Actions API 交叉核对 head_sha，非转述）： |
| 08-26 13:36 | 历史对话回底部「随机失效」调查进行中（2026-08-26 上午，接续 8484a49） | 用户反馈：fix/history-open-at-bottom（8484a49）合并装包后仍随机失效——偶尔定位顶部、偶尔正确到底部。真机 beta.1083 = main 0ba797a（含修复），版本已核实（dump… |
| 08-26 14:39 | 历史对话回底部随机失效 — 根因修复已合 main（dbaa4aa，2026-08-26） | 根因（Compose 源码级实证）：8484a49 修复后仍随机的根因有两层： |
| 08-27 13:28 | 历史对话「打开定位到底部」第三次修复 — 诊断完成待施工（2026-08-27） | 用户报「打开旧对话要定位到底部」，之前修过两轮（8484a49「空列表吞请求」→ dbaa4aa「sentinel 可见才 consume」）仍没修好。本次诊断定位到新一层的根因。 |
| 08-27 14:15 | 历史对话「回底部」第三轮施工翻车复盘（2026-08-27 下午） | 施工会话按我的 task-H 任务书做了，提交 a256178 fix(chat): wait for first-frame layout stability before the bottom scroll，但没解决… |
| 08-27 14:23 | 历史对话「回底部」第四轮方案定案（2026-08-27 下午） | 用户确认第三轮（a256178「等首帧布局稳定」poll）没修好还引入新问题：施工后老毛病照旧，且新增「对话进行中往上滑，会突然跳到非常前面的某一段」。 |
| 08-27 15:48 | 会话任务 H（历史回底部第四轮）终止交接（2026-08-27） | 任务 H 施工终止转交接，交接文档 /var/minis/shared/rikkahub-smoothness-absorption/session-task-H-handover.md。 |
| 08-27 18:23 | 会话任务 H 第四轮方案 B 施工完成（2026-08-27，commit a67e7fe，CI run #1127 success） | 背景：历史对话「打开定位到底部」第四轮（55b85b1，sessionLoaded 门控）用户真机反馈「先到底部又被拽走」。本会话核实出确定性缺陷：sessionLoaded 在 loadSession finally … |
| 09-01 15:12 | PlaceStorm 根因定位与修复（2026-09-01，用户日志实测驱动） | 用户问题与关键事实 |
| 09-01 15:53 | place-storm 修复收口：小号 main 已合并（2026-09-01） | 分支 fix/place-storm-follow-clamp-loop 分支 CI run 33480996005 success（head_sha=70f927d 核实） |
| 09-01 16:54 | place-storm 钳位修复汇入主号收口（2026-09-01） | 用户拍板：小号 70f927d1 的 SIMPLE_FOLLOW 钳位守卫修复已在 lab 真机验证（日志 minis-2026-09-01__3_.log 全绿），汇入主号。 |
| 09-01 19:07 | place-storm 残留源修复 + launch-resume 导航修复（2026-09-01，commit 65b8a74 合并 main） | 用户试运行日志验证结论（minis-2026-09-01__4_.log，主号 91498d74 构建） |
| 09-01 20:27 | 自动跟随失效修复（2026-09-01，commit 65418137 合并 main） | 问题：底部自动跟随在流式期间整个回合失效。用户提供 minis-2026-09-01__5_.log（65b8a749 构建）实测。 |
| 09-13 12:39 | 打开历史会话「差一段」第 5 轮取证（真机数据实锤） | 抓取方法：后台白名单转储 /data/local/tmp/rc2.log（logcat -b all -v time -s Minis.ScrollSrc:V Minis.Perf:V Minis.JankDiag:V … |
| 09-13 12:47 | 打开会话「差一段」修复分支已推送（2026-09-13） | 分支 fix/open-catchup-guard @ 32571f8（基于 main @ 15b3f447）：3 文件 +210。 |
| 09-13 13:31 | 【交接】打开会话「差一段」v1 已装机验证 → 需 v2（2026-09-13 13:35） | 完整交接文档：/var/minis/shared/open-catchup-handoff-2026-09-13.md（14.6KB，含全部背景/证据/代码位置/流程/环境）——新会话先读它。 |
| 09-13 14:04 | 打开会话「差一段」v2：根因锁定 + 修好待真机验证（2026-09-13 14:00） | 分支 fix/open-catchup-guard @ daab66b（v1 32571f8 + v2 一 commit；基于 main 15b3f447） |
| 09-13 14:07 | 打开会话「差一段」v2 已合并 main（2026-09-13 14:05） | 用户真机验收："解决的很完美" → 拍板合并。 |
| 09-19 11:57 | 09-19：offload 审计**路线改判**——下一棒 = 继续推进扫描（不是修代码）+ 锚点机制升级（ANCHOR.md 单一来源） | 用户指正：「那个任务（= 我写的第 7 棒修补批次任务书）已经在另外一个会话做了，并且已经几乎处理完了；扫描还需要继续推进，需要的是继续推进扫描的」。 |
| 09-19 12:55 | 状态：✅ 第 7 棒完成 · 锚点 c6d8d63f（= origin/main）· 只读，仓库 0 改动 · QA 2 | 状态：✅ 第 7 棒完成 · 锚点 c6d8d63f（= origin/main）· 只读，仓库 0 改动 · QA 23/23 全绿 |
| 09-19 19:04 | 09-19 晚：压缩修复第二棒 —— 长工具循环的预算锚点（写侧+读侧）→ main = afa404bf | 背景：第一棒（796307ec，F1/F2/F4）修好了锚点判定 + 诊断 + 硬裁剪兜底，但「一句指令 + 几百轮工具调用」会话只有一个用户轮次，而整套压缩逻辑按「用户轮次」计价 ⇒ 两侧同时退化。 |

## 2. 输入框 / 键盘 / 粘贴

**跨度** 2026-08-04 ～ 2026-09-20 · **27 条** · **状态** 已闭环（09-12 三修复打包 fix/ttfb-thinktag-composer 合并 main @ e1a0b08）

**叙事**：光标跳（08-06）→ 草稿持久化 + IME 高度（08-05）→ hasText 统一 + composition 门控（08-08）→ 单行粘贴换行折叠（09-11）→ 「吞内容」根因 imeBurstBuffer 残留 stale 快照（09-12）。共同根因族：**Compose 输入状态与 IME 事件的时序竞争**，每次都表现为「偶发、难复现」。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-04 05:23 | OpenMinis fork — UX polish 批量改动（2026-08-04，分支 feat/ux-polish） | 分支 feat/ux-polish（基于 feat/chat-history-drawer，2 个提交 1c28bf4 + 8c5bd58 文档），CI run 30853868293 success。 |
| 08-05 00:56 | RikkaMinis — 草稿持久化 + 抽屉键盘 + rootfs 统计修复（2026-08-05，分支 feat/draft-persistence-ime-storage，commit aba858b） | 用户三个问题的根因与修复，全部已实现并推送，CI run 30931495639 验证中（分支验证，未合并 main）： |
| 08-05 01:23 | RikkaMinis — 草稿/键盘/存储三项修复完成（2026-08-05，分支 feat/draft-persistence-ime-storage） | 分支 4 个提交，最终 commit ffd12bc，CI run 30932967686 全绿（含 "Run unit tests (full suite)" + APK 构建签名）。未合并 main，等用户确认。 |
| 08-06 23:28 | RikkaMinis — 输入框光标跳修复 + 大模型回答跳诊断（2026-08-06 晚） | 问题一：输入框光标跳（已改，CI 31115770832 验证中） |
| 08-06 23:38 | RikkaMinis — 输入框光标跳 修复完成并合并 main（2026-08-06 晚 收口） | 状态 |
| 08-07 00:25 | RikkaMinis 滚动/光标问题交接（2026-08-07 凌晨，交接给新会话） | 完整交接文档：/var/minis/workspace/handover-chat-jump-issues.md |
| 08-08 09:49 | 修复：草稿中点击「新建对话」无响应 | 问题：用户在草稿（__new__<uuid>）中输入内容后，点击顶栏「新建对话」按钮，视觉上无任何反应。 |
| 08-08 10:06 | 并发会话冲突：promote-draft 分支被 stash | 背景：本会话实现"草稿中新建对话自动提升为正式会话"（方案 2），在工作区开了分支 feat/chat-promote-draft-on-new-chat（tip=0d968d4）。另一会话在同一工作区并行操作（分支 f… |
| 08-08 10:15 | 输入模块优化：hasText 统一 + T217-2 composition 门控（2026-08-08） | 分支 fix/input-composer-hastext-composition（基于 main 0d968d4，commit 38d84fd），CI run 31234234221 success。 |
| 08-08 16:50 | fix/append-to-input-caret — 填入对话框光标随机位置修复（2026-08-08） | Bug：长按 AI 回复选中文本 → "填入对话框" → 光标有时在最前、有时在最后、有时在中间。 |
| 08-19 00:00 | 输入框闪退 + 流式排版重复 排查（2026-08-18 深夜，未定位） | 用户报两个问题： |
| 08-23 14:29 | 会话 A（bug-hunt-pressure / session-task-A）中途结束：用户判定意义不大 | 用户明确取消会话 A 的「agent 多轮流式 + worker 生命周期压测」任务，理由：这部分日常使用几乎都会遇到，有问题他能立刻感知，压力测试意义不大。 |
| 08-27 21:37 | 语音输入顺滑度修复完成（分支待合并，2026-08-27） | 用户主诉：对话框里用「输入法自带的语音输入」感觉比其他应用不顺畅（注意：不是 app 自带语音，已澄清）。 |
| 08-31 00:21 | 输入框宽度对齐 RikkaHub（2026-08-31） | 用户反馈 RikkaMinis 聊天输入框比 RikkaHub 的窄，要求调成 RikkaHub 的宽度。 |
| 09-10 12:03 | 挂载编辑页名称提示修复 + 输入框高度对齐 rikkahub（2026-09-10 下午） | 用户拍板：本会话收尾，验证工序交下个会话。交接文档 /var/minis/shared/composer-mount-ui-fix-handoff.md。 |
| 09-10 14:12 | 挂载页删改名 + composer 回退（2026-09-10，main @ 09f040e） | 两件事，分支 fix/mount-drop-rename（2 commit）： |
| 09-11 21:26 | fix/singleline-paste-newline 分支审计：无 bug（2026-09-11） | 正在跑的分支 = fix/singleline-paste-newline（单 commit f4b6c4a 基于 main @ 3a988d5，run 34603318501，19 文件 +188/−28）。用户要求检… |
| 09-11 21:28 | fix/singleline-paste-newline — 单行输入框粘贴换行折叠（2026-09-11 晚，CI 绿，待拍板合并） | 问题（用户报告）：向输入框粘贴多行文本（如 newapi 的 headers、多行 key），只显示第一行，其余被裁；删掉可见字符后字段看起来空了、实际还残留不可见 \n。根因：Compose singleLine=tr… |
| 09-11 21:30 | ↳ singleline-paste-newline 合并收尾（main @ f4b6c4a） | 合并：分支 CI #1464 success（head f4b6c4a 三方一致）→ 用户拍板 → refspec 直推 ff（3a988d5..f4b6c4a，无 force）→ ls-remote 复核 main=f… |
| 09-11 21:32 | ↳ singleline-paste-newline 真机验证通过（2026-09-11 晚） | 用户实测分支构建（= main @ f4b6c4a 同一 commit，等价）：四项验收点全过，无问题——①多行粘贴到 API Key/UA/URL 字段整段单行可见 ②清空后字段确真空 ③多 key 轮换正常 ④MCP… |
| 09-12 00:25 | 聊天输入框"吞内容"根因：imeBurstBuffer 残留 stale 快照（2026-09-11 晚） | 用户报告：发 URL+key 时前面的描述被吞（间歇，"有时候"） |
| 09-12 00:39 | 三修复打包分支 fix/ttfb-thinktag-composer 推送+CI（2026-09-11 深夜） | 用户拍板：TTFB 直接调默认值（30s→90s）；think 标签+输入框"照常修，直接打包一起"（一分支三 commit） |
| 09-12 01:26 | fix/ttfb-thinktag-composer 合并收尾完成（main @ e1a0b08） | 合并：refspec 直推 ff（f4b6c4a..e1a0b08，无 force）→ ls-remote 复核 main = e1a0b08692ac4cf79eabff2b6c17abc56282fd2f |
| 09-12 21:19 | 参数化第一批施工中：feat/chat-tuning-panel（2026-09-12 晚） | 用户拍板"全做"（A+B+C+D+E 全部候选参数化），分两批实施。第一批 A+D 已完成后进入 CI： |
| 09-20 17:14 | 09-20：键盘遮挡编辑内容（IME occlusion）—— 诊断完成，交接给新会话 | 用户报告：技能编辑 / 记忆编辑中，点击后输入法键盘直接盖住正在编辑的内容；并要求排查所有编辑面。 |
| 09-20 17:35 | 09-20 晚：IME 键盘遮挡修复完成 → 分支 `fix/ime-occlusion-hosts` @ `4d4333d3`（CI 绿，**按用户要求不合并**） | 用户指令：跑完不合并，等统一处理。远端现有三个未合并分支（同基 3ec26816）： |
| 09-20 20:02 | 09-20 夜：思考期间输入框卡顿 —— 根因 = ThinkingDelta 分支缺引擎级节流（分支 `fix/thinking-delta-main-thread-throttle` @ 37e18fd2，**按用户要求不合并**） | 用户报告：「大模型思考时性能消耗很大，这时用输入框明显卡顿；尤其在有比较大上下文的长对话中」。 |

## 3. native 内存 / OOM / 崩溃

**跨度** 2026-08-03 ～ 2026-09-22 · **115 条** · **状态** 持续跟踪（09-13 三件套已合并；SOFT 450 / HARD 1200 阈值待 1–2 周真实数据复核）

**叙事**：08-09 Scudo OOM → 08-15 早三次 OOM → 08-17 native offload 泄漏实证 → 08-20~22 进程隔离五 Phase + 小号 TF-A..TF-J 十连修 → 08-25/26 「RSS 单调泄漏」被沙箱实测**推翻**（是 PRoot VSZ reserve 不是泄漏）→ 09-13 两次 SIGABRT / RSS 5.4–6.1GB，口径从 VmRSS 换 **RssAnon**（61% 信号是可回收页）+ 崩溃态取证 → 09-15 HangDetector 后台冻结假阳性。**这是全档案最长的 saga，也是唯一「调查被自己的实测推翻」的一条。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 21:00 | OpenMinis Android — 空对话残留 bug 根因与修复（commit b194927，CI 30815328668 success） | 用户报「历史里出现空对话，自动删除时好时坏」。定位到根因链： |
| 08-07 10:53 | 2026-08-07 多对话框并发操作同一 worktree 的教训（native OOM 修复） | 用户做闪退修复时，发现另一个对话框在并发操作同一个 git worktree（/tmp/rikkaminis-full, branch merge/scroll+caret），导致： |
| 08-07 11:06 | RikkaMinis 三件待办交接（2026-08-07 中） | 状态 |
| 08-07 11:10 | RikkaMinis native OOM 修复已固化为独立提交（2026-08-07 中午） | 另一对话框在 /tmp/rikkaminis-full (branch merge/scroll+caret) 编译打包了权限修复（1ef0239，单测已过、正在编译），该版本不含 native 泄漏修复。 |
| 08-07 11:14 | RikkaMinis 合并收尾完成 — main= e941ffb（2026-08-07 中午） | 用户决定"所有改动全部合并，告一段落"。 |
| 08-07 13:12 | RikkaMinis「重启后进入历史会话列表页而非新会话」根因（2026-08-07） | 用户预期：设置里选了"启动 New Chat"，打开 app 应直接进聊天对话框。现象：重启后停在历史会话列表页（HOME）。 |
| 08-07 13:51 | RikkaMinis 双修复方案 A+B 完成并推送 CI（2026-08-07 下午） | 用户报(13:17)又出现 1) 读历史被拽回底部(bug 复现) 2) 一次 native SIGABRT 闪退。诊断后用方案A+B 修复，推分支 fix/proot-rss-monitor（2 commits 8d3… |
| 08-07 15:51 | RikkaMinis PRoot 泄漏修复 — 方案A(nativeRss) 从 fix/proot-rss-monitor 拆出推 CI（2026-08-07 下午） | 背景 |
| 08-07 16:01 | RikkaMinis 方案A(nativeRss) — CI 全绿，待用户确认合并（2026-08-07 下午 收尾） | run 31159304878 conclusion: success（job "build" 全过：NDK proot 编译 + APK assemble + 签名校验 → 源码级正确，nativeRssMB/proc… |
| 08-07 17:32 | 多个对话框并行处理中（2026-08-07）— 三条活跃工作线 | 用户提示：其他对话框正在处理事情，不要冲突/干扰。当前并行在做： |
| 08-09 06:40 | OOM 闪退 — 终端反复开关 12 次导致内存耗尽（2026-08-09） | CI #305 正在构建中（e628206，修了清屏 Ctrl+L + 换 bash）。用户测试 #304 时 7 分钟内开关终端 12 次，PRoot 子进程反复创建/销毁，Scudo 内存分配器耗尽（internal… |
| 08-09 06:49 | 终端修复交接（2026-08-09） | 分支状态 |
| 08-09 07:27 | 终端双问题根因 + 修复（commit b8dd5cb，CI run 31283965704） | 用户报两个新症状（#305 APK = e628206）： |
| 08-09 08:31 | Scudo OOM 修复 — P2-app-native-oom（2026-08-09，已合并 main 56dc1c6） | 崩溃复现：08:08:52 / 08:10:21 / 08:12 三次 Scudo OOM SIGABRT（29966→29518→29915），做密集型工具调用（20+ git/shell 命令 2.5 分钟）+ B … |
| 08-09 13:09 | 供应商详情页闪退根因 + 修复（2026-08-09 13:02 崩溃） | 用户报 13:02 闪退（crash-2026-08-09_13-02-25.log + _13-02-31.log，连环崩：10286 → 17619 → 17979 重启）。ACRA 捕获 IllegalStateE… |
| 08-09 17:47 | 2026-08-09 收尾总结 | 重大改动：终端引擎替换（feat/termux-terminal-engine）— 自研 2249 行 → Termux 0.118.0，6 轮修复后合并 main。 |
| 08-09 18:36 | 备份并发 OOM 修复（fix/backup-concurrency-oom，已合并 main） | 用户问题：先点云端备份（进行中）再点本地备份 → 本地 OOM：Failed to allocate 150994952 bytes, 512MB heap。备份体积 ~70MB。 |
| 08-09 22:21 | ↳ RikkaMinis 开发项目收尾归档（2026-08-09 深夜） | 三件事全部完成，画句号： |
| 08-13 04:49 | 任务 5：BrowserTabPool 低内存释放 ✅ 完成并合并到 main (c371506) | 改动内容 |
| 08-13 11:47 | 本机日志崩溃分析（2026-08-13）— native 内存泄漏 SIGABRT | 用户上传本机日志（11065行）+ native-crash 文件。分析结论： |
| 08-15 08:30 | 08-15 早上 com.openminis.app OOM 崩溃分析（3 次，08:25-08:27） | 现象 |
| 08-15 08:32 | 从 08-15 OOM 崩溃暴露的架构问题与改进待办 | 架构问题 |
| 08-15 08:51 | Memory-pressure-gate 施工中（fix/memory-pressure-gate，2026-08-15 上午） | 来源：08:25-08:27 三次 OOM（pthread_create 1MB 栈失败 = native 内存耗尽）。现有 ExecutionCoordinator P2-app-native-oom 只监控 Debu… |
| 08-15 09:03 | Memory-pressure-gate 完成 — 已合并 main（1c4bd45，2026-08-15 上午） | 改动（commit 1c4bd45，5 文件 +321，全在 service/ + MinisApp 下）： |
| 08-15 09:39 | T9 性能基线完成 — 已合并 main（05a9d111，2026-08-15） | 任务：RikkaMinis 平衡点施工 T9 — 性能观测、基线与门禁（Phase 1-2：指标插桩 + 合成 workload + 报告工具）。 |
| 08-15 09:58 | ↳ T6 Trace 扩展完成 — 已合并 main（8ad067a，2026-08-15） | 任务：RikkaMinis 平衡点施工 T6 — Trace 扩展为预算和终态证据。 |
| 08-16 09:38 | 审计修复合并 main 完成（2fcc96c） | fix/audit-p0-security-boundaries（14 文件 +328/-64）已 ff 合并 main（681bb18→2fcc96c），分支 CI run 31919378138 绿；main rel… |
| 08-17 00:11 | ✅ 两件内存任务全部闭环（2026-08-17，环境恢复后） | 前一个会话 native 堆泄漏到 5.5GB 被锁死，但用户重启/新版本后环境恢复，本会话可以直接执行——确认修复生效（shell 正常、git/python3/curl/token 齐全、可用内存 5.6GB）。 |
| 08-17 22:17 | 🔴 本次会话疑似是 native 堆失控的元凶（2026-08-17 深夜） | 现象：用户在 21:57 和稍后又遭遇两次应用闪退，native crash 日志： |
| 08-17 22:21 | native offload 泄漏实证（新会话诊断，2026-08-17 深夜） | 崩溃现场：PID 4505 从 22:08 启动 → 22:15:51 SIGABRT，仅 7 分钟 VmRSS 6.2GB / VmPeak 17GB / 57 线程。短期高频调用引爆，非长期累积。 |
| 08-18 00:10 | ✅ shizuku binder 泄漏修复闭环（2026-08-18） | 问题：app 反复 SIGABRT，崩溃现场 VmRSS 6-8GB / VmPeak 17GB。用户在同一对话打开终端跑程序，内存飙到 4-5GB。 |
| 08-18 00:24 | 可复用教训：native 进程 / offload 的一类泄漏 bug 模式（2026-08-18 shizuku 修复沉淀） | 昨天（2026-08-18）修完 shizuku binder 泄漏后，把两个 bug 模式提炼成可复用的排查/修复纪律，供后续会话参考（详细修复记录在同日 daily log"shizuku binder 泄漏修复闭环… |
| 08-18 09:25 | B1 任务包：调高 MemoryPressureGate 阈值（2026-08-18 09:30） | 背景 |
| 08-19 00:00 | 输入框闪退 + 流式排版重复 排查（2026-08-18 深夜，未定位） | 用户报两个问题： |
| 08-20 00:02 | native OOM 施工交接（2026-08-20，换新会话继续） | 用户要求开新对话继续施工，别再拉长本对话。交接已固化，新会话直接读文件即可接手。 |
| 08-20 00:34 | native-OOM 施工 Phase 0 闭环 + Phase 1 开工（2026-08-20） | 分支 fix/native-rss-tool-guard，工作树 /tmp/rikka-diag，GIT_ASKPASS=/var/minis/workspace/.git_askpass.sh。 |
| 08-20 08:08 | native-OOM Phase 1 侦查推进（2026-08-20 会话二） | 接手 fix/native-rss-tool-guard 分支施工。工作树 /tmp/rikka-diag 干净，HEAD=b31bb65，领先 origin/main（ce760f9）5 commits。分支 CI 全… |
| 08-20 09:29 | 打点定位落地：native-offload RSS 归因（2026-08-20 会话三） | 用户质疑"彻底解决 60% 多概率是否值得"，我在成本收益分析后建议"先打点定位再决定投入"，用户拍板执行。 |
| 08-20 13:02 | P2/P3/P4 低风险护栏合并 main 闭环（2026-08-20） | 分支 fix/p2p3p4-guardrails（58d578a）CI 绿（run 32332509717）→ ff main（1954bac → 58d578a），release CI run 32333314772 … |
| 08-20 13:55 | Phase 1 骨架捞回 + 任务清单定稿（2026-08-20 会话收口） | 挖回丢失的 Phase 1 基石：15ba1ae(OffloadHandlerCatalog) + b31bb65(ToolExecutionService) 原本悬在已删的 fix/native-rss-tool-gu… |
| 08-20 14:32 | P-1 压测闭环：三大负载 RSS 全部受控，未复现 6GB 泄漏（2026-08-20） | 用户选「方案二」（沙箱并发派发负载），我用 shizuku 从沙箱读手机 logcat 完成定向压测。关键结论： |
| 08-20 16:40 | bug-hunt 2026-08-19 最终收口（2026-08-20 16:40） | 6GB native OOM 事故完成「止血 → 定位 → 并发上限放宽验证」三阶段闭环，全部收口。 |
| 08-20 17:11 | ↳ 并发会话上限放开（2026-08-20 收尾） | 用户要求把并发会话上限「彻底放开」，不要最高只能是 4。理由是应用已足够稳定。 |
| 08-21 12:07 | 2026-08-21 崩溃修复 + 偶发丢消息搁置 | 已修复并合并（main a1bc4bb） |
| 08-21 16:03 | native OOM「进程隔离 + 自动划卡片」修复闭环（2026-08-21） | 背景：用户报内存仍会飙到"拒绝运行指令/工具"，但能靠后台划卡片恢复。用户决定改，参考了工业界三方案（多进程用完即弃 / AVF 微虚拟机 / 端云分离），确认只有「多进程用完即弃」可行（AVF 需 Pixel+厂商签名… |
| 08-21 19:44 | 原生内存隔离施工开始（Phase 0：bounded admission） | 用户要求彻底解决内存飙升，给出 v2 方案（/var/minis/workspace/rikkaminis-native-memory-isolation-plan-v2.md），用户确认施工。 |
| 08-21 20:46 | 内存隔离 Phase 0+1 闭环 + 并行派发（2026-08-21 晚） | Phase 0 fix/offload-bounded-admission → main fa28549：固定 ThreadPoolExecutor 替换 per-connection thread+Semaphore（… |
| 08-21 23:01 | 内存隔离 v2 五 Phase 代码审计发现（2026-08-21 独立会话审计 main 332f30e） | 用户要我审计已合并 main 的原生内存隔离 v2 施工（Phase 0-5 全在，release CI run 32492787202 success）。方案文档 workspace 被重置丢了，靠 shared/na… |
| 08-22 01:47 | 2026-08-22 对话崩溃排查 + main 回滚 #985 + release 说明（会话收尾） | 用户决策：最新版（Phase 0-4 内存隔离 + :modelservice/:toolservice/:browserservice 三进程 + bridge）反复出问题，用户拍板回滚 main 到 #985（75a… |
| 08-22 12:54 | RikkaMinis 官方对比自述文档完成（2026-08-22） | 生成了 /var/minis/workspace/RikkaMinis-官方对比自述.md，把 fork 相对官方 OpenMinis 的全部差异按三类归档： |
| 08-22 16:48 | [dual-appid] lab 包闪退根因修复（native-offload abstract socket 冲突） | 继续 dual-appid 前置工作。小号 lab APK（applicationId=com.openminis.app.lab，已生效）安装后闪退，logcat -b crash 抓到 P0根因： |
| 08-22 17:03 | [dual-appid] 闭环维护重要更新（lab 包安装正常） | 用户确认已装 lab APK（com.openminis.app.lab），安装后正常运行，无闪退。 dual-appid 前置工作核心验证通过：大号 com.openminis.app + 小号 com.openmin… |
| 08-22 17:19 | [小号内存治理] 交接：用户决定在小号补 D-4b 验证（聊天 provider-rss 泄漏判定） | 用户当前明确决策链（2026-08-22 晚，本会话对齐）： |
| 08-22 18:20 | 小号内存治理方案提案（2026-08-22） | 审计 ALT_USER/RikkaMinis chore/dual-appid tip 5672ca3 后，建议不恢复完整内存隔离 v2，也不直接迁 browser/toolservice；先做 D-4b 取证，再施工 … |
| 08-22 18:38 | 小号内存治理：任务已派发（2026-08-22 晚） | 方案文档已从 workspace 备份到笔记文件夹（/var/minis/mounts/笔记/RikkaMinis开发档案/小号内存飙升根治方案-Provider-Worker-Hard-Boundary.md，sha2… |
| 08-22 19:26 | TF-A provider-rss v2 观测打点完成（会话 A，2026-08-22 收尾） | 分支 diag/provider-rss-v2（基于 chore/dual-appid 5672ca3，tip c8ff0a4），分支 CI run 32569593961 全绿（单测 full suite + scan… |
| 08-22 21:31 | 小号内存治理：五分支全部完成，已收口（2026-08-22 晚） | TF-A/B/C/D/E 五分支真实完成并独立核实（非仅转述）：A diag/provider-rss-v2 c8ff0a4 (run 32569593961) / B fix/modelservice-terminal… |
| 08-22 21:54 | ↳ 真机日志推翻 worker 协议闭环（2026-08-22 晚） | TF-E APK 真机日志发现 P0：:modelservice PID 30053 因 ModelExecutionService.finishRequest() 写 state.json 时 run 目录已被主进程删… |
| 08-22 22:58 | ↳ TF-F modelservice run-dir 所有权 P0 修复完成（会话 F，2026-08-22 晚） | P0 根因：TF-E APK 真机 crash（:modelservice PID 30053 state.json ENOENT）——ChatStreamOffloadHandler.finally 在 result.… |
| 08-22 23:56 | [会话 G 交接] TF-F 后 modelservice worker 仍被 SIGKILL——根因与施工方案（2026-08-22 深夜） | 用户最新证据：附件 minis-2026-08-22__3_.log（23:00-23:01，主进程 PID 14059）。lab 包 1.0.0-beta.28（TF-F 4967a9a）聊天仍失败：23:00:18/… |
| 08-23 11:05 | TF-I modelservice 串行/探针修复完成（处理 TF-H 三个 P0） | 分支 fix/modelservice-before-dispatch-notify（基于 TF-H e9bec97），单 commit f8b0a6a。分支 CI run 32613813748 全绿（head_sha… |
| 08-25 22:36 | 刷新应用后的内存快照（2026-08-25 22:35） | 用户刷新应用（关后台、清进程）后恢复正常，但抓到了刷新后未完全稳定时的快照： |
| 08-25 22:40 | 终端命令触发主进程 RSS 单调泄漏（2026-08-25 22:40 现场抓到） | 用户复现：某对话框「继续运行终端」→ 主进程内存飙升 → 再发消息 → 闪退（日志开着）。 |
| 08-25 22:50 | 终端 RSS 泄漏 — 源码定位审计结论（2026-08-25 深夜） | 审计范围：/tmp/RikkaMinis（main tip 2f3498f）的 NativeOffload.kt / ExecutionCoordinator.kt / PersistentShell.kt / Offl… |
| 08-25 23:36 | A 方案（offload-rss-governance）真机验证失败 — 需重新理解「飙升」现象（2026-08-25） | 已合并 main 4af3597 的 A 方案（OffloadRssProbe 加 governanceHook，累计>256MB 或单次>64MB 触发 recycleIdleShells+evictIdleTabs）… |
| 08-25 23:40 | 「立刻飙升」真根因定位：PRoot 虚拟地址空间 reserve，非真实泄漏（2026-08-25） | 用户澄清：某个对话框一调用终端就「立刻」飙升（复现用的是 sed 查看源码命令，本质是 shell_execute）。A 方案（慢泄漏治理）当然无效——形态根本不同。 |
| 08-26 00:55 | 主号回滚 + 小号同步执行记录（2026-08-25 深夜） | 背景：我（本会话）之前做的「限 PRoot 地址空间 RLIMIT_AS=4GB」修复（commit de13ed18）翻车——4GB 压太狠导致 PRoot tracer 起不来，所有终端/shell 瘫痪（用户 B … |
| 08-26 01:55 | PRoot VSZ 虚高根因 — 沙箱实测推翻旧假设（2026-08-26） | 任务：根治 libproot.so tracer 的 ~10GB VSZ 导致的 MIUI 误杀。 |
| 08-26 11:17 | 方向 A 实测：主进程无单调泄漏，是锯齿模式（2026-08-26 11:00-11:20 真机） | 实验设计：利用本会话自身每条 shell_execute 都走 offload→PRoot 拉起回收链路的特性，跑 24 条命令分两轮，每轮活动期/沉淀期各测一次 dumpsys meminfo com.openmini… |
| 08-26 11:19 | 收口：内存三线调查全部关闭（2026-08-26 11:30） | 任务：proot-vsz-rootcause-task.md → 已完成并收口，任务文件内已写结论。 |
| 08-27 23:04 | AddProvider 导入闪退修复已合 main（2026-08-27 晚，main=9105ff1） | 用户现象：电商平台买密钥，导入第二个 provider 时把两枚密钥一起填进了密钥框 → 保存 → 闪退。崩溃日志：NullPointerException: Can't toast on a thread that h… |
| 09-01 02:26 | 开发线转移 + 成熟度门槛（2026-09-01 凌晨，用户拍板） | 仓库架构决策 |
| 09-02 08:53 | thinking-OFF turn 崩溃修复（2026-09-02，commit de2dca7d 合并 main） | 症状：关闭思考模式后无法使用——每次请求 3 次 transient retry + 模型 failover 链全灭，报 unknown t0 value: （t0=R8 混淆的 ThinkingLevel 枚举类名，值… |
| 09-03 21:18 | 5af0306 真机验证测试完成（2026-09-03） | 真机 beta.1273（versionCode 220001273，20:53 安装）= main HEAD 5af0306（占位气泡修复版），用户从分支 CI artifact zip 直接安装确认 |
| 09-07 18:59 | 「内存又涨上去了」诊断（2026-09-07 晚，日志铁证） | 结论：涨的是主进程 native heap（Skia 文本排版层），由本会话自己的巨型流式消息 + 每秒一次的重组触发；守卫和 LMK 都按设计工作；与浏览器三件修改无关。 |
| 09-09 17:44 | T10 ui-other 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T10.md。扫描 45 文件/14666 行：HIGH 1（PdfPreview 50 页全量 bi… |
| 09-09 18:37 | T4 data+backup 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T4.md。扫描 75 文件/17039 行：HIGH 1（ProviderDatabase MIGR… |
| 09-10 02:49 | 第二轮审计 LOW 全收口（B22，main @ 54eade2，2026-09-10） | 结果：11 项剩余 LOW 一次做完 → 分支 fix/audit-0909-b22 CI run 34389944956 success（head 54eade2）→ ff 合并 main @ 54eade2 → re… |
| 09-11 11:35 | 思考泄漏（thinking leaked into body）成因调查（2026-09-11） | 用户观察：模型组里混杂各供应商模型，偶发思考内容漏进正文；自测后判断"主要是上游供应商没完善，只对特定供应商的模型出现"。 |
| 09-11 11:38 | 思考泄漏取证：中转站自报 reasoning_tokens 但零 reasoning 字段（2026-09-11，用户一线样本） | 用户指出"当前这个会话本身就是典型案例"——他通过第三方中转站调用当前模型，且该问题只在这个模型上出现。 |
| 09-12 00:25 | think 泄漏根因实锤：中转站 `<think>` 标签不在解析表（2026-09-11 晚） | 用户报告"思考泄漏是应用本身的问题"（rikkahub 同站正常）→ 直接 curl 中转站实锤 |
| 09-12 11:43 | P0/P1/P2 三件全闭环（main @ fe39a66，2026-09-12） | 分支 fix/p012-strict-json-reasoning-flag（2 commit：60aeea8 测试 + fe39a66 诊断）→ 沙箱 JVM 11/11 绿 → 分支 CI run #1471 suc… |
| 09-13 15:02 | 性能实测：沙箱重活把 app 进程 RSS 顶到 1GB，触发自有内存门反过来掐工具通道（2026-09-13） | 用户提问：「应用效率和性能是否还能提升」→ 转成设备实测（pid 442 = com.rikkaminis.app）。 |
| 09-13 15:36 | ★ 2026-09-13 实测：app 进程两次 SIGABRT（native OOM），RSS 5.4-6.1GB | 证据来源：/var/minis/logs/native-crash-.log（沙箱内可读，无需 shizuku） |
| 09-13 15:50 | ★ 内存飙升的会话级归属：shell 是 per-session 的（2026-09-13 用户对照实验 + 代码核实） | 用户对照实验结论：RSS 飙升只出现在他置顶的那两个会话，本会话（新会话）调用终端完全正常。 |
| 09-13 16:53 | ★ 内存实验结论 + 发现应用内建 per-command RSS 探针（2026-09-13 16:50-16:53 实测） | 实验：在"曾报内存告警"的会话里连调 36 次终端 |
| 09-13 17:02 | ★★ 交接：native OOM 频发 + 冷打开空白（本会话产出，未动手修） | 交接文档：/var/minis/shared/native-oom-handoff-2026-09-13.md（10.8KB，含全部硬证据 / 代码位置 / 缺口分析 / 任务书 T1-T5 / 环境纪律） |
| 09-13 17:09 | ★ 交接：内存探针（memspike）非 shell 路径扩展 —— 分支待装包复现（2026-09-13 17:10） | 一句话状态：诊断分支 feat/diag-memspike-dualapp @ 73d741c（3 commits，已推，工作树干净）已做完并把探针接到非 shell 路径；CI run 34748974970 已派发（… |
| 09-13 17:26 | 2026-09-13 17:2x 全场状态复核（用户要求"梳理今天 + 还有什么没搞"） | 设备当前装包 = memspike 诊断包（feat/diag-memspike-dualapp @ 73d741c，run 34748974970 success @ 09:20Z）：证据 = 17:24 起 mems… |
| 09-13 17:49 | ★ 合并收尾：main = f113039（2026-09-13 17:5x，用户"把该合并的合并一下"） | 用户先纠偏：确认设备上装的诊断包 73d741c 基于 d6cbe49e（回滚态）——git ls-tree 实查该树不含 ChatColdOpenPrewarm.kt / ContextGrowthTracker.kt… |
| 09-13 18:23 | ★ 2026-09-13 收尾终态（main = 6c20459）+ 存储清理 + 探针失效 | 五次合并全部落在 main（按时序）：e057d151（shellTimeout 接线 + 滑杆密度，凌晨，真机已验）→ 15b3f447（dev-history 926 条）→ 3ca019e（恢复被回滚的三笔：ope… |
| 09-13 19:09 | ★ 2026-09-13 收尾三：内存探针并入 main（main = 82c7925），远端只剩 main | 用户拍板："诊断的没合并进去吗？日常应用中合并进去的话有就能直接抓到" → 决定把探针常驻进日常包（触发有偶然性，靠偶遇复现不现实）。 |
| 09-13 19:34 | 今日修改全量审计：干净，无需改动（main @ 82c7925） | 用户要求"扫一下今天的修改有没有引入 bug"。审计范围 = 今日合入 main 的全部代码提交：3ca019e（恢复 open-catchup v1/v2 + adaptive compact）、f113039（UTF… |
| 09-13 19:59 | 内存防线缺口审计（main @ 82c7925，报告 shared/memory-defense-gap-audit-2026-09-13.md） | 最重要更新：交接文档"无执行中看门狗"已过时——daab66b（随 09-13 restore 回归 main）已带 in-flight 监控（PersistentShell 1s 轮询 → midCommandRecy… |
| 09-13 20:46 | 内存加固三件套实现完成（分支 fix/memory-hardening-rss-stall，3 commits，CI #1498 success） | commits：a641e27 KaTeX 位图上限（H1）→ 45ce0c8 app 自身压力治理器（H2）→ c508896 挂死处置（用户追加要求）。沙箱 JVM 56/56 绿、仓库门禁 6/6、CI #1498… |
| 09-13 22:09 | 最近 5 commit 独立审计：干净，零改动（main @ c5088961） | 背景：用户要求扫最近 5 次修改。main 已推进到 c5088961——内存加固三件套（a641e27/45ce0c8/c508896）已由用户拍板合并（此前会话状态是"未合并待拍板"）。三件套是"实现+自测"出身、没… |
| 09-13 22:10 | ★ 2026-09-13 H3 完成：压力门口径换 RssAnon（①）+ 崩溃态取证（②），两分支 CI 全绿待合并 | 用户拍板："按你说的来"（先①口径，再②取证）；并自己提出"②是不是另开分支更好" → 采纳，且② 基于 ① 的 tip（而非 main），这样 ① ff 合并后 ② 仍是直系后代，照样 ff，且避开 MemorySpi… |
| 09-13 22:13 | ★ 2026-09-13 H3 收尾：两分支已 ff 合并 main = `d3873c0`（release CI 用户拍板不等） | 合并顺序按 ② 基于 ① 的设计执行，两步均 ff、零 rebase：c508896..f5253be（① 口径）→ f5253be..d3873c0（② 崩溃态取证）；远端分支 fix/memory-gate-anon… |
| 09-13 22:49 | ★ 2026-09-13 环节完善度横向扫描（main @ d3873c0e）：1 结构缺口 + 3 真缺陷 + 1 注释漂移 | 扫描轴刻意换过：历史 T1–T10 全域审计（5 HIGH+34 MED+54 LOW 已收口）是「按功能域找 bug」；本次是同构组件一致性 + 审计清单之外横切面。手段：5 个静态探针（scan1–5 在 /var/… |
| 09-14 09:07 | 度量存活度审计（代码声明 × 日志实测）—— 新方法 + 首批发现（2026-09-14，main @ a82f425a） | 任务：用户提出"度量死了应该是一类问题，用积累的日志交叉验证：哪些是代码上说有、日志里没有的"。做了系统化审计。 |
| 09-14 09:43 | ↳ 处置批次打包完成并合并：main = `2dc6e0d4`（2026-09-14） | 用户确认负载均衡已关闭 → ChatVMRouting=0 属预期（该行无条件打印，0 即"没轮转过"），从待查清单移除。 |
| 09-16 12:03 | 09-16 午：日志审计（09-16 窗口）— 3 项新发现，首要 = 超时 124 被当 shell 死亡盲重跑 | 判据来源：/var/minis/logs/minis-2026-09-16.log（7.7MB）+ memspike-2026-09-16.log（app 自带 rss/phase 探针）+ launch-beacon.… |
| 09-16 12:03 | ↳ 09-16 午：两个 audit0916 分支合并 main（main = 1863e4d2） | 远端原有两分支：fix/audit0916-scan-cost-and-anchor-gap @ 1ce9d8a（CI run 35047155917 success）与 fix/audit0916-graying-an… |
| 09-17 09:00 | 完成状态 | 09-17 凌晨：三模型 576 单元扫描 + 双向反驳收口 |
| 09-18 18:13 | 09-18：Termux×RikkaMinis「连接收益」分析（实测 5 轮探针，未铺任何线） | 结论：候选收益里只有两条过一阶门——①长驻进程（沙箱结构性做不到：shell_execute 隔离、nohup 后台 ~12 分钟被清、无调度器能唤醒 agent）②重活移出 app 进程组（§18 cgroup 证据 … |
| 09-19 01:36 | 09-19 凌晨：deepseek 400 重查（无法复现，定性为中继渠道天气）+ §18 判定日结论（归因翻转） | 400 重查（agentrouter，用户给的 Kilo-Code key，探针 /tmp/ar_probe.py）： |
| 09-20 03:26 | 09-20：offload 审计 WAVE-2 并发线 C2 完成（`ui/chat/` 渲染与文本组件） | 身份：第 21 棒并发线 C2（只读，仓库 0 改动，HEAD = 99783703 / 锚点 c6d8d63f）。 |
| 09-20 18:12 | 09-20 夜：日志全量异常扫描（去污染口径）+ 两任务已派发 | 用户指令：①用子代理把修复任务安排出去 ②查还有没有类似问题，分析日志找异常，找到就继续往下分析。 |
| 09-20 22:38 | 09-20 深夜：日志分析 → worker 进程复用率断崖，真因是 ack 令牌泄漏（同版本天然对照实验） | 任务：分析 /var/minis/logs/ 真机日志。 |
| 09-21 15:12 | 09-21：上游 issue 核实第二轮 —— 函数级探针 + 双向对照 + JVM 实测 | 用户指令：「先进行具体的核实检查，之后再决定修什么。」 |
| 09-21 16:09 | 09-21 晚：同类应用 issue 核实（1106 条 / 7 仓库）→ 主发现「非视觉模型图片门只覆盖 1/3 provider」 | 用户指令：「查一下与这个应用同类型的应用（omnibot、operit 这类）的 issue，看他们的问题在这个应用里是否同样有」 |
| 09-21 21:42 | 09-21 深夜：Chromium + Acode 源码对标调研（用户点名两个源）→ 报告 + 3 条真机硬发现 | 产出：/var/minis/shared/chromium-acode-ref-0921/REPORT.md · 加固脚本 /var/minis/shared/logq.sh |
| 09-21 21:53 | 09-21 深夜（续）：Chromium/Acode 对标收口 —— 三处自我纠错 + 产出 tag 锚定查询器 | 用户指令 |
| 09-21 22:25 | 09-21 深夜：新包 1.0.0+1741 复验 S4 → **泄漏确认修复**（pendingAck 17→1），但发现**第二道回收阻塞** | 版本核实（不靠时间猜）：dumpsys package → versionName=1.0.0+1741 / versionCode=220001741 / lastUpdateTime=2026-09-21 22:06… |
| 09-22 01:19 | V1 任务完成（verify-all-0921）：ui/chat + ui/settings 微功能验证 | 产出：/var/minis/shared/verify-all-0921/reports/V1-ui-chat-settings.md · 工具 tools/applog.py · 冻结证据 evidence-snap/ |

## 4. 沙箱 / rootfs / PRoot / 终端

**跨度** 2026-08-03 ～ 2026-09-21 · **104 条** · **状态** 稳定（09-05 rootfs 事件日志 + apk 自动恢复已上线）

**叙事**：08-03 proot 源码构建（loader 必须独立打包）→ 08-09 终端死屏根治 + 反复开关 OOM → 08-13 PRoot 文件 IO 幽灵层 + apk 包持久化方案 3 + rootfs 占位 tar → 09-05 「沙箱重置、工具不见」三源取证（**不是重置，是 per-session 设计**）→ 09-13 apk 残留进程占锁误诊为「源慢」。**教训密度最高的一条**：沙箱里几乎每个「环境坏了」最后都归到「你误诊了现象」。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 16:35 | OpenMinis proot 源码构建（2026-08-03） | 用户关注点：从 APK 提取的 proot 二进制能否由开源仓库替代/改善。 |
| 08-03 17:28 | OpenMinis fork 恢复 proot 源码构建（2026-08-03） | 分支 feat/build-proot-from-source（commit 1506c14，已推送 GitHub）。 |
| 08-03 17:39 | OpenMinis fork proot 源码构建已上线（2026-08-03 完成） | 分支 feat/build-proot-from-source（1506c14）已快进合并到 main 并推送，CI run 30801684624 全绿 success。 |
| 08-03 19:15 | OpenMinis Android — PRoot loader 必须独立打包（重要排障结论） | 从源码编译 proot 时，必须把独立 loader 也装进 jniLibs，否则真机终端/shell 会在 ~20ms 内静默死亡（status=1，无输出）。 |
| 08-04 17:56 | RikkaMinis — fix/webdav-restore-doublefire 构建检查（2026-08-04） | 用户报「构建完成了，检查一下」。检查结果： |
| 08-04 22:20 | code-workbench-tools 技能首次测试（2026-08-04） | 最新加载的技能（/var/minis/skills/code-workbench-tools，SKILL.md 22:09 更新）做了完整功能测试。 |
| 08-04 23:27 | RikkaMinis — 模型组列表拖拽排序 + 排序机制统一（2026-08-04，commit 2f42573） | 分支 feat/reorder-model-groups（从 e8f7c27 起），CI run 30923457804 全绿 success（含 testReleaseUnitTest 全量），APK 12.78MB。… |
| 08-05 00:56 | RikkaMinis — 草稿持久化 + 抽屉键盘 + rootfs 统计修复（2026-08-05，分支 feat/draft-persistence-ime-storage，commit aba858b） | 用户三个问题的根因与修复，全部已实现并推送，CI run 30931495639 验证中（分支验证，未合并 main）： |
| 08-05 01:30 | RikkaMinis — rootfs 管理页大小虚高：诊断完成，待新会话执行修复（2026-08-05） | 用户问题 |
| 08-05 01:44 | RikkaMinis — rootfs 管理页修复已完成并 CI 全绿（2026-08-05，commit 7468873） | 分支 feat/draft-persistence-ime-storage 新增第 5 个提交 7468873（fix(rootfs): use real disk usage in the rootfs managem… |
| 08-05 01:45 | RikkaMinis — Mermaid 无法渲染成 PNG（2026-08-05） | 在 PRoot/iSH 沙箱内，@mermaid-js/mermaid-cli 过不了 headless chromium 的 CDP 连接： |
| 08-06 14:19 | RikkaMinis — agent 直读应用日志（/var/minis/logs bind，分支 feat/logs-bind-agent） | 背景 |
| 08-06 22:43 | code-workbench-tools SKILL 升级到 v1.2.0 — 加"沙箱环境约束"一节（2026-08-06） | 用户反复看到模型 agent 在 RikkaMinis 沙箱里跑 grep -rn --include='.kt' 报 grep: unrecognized option: include=.kt（busybox gre… |
| 08-07 10:46 | RikkaMinis 第3项权限统一（2026-08-07）+ 意外发现 P2-proot 修复 | 第3项完成：权限判定单一事实源 |
| 08-07 14:36 | 2026-08-07 对话框交接（scroll-proot-诊断会话） | 因对话框内容将满，滚动「触底触发器」重构交给新对话框。交接完成： |
| 08-07 15:51 | RikkaMinis PRoot 泄漏修复 — 方案A(nativeRss) 从 fix/proot-rss-monitor 拆出推 CI（2026-08-07 下午） | 背景 |
| 08-07 17:32 | 多个对话框并行处理中（2026-08-07）— 三条活跃工作线 | 用户提示：其他对话框正在处理事情，不要冲突/干扰。当前并行在做： |
| 08-07 19:13 | RikkaMinis: 砍 rootfs 备份/恢复 + soul.lang 接线（2026-08-07 已发版） | 用户要求合并编译发版，5 文件改动已合 main a37c537，CI run 31172667557 全绿，release android-latest 资产已更新（11:12Z）。 |
| 08-08 00:12 | 终端沙盒模块审计 — 2026-08-08 待修清单 | 审计范围 |
| 08-08 00:22 | 终端沙盒修复施工完成 — 2026-08-08（7 文件 +76/-146） | 在 /tmp/rikkaminis-full（沙箱 git，基线 b45a68a）完成 4 项施工，未 push（跟其它并行分支等用户统筹）。 |
| 08-08 00:35 | 终端沙盒修复施工 — CI 编译通过（2026-08-08） | 在分支 fix/sandbox-audit-2026-08-08（commit 5d0faeb，基于 b45a68a）完成 4+1 项沙盒修复，push 后 dispatch CI run 31197544235，编译成… |
| 08-08 09:46 | RikkaMinis main 分支完整合并梳理（2026-08-08） | 远程状态 |
| 08-09 02:22 | feat/termux-terminal-engine 交接（2026-08-09） | 目标：把 RikkaMinis 自研终端仿真器（2249 行）替换成 Termux 0.118.0 引擎。 |
| 08-09 03:25 | feat/termux-terminal-engine 交接（2026-08-09） | 当前状态 |
| 08-09 03:56 | 终端死屏根治 — Termux TerminalView 渲染管线修复（2026-08-09） | 用户反馈：终端仍"不能操作"，只有 ✕ 可点。日志（minis-2026-08-09.log, PID 23277 = #302 包）显示 PTY 每次都正常启动（03:22:47 Termux PTY started）… |
| 08-09 06:40 | OOM 闪退 — 终端反复开关 12 次导致内存耗尽（2026-08-09） | CI #305 正在构建中（e628206，修了清屏 Ctrl+L + 换 bash）。用户测试 #304 时 7 分钟内开关终端 12 次，PRoot 子进程反复创建/销毁，Scudo 内存分配器耗尽（internal… |
| 08-09 06:49 | 终端修复交接（2026-08-09） | 分支状态 |
| 08-09 07:27 | 终端双问题根因 + 修复（commit b8dd5cb，CI run 31283965704） | 用户报两个新症状（#305 APK = e628206）： |
| 08-09 07:42 | 终端修复 #306 完成（CI run 31284262599 success） | APK：/var/minis/shared/terminux-fix/RikkaMinis-306-terminal-fix.apk（branch feat/termux-terminal-engine） |
| 08-09 08:00 | feat/termux-terminal-engine 合并进 main（2026-08-09） | 合并 commit：521431b（Merge branch 'main' into feat/termux-terminal-engine） |
| 08-13 04:19 | 任务 4：RootfsManager 完整性校验 — 完成 | 分支 fix/rootfs-integrity-check → main（merge commit 1312155） |
| 08-13 09:23 | P0 终端层补测试 — 完成 ✅ | 分支 fix/sandbox-layer-tests → main 77e789f（CI run 31657029853 success） |
| 08-13 09:59 | ↳ P2 核心文件补测试 — 完成 ✅ | 分支 fix/core-file-tests → main dc8e673（CI run 31658922502 success，中间 2 次失败后修复） |
| 08-13 10:13 | ↳ 收尾检查完成（2026-08-13） | main 最新 CI（run 31659431581, dc8e673-d）完成，success，02:09 发布 APK beta.535（versionCode 220000535）到 android-latest … |
| 08-13 10:30 | ↳ 阶段性总结已归档（2026-08-13） | 文件：/var/minis/workspace/phase-summary-2026-08-13.md |
| 08-13 16:13 | PRoot 沙箱文件 IO 幽灵层教训（2026-08-13 终端修复期间发现） | 现象：同一文件，不同进程读到不同内容，且各自稳定： |
| 08-13 16:23 | 终端三方案交接（2026-08-13，用户要求重开对话） | 交接文档：/var/minis/workspace/handover-terminal-fixes-2026-08-13.md |
| 08-13 16:53 | 终端三方案收尾（2026-08-13 续，新会话接管完成） | 交接文档：/var/minis/attachments/uploads/handover-terminal-fixes-2026-08-13.md（新会话已读取并完成全部任务） |
| 08-13 18:01 | rootfs 定向恢复真机验证（2026-08-13 续）— BUG 1 修合并 main，BUG 2 待决策 | 交接文档：/var/minis/workspace/handover-rootfs-targeted-restore-2026-08-13.md（新会话必读） |
| 08-13 18:47 | rootfs 占位 tar 重大发现（2026-08-13 晚，跨设备交接必读） | 当前设备状态：alpine-rootfs 已被重置且重装只写出 98.30 kB（rootfs 管理页显示），终端 proot 报 '/bin/sh' not found，我的 shell_execute 全部 [She… |
| 08-13 19:17 | rootfs 占位 bug 根因修正——extractTar 对 `./` 目录条目的 isEmpty break 回归 | 根因：BUG 1 修复（c1118d3, 8/13）加了 while (fullName.startsWith("./")) fullName = removePrefix("./") normalize 逻辑，但没考虑… |
| 08-13 19:42 | 验证 2 闭环通过（2026-08-13 19:55） | 验证 2（破坏 busybox/sh/ld-musl → 断网 → 强杀重开 → 自动恢复）全部通过： |
| 08-13 20:57 | ↳ 模型隔离进程真机验证闭环通过（2026-08-13 晚） | 用户要求真机验证"模型隔离进程"（feat/model-exec-service, cfd0172）—— 验证全部通过 ✅ |
| 08-13 21:26 | 【BUG 调查】rootfs 周期性重建清空 apk 包 + bash 不恢复（2026-08-13 晚） | 现象：会话中途 curl/python3/bash 全部消失，apk add 重装后约 30 分钟又丢。 |
| 08-13 21:34 | 【交接·方案3】apk 包持久化 — bug 因果链 + 方案设计（2026-08-13 21:40） | 任务一句话：把"用户通过 apk 安装的包"做成可恢复快照——apk 装包清单持久化到 host 侧（app 私有目录），rootfs 被 reset/全量重建后按清单自动重装。修掉"强停/杀应用 → 重开 → root… |
| 08-13 21:34 | 【交接·方案3】代码位置 + 开发纪律 + 开工指引（2026-08-13 21:40） | 相关代码与已知约束（新会话需拉代码确认） |
| 08-13 21:47 | 【方案3 实施中】apk 包持久化 — feat/rootfs-apk-world（2026-08-13 深夜） | commit：a2e1ee0（已 push origin，CI run 已触发 pending） |
| 08-13 22:08 | 【方案3 验证】真机抓到 PATH bug → 已修复重推（2026-08-13 22:0x） | 真机证据（用户装 a2e1ee0 构建的 APK 后 logcat）： |
| 08-13 22:54 | 终端模块 bug 修复施工（2026-08-13 晚，进行中） | 任务：用户要求终端模块 bug 修复的详细施工方案并直接施工。 |
| 08-13 23:04 | 终端 bug 修复全部闭环（2026-08-13 深夜） | 全部完成：三个 bug 修复已全部合并 main 并推送（origin/main = c64e9bc），分支全删，main release build run 31713412743 进行中（c64e9bc）。 |
| 08-13 23:30 | 【真机验证】方案3+三bug修复 验证 1 通过（2026-08-13 深夜） | 用户装 1.0.0-beta.583（c64e9bc，lastUpdateTime 23:14:20）后真机验证： |
| 08-13 23:31 | 【真机验证】验证 3（手动 reset）通过 —— 方案3 彻底闭环（2026-08-13 深夜） | 用户手动 reset rootfs → 全量重建 → restoreApkWorld 自动恢复 59 包 + bash 就位 ✅（apk list --installed \| wc -l = 59，which bash … |
| 08-15 16:21 | T4-B 修复完成 — 已合并 main（571bfe4，2026-08-15 晚） | 用户现象延续：main c14d29f #751 红（冲突标记 + F09/F14 失败）→ 修复分支 fix/t4b-clean-conflicts fc376ae #752 仍红（实际全 12 个测试都挂）。 |
| 08-15 16:29 | ↳ T7 状态确认：已全部完成（main 571bfe4） | 领取 T7 任务后全面检查代码状态，确认 T7-A/B/C/D 四阶段全部已在 main 中实现： |
| 08-15 17:27 | ↳ T7-RealRuntimePort 完成 — 已合并 main（83abd79，2026-08-15） | 分支：stability/T7-real-runtime-port（已删） |
| 08-15 17:43 | ↳ 剩余可分派任务（2026-08-15 下午更新） | 代码层面全部完成（T0-T9 + T4-B 真实适配已合并 main 83abd79a，release CI 绿）。 |
| 08-15 18:03 | ↳ T10 最终验收执行完成（2026-08-15 下午） | H 层验证：本地重建 JVM 沙箱环境，RealRuntimePortAcceptanceTest 2 轮 17/17 ✅，RealAgentAdapterAcceptanceTest 2 轮 14/14 ✅，合计 62… |
| 08-20 08:08 | native-OOM Phase 1 侦查推进（2026-08-20 会话二） | 接手 fix/native-rss-tool-guard 分支施工。工作树 /tmp/rikka-diag 干净，HEAD=b31bb65，领先 origin/main（ce760f9）5 commits。分支 CI 全… |
| 08-21 21:02 | Phase 4 fix/proot-child-memory-guard 施工（会话 B，2026-08-21 晚） | ⚠️ 共享工作树事故（重要协作教训）：/tmp/rb 是共享工作树，会话 A（Phase 2 fix/modelservice-terminal-protocol）中途 checkout 切走了分支，导致会话 B 的工作… |
| 08-22 12:54 | RikkaMinis 官方对比自述文档完成（2026-08-22） | 生成了 /var/minis/workspace/RikkaMinis-官方对比自述.md，把 fork 相对官方 OpenMinis 的全部差异按三类归档： |
| 08-23 14:34 | 会话 B 完成：终端 sandbox（PRoot）压测 — 3 个 P0 + 1 个中危（2026-08-23） | 报告：/var/minis/shared/bug-hunt-pressure/reports/session-B-report.md |
| 08-25 22:40 | 终端命令触发主进程 RSS 单调泄漏（2026-08-25 22:40 现场抓到） | 用户复现：某对话框「继续运行终端」→ 主进程内存飙升 → 再发消息 → 闪退（日志开着）。 |
| 08-25 22:50 | 终端 RSS 泄漏 — 源码定位审计结论（2026-08-25 深夜） | 审计范围：/tmp/RikkaMinis（main tip 2f3498f）的 NativeOffload.kt / ExecutionCoordinator.kt / PersistentShell.kt / Offl… |
| 08-25 23:40 | 「立刻飙升」真根因定位：PRoot 虚拟地址空间 reserve，非真实泄漏（2026-08-25） | 用户澄清：某个对话框一调用终端就「立刻」飙升（复现用的是 sed 查看源码命令，本质是 shell_execute）。A 方案（慢泄漏治理）当然无效——形态根本不同。 |
| 08-26 00:47 | 会话 F：打开历史对话默认回顶部 — 施工+阻塞(2026-08-26) | 任务：修复「冷打开历史会话默认落在顶部而非底部」。根因(代码实证)：消息级聚合(AGGREGATE_MESSAGE_ITEMS=true)+SIMPLE_FOLLOW 改造后，InitialOpen 消费端在 LazyC… |
| 08-26 01:55 | PRoot VSZ 虚高根因 — 沙箱实测推翻旧假设（2026-08-26） | 任务：根治 libproot.so tracer 的 ~10GB VSZ 导致的 MIUI 误杀。 |
| 08-28 01:13 | 收尾加固会话 C 完成：外围 i18n + a11y + rootfs 磁盘预检（2026-08-28，分支 fix/i18n-periphery-and-diskguard） | 状态：分支 CI 绿（run #1141 success，head=65010cb 核实一致），未合并 main（按任务书纪律等总控收口）。回报 /var/minis/shared/final-hardening-dis… |
| 08-30 13:45 | 文档收尾：README/docs 与当前代码对齐（2026-08-30） | 用户指示：文档部分经多轮修改已与代码脱节，要求核对并更新收尾。审计 main@d49235c（fetch 后）逐篇对照，纯文档改动提交 9beae14d 推上 main（ff，未触发 CI——build-apk.yml … |
| 08-31 14:43 | LiteLLM 成本层 V2：JSON 价格表 + 用户可编辑价格（2026-08-31，commit fbe888e7） | 用户反馈 Usage 页看不到「预估费用」→ 根因：价格目录是硬编码 Kotlin map，只覆盖 40 个内置模型，中转站模型（deepseek-v4-pro-0813 之类）不在任何公共价格表里，按「未知→null→… |
| 09-02 11:14 | session4 浏览器/沙箱层审计完成（rikka-bug-hunt） | 审计 /tmp/rikka 的 browser/ 全目录 + ExecutionCoordinator.kt + RootfsManager.kt，报告：/var/minis/shared/rikka-bug-hunt/… |
| 09-02 20:53 | FE-5 route C ③ CI 红修复 + 沙箱重建（2026-09-02 晚） | CI run 33629407247 失败原因（交接文档预言的「引擎没过真实 Android 编译链」）：全是编译错误—— |
| 09-02 23:17 | ↳ FE-5 第四/五批合并拆分（2026-09-02 晚，commit 9a3949f） | 用户拍板：后面几批合并一起拆，拆完做系统性 bug 扫描（llm-bug-audit），不必逐批保真。先彻底解决「拆」再扫 bug。 |
| 09-05 16:46 | 用户报"最近经常遇到沙盒重置、kotlinc 等工具不见了"。调查结论（三源验证：文件时间戳 + 日志 + 语义记忆）： | 用户报"最近经常遇到沙盒重置、kotlinc 等工具不见了"。调查结论（三源验证：文件时间戳 + 日志 + 语义记忆）： |
| 09-05 17:33 | feat/rootfs-event-log 收尾闭环（2026-09-05 17:5x） | 产出：main @ 5ebff693（12bfbbaf ff 合并）。分支→main 全链路干净。 |
| 09-05 22:11 | rootfs-event-log 真机验证确认（2026-09-05 用户补记） | 用户确认 main @ 5ebff693（rootfs 事件日志）真机早已测试通过：装新 APK 后手动 reset，沙箱 cat /var/minis/logs/rootfs-events.log 可见 MANUAL_… |
| 09-06 12:15 | 知识图谱备份安全性核查（2026-09-06）—— 图谱数据不会被备份导出 | 用户问"删应用前做备份，图谱那部分会不会也被备份"。代码级核查结论： |
| 09-07 18:59 | 「内存又涨上去了」诊断（2026-09-07 晚，日志铁证） | 结论：涨的是主进程 native heap（Skia 文本排版层），由本会话自己的巨型流式消息 + 每秒一次的重组触发；守卫和 LMK 都按设计工作；与浏览器三件修改无关。 |
| 09-09 19:00 | T3 sandbox-offload 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T3.md。扫描 69 文件/24961 行：HIGH 0 + MEDIUM 6 + LOW 2。 |
| 09-10 08:09 | 工具显示两处修复（分支 fix/shell-stream-partial-line @ fb14213b，CI #1445 绿，用户拍板暂不合并） | 用户两个问题 → 两个根因（都在显示层，模型侧数据是干净的） |
| 09-12 11:43 | P0/P1/P2 三件全闭环（main @ fe39a66，2026-09-12） | 分支 fix/p012-strict-json-reasoning-flag（2 commit：60aeea8 测试 + fe39a66 诊断）→ 沙箱 JVM 11/11 绿 → 分支 CI run #1471 suc… |
| 09-13 15:02 | 性能实测：沙箱重活把 app 进程 RSS 顶到 1GB，触发自有内存门反过来掐工具通道（2026-09-13） | 用户提问：「应用效率和性能是否还能提升」→ 转成设备实测（pid 442 = com.rikkaminis.app）。 |
| 09-13 15:56 | ★★ 交接：legacy 管线隔离 + backlog 两项（分支未推，本会话终端环境已损坏） | ⚠️ 本会话终端不可用：任何 shell_execute 都会触发 [System busy: process memory is critically high (982/1022/2283MB)]。成果全部在磁盘上，… |
| 09-13 16:53 | ★ 内存实验结论 + 发现应用内建 per-command RSS 探针（2026-09-13 16:50-16:53 实测） | 实验：在"曾报内存告警"的会话里连调 36 次终端 |
| 09-13 17:09 | ★ 交接：内存探针（memspike）非 shell 路径扩展 —— 分支待装包复现（2026-09-13 17:10） | 一句话状态：诊断分支 feat/diag-memspike-dualapp @ 73d741c（3 commits，已推，工作树干净）已做完并把探针接到非 shell 路径；CI run 34748974970 已派发（… |
| 09-13 18:23 | ★ 2026-09-13 收尾终态（main = 6c20459）+ 存储清理 + 探针失效 | 五次合并全部落在 main（按时序）：e057d151（shellTimeout 接线 + 滑杆密度，凌晨，真机已验）→ 15b3f447（dev-history 926 条）→ 3ca019e（恢复被回滚的三笔：ope… |
| 09-13 20:46 | 内存加固三件套实现完成（分支 fix/memory-hardening-rss-stall，3 commits，CI #1498 success） | commits：a641e27 KaTeX 位图上限（H1）→ 45ce0c8 app 自身压力治理器（H2）→ c508896 挂死处置（用户追加要求）。沙箱 JVM 56/56 绿、仓库门禁 6/6、CI #1498… |
| 09-13 23:54 | ★ 2026-09-13 分支修复真机验证完成（全项通过）—— 待用户拍板合并 | 包确认：设备 versionName 1.0.0+1504、versionCode 220001503→220001504（ShortcutService 日志实证更新）、APK 时间戳 23:28 = CI run_n… |
| 09-14 20:05 | 09-14 夜：运行日志审计 → 6 项修复已合并 main = ad71828c（release CI #1526 已触发，用户拍板不等） | 前提（用户提醒）：日志（13:48–18:51）属旧包（1.0.0+1516 及更早），设备现装 1.0.0+1524 已含 79d57d69 修复 —— 我用 APK dex grep（grep -ac 'stub, … |
| 09-15 21:56 | 09-15 晚：HF 语义记忆重建 + MCP 知识图谱重建（09-06 套件随 rootfs 全丢） | HF：semantic_memory.py build 732→1070 条（索引 5.8MB，已上传 dataset USER/rikkaminis-memory），搜索验证命中正常。 |
| 09-16 08:00 | 09-16 早：§18 归因实证 + 用户拍板「记纪律不立项」（beacon 观测桩上线） | ★ 归因实证（新证据，之前只有时间相关）：沙箱 /proc/self/cgroup 的组路径 = 0::/uid_11618/pid_19313，而 pid_19313 在沙箱 /proc 里 No such file … |
| 09-16 16:48 | 产出 /var/minis/mounts/笔记/三家终端执行型项目对比RikkaMinis-2026-09-16.md（ | 产出 /var/minis/mounts/笔记/三家终端执行型项目对比RikkaMinis-2026-09-16.md（7 关节 × 4 代码库，file:line 级）。方法：fresh clone 四仓（OI 竟已转… |
| 09-17 14:22 | 09-17 下午：审计存量缺陷批次 1-7 收口 — main = e657335f | 成果：215 条 CONFIRMED → 已处理 155 条 / 92 文件，剩余 60 条（HIGH 4 / MED 32 / 其他 24）。10 个提交 FF 合并，CI 全绿，远端仅剩 main。 |
| 09-17 18:13 | 09-17 傍晚：存储页转圈 + markdown 列表误渲染双修复 → main = 40a58c94 | 用户报告两件事，都实锤： |
| 09-17 20:18 | ↳ 09-17 收尾：全天工程量统计（用户问"为什么感觉工程量大"时的硬数据） | main 交付量（SGT 09:41→17:55）：33 提交 / 147 unique 文件（全仓 ~524 文件的 28%）/ +3031 −577 行；CI 今天 43 轮构建（29 绿 / 9 红 / 5 取消，… |
| 09-18 18:13 | 09-18：Termux×RikkaMinis「连接收益」分析（实测 5 轮探针，未铺任何线） | 结论：候选收益里只有两条过一阶门——①长驻进程（沙箱结构性做不到：shell_execute 隔离、nohup 后台 ~12 分钟被清、无调度器能唤醒 agent）②重活移出 app 进程组（§18 cgroup 证据 … |
| 09-18 18:31 | 09-18 深夜：Termux↔RikkaMinis 打通（termux-dock MCP 桥）+ 三组实测数字 | 怎么发现的：探测本机监听端口时发现 127.0.0.1:8000 回 termux-dock MCP is running —— 用户 Termux 里早就跑着一个 MCP 服务（pm2 + watchdog 托管：te… |
| 09-18 18:37 | 09-18 深夜：PRoot 慢 8-10x 的机制与实测（syscall 税） | 机制：沙箱被 app 自带的 libproot.so 用 ptrace 跟踪（沙箱内 /proc/self/status 实测 TracerPid=13403、Seccomp=2）。seccomp 过滤=2 意味着只有"… |
| 09-18 18:43 | 09-18 深夜：查清 native_offload 的判定条件（结论：不适合当重活加速器） | 判定链：execve 的 basename ∈ 编译期常量名单（sandbox/OffloadHandlerCatalog.kt，19 项）→ libproot 的 C 扩展截获 → abstract unix sock… |
| 09-20 08:48 | 09-20：FIX-6 修复批完成并合入 main（设置/配置 9 条 + F-224 数据层收尾） | 最终 main = b67558d。两个阶段： |
| 09-20 10:03 | 09-20：FIX-8-approot 补扫 —— 两文件 1,820 行真缺口，抓出 3 条 D（main = `541fbb2`） | 起点：第 22 棒收口报告里登记的「MinisApp.kt + MainActivity.kt = 1,815 行从未逐行走查」。用户直接说「那你这里直接把他们补上」。 |
| 09-20 10:54 | 09-20：真机验证清单 agent 侧独立验证（42/42 + 反向臂 34 红） | 用户指令：T2-3（输出上限字节口径）"这是代码的问题，别测了"、另一条"在另外一个地方修了" → 只做不需要设备 UI 的条目。 |
| 09-20 17:10 | 09-20 晚：隐藏设置页 Agent Runtime 里的终端行 → 分支 `chore/hide-settings-terminal-row`（CI 绿，**按用户要求不合并**） | 用户判断：设置 → Agent Runtime 里的「终端」行冗余——真要用，外观 → 聊天菜单里已经能把它放到右上角菜单/抽屉底栏。去掉。 |
| 09-21 21:15 | 09-21 深夜：S4 会话（沙箱两条）完成 —— 分支 `fix/s4-sandbox` @ `6375eb36`，CI 35602522458 全绿，**按任务书不合并** | 任务书：/var/minis/shared/dispatch-0921/tasks/S4-sandbox.md（交付边界=分支 CI 绿，禁止合并/开 PR/删分支）。报告全文：/var/minis/shared/s4-… |
| 09-21 22:01 | 09-21 收口：dispatch-0921 五分支审查 + 合并 main = `671f928c`（CI 35607838698 全绿，release 资产已刷新） | 用户指令：检查核验云端 5 个分支 → 有问题就改，没问题就合并。 |

## 5. 流式回答中断 / 恢复 / 取消

**跨度** 2026-08-03 ～ 2026-09-21 · **120 条** · **状态** 已闭环（09-07 收口，含停止按钮竞态 + 多会话真并发真机验证）

**叙事**：08-18 「回答频繁断掉」诊断链（provider 层铁证健康）→ 08-24 首块超时 30000ms + retry 分类不对称 → 09-06 一天内连收四种形态：EOF 静默停、stream error 手动重试、content_filter→fallback、finish_reason=network_error 伪正常结束 → 09-07 预算墙第 4 形态 + provider-exec-concurrency。**「同一个用户现象 = 五种不同根因」的教科书案例**，每条都靠日志实证拆开。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 21:00 | OpenMinis Android — 空对话残留 bug 根因与修复（commit b194927，CI 30815328668 success） | 用户报「历史里出现空对话，自动删除时好时坏」。定位到根因链： |
| 08-05 10:01 | RikkaMinis 底部工具条 — 真机发现 footer 按钮失效，根因 = LaunchedEffect 自取消（2026-08-05） | 用户真机验收发现：历史抽屉 footer 里 Token 用量 / 设置两个按钮（默认唯一两个）点击无反应。 |
| 08-05 23:15 | RikkaMinis 全量代码审查（2026-08-05 晚，clone /tmp/rikkaminis-review） | 结论 |
| 08-11 00:58 | 吸收上游 fork 的降级三件套 — 实际只做了一件（feat/fix/length-wall-continue） | 用户按自己的标准（①shell/框架做不到的事 ②可验证 ③框架>功能）审查 17 个真改动 fork 的功能： |
| 08-11 01:29 | ↳ RikkaMinis 开发项目最终定档收尾（2026-08-11） | 给已有的《RikkaMinis-项目收尾总览.md》追加了"再补续——最终定档"章节（08-10 是"后记"，08-11 是真正句号）。定档内容： |
| 08-12 02:05 | 文字渲染空白 bug 根因定位 + 修复完成（2026-08-12） | 用户一手现象：大模型高速流式回答时，偶发文字块空白；滑出屏幕再滑回，文字正常。 |
| 08-13 04:29 | 任务3：NativeOffload 大请求流控 ✅ | 分支: fix/native-offload-size-limit → main 05e7398（CI success） |
| 08-13 06:19 | 任务 D：删残留分支 ✅ 完成（2026-08-13） | 远端 test/clean-generated（1492f21，AI 测试教训产物）已删除 |
| 08-13 06:41 | ↳ 任务 A：webapp-hidden TODO 评估 — 完成（2026-08-13） | 评估结论：功能完整可用，非半成品。6 处 TODO 全是 false && 或 ENABLED = false 守卫。 |
| 08-13 06:59 | ↳ 任务 B：核心文件补测试 ✅ 完成（2026-08-13） |  |
| 08-14 20:36 | ⚠️ GitHub workflow_dispatch 并发竞态（2026-08-14 D 类会话实测） | 现象：dispatch 自己的分支 CI（ref=fix/err-family-provider-defense），run 的 API head_branch/head_sha 显示正确（fix/err-family-p… |
| 08-15 05:32 | T3 派发指令 — 副作用重试策略 | 你负责 RikkaMinis 平衡点施工 T3 — 工具与 shell 的副作用重试策略。 |
| 08-15 05:32 | T4-A 派发指令 — 故障注入 Harness | 你负责 RikkaMinis 平衡点施工 T4-A — 故障注入 Harness（fakes + 场景协议 + 独立 runner）。 |
| 08-15 06:11 | T3 副作用重试策略完成 — CI 绿（1cb366c，2026-08-15） | 分支：stability/T3-retry-side-effects |
| 08-15 16:21 | T4-B 修复完成 — 已合并 main（571bfe4，2026-08-15 晚） | 用户现象延续：main c14d29f #751 红（冲突标记 + F09/F14 失败）→ 修复分支 fix/t4b-clean-conflicts fc376ae #752 仍红（实际全 12 个测试都挂）。 |
| 08-15 16:29 | ↳ T7 状态确认：已全部完成（main 571bfe4） | 领取 T7 任务后全面检查代码状态，确认 T7-A/B/C/D 四阶段全部已在 main 中实现： |
| 08-15 17:27 | ↳ T7-RealRuntimePort 完成 — 已合并 main（83abd79，2026-08-15） | 分支：stability/T7-real-runtime-port（已删） |
| 08-15 17:43 | ↳ 剩余可分派任务（2026-08-15 下午更新） | 代码层面全部完成（T0-T9 + T4-B 真实适配已合并 main 83abd79a，release CI 绿）。 |
| 08-15 18:03 | ↳ T10 最终验收执行完成（2026-08-15 下午） | H 层验证：本地重建 JVM 沙箱环境，RealRuntimePortAcceptanceTest 2 轮 17/17 ✅，RealAgentAdapterAcceptanceTest 2 轮 14/14 ✅，合计 62… |
| 08-16 22:01 | 打断 bug 根因确诊 + 修复合并 main（665dfac，2026-08-16 下午） | 用户现象：流式回答期间发消息打断 → ①旧 turn 卡"正在思考"虚线框（实际已停）②新发消息显示为虚线框永不变化（"被吞"）③退出重进正常。重进正常 = 重新 seed 全量 build 后一切正确。 |
| 08-17 01:37 | 打断后"旧工具一直转/thinking 残留"修复施工中（2026-08-17） | 用户复现的 bug 根因已定位：流式/工具回合中发新消息，旧 assistant 实际状态已收敛到终态（tool SUCCESS/FAILED/CANCELLED、isStreaming=false），但 StableC… |
| 08-17 02:16 | 打断后"thinking 残留"/"tool 停但 thinking 还在"根因排查（2026-08-17 续） | 用户反馈：账本修复（fix/ledger-status-sync 已合 main faa1905）真机验证"tool 停了但 thinking 还在"。 |
| 08-17 02:42 | 打断残留修复纠正 | 用户明确确认真机安装的是 88e6a263d9bd030608a0f81ffba481555fb2f5b0。因此不能再把残留归因于补丁未推送/未安装；后续应按“88e6a26 已真机失败”审查其状态收敛逻辑。 |
| 08-17 06:25 | 打断后 thinking/工具残留双路径根因诊断（2026-08-17 续） | 用户真机装 38b2960 验证 de18d25 的账本修复（activeAssistantIds 登记 + isLiveAssistant 扩大）仍显示：打断位置上方仍有旧"正在思考"残留。且用户问"打断时正在执行的工… |
| 08-17 08:08 | 打断残留修复终极根因：prune 抢先 + rowsTouched 门控 + thinking 折叠信号（2026-08-17 系统性收口） | 用户关键观察（扭转方向，价值极高）：切对话再切回 → 残留消失 → 证明 canonical 数据从头到尾正确，问题纯在"已发布行未刷新 UI"。用户最后拍板：不追求"消失"，而是 UX——打断后的旧回合应呈现"已停止"… |
| 08-17 09:20 | 方向A实施进行中：聊天主路径LLM调用隔离到 :modelservice（2026-08-17） | 接手交接文档 /var/minis/shared/memory-native-offload-direction-a-handover.md，方向A治本方案。 |
| 08-18 08:58 | 断流诊断采集链路已就绪（2026-08-18 08:58） | 用户已确认断流形态=第2种：回答已开始输出，中途突然停（无错误提示、无声无息停）。 |
| 08-18 09:01 | 断流定性重大进展（2026-08-18 09:01，provider 层排除网络断流） | provider 层 100% 无断流：扫描 ms2.log 全部 llmhost.net + .sensenova.cn 请求，每个都有 responseBodyEnd bytes=<完整> → canceled（ca… |
| 08-18 09:03 | 断流诊断第2次复现（2026-08-18 09:03）——provider 层铁证健康 | 扫描全部 ms2.log：每个 provider 请求（llmhost.net + .sensenova.cn）都在 modelservice 侧完整收尾——stream done + finish_reason + s… |
| 08-18 09:22 | 断流根因重大进展（2026-08-18 09:21）——最可能根因锁定 | 现象新事实 |
| 08-18 09:27 | B2 任务包：保护活跃流式不被压力回收打断（2026-08-18 09:32） | 背景 |
| 08-18 10:29 | ✅ thinking 漏进正文 修复闭环（2026-08-18，main 500c5fa） | 根因（代码确证，非 UI 层）：泄漏发生在 Provider 归类层（思考被发成 Text），不是折叠 UI。折叠 UI（ChatFlatItems 只对 kind=="thinking" 发 AssistantThin… |
| 08-18 14:48 | RikkaMinis 审计 T03+T10 完成（2026-08-18，会话 C） | 基线 500c5fa 确认（工作树干净）。报告： |
| 08-18 15:12 | RC 整改执行指令（分会话凭编号即可领取，勿需用户贴长文本） | 用户约定：以后派发整改任务，只需发编号（RC1~RC6）。分会话 agent 收到编号后，到下面对应条目领取完整执行指令，并自查「通用纪律」。总控在本会话收口。 |
| 08-18 16:11 | RC2 流式断流截断标记完成（2026-08-18，独立会话） | 分支 fix/audit-rc2-truncated-detection，commits c942aae(3 provider 改) + 3ee6487(Gemini 测试修)，CI run 32113869899 su… |
| 08-18 17:01 | 6 个 RC 整改全部合并 main 完成（2026-08-18 收口） | 用户把审计整改按编号派发到其他会话，全部完成后本会话统一合并收口。 |
| 08-18 19:47 | RC16 — MultiDeviceSync 先拉后推覆盖竞态（乐观锁）完成（2026-08-18 晚，独立会话） | 分支 fix/audit-rc16-sync-if-match，commit fe0a43f（+ 首跑测试修复前 e8369a2），CI run 32132091706 success（25 steps 全绿）。回报 r… |
| 08-18 19:57 | ↳ FE-4 route A+B 完成:ChatViewModel 纯函数抽取(2026-08-18) | 用户领取 FE-4 任务(ChatViewModel 12158 行拆分),走交接文档的"路线 A:先抽无状态纯函数"了路线,零回归闭环,CI 绿(run 32133152371),未合并 main(等用户拍板)。 |
| 08-20 22:52 | thinking 级别无效 + 工具卡"正在调用"卡死修复（2026-08-20 收尾） | 任务文件：/var/minis/shared/task-thinking-level-and-ui-stuck.md |
| 08-20 23:19 | thinking 级别无效 + 工具卡"正在调用"卡死修复（2026-08-20 收尾） |  |
| 08-21 12:07 | 2026-08-21 崩溃修复 + 偶发丢消息搁置 | 已修复并合并（main a1bc4bb） |
| 08-21 14:09 | 工具调用"持续运转无结果"+发消息后自动恢复 诊断（2026-08-21） | 现象：agent 执行工具调用（shell_execute grep 等）时，UI 卡在"正在调用"持续运转却没结果；用户发一条新消息后，界面自动"刷新"恢复。 |
| 08-21 14:38 | ↳ 工具卡住诊断埋点落地闭环（2026-08-21 下午） | 背景：用户报"工具调用持续运转无结果，发消息后自动刷新恢复"。日志实证（minis-2026-08-21.log）：turn=13 工具全部 SUCCESS 后 87 秒无下一轮 REQ，StreamPerf turnS… |
| 08-21 19:44 | 原生内存隔离施工开始（Phase 0：bounded admission） | 用户要求彻底解决内存飙升，给出 v2 方案（/var/minis/workspace/rikkaminis-native-memory-isolation-plan-v2.md），用户确认施工。 |
| 08-21 22:01 | Phase 2 modelservice 可靠 worker 生命周期 完成（会话 A，2026-08-21 收尾） | 闭环：分支 fix/modelservice-terminal-protocol → 分支 CI 绿（run 32487240396，head_sha=f1f04b0 核实）→ rebase 最新 main（2d2ffb… |
| 08-23 12:01 | TF-J modelservice「dead before any output」竞态修复完成（2026-08-23） | 用户两版真机日志（__2_.log / __3_.log）驱动。TF-I 后仍复现：agent 多轮工具调用触发连续 stream offload，worker pid=3592/startTicks 恒定（进程没被系统… |
| 08-23 14:39 | 会话 D 完成：浏览器（browser_use / bridge）压测（2026-08-23） | 基线 a6b2665。报告：/var/minis/shared/bug-hunt-pressure/reports/session-D-report.md。实测路径：内置 browser_use → minis-brow… |
| 08-23 15:36 | 修复 03：get_text 超长文本截断标记 | 在小号 ALT_USER/RikkaMinis 基线 a6b2665 上创建并推送分支 fix/browser-gettext-truncate-flag，commit 6da1df1。BrowserUseJS get_… |
| 08-23 19:56 | 双交互缺陷分析与修复方案（2026-08-23，主号 main dc18d45） | 问题一：手动终止回答后 · 立即再发 → 上一条"思考中"残留 |
| 08-23 20:51 | ↳ 双交互缺陷修复已合入 main（2026-08-23） | bc7cc13: ProviderRepository.saveApiKey/deleteApiKey 从 SharedPreferences .apply() 改 .commit()，避免保存后立即发送时 models… |
| 08-24 10:52 | 首块超时「provider produced no first chunk within 30000ms」调查 + 委托派发（2026-08-24） | 测试/证据：真机日志 08-24 该错误 90 次（07:42 后集中），08-22/08-23 = 0 次真实运行；涉及 deepseek-v4-flash/gpt-5.6-luna/deepseek-v4-pro，走… |
| 08-24 11:26 | 首块超时调查（会话 1）= 发现 retry 分类不对称 bug + 路由感知超时（2026-08-24） | 结论：30s 守卫本身没错（防 live worker 误判 DEAD 是 TF-I/TF-J 正确设计），但主进程 retry 分类有确凿不对称 bug——first_chunk_timeout 抛 ModelStre… |
| 08-24 11:29 | 首块超时修复已合入主号 main（2026-08-24，会话 1 汇报后用户拍板） | 合并状态：分支 diag/first-chunk-timeout commit 62d3db4 已推送并 ff 合并主号 main（a09206a→62d3db4），main 已推送。release CI run 326… |
| 08-24 11:35 | ↳ 工具「被调用两次」修复完成：fix/tool-call-dedupe @ 73400d6（2026-08-24） | 任务来源：工具派发任务书 /var/minis/shared/tool-dup-exec-fix/FIX-TASK.md（用户报告：一个回合内大模型重复调用同一工具，客户端各执行一次，串行的第二个一直在跑/占空间）。 |
| 08-24 11:42 | 首块超时修复 release CI 全绿 + 收口（2026-08-24） | release CI run 32686587661 success，head_sha=62d3db4a 已核实一致（防假绿）。远端分支 diag/first-chunk-timeout 已删除。闭环完成：调查 → 修复… |
| 08-25 09:46 | shell_execute 取消后 UI 永久转圈 + 停工具连带停对话（2026-08-25） | 用户真机（beta.1033 = main 85e7b29）报：shell_execute 工具卡"一直运行、转圈不停"；且手动停工具会连带停掉整个对话。 |
| 08-25 19:53 | 首块超时掐断思考模型 bug 已修复合并 main（3eb1785，2026-08-25） | 用户报：思考型模型（reasoning/thinking）会在固定时间被掐断，报错 provider produced no first chunk within 45000ms (hadChunks=false)，后台… |
| 08-26 02:55 | 最近改动 bug 审计（2026-08-26，用户要求"抓 bug + 施工方案"） | 审计 main 7f68752 及前 3 个 commit。报告 /var/minis/shared/recent-changes-bug-audit-2026-08-26.md。 |
| 08-26 13:36 | 历史对话回底部「随机失效」调查进行中（2026-08-26 上午，接续 8484a49） | 用户反馈：fix/history-open-at-bottom（8484a49）合并装包后仍随机失效——偶尔定位顶部、偶尔正确到底部。真机 beta.1083 = main 0ba797a（含修复），版本已核实（dump… |
| 08-26 14:39 | 历史对话回底部随机失效 — 根因修复已合 main（dbaa4aa，2026-08-26） | 根因（Compose 源码级实证）：8484a49 修复后仍随机的根因有两层： |
| 08-27 20:21 | 停止卡顿 + 发消息卡顿根因定位与修复（2026-08-27，分支 fix/stop-lag-and-send-prompt-bloat） | 用户现象两个：①长任务后期点「停止」卡 1~2 秒才真正停；②稍长对话发消息一开始卡顿（之前缓解过但不够）。 |
| 08-27 20:54 | 停止卡顿 + 发消息卡顿修复已合并 main（2026-08-27 收尾） | 分支 fix/stop-lag-and-send-prompt-bloat 两 commit 已 ff 合并 main（a8f8c03 → 4f8245e），push 成功，main release CI run #11… |
| 08-31 20:40 | 卡死诊断：冷启动进 chat 界面卡死 ~10s（2026-08-31，minis-2026-08-31.log） | 用户报"装更新后整个应用卡死一段时间"。日志分析结论（证据链完整）： |
| 09-01 02:26 | 开发线转移 + 成熟度门槛（2026-09-01 凌晨，用户拍板） | 仓库架构决策 |
| 09-01 19:07 | place-storm 残留源修复 + launch-resume 导航修复（2026-09-01，commit 65b8a74 合并 main） | 用户试运行日志验证结论（minis-2026-09-01__4_.log，主号 91498d74 构建） |
| 09-06 18:16 | 负载均衡请求级改造 + 停止按钮竞态修复全链路闭环（main @ cd32795，分支 CI run 340260822 | 负载均衡请求级改造 + 停止按钮竞态修复全链路闭环（main @ cd32795，分支 CI run 34026082245 head_sha 核对一致后 ff 合并，release CI 34026796848 in_… |
| 09-06 19:09 | Hermes Tier-1 harness 四改动合并 main（2026-09-06 晚，main @ d7ee353e） | 分支 feat/hermes-tier1-harness → 分支 CI 34028554543 success（bridge 核对）→ ff 合并 main → push 触发 release CI（未等）→ 本地+远… |
| 09-06 19:37 | EOF 断流静默停修复合并 main（2026-09-06 晚，main @ 1150e05f） | 用户症状：回答到一半突然停，手动发"继续"才接上。根因 = 中转商 SSE 断流（无 finish_reason）：旧 T-truncated-stream-retry 删半截答案重新生成（浪费 + 重发开头），第二次 … |
| 09-06 19:40 | 会话交接（2026-09-06 晚）→ 交接文档 /var/minis/shared/hermes-tier1-handoff.md | main @ 1150e05f（tier-1 harness 四改动 + EOF 断流静默停修复，双分支 CI 绿 ff 合并）。main release CI run 34030771852 结论未等——新会话开场先查… |
| 09-06 20:06 | compaction recall eval 种子跑通（2026-09-06 晚） | 脚本 /var/minis/shared/compaction-recall-eval/compaction_eval.py（Hermes evals/compaction 移植 + RikkaMinis 压缩管线忠实 … |
| 09-06 20:15 | 真机验证状态更新（2026-09-06 晚，用户拍板） | 用户已装 android-latest（main @ 1150e05f release CI 绿的包），验证方式 = 日常使用即验证（用户明确："你的工作就在验证范围内，感觉挺顺利"），无需专门测试仪式 |
| 09-06 20:45 | "Stream error 手动重试"根因确诊（2026-09-06 晚，纯代码读穿，无需日志） | 用户观察：单选具体模型时 stream error 出手动重试按钮；模型组模式"似乎会自动重试"。 |
| 09-06 21:30 | stream-error 自动恢复修复合并 main（2026-09-06 深夜，main @ 82735b14） | 分支 fix/stream-error-silent-recovery 两提交（e8500bb8 + 82735b14 编译修复）ff 合并 main；分支 CI 34035507023 success（head_sha… |
| 09-06 21:49 | stream-error 自动恢复 + EOF 真机验证闭环（2026-09-06 深夜，用户确认） | 用户已真机验证通过：新装 android-latest（main @ 82735b14 release CI success）后，特意换了一个质量差的 API key 触发断流/错误场景，验证了 stream-error… |
| 09-06 22:35 | Tier 2 ② content_filter→fallback 合并 main（2026-09-06 深夜，main @ f311aa99） | 分支 feat/content-filter-fallback 两提交（b8e8dda7 功能 + f311aa99 i18n 修复）ff 合并 main；分支 CI 34038969226 success（head_s… |
| 09-06 23:03 | 现场抓取：「回答到一半突然停」第2次自然复现（2026-09-06 23:0x，未捕获直接证据） | 用户报"回答着回答着突然停"——即当前会话（正在做 verification_stop 时）。立即用 android-shizuku-cli 抓 logcat（约停后 1-3 分钟内，多轮抓取）。 |
| 09-06 23:15 | 突然停根因确诊（finish_reason=network_error 伪正常结束）+ verification_stop 分支推送（2026-09-06 深夜） | 用户抓到第三次复现的完整现场（提前开了应用内日志，上传 /var/minis/attachments/uploads/minis-2026-09-06__3_.log）——前两次 ring buffer 被冲掉的教训后这… |
| 09-06 23:28 | Tier 2 ③ verification_stop + network_error 修复合并 main（2026-09-06 深夜，main @ 54a836ae） | 分支 feat/verification-stop 单提交 54a836ae ff 合并 main；分支 CI 34041687429 success（head_sha 核对一致）；release CI push 自动触… |
| 09-06 23:34 | 收尾交接（2026-09-06 深夜） | 交接文档 /var/minis/shared/tier2-closeout-handoff.md（突然停三形态闭环总表 + Tier 2 最终状态 + backlog + 采集器重挂命令） |
| 09-06 23:58 | dev-history 0906 同步 + 主号→小号全量同步（2026-09-06 深夜） | 主号 main @ 7d928fa3（docs(dev-history): sync archive to 2026-09-06, 794 entries / 35 days）： |
| 09-07 01:31 | provider-exec-concurrency 分支（A+B 多会话并发）+ 突然停第4形态（预算墙）双修复 | 分支 feat/provider-exec-concurrency（未合并 main），三提交： |
| 09-10 01:00 | 第二轮审计修复：MEDIUM 清零 + LOW 批次（2026-09-10 凌晨） | main 状态：main @ d66fea1（B11 ff2455b + B12 d66fea1 已 ff 合并；B11 release CI 34377683652 success，B12 release CI 343… |
| 09-10 01:50 | LOW 批次 B17–B21 全部合并 main（2026-09-10，main @ 01fe7ef） | 结果：5 个分支各自 CI 绿（34382562758 / 34382828537 / 34383229620 / 34383477456 / 34383775270）→ 集成分支 fix/audit-0909-inte… |
| 09-10 12:34 | 两分支合并前检查 + 64 字符边界修复 + 合并 main（2026-09-10 下午，main @ a3fbf44） | 检查结论：fix/mount-detail-name-hint（挂载编辑页路径提示 + 重名前置拦截）与 fix/composer-height-parity（输入框 56dp 对标 rikkahub）均未引入 bug。… |
| 09-11 10:59 | 三处改动审计：两次已合并 + fix/key-roulette-refresh（全过，分支待合并） | 审计结论：三处均无 bug。 |
| 09-12 00:25 | 聊天输入框"吞内容"根因：imeBurstBuffer 残留 stale 快照（2026-09-11 晚） | 用户报告：发 URL+key 时前面的描述被吞（间歇，"有时候"） |
| 09-12 00:25 | senseaudio"系统繁忙"实测 + TTFB 30s 通用缺口（2026-09-11 晚） | senseaudio 实测（api.senseaudio.cn，37 模型聚合站）：8 并发 → 全 HTTP 500 {"code":"internal","message":"服务繁忙，请稍后再试","ref_cod… |
| 09-12 00:39 | 三修复打包分支 fix/ttfb-thinktag-composer 推送+CI（2026-09-11 深夜） | 用户拍板：TTFB 直接调默认值（30s→90s）；think 标签+输入框"照常修，直接打包一起"（一分支三 commit） |
| 09-12 01:26 | fix/ttfb-thinktag-composer 合并收尾完成（main @ e1a0b08） | 合并：refspec 直推 ff（f4b6c4a..e1a0b08，无 force）→ ls-remote 复核 main = e1a0b08692ac4cf79eabff2b6c17abc56282fd2f |
| 09-13 14:40 | Legacy 渲染管线残留清单审计（2026-09-13，基线 daab66b） | 报告：/var/minis/shared/legacy-pipeline-audit-2026-09-13.md（扫描脚本 /tmp/legacy_scan{,2,3,4}.py） |
| 09-13 17:09 | ★ 交接：内存探针（memspike）非 shell 路径扩展 —— 分支待装包复现（2026-09-13 17:10） | 一句话状态：诊断分支 feat/diag-memspike-dualapp @ 73d741c（3 commits，已推，工作树干净）已做完并把探针接到非 shell 路径；CI run 34748974970 已派发（… |
| 09-13 20:46 | 内存加固三件套实现完成（分支 fix/memory-hardening-rss-stall，3 commits，CI #1498 success） | commits：a641e27 KaTeX 位图上限（H1）→ 45ce0c8 app 自身压力治理器（H2）→ c508896 挂死处置（用户追加要求）。沙箱 JVM 56/56 绿、仓库门禁 6/6、CI #1498… |
| 09-13 22:09 | 最近 5 commit 独立审计：干净，零改动（main @ c5088961） | 背景：用户要求扫最近 5 次修改。main 已推进到 c5088961——内存加固三件套（a641e27/45ce0c8/c508896）已由用户拍板合并（此前会话状态是"未合并待拍板"）。三件套是"实现+自测"出身、没… |
| 09-14 16:16 | 09-14 晚：截断工具调用守卫落地（fix/truncated-tool-call-guard @ fbc50cdd，CI run 1517）+ Eta 调研两条修正 | 改动（3 文件 +217 行，零删除，未合并待拍板）：①新建 ui/chat/TruncatedToolCallPolicy.kt（74 行纯函数：7 个截断 finish reason 别名 length/max_ou… |
| 09-14 16:29 | 09-14 截断工具调用守卫已合并 main = fbc50cdd（release CI run 1518 已触发） | 合并动作：CI 1517 success（job build）→ FF 前置验证（merge-base = origin/main = 13624962 = 分支直接祖先）→ refspec 直推 13624962..f… |
| 09-14 21:31 | 09-14 夜：U7 撤回（带触发条件）+ T4 conversation_history 完成（分支 CI 已绿） | U7 胶囊——撤回，不是"待做"（依据代码而非偏好）： |
| 09-15 18:04 | 09-15 晚：backlog §13/§14/§15 打包修复闭环（用户拍板合并）— main = 03ceed5f | 分支 fix/backlog-131415-0915 @ 03ceed5f（3 文件 +64/−8）→ 分支 CI 34953012745 success → 真机验证通过 → FF 合并 ad7c9e3..03ceed… |
| 09-15 22:44 | 09-15 夜：两支改动的归类排查（一类还是两类）+ 同病扫描 | 对象：fix/compact-swallows-queued-instruction @ decafa9a（CI 1549 success）、fix/tool-call-copy-suppress @ 7c1dddad（… |
| 09-16 00:23 | 09-16 凌晨：日志审计（09-15 窗口）— 23 次 silent_kill churn + 400 重试 + 混淆类名 | 复用工具：/var/minis/workspace/logaudit/{analyze,probe,deaths}.py（格式归一化 / 定向过滤 / 按 pid 分组看死亡上下文）。日志文件只剩 18:05 后窗口（重… |
| 09-16 01:02 | DSML 封套残留（第三次撞同族，已修，main = 711b6dd）：DeepSeek 原生 <｜DSML｜ invo | DSML 封套残留（第三次撞同族，已修，main = 711b6dd）：DeepSeek 原生 <｜DSML｜ invoke/parameter/calls> 封套整段漏成正文。判据确认：现有 ToolCallResid… |
| 09-19 09:31 | 09-19：offload 模型执行链路全量源码精读（压力测试会话，产出文档+图+13 项发现） | 用户要求不开代理、本对话内完成"看源码→写精确文档+mermaid 图→顺便查 bug"的压力测试。对象选了 sandbox/offload 模型执行链路（约 6000 行，main=c6d8d63f），逐行读完：Run… |
| 09-19 14:32 | 09-19：offload 审计第 9 棒完成（Ring 2 / P1 = `browser` 包 → F-98…F-104） | 状态：✅ 第 9 棒完成 · 只读，仓库 0 改动 · QA verify_all.sh 32/32 全绿 · 判据 verify_findings_9th.sh 73/73 · 生成器自对账 16 项 |
| 09-19 19:20 | 09-19：offload 审计第 15 棒完成（Ring 2 第八段 = `tools/` 14 文件 2,792 行 + `speech/` 7 文件 1,649 行 → F-153…F-160）· **Ring 2 至此 27,003 行 / 88 文件全部走完** | 状态：✅ 只读，仓库 0 改动（HEAD = c6d8d63f = 锚点，git status --porcelain 空）· 包闸门 verify_all.sh 67/67 · 判据 verify_findings_1… |
| 09-19 21:41 | 09-19：第 18 棒完成（`provider/` 第一轮）→ F-177 / F-178 / F-179（3 D）+ O-44…O-46（3 O） | F-177【D】★「取消」被记账成「流解析错误」（当日 14×，真因被丢弃 + 归类错） —— 链条四处： |
| 09-20 03:07 | 09-20 第 21 棒 · 并发线 D1 完成（`app/offload/` 顶层包 12 文件 / 2,291 行） | 范围：src/android/app/src/main/java/com/rikkaminis/app/offload/（顶层包，非已扫完的 sandbox/offload/）。基线 rev 99783703，独立 cl… |
| 09-20 05:11 | 09-20：FIX-7 批次修复完成（`app/offload` 顶层包 + sandbox 根文件，11 条 D）→ main = `2bbe60a` | 任务书：/var/minis/shared/offload-audit-0919/fix-tasks/FIX-7-approot-offload.md（11 条 D，来源线 B1 + D1） |
| 09-20 17:42 | 09-20 傍晚：用户报「调用大模型卡死」→ 根因 = 流式看门狗被阻塞调用饿死（P0 级，待修） | 用户现象：「刚刚出现了一次卡死，就是调用大模型的，很长时间都出不来」。 |
| 09-20 18:02 | 09-20 夜：卡死 bug 溯源 —— 不是「改 bug 改出来的」，是「架构改动移除了隐患的沉睡条件」 | 用户问：「怎么回事？什么时候这个又出现了？他是改Bug改出来的，还是原本就有的？」 |
| 09-20 18:12 | 09-20 夜：日志全量异常扫描（去污染口径）+ 两任务已派发 | 用户指令：①用子代理把修复任务安排出去 ②查还有没有类似问题，分析日志找异常，找到就继续往下分析。 |
| 09-20 18:45 | 09-20 夜（续）：日志扫描收官 → 共派发 5 个修复任务 | 用户指令：「如果发现了问题，并且能够确定的话就直接用子代理把任务派出去。」 |
| 09-20 19:02 | 09-20 深夜：流式看门狗饿死 bug 修复完成 → 分支 `fix/provider-stream-flowon` @ `13ea691c`（CI run 35506103286 success，**未合并**，用户要求） | 用户指令：「跑完不要合并」。已推送分支，CI 绿，分支保留。 |
| 09-20 19:17 | 09-20 夜：压缩 anchor 卡死任务 —— 任务书假说被证伪，真机制已坐实（n=65 完美分离） | 任务书假说 H1：「startIdx > 0（已压缩过一次）⇒ resolveBudgetAnchorIdx 恒返回 -1」。 |
| 09-20 19:27 | 09-20 深夜：F-169「错误快照缺触发行」修复完成 → 分支 `fix/error-snapshot-missing-trigger` @ `f6a50a2b`（CI 35507084738 绿，**按用户指令不合并**） | 任务书数字被我用严格判据修正（诊断方向不变，数字更准）：任务书写「14/41 快照不含 [ERROR]」，实际按严格判据（是否含触发它的那一条）是 17/41（41%）。差异 = 3 个快照「含 ERROR 但不是触发行… |
| 09-20 19:31 | 09-20 夜：上下文超限后 agent loop 空重试死循环 → 修复分支 `fix/context-exhausted-loop` @ `55703df9`（CI 绿，**未合并**，用户要求等统一处理） | 任务书：/var/minis/shared/hang-0920/session-task-ctxloop.md（要求：先复核四点 → 三方案分析 → 实施推荐 → JVM 复现 + 反向对照 + scan + CI → … |
| 09-20 19:41 | 09-20 夜：压缩 anchor 卡死任务 —— 复核完成，任务书假说被证伪（仓库 0 改动，按 §2 停止） | 任务书路径：/var/minis/shared/hang-0920/session-task-anchor.md。报告：/var/minis/shared/hang-0920/anchor-exp/REVIEW.md。 |
| 09-20 20:20 | 09-20 夜：F-177 修复完成（分支 `fix/cancellation-accounting` @ `c5aedaa6`，CI 35509454695 success，**未合并**） | 任务书：/var/minis/shared/hang-0920/session-task-f177.md · 报告：/var/minis/shared/hang-0920/F177-RESULT.md · 实验：f177… |
| 09-21 09:30 | 09-21 上午：近三天改动复审（c6d8d63f → 402d25d2）→ 合并质量高，发现 F-134 本批漏修 | 任务：用户「检查一下最近三天的修改有没有引入问题之类的」。 |
| 09-21 16:09 | 09-21 晚：同类应用 issue 核实（1106 条 / 7 仓库）→ 主发现「非视觉模型图片门只覆盖 1/3 provider」 | 用户指令：「查一下与这个应用同类型的应用（omnibot、operit 这类）的 issue，看他们的问题在这个应用里是否同样有」 |
| 09-21 17:03 | 09-21 晚：零 chunk 取消修复 — 中档完成，分支 `fix/zero-chunk-cancel-and-wait-state` @ `eabff9c`（CI 35579684513 全绿） | 用户指令：做中档（①取消在所有阶段生效 + ③界面区分"思考中/等网络"）；并提醒 main 已推进（实际未推进，文档推成了分支 docs/dev-history-0921 @ 1114170a，只改 docs/dev-… |
| 09-21 19:42 | 09-21 晚：S2 派发任务完成 — provider 层两条（§27a 图片门 + §24b TTFB 统一） | 分支 fix/s2-provider-layer @ eca54225b4b93df0f3562a61926e22ac496edd5f，CI run 35593738205 success，head_sha 逐字符一致，… |

## 6. 思考 / 推理泄漏

**跨度** 2026-08-08 ～ 2026-09-22 · **57 条** · **状态** 已闭环（09-12 打包合并；同类新方言需重新进表）

**叙事**：08-14 思考折叠框缺失（模型组中转站场景）→ 08-18 「思考跑进正文」+ 断流诊断 → 08-20 thinking 级别无效 → 09-04 thinking-rules-port + 思考字段决策键 → 09-05 thinking-gap-close（审计出 2 HIGH）→ 09-11/12 **think 泄漏实锤：中转站 `<think>` 标签不在解析表**。核心难点：泄漏来自**上游 provider 的方言差异**，不是我们自己的解析 bug。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-08 15:07 | ✅ compact/thinking 从斜杠提到顶层菜单 — 已合并 main（2026-08-08） | 分支 feat/menu-compact-thinking @5a3640d（13 文件 +152/-54）： |
| 08-13 15:29 | 思考+工具两条杠移到回答下方（fix/reorder-think-tool-bottom → main cdc72f4） | 用户需求：AI 生成回答时，thinking 折叠条和 tool-run 折叠条原来在回答文本上方（model block 顺序 thinking→tool_use→text 导致），回答太长时自动滚动把两条杠顶出视口。… |
| 08-14 20:50 | 思考折叠框缺失根因确认（模型组中转站场景，2026-08-14 晚） | 现象：只有 A 类对话框（模型组，成员 fallback 切换）没有 Deep Thinking 折叠框，思考变普通正文；B/C/D 正常。 |
| 08-14 21:43 | 思考折叠框修复完成：已合并 main（5c97940，2026-08-14 晚） | 任务来源：交接文档 /var/minis/shared/thinking-fold-fix-handover.md（A 类对话框经中转站无折叠框）。修复点 100% 在 OpenAIProvider.kt。 |
| 08-17 01:37 | 打断后"旧工具一直转/thinking 残留"修复施工中（2026-08-17） | 用户复现的 bug 根因已定位：流式/工具回合中发新消息，旧 assistant 实际状态已收敛到终态（tool SUCCESS/FAILED/CANCELLED、isStreaming=false），但 StableC… |
| 08-17 02:16 | 打断后"thinking 残留"/"tool 停但 thinking 还在"根因排查（2026-08-17 续） | 用户反馈：账本修复（fix/ledger-status-sync 已合 main faa1905）真机验证"tool 停了但 thinking 还在"。 |
| 08-17 06:25 | 打断后 thinking/工具残留双路径根因诊断（2026-08-17 续） | 用户真机装 38b2960 验证 de18d25 的账本修复（activeAssistantIds 登记 + isLiveAssistant 扩大）仍显示：打断位置上方仍有旧"正在思考"残留。且用户问"打断时正在执行的工… |
| 08-17 08:08 | 打断残留修复终极根因：prune 抢先 + rowsTouched 门控 + thinking 折叠信号（2026-08-17 系统性收口） | 用户关键观察（扭转方向，价值极高）：切对话再切回 → 残留消失 → 证明 canonical 数据从头到尾正确，问题纯在"已发布行未刷新 UI"。用户最后拍板：不追求"消失"，而是 UX——打断后的旧回合应呈现"已停止"… |
| 08-18 07:24 | RikkaMinis 思考折叠问题诊断（2026-08-18 下午，用户现象：思考跑进正文） | 用户偏好（重要，与 rikkahub 不同） |
| 08-18 07:38 | RikkaMinis 思考折叠修复进行中（2026-08-18，分支 fix/thinking-split-and-leak） | 用户确认：思考/工具默认都折叠、点才展开（明确不要 rikkahub 流式自动 Preview）。思考强度调到最大。现象①思考有时露有时藏 ②思考内容跑到正文。倾向方案 B（thinking 拆独立成行）。 |
| 08-18 08:27 | ✅ B（thinking 拆独立）完整闭环（2026-08-18，main ddebe55） | main release CI run 32082121920 conclusion=success（注：ci-bridge worker 显示滞后，实际已完成 success） |
| 08-18 09:22 | 思考跑正文（任务 A）闭环状态（2026-08-18 09:25） | e0d9c62（THINK_TAG_FORMATS 扩充 <Thought>/<analysis> + 大小写不敏感扫描修复）已合并 main。 |
| 08-18 10:29 | ✅ thinking 漏进正文 修复闭环（2026-08-18，main 500c5fa） | 根因（代码确证，非 UI 层）：泄漏发生在 Provider 归类层（思考被发成 Text），不是折叠 UI。折叠 UI（ChatFlatItems 只对 kind=="thinking" 发 AssistantThin… |
| 08-20 22:52 | thinking 级别无效 + 工具卡"正在调用"卡死修复（2026-08-20 收尾） | 任务文件：/var/minis/shared/task-thinking-level-and-ui-stuck.md |
| 08-20 23:19 | thinking 级别无效 + 工具卡"正在调用"卡死修复（2026-08-20 收尾） |  |
| 08-20 23:21 | 真机验收确认（2026-08-20 用户反馈） | 第 1 项（对话内 thinking 级别调节，supportsReasoning=null 模型）:用户已验证生效 ✅ |
| 08-22 12:54 | RikkaMinis 官方对比自述文档完成（2026-08-22） | 生成了 /var/minis/workspace/RikkaMinis-官方对比自述.md，把 fork 相对官方 OpenMinis 的全部差异按三类归档： |
| 08-24 18:01 | 思考模式修复已合并 main + CI 工具化收口（2026-08-24 晚） | 思考模式重开入口修复（commit 6ea8c1b）已 ff 合并主号 main（108c5a2→6ea8c1b）并推送。改动：ChatScreen.kt + ChatThinkingBadgeUI.kt，徽章显示条件从… |
| 08-25 19:53 | 首块超时掐断思考模型 bug 已修复合并 main（3eb1785，2026-08-25） | 用户报：思考型模型（reasoning/thinking）会在固定时间被掐断，报错 provider produced no first chunk within 45000ms (hadChunks=false)，后台… |
| 08-26 02:14 | 继承 generation 超时墙系统性修复已合 main（7f68752，2026-08-26） | 用户一手现象：思考关闭 + 写入较长内容（如施工方案）写到后期突然报 provider produced no first chunk within 45000ms (hadChunks=false)。 |
| 08-27 15:26 | tokenrhythm.studio deepseek-v4 思考模式打不开 — 根因定位（2026-08-27） | 用户现象：tokenrhythm.studio 这个中转（key sk_tr_...，OpenAI 兼容 /v1/chat/completions + Anthropic /v1/messages）配的 deepseek… |
| 08-27 16:19 | tokenrhythm deepseek-v4 思考模式修复已合并 main（2026-08-27 收尾） | 修复提交：cb434c5 fix(provider): route third-party deepseek-v4 relays through standard reasoning_effort（已 ff 合并 mai… |
| 09-02 08:53 | thinking-OFF turn 崩溃修复（2026-09-02，commit de2dca7d 合并 main） | 症状：关闭思考模式后无法使用——每次请求 3 次 transient retry + 模型 failover 链全灭，报 unknown t0 value: （t0=R8 混淆的 ThinkingLevel 枚举类名，值… |
| 09-04 13:17 | 思考字段决策键根治（tokenrhythm qwen 报错，2026-09-04） | 用户场景：tokenrhythm.studio + qwen3.8-max，开思考就报错（低档也报错），关思考没事。RikkaHub 不报错。 |
| 09-04 21:23 | feat/thinking-rules-port 分支审计（2026-09-04） | 范围：main(7aea092d)→f7865b2b，2 commits +3440/−217（thinking 规则引擎 port + Phase 2 自定义规则）。CI f7865b2b run 3387392799… |
| 09-04 21:29 | thinking-rules-port 分支待合并（2026-09-04，CI f7865b2b run 33873927997 success） |  |
| 09-04 21:35 | thinking-rules-port 分支待合并（2026-09-04，CI f7865b2b run 33873927997 success） | 用户开 bug-audit 会话处理该分支的 bug hunt；本会话收尾，未做 ff 合并 main。 |
| 09-04 21:53 | thinking-rules-port 分支已合并 main @ 1ba1310e（2026-09-04 晚） | D3 修复闭环：nested 分支补 clampEffortForModel(wireEffort(ctx.level), lid)（commit 1ba1310e，+47/−6），新增 2 条回归测试（openrout… |
| 09-05 12:49 | thinking-gap-close-0905 分支完成，交接给下一会话（2026-09-05） | 分支 feat/thinking-gap-close-0905（HEAD f134fd02）：AUTO 档 + golden/Gemini guard + provider knobs（custom headers/bo… |
| 09-05 13:23 | thinking-gap-close-0905 分支审计（f134fd02，未合并）——2 HIGH 实锤 | 仓库 /tmp/rikka-clone（HEAD feat/thinking-gap-close-0905）。+2247/−76，47 文件。审计报告见本会话回复。 |
| 09-05 14:05 | thinking-gap-close 审计修复已合并 main @ 392783a0（2026-09-05） | 分支 feat/thinking-gap-close-0905 审计后修复（commit 392783a0，16 文件 +302/−30），分支 CI 33948346008 success（head_sha 核对一致）… |
| 09-06 15:53 | 商汤思考档 400 双 bug 修复闭环（2026-09-06，main @ 6b95929） | 用户报障：买的 API key（商汤 .sensenova.cn），思考档开到"超高"报错、中低档正常；且按指引添加的自定义规则（CustomPath reasoning_effort=xhigh）保存成功但聊天仍 40… |
| 09-06 16:07 | 主号→小号同步 6b95929（2026-09-06 16:0x，merge 9047a8ef） | 同步内容：主号 main 6b95929（商汤思考档 400 双 bug 修复：worker thinking rules 跨进程恢复 + sensenova effort enum clamp，7 文件 +310）→ … |
| 09-07 16:08 | fix/worker-reasoning-content-roundtrip 合并 main（2026-09-07，main @ 742bf77e） | 用户报告：模型组里 danfeng 供应商 deepseek-v4-flash/pro 报 Provider error:[400] The 'reasoning_content' in the thinking mod… |
| 09-07 21:54 | 规则体系对照复杂度五纪律的复查（2026-09-07，待拍板 2 条） | 用户贴来一段复杂度管理五纪律（地板之上不增一克/边要收费/纠缠最小化/机制可压缩/整体保持可读），问我们的规则体系是否需要吸收。复查结论： |
| 09-11 11:35 | 思考泄漏（thinking leaked into body）成因调查（2026-09-11） | 用户观察：模型组里混杂各供应商模型，偶发思考内容漏进正文；自测后判断"主要是上游供应商没完善，只对特定供应商的模型出现"。 |
| 09-11 11:38 | 思考泄漏取证：中转站自报 reasoning_tokens 但零 reasoning 字段（2026-09-11，用户一线样本） | 用户指出"当前这个会话本身就是典型案例"——他通过第三方中转站调用当前模型，且该问题只在这个模型上出现。 |
| 09-12 00:25 | think 泄漏根因实锤：中转站 `<think>` 标签不在解析表（2026-09-11 晚） | 用户报告"思考泄漏是应用本身的问题"（rikkahub 同站正常）→ 直接 curl 中转站实锤 |
| 09-15 12:07 | 热路径同类扫描（用户问"还有没有"）：新发现 §11 ThinkingRulesSection UI 直接 runBlo | 热路径同类扫描（用户问"还有没有"）：新发现 §11 ThinkingRulesSection UI 直接 runBlocking Room（4 个 remember 块组合期 4 次 DB 读——thinkingRul… |
| 09-18 12:32 | 09-18：诊断「[400] The content[].thinking in the thinking mode must be passed back to the API」 | 现象：今天 4-5 次（11:43:34 / 11:43:47 / 11:50:30 / 11:57:34 / 11:59:16），全部 session 2f5dae85、全部 model=deepseek-v4-fla… |
| 09-18 13:13 | 09-18：修复 DeepSeek V4 思考回传 400（分支 fix/deepseek-thinking-echo @ f6ab4eb2） | 改动（9 文件 +700/−70）： |
| 09-18 14:05 | 09-18：DeepSeek V4 思考回传 400 追查（未解决，已按用户决定搁置）+ 分支合并 main | 结论先行：装了修复版（1.0.0+1646 / cfa48ec9）后仍复现 → 修复不够。用户判定该故障有随机性，决定暂不继续修，仅合并分支。 |
| 09-18 14:12 | 09-18：deepseek-thinking-echo 分支独立审查通过 | main = aff89798（c82bbf3e fix + aff89798 test）已推送，CI #35313387036 in_progress（用户拍板不等）。 |
| 09-19 21:41 | 09-19：第 18 棒完成（`provider/` 第一轮）→ F-177 / F-178 / F-179（3 D）+ O-44…O-46（3 O） | F-177【D】★「取消」被记账成「流解析错误」（当日 14×，真因被丢弃 + 归类错） —— 链条四处： |
| 09-20 00:56 | 09-20 凌晨：offload 审计并发线 A1 完成（`provider/openai/` 5 文件 3,559 行 · 第 21 棒） | 产出：/var/minis/shared/offload-audit-0919/wave2-a1/ —— report.md(35KB) · ledger-a1.json(21 条：D=3 · O=18，其中负结果 N-… |
| 09-20 01:47 | 09-20 凌晨：offload 审计 WAVE-2 并发线 A4 完成（`provider/` 杂项 + `ModelsDevApi`） | 产出（/var/minis/shared/offload-audit-0919/wave2-a4/）：report.md(34.9KB) · ledger-a4.json · ledger-section.md（可直接并… |
| 09-20 02:26 | 09-20 凌晨：offload 审计第 21 棒 **并发线 A3** 完成（`provider/thinking/` + `provider/voice/`） | 产出：/var/minis/shared/offload-audit-0919/wave2-a3/（report.md + ledger-a3.json + verify_a3.sh + exp_a3/）。 |
| 09-20 05:35 | 09-20：修复批次 FIX-2-thinking-voice 完成（6 条，分支已推 + CI 绿） | 批次：FIX-2-thinking-voice · 分支 fix/thinking-voice-layer · commit 0012517 · 基线 afa404b |
| 09-20 08:37 | ↳ 09-20：修复批 FIX-3-chat-state 完成（chat 状态机与并发 · 7 条 + 跨批补丁 4 处） | 分支 fix/chat-state-machine · 基线 afa404b（父提交已核）· commit 3b3c474 + 9dfe54f · CI #1670 → #1682 两次均 success · diff … |
| 09-20 18:12 | 09-20 夜：日志全量异常扫描（去污染口径）+ 两任务已派发 | 用户指令：①用子代理把修复任务安排出去 ②查还有没有类似问题，分析日志找异常，找到就继续往下分析。 |
| 09-20 20:02 | 09-20 夜：思考期间输入框卡顿 —— 根因 = ThinkingDelta 分支缺引擎级节流（分支 `fix/thinking-delta-main-thread-throttle` @ 37e18fd2，**按用户要求不合并**） | 用户报告：「大模型思考时性能消耗很大，这时用输入框明显卡顿；尤其在有比较大上下文的长对话中」。 |
| 09-20 23:51 | 09-20 深夜：T2 线（regression-audit-0920）取证收口 —— 任务书前提被证伪，按修复门纪律不动手 | 结论：/var/minis/shared/regression-audit-0920/tasks/T2-degrade-restore.md 的四条事实前提全部不成立。产出 /var/minis/shared/regre… |
| 09-21 15:16 | 09-21 晚：用户报告「思考卡住」→ 最小复现实验定位到「零 chunk 窗口」守卫盲区 | 用户症状：「遇到几次卡着很久不动，切换代理之后就解决了」——切换代理即恢复 ⇒ 卡点在 TCP 链路，不是模型。 |
| 09-22 10:02 | 09-22：思考强度（Thinking Level）功能审计 → **确认是 bug，4 处缺陷** | 用户报告：「调思考强度只有中、低、高以及自动；用模型组时明明调到最高，但会出现关了的情况」 |
| 09-22 11:02 | 09-22：思考强度 B1–B4 修复完成 → 分支 `fix/thinking-level-ui-truth` @ `ae5517cc`（CI 绿，**按停止节点停在分支上**） | CI：run 35680380432 success · head_sha ae5517cc… 与本地 HEAD 逐字符一致 · 23 步全 success · Publish to Releases skipped（m… |
| 09-22 11:31 | 09-22：思考强度会话交接（接手者必读） | 交接产物：/var/minis/shared/thinking-audit-0922/handoff/HANDOFF.md（8 章节，含待决问题 + 复跑命令 + 纪律） |
| 09-22 13:07 | 09-22：思考强度缺陷③ 判定推翻 —— `none`/`minimal` 无 UI 入口 = **P3 刻意不修**（用户拍板） | 背景：交接任务说「只把那个缺陷修了」。接手后取证发现，交接文档指定的「③ 真缺陷（P2）」判错了，且给的修法不可执行。用户听完判据后拍板：不修。 |

## 7. 上下文压缩 / 记忆 / 预算

**跨度** 2026-08-06 ～ 2026-09-21 · **107 条** · **状态** 稳定（09-12 差距清单收敛：D1 攒着、D3/D4/D5 归档不吸收）

**叙事**：08-05 RAG v1 实验 → 08-06 经验记忆模块（实施→审查→修复→**08-07 整体摘除**）→ 08-12 上下文压缩引擎 T5 → 08-14 切片无清洗 + summary 注入 tool_result（400 报错）→ 08-16 动态预算 → 08-18 上下文窗口来源治理 → 08-31 memory facts + 语义索引 → 09-12 A1 Prompt Cache 调查（**结论：本地已是上游超集，无需移植**）。一条「先做、再摘、再重做」的螺旋线。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-06 00:39 | 记忆体/经验引擎概念 — 小号独立应用方向（2026-08-06 灵感记录，暂缓） | 用户在思考"以 RAG/记忆为核心的东西"时有了关键洞察，与 RAG v1 不同，先记下来以后再做： |
| 08-06 08:48 | RikkaMinis 经验记忆模块 — 实施中（2026-08-06） | 主账号（OWNER/RikkaMinis）经验记忆（Episodic Memory）模块，分支 feat/experience-memory，commit 4e7b5a6（+826 行，18 文件）。 |
| 08-06 09:46 | RikkaMinis — 经验记忆查看功能（行内展开，方案 A）已实现待验证（2026-08-06） | 用户反馈：经验记忆模块只能删不能看（记忆=episodes.jsonl）。本次按方案 A（行内展开）实现，分支 feat/episode-viewer，commit b25a806，CI run 31063658446 … |
| 08-06 10:15 | RikkaMinis — 经验记忆详情 dialog 滚动修复（2026-08-06，commit 5d20e49） | 用户报：经验详情 dialog 里长 reply 内容"下面还有但滑不动"。真机验证后修复。 |
| 08-06 10:15 | RikkaMinis — 经验记忆详情 dialog 滚动修复详情（2026-08-06，commit 5d20e49，feat/episode-viewer） | 分支 feat/episode-viewer 第二个提交 5d20e49（前一个 b25a806），CI run 31065068027。 |
| 08-06 10:20 | RikkaMinis 经验记忆 TOCTOU bug — 发现与修复（2026-08-06，分支 fix/episode-feedback-tocou） | 背景 |
| 08-06 10:36 | RikkaMinis 经验记忆滚动修复合并 — 编译失败与修复（2026-08-06 下午） | 事件 |
| 08-06 10:55 | RikkaMinis 经验记忆系统审查结论（87f69eb） | 系统审查发现：①EpisodeMemoryStore 共享 JSONL 无同步，而应用允许 5 个会话并发，read-modify-write 会丢写，行号反馈跨会话失效，clear 也可被在途写回；②Hook A/B … |
| 08-06 11:03 | RikkaMinis 经验记忆修复实施方案（交接版，基线 87f69eb） | 核心架构 |
| 08-06 11:06 | RikkaMinis 经验记忆修复完整方案（可直接开工版，基线 main@87f69eb） | 目标：让经验的检索/验证/回写在多会话并发、排队切换、取消、异常、清空下保持同一任务语义。原则：一次经验交换必须有稳定身份和明确生命周期，禁止再用"最后一条消息"和文件行号猜测。 |
| 08-06 11:56 | RikkaMinis 经验记忆修复实施完成（2026-08-06，4 commit 已推 main 61ea3a2） | 基线 87f69eb → 61ea3a2，4 个 commit 全部推送 main，CI run 31069812286 验证中。 |
| 08-06 12:03 | RikkaMinis 经验记忆修复 — 交接标记（2026-08-06 12:00 UTC+8） | 4 个 commit + 1 个法语修复已推 main（3eaff17），CI run 31070074525 验证中。 |
| 08-06 16:17 | 2026-08-06 会话总结（交接用） | 已完成 |
| 08-06 17:46 | 会话状态核实（2026-08-06 晚，续 pinned-providers） | 后台构建（用户说"一个在打包构建中"） |
| 08-06 18:40 | RikkaMinis 记忆页改造 — feat/memory-management-optimize（2026-08-06 晚，CI success run 31093633313，commit a26eaeb） | 用户真实需求（对齐后） |
| 08-07 17:32 | 多个对话框并行处理中（2026-08-07）— 三条活跃工作线 | 用户提示：其他对话框正在处理事情，不要冲突/干扰。当前并行在做： |
| 08-07 17:43 | RikkaMinis — 经验记忆模块已摘除（分支 revert/experience-memory, commit 3a1d2b6） | 用户判定经验记忆（episodic memory）是噪音，要求连根摘除。已在 /tmp/rikkaminis-full 从 origin/main(de2b938) 起分支 revert/experience-memor… |
| 08-07 17:50 | RikkaMinis 四分支合并进 main（2026-08-07 09:50 已完成推送） | 用户要求"把最近的更改合并进 main"。处理了 4 个待合并分支，全部无冲突 merge（ort 策略自动合并），HEAD = 63c4c21 已推送，CI run 自动触发（queued）。 |
| 08-07 17:55 | 四分支合并后 CI 失败修复 — MemoryManagementScreen 未闭合注释（2026-08-07 17:5x） | 事件 |
| 08-07 18:16 | RikkaMinis 稳定性回归审计 — HEAD 75cd067（2026-08-07） | 用户想确认当前版本是否有回归、过去修的问题会不会复发。做了系统性回归审计（代码 + CI + release 三重验证）。 |
| 08-08 00:59 | 记忆模块 5 项优化完成（2026-08-08，分支 fix/memory-module-optimize） | 施工内容（commit fe501f3 in fix/memory-module-optimize，已 push 远端） |
| 08-08 01:01 | 统筹合并待办清单（2026-08-08，记忆模块已就绪） | 当前四个相关分支状态 |
| 08-08 01:12 | RikkaMinis 提供商管理优化施工 — Phase 1+2 完成（2026-08-08 凌晨） | 分支 |
| 08-08 01:27 | 统筹合并完成 — provider/memory/token 三分支合 main + CI 修复（2026-08-08） | 背景 |
| 08-08 01:48 | ↳ 未合并分支已全部合并到 main（2026-08-08） | feat/logging-module-optimize (092ae88) — 日志模块优化：降低 flush 节奏、修日志读取 OOM、容量上限、i18n crash 区块 → ff 合并 main |
| 08-08 09:46 | RikkaMinis main 分支完整合并梳理（2026-08-08） | 远程状态 |
| 08-08 15:07 | ✅ compact/thinking 从斜杠提到顶层菜单 — 已合并 main（2026-08-08） | 分支 feat/menu-compact-thinking @5a3640d（13 文件 +152/-54）： |
| 08-09 15:33 | 模型组上下文限制硬生效 — feat/context-limit-enforce（CI run 31301106282 success） | 用户问题：设模型组 contextLimitTokens=128K，Token Usage 面板 Context Used 仍超 128K（能到 200K+）。 |
| 08-12 15:13 | 并行任务清单已建立（2026-08-12）—— 多会话协作模式 | 用户要求把 OmniBot 借鉴点变成待办清单，多开对话各自领任务并行开发。清单文件： |
| 08-12 15:51 | ↳ T3 失败学习自动化钩子完成（feat/failure-auto-log → main 667f17d） | 改动： |
| 08-12 15:54 | T5 上下文压缩引擎完成（feat/context-compactor → main bbf8ab1，CI 绿） | 改动：新文件 conversation/ContextCompactor.kt（纯逻辑决策引擎）+ ChatViewModel 挂载 + 单测。 |
| 08-12 15:55 | ↳ T3 失败学习自动化钩子完成（feat/failure-auto-log → main 667f17d） | 2026-08-13 |
| 08-13 03:30 | ↳ 任务 B：核心文件补测试 ✅ 完成（2026-08-13） | 分支 fix/core-file-tests → rebase 到 main（f76e5d1）后合并推送，分支已删（本地+远端） |
| 08-14 12:43 | rikkaminis-dev-history.md 从记忆重建（2026-08-14 12:4x） | 用户发现笔记挂载目录 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md（应用修改的日志合并导出）"被改出问题"（原文：被应用改动出了毛病；检查发现 2 处时间戳乱序，用户决定不走修复… |
| 08-14 18:49 | 400 "tool must be a response to preceding tool_calls" 根因 + 修复（fix/compact-slice-tool-pairing，2026-08-14） | 用户报告：长对话出现 Provider error:[400] Messages with role 'tool' must be a response to a preceding message with 'tool… |
| 08-14 18:58 | 400 切片 bug 修复已合并 main + 真机验证通过（2026-08-14） | 用户真机验证：下载最新 release APK 后测试，问题解决。 |
| 08-14 20:04 | 修复 v1 切片无清洗 + summary 注入 tool_result（fix/compact-slice-sanitize-v1-summary-toolresult → main 1cdb660，2026-08-14） | 背景：用户问"400 tool must be response to preceding tool_calls 是否还有其他同类错误"，对消息构造管线做全量审计，发现 4 个盲区，修了核心 2 个。 |
| 08-15 00:34 | 模块核心假设分析完成（2026-08-15） | 基于 RikkaMinis 源码（~146K 行 Kotlin，31 子包，405 文件）反推了 16 个模块的核心假设，报告已写入 /var/minis/workspace/rikkaminis-core-assump… |
| 08-15 05:27 | ↳ T0 基线契约完成：已合并 main（e6f2be32，2026-08-15） | 任务：RikkaMinis 平衡点施工蓝图 T0（冻结基线契约，不改生产代码）。 |
| 08-15 08:51 | Memory-pressure-gate 施工中（fix/memory-pressure-gate，2026-08-15 上午） | 来源：08:25-08:27 三次 OOM（pthread_create 1MB 栈失败 = native 内存耗尽）。现有 ExecutionCoordinator P2-app-native-oom 只监控 Debu… |
| 08-15 09:03 | Memory-pressure-gate 完成 — 已合并 main（1c4bd45，2026-08-15 上午） | 改动（commit 1c4bd45，5 文件 +321，全在 service/ + MinisApp 下）： |
| 08-15 19:50 | 决策：不做"自动接力方案（无感分卷）"（2026-08-15） | 用户提出"自动接力/无感分卷"（长会话触碰阈值 → 自动总结 → 切新会话 Part 2 带 summary 满血启动）后，自己收敛到"不要做"。评估确认其直觉正确： |
| 08-15 19:58 | ↳ 字体大小设置四 bug 修复完成（2026-08-15） | 用户要求检查 Settings → Appearance → Font Size 是否有 bug，发现 4 个问题，全部在一个分支修完： |
| 08-16 22:42 | fix/memory-dynamic-budget 编译失败任务交接 | commit 1d1ac2290efa 编译失败（CI run 31951519857），根因：动态预算常量定义在 object ExecutionCoordinator 内部作为 private const val，但… |
| 08-16 23:13 | 交接完成：新会话接收 fix/memory-dynamic-budget（2026-08-16 晚） | 交接文件：/var/minis/shared/fix-memory-dynamic-budget-handover.md（已更新含用户最终交代）。 |
| 08-16 23:35 | fix/memory-dynamic-budget 合并 main 完成（d644972，2026-08-16 晚） | 任务一（合并 fix/memory-dynamic-budget）已闭环： |
| 08-18 01:56 | 上下文窗口来源治理 + 组为准 + iOS-parity 上下文已满弹窗(fix/context-window-sources) | 用户痛点(A + C): |
| 08-18 02:49 | ✅ 上下文窗口治理闭环完成(fix/context-window-sources → main a0b03e8) | 发布状态:分支 CI run 32052515965 success(scan gate + 1653+ 单测 + APK 构建全绿)→ ff 合并 main(70bb88b..a0b03e8)→ main releas… |
| 08-18 09:25 | B1 任务包：调高 MemoryPressureGate 阈值（2026-08-18 09:30） | 背景 |
| 08-21 21:02 | Phase 4 fix/proot-child-memory-guard 施工（会话 B，2026-08-21 晚） | ⚠️ 共享工作树事故（重要协作教训）：/tmp/rb 是共享工作树，会话 A（Phase 2 fix/modelservice-terminal-protocol）中途 checkout 切走了分支，导致会话 B 的工作… |
| 08-22 12:54 | RikkaMinis 官方对比自述文档完成（2026-08-22） | 生成了 /var/minis/workspace/RikkaMinis-官方对比自述.md，把 fork 相对官方 OpenMinis 的全部差异按三类归档： |
| 08-23 14:26 | 会话 E 完成：记忆/压缩/宏/子代理/失败钩子 压测（2026-08-23） | 方法：沙箱装 OpenJDK17 + kotlinc 1.9.24，直接编译 app 自身纯逻辑源码（MemoryRollupEngine/ToolFailureHook/ContextCompactor/MemoryR… |
| 08-25 16:12 | 只读记忆查看器懒加载修复已完成（分支未合 main） | 施工方案 /var/minis/shared/memory-file-lazy-render/施工方案.md 已执行。基于 main@5f92949 创建并推送分支 fix/memory-file-lazy-render… |
| 08-25 16:15 | 只读记忆查看器懒加载修复已合并 main（62a3a7d） | 用户拍板合并。fix/memory-file-lazy-render 已通过 ff 合并并推送主号 main：5f92949→62a3a7d。严格只改 MemoryDetailScreens.kt；分支 CI run 3… |
| 08-25 22:45 | ⚠️ 记忆更正：D 任务已完成（此前误标）— 2026-08-25 交叉验证 | 更正：此前记忆「D 任务开工准备完成、D 可开工」是错的。用户第一手指出「D 做完了」，交叉验证（git ls-remote + 分支 + CI API）确凿证实： |
| 08-27 20:21 | 停止卡顿 + 发消息卡顿根因定位与修复（2026-08-27，分支 fix/stop-lag-and-send-prompt-bloat） | 用户现象两个：①长任务后期点「停止」卡 1~2 秒才真正停；②稍长对话发消息一开始卡顿（之前缓解过但不够）。 |
| 08-31 13:03 | 吸收开源 Agent 生态三件套之 ①③ 落地（2026-08-31） | 背景：用户给了 Mem0/LangGraph/E2B/Langfuse/LiteLLM 等开源项目清单，评估后拍板吸收三个增量：①记忆时间衰减 ③trace 回放评估（本会话直接做）；②实体/偏好结构化抽取（单独任务，未… |
| 08-31 13:28 | 任务② memory facts 派发准备完成（2026-08-31） | 两决策点用户拍板：A=写入时 agent 自声明（memory_write 加可选 facts 参数，零额外 LLM 调用）+ rollup 时机文案提示回填（v1 不自动化）；B=v1 不进 SyncMerge（SYN… |
| 08-31 14:44 | facts 任务收口：memory-facts + litellm-cost-json 双分支合并 main（2026-08-31） | main = c87df78b（ea6b9213 → fbe888e7[litellm] → c87df78b[memory-facts]），release CI run 33363933066 success（head… |
| 08-31 16:57 | 砍除 USD 成本估算 + 修复 facts 空时间戳（2026-08-31，commit b4e166fb） | 背景：用户真机验证「Usage 页费用显示有的有、有的没有」。定位：价格目录 model_prices.json（44 键）只解析到 1148 个实际 model_id 中的 75 个，其余 1073 个中转站/代理模型… |
| 08-31 17:17 | 第二轮开源清单评估：12 项目裁定，A/B/C 待拍板 | 用户给了第二份 Agent 生态开源清单（Mem0/Zep/Chroma/LangGraph/AutoGen/CrewAI/E2B/Composio/Open Interpreter/Langfuse/Phoenix/L… |
| 08-31 18:02 | 语义索引增量重建 + facts 种子回填（2026-08-31 下午） | 背景：用户问「现在能做什么」，定位到瓶颈是 facts 生产量（上线 24h 只有 1 条）。做了两件事 + 一次事故复盘。 |
| 08-31 18:49 | B 方案落地：facts 查询相关检索（2026-08-31，commit d76354d3 合并 main） | 背景：用户拍板直接在本会话做 B（检索信号融合，Mem0 V3 概念启发），不必拆任务书。改动小、纯 Kotlin JVM 可测。 |
| 08-31 21:36 | facts 查询标点假命中修复 + 双分支合并（2026-08-31 晚） | 背景：用户要验证"facts 检索是否起效果"（本地事实库根据输入匹配→注入提示词那条链路，不是 HF 语义索引）。用用户 5 句真实输入回测（jieba 模拟生产分词链路），发现 7/10 命中、top1 基本对，但暴… |
| 09-01 16:54 | place-storm 钳位修复汇入主号收口（2026-09-01） | 用户拍板：小号 70f927d1 的 SIMPLE_FOLLOW 钳位守卫修复已在 lab 真机验证（日志 minis-2026-09-01__3_.log 全绿），汇入主号。 |
| 09-03 01:08 | FE-5 第五批第一簇完成 + 第二簇交接（2026-09-03） | 进度：ChatViewModel 12338 → 6499（累计 −5839，约 47%）。目标 3500-4000，还差 ~2500-3000。 |
| 09-03 07:28 | ↳ FE-5 第五批第二簇完成（2026-09-03） | 交付：分支 refactor/fe5-batch6-cluster2，commit 9b8a0a03 + 8c451f7f + 5236b8bd，CI run 33694126769 success，ff 合并 main… |
| 09-04 00:19 | 三问题修复收尾（2026-09-03 晚，全部合并 main @ 73925e9） | 用户提出三个问题，全部完成并验证： |
| 09-05 16:46 | 用户报"最近经常遇到沙盒重置、kotlinc 等工具不见了"。调查结论（三源验证：文件时间戳 + 日志 + 语义记忆）： | 用户报"最近经常遇到沙盒重置、kotlinc 等工具不见了"。调查结论（三源验证：文件时间戳 + 日志 + 语义记忆）： |
| 09-06 09:51 | MCP 记忆增强盘点结论 + server-memory 配置完成（2026-09-06） | 用户问三个主流记忆 MCP 哪个能用上。盘点结论： |
| 09-06 09:54 | 知识图谱种子数据灌入完成（2026-09-06，续） | 10 实体 + 11 关系已写入 MCP 知识图谱：RikkaMinis/OpenMinis/OWNER/ALT_USER/USER/rikka-ci-bridge/semantic_memory/knowledge_g… |
| 09-06 10:09 | 知识图谱自动同步器完成（sync_kg.py）—— 新会话开场请增量跑一次 | 脚本：/var/minis/shared/knowledge-graph-backup/sync_kg.py |
| 09-06 12:09 | 知识图谱全量灌入完成 + LLM 切换到 tokenrhythm（2026-09-06 中午） | 图谱现在：3627 实体 / 4739 关系（从今天早上的 185/153 爆发式增长，覆盖 2026-07-31 ~ 09-06 全部 798 条日志），备份 20318 条记录。 |
| 09-06 12:15 | 知识图谱备份安全性核查（2026-09-06）—— 图谱数据不会被备份导出 | 用户问"删应用前做备份，图谱那部分会不会也被备份"。代码级核查结论： |
| 09-06 13:40 | 主号→小号全量同步（2026-09-06 13:40，merge 350843f） | 背景：用户要求"把主号的成果与小号同步"。主号 OWNER/RikkaMinis main @ 5ebff693（09-05），小号 ALT_USER/RikkaMinis main @ 70f927d1（09-01）。… |
| 09-06 17:15 | Hermes Agent（Nous Research）吸收分析（2026-09-06） | 源码 /tmp/hermes/hermes-agent-main（20.6 万行 Python），报告 /var/minis/shared/hermes-agent-absorb-analysis.md。 |
| 09-06 20:06 | compaction recall eval 种子跑通（2026-09-06 晚） | 脚本 /var/minis/shared/compaction-recall-eval/compaction_eval.py（Hermes evals/compaction 移植 + RikkaMinis 压缩管线忠实 … |
| 09-07 01:31 | provider-exec-concurrency 分支（A+B 多会话并发）+ 突然停第4形态（预算墙）双修复 | 分支 feat/provider-exec-concurrency（未合并 main），三提交： |
| 09-07 01:54 | provider-exec-concurrency + 预算墙修复合并 main（2026-09-07 凌晨，main @ 303d375f） | 全链路：分支 feat/provider-exec-concurrency 四提交（1e20e04b 预算墙+slot池 → 8eeaccba 队列可见性 → 644a36df JDK Semaphore 修复 → 30… |
| 09-08 15:32 | 备份设计定调：资产 vs 副产品（用户拍板的产品哲学） | 用户纠正我的框架："这是智能体应用不是聊天应用，聊天记录里 90% 是 AI 工作过程数据，聊天记录反而是最不重要的。"由此定调自动备份设计： |
| 09-10 08:11 | 压缩提示 UX 修复（分支 fix/compact-divider-ux @ 3da98de1，已推送未合并） | 用户报的两个问题：①「已压缩 N 条消息」细线+10sp 灰字体验差 ②提示出现后回答继续长在提示上方（直觉应在下方）。 |
| 09-10 08:26 | ↳ 两个分支合并前检查 + 合并 main（2026-09-10，main @ 31c8abb9） | 指令：检查 fix/shell-stream-partial-line + fix/compact-divider-ux 是否引入 bug，无则合并。 |
| 09-13 14:22 | 压缩「跟不上新模型」修复已合并 main（2026-09-13，main @ 33aa72d） | 用户症状：新模型反应快、输出多 → 压缩功能效果大打折扣。 |
| 09-13 19:59 | 内存防线缺口审计（main @ 82c7925，报告 shared/memory-defense-gap-audit-2026-09-13.md） | 最重要更新：交接文档"无执行中看门狗"已过时——daab66b（随 09-13 restore 回归 main）已带 in-flight 监控（PersistentShell 1s 轮询 → midCommandRecy… |
| 09-13 20:46 | 内存加固三件套实现完成（分支 fix/memory-hardening-rss-stall，3 commits，CI #1498 success） | commits：a641e27 KaTeX 位图上限（H1）→ 45ce0c8 app 自身压力治理器（H2）→ c508896 挂死处置（用户追加要求）。沙箱 JVM 56/56 绿、仓库门禁 6/6、CI #1498… |
| 09-14 00:04 | 自适应压缩真机验证通过，三处记录已同步（2026-09-13 晚） | 用户确认：33aa72d（自适应压缩阈值，经 3ca019e 恢复合并）在日常使用中行为符合预期，压缩正常触发、效果如设计。原记录中的"待办：用户真机日常验证"已改为验证通过状态，同步三处： |
| 09-15 21:56 | 09-15 晚：HF 语义记忆重建 + MCP 知识图谱重建（09-06 套件随 rootfs 全丢） | HF：semantic_memory.py build 732→1070 条（索引 5.8MB，已上传 dataset USER/rikkaminis-memory），搜索验证命中正常。 |
| 09-15 21:57 | 09-15 夜：工具调用"复述副本"漏进聊天气泡 + 导致 run 停（模型专属现象，用户实锤） | 现象（用户原话）：工具调用以文本形式漏在自己的消息/气泡里；"连续两个调用，后面一个成功、前面一个不成功 → 不会停也不会打断"（反之：最后/唯一那个调用没被解析 → run 直接停在 finishReason=stop… |
| 09-16 13:34 | 09-16 午后：GitHub 热榜 Top5 实测审阅 — 意外挖出 semantic-memory 真 bug | 报告：/var/minis/shared/gh-hotlist-2026-09-w2-review.md（13KB）。方法：API 拉元数据 + 拉源码/规则原文，不看宣传语，凡「能否为我所用」判断都跑实测。 |
| 09-16 13:57 | 09-16 下午：semantic-memory 修复收口（main = 735eadb）+ 第二批热榜审阅 | 修复已闭环（用户拍板"打包一起"）：时间衰减从「乘性侵蚀」改为「加性助推」+ 输出真实 cos + SKILL.md 约定改为按 cos 判定。分支 fix/semantic-memory-scoring-0916 @ … |
| 09-16 17:43 | 批次 23 收口：分支 feat/batch23-absorb（3 commit，13 文件 +252/−28 基 ma | 批次 23 收口：分支 feat/batch23-absorb（3 commit，13 文件 +252/−28 基 main 116a23d5）→ CI run 1578 success → FF 合并 main = 2… |
| 09-16 21:27 | 09-16 夜：全库 LLM 扫描 + 管线压力测试（用户拍板「扫描仓库 + 用满额度 + 压力测试」） | 靶：RikkaMinis @ main 551ed6b（fresh clone /tmp/RikkaMinis，1129 commits）。产出：报告 /var/minis/mounts/笔记/RikkaMinis源码扫… |
| 09-19 16:29 | 09-19：offload 审计第 12 棒完成（Ring 2 第五段 = `debug/` 包 12/12 文件 5,592 行全部走完 → F-124…F-133） | 下一棒：HANDOFF-offload-13th.md —— P1 = config/ 包（4,963 行）（剩余最大 + 「用户可见入口 = 机器入口」三方同写一份配置 ⇒ 同族三标准富集）；P2 = service/… |
| 09-19 18:02 | 09-19 晚：上下文压缩"空转"根因定位（用户报告：第 1 条消息处不断压缩但无效果） | 现象（生产日志实证）：auto-compact 每 10-20s 触发一次，compactAll() invoked 之后无任何后续日志（既无 divider 也无 failed）；[CompactDiag] eAH v… |
| 09-19 19:04 | 09-19 晚：压缩修复第二棒 —— 长工具循环的预算锚点（写侧+读侧）→ main = afa404bf | 背景：第一棒（796307ec，F1/F2/F4）修好了锚点判定 + 诊断 + 硬裁剪兜底，但「一句指令 + 几百轮工具调用」会话只有一个用户轮次，而整套压缩逻辑按「用户轮次」计价 ⇒ 两侧同时退化。 |
| 09-19 19:59 | 09-19：offload 审计第 16 棒完成（P1 = `backup/` + `diagnostics/` + `logging/` + `crash/` → F-161…F-169） | 状态：✅ 只读，仓库 0 改动（HEAD = c6d8d63f = 锚点，git status --porcelain 空）· 判据 verify_findings_16th.sh 90/90 · 总闸门 verify_… |
| 09-19 21:14 | 09-19：offload 审计第 17 棒完成（小包扫尾 + app 根文件 + `shared/` = 40 文件 / 8,275 行 → F-170…F-176 · O-29…O-43） | 下一棒：HANDOFF-offload-18th.md —— P1 = provider/ 11,202 行（唯一带生产日志活体证据的未扫区域：当天 14× stream parse exception: Cancell… |
| 09-20 19:17 | 09-20 夜：压缩 anchor 卡死任务 —— 任务书假说被证伪，真机制已坐实（n=65 完美分离） | 任务书假说 H1：「startIdx > 0（已压缩过一次）⇒ resolveBudgetAnchorIdx 恒返回 -1」。 |
| 09-20 19:31 | 09-20 夜：上下文超限后 agent loop 空重试死循环 → 修复分支 `fix/context-exhausted-loop` @ `55703df9`（CI 绿，**未合并**，用户要求等统一处理） | 任务书：/var/minis/shared/hang-0920/session-task-ctxloop.md（要求：先复核四点 → 三方案分析 → 实施推荐 → JVM 复现 + 反向对照 + scan + CI → … |
| 09-20 19:41 | 09-20 夜：压缩 anchor 卡死任务 —— 复核完成，任务书假说被证伪（仓库 0 改动，按 §2 停止） | 任务书路径：/var/minis/shared/hang-0920/session-task-anchor.md。报告：/var/minis/shared/hang-0920/anchor-exp/REVIEW.md。 |
| 09-20 20:22 | 09-20 夜：压缩 anchor 死区修复完成 → 分支 `fix/compact-budget-anchor-dead-zone` @ `96e8d6cf`（CI 绿，**按用户要求不合并**） | 报告：/var/minis/shared/hang-0920/anchor-exp/（REVIEW.md 复核 + FIX.md 修复 + run_all.sh PASS=13）。 |
| 09-21 08:57 | 09-21 凌晨：审查 fix/silent-auto-compact → 发现 4 处缺陷（2 真 bug）→ 修复并合并 main = `402d25d2` | 任务：用户「检查一下云端那个分支的修改有没有引入问题之类的，如果没有就合并吧」。 |
| 09-21 10:27 | 09-21：记忆文件查看卡顿排查 → 根因是编辑路径无懒加载（整份文件塞进一个 BasicTextField） | 用户报告：记忆中单个文件超 ~130KB 后上下滑动严重卡顿。 |
| 09-21 11:02 | 09-21：记忆文件卡顿修复 → 分支 `fix/memory-file-jank` @ `7e89b81`（CI 绿，**按用户要求不合并**） | 用户指令：「修吧，就是分支上跑完了，不要合并。」 |
| 09-21 13:11 | ↳ 09-21：云端两分支审查 → 发现 2 真问题 → 修复 → 合并 main = `a081080c` | 任务链：用户「检查云端两个分支有没有 bug」→ 发现 2 处 → 「修吧」→ 「跑完没问题就合并」。 |
| 09-21 13:25 | ↳ 09-21 补：两分支修复经**真机验证通过** | 用户确认：fix/memory-file-jank 的空行修复 + fix/config-prefs-listener-gc 的闸门修复，真机验证没有问题。 |
| 09-21 14:07 | 09-21 下午：两套记忆装置增量更新（HF 语义记忆 + MCP 知识图谱） | 触发：用户「本地的和云端的两个记忆存储装置好像很久没更新了，就是 MCP 和 HF 处理一下」。 |

## 8. Provider / 模型组 / 负载均衡

**跨度** 2026-08-04 ～ 2026-09-22 · **116 条** · **状态** 已闭环（09-12 收尾；负载均衡语义已在代码级核实为「生效但语义与用户预期不同」）

**叙事**：08-04 loadBalance 轮转游标不前进 → 08-08 模型组 recovery 策略 → 08-14 模型组策略重构 P1–P4 + 思考折叠（中转站）→ 08-15 模型切换无缝（cancel+restart）→ 09-04 思考字段决策键（tokenrhythm 报错）→ 09-06 负载均衡请求级改造 → 09-11 多密钥轮换 + Groq 免费档不可用 → 09-12 「负载均衡像回退模式」**第二次**被报（08-04 同款问题）。**同一现象两次立项**，说明第一次的修法没解释清语义。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-04 06:45 | RikkaMinis — loadBalance 轮转游标修复（2026-08-04，分支 feat/loadbalance-rotation-advance） | 用户报「模型组负载均衡没发挥作用，好像只有回退模式」→ 查代码确认是真 bug： |
| 08-04 21:53 | RikkaMinis — 模型组页简化 + 语音 UI 移除（2026-08-04，分支 feat/...） | 仓库 OWNER/RikkaMinis，分支 feat/simplify-model-groups-remove-voice，commit 6cf806f9，CI run 30915989290 验证中。 |
| 08-04 22:10 | RikkaMinis 模型组重构 — CI 首次失败根因 + 自检教训（2026-08-04 续） | commit 6cf806f 首次 CI（run 30915989290）失败。根因 = AgentLoopModelsScreen.kt 从原文件搬代码时切边界不干净：把下一个函数（GroupModalityMarke… |
| 08-04 23:27 | RikkaMinis — 模型组列表拖拽排序 + 排序机制统一（2026-08-04，commit 2f42573） | 分支 feat/reorder-model-groups（从 e8f7c27 起），CI run 30923457804 全绿 success（含 testReleaseUnitTest 全量），APK 12.78MB。… |
| 08-04 23:49 | RikkaMinis — 模型组拖拽排序已合并 main（2026-08-04 收尾） | commit 2f42573 已 ff 合并到 main 并推送。主构建 run 30925451151 全绿 success，release 资产 android-latest 的 RikkaMinis-0.22-pr… |
| 08-06 16:17 | 2026-08-06 会话总结（交接用） | 已完成 |
| 08-06 17:18 | RikkaMinis — 双击返回 + fallback entry 精度修复（2026-08-06 下午） | 已合并 main |
| 08-06 17:35 | RikkaMinis — fallback 已合并 + 提供商"常用"固定功能（2026-08-06 晚） | fallback entry 精度（1f01a36）已合并 main |
| 08-06 17:46 | 会话状态核实（2026-08-06 晚，续 pinned-providers） | 后台构建（用户说"一个在打包构建中"） |
| 08-06 17:54 | RikkaMinis fallback 不一致修复（2026-08-06 深夜，分支 fix/fallback-reentry-and-anchor，commit 400db4b，CI 31090465585 success） | 用户报告 |
| 08-06 19:05 | RikkaMinis 三分支合并收尾 — main 479c2b9（2026-08-06 晚 11:05） | 已合并到 main 的三个分支（CI 验证均绿） |
| 08-06 19:14 | RikkaMinis 三分支合并 — 收尾完成（2026-08-06 晚 11:20） | 主构建结果 |
| 08-06 20:10 | RikkaMinis pinned-providers「点亮星无反应」调查（2026-08-06 晚，进行中） | 用户报告 |
| 08-06 20:17 | pinned-providers 点亮星 — 日志真机时间线（2026-08-06 续，20:11 新 session） | 用户 20:11:35 重开日志绑定，20:11:42-55 在真机反复点星。日志证据链： |
| 08-06 22:21 | RikkaMinis — 模型选择器支持 pinned 常用区（2026-08-06） | 用户问题 |
| 08-08 00:09 | RikkaMinis 模型运行时无缝切换（Plan A: cancel+restart）— 2026-08-08 | 状态 |
| 08-08 00:20 | 收尾指令：模型切换暂不合 main，与其他并行改动一起编译合并（2026-08-08 用户指示） | 用户明确说：「背景是还有一部分模块在修改中，你把你这里的一部分记一下，等所有都准备好，一起合并。」 |
| 08-08 00:59 | 记忆模块 5 项优化完成（2026-08-08，分支 fix/memory-module-optimize） | 施工内容（commit fe501f3 in fix/memory-module-optimize，已 push 远端） |
| 08-08 01:12 | RikkaMinis 提供商管理优化施工 — Phase 1+2 完成（2026-08-08 凌晨） | 分支 |
| 08-08 01:27 | 统筹合并完成 — provider/memory/token 三分支合 main + CI 修复（2026-08-08） | 背景 |
| 08-08 01:48 | ↳ 未合并分支已全部合并到 main（2026-08-08） | feat/logging-module-optimize (092ae88) — 日志模块优化：降低 flush 节奏、修日志读取 OOM、容量上限、i18n crash 区块 → ff 合并 main |
| 08-08 03:17 | RikkaMinis provider 行内星标改造 — 已合并 main（2026-08-08） | 用户反馈:provider 列表"设为常用"要先点三个点(MoreVert)弹菜单再点星号,多余。改为行内直接放星号按钮,点击即切换常用。 |
| 08-08 11:46 | RikkaMinis 模型组 recovery 策略（2026-08-08） | 用户痛点：免费 key（500 次/5h，商汤）RPM 低，撞 429 后 fallback 到付费 key 焊死（persist binding），免费 key 限流窗口重置后也不会回去 |
| 08-08 11:47 | ↳ RikkaMinis 性能优化 — 方案1完成，方案2研判调整（2026-08-08） | 方案1（@Immutable）已完成并推送：分支 perf/immutable-chat-models，commit 19e9448 + 6fd0482。给 ChatModels.kt（ChatMessage/Strea… |
| 08-08 12:27 | RikkaMinis Provider 列表点击卡顿修复（2026-08-08） | 用户反馈「设置 → 大模型提供商列表，点任意 provider 行轻微卡顿」。 |
| 08-08 12:35 | ✅ Provider 列表点击卡顿修复 — 已合并 main（2026-08-08） | 分支 perf/provider-list-click-latency @28dcb03： |
| 08-08 13:10 | ✅ Provider 详情页卡顿修复 — 已合并 main（2026-08-08，接力上一轮） | 分支 perf/provider-detail-launched-key @ f543b80，CI run 31240754128 success → ff 合并 main → 推送（主构建 run 3124107707… |
| 08-08 18:09 | 模型选择器改圆形按钮（feat/circular-model-picker）2026-08-08 | 用户反馈"对话框显示模型名称的应该改成圆的，为什么还是原来的样子"。 |
| 08-09 12:47 | 提供商详情页卡顿修复完成（2026-08-09） | 页面：管理提供商 → 具体供应商详情（ProviderDetailScreen.kt） |
| 08-09 12:54 | 三页面加载卡顿修复全部合并 main（2026-08-09） | 用户报"存储页再点进去转圈"，附带背景：三处页面加载体验问题。三任务分三分支独立 CI 验证后合并进 main（a2e88b6 success）： |
| 08-09 13:09 | 供应商详情页闪退根因 + 修复（2026-08-09 13:02 崩溃） | 用户报 13:02 闪退（crash-2026-08-09_13-02-25.log + _13-02-31.log，连环崩：10286 → 17619 → 17979 重启）。ACRA 捕获 IllegalStateE… |
| 08-09 13:50 | 供应商详情页只显示已选模型 + 新增"管理全部模型"页（分支 feat/provider-models-manage，CI run 31297147693 success） | 用户灵感：仿 rikkahub"模型照拉、只显示选中的几个"。实现： |
| 08-09 14:57 | 供应商详情页 v2 重构（feat/provider-models-manage，CI run 31299697758 success） | 用户对 v1（整屏管理页 + 大刷新行 + 搜索框有字）反馈"别扭"，定了新布局： |
| 08-09 15:33 | 模型组上下文限制硬生效 — feat/context-limit-enforce（CI run 31301106282 success） | 用户问题：设模型组 contextLimitTokens=128K，Token Usage 面板 Context Used 仍超 128K（能到 200K+）。 |
| 08-09 16:16 | 提供商模型页两处修复合并 main（2026-08-09） | 分支 fix/manager-sheet-bottom-clip，两 commit 已 ff 合并 main（6547894），正式构建 run 31303103065。 |
| 08-09 17:12 | 模型组「继续上一个」说明移到卡片下小字（2026-08-09） | 用户诉求：模型组详情页 → Recovery Policy 卡片第一项，"停留在回退模型（继续上一个）"——括号里的说明不该写在选项标题里，说明应放卡片下方 footer 小字。 |
| 08-09 17:37 | ProviderConnectionScreen Custom Base URL 占位符修复（2026-08-09） | 用户报：添加 Gemini 提供商后，详情页「API & Connection」子页的自定义 API 地址占位符仍显示 https://api.example.com（对 gemini 无意义）。 |
| 08-09 17:47 | 2026-08-09 收尾总结 | 重大改动：终端引擎替换（feat/termux-terminal-engine）— 自研 2249 行 → Termux 0.118.0，6 轮修复后合并 main。 |
| 08-14 10:07 | 供应商模型列表"默认没有+实时刷新"改造完成（2026-08-14） | 问题：添加供应商后默认出现一批过时模型（"变化太快"）。根因：refreshModels 在 API 空/无 key 时 fallback 到 ModelsDevApi.fetchModels()（48h TTL + 3… |
| 08-14 10:20 | 层 D 完成：删除 3MB 内置 models.dev asset（2026-08-14） | 承接"供应商模型列表改造"（cae67b1）。层 D 施工（分支 feat/remove-models-dev-asset → main dc0e1de）： |
| 08-14 10:35 | 模型组重设计施工完成，main = 2f9d0f1（2026-08-14） | 方案：/var/minis/shared/model-groups-redesign-plan.md（砍 Sub 概念 + 星形默认主组 + Agent Loop 文案收口）。 |
| 08-14 17:08 | 修复：添加服务商后模型不自动刷新（fix/provider-save-refresh-scope → main 97be9c0） | 用户反馈：添加新服务商（填 key 保存）后，模型列表没有自动刷新（之前验证通过的"填 key 拉官方"失效）。 |
| 08-14 17:19 | 网络错误自动切换 + 错误不留痕（feat/fallback-network-error → 施工中，2026-08-14） | 任务来源：用户报两个问题：①deepseek-v4-flash: stream was reset: CANC...（OkHttp HTTP/2 StreamResetException → NetworkError）太… |
| 08-14 17:52 | 模型组策略系统重构设计文档（2026-08-14） | 用户对模型组功能做元反思（目的/策略/是否有用/程序是否支持），经代码核查发现：recovery 维度是死脚手架（rateLimitCooldowns map 从未读写、RATE_LIMIT_COOLDOWN_DEFAU… |
| 08-14 18:13 | fallback 切换提示改为顶部 Snackbar（feat/fallback-snackbar → main 327110a，2026-08-14） | 用户反馈（真机验证 e992221 后）："已切换至 xxx" 的 fallback info 块被停在那里（作为消息块永久留在聊天流里）——用户想要的是：从对话框上面弹出横杠提示，几秒后自动退掉，不留痕迹。 |
| 08-14 18:26 | ↳ 全量验证通过（2026-08-14 用户确认） | 用户装 327110a release APK 后真机验证通过： |
| 08-14 19:13 | 模型组策略重构 P1-P4 全量施工（2026-08-14 晚，多分支并行） | 背景：设计文档 /var/minis/shared/model-group-strategy-redesign.md 落成后，另一会话先合了 P1（GroupRouter 抽取 = 596adc2，抽取但未接线）。本会话… |
| 08-14 19:37 | 模型组策略重构 P1-P4 全部合并 main（2026-08-14，main = 18f6e95） | 最终状态：P1（596adc2 另一会话）+ P2（49a38e7）+ P3（caf0828）+ P4（18f6e95）全部 ff 合并 main 并推送。远端仅 main 分支。release 构建 run 31796… |
| 08-14 20:08 | D 类（provider 层防御纵深）施工中 — fix/err-family-provider-defense（2026-08-14 20:1x） | 任务来源：用户分派"你负责 d 类"，审计文档 /var/minis/shared/request-construction-error-audit.md 的 D 类 = provider 层序列化前最后防线。 |
| 08-14 20:08 | ↳ A 类完成 + request-construction-error-audit 进展（2026-08-14 晚） | A 类（消息序列结构错误）状态： |
| 08-14 20:50 | 思考折叠框缺失根因确认（模型组中转站场景，2026-08-14 晚） | 现象：只有 A 类对话框（模型组，成员 fallback 切换）没有 Deep Thinking 折叠框，思考变普通正文；B/C/D 正常。 |
| 08-14 21:15 | D 类（provider 层防御纵深）完成：已合并 main 75995d6（2026-08-14 晚） | 最终交付（4 commit rebase 后：4bdf146/a94404a/fe7700b/75995d6）： |
| 08-14 21:43 | 思考折叠框修复完成：已合并 main（5c97940，2026-08-14 晚） | 任务来源：交接文档 /var/minis/shared/thinking-fold-fix-handover.md（A 类对话框经中转站无折叠框）。修复点 100% 在 OpenAIProvider.kt。 |
| 08-14 23:28 | deepseek-v4-flash 频繁 429/stream reset 根因确诊（2026-08-14 晚） | 用户现象：deepseek-v4-flash: Rate limited + stream was reset: CANCEL 频繁出现，"频繁到不正常"。 |
| 08-15 00:04 | fix/fallback-retry-original 合并 main 完成（b8dec8c3，2026-08-14 深夜） | 改动：恢复 3b3a12f 之前的 fallback 重试行为——去掉 isFallbackMember 条件，所有成员（包括 fallback 链上的 KUAPI）对瞬态错误享受 3 次重试（1s+2s+4s）。 |
| 08-15 05:32 | T4-A 派发指令 — 故障注入 Harness | 你负责 RikkaMinis 平衡点施工 T4-A — 故障注入 Harness（fakes + 场景协议 + 独立 runner）。 |
| 08-15 05:54 | T10 派发指令 — 故障矩阵与最终验收 | 你负责 RikkaMinis 平衡点施工 T10 — 故障矩阵与最终验收。 |
| 08-15 09:02 | T4-A 故障注入 Harness 完成：已合并 main 883b3c6（2026-08-15） | 交付：分支 stability/T4-fault-harness（2 commits：7edfba4 + 883b3c6），15 文件 +2198 行，全部在 src/android/app/src/test/java/… |
| 08-15 15:07 | T4-B 完成 — F01-F14 adapter 骨架 + driveTurnLoop + ScenarioRuntimePort 已合并 main（c14d29f，2026-08-15） | 交付内容： |
| 08-15 15:32 | 模型选择器分组头部显示修复 — 已合并 main（2a0d7db，2026-08-15） | 用户现象：模型选择器里「施工队」分组头部显示 → deepseek-v4-flash（第一个成员），但实际使用的（fallback 后/cheapestFirst 选中）是 LLM HOST·grok-4.6——「使用的… |
| 08-18 09:01 | 断流定性重大进展（2026-08-18 09:01，provider 层排除网络断流） | provider 层 100% 无断流：扫描 ms2.log 全部 llmhost.net + .sensenova.cn 请求，每个都有 responseBodyEnd bytes=<完整> → canceled（ca… |
| 08-18 09:03 | 断流诊断第2次复现（2026-08-18 09:03）——provider 层铁证健康 | 扫描全部 ms2.log：每个 provider 请求（llmhost.net + .sensenova.cn）都在 modelservice 侧完整收尾——stream done + finish_reason + s… |
| 08-22 17:19 | [小号内存治理] 交接：用户决定在小号补 D-4b 验证（聊天 provider-rss 泄漏判定） | 用户当前明确决策链（2026-08-22 晚，本会话对齐）： |
| 08-22 19:26 | TF-A provider-rss v2 观测打点完成（会话 A，2026-08-22 收尾） | 分支 diag/provider-rss-v2（基于 chore/dual-appid 5672ca3，tip c8ff0a4），分支 CI run 32569593961 全绿（单测 full suite + scan… |
| 08-22 20:55 | TF-D ProviderExecutionGateway 完成（会话 D，2026-08-22 晚） | 交接：分支 feat/provider-execution-gateway（基于 chore/dual-appid 5672ca3 + A/B/C 合并），tip 33185f8，分支 CI run 3257334894… |
| 08-22 21:23 | TF-E provider 进程域守卫完成（会话 E，2026-08-22 晚） | 交接：TF-E 已收尾。分支 test/provider-process-boundary-soak（基于 TF-D 33185f8），tip fb4981e，分支 CI run 32574820255 全绿（28 ta… |
| 08-24 10:52 | 首块超时「provider produced no first chunk within 30000ms」调查 + 委托派发（2026-08-24） | 测试/证据：真机日志 08-24 该错误 90 次（07:42 后集中），08-22/08-23 = 0 次真实运行；涉及 deepseek-v4-flash/gpt-5.6-luna/deepseek-v4-pro，走… |
| 08-24 19:27 | 模型删除 bug 修复施工完成（2026-08-24 晚） | 任务：/var/minis/shared/model-delete-bug-diagnosis.md（手动添加的模型删不掉）。分支 fix/model-delete-custom-identity @ 85e7b29（主… |
| 08-24 19:32 | 模型删除修复已合并 main（2026-08-24 晚） | 用户拍板合并 → ff 合并 main（6ea8c1b→85e7b29）已推送主号。本地+远端分支 fix/model-delete-custom-identity 已删。main release CI run #103… |
| 08-25 12:54 | 新增 provider 需重启才生效 — 修复已合并 main（5f92949） | 用户拍板合并。ff 合并 main（2f3498f→5f92949，rebase 到含 shell-cancel 的 main 零冲突）。release CI run #1050（id 32808215905）succe… |
| 08-26 16:45 | 模型组模块审计修复已合 main（01df5e7，2026-08-26 晚） | 任务：用户要求审计「模型组」模块找 bug 并修。 |
| 08-27 22:23 | provider 路由字段即时生效修复 + voice-ime 合并 main（2026-08-27 晚，main=9e3374c） | 用户主诉：设置里改大模型提供商的地址/开关（custom base URL 等）要重启 app 才生效，体验差。连带要求把另一个已跑完 CI 的分支一起合并。 |
| 08-27 23:04 | AddProvider 导入闪退修复已合 main（2026-08-27 晚，main=9105ff1） | 用户现象：电商平台买密钥，导入第二个 provider 时把两枚密钥一起填进了密钥框 → 保存 → 闪退。崩溃日志：NullPointerException: Can't toast on a thread that h… |
| 08-31 13:13 | LiteLLM 吸收三件套 A+B+C 合并 main（2026-08-31） | 背景：用户调研 BerriAI/litellm（57.6k stars 开源 AI 网关），让我评估「能不能整合进 RikkaMinis」。结论：网关层（多租户/虚拟密钥/Redis/Terraform）对单用户 And… |
| 08-31 14:43 | LiteLLM 成本层 V2：JSON 价格表 + 用户可编辑价格（2026-08-31，commit fbe888e7） | 用户反馈 Usage 页看不到「预估费用」→ 根因：价格目录是硬编码 Kotlin map，只覆盖 40 个内置模型，中转站模型（deepseek-v4-pro-0813 之类）不在任何公共价格表里，按「未知→null→… |
| 08-31 16:57 | 砍除 USD 成本估算 + 修复 facts 空时间戳（2026-08-31，commit b4e166fb） | 背景：用户真机验证「Usage 页费用显示有的有、有的没有」。定位：价格目录 model_prices.json（44 键）只解析到 1148 个实际 model_id 中的 75 个，其余 1073 个中转站/代理模型… |
| 08-31 17:17 | 第二轮开源清单评估：12 项目裁定，A/B/C 待拍板 | 用户给了第二份 Agent 生态开源清单（Mem0/Zep/Chroma/LangGraph/AutoGen/CrewAI/E2B/Composio/Open Interpreter/Langfuse/Phoenix/L… |
| 09-03 14:54 | FE-5 bug-audit session4（provider 域）完成（2026-09-03） | 审计 29 文件 / 9108 行（provider + providers 域），报告 /var/minis/shared/fe5-bug-audit/reports/session4.md。High 0 / Medi… |
| 09-03 15:19 | ↳ FE-5 bug-audit session2（ui 除 chat 域）完成（2026-09-03） | 审计 89 文件清单（约 33284 行）+ 全 ui 域 grep 探针（172 文件 / 76305 行）。报告 /var/minis/shared/fe5-bug-audit/reports/session2.md… |
| 09-03 15:55 | ↳ FE-5 bug-audit session7（杂项域）完成（2026-09-03） | 审计 93 文件 / ~23686 行（backup/config/debug/speech/diagnostics/webapp/crash/share/mcp/deeplink 等）。报告 /var/minis/sh… |
| 09-03 16:56 | ↳ FE-5 bug-audit session1（Chat 核心域）完成（2026-09-03） | 审计 83 文件 / 43033 行（chat 域全量）。报告 /var/minis/shared/fe5-bug-audit/reports/session1.md。High 2 / Medium 0 / Low 1。 |
| 09-03 17:02 | ↳ FE-5 bug-audit session3（sandbox+offload 域）完成（2026-09-03） | 审计 66 文件 / 24146 行（sandbox/ + offload/ 全量）。报告 /var/minis/shared/fe5-bug-audit/reports/session3.md。High 0 / Med… |
| 09-05 15:11 | 摘除 provider knobs + 连接测试两个功能（已合并 main @ 0103d96f） | 用户判定昨天 6584aca5 引入的「高级自定义（custom headers/body）」和「测试连接」两个功能"极度不成熟，增加复杂度/维护成本，还有问题"，决定精准摘除（非 git 回滚——那会连带干掉 thin… |
| 09-06 16:22 | 用户报"模型组负载均衡不生效，后台看不到其他模型被使用"。代码级核查（main @ 6b95929）：功能生效，但语义是 | 用户报"模型组负载均衡不生效，后台看不到其他模型被使用"。代码级核查（main @ 6b95929）：功能生效，但语义是会话级轮转不是请求级。GroupRouter.select 的 loadBalance 分支 = u… |
| 09-06 18:16 | 负载均衡请求级改造 + 停止按钮竞态修复全链路闭环（main @ cd32795，分支 CI run 340260822 | 负载均衡请求级改造 + 停止按钮竞态修复全链路闭环（main @ cd32795，分支 CI run 34026082245 head_sha 核对一致后 ff 合并，release CI 34026796848 in_… |
| 09-06 20:45 | "Stream error 手动重试"根因确诊（2026-09-06 晚，纯代码读穿，无需日志） | 用户观察：单选具体模型时 stream error 出手动重试按钮；模型组模式"似乎会自动重试"。 |
| 09-06 22:35 | Tier 2 ② content_filter→fallback 合并 main（2026-09-06 深夜，main @ f311aa99） | 分支 feat/content-filter-fallback 两提交（b8e8dda7 功能 + f311aa99 i18n 修复）ff 合并 main；分支 CI 34038969226 success（head_s… |
| 09-07 01:31 | provider-exec-concurrency 分支（A+B 多会话并发）+ 突然停第4形态（预算墙）双修复 | 分支 feat/provider-exec-concurrency（未合并 main），三提交： |
| 09-07 01:54 | provider-exec-concurrency + 预算墙修复合并 main（2026-09-07 凌晨，main @ 303d375f） | 全链路：分支 feat/provider-exec-concurrency 四提交（1e20e04b 预算墙+slot池 → 8eeaccba 队列可见性 → 644a36df JDK Semaphore 修复 → 30… |
| 09-07 02:07 | provider-exec-concurrency 全链路闭环（2026-09-07 凌晨收尾确认） | release CI 34049962050 success @ 303d375f（18:06 完成），android-latest APK（RikkaMinis-arm64-v8a.apk 13MB，18:05:58 … |
| 09-07 16:08 | fix/worker-reasoning-content-roundtrip 合并 main（2026-09-07，main @ 742bf77e） | 用户报告：模型组里 danfeng 供应商 deepseek-v4-flash/pro 报 Provider error:[400] The 'reasoning_content' in the thinking mod… |
| 09-09 19:07 | T5 provider 协议域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T5.md。扫描 34 文件/10512 行：HIGH 0 / MEDIUM 4 / LOW 7。 |
| 09-11 09:43 | 用户对「Provider 多密钥轮换」功能提出两点：①修掉梳理时发现的边界 bug ②该功能无任何 UI 说明，用户自己 | 用户对「Provider 多密钥轮换」功能提出两点：①修掉梳理时发现的边界 bug ②该功能无任何 UI 说明，用户自己都不知道存在，要求加上。 |
| 09-11 10:59 | 三处改动审计：两次已合并 + fix/key-roulette-refresh（全过，分支待合并） | 审计结论：三处均无 bug。 |
| 09-11 11:02 | fix/key-roulette-refresh 合并收尾（main @ 3a988d5f） | 合并：审计三处全过 → 用户拍板 → refspec 直推 ff（7cf6caa0..3a988d5f，无 force）→ 远端 main 用 ls-remote 复核 = 3a988d5f → 远端分支 API DEL… |
| 09-11 11:35 | 思考泄漏（thinking leaked into body）成因调查（2026-09-11） | 用户观察：模型组里混杂各供应商模型，偶发思考内容漏进正文；自测后判断"主要是上游供应商没完善，只对特定供应商的模型出现"。 |
| 09-11 11:38 | 思考泄漏取证：中转站自报 reasoning_tokens 但零 reasoning 字段（2026-09-11，用户一线样本） | 用户指出"当前这个会话本身就是典型案例"——他通过第三方中转站调用当前模型，且该问题只在这个模型上出现。 |
| 09-11 20:42 | key-roulette release CI 终态确认（2026-09-11 晚） | main @ 3a988d5f 的 release CI run 34556828343 已确认 success（bridge /status/main 实测）。至此 fix/key-roulette-refresh 全… |
| 09-12 00:25 | think 泄漏根因实锤：中转站 `<think>` 标签不在解析表（2026-09-11 晚） | 用户报告"思考泄漏是应用本身的问题"（rikkahub 同站正常）→ 直接 curl 中转站实锤 |
| 09-12 02:14 | 交接完成：A1 Prompt Cache + A2 定时 rollup → 新会话处理（2026-09-12） | 交接文档：/var/minis/shared/prompt-cache-rollup-handoff.md（含全部代码坐标/执行步骤/坑位/验证清单） |
| 09-12 21:50 | 「负载均衡像回退模式」调查（2026-09-12 晚，进行中） | 用户报告：模型组负载均衡模式「似乎还是回退模式」（同主题第 3 次：08-04 游标修复、09-06 会话级→per-message 修复）。 |
| 09-15 11:29 | 09-15 深夜：日志修复 CI 闭环 + 热路径同类排查（ProviderRepository 实锤）+ 装错包对账 | 日志分支闭环：fix/applogger-async-writer @ 28f051b（rebase 到含 CI 修复的 main 95df092）→ CI run 1536 success（SDK 修复生效）→ APK… |
| 09-15 11:57 | 09-15 深夜续：日志分支合并 main + ProviderRepository 热路径修复闭环 | 日志分支收口：fix/applogger-async-writer @ 28f051b → 用户拍板"先合并"→ FF 合并 main（28f051b）→ 远端分支 DELETE 204 → main release C… |
| 09-15 21:57 | 09-15 夜：工具调用"复述副本"漏进聊天气泡 + 导致 run 停（模型专属现象，用户实锤） | 现象（用户原话）：工具调用以文本形式漏在自己的消息/气泡里；"连续两个调用，后面一个成功、前面一个不成功 → 不会停也不会打断"（反之：最后/唯一那个调用没被解析 → run 直接停在 finishReason=stop… |
| 09-19 16:29 | 09-19：offload 审计第 12 棒完成（Ring 2 第五段 = `debug/` 包 12/12 文件 5,592 行全部走完 → F-124…F-133） | 下一棒：HANDOFF-offload-13th.md —— P1 = config/ 包（4,963 行）（剩余最大 + 「用户可见入口 = 机器入口」三方同写一份配置 ⇒ 同族三标准富集）；P2 = service/… |
| 09-19 21:14 | 09-19：offload 审计第 17 棒完成（小包扫尾 + app 根文件 + `shared/` = 40 文件 / 8,275 行 → F-170…F-176 · O-29…O-43） | 下一棒：HANDOFF-offload-18th.md —— P1 = provider/ 11,202 行（唯一带生产日志活体证据的未扫区域：当天 14× stream parse exception: Cancell… |
| 09-19 21:41 | 09-19：第 18 棒完成（`provider/` 第一轮）→ F-177 / F-178 / F-179（3 D）+ O-44…O-46（3 O） | F-177【D】★「取消」被记账成「流解析错误」（当日 14×，真因被丢弃 + 归类错） —— 链条四处： |
| 09-19 22:05 | 09-19：第 19 棒中途（`provider/` 网络与预算面）→ F-180（D）+ O-47 + N-26 + **勘误 E-18（撤回 O-44）** | ★ 勘误 E-18（重要，方法论级）：第 18 棒我写进台账的 O-44 我误判了 —— 声称 ToolJsonRepair.levenshteinAtMostOne（:124-155） |
| 09-20 00:56 | 09-20 凌晨：offload 审计并发线 A1 完成（`provider/openai/` 5 文件 3,559 行 · 第 21 棒） | 产出：/var/minis/shared/offload-audit-0919/wave2-a1/ —— report.md(35KB) · ledger-a1.json(21 条：D=3 · O=18，其中负结果 N-… |
| 09-20 01:47 | 09-20 凌晨：offload 审计 WAVE-2 并发线 A4 完成（`provider/` 杂项 + `ModelsDevApi`） | 产出（/var/minis/shared/offload-audit-0919/wave2-a4/）：report.md(34.9KB) · ledger-a4.json · ledger-section.md（可直接并… |
| 09-20 02:06 | 09-20 凌晨：offload 审计第 21 棒 · 并发线 A2 完成（provider/anthropic + provider/gemini） | 范围：AnthropicProvider(1023) + GeminiProvider(566) + AnthropicModelsApi(224) + GeminiModelsApi(127) + AnthropicM… |
| 09-20 02:26 | 09-20 凌晨：offload 审计第 21 棒 **并发线 A3** 完成（`provider/thinking/` + `provider/voice/`） | 产出：/var/minis/shared/offload-audit-0919/wave2-a3/（report.md + ledger-a3.json + verify_a3.sh + exp_a3/）。 |
| 09-20 19:02 | 09-20 深夜：流式看门狗饿死 bug 修复完成 → 分支 `fix/provider-stream-flowon` @ `13ea691c`（CI run 35506103286 success，**未合并**，用户要求） | 用户指令：「跑完不要合并」。已推送分支，CI 绿，分支保留。 |
| 09-21 16:09 | 09-21 晚：同类应用 issue 核实（1106 条 / 7 仓库）→ 主发现「非视觉模型图片门只覆盖 1/3 provider」 | 用户指令：「查一下与这个应用同类型的应用（omnibot、operit 这类）的 issue，看他们的问题在这个应用里是否同样有」 |
| 09-21 19:42 | 09-21 晚：S2 派发任务完成 — provider 层两条（§27a 图片门 + §24b TTFB 统一） | 分支 fix/s2-provider-layer @ eca54225b4b93df0f3562a61926e22ac496edd5f，CI run 35593738205 success，head_sha 逐字符一致，… |
| 09-22 02:50 | 09-22 凌晨：V3 审计（data/provider/agent/tools）→ 找到 P0 跨 IPC 字段缺失 | 产出：/var/minis/shared/verify-all-0921/reports/V3-data-provider-agent.md（10196 B） |

## 9. 备份 / 多端同步

**跨度** 2026-08-03 ～ 2026-09-21 · **102 条** · **状态** 收敛（多端同步已砍除，自动备份按「资产」语义保留）

**叙事**：08-04 WebDAV 备份把 main 编译弄坏 + 导入去重 → 08-09 备份并发 OOM → 08-10 多端自动同步 → 08-11 流量审计（用户发现方案欠考虑）→ 降本 + 合并守卫 → 08-13 备份模块审计 7 项 → 08-28 方案 C 重构 → 09-08 **产品哲学定调：资产 vs 副产品** + 自动备份 A+B + auto/ 目录分离 → 09-09 多端同步砍除。**从「做」到「砍」只用了一个月，中间靠一次流量审计转弯。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 21:47 | OpenMinis fork — 测试 backlog 清理 + 动态版本 + 上游同步（2026-08-03 进行中） | 分支 feat/test-backlog-version-sync（4 个提交 ca4a7e2/791e543/2b6ec3c/…），CI run 30819582646 验证中。 |
| 08-04 05:29 | OpenMinis fork — 仓库实况核查（2026-08-04 午后） | 用户问「项目被改到什么程度/整合了两个项目到什么程度」，用 git 全量核查，修正并补全此前的记忆： |
| 08-04 13:01 | RikkaMinis 备份导入去重（2026-08-04，分支 fix/backup-import-dedup，commit 20879a8） | 用户发现：备份导入时，若某 provider 已在设备上存在，导入会产生重复 "provider (2)"；模型分组同名时也产生 "分组 (2)"。这是 [T-backup-group-idmap] 时代就有的设计——i… |
| 08-04 16:47 | RikkaMinis — WebDAV 备份系列提交把 main 编译弄坏，已修复（2026-08-04，commit bb131db） | 用户报「构建失败」。CI 从 868b6f5（WebDAV backup 功能）起连续 4 个 run 红（30890121437/30890395267/30890748946/30890856653），全部挂在 co… |
| 08-04 16:49 | ↳ RikkaMinis 收尾确认（2026-08-04，bb131db 之后） | 修复 WebDAV 编译错误后的收尾状态： |
| 08-04 17:56 | RikkaMinis — fix/webdav-restore-doublefire 构建检查（2026-08-04） | 用户报「构建完成了，检查一下」。检查结果： |
| 08-04 18:11 | RikkaMinis — WebDAV restore 分支收尾完成（2026-08-04） | 检查后把 fix/webdav-restore-doublefire 分支 ff 合并进 main 并删分支，main push 自动构建成功。 |
| 08-05 23:15 | RikkaMinis 全量代码审查（2026-08-05 晚，clone /tmp/rikkaminis-review） | 结论 |
| 08-06 11:56 | RikkaMinis 经验记忆修复实施完成（2026-08-06，4 commit 已推 main 61ea3a2） | 基线 87f69eb → 61ea3a2，4 个 commit 全部推送 main，CI run 31069812286 验证中。 |
| 08-06 21:13 | README 文档同步（2026-08-06 收尾补）— main 已到 ebb4e11 | 下午误判"今天的改动不涉及 README 描述变更"，用户提醒后补充：今天确实有两个用户可见新功能值得记进 README。 |
| 08-07 08:10 | RikkaMinis 系统审查（2026-08-07，基线 main@9bb8400） | 用户要求用高性能模型系统审查最近几天新增功能，只给清单不动手。审查范围 08-04~08-07 共 93 个 non-merge commit。 |
| 08-07 18:08 | RikkaMinis(Android fork) vs OpenMinis(iOS) 功能对比 — 2026-08-07 | 对比方法：两份代码都在本地（/tmp/official-openminis=9cf3a85 上游，/tmp/rikkaminis-full=75cd067 Android fork）。Android fork 几乎抄全了… |
| 08-07 19:13 | RikkaMinis: 砍 rootfs 备份/恢复 + soul.lang 接线（2026-08-07 已发版） | 用户要求合并编译发版，5 文件改动已合 main a37c537，CI run 31172667557 全绿，release android-latest 资产已更新（11:12Z）。 |
| 08-08 09:46 | RikkaMinis main 分支完整合并梳理（2026-08-08） | 远程状态 |
| 08-08 20:36 | 三平台集成文档化 — README 补全（2026-08-08） | 用户指出"三大平台纳入"是一次重大升级，不是小修小补，README 必须写清楚，否则以后自己都会忘、别人也看不懂。 |
| 08-09 18:36 | 备份并发 OOM 修复（fix/backup-concurrency-oom，已合并 main） | 用户问题：先点云端备份（进行中）再点本地备份 → 本地 OOM：Failed to allocate 150994952 bytes, 512MB heap。备份体积 ~70MB。 |
| 08-09 22:21 | ↳ RikkaMinis 开发项目收尾归档（2026-08-09 深夜） | 三件事全部完成，画句号： |
| 08-10 16:16 | 多端自动同步功能完成（feat/multi-device-sync → main 7c73343） | 为 RikkaMinis 加了"多端自动同步"，在用户两台设备（手机+平板）间同步轻量配置。核心设计：对齐现有备份机制而非新增第二套。 |
| 08-11 08:14 | 多端自动同步流量审计（用户发现方案欠考虑） | 用户指出 MultiDeviceSync 方案在坚果云免费版下会撞流量限制。查证结论： |
| 08-11 08:33 | 多端同步精细化方案定稿（2026-08-11，分支 fix/sync-hide-prune） | 用户决策三项：①改动3（段级时间戳合并）拆下轮 ②overrides 留存用"抽出来重拉后恢复"(ii) ③TTL 闲置清理而非刷新后清。 |
| 08-11 09:04 | ✅ 多端同步降本落地完成（分支 fix/sync-hide-prune → main 088a082） | 改动1+改动2 完成、CI success（run 31447538822）、ff 合并 main、push 后分叉已删。改动3（段级时间戳合并）按计划拆下一轮。 |
| 08-11 09:24 | ✅ 改动3（同步合并守卫）完成 — fix/sync-merge-guard → main a5d56f8 | CI success（run 31448661275）、ff 合并 main、push 后分叉已删。 |
| 08-14 00:03 | 备份模块审计完成 — backup-module-audit-plan.md（2026-08-13） | 用户要求审计备份模块（不动代码，出方案给其他模型施工）。审计产物：/var/minis/workspace/backup-module-audit-plan.md（21KB，11 个问题 + 分支规划 + 验证清单）。 |
| 08-14 00:17 | 备份模块审计二次检查 — P0-1 误判修正（2026-08-13 深夜） | 重要修正：第一版报告 P0-1「聊天还原级联删除消息」是误判。二次检查下载了 Room 2.6.1 + androidx.sqlite 2.4.0 源码确认： |
| 08-14 00:37 | 最终确认：main 6823ca4 release 构建 31720574220 success（2026-08-13 | 最终确认：main 6823ca4 release 构建 #31720574220 success（2026-08-13 16:2x）。三修复分支（A i18n / B 主题色 / C 生命周期）全部合并 main 并过… |
| 08-14 01:06 | 备份模块 7 项修复全部合入 main（2026-08-14 凌晨） | main = 6bdaa68，release 构建 31723209809 success。7 个分支全部 CI 绿 → 合并 → 推送 → 删分支（本地+远端）。 |
| 08-14 03:10 | OAuth 登录移除施工完成，合入 main（2026-08-14） | 目标：用户想砍"设置 → 添加 AI 服务商"的复杂度。审计后确认：复杂度 90% 来自 OAuth 登录层（auth/ 包 2969 行），而 6 个 provider 全部支持手动 API key（gemini 甚至… |
| 08-14 05:54 | 自动备份（MultiDeviceSync）验证 — 机制正常，坚果云上传额度耗尽（2026-08-14） | 用户要求检查自动备份是否生效。验证方法（可复用）： |
| 08-14 12:43 | rikkaminis-dev-history.md 从记忆重建（2026-08-14 12:4x） | 用户发现笔记挂载目录 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md（应用修改的日志合并导出）"被改出问题"（原文：被应用改动出了毛病；检查发现 2 处时间戳乱序，用户决定不走修复… |
| 08-15 10:16 | 可并行任务清单（与 T7 同步开工） | T7 正在另一个对话框施工（主链接入，ChatViewModel.kt）。以下任务可并行： |
| 08-15 10:19 | ↳ T10 验收准备完成 — 验收矩阵已交付（2026-08-15 下午） | 任务 3（可并行清单）完成：纯文档任务，未改代码、未跑测试。 |
| 08-18 14:40 | E 会话闭包：T05（数据/配置/备份）+ T12（架构所有权）只读审计完成（2026-08-18） | 基础：main@500c5fa 已确认，仓库 /tmp/rikka-src，只读，未改任何源码（无脚本，纯 rg/sed 静态审计）。 |
| 08-18 18:55 | 后端二轮扫描完成 + RC7 收口（2026-08-18 晚） | RC7 最终状态 |
| 08-18 19:33 | RC17A 备份警示文案完成（2026-08-18 晚，独立会话） | 用户拍板走方案 A（备份 apiKey 维持 Base64 明文 + 加警示文案，不做口令加密）。本会话完成纯文案改动。 |
| 08-18 19:47 | RC16 — MultiDeviceSync 先拉后推覆盖竞态（乐观锁）完成（2026-08-18 晚，独立会话） | 分支 fix/audit-rc16-sync-if-match，commit fe0a43f（+ 首跑测试修复前 e8369a2），CI run 32132091706 success（25 steps 全绿）。回报 r… |
| 08-18 19:57 | ↳ FE-4 route A+B 完成:ChatViewModel 纯函数抽取(2026-08-18) | 用户领取 FE-4 任务(ChatViewModel 12158 行拆分),走交接文档的"路线 A:先抽无状态纯函数"了路线,零回归闭环,CI 绿(run 32133152371),未合并 main(等用户拍板)。 |
| 08-21 12:07 | 2026-08-21 崩溃修复 + 偶发丢消息搁置 | 已修复并合并（main a1bc4bb） |
| 08-22 12:54 | RikkaMinis 官方对比自述文档完成（2026-08-22） | 生成了 /var/minis/workspace/RikkaMinis-官方对比自述.md，把 fork 相对官方 OpenMinis 的全部差异按三类归档： |
| 08-22 15:55 | 小号/大号应用共存前置工作（dual-appid） | 用户决策：接下来改动集中在小号（ALT_USER fork）做实验，需要大小号编的应用能共存（同设备并排诊断，互不覆盖）。大号 OWNER/RikkaMinis 保稳定不动。 |
| 08-22 16:48 | [dual-appid] lab 包闪退根因修复（native-offload abstract socket 冲突） | 继续 dual-appid 前置工作。小号 lab APK（applicationId=com.openminis.app.lab，已生效）安装后闪退，logcat -b crash 抓到 P0根因： |
| 08-22 17:03 | [dual-appid] 闭环维护重要更新（lab 包安装正常） | 用户确认已装 lab APK（com.openminis.app.lab），安装后正常运行，无闪退。 dual-appid 前置工作核心验证通过：大号 com.openminis.app + 小号 com.openmin… |
| 08-22 17:04 | [dual-appid] 重要决策：小号维持分支进度，不合并 main | 用户明确拍板：dual-appid 的改动（应用共存实验）留在小号实验分支 chore/dual-appid 的"进度"态即可，不要合并进小号 main。 |
| 08-23 14:29 | 会话 C 进行中（2026-08-23）：存储/备份/配置同步压力测试 | 执行环境：真机 Redmi marble (Android 15, SDK 35)，com.openminis.app.lab beta.41/versionCode 220000041 已装。android-shizu… |
| 08-23 14:32 | 会话 C 发现确凿字段蒸发（2026-08-23） | 确凿 bug（备份/同步序列化字段蒸发）：ModelOverrides.maxThinkingLevel（用户设置的 thinking 强度上限覆盖）在 ConfigBackup 备份导出/恢复路径完全未序列化/未恢复： |
| 08-23 14:38 | 会话 C 完成（2026-08-23 收尾） | 用户决策：不做「模拟用户操作/点击」类 UI 压测（日常使用用户能立刻感知，意义不大）。会话 C 因此停止 UI 动态操作，聚焦数据层静态+独立自证。 |
| 08-23 16:14 | 修复 01：备份/同步字段蒸发 已完成实现+推送（2026-08-23） | 在独立 clone /tmp/fix01-repo（基线 a6b2665 / fix/modelservice-before-dispatch-notify）创建分支 fix/backup-field-evap，comm… |
| 08-23 16:44 | 修复收口完成（2026-08-23 晚）— 5/5 修复分支 CI 全绿 | 基于 a6b2665 的 5 条修复分支全部完成并推送小号远端，分支 CI 全绿（head_sha 与 tip 逐一核实）： |
| 08-26 00:55 | 主号回滚 + 小号同步执行记录（2026-08-25 深夜） | 背景：我（本会话）之前做的「限 PRoot 地址空间 RLIMIT_AS=4GB」修复（commit de13ed18）翻车——4GB 压太狠导致 PRoot tracer 起不来，所有终端/shell 瘫痪（用户 B … |
| 08-27 09:32 | backup 模块审计（task-04，module-audit-batch） | 模块 com.openminis.app.backup（5 文件 / 2197 行）系统审计完成，报告 /var/minis/shared/module-audit-batch/reports/report-backup… |
| 08-27 23:04 | AddProvider 导入闪退修复已合 main（2026-08-27 晚，main=9105ff1） | 用户现象：电商平台买密钥，导入第二个 provider 时把两枚密钥一起填进了密钥框 → 保存 → 闪退。崩溃日志：NullPointerException: Can't toast on a thread that h… |
| 08-28 11:22 | 多设备自动同步重构（方案 C）已合 main = d83cdfe（2026-08-28） | 用户诉求：自动备份/多设备同步设计不合理——A 设备动作自动上传、B 设备打开自动同步的全量覆盖模型会把另一台的改动/删除冲掉（两台同一天都在用时会互相覆盖 GLOBAL.md 和每日日志）。 |
| 08-31 23:03 | 备份超限修复：字节预算线性裁剪（2026-08-31 晚，commit 93773448 合并 main） | 问题：用户手动全量备份报 Backup too large (72658077 chars, max 67108864)——72MB 顶爆 64MB 上限，导出直接 throw，全有或全无。用户用「聊天窗口=0」验证成功… |
| 09-01 02:26 | 开发线转移 + 成熟度门槛（2026-09-01 凌晨，用户拍板） | 仓库架构决策 |
| 09-01 19:27 | 阶段性总结（2026-09-01）——应用当前状态速览 | 仓库现状 |
| 09-04 22:28 | dev-history 文档同步到 09-04（2026-09-04，main @ d1b6af90） | 用户要求更新仓库 docs/dev-history/ 开发日志。流程：rebuild_dev_history.py → sanitize_dev_history.py → 验证 → 同步挂载副本 → 分支提交合并。 |
| 09-05 15:49 | dev-history 文档同步到 09-05（2026-09-05，main @ 12bfbbaf） | 用户要求更新仓库 + 本地两份开发档案。流程照旧：rebuild_dev_history.py → sanitize_dev_history.py → 验证 → 同步挂载副本 → 分支提交合并。 |
| 09-06 09:54 | 知识图谱种子数据灌入完成（2026-09-06，续） | 10 实体 + 11 关系已写入 MCP 知识图谱：RikkaMinis/OpenMinis/OWNER/ALT_USER/USER/rikka-ci-bridge/semantic_memory/knowledge_g… |
| 09-06 10:09 | 知识图谱自动同步器完成（sync_kg.py）—— 新会话开场请增量跑一次 | 脚本：/var/minis/shared/knowledge-graph-backup/sync_kg.py |
| 09-06 12:15 | 知识图谱备份安全性核查（2026-09-06）—— 图谱数据不会被备份导出 | 用户问"删应用前做备份，图谱那部分会不会也被备份"。代码级核查结论： |
| 09-06 13:40 | 主号→小号全量同步（2026-09-06 13:40，merge 350843f） | 背景：用户要求"把主号的成果与小号同步"。主号 OWNER/RikkaMinis main @ 5ebff693（09-05），小号 ALT_USER/RikkaMinis main @ 70f927d1（09-01）。… |
| 09-06 14:02 | 小号同步 CI 闭环确认（2026-09-06 13:55） | 主号→小号 merge 350843f 的 CI success（run 34014575216，head_sha=350843f 核对一致）： |
| 09-06 16:07 | 主号→小号同步 6b95929（2026-09-06 16:0x，merge 9047a8ef） | 同步内容：主号 main 6b95929（商汤思考档 400 双 bug 修复：worker thinking rules 跨进程恢复 + sensenova effort enum clamp，7 文件 +310）→ … |
| 09-06 17:30 | 小号同步 CI 遗留闭环确认（2026-09-06 晚） | 小号 ALT_USER/RikkaMinis main @ 9047a8ef（merge 6b95929）的 CI run 34020741695 success（head_sha=9047a8ef 核对一致）；主号 m… |
| 09-06 23:58 | dev-history 0906 同步 + 主号→小号全量同步（2026-09-06 深夜） | 主号 main @ 7d928fa3（docs(dev-history): sync archive to 2026-09-06, 794 entries / 35 days）： |
| 09-07 08:33 | ARCHITECTURE.md + dev-history 0907 同步（2026-09-07，main @ 3356a4cd） | 用户要求「做架构全貌文档 + 更新仓库文档」已闭环： |
| 09-07 10:52 | ↳ ChatScreen 拆分批次 1 合并 main（2026-09-07，main @ cf8d8a93） | 用户要求拆分四个超大文件（ChatScreen 6439 / ChatViewModel 3782 / StreamingMarkdownText 3754 / OpenAIProvider 3174），评估后按四批推进… |
| 09-07 11:23 | ↳ ChatScreen 拆分批次 2 合并 main（2026-09-07，main @ 16c20c08） | StreamingMarkdownText.kt 3755 → 3017 行，两个新文件：MarkdownStreamMerge.kt（256 行，流式合并纯函数，零 Compose 依赖）+ MarkdownBlock… |
| 09-07 12:03 | ↳ ChatScreen 四文件拆分批次 3+4 合并 main（2026-09-07，main @ 29a20a47） | 用户拍板批次 3+4 合一个分支做。全部四批拆分完成。 |
| 09-07 15:14 | 文档更新 + Hermes 借鉴登记 + 小号同步（2026-09-07，main @ 5e81aa1a / alt @ 3e8dc9e6） | 任务：用户要求更新文档 + 同步小号，并把"这次新借鉴的东西"在文档做登记。 |
| 09-07 16:08 | fix/worker-reasoning-content-roundtrip 合并 main（2026-09-07，main @ 742bf77e） | 用户报告：模型组里 danfeng 供应商 deepseek-v4-flash/pro 报 Provider error:[400] The 'reasoning_content' in the thinking mod… |
| 09-07 22:48 | 文档更新 + 小号同步（2026-09-07 晚二次，main @ fce2ccc8 / alt @ 46e7d0e2） | 任务：用户再次要求更新主号文档 + 同步小号（15:14 已做过一轮，此为 15:14 之后的增量）。 |
| 09-08 13:40 | 环境变量分组分支审计+修复合并 main（2026-09-08，main @ fa87964） | 任务：用户要求审计 fix/envvar-flatten-usage-sheets（4 commit）有 bug 修完再合并。 |
| 09-08 14:00 | 文档同步：dev-history + README 致谢更新（2026-09-08，main @ ec39da7） | 用户要求"文档与事实不相符的部分更新一下，readme 也是，致谢缺了一些，还有三平台的之类的"。已完成： |
| 09-08 15:32 | 备份设计定调：资产 vs 副产品（用户拍板的产品哲学） | 用户纠正我的框架："这是智能体应用不是聊天应用，聊天记录里 90% 是 AI 工作过程数据，聊天记录反而是最不重要的。"由此定调自动备份设计： |
| 09-08 16:45 | 自动备份 A+B 施工交接（2026-09-08，feat/backup-assets-complete @ f8a2372，未合并） | 第三轮分支 CI run 34205678023 in_progress 结论未确认——新会话开场先查。交接文档 /var/minis/shared/backup-assets-complete-handoff.md（含… |
| 09-08 17:27 | 自动备份 A+B 审计修复 + 合并 main（2026-09-08，main @ 568d87a） | 流程：新会话开场查交接文档 + 分支 CI（run 34205678023 f8a2372 已 success）→ 本地全量审计（17 文件 1108 行）→ 发现 2 真 bug 修复 → 分支 CI run 3420… |
| 09-08 19:10 | 自动备份远端管理 + auto/ 目录分离施工交接（2026-09-08 晚，分支 feat/backup-auto-remote-dir @ 3c155f6，未合并） | 用户多设备痛点（实测）：A 自动备份推 WebDAV 成功；B「自动备份」区无入口（本地列表=本机文件，设计如此但无引导）；B 全量远端列表混入 auto 文件无法区分，第一轮点恢复"没恢复"（实际恢复的是 B 自己的备… |
| 09-08 19:23 | auto/ 目录分离分支审计+合并 main（2026-09-08，main @ 6e6b822） | 用户指令：检查分支 feat/backup-auto-remote-dir（3c155f6）是否引入 bug，有则修、无则合并。新会话流程：交接文档 → 本地审计 → CI 结论。 |
| 09-08 19:32 | ↳ auto/ 目录分离真机验证闭环（2026-09-08 晚，用户确认） | 用户确认真机验证符合预期。验证包 = 分支 CI run 34218884469 的 APK（head 3c155f6），与合并进 main 的 commit 同一 head——分支包即合并内容，验证有效性成立（head… |
| 09-09 00:05 | Agent 循环预算统一调高（2026-09-09） | 用户指令：事实使用中发现多端自动同步无法发挥作用、多数时候是负作用，砍掉。 |
| 09-09 00:29 | 多端同步砍除合并收尾（main @ c91f632） | 用户澄清"调默认值"指程序源码默认（下载安装包里写死的），非运行时配置（运行时那批 256/120 也改了，双管齐下）。 |
| 09-09 13:10 | runtime-limits-ux 审计无 bug + 合并收尾（main @ ec1e5d8） | 来源：上一轮 runtime-limits-ux 审计顺带发现的既有 LOW（非最近 5 次引入）——BackupSettingsScreen importLauncher（SAF activity-result 回调，… |
| 09-09 13:57 | 手动导入主线程 readText 修复合并收尾（main @ 40ea387） | 用户要求对整个程序再做一次全局系统性抓 bug，不开子代理，手动分配会话。已产出完整材料在 /var/minis/shared/global-bug-audit-0909/： |
| 09-09 18:37 | T4 data+backup 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T4.md。扫描 75 文件/17039 行：HIGH 1（ProviderDatabase MIGR… |
| 09-10 01:00 | 第二轮审计修复：MEDIUM 清零 + LOW 批次（2026-09-10 凌晨） | main 状态：main @ d66fea1（B11 ff2455b + B12 d66fea1 已 ff 合并；B11 release CI 34377683652 success，B12 release CI 343… |
| 09-10 01:46 | 上游痕迹清理评估 + 包名对齐方案（2026-09-10，待用户拍板） | 用户指令：去掉上游（OpenMinis）痕迹、与应用名 RikkaMinis 对齐，重点问包名工程量。用户已拍板 B 方案（改 namespace/包路径），并明确 不再同步上游（差异太大，改为按需"融合"，融合本身工作… |
| 09-10 03:10 | 包名迁移完成：com.openminis.app → com.rikkaminis.app（2026-09-10，main @ e24ca02） | 用户指令：方案 B（改 namespace/包路径）+ 不再同步上游（改按需"融合"）；批准后要求"直接合并、不用等"，因为最终会有一次统一审核环节。 |
| 09-10 09:18 | feat/port-streaming-backup-and-trace-gate 审计 + 引号 bug 修复（2026-09-10） | 分支：另一会话推的流式备份导出（杀 payload-String OOM）+ trace 门禁，f2ac8e06，run #1448 success 11m57s（正常带宽）。 |
| 09-10 09:32 | 流式备份分支合并收尾（2026-09-10，main @ e0f32d40） | 闭环：审计发现引号 bug → 独立 worktree 修复（e0f32d40）→ 分支 CI #1449 success（head 核对，12m20s）→ 用户拍板 → git push origin e0f32d40… |
| 09-10 09:44 | 小号线自动同步 + lab 侧同款修复 + 文档三项（2026-09-10） | 小号线定位（用户澄清）：ALT_USER/RikkaMinis = 上游 main verbatim + 唯一 delta（workflow 注入 MINIS_APP_ID_OVERRIDE=com.rikkaminis… |
| 09-10 10:02 | 小号线同步闭环验证 + 文档合并（2026-09-10，main @ f1123178） | 小号线（ALT_USER/RikkaMinis）三条验证全绿： |
| 09-10 15:08 | dev-history 重建 882 条 + 小号同步机制实测通过（2026-09-10 下午收尾，主仓 main @ 68f5715） | ① 文档更新：rebuild_dev_history.py + sanitize → 882 条 / 39 天 / fences 32 even / outOrder 0 → 复制进主仓 docs/dev-history… |
| 09-14 00:04 | 自适应压缩真机验证通过，三处记录已同步（2026-09-13 晚） | 用户确认：33aa72d（自适应压缩阈值，经 3ca019e 恢复合并）在日常使用中行为符合预期，压缩正常触发、效果如设计。原记录中的"待办：用户真机日常验证"已改为验证通过状态，同步三处： |
| 09-14 22:27 | 09-14 深夜：dev-history 档案同步到 09-14（1003 条）+ sanitize 脚本固化头部刷新 | 档案：953 → 1003 条 / 43 天 / 1,196,897 字符 / 18,828 行；fences 36 even、anchors=outOrder=0、脱敏 113 处 + INDEX 6 处、Remain… |
| 09-15 21:56 | 09-15 晚：HF 语义记忆重建 + MCP 知识图谱重建（09-06 套件随 rootfs 全丢） | HF：semantic_memory.py build 732→1070 条（索引 5.8MB，已上传 dataset USER/rikkaminis-memory），搜索验证命中正常。 |
| 09-18 00:27 | 09-17 深夜修复：冷启动恢复覆盖 Launch Session 设置（main = bd55749b，#1637 绿 / #1638 release） | 改动（分支 fix/coldstart-restore-gate，3 文件 +137−3）： |
| 09-19 00:25 | 09-19：小号 fork 同步断线 4 天修复（***ALT_USER***，非代码仓改动，直接 API 操作） | 情况：ALT_USER/RikkaMinis 的每晚同步 workflow（sync-fork-main.yml）09-15 起连续 4 天 failure，fork main 停在 09-14，lab 救援线过期。 |
| 09-19 19:59 | 09-19：offload 审计第 16 棒完成（P1 = `backup/` + `diagnostics/` + `logging/` + `crash/` → F-161…F-169） | 状态：✅ 只读，仓库 0 改动（HEAD = c6d8d63f = 锚点，git status --porcelain 空）· 判据 verify_findings_16th.sh 90/90 · 总闸门 verify_… |
| 09-19 21:14 | 09-19：offload 审计第 17 棒完成（小包扫尾 + app 根文件 + `shared/` = 40 文件 / 8,275 行 → F-170…F-176 · O-29…O-43） | 下一棒：HANDOFF-offload-18th.md —— P1 = provider/ 11,202 行（唯一带生产日志活体证据的未扫区域：当天 14× stream parse exception: Cancell… |
| 09-20 02:07 | 09-20 第 21 棒 · 并发线 B2 完成（`data/repository/` 11 文件 / 7,101 行） | 产出：/var/minis/shared/offload-audit-0919/wave2-b2/ —— report.md（含覆盖表）· ledger-b2.json（25 条：D=6 · O=10 · N=9）· v… |
| 09-20 09:22 | 09-20：近两日（09-19~09-20）改动独立核查 —— 结论「未引入问题」 | 核查对象：afa404b（09-19 18:50）→ 6a10661（09-20 08:51），22 commit / 96 文件 / +3417 −1512。 |
| 09-21 14:07 | 09-21 下午：两套记忆装置增量更新（HF 语义记忆 + MCP 知识图谱） | 触发：用户「本地的和云端的两个记忆存储装置好像很久没更新了，就是 MCP 和 HF 处理一下」。 |

## 10. 渲染性能 / Markdown / 长会话卡顿

**跨度** 2026-08-05 ～ 2026-09-21 · **100 条** · **状态** 已闭环 + 持续加固（09-15 七轴 sweep：纯逻辑层零新 bug，真发现全在接线/时序边界）

**叙事**：08-12 文字渲染空白 → 08-20 工具卡「正在调用」+ CPU 80% → 08-21 长会话流式渲染 CPU 满载 → 08-25 rikkahub 流畅性吸收 A–E（消息级聚合 + @Stable/@Immutable 纪律）→ 08-26 聚合路径复制失效 → 09-14 census 接错渲染器（7 分支全死）→ 09-15 markdown 解析器死循环（`#196` 类行挂死，**用户报「大文件加载不出来」**）→ 七轴 fuzz sweep。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-05 01:45 | RikkaMinis — Mermaid 无法渲染成 PNG（2026-08-05） | 在 PRoot/iSH 沙箱内，@mermaid-js/mermaid-cli 过不了 headless chromium 的 CDP 连接： |
| 08-08 11:29 | RikkaMinis 性能审查 (2026-08-08) | 用户要求系统性检查 UI 交互流畅度。已深入审查 ChatViewModel (10088行)、ChatScreen (5942行)、StreamingMarkdownText (3542行)、ChatFlatItems… |
| 08-08 12:27 | RikkaMinis Provider 列表点击卡顿修复（2026-08-08） | 用户反馈「设置 → 大模型提供商列表，点任意 provider 行轻微卡顿」。 |
| 08-08 12:35 | ✅ Provider 列表点击卡顿修复 — 已合并 main（2026-08-08） | 分支 perf/provider-list-click-latency @28dcb03： |
| 08-08 13:10 | ✅ Provider 详情页卡顿修复 — 已合并 main（2026-08-08，接力上一轮） | 分支 perf/provider-detail-launched-key @ f543b80，CI run 31240754128 success → ff 合并 main → 推送（主构建 run 3124107707… |
| 08-09 03:56 | 终端死屏根治 — Termux TerminalView 渲染管线修复（2026-08-09） | 用户反馈：终端仍"不能操作"，只有 ✕ 可点。日志（minis-2026-08-09.log, PID 23277 = #302 包）显示 PTY 每次都正常启动（03:22:47 Termux PTY started）… |
| 08-09 10:37 | #307 发送路径流畅度优化（perf/send-thread-io）已合并 main | 将 7 处发送路径的 viewModelScope.launch { } 改为 Dispatchers.IO，消除了主线程上同步磁盘 IO（SOUL.md/GLOBAL.md/daily memory 读取、requir… |
| 08-09 12:47 | 提供商详情页卡顿修复完成（2026-08-09） | 页面：管理提供商 → 具体供应商详情（ProviderDetailScreen.kt） |
| 08-09 12:54 | 三页面加载卡顿修复全部合并 main（2026-08-09） | 用户报"存储页再点进去转圈"，附带背景：三处页面加载体验问题。三任务分三分支独立 CI 验证后合并进 main（a2e88b6 success）： |
| 08-12 01:52 | 文字渲染空白 bug（用户报告，第一手） | 现象：大模型高速流式回答时，偶发文字块显示为空白。滑出屏幕再滑回，文字正常出现。 |
| 08-12 02:05 | 文字渲染空白 bug 根因定位 + 修复完成（2026-08-12） | 用户一手现象：大模型高速流式回答时，偶发文字块空白；滑出屏幕再滑回，文字正常。 |
| 08-12 11:09 | ✅ 文字渲染空白 bug 真机验证通过（2026-08-12 用户确认） | 用户真机测试后确认：修复生效，高速流式时之前看到的空白问题消失了。本次 fix/stream-fade-frame-driver-restart 闭环完成（CI 分支+main 双绿），无需继续排查 produceSta… |
| 08-13 07:02 | 任务 C：组件渲染测试 — 完成 | 分支：fix/component-render-tests → main 95f042b（CI run 31648578397 ✅ success） |
| 08-13 11:33 | 冷启动卡顿分析（2026-08-13 用户日志） | 用户另一台设备日志显示进入聊天页面时有"卡住"感觉。根因分析： |
| 08-14 05:46 | UI 四验证项全部闭环（2026-08-14）+ CPU 采样验证法沉淀 | 验证结果（用户装 main 最新 APK 后）： |
| 08-14 11:04 | 滚动体验三问题：A+B 施工中，D 已交接（2026-08-14 上午） | 任务来源：用户反馈聊天界面三问题：①滚动必须"很直"才能滑 ②内容上下跳 ③思考栏/工具栏形态。 |
| 08-14 11:11 | ↳ A+B 已合入 main 3c95878（2026-08-14 上午，更新） | A（表格折叠）+ B（工具行动画）分支 fix/scroll-ux-table-fold-animate 已合入 main（3c95878），分支已删（远端 204 + 本地 -D） |
| 08-14 11:28 | 任务 D（回合聚合）施工完成，main = cce2a10（2026-08-14 上午） | 用户委托"执行任务 D"（交接文档 /var/minis/shared/task-D-agent-run-group.md）。全流程闭环： |
| 08-14 20:26 | 任务 B 核心文件补测试（fix/core-file-tests，2026-08-14 晚） | 任务来源：用户分配任务 B（task-allocation-0813）：为 4 个审计零覆盖核心文件补测试（ChatViewModel 11289 / ChatScreen 6031 / StreamingMarkdow… |
| 08-14 21:43 | 思考折叠框修复完成：已合并 main（5c97940，2026-08-14 晚） | 任务来源：交接文档 /var/minis/shared/thinking-fold-fix-handover.md（A 类对话框经中转站无折叠框）。修复点 100% 在 OpenAIProvider.kt。 |
| 08-15 10:21 | 可并行清单任务 1（C 类组件渲染测试）= 已完成项，无需施工（2026-08-15 上午） | 核查结论：任务 C 的交付物 08-13 就已合并 main，派发清单状态未同步导致被重复派发。 |
| 08-15 10:21 | ↳ T10 验收准备完成 — 验收矩阵已交付（2026-08-15 下午） | 任务 3（可并行清单）完成：纯文档任务，未改代码、未跑测试。 |
| 08-16 19:21 | rikkaminis-dev-history.md 四次重建（补 08-16 下午/晚条目）+ 解析器 bug 修复（2026-08-16 晚） | 用户要求把今天下午/晚的新条目也补进 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md（BiliRoamingX 安全分析/编译失败、rikkahub 流式剖析、滚动跳动施工方案定案… |
| 08-19 00:00 | 输入框闪退 + 流式排版重复 排查（2026-08-18 深夜，未定位） | 用户报两个问题： |
| 08-20 23:48 | 工具卡「正在调用」+ CPU 80% 根因定位（2026-08-20 日志实证） | 用户抓到真机日志 /var/minis/attachments/uploads/minis-2026-08-20.log（31400 行）。结论：不是纯 UI 状态丢失，是长会话流式渲染的分配风暴打爆主线程。 |
| 08-21 00:30 | 长会话流式渲染 CPU 满载根因修复合并收尾（2026-08-21） | 分支 CI 已绿（run 32390584929 success）→ 已合并 main 并收尾。 |
| 08-24 07:49 | ChatScreen 显示/发送链路审计（2026-08-24，main 0e68209） | 用户报两现象：①大模型显示文本偶尔有问题 ②对话框变大后输入+发送变卡。全链路审计（ChatScreen/ChatViewModel/ChatFlatItems/StableChatRowLedger/AppendOnl… |
| 08-24 08:00 | ChatScreen 渲染修复方案已定稿并派发（2026-08-24） | 方案文件：/var/minis/shared/chat-render-composer-audit/fix-plan.md（定稿）+ FIX-TASK.md（任务书）。 |
| 08-24 09:22 | ChatScreen 渲染修复已完成并合入主号 main（2026-08-24） | 结论：fix/chat-render-turnend-settle 两个 commit（bee7cc3 + a09206a）已 ff 推送主号 main（0e68209→a09206a）。用户判定低风险直接推主号，出问题… |
| 08-24 10:52 | 首块超时「provider produced no first chunk within 30000ms」调查 + 委托派发（2026-08-24） | 测试/证据：真机日志 08-24 该错误 90 次（07:42 后集中），08-22/08-23 = 0 次真实运行；涉及 deepseek-v4-flash/gpt-5.6-luna/deepseek-v4-pro，走… |
| 08-24 11:26 | 首块超时调查（会话 1）= 发现 retry 分类不对称 bug + 路由感知超时（2026-08-24） | 结论：30s 守卫本身没错（防 live worker 误判 DEAD 是 TF-I/TF-J 正确设计），但主进程 retry 分类有确凿不对称 bug——first_chunk_timeout 抛 ModelStre… |
| 08-24 11:29 | 首块超时修复已合入主号 main（2026-08-24，会话 1 汇报后用户拍板） | 合并状态：分支 diag/first-chunk-timeout commit 62d3db4 已推送并 ff 合并主号 main（a09206a→62d3db4），main 已推送。release CI run 326… |
| 08-24 11:35 | ↳ 工具「被调用两次」修复完成：fix/tool-call-dedupe @ 73400d6（2026-08-24） | 任务来源：工具派发任务书 /var/minis/shared/tool-dup-exec-fix/FIX-TASK.md（用户报告：一个回合内大模型重复调用同一工具，客户端各执行一次，串行的第二个一直在跑/占空间）。 |
| 08-24 11:42 | 首块超时修复 release CI 全绿 + 收口（2026-08-24） | release CI run 32686587661 success，head_sha=62d3db4a 已核实一致（防假绿）。远端分支 diag/first-chunk-timeout 已删除。闭环完成：调查 → 修复… |
| 08-24 14:31 | 渲染管线审计（会话 1）发现 P1：turn-end verify 收敛守卫失效 + 测试假绿（2026-08-24） | 审计范围 bee7cc3 + a09206a（渲染管线）。报告 /var/minis/shared/recent-fix-audit/reports/report-render.md。 |
| 08-24 16:01 | FIX-A 施工完成：P1 渲染收敛守卫修复，分支 CI 全绿（2026-08-24） | 分支：fix/chat-render-verify-p1（基于主号 main 0b90cf0），commit 844b6b1，已推送主号 origin。未合并 main（等用户拍板）。分支 CI run 32703055… |
| 08-25 19:47 | rikkahub 流畅性吸收 — 施工方案已产出 | 调研（rikkahub@3ebda54 vs RikkaMinis main@62a3a7d）落地成施工方案：/var/minis/shared/rikkahub-smoothness-absorption/施工方案.m… |
| 08-25 19:53 | 首块超时掐断思考模型 bug 已修复合并 main（3eb1785，2026-08-25） | 用户报：思考型模型（reasoning/thinking）会在固定时间被掐断，报错 provider produced no first chunk within 45000ms (hadChunks=false)，后台… |
| 08-25 20:22 | rikkahub 流畅性吸收 — 施工方案已产出并派发就绪（2026-08-25） | 用户拍板要「具体可直接施工的方案」。调研（rikkahub@3ebda54 vs RikkaMinis main@62a3a7d）落地成 5 任务派发包，目录 /var/minis/shared/rikkahub-smo… |
| 08-25 20:49 | rikkahub 流畅性吸收 · 会话 B @Stable/@Immutable 纪律 — 完成（分支未合 main） | 任务：/var/minis/shared/rikkahub-smoothness-absorption/session-task-B.md，分支 fix/chat-stable-discipline（主号 OWNER，基… |
| 08-25 21:06 | 会话 A 完成：消息级聚合回归基线测试（fix/chat-render-baseline-tests） | 交付：新增 MessageItemAggregationBaselineTest.kt（10 @Test，纯 JVM）。本地 shadow 10/10 绿（单次 kotlinc 编译）；分支 CI run #1057 s… |
| 08-25 21:30 | rikkahub 流畅性吸收 · 会话 C 聚合 Item 生成器完成（分支未合 main） | 任务：/var/minis/shared/rikkahub-smoothness-absorption/session-task-C.md，分支 fix/message-node-item-generator（主号 OW… |
| 08-25 21:54 | 任务：合并 rikkahub 流畅性吸收 A/B/C 三分支到 main。基线 main 3eb1785，三分支各一 c | 任务：合并 rikkahub 流畅性吸收 A/B/C 三分支到 main。基线 main 3eb1785，三分支各一 commit 文件零重叠：A=0881981（测试基线）、B=c1925b3（SlashCommand… |
| 08-25 22:36 | rikkahub 平滑吸收 · D 任务开工准备完成（2026-08-25） | 用户确认 A/B/C 已解决，为 D（聚合 Item 渲染器 + 翻转开关，fix/message-node-item-renderer）做准备了。main tip = 2863f60（C 的 commit），前置核实全… |
| 08-25 23:03 | D 任务收口合并完成 — main = 0e07ac4（2026-08-25） | D 分支（fix/message-node-item-renderer @ 0e07ac4）已 ff 合并 main 并推送主号（2863f60→0e07ac4），远端分支已删（API 204）。main release… |
| 08-26 00:13 | rikkahub 流畅性吸收 — A/B/C/D/E 全部完成并合入 main（2026-08-25 búi 收口） | 5 阶段全部合入 main（当前 main = 4829e67），收口会话独立核实（拉取 origin/main + Actions API 交叉核对 head_sha，非转述）： |
| 08-26 01:08 | 正优化第二毛刺：聚合路径「复制普通文本失效」根因已定位（2026-08-26） | 用户反馈（正优化后续第二个小毛刺）：「复制普通文本这种功能失效了」→ 已精确定位根因，任务文件 /var/minis/shared/rikkahub-smoothness-absorption/session-task-… |
| 08-26 01:32 | 会话 G 完成：聚合路径复制普通文本修复（fix/aggregate-copy-text） | 任务：给 AssistantMessageView（ChatAssistantMessageUI.kt）的两处 StreamingMarkdownText 补 shardId，修复聚合路径（AGGREGATE_MESSA… |
| 08-26 15:39 | 长会话「输入/暂停」卡顿根因诊断（2026-08-26） | 现象：对话较长时，按暂停或输入都有明显卡顿感。 |
| 08-26 17:03 | 长会话卡顿修复：暂停卡顿（aggregate 增量）已施工完成（2026-08-26） | 任务：用户要求「长会话输入/暂停卡顿」一起修，工程量不大就一起做完。 |
| 08-26 18:22 | 收尾：4 个待合分支全部清理，main=4095abef（2026-08-26 晚） | 用户要求把所有未合并分支检查后合并。检查 + 合并结果： |
| 08-26 19:17 | 两分支合并 + 全天收尾核查完成（2026-08-26 晚续） | 用户要求把「还有两个没合并的分支」检查后合并，并把今天没收尾的一起收尾。结果：合并 2 分支 + 全天事项核查全部闭环。 |
| 08-26 22:08 | 长会话「输入卡顿」修复1 施工完成（2026-08-26 晚，分支 CI 绿） | 用户指派「长会话输入卡顿修复1」——把 ChatScreen 顶层 inputText 的 collectAsState() 订阅下沉到独立 composer 叶子，消除每次键入让整个 6277 行 ChatScreen… |
| 08-26 22:25 | 长会话「输入卡顿」修复1 已合 main（5df48e7d，2026-08-26 晚） | 主线闭环：分支 CI #1110 绿 → 用户拍板合并 → ff 合并 main（4ea10b17..5df48e7d，纯 ff 齐确认 main 未被推进）→ push main → release CI run 32… |
| 08-27 13:28 | 历史对话「打开定位到底部」第三次修复 — 诊断完成待施工（2026-08-27） | 用户报「打开旧对话要定位到底部」，之前修过两轮（8484a49「空列表吞请求」→ dbaa4aa「sentinel 可见才 consume」）仍没修好。本次诊断定位到新一层的根因。 |
| 08-27 20:21 | 停止卡顿 + 发消息卡顿根因定位与修复（2026-08-27，分支 fix/stop-lag-and-send-prompt-bloat） | 用户现象两个：①长任务后期点「停止」卡 1~2 秒才真正停；②稍长对话发消息一开始卡顿（之前缓解过但不够）。 |
| 08-27 20:54 | 停止卡顿 + 发消息卡顿修复已合并 main（2026-08-27 收尾） | 分支 fix/stop-lag-and-send-prompt-bloat 两 commit 已 ff 合并 main（a8f8c03 → 4f8245e），push 成功，main release CI run #11… |
| 09-03 21:00 | 修复：assistant 占位气泡在 FE-5 route C 拆分中丢失（2026-09-03 真机验证通过，已合并 main @ 5af0306） | 用户症状：发送指令后 AI 正常运作（日志显示 tool 调用、流式 delta、persist 全在跑）但界面不渲染回复内容；无"正在思考"指示；切后台/重启 app 后才显示内容。 |
| 09-07 20:08 | 渲染三连修交接（2026-09-07 深夜，待新会话执行） | 用户拍板：开一个新会话把三个修复一起做完（不打补丁，按不变量修）。交接文档已写： |
| 09-07 21:11 | 渲染三连修完成（2026-09-07 深夜，分支 fix/render-progress-channel @ 61e68224，待统一合并） | 用户拍板：本分支不合并 main，与另一分支统一处理（合并方：ff 合并 + release CI + 真机验证）。 |
| 09-07 21:20 | ↳ fix/browser-filechooser-gesture 施工完成（2026-09-07，待用户统一合并） | 根因：CLICK/EXECUTE_JS 全走 evaluateJavascript JS 事件 → 无 user activation → Chromium 150 对 file input 静默拒绝 → onShowF… |
| 09-10 02:49 | 第二轮审计 LOW 全收口（B22，main @ 54eade2，2026-09-10） | 结果：11 项剩余 LOW 一次做完 → 分支 fix/audit-0909-b22 CI run 34389944956 success（head 54eade2）→ ff 合并 main @ 54eade2 → re… |
| 09-10 10:09 | 验证补强：逐字抽取生产代码 + 严格解析器当判据（2026-09-10） | 问题：单测只复刻了发射形状、没跑生产代码（exportToWriter 需要 repo 依赖，沙箱编不了整文件）→ 修复的"生产性"证据不足。 |
| 09-13 14:04 | 打开会话「差一段」v2：根因锁定 + 修好待真机验证（2026-09-13 14:00） | 分支 fix/open-catchup-guard @ daab66b（v1 32571f8 + v2 一 commit；基于 main 15b3f447） |
| 09-13 14:40 | Legacy 渲染管线残留清单审计（2026-09-13，基线 daab66b） | 报告：/var/minis/shared/legacy-pipeline-audit-2026-09-13.md（扫描脚本 /tmp/legacy_scan{,2,3,4}.py） |
| 09-13 19:59 | 内存防线缺口审计（main @ 82c7925，报告 shared/memory-defense-gap-audit-2026-09-13.md） | 最重要更新：交接文档"无执行中看门狗"已过时——daab66b（随 09-13 restore 回归 main）已带 in-flight 监控（PersistentShell 1s 轮询 → midCommandRecy… |
| 09-13 20:46 | 内存加固三件套实现完成（分支 fix/memory-hardening-rss-stall，3 commits，CI #1498 success） | commits：a641e27 KaTeX 位图上限（H1）→ 45ce0c8 app 自身压力治理器（H2）→ c508896 挂死处置（用户追加要求）。沙箱 JVM 56/56 绿、仓库门禁 6/6、CI #1498… |
| 09-13 22:09 | 最近 5 commit 独立审计：干净，零改动（main @ c5088961） | 背景：用户要求扫最近 5 次修改。main 已推进到 c5088961——内存加固三件套（a641e27/45ce0c8/c508896）已由用户拍板合并（此前会话状态是"未合并待拍板"）。三件套是"实现+自测"出身、没… |
| 09-14 10:22 | 2026-09-14 日志体系"可消费性"诊断（回答用户"日志是否足够详细"）—— 详细度过剩、组织度是缺口 | 用户动机：上轮 liveness audit 让用户意识到"AI 智能判断 + 日志材料 + 用户交互"闭环，问缺口是否为"日志产生不够详细"。实测后结论：详细度已过剩（12.9 万行/17MB 半天），缺的是组织度 +… |
| 09-14 10:29 | ★★ 09-14 重大发现 + 修复：census 接错渲染器（7 分支全死）→ fix/liveness-followups-0914 @ 58620206（CI run 1510 success） | 修复内容（5 文件 +93/−44，分支 fix/liveness-followups-0914）： |
| 09-15 14:47 | 09-15 下午：markdown 解析器死循环修复闭环（#196 类行挂死，用户报大文件加载不出来） | HIGH 真 bug：parseMarkdownBlocks 死循环——标题分支要求 # 后有空格，段落循环停条件是裸 startsWith("#") → #196 @sha、#!/bin/sh、#include、#ha… |
| 09-15 14:49 | 09-15 下午：死循环修复后日志窗口分析（14:47–14:49，数据干净但复测未做） | 修复后 minis 侧 W/E = 0（仅平台噪音 WindowOnBackDispatcher ×2）。 |
| 09-15 16:20 | 09-15 傍晚：输入变异轴（fuzz）首轮跑通 — 块解析器输入空间已覆盖，零新 bug | harness：/tmp/fuzz-md/（FuzzDriver.kt + Diag.kt + Bisect.kt + MinRepro.kt + Stub.kt[looksLikeMath/findInlineMath… |
| 09-15 16:30 | 09-15 晚：轴 2（行内层纯函数 fuzz）收口 — 186/0 绿，重大抽取纪律抓漏 | 范围：StreamingMarkdownText 行内层 5 个纯函数（safeInlineSplitOffset / findInlineCodeClose / inlineMathSizeEm / collectIn… |
| 09-15 16:36 | 09-15 晚：轴 3（reducer 状态机矩阵 + 随机序列 fuzz）收口 — 零 bug，零违规 | harness：/tmp/fuzz-md/ReducerMatrix.kt + ReducerFuzz.kt，生产 AgentRunReducer.kt + AgentRunState.kt 逐字编译（零改动）。 |
| 09-15 18:05 | 09-15 晚：+1545 死循环修复真机复测通过（收口） | 用户装包实测大文件（dev-history 档案）不挂 → markdown 解析器死循环修复（ad7c9e3f）真机验证通过，§14/§15 之外的最后验证缺口关闭。 |
| 09-16 18:17 | 09-16 晚：23c-2 渲染期 resolve + 灰链收口 — main = 3df7977e（release CI 35084162878，用户拍板不等） | 改动：ChatLinkRenderCache（LRU 256，键 (url,sessionId)，resolveFn 注入 → JVM 可测）+ LocalMarkdownLinkRenderResolver Compo… |
| 09-17 09:08 | 聚合方法 | 09-17：审计函数聚合结果 — 无单点杠杆（重要结论） |
| 09-17 18:13 | 09-17 傍晚：存储页转圈 + markdown 列表误渲染双修复 → main = 40a58c94 | 用户报告两件事，都实锤： |
| 09-17 20:18 | ↳ 09-17 收尾：全天工程量统计（用户问"为什么感觉工程量大"时的硬数据） | main 交付量（SGT 09:41→17:55）：33 提交 / 147 unique 文件（全仓 ~524 文件的 28%）/ +3031 −577 行；CI 今天 43 轮构建（29 绿 / 9 红 / 5 取消，… |
| 09-17 21:01 | 09-17 深夜：★"双胞胎解析器"——聊天列表误渲染的真正根因与修复（main = 14ca90e3） | 事件：用户真机复现"4. 分钟"（昨晚 40a58c94 记的"已修"无效）→ 追查发现 app 有两套 markdown 解析器： |
| 09-17 21:04 | 09-17 深夜收口：聊天列表误渲染修复真机验证通过（main = 14ca90e3） | 用户装机 1.0.0+1630（lastUpdateTime 21:02:03，= CI #1630 artifact / 14ca90e3 树）后确认：样本行显示为普通段落，符合预期 → "双胞胎解析器"修复实锤生效。… |
| 09-19 21:14 | 09-19：offload 审计第 17 棒完成（小包扫尾 + app 根文件 + `shared/` = 40 文件 / 8,275 行 → F-170…F-176 · O-29…O-43） | 下一棒：HANDOFF-offload-18th.md —— P1 = provider/ 11,202 行（唯一带生产日志活体证据的未扫区域：当天 14× stream parse exception: Cancell… |
| 09-20 02:26 | 09-20 凌晨：offload 审计第 21 棒 **并发线 A3** 完成（`provider/thinking/` + `provider/voice/`） | 产出：/var/minis/shared/offload-audit-0919/wave2-a3/（report.md + ledger-a3.json + verify_a3.sh + exp_a3/）。 |
| 09-20 03:26 | 09-20：offload 审计 WAVE-2 并发线 C2 完成（`ui/chat/` 渲染与文本组件） | 身份：第 21 棒并发线 C2（只读，仓库 0 改动，HEAD = 99783703 / 锚点 c6d8d63f）。 |
| 09-20 05:03 | 09-20：offload 审计修复批次 FIX-4-render-ui 完成（渲染/UI 组件层） | 产出：/var/minis/shared/offload-audit-0919/fix-out/fix4/ —— REPORT.md · FIXED-F-255/256/257/262/270…277.md（12 份，各… |
| 09-20 09:21 | 09-20：FIX-WAVE 修复批次并行 → 统一合并收口（第 22 棒，main = `6a10661`） | 起点：读 HANDOFF-offload-21st-TO-DISPATCHER.md（第 21 棒交接包），用户追加背景「云端 main 已被推进」，要求「核实聚合信息 → 分配任务 → 各自分支 → 统一合并」。 |
| 09-20 13:56 | 09-20 下午：云端三分支核查 → 合并 2 个（main = `9c4ccfb8`），**扣下 T2**（证据：改在 runtime-dead 链上） | 三个分支同基 d11a4c1b，三次 merge 全部 ort 自动、零冲突（fab 与 diag 共改 ChatScreen.kt 相邻区域也不冲突）——「文件重叠 ≠ 文本冲突」再次成立。 |
| 09-20 13:58 | ↳ 09-20 收尾：`fix/streaming-degrade-live-text` 已按用户决定删除（远端 + 本地） | 用户拍板「既然如此，那就把它去掉吧」——无效修复不留枝。至此远端只剩 main = 9c4ccfb8（fab 修复 + diag 探针），三分支全部收口。 |
| 09-20 17:11 | 09-20 晚：F-255 活体流式文本冻结修复 → 分支 `fix/f255-live-text-observable` @ `1478c0ae`（**未合并，用户计划与另两个分支统一合并**） | 用户症状：一条回复只渲染出开头几个字，其余永不出现；切走再切回会话能看到完整答案。（「轻版」回归，非当年冻屏） |
| 09-20 17:57 | 09-20 晚：云端三分支核查 → 全部合并进 main = `27eced19`（CI run 35503040884 success） | 用户指令：检查云端三个分支有没有引入 bug，没有就合并。 |
| 09-20 18:02 | ↳ 09-20 晚：三分支合并的真机验证 —— 用户确认全部通过 | 用户反馈：「修改，验证已完成，都没有问题。」 |
| 09-20 19:31 | 09-20 夜：上下文超限后 agent loop 空重试死循环 → 修复分支 `fix/context-exhausted-loop` @ `55703df9`（CI 绿，**未合并**，用户要求等统一处理） | 任务书：/var/minis/shared/hang-0920/session-task-ctxloop.md（要求：先复核四点 → 三方案分析 → 实施推荐 → JVM 复现 + 反向对照 + scan + CI → … |
| 09-20 20:02 | 09-20 夜：思考期间输入框卡顿 —— 根因 = ThinkingDelta 分支缺引擎级节流（分支 `fix/thinking-delta-main-thread-throttle` @ 37e18fd2，**按用户要求不合并**） | 用户报告：「大模型思考时性能消耗很大，这时用输入框明显卡顿；尤其在有比较大上下文的长对话中」。 |
| 09-20 23:51 | 09-20 深夜：T2 线（regression-audit-0920）取证收口 —— 任务书前提被证伪，按修复门纪律不动手 | 结论：/var/minis/shared/regression-audit-0920/tasks/T2-degrade-restore.md 的四条事实前提全部不成立。产出 /var/minis/shared/regre… |
| 09-21 10:27 | 09-21：记忆文件查看卡顿排查 → 根因是编辑路径无懒加载（整份文件塞进一个 BasicTextField） | 用户报告：记忆中单个文件超 ~130KB 后上下滑动严重卡顿。 |
| 09-21 11:02 | 09-21：记忆文件卡顿修复 → 分支 `fix/memory-file-jank` @ `7e89b81`（CI 绿，**按用户要求不合并**） | 用户指令：「修吧，就是分支上跑完了，不要合并。」 |
| 09-21 13:11 | ↳ 09-21：云端两分支审查 → 发现 2 真问题 → 修复 → 合并 main = `a081080c` | 任务链：用户「检查云端两个分支有没有 bug」→ 发现 2 处 → 「修吧」→ 「跑完没问题就合并」。 |
| 09-21 13:25 | ↳ 09-21 补：两分支修复经**真机验证通过** | 用户确认：fix/memory-file-jank 的空行修复 + fix/config-prefs-listener-gc 的闸门修复，真机验证没有问题。 |
| 09-21 16:34 | 09-21 会话 A（fix-dispatch-0921）：#341 WebView 渲染进程死亡修复完成 → 分支 `fix/webview-render-process-gone` @ `855cc26d`（CI 绿，**按指令不合并**） | 任务书：/var/minis/shared/fix-dispatch-0921/task-A-webview-341.md（派发目录里还有 task-B-p1-trio.md，是别的会话的活，未动）。 |

## 11. 审计 / 整改（多轮）

**跨度** 2026-08-03 ～ 2026-09-22 · **226 条** · **状态** 常态机制（已固化为 security-audit-checklist / four-way-sync-check 等 skill）

**叙事**：08-05 首次全量代码审查 → 08-16 系统性审计 → 08-18 RC1–RC17 两轮整改（多会话并行）→ 08-26/27 模块审计批 → 09-02 bug-hunt 四会话 → 09-04 diff 驱动定向审计 → 09-09 全局第二轮 T1–T12 → 09-13 完善度横向扫描 → 09-14 度量存活度 → 09-15 七轴 sweep。**审计是本项目最稳定的工程节奏**：每次改动后必有一次独立审计，且审计本身产出纪律（skill）。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 21:47 | OpenMinis fork — 测试 backlog 清理 + 动态版本 + 上游同步（2026-08-03 进行中） | 分支 feat/test-backlog-version-sync（4 个提交 ca4a7e2/791e543/2b6ec3c/…），CI run 30819582646 验证中。 |
| 08-05 07:48 | RikkaMinis 功能完整性审计报告（2026-08-05） | 用户判断"没什么可加了，加任何功能收益都<临界值"。我做了完整审计，结论：判断基本成立。 |
| 08-05 15:01 | CF 小号技能化 + token 安全迁移完成（2026-08-05） | Cloudflare 小号 token 已从明文迁移到环境变量 |
| 08-06 13:31 | 语音功能清理 — 已合并 main（093d13b） | 删了 |
| 08-07 08:31 | RikkaMinis 审计修复（2026-08-07，分支 fix/audit-2026-08-07） | 基于 08-04~08-07 系统审查的实施批次，已推送分支 + 触发 CI。 |
| 08-07 18:16 | RikkaMinis 稳定性回归审计 — HEAD 75cd067（2026-08-07） | 用户想确认当前版本是否有回归、过去修的问题会不会复发。做了系统性回归审计（代码 + CI + release 三重验证）。 |
| 08-08 00:12 | 终端沙盒模块审计 — 2026-08-08 待修清单 | 审计范围 |
| 08-08 18:09 | 模型选择器改圆形按钮（feat/circular-model-picker）2026-08-08 | 用户反馈"对话框显示模型名称的应该改成圆的，为什么还是原来的样子"。 |
| 08-08 21:28 | 教训：平台技能判定不能用 importSource | 平台集成卡片的筛选条件不能用 importSource == BUNDLED——老用户的技能可能是通过 SESSION/FILE 等途径安装的，installBundledSkills() 在版本号已 ≥ 捆绑版时会 s… |
| 08-09 15:33 | 模型组上下文限制硬生效 — feat/context-limit-enforce（CI run 31301106282 success） | 用户问题：设模型组 contextLimitTokens=128K，Token Usage 面板 Context Used 仍超 128K（能到 200K+）。 |
| 08-09 17:37 | ProviderConnectionScreen Custom Base URL 占位符修复（2026-08-09） | 用户报：添加 Gemini 提供商后，详情页「API & Connection」子页的自定义 API 地址占位符仍显示 https://api.example.com（对 gemini 无意义）。 |
| 08-10 13:55 | Circuit 正式定名 + 交接归档（2026-08-10 收尾） | 从 RikkaMinis 抽取的"自我修改能力"最小核心，走完全程： |
| 08-11 08:14 | 多端自动同步流量审计（用户发现方案欠考虑） | 用户指出 MultiDeviceSync 方案在坚果云免费版下会撞流量限制。查证结论： |
| 08-12 01:49 | 交叉验证纪律写入 GLOBAL.md（2026-08-12） | 用户纠正了对"17秒幻觉"的归因：根因不是"记忆自证循环"，而是没有交叉验证。教训已写入 GLOBAL.md「问题核查纪律：交叉验证法则」——任何待处理问题必须三源取二（用户亲述/独立实测/客观证据）才认定为事实，单一来… |
| 08-13 04:17 | 任务 2 完成：OAuth Token 存储安全检查（fix/oauth-secure-storage） | 审计结论：OAuth token 存储已是加密的，无需修改加密方案。 所有 token 存储路径均使用 EncryptedPrefsFactory.safeCreate() → EncryptedSharedPrefer… |
| 08-13 09:07 | 清理 macro + recovery 已完成并合并到 main | 分支：cleanup/cut-macro-and-recovery → main e8abd4c（PR #1，squash merge） |
| 08-13 09:12 | ↳ 任务 B：流式文本原始层合并保护 — 完成 ✅ | 分支：fix/stream-text-merge → main 8b8f788（CI run 31656520277 ✅ success，run 31655970156 ✅ success） |
| 08-13 12:13 | Circuit 自进化实验（2026-08-13 11:06-12:13，无限额度临时密钥） | 实验设定 |
| 08-14 00:03 | 备份模块审计完成 — backup-module-audit-plan.md（2026-08-13） | 用户要求审计备份模块（不动代码，出方案给其他模型施工）。审计产物：/var/minis/workspace/backup-module-audit-plan.md（21KB，11 个问题 + 分支规划 + 验证清单）。 |
| 08-14 00:17 | 备份模块审计二次检查 — P0-1 误判修正（2026-08-13 深夜） | 重要修正：第一版报告 P0-1「聊天还原级联删除消息」是误判。二次检查下载了 Room 2.6.1 + androidx.sqlite 2.4.0 源码确认： |
| 08-14 00:25 | UI 模块审计 + 三修复分支全部合并 main（2026-08-14） | 任务：用户要求审计 RikkaMinis UI 模块（140 文件 7.15 万行），产出施工方案交其他模型，随后改口"开始干吧"由本会话直接施工。 |
| 08-14 20:07 | C 类审计完成：请求构造错误分类排查（2026-08-14，对话 3） | 任务来源：/var/minis/shared/request-construction-error-audit.md 分对话清单，本会话负责 C 类（上下文管理边界：C5 loadSession 重载往返 + C6 ma… |
| 08-15 11:23 | T7-B 完成：资源 lease trace + finally 清理 — 已合并 main（f93268f，2026-08-15） | T7-B：接资源 lease 和 finally 清理（中间层） |
| 08-15 19:17 | 字体大小设置代码审计（2026-08-15） | 用户要求检查 Settings → Appearance → Font Size 是否有 bug。审计 main a1354d5，报告在 /var/minis/shared/font-scale-audit-202608… |
| 08-16 08:59 | RikkaMinis 系统性代码审计（main 681bb18） | 完成代码层系统审计，报告：/var/minis/shared/rikkaminis-code-audit-20260816.md。确认：P0 公开 minis://open_terminal?init_command= … |
| 08-16 09:38 | 审计修复合并 main 完成（2fcc96c） | fix/audit-p0-security-boundaries（14 文件 +328/-64）已 ff 合并 main（681bb18→2fcc96c），分支 CI run 31919378138 绿；main rel… |
| 08-16 09:44 | rikkaminis-dev-history.md 三次重建 + 敏感内容脱敏（2026-08-16 上午） | 用户要求更新挂载目录 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md（此前覆盖到 08-15 19:01），并提醒敏感内容处理。 |
| 08-17 18:02 | 清理：移除 Settings 页 Tier0 测试入口 + 治标分支清理（2026-08-17） | 用户提出：Settings 最底下的测试入口（"Crash Test (temp)" + "Trigger Native Crash" 按钮）是 Tier0 临时验证遗留，该处理。已完整删除： |
| 08-17 18:30 | 三合一清理分支 chore/three-cleanups（2026-08-17 晚） | 分支 chore/three-cleanups（基于 main 75015ca，仓库 /tmp/rikka-membudget-merge）三项改动已全部完成，待 CI 验证： |
| 08-17 19:24 | ✅ 三合一清理分支 chore/three-cleanups 全链路闭环（2026-08-17 晚） | 三项清理全部完成并合并 main f1d02fb： |
| 08-18 01:56 | 上下文窗口来源治理 + 组为准 + iOS-parity 上下文已满弹窗(fix/context-window-sources) | 用户痛点(A + C): |
| 08-18 02:49 | ✅ 上下文窗口治理闭环完成(fix/context-window-sources → main a0b03e8) | 发布状态:分支 CI run 32052515965 success(scan gate + 1653+ 单测 + APK 构建全绿)→ ff 合并 main(70bb88b..a0b03e8)→ main releas… |
| 08-18 14:21 | RikkaMinis 全量审计已派发（2026-08-18） | 总控会话（当前）负责收口，不爬代码。 |
| 08-18 14:33 | T01+T11 审计完成（2026-08-18，A 会话，main@500c5fa） | 本会话（总控派发的 A：T01 运行时 + T11 测试体系）只读审计完成，报告已写： |
| 08-18 14:33 | T06+T07 只读审计会话（2026-08-18） | 认领 F 组：T06 安全与信任边界 + T07 浏览器/文件/WebApp/分享。 |
| 08-18 14:39 | T02+T09 只读审计完成（2026-08-18，B 会话，main@500c5fa） | 执行者 B 会话，报告 /var/minis/shared/rikkaminis-audit-2026-08-18/reports/T02.md 和 T09.md。 |
| 08-18 14:40 | E 会话闭包：T05（数据/配置/备份）+ T12（架构所有权）只读审计完成（2026-08-18） | 基础：main@500c5fa 已确认，仓库 /tmp/rikka-src，只读，未改任何源码（无脚本，纯 rg/sed 静态审计）。 |
| 08-18 14:48 | RikkaMinis 审计 T03+T10 完成（2026-08-18，会话 C） | 基线 500c5fa 确认（工作树干净）。报告： |
| 08-18 15:00 | T06+T07 审计完成（2026-08-18，F 会话，main@500c5fa） | 报告已写：/var/minis/shared/rikkaminis-audit-2026-08-18/reports/T06.md 和 T07.md。 |
| 08-18 15:06 | 全量审计总控收口完成（2026-08-18） | 用户把 RikkaMinis 全量检查交给其他分会话执行，我在本会话收口。12 份报告全部到位，验收通过。 |
| 08-18 15:10 | 整改施工派发包已就绪（2026-08-18） | 用户决定把审计整改改成「分派到其他会话」模式（同审计的协作方式）。我已在总控会话把 6 个 P1/P2 根因簇（RC1/RC2/RC4/RC5/RC6/RC7）全部定位到精确文件和行号，写入 /var/minis/sha… |
| 08-18 15:12 | RC 整改执行指令（分会话凭编号即可领取，勿需用户贴长文本） | 用户约定：以后派发整改任务，只需发编号（RC1~RC6）。分会话 agent 收到编号后，到下面对应条目领取完整执行指令，并自查「通用纪律」。总控在本会话收口。 |
| 08-18 15:54 | RC4 FGS wakelock 解耦完成（2026-08-18） | RC4（FGS wakelock 与 active 流式解耦，P2）完成并分支 CI 全绿。 |
| 08-18 15:59 | RC3 整改完成（2026-08-18，独立会话） | 分支 feat/agent-loop-direct-tests，commit 39664b4，CI run 32112439134（build job 25 steps 全 success，含全量 testRelease… |
| 08-18 15:59 | RC6 发布 concurrency + 签名身份 已完成（2026-08-18） | 分支 fix/audit-rc6-release，commit c6a0285（仅改 .github/workflows/build-apk.yml，+28 行），CI run 32111869229 success（2… |
| 08-18 16:04 | RC1 minis:// 路径解析规范化完成（2026-08-18） | 分支 fix/audit-rc1-path-normalize，commit 0bf4574，CI run 32113515882 success（build job 25 steps 全绿）。 |
| 08-18 16:11 | RC2 流式断流截断标记完成（2026-08-18，独立会话） | 分支 fix/audit-rc2-truncated-detection，commits c942aae(3 provider 改) + 3ee6487(Gemini 测试修)，CI run 32113869899 su… |
| 08-18 17:01 | 6 个 RC 整改全部合并 main 完成（2026-08-18 收口） | 用户把审计整改按编号派发到其他会话，全部完成后本会话统一合并收口。 |
| 08-18 18:55 | 后端二轮扫描完成 + RC7 收口（2026-08-18 晚） | RC7 最终状态 |
| 08-18 19:10 | RC17 拍板走 A（2026-08-18 晚） | 用户拍板 RC17 用 方案 A（备份 apiKey 维持 Base64 明文 + 加警示文案，不做口令加密）。已把 RC17A 补进 ROUND2-FIX-DISPATCH.md 为可执行 RC（纯文案改动）。 |
| 08-18 19:25 | RC14 分享累计上限实例字段化 完成（2026-08-18，独立会话） | 领取编号 RC14，独立 clone /tmp/rikka-rc14（origin 直连 GitHub），基线 main@fcf9470（派发文件写的 1aef2e9 已被前端推进，但 RC14 文件 ShareRece… |
| 08-18 19:33 | RC17A 备份警示文案完成（2026-08-18 晚，独立会话） | 用户拍板走方案 A（备份 apiKey 维持 Base64 明文 + 加警示文案，不做口令加密）。本会话完成纯文案改动。 |
| 08-18 19:34 | RC12 整改完成（2026-08-18，独立会话） | 分支 fix/audit-rc12-debugserver-auth，commit 8be02d5，CI run 32131251740 success（build job 22 steps 全绿，含全量单测 + ins… |
| 08-18 19:35 | RC11+RC13 整改完成（2026-08-18，合并会话） | 分支：fix/audit-rc11-rc13-browser（RC11 与 RC13 同会话合并做，两 RC 同文件 BrowserUseManager.kt 不同函数） |
| 08-18 19:47 | RC16 — MultiDeviceSync 先拉后推覆盖竞态（乐观锁）完成（2026-08-18 晚，独立会话） | 分支 fix/audit-rc16-sync-if-match，commit fe0a43f（+ 首跑测试修复前 e8369a2），CI run 32132091706 success（25 steps 全绿）。回报 r… |
| 08-18 19:57 | ↳ FE-4 route A+B 完成:ChatViewModel 纯函数抽取(2026-08-18) | 用户领取 FE-4 任务(ChatViewModel 12158 行拆分),走交接文档的"路线 A:先抽无状态纯函数"了路线,零回归闭环,CI 绿(run 32133152371),未合并 main(等用户拍板)。 |
| 08-18 20:02 | 二轮整改 6 RC 合并 main 完成（2026-08-18 收口） | 合并结果：main fcf9470 → 86ec803，6 个分支全部三方合并（ort，零冲突），main release CI（run 32133842603）success，远端分支全部删除。 |
| 08-18 20:30 | 交叉验证 + 僵尸分支清理（2026-08-18 深夜） | 交叉验证结论（git merge-base --is-ancestor 逐一确认）： |
| 08-18 20:48 | RC10 完成（2026-08-18，独立会话） | 深链 minis://session/<sid>/<path> 路径穿越整改闭环，CI 绿（run 32137371990 success），未合并 main、未删分支（等总控 ff）。 |
| 08-18 21:03 | RC10 深链路径穿越整改闭环（2026-08-18 深夜） | 分支 fix/audit-rc10-deeplink-traversal，commit 8efef27，CI 绿（run 32137371990 success），已 ff 合并 main a4369d3 → 8efef… |
| 08-18 21:23 | ↳ FE-4 纯函数扫尾(层次1)合并 main 2026-08-18 | FE-4 第三波(层次1扫尾)合并 main 8efef27 → 2231857,release CI 绿(run 32140809865),分支已删。 |
| 08-18 22:01 | RC15 — sort_order 唯一索引整改完成（2026-08-18，独立会话） | 分支 fix/audit-rc15-sort-order-unique，commit 988dfac，CI run 32144384034 success（build 25 steps 全绿，Publish skippe… |
| 08-18 22:17 | RC15 — sort_order 唯一索引整改完成（2026-08-18，独立会话） | 分支 fix/audit-rc15-sort-order-unique，commit 988dfac，CI run 32144384034 success（build 25 steps 全绿，Publish skippe… |
| 08-20 16:40 | bug-hunt 2026-08-19 最终收口（2026-08-20 16:40） | 6GB native OOM 事故完成「止血 → 定位 → 并发上限放宽验证」三阶段闭环，全部收口。 |
| 08-20 17:11 | ↳ 并发会话上限放开（2026-08-20 收尾） | 用户要求把并发会话上限「彻底放开」，不要最高只能是 4。理由是应用已足够稳定。 |
| 08-21 23:01 | 内存隔离 v2 五 Phase 代码审计发现（2026-08-21 独立会话审计 main 332f30e） | 用户要我审计已合并 main 的原生内存隔离 v2 施工（Phase 0-5 全在，release CI run 32492787202 success）。方案文档 workspace 被重置丢了，靠 shared/na… |
| 08-22 19:08 | TF-C 大对象序列化审计完成（2026-08-22 晚） | 分支 fix/modelservice-file-payload-audit（基于 chore/dual-appid 5672ca3），CI run 32568646560 绿（head f20335e，build jo… |
| 08-22 19:21 | ↳ TF-B 可靠 worker 生命周期完成（会话 B，2026-08-22） | 分支 fix/modelservice-terminal-protocol（基于 chore/dual-appid 5672ca3，tip f879e9d），分支 CI run 32569478673 全绿（+795/-… |
| 08-23 14:29 | 会话 A（bug-hunt-pressure / session-task-A）中途结束：用户判定意义不大 | 用户明确取消会话 A 的「agent 多轮流式 + worker 生命周期压测」任务，理由：这部分日常使用几乎都会遇到，有问题他能立刻感知，压力测试意义不大。 |
| 08-23 14:47 | 会话收敛：bug-hunt-pressure 收口完成（2026-08-23 晚） | 5 条攻击面全跑完，beta.41（a6b2665）心跳修复未复现假死（logcat 678 万行 0 命中 worker died/proc_missing/DIED/beat_stale）。挖出 4 个确凿 P0 +… |
| 08-23 14:49 | 修复任务派发就绪（bug-hunt 收敛后，2026-08-23） | 对 beta.41（a6b2665）压测出的 P0/P1，已把修复拆成 5 个可并行会话，产物在 /var/minis/shared/bug-hunt-pressure/： |
| 08-23 17:43 | bug-hunt 五修复真机验证通过（2026-08-23 收口完成） | 用户装 beta.50（versionCode 220000050 = main 15ca95c，run 32629219062 绿）后真机验证： |
| 08-23 18:53 | ↳ browser get_text 大文本 ANR 修复真机验证通过（2026-08-23 晚） | 用户装 beta.52（versionCode 220000052 = main 3576528，run 32634077568 绿）后验证： |
| 08-24 07:49 | ChatScreen 显示/发送链路审计（2026-08-24，main 0e68209） | 用户报两现象：①大模型显示文本偶尔有问题 ②对话框变大后输入+发送变卡。全链路审计（ChatScreen/ChatViewModel/ChatFlatItems/StableChatRowLedger/AppendOnl… |
| 08-24 13:40 | 近期修改审计派发（2026-08-24） | 审计范围 a6b2665..0b90cf0（约 30 commits，2 天改动）。初筛发现 1 个确凿问题（SanitizeAgentHistory.kt println 替代 Log.w）+ 3 个灰色区域。已派发 … |
| 08-24 14:20 | 会话 2 审计完成：工具去重 commit 0b90cf0（2026-08-24） | 审计 /var/minis/shared/recent-fix-audit/reports/report-dedupe.md。结论：无 P0/P1 功能 bug，去重逻辑闭环正确。3 处 🟡 + 1 处日志回归： |
| 08-24 14:21 | 会话 4：bug-fix 批量审计完成（2026-08-24） | 对 15ca95c octopus merge 的 5 个修复 commit 逐项审计（只读，只读，零冲突）： |
| 08-24 14:31 | 渲染管线审计（会话 1）发现 P1：turn-end verify 收敛守卫失效 + 测试假绿（2026-08-24） | 审计范围 bee7cc3 + a09206a（渲染管线）。报告 /var/minis/shared/recent-fix-audit/reports/report-render.md。 |
| 08-24 14:38 | 审计收口：4 会话报告已收齐，施工方案已定（2026-08-24） | 4 个审计会话全部完成，结论：仅 1 个 P1 必修（渲染管线 reconcileAndVerifyTerminalText 收敛守卫失效——settled 后同长重写时 segmenter absorbDivergen… |
| 08-24 15:51 | ↳ FIX-B 施工完成：低风险收尾修复（2026-08-24） | 分支：fix/sanitize-firstchunk-cleanup（主号 OWNER/RikkaMinis，基于 0b90cf0），commit c78bcca，分支 CI run 32702069207 succes… |
| 08-24 16:06 | 审计→修复→合并 全闭环完成（2026-08-24） | 4 会话审计收口后拆 2 个施工会话，均已合并 main： |
| 08-25 22:45 | ⚠️ 记忆更正：D 任务已完成（此前误标）— 2026-08-25 交叉验证 | 更正：此前记忆「D 任务开工准备完成、D 可开工」是错的。用户第一手指出「D 做完了」，交叉验证（git ls-remote + 分支 + CI API）确凿证实： |
| 08-25 22:50 | 终端 RSS 泄漏 — 源码定位审计结论（2026-08-25 深夜） | 审计范围：/tmp/RikkaMinis（main tip 2f3498f）的 NativeOffload.kt / ExecutionCoordinator.kt / PersistentShell.kt / Offl… |
| 08-26 02:55 | 最近改动 bug 审计（2026-08-26，用户要求"抓 bug + 施工方案"） | 审计 main 7f68752 及前 3 个 commit。报告 /var/minis/shared/recent-changes-bug-audit-2026-08-26.md。 |
| 08-26 03:29 | 超时分层 + 清理 两个施工任务已合并 main（0ba797a，2026-08-26） | 已完成两分支合并： |
| 08-26 16:45 | 模型组模块审计修复已合 main（01df5e7，2026-08-26 晚） | 任务：用户要求审计「模型组」模块找 bug 并修。 |
| 08-26 17:41 | 人格(Soul)模块审计+加固完成（fix/soul-hardening → 分支 CI #1095 绿，未合 main 等拍板） | 用户要求审计「设置→人格」模块并修复/优化，6 个原发现 + 补测时又挖出 2 个真 bug，全部修完。 |
| 08-26 17:46 | MCP 模块审计修复完成（2026-08-26） | 用户要求检查设置里 MCP 模块并修 bug。审计了 MCPRepository/MCPIntegrationsScreen/SessionMcpsSheet/OAuth 四件套 + CLI（minis-mcp-cli）… |
| 08-26 18:22 | 收尾：4 个待合分支全部清理，main=4095abef（2026-08-26 晚） | 用户要求把所有未合并分支检查后合并。检查 + 合并结果： |
| 08-26 22:47 | 技能+权限模块审计修复完成（分支 CI 绿，未合 main 等拍板） | 用户要求审计「设置→技能」「设置→权限」两模块并优化，全部做完（branch fix/skills-permissions-polish @ 4f51c8b，分支 CI run #1112 success，head 核实… |
| 08-27 09:25 | 任务06审计完成：crash+diagnostics+offload+杂项（main 4f51c8b0） | 报告 /var/minis/shared/module-audit-batch/reports/report-crash-diag-offload-misc.md |
| 08-27 09:32 | backup 模块审计（task-04，module-audit-batch） | 模块 com.openminis.app.backup（5 文件 / 2197 行）系统审计完成，报告 /var/minis/shared/module-audit-batch/reports/report-backup… |
| 08-27 09:39 | debug 模块系统审计完成（任务02，2026-08-27） | 模块 com.openminis.app.debug（12 文件/5324 行 + 2 测试）只读审计完毕，报告在 |
| 08-27 09:40 | 任务05审计完成：speech + webapp 模块（2026-08-27） | 审计 /var/minis/shared/module-audit-batch/tasks/task-05-speech-webapp.md，报告已写 /var/minis/shared/module-audit-bat… |
| 08-27 10:34 | 模块审计批 · 修复任务 5 完成（service+notification 清理，分支 CI 绿未合 main） | 分支 fix/service-notify-cleanup，commit 3a147edae5a571ddf07e897baa15ffe1d39fecfe，基线 main 4f51c8b0，分支 CI run #1115… |
| 08-27 10:36 | fix-04 diagnostics/offload 死代码清理收尾（2026-08-27） | 分支 fix/diagnostics-offload-deadcode（commit d7478fe，基于 main 4f51c8b0），CI run 33033020935 success，head 核实一致。未合并 … |
| 08-27 10:36 | fix-03 debug 凭证脱敏完成（2026-08-27） | 任务 /var/minis/shared/module-audit-batch/fixes/fix-03-debug-redact.md（🟡 P2）施工完成，分支 fix/debug-credential-redact … |
| 08-27 10:48 | ↳ fix-01 backup 条目 id 顺序漂移修复完成（2026-08-27） | 模块审计批 fix-01（backup entry-id 顺序漂移，🔴 P1）施工完成，分支 fix/backup-entry-id-order-drift @ 717a858a，CI run #1118 success… |
| 08-27 11:25 | 模块审计批 6 修复合并收尾：main = 8a6a01bc（2026-08-27 上午） | 6 个修复分支全部 ff/cherry-pick 合并 main（4f51c8b0 → 8a6a01bc），release CI run #1120 success，head=8a6a01bc 核实一致，远端 6 分支全… |
| 08-30 00:42 | 扫描修复包四任务全部收口（2026-08-30 凌晨） | main 从 ea096be 推进到 d49235c（A→B→C→D 四 commit 依次 ff），release CI run 33262714099 success（head_sha=d49235c 核实一致）。四… |
| 08-30 15:08 | RikkaMinis 收尾：安全止血 + 开源 + 封存（2026-08-30） | 用户诉求：开发收尾，把开发数据丢云端封存当备份 + 开源开发历史。过程中发现并处理了一个安全泄露。 |
| 09-02 11:14 | session4 浏览器/沙箱层审计完成（rikka-bug-hunt） | 审计 /tmp/rikka 的 browser/ 全目录 + ExecutionCoordinator.kt + RootfsManager.kt，报告：/var/minis/shared/rikka-bug-hunt/… |
| 09-02 12:16 | session2 执行层审计完成（rikka-bug-hunt） | 审计 /tmp/rikka（@de2dca7d）6 文件（ChatViewModel 12338 / ModelExecutionService 1458 / ModelExecutionDispatcher 421 /… |
| 09-02 12:26 | Bug-Hunt 四会话审计收口（2026-09-02） | 四会话并行审计 /tmp/rikka @ de2dca7d：HIGH 11 报出 / 10 实锤 1 误报，MEDIUM ~15 实锤。收口报告：/var/minis/shared/rikka-bug-hunt/repo… |
| 09-02 12:27 | session2 执行层审计完成（rikka-bug-hunt） | 可复用教训：①「写侧序列化、读侧没接」是跨进程协议缺口的高发形态，audit 时要对每个 buildRequestJson 写的键在 Service 两侧（executeRun/executeStreamingRun）各… |
| 09-03 19:34 | FE-5 bug-audit 全库扫描 + 修复收口（2026-09-03） | 全库 678 文件 / 199,705 行 / 9.2MB，7 域并行审计（deepseek-v4-flash-0731 带工具 + R 类拆分规则库）。报出 High 7 / Medium 19 / Low 13 = … |
| 09-04 10:35 | Diff 驱动定向审计完成（a1abcb6b..07d63699，4 实锤，未修复待用户拍板） | 审计 main 在全库审计（a1abcb6b）后的 7 个提交（subagent 开关 + auto-compact + 占位气泡修复），+328 行新代码。报告：/var/minis/shared/fe5-bug-au… |
| 09-04 21:23 | feat/thinking-rules-port 分支审计（2026-09-04） | 范围：main(7aea092d)→f7865b2b，2 commits +3440/−217（thinking 规则引擎 port + Phase 2 自定义规则）。CI f7865b2b run 3387392799… |
| 09-05 13:23 | thinking-gap-close-0905 分支审计（f134fd02，未合并）——2 HIGH 实锤 | 仓库 /tmp/rikka-clone（HEAD feat/thinking-gap-close-0905）。+2247/−76，47 文件。审计报告见本会话回复。 |
| 09-05 14:05 | thinking-gap-close 审计修复已合并 main @ 392783a0（2026-09-05） | 分支 feat/thinking-gap-close-0905 审计后修复（commit 392783a0，16 文件 +302/−30），分支 CI 33948346008 success（head_sha 核对一致）… |
| 09-06 12:15 | 知识图谱备份安全性核查（2026-09-06）—— 图谱数据不会被备份导出 | 用户问"删应用前做备份，图谱那部分会不会也被备份"。代码级核查结论： |
| 09-06 17:15 | Hermes Agent（Nous Research）吸收分析（2026-09-06） | 源码 /tmp/hermes/hermes-agent-main（20.6 万行 Python），报告 /var/minis/shared/hermes-agent-absorb-analysis.md。 |
| 09-07 03:35 | 两日改动审计闭环（2026-09-07 凌晨，main @ 2c32d726） | 范围：09-06/09-07 共 13 提交 47 文件 +3521/−131（并发/流恢复/content-filter/验证守卫/hermes guards/负载均衡/claim epoch/worker 规则）逐行… |
| 09-07 08:33 | ARCHITECTURE.md + dev-history 0907 同步（2026-09-07，main @ 3356a4cd） | 用户要求「做架构全貌文档 + 更新仓库文档」已闭环： |
| 09-07 10:52 | ↳ ChatScreen 拆分批次 1 合并 main（2026-09-07，main @ cf8d8a93） | 用户要求拆分四个超大文件（ChatScreen 6439 / ChatViewModel 3782 / StreamingMarkdownText 3754 / OpenAIProvider 3174），评估后按四批推进… |
| 09-07 11:23 | ↳ ChatScreen 拆分批次 2 合并 main（2026-09-07，main @ 16c20c08） | StreamingMarkdownText.kt 3755 → 3017 行，两个新文件：MarkdownStreamMerge.kt（256 行，流式合并纯函数，零 Compose 依赖）+ MarkdownBlock… |
| 09-07 12:03 | ↳ ChatScreen 四文件拆分批次 3+4 合并 main（2026-09-07，main @ 29a20a47） | 用户拍板批次 3+4 合一个分支做。全部四批拆分完成。 |
| 09-07 14:25 | Runtime Limits 审计修复合并 main（2026-09-07，main @ 90ec25e） | 用户要求复查 runtime-limits 三提交（d908e90/4f52adb/059aa66）+ 拆分批次。审计实锤 4 个问题，全修，分支 fix/runtime-limits-audit 单提交 90ec25e… |
| 09-07 21:54 | 规则体系对照复杂度五纪律的复查（2026-09-07，待拍板 2 条） | 用户贴来一段复杂度管理五纪律（地板之上不增一克/边要收费/纠缠最小化/机制可压缩/整体保持可读），问我们的规则体系是否需要吸收。复查结论： |
| 09-07 21:55 | 双分支预合并审计 + 2 bug 修复 + 合并 main（2026-09-07 深夜，main @ 784c4065） | 任务：用户要求检查云端刚跑完的两个分支 CI（fix/render-progress-channel 61e6822 / fix/browser-filechooser-gesture 70bf2bc1），有 bug 就… |
| 09-07 22:30 | ↳ 最新包真机验证闭环（2026-09-07 深夜，1.0.0-beta.1372 @ main 784c4065） | 用户确认刚装的最新包（beta.1372，22:06 安装，release CI 34129869414 success）双分支功能全部闭环： |
| 09-08 13:40 | 环境变量分组分支审计+修复合并 main（2026-09-08，main @ fa87964） | 任务：用户要求审计 fix/envvar-flatten-usage-sheets（4 commit）有 bug 修完再合并。 |
| 09-08 17:27 | 自动备份 A+B 审计修复 + 合并 main（2026-09-08，main @ 568d87a） | 流程：新会话开场查交接文档 + 分支 CI（run 34205678023 f8a2372 已 success）→ 本地全量审计（17 文件 1108 行）→ 发现 2 真 bug 修复 → 分支 CI run 3420… |
| 09-08 19:23 | auto/ 目录分离分支审计+合并 main（2026-09-08，main @ 6e6b822） | 用户指令：检查分支 feat/backup-auto-remote-dir（3c155f6）是否引入 bug，有则修、无则合并。新会话流程：交接文档 → 本地审计 → CI 结论。 |
| 09-08 19:32 | ↳ auto/ 目录分离真机验证闭环（2026-09-08 晚，用户确认） | 用户确认真机验证符合预期。验证包 = 分支 CI run 34218884469 的 APK（head 3c155f6），与合并进 main 的 commit 同一 head——分支包即合并内容，验证有效性成立（head… |
| 09-09 13:10 | runtime-limits-ux 审计无 bug + 合并收尾（main @ ec1e5d8） | 来源：上一轮 runtime-limits-ux 审计顺带发现的既有 LOW（非最近 5 次引入）——BackupSettingsScreen importLauncher（SAF activity-result 回调，… |
| 09-09 17:44 | T10 ui-other 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T10.md。扫描 45 文件/14666 行：HIGH 1（PdfPreview 50 页全量 bi… |
| 09-09 18:37 | T4 data+backup 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T4.md。扫描 75 文件/17039 行：HIGH 1（ProviderDatabase MIGR… |
| 09-09 18:42 | T8 运行时域审计完成（0 HIGH / 3 MEDIUM / 5 LOW，报告已交） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T8.md。扫描 72 文件/15383 行。 |
| 09-09 18:50 | T7 browser+webapp+mcp 域审计完成（1 HIGH / 3 MEDIUM / 5 LOW） | 报告：/var/minis/shared/global-bug-audit-0909/reports/session-T7.md。扫描 29 文件/9647 行。 |
| 09-09 19:00 | T3 sandbox-offload 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T3.md。扫描 69 文件/24961 行：HIGH 0 + MEDIUM 6 + LOW 2。 |
| 09-09 19:07 | T5 provider 协议域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T5.md。扫描 34 文件/10512 行：HIGH 0 / MEDIUM 4 / LOW 7。 |
| 09-09 20:27 | 全局第二轮审计收口 + HIGH 修复闭环（2026-09-09 晚） | 第二道门核实（reports/FINAL-closure.md）：10 域 498 文件 / 169,059 行，5 HIGH + 34 MEDIUM + 55 LOW。5 条 HIGH 逐条独立核实： |
| 09-10 00:19 | 全局第二轮审计修复进度（2026-09-09 深夜，交接点） | 交接文档：/var/minis/shared/audit-0909-round2-handoff.md（102 行，含状态锚点/批次表/剩余清单/下一步命令/坑） |
| 09-10 00:22 | 第二轮审计修复交接收尾（2026-09-10 00:25，会话结束点） | 交接文档：/var/minis/shared/audit-0909-round2-handoff.md（102 行，状态锚点/批次表/剩余清单/精确命令/6 条坑） |
| 09-10 00:26 | ↳ 静态作用域验证脚本（B11 收尾新增，可复用） | 脚本：/var/minis/shared/tools/verify_offload_scope.py（+ 坏样本 verify_offload_scope_selftest_Bad.kt） |
| 09-10 01:00 | 第二轮审计修复：MEDIUM 清零 + LOW 批次（2026-09-10 凌晨） | main 状态：main @ d66fea1（B11 ff2455b + B12 d66fea1 已 ff 合并；B11 release CI 34377683652 success，B12 release CI 343… |
| 09-10 01:17 | 第二轮审计修复全部合并 main（2026-09-10，main @ 85483e6） | 最终状态：main @ 85483e6，release CI run 34381819777（head 85483e6，触发后未等结论）。远端分支只剩 main（b11/b12/b13 全部 204 删除），本地唯一工作… |
| 09-10 01:46 | 上游痕迹清理评估 + 包名对齐方案（2026-09-10，待用户拍板） | 用户指令：去掉上游（OpenMinis）痕迹、与应用名 RikkaMinis 对齐，重点问包名工程量。用户已拍板 B 方案（改 namespace/包路径），并明确 不再同步上游（差异太大，改为按需"融合"，融合本身工作… |
| 09-10 02:49 | 第二轮审计 LOW 全收口（B22，main @ 54eade2，2026-09-10） | 结果：11 项剩余 LOW 一次做完 → 分支 fix/audit-0909-b22 CI run 34389944956 success（head 54eade2）→ ff 合并 main @ 54eade2 → re… |
| 09-10 03:34 | 第三轮复查完成：B1-B22 + token + 包名重构，无 bug（2026-09-10 深夜） | 第三轮复查完成：B1-B22 + token + 包名重构，无 bug（2026-09-10 深夜） |
| 09-10 09:18 | feat/port-streaming-backup-and-trace-gate 审计 + 引号 bug 修复（2026-09-10） | 分支：另一会话推的流式备份导出（杀 payload-String OOM）+ trace 门禁，f2ac8e06，run #1448 success 11m57s（正常带宽）。 |
| 09-11 10:59 | 三处改动审计：两次已合并 + fix/key-roulette-refresh（全过，分支待合并） | 审计结论：三处均无 bug。 |
| 09-11 21:26 | fix/singleline-paste-newline 分支审计：无 bug（2026-09-11） | 正在跑的分支 = fix/singleline-paste-newline（单 commit f4b6c4a 基于 main @ 3a988d5，run 34603318501，19 文件 +188/−28）。用户要求检… |
| 09-12 10:10 | backlog.md 清理（2026-09-12） | 用户指出 backlog 里大多是已修复/已有对应的死项+编号混乱（两个##2）→ 重写：活项 4 节（sanitizeUtf16 / B 种子 2 / C 等窗口 3 / D 观察 2）+「已关闭存档」表格（7 行，含… |
| 09-12 22:55 | chat-tuning-panel 独立审计（main @ 70a0f5c，2026-09-12 深夜） | 范围：feat/chat-tuning-panel 两 commit（2d7cd82..70a0f5c，29 文件 +1891/−89），独立复核（非重复前一会话的合并前审计）。 |
| 09-13 14:32 | 2026-09-13 三笔改动审计收尾（main @ 33aa72d） | 范围：①32571f8 open-catchup v1 ②daab66b v2 首帧贴底 ③33aa72d 自适应压缩阈值（原分支 fix/adaptive-compact-thresholds，14:00 由另一会话 … |
| 09-13 14:40 | Legacy 渲染管线残留清单审计（2026-09-13，基线 daab66b） | 报告：/var/minis/shared/legacy-pipeline-audit-2026-09-13.md（扫描脚本 /tmp/legacy_scan{,2,3,4}.py） |
| 09-13 15:56 | ★ legacy 管线审计：最终形态与可复用教训（2026-09-13 二轮，动手实施后） | 审计报告：/var/minis/shared/legacy-pipeline-audit-2026-09-13.md（⚠️ 该文件的「三条处置路线」章节已滞后：报告假设 10 个 legacy 行型类可移入 ui/cha… |
| 09-13 18:23 | ★ 2026-09-13 收尾终态（main = 6c20459）+ 存储清理 + 探针失效 | 五次合并全部落在 main（按时序）：e057d151（shellTimeout 接线 + 滑杆密度，凌晨，真机已验）→ 15b3f447（dev-history 926 条）→ 3ca019e（恢复被回滚的三笔：ope… |
| 09-13 18:51 | ★ 2026-09-13 收尾二（main = 27be8e3）+ 存储两次清理 | ⑤ dev-history 档案同步：重建 + 脱敏 + 校验全绿（953 条，+27；1,122,819 字符；17,928 行；fences 36 even / ts 953 / outOrder 0；脱敏 107+… |
| 09-13 19:34 | 今日修改全量审计：干净，无需改动（main @ 82c7925） | 用户要求"扫一下今天的修改有没有引入 bug"。审计范围 = 今日合入 main 的全部代码提交：3ca019e（恢复 open-catchup v1/v2 + adaptive compact）、f113039（UTF… |
| 09-13 19:59 | 内存防线缺口审计（main @ 82c7925，报告 shared/memory-defense-gap-audit-2026-09-13.md） | 最重要更新：交接文档"无执行中看门狗"已过时——daab66b（随 09-13 restore 回归 main）已带 in-flight 监控（PersistentShell 1s 轮询 → midCommandRecy… |
| 09-13 22:09 | 最近 5 commit 独立审计：干净，零改动（main @ c5088961） | 背景：用户要求扫最近 5 次修改。main 已推进到 c5088961——内存加固三件套（a641e27/45ce0c8/c508896）已由用户拍板合并（此前会话状态是"未合并待拍板"）。三件套是"实现+自测"出身、没… |
| 09-13 22:49 | ★ 2026-09-13 环节完善度横向扫描（main @ d3873c0e）：1 结构缺口 + 3 真缺陷 + 1 注释漂移 | 扫描轴刻意换过：历史 T1–T10 全域审计（5 HIGH+34 MED+54 LOW 已收口）是「按功能域找 bug」；本次是同构组件一致性 + 审计清单之外横切面。手段：5 个静态探针（scan1–5 在 /var/… |
| 09-13 23:23 | ★ 2026-09-13 完善度扫描收尾：打包修复分支 CI 绿，待真机验证 + 拍板合并 | 分支 fix/completeness-followups-0913 @ a330ce2f（基于 main d3873c0e，7 文件 +143/−4），CI run 34764642145 success（步骤 3 门… |
| 09-14 00:07 | 2026-09-13 完善度修复全链路闭环完成（release CI 已确认 success） | 用户说"触发了就不用等了"，但查证发现 release CI run 1505 已经是 completed success（head a330ce2f）——全链路闭环： |
| 09-14 09:07 | 度量存活度审计（代码声明 × 日志实测）—— 新方法 + 首批发现（2026-09-14，main @ a82f425a） | 任务：用户提出"度量死了应该是一类问题，用积累的日志交叉验证：哪些是代码上说有、日志里没有的"。做了系统化审计。 |
| 09-14 09:43 | ↳ 处置批次打包完成并合并：main = `2dc6e0d4`（2026-09-14） | 用户确认负载均衡已关闭 → ChatVMRouting=0 属预期（该行无条件打印，0 即"没轮转过"），从待查清单移除。 |
| 09-14 09:52 | 09-14 两个 diag 提交独立审计：干净，4 个 LOW（main @ 2dc6e0d4） | 范围：a82f425a（reentry 锚点 + stream tick 复活）+ 2dc6e0d4（RenderCensus 尺子 + 删 3 死埋点），共 9 文件。 |
| 09-14 12:46 | 2026-09-14 文档刷新闭环：main = 582de2ed（docs: refresh architecture/philosophy + add observability design doc） | 范围（3 文件，+239/−24，docs-only 分支 docs/refresh-0914 → ff 直推 main → 远端分支已删，终验仅剩 main；docs/ 不在 CI paths 过滤内 → 不触发构建）… |
| 09-14 18:47 | 09-14 晚：今日 10 commit 独立审计 — 无 HIGH/MED，3 个 LOW | 范围：a82f425/2dc6e0d/5862020/fe99158/fa2ec1e/1362496（diag 批）+ fbc50cd（截断守卫）/27265bb（preflight 校验）/a383f8c（敏感转录）/… |
| 09-14 20:05 | 09-14 夜：运行日志审计 → 6 项修复已合并 main = ad71828c（release CI #1526 已触发，用户拍板不等） | 前提（用户提醒）：日志（13:48–18:51）属旧包（1.0.0+1516 及更早），设备现装 1.0.0+1524 已含 79d57d69 修复 —— 我用 APK dex grep（grep -ac 'stub, … |
| 09-14 22:18 | 09-14 深夜：最近 5 commit 独立审计（main @ ae83b79）— 零 bug，3 个 LOW 留档 | 范围：46fb3d2（update digest 校验）/ 0f07a8b（故障 golden）/ 954983e（注释修正）/ da8cbc3（conversation_history）/ ae83b79（suspen… |
| 09-14 22:20 | 09-14 深夜：审计 3 个 LOW 已攒入 backlog §7-9（用户拍板"攒着"） | /var/minis/shared/backlog.md 新增三节（带位置/影响/可选处理/触发条件）： |
| 09-15 12:04 | 09-15 中午：今日改动审计（main @ 31e8f76）— 零 Bug | 3 commit：95df092（CI tools 包）/ 28f051b（AppLogger 异步写 + LogWriteQueue）/ 31e8f76（ProviderRepository persist 移后台线程… |
| 09-15 12:07 | 热路径同类扫描（用户问"还有没有"）：新发现 §11 ThinkingRulesSection UI 直接 runBlo | 热路径同类扫描（用户问"还有没有"）：新发现 §11 ThinkingRulesSection UI 直接 runBlocking Room（4 个 remember 块组合期 4 次 DB 读——thinkingRul… |
| 09-15 12:09 | 09-15 下午：热路径扩展扫描（第二轮，新轴）— 新增 §12 观察项 | 新轴：Room 全表 / O(n²) / fsync / Compose 大对象 emit / OkHttp 客户端 / 启动路径 / memspike 写入。 |
| 09-15 13:06 | 09-15 下午：日志审计实锤 HangDetector 后台冻结假阳性（HIGH） | 证据：今日 379 HANG 样本，长挂起（42-176s）mid-hang 栈全部停在 nativePollOnce（空闲）+ 前后台日志精确对齐（13:00:46 fg=false → 13:02-13:03 假 h… |
| 09-15 13:51 | 09-15 下午：第二轮日志审计（新包 183a01ed 上线后）— 1 个 LOW 进 backlog §13 | 新包表现验证（三源）：13:41 后 W/HangDetector=0、TRIPPED=0、forcehome 全日志 0 → 前台门控 + launch verdict 修复在野生环境生效。ToolPreflight … |
| 09-15 15:54 | 09-15 下午：日志审计（五连杀窗口）— 零应用 bug，防线全过 | 事件：15:19/15:22/15:36/15:38/15:39 五次 silent_kill，全部落在 CF 优选跑测窗口（500 TCP 并发跑在 app cgroup 内 → HyperOS 整组回收）。应用 RS… |
| 09-15 17:06 | 09-15 晚：轴 7 收口（loop mechanics 时序审计）— §15 发现 + 七轴 sweep 全部完成 | §15（真发现，旁路层）：runAgentLoop 两个 catch（2751 CancellationException→t7EndRun(CANCELLED) / 2768 Exception→t7EndRun(FA… |
| 09-15 18:45 | 09-15 晚：dev-history 档案重建加固 + 脚本脱敏能力审计 + 公开历史暴露面 | ★ 最重要发现（需用户决策）：docs/dev-history/ 在 public 仓库，且 raw.githubusercontent.com 无 token 可读（实测 200）。这意味着脱敏不是"内部整理"，是公开… |
| 09-15 18:45 | 09-15 晚：公开历史泄露的清理选项（待用户拍板） | 问题：docs/dev-history/ 在 public 仓库，旧提交（0da07a10 08-30 起）含主号名 74 处、小号名 39 处、CF KV 命名空间 ID ×3、com... 包名、DOMAIN 域名、… |
| 09-15 22:44 | 09-15 夜：两支改动的归类排查（一类还是两类）+ 同病扫描 | 对象：fix/compact-swallows-queued-instruction @ decafa9a（CI 1549 success）、fix/tool-call-copy-suppress @ 7c1dddad（… |
| 09-16 00:23 | 09-16 凌晨：日志审计（09-15 窗口）— 23 次 silent_kill churn + 400 重试 + 混淆类名 | 复用工具：/var/minis/workspace/logaudit/{analyze,probe,deaths}.py（格式归一化 / 定向过滤 / 按 pid 分组看死亡上下文）。日志文件只剩 18:05 后窗口（重… |
| 09-16 00:27 | 09-16 凌晨：日志审计三发现攒入 backlog §16-§18（用户拍板"攒着"） | §16 REJECTED 打印 R8 混淆类名（LOW 一行修，ChatAgentTraceObserver.kt:128） |
| 09-16 08:58 | 09-16 上午：26 commit 组合审计（d5ce767）— 1 HIGH + 1 MED，其余干净 | ★ HIGH（昨天 7c1dddad 引入，未上真机所以还没炸）：ToolCallResiduePolicy 流路径性能回归。实测（生产源码逐字编译，JVM harness /tmp/audit16-h）：firstRe… |
| 09-16 12:03 | 09-16 午：日志审计（09-16 窗口）— 3 项新发现，首要 = 超时 124 被当 shell 死亡盲重跑 | 判据来源：/var/minis/logs/minis-2026-09-16.log（7.7MB）+ memspike-2026-09-16.log（app 自带 rss/phase 探针）+ launch-beacon.… |
| 09-16 12:03 | ↳ 09-16 午：两个 audit0916 分支合并 main（main = 1863e4d2） | 远端原有两分支：fix/audit0916-scan-cost-and-anchor-gap @ 1ce9d8a（CI run 35047155917 success）与 fix/audit0916-graying-an… |
| 09-16 14:45 | 09-16 傍晚：§22 热榜吸收三项落地（22a 阶梯 skill / 22b evals / 22c 债务扫描） | 用户拍板同批：vector_index.pkl 不打包（backlog §22「设计决定」已改为已拍板 + 触发重估条件 + 附带待办：下次动仓库 requirements.json 时补一行「首次使用需 build（需… |
| 09-16 21:27 | 09-16 夜：全库 LLM 扫描 + 管线压力测试（用户拍板「扫描仓库 + 用满额度 + 压力测试」） | 靶：RikkaMinis @ main 551ed6b（fresh clone /tmp/RikkaMinis，1129 commits）。产出：报告 /var/minis/mounts/笔记/RikkaMinis源码扫… |
| 09-17 09:11 | 09-17：审计交接文档已就绪（新会话处理 215 条 CONFIRMED） | 09-17：审计交接文档已就绪（新会话处理 215 条 CONFIRMED） |
| 09-17 10:23 | 09-17：审计存量缺陷处理（215 条）批次 1-3 已推 CI | 09-17：审计存量缺陷处理（215 条）批次 1-3 已推 CI |
| 09-17 12:34 | 09-17 续：审计修复推进（新会话接手，另一会话模型故障） | 状态：main = 55d38484（4 提交 FF 合并，CI 35181007403 success）；第二批 fix/audit0917-high2 @ b66c21bc CI 已触发。 |
| 09-17 14:22 | 09-17 下午：审计存量缺陷批次 1-7 收口 — main = e657335f | 成果：215 条 CONFIRMED → 已处理 155 条 / 92 文件，剩余 60 条（HIGH 4 / MED 32 / 其他 24）。10 个提交 FF 合并，CI 全绿，远端仅剩 main。 |
| 09-17 14:26 | 09-17：审计交接包完成（新会话可直接接手剩余 58 条） | main = e657335f，远端仅剩 main，CI 绿。交接物全部在 /var/minis/shared/audit-0917/（14MB）。 |
| 09-17 16:43 | 09-17 晚：审计 58 条全部收口 — main = 8fbb9d2e（release CI 同 SHA） | 收口链路：分支 fix/audit0917-chatbatch（6 提交，5 轮 CI：3 编译错 @ nav-currentStateFlow / 坏删 context / toolTitle 作用域 + 1 单测契约… |
| 09-17 16:58 | 09-17 收口后复查：今日 30 提交（审查结论 + 1 个真缺陷） | 对象：main 8fbb9d2e vs 昨日 c6f06d8b（30 提交 / 143 文件 / +2371−532）。报告：/var/minis/mounts/笔记/今日改动复查报告-2026-09-17.md |
| 09-19 09:52 | 09-19：offload 审计交接包落盘（第 3 棒待接力）+ 一条重要教训 | 用户指令：「准备交接，进行接力」。产出跨会话交接包 → /var/minis/shared/offload-audit-0919/（关键前提：/var/minis/workspace 是会话私有，交接必须先归档到 sha… |
| 09-19 10:05 | 09-19：offload 审计第 3 棒完成（Shizuku/A11y/Photos 逐行走查 → F-26…F-40，只读） | 任务：读交接书 /var/minis/shared/offload-audit-0919/HANDOFF-offload-3rd.md 并执行第 3 棒——逐行走查三个高风险 handler（函数体未读），按 7 问清单… |
| 09-19 10:18 | 09-19：offload 审计第 4 棒完成（Contacts/Notification/Location + ★勘误 E-1 进程模型） | 任务：接第 3 棒继续推进。本棒 = ContactsOffloadHandler(332，"参考实现") + NotificationOffloadHandler(579，PII) + LocationOffloadH… |
| 09-19 10:24 | 09-19：offload 审计第 5 棒交接包封包完成（新会话入口 = HANDOFF-offload-5th.md） | 用户指令：「准备交接，下一对话再继续推进」。产出跨会话交接包 → /var/minis/shared/offload-audit-0919/（1.6M，入口 = HANDOFF-offload-5th.md，202 行）… |
| 09-19 10:48 | 09-19：offload 审计第 5 棒完成（Calendar/Alarm/Speech+Speak → F-51…F-68 + 勘误 E-2 + 权限分类重评估表） | 产出（/var/minis/shared/offload-audit-0919/）：报告 reports/rikkaminis-calendar-alarm-speech-audit.md(31KB) · diagram… |
| 09-19 11:14 | 09-19：offload 审计第 6 棒完成（Notification 全文 + BrowserUse/Config/Sessions + 权限页 → F-69…F-82 + 勘误 E-3/E-4/E-5 + A 类边清单） | 状态：只读审计，仓库 0 改动，锚点 main c6d8d63f。 |
| 09-19 11:18 | 09-19：offload 审计第 6 棒交接包封包完成（入口 = HANDOFF-offload-7th.md，**下一棒起转为写代码棒**） | 封包自检（模拟新会话开工）：sh bootstrap.sh（复用 /tmp/RikkaMinis，锚点 c6d8d63f，工作区 0 改动，5 个关键文件行数指纹 ✅）→ sh verify_all.sh 20/20 ✅… |
| 09-19 11:57 | 09-19：offload 审计**路线改判**——下一棒 = 继续推进扫描（不是修代码）+ 锚点机制升级（ANCHOR.md 单一来源） | 用户指正：「那个任务（= 我写的第 7 棒修补批次任务书）已经在另外一个会话做了，并且已经几乎处理完了；扫描还需要继续推进，需要的是继续推进扫描的」。 |
| 09-19 13:51 | 09-19：offload 审计第 8 棒完成（Ring 2 第一段 = config/tools/debug/browser/speech 抽样 → F-95…F-97） | 用户指令（本棒方向性）：「继续扫」「扫完之后，再统一进行其他的处理」→ 修补全部推后，资源投在把 Ring 2 扫完。 |
| 09-19 14:32 | 09-19：offload 审计第 9 棒完成（Ring 2 / P1 = `browser` 包 → F-98…F-104） | 状态：✅ 第 9 棒完成 · 只读，仓库 0 改动 · QA verify_all.sh 32/32 全绿 · 判据 verify_findings_9th.sh 73/73 · 生成器自对账 16 项 |
| 09-19 15:15 | 09-19：offload 审计第 10 棒完成（Ring 2 / P4 = `agent/` 运行时 + `SoulStore` → F-105…F-115） | 状态：✅ 第 10 棒完成 · 只读，仓库 0 改动 · QA verify_all.sh 38/38 · 判据 verify_findings_10th.sh 99/99 · 生成器自对账 40 项 |
| 09-19 15:55 | 09-19：offload 审计第 11 棒完成（Ring 2 第四段 = `agent/` 剩余 + `debug/` 抽样 → F-116…F-123） | 产出（/var/minis/shared/offload-audit-0919/）：报告 reports/rikkaminis-agent-shell-debug-audit.md(23KB) · verify_find… |
| 09-19 16:29 | 09-19：offload 审计第 12 棒完成（Ring 2 第五段 = `debug/` 包 12/12 文件 5,592 行全部走完 → F-124…F-133） | 下一棒：HANDOFF-offload-13th.md —— P1 = config/ 包（4,963 行）（剩余最大 + 「用户可见入口 = 机器入口」三方同写一份配置 ⇒ 同族三标准富集）；P2 = service/… |
| 09-19 17:30 | 09-19：offload 审计第 13 棒完成（Ring 2 第六段 = `config/` 包 4,963 行 / 20 文件全部走完 → F-134…F-140） | 锚点：c6d8d63f（= origin/main，未前进）· 扫描基线 99783703 |
| 09-19 18:12 | 09-19：offload 审计第 14 棒完成（Ring 2 第七段 = `service/` 包 11 文件 3,459 行全部走完 → F-141…F-152） | 主题：service/ = 「进程级用户可见面」（前台服务常驻通知 / 悬浮胶囊 / 内存门 / 会话并发槽）。7 条 D 级发现里 6 条落在「同一决策的两套实现」或「声明 vs 实现」。 |
| 09-19 19:20 | 09-19：offload 审计第 15 棒完成（Ring 2 第八段 = `tools/` 14 文件 2,792 行 + `speech/` 7 文件 1,649 行 → F-153…F-160）· **Ring 2 至此 27,003 行 / 88 文件全部走完** | 状态：✅ 只读，仓库 0 改动（HEAD = c6d8d63f = 锚点，git status --porcelain 空）· 包闸门 verify_all.sh 67/67 · 判据 verify_findings_1… |
| 09-19 19:59 | 09-19：offload 审计第 16 棒完成（P1 = `backup/` + `diagnostics/` + `logging/` + `crash/` → F-161…F-169） | 状态：✅ 只读，仓库 0 改动（HEAD = c6d8d63f = 锚点，git status --porcelain 空）· 判据 verify_findings_16th.sh 90/90 · 总闸门 verify_… |
| 09-19 21:14 | 09-19：offload 审计第 17 棒完成（小包扫尾 + app 根文件 + `shared/` = 40 文件 / 8,275 行 → F-170…F-176 · O-29…O-43） | 下一棒：HANDOFF-offload-18th.md —— P1 = provider/ 11,202 行（唯一带生产日志活体证据的未扫区域：当天 14× stream parse exception: Cancell… |
| 09-19 21:25 | 09-19：审计口径变更为「全量覆盖」（用户：「都扫一遍吧」）+ 第 18 棒第一部分 → F-177 | 口径变更（重要，此前各棒的「不逐行走查」豁免一律作废）：ui/(84,174) 与 sandbox/ 剩余纳入逐行走查。 |
| 09-19 22:38 | 09-19 晚：offload 审计转「并发派发」模式 —— WAVE-2 四线并行（第 21 棒） | 背景：用户要求提速（"效率太慢，开拓会话并发同步推进"），并授权"不要问，直接扫、推完"。主会话转为派发者 + 收口者。 |
| 09-20 00:56 | 09-20 凌晨：offload 审计并发线 A1 完成（`provider/openai/` 5 文件 3,559 行 · 第 21 棒） | 产出：/var/minis/shared/offload-audit-0919/wave2-a1/ —— report.md(35KB) · ledger-a1.json(21 条：D=3 · O=18，其中负结果 N-… |
| 09-20 00:57 | 09-20 凌晨：offload 审计 WAVE-2 线 B3 完成（`data/` 除 `repository/`，60 文件 / 8,744 行） | 状态：只读，仓库 0 改动（HEAD = 99783703）· 判据 verify_b3.sh 85/85 · 台账 27 条（F-230…237 · O-110…119 · N-85…93）· 独立 clone /tm… |
| 09-20 01:47 | 09-20 凌晨：offload 审计 WAVE-2 并发线 A4 完成（`provider/` 杂项 + `ModelsDevApi`） | 产出（/var/minis/shared/offload-audit-0919/wave2-a4/）：report.md(34.9KB) · ledger-a4.json · ledger-section.md（可直接并… |
| 09-20 01:52 | 09-20：offload 审计 WAVE-2 · B4 线完成（`ui/settings/` 42 文件 / 18,653 行） | 交付（/var/minis/shared/offload-audit-0919/wave2-b4/）：report.md(25.8KB, 7 章, 含 42 文件覆盖表) · verify_b4.sh 55/55 绿 ·… |
| 09-20 02:06 | 09-20 凌晨：offload 审计第 21 棒 · 并发线 A2 完成（provider/anthropic + provider/gemini） | 范围：AnthropicProvider(1023) + GeminiProvider(566) + AnthropicModelsApi(224) + GeminiModelsApi(127) + AnthropicM… |
| 09-20 02:08 | 09-20：offload 审计第 21 棒 · 并发线 C4 完成（`ui/` 其余全部 52 文件 / 17,194 行） | 范围：ui/components sandbox browser markdown preview navigation media onboarding terminal theme sessions util + u… |
| 09-20 02:26 | 09-20 凌晨：offload 审计第 21 棒 **并发线 A3** 完成（`provider/thinking/` + `provider/voice/`） | 产出：/var/minis/shared/offload-audit-0919/wave2-a3/（report.md + ledger-a3.json + verify_a3.sh + exp_a3/）。 |
| 09-20 02:55 | 09-20 凌晨：offload 审计第 21 棒 **并发线 C1** 完成（`ui/chat/` 核心状态机与持久化） | 范围：ChatScreen.kt 5027 + ChatViewModel.kt 3843 + ChatSessionLifecycle.kt 1557 + ChatTurnPersistence.kt 454 + Ch… |
| 09-20 02:59 | 09-20：第 21 棒 WAVE-2 并发审计 → 交接包就绪（给新调度中枢） | 用户决定：本会话不再等 C1/C2/D1 收尾，直接交接——新会话当调度中枢，本会话提供背景信息。 |
| 09-20 03:26 | 09-20：offload 审计 WAVE-2 并发线 C2 完成（`ui/chat/` 渲染与文本组件） | 身份：第 21 棒并发线 C2（只读，仓库 0 改动，HEAD = 99783703 / 锚点 c6d8d63f）。 |
| 09-20 05:03 | 09-20：offload 审计修复批次 FIX-4-render-ui 完成（渲染/UI 组件层） | 产出：/var/minis/shared/offload-audit-0919/fix-out/fix4/ —— REPORT.md · FIXED-F-255/256/257/262/270…277.md（12 份，各… |
| 09-20 15:52 | 09-20：审计图表整理进笔记库 → `/var/minis/mounts/笔记/RikkaMinis审计图表/` | 用户要求：把审计产出的图表（mermaid 这类）在笔记文件夹里开一个文件夹放进去，并检查缺什么、补上。 |
| 09-20 18:12 | 09-20 夜：日志全量异常扫描（去污染口径）+ 两任务已派发 | 用户指令：①用子代理把修复任务安排出去 ②查还有没有类似问题，分析日志找异常，找到就继续往下分析。 |
| 09-20 18:45 | 09-20 夜（续）：日志扫描收官 → 共派发 5 个修复任务 | 用户指令：「如果发现了问题，并且能够确定的话就直接用子代理把任务派出去。」 |
| 09-22 02:50 | 09-22 凌晨：V3 审计（data/provider/agent/tools）→ 找到 P0 跨 IPC 字段缺失 | 产出：/var/minis/shared/verify-all-0921/reports/V3-data-provider-agent.md（10196 B） |
| 09-22 04:49 | 09-22：族扫描实验（5 族 × 5 扫描器）→ **假设被证伪：高危面已修完，新 D 级 = 0** | 背景：上一轮（V1–V4 日志法）后，我提出「~12,500 行 / 全仓 6.8% 是高危面，历史上多数 D 级集中在这 5 族」。用户同意按此做第一步实验。产物 /var/minis/shared/verify-al… |
| 09-22 10:02 | 09-22：思考强度（Thinking Level）功能审计 → **确认是 bug，4 处缺陷** | 用户报告：「调思考强度只有中、低、高以及自动；用模型组时明明调到最高，但会出现关了的情况」 |

## 12. 多会话并行协作 / 派发 / 交接

**跨度** 2026-08-04 ～ 2026-09-22 · **202 条** · **状态** 成熟（skill: task-dispatch / git-parallel-collaboration / rikkaminis-dev-methodology）

**叙事**：08-07 多对话框并发操作同一 worktree 翻车（native OOM 修复）→ 08-12 多任务并行推进模式确立 → 08-15 T1–T10 十会话派发（平衡点施工）→ 08-18 RC 整改凭编号领取 → 08-22 小号 TF-A..TF-J → 08-23 bug-hunt 五会话 → 09-13 两条纪律固化：**每会话独立 clone 绝不共享 .git** + **两分支同基各一 commit 时第二个必须 rebase**。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-04 20:51 | vlc-android — 会话交接（2026-08-04 22:50，未完成事项） | 仓库 |
| 08-06 11:03 | RikkaMinis 经验记忆修复实施方案（交接版，基线 87f69eb） | 核心架构 |
| 08-06 12:03 | RikkaMinis 经验记忆修复 — 交接标记（2026-08-06 12:00 UTC+8） | 4 个 commit + 1 个法语修复已推 main（3eaff17），CI run 31070074525 验证中。 |
| 08-06 16:17 | 2026-08-06 会话总结（交接用） | 已完成 |
| 08-06 23:38 | RikkaMinis — 输入框光标跳 修复完成并合并 main（2026-08-06 晚 收口） | 状态 |
| 08-07 00:25 | RikkaMinis 滚动/光标问题交接（2026-08-07 凌晨，交接给新会话） | 完整交接文档：/var/minis/workspace/handover-chat-jump-issues.md |
| 08-07 10:53 | 2026-08-07 多对话框并发操作同一 worktree 的教训（native OOM 修复） | 用户做闪退修复时，发现另一个对话框在并发操作同一个 git worktree（/tmp/rikkaminis-full, branch merge/scroll+caret），导致： |
| 08-07 11:06 | RikkaMinis 三件待办交接（2026-08-07 中） | 状态 |
| 08-07 14:35 | RikkaMinis 滚动「触底触发器」重构方向（2026-08-07 交接） | 用户提出全新滚动模型：不再用位置 gate 判断是否跟随，而是以「用户手势滑到底部尽头」为唯一触发器。 |
| 08-07 14:36 | 2026-08-07 对话框交接（scroll-proot-诊断会话） | 因对话框内容将满，滚动「触底触发器」重构交给新对话框。交接完成： |
| 08-07 17:32 | 多个对话框并行处理中（2026-08-07）— 三条活跃工作线 | 用户提示：其他对话框正在处理事情，不要冲突/干扰。当前并行在做： |
| 08-08 00:10 | 收尾指令：锚点守护暂不合 main，与其他并行改动一起编译合并（2026-08-08 用户指示） | 用户明确说：「在其他的页面中还在改其他的，到时候一起编译合并。」 |
| 08-08 00:20 | 收尾指令：模型切换暂不合 main，与其他并行改动一起编译合并（2026-08-08 用户指示） | 用户明确说：「背景是还有一部分模块在修改中，你把你这里的一部分记一下，等所有都准备好，一起合并。」 |
| 08-08 10:06 | 并发会话冲突：promote-draft 分支被 stash | 背景：本会话实现"草稿中新建对话自动提升为正式会话"（方案 2），在工作区开了分支 feat/chat-promote-draft-on-new-chat（tip=0d968d4）。另一会话在同一工作区并行操作（分支 f… |
| 08-09 02:22 | feat/termux-terminal-engine 交接（2026-08-09） | 目标：把 RikkaMinis 自研终端仿真器（2249 行）替换成 Termux 0.118.0 引擎。 |
| 08-09 03:25 | feat/termux-terminal-engine 交接（2026-08-09） | 当前状态 |
| 08-09 06:49 | 终端修复交接（2026-08-09） | 分支状态 |
| 08-10 13:55 | Circuit 正式定名 + 交接归档（2026-08-10 收尾） | 从 RikkaMinis 抽取的"自我修改能力"最小核心，走完全程： |
| 08-12 15:10 | 多任务并行推进模式（2026-08-12 用户决策） | 用户批评"逐个等 CI 太慢"，要求任务分解、独立分支并行推进。工作方式： |
| 08-12 15:13 | 并行任务清单已建立（2026-08-12）—— 多会话协作模式 | 用户要求把 OmniBot 借鉴点变成待办清单，多开对话各自领任务并行开发。清单文件： |
| 08-12 15:51 | ↳ T3 失败学习自动化钩子完成（feat/failure-auto-log → main 667f17d） | 改动： |
| 08-13 03:52 | 可并行分配的 Bug 修复任务清单（2026-08-13，待多会话并行执行） | 来源：套餐全库分析 + 健康度热力图筛选出的可定位 bug（非架构重构）。每个任务独立分支、独立会话执行，互不依赖。 |
| 08-13 08:48 | 并行任务 A：浏览器风险挑战检测 + throttle 控制 | 背景 |
| 08-13 08:48 | 并行任务 B：流式文本原始层合并保护 | 背景 |
| 08-13 16:23 | 终端三方案交接（2026-08-13，用户要求重开对话） | 交接文档：/var/minis/workspace/handover-terminal-fixes-2026-08-13.md |
| 08-13 18:47 | rootfs 占位 tar 重大发现（2026-08-13 晚，跨设备交接必读） | 当前设备状态：alpine-rootfs 已被重置且重装只写出 98.30 kB（rootfs 管理页显示），终端 proot 报 '/bin/sh' not found，我的 shell_execute 全部 [She… |
| 08-13 21:34 | 【交接·方案3】apk 包持久化 — bug 因果链 + 方案设计（2026-08-13 21:40） | 任务一句话：把"用户通过 apk 安装的包"做成可恢复快照——apk 装包清单持久化到 host 侧（app 私有目录），rootfs 被 reset/全量重建后按清单自动重装。修掉"强停/杀应用 → 重开 → root… |
| 08-13 21:34 | 【交接·方案3】代码位置 + 开发纪律 + 开工指引（2026-08-13 21:40） | 相关代码与已知约束（新会话需拉代码确认） |
| 08-14 11:04 | 滚动体验三问题：A+B 施工中，D 已交接（2026-08-14 上午） | 任务来源：用户反馈聊天界面三问题：①滚动必须"很直"才能滑 ②内容上下跳 ③思考栏/工具栏形态。 |
| 08-14 11:11 | ↳ A+B 已合入 main 3c95878（2026-08-14 上午，更新） | A（表格折叠）+ B（工具行动画）分支 fix/scroll-ux-table-fold-animate 已合入 main（3c95878），分支已删（远端 204 + 本地 -D） |
| 08-14 19:13 | 模型组策略重构 P1-P4 全量施工（2026-08-14 晚，多分支并行） | 背景：设计文档 /var/minis/shared/model-group-strategy-redesign.md 落成后，另一会话先合了 P1（GroupRouter 抽取 = 596adc2，抽取但未接线）。本会话… |
| 08-15 05:32 | T1 派发指令 — 会话并发槽位 | 你负责 RikkaMinis 平衡点施工 T1 — 修复会话并发槽位。 |
| 08-15 05:32 | T2 派发指令 — AgentExecutionBudget 纯逻辑核心 | 你负责 RikkaMinis 平衡点施工 T2 — 建立 AgentExecutionBudget 纯逻辑核心。 |
| 08-15 05:32 | T3 派发指令 — 副作用重试策略 | 你负责 RikkaMinis 平衡点施工 T3 — 工具与 shell 的副作用重试策略。 |
| 08-15 05:32 | T4-A 派发指令 — 故障注入 Harness | 你负责 RikkaMinis 平衡点施工 T4-A — 故障注入 Harness（fakes + 场景协议 + 独立 runner）。 |
| 08-15 05:32 | T5 派发指令 — Agent Run 终态状态机 | 你负责 RikkaMinis 平衡点施工 T5 — Agent Run 终态状态机与不变量。 |
| 08-15 05:54 | T6 派发指令 — Trace 扩展为预算和终态证据 | 你负责 RikkaMinis 平衡点施工 T6 — Trace 扩展为预算和终态证据。 |
| 08-15 05:54 | T7 派发指令 — Agent Run 主链路渐进接入 | 你负责 RikkaMinis 平衡点施工 T7 — Agent Run 主链路渐进接入。 |
| 08-15 05:54 | T4-B 派发指令 — 把 Harness 挂接真实 Agent Run | 你负责 RikkaMinis 平衡点施工 T4-B — 把已有 Harness 挂接真实 Agent Run adapter。 |
| 08-15 05:54 | T8 派发指令 — Interrupted / OutcomeUnknown 恢复语义 | 你负责 RikkaMinis 平衡点施工 T8 — Interrupted / OutcomeUnknown 恢复语义。 |
| 08-15 05:54 | T9 派发指令 — 性能观测、基线与门禁 | 你负责 RikkaMinis 平衡点施工 T9 — 性能观测、基线与门禁。 |
| 08-15 05:54 | T10 派发指令 — 故障矩阵与最终验收 | 你负责 RikkaMinis 平衡点施工 T10 — 故障矩阵与最终验收。 |
| 08-15 05:59 | T1-T5 并行施工期间准备的前置资产 | T1-T5 五路并行施工期间，本会话（协调会话）提前准备了以下资产，供后续任务直接使用： |
| 08-15 06:59 | T1 会话并发槽位完成：已合并 main 460ea04（2026-08-15 早） | 任务：修复 SessionConcurrencyManager 准入/取消竞态（蓝图 Wave 1 T1）。 |
| 08-15 10:03 | T3/T8 合并施工 + T7 派发文件（2026-08-15） | T3（RetrySafety + RetryPolicy）：分支 stability/T3-retry-side-effects rebase 到最新 main（77f7ff9），CI 跑完即可合并 main。 |
| 08-15 10:11 | ↳ T3/T8 已合并 main — 平衡点基础件全部完成（2026-08-15 上午） | T3（RetrySafety + RetryPolicy）：ff 合并 main（77f7ff9），远端分支已删。 |
| 08-15 10:16 | 可并行任务清单（与 T7 同步开工） | T7 正在另一个对话框施工（主链接入，ChatViewModel.kt）。以下任务可并行： |
| 08-15 10:19 | ↳ T10 验收准备完成 — 验收矩阵已交付（2026-08-15 下午） | 任务 3（可并行清单）完成：纯文档任务，未改代码、未跑测试。 |
| 08-15 10:21 | 可并行清单任务 1（C 类组件渲染测试）= 已完成项，无需施工（2026-08-15 上午） | 核查结论：任务 C 的交付物 08-13 就已合并 main，派发清单状态未同步导致被重复派发。 |
| 08-15 10:21 | ↳ T10 验收准备完成 — 验收矩阵已交付（2026-08-15 下午） | 任务 3（可并行清单）完成：纯文档任务，未改代码、未跑测试。 |
| 08-15 10:25 | 可并行任务状态更新（2026-08-15 下午） | 任务 1（C 类组件渲染测试）已在 08-12 合并 main（7e64fc5f），派发文件过时，无需施工。 |
| 08-15 10:36 | ↳ 任务4完成：boot /bin/sh 误报修复 — 已合并 main（7af5a08，2026-08-15） | 任务来源：可并行任务清单 #4（/var/minis/shared/boot-sh-fix-dispatch.md）。 |
| 08-15 11:04 | ↳ T7-A 完成：观察模式 trace 接入 — 已合并 main（4dd9557，2026-08-15） | T7-A：只接 trace 和观察模式，不改变任何 retry/fallback/UI 行为 |
| 08-15 15:20 | T4-B 修复交接（2026-08-15 凌晨） | main 当前状态：c14d29f（#751 FAILED）——冲突标记残留导致编译失败，F09/F14 测试失败。 |
| 08-15 20:30 | 修复：流式回答内容"跳动"（ToolCallRunGroup animateContentSize 冲突）（2026-08-15 晚） | 用户现象：大模型回答期间，渲染内容"跳动一下"（可复现）。 |
| 08-16 22:42 | fix/memory-dynamic-budget 编译失败任务交接 | commit 1d1ac2290efa 编译失败（CI run 31951519857），根因：动态预算常量定义在 object ExecutionCoordinator 内部作为 private const val，但… |
| 08-16 23:13 | 交接完成：新会话接收 fix/memory-dynamic-budget（2026-08-16 晚） | 交接文件：/var/minis/shared/fix-memory-dynamic-budget-handover.md（已更新含用户最终交代）。 |
| 08-17 08:08 | 打断残留修复终极根因：prune 抢先 + rowsTouched 门控 + thinking 折叠信号（2026-08-17 系统性收口） | 用户关键观察（扭转方向，价值极高）：切对话再切回 → 残留消失 → 证明 canonical 数据从头到尾正确，问题纯在"已发布行未刷新 UI"。用户最后拍板：不追求"消失"，而是 UX——打断后的旧回合应呈现"已停止"… |
| 08-17 09:03 | PiliPlus 阉割任务交接（3-Tab 精简，2026-08-17） | 本会话因 RikkaMinis native 堆内存锁死（ExecutionCoordinator 拦截工具，1700-3400MB 触线）无法继续，已交接。新会话从这个记忆或交接文件接手。 |
| 08-17 09:16 | 方向 A 交接：聊天主路径 LLM 调用隔离到 :modelservice（用户拍板，2026-08-16 晚） | 用户决策：native 堆"涨到锁死只能杀 app 重进"是根因（不是阈值问题），选择方向 A（治本）——把聊天主路径 LLM 调用移到 :modelservice 独立进程。已完成代码考古，交接文档写好：/var/mi… |
| 08-18 09:25 | B1 任务包：调高 MemoryPressureGate 阈值（2026-08-18 09:30） | 背景 |
| 08-18 09:27 | B2 任务包：保护活跃流式不被压力回收打断（2026-08-18 09:32） | 背景 |
| 08-18 09:33 | B1+B2 任务包执行中（2026-08-18） | B1（MemoryPressureGate 阈值）+ B2（保护活跃流式不被回收打断）已在分支 fix/memory-gate-thresholds（基于 main e0d9c62）实施。 |
| 08-18 14:21 | RikkaMinis 全量审计已派发（2026-08-18） | 总控会话（当前）负责收口，不爬代码。 |
| 08-18 15:06 | 全量审计总控收口完成（2026-08-18） | 用户把 RikkaMinis 全量检查交给其他分会话执行，我在本会话收口。12 份报告全部到位，验收通过。 |
| 08-18 15:10 | 整改施工派发包已就绪（2026-08-18） | 用户决定把审计整改改成「分派到其他会话」模式（同审计的协作方式）。我已在总控会话把 6 个 P1/P2 根因簇（RC1/RC2/RC4/RC5/RC6/RC7）全部定位到精确文件和行号，写入 /var/minis/sha… |
| 08-18 17:01 | 6 个 RC 整改全部合并 main 完成（2026-08-18 收口） | 用户把审计整改按编号派发到其他会话，全部完成后本会话统一合并收口。 |
| 08-18 18:49 | RikkaMinis 前端施工收口决策（2026-08-18） | 用户拍板走 B 路径：FE 施工收口在 FE-1（颜色 token 化）+ FE-3 step1（Thinking 三函数抽取）两个已闭环、CI 绿、零回归的成果上，main 停在 fcf9470。 |
| 08-18 18:55 | 后端二轮扫描完成 + RC7 收口（2026-08-18 晚） | RC7 最终状态 |
| 08-18 19:05 | 二轮派发包就绪（2026-08-18 晚续） | 产出 /var/minis/shared/rikkaminis-audit-2026-08-18/ROUND2-FIX-DISPATCH.md（RC10-RC16 浓缩执行指令，凭编号即可领取，复用 FIX-DISPAT… |
| 08-18 20:02 | 二轮整改 6 RC 合并 main 完成（2026-08-18 收口） | 合并结果：main fcf9470 → 86ec803，6 个分支全部三方合并（ort，零冲突），main release CI（run 32133842603）success，远端分支全部删除。 |
| 08-18 20:32 | FE-4 收口:route A+B 已合并 main(2026-08-18) | FE-4 纯函数抽取(route A+B)已合并 main 86ec803 → a4369d3,release CI 绿(run 32136399524),远端+本地分支已删。 |
| 08-20 00:02 | native OOM 施工交接（2026-08-20，换新会话继续） | 用户要求开新对话继续施工，别再拉长本对话。交接已固化，新会话直接读文件即可接手。 |
| 08-20 13:55 | Phase 1 骨架捞回 + 任务清单定稿（2026-08-20 会话收口） | 挖回丢失的 Phase 1 基石：15ba1ae(OffloadHandlerCatalog) + b31bb65(ToolExecutionService) 原本悬在已删的 fix/native-rss-tool-gu… |
| 08-20 15:32 | 任务状态收口：D-3 已合并 + 任务清单已更新（2026-08-20 15:35） | main 现在是 d8d9f0a（含 D-3 provider-rss 打点，release CI 绿 run 32343017398）。 |
| 08-20 15:39 | D-4b 领任务启动（2026-08-20） | 领 D-4b（长时并发聊天压测，定位 6GB 泄漏真凶）： |
| 08-20 16:40 | bug-hunt 2026-08-19 最终收口（2026-08-20 16:40） | 6GB native OOM 事故完成「止血 → 定位 → 并发上限放宽验证」三阶段闭环，全部收口。 |
| 08-20 17:11 | ↳ 并发会话上限放开（2026-08-20 收尾） | 用户要求把并发会话上限「彻底放开」，不要最高只能是 4。理由是应用已足够稳定。 |
| 08-21 20:46 | 内存隔离 Phase 0+1 闭环 + 并行派发（2026-08-21 晚） | Phase 0 fix/offload-bounded-admission → main fa28549：固定 ThreadPoolExecutor 替换 per-connection thread+Semaphore（… |
| 08-21 22:19 | Phase 3 browserservice 中途交接（2026-08-21，用户倾向新会话接手） | 分支 feat/browser-agent-process tip 332f30e，已基于最新 main 0a43b5c（含 Phase 2+4），本地干净仓库在 /tmp/rb-c（新 clone 专属，/tmp/rb… |
| 08-21 22:34 | ↳ Phase 3 browserservice 合并闭环完成（2026-08-21，会话 C 收尾） | 分支 feat/browser-agent-process tip 332f30e（基于 main 0a43b5c，8 commits）重跑分支 CI：run 32491582998 success，head_sha=3… |
| 08-22 16:48 | [dual-appid] lab 包闪退根因修复（native-offload abstract socket 冲突） | 继续 dual-appid 前置工作。小号 lab APK（applicationId=com.openminis.app.lab，已生效）安装后闪退，logcat -b crash 抓到 P0根因： |
| 08-22 17:19 | [小号内存治理] 交接：用户决定在小号补 D-4b 验证（聊天 provider-rss 泄漏判定） | 用户当前明确决策链（2026-08-22 晚，本会话对齐）： |
| 08-22 18:38 | 小号内存治理：任务已派发（2026-08-22 晚） | 方案文档已从 workspace 备份到笔记文件夹（/var/minis/mounts/笔记/RikkaMinis开发档案/小号内存飙升根治方案-Provider-Worker-Hard-Boundary.md，sha2… |
| 08-22 21:31 | 小号内存治理：五分支全部完成，已收口（2026-08-22 晚） | TF-A/B/C/D/E 五分支真实完成并独立核实（非仅转述）：A diag/provider-rss-v2 c8ff0a4 (run 32569593961) / B fix/modelservice-terminal… |
| 08-22 21:54 | ↳ 真机日志推翻 worker 协议闭环（2026-08-22 晚） | TF-E APK 真机日志发现 P0：:modelservice PID 30053 因 ModelExecutionService.finishRequest() 写 state.json 时 run 目录已被主进程删… |
| 08-22 22:58 | ↳ TF-F modelservice run-dir 所有权 P0 修复完成（会话 F，2026-08-22 晚） | P0 根因：TF-E APK 真机 crash（:modelservice PID 30053 state.json ENOENT）——ChatStreamOffloadHandler.finally 在 result.… |
| 08-22 23:56 | [会话 G 交接] TF-F 后 modelservice worker 仍被 SIGKILL——根因与施工方案（2026-08-22 深夜） | 用户最新证据：附件 minis-2026-08-22__3_.log（23:00-23:01，主进程 PID 14059）。lab 包 1.0.0-beta.28（TF-F 4967a9a）聊天仍失败：23:00:18/… |
| 08-23 01:30 | [会话 G 交接] TF-G worker SIGKILL P0 修复完成（2026-08-23 凌晨） | 分支 fix/modelservice-worker-ack-liveness（基于 TF-F 4967a9a），tip 6c0bb32，CI run 32586641059 全绿（22 step 全 success，h… |
| 08-23 05:17 | ↳ TF-G 真机复测推翻协议闭环 | 用户提供 2026-08-23 真机日志，已安装确认是 TF-G beta.33（6c0bb32），但 9 次 modelservice 流均在约 5 秒客户端 grace 后报 DIED_AFTER_READY_NO_… |
| 08-23 13:37 | Bug Hunt + 压力测试 多会话派发（2026-08-23） | 用户拍板：对小号 lab 包 com.openminis.app.lab（beta.41 = tip a6b2665，分支 fix/modelservice-before-dispatch-notify）做「找 bug … |
| 08-23 14:26 | 会话 E 完成：记忆/压缩/宏/子代理/失败钩子 压测（2026-08-23） | 方法：沙箱装 OpenJDK17 + kotlinc 1.9.24，直接编译 app 自身纯逻辑源码（MemoryRollupEngine/ToolFailureHook/ContextCompactor/MemoryR… |
| 08-23 14:47 | 会话收敛：bug-hunt-pressure 收口完成（2026-08-23 晚） | 5 条攻击面全跑完，beta.41（a6b2665）心跳修复未复现假死（logcat 678 万行 0 命中 worker died/proc_missing/DIED/beat_stale）。挖出 4 个确凿 P0 +… |
| 08-23 14:49 | 修复任务派发就绪（bug-hunt 收敛后，2026-08-23） | 对 beta.41（a6b2665）压测出的 P0/P1，已把修复拆成 5 个可并行会话，产物在 /var/minis/shared/bug-hunt-pressure/： |
| 08-23 16:44 | 修复收口完成（2026-08-23 晚）— 5/5 修复分支 CI 全绿 | 基于 a6b2665 的 5 条修复分支全部完成并推送小号远端，分支 CI 全绿（head_sha 与 tip 逐一核实）： |
| 08-23 17:43 | bug-hunt 五修复真机验证通过（2026-08-23 收口完成） | 用户装 beta.50（versionCode 220000050 = main 15ca95c，run 32629219062 绿）后真机验证： |
| 08-23 18:53 | ↳ browser get_text 大文本 ANR 修复真机验证通过（2026-08-23 晚） | 用户装 beta.52（versionCode 220000052 = main 3576528，run 32634077568 绿）后验证： |
| 08-24 08:00 | ChatScreen 渲染修复方案已定稿并派发（2026-08-24） | 方案文件：/var/minis/shared/chat-render-composer-audit/fix-plan.md（定稿）+ FIX-TASK.md（任务书）。 |
| 08-24 10:52 | 首块超时「provider produced no first chunk within 30000ms」调查 + 委托派发（2026-08-24） | 测试/证据：真机日志 08-24 该错误 90 次（07:42 后集中），08-22/08-23 = 0 次真实运行；涉及 deepseek-v4-flash/gpt-5.6-luna/deepseek-v4-pro，走… |
| 08-24 11:42 | 首块超时修复 release CI 全绿 + 收口（2026-08-24） | release CI run 32686587661 success，head_sha=62d3db4a 已核实一致（防假绿）。远端分支 diag/first-chunk-timeout 已删除。闭环完成：调查 → 修复… |
| 08-24 11:57 | 工具去重修复已合入主号 main：release CI 全绿 + 收口（2026-08-24） | 合并状态：用户拍板「没问题就汇聚到主号」→ 分支 fix/tool-call-dedupe rebase 到最新 main（62d3db4，首块超时修复已合入）→ 新 commit 0b90cf0 → ff 合并 mai… |
| 08-24 13:40 | 近期修改审计派发（2026-08-24） | 审计范围 a6b2665..0b90cf0（约 30 commits，2 天改动）。初筛发现 1 个确凿问题（SanitizeAgentHistory.kt println 替代 Log.w）+ 3 个灰色区域。已派发 … |
| 08-24 14:38 | 审计收口：4 会话报告已收齐，施工方案已定（2026-08-24） | 4 个审计会话全部完成，结论：仅 1 个 P1 必修（渲染管线 reconcileAndVerifyTerminalText 收敛守卫失效——settled 后同长重写时 segmenter absorbDivergen… |
| 08-24 15:51 | ↳ FIX-B 施工完成：低风险收尾修复（2026-08-24） | 分支：fix/sanitize-firstchunk-cleanup（主号 OWNER/RikkaMinis，基于 0b90cf0），commit c78bcca，分支 CI run 32702069207 succes… |
| 08-24 18:01 | 思考模式修复已合并 main + CI 工具化收口（2026-08-24 晚） | 思考模式重开入口修复（commit 6ea8c1b）已 ff 合并主号 main（108c5a2→6ea8c1b）并推送。改动：ChatScreen.kt + ChatThinkingBadgeUI.kt，徽章显示条件从… |
| 08-24 18:48 | 更新：手动添加大模型删不掉 bug 已派发修复任务，任务文件 = 自包含（背景+3方案+步骤+验收），路径 /var/m | 更新：手动添加大模型删不掉 bug 已派发修复任务，任务文件 = 自包含（背景+3方案+步骤+验收），路径 /var/minis/shared/model-delete-bug-diagnosis.md（即之前的诊断报告… |
| 08-25 09:00 | Token 用量统计优化 A+B 已派发（2026-08-25） | 用户拍板方案 A（归属正确性）+ B（聚合性能+体验），已写好两份自包含任务书派发： |
| 08-25 20:22 | rikkahub 流畅性吸收 — 施工方案已产出并派发就绪（2026-08-25） | 用户拍板要「具体可直接施工的方案」。调研（rikkahub@3ebda54 vs RikkaMinis main@62a3a7d）落地成 5 任务派发包，目录 /var/minis/shared/rikkahub-smo… |
| 08-25 23:03 | D 任务收口合并完成 — main = 0e07ac4（2026-08-25） | D 分支（fix/message-node-item-renderer @ 0e07ac4）已 ff 合并 main 并推送主号（2863f60→0e07ac4），远端分支已删（API 204）。main release… |
| 08-26 00:13 | rikkahub 流畅性吸收 — A/B/C/D/E 全部完成并合入 main（2026-08-25 búi 收口） | 5 阶段全部合入 main（当前 main = 4829e67），收口会话独立核实（拉取 origin/main + Actions API 交叉核对 head_sha，非转述）： |
| 08-26 02:58 | 最近改动 bug 已派发两个施工任务（2026-08-26） | 报告：/var/minis/shared/recent-changes-bug-audit-2026-08-26.md |
| 08-26 11:19 | 收口：内存三线调查全部关闭（2026-08-26 11:30） | 任务：proot-vsz-rootcause-task.md → 已完成并收口，任务文件内已写结论。 |
| 08-27 15:48 | 会话任务 H（历史回底部第四轮）终止交接（2026-08-27） | 任务 H 施工终止转交接，交接文档 /var/minis/shared/rikkahub-smoothness-absorption/session-task-H-handover.md。 |
| 08-27 23:52 | 收尾加固派发包就绪（2026-08-27 深夜） | 审计结论（main@9105ff1，445 文件/15.8 万行）：20 项审计面全过（四处同步/Toast 线程/异常兜底/runAgentLoop 外层 catch 链/网络超时/备份排除规则/图片压缩/文件预览截断… |
| 08-28 07:39 | A/B/C 三任务收口合并 main 完成（2026-08-28 07:30） | main = a23bdf1（9105ff1 → 470dea3(A) → 3ad6bd3+e8e0b97(B) → 69d967b+a23bdf1(C)），release run #1143 success（head … |
| 08-30 00:42 | 扫描修复包四任务全部收口（2026-08-30 凌晨） | main 从 ea096be 推进到 d49235c（A→B→C→D 四 commit 依次 ff），release CI run 33262714099 success（head_sha=d49235c 核实一致）。四… |
| 08-31 13:28 | 任务② memory facts 派发准备完成（2026-08-31） | 两决策点用户拍板：A=写入时 agent 自声明（memory_write 加可选 facts 参数，零额外 LLM 调用）+ rollup 时机文案提示回填（v1 不自动化）；B=v1 不进 SyncMerge（SYN… |
| 08-31 14:44 | facts 任务收口：memory-facts + litellm-cost-json 双分支合并 main（2026-08-31） | main = c87df78b（ea6b9213 → fbe888e7[litellm] → c87df78b[memory-facts]），release CI run 33363933066 success（head… |
| 09-01 15:53 | place-storm 修复收口：小号 main 已合并（2026-09-01） | 分支 fix/place-storm-follow-clamp-loop 分支 CI run 33480996005 success（head_sha=70f927d 核实） |
| 09-01 16:54 | place-storm 钳位修复汇入主号收口（2026-09-01） | 用户拍板：小号 70f927d1 的 SIMPLE_FOLLOW 钳位守卫修复已在 lab 真机验证（日志 minis-2026-09-01__3_.log 全绿），汇入主号。 |
| 09-02 12:26 | Bug-Hunt 四会话审计收口（2026-09-02） | 四会话并行审计 /tmp/rikka @ de2dca7d：HIGH 11 报出 / 10 实锤 1 误报，MEDIUM ~15 实锤。收口报告：/var/minis/shared/rikka-bug-hunt/repo… |
| 09-02 16:39 | FE-5 第二批拆分完成 + 第三批交接（2026-09-02） | 第二批（route B 工具执行层）已合并 main b38a186f：ChatViewModel 11899→11528（-371）。两个新文件： |
| 09-02 19:01 | ↳ FE-5 第三批 route C 前两步完成（2026-09-02，commit f297481 合并 main） | 交付：ChatViewModel 11528→11037（-491）。两个新文件 + 1 提升类： |
| 09-02 20:24 | FE-5 route C ③ 完成待 CI + 第四批交接（2026-09-02 晚） | route C ③（AgentLoopEngine 主体搬迁）已完成编码，commit be7d3a5，分支 refactor/fe5-route-c-agentloop-engine2，CI run 336294072… |
| 09-03 01:08 | FE-5 第五批第一簇完成 + 第二簇交接（2026-09-03） | 进度：ChatViewModel 12338 → 6499（累计 −5839，约 47%）。目标 3500-4000，还差 ~2500-3000。 |
| 09-03 07:28 | ↳ FE-5 第五批第二簇完成（2026-09-03） | 交付：分支 refactor/fe5-batch6-cluster2，commit 9b8a0a03 + 8c451f7f + 5236b8bd，CI run 33694126769 success，ff 合并 main… |
| 09-03 10:06 | FE-5 第三簇交接前状态快照（2026-09-03 会话收尾） | main @ f8e3b6b（本地=远端一致，工作树干净）。release CI 33706046499 触发中（f8e3b6b 的完整验证）。 |
| 09-03 19:34 | FE-5 bug-audit 全库扫描 + 修复收口（2026-09-03） | 全库 678 文件 / 199,705 行 / 9.2MB，7 域并行审计（deepseek-v4-flash-0731 带工具 + R 类拆分规则库）。报出 High 7 / Medium 19 / Low 13 = … |
| 09-04 00:19 | subagent 跨会话派发功能验证（2026-09-04） | 验证了 HEAD 813eaf6 的 subagent 跨会话派发功能（eaa3a10 引入，SessionsOffloadHandler + SubagentPrefs + minis-sessions-cli sen… |
| 09-04 09:37 | 子代理设置行 UI 修复（2026-09-04，已合并 main @ 07d63699） | 用户反馈：设置页「子代理派发」副标题太长占 3 行（其他设置项都 1 行），要求压到 ~18 字符内。 |
| 09-05 12:49 | thinking-gap-close-0905 分支完成，交接给下一会话（2026-09-05） | 分支 feat/thinking-gap-close-0905（HEAD f134fd02）：AUTO 档 + golden/Gemini guard + provider knobs（custom headers/bo… |
| 09-06 19:40 | 会话交接（2026-09-06 晚）→ 交接文档 /var/minis/shared/hermes-tier1-handoff.md | main @ 1150e05f（tier-1 harness 四改动 + EOF 断流静默停修复，双分支 CI 绿 ff 合并）。main release CI run 34030771852 结论未等——新会话开场先查… |
| 09-06 23:34 | 收尾交接（2026-09-06 深夜） | 交接文档 /var/minis/shared/tier2-closeout-handoff.md（突然停三形态闭环总表 + Tier 2 最终状态 + backlog + 采集器重挂命令） |
| 09-07 01:31 | provider-exec-concurrency 分支（A+B 多会话并发）+ 突然停第4形态（预算墙）双修复 | 分支 feat/provider-exec-concurrency（未合并 main），三提交： |
| 09-07 02:10 | 用户真机验证通过：多会话真并发（2026-09-07） | 用户亲测确认：装 android-latest（main @ 303d375f）后 2~3 个会话同时进行，真并发生效，无冻结——provider-exec-concurrency 分支的核心目标（B：execution… |
| 09-07 02:38 | fix/budget-banner-number 交接（2026-09-07，交给下一会话收尾） | 背景：用户真机撞到 provider-attempt 预算墙，横幅正确显示但数字是错的——写死 "64 calls"，实际预算已提为 128（同一次提交改了预算忘了改文案，出生即漂移）。 |
| 09-07 20:08 | 渲染三连修交接（2026-09-07 深夜，待新会话执行） | 用户拍板：开一个新会话把三个修复一起做完（不打补丁，按不变量修）。交接文档已写： |
| 09-08 13:05 | 环境变量分组 + Sheet 修复（2026-09-08，分支 fix/envvar-flatten-usage-sheets @ 85698cd，已交接未合并） | 任务：①删平台特殊卡片（用户明确"也是要砍的"）②环境变量分组功能（用户澄清"分组指的是环境变量中的分组，解决变量太多乱的问题"——首轮理解偏差：我误以为分组也不要，实际只要砍平台卡片）③Token 用量抽屉顶太高（fi… |
| 09-08 16:45 | 自动备份 A+B 施工交接（2026-09-08，feat/backup-assets-complete @ f8a2372，未合并） | 第三轮分支 CI run 34205678023 in_progress 结论未确认——新会话开场先查。交接文档 /var/minis/shared/backup-assets-complete-handoff.md（含… |
| 09-08 19:10 | 自动备份远端管理 + auto/ 目录分离施工交接（2026-09-08 晚，分支 feat/backup-auto-remote-dir @ 3c155f6，未合并） | 用户多设备痛点（实测）：A 自动备份推 WebDAV 成功；B「自动备份」区无入口（本地列表=本机文件，设计如此但无引导）；B 全量远端列表混入 auto 文件无法区分，第一轮点恢复"没恢复"（实际恢复的是 B 自己的备… |
| 09-09 20:27 | 全局第二轮审计收口 + HIGH 修复闭环（2026-09-09 晚） | 第二道门核实（reports/FINAL-closure.md）：10 域 498 文件 / 169,059 行，5 HIGH + 34 MEDIUM + 55 LOW。5 条 HIGH 逐条独立核实： |
| 09-10 00:19 | 全局第二轮审计修复进度（2026-09-09 深夜，交接点） | 交接文档：/var/minis/shared/audit-0909-round2-handoff.md（102 行，含状态锚点/批次表/剩余清单/下一步命令/坑） |
| 09-10 00:22 | 第二轮审计修复交接收尾（2026-09-10 00:25，会话结束点） | 交接文档：/var/minis/shared/audit-0909-round2-handoff.md（102 行，状态锚点/批次表/剩余清单/精确命令/6 条坑） |
| 09-10 00:26 | ↳ 静态作用域验证脚本（B11 收尾新增，可复用） | 脚本：/var/minis/shared/tools/verify_offload_scope.py（+ 坏样本 verify_offload_scope_selftest_Bad.kt） |
| 09-10 02:49 | 第二轮审计 LOW 全收口（B22，main @ 54eade2，2026-09-10） | 结果：11 项剩余 LOW 一次做完 → 分支 fix/audit-0909-b22 CI run 34389944956 success（head 54eade2）→ ff 合并 main @ 54eade2 → re… |
| 09-12 02:14 | 交接完成：A1 Prompt Cache + A2 定时 rollup → 新会话处理（2026-09-12） | 交接文档：/var/minis/shared/prompt-cache-rollup-handoff.md（含全部代码坐标/执行步骤/坑位/验证清单） |
| 09-13 13:31 | 【交接】打开会话「差一段」v1 已装机验证 → 需 v2（2026-09-13 13:35） | 完整交接文档：/var/minis/shared/open-catchup-handoff-2026-09-13.md（14.6KB，含全部背景/证据/代码位置/流程/环境）——新会话先读它。 |
| 09-13 15:56 | ★★ 交接：legacy 管线隔离 + backlog 两项（分支未推，本会话终端环境已损坏） | ⚠️ 本会话终端不可用：任何 shell_execute 都会触发 [System busy: process memory is critically high (982/1022/2283MB)]。成果全部在磁盘上，… |
| 09-13 17:02 | ★★ 交接：native OOM 频发 + 冷打开空白（本会话产出，未动手修） | 交接文档：/var/minis/shared/native-oom-handoff-2026-09-13.md（10.8KB，含全部硬证据 / 代码位置 / 缺口分析 / 任务书 T1-T5 / 环境纪律） |
| 09-13 17:09 | ★ 交接：内存探针（memspike）非 shell 路径扩展 —— 分支待装包复现（2026-09-13 17:10） | 一句话状态：诊断分支 feat/diag-memspike-dualapp @ 73d741c（3 commits，已推，工作树干净）已做完并把探针接到非 shell 路径；CI run 34748974970 已派发（… |
| 09-14 17:07 | 09-14 深夜：A3 工具参数校验升级完成（fix/preflight-schema-guards @ 27265bbf，CI 已派发） | 改动（4 文件 +276/−5，基于已合并的 main=fbc50cdd）：①ChatViewModel.preflightValidateToolCallImpl 新增枚举成员校验 + 标量/容器形状校验（AgentT… |
| 09-14 17:41 | 09-14 深夜：加固第二批全部收口（main = a383f8c7）+ B2 交接文档 | 已合并 main = a383f8c7（远端仅剩 main，三个分支均 FF 合并后删除）： |
| 09-14 20:20 | 09-14 深夜：交接文档已写（HANDOFF-absorb-2026-09-14） | 交接：/var/minis/shared/HANDOFF-absorb-2026-09-14.md（8KB）。两个分支：分支 1 feat/absorb-small-batch = U1 更新包 digest 校验 + … |
| 09-15 16:30 | 09-15 晚：轴 2（行内层纯函数 fuzz）收口 — 186/0 绿，重大抽取纪律抓漏 | 范围：StreamingMarkdownText 行内层 5 个纯函数（safeInlineSplitOffset / findInlineCodeClose / inlineMathSizeEm / collectIn… |
| 09-15 16:36 | 09-15 晚：轴 3（reducer 状态机矩阵 + 随机序列 fuzz）收口 — 零 bug，零违规 | harness：/tmp/fuzz-md/ReducerMatrix.kt + ReducerFuzz.kt，生产 AgentRunReducer.kt + AgentRunState.kt 逐字编译（零改动）。 |
| 09-15 16:56 | 09-15 晚：轴 4/5/6 收口（事件接线/决策层/资源生命周期） | 轴 4 事件接线：运行循环实际发射 9 种事件；PersistenceFailed 零发射点（§14 已攒 backlog）。 |
| 09-15 17:06 | 09-15 晚：轴 7 收口（loop mechanics 时序审计）— §15 发现 + 七轴 sweep 全部完成 | §15（真发现，旁路层）：runAgentLoop 两个 catch（2751 CancellationException→t7EndRun(CANCELLED) / 2768 Exception→t7EndRun(FA… |
| 09-15 18:05 | 09-15 晚：+1545 死循环修复真机复测通过（收口） | 用户装包实测大文件（dev-history 档案）不挂 → markdown 解析器死循环修复（ad7c9e3f）真机验证通过，§14/§15 之外的最后验证缺口关闭。 |
| 09-15 23:51 | 09-16 凌晨：四线全部收口 — main = 10e4652（同族收口 + isInFlight 谓词不变量） | 收口链（全部 FF、远端仅剩 main）： |
| 09-16 13:12 | 09-16 午后：audit-0916 批次（§19/§20/§21）修完推送分支，CI 跑中（交接已写） | 分支 fix/audit0916-timeout-retry-and-browser @ 4aa2e76（11 文件 +340/−46，基 main 1863e4d），CI run 35057861211（最后看 in_… |
| 09-16 13:14 | 09-16 午后：audit0916 批次（§19/§20/§21）收口 — main = 4aa2e76 | 分支 CI 35057861211 success → FF 合并 main（1863e4d..4aa2e76，11 文件 +340/−46）→ push-main OK → 远端分支 API DELETE 204 → … |
| 09-16 13:57 | 09-16 下午：semantic-memory 修复收口（main = 735eadb）+ 第二批热榜审阅 | 修复已闭环（用户拍板"打包一起"）：时间衰减从「乘性侵蚀」改为「加性助推」+ 输出真实 cos + SKILL.md 约定改为按 cos 判定。分支 fix/semantic-memory-scoring-0916 @ … |
| 09-16 14:21 | 09-16 傍晚：§20b 导航结果修复收口 — main = e940148（release CI 35063273119，用户拍板不等） | 分支 fix/audit0916b-nav-outcome（2 commit）→ cherry-pick 到新 main 735eadb 之上（另一会话推了 skills commit，零重叠）→ push tmp-me… |
| 09-16 16:36 | 09-16 晚：报告修复轮收口 — main = 116a23d5（M1/M2/M3/L3 已修） | 闭环：分支 fix/audit0916c-release-boundary-and-webdav-tls @ 116a23d5（8 文件 +657/−7）→ 分支 CI 35073346367 success → FF … |
| 09-16 17:43 | 批次 23 收口：分支 feat/batch23-absorb（3 commit，13 文件 +252/−28 基 ma | 批次 23 收口：分支 feat/batch23-absorb（3 commit，13 文件 +252/−28 基 main 116a23d5）→ CI run 1578 success → FF 合并 main = 2… |
| 09-16 18:17 | 09-16 晚：23c-2 渲染期 resolve + 灰链收口 — main = 3df7977e（release CI 35084162878，用户拍板不等） | 改动：ChatLinkRenderCache（LRU 256，键 (url,sessionId)，resolveFn 注入 → JVM 可测）+ LocalMarkdownLinkRenderResolver Compo… |
| 09-16 19:04 | 09-16 晚：audit-0916d 收口 —— main = 551ed6b9（两处 CI 门缺陷修复） | 审计发现（今天 15 提交逐个走查，运行时改动零真 bug，缺陷全在新 CI 门自己身上）： |
| 09-17 09:11 | 09-17：审计交接文档已就绪（新会话处理 215 条 CONFIRMED） | 09-17：审计交接文档已就绪（新会话处理 215 条 CONFIRMED） |
| 09-17 14:22 | 09-17 下午：审计存量缺陷批次 1-7 收口 — main = e657335f | 成果：215 条 CONFIRMED → 已处理 155 条 / 92 文件，剩余 60 条（HIGH 4 / MED 32 / 其他 24）。10 个提交 FF 合并，CI 全绿，远端仅剩 main。 |
| 09-17 14:26 | 09-17：审计交接包完成（新会话可直接接手剩余 58 条） | main = e657335f，远端仅剩 main，CI 绿。交接物全部在 /var/minis/shared/audit-0917/（14MB）。 |
| 09-17 15:52 | 09-17 下午续：接手瘫痪会话的工作树（b7/b8 批次收口） | 背景：另一个会话在做审计剩余 58 条（HANDOFF-audit-fix-2026-09-17.md），因对话压缩故障瘫痪，无法交接。本会话直接接手它的 /tmp/RikkaMinis 工作树。 |
| 09-17 16:43 | 09-17 晚：审计 58 条全部收口 — main = 8fbb9d2e（release CI 同 SHA） | 收口链路：分支 fix/audit0917-chatbatch（6 提交，5 轮 CI：3 编译错 @ nav-currentStateFlow / 坏删 context / toolTitle 作用域 + 1 单测契约… |
| 09-17 16:58 | 09-17 收口后复查：今日 30 提交（审查结论 + 1 个真缺陷） | 对象：main 8fbb9d2e vs 昨日 c6f06d8b（30 提交 / 143 文件 / +2371−532）。报告：/var/minis/mounts/笔记/今日改动复查报告-2026-09-17.md |
| 09-17 17:15 | 09-17 收口：OffloadPermissionManager 超时守卫修复 → main = fcfaf515 | 修复：requestAndroidPermission TIMEOUT 分支的 androidPermissionContinuation = null 从「无条件」改为与 dialog 清空同一个 if (androi… |
| 09-17 21:04 | 09-17 深夜收口：聊天列表误渲染修复真机验证通过（main = 14ca90e3） | 用户装机 1.0.0+1630（lastUpdateTime 21:02:03，= CI #1630 artifact / 14ca90e3 树）后确认：样本行显示为普通段落，符合预期 → "双胞胎解析器"修复实锤生效。… |
| 09-18 00:29 | 09-17 深夜收口：冷启动恢复修复真机验证通过（main = bd55749b） | 用户真机验证通过：装的是分支构建（CI #1637 artifact，bd55749b 树）解压出的 APK，完全退出后重开 → 新会话，符合预期。四层闭环全部打开：新代码 6/6 绿 → 反向对照 3/6 红 → 分支… |
| 09-18 10:14 | 09-18：两分支盯 CI + 审查 + 合并收口 — main = ec52ba58 | 用户点名盯的两个编译分支：fix/indented-bullet-continuation（34d19003）与 feat/skill-md-file-render（6dd662e5），均基 ab1a3203。 |
| 09-18 12:01 | 09-18：1 号满权限小号（alarmedvine）接入收口 —— 含 gh_fullright.sh 两个老 bug 修复 | 新账号：环境变量 GITHUB_TOKEN_FULL_RIGHT_1 = GitHub 1 号小号 alarmedvine（ID 210298370，2025-05-05 注册，free，有 2FA，21 个全量 sco… |
| 09-18 15:20 | 09-18 晚：日志全量覆盖改造收口 → main = cdd2817（CI #1651 绿 → FF main → release #35318635393 自动触发，用户惯例「触发后不用等」） | 用户命题：日志是应用的基础设施，应能全量反映运行 → 先量化缺口，再按缺口逐条修。 |
| 09-18 16:17 | 09-18 晚：双修复开工收口 → main = 1fb74fd0（两个分支各自 CI 绿 → FF → 远端只剩 main） | 修复 A（a3183aa1）：ack 竞速 —— ModelExecutionRunDir.workerDrained() 共享判据（只认 worker 自有证据：terminal，或 result+beat silen… |
| 09-19 09:52 | 09-19：offload 审计交接包落盘（第 3 棒待接力）+ 一条重要教训 | 用户指令：「准备交接，进行接力」。产出跨会话交接包 → /var/minis/shared/offload-audit-0919/（关键前提：/var/minis/workspace 是会话私有，交接必须先归档到 sha… |
| 09-19 10:24 | 09-19：offload 审计第 5 棒交接包封包完成（新会话入口 = HANDOFF-offload-5th.md） | 用户指令：「准备交接，下一对话再继续推进」。产出跨会话交接包 → /var/minis/shared/offload-audit-0919/（1.6M，入口 = HANDOFF-offload-5th.md，202 行）… |
| 09-19 11:18 | 09-19：offload 审计第 6 棒交接包封包完成（入口 = HANDOFF-offload-7th.md，**下一棒起转为写代码棒**） | 封包自检（模拟新会话开工）：sh bootstrap.sh（复用 /tmp/RikkaMinis，锚点 c6d8d63f，工作区 0 改动，5 个关键文件行数指纹 ✅）→ sh verify_all.sh 20/20 ✅… |
| 09-19 12:57 | 09-19：云端两分支审查 + 合并进 main = 99783703（P1 隐私面 + 子代理上下文） | 用户指令：检查云端两个分支有没有引入问题，没问题就合并，合并触发后不用管。 |
| 09-19 22:38 | 09-19 晚：offload 审计转「并发派发」模式 —— WAVE-2 四线并行（第 21 棒） | 背景：用户要求提速（"效率太慢，开拓会话并发同步推进"），并授权"不要问，直接扫、推完"。主会话转为派发者 + 收口者。 |
| 09-20 02:59 | 09-20：第 21 棒 WAVE-2 并发审计 → 交接包就绪（给新调度中枢） | 用户决定：本会话不再等 C1/C2/D1 收尾，直接交接——新会话当调度中枢，本会话提供背景信息。 |
| 09-20 04:50 | 09-20：FIX-5 收口 —— 已合并 main = `4cde346` | 流程：分支 fix/data-layer（基线 afa404b）→ 判据 43/43 + JVM 16/16 → push → dispatch CI #1666 success（核对 head_sha=4cde346 … |
| 09-20 09:21 | 09-20：FIX-WAVE 修复批次并行 → 统一合并收口（第 22 棒，main = `6a10661`） | 起点：读 HANDOFF-offload-21st-TO-DISPATCHER.md（第 21 棒交接包），用户追加背景「云端 main 已被推进」，要求「核实聚合信息 → 分配任务 → 各自分支 → 统一合并」。 |
| 09-20 17:14 | 09-20：键盘遮挡编辑内容（IME occlusion）—— 诊断完成，交接给新会话 | 用户报告：技能编辑 / 记忆编辑中，点击后输入法键盘直接盖住正在编辑的内容；并要求排查所有编辑面。 |
| 09-20 18:12 | 09-20 夜：日志全量异常扫描（去污染口径）+ 两任务已派发 | 用户指令：①用子代理把修复任务安排出去 ②查还有没有类似问题，分析日志找异常，找到就继续往下分析。 |
| 09-20 18:45 | 09-20 夜（续）：日志扫描收官 → 共派发 5 个修复任务 | 用户指令：「如果发现了问题，并且能够确定的话就直接用子代理把任务派出去。」 |
| 09-20 23:51 | 09-20 深夜：T2 线（regression-audit-0920）取证收口 —— 任务书前提被证伪，按修复门纪律不动手 | 结论：/var/minis/shared/regression-audit-0920/tasks/T2-degrade-restore.md 的四条事实前提全部不成立。产出 /var/minis/shared/regre… |
| 09-21 17:10 | 09-21 续：fixB 三分支（#377/#116/#93）CI 四轮收口 —— ★ KDoc 方括号陷阱 + golden snapshot 抓到我修过头 | 分支 fix/provider-effort-notification-picker @ 154ac5c9，CI run 35580684921 success（head_sha 逐字符核对，Publish to Rel… |
| 09-21 18:57 | ↳ 09-21 晚：三云端分支审查 → 修掉一个真回归 → 全部合并进 main（`d09b1915`） | 用户指令：「检查核实云端上的三个分支，看看他们是否修改出了问题，以及有没有真正意义上的解决问题，如果有问题就修一下，如果没有问题就把他们合并了。」 |
| 09-21 19:38 | 09-21 晚：S1 小三条完成（派发线 fix/s1-small-trio @ `d25b45c1`，CI 35593673164 绿，**未合并**） | 任务书：/var/minis/shared/dispatch-0921/tasks/S1-small-trio.md（五线并发派发中的 S1）。交付边界 = 分支 CI 绿，不合并。 |
| 09-21 19:42 | 09-21 晚：S2 派发任务完成 — provider 层两条（§27a 图片门 + §24b TTFB 统一） | 分支 fix/s2-provider-layer @ eca54225b4b93df0f3562a61926e22ac496edd5f，CI run 35593738205 success，head_sha 逐字符一致，… |
| 09-21 19:43 | 09-21 晚：S5 派发任务（resume-guard 核实）→ 结论 C，零逻辑改动，分支 CI 绿 | 任务：核实 backlog §27c(2)「生产 canResume 不看工具结果是否已知 ⇒ 设计意图与实现不一致」是否成立。 |
| 09-21 21:53 | 09-21 深夜（续）：Chromium/Acode 对标收口 —— 三处自我纠错 + 产出 tag 锚定查询器 | 用户指令 |
| 09-21 22:01 | 09-21 收口：dispatch-0921 五分支审查 + 合并 main = `671f928c`（CI 35607838698 全绿，release 资产已刷新） | 用户指令：检查核验云端 5 个分支 → 有问题就改，没问题就合并。 |
| 09-22 01:45 | 09-21 深夜：V4 线（verify-all-0921 剩余模块验证）收口 —— 产出 5 个装置缺陷，其中日志撕裂机制完全确证 | 任务书 /var/minis/shared/verify-all-0921/tasks/V4-rest.md。产出 /var/minis/shared/verify-all-0921/reports/V4-report.… |
| 09-22 04:21 | 09-22 收口：全应用微功能验证（verify-all-0921）四线汇总 → REPORT.md | 任务：接替上下文已满的会话，收口 V1–V4 四路验证线，产出总账。产物 /var/minis/shared/verify-all-0921/REPORT.md。仓库 0 改动。 |
| 09-22 11:31 | 09-22：思考强度会话交接（接手者必读） | 交接产物：/var/minis/shared/thinking-audit-0922/handoff/HANDOFF.md（8 章节，含待决问题 + 复跑命令 + 纪律） |

## 13. CI / 构建 / 发布流水线

**跨度** 2026-08-03 ～ 2026-09-22 · **180 条** · **状态** 稳定（Publish 步骤已加 `if: refs/heads/main` 门控）

**叙事**：08-04 main 编译被 WebDAV 提交弄坏（连红 4 个 run）→ 08-05 android-latest 被分支构建污染 → workflow 门控修复 → 08-17 **CI 缓存恢复旧 native .so**（Tier 0 真 bug）→ 08-24 gh_ci_wait.sh 工具化 + 重复构建幂等守卫 → 09-10 语法门 grep 大小写坑 → 09-14 workflow_dispatch 并发竞态。**「CI 绿 ≠ 逻辑对」这条纪律就是从这条 saga 长出来的。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 16:35 | OpenMinis proot 源码构建（2026-08-03） | 用户关注点：从 APK 提取的 proot 二进制能否由开源仓库替代/改善。 |
| 08-03 17:28 | OpenMinis fork 恢复 proot 源码构建（2026-08-03） | 分支 feat/build-proot-from-source（commit 1506c14，已推送 GitHub）。 |
| 08-03 17:39 | OpenMinis fork proot 源码构建已上线（2026-08-03 完成） | 分支 feat/build-proot-from-source（1506c14）已快进合并到 main 并推送，CI run 30801684624 全绿 success。 |
| 08-03 21:00 | OpenMinis Android — 空对话残留 bug 根因与修复（commit b194927，CI 30815328668 success） | 用户报「历史里出现空对话，自动删除时好时坏」。定位到根因链： |
| 08-04 07:18 | CF-Optimizer 仓库 v4.2 → v4.6 更新推送（2026-08-04） | 用户两个挂载文件夹：CF-Optimizer（开源仓库工作副本，GitHub OWNER/CF-Optimizer）与 Cloudflare优选助手_v4.6（日常使用版）。任务：把 v4.6 迭代同步到开源仓库并推送。 |
| 08-04 17:06 | 工作流备忘：main 自动构建无需等待（2026-08-04） | push 到 main 之后 GitHub Actions 会自动跑正式构建并刷新 android-latest 的 APK 资产。这条链是自动的，无需用 delay/sleep 空等去盯它跑完： |
| 08-04 17:56 | RikkaMinis — fix/webdav-restore-doublefire 构建检查（2026-08-04） | 用户报「构建完成了，检查一下」。检查结果： |
| 08-04 20:16 | vlc-android — release split 包安装失败 (33) 已修复（2026-08-04，commit 25f8bcb） | 用户报新 release 包（49.6MB）安装报"解析软件包时出现问题 (33) packageInfo is null"。 |
| 08-04 22:10 | RikkaMinis 模型组重构 — CI 首次失败根因 + 自检教训（2026-08-04 续） | commit 6cf806f 首次 CI（run 30915989290）失败。根因 = AgentLoopModelsScreen.kt 从原文件搬代码时切边界不干净：把下一个函数（GroupModalityMarke… |
| 08-05 01:44 | RikkaMinis — rootfs 管理页修复已完成并 CI 全绿（2026-08-05，commit 7468873） | 分支 feat/draft-persistence-ime-storage 新增第 5 个提交 7468873（fix(rootfs): use real disk usage in the rootfs managem… |
| 08-05 16:53 | RikkaMinis android-latest 被分支构建污染 + workflow 门控修复（2026-08-05） | 开发 Input History 功能时再次踩中已知坑并彻底修复： |
| 08-06 00:16 | TokenUsageSheet 补上缓存命中率（2026-08-06） | 用户发现 Settings → Usage Stats 有缓存命中率，但对话中 ⋮ → Token Usage 底部 sheet 没有。 |
| 08-06 10:25 | RikkaMinis CI 构建周期 | CI 全流程（assembleRelease + testReleaseUnitTest）约 7 分钟构建打包完。从 workflow_dispatch 触发到出结论约 7 分钟左右。 |
| 08-06 10:36 | RikkaMinis 经验记忆滚动修复合并 — 编译失败与修复（2026-08-06 下午） | 事件 |
| 08-06 16:46 | RikkaMinis CI 构建耗时备忘 | 用户提醒：RikkaMinis 的 build-apk.yml CI 构建（从 workflow_dispatch 派发到 completed）大约需 8 分钟以内。轮询 CI 状态时，间隔建议按此节奏安排，避免过早频繁… |
| 08-06 17:54 | RikkaMinis fallback 不一致修复（2026-08-06 深夜，分支 fix/fallback-reentry-and-anchor，commit 400db4b，CI 31090465585 success） | 用户报告 |
| 08-06 18:40 | RikkaMinis 记忆页改造 — feat/memory-management-optimize（2026-08-06 晚，CI success run 31093633313，commit a26eaeb） | 用户真实需求（对齐后） |
| 08-07 09:12 | RikkaMinis 待办 #1 滚动决策函数 — 已实现并推送（2026-08-07） | 完成状态 |
| 08-07 10:01 | RikkaMinis 滚动"跳"根因确诊 + 修复（2026-08-07，已验证 CI #196 success） | 背景 |
| 08-07 13:51 | RikkaMinis 双修复方案 A+B 完成并推送 CI（2026-08-07 下午） | 用户报(13:17)又出现 1) 读历史被拽回底部(bug 复现) 2) 一次 native SIGABRT 闪退。诊断后用方案A+B 修复，推分支 fix/proot-rss-monitor（2 commits 8d3… |
| 08-07 15:51 | RikkaMinis PRoot 泄漏修复 — 方案A(nativeRss) 从 fix/proot-rss-monitor 拆出推 CI（2026-08-07 下午） | 背景 |
| 08-07 16:01 | RikkaMinis 方案A(nativeRss) — CI 全绿，待用户确认合并（2026-08-07 下午 收尾） | run 31159304878 conclusion: success（job "build" 全过：NDK proot 编译 + APK assemble + 签名校验 → 源码级正确，nativeRssMB/proc… |
| 08-07 17:50 | RikkaMinis 四分支合并进 main（2026-08-07 09:50 已完成推送） | 用户要求"把最近的更改合并进 main"。处理了 4 个待合并分支，全部无冲突 merge（ort 策略自动合并），HEAD = 63c4c21 已推送，CI run 自动触发（queued）。 |
| 08-07 17:55 | 四分支合并后 CI 失败修复 — MemoryManagementScreen 未闭合注释（2026-08-07 17:5x） | 事件 |
| 08-08 00:10 | 关键坑：Kotlin 前向引用导致 compileReleaseKotlin FAILED（CI 单测组合步骤误报） | 现象 |
| 08-08 00:35 | 终端沙盒修复施工 — CI 编译通过（2026-08-08） | 在分支 fix/sandbox-audit-2026-08-08（commit 5d0faeb，基于 b45a68a）完成 4+1 项沙盒修复，push 后 dispatch CI run 31197544235，编译成… |
| 08-08 01:27 | 统筹合并完成 — provider/memory/token 三分支合 main + CI 修复（2026-08-08） | 背景 |
| 08-08 01:48 | ↳ 未合并分支已全部合并到 main（2026-08-08） | feat/logging-module-optimize (092ae88) — 日志模块优化：降低 flush 节奏、修日志读取 OOM、容量上限、i18n crash 区块 → ff 合并 main |
| 08-08 10:08 | promote-draft-on-new-chat 分支已推送，CI 构建中 | 分支 feat/chat-promote-draft-on-new-chat 已推送（commit 82b15c8），CI run 31234268332（workflow_dispatch，in_progress） |
| 08-08 10:15 | 输入模块优化：hasText 统一 + T217-2 composition 门控（2026-08-08） | 分支 fix/input-composer-hastext-composition（基于 main 0d968d4，commit 38d84fd），CI run 31234234221 success。 |
| 08-08 10:37 | 任务完成提示音 — feat/completion-sound 已推送 CI 绿 | 分支：feat/completion-sound（commit c8f49e8，基于 main 82b15c8，未被并发会话改动冲突） |
| 08-08 10:50 | ↳ 三个待合并分支已全部合并 main（2026-08-08） | 合并时 main 基线：82b15c8（promote-draft） |
| 08-08 11:28 | 灵动岛焦点通知测试 — CI 已绿，待用户装包验证（2026-08-08） | 分支 feat/focus-notification-dev-test（commit d256c64），CI run 31236760448 success |
| 08-08 11:52 | 移除冗余关闭/返回按钮 — 已提交推送，CI 验证中（2026-08-08） | 分支 feat/remove-redundant-close-buttons（commit 8e4bf92，基于 feat/recovery-strategy HEAD 9874a4f） |
| 08-08 11:59 | ↳ 四个待合并分支已全部合并 main（2026-08-08，HEAD b000e31） | 合并方式：feat/recovery-strategy(9874a4f)、feat/remove-redundant-close-buttons(8e4bf92) 线性 ff；perf/immutable-chat-mo… |
| 08-08 18:09 | 模型选择器改圆形按钮（feat/circular-model-picker）2026-08-08 | 用户反馈"对话框显示模型名称的应该改成圆的，为什么还是原来的样子"。 |
| 08-09 07:27 | 终端双问题根因 + 修复（commit b8dd5cb，CI run 31283965704） | 用户报两个新症状（#305 APK = e628206）： |
| 08-09 07:42 | 终端修复 #306 完成（CI run 31284262599 success） | APK：/var/minis/shared/terminux-fix/RikkaMinis-306-terminal-fix.apk（branch feat/termux-terminal-engine） |
| 08-09 13:50 | 供应商详情页只显示已选模型 + 新增"管理全部模型"页（分支 feat/provider-models-manage，CI run 31297147693 success） | 用户灵感：仿 rikkahub"模型照拉、只显示选中的几个"。实现： |
| 08-09 14:57 | 供应商详情页 v2 重构（feat/provider-models-manage，CI run 31299697758 success） | 用户对 v1（整屏管理页 + 大刷新行 + 搜索框有字）反馈"别扭"，定了新布局： |
| 08-09 15:33 | 模型组上下文限制硬生效 — feat/context-limit-enforce（CI run 31301106282 success） | 用户问题：设模型组 contextLimitTokens=128K，Token Usage 面板 Context Used 仍超 128K（能到 200K+）。 |
| 08-10 13:55 | Circuit 正式定名 + 交接归档（2026-08-10 收尾） | 从 RikkaMinis 抽取的"自我修改能力"最小核心，走完全程： |
| 08-12 12:37 | 会话存储回收功能完成（feat/session-storage-reclamation → main bfd621c，CI 双绿） | 用户报本地设置存储页看到工具类对话体积接近 200MB，与备份 OOM（ConfigBackup.export 打包会话内容）同源——会话目录无约束累积、无自动回收。 |
| 08-12 15:54 | T5 上下文压缩引擎完成（feat/context-compactor → main bbf8ab1，CI 绿） | 改动：新文件 conversation/ContextCompactor.kt（纯逻辑决策引擎）+ ChatViewModel 挂载 + 单测。 |
| 08-12 15:55 | ↳ T3 失败学习自动化钩子完成（feat/failure-auto-log → main 667f17d） | 2026-08-13 |
| 08-13 03:30 | ↳ 任务 B：核心文件补测试 ✅ 完成（2026-08-13） | 分支 fix/core-file-tests → rebase 到 main（f76e5d1）后合并推送，分支已删（本地+远端） |
| 08-13 12:13 | Circuit 自进化实验（2026-08-13 11:06-12:13，无限额度临时密钥） | 实验设定 |
| 08-13 14:27 | 核心大文件纯函数测试补全完成（2026-08-13） | 补了 ChatViewModel（11245行）、BrowserUseManager（1726行）、ProviderRepository（2400行）三个大文件中的纯函数测试。 |
| 08-14 00:37 | 最终确认：main 6823ca4 release 构建 31720574220 success（2026-08-13 | 最终确认：main 6823ca4 release 构建 #31720574220 success（2026-08-13 16:2x）。三修复分支（A i18n / B 主题色 / C 生命周期）全部合并 main 并过… |
| 08-14 11:28 | 会话收尾（2026-08-14 03:4x）：任务 D 已闭环，用户确认不再等待 release 构建。main = c | 会话收尾（2026-08-14 03:4x）：任务 D 已闭环，用户确认不再等待 release 构建。main = cce2a10，远端仅 main 分支（feat/run-group-thinking 已删）。rel… |
| 08-14 11:35 | 未验证清单汇总（2026-08-14，等 cce2a10 release 一起验证） | 用户要求"把还没验证的都整理出来，一起验证"。清单文件：/var/minis/shared/verification-checklist-2026-08-14.md（29 项 + 参考）。 |
| 08-14 20:36 | ⚠️ GitHub workflow_dispatch 并发竞态（2026-08-14 D 类会话实测） | 现象：dispatch 自己的分支 CI（ref=fix/err-family-provider-defense），run 的 API head_branch/head_sha 显示正确（fix/err-family-p… |
| 08-14 20:36 | A 类闭环（2026-08-14 晚）—— 已合并 main=0cfff69 + release 绿 | A 类（消息序列结构错误）全部完成，main = 0cfff69，main release build 31800004080 success。 |
| 08-14 20:37 | ↳ 任务 B 完成：已合并 main（91d000a，2026-08-14 晚） | 流程闭环：分支 CI run 31800314630 success（1109 测试全过）→ rebase origin/main（0cfff69 A3，无冲突）→ ff 合并 main 91d000a → push m… |
| 08-15 05:51 | T2 AgentExecutionBudget 纯逻辑核心实现完成（CI 进行中） | 分支：stability/T2-agent-budget |
| 08-15 06:11 | T3 副作用重试策略完成 — CI 绿（1cb366c，2026-08-15） | 分支：stability/T3-retry-side-effects |
| 08-15 06:37 | T5 AgentRunReducer 完成 — CI 绿 + 合并 main（5f1be1f，2026-08-15） | 分支：stability/T5-agent-run-state，3 commits（5405a5c / 8944586 / 7a2029a → cherry-pick 到 main 为 352b9d4 / 45f2103… |
| 08-15 09:20 | T8 Interrupted / OutcomeUnknown 恢复语义 — 纯 JVM 核心完成（CI 绿，分支 stability/T8-interrupted-recovery） | commit：87f11ee（2 files +638/-0） |
| 08-16 18:54 | 滚动修复施工完成：分支 CI 绿，待真机验证（2026-08-16 晚） | 分支：fix/forward-stable-chat-scroll（main@2fcc96c 之后） |
| 08-16 22:42 | fix/memory-dynamic-budget 编译失败任务交接 | commit 1d1ac2290efa 编译失败（CI run 31951519857），根因：动态预算常量定义在 object ExecutionCoordinator 内部作为 private const val，但… |
| 08-16 23:48 | 任务一 release 绿 + 任务二实现提交（2026-08-16 晚） | 任务一（合并 fix/memory-dynamic-budget）全面闭环： |
| 08-17 08:08 | 打断残留修复终极根因：prune 抢先 + rowsTouched 门控 + thinking 折叠信号（2026-08-17 系统性收口） | 用户关键观察（扭转方向，价值极高）：切对话再切回 → 残留消失 → 证明 canonical 数据从头到尾正确，问题纯在"已发布行未刷新 UI"。用户最后拍板：不追求"消失"，而是 UX——打断后的旧回合应呈现"已停止"… |
| 08-17 13:30 | CI 坑：`cache: gradle` 恢复旧 native .so（Tier 0 抓到的真 bug，2026-08-17） | 症状：改了 crash_handler.cpp（加 Process/VmRSS/VmPeak/Threads 字段 + nativeTriggerAbort 测试入口），CI 两次绿（run 828/331），但下载下来… |
| 08-17 14:42 | ↳ ✅ crash-handler Tier 0 修复完整闭环（2026-08-17 下午） | 背景：用户真机装 APK 点 Trigger Native Crash 后崩溃日志显示旧格式（Time: 行、无 Process/VmRSS）——CI 绿但 .so 是旧的。 |
| 08-17 16:02 | 方向A实施进展：Step2调度层完成 + CI绿（2026-08-17 下午） | 分支 feat/chat-stream-modelservice（本地 /tmp/rikka-membudget-merge），f0c90a4 CI run 32007476884 绿。 |
| 08-17 16:39 | 方向A Step3+4 实施：主聊天切流已 CI 绿，Step4 已提交未 push（2026-08-17） | 分支 feat/chat-stream-modelservice（/tmp/rikka-membudget-merge）渐进推进： |
| 08-17 16:45 | ↳ 方向A Step4 进展补充：重复 import 修复已提交未完全确认 push（2026-08-17 17:0x） | Step4 首跑 CI（61c7f16, run 32011476823）失败，根因：ExecutionCoordinator.kt L15/L16 重复 import ModelExecutionService → C… |
| 08-17 17:00 | 方向A Step4 已合并 main（a51d1a8, release CI 待触） | Step4 压力回收（kill+restart :modelservice）修复重复 import 后 commit a51d1a8 → CI run 32011856601 success → ff 合并 main（8… |
| 08-17 17:31 | ↳ 方向A Step5 真机验证通过 — 全链路闭环（2026-08-17） | 用户真机验证通过：此前 native 堆 5GB 锁死的那个老会话，装新 APK（main a51d1a8）后继续推进，不再卡死、变平稳。这直接证明方向A切流（Step3 主聊天流式 → :modelservice）+ … |
| 08-20 11:21 | T1 trim 语义修正 CI 绿 + 拓扑澄清（2026-08-20） | 分支 fix/trim-memory-semantics（1954bac）CI run 32325951490 success。 |
| 08-20 11:33 | ↳ T1 合并 main 闭环（2026-08-20） | 方案 B（用户拍板：打点 + trim 一次 ff 一起进）。 |
| 08-22 01:47 | 2026-08-22 对话崩溃排查 + main 回滚 #985 + release 说明（会话收尾） | 用户决策：最新版（Phase 0-4 内存隔离 + :modelservice/:toolservice/:browserservice 三进程 + bridge）反复出问题，用户拍板回滚 main 到 #985（75a… |
| 08-23 10:30 | TF-H modelservice 进程身份修复完成（CI 全绿，待真机矩阵） | 分支 fix/modelservice-process-proof（基于 TF-G 6c0bb32，四轮 commit：b881fea→ec0cca9→e748597→e9bec97），分支 CI run 3261235… |
| 08-23 15:47 | 修复 04 已推送 | 在独立 clone /tmp/fix04-repo 基于 a6b2665 创建 fix/rootfs-binsh-repair，commit ed9c9a2 并推送小号远端。RootfsManager Stage 2.6… |
| 08-23 15:49 | ↳ 修复 11 完成 | 在小号 ALT_USER/RikkaMinis 基线 a6b2665 上独立 clone /tmp/fix11-repo，分支 fix/memory-rollup-largest-log 提交并推送 commit 722… |
| 08-23 15:56 | 修复 03 分支 CI run 32625933380 已完成并成功，head_sha=6da1df1，与推送 comm | 修复 03 分支 CI run 32625933380 已完成并成功，head_sha=6da1df1，与推送 commit 一致；分支验证闭环完成，仍未合并 main。 |
| 08-23 16:14 | 修复 01：备份/同步字段蒸发 已完成实现+推送（2026-08-23） | 在独立 clone /tmp/fix01-repo（基线 a6b2665 / fix/modelservice-before-dispatch-notify）创建分支 fix/backup-field-evap，comm… |
| 08-23 16:44 | 修复收口完成（2026-08-23 晚）— 5/5 修复分支 CI 全绿 | 基于 a6b2665 的 5 条修复分支全部完成并推送小号远端，分支 CI 全绿（head_sha 与 tip 逐一核实）： |
| 08-24 11:42 | 首块超时修复 release CI 全绿 + 收口（2026-08-24） | release CI run 32686587661 success，head_sha=62d3db4a 已核实一致（防假绿）。远端分支 diag/first-chunk-timeout 已删除。闭环完成：调查 → 修复… |
| 08-24 11:57 | 工具去重修复已合入主号 main：release CI 全绿 + 收口（2026-08-24） | 合并状态：用户拍板「没问题就汇聚到主号」→ 分支 fix/tool-call-dedupe rebase 到最新 main（62d3db4，首块超时修复已合入）→ 新 commit 0b90cf0 → ff 合并 mai… |
| 08-24 16:01 | FIX-A 施工完成：P1 渲染收敛守卫修复，分支 CI 全绿（2026-08-24） | 分支：fix/chat-render-verify-p1（基于主号 main 0b90cf0），commit 844b6b1，已推送主号 origin。未合并 main（等用户拍板）。分支 CI run 32703055… |
| 08-24 17:58 | CI 轮询工具化：gh_ci_wait.sh 一步到位（2026-08-24） | 把原来「dispatch→等 run→找正确 run→核对 head_sha→轮询到 endpoint→输出结论」的手动流程封装成 /var/minis/skills/github-ops/scripts/gh_ci_w… |
| 08-24 18:01 | 思考模式修复已合并 main + CI 工具化收口（2026-08-24 晚） | 思考模式重开入口修复（commit 6ea8c1b）已 ff 合并主号 main（108c5a2→6ea8c1b）并推送。改动：ChatScreen.kt + ChatThinkingBadgeUI.kt，徽章显示条件从… |
| 08-24 18:08 | CI 轮询间隔 | 用户要求 GitHub CI 轮询不要过于频繁：gh_ci_wait.sh 默认轮询从 10 秒改为 60 秒，github-ops 与 rikkaminis-dev-methodology 中的示例也统一为 --pol… |
| 08-25 22:22 | gh_ci_wait.sh 重复构建根因 + 幂等守卫修复（2026-08-25） | 现象：同一构建（同 ref+head）连续起 3 个 run，前两个被 cancel，第三个才跑完。 |
| 08-26 17:41 | 人格(Soul)模块审计+加固完成（fix/soul-hardening → 分支 CI #1095 绿，未合 main 等拍板） | 用户要求审计「设置→人格」模块并修复/优化，6 个原发现 + 补测时又挖出 2 个真 bug，全部修完。 |
| 08-26 22:08 | 长会话「输入卡顿」修复1 施工完成（2026-08-26 晚，分支 CI 绿） | 用户指派「长会话输入卡顿修复1」——把 ChatScreen 顶层 inputText 的 collectAsState() 订阅下沉到独立 composer 叶子，消除每次键入让整个 6277 行 ChatScreen… |
| 08-26 22:47 | 技能+权限模块审计修复完成（分支 CI 绿，未合 main 等拍板） | 用户要求审计「设置→技能」「设置→权限」两模块并优化，全部做完（branch fix/skills-permissions-polish @ 4f51c8b，分支 CI run #1112 success，head 核实… |
| 08-27 10:34 | 模块审计批 · 修复任务 5 完成（service+notification 清理，分支 CI 绿未合 main） | 分支 fix/service-notify-cleanup，commit 3a147edae5a571ddf07e897baa15ffe1d39fecfe，基线 main 4f51c8b0，分支 CI run #1115… |
| 08-27 18:23 | 会话任务 H 第四轮方案 B 施工完成（2026-08-27，commit a67e7fe，CI run #1127 success） | 背景：历史对话「打开定位到底部」第四轮（55b85b1，sessionLoaded 门控）用户真机反馈「先到底部又被拽走」。本会话核实出确定性缺陷：sessionLoaded 在 loadSession finally … |
| 09-02 20:24 | FE-5 route C ③ 完成待 CI + 第四批交接（2026-09-02 晚） | route C ③（AgentLoopEngine 主体搬迁）已完成编码，commit be7d3a5，分支 refactor/fe5-route-c-agentloop-engine2，CI run 336294072… |
| 09-02 20:53 | FE-5 route C ③ CI 红修复 + 沙箱重建（2026-09-02 晚） | CI run 33629407247 失败原因（交接文档预言的「引擎没过真实 Android 编译链」）：全是编译错误—— |
| 09-02 23:17 | ↳ FE-5 第四/五批合并拆分（2026-09-02 晚，commit 9a3949f） | 用户拍板：后面几批合并一起拆，拆完做系统性 bug 扫描（llm-bug-audit），不必逐批保真。先彻底解决「拆」再扫 bug。 |
| 09-04 21:29 | thinking-rules-port 分支待合并（2026-09-04，CI f7865b2b run 33873927997 success） |  |
| 09-04 21:35 | thinking-rules-port 分支待合并（2026-09-04，CI f7865b2b run 33873927997 success） | 用户开 bug-audit 会话处理该分支的 bug hunt；本会话收尾，未做 ff 合并 main。 |
| 09-06 14:02 | 小号同步 CI 闭环确认（2026-09-06 13:55） | 主号→小号 merge 350843f 的 CI success（run 34014575216，head_sha=350843f 核对一致）： |
| 09-06 17:30 | 小号同步 CI 遗留闭环确认（2026-09-06 晚） | 小号 ALT_USER/RikkaMinis main @ 9047a8ef（merge 6b95929）的 CI run 34020741695 success（head_sha=9047a8ef 核对一致）；主号 m… |
| 09-06 18:16 | 负载均衡请求级改造 + 停止按钮竞态修复全链路闭环（main @ cd32795，分支 CI run 340260822 | 负载均衡请求级改造 + 停止按钮竞态修复全链路闭环（main @ cd32795，分支 CI run 34026082245 head_sha 核对一致后 ff 合并，release CI 34026796848 in_… |
| 09-06 23:15 | 突然停根因确诊（finish_reason=network_error 伪正常结束）+ verification_stop 分支推送（2026-09-06 深夜） | 用户抓到第三次复现的完整现场（提前开了应用内日志，上传 /var/minis/attachments/uploads/minis-2026-09-06__3_.log）——前两次 ring buffer 被冲掉的教训后这… |
| 09-07 12:26 | Runtime Limits 面板分支推送（2026-09-07，分支 feat/runtime-limits-panel @ d908e90） | 用户需求三连：①64→128 预算是否硬编码（答：是，ChatAgentTraceObserver const）②抽出来变可调 ③"能调的都放进去"，以现值为默认；UI 要求：Settings 列表描述保持简短，详细说明… |
| 09-09 20:09 | Token 用量「总循环次数」不实时 — 根因与修复（2026-09-09，分支 fix/token-usage-live-loop-count @ cd360fd，CI 绿，未合并） | 用户症状：会话 Token 用量 → Agent Loop → 总循环次数，运行期间一直显示 1，暂停/结束才一次性跳到真实数。上一轮（ec1e5d8）改的 1s 轮询没治好。 |
| 09-10 03:51 | 语法门 grep 大小写坑 + 括号配平检查（2026-09-10） | 语法门 grep 大小写坑 + 括号配平检查（2026-09-10） |
| 09-10 08:09 | 工具显示两处修复（分支 fix/shell-stream-partial-line @ fb14213b，CI #1445 绿，用户拍板暂不合并） | 用户两个问题 → 两个根因（都在显示层，模型侧数据是干净的） |
| 09-10 08:11 | 压缩提示 UX 修复（分支 fix/compact-divider-ux @ 3da98de1，已推送未合并） | 用户报的两个问题：①「已压缩 N 条消息」细线+10sp 灰字体验差 ②提示出现后回答继续长在提示上方（直觉应在下方）。 |
| 09-10 08:26 | ↳ 两个分支合并前检查 + 合并 main（2026-09-10，main @ 31c8abb9） | 指令：检查 fix/shell-stream-partial-line + fix/compact-divider-ux 是否引入 bug，无则合并。 |
| 09-11 20:42 | key-roulette release CI 终态确认（2026-09-11 晚） | main @ 3a988d5f 的 release CI run 34556828343 已确认 success（bridge /status/main 实测）。至此 fix/key-roulette-refresh 全… |
| 09-11 21:28 | fix/singleline-paste-newline — 单行输入框粘贴换行折叠（2026-09-11 晚，CI 绿，待拍板合并） | 问题（用户报告）：向输入框粘贴多行文本（如 newapi 的 headers、多行 key），只显示第一行，其余被裁；删掉可见字符后字段看起来空了、实际还残留不可见 \n。根因：Compose singleLine=tr… |
| 09-11 21:30 | ↳ singleline-paste-newline 合并收尾（main @ f4b6c4a） | 合并：分支 CI #1464 success（head f4b6c4a 三方一致）→ 用户拍板 → refspec 直推 ff（3a988d5..f4b6c4a，无 force）→ ls-remote 复核 main=f… |
| 09-11 21:32 | ↳ singleline-paste-newline 真机验证通过（2026-09-11 晚） | 用户实测分支构建（= main @ f4b6c4a 同一 commit，等价）：四项验收点全过，无问题——①多行粘贴到 API Key/UA/URL 字段整段单行可见 ②清空后字段确真空 ③多 key 轮换正常 ④MCP… |
| 09-12 00:39 | 三修复打包分支 fix/ttfb-thinktag-composer 推送+CI（2026-09-11 深夜） | 用户拍板：TTFB 直接调默认值（30s→90s）；think 标签+输入框"照常修，直接打包一起"（一分支三 commit） |
| 09-13 12:47 | 打开会话「差一段」修复分支已推送（2026-09-13） | 分支 fix/open-catchup-guard @ 32571f8（基于 main @ 15b3f447）：3 文件 +210。 |
| 09-13 13:03 | open-catchup 修复 CI 绿（2026-09-13） | fix/open-catchup-guard @ 32571f8 三源一致（本地 HEAD = 远端分支 = run head_sha）→ CI #1478 success（run 34738708107，10m43s）… |
| 09-13 20:46 | 内存加固三件套实现完成（分支 fix/memory-hardening-rss-stall，3 commits，CI #1498 success） | commits：a641e27 KaTeX 位图上限（H1）→ 45ce0c8 app 自身压力治理器（H2）→ c508896 挂死处置（用户追加要求）。沙箱 JVM 56/56 绿、仓库门禁 6/6、CI #1498… |
| 09-13 22:10 | ★ 2026-09-13 H3 完成：压力门口径换 RssAnon（①）+ 崩溃态取证（②），两分支 CI 全绿待合并 | 用户拍板："按你说的来"（先①口径，再②取证）；并自己提出"②是不是另开分支更好" → 采纳，且② 基于 ① 的 tip（而非 main），这样 ① ff 合并后 ② 仍是直系后代，照样 ff，且避开 MemorySpi… |
| 09-13 22:13 | ★ 2026-09-13 H3 收尾：两分支已 ff 合并 main = `d3873c0`（release CI 用户拍板不等） | 合并顺序按 ② 基于 ① 的设计执行，两步均 ff、零 rebase：c508896..f5253be（① 口径）→ f5253be..d3873c0（② 崩溃态取证）；远端分支 fix/memory-gate-anon… |
| 09-13 23:23 | ★ 2026-09-13 完善度扫描收尾：打包修复分支 CI 绿，待真机验证 + 拍板合并 | 分支 fix/completeness-followups-0913 @ a330ce2f（基于 main d3873c0e，7 文件 +143/−4），CI run 34764642145 success（步骤 3 门… |
| 09-14 00:07 | 2026-09-13 完善度修复全链路闭环完成（release CI 已确认 success） | 用户说"触发了就不用等了"，但查证发现 release CI run 1505 已经是 completed success（head a330ce2f）——全链路闭环： |
| 09-14 10:29 | ★★ 09-14 重大发现 + 修复：census 接错渲染器（7 分支全死）→ fix/liveness-followups-0914 @ 58620206（CI run 1510 success） | 修复内容（5 文件 +93/−44，分支 fix/liveness-followups-0914）： |
| 09-14 10:30 | 09-14 census 修复已合并 main = 58620206（release CI run 1511 已触发，用户拍板不等） | 合并动作：ff 直推 2dc6e0d4..58620206（merge-base 验证 FF-OK，无 force）→ ls-remote 复核 main = 58620206 → 远端分支 fix/liveness-f… |
| 09-14 11:33 | 09-14 观察1处置完成：appendMessage marker 迁移补全 @ fa2ec1e（分支 diag/log-observability-0914，CI run 1513 success） | 改动（ChatRepository.kt 8 行）：7 处 android.util.Log.i → AppLogger.info（insertMessage enter/done、constraint-retry、in… |
| 09-14 11:35 | 09-14 日志治理分支已合并 main = fa2ec1e（release CI run 1514 已触发，用户拍板不等） | 合并动作：FF 前置验证（merge-base = origin/main = 58620206 = 分支直接祖先）→ refspec 直推 5862020..fa2ec1e HEAD:main（无 force）→ ls… |
| 09-14 13:40 | 会话 ID 别名裂缝修复完成（2026-09-14，分支 fix/session-id-alias-diag @ 13624962，CI run 1515 success） | 背景：日志检验发现 F1（同一会话双 ID，跨窗口 rename 无桥 → 全链重建断裂）。用户"那就解决吧"→ 本对话框内完成。 |
| 09-14 13:44 | 09-14 会话 ID 别名裂缝修复已合并 main = 13624962（release CI run 1516 已触发，用户拍板不等） | 合并动作：ff 前置验证（merge-base = origin/main = 582de2ed = 分支直接祖先，单一提交）→ refspec 直推 582de2ed..13624962（无 force）→ ls-re… |
| 09-14 16:16 | 09-14 晚：截断工具调用守卫落地（fix/truncated-tool-call-guard @ fbc50cdd，CI run 1517）+ Eta 调研两条修正 | 改动（3 文件 +217 行，零删除，未合并待拍板）：①新建 ui/chat/TruncatedToolCallPolicy.kt（74 行纯函数：7 个截断 finish reason 别名 length/max_ou… |
| 09-14 16:29 | 09-14 截断工具调用守卫已合并 main = fbc50cdd（release CI run 1518 已触发） | 合并动作：CI 1517 success（job build）→ FF 前置验证（merge-base = origin/main = 13624962 = 分支直接祖先）→ refspec 直推 13624962..f… |
| 09-14 17:07 | 09-14 深夜：A3 工具参数校验升级完成（fix/preflight-schema-guards @ 27265bbf，CI 已派发） | 改动（4 文件 +276/−5，基于已合并的 main=fbc50cdd）：①ChatViewModel.preflightValidateToolCallImpl 新增枚举成员校验 + 标量/容器形状校验（AgentT… |
| 09-14 18:31 | 09-14 夜：offload 载荷 bug 定位并修复（main = 79d57d69，release CI 34833605568） | ★ 真 bug（静默数据丢失，非工具环境问题）：ChatContextWindow.kt 的 offloadContextIfNeeded 把 file_write 的 content 入参列为 offload 候选，并… |
| 09-14 20:05 | 09-14 夜：运行日志审计 → 6 项修复已合并 main = ad71828c（release CI #1526 已触发，用户拍板不等） | 前提（用户提醒）：日志（13:48–18:51）属旧包（1.0.0+1516 及更早），设备现装 1.0.0+1524 已含 79d57d69 修复 —— 我用 APK dex grep（grep -ac 'stub, … |
| 09-14 21:31 | 09-14 夜：U7 撤回（带触发条件）+ T4 conversation_history 完成（分支 CI 已绿） | U7 胶囊——撤回，不是"待做"（依据代码而非偏好）： |
| 09-14 21:33 | 09-14 收尾：T4 已合并 main = ae83b79（release CI 34849895434，用户拍板不等） | 两个分支全收口：分支 1（T1 digest 校验 / T2 故障 golden / T3 注释）→ CI 34845264262 success → FF 合并 main 954983e，其 release CI 34… |
| 09-15 11:29 | 09-15 深夜：日志修复 CI 闭环 + 热路径同类排查（ProviderRepository 实锤）+ 装错包对账 | 日志分支闭环：fix/applogger-async-writer @ 28f051b（rebase 到含 CI 修复的 main 95df092）→ CI run 1536 success（SDK 修复生效）→ APK… |
| 09-15 12:33 | 09-15 中午：backlog 打包修复分支 CI 绿，等用户装 +1540 拍板 | 分支 fix/backlog-small-batch-0915 @ 16e619c6（3 commits）：2c8403af（§7 翻页 cursor 改 sort_order + §9 redactWithRemind… |
| 09-15 13:00 | 09-15 中午：backlog 打包分支闭环 —— main = 16e619c6，release CI 1541 success | 真机验证（三源）：设备 1.0.0+1540 = CI 1540 = APK manifest；①设置页 Thinking Rules 用户已验；②conversation_history 我亲自调用验证（小页读取 + … |
| 09-15 13:29 | 09-15 下午：HangDetector 前台门控修复闭环（分支 CI 1542 绿，待真机验证拍板） | 分支 fix/hangdetector-foreground-gate @ 183a01ed（1 文件 +136/−20）：新增 BackgroundFreezePolicy 纯函数决策表（IDLE/HANG/BG_FR… |
| 09-15 13:41 | 09-15 下午：HangDetector 前台门控真机验证通过，FF 合并 main = 183a01ed | 真机验证（三源）：我按 HOME 切后台 5 分钟（13:34-13:39），进程整冻（零日志零 tick）→ 解冻后心跳 454ms 落地 → 无 hang detected、无 breaker TRIPPED、sta… |
| 09-16 13:12 | 09-16 午后：audit-0916 批次（§19/§20/§21）修完推送分支，CI 跑中（交接已写） | 分支 fix/audit0916-timeout-retry-and-browser @ 4aa2e76（11 文件 +340/−46，基 main 1863e4d），CI run 35057861211（最后看 in_… |
| 09-16 14:05 | 09-16 傍晚：热榜吸收攒进 backlog §22（用户拍板"攒着"）+ CI flake 处置 | 用户拍板：该吸收的不实施，攒着。已写进 /var/minis/shared/backlog.md §22（33KB，file_write append 成功）： |
| 09-16 14:21 | 09-16 傍晚：§20b 导航结果修复收口 — main = e940148（release CI 35063273119，用户拍板不等） | 分支 fix/audit0916b-nav-outcome（2 commit）→ cherry-pick 到新 main 735eadb 之上（另一会话推了 skills commit，零重叠）→ push tmp-me… |
| 09-16 18:17 | 09-16 晚：23c-2 渲染期 resolve + 灰链收口 — main = 3df7977e（release CI 35084162878，用户拍板不等） | 改动：ChatLinkRenderCache（LRU 256，键 (url,sessionId)，resolveFn 注入 → JVM 可测）+ LocalMarkdownLinkRenderResolver Compo… |
| 09-16 19:04 | 09-16 晚：audit-0916d 收口 —— main = 551ed6b9（两处 CI 门缺陷修复） | 审计发现（今天 15 提交逐个走查，运行时改动零真 bug，缺陷全在新 CI 门自己身上）： |
| 09-17 10:23 | 09-17：审计存量缺陷处理（215 条）批次 1-3 已推 CI | 09-17：审计存量缺陷处理（215 条）批次 1-3 已推 CI |
| 09-17 16:09 | 09-17 下午续 2：b9/b10 完成，两轮 CI 失败教训 | b9（ui/chat 9 条：6 修 3 驳） 分支 fix/audit0917-chatbatch： |
| 09-17 16:19 | 09-17 下午续 3：三轮 CI 失败与「本地门盲区」的根治（重要方法论） | 三轮 CI 失败全在本地可拦，全因"把真错当噪声"： |
| 09-17 16:43 | 09-17 晚：审计 58 条全部收口 — main = 8fbb9d2e（release CI 同 SHA） | 收口链路：分支 fix/audit0917-chatbatch（6 提交，5 轮 CI：3 编译错 @ nav-currentStateFlow / 坏删 context / toolTitle 作用域 + 1 单测契约… |
| 09-18 00:27 | 09-17 深夜修复：冷启动恢复覆盖 Launch Session 设置（main = bd55749b，#1637 绿 / #1638 release） | 改动（分支 fix/coldstart-restore-gate，3 文件 +137−3）： |
| 09-18 10:14 | 09-18：两分支盯 CI + 审查 + 合并收口 — main = ec52ba58 | 用户点名盯的两个编译分支：fix/indented-bullet-continuation（34d19003）与 feat/skill-md-file-render（6dd662e5），均基 ab1a3203。 |
| 09-18 10:55 | 09-18：钉住 CI runner ubuntu-24.04 — main = 6f2cef22 | 用户报告 GitHub Actions 提示：ubuntu-latest 将于 2026-10-19 迁移至 Ubuntu 26（runner-images#14748），问是否需要解决。 |
| 09-18 15:20 | 09-18 晚：日志全量覆盖改造收口 → main = cdd2817（CI #1651 绿 → FF main → release #35318635393 自动触发，用户惯例「触发后不用等」） | 用户命题：日志是应用的基础设施，应能全量反映运行 → 先量化缺口，再按缺口逐条修。 |
| 09-18 16:17 | 09-18 晚：双修复开工收口 → main = 1fb74fd0（两个分支各自 CI 绿 → FF → 远端只剩 main） | 修复 A（a3183aa1）：ack 竞速 —— ModelExecutionRunDir.workerDrained() 共享判据（只认 worker 自有证据：terminal，或 result+beat silen… |
| 09-20 05:35 | 09-20：修复批次 FIX-2-thinking-voice 完成（6 条，分支已推 + CI 绿） | 批次：FIX-2-thinking-voice · 分支 fix/thinking-voice-layer · commit 0012517 · 基线 afa404b |
| 09-20 08:37 | ↳ 09-20：修复批 FIX-3-chat-state 完成（chat 状态机与并发 · 7 条 + 跨批补丁 4 处） | 分支 fix/chat-state-machine · 基线 afa404b（父提交已核）· commit 3b3c474 + 9dfe54f · CI #1670 → #1682 两次均 success · diff … |
| 09-20 11:01 | 09-20：EXTRA_DAYS 容器类型修复完成 → main = `d11a4c1b`（CI #1691 绿 → FF → 远端仅 main） | 修复内容（1 commit / 4 文件 / +237 −35）： |
| 09-20 17:10 | 09-20 晚：隐藏设置页 Agent Runtime 里的终端行 → 分支 `chore/hide-settings-terminal-row`（CI 绿，**按用户要求不合并**） | 用户判断：设置 → Agent Runtime 里的「终端」行冗余——真要用，外观 → 聊天菜单里已经能把它放到右上角菜单/抽屉底栏。去掉。 |
| 09-20 17:35 | 09-20 晚：IME 键盘遮挡修复完成 → 分支 `fix/ime-occlusion-hosts` @ `4d4333d3`（CI 绿，**按用户要求不合并**） | 用户指令：跑完不合并，等统一处理。远端现有三个未合并分支（同基 3ec26816）： |
| 09-20 17:57 | 09-20 晚：云端三分支核查 → 全部合并进 main = `27eced19`（CI run 35503040884 success） | 用户指令：检查云端三个分支有没有引入 bug，没有就合并。 |
| 09-20 18:02 | ↳ 09-20 晚：三分支合并的真机验证 —— 用户确认全部通过 | 用户反馈：「修改，验证已完成，都没有问题。」 |
| 09-20 19:02 | 09-20 深夜：流式看门狗饿死 bug 修复完成 → 分支 `fix/provider-stream-flowon` @ `13ea691c`（CI run 35506103286 success，**未合并**，用户要求） | 用户指令：「跑完不要合并」。已推送分支，CI 绿，分支保留。 |
| 09-20 19:27 | 09-20 深夜：F-169「错误快照缺触发行」修复完成 → 分支 `fix/error-snapshot-missing-trigger` @ `f6a50a2b`（CI 35507084738 绿，**按用户指令不合并**） | 任务书数字被我用严格判据修正（诊断方向不变，数字更准）：任务书写「14/41 快照不含 [ERROR]」，实际按严格判据（是否含触发它的那一条）是 17/41（41%）。差异 = 3 个快照「含 ERROR 但不是触发行… |
| 09-20 19:31 | 09-20 夜：上下文超限后 agent loop 空重试死循环 → 修复分支 `fix/context-exhausted-loop` @ `55703df9`（CI 绿，**未合并**，用户要求等统一处理） | 任务书：/var/minis/shared/hang-0920/session-task-ctxloop.md（要求：先复核四点 → 三方案分析 → 实施推荐 → JVM 复现 + 反向对照 + scan + CI → … |
| 09-20 20:20 | 09-20 夜：F-177 修复完成（分支 `fix/cancellation-accounting` @ `c5aedaa6`，CI 35509454695 success，**未合并**） | 任务书：/var/minis/shared/hang-0920/session-task-f177.md · 报告：/var/minis/shared/hang-0920/F177-RESULT.md · 实验：f177… |
| 09-20 20:22 | 09-20 夜：压缩 anchor 死区修复完成 → 分支 `fix/compact-budget-anchor-dead-zone` @ `96e8d6cf`（CI 绿，**按用户要求不合并**） | 报告：/var/minis/shared/hang-0920/anchor-exp/（REVIEW.md 复核 + FIX.md 修复 + run_all.sh PASS=13）。 |
| 09-20 21:09 | 09-20 深夜：7 个云端分支审查 → 合并 main = `a01d36a0`（CI 35511791254 绿） | 任务：用户要求检查云端 7 个分支有无 bug → 有问题就修，没有就合并。 |
| 09-21 09:57 | 09-21：F-134 修复完成 → 分支 `fix/config-prefs-listener-gc` @ `720e77ce`（CI 绿，**按用户要求不合并**） | 任务：用户「开个分支修了，跑完之后不要合并」。 |
| 09-21 11:02 | 09-21：记忆文件卡顿修复 → 分支 `fix/memory-file-jank` @ `7e89b81`（CI 绿，**按用户要求不合并**） | 用户指令：「修吧，就是分支上跑完了，不要合并。」 |
| 09-21 13:11 | ↳ 09-21：云端两分支审查 → 发现 2 真问题 → 修复 → 合并 main = `a081080c` | 任务链：用户「检查云端两个分支有没有 bug」→ 发现 2 处 → 「修吧」→ 「跑完没问题就合并」。 |
| 09-21 13:25 | ↳ 09-21 补：两分支修复经**真机验证通过** | 用户确认：fix/memory-file-jank 的空行修复 + fix/config-prefs-listener-gc 的闸门修复，真机验证没有问题。 |
| 09-21 13:34 | 09-21：evidence-discipline 升级 v1.3.0（新增「自证循环」章节）+ 修掉 check_evals.py 的假绿 | 用户指令：「加进去」—— 把本轮「修完必须回真实仓库验证」的教训并入 evidence-discipline。 |
| 09-21 16:34 | 09-21 会话 A（fix-dispatch-0921）：#341 WebView 渲染进程死亡修复完成 → 分支 `fix/webview-render-process-gone` @ `855cc26d`（CI 绿，**按指令不合并**） | 任务书：/var/minis/shared/fix-dispatch-0921/task-A-webview-341.md（派发目录里还有 task-B-p1-trio.md，是别的会话的活，未动）。 |
| 09-21 17:03 | 09-21 晚：零 chunk 取消修复 — 中档完成，分支 `fix/zero-chunk-cancel-and-wait-state` @ `eabff9c`（CI 35579684513 全绿） | 用户指令：做中档（①取消在所有阶段生效 + ③界面区分"思考中/等网络"）；并提醒 main 已推进（实际未推进，文档推成了分支 docs/dev-history-0921 @ 1114170a，只改 docs/dev-… |
| 09-21 17:10 | 09-21 续：fixB 三分支（#377/#116/#93）CI 四轮收口 —— ★ KDoc 方括号陷阱 + golden snapshot 抓到我修过头 | 分支 fix/provider-effort-notification-picker @ 154ac5c9，CI run 35580684921 success（head_sha 逐字符核对，Publish to Rel… |
| 09-21 18:57 | ↳ 09-21 晚：三云端分支审查 → 修掉一个真回归 → 全部合并进 main（`d09b1915`） | 用户指令：「检查核实云端上的三个分支，看看他们是否修改出了问题，以及有没有真正意义上的解决问题，如果有问题就修一下，如果没有问题就把他们合并了。」 |
| 09-21 19:38 | 09-21 晚：S1 小三条完成（派发线 fix/s1-small-trio @ `d25b45c1`，CI 35593673164 绿，**未合并**） | 任务书：/var/minis/shared/dispatch-0921/tasks/S1-small-trio.md（五线并发派发中的 S1）。交付边界 = 分支 CI 绿，不合并。 |
| 09-21 19:43 | 09-21 晚：S5 派发任务（resume-guard 核实）→ 结论 C，零逻辑改动，分支 CI 绿 | 任务：核实 backlog §27c(2)「生产 canResume 不看工具结果是否已知 ⇒ 设计意图与实现不一致」是否成立。 |
| 09-21 20:44 | 09-21 晚：S3 会话（数据/CLI + UI）交付 —— 分支 `fix/s3-data-cli-ui` @ `16d7d5ed`，CI 35599198169 全绿 | 任务：dispatch-0921 派发的 S3（#200 messages 日期过滤 + #272 选择器排序）。终点 = 分支 CI 绿，不合并（遵守交付边界）。 |
| 09-21 21:15 | 09-21 深夜：S4 会话（沙箱两条）完成 —— 分支 `fix/s4-sandbox` @ `6375eb36`，CI 35602522458 全绿，**按任务书不合并** | 任务书：/var/minis/shared/dispatch-0921/tasks/S4-sandbox.md（交付边界=分支 CI 绿，禁止合并/开 PR/删分支）。报告全文：/var/minis/shared/s4-… |
| 09-21 22:01 | 09-21 收口：dispatch-0921 五分支审查 + 合并 main = `671f928c`（CI 35607838698 全绿，release 资产已刷新） | 用户指令：检查核验云端 5 个分支 → 有问题就改，没问题就合并。 |
| 09-21 22:06 | 09-21 用户拍板：分支 CI 绿 = 确定性停止节点（流程纪律改写） | 用户原话：改完 → 云端 CI 绿 → 这就是结束；分支 CI 绿作为一个确定性的停止节点。合并前必须先「检查、验证、核实」，走完这套才能合并。 |
| 09-22 05:39 | 09-22：任务 2→1→3 完成（日志法深挖 → CI 门 → 修 P0），分支 `fix/family-scan-ci-gates` @ `82a40f63`，CI 35657163489 绿 | 任务 2 · 日志法深挖（三段链路，推进到从未查过的「判定段」） |
| 09-22 06:06 | 09-22：合并核查 `fix/family-scan-ci-gates` → main = `82a40f63`（ff 合并，远端已推） | 三项核查（全部有证据） |
| 09-22 11:02 | 09-22：思考强度 B1–B4 修复完成 → 分支 `fix/thinking-level-ui-truth` @ `ae5517cc`（CI 绿，**按停止节点停在分支上**） | CI：run 35680380432 success · head_sha ae5517cc… 与本地 HEAD 逐字符一致 · 23 步全 success · Publish to Releases skipped（m… |

## 14. 开发档案 / 记忆 / 工具链

**跨度** 2026-08-03 ～ 2026-09-21 · **92 条** · **状态** 常态维护（skill: dev-history-sync，每次 dev 会话收尾同步）

**叙事**：08-06 经验记忆引擎概念 → 08-08 三平台技能架构固化 → 08-14 档案首次从记忆重建 → 08-16/24/09-04/05/07/08/10/12/13/14 反复重建（**每次重建都在修解析器或脱敏规则**）→ 09-06 知识图谱 + MCP memory → 09-14 日志「可消费性」诊断 → 09-15 sanitize 头部刷新固化。**档案本身是被反复施工的产物，不是一次性导出。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 21:47 | OpenMinis fork — 测试 backlog 清理 + 动态版本 + 上游同步（2026-08-03 进行中） | 分支 feat/test-backlog-version-sync（4 个提交 ca4a7e2/791e543/2b6ec3c/…），CI run 30819582646 验证中。 |
| 08-04 22:20 | code-workbench-tools 技能首次测试（2026-08-04） | 最新加载的技能（/var/minis/skills/code-workbench-tools，SKILL.md 22:09 更新）做了完整功能测试。 |
| 08-04 22:26 | code-workbench-tools 技能完善（2026-08-04） | 按用户要求修复了技能的 bug。改动 /var/minis/skills/code-workbench-tools/ 下 setup.sh + SKILL.md。 |
| 08-05 15:01 | CF 小号技能化 + token 安全迁移完成（2026-08-05） | Cloudflare 小号 token 已从明文迁移到环境变量 |
| 08-06 10:55 | RikkaMinis 经验记忆系统审查结论（87f69eb） | 系统审查发现：①EpisodeMemoryStore 共享 JSONL 无同步，而应用允许 5 个会话并发，read-modify-write 会丢写，行号反馈跨会话失效，clear 也可被在途写回；②Hook A/B … |
| 08-06 22:43 | code-workbench-tools SKILL 升级到 v1.2.0 — 加"沙箱环境约束"一节（2026-08-06） | 用户反复看到模型 agent 在 RikkaMinis 沙箱里跑 grep -rn --include='.kt' 报 grep: unrecognized option: include=.kt（busybox gre… |
| 08-08 16:01 | 三平台技能架构（2026-08-08） | 三个平台各司其职，技能统一备份在 GitHub： |
| 08-08 17:06 | 三平台基础设施全覆盖 — 盘点与固化（2026-08-08） | 用户批评：GitHub、CF、HF 三个平台都配了基础设施，一个出问题其他两个必然也有同样问题（没先查 skill 裸调 API）。 |
| 08-08 18:55 | 合并 feat/bundled-platform-skills → main，修复"内置集成显示需配置"（2026-08-08） | 背景：用户问"内置集成为什么显示需配置"。排查发现： |
| 08-08 19:11 | ↳ 集成状态诊断日志 — feat/integration-status-diagnostics（2026-08-08） | 背景：用户反映"内置集成显示需配置"，但新会话显示"完整 tier 2"——同一个运行时代码，结果不同。经排查（源码里只有动态 buildIntegrationStatus，无静态模板；日志干净无 keystore/读取… |
| 08-08 19:19 | ↳ ✅ feat/integration-status-diagnostics 已合并 main（2026-08-08） | run #279 success → ff 合并 main（48fd1aa→13f0ee7）→ 推送 main（gh_sync.sh push-main）→ 分支本地+远端已删 |
| 08-08 20:36 | 三平台集成文档化 — README 补全（2026-08-08） | 用户指出"三大平台纳入"是一次重大升级，不是小修小补，README 必须写清楚，否则以后自己都会忘、别人也看不懂。 |
| 08-08 21:28 | 教训：平台技能判定不能用 importSource | 平台集成卡片的筛选条件不能用 importSource == BUNDLED——老用户的技能可能是通过 SESSION/FILE 等途径安装的，installBundledSkills() 在版本号已 ≥ 捆绑版时会 s… |
| 08-08 22:46 | RikkaMinis 新图标设计语义 | 用户希望手机端智能体应用图标表达：控制论在应然与实然间搭桥、系统机制相互作用、GEB 怪圈，以及从上游 fork 后“边使用边开发、边开发边使用”的本地开发→云端编译→循环迭代。视觉压缩原则：不堆叠概念，不用机器人/脑/… |
| 08-13 04:08 | 任务1：SkillRepository 原子性修复 ✅ 完成 | 分支：fix/skill-repo-atomic → main (3488e70) |
| 08-14 12:43 | rikkaminis-dev-history.md 从记忆重建（2026-08-14 12:4x） | 用户发现笔记挂载目录 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md（应用修改的日志合并导出）"被改出问题"（原文：被应用改动出了毛病；检查发现 2 处时间戳乱序，用户决定不走修复… |
| 08-14 22:21 | rikkaminis-dev-history.md 二次重建（2026-08-14 22:2x） | 用户再次要求更新挂载目录的 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md。上次 12:4x 重建的脚本 /var/minis/workspace/rebuild_dev_hist… |
| 08-15 05:32 | T4-A 派发指令 — 故障注入 Harness | 你负责 RikkaMinis 平衡点施工 T4-A — 故障注入 Harness（fakes + 场景协议 + 独立 runner）。 |
| 08-15 05:54 | T4-B 派发指令 — 把 Harness 挂接真实 Agent Run | 你负责 RikkaMinis 平衡点施工 T4-B — 把已有 Harness 挂接真实 Agent Run adapter。 |
| 08-15 05:54 | T8 派发指令 — Interrupted / OutcomeUnknown 恢复语义 | 你负责 RikkaMinis 平衡点施工 T8 — Interrupted / OutcomeUnknown 恢复语义。 |
| 08-15 09:02 | T4-A 故障注入 Harness 完成：已合并 main 883b3c6（2026-08-15） | 交付：分支 stability/T4-fault-harness（2 commits：7edfba4 + 883b3c6），15 文件 +2198 行，全部在 src/android/app/src/test/java/… |
| 08-15 09:20 | T8 Interrupted / OutcomeUnknown 恢复语义 — 纯 JVM 核心完成（CI 绿，分支 stability/T8-interrupted-recovery） | commit：87f11ee（2 files +638/-0） |
| 08-16 09:44 | rikkaminis-dev-history.md 三次重建 + 敏感内容脱敏（2026-08-16 上午） | 用户要求更新挂载目录 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md（此前覆盖到 08-15 19:01），并提醒敏感内容处理。 |
| 08-16 19:21 | rikkaminis-dev-history.md 四次重建（补 08-16 下午/晚条目）+ 解析器 bug 修复（2026-08-16 晚） | 用户要求把今天下午/晚的新条目也补进 笔记/RikkaMinis开发档案/rikkaminis-dev-history.md（BiliRoamingX 安全分析/编译失败、rikkahub 流式剖析、滚动跳动施工方案定案… |
| 08-20 11:21 | T1 trim 语义修正 CI 绿 + 拓扑澄清（2026-08-20） | 分支 fix/trim-memory-semantics（1954bac）CI run 32325951490 success。 |
| 08-20 11:33 | ↳ T1 合并 main 闭环（2026-08-20） | 方案 B（用户拍板：打点 + trim 一次 ff 一起进）。 |
| 08-22 02:14 | 应用重置后技能/工具全面检查修复（2026-08-22） | 用户重置应用后要求检查并修复各项技能。检查结论与修复： |
| 08-24 16:09 | dev-history 档案已更新（2026-08-24 16:07） | 按 dev-history-sync 技能全流程执行：rebuild（605 条、22 天，含 08-24 的 17 条新条目）→ sanitize（59 处脱敏，mostly CF_ACCOUNT_ID/UUID/域名… |
| 08-24 17:26 | 提炼两个新技能（2026-08-24） | 把散落在 daily log 的高频踩坑提炼成两个独立技能： |
| 08-26 14:33 | 技能脚本全面体检 + 修复（2026-08-26 下午） | 用户要求检查所有技能里的脚本，修 bug + 优化。共扫 9 个脚本文件（gh_ci_wait/gh_sync/gh_fullright/minis_auto_log/semantic_memory/rebuild/sa… |
| 08-26 17:46 | MCP 模块审计修复完成（2026-08-26） | 用户要求检查设置里 MCP 模块并修 bug。审计了 MCPRepository/MCPIntegrationsScreen/SessionMcpsSheet/OAuth 四件套 + CLI（minis-mcp-cli）… |
| 08-26 22:47 | 技能+权限模块审计修复完成（分支 CI 绿，未合 main 等拍板） | 用户要求审计「设置→技能」「设置→权限」两模块并优化，全部做完（branch fix/skills-permissions-polish @ 4f51c8b，分支 CI run #1112 success，head 核实… |
| 08-31 18:02 | 语义索引增量重建 + facts 种子回填（2026-08-31 下午） | 背景：用户问「现在能做什么」，定位到瓶颈是 facts 生产量（上线 24h 只有 1 条）。做了两件事 + 一次事故复盘。 |
| 09-04 22:28 | dev-history 文档同步到 09-04（2026-09-04，main @ d1b6af90） | 用户要求更新仓库 docs/dev-history/ 开发日志。流程：rebuild_dev_history.py → sanitize_dev_history.py → 验证 → 同步挂载副本 → 分支提交合并。 |
| 09-05 15:49 | dev-history 文档同步到 09-05（2026-09-05，main @ 12bfbbaf） | 用户要求更新仓库 + 本地两份开发档案。流程照旧：rebuild_dev_history.py → sanitize_dev_history.py → 验证 → 同步挂载副本 → 分支提交合并。 |
| 09-05 16:46 | 用户报"最近经常遇到沙盒重置、kotlinc 等工具不见了"。调查结论（三源验证：文件时间戳 + 日志 + 语义记忆）： | 用户报"最近经常遇到沙盒重置、kotlinc 等工具不见了"。调查结论（三源验证：文件时间戳 + 日志 + 语义记忆）： |
| 09-06 09:51 | MCP 记忆增强盘点结论 + server-memory 配置完成（2026-09-06） | 用户问三个主流记忆 MCP 哪个能用上。盘点结论： |
| 09-06 09:54 | 知识图谱种子数据灌入完成（2026-09-06，续） | 10 实体 + 11 关系已写入 MCP 知识图谱：RikkaMinis/OpenMinis/OWNER/ALT_USER/USER/rikka-ci-bridge/semantic_memory/knowledge_g… |
| 09-06 10:09 | 知识图谱自动同步器完成（sync_kg.py）—— 新会话开场请增量跑一次 | 脚本：/var/minis/shared/knowledge-graph-backup/sync_kg.py |
| 09-06 12:09 | 知识图谱全量灌入完成 + LLM 切换到 tokenrhythm（2026-09-06 中午） | 图谱现在：3627 实体 / 4739 关系（从今天早上的 185/153 爆发式增长，覆盖 2026-07-31 ~ 09-06 全部 798 条日志），备份 20318 条记录。 |
| 09-06 12:15 | 知识图谱备份安全性核查（2026-09-06）—— 图谱数据不会被备份导出 | 用户问"删应用前做备份，图谱那部分会不会也被备份"。代码级核查结论： |
| 09-06 16:22 | 用户报"模型组负载均衡不生效，后台看不到其他模型被使用"。代码级核查（main @ 6b95929）：功能生效，但语义是 | 用户报"模型组负载均衡不生效，后台看不到其他模型被使用"。代码级核查（main @ 6b95929）：功能生效，但语义是会话级轮转不是请求级。GroupRouter.select 的 loadBalance 分支 = u… |
| 09-06 19:09 | Hermes Tier-1 harness 四改动合并 main（2026-09-06 晚，main @ d7ee353e） | 分支 feat/hermes-tier1-harness → 分支 CI 34028554543 success（bridge 核对）→ ff 合并 main → push 触发 release CI（未等）→ 本地+远… |
| 09-06 19:40 | 会话交接（2026-09-06 晚）→ 交接文档 /var/minis/shared/hermes-tier1-handoff.md | main @ 1150e05f（tier-1 harness 四改动 + EOF 断流静默停修复，双分支 CI 绿 ff 合并）。main release CI run 34030771852 结论未等——新会话开场先查… |
| 09-06 23:58 | dev-history 0906 同步 + 主号→小号全量同步（2026-09-06 深夜） | 主号 main @ 7d928fa3（docs(dev-history): sync archive to 2026-09-06, 794 entries / 35 days）： |
| 09-07 08:33 | ARCHITECTURE.md + dev-history 0907 同步（2026-09-07，main @ 3356a4cd） | 用户要求「做架构全貌文档 + 更新仓库文档」已闭环： |
| 09-07 10:52 | ↳ ChatScreen 拆分批次 1 合并 main（2026-09-07，main @ cf8d8a93） | 用户要求拆分四个超大文件（ChatScreen 6439 / ChatViewModel 3782 / StreamingMarkdownText 3754 / OpenAIProvider 3174），评估后按四批推进… |
| 09-07 11:23 | ↳ ChatScreen 拆分批次 2 合并 main（2026-09-07，main @ 16c20c08） | StreamingMarkdownText.kt 3755 → 3017 行，两个新文件：MarkdownStreamMerge.kt（256 行，流式合并纯函数，零 Compose 依赖）+ MarkdownBlock… |
| 09-07 12:03 | ↳ ChatScreen 四文件拆分批次 3+4 合并 main（2026-09-07，main @ 29a20a47） | 用户拍板批次 3+4 合一个分支做。全部四批拆分完成。 |
| 09-08 14:00 | 文档同步：dev-history + README 致谢更新（2026-09-08，main @ ec39da7） | 用户要求"文档与事实不相符的部分更新一下，readme 也是，致谢缺了一些，还有三平台的之类的"。已完成： |
| 09-09 18:50 | T7 browser+webapp+mcp 域审计完成（1 HIGH / 3 MEDIUM / 5 LOW） | 报告：/var/minis/shared/global-bug-audit-0909/reports/session-T7.md。扫描 29 文件/9647 行。 |
| 09-10 15:08 | dev-history 重建 882 条 + 小号同步机制实测通过（2026-09-10 下午收尾，主仓 main @ 68f5715） | ① 文档更新：rebuild_dev_history.py + sanitize → 882 条 / 39 天 / fences 32 even / outOrder 0 → 复制进主仓 docs/dev-history… |
| 09-12 02:24 | A1/A2 双双归档 + parseUsage bug 进 backlog（2026-09-12 收尾） | 用户拍板：A1 parseUsage bug「先攒着」（选 2=backlog），A2 一起调查后一起处理 |
| 09-12 10:10 | backlog.md 清理（2026-09-12） | 用户指出 backlog 里大多是已修复/已有对应的死项+编号混乱（两个##2）→ 重写：活项 4 节（sanitizeUtf16 / B 种子 2 / C 等窗口 3 / D 观察 2）+「已关闭存档」表格（7 行，含… |
| 09-12 12:01 | dev-history 文档更新闭环（2026-09-12，main @ 2d7cd82） | 档案：914 条 / 41 天（原 882/39，+32 条）/ 1,064,626 字符 / 17,116 行；fences 32 even、outOrder 0、头部占位符已填。挂载版（笔记/RikkaMinis开发… |
| 09-13 07:45 | dev-history 文档更新闭环（2026-09-13，main @ 15b3f447） | 档案：926 条 / 42 天（原 914/41，+12 条）/ 1,082,347 字符 / 17,342 行；fences 32 even、outOrder 0。今日新增 3 条（07:06 合并收尾、07:11 真… |
| 09-13 14:46 | 待办归档：catch-up 守卫盲区已记入 backlog（2026-09-13） | 用户拍板"攒着不修"。已写入 /var/minis/shared/backlog.md 新增 #5：open-catchup 守卫不认 InputHistorySheet 跳转（2.5s 窗口内跳历史会被拽回，极窄窗口）… |
| 09-13 15:56 | ★★ 交接：legacy 管线隔离 + backlog 两项（分支未推，本会话终端环境已损坏） | ⚠️ 本会话终端不可用：任何 shell_execute 都会触发 [System busy: process memory is critically high (982/1022/2283MB)]。成果全部在磁盘上，… |
| 09-14 10:22 | 2026-09-14 日志体系"可消费性"诊断（回答用户"日志是否足够详细"）—— 详细度过剩、组织度是缺口 | 用户动机：上轮 liveness audit 让用户意识到"AI 智能判断 + 日志材料 + 用户交互"闭环，问缺口是否为"日志产生不够详细"。实测后结论：详细度已过剩（12.9 万行/17MB 半天），缺的是组织度 +… |
| 09-14 10:26 | 日志治理收益评估（2026-09-14，回答用户"收益是什么样子"） | 实测底数（09-14 前 10.3h）：日志 17.7MB / 13.4 万行；ToolInputDelta 碎片 79,408 行 / 9.1MB（59% 行、51% 体积）；工具调用 479 次（平均 166 行碎片… |
| 09-14 11:35 | 09-14 日志治理分支已合并 main = fa2ec1e（release CI run 1514 已触发，用户拍板不等） | 合并动作：FF 前置验证（merge-base = origin/main = 58620206 = 分支直接祖先）→ refspec 直推 5862020..fa2ec1e HEAD:main（无 force）→ ls… |
| 09-14 12:56 | 日志治理首战检验完成（2026-09-14，+1514 包，任务窗口 12:14:41-12:47:35） | 背景：用户"刚刚执行了一个任务，当时开着日志"——即 12:13:51-12:46 的「文档刷新」任务（会话 19550430，94 条消息），要求分析日志验证治理成果（"也是检验的时候"）。 |
| 09-14 17:41 | 09-14 深夜：加固第二批全部收口（main = a383f8c7）+ B2 交接文档 | 已合并 main = a383f8c7（远端仅剩 main，三个分支均 FF 合并后删除）： |
| 09-14 20:20 | 09-14 深夜：交接文档已写（HANDOFF-absorb-2026-09-14） | 交接：/var/minis/shared/HANDOFF-absorb-2026-09-14.md（8KB）。两个分支：分支 1 feat/absorb-small-batch = U1 更新包 digest 校验 + … |
| 09-14 22:20 | 09-14 深夜：审计 3 个 LOW 已攒入 backlog §7-9（用户拍板"攒着"） | /var/minis/shared/backlog.md 新增三节（带位置/影响/可选处理/触发条件）： |
| 09-14 22:27 | 09-14 深夜：dev-history 档案同步到 09-14（1003 条）+ sanitize 脚本固化头部刷新 | 档案：953 → 1003 条 / 43 天 / 1,196,897 字符 / 18,828 行；fences 36 even、anchors=outOrder=0、脱敏 113 处 + INDEX 6 处、Remain… |
| 09-15 12:33 | 09-15 中午：backlog 打包修复分支 CI 绿，等用户装 +1540 拍板 | 分支 fix/backlog-small-batch-0915 @ 16e619c6（3 commits）：2c8403af（§7 翻页 cursor 改 sort_order + §9 redactWithRemind… |
| 09-15 13:00 | 09-15 中午：backlog 打包分支闭环 —— main = 16e619c6，release CI 1541 success | 真机验证（三源）：设备 1.0.0+1540 = CI 1540 = APK manifest；①设置页 Thinking Rules 用户已验；②conversation_history 我亲自调用验证（小页读取 + … |
| 09-15 13:51 | 09-15 下午：第二轮日志审计（新包 183a01ed 上线后）— 1 个 LOW 进 backlog §13 | 新包表现验证（三源）：13:41 后 W/HangDetector=0、TRIPPED=0、forcehome 全日志 0 → 前台门控 + launch verdict 修复在野生环境生效。ToolPreflight … |
| 09-15 18:04 | 09-15 晚：backlog §13/§14/§15 打包修复闭环（用户拍板合并）— main = 03ceed5f | 分支 fix/backlog-131415-0915 @ 03ceed5f（3 文件 +64/−8）→ 分支 CI 34953012745 success → 真机验证通过 → FF 合并 ad7c9e3..03ceed… |
| 09-15 18:45 | 09-15 晚：dev-history 档案重建加固 + 脚本脱敏能力审计 + 公开历史暴露面 | ★ 最重要发现（需用户决策）：docs/dev-history/ 在 public 仓库，且 raw.githubusercontent.com 无 token 可读（实测 200）。这意味着脱敏不是"内部整理"，是公开… |
| 09-15 18:52 | 09-15 深夜：dev-history 脚本补回归测试 + 抓到标题重复真 bug | 用户拍板：公开历史泄露选方案 1（不做）——接受已公开的旧提交，不重写历史、不移动目录。理由（我的判断，用户认可）：这些标识（主号名）本来就在公开仓库 URL 里，KV 命名空间 ID 单独不可利用。今后重建自动清（v2… |
| 09-15 19:00 | 09-15 晚：dev-history 主题索引（SAGAS）落地 —— main = 5363177 | 用户贴了一份外部评审（"你的档案是一条河，不是一张图"），认为有道理。核实后同意诊断，并直接实施。 |
| 09-15 19:06 | 09-15 晚：SAGAS 主题索引固化进 skill（v2.1.0）—— main = 5c9ff85 | 用户问「有没有固化下来，以后可能还会用」→ 核查后发现三个真实缺口并全部补上。 |
| 09-15 21:56 | 09-15 晚：HF 语义记忆重建 + MCP 知识图谱重建（09-06 套件随 rootfs 全丢） | HF：semantic_memory.py build 732→1070 条（索引 5.8MB，已上传 dataset USER/rikkaminis-memory），搜索验证命中正常。 |
| 09-16 00:27 | 09-16 凌晨：日志审计三发现攒入 backlog §16-§18（用户拍板"攒着"） | §16 REJECTED 打印 R8 混淆类名（LOW 一行修，ChatAgentTraceObserver.kt:128） |
| 09-16 01:26 | 09-16 凌晨：backlog A 类两条修完并合并 main = d5ce767（§16 + §17） | 分支 backlog-A → cherry-pick 到 711b6dd 之上 → d5ce767（4 生产 + 2 测试文件，+270/-14）。 |
| 09-16 14:05 | 09-16 傍晚：热榜吸收攒进 backlog §22（用户拍板"攒着"）+ CI flake 处置 | 用户拍板：该吸收的不实施，攒着。已写进 /var/minis/shared/backlog.md §22（33KB，file_write append 成功）： |
| 09-16 14:45 | 09-16 傍晚：§22 热榜吸收三项落地（22a 阶梯 skill / 22b evals / 22c 债务扫描） | 用户拍板同批：vector_index.pkl 不打包（backlog §22「设计决定」已改为已拍板 + 触发重估条件 + 附带待办：下次动仓库 requirements.json 时补一行「首次使用需 build（需… |
| 09-16 14:58 | 09-16 晚：dev-history 档案更新到 09-16（挂载版 + 仓库 docs 双份同源） | 挂载版：1019 条 / 45 天 / 1,211,143 字符 / 18,811 行；fences 32 even、anchors=header=1019、outOrder=0；脱敏 229 处（main 204 + … |
| 09-16 19:21 | 09-16 晚：共享区 + backlog 双重归档（用户拍板 A「搬」；两处同病：只进不出） | 触发：用户直觉「backlog 已经很长」「共享文档有点乱」→ 量化证实，且是同一个病：条目只进不出 + 没有判定时点。 |
| 09-17 09:11 | 09-17：审计交接文档已就绪（新会话处理 215 条 CONFIRMED） | 09-17：审计交接文档已就绪（新会话处理 215 条 CONFIRMED） |
| 09-17 17:21 | main = 709be37（docs/dev-history-0917 直达 main，docs 变更不触发 buil | main = 709be37（docs/dev-history-0917 直达 main，docs 变更不触发 build CI） |
| 09-18 11:20 | 09-18：dev-history 档案更新到 09-18（挂载版 + 仓库 docs 双份同源） |  |
| 09-18 11:44 | 09-18：dev-history 档案更新到 09-18（挂载版 + 仓库 docs 双份同源） | 挂载版：1057 条 / 46 天 / 1,258,261 字符 / 19,430 行；fences 32 even、anchors=header=1057、outOrder=0；脱敏 main + INDEX + SA… |
| 09-18 16:23 | 09-18 晚：dev-history 档案更新到 09-18（挂载版 + 仓库 docs 双份同源） | 挂载版：1069 条 / 62 天 / 1,274,940 字符 / 19,601 行；fences 32 even、anchors=header=1069、outOrder=0；脱敏 main + INDEX + SA… |
| 09-18 18:31 | 09-18 深夜：Termux↔RikkaMinis 打通（termux-dock MCP 桥）+ 三组实测数字 | 怎么发现的：探测本机监听端口时发现 127.0.0.1:8000 回 termux-dock MCP is running —— 用户 Termux 里早就跑着一个 MCP 服务（pm2 + watchdog 托管：te… |
| 09-19 01:44 | 09-19：in-app 更新 UI 判死（用户拍板"多余"，一阶门复核不过，写入 backlog 附记） | 用户主动提出不愿加 in-app 更新（设置页辟空间不值得）。agent 复核同意：一阶门不过（所有步骤手动可达，只省 4-5 次交互→2 次）；本 app 唯一用户=构建者（看 CI 拿包，无外部用户）；成本边清单 =… |
| 09-20 14:08 | 09-20：dev-history 档案更新到 09-20 → main = `3ec2681`（1137 条 / 49 天） | 流程（skill 标准四步全跑）：rebuild_dev_history.py → sanitize（main 212 + INDEX 27 处替换，独立探针 NONE）→ build_sagas.py（步 3 必在步 … |
| 09-20 21:17 | 09-20 深夜补：合并审查的遗留项登记 backlog §24 + ★ 一个实质发现 | 用户拍板「那个问题就先攒着」→ 按修复门纪律登记，不修。产出：/var/minis/shared/backlog.md §24（两条），scan_debt.py notes 复验 0 条 no-trigger。 |
| 09-21 14:07 | 09-21 下午：两套记忆装置增量更新（HF 语义记忆 + MCP 知识图谱） | 触发：用户「本地的和云端的两个记忆存储装置好像很久没更新了，就是 MCP 和 HF 处理一下」。 |
| 09-21 16:51 | 09-21 晚补：坐实批已入 backlog §27（用户：「把能够坐实的都丢进攒着的那里吧」） | 写入：/var/minis/shared/backlog.md §27（8 个子节 a–h，492 行）。扫描器 scan_debt.py notes → 18 段 / no-trigger 0 / 退出码 0。 |

## 15. 会话 / 导航 / 抽屉交互

**跨度** 2026-08-03 ～ 2026-09-22 · **81 条** · **状态** 已闭环（09-01 后未再复现）

**叙事**：08-03 空对话残留 → 08-04 左滑历史抽屉 + 新建对话弹窗 → 08-06 系统返回不进 SESSION_LIST + 双击返回 → 08-07 重启后进列表页而非新会话 → 08-18 RC10 深链路径穿越 → 09-01 launch-resume 导航修复 → 09-13 冷打开空白。**「打开应用后我在哪」这个问题被修了 6 次**，每次都是不同的入口（返回键 / 重启 / 深链 / 冷启）。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 21:00 | OpenMinis Android — 空对话残留 bug 根因与修复（commit b194927，CI 30815328668 success） | 用户报「历史里出现空对话，自动删除时好时坏」。定位到根因链： |
| 08-04 04:49 | OpenMinis fork — RikkaHub 风格左滑历史对话抽屉（2026-08-04 完成） | 分支 feat/chat-history-drawer（3 个提交 09bb392/80d73af/eb4893d），CI 全绿（30850745470、30851428249）。 |
| 08-04 05:23 | OpenMinis fork — UX polish 批量改动（2026-08-04，分支 feat/ux-polish） | 分支 feat/ux-polish（基于 feat/chat-history-drawer，2 个提交 1c28bf4 + 8c5bd58 文档），CI run 30853868293 success。 |
| 08-04 15:03 | RikkaMinis — 新建对话弹窗缺陷修复 + 收尾（2026-08-04 傍晚） | 用户报「对话进行中点顶栏铅笔（New Chat）会弹『停止对话并重新开始』确认框，明显不对；完结对话则直接创建」→ 定位为设计缺陷并修复。 |
| 08-04 19:59 | vlc-android fork 性能修复 + 底部导航确认（2026-08-04 晚） | 仓库 OWNER/vlc-android（master 分支，commit b9f4eae）。 |
| 08-04 20:56 | vlc-android — 底部导航谜底揭晓（2026-08-04 续） | 结论：用户装的是旧 APK。 截图"视频+浏览"两图标 = bottom_navigation.xml 菜单仅剩的可见项（nav_video+nav_directories），这是 v0.1-simplified 旧版（… |
| 08-05 00:56 | RikkaMinis — 草稿持久化 + 抽屉键盘 + rootfs 统计修复（2026-08-05，分支 feat/draft-persistence-ime-storage，commit aba858b） | 用户三个问题的根因与修复，全部已实现并推送，CI run 30931495639 验证中（分支验证，未合并 main）： |
| 08-05 10:01 | RikkaMinis 底部工具条 — 真机发现 footer 按钮失效，根因 = LaunchedEffect 自取消（2026-08-05） | 用户真机验收发现：历史抽屉 footer 里 Token 用量 / 设置两个按钮（默认唯一两个）点击无反应。 |
| 08-05 10:13 | RikkaMinis footer 按钮仍无效 — 静态逻辑已穷尽的排障备忘（2026-08-05 进行中） | 用户真机：历史抽屉 footer 的「Token 用量」「设置」两个按钮仍无效，且因此无法进入设置页。前两个修复（自取消 LaunchedEffect → historyDrawerScope.launch）后依然无效（… |
| 08-05 10:24 | RikkaMinis footer 按钮失效 — 真正根因 = dispatch 等 close() 挂起（2026-08-05，commit 599fe97） | 用户复验 5b54408 仍失效（v220000091）。最终根因不是 LaunchedEffect 自取消（那个也修了），而是 dispatch 顺序： |
| 08-06 11:06 | RikkaMinis 经验记忆修复完整方案（可直接开工版，基线 main@87f69eb） | 目标：让经验的检索/验证/回写在多会话并发、排队切换、取消、异常、清空下保持同一任务语义。原则：一次经验交换必须有稳定身份和明确生命周期，禁止再用"最后一条消息"和文件行号猜测。 |
| 08-06 13:18 | RikkaMinis 抽屉返回手势修复 — 2026-08-06 | 问题 |
| 08-06 15:04 | 修复：系统返回不再进入 SESSION_LIST + 合并日志 bind（2026-08-06） | 分支 fix/drawer-back-gesture（fde1a8b，CI run 31078807462 success） |
| 08-06 16:17 | 2026-08-06 会话总结（交接用） | 已完成 |
| 08-06 17:18 | RikkaMinis — 双击返回 + fallback entry 精度修复（2026-08-06 下午） | 已合并 main |
| 08-06 17:46 | 会话状态核实（2026-08-06 晚，续 pinned-providers） | 后台构建（用户说"一个在打包构建中"） |
| 08-06 20:10 | RikkaMinis pinned-providers「点亮星无反应」调查（2026-08-06 晚，进行中） | 用户报告 |
| 08-06 20:17 | pinned-providers 点亮星 — 日志真机时间线（2026-08-06 续，20:11 新 session） | 用户 20:11:35 重开日志绑定，20:11:42-55 在真机反复点星。日志证据链： |
| 08-06 20:26 | RikkaMinis pinned 修复 — 根因 + 「为什么难找」复盘（2026-08-06 收尾） | 根因（100% 确认） |
| 08-06 22:21 | RikkaMinis — 模型选择器支持 pinned 常用区（2026-08-06） | 用户问题 |
| 08-07 13:12 | RikkaMinis「重启后进入历史会话列表页而非新会话」根因（2026-08-07） | 用户预期：设置里选了"启动 New Chat"，打开 app 应直接进聊天对话框。现象：重启后停在历史会话列表页（HOME）。 |
| 08-08 09:49 | 修复：草稿中点击「新建对话」无响应 | 问题：用户在草稿（__new__<uuid>）中输入内容后，点击顶栏「新建对话」按钮，视觉上无任何反应。 |
| 08-08 10:53 | 固定会话 Pin 按钮位置修复 — 移到行最右 | 用户反馈：历史抽屉固定会话的 PushPin 按钮放错位置——原来在标题列和时间中间（图标 \| 标题 \| Pin \| 时间），应放到最右边（图标 \| 标题 \| 时间 \| Pin）。 |
| 08-08 11:52 | 移除冗余关闭/返回按钮 — 已提交推送，CI 验证中（2026-08-08） | 分支 feat/remove-redundant-close-buttons（commit 8e4bf92，基于 feat/recovery-strategy HEAD 9874a4f） |
| 08-08 11:59 | ↳ 四个待合并分支已全部合并 main（2026-08-08，HEAD b000e31） | 合并方式：feat/recovery-strategy(9874a4f)、feat/remove-redundant-close-buttons(8e4bf92) 线性 ff；perf/immutable-chat-mo… |
| 08-08 15:07 | ✅ compact/thinking 从斜杠提到顶层菜单 — 已合并 main（2026-08-08） | 分支 feat/menu-compact-thinking @5a3640d（13 文件 +152/-54）： |
| 08-09 07:27 | 终端双问题根因 + 修复（commit b8dd5cb，CI run 31283965704） | 用户报两个新症状（#305 APK = e628206）： |
| 08-12 01:43 | 冷启动性能实测（2026-08-12）——config 加载不是瓶颈 | 用户报"17 秒冷启动"。真机实测（进程 18006，被 MinisNotificationListenerService 拉起）： |
| 08-12 12:37 | 会话存储回收功能完成（feat/session-storage-reclamation → main bfd621c，CI 双绿） | 用户报本地设置存储页看到工具类对话体积接近 200MB，与备份 OOM（ConfigBackup.export 打包会话内容）同源——会话目录无约束累积、无自动回收。 |
| 08-12 12:42 | 会话存储回收功能真机验证通过（2026-08-12 用户确认） | Clear（留文字删文件）：存储页逐会话清空后，体积释放，对话历史条目和内容不变，符合预期 ✅ |
| 08-12 15:54 | T5 上下文压缩引擎完成（feat/context-compactor → main bbf8ab1，CI 绿） | 改动：新文件 conversation/ContextCompactor.kt（纯逻辑决策引擎）+ ChatViewModel 挂载 + 单测。 |
| 08-12 15:55 | ↳ T3 失败学习自动化钩子完成（feat/failure-auto-log → main 667f17d） | 2026-08-13 |
| 08-13 03:30 | ↳ 任务 B：核心文件补测试 ✅ 完成（2026-08-13） | 分支 fix/core-file-tests → rebase 到 main（f76e5d1）后合并推送，分支已删（本地+远端） |
| 08-13 11:33 | 冷启动卡顿分析（2026-08-13 用户日志） | 用户另一台设备日志显示进入聊天页面时有"卡住"感觉。根因分析： |
| 08-13 21:26 | 【BUG 调查】rootfs 周期性重建清空 apk 包 + bash 不恢复（2026-08-13 晚） | 现象：会话中途 curl/python3/bash 全部消失，apk add 重装后约 30 分钟又丢。 |
| 08-13 21:34 | 【交接·方案3】apk 包持久化 — bug 因果链 + 方案设计（2026-08-13 21:40） | 任务一句话：把"用户通过 apk 安装的包"做成可恢复快照——apk 装包清单持久化到 host 侧（app 私有目录），rootfs 被 reset/全量重建后按清单自动重装。修掉"强停/杀应用 → 重开 → root… |
| 08-18 15:12 | RC 整改执行指令（分会话凭编号即可领取，勿需用户贴长文本） | 用户约定：以后派发整改任务，只需发编号（RC1~RC6）。分会话 agent 收到编号后，到下面对应条目领取完整执行指令，并自查「通用纪律」。总控在本会话收口。 |
| 08-18 16:04 | RC1 minis:// 路径解析规范化完成（2026-08-18） | 分支 fix/audit-rc1-path-normalize，commit 0bf4574，CI run 32113515882 success（build job 25 steps 全绿）。 |
| 08-18 20:48 | RC10 完成（2026-08-18，独立会话） | 深链 minis://session/<sid>/<path> 路径穿越整改闭环，CI 绿（run 32137371990 success），未合并 main、未删分支（等总控 ff）。 |
| 08-18 21:03 | RC10 深链路径穿越整改闭环（2026-08-18 深夜） | 分支 fix/audit-rc10-deeplink-traversal，commit 8efef27，CI 绿（run 32137371990 success），已 ff 合并 main a4369d3 → 8efef… |
| 08-18 21:23 | ↳ FE-4 纯函数扫尾(层次1)合并 main 2026-08-18 | FE-4 第三波(层次1扫尾)合并 main 8efef27 → 2231857,release CI 绿(run 32140809865),分支已删。 |
| 08-20 15:39 | D-4b 领任务启动（2026-08-20） | 领 D-4b（长时并发聊天压测，定位 6GB 泄漏真凶）： |
| 08-23 16:44 | 修复收口完成（2026-08-23 晚）— 5/5 修复分支 CI 全绿 | 基于 a6b2665 的 5 条修复分支全部完成并推送小号远端，分支 CI 全绿（head_sha 与 tip 逐一核实）： |
| 08-26 00:47 | 会话 F：打开历史对话默认回顶部 — 施工+阻塞(2026-08-26) | 任务：修复「冷打开历史会话默认落在顶部而非底部」。根因(代码实证)：消息级聚合(AGGREGATE_MESSAGE_ITEMS=true)+SIMPLE_FOLLOW 改造后，InitialOpen 消费端在 LazyC… |
| 08-26 13:36 | 历史对话回底部「随机失效」调查进行中（2026-08-26 上午，接续 8484a49） | 用户反馈：fix/history-open-at-bottom（8484a49）合并装包后仍随机失效——偶尔定位顶部、偶尔正确到底部。真机 beta.1083 = main 0ba797a（含修复），版本已核实（dump… |
| 08-26 14:39 | 历史对话回底部随机失效 — 根因修复已合 main（dbaa4aa，2026-08-26） | 根因（Compose 源码级实证）：8484a49 修复后仍随机的根因有两层： |
| 08-27 13:28 | 历史对话「打开定位到底部」第三次修复 — 诊断完成待施工（2026-08-27） | 用户报「打开旧对话要定位到底部」，之前修过两轮（8484a49「空列表吞请求」→ dbaa4aa「sentinel 可见才 consume」）仍没修好。本次诊断定位到新一层的根因。 |
| 08-27 14:15 | 历史对话「回底部」第三轮施工翻车复盘（2026-08-27 下午） | 施工会话按我的 task-H 任务书做了，提交 a256178 fix(chat): wait for first-frame layout stability before the bottom scroll，但没解决… |
| 08-27 14:23 | 历史对话「回底部」第四轮方案定案（2026-08-27 下午） | 用户确认第三轮（a256178「等首帧布局稳定」poll）没修好还引入新问题：施工后老毛病照旧，且新增「对话进行中往上滑，会突然跳到非常前面的某一段」。 |
| 08-28 11:39 | minis:// 链接误报 "Blocked link to external app" 修复已合 main（2026-08-28，main=75377e3） | 用户主诉：会话里点大模型产出的已下载文件链接（安装包/文档，minis:// 形式）偶尔被挡，报 "Blocked link to external app (minis)"——尤其"切到别的会话再切回来"时。另：UI … |
| 08-28 16:03 | 语言切换跳回聊天 bug 修复已合 main（2026-08-28，main=155aad0） | 分支：fix/lang-switch-nav-jump → 155aad0，分支 CI run 33151747788 success → ff 合并 main（75377e3..155aad0）→ release CI… |
| 08-28 19:50 | 语言切换跳回聊天 bug 最终根因 + 修复（2026-08-28，main=439c6c2，真机验证通过） | 第一轮（155aad0）用错 API，用户真机复现仍跳。第二轮（439c6c2）真正修复，用户实测「问题解决」。 |
| 08-31 20:40 | 卡死诊断：冷启动进 chat 界面卡死 ~10s（2026-08-31，minis-2026-08-31.log） | 用户报"装更新后整个应用卡死一段时间"。日志分析结论（证据链完整）： |
| 09-01 19:07 | place-storm 残留源修复 + launch-resume 导航修复（2026-09-01，commit 65b8a74 合并 main） | 用户试运行日志验证结论（minis-2026-09-01__4_.log，主号 91498d74 构建） |
| 09-04 11:01 | diff-audit 0904 四实锤修复闭环（合并 main @ b21ef1a1） | 按用户要求"工程量不大直接修"，分支 fix/diff-audit-0904 修复 4 处，分支 CI 33830806928 success（head_sha b21ef1a17 核对一致），ff 合并 main，删远… |
| 09-09 12:31 | fork 动态：Filterrr/RikkaMinis 深度自研（2026-09-09 用户问询） | 用户两诉求：①右滑弹出的会话 token 用量抽屉（TokenUsageSheet）数据不实时（尤其"总循环次数"）②Agent Runtime 预算上限调大：回合/Provider/工具/Shell 最大 500/51… |
| 09-09 13:10 | runtime-limits-ux 审计无 bug + 合并收尾（main @ ec1e5d8） | 来源：上一轮 runtime-limits-ux 审计顺带发现的既有 LOW（非最近 5 次引入）——BackupSettingsScreen importLauncher（SAF activity-result 回调，… |
| 09-10 01:00 | 第二轮审计修复：MEDIUM 清零 + LOW 批次（2026-09-10 凌晨） | main 状态：main @ d66fea1（B11 ff2455b + B12 d66fea1 已 ff 合并；B11 release CI 34377683652 success，B12 release CI 343… |
| 09-13 15:50 | ★ 内存飙升的会话级归属：shell 是 per-session 的（2026-09-13 用户对照实验 + 代码核实） | 用户对照实验结论：RSS 飙升只出现在他置顶的那两个会话，本会话（新会话）调用终端完全正常。 |
| 09-14 20:05 | 09-14 夜：运行日志审计 → 6 项修复已合并 main = ad71828c（release CI #1526 已触发，用户拍板不等） | 前提（用户提醒）：日志（13:48–18:51）属旧包（1.0.0+1516 及更早），设备现装 1.0.0+1524 已含 79d57d69 修复 —— 我用 APK dex grep（grep -ac 'stub, … |
| 09-15 13:51 | 09-15 下午：第二轮日志审计（新包 183a01ed 上线后）— 1 个 LOW 进 backlog §13 | 新包表现验证（三源）：13:41 后 W/HangDetector=0、TRIPPED=0、forcehome 全日志 0 → 前台门控 + launch verdict 修复在野生环境生效。ToolPreflight … |
| 09-16 14:21 | 09-16 傍晚：§20b 导航结果修复收口 — main = e940148（release CI 35063273119，用户拍板不等） | 分支 fix/audit0916b-nav-outcome（2 commit）→ cherry-pick 到新 main 735eadb 之上（另一会话推了 skills commit，零重叠）→ push tmp-me… |
| 09-16 15:08 | 09-16 晚：§20b 导航结果真机验证通过（e940148 验证缺口全关） | 实测：navigate http://127.0.0.1:1/ → success=false + Navigation error: net::ERR_UNSAFE_PORT + block status→FAILED… |
| 09-16 16:54 | ★ minis:// 链接坑（用户实测点不了，已纠正） | minis://shared/../mounts/... 无效：minis:// 只认固定前缀（attachments/workspace/shared/offloads/browser），mounts/ 不在映射，..… |
| 09-16 16:56 | minis:// 链接机制化（从"每次提醒"升级为机械守卫） | 单一漏斗：python3 /var/minis/shared/minis_link.py <绝对路径> 产链接（挂载盘/中文名自动 cp 到 shared + sha1 短名，幂等覆盖；不存在文件 exit 1 不产 U… |
| 09-17 14:22 | 09-17 下午：审计存量缺陷批次 1-7 收口 — main = e657335f | 成果：215 条 CONFIRMED → 已处理 155 条 / 92 文件，剩余 60 条（HIGH 4 / MED 32 / 其他 24）。10 个提交 FF 合并，CI 全绿，远端仅剩 main。 |
| 09-17 20:28 | 09-17 晚：用户已装新包 + 今晚 launch-beacon 数据判读 | 用户装了新包：设备 versionCode=220001628 / versionName=1.0.0+1628，lastUpdateTime=2026-09-17 18:15:09（= 傍晚 CI #1628 构建，与… |
| 09-18 00:00 | 09-17 深夜：诊断「完全退出后回到上次会话」——ce4ccb91 深链修复的副作用 | 现象：用户设置 Launch Session = NewChat（日志 [LaunchSession] mode=NewChat 实锤），后台恢复走新会话 ✓，但完全退出后冷启动 → 打开上次所在会话 ✗。 |
| 09-18 00:27 | 09-17 深夜修复：冷启动恢复覆盖 Launch Session 设置（main = bd55749b，#1637 绿 / #1638 release） | 改动（分支 fix/coldstart-restore-gate，3 文件 +137−3）： |
| 09-18 00:29 | 09-17 深夜收口：冷启动恢复修复真机验证通过（main = bd55749b） | 用户真机验证通过：装的是分支构建（CI #1637 artifact，bd55749b 树）解压出的 APK，完全退出后重开 → 新会话，符合预期。四层闭环全部打开：新代码 6/6 绿 → 反向对照 3/6 红 → 分支… |
| 09-18 15:33 | 09-18 晚：查清「T321 的 500」——顺便揪出潜伏的 ack 协议缺陷（日志改造首日战果） | 触发：15:22 用户装新包（cdd2817）后，error-snapshot-2026-09-18-152205.log 显示 T321 请求 500。用户点破："你就是那个中转站提供的那个"——T321 = 本会话自… |
| 09-19 14:32 | 09-19：offload 审计第 9 棒完成（Ring 2 / P1 = `browser` 包 → F-98…F-104） | 状态：✅ 第 9 棒完成 · 只读，仓库 0 改动 · QA verify_all.sh 32/32 全绿 · 判据 verify_findings_9th.sh 73/73 · 生成器自对账 16 项 |
| 09-19 19:20 | 09-19：offload 审计第 15 棒完成（Ring 2 第八段 = `tools/` 14 文件 2,792 行 + `speech/` 7 文件 1,649 行 → F-153…F-160）· **Ring 2 至此 27,003 行 / 88 文件全部走完** | 状态：✅ 只读，仓库 0 改动（HEAD = c6d8d63f = 锚点，git status --porcelain 空）· 包闸门 verify_all.sh 67/67 · 判据 verify_findings_1… |
| 09-20 01:47 | 09-20 凌晨：offload 审计 WAVE-2 并发线 A4 完成（`provider/` 杂项 + `ModelsDevApi`） | 产出（/var/minis/shared/offload-audit-0919/wave2-a4/）：report.md(34.9KB) · ledger-a4.json · ledger-section.md（可直接并… |
| 09-20 02:55 | 09-20 凌晨：offload 审计第 21 棒 **并发线 C1** 完成（`ui/chat/` 核心状态机与持久化） | 范围：ChatScreen.kt 5027 + ChatViewModel.kt 3843 + ChatSessionLifecycle.kt 1557 + ChatTurnPersistence.kt 454 + Ch… |
| 09-20 10:03 | 09-20：FIX-8-approot 补扫 —— 两文件 1,820 行真缺口，抓出 3 条 D（main = `541fbb2`） | 起点：第 22 棒收口报告里登记的「MinisApp.kt + MainActivity.kt = 1,815 行从未逐行走查」。用户直接说「那你这里直接把他们补上」。 |
| 09-20 17:10 | 09-20 晚：隐藏设置页 Agent Runtime 里的终端行 → 分支 `chore/hide-settings-terminal-row`（CI 绿，**按用户要求不合并**） | 用户判断：设置 → Agent Runtime 里的「终端」行冗余——真要用，外观 → 聊天菜单里已经能把它放到右上角菜单/抽屉底栏。去掉。 |
| 09-20 17:57 | 09-20 晚：云端三分支核查 → 全部合并进 main = `27eced19`（CI run 35503040884 success） | 用户指令：检查云端三个分支有没有引入 bug，没有就合并。 |
| 09-20 18:02 | ↳ 09-20 晚：三分支合并的真机验证 —— 用户确认全部通过 | 用户反馈：「修改，验证已完成，都没有问题。」 |
| 09-21 16:44 | 09-21 晚补：同类应用 issue「坐实」——从静态 grep 升级到独立实测，并挖出 T8 未接线 | 用户指令：「进行验证，先把他们坐实。」 |
| 09-22 01:45 | 09-21 深夜：V4 线（verify-all-0921 剩余模块验证）收口 —— 产出 5 个装置缺陷，其中日志撕裂机制完全确证 | 任务书 /var/minis/shared/verify-all-0921/tasks/V4-rest.md。产出 /var/minis/shared/verify-all-0921/reports/V4-report.… |

## 16. 平台适配 / 通知 / 图标 / 权限

**跨度** 2026-08-03 ～ 2026-09-20 · **71 条** · **状态** 部分放弃（灵动岛需申请小米白名单；其余已闭环）

**叙事**：08-05 小米灵动岛适配 → **卡平台白名单，已废弃回滚** → 08-07 通知震动被 MIUI 掐死 → 改直驱 Vibrator → 08-08 灵动岛焦点通知**再次砍掉** → 08-10 图标跟随系统主题 → 08-22 双 appid 共存 → 09-10 包名迁移 com.openminis.app → com.rikkaminis.app → 09-13 图标极简重设计**放弃并全部回滚** → 09-15 HangDetector 前台门控（HyperOS 冻结后台）。**平台层是本项目「努力最多、成功最少」的一层。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 21:09 | Minis 全功能自检（2026-08-03 晚） | 对设备上的 Minis 环境做了系统性功能测试，结果： |
| 08-05 07:48 | RikkaMinis 功能完整性审计报告（2026-08-05） | 用户判断"没什么可加了，加任何功能收益都<临界值"。我做了完整审计，结论：判断基本成立。 |
| 08-05 14:36 | 用户 GitHub 多账号 — 官方 App 多账号切换（2026-08-05） | 用户有两个 GitHub 账号：主号 OWNER（所有仓库/CI 都在此）+ 网页新注册的第二个号。曾在手机上用「应用双开」试图双开 GitHub App 登录新号失败（双开分身改包名 → OAuth 回调 URL sc… |
| 08-05 14:45 | GitHub 小号 ***ALT_USER*** 满权限 token 已验证（2026-08-05） | 用户给了沙箱第二个 GitHub token：GITHUB_TOKEN_FULL_RIGHT（环境变量），归属账号 ALT_USER（ID 313291818，2026-08-05 注册，free 计划，无 2FA，无仓… |
| 08-05 15:01 | CF 小号技能化 + token 安全迁移完成（2026-08-05） | Cloudflare 小号 token 已从明文迁移到环境变量 |
| 08-05 16:00 | RikkaMinis 小米灵动岛适配 — 代码完成，卡平台白名单（2026-08-05） | 用户设备：Redmi Note 12 Turbo（marble），Android 15 + HyperOS 3.0（OS3.0.1.0.VMRCNXM），非 Android 16。RikkaMinis（com.openm… |
| 08-05 16:11 | RikkaMinis 小米灵动岛适配 — 已废弃回滚（2026-08-05 收尾） | 用户决定废弃（"不起作用就废弃，回滚到之前没干这个的状态"）。 |
| 08-06 00:39 | 记忆体/经验引擎概念 — 小号独立应用方向（2026-08-06 灵感记录，暂缓） | 用户在思考"以 RAG/记忆为核心的东西"时有了关键洞察，与 RAG v1 不同，先记下来以后再做： |
| 08-06 20:10 | RikkaMinis pinned-providers「点亮星无反应」调查（2026-08-06 晚，进行中） | 用户报告 |
| 08-07 10:46 | RikkaMinis 第3项权限统一（2026-08-07）+ 意外发现 P2-proot 修复 | 第3项完成：权限判定单一事实源 |
| 08-07 20:48 | 任务完成通知增加震动（2026-08-07 已合 main 3a30411） | 用户场景：塞耳机听别的事 + 息屏，agent 后台任务执行完后能震一下提醒。 |
| 08-07 21:34 | MIUI 通知震动被系统层掐死 → 改直驱 Vibrator（2026-08-07 重大根因） | 现象 |
| 08-07 21:45 | RikkaMinis 任务完成震动 — 已成功 + 前台震动待办（2026-08-07 收尾） | 最终状态 |
| 08-07 22:00 | RikkaMinis 前台震动已完成并真机验证（2026-08-07 收尾） | 最终状态 |
| 08-08 11:28 | 灵动岛焦点通知测试 — CI 已绿，待用户装包验证（2026-08-08） | 分支 feat/focus-notification-dev-test（commit d256c64），CI run 31236760448 success |
| 08-08 11:30 | 灵动岛焦点通知 — 已砍掉（2026-08-08） | 用户决定放弃该功能。分支 feat/focus-notification-dev-test（d256c64，含 FocusNotificationTester/XiaomiFocusHelper/XmsfFirewall… |
| 08-08 13:50 | 并发会话提醒 — 另一个对话框在做图标修复（2026-08-08） | 用户告知：另一个对话框正在进行"图标"相关的修复（具体内容未详，可能是 app 图标/UI 图标/启动图标）。 |
| 08-08 22:46 | RikkaMinis 新图标设计语义 | 用户希望手机端智能体应用图标表达：控制论在应然与实然间搭桥、系统机制相互作用、GEB 怪圈，以及从上游 fork 后“边使用边开发、边开发边使用”的本地开发→云端编译→循环迭代。视觉压缩原则：不堆叠概念，不用机器人/脑/… |
| 08-08 22:52 | RikkaMinis 图标 V1 | 已生成“分叉怪圈”首版成品预览：深墨绿方圆底、薄荷色连续回路、翻面处少量珊瑚色，并验证 128/96/64/48/32px 与 Android 单色版。SVG 位于 workspace/rikkaminis_icon_v… |
| 08-10 11:10 | 图标自动跟随系统主题修复 + 通知横幅精简（2026-08-10） | 分支 fix/icon-auto-follow-system → 已合并 main，CI success |
| 08-11 00:30 | 任务完成弹窗大图标修复（fix/notif-drop-large-icon → main 15888dc） | 用户报任务完成弹窗"左边小图标+右边大图标"。根因：2026-08-10 删 FGS 大图标时只处理了 AgentForegroundService.kt，漏了 BackgroundTaskNotifier.kt（任务完… |
| 08-13 21:34 | 【交接·方案3】apk 包持久化 — bug 因果链 + 方案设计（2026-08-13 21:40） | 任务一句话：把"用户通过 apk 安装的包"做成可恢复快照——apk 装包清单持久化到 host 侧（app 私有目录），rootfs 被 reset/全量重建后按清单自动重装。修掉"强停/杀应用 → 重开 → root… |
| 08-18 00:10 | ✅ shizuku binder 泄漏修复闭环（2026-08-18） | 问题：app 反复 SIGABRT，崩溃现场 VmRSS 6-8GB / VmPeak 17GB。用户在同一对话打开终端跑程序，内存飙到 4-5GB。 |
| 08-18 00:24 | 可复用教训：native 进程 / offload 的一类泄漏 bug 模式（2026-08-18 shizuku 修复沉淀） | 昨天（2026-08-18）修完 shizuku binder 泄漏后，把两个 bug 模式提炼成可复用的排查/修复纪律，供后续会话参考（详细修复记录在同日 daily log"shizuku binder 泄漏修复闭环… |
| 08-20 15:39 | D-4b 领任务启动（2026-08-20） | 领 D-4b（长时并发聊天压测，定位 6GB 泄漏真凶）： |
| 08-20 19:19 | 权限页「配置工具」开关文案本地化（2026-08-20 收尾） | 用户反馈：设置 → 权限 →「配置工具」分区里，开关标签显示的是英文技术名「允许 minis-config」，与中文界面（及「配置工具」分区标题）不协调。 |
| 08-22 15:55 | 小号/大号应用共存前置工作（dual-appid） | 用户决策：接下来改动集中在小号（ALT_USER fork）做实验，需要大小号编的应用能共存（同设备并排诊断，互不覆盖）。大号 OWNER/RikkaMinis 保稳定不动。 |
| 08-22 17:04 | [dual-appid] 重要决策：小号维持分支进度，不合并 main | 用户明确拍板：dual-appid 的改动（应用共存实验）留在小号实验分支 chore/dual-appid 的"进度"态即可，不要合并进小号 main。 |
| 08-22 17:19 | [小号内存治理] 交接：用户决定在小号补 D-4b 验证（聊天 provider-rss 泄漏判定） | 用户当前明确决策链（2026-08-22 晚，本会话对齐）： |
| 08-22 18:20 | 小号内存治理方案提案（2026-08-22） | 审计 ALT_USER/RikkaMinis chore/dual-appid tip 5672ca3 后，建议不恢复完整内存隔离 v2，也不直接迁 browser/toolservice；先做 D-4b 取证，再施工 … |
| 08-22 18:38 | 小号内存治理：任务已派发（2026-08-22 晚） | 方案文档已从 workspace 备份到笔记文件夹（/var/minis/mounts/笔记/RikkaMinis开发档案/小号内存飙升根治方案-Provider-Worker-Hard-Boundary.md，sha2… |
| 08-22 21:31 | 小号内存治理：五分支全部完成，已收口（2026-08-22 晚） | TF-A/B/C/D/E 五分支真实完成并独立核实（非仅转述）：A diag/provider-rss-v2 c8ff0a4 (run 32569593961) / B fix/modelservice-terminal… |
| 08-22 21:54 | ↳ 真机日志推翻 worker 协议闭环（2026-08-22 晚） | TF-E APK 真机日志发现 P0：:modelservice PID 30053 因 ModelExecutionService.finishRequest() 写 state.json 时 run 目录已被主进程删… |
| 08-22 22:58 | ↳ TF-F modelservice run-dir 所有权 P0 修复完成（会话 F，2026-08-22 晚） | P0 根因：TF-E APK 真机 crash（:modelservice PID 30053 state.json ENOENT）——ChatStreamOffloadHandler.finally 在 result.… |
| 08-23 13:28 | TF-J2 根因确证：/proc hidepid=invisible 导致假死，心跳修复完成（2026-08-23） | 决定性根因（设备实测坐实，不是代码 bug）：用户最新真机日志 minis-2026-08-23__4_.log + android-shizuku-cli exec 'mount \| grep " /proc "' 确… |
| 08-23 14:29 | 会话 C 进行中（2026-08-23）：存储/备份/配置同步压力测试 | 执行环境：真机 Redmi marble (Android 15, SDK 35)，com.openminis.app.lab beta.41/versionCode 220000041 已装。android-shizu… |
| 08-23 14:29 | 会话 A（bug-hunt-pressure / session-task-A）中途结束：用户判定意义不大 | 用户明确取消会话 A 的「agent 多轮流式 + worker 生命周期压测」任务，理由：这部分日常使用几乎都会遇到，有问题他能立刻感知，压力测试意义不大。 |
| 08-23 19:15 | 小号成果整体快进合并进主号 main 完成（2026-08-23晚） | 用户拍板「把小号成果合并进入主号」，按建议整体 fast-forward，闭环完成： |
| 08-26 00:55 | 主号回滚 + 小号同步执行记录（2026-08-25 深夜） | 背景：我（本会话）之前做的「限 PRoot 地址空间 RLIMIT_AS=4GB」修复（commit de13ed18）翻车——4GB 压太狠导致 PRoot tracer 起不来，所有终端/shell 瘫痪（用户 B … |
| 08-26 22:47 | 技能+权限模块审计修复完成（分支 CI 绿，未合 main 等拍板） | 用户要求审计「设置→技能」「设置→权限」两模块并优化，全部做完（branch fix/skills-permissions-polish @ 4f51c8b，分支 CI run #1112 success，head 核实… |
| 08-28 01:13 | 收尾加固会话 C 完成：外围 i18n + a11y + rootfs 磁盘预检（2026-08-28，分支 fix/i18n-periphery-and-diskguard） | 状态：分支 CI 绿（run #1141 success，head=65010cb 核实一致），未合并 main（按任务书纪律等总控收口）。回报 /var/minis/shared/final-hardening-dis… |
| 09-01 15:53 | place-storm 修复收口：小号 main 已合并（2026-09-01） | 分支 fix/place-storm-follow-clamp-loop 分支 CI run 33480996005 success（head_sha=70f927d 核实） |
| 09-03 21:18 | 5af0306 真机验证测试完成（2026-09-03） | 真机 beta.1273（versionCode 220001273，20:53 安装）= main HEAD 5af0306（占位气泡修复版），用户从分支 CI artifact zip 直接安装确认 |
| 09-06 13:40 | 主号→小号全量同步（2026-09-06 13:40，merge 350843f） | 背景：用户要求"把主号的成果与小号同步"。主号 OWNER/RikkaMinis main @ 5ebff693（09-05），小号 ALT_USER/RikkaMinis main @ 70f927d1（09-01）。… |
| 09-06 14:02 | 小号同步 CI 闭环确认（2026-09-06 13:55） | 主号→小号 merge 350843f 的 CI success（run 34014575216，head_sha=350843f 核对一致）： |
| 09-06 16:07 | 主号→小号同步 6b95929（2026-09-06 16:0x，merge 9047a8ef） | 同步内容：主号 main 6b95929（商汤思考档 400 双 bug 修复：worker thinking rules 跨进程恢复 + sensenova effort enum clamp，7 文件 +310）→ … |
| 09-06 17:30 | 小号同步 CI 遗留闭环确认（2026-09-06 晚） | 小号 ALT_USER/RikkaMinis main @ 9047a8ef（merge 6b95929）的 CI run 34020741695 success（head_sha=9047a8ef 核对一致）；主号 m… |
| 09-06 23:58 | dev-history 0906 同步 + 主号→小号全量同步（2026-09-06 深夜） | 主号 main @ 7d928fa3（docs(dev-history): sync archive to 2026-09-06, 794 entries / 35 days）： |
| 09-07 15:14 | 文档更新 + Hermes 借鉴登记 + 小号同步（2026-09-07，main @ 5e81aa1a / alt @ 3e8dc9e6） | 任务：用户要求更新文档 + 同步小号，并把"这次新借鉴的东西"在文档做登记。 |
| 09-07 22:48 | 文档更新 + 小号同步（2026-09-07 晚二次，main @ fce2ccc8 / alt @ 46e7d0e2） | 任务：用户再次要求更新主号文档 + 同步小号（15:14 已做过一轮，此为 15:14 之后的增量）。 |
| 09-10 01:46 | 上游痕迹清理评估 + 包名对齐方案（2026-09-10，待用户拍板） | 用户指令：去掉上游（OpenMinis）痕迹、与应用名 RikkaMinis 对齐，重点问包名工程量。用户已拍板 B 方案（改 namespace/包路径），并明确 不再同步上游（差异太大，改为按需"融合"，融合本身工作… |
| 09-10 03:10 | 包名迁移完成：com.openminis.app → com.rikkaminis.app（2026-09-10，main @ e24ca02） | 用户指令：方案 B（改 namespace/包路径）+ 不再同步上游（改按需"融合"）；批准后要求"直接合并、不用等"，因为最终会有一次统一审核环节。 |
| 09-10 03:30 | 图标设计（2026-09-10，用户拍板：不改） | 决定：用户看过我的新方案后说"算了，还是用原来的吧，就是他是我选出来的，让我觉得最不坏的"——图标保持现状，不动仓库。 |
| 09-10 03:34 | 第三轮复查完成：B1-B22 + token + 包名重构，无 bug（2026-09-10 深夜） | 第三轮复查完成：B1-B22 + token + 包名重构，无 bug（2026-09-10 深夜） |
| 09-10 04:09 | 最新打包应用全面自检（2026-09-10 04:00-04:20，包 1.0.0-beta.1439） | 测试对象：com.rikkaminis.app versionName=1.0.0-beta.1439（versionCode 220001439，装于 03:56），对应 main CI run #1439 @ e24… |
| 09-10 09:44 | 小号线自动同步 + lab 侧同款修复 + 文档三项（2026-09-10） | 小号线定位（用户澄清）：ALT_USER/RikkaMinis = 上游 main verbatim + 唯一 delta（workflow 注入 MINIS_APP_ID_OVERRIDE=com.rikkaminis… |
| 09-10 10:02 | 小号线同步闭环验证 + 文档合并（2026-09-10，main @ f1123178） | 小号线（ALT_USER/RikkaMinis）三条验证全绿： |
| 09-10 12:03 | 挂载编辑页名称提示修复 + 输入框高度对齐 rikkahub（2026-09-10 下午） | 用户拍板：本会话收尾，验证工序交下个会话。交接文档 /var/minis/shared/composer-mount-ui-fix-handoff.md。 |
| 09-10 15:08 | dev-history 重建 882 条 + 小号同步机制实测通过（2026-09-10 下午收尾，主仓 main @ 68f5715） | ① 文档更新：rebuild_dev_history.py + sanitize → 882 条 / 39 天 / fences 32 even / outOrder 0 → 复制进主仓 docs/dev-history… |
| 09-13 21:05 | 应用图标极简重设计（进行中，2026-09-13 晚） | 用户诉求：当前图标太"立体渐变"，想要 OpenAI / DeepSeek 那种简约风，但必须还是原来那个 logo——原 logo = 两个莫比乌斯环/两条互锁带状月牙共构一个圆（蓝带+绿带，浅色渐变立体渲染，白底）。… |
| 09-13 21:06 | 应用图标极简重设计 —— 已放弃并全部回滚（2026-09-13 晚） | 结果：用户看过 A/B/C 三个候选（白底双色 / 深蓝底白+青 / 白底单色，均为"两莫比乌斯环互锁成圆"的扁平重绘）后判定都不行，决定保留原图标，并说"暂时不改了"。 |
| 09-14 18:32 | 09-14 夜：HANDOFF-B2 量化结论（前提被推翻）+ 待办 2 已存在 | B2 的第一步（先量化）做完了，结论是「不做」。 |
| 09-18 12:01 | 09-18：1 号满权限小号（alarmedvine）接入收口 —— 含 gh_fullright.sh 两个老 bug 修复 | 新账号：环境变量 GITHUB_TOKEN_FULL_RIGHT_1 = GitHub 1 号小号 alarmedvine（ID 210298370，2025-05-05 注册，free，有 2FA，21 个全量 sco… |
| 09-19 00:25 | 09-19：小号 fork 同步断线 4 天修复（***ALT_USER***，非代码仓改动，直接 API 操作） | 情况：ALT_USER/RikkaMinis 的每晚同步 workflow（sync-fork-main.yml）09-15 起连续 4 天 failure，fork main 停在 09-14，lab 救援线过期。 |
| 09-19 09:45 | 09-19 第二轮：工具 handler 群精读（offload 第二个消费面）→ 新报告 + 5 项发现 | 用户"继续推"后，按上轮承诺推进第二个消费面（guest CLI → abstract socket → :toolservice → handler）。 |
| 09-19 10:05 | 09-19：offload 审计第 3 棒完成（Shizuku/A11y/Photos 逐行走查 → F-26…F-40，只读） | 任务：读交接书 /var/minis/shared/offload-audit-0919/HANDOFF-offload-3rd.md 并执行第 3 棒——逐行走查三个高风险 handler（函数体未读），按 7 问清单… |
| 09-19 10:18 | 09-19：offload 审计第 4 棒完成（Contacts/Notification/Location + ★勘误 E-1 进程模型） | 任务：接第 3 棒继续推进。本棒 = ContactsOffloadHandler(332，"参考实现") + NotificationOffloadHandler(579，PII) + LocationOffloadH… |
| 09-19 10:48 | 09-19：offload 审计第 5 棒完成（Calendar/Alarm/Speech+Speak → F-51…F-68 + 勘误 E-2 + 权限分类重评估表） | 产出（/var/minis/shared/offload-audit-0919/）：报告 reports/rikkaminis-calendar-alarm-speech-audit.md(31KB) · diagram… |
| 09-19 11:14 | 09-19：offload 审计第 6 棒完成（Notification 全文 + BrowserUse/Config/Sessions + 权限页 → F-69…F-82 + 勘误 E-3/E-4/E-5 + A 类边清单） | 状态：只读审计，仓库 0 改动，锚点 main c6d8d63f。 |
| 09-20 03:07 | 09-20 第 21 棒 · 并发线 D1 完成（`app/offload/` 顶层包 12 文件 / 2,291 行） | 范围：src/android/app/src/main/java/com/rikkaminis/app/offload/（顶层包，非已扫完的 sandbox/offload/）。基线 rev 99783703，独立 cl… |
| 09-20 08:48 | 09-20：FIX-6 修复批完成并合入 main（设置/配置 9 条 + F-224 数据层收尾） | 最终 main = b67558d。两个阶段： |

## 17. 国际化 / 文案 / 本地化

**跨度** 2026-08-04 ～ 2026-09-19 · **25 条** · **状态** 已闭环（08-28 全量 i18n 改造合并 main）

**叙事**：08-07 剔除法语文档 values-fr → 08-28 收尾加固三任务（ChatViewModel i18n + chat 组件 + 外围 i18n）→ **语言切换跳回聊天 bug**（两轮：一次误判，一次最终根因）→ 09-15 七语言字符串同步纪律（搬运大段代码后必须对照 strings.xml 清干净）。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-04 15:27 | RikkaMinis — Chat Menu 设置屏三项修复（2026-08-04，commit 274709a 已合并 main，beta.59） | 用户报 3 个问题：①设置→外观→Chat Menu 里开关不即时反应；②拖动排序只能滑一格；③"Chat menu"英文突兀（未本地化）。 |
| 08-04 17:56 | RikkaMinis — fix/webdav-restore-doublefire 构建检查（2026-08-04） | 用户报「构建完成了，检查一下」。检查结果： |
| 08-04 21:53 | RikkaMinis — 模型组页简化 + 语音 UI 移除（2026-08-04，分支 feat/...） | 仓库 OWNER/RikkaMinis，分支 feat/simplify-model-groups-remove-voice，commit 6cf806f9，CI run 30915989290 验证中。 |
| 08-06 00:16 | TokenUsageSheet 补上缓存命中率（2026-08-06） | 用户发现 Settings → Usage Stats 有缓存命中率，但对话中 ⋮ → Token Usage 底部 sheet 没有。 |
| 08-07 19:13 | RikkaMinis: 砍 rootfs 备份/恢复 + soul.lang 接线（2026-08-07 已发版） | 用户要求合并编译发版，5 文件改动已合 main a37c537，CI run 31172667557 全绿，release android-latest 资产已更新（11:12Z）。 |
| 08-07 22:26 | RikkaMinis 剔除法语文档 values-fr（2026-08-07, commit f94ad2e） | 决定与理由 |
| 08-09 13:50 | 供应商详情页只显示已选模型 + 新增"管理全部模型"页（分支 feat/provider-models-manage，CI run 31297147693 success） | 用户灵感：仿 rikkahub"模型照拉、只显示选中的几个"。实现： |
| 08-14 00:25 | UI 模块审计 + 三修复分支全部合并 main（2026-08-14） | 任务：用户要求审计 RikkaMinis UI 模块（140 文件 7.15 万行），产出施工方案交其他模型，随后改口"开始干吧"由本会话直接施工。 |
| 08-14 05:46 | UI 四验证项全部闭环（2026-08-14）+ CPU 采样验证法沉淀 | 验证结果（用户装 main 最新 APK 后）： |
| 08-14 10:35 | 模型组重设计施工完成，main = 2f9d0f1（2026-08-14） | 方案：/var/minis/shared/model-groups-redesign-plan.md（砍 Sub 概念 + 星形默认主组 + Agent Loop 文案收口）。 |
| 08-18 14:39 | T02+T09 只读审计完成（2026-08-18，B 会话，main@500c5fa） | 执行者 B 会话，报告 /var/minis/shared/rikkaminis-audit-2026-08-18/reports/T02.md 和 T09.md。 |
| 08-18 19:10 | RC17 拍板走 A（2026-08-18 晚） | 用户拍板 RC17 用 方案 A（备份 apiKey 维持 Base64 明文 + 加警示文案，不做口令加密）。已把 RC17A 补进 ROUND2-FIX-DISPATCH.md 为可执行 RC（纯文案改动）。 |
| 08-18 19:33 | RC17A 备份警示文案完成（2026-08-18 晚，独立会话） | 用户拍板走方案 A（备份 apiKey 维持 Base64 明文 + 加警示文案，不做口令加密）。本会话完成纯文案改动。 |
| 08-20 19:19 | 权限页「配置工具」开关文案本地化（2026-08-20 收尾） | 用户反馈：设置 → 权限 →「配置工具」分区里，开关标签显示的是英文技术名「允许 minis-config」，与中文界面（及「配置工具」分区标题）不协调。 |
| 08-26 17:41 | 人格(Soul)模块审计+加固完成（fix/soul-hardening → 分支 CI #1095 绿，未合 main 等拍板） | 用户要求审计「设置→人格」模块并修复/优化，6 个原发现 + 补测时又挖出 2 个真 bug，全部修完。 |
| 08-26 17:46 | MCP 模块审计修复完成（2026-08-26） | 用户要求检查设置里 MCP 模块并修 bug。审计了 MCPRepository/MCPIntegrationsScreen/SessionMcpsSheet/OAuth 四件套 + CLI（minis-mcp-cli）… |
| 08-28 00:59 | 任务 A（ChatViewModel i18n）完成（2026-08-28） | 分支 fix/i18n-chat-viewmodel，commit 470dea3c，基于 main@9105ff1，分支 CI run #1138 success（head 核实一致）。未合并 main（总控收口）。 |
| 08-28 01:13 | 收尾加固会话 C 完成：外围 i18n + a11y + rootfs 磁盘预检（2026-08-28，分支 fix/i18n-periphery-and-diskguard） | 状态：分支 CI 绿（run #1141 success，head=65010cb 核实一致），未合并 main（按任务书纪律等总控收口）。回报 /var/minis/shared/final-hardening-dis… |
| 08-28 01:32 | 任务 B 完成：chat 组件 i18n 改造（2026-08-28 凌晨） | 分支 fix/i18n-a11y-chat-ui，基线 main@9105ff1，两 commit： |
| 08-28 09:00 | 收尾加固整体闭环（2026-08-28 08:56，main=1cd58b9） | D（繁中补齐）合并完成：branch fix/i18n-zh-rtw-complete → 1cd58b9，仅碰 values-zh-rTW/strings.xml（+562 行，869→1482 key 全量补齐）。r… |
| 08-28 16:03 | 语言切换跳回聊天 bug 修复已合 main（2026-08-28，main=155aad0） | 分支：fix/lang-switch-nav-jump → 155aad0，分支 CI run 33151747788 success → ff 合并 main（75377e3..155aad0）→ release CI… |
| 08-28 19:50 | 语言切换跳回聊天 bug 最终根因 + 修复（2026-08-28，main=439c6c2，真机验证通过） | 第一轮（155aad0）用错 API，用户真机复现仍跳。第二轮（439c6c2）真正修复，用户实测「问题解决」。 |
| 09-19 17:30 | 09-19：offload 审计第 13 棒完成（Ring 2 第六段 = `config/` 包 4,963 行 / 20 文件全部走完 → F-134…F-140） | 锚点：c6d8d63f（= origin/main，未前进）· 扫描基线 99783703 |
| 09-19 17:31 | （接上条）F-136【D】i18n 门只查一个方向 —— scripts/scan/i18n_check.py Phas | （接上条）F-136【D】i18n 门只查一个方向 —— scripts/scan/i18n_check.py Phase 1 自述 "Orphan keys (code references but no defini… |
| 09-19 18:12 | 09-19：offload 审计第 14 棒完成（Ring 2 第七段 = `service/` 包 11 文件 3,459 行全部走完 → F-141…F-152） | 主题：service/ = 「进程级用户可见面」（前台服务常驻通知 / 悬浮胶囊 / 内存门 / 会话并发槽）。7 条 D 级发现里 6 条落在「同一决策的两套实现」或「声明 vs 实现」。 |

## 18. 上游 / 生态吸收 / 开源

**跨度** 2026-08-03 ～ 2026-09-21 · **58 条** · **状态** 常态（每轮吸收都有「不做」清单，比「做」清单更重要）

**叙事**：08-03 OpenMinis fork 起步 → 08-04 改名 RikkaMinis（OpenMinis 核 + RikkaHub 皮）→ 08-11 上游降级三件套 → 08-25 rikkahub 流畅性吸收 A–E → 08-31 LiteLLM 成本层 + facts → 09-06 Hermes Tier-1 harness → 09-07 Operit 吸收三件 → 09-12 差距清单收敛（D3/D4/D5 归档不吸收）→ 09-14/15 生态调查报告。**「吸收」有明确门槛：只吸收能接进现有边的东西，接不进的归档。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 16:35 | OpenMinis proot 源码构建（2026-08-03） | 用户关注点：从 APK 提取的 proot 二进制能否由开源仓库替代/改善。 |
| 08-03 17:28 | OpenMinis fork 恢复 proot 源码构建（2026-08-03） | 分支 feat/build-proot-from-source（commit 1506c14，已推送 GitHub）。 |
| 08-03 17:39 | OpenMinis fork proot 源码构建已上线（2026-08-03 完成） | 分支 feat/build-proot-from-source（1506c14）已快进合并到 main 并推送，CI run 30801684624 全绿 success。 |
| 08-03 19:15 | OpenMinis Android — PRoot loader 必须独立打包（重要排障结论） | 从源码编译 proot 时，必须把独立 loader 也装进 jniLibs，否则真机终端/shell 会在 ~20ms 内静默死亡（status=1，无输出）。 |
| 08-03 21:00 | OpenMinis Android — 空对话残留 bug 根因与修复（commit b194927，CI 30815328668 success） | 用户报「历史里出现空对话，自动删除时好时坏」。定位到根因链： |
| 08-03 21:47 | OpenMinis fork — 测试 backlog 清理 + 动态版本 + 上游同步（2026-08-03 进行中） | 分支 feat/test-backlog-version-sync（4 个提交 ca4a7e2/791e543/2b6ec3c/…），CI run 30819582646 验证中。 |
| 08-03 22:11 | OpenMinis fork — #2/#3/#4 全部完成（2026-08-03 晚，已合并 main e11eb44） | 2 测试 backlog 39 → 0 ✅ |
| 08-04 04:49 | OpenMinis fork — RikkaHub 风格左滑历史对话抽屉（2026-08-04 完成） | 分支 feat/chat-history-drawer（3 个提交 09bb392/80d73af/eb4893d），CI 全绿（30850745470、30851428249）。 |
| 08-04 05:23 | OpenMinis fork — UX polish 批量改动（2026-08-04，分支 feat/ux-polish） | 分支 feat/ux-polish（基于 feat/chat-history-drawer，2 个提交 1c28bf4 + 8c5bd58 文档），CI run 30853868293 success。 |
| 08-04 05:29 | OpenMinis fork — 仓库实况核查（2026-08-04 午后） | 用户问「项目被改到什么程度/整合了两个项目到什么程度」，用 git 全量核查，修正并补全此前的记忆： |
| 08-04 05:56 | OpenMinis fork — 设置页去箭头（2026-08-04，feat/ux-polish 分支追加） | 用户主张「很多箭头该去掉」，在 ux-polish 分支上继续追加两批去箭头提交（CI 全绿）： |
| 08-04 06:12 | OpenMinis fork 正式改名 RikkaMinis（2026-08-04） | 用户定名 RikkaMinis（OpenMinis 核 + RikkaHub 皮，Rikka 在前因用户对 rikkahub 作者好感更高、OpenMinis 作者曾被其认为气度差）。已全部执行： |
| 08-04 06:21 | ↳ RikkaMinis 改名善后完成（2026-08-04 补充） | 改名后当天完成全部善后： |
| 08-04 19:59 | vlc-android fork 性能修复 + 底部导航确认（2026-08-04 晚） | 仓库 OWNER/vlc-android（master 分支，commit b9f4eae）。 |
| 08-06 21:42 | RikkaMinis fork 曝光 + 代码规模量化（2026-08-06） | 两个 star 的来源（非推广，是 fork 网络被动引流） |
| 08-07 18:08 | RikkaMinis(Android fork) vs OpenMinis(iOS) 功能对比 — 2026-08-07 | 对比方法：两份代码都在本地（/tmp/official-openminis=9cf3a85 上游，/tmp/rikkaminis-full=75cd067 Android fork）。Android fork 几乎抄全了… |
| 08-08 03:00 | README 补充 RikkaHub credit — 扩写 UI 与交互逻辑来源（2026-08-08） | 用户提出：README 只把 RikkaHub 归为"交互设计（三个 UI 元素）"，但 UI 与交互逻辑的灵感实际都来自 RikkaHub，应一并 credit。 |
| 08-11 00:16 | 上游 fork 网络侦查（2026-08-11） | 用户问上游"接近400个分支"都是谁改了什么。查证：上游 OpenMinis/OpenMinis 仅 2 分支（main=9cf3a855fe + v1.10），387 个是 fork。全量比对 main HEAD 后：… |
| 08-11 00:58 | 吸收上游 fork 的降级三件套 — 实际只做了一件（feat/fix/length-wall-continue） | 用户按自己的标准（①shell/框架做不到的事 ②可验证 ③框架>功能）审查 17 个真改动 fork 的功能： |
| 08-11 01:29 | ↳ RikkaMinis 开发项目最终定档收尾（2026-08-11） | 给已有的《RikkaMinis-项目收尾总览.md》追加了"再补续——最终定档"章节（08-10 是"后记"，08-11 是真正句号）。定档内容： |
| 08-13 08:53 | 任务 A：BrowserRiskControl 移植完成（2026-08-13） | 从 OmniBot 上游移植了浏览器风险挑战检测 + throttle 控制到 RikkaMinis。 |
| 08-15 08:30 | 08-15 早上 com.openminis.app OOM 崩溃分析（3 次，08:25-08:27） | 现象 |
| 08-22 12:54 | RikkaMinis 官方对比自述文档完成（2026-08-22） | 生成了 /var/minis/workspace/RikkaMinis-官方对比自述.md，把 fork 相对官方 OpenMinis 的全部差异按三类归档： |
| 08-23 10:41 | TF-H 真机第二轮失败诊断（有 worker phase 证据，根因转到更上游） | 用户附最新测试日志 minis-2026-08-23__1_.log（10:30-10:32，主进程 31139）。4 次 stream offload 全 DIED_BEFORE_READY。这次拿到 worker 侧… |
| 08-25 19:47 | rikkahub 流畅性吸收 — 施工方案已产出 | 调研（rikkahub@3ebda54 vs RikkaMinis main@62a3a7d）落地成施工方案：/var/minis/shared/rikkahub-smoothness-absorption/施工方案.m… |
| 08-25 20:22 | rikkahub 流畅性吸收 — 施工方案已产出并派发就绪（2026-08-25） | 用户拍板要「具体可直接施工的方案」。调研（rikkahub@3ebda54 vs RikkaMinis main@62a3a7d）落地成 5 任务派发包，目录 /var/minis/shared/rikkahub-smo… |
| 08-25 20:49 | rikkahub 流畅性吸收 · 会话 B @Stable/@Immutable 纪律 — 完成（分支未合 main） | 任务：/var/minis/shared/rikkahub-smoothness-absorption/session-task-B.md，分支 fix/chat-stable-discipline（主号 OWNER，基… |
| 08-25 21:30 | rikkahub 流畅性吸收 · 会话 C 聚合 Item 生成器完成（分支未合 main） | 任务：/var/minis/shared/rikkahub-smoothness-absorption/session-task-C.md，分支 fix/message-node-item-generator（主号 OW… |
| 08-25 21:54 | 任务：合并 rikkahub 流畅性吸收 A/B/C 三分支到 main。基线 main 3eb1785，三分支各一 c | 任务：合并 rikkahub 流畅性吸收 A/B/C 三分支到 main。基线 main 3eb1785，三分支各一 commit 文件零重叠：A=0881981（测试基线）、B=c1925b3（SlashCommand… |
| 08-25 22:36 | rikkahub 平滑吸收 · D 任务开工准备完成（2026-08-25） | 用户确认 A/B/C 已解决，为 D（聚合 Item 渲染器 + 翻转开关，fix/message-node-item-renderer）做准备了。main tip = 2863f60（C 的 commit），前置核实全… |
| 08-26 00:13 | rikkahub 流畅性吸收 — A/B/C/D/E 全部完成并合入 main（2026-08-25 búi 收口） | 5 阶段全部合入 main（当前 main = 4829e67），收口会话独立核实（拉取 origin/main + Actions API 交叉核对 head_sha，非转述）： |
| 08-30 15:08 | RikkaMinis 收尾：安全止血 + 开源 + 封存（2026-08-30） | 用户诉求：开发收尾，把开发数据丢云端封存当备份 + 开源开发历史。过程中发现并处理了一个安全泄露。 |
| 08-31 00:21 | 输入框宽度对齐 RikkaHub（2026-08-31） | 用户反馈 RikkaMinis 聊天输入框比 RikkaHub 的窄，要求调成 RikkaHub 的宽度。 |
| 08-31 13:03 | 吸收开源 Agent 生态三件套之 ①③ 落地（2026-08-31） | 背景：用户给了 Mem0/LangGraph/E2B/Langfuse/LiteLLM 等开源项目清单，评估后拍板吸收三个增量：①记忆时间衰减 ③trace 回放评估（本会话直接做）；②实体/偏好结构化抽取（单独任务，未… |
| 08-31 13:13 | LiteLLM 吸收三件套 A+B+C 合并 main（2026-08-31） | 背景：用户调研 BerriAI/litellm（57.6k stars 开源 AI 网关），让我评估「能不能整合进 RikkaMinis」。结论：网关层（多租户/虚拟密钥/Redis/Terraform）对单用户 And… |
| 08-31 14:43 | LiteLLM 成本层 V2：JSON 价格表 + 用户可编辑价格（2026-08-31，commit fbe888e7） | 用户反馈 Usage 页看不到「预估费用」→ 根因：价格目录是硬编码 Kotlin map，只覆盖 40 个内置模型，中转站模型（deepseek-v4-pro-0813 之类）不在任何公共价格表里，按「未知→null→… |
| 08-31 14:44 | facts 任务收口：memory-facts + litellm-cost-json 双分支合并 main（2026-08-31） | main = c87df78b（ea6b9213 → fbe888e7[litellm] → c87df78b[memory-facts]），release CI run 33363933066 success（head… |
| 08-31 17:17 | 第二轮开源清单评估：12 项目裁定，A/B/C 待拍板 | 用户给了第二份 Agent 生态开源清单（Mem0/Zep/Chroma/LangGraph/AutoGen/CrewAI/E2B/Composio/Open Interpreter/Langfuse/Phoenix/L… |
| 09-04 13:43 | 上游 OpenMinis iOS 端差异分析（2026-09-04，/tmp/openminis sparse clone @ 4ef2900） | 背景：用户让查上游苹果版有什么 RikkaMinis 可吸收。上游是双端仓库（iOS 442 swift/23.2万行 vs Android 477 kt/18.5万行），iOS 功能面明显更全。 |
| 09-06 17:15 | Hermes Agent（Nous Research）吸收分析（2026-09-06） | 源码 /tmp/hermes/hermes-agent-main（20.6 万行 Python），报告 /var/minis/shared/hermes-agent-absorb-analysis.md。 |
| 09-06 19:09 | Hermes Tier-1 harness 四改动合并 main（2026-09-06 晚，main @ d7ee353e） | 分支 feat/hermes-tier1-harness → 分支 CI 34028554543 success（bridge 核对）→ ff 合并 main → push 触发 release CI（未等）→ 本地+远… |
| 09-06 19:40 | 会话交接（2026-09-06 晚）→ 交接文档 /var/minis/shared/hermes-tier1-handoff.md | main @ 1150e05f（tier-1 harness 四改动 + EOF 断流静默停修复，双分支 CI 绿 ff 合并）。main release CI run 34030771852 结论未等——新会话开场先查… |
| 09-07 15:14 | 文档更新 + Hermes 借鉴登记 + 小号同步（2026-09-07，main @ 5e81aa1a / alt @ 3e8dc9e6） | 任务：用户要求更新文档 + 同步小号，并把"这次新借鉴的东西"在文档做登记。 |
| 09-07 16:54 | Operit（AAswordman）吸收分析（2026-09-07） | 源码 /tmp/operit（浅克隆，会话级）。报告 /var/minis/shared/operit-absorb-analysis.md。7596 stars / LGPL-3.0 / app 模块 45.1 万行（… |
| 09-07 18:03 | Operit 吸收落地 2/3/6 三件闭环（2026-09-07，main @ a5346911） | Task 2 浏览器三件（已合并 main）：get_console_messages（onConsoleMessage→200 条环形缓冲）/ get_network_requests（shouldInterceptR… |
| 09-07 18:09 | Operit 吸收三件收尾（2026-09-07 终态） | 全部代码工作已完成，main @ a5346911（本地=origin/main，工作树干净）。 |
| 09-09 12:31 | fork 动态：Filterrr/RikkaMinis 深度自研（2026-09-09 用户问询） | 用户两诉求：①右滑弹出的会话 token 用量抽屉（TokenUsageSheet）数据不实时（尤其"总循环次数"）②Agent Runtime 预算上限调大：回合/Provider/工具/Shell 最大 500/51… |
| 09-10 01:46 | 上游痕迹清理评估 + 包名对齐方案（2026-09-10，待用户拍板） | 用户指令：去掉上游（OpenMinis）痕迹、与应用名 RikkaMinis 对齐，重点问包名工程量。用户已拍板 B 方案（改 namespace/包路径），并明确 不再同步上游（差异太大，改为按需"融合"，融合本身工作… |
| 09-10 03:10 | 包名迁移完成：com.openminis.app → com.rikkaminis.app（2026-09-10，main @ e24ca02） | 用户指令：方案 B（改 namespace/包路径）+ 不再同步上游（改按需"融合"）；批准后要求"直接合并、不用等"，因为最终会有一次统一审核环节。 |
| 09-10 12:03 | 挂载编辑页名称提示修复 + 输入框高度对齐 rikkahub（2026-09-10 下午） | 用户拍板：本会话收尾，验证工序交下个会话。交接文档 /var/minis/shared/composer-mount-ui-fix-handoff.md。 |
| 09-12 02:21 | A1 Prompt Cache 调查结论：本地已是上游超集，无需移植（2026-09-12） | 交接文档取证有误：e1a0b08 的 AnthropicProvider.kt 已有完整 cache_control 实现（ephemeralCacheControl + enhancedCache 5min/1h + … |
| 09-12 10:05 | 差距清单收敛完成：D1 攒着，D3/D4/D5 归档不吸收（2026-09-12） | D1 sanitizeUtf16 → backlog.md 第 2 条（参照 OmniBot AgentTextSanitizer.kt 63 行，宜与下次 provider 改动顺手带上） |
| 09-16 14:05 | 09-16 傍晚：热榜吸收攒进 backlog §22（用户拍板"攒着"）+ CI flake 处置 | 用户拍板：该吸收的不实施，攒着。已写进 /var/minis/shared/backlog.md §22（33KB，file_write append 成功）： |
| 09-16 14:45 | 09-16 傍晚：§22 热榜吸收三项落地（22a 阶梯 skill / 22b evals / 22c 债务扫描） | 用户拍板同批：vector_index.pkl 不打包（backlog §22「设计决定」已改为已拍板 + 触发重估条件 + 附带待办：下次动仓库 requirements.json 时补一行「首次使用需 build（需… |
| 09-16 16:48 | 产出 /var/minis/mounts/笔记/三家终端执行型项目对比RikkaMinis-2026-09-16.md（ | 产出 /var/minis/mounts/笔记/三家终端执行型项目对比RikkaMinis-2026-09-16.md（7 关节 × 4 代码库，file:line 级）。方法：fresh clone 四仓（OI 竟已转… |
| 09-19 00:25 | 09-19：小号 fork 同步断线 4 天修复（***ALT_USER***，非代码仓改动，直接 API 操作） | 情况：ALT_USER/RikkaMinis 的每晚同步 workflow（sync-fork-main.yml）09-15 起连续 4 天 failure，fork main 停在 09-14，lab 救援线过期。 |
| 09-21 14:55 | 09-21：上游 OpenMinis issue 对照核实（212 open issue → 21 条判定） | 用户指令：「去上游的 Issue 那你看一下，看看那里提的问题这个应用有没有？」 |
| 09-21 15:12 | 09-21：上游 issue 核实第二轮 —— 函数级探针 + 双向对照 + JVM 实测 | 用户指令：「先进行具体的核实检查，之后再决定修什么。」 |

## 19. UI 组件 / 设置页 / 交互微调

**跨度** 2026-08-04 ～ 2026-09-21 · **83 条** · **状态** 持续（09-12 参数化面板已合并 main）

**叙事**：贯穿全程的「小改动流」：08-04 设置页去箭头 + Chat Menu 三项 + 模型组拖拽排序 → 08-05 底部工具条可配置化（含 footer 按钮失效两轮定位）→ 08-08 移除冗余返回按钮 → 08-09 提供商详情页 v2 重构 → 08-14 聊天体验微调 + 回合组折叠 → 09-12 chat-tuning-panel 参数化。**这类改动单看都不重要，但它们占了档案的相当比例——产品的手感就是这么磨出来的。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-04 05:56 | OpenMinis fork — 设置页去箭头（2026-08-04，feat/ux-polish 分支追加） | 用户主张「很多箭头该去掉」，在 ux-polish 分支上继续追加两批去箭头提交（CI 全绿）： |
| 08-04 15:03 | RikkaMinis — 新建对话弹窗缺陷修复 + 收尾（2026-08-04 傍晚） | 用户报「对话进行中点顶栏铅笔（New Chat）会弹『停止对话并重新开始』确认框，明显不对；完结对话则直接创建」→ 定位为设计缺陷并修复。 |
| 08-04 15:27 | RikkaMinis — Chat Menu 设置屏三项修复（2026-08-04，commit 274709a 已合并 main，beta.59） | 用户报 3 个问题：①设置→外观→Chat Menu 里开关不即时反应；②拖动排序只能滑一格；③"Chat menu"英文突兀（未本地化）。 |
| 08-04 20:51 | vlc-android — 会话交接（2026-08-04 22:50，未完成事项） | 仓库 |
| 08-04 21:53 | RikkaMinis — 模型组页简化 + 语音 UI 移除（2026-08-04，分支 feat/...） | 仓库 OWNER/RikkaMinis，分支 feat/simplify-model-groups-remove-voice，commit 6cf806f9，CI run 30915989290 验证中。 |
| 08-04 23:27 | RikkaMinis — 模型组列表拖拽排序 + 排序机制统一（2026-08-04，commit 2f42573） | 分支 feat/reorder-model-groups（从 e8f7c27 起），CI run 30923457804 全绿 success（含 testReleaseUnitTest 全量），APK 12.78MB。… |
| 08-04 23:49 | RikkaMinis — 模型组拖拽排序已合并 main（2026-08-04 收尾） | commit 2f42573 已 ff 合并到 main 并推送。主构建 run 30925451151 全绿 success，release 资产 android-latest 的 RikkaMinis-0.22-pr… |
| 08-04 23:54 | RikkaMinis — 空菜单隐藏三个点按钮（2026-08-04，分支 feat/hide-empty-chat-menu） | 用户反馈：设置 → Appearance → Chat Menu 里把 8 个可定制项全关后，右上角 "..." 菜单为空（点开只剩条件性条目，平时都不显示）→ 按钮"失效"。需求：全关时三个点消失，铅笔（New Cha… |
| 08-05 00:04 | RikkaMinis — 空菜单隐藏三点已合并 main（2026-08-04 收尾） | commit 7b24b24 已 ff 合并 main 并推送，主构建 run 30926659989 全绿 success（head 7b24b24，16:03 完成）。release android-latest 资… |
| 08-05 08:17 | RikkaMinis — 底部输入工具栏可配置化评估（2026-08-05） | 检查 main @ ccf7291 的 ChatScreen：底栏由模型选择胶囊、条件性“退出编辑”、附件“+”菜单、固定的发送/排队/停止状态机组成，并非同质菜单项。结论：不建议照搬聊天“…”菜单做全自由隐藏/拖排；当… |
| 08-05 08:25 | 更正：用户所指“底部工具条”（2026-08-05） | 此前误解为聊天 composer 输入栏；用户实际指 ChatHistoryDrawer 历史抽屉 footer（当前 Token 用量左、设置右）。评估应以用户上传的《底部工具条可配置化—实施蓝图》为准，先前针对输入栏… |
| 08-05 08:58 | RikkaMinis 底部工具条可配置化 — 进度快照（2026-08-05） | 按用户上传的《底部工具条可配置化—实施蓝图》（历史抽屉 footer 可配置，非 composer）正在实施。 |
| 08-05 09:02 | RikkaMinis 底部工具条可配置化 — 进度更新（2026-08-05） | 已完成（1-8 步，95% 数据层 + UI 组件 + 资源） |
| 08-05 09:56 | RikkaMinis 底部工具条可配置化 — 两处 bug 修复 + actions 升级（2026-08-05） | 分支 feat/customizable-chat-footer 现含 3 个提交：fe16ebc（原始实现）+ fe1bf65 + 449af48，全部 CI 绿（30966766825、30967414758）。未合… |
| 08-05 10:01 | RikkaMinis 底部工具条 — 真机发现 footer 按钮失效，根因 = LaunchedEffect 自取消（2026-08-05） | 用户真机验收发现：历史抽屉 footer 里 Token 用量 / 设置两个按钮（默认唯一两个）点击无反应。 |
| 08-05 10:13 | RikkaMinis footer 按钮仍无效 — 静态逻辑已穷尽的排障备忘（2026-08-05 进行中） | 用户真机：历史抽屉 footer 的「Token 用量」「设置」两个按钮仍无效，且因此无法进入设置页。前两个修复（自取消 LaunchedEffect → historyDrawerScope.launch）后依然无效（… |
| 08-05 10:24 | RikkaMinis footer 按钮失效 — 真正根因 = dispatch 等 close() 挂起（2026-08-05，commit 599fe97） | 用户复验 5b54408 仍失效（v220000091）。最终根因不是 LaunchedEffect 自取消（那个也修了），而是 dispatch 顺序： |
| 08-05 16:11 | RikkaMinis 小米灵动岛适配 — 已废弃回滚（2026-08-05 收尾） | 用户决定废弃（"不起作用就废弃，回滚到之前没干这个的状态"）。 |
| 08-05 17:33 | RikkaMinis 历史输入列表（Input History）功能完成 — 已合并 main（2026-08-05） | 用户要在 RikkaMinis 参考 rikkahub 右上角"Chat Options"加历史输入面板。已全部完成并合并进 main。 |
| 08-06 11:06 | RikkaMinis 经验记忆修复完整方案（可直接开工版，基线 main@87f69eb） | 目标：让经验的检索/验证/回写在多会话并发、排队切换、取消、异常、清空下保持同一任务语义。原则：一次经验交换必须有稳定身份和明确生命周期，禁止再用"最后一条消息"和文件行号猜测。 |
| 08-06 17:35 | RikkaMinis — fallback 已合并 + 提供商"常用"固定功能（2026-08-06 晚） | fallback entry 精度（1f01a36）已合并 main |
| 08-06 18:40 | RikkaMinis 记忆页改造 — feat/memory-management-optimize（2026-08-06 晚，CI success run 31093633313，commit a26eaeb） | 用户真实需求（对齐后） |
| 08-06 21:13 | README 文档同步（2026-08-06 收尾补）— main 已到 ebb4e11 | 下午误判"今天的改动不涉及 README 描述变更"，用户提醒后补充：今天确实有两个用户可见新功能值得记进 README。 |
| 08-07 08:31 | RikkaMinis 审计修复（2026-08-07，分支 fix/audit-2026-08-07） | 基于 08-04~08-07 系统审查的实施批次，已推送分支 + 触发 CI。 |
| 08-07 13:12 | RikkaMinis「重启后进入历史会话列表页而非新会话」根因（2026-08-07） | 用户预期：设置里选了"启动 New Chat"，打开 app 应直接进聊天对话框。现象：重启后停在历史会话列表页（HOME）。 |
| 08-08 01:12 | RikkaMinis 提供商管理优化施工 — Phase 1+2 完成（2026-08-08 凌晨） | 分支 |
| 08-08 03:00 | README 补充 RikkaHub credit — 扩写 UI 与交互逻辑来源（2026-08-08） | 用户提出：README 只把 RikkaHub 归为"交互设计（三个 UI 元素）"，但 UI 与交互逻辑的灵感实际都来自 RikkaHub，应一并 credit。 |
| 08-08 03:17 | RikkaMinis provider 行内星标改造 — 已合并 main（2026-08-08） | 用户反馈:provider 列表"设为常用"要先点三个点(MoreVert)弹菜单再点星号,多余。改为行内直接放星号按钮,点击即切换常用。 |
| 08-08 10:53 | 固定会话 Pin 按钮位置修复 — 移到行最右 | 用户反馈：历史抽屉固定会话的 PushPin 按钮放错位置——原来在标题列和时间中间（图标 \| 标题 \| Pin \| 时间），应放到最右边（图标 \| 标题 \| 时间 \| Pin）。 |
| 08-08 11:52 | 移除冗余关闭/返回按钮 — 已提交推送，CI 验证中（2026-08-08） | 分支 feat/remove-redundant-close-buttons（commit 8e4bf92，基于 feat/recovery-strategy HEAD 9874a4f） |
| 08-08 11:59 | ↳ 四个待合并分支已全部合并 main（2026-08-08，HEAD b000e31） | 合并方式：feat/recovery-strategy(9874a4f)、feat/remove-redundant-close-buttons(8e4bf92) 线性 ff；perf/immutable-chat-mo… |
| 08-08 12:27 | RikkaMinis Provider 列表点击卡顿修复（2026-08-08） | 用户反馈「设置 → 大模型提供商列表，点任意 provider 行轻微卡顿」。 |
| 08-08 12:35 | ✅ Provider 列表点击卡顿修复 — 已合并 main（2026-08-08） | 分支 perf/provider-list-click-latency @28dcb03： |
| 08-08 15:07 | ✅ compact/thinking 从斜杠提到顶层菜单 — 已合并 main（2026-08-08） | 分支 feat/menu-compact-thinking @5a3640d（13 文件 +152/-54）： |
| 08-08 18:09 | 模型选择器改圆形按钮（feat/circular-model-picker）2026-08-08 | 用户反馈"对话框显示模型名称的应该改成圆的，为什么还是原来的样子"。 |
| 08-09 06:40 | OOM 闪退 — 终端反复开关 12 次导致内存耗尽（2026-08-09） | CI #305 正在构建中（e628206，修了清屏 Ctrl+L + 换 bash）。用户测试 #304 时 7 分钟内开关终端 12 次，PRoot 子进程反复创建/销毁，Scudo 内存分配器耗尽（internal… |
| 08-09 14:57 | 供应商详情页 v2 重构（feat/provider-models-manage，CI run 31299697758 success） | 用户对 v1（整屏管理页 + 大刷新行 + 搜索框有字）反馈"别扭"，定了新布局： |
| 08-09 17:12 | 模型组「继续上一个」说明移到卡片下小字（2026-08-09） | 用户诉求：模型组详情页 → Recovery Policy 卡片第一项，"停留在回退模型（继续上一个）"——括号里的说明不该写在选项标题里，说明应放卡片下方 footer 小字。 |
| 08-10 09:29 | feat/attach-menu-visibility-order 完成（2026-08-10） | 分支已合并 main，CI 构建成功 |
| 08-10 11:10 | 图标自动跟随系统主题修复 + 通知横幅精简（2026-08-10） | 分支 fix/icon-auto-follow-system → 已合并 main，CI success |
| 08-11 00:30 | 任务完成弹窗大图标修复（fix/notif-drop-large-icon → main 15888dc） | 用户报任务完成弹窗"左边小图标+右边大图标"。根因：2026-08-10 删 FGS 大图标时只处理了 AgentForegroundService.kt，漏了 BackgroundTaskNotifier.kt（任务完… |
| 08-12 12:37 | 会话存储回收功能完成（feat/session-storage-reclamation → main bfd621c，CI 双绿） | 用户报本地设置存储页看到工具类对话体积接近 200MB，与备份 OOM（ConfigBackup.export 打包会话内容）同源——会话目录无约束累积、无自动回收。 |
| 08-13 07:02 | 任务 C：组件渲染测试 — 完成 | 分支：fix/component-render-tests → main 95f042b（CI run 31648578397 ✅ success） |
| 08-14 00:03 | 备份模块审计完成 — backup-module-audit-plan.md（2026-08-13） | 用户要求审计备份模块（不动代码，出方案给其他模型施工）。审计产物：/var/minis/workspace/backup-module-audit-plan.md（21KB，11 个问题 + 分支规划 + 验证清单）。 |
| 08-14 09:57 | 聊天体验微调三分支施工完成，main = c2666e7（2026-08-14） | 用户委托施工 chat-ux-polish-plan.md 方案（/var/minis/shared/chat-ux-polish-plan.md，四问题方案文档）。施工结果（3 分支全部 CI 绿 → 合并 main … |
| 08-14 10:07 | 供应商模型列表"默认没有+实时刷新"改造完成（2026-08-14） | 问题：添加供应商后默认出现一批过时模型（"变化太快"）。根因：refreshModels 在 API 空/无 key 时 fallback 到 ModelsDevApi.fetchModels()（48h TTL + 3… |
| 08-14 11:04 | 滚动体验三问题：A+B 施工中，D 已交接（2026-08-14 上午） | 任务来源：用户反馈聊天界面三问题：①滚动必须"很直"才能滑 ②内容上下跳 ③思考栏/工具栏形态。 |
| 08-14 11:11 | ↳ A+B 已合入 main 3c95878（2026-08-14 上午，更新） | A（表格折叠）+ B（工具行动画）分支 fix/scroll-ux-table-fold-animate 已合入 main（3c95878），分支已删（远端 204 + 本地 -D） |
| 08-14 11:35 | 未验证清单汇总（2026-08-14，等 cce2a10 release 一起验证） | 用户要求"把还没验证的都整理出来，一起验证"。清单文件：/var/minis/shared/verification-checklist-2026-08-14.md（29 项 + 参考）。 |
| 08-14 11:39 | 全量验证通过（2026-08-14 用户确认） | 用户装 cce2a10 release APK 后，验证清单 29 项全部通过： |
| 08-14 12:11 | ↳ 回合组默认折叠 + 移到回答上方 — 全链闭环（2026-08-14，main = 4ad1533） | 分支 feat/run-group-manual-collapse CI 绿（run 31767903822 success）→ ff 合并 main → push main（4ad1533）→ release 构建 r… |
| 08-14 19:37 | 模型组策略重构 P1-P4 全部合并 main（2026-08-14，main = 18f6e95） | 最终状态：P1（596adc2 另一会话）+ P2（49a38e7）+ P3（caf0828）+ P4（18f6e95）全部 ff 合并 main 并推送。远端仅 main 分支。release 构建 run 31796… |
| 08-15 10:21 | 可并行清单任务 1（C 类组件渲染测试）= 已完成项，无需施工（2026-08-15 上午） | 核查结论：任务 C 的交付物 08-13 就已合并 main，派发清单状态未同步导致被重复派发。 |
| 08-15 10:21 | ↳ T10 验收准备完成 — 验收矩阵已交付（2026-08-15 下午） | 任务 3（可并行清单）完成：纯文档任务，未改代码、未跑测试。 |
| 08-18 01:56 | 上下文窗口来源治理 + 组为准 + iOS-parity 上下文已满弹窗(fix/context-window-sources) | 用户痛点(A + C): |
| 08-20 19:19 | 权限页「配置工具」开关文案本地化（2026-08-20 收尾） | 用户反馈：设置 → 权限 →「配置工具」分区里，开关标签显示的是英文技术名「允许 minis-config」，与中文界面（及「配置工具」分区标题）不协调。 |
| 08-21 16:03 | native OOM「进程隔离 + 自动划卡片」修复闭环（2026-08-21） | 背景：用户报内存仍会飙到"拒绝运行指令/工具"，但能靠后台划卡片恢复。用户决定改，参考了工业界三方案（多进程用完即弃 / AVF 微虚拟机 / 端云分离），确认只有「多进程用完即弃」可行（AVF 需 Pixel+厂商签名… |
| 08-28 01:32 | 任务 B 完成：chat 组件 i18n 改造（2026-08-28 凌晨） | 分支 fix/i18n-a11y-chat-ui，基线 main@9105ff1，两 commit： |
| 08-31 00:21 | 输入框宽度对齐 RikkaHub（2026-08-31） | 用户反馈 RikkaMinis 聊天输入框比 RikkaHub 的窄，要求调成 RikkaHub 的宽度。 |
| 08-31 20:40 | 卡死诊断：冷启动进 chat 界面卡死 ~10s（2026-08-31，minis-2026-08-31.log） | 用户报"装更新后整个应用卡死一段时间"。日志分析结论（证据链完整）： |
| 09-04 09:37 | 子代理设置行 UI 修复（2026-09-04，已合并 main @ 07d63699） | 用户反馈：设置页「子代理派发」副标题太长占 3 行（其他设置项都 1 行），要求压到 ~18 字符内。 |
| 09-06 18:16 | 负载均衡请求级改造 + 停止按钮竞态修复全链路闭环（main @ cd32795，分支 CI run 340260822 | 负载均衡请求级改造 + 停止按钮竞态修复全链路闭环（main @ cd32795，分支 CI run 34026082245 head_sha 核对一致后 ff 合并，release CI 34026796848 in_… |
| 09-07 12:26 | Runtime Limits 面板分支推送（2026-09-07，分支 feat/runtime-limits-panel @ d908e90） | 用户需求三连：①64→128 预算是否硬编码（答：是，ChatAgentTraceObserver const）②抽出来变可调 ③"能调的都放进去"，以现值为默认；UI 要求：Settings 列表描述保持简短，详细说明… |
| 09-07 13:07 | Runtime Limits 面板全链路闭环（2026-09-07，main @ 059aa66） | 已合并 main 并收尾：分支 feat/runtime-limits-panel 三提交（d908e90 主体 + 4f52adb OpenAIProvider rebase 修复 + 059aa66 MinisTex… |
| 09-08 17:27 | 自动备份 A+B 审计修复 + 合并 main（2026-09-08，main @ 568d87a） | 流程：新会话开场查交接文档 + 分支 CI（run 34205678023 f8a2372 已 success）→ 本地全量审计（17 文件 1108 行）→ 发现 2 真 bug 修复 → 分支 CI run 3420… |
| 09-10 07:42 | 「RikkaMinis Computer」详情面板输出错位根因（2026-09-10 排查） | 现象：shell 输出在工具详情面板里时不时在单词中间断行（"有的错位有的不会"），绿色输出行长度从 3 到 40+ 字符不等，与行宽无关。 |
| 09-10 12:03 | 挂载编辑页名称提示修复 + 输入框高度对齐 rikkahub（2026-09-10 下午） | 用户拍板：本会话收尾，验证工序交下个会话。交接文档 /var/minis/shared/composer-mount-ui-fix-handoff.md。 |
| 09-11 10:59 | 三处改动审计：两次已合并 + fix/key-roulette-refresh（全过，分支待合并） | 审计结论：三处均无 bug。 |
| 09-15 19:00 | 09-15 晚：dev-history 主题索引（SAGAS）落地 —— main = 5363177 | 用户贴了一份外部评审（"你的档案是一条河，不是一张图"），认为有道理。核实后同意诊断，并直接实施。 |
| 09-15 19:06 | 09-15 晚：SAGAS 主题索引固化进 skill（v2.1.0）—— main = 5c9ff85 | 用户问「有没有固化下来，以后可能还会用」→ 核查后发现三个真实缺口并全部补上。 |
| 09-17 18:13 | 09-17 傍晚：存储页转圈 + markdown 列表误渲染双修复 → main = 40a58c94 | 用户报告两件事，都实锤： |
| 09-17 20:18 | ↳ 09-17 收尾：全天工程量统计（用户问"为什么感觉工程量大"时的硬数据） | main 交付量（SGT 09:41→17:55）：33 提交 / 147 unique 文件（全仓 ~524 文件的 28%）/ +3031 −577 行；CI 今天 43 轮构建（29 绿 / 9 红 / 5 取消，… |
| 09-17 21:01 | 09-17 深夜：★"双胞胎解析器"——聊天列表误渲染的真正根因与修复（main = 14ca90e3） | 事件：用户真机复现"4. 分钟"（昨晚 40a58c94 记的"已修"无效）→ 追查发现 app 有两套 markdown 解析器： |
| 09-17 21:04 | 09-17 深夜收口：聊天列表误渲染修复真机验证通过（main = 14ca90e3） | 用户装机 1.0.0+1630（lastUpdateTime 21:02:03，= CI #1630 artifact / 14ca90e3 树）后确认：样本行显示为普通段落，符合预期 → "双胞胎解析器"修复实锤生效。… |
| 09-19 19:59 | 09-19：offload 审计第 16 棒完成（P1 = `backup/` + `diagnostics/` + `logging/` + `crash/` → F-161…F-169） | 状态：✅ 只读，仓库 0 改动（HEAD = c6d8d63f = 锚点，git status --porcelain 空）· 判据 verify_findings_16th.sh 90/90 · 总闸门 verify_… |
| 09-20 01:52 | 09-20：offload 审计 WAVE-2 · B4 线完成（`ui/settings/` 42 文件 / 18,653 行） | 交付（/var/minis/shared/offload-audit-0919/wave2-b4/）：report.md(25.8KB, 7 章, 含 42 文件覆盖表) · verify_b4.sh 55/55 绿 ·… |
| 09-20 02:07 | 09-20 第 21 棒 · 并发线 B2 完成（`data/repository/` 11 文件 / 7,101 行） | 产出：/var/minis/shared/offload-audit-0919/wave2-b2/ —— report.md（含覆盖表）· ledger-b2.json（25 条：D=6 · O=10 · N=9）· v… |
| 09-20 03:26 | 09-20：offload 审计 WAVE-2 并发线 C2 完成（`ui/chat/` 渲染与文本组件） | 身份：第 21 棒并发线 C2（只读，仓库 0 改动，HEAD = 99783703 / 锚点 c6d8d63f）。 |
| 09-20 05:03 | 09-20：offload 审计修复批次 FIX-4-render-ui 完成（渲染/UI 组件层） | 产出：/var/minis/shared/offload-audit-0919/fix-out/fix4/ —— REPORT.md · FIXED-F-255/256/257/262/270…277.md（12 份，各… |
| 09-20 08:48 | 09-20：FIX-6 修复批完成并合入 main（设置/配置 9 条 + F-224 数据层收尾） | 最终 main = b67558d。两个阶段： |
| 09-20 17:10 | 09-20 晚：隐藏设置页 Agent Runtime 里的终端行 → 分支 `chore/hide-settings-terminal-row`（CI 绿，**按用户要求不合并**） | 用户判断：设置 → Agent Runtime 里的「终端」行冗余——真要用，外观 → 聊天菜单里已经能把它放到右上角菜单/抽屉底栏。去掉。 |
| 09-20 17:14 | 09-20：键盘遮挡编辑内容（IME occlusion）—— 诊断完成，交接给新会话 | 用户报告：技能编辑 / 记忆编辑中，点击后输入法键盘直接盖住正在编辑的内容；并要求排查所有编辑面。 |
| 09-21 10:27 | 09-21：记忆文件查看卡顿排查 → 根因是编辑路径无懒加载（整份文件塞进一个 BasicTextField） | 用户报告：记忆中单个文件超 ~130KB 后上下滑动严重卡顿。 |

## 20. Token 用量 / 成本统计

**跨度** 2026-08-05 ～ 2026-09-22 · **54 条** · **状态** 已闭环（09-09 实时用量 + 预算上限；09-12 parseUsage 修复合并）

**叙事**：08-06 TokenUsageSheet 补缓存命中率 → 08-08 双重扣减 + Gemini cache 修复 → 08-25 用量统计优化 A+B → 08-31 LiteLLM 成本层 V2（JSON 价格表 + 用户可编辑）→ **09-12 parseUsage 双重扣减再次出现**（同族复发）。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-05 08:58 | RikkaMinis 底部工具条可配置化 — 进度快照（2026-08-05） | 按用户上传的《底部工具条可配置化—实施蓝图》（历史抽屉 footer 可配置，非 composer）正在实施。 |
| 08-05 10:01 | RikkaMinis 底部工具条 — 真机发现 footer 按钮失效，根因 = LaunchedEffect 自取消（2026-08-05） | 用户真机验收发现：历史抽屉 footer 里 Token 用量 / 设置两个按钮（默认唯一两个）点击无反应。 |
| 08-05 10:13 | RikkaMinis footer 按钮仍无效 — 静态逻辑已穷尽的排障备忘（2026-08-05 进行中） | 用户真机：历史抽屉 footer 的「Token 用量」「设置」两个按钮仍无效，且因此无法进入设置页。前两个修复（自取消 LaunchedEffect → historyDrawerScope.launch）后依然无效（… |
| 08-05 14:45 | GitHub 小号 ***ALT_USER*** 满权限 token 已验证（2026-08-05） | 用户给了沙箱第二个 GitHub token：GITHUB_TOKEN_FULL_RIGHT（环境变量），归属账号 ALT_USER（ID 313291818，2026-08-05 注册，free 计划，无 2FA，无仓… |
| 08-05 15:01 | CF 小号技能化 + token 安全迁移完成（2026-08-05） | Cloudflare 小号 token 已从明文迁移到环境变量 |
| 08-06 00:16 | TokenUsageSheet 补上缓存命中率（2026-08-06） | 用户发现 Settings → Usage Stats 有缓存命中率，但对话中 ⋮ → Token Usage 底部 sheet 没有。 |
| 08-08 01:01 | Token 用量修复施工完成（2026-08-08，分支 fix/token-usage-double-count-and-gemini-cache） | 施工内容（commit e89edd3，2 文件 +16/-6，已推远程 + CI 已触发） |
| 08-08 01:27 | 统筹合并完成 — provider/memory/token 三分支合 main + CI 修复（2026-08-08） | 背景 |
| 08-08 01:48 | ↳ 未合并分支已全部合并到 main（2026-08-08） | feat/logging-module-optimize (092ae88) — 日志模块优化：降低 flush 节奏、修日志读取 OOM、容量上限、i18n crash 区块 → ff 合并 main |
| 08-08 09:46 | RikkaMinis main 分支完整合并梳理（2026-08-08） | 远程状态 |
| 08-13 04:17 | 任务 2 完成：OAuth Token 存储安全检查（fix/oauth-secure-storage） | 审计结论：OAuth token 存储已是加密的，无需修改加密方案。 所有 token 存储路径均使用 EncryptedPrefsFactory.safeCreate() → EncryptedSharedPrefer… |
| 08-15 05:54 | T6 派发指令 — Trace 扩展为预算和终态证据 | 你负责 RikkaMinis 平衡点施工 T6 — Trace 扩展为预算和终态证据。 |
| 08-15 05:54 | T7 派发指令 — Agent Run 主链路渐进接入 | 你负责 RikkaMinis 平衡点施工 T7 — Agent Run 主链路渐进接入。 |
| 08-15 08:32 | 从 08-15 OOM 崩溃暴露的架构问题与改进待办 | 架构问题 |
| 08-18 01:56 | 上下文窗口来源治理 + 组为准 + iOS-parity 上下文已满弹窗(fix/context-window-sources) | 用户痛点(A + C): |
| 08-25 09:00 | Token 用量统计优化 A+B 已派发（2026-08-25） | 用户拍板方案 A（归属正确性）+ B（聚合性能+体验），已写好两份自包含任务书派发： |
| 08-25 11:15 | Token 用量统计优化 A+B 已完成合并 main（2026-08-25） | 最终 main = cccc235（基线 85e7b29），release CI run 32803462449 success（head_sha 核实一致）。两个分支已删。 |
| 08-25 11:30 | ↳ fix/shell-cancel-uncaught-exception 已合并 main（2026-08-25） | 修 shell_execute/browser_use 工具执行吞掉 CancellationException 导致 UI 永久转圈的 bug。 |
| 08-27 15:26 | tokenrhythm.studio deepseek-v4 思考模式打不开 — 根因定位（2026-08-27） | 用户现象：tokenrhythm.studio 这个中转（key sk_tr_...，OpenAI 兼容 /v1/chat/completions + Anthropic /v1/messages）配的 deepseek… |
| 08-27 16:19 | tokenrhythm deepseek-v4 思考模式修复已合并 main（2026-08-27 收尾） | 修复提交：cb434c5 fix(provider): route third-party deepseek-v4 relays through standard reasoning_effort（已 ff 合并 mai… |
| 08-31 13:13 | LiteLLM 吸收三件套 A+B+C 合并 main（2026-08-31） | 背景：用户调研 BerriAI/litellm（57.6k stars 开源 AI 网关），让我评估「能不能整合进 RikkaMinis」。结论：网关层（多租户/虚拟密钥/Redis/Terraform）对单用户 And… |
| 08-31 14:43 | LiteLLM 成本层 V2：JSON 价格表 + 用户可编辑价格（2026-08-31，commit fbe888e7） | 用户反馈 Usage 页看不到「预估费用」→ 根因：价格目录是硬编码 Kotlin map，只覆盖 40 个内置模型，中转站模型（deepseek-v4-pro-0813 之类）不在任何公共价格表里，按「未知→null→… |
| 08-31 16:57 | 砍除 USD 成本估算 + 修复 facts 空时间戳（2026-08-31，commit b4e166fb） | 背景：用户真机验证「Usage 页费用显示有的有、有的没有」。定位：价格目录 model_prices.json（44 键）只解析到 1148 个实际 model_id 中的 75 个，其余 1073 个中转站/代理模型… |
| 08-31 23:03 | 备份超限修复：字节预算线性裁剪（2026-08-31 晚，commit 93773448 合并 main） | 问题：用户手动全量备份报 Backup too large (72658077 chars, max 67108864)——72MB 顶爆 64MB 上限，导出直接 throw，全有或全无。用户用「聊天窗口=0」验证成功… |
| 09-03 10:56 | FE-5 第五批第四簇（slash/token）完成（2026-09-03） | 进度：ChatViewModel 4334 → 4065（−269，累计 FE-5 −8273，约 67%）。目标 3500-4000，还差 ~65-565 行。 |
| 09-03 12:11 | ↳ FE-5 第五批全部拆完（2026-09-03 会话收尾） | 终态：ChatViewModel 4334 → 3581（本会话第四/五/六簇 −753，累计 FE-5 −8757，约 71%）。已进入目标区间 3500-4000。 |
| 09-04 13:17 | 思考字段决策键根治（tokenrhythm qwen 报错，2026-09-04） | 用户场景：tokenrhythm.studio + qwen3.8-max，开思考就报错（低档也报错），关思考没事。RikkaHub 不报错。 |
| 09-06 12:09 | 知识图谱全量灌入完成 + LLM 切换到 tokenrhythm（2026-09-06 中午） | 图谱现在：3627 实体 / 4739 关系（从今天早上的 185/153 爆发式增长，覆盖 2026-07-31 ~ 09-06 全部 798 条日志），备份 20318 条记录。 |
| 09-06 17:15 | Hermes Agent（Nous Research）吸收分析（2026-09-06） | 源码 /tmp/hermes/hermes-agent-main（20.6 万行 Python），报告 /var/minis/shared/hermes-agent-absorb-analysis.md。 |
| 09-07 01:31 | provider-exec-concurrency 分支（A+B 多会话并发）+ 突然停第4形态（预算墙）双修复 | 分支 feat/provider-exec-concurrency（未合并 main），三提交： |
| 09-07 01:54 | provider-exec-concurrency + 预算墙修复合并 main（2026-09-07 凌晨，main @ 303d375f） | 全链路：分支 feat/provider-exec-concurrency 四提交（1e20e04b 预算墙+slot池 → 8eeaccba 队列可见性 → 644a36df JDK Semaphore 修复 → 30… |
| 09-08 13:05 | 环境变量分组 + Sheet 修复（2026-09-08，分支 fix/envvar-flatten-usage-sheets @ 85698cd，已交接未合并） | 任务：①删平台特殊卡片（用户明确"也是要砍的"）②环境变量分组功能（用户澄清"分组指的是环境变量中的分组，解决变量太多乱的问题"——首轮理解偏差：我误以为分组也不要，实际只要砍平台卡片）③Token 用量抽屉顶太高（fi… |
| 09-08 13:40 | 环境变量分组分支审计+修复合并 main（2026-09-08，main @ fa87964） | 任务：用户要求审计 fix/envvar-flatten-usage-sheets（4 commit）有 bug 修完再合并。 |
| 09-09 00:04 | Agent 循环预算统一调高（2026-09-09） | 用户嫌之前预算调太低、容易撞墙，统一调高（minis-config set-batch 一次确认生效）： |
| 09-09 00:05 | Agent 循环预算统一调高（2026-09-09） | 用户指令：事实使用中发现多端自动同步无法发挥作用、多数时候是负作用，砍掉。 |
| 09-09 08:29 | 程序默认预算调高合并 main（2026-09-09，main @ 2a184cd） | 用户问"fork 的又有一个开始改了"。查 forks API（sort=newest）确认：Filterrr/RikkaMinis 在深度自研（ahead 946 / behind 965，diverged；08-21… |
| 09-09 12:31 | fork 动态：Filterrr/RikkaMinis 深度自研（2026-09-09 用户问询） | 用户两诉求：①右滑弹出的会话 token 用量抽屉（TokenUsageSheet）数据不实时（尤其"总循环次数"）②Agent Runtime 预算上限调大：回合/Provider/工具/Shell 最大 500/51… |
| 09-09 12:46 | Token 用量实时 + 预算上限 1000/6h（2026-09-09，分支 fix/runtime-limits-ux @ ec1e5d8，未合并） | 流程：分支 CI run 34310631679 success（head_sha=ec1e5d8 API 双核对）→ 全量审计（diff 3 文件 +18/−12）无 bug → ff 合并 main @ ec1e5d… |
| 09-09 20:09 | Token 用量「总循环次数」不实时 — 根因与修复（2026-09-09，分支 fix/token-usage-live-loop-count @ cd360fd，CI 绿，未合并） | 用户症状：会话 Token 用量 → Agent Loop → 总循环次数，运行期间一直显示 1，暂停/结束才一次性跳到真实数。上一轮（ec1e5d8）改的 1s 轮询没治好。 |
| 09-09 20:16 | Token 用量总循环次数实时化 — 合并收尾（2026-09-09，main @ e7ff5ad） | 流程：分支 CI run 34348101463 success（head cd360fd 核对）→ 期间 main 前进到 842b3d4（并行会话 agentloop 修复已由用户合并）→ rebase 到新 mai… |
| 09-09 20:27 | 全局第二轮审计收口 + HIGH 修复闭环（2026-09-09 晚） | 第二道门核实（reports/FINAL-closure.md）：10 域 498 文件 / 169,059 行，5 HIGH + 34 MEDIUM + 55 LOW。5 条 HIGH 逐条独立核实： |
| 09-10 03:34 | 第三轮复查完成：B1-B22 + token + 包名重构，无 bug（2026-09-10 深夜） | 第三轮复查完成：B1-B22 + token + 包名重构，无 bug（2026-09-10 深夜） |
| 09-11 11:38 | 思考泄漏取证：中转站自报 reasoning_tokens 但零 reasoning 字段（2026-09-11，用户一线样本） | 用户指出"当前这个会话本身就是典型案例"——他通过第三方中转站调用当前模型，且该问题只在这个模型上出现。 |
| 09-12 02:24 | A1/A2 双双归档 + parseUsage bug 进 backlog（2026-09-12 收尾） | 用户拍板：A1 parseUsage bug「先攒着」（选 2=backlog），A2 一起调查后一起处理 |
| 09-12 02:45 | parseUsage 双重扣减 bug 已修复合并（main @ 8e98166，2026-09-12） | 用户拍板修（backlog 第 3 条），分支 fix/anthropic-parse-usage @ 8e98166 |
| 09-12 22:13 | 参数化两批全部完成：feat/chat-tuning-panel @ 70a0f5c（2026-09-12 晚） | 两批 23 个参数已全部实现并推送（同一分支两个 commit，一次 CI 覆盖）： |
| 09-12 22:30 | ↳ feat/chat-tuning-panel 合并收尾（main @ 70a0f5c，2026-09-12 晚） | 审计（合并前用户要求）：29 文件 +1891/−89 两 commit 全过——①ImageBudget 重构默认 (2000,80) 逐项复刻旧阶梯（整数因子无浮点截断，JVM 钉住）②16 新键 Prefs 全配 … |
| 09-14 16:16 | 09-14 晚：截断工具调用守卫落地（fix/truncated-tool-call-guard @ fbc50cdd，CI run 1517）+ Eta 调研两条修正 | 改动（3 文件 +217 行，零删除，未合并待拍板）：①新建 ui/chat/TruncatedToolCallPolicy.kt（74 行纯函数：7 个截断 finish reason 别名 length/max_ou… |
| 09-19 19:04 | 09-19 晚：压缩修复第二棒 —— 长工具循环的预算锚点（写侧+读侧）→ main = afa404bf | 背景：第一棒（796307ec，F1/F2/F4）修好了锚点判定 + 诊断 + 硬裁剪兜底，但「一句指令 + 几百轮工具调用」会话只有一个用户轮次，而整套压缩逻辑按「用户轮次」计价 ⇒ 两侧同时退化。 |
| 09-19 22:05 | 09-19：第 19 棒中途（`provider/` 网络与预算面）→ F-180（D）+ O-47 + N-26 + **勘误 E-18（撤回 O-44）** | ★ 勘误 E-18（重要，方法论级）：第 18 棒我写进台账的 O-44 我误判了 —— 声称 ToolJsonRepair.levenshteinAtMostOne（:124-155） |
| 09-20 00:57 | 09-20 凌晨：offload 审计 WAVE-2 线 B3 完成（`data/` 除 `repository/`，60 文件 / 8,744 行） | 状态：只读，仓库 0 改动（HEAD = 99783703）· 判据 verify_b3.sh 85/85 · 台账 27 条（F-230…237 · O-110…119 · N-85…93）· 独立 clone /tm… |
| 09-20 02:06 | 09-20 凌晨：offload 审计第 21 棒 · 并发线 A2 完成（provider/anthropic + provider/gemini） | 范围：AnthropicProvider(1023) + GeminiProvider(566) + AnthropicModelsApi(224) + GeminiModelsApi(127) + AnthropicM… |
| 09-22 04:21 | 09-22 收口：全应用微功能验证（verify-all-0921）四线汇总 → REPORT.md | 任务：接替上下文已满的会话，收口 V1–V4 四路验证线，产出总账。产物 /var/minis/shared/verify-all-0921/REPORT.md。仓库 0 改动。 |
| 09-22 05:46 | 09-22：P0 真机验证 → **修复生效且用户可感知**（+1742） | 版本核实（不靠时间猜）：dumpsys package → versionName=1.0.0+1742 / versionCode=220001742 / |

## 21. 人格 / Soul / 提示词 / 技能体系

**跨度** 2026-08-04 ～ 2026-09-21 · **60 条** · **状态** 稳定（技能体系已固化为 12+ 个 skill，跨会话持久）

**叙事**：08-07 soul.lang 接线 → 08-08 三平台技能架构 + 内置集成显示修复 → 08-09 Soul 默认人格名对齐 → 08-18 RC 整改含技能模块 → 08-26 人格(Soul)模块审计加固 + 技能/权限模块审计 → 08-29/09-04 subagent 跨会话派发 → 09-07 规则体系对照复杂度五纪律。**「给 agent 定语言 + 定运行时 + 定验证框架」这条主线在应用内的投影。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-04 05:29 | OpenMinis fork — 仓库实况核查（2026-08-04 午后） | 用户问「项目被改到什么程度/整合了两个项目到什么程度」，用 git 全量核查，修正并补全此前的记忆： |
| 08-04 05:56 | OpenMinis fork — 设置页去箭头（2026-08-04，feat/ux-polish 分支追加） | 用户主张「很多箭头该去掉」，在 ux-polish 分支上继续追加两批去箭头提交（CI 全绿）： |
| 08-04 22:20 | code-workbench-tools 技能首次测试（2026-08-04） | 最新加载的技能（/var/minis/skills/code-workbench-tools，SKILL.md 22:09 更新）做了完整功能测试。 |
| 08-04 22:26 | code-workbench-tools 技能完善（2026-08-04） | 按用户要求修复了技能的 bug。改动 /var/minis/skills/code-workbench-tools/ 下 setup.sh + SKILL.md。 |
| 08-05 15:01 | CF 小号技能化 + token 安全迁移完成（2026-08-05） | Cloudflare 小号 token 已从明文迁移到环境变量 |
| 08-06 22:43 | code-workbench-tools SKILL 升级到 v1.2.0 — 加"沙箱环境约束"一节（2026-08-06） | 用户反复看到模型 agent 在 RikkaMinis 沙箱里跑 grep -rn --include='.kt' 报 grep: unrecognized option: include=.kt（busybox gre… |
| 08-07 18:08 | RikkaMinis(Android fork) vs OpenMinis(iOS) 功能对比 — 2026-08-07 | 对比方法：两份代码都在本地（/tmp/official-openminis=9cf3a85 上游，/tmp/rikkaminis-full=75cd067 Android fork）。Android fork 几乎抄全了… |
| 08-07 19:13 | RikkaMinis: 砍 rootfs 备份/恢复 + soul.lang 接线（2026-08-07 已发版） | 用户要求合并编译发版，5 文件改动已合 main a37c537，CI run 31172667557 全绿，release android-latest 资产已更新（11:12Z）。 |
| 08-08 16:01 | 三平台技能架构（2026-08-08） | 三个平台各司其职，技能统一备份在 GitHub： |
| 08-08 18:55 | 合并 feat/bundled-platform-skills → main，修复"内置集成显示需配置"（2026-08-08） | 背景：用户问"内置集成为什么显示需配置"。排查发现： |
| 08-08 19:11 | ↳ 集成状态诊断日志 — feat/integration-status-diagnostics（2026-08-08） | 背景：用户反映"内置集成显示需配置"，但新会话显示"完整 tier 2"——同一个运行时代码，结果不同。经排查（源码里只有动态 buildIntegrationStatus，无静态模板；日志干净无 keystore/读取… |
| 08-08 19:19 | ↳ ✅ feat/integration-status-diagnostics 已合并 main（2026-08-08） | run #279 success → ff 合并 main（48fd1aa→13f0ee7）→ 推送 main（gh_sync.sh push-main）→ 分支本地+远端已删 |
| 08-08 20:36 | 三平台集成文档化 — README 补全（2026-08-08） | 用户指出"三大平台纳入"是一次重大升级，不是小修小补，README 必须写清楚，否则以后自己都会忘、别人也看不懂。 |
| 08-08 21:28 | 教训：平台技能判定不能用 importSource | 平台集成卡片的筛选条件不能用 importSource == BUNDLED——老用户的技能可能是通过 SESSION/FILE 等途径安装的，installBundledSkills() 在版本号已 ≥ 捆绑版时会 s… |
| 08-09 10:52 | RikkaMinis 系统提示词大小实测（2026-08-09） | 从源码 ChatViewModel.buildSystemPrompt() 逐段重建 + 用户实时数据测量： |
| 08-09 12:32 | Soul 默认人格名对齐应用名 — feat/soul-default-name（2026-08-09） | 用户要求设置页「人格(Soul)」默认名对齐应用名。应用名 app_name=RikkaMinis，而默认人格名是 "Minis"。 |
| 08-12 01:49 | 交叉验证纪律写入 GLOBAL.md（2026-08-12） | 用户纠正了对"17秒幻觉"的归因：根因不是"记忆自证循环"，而是没有交叉验证。教训已写入 GLOBAL.md「问题核查纪律：交叉验证法则」——任何待处理问题必须三源取二（用户亲述/独立实测/客观证据）才认定为事实，单一来… |
| 08-13 04:08 | 任务1：SkillRepository 原子性修复 ✅ 完成 | 分支：fix/skill-repo-atomic → main (3488e70) |
| 08-13 21:34 | 【交接·方案3】代码位置 + 开发纪律 + 开工指引（2026-08-13 21:40） | 相关代码与已知约束（新会话需拉代码确认） |
| 08-22 02:14 | 应用重置后技能/工具全面检查修复（2026-08-22） | 用户重置应用后要求检查并修复各项技能。检查结论与修复： |
| 08-23 14:26 | 会话 E 完成：记忆/压缩/宏/子代理/失败钩子 压测（2026-08-23） | 方法：沙箱装 OpenJDK17 + kotlinc 1.9.24，直接编译 app 自身纯逻辑源码（MemoryRollupEngine/ToolFailureHook/ContextCompactor/MemoryR… |
| 08-24 17:26 | 提炼两个新技能（2026-08-24） | 把散落在 daily log 的高频踩坑提炼成两个独立技能： |
| 08-25 20:49 | rikkahub 流畅性吸收 · 会话 B @Stable/@Immutable 纪律 — 完成（分支未合 main） | 任务：/var/minis/shared/rikkahub-smoothness-absorption/session-task-B.md，分支 fix/chat-stable-discipline（主号 OWNER，基… |
| 08-26 14:33 | 技能脚本全面体检 + 修复（2026-08-26 下午） | 用户要求检查所有技能里的脚本，修 bug + 优化。共扫 9 个脚本文件（gh_ci_wait/gh_sync/gh_fullright/minis_auto_log/semantic_memory/rebuild/sa… |
| 08-26 17:41 | 人格(Soul)模块审计+加固完成（fix/soul-hardening → 分支 CI #1095 绿，未合 main 等拍板） | 用户要求审计「设置→人格」模块并修复/优化，6 个原发现 + 补测时又挖出 2 个真 bug，全部修完。 |
| 08-26 17:46 | MCP 模块审计修复完成（2026-08-26） | 用户要求检查设置里 MCP 模块并修 bug。审计了 MCPRepository/MCPIntegrationsScreen/SessionMcpsSheet/OAuth 四件套 + CLI（minis-mcp-cli）… |
| 08-26 19:17 | 两分支合并 + 全天收尾核查完成（2026-08-26 晚续） | 用户要求把「还有两个没合并的分支」检查后合并，并把今天没收尾的一起收尾。结果：合并 2 分支 + 全天事项核查全部闭环。 |
| 08-26 22:47 | 技能+权限模块审计修复完成（分支 CI 绿，未合 main 等拍板） | 用户要求审计「设置→技能」「设置→权限」两模块并优化，全部做完（branch fix/skills-permissions-polish @ 4f51c8b，分支 CI run #1112 success，head 核实… |
| 09-04 00:19 | subagent 跨会话派发功能验证（2026-09-04） | 验证了 HEAD 813eaf6 的 subagent 跨会话派发功能（eaa3a10 引入，SessionsOffloadHandler + SubagentPrefs + minis-sessions-cli sen… |
| 09-04 00:19 | 三问题修复收尾（2026-09-03 晚，全部合并 main @ 73925e9） | 用户提出三个问题，全部完成并验证： |
| 09-04 09:37 | 子代理设置行 UI 修复（2026-09-04，已合并 main @ 07d63699） | 用户反馈：设置页「子代理派发」副标题太长占 3 行（其他设置项都 1 行），要求压到 ~18 字符内。 |
| 09-05 17:26 | A+B+C 三项按用户拍板"直接干"全部落地: | A+B+C 三项按用户拍板"直接干"全部落地: |
| 09-06 09:51 | MCP 记忆增强盘点结论 + server-memory 配置完成（2026-09-06） | 用户问三个主流记忆 MCP 哪个能用上。盘点结论： |
| 09-06 17:15 | Hermes Agent（Nous Research）吸收分析（2026-09-06） | 源码 /tmp/hermes/hermes-agent-main（20.6 万行 Python），报告 /var/minis/shared/hermes-agent-absorb-analysis.md。 |
| 09-07 21:54 | 规则体系对照复杂度五纪律的复查（2026-09-07，待拍板 2 条） | 用户贴来一段复杂度管理五纪律（地板之上不增一克/边要收费/纠缠最小化/机制可压缩/整体保持可读），问我们的规则体系是否需要吸收。复查结论： |
| 09-09 18:37 | T4 data+backup 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T4.md。扫描 75 文件/17039 行：HIGH 1（ProviderDatabase MIGR… |
| 09-09 18:50 | T7 browser+webapp+mcp 域审计完成（1 HIGH / 3 MEDIUM / 5 LOW） | 报告：/var/minis/shared/global-bug-audit-0909/reports/session-T7.md。扫描 29 文件/9647 行。 |
| 09-11 00:44 | Obsidian 式插件系统评估：不做（2026-09-11） | 用户问「给 RikkaMinis 做 Obsidian 那样的插件系统是否值得」。按一阶门/二阶门评估后拍板不做： |
| 09-14 18:32 | 09-14 夜：HANDOFF-B2 量化结论（前提被推翻）+ 待办 2 已存在 | B2 的第一步（先量化）做完了，结论是「不做」。 |
| 09-15 16:30 | 09-15 晚：轴 2（行内层纯函数 fuzz）收口 — 186/0 绿，重大抽取纪律抓漏 | 范围：StreamingMarkdownText 行内层 5 个纯函数（safeInlineSplitOffset / findInlineCodeClose / inlineMathSizeEm / collectIn… |
| 09-15 19:06 | 09-15 晚：SAGAS 主题索引固化进 skill（v2.1.0）—— main = 5c9ff85 | 用户问「有没有固化下来，以后可能还会用」→ 核查后发现三个真实缺口并全部补上。 |
| 09-15 21:56 | 09-15 晚：HF 语义记忆重建 + MCP 知识图谱重建（09-06 套件随 rootfs 全丢） | HF：semantic_memory.py build 732→1070 条（索引 5.8MB，已上传 dataset USER/rikkaminis-memory），搜索验证命中正常。 |
| 09-16 08:00 | 09-16 早：§18 归因实证 + 用户拍板「记纪律不立项」（beacon 观测桩上线） | ★ 归因实证（新证据，之前只有时间相关）：沙箱 /proc/self/cgroup 的组路径 = 0::/uid_11618/pid_19313，而 pid_19313 在沙箱 /proc 里 No such file … |
| 09-16 13:34 | 09-16 午后：GitHub 热榜 Top5 实测审阅 — 意外挖出 semantic-memory 真 bug | 报告：/var/minis/shared/gh-hotlist-2026-09-w2-review.md（13KB）。方法：API 拉元数据 + 拉源码/规则原文，不看宣传语，凡「能否为我所用」判断都跑实测。 |
| 09-16 14:45 | 09-16 傍晚：§22 热榜吸收三项落地（22a 阶梯 skill / 22b evals / 22c 债务扫描） | 用户拍板同批：vector_index.pkl 不打包（backlog §22「设计决定」已改为已拍板 + 触发重估条件 + 附带待办：下次动仓库 requirements.json 时补一行「首次使用需 build（需… |
| 09-16 14:49 | 09-16 傍晚（续）：GLOBAL.md 加两条纪律（用户拍板「写进去吧」） | 新增 「## 改动阶梯纪律（写代码前的默认工作流）」（现 27–34 行）与 「## Skill 触发验证纪律（新增/改 skill 时）」（36–39 行），插在「分支隔离纪律」之前——顺序即语义：阶梯在前（写多少）、… |
| 09-17 18:13 | 09-17 傍晚：存储页转圈 + markdown 列表误渲染双修复 → main = 40a58c94 | 用户报告两件事，都实锤： |
| 09-17 20:18 | ↳ 09-17 收尾：全天工程量统计（用户问"为什么感觉工程量大"时的硬数据） | main 交付量（SGT 09:41→17:55）：33 提交 / 147 unique 文件（全仓 ~524 文件的 28%）/ +3031 −577 行；CI 今天 43 轮构建（29 绿 / 9 红 / 5 取消，… |
| 09-18 18:31 | 09-18 深夜：Termux↔RikkaMinis 打通（termux-dock MCP 桥）+ 三组实测数字 | 怎么发现的：探测本机监听端口时发现 127.0.0.1:8000 回 termux-dock MCP is running —— 用户 Termux 里早就跑着一个 MCP 服务（pm2 + watchdog 托管：te… |
| 09-19 11:14 | 09-19：offload 审计第 6 棒完成（Notification 全文 + BrowserUse/Config/Sessions + 权限页 → F-69…F-82 + 勘误 E-3/E-4/E-5 + A 类边清单） | 状态：只读审计，仓库 0 改动，锚点 main c6d8d63f。 |
| 09-19 12:57 | 09-19：云端两分支审查 + 合并进 main = 99783703（P1 隐私面 + 子代理上下文） | 用户指令：检查云端两个分支有没有引入问题，没问题就合并，合并触发后不用管。 |
| 09-19 15:15 | 09-19：offload 审计第 10 棒完成（Ring 2 / P4 = `agent/` 运行时 + `SoulStore` → F-105…F-115） | 状态：✅ 第 10 棒完成 · 只读，仓库 0 改动 · QA verify_all.sh 38/38 · 判据 verify_findings_10th.sh 99/99 · 生成器自对账 40 项 |
| 09-20 02:07 | 09-20 第 21 棒 · 并发线 B2 完成（`data/repository/` 11 文件 / 7,101 行） | 产出：/var/minis/shared/offload-audit-0919/wave2-b2/ —— report.md（含覆盖表）· ledger-b2.json（25 条：D=6 · O=10 · N=9）· v… |
| 09-20 04:36 | 09-20：FIX-5 批次（data 层修复）完成 — 8 修 / 1 证伪 / 2 越界 | 分支 fix/data-layer @ 4cde346（基线 afa404b）· 9 文件 + 2 测试 / +640 −70 · 判据 43/43 · JVM 16/16 · 产出 /var/minis/shared/… |
| 09-20 15:52 | 09-20：审计图表整理进笔记库 → `/var/minis/mounts/笔记/RikkaMinis审计图表/` | 用户要求：把审计产出的图表（mermaid 这类）在笔记文件夹里开一个文件夹放进去，并检查缺什么、补上。 |
| 09-20 17:14 | 09-20：键盘遮挡编辑内容（IME occlusion）—— 诊断完成，交接给新会话 | 用户报告：技能编辑 / 记忆编辑中，点击后输入法键盘直接盖住正在编辑的内容；并要求排查所有编辑面。 |
| 09-20 17:35 | 09-20 晚：IME 键盘遮挡修复完成 → 分支 `fix/ime-occlusion-hosts` @ `4d4333d3`（CI 绿，**按用户要求不合并**） | 用户指令：跑完不合并，等统一处理。远端现有三个未合并分支（同基 3ec26816）： |
| 09-20 23:51 | 09-20 深夜：T2 线（regression-audit-0920）取证收口 —— 任务书前提被证伪，按修复门纪律不动手 | 结论：/var/minis/shared/regression-audit-0920/tasks/T2-degrade-restore.md 的四条事实前提全部不成立。产出 /var/minis/shared/regre… |
| 09-21 14:07 | 09-21 下午：两套记忆装置增量更新（HF 语义记忆 + MCP 知识图谱） | 触发：用户「本地的和云端的两个记忆存储装置好像很久没更新了，就是 MCP 和 HF 处理一下」。 |
| 09-21 22:06 | 09-21 用户拍板：分支 CI 绿 = 确定性停止节点（流程纪律改写） | 用户原话：改完 → 云端 CI 绿 → 这就是结束；分支 CI 绿作为一个确定性的停止节点。合并前必须先「检查、验证、核实」，走完这套才能合并。 |

## 22. 语音 / 多模态输入

**跨度** 2026-08-03 ～ 2026-09-21 · **22 条** · **状态** 低优先级维护（语音输入可用，未再重点投入）

**叙事**：08-04 语音 UI 移除 → 08-06 语音功能清理（合并 main）→ 08-27 语音输入顺滑度修复 + voice-ime 合并 → 09-12 senseaudio「系统繁忙」实测 + TTFB 30s 通用缺口。**两次「移除」后又两次「修回来」**——语音这条线反复横跳，最终落在「保留但降低优先级」。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-03 21:09 | Minis 全功能自检（2026-08-03 晚） | 对设备上的 Minis 环境做了系统性功能测试，结果： |
| 08-04 21:53 | RikkaMinis — 模型组页简化 + 语音 UI 移除（2026-08-04，分支 feat/...） | 仓库 OWNER/RikkaMinis，分支 feat/simplify-model-groups-remove-voice，commit 6cf806f9，CI run 30915989290 验证中。 |
| 08-06 13:31 | 语音功能清理 — 已合并 main（093d13b） | 删了 |
| 08-07 18:08 | RikkaMinis(Android fork) vs OpenMinis(iOS) 功能对比 — 2026-08-07 | 对比方法：两份代码都在本地（/tmp/official-openminis=9cf3a85 上游，/tmp/rikkaminis-full=75cd067 Android fork）。Android fork 几乎抄全了… |
| 08-09 16:16 | 提供商模型页两处修复合并 main（2026-08-09） | 分支 fix/manager-sheet-bottom-clip，两 commit 已 ff 合并 main（6547894），正式构建 run 31303103065。 |
| 08-27 09:40 | 任务05审计完成：speech + webapp 模块（2026-08-27） | 审计 /var/minis/shared/module-audit-batch/tasks/task-05-speech-webapp.md，报告已写 /var/minis/shared/module-audit-bat… |
| 08-27 11:01 | fix-06 browser/speech/media 低风险批修复完成（2026-08-27） | 模块审计批 fix-06（🟢 P3 批量）施工完成，分支 fix/misc-low-risk-batch @ f1f26ec，CI run #1119 success（head 核实一致），未合并 main 等收口拍板。… |
| 08-27 21:37 | 语音输入顺滑度修复完成（分支待合并，2026-08-27） | 用户主诉：对话框里用「输入法自带的语音输入」感觉比其他应用不顺畅（注意：不是 app 自带语音，已澄清）。 |
| 08-27 22:23 | provider 路由字段即时生效修复 + voice-ime 合并 main（2026-08-27 晚，main=9e3374c） | 用户主诉：设置里改大模型提供商的地址/开关（custom base URL 等）要重启 app 才生效，体验差。连带要求把另一个已跑完 CI 的分支一起合并。 |
| 09-09 19:00 | T3 sandbox-offload 域审计完成（全局第二轮） | 产出：/var/minis/shared/global-bug-audit-0909/reports/session-T3.md。扫描 69 文件/24961 行：HIGH 0 + MEDIUM 6 + LOW 2。 |
| 09-10 01:00 | 第二轮审计修复：MEDIUM 清零 + LOW 批次（2026-09-10 凌晨） | main 状态：main @ d66fea1（B11 ff2455b + B12 d66fea1 已 ff 合并；B11 release CI 34377683652 success，B12 release CI 343… |
| 09-13 21:06 | 应用图标极简重设计 —— 已放弃并全部回滚（2026-09-13 晚） | 结果：用户看过 A/B/C 三个候选（白底双色 / 深蓝底白+青 / 白底单色，均为"两莫比乌斯环互锁成圆"的扁平重绘）后判定都不行，决定保留原图标，并说"暂时不改了"。 |
| 09-13 22:49 | ★ 2026-09-13 环节完善度横向扫描（main @ d3873c0e）：1 结构缺口 + 3 真缺陷 + 1 注释漂移 | 扫描轴刻意换过：历史 T1–T10 全域审计（5 HIGH+34 MED+54 LOW 已收口）是「按功能域找 bug」；本次是同构组件一致性 + 审计清单之外横切面。手段：5 个静态探针（scan1–5 在 /var/… |
| 09-19 10:48 | 09-19：offload 审计第 5 棒完成（Calendar/Alarm/Speech+Speak → F-51…F-68 + 勘误 E-2 + 权限分类重评估表） | 产出（/var/minis/shared/offload-audit-0919/）：报告 reports/rikkaminis-calendar-alarm-speech-audit.md(31KB) · diagram… |
| 09-19 13:51 | 09-19：offload 审计第 8 棒完成（Ring 2 第一段 = config/tools/debug/browser/speech 抽样 → F-95…F-97） | 用户指令（本棒方向性）：「继续扫」「扫完之后，再统一进行其他的处理」→ 修补全部推后，资源投在把 Ring 2 扫完。 |
| 09-19 19:20 | 09-19：offload 审计第 15 棒完成（Ring 2 第八段 = `tools/` 14 文件 2,792 行 + `speech/` 7 文件 1,649 行 → F-153…F-160）· **Ring 2 至此 27,003 行 / 88 文件全部走完** | 状态：✅ 只读，仓库 0 改动（HEAD = c6d8d63f = 锚点，git status --porcelain 空）· 包闸门 verify_all.sh 67/67 · 判据 verify_findings_1… |
| 09-20 02:26 | 09-20 凌晨：offload 审计第 21 棒 **并发线 A3** 完成（`provider/thinking/` + `provider/voice/`） | 产出：/var/minis/shared/offload-audit-0919/wave2-a3/（report.md + ledger-a3.json + verify_a3.sh + exp_a3/）。 |
| 09-20 05:35 | 09-20：修复批次 FIX-2-thinking-voice 完成（6 条，分支已推 + CI 绿） | 批次：FIX-2-thinking-voice · 分支 fix/thinking-voice-layer · commit 0012517 · 基线 afa404b |
| 09-20 08:37 | ↳ 09-20：修复批 FIX-3-chat-state 完成（chat 状态机与并发 · 7 条 + 跨批补丁 4 处） | 分支 fix/chat-state-machine · 基线 afa404b（父提交已核）· commit 3b3c474 + 9dfe54f · CI #1670 → #1682 两次均 success · diff … |
| 09-21 16:09 | 09-21 晚：同类应用 issue 核实（1106 条 / 7 仓库）→ 主发现「非视觉模型图片门只覆盖 1/3 provider」 | 用户指令：「查一下与这个应用同类型的应用（omnibot、operit 这类）的 issue，看他们的问题在这个应用里是否同样有」 |
| 09-21 16:44 | 09-21 晚补：同类应用 issue「坐实」——从静态 grep 升级到独立实测，并挖出 T8 未接线 | 用户指令：「进行验证，先把他们坐实。」 |
| 09-21 19:42 | 09-21 晚：S2 派发任务完成 — provider 层两条（§27a 图片门 + §24b TTFB 统一） | 分支 fix/s2-provider-layer @ eca54225b4b93df0f3562a61926e22ac496edd5f，CI run 35593738205 success，head_sha 逐字符一致，… |

## 23. 被否掉的方向（决策记录）

**跨度** 2026-08-04 ～ 2026-09-20 · **37 条** · **状态** 持续累积（每条否决都带触发条件，可被未来重新打开）

**叙事**：08-07 多智能体协作 → **否掉** / 08-07 经验记忆模块 → **摘除** / 08-08 聊天模式 → **不加** / 08-08 灵动岛 → **砍掉** / 08-15 自动接力方案 → **不做** / 09-05 provider knobs + 连接测试 → **摘除** / 09-11 Obsidian 式插件系统 → **不做** / 09-13 图标重设计 → **放弃并回滚** / 09-14 U7 胶囊暂停 → **撤回（带触发条件）** / 09-14 U1..U11 中 8 项 → **归档不吸收**。**这份清单和「做了什么」同等重要**：它记录了边界是怎么被划出来的。

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-04 21:53 | RikkaMinis — 模型组页简化 + 语音 UI 移除（2026-08-04，分支 feat/...） | 仓库 OWNER/RikkaMinis，分支 feat/simplify-model-groups-remove-voice，commit 6cf806f9，CI run 30915989290 验证中。 |
| 08-05 16:11 | RikkaMinis 小米灵动岛适配 — 已废弃回滚（2026-08-05 收尾） | 用户决定废弃（"不起作用就废弃，回滚到之前没干这个的状态"）。 |
| 08-06 00:39 | 记忆体/经验引擎概念 — 小号独立应用方向（2026-08-06 灵感记录，暂缓） | 用户在思考"以 RAG/记忆为核心的东西"时有了关键洞察，与 RAG v1 不同，先记下来以后再做： |
| 08-07 09:12 | RikkaMinis 待办 #1 滚动决策函数 — 已实现并推送（2026-08-07） | 完成状态 |
| 08-07 09:29 | 待办 #1 滚动决策函数 — 系统 self-review + 类型修复（2026-08-07） | 系统检查结论（逐门控对照原始实现，9 条路径全部语义等价） |
| 08-07 10:53 | RikkaMinis「多智能体协作」决策 —— 否掉，别做（2026-08-07） | 用户对"是否加 agent 间消息通道 / 多智能体协作"的探讨，最终结论：过度工程，不做。 |
| 08-07 17:43 | RikkaMinis — 经验记忆模块已摘除（分支 revert/experience-memory, commit 3a1d2b6） | 用户判定经验记忆（episodic memory）是噪音，要求连根摘除。已在 /tmp/rikkaminis-full 从 origin/main(de2b938) 起分支 revert/experience-memor… |
| 08-08 11:30 | 灵动岛焦点通知 — 已砍掉（2026-08-08） | 用户决定放弃该功能。分支 feat/focus-notification-dev-test（d256c64，含 FocusNotificationTester/XiaomiFocusHelper/XmsfFirewall… |
| 08-08 11:52 | 移除冗余关闭/返回按钮 — 已提交推送，CI 验证中（2026-08-08） | 分支 feat/remove-redundant-close-buttons（commit 8e4bf92，基于 feat/recovery-strategy HEAD 9874a4f） |
| 08-08 11:59 | ↳ 四个待合并分支已全部合并 main（2026-08-08，HEAD b000e31） | 合并方式：feat/recovery-strategy(9874a4f)、feat/remove-redundant-close-buttons(8e4bf92) 线性 ff；perf/immutable-chat-mo… |
| 08-08 18:39 | 聊天模式决策：不加（2026-08-08） | 用决策框架分析后结论：聊天模式让 agent 能做的是"省 token/省延迟/去 agent 人格污染"，不是"之前做不到的事"。用户有 RikkaHub 做纯聊天，RikkaMinis 定位就是智能体模式，加聊天模式… |
| 08-12 15:10 | 多任务并行推进模式（2026-08-12 用户决策） | 用户批评"逐个等 CI 太慢"，要求任务分解、独立分支并行推进。工作方式： |
| 08-13 18:01 | rootfs 定向恢复真机验证（2026-08-13 续）— BUG 1 修合并 main，BUG 2 待决策 | 交接文档：/var/minis/workspace/handover-rootfs-targeted-restore-2026-08-13.md（新会话必读） |
| 08-14 03:10 | OAuth 登录移除施工完成，合入 main（2026-08-14） | 目标：用户想砍"设置 → 添加 AI 服务商"的复杂度。审计后确认：复杂度 90% 来自 OAuth 登录层（auth/ 包 2969 行），而 6 个 provider 全部支持手动 API key（gemini 甚至… |
| 08-14 06:00 | OAuth 移除真机验证 — 用户确认闭环（2026-08-14） | 用户装 main f87204a 新 APK 后验证结果： |
| 08-15 19:50 | 决策：不做"自动接力方案（无感分卷）"（2026-08-15） | 用户提出"自动接力/无感分卷"（长会话触碰阈值 → 自动总结 → 切新会话 Part 2 带 summary 满血启动）后，自己收敛到"不要做"。评估确认其直觉正确： |
| 08-15 19:58 | ↳ 字体大小设置四 bug 修复完成（2026-08-15） | 用户要求检查 Settings → Appearance → Font Size 是否有 bug，发现 4 个问题，全部在一个分支修完： |
| 08-17 18:02 | 清理：移除 Settings 页 Tier0 测试入口 + 治标分支清理（2026-08-17） | 用户提出：Settings 最底下的测试入口（"Crash Test (temp)" + "Trigger Native Crash" 按钮）是 Tier0 临时验证遗留，该处理。已完整删除： |
| 08-18 18:49 | RikkaMinis 前端施工收口决策（2026-08-18） | 用户拍板走 B 路径：FE 施工收口在 FE-1（颜色 token 化）+ FE-3 step1（Thinking 三函数抽取）两个已闭环、CI 绿、零回归的成果上，main 停在 fcf9470。 |
| 08-21 12:07 | 2026-08-21 崩溃修复 + 偶发丢消息搁置 | 已修复并合并（main a1bc4bb） |
| 08-22 01:47 | 2026-08-22 对话崩溃排查 + main 回滚 #985 + release 说明（会话收尾） | 用户决策：最新版（Phase 0-4 内存隔离 + :modelservice/:toolservice/:browserservice 三进程 + bridge）反复出问题，用户拍板回滚 main 到 #985（75a… |
| 08-22 17:04 | [dual-appid] 重要决策：小号维持分支进度，不合并 main | 用户明确拍板：dual-appid 的改动（应用共存实验）留在小号实验分支 chore/dual-appid 的"进度"态即可，不要合并进小号 main。 |
| 08-26 00:55 | 主号回滚 + 小号同步执行记录（2026-08-25 深夜） | 背景：我（本会话）之前做的「限 PRoot 地址空间 RLIMIT_AS=4GB」修复（commit de13ed18）翻车——4GB 压太狠导致 PRoot tracer 起不来，所有终端/shell 瘫痪（用户 B … |
| 08-27 15:48 | 会话任务 H（历史回底部第四轮）终止交接（2026-08-27） | 任务 H 施工终止转交接，交接文档 /var/minis/shared/rikkahub-smoothness-absorption/session-task-H-handover.md。 |
| 09-04 13:17 | 思考字段决策键根治（tokenrhythm qwen 报错，2026-09-04） | 用户场景：tokenrhythm.studio + qwen3.8-max，开思考就报错（低档也报错），关思考没事。RikkaHub 不报错。 |
| 09-05 15:11 | 摘除 provider knobs + 连接测试两个功能（已合并 main @ 0103d96f） | 用户判定昨天 6584aca5 引入的「高级自定义（custom headers/body）」和「测试连接」两个功能"极度不成熟，增加复杂度/维护成本，还有问题"，决定精准摘除（非 git 回滚——那会连带干掉 thin… |
| 09-10 12:41 | 遗留清单机制建立 + NUL 项搁置（2026-09-10） | 用户对 sanitizeName 的 NUL 检查拍板「这是个小问题，攒着吧，记下来」。 |
| 09-11 00:44 | Obsidian 式插件系统评估：不做（2026-09-11） | 用户问「给 RikkaMinis 做 Obsidian 那样的插件系统是否值得」。按一阶门/二阶门评估后拍板不做： |
| 09-12 15:21 | A1 T9 性能基线验证 + 拍板暂缓（2026-09-12 收尾） | 验证实证：从 05a9d111b2（rk-JcEvoX clone）提取 4 生产类（Collector 324/Report 274/Tracker 132/Workload 209 行）+ 4 测试类（618 行）；… |
| 09-13 15:33 | 用户拍板回滚今天全部改动（2026-09-13） | 用户要求"不要做多余操作，立即回滚到今天改动前"。main 上今天 3 笔（32571f84 open-catchup v1、daab66b8 v2、33aa72d1 自适应压缩——后者已被他人先 revert 为 26… |
| 09-13 15:47 | 回滚后 main 终态确认（2026-09-13 晚） | API 实查 main = d6cbe49e，revert 链已落地：d6cbe49e（revert 32571f84 open-catchup v1）← 46a33c70（revert daab66b v2）← 262… |
| 09-13 21:06 | 应用图标极简重设计 —— 已放弃并全部回滚（2026-09-13 晚） | 结果：用户看过 A/B/C 三个候选（白底双色 / 深蓝底白+青 / 白底单色，均为"两莫比乌斯环互锁成圆"的扁平重绘）后判定都不行，决定保留原图标，并说"暂时不改了"。 |
| 09-14 21:31 | 09-14 夜：U7 撤回（带触发条件）+ T4 conversation_history 完成（分支 CI 已绿） | U7 胶囊——撤回，不是"待做"（依据代码而非偏好）： |
| 09-15 16:56 | 09-15 晚：轴 4/5/6 收口（事件接线/决策层/资源生命周期） | 轴 4 事件接线：运行循环实际发射 9 种事件；PersistenceFailed 零发射点（§14 已攒 backlog）。 |
| 09-18 14:05 | 09-18：DeepSeek V4 思考回传 400 追查（未解决，已按用户决定搁置）+ 分支合并 main | 结论先行：装了修复版（1.0.0+1646 / cfa48ec9）后仍复现 → 修复不够。用户判定该故障有随机性，决定暂不继续修，仅合并分支。 |
| 09-19 22:05 | 09-19：第 19 棒中途（`provider/` 网络与预算面）→ F-180（D）+ O-47 + N-26 + **勘误 E-18（撤回 O-44）** | ★ 勘误 E-18（重要，方法论级）：第 18 棒我写进台账的 O-44 我误判了 —— 声称 ToolJsonRepair.levenshteinAtMostOne（:124-155） |
| 09-20 18:02 | 09-20 夜：卡死 bug 溯源 —— 不是「改 bug 改出来的」，是「架构改动移除了隐患的沉睡条件」 | 用户问：「怎么回事？什么时候这个又出现了？他是改Bug改出来的，还是原本就有的？」 |

---

## 未归入任何主题的条目

> 这些条目落在现有主题之外。**它们是下一轮主题发现的第一手候选** ——
> 如果同一天区反复出现同类条目，就该为它开一个新 saga。

| 日期 | 标题 |
|------|------|
| 08-03 00:17 | AgentDock v0.1 foundation |
| 08-03 00:25 | DockBin 情绪垃圾桶方向（AgentDock v0.1 验证成功后） |
| 08-03 14:38 | Peezy API Key |
| 08-04 13:30 | RikkaMinis — 恢复丢失的 UI 改动（2026-08-04） |
| 08-05 22:09 | RAG v1 知识库实验 — 经验教训（2026-08-05） |
| 08-08 01:32 | 待办：历史栏点击跳转后自动缩回（2026-08-08 用户提出） |
| 08-08 01:39 | 历史栏点击后自动缩回 — 修复已提交（2026-08-08） |
| 08-08 01:50 | RikkaMinis 日志模块优化施工完成 — 分支 feat/logging-module-optimize（2026-08-08） |
| 08-08 15:43 | 重要结论：目录可靠性分级（2026-08-08） |
| 08-09 02:40 | GitHub API header 名教训（2026-08-09） |
| 08-09 10:50 | 用户消息优先抢占 agent 任务 — feat/user-message-preempts-agent 已合并（2026-08-09） |
| 08-09 17:54 | 日志排查（2026-08-09 用户报"去看日志出问题了"） |
| 08-09 18:22 | RikkaMinis 开发起点时间线（查证 2026-08-09） |
| 08-10 14:42 | README 致谢/许可证段主语错误修复（2026-08-10） |
| 08-10 23:51 | 版本号体系 0.22-preview → 1.0.0（feat/version-1.0.0 → main e240ef2） |
| 08-13 00:58 | 套餐批量生成测试成果（2026-08-12/13） |
| 08-13 01:47 | AI 批量生成测试的教训（2026-08-13） |
| 08-13 22:26 | 【方案3 验证】每次 boot 全量重建的真相 — verifyIntegrity size 检查误判动态文件（2026-08-13 22:2x） |
| 08-13 22:48 | 【方案3 收尾】已 ff 合并 main（5e97324）+ 另一个会话施工 Bug 3（2026-08-13 23:0x） |
| 08-13 23:35 | 修正：断网验证（验证 2）用户实际已测过（2026-08-13 深夜） |
| 08-14 11:48 | 回合组默认折叠 + 移到回答上方（feat/run-group-manual-collapse → 4ad1533，2026-08-14） |
| 08-15 01:12 | RikkaMinis 平衡点施工蓝图（2026-08-15） |
| 08-15 05:59 | T5 AgentRunReducer 施工中（2026-08-15） |
| 08-15 09:14 | T9 性能基线完成 — 分支 stability/T9-performance-baseline（0883fda4，2026-08-15） |
| 08-15 10:50 | T4-B 准备完成 — adapter 接口+骨架+映射+验收清单 已合并 main（b877a8f，2026-08-15） |
| 08-15 18:45 | T10 最终验收 — 代码层已闭合，待真机验证（2026-08-15 晚） |
| 08-15 19:01 | T10 最终验收完全通过（2026-08-15 晚） |
| 08-16 05:17 | 临时密钥窗口补核心文件测试（2026-08-15 深夜） |
| 08-18 08:53 | 大模型回答频繁断掉诊断进行中（2026-08-18 08:47+，用户主诉"大模型回答频繁断掉"） |
| 08-18 14:10 | 协作方式偏好 |
| 08-18 18:21 | RikkaMinis 前端 FE 拆分施工 — 阶段性认知（2026-08-18） |
| 08-20 16:33 | 会话1 offload 工具类压测结果（2026-08-20） |
| 08-21 18:09 | idle-reap 误杀流式回归修复（2026-08-21 补充） |
| 08-21 20:23 | Phase 1 :toolservice socket owner 施工中 |
| 08-21 23:41 | fix/bridge-large-payload 合并 main（2026-08-21 晚，P0 bridge 修复闭环） |
| 08-21 23:42 | fix/bridge-large-payload 合并 main（2026-08-21 晚，P0 bridge 修复闭环） |
| 08-22 13:17 | RikkaMinis 第三方视角自述文档（含诚实缺陷节）（2026-08-22） |
| 08-23 18:46 | 新 P0 发现+修复：browser get_text 超长文本导致 ANR（2026-08-23 晚） |
| 08-24 18:46 | Bug 诊断：手动添加的大模型「删不掉」（2026-08-24） |
| 08-26 01:15 | 会话 F 收尾：fix/history-open-at-bottom 已合入 main 8484a49（2026-08-26） |
| 08-29 22:29 | 会话任务 A：WorkerKeyFreshness 时钟回拨修复完成（2026-08-29） |
| 08-29 22:39 | 会话任务 B：appendSystemInfo 合并节流完成（2026-08-29） |
| 08-29 23:08 | A/B 两任务合并 main 完成（2026-08-29） |
| 08-29 23:34 | 会话任务 C：readAppendedChunks 统一到 BoundedLineReader 完成并已合并 main（2026-08-29） |
| 08-30 00:21 | 会话任务 D：safeEnum 跨版本兜底收紧完成并已合并 main（2026-08-29 深夜） |
| 09-02 14:41 | FE-5 ChatViewModel 拆分第一批完成（2026-09-02，commit 8f0d64dc 合并 main） |
| 09-03 09:46 | FE-5 第三簇拆分会话——流程纠偏记录（2026-09-03） |
| 09-07 08:15 | RikkaMinis 全貌梳理请求（2026-09-07） |
| 09-07 20:19 | 浏览器三件真机验证（2026-09-07，新装 android-latest = main @ fa92a3e） |
| 09-07 22:04 | 规则升级已拍板落地（2026-09-07 晚，用户批准两条） |
| 09-07 23:31 | 复杂度量化度量（2026-09-07，回答"复杂度涨了多少/是不是错觉"） |
| 09-08 13:45 | env-var group 修复的补强验证（真实文件 JVM 驱动测试） |
| 09-10 01:03 | B13 批次验证证据（2026-09-10，含一条新发现的既有 quirk） |
| 09-10 01:07 | 结构断言套件 assert_changes.py（B12–B16，可复用） |
| 09-10 04:09 | 关键发现（推翻了我自己的前提） |
| 09-10 13:31 | verify nudge 默认关闭（2026-09-10，main @ 478ae1e） |
| 09-11 14:47 | Groq 免费档在 RikkaMinis 中不可用的根因（2026-09-11，用户提供 gsk_ key 排查） |
| 09-12 10:33 | 开发趋势图 + 统计快照（2026-09-12） |
| 09-12 10:47 | 体量统计 bug 修正 + 洞察层结论（2026-09-12，接续上一条） |
| 09-12 23:28 | shellTimeout 接线 + 滑杆密度修复（分支 fix/tuning-shell-timeout-and-slider-density @ e057d151，2026-09-12 深夜） |
| 09-13 07:06 | fix/tuning-shell-timeout-and-slider-density 合并收尾（main @ e057d151，2026-09-13 凌晨） |
| 09-13 07:11 | shellTimeout 修复真机验证（2026-09-13 用户反馈） |
| 09-13 07:35 | shellTimeout 撞 60s 实证成功（2026-09-13） |
| 09-14 00:04 | ★ 2026-09-13 分支 fix/completeness-followups-0913 已 ff 合并 main = `a330ce2f` |
| 09-14 16:20 | 背景：用户要求分析新包（1.0.0+1516，会话 ID 别名修复）运行 13:45-16:14 的日志找 bug。 |
| 09-15 19:31 | assets/ 里两个未引用文件（badge-android.svg "Get the APK on GitHub" 徽 |
| 09-16 15:43 | 09-16 晚：RikkaMinis 源码第三方独立调查报告（笔记/） |
| 09-16 19:24 | 09-16 晚：minis_link.py 加「跨名查重」（漏斗改进，用户拍板"做吧"） |
| 09-17 21:51 | 09-17 晚：第二类 bug 测试缺口对账 + 4 个测试补齐 → main = 0c6ff4d7（#1636 绿） |
| 09-18 14:34 | 09-18：worker 日志落盘缺口修复 → main = d09f3adf（用户拍板「做吧」） |
| 09-18 14:40 | 09-18：evals 欠账补齐第一轮（覆盖 6→10/24，行为版 24/24） |
| 09-19 09:34 | 09-19 补：精读覆盖率实测数字（更正上一条的"约 6000 行"估计值） |
| 09-19 17:31 | （接上条）★ 方法论增量（6 条）：①实验证伪自己的假设是最高产产出形态 —— 「幽灵对话框」假设带了完整装置后被推翻， |
| 09-19 18:12 | （接上条 09-19 第 14 棒）⑦ F-147 isAppForeground 是普通 var（SessionAct |
| 09-20 10:24 | 09-20：「最近两天改动」二轮核查（541fbb2）→ 抓出 2 条：EXTRA_DAYS 容器类型 + KDoc 挂错 |
| 09-20 14:03 | 09-20：用户拍板「修复门」规则 → 写入 GLOBAL.md |
| 09-21 15:32 | 09-21 补：五日工作梳理成文 + 文档整理（shared 196M→40M，修 2 处失效引用 + 1 个闸门误导性诊断） |
| 09-22 04:10 | 09-21 深夜：V2（sandbox/offload/service）微功能验证报告定稿 |
