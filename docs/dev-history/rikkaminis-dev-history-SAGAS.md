# RikkaMinis 开发档案 · 主题索引（SAGAS）

> **这是档案的「主题视角」**。同一批条目，换一根轴来读：
> - `rikkaminis-dev-history.md` — 时间轴（这天发生了什么）
> - `rikkaminis-dev-history-INDEX.md` — 按天索引（快速跳转）
> - **本文件** — 主题轴（这件事的完整历史，跨 86 天）
>
> 由 `skills/dev-history-sync/scripts/build_sagas.py` 从档案自动生成，可重复执行。

- 覆盖范围：2026-08-03 ～ 2026-10-27，共 86 天
- 条目总数：2369（源文件：rikkaminis-dev-history.md）
- 主题数：24
- 未归入任何主题：201 条（8%，列在文末）
- 归入 ≥4 个主题（说明规则偏松）：184 条
- 标 `↳` 的条目是**推断归属**：标题里没有主题词（「X 完成 / X 收尾」这类收尾条目，主题词在**上一条**里），由「12 小时内最近一条有主题的条目」继承而来，共 141 条。

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
| 1 | 滚动跟随 / 流式跳动 | 79 | 08-05 ～ 10-24 | 观察中 |
| 2 | 输入框 / 键盘 / 粘贴 | 36 | 08-04 ～ 10-24 | 已闭环 |
| 3 | native 内存 / OOM / 崩溃 | 162 | 08-03 ～ 10-27 | 持续跟踪 |
| 4 | 沙箱 / rootfs / PRoot / 终端 | 149 | 08-03 ～ 10-27 | 稳定 |
| 5 | fastio offload / syscall 税 | 41 | 08-20 ～ 10-26 | 已合并 main |
| 6 | 流式回答中断 / 恢复 / 取消 | 187 | 08-03 ～ 10-27 | 已闭环 |
| 7 | 思考 / 推理泄漏 | 98 | 08-08 ～ 10-26 | 已闭环 |
| 8 | 上下文压缩 / 记忆 / 预算 | 212 | 08-06 ～ 10-27 | 稳定 |
| 9 | Provider / 模型组 / 负载均衡 | 164 | 08-04 ～ 10-26 | 已闭环 |
| 10 | 备份 / 多端同步 | 176 | 08-03 ～ 10-26 | 收敛 |
| 11 | 渲染性能 / Markdown / 长会话卡顿 | 136 | 08-05 ～ 10-26 | 已闭环 + 持续加固 |
| 12 | 审计 / 整改（多轮） | 376 | 08-03 ～ 10-27 | 常态机制 |
| 13 | 多会话并行协作 / 派发 / 交接 | 730 | 08-04 ～ 10-27 | 成熟 |
| 14 | CI / 构建 / 发布流水线 | 736 | 08-03 ～ 10-27 | 稳定 |
| 15 | 开发档案 / 记忆 / 工具链 | 200 | 08-03 ～ 10-27 | 常态维护 |
| 16 | 会话 / 导航 / 抽屉交互 | 169 | 08-03 ～ 10-27 | 已闭环 |
| 17 | 平台适配 / 通知 / 图标 / 权限 | 103 | 08-03 ～ 10-26 | 部分放弃 |
| 18 | 国际化 / 文案 / 本地化 | 33 | 08-04 ～ 10-26 | 已闭环 |
| 19 | 上游 / 生态吸收 / 开源 | 104 | 08-03 ～ 10-26 | 常态 |
| 20 | UI 组件 / 设置页 / 交互微调 | 123 | 08-04 ～ 10-24 | 持续 |
| 21 | Token 用量 / 成本统计 | 84 | 08-05 ～ 10-23 | 已闭环 |
| 22 | 人格 / Soul / 提示词 / 技能体系 | 174 | 08-04 ～ 10-26 | 稳定 |
| 23 | 语音 / 多模态输入 | 58 | 08-03 ～ 10-26 | 低优先级维护 |
| 24 | 被否掉的方向（决策记录） | 51 | 08-04 ～ 10-18 | 持续累积 |

---

## 1. 滚动跟随 / 流式跳动

**跨度** 2026-08-05 ～ 2026-10-24 · **79 条** · **状态** 观察中（09-13 第 5 轮已合并 main；真机复测窗口内未再复发）

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
| 09-25 15:26 | 用户报告：记忆编辑模块点键盘时光标/内容跳回最顶端。诊断结论（09-25）：Compose 框架已知 bug，非 app | 用户报告：记忆编辑模块点键盘时光标/内容跳回最顶端。诊断结论（09-25）：Compose 框架已知 bug，非 app 代码错——Google issuetracker 235693496「On focus, text… |
| 09-25 15:46 | memory-editor 跳顶修复分支 CI 绿（09-25，停止节点）：fix/memory-editor-jump | memory-editor 跳顶修复分支 CI 绿（09-25，停止节点）：fix/memory-editor-jump-to-top @ 13d8e9a8（1 commit，4 文件 +56/−34，基点 main 7… |
| 09-26 11:35 | 工具调用时底部跳动的根因定位（09-26，用户报告「调用工具时跳」） | 36dp 打字状态带本身是稳的（TypingIndicator 内容包固定 36dp Box，空闲=36dp Spacer，ChatIndicators.kt:102/149），带子不会跳。 |
| 09-28 09:07 | B 折叠「重新设计」讨论 + 「只渲染可见部分」虚拟化路线分析（09-28） | 用户反馈：B 折叠生效但形态别扭——只折工具药丸、思考块留在外面；交替结构（思考→工具→思考）视觉上变成「靠近顶部一张折叠行 + 下方一串思考标题墙」。用户提出新方向：能否走「只渲染用户看到的那一部分」（item 级虚拟… |
| 09-28 09:39 | ↳ 工具药丸折叠 revert 合并 main = 8aaad10（09-28，用户拍板「负值交易，按你说的来」） | 决策链：用户判折叠为负值设计（UX 损害 > 性能收益）→ A/B 实测证实 → revert B 合并（3c72bc1a）→ 用户授权「跑完直接合并，检查环节在当初合并时已完成」→ main = 8aaad10（mer… |
| 09-28 09:43 | ↳ 折叠 revert 装包验证闭环（09-28，用户确认） | 用户确认真机安装的是 8aaad10e（折叠 revert 合并后的 main）编译的 APK，运行没有问题。两处欠账随之闭环： |
| 10-17 10:49 | FIX-G 收口：deep-audit-1020 ISSUE-07（memory_rollup 门禁 + provider UA 字段）—— fix/da1020-memory-gate-provider-fields @ fd3acf3，CI #2220 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-G.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-G.m… |
| 10-17 17:18 | FIX-A 收口：deep-audit-1022 ISSUE-01（跨进程媒体 base64 契约，DEFAULT 折行 vs 严格解码）—— fix/da1022-offload-base64-wire @ afbff5e，CI #2238 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-A.md 并执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pro… |
| 10-20 13:04 | FIX-B1 收口（deep-audit-1035 ISSUE-01 + ISSUE-02）：宿主窗口静默空转 + 移动视口极性 —— `fix/dv14-b1-window-viewport` @ `70d7b904`，CI #2379 success，停在分支 | 身份链三方逐字符：基线 main 76488f2dfcbbc79db8eac6df60b7682e187121f6 → 3 提交（df34b84a 生产+出货测试 / 41b5fe00 门 W5e / 70d7b904 … |
| 10-20 19:47 | FIX-A2 收口（deep-audit-1036 / dv15 A2 支）：`fix/dv15-a2-handler-guards` @ `e62e699e`，CI #39414690008 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 单提交 e62e699e5b8c0067c72711b04209f2fde527f4ed → CI run 39414690008（手动 dispatch，11:2… |
| 10-24 09:56 | FIX-A1 收口（deep-audit-1048 / A-01+A-08 P1）：`fix/dv28-a1-openai-toolcall-eof` @ `c17f0200`，CI #40050571513 success，停在分支 | 身份链：基线 eea9f407 → 4 提交（生产 a6d5cd21 +127/−10 / 测试 1d9ac1cf +222 / 门 07159efe +231/−34 = CI 绿 sha / 报告 c17f0200）… |
| 10-24 14:37 | dv29-B4 收口（浏览器会话视口未重放，B-04）：`fix/dv29-b4-browser-viewport-reset` @ `fb6f6321`，分支 CI #2599 绿，停在分支 | 身份链：基线 main b82aa8dc → 4 提交（bbae0ada 生产 +74/−14 · 29bb5ded 测试 +317/−8 · 55d898d8 门 +315/−2 ← CI 绿于此 · fb6f6321… |

## 2. 输入框 / 键盘 / 粘贴

**跨度** 2026-08-04 ～ 2026-10-24 · **36 条** · **状态** 已闭环（09-12 三修复打包 fix/ttfb-thinktag-composer 合并 main @ e1a0b08）

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
| 09-25 15:26 | 用户报告：记忆编辑模块点键盘时光标/内容跳回最顶端。诊断结论（09-25）：Compose 框架已知 bug，非 app | 用户报告：记忆编辑模块点键盘时光标/内容跳回最顶端。诊断结论（09-25）：Compose 框架已知 bug，非 app 代码错——Google issuetracker 235693496「On focus, text… |
| 09-27 14:22 | 键盘意外弹出修复（09-27，分支 CI 绿 = 停止节点，未合并） | 根因（代码级 + AOSP 源码双证据）：ModalBottomSheet/Dialog 是独立 focusable window——打开时主 window 失焦、关闭时重获焦点，焦点回到 composer 的 Text… |
| 10-17 16:53 | FIX-B 收口：deep-audit-1022 ISSUE-02（草稿晋升后子面板仍绑 `__new__` 路由参数）—— fix/da1022-draft-session-binding @ f700db8，CI #2234 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-B.md 并执行。报告 fix-reports/FIX-B.md（8 节）；装置 repo-b/pro… |
| 10-19 08:10 | FIX-A (deep-audit-1029) 收口：I01 草稿单源 + I02 深链穷举，分支 CI 绿 @ e76330e，停在分支 | 身份链：基线 83caac6 → 2 提交（5ca7e1a 修法 + e76330e CI 编译红补丁）→ HEAD e76330e999e7daa8e9aa62a552b3117492351657 == 远端 tip … |
| 10-21 10:34 | FIX-1 收口（deep-audit-1038 / dv17 波）：风控提示无消费者 + 草稿晋升后下载记录指向已删目录 —— `fix/dv17-1-browser-risk-downloads` @ `09f46b602776a83b20fd461e8f1d93bced67733d`，CI #2419（39531036966）success，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（1c6bbbc2 生产 / 261fe80a 测试 / 09f46b60 门）→ LOCAL == ls-remote == CI head_sha = … |
| 10-21 19:29 | FIX-B1 收口（deep-audit-1039 / dv19 面 B）：composer 实时源单一化 —— `fix/dv19-b1-chat-input-single-source` @ `e8bfb52b`，CI #2433 success（attempt 1），停止节点达成，未合并 main | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（e0b84e84 生产 / b9c531b9 测试 / e8bfb52b 门）→ LOCAL == git ls-remote == CI head_sh… |
| 10-23 01:07 | FIX-9 收口（deep-audit-1043 / v22 波 B-03 P1）：`fix/dv23-b3-composer-single-writer` @ `8462e98d`，CI #2510 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 3900f9cd / 测试 a187a2a6 / 门 8462e98d）→ 本地 == ls-remote == CI head_sha = 846… |
| 10-23 11:51 | FIX-7 收口（deep-audit-1044 / dv24 波 B-01+B-02+B-05，P1×3）：`fix/dv24-b1-picker-quicktest-composer` @ `71dc0225f1`，CI #2529 (run `39894855850` / job `120222802096`) success，停在分支 | 身份链三方逐字符：基线 main f94413f5 → 5 提交（f49a541d B-01 生产 / 5ef21819 B-02 生产 / 1529ecae B-05 生产 / 5edf7021 测试 / 71dc02… |
| 10-24 14:58 | FIX-B3 收口（deep-audit-1049 / dv29 波 B-03·B-05·B-06，全 P1）：`fix/dv29-b3-share-print-composer` @ `0f6e6ba4`，CI #2605 success，停在分支 | 身份链：基线 main b82aa8dc → 4 提交（c5f211bc 生产 8 文件 +1008/−58 / ed2405d0 测试 / a183d691 门 3 文件 ← CI 绿于此 / 419ff840+0f6… |

## 3. native 内存 / OOM / 崩溃

**跨度** 2026-08-03 ～ 2026-10-27 · **162 条** · **状态** 持续跟踪（09-13 三件套已合并；SOFT 450 / HARD 1200 阈值待 1–2 周真实数据复核）

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
| 09-22 14:42 | 09-22：应用开发阶段收口 —— 总账梳理 + 档案更新（分支 CI 进行中） | 用户指令：「应用开发告一段落，帮我总结梳理这一段开发的具体情况，然后更新文档」。 |
| 09-23 00:34 | 闭环审计框架落地 + spawn_agent 技能快照坑 | 工具坑（新，已实证） |
| 09-23 19:29 | 上游沙箱/闪退修复对比（2026-09-23 补） | 上游 1.12 沙箱修复（commit df9cb59b）：SeccompFallbackPolicy.kt 新文件+129（seccomp 被拒时降级重试自愈早期启动崩溃，带 JVM 测试） + PersistentS… |
| 09-24 16:36 | 用户反馈：应用已很稳定，release 页的「⚠️ 已知问题与使用边界」（内存飙升警告 + 4 步处理阶梯）过期。定案： | 用户反馈：应用已很稳定，release 页的「⚠️ 已知问题与使用边界」（内存飙升警告 + 4 步处理阶梯）过期。定案：该文案在 .github/workflows/build-apk.yml 的 Publish to … |
| 09-26 15:23 | 日志分析（09-26，用户指派）→ 报告 /var/minis/shared/log-analysis-0926/REPORT.md | 范围 /var/minis/logs 173M（深挖 09-25/26）；背景=高强度开发期，先剥离开发活动信号。核心结论： |
| 09-26 16:19 | 双分支检查 + 合并 main = 8dcb82e8（09-26，用户「检查完没问题就合并」） | 结局：fix/launch-beacon-uptime @ 593dc5b0 + fix/preview-null-overwrite @ 9d52f373 两分支 8 项核查全过（无问题、无需修）→ --no-ff ×… |
| 09-27 13:30 | 沙箱生命周期 5 个硬编码常量改为可调 + 内置提示词补全（09-27，用户拍板「5 个一起上、UI 滑条要、范围按建议」） | 分支 feat/runtime-sandbox-knobs @ a3dc9027（基 main 8dcb82e8，17 文件 +358/−23），CI run 36296621337 completed/success … |
| 09-27 17:18 | 两问题核查（思考标签泄漏 + 选择框复制失灵）→ /var/minis/shared/work/leak-0927/REPORT.md（09-27，基线 aeffcef9，未改代码） | 任务：用户报两个问题（① 像 …defects</thinking> 这样的内部文本「露出来」，且发生在正在跑的会话里；② 长按自动选择弹出的选项框里点「复制」有时候失效），「两个一起查」。 |
| 09-27 19:47 | 修「思考泄漏」孤儿闭标签 → 分支 fix/thinktag-orphan-close @ df8bdf90（09-27，用户「修吧」，未合并） | 结论：机制定案 —— 不是「开标签被 chunk 劈开」（扫描器早就有跨 chunk 半标签缓冲 maxOpenTagPrefixLen()，且已有测试 buffers partial open tag across c… |
| 09-30 19:52 | 上游崩溃类修复对比定案（09-30，用户问「上游解决沙箱崩/闪退没有」） | GH#341 闪退（WebView 渲染进程死亡 → 整 app SIGTRAP abort）：上游 v1.14 用 ui/webview/WebViewRenderProcess.kt helper（79 行，只打日志… |
| 09-30 23:59 | fastio 真机测试完成（09-30 深夜，装包=含 fastio 最新版） | 测试全绿（沙箱终端 agent 路径，PersistentShell）： |
| 10-09 12:49 | 备份 OOM（512MB largeHeap 打满）根因与修复 — 2026-10-09 | 症状：真机报 写入失败: Failed to allocate a 4480 byte allocation … <1% of heap free after GC（target/growth limit 5368709… |
| 10-09 13:23 | 分支审查完成：fix/backup-chat-eager-load-oom-1009（无次生问题，§79 登记） | 对象：fix/backup-chat-eager-load-oom-1009 @ 71f605e（远端仅此分支+main），2 commits（33c1c06 lazy 逐会话加载 → 71f605e 先过 metada… |
| 10-09 13:32 | 备份 OOM 修复合并收口（2026-10-09） | 合并完成（用户放行「修好了，合并吧」）：三项核查齐（diff 3 文件逐行读无夹带、0 冲突标记；分支 CI 37884504189 head_sha 逐字符 = 71f605e；独立证据=影子 harness 三路对照… |
| 10-12 20:25 | T5 派发：MarkdownParser 差分探针 + 重复派发事故（2026-10-12 晚） | 派发：MarkdownParser.kt（639 行纯 Kotlin object 零 import，String→List<Block>，宿主可测；已有覆盖仅 numbered list + KaTeX 上限）→ T5… |
| 10-12 20:40 | T5 完成：MarkdownParser 差分探针（PR #11，CI 绿，停在分支未合并） | 分支 test/md-parser-probe-1012 @ 85f3535c（2 commits：910ff2b 修复 / 85f3535 测试 58 例），PR #11（base main ← 分支），CI run … |
| 10-12 20:41 | ↳ PR 合并：test/md-parser-probe-1012 进 main（用户放行「没问题就合并」） | PR 合并：test/md-parser-probe-1012 进 main（用户放行「没问题就合并」） |
| 10-12 20:42 | T5 收口：MarkdownParser 探针完成 + 2 个 P1 修复已合并（2026-10-12 晚） | 产出：64 条探针语料 + 58 例测试（MarkdownParserBlockStructureTest，468 行）+ 2 个 P1 缺陷修复（①heading 尾部 # 双向：## C# 内容被吃、## title… |
| 10-14 09:27 | v1.14 移植 ROI 分析：条带级挑拿（2026-10-14） | 背景：用户令「建议跟哪些 + 投入产出比」。对 v1.14（678 文件 +104K/−6K）做逐文件三态分类（fork vs 上游）：11 可直拿 / 150 两边都改 / 472 fork 没有——整域跟版不可能（f… |
| 10-14 15:35 | 验证：复现装置实跑旧代码 25/25 轮丢响应（server trace 证 4 条响应全发出）+ 每次超时稳定泄漏 + | 验证：复现装置实跑旧代码 25/25 轮丢响应（server trace 证 4 条响应全发出）+ 每次超时稳定泄漏 +1 读线程；新代码 0/25 丢、0 泄漏、stderr-crash 附上下文。scan 21/21… |
| 10-14 21:34 | FIX-RV-E 完成：E1 API33 readNBytes 崩溃 + E2 技能更新 id + E4 视频高度 no | FIX-RV-E 完成：E1 API33 readNBytes 崩溃 + E2 技能更新 id + E4 视频高度 no-op（fix/rv1014-ui-small @ dff1ddf，CI #2083/3857062… |
| 10-14 21:36 | ↳ FIX-RV-D 完成：D1 会话克隆 media 重映射 + D2 KeyRoulette 存量明文迁移（fix/rv | FIX-RV-D 完成：D1 会话克隆 media 重映射 + D2 KeyRoulette 存量明文迁移（fix/rv1014-fork-keys @ e39c66b，CI #2084 绿） |
| 10-15 18:48 | FIX-A6-2 收口：精确缩放解码窗口改回 `< 2 × maxEdge`，分支 CI #2126 绿（2026-10-15） | 任务：读 shared/work/verify-audit2-1015/fix-tasks/FIX-A6-2.md 执行。修 audit2 第二道门发现的行为回归（PERF-03 的修法把 (maxEdge, 2×max… |
| 10-15 18:55 | audit2 修复批次收口：六支合并 main `ba9ed437`（2026-10-15，用户令「检查，没问题就合并，有问题就修」+「完成之后再更新文档，包括云端的」） | 输入：全仓审计报告第二道门核实后的 6 份任务书（shared/work/verify-audit2-1015/fix-tasks/FIX-A{1..6}.md，基线 main 891d755）。 |
| 10-15 20:26 | audit3 第二道门核实 + 七支派发（2026-10-15，基线 main `ca8b95d1`） | 输入：用户丢来 全量源码级深度架构、安全与性能审计报告（13 项：3×P0 / 4×P1 / 1×P2 / 3×架构 / 2×性能-内存），说「按流程来吧」。产物目录 /var/minis/shared/work/aud… |
| 10-15 21:52 | FIX-D 收口：F-05 取消桥前移 + F-06 内存回收不重试（fix/audit3-cancel-retry-1015 @ a9631bf9，CI #2133 绿，停在分支） | 任务书 shared/work/audit3-1015/fix-tasks/FIX-D.md；报告 fix-reports/FIX-D.md；基线 main ca8b95d1；两提交 b76a0c5e（本体）+ a963… |
| 10-16 08:52 | crash-1016 闪退诊断：列表分支吞缩进围栏 + `\(\)` 空 latex → androidx appendInlineContent require 崩 | 现象：08:31:58 / 08:32:22 两次闪退（crash-.log，同一栈），版本 1.0.0+2152（main 04e74c53）。异常 IllegalArgumentException: alternat… |
| 10-16 09:44 | crash-1016 修复交付：列表项吞缩进围栏 + `\(\)` 空 latex（分支 `fix/1016-md-list-fence-inline-math` @ `25e5400`，CI #2153 绿，停在分支） | 任务：用户「修」（承接上一轮的闪退诊断）。基线 main 04e74c53325a（= 设备版本 1.0.0+2152 的 head_sha，逐字符一致）。报告 shared/work/crash1016-fix/REP… |
| 10-16 10:07 | FIX-D 收口：deep-audit-1016 执行闸门三件——4a heavy permit 泄漏回滚 + 4b runInterruptible 可取消等待 + 4c 主线程 GC 分发（fix/da1016-exec-gate @ edb45cbd，CI #2158 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-D.md 立刻执行（基线 main 04e74c53）。报告 fix-reports/FIX-D.md；装置 fix-d/pr… |
| 10-16 11:14 | VERIFY-DEVICE-1016 真机验证（设备包 1.0.0+2164，用户令「这个应用包含全部修改，开始验证」） | 身份链闭合：设备 base.apk sha256 36b7f59e…45f5d（14,351,449 B，装于 11:03:51）== CI run #2164（id 38842323044，conclusion suc… |
| 10-16 14:47 | VERIFY-DEVICE-1017 完成：deep-audit-1017 并集树真机验证（run #2174，身份链闭合） | 身份链：设备包 1.0.0+2174 base.apk sha256 6bc3c3bb…7e98（14,353,469 B）== CI run #2174 artifact 逐字节一致 = integration/da1… |
| 10-16 16:38 | deep-audit-1016c 启动：第二道门核实 + 8 支派发（用户令「按流程吧」，附新审计报告 12 项） | 输入：附件 shared-8c9ee41c.md（170KB / 2645 行，ISSUE-01..12）。基线 main e3ef74545bcc（= 1017 收口后最新树，报告声明 35 门/176 夹具与之吻合）… |
| 10-16 17:58 | FIX-H 收口：OffloadRssProbe 锁范围（deep-audit-1016c ISSUE-11）—— fix/da1016-rssprobe-lock-scope @ c18d1b4，CI #2179 绿，停在分支 | 修法：record() 的 synchronized(st) 原来只护 4 个字段自增；LEAK-SUSPECT 检查-置位、govern 判定、累计复位全在锁外（NativeOffload.kt Semaphore(2… |
| 10-16 18:16 | FIX-F 收口：deep-audit-1016c ISSUE-09A/09B（调度日 settle 泄漏 + 裸日期/DST 边界）—— fix/da1016-schedule-date-bounds @ c6df69c0，CI #2182 绿，停在分支 | 修法：09A = finalizeRun 的 completeRun 日期实参从 settle 时刻 LocalDate.now() 改为 fire 时刻捕获（Dispatch data class 增 schedule… |
| 10-16 19:39 | VERIFY-DEVICE-1016c 真机验证完成（2026-10-16 19:26–19:45，用户令「开始验证」）—— 4 支取得判据 · 3 支未触发 · 1 新装置发现 | 身份链闭合：设备 1.0.0+2185 base.apk sha256 27eef513…5681（14,362,657 B）== CI #2185 artifact 逐字节一致（branch integration/d… |
| 10-16 22:21 | FIX-G 收口：deep-audit-1018 ISSUE-11（OAuth 回调双解码 + socket FD 泄漏，P2）—— fix/da1018-oauth-callback @ d94cf63，CI #38912447230 绿，停在分支 | 修法两半：①parseCallbackQuery(uri.rawQuery) 纯函数（单次解码，key/value 各 URLDecoder.decode(raw.replace("+","%2B"))，WebDavCl… |
| 10-17 07:38 | FIX-I 收口：deep-audit-1019 ISSUE-09（BrowserRssProbe/ProviderRssProbe 锁范围）—— fix/da1019-rssprobe-lock-scope @ ace1887，CI #2204 绿，停在分支（2026-10-17） | 任务书 shared/work/deep-audit-1019/fix-tasks/FIX-I.md；报告 fix-reports/FIX-I.md；装置 repo-i/probe/（durable）。基线 main e… |
| 10-17 10:31 | FIX-H 收口：deep-audit-1020 ISSUE-08（打印路径无界读 OOM，P2）—— fix/da1020-print-bounded-read @ a59510c，CI #2212 绿，停在分支（2026-10-17） | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-H.md；报告 fix-reports/FIX-H.md；装置 repo-h/probe/（durable：extract.py… |
| 10-17 11:50 | 真机验证：deep-audit-1020 八支修复（设备 1.0.0+2221 = CI #2221 / 试合树 bdc6d68） | 身份链闭合：设备 base.apk sha256 029be18c…81db97（14,370,617 B，装于 11:26:45）与 CI run #2221（id 38997214449）artifact 逐字节一致… |
| 10-18 00:39 | FIX-E 收口：deep-audit-1024 ISSUE-05（MODEL_ENTRY_DETAIL 路由二次 decode，P2）—— fix/da1024-model-entry-route-no-double-decode @ e0ad364，CI #39048800163 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md（193 行 8 节齐全）；装置 re… |
| 10-18 00:44 | FIX-H 收口：deep-audit-1024 ISSUE-08（Reset All 后 UI 不刷新 + 内存 session 授权/拒绝未清，P2）—— fix/da1024-offload-permission-reset-sync @ db52d99，CI #2262 绿，停在分支 | 身份链：基线 main f688c97c6877e3b801eeacd33d58c2371d1b803f → 单提交 db52d99786f64171fcb4abe4643a60036787764a；远端 tip == … |
| 10-18 10:00 | FIX-H 交付收口：deep-audit-1025 ISSUE-08（挂载探针 + 日志清理离开主线程）—— `fix/da1025-mount-probe-io` @ `d66c7a6`，CI #39087410687 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-H.md（P2，8 节报告齐）。基线 main bcb6569。交付 3 文件：2 生产（+32/−5）+ 1 门脚本扩展（238… |
| 10-22 15:46 | VERIFY-2476 真机窗口：攒着的欠账一次性过 11 条 PASS + 3 条新发现（app `1.0.0+2476`） | 触发：用户「现在这个应用是包含所有的修改的」「你把他们做了吧」。装机自核 = versionName=1.0.0+2476 / versionCode=220002476 / lastUpdateTime 15:17:5… |
| 10-22 16:27 | F1 收口（COLLECT-R1 同族普查第 3 处）：`fix/verify-2476-rssprobe-finally` @ `3a740442`，CI #2477 success，停在分支 | 身份链：基线 main 5dd278da → 单提交 3a740442 → 本地 == ls-remote == CI head_sha = 3a7404421163fd8deef84e35e103fd00dd71a31… |
| 10-23 11:54 | FIX-4 收口（deep-audit-1044 / v23 波 A-05 P1）：`fix/dv24-a4-config-session-writethrough` @ `90a0a064`，CI #2530 success（源码树 = `9a002abf`），停在分支 | 身份链：基线 main f94413f5 → 4 提交（b7979890 生产 / 0f5897e7 测试 / 2451a671 门 / 9a002abf→90a0a064 报告回填）。CI 绿于 9a002abf，HE… |
| 10-24 13:11 | VERIFY-2597 第 3 轮验证（CLI/工具面）+ 崩溃缺陷登记 —— 2026-10-24 12:57–13:2x | ★ 新缺陷（P1，已登记 backlog §2597-VERIFY-3）：minis-sessions-cli send --session <不存在的 id> 两次把 app 进程打崩（12:57:02 / 12:57… |
| 10-27 00:43 | U2 图像链收口：分支 `fix/image-chain-oob` @ `9d8d70f77`（绿 sha `db8c0b202`），CI #2759 绿，停分支（2026-10-27 凌晨） | 四笔全修，1 提交生产 6 文件 +231/−87 + 测试 2 文件（ChatImageRestoreTest 8 臂 + DailyLogRollupMetricsTest 3 臂）。 |

## 4. 沙箱 / rootfs / PRoot / 终端

**跨度** 2026-08-03 ～ 2026-10-27 · **149 条** · **状态** 稳定（09-05 rootfs 事件日志 + apk 自动恢复已上线）

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
| 09-23 17:20 | P1 三条批量处置：两条已被修、一条方向与 backlog 原判相反（09-23） | 用户指令：「p1，那就把他们都修了吧」（P1 = §27a 图片门 / §27c(2) canResume 检查 / §29 换源重试）。 |
| 09-23 18:33 | 全量 round-trip 测试进 main（09-23 分派任务，分支 CI 绿停在分支） | 全量 round-trip 测试进 main（09-23 分派任务，分支 CI 绿停在分支） |
| 09-23 18:45 | ↳ round-trip 分支核验 + 合并 main = 051a6b35（2026-09-23） | 用户指令：「任务完成了，检查核验，没问题就合并」= 核实 + 合并放行。 |
| 09-23 19:29 | 上游沙箱/闪退修复对比（2026-09-23 补） | 上游 1.12 沙箱修复（commit df9cb59b）：SeccompFallbackPolicy.kt 新文件+129（seccomp 被拒时降级重试自愈早期启动崩溃，带 JVM 测试） + PersistentS… |
| 09-26 09:13 | Filterrr/RikkaMinis 仓库调查（09-26，用户问「他改了什么」）：①不是本仓库 fork——API | Filterrr/RikkaMinis 仓库调查（09-26，用户问「他改了什么」）：①不是本仓库 fork——API fork=False、compare 404、fork 网络脱离；与我们的共同祖先只有 OpenMi… |
| 09-27 00:02 | RikkaMinis 观众画像 & 上游情报调查（09-26，用户发起：查关注者/fork 者的关注图谱当"信息富集地"） | 产物：/var/minis/shared/audience-research-20260926/REPORT.md（395 行）+ audience-0926/（56 份原始 JSON + collect.py/anal… |
| 09-27 13:30 | 沙箱生命周期 5 个硬编码常量改为可调 + 内置提示词补全（09-27，用户拍板「5 个一起上、UI 滑条要、范围按建议」） | 分支 feat/runtime-sandbox-knobs @ a3dc9027（基 main 8dcb82e8，17 文件 +358/−23），CI run 36296621337 completed/success … |
| 09-30 12:31 | ★ 沙箱怪癖：shell_execute 输出管道会吞掉「裸 null 行」（09-30 实证） | 现象：printf 'a\nnull\nb\n' → 输出只有 a、b；加 \| cat -A 后正常显示 null$。 |
| 09-30 19:52 | 上游崩溃类修复对比定案（09-30，用户问「上游解决沙箱崩/闪退没有」） | GH#341 闪退（WebView 渲染进程死亡 → 整 app SIGTRAP abort）：上游 v1.14 用 ui/webview/WebViewRenderProcess.kt helper（79 行，只打日志… |
| 09-30 21:25 | 终端性能基准全景（09-30，用户问「你能操作的终端性能怎么样」，代理已关所以网络项缺） | 环境：8 核可见（affinity 0-4,6-7，nproc=6）、内核 5.10.236-android12、内存 10.9GB 总/5.4GB 可用；PRoot ptrace 追踪确认（TracerPid 非零）；… |
| 09-30 21:53 | syscall 税缓解方案定案（09-30，用户拍板方向：Termux 排除（摩擦力），提升 app 内终端本身） | 新证据（一手）： |
| 09-30 22:35 | minis-fastio offload handler 完成（09-30 深夜，分支 feat/minis-fastio-offload @ d2003f75，CI 绿未合并） | 停止节点：分支 feat/minis-fastio-offload，commit d2003f75，CI run #1891（id 36728328060）success @ head_sha == 本地 HEAD ==… |
| 09-30 23:00 | 审查对象：云端分支 feat/minis-fastio-offload @ 3e496953（fix 提交：尾随 sym | 审查对象：云端分支 feat/minis-fastio-offload @ 3e496953（fix 提交：尾随 symlink 不跟随 + 接受 rm -rf），相对 main 26b7ce1a 共 2 commits… |
| 09-30 23:06 | du 字节口径争议裁决（两会话矛盾，沙箱实测定案）：fastio 开发会话报「handler du 比 busybox | du 字节口径争议裁决（两会话矛盾，沙箱实测定案）：fastio 开发会话报「handler du 比 busybox du -sb 系统性偏大 dirs×3452」；交付会话记忆说「busybox 计目录、已对齐 bu… |
| 09-30 23:59 | fastio 真机测试完成（09-30 深夜，装包=含 fastio 最新版） | 测试全绿（沙箱终端 agent 路径，PersistentShell）： |
| 10-01 00:52 | fastio 第二期完成（10-01，分支 feat/fastio-primitives-2 @ d74a49c1，CI 绿未合并） | 停止节点已到：分支 feat/fastio-primitives-2，commits 57693ba6(主体) + d74a49c1(CI 修复)，远端 == 本地 == d74a49c1ca51 逐字符，工作树 cle… |
| 10-05 09:42 | 任务书 D 执行完成：压缩遥测 + SUPERSEDED 条款（fix/compact-telemetry-superseded-1005，CI 进行中） | 状态：两个 commit 已推远端 + CI 已 dispatch（run 37252315441），独立会话执行任务书 D 全文。 |
| 10-13 01:26 | RikkaMinis main @ad4a4beb 独立复验（2026-10-13，用户要求） | 三源核对：远端 main = ad4a4be = CI run 38279260922 head_sha，conclusion success，无假绿。 |
| 10-13 16:08 | 债务清理批次（fix/debt-cleanup-1013，PR #13，6 commits @08f818f3c） | 范围：审查自报 4 条（D-1/D-2/D-3/D-4）+ backlog §89 择条 4 条（F-2/F-5/F-9/F-10），CI：scan-gate 38367123483 + build-apk 383671… |
| 10-14 13:52 | 报告：/var/minis/shared/sweep-1014/TRIAGE.md；任务书 tasks/FIX-{1.. | 报告：/var/minis/shared/sweep-1014/TRIAGE.md；任务书 tasks/FIX-{1..9}.md；报告落 reports/。核实基线 main @ d5d2be59f（浅克隆 /var/… |
| 10-14 14:55 | 任务书 shared/sweep-1014/tasks/FIX-4.md（用户令「读这个文件并立刻执行」）；工作树 sw | 任务书 shared/sweep-1014/tasks/FIX-4.md（用户令「读这个文件并立刻执行」）；工作树 sweep1014-fix/f4/。 |
| 10-14 16:06 | FIX-3 完成：FastioTar + 只读挂载守卫（fix/sweep1014-sandbox-io @ bf01735cd，CI #2027 绿） | 报告：/var/minis/shared/sweep-1014/reports/FIX-3.md；装置 /var/minis/shared/work/sweep1014-fix/f3/jvm/（extract_f3.py… |
| 10-14 17:26 | FIX-R14 完成：model-use B24 stdin 位置 prompt 替代 + cwd 透传（fix/swe | FIX-R14 完成：model-use B24 stdin 位置 prompt 替代 + cwd 透传（fix/sweep1014-modeluse @ 4200c406，CI #2039 绿） |
| 10-15 01:05 | §98 文档同步收口 + 沙箱膨胀数据清理（2026-10-15） | 文档同步：dev-history 三件套重建（1734 条 → 24 saga，脱敏 3 遍 NONE）→ 分支 docs/dev-history-sync-1015 → --no-ff 合入 main e5e733b（… |
| 10-15 07:20 | audit-1015 FIX-D 会话（native/jieba/WebApp/IPC 修复完成） | 分支 fix/audit-1015-native-misc（333d7cb + 9815cf4），CI run 38652238091 绿，按任务书停在分支未合并。 |
| 10-16 10:31 | deep-audit-1016 FIX-C 交付收口：沙箱路径归一化 / 只读写守卫 / boot 门（`fix/da1016-sandbox-path` @ `cce8919`，CI #2163 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-C.md 立刻执行（基线 main 04e74c5）。报告 shared/work/deep-audit-1016/fix-r… |
| 10-17 10:50 | FIX-C 收口：deep-audit-1020（BrowserUse/Fastio 相对路径 + tar 前缀）—— fix/da1020-offload-relpath-tar @ be8def62，CI #2219 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-C.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-C.md；装置 repo-c/probe/… |
| 10-17 12:32 | 沙箱存储清理：/var/minis 11.2G → 580M（用户报「缓存数据又上去了」，2026-10-17） | 构成与处置（Android app cache 本身只有 23.9MB，真正的积累在沙箱）： |
| 10-17 13:20 | FIX-F 收口：deep-audit-1021（WebApp asset URL 单源 + AddToHome 清理闭环）—— fix/da1021-webapp-asset-url-cleanup @ ab9c247，CI #2226 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-F.md（8 支并发派发，我领 FIX-F）。报告 fix-reports/FIX-F.md；装置 r… |
| 10-17 17:23 | FIX-F 收口：deep-audit-1022 ISSUE-06（镜像 ViewModel 跨线程快照写 + 主线程 IO + 测速自动选源半途）—— fix/da1022-mirror-vm-thread @ 0d4f11d，CI #2240 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-F.md 并执行（先证再改）。基线 main ba4daf69（克隆后逐字符核对）。报告 fix-re… |
| 10-18 20:55 | FIX-D 收口：deep-audit-1027 ISSUE-05（RootfsVM 两次 verifyIntegrity 主线程直调）—— fix/da1027-d-rootfs-io @ a7aa249，CI #39127888788 绿，停在分支 | 修法：repairRootfs(ctx) 整体并入一个 viewModelScope.launch，两处 manager.verifyIntegrity()（基线 :173 点击回调内 / :191 launch 内 M… |
| 10-19 20:54 | deep-audit-1032 收口完成（用户令「先更新文档，然后再合并」）：main `c6d426a` + 档案 2012 条 + 7 支待清 | 身份链终态：基线 main 09b6c12（= 报告 2c8c174 + docs，代码零差异）→ 5 支（A c6978d1 / B 6c46f96 / C ad24155 / D af900f2 / E a2106a… |
| 10-20 13:04 | FIX-B1 收口（deep-audit-1035 ISSUE-01 + ISSUE-02）：宿主窗口静默空转 + 移动视口极性 —— `fix/dv14-b1-window-viewport` @ `70d7b904`，CI #2379 success，停在分支 | 身份链三方逐字符：基线 main 76488f2dfcbbc79db8eac6df60b7682e187121f6 → 3 提交（df34b84a 生产+出货测试 / 41b5fe00 门 W5e / 70d7b904 … |
| 10-20 15:47 | §VERIFY-2388 真机验证收口（CLI/深链面全绿 + S34 新缺陷入册 + UI 视觉面交用户） | 身份：app 1.0.0+2388（15:11 装 = main CI #2388，含 dv14 九支并集 71882a8e）。工作区 shared/work/verify-2388/，回执已入 backlog（267→… |
| 10-21 18:46 | fastio 权限门 BYPASS 收口（deep-audit 外单点）：分支 `fix/fastio-permission-bypass-1021` @ `81c70b68`，CI #2430 首轮 success，停在分支 | 触发：用户批「fastio 首用弹窗不对——这不是敏感权限，该像沙箱/终端一样；甚至该自动做」→ 主会话判定「批评成立、但『自动做』被否决」→ 用户令「方案 B，开始做」。 |
| 10-22 12:39 | FIX-B2 收口（deep-audit-1041 / dv21 波 B-02 + B-07）：`fix/dv21-b2-rootfs-log` @ `2d6b43ac`，CI #2462 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（8f0007d7 生产 / 2967fd7f 测试 / 2d6b43ac 门）→ 本地 == ls-remote == CI head_sha = 2d6… |
| 10-22 13:09 | FIX-B4 收口（deep-audit-1041 / dv21 波 B-05+B-06+B-08）：`fix/dv21-b4-render-decode` @ `db111d34`，CI #2469 success，停在分支 | 身份链：基线 843ab917 → 4 提交（cb3717ff B-05 / 09ecc327 B-06 / 485cad2b B-08 / db111d34 B-08 CI 红修复）→ 本地 == ls-remote（… |
| 10-22 15:46 | VERIFY-2476 真机窗口：攒着的欠账一次性过 11 条 PASS + 3 条新发现（app `1.0.0+2476`） | 触发：用户「现在这个应用是包含所有的修改的」「你把他们做了吧」。装机自核 = versionName=1.0.0+2476 / versionCode=220002476 / lastUpdateTime 15:17:5… |
| 10-22 19:30 | FIX-A2 收口（deep-audit-1042 / A-02 P1）：`tarCreate` 顶层根循环三缺口 — `fix/dv22-a2-fastio-tar-toplevel` @ `5a144b2c`，CI #39768460593 success，停在分支 | 身份链：基线 main 5dd278da → 5 提交（生产 d4aa69cf / 测试 c573481f / 门 9535d2fc / 报告 f8b1590d + 5a144b2c）。本地 == ls-remote =… |
| 10-22 19:45 | FIX-B5 收口（deep-audit-1042 / dv22 波 B-05 P1）：`fix/dv22-b5-rootfs-progress-replay` @ `d503eeb2`，CI #39768763850 success，停在分支 | 身份链三方逐字符：基线 main 5dd278da → 3 提交（9b1a7a14 生产 +161/−21 · b0c3a5e4 测试 +216 · d503eeb2 门 +280）→ 本地 == ls-remote =… |
| 10-23 00:43 | FIX-7 收口（deep-audit-1043 / B-01 P1）：Rootfs 修复结算把继承来的 `Failed` 当成本次判决 — `fix/dv23-b1-rootfs-repair-settle` @ `84e4f9ac`，CI #39813094967 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 f1425db6 / 测试 cbf2845b / 门 84e4f9ac）→ 本地 == ls-remote == CI run.head_sha =… |
| 10-24 09:56 | FIX-A1 收口（deep-audit-1048 / A-01+A-08 P1）：`fix/dv28-a1-openai-toolcall-eof` @ `c17f0200`，CI #40050571513 success，停在分支 | 身份链：基线 eea9f407 → 4 提交（生产 a6d5cd21 +127/−10 / 测试 1d9ac1cf +222 / 门 07159efe +231/−34 = CI 绿 sha / 报告 c17f0200）… |
| 10-24 14:31 | VERIFY-2597 第 5 轮：CLI 可脚本化 6 条全部收尾 + 一个新发现 —— 2026-10-24 14:1x–14:4x | 总账（台账 ledger-1040-1048.json 已同步）：86 条中 CLEARED 9 / PARTIAL 11 / BLOCKED 8 / PENDING 58（84 口径：剩 58 条未动，全为 UI 35… |
| 10-25 05:58 | wide-1024 支 09-browser-webview 收口：5 门见证加固，16/16 全抓住，CI #2632 绿（2026-10-25 早） | 分支 fix/meta-wide-09-browser-webview @ 5f384731（源码树），报告提交 1059156e（fix-reports/ 5 份，源码与绿 sha 等价：git diff --name… |
| 10-27 06:41 | 沙箱缺陷召回修复收口（分支 `fix/defect-recall-seeds` @ `9d7c0c85b`，报告 `acdeae6b8`，CI `40409678884` 绿，停分支）—— 2026-10-27 | 用户说「修吧」→ 走分支隔离全流程收口。改动 2 文件 +187/−15（SandboxDefectRegistry.kt +96、测试臂 15→20）。 |

## 5. fastio offload / syscall 税

**跨度** 2026-08-20 ～ 2026-10-26 · **41 条** · **状态** 已合并 main（三期原语 + du 口径 P1 + F1 全部合并；未验证面 = 真机高并发耗时与发热）

**叙事**：09-18 syscall 税首次量化（~200µs/op，纯计算≈原生）→ 09-30 定案缓解分层（L0 行为约定 / L1 fastio offload 立项，Termux 方向被否）→ minis-fastio handler（du + rm + GuestPathMapper 前缀守卫）→ du 字节口径 P1（busybox vs GNU 两个 du 同名不同语义）→ gate 闭环（ASK_ONCE + 会话桶缓存）→ 第二期 find/grep/cp/mv → 第三期 tar（slip 三面 + 权限行）→ 10-01 分支审查 F1（tar 成员名用解析后绝对路径）修后合并。**主题：把文件密集原语从 proot 税里搬出去；每期都用「逐字节 parity + 双臂探针 + 变异负控」验收。**

| 日期 | 标题 | 摘要 |
|------|------|------|
| 08-20 09:29 | 打点定位落地：native-offload RSS 归因（2026-08-20 会话三） | 用户质疑"彻底解决 60% 多概率是否值得"，我在成本收益分析后建议"先打点定位再决定投入"，用户拍板执行。 |
| 08-22 16:48 | [dual-appid] lab 包闪退根因修复（native-offload abstract socket 冲突） | 继续 dual-appid 前置工作。小号 lab APK（applicationId=com.openminis.app.lab，已生效）安装后闪退，logcat -b crash 抓到 P0根因： |
| 09-18 18:37 | 09-18 深夜：PRoot 慢 8-10x 的机制与实测（syscall 税） | 机制：沙箱被 app 自带的 libproot.so 用 ptrace 跟踪（沙箱内 /proc/self/status 实测 TracerPid=13403、Seccomp=2）。seccomp 过滤=2 意味着只有"… |
| 09-18 18:43 | 09-18 深夜：查清 native_offload 的判定条件（结论：不适合当重活加速器） | 判定链：execve 的 basename ∈ 编译期常量名单（sandbox/OffloadHandlerCatalog.kt，19 项）→ libproot 的 C 扩展截获 → abstract unix sock… |
| 09-30 21:25 | 终端性能基准全景（09-30，用户问「你能操作的终端性能怎么样」，代理已关所以网络项缺） | 环境：8 核可见（affinity 0-4,6-7，nproc=6）、内核 5.10.236-android12、内存 10.9GB 总/5.4GB 可用；PRoot ptrace 追踪确认（TracerPid 非零）；… |
| 09-30 21:53 | syscall 税缓解方案定案（09-30，用户拍板方向：Termux 排除（摩擦力），提升 app 内终端本身） | 新证据（一手）： |
| 09-30 22:01 | L1 fastio 任务接力准备完成（09-30 深夜，用户将开新会话专门执行） | 交接件（唯一入口，新会话读完即可开工）：/var/minis/shared/handoff-fastio-0930/HANDOFF.md |
| 09-30 22:35 | minis-fastio offload handler 完成（09-30 深夜，分支 feat/minis-fastio-offload @ d2003f75，CI 绿未合并） | 停止节点：分支 feat/minis-fastio-offload，commit d2003f75，CI run #1891（id 36728328060）success @ head_sha == 本地 HEAD ==… |
| 09-30 23:00 | 审查对象：云端分支 feat/minis-fastio-offload @ 3e496953（fix 提交：尾随 sym | 审查对象：云端分支 feat/minis-fastio-offload @ 3e496953（fix 提交：尾随 symlink 不跟随 + 接受 rm -rf），相对 main 26b7ce1a 共 2 commits… |
| 09-30 23:06 | du 字节口径争议裁决（两会话矛盾，沙箱实测定案）：fastio 开发会话报「handler du 比 busybox | du 字节口径争议裁决（两会话矛盾，沙箱实测定案）：fastio 开发会话报「handler du 比 busybox du -sb 系统性偏大 dirs×3452」；交付会话记忆说「busybox 计目录、已对齐 bu… |
| 09-30 23:36 | du 字节口径 P1 修复完成（分支 fix/fastio-du-gnu-byte-parity @ 5397bf51， | du 字节口径 P1 修复完成（分支 fix/fastio-du-gnu-byte-parity @ 5397bf51，未合并）：①walkForSize 删 preVisitDirectory 目录字节；②delete… |
| 09-30 23:45 | du 字节口径 P1 已合并（main = e47c6560，父 0af354ac + 5397bf51，--no-ff | du 字节口径 P1 已合并（main = e47c6560，父 0af354ac + 5397bf51，--no-ff）：合并前核查三项全过——①分支 CI 36736749668 success 且 head_sha… |
| 09-30 23:59 | fastio 真机测试完成（09-30 深夜，装包=含 fastio 最新版） | 测试全绿（沙箱终端 agent 路径，PersistentShell）： |
| 10-01 00:07 | fastio 第二期接力准备完成（09-30 深夜，扩 find/grep/cp/mv） | 任务书（唯一入口）：/var/minis/shared/handoff-fastio-0930/HANDOFF-2.md |
| 10-01 00:52 | fastio 第二期完成（10-01，分支 feat/fastio-primitives-2 @ d74a49c1，CI 绿未合并） | 停止节点已到：分支 feat/fastio-primitives-2，commits 57693ba6(主体) + d74a49c1(CI 修复)，远端 == 本地 == d74a49c1ca51 逐字符，工作树 cle… |
| 10-01 00:55 | 审查 feat/fastio-primitives-2（第二期 find/grep/cp/mv）—— 发现 1 个合并前该修的 P1 | 对象：云端分支 feat/fastio-primitives-2 @ d74a49c1（相对 main e47c6560，9 文件 +1566/−48），CI run 36745583553 success @ head… |
| 10-01 01:22 | 修复 feat/fastio-primitives-2 的 `mv --force`（commit 98edf12，CI 绿停止节点） | 改动（4 文件 +92/−8，同一分支补 commit d74a49c1 → 98edf12）： |
| 10-01 01:27 | fastio 第二期真机验证完成（10-01 凌晨，装包=第二期最新版） | 测试结果总表（handler 内部 elapsed vs 含税基线，全部一手实测）： |
| 10-01 01:30 | fastio 第三期打包任务书完成（10-01 凌晨，find 优化 + tar 原语 + 顺路小项） | 任务书（唯一入口）：/var/minis/shared/handoff-fastio-0930/HANDOFF-3.md |
| 10-01 01:34 | fastio 第二期已合并 main（10-01，merge commit 20c0c16b） | 合并：--no-ff，merge commit 20c0c16b，父 = e47c6560（main）+ 98edf12b（分支 tip = 修复后的第二期）。 |
| 10-01 02:45 | fastio 第三期完成（10-01，分支 feat/fastio-primitives-3 @ 4bb8ab42，CI 绿未合并） | 停止节点已到：CI run #1900 Build RikkaMinis Android APK completed success，head_sha 4bb8ab428232d517e64c5f46ad8c8f1201… |
| 10-01 07:00 | fastio 第三期分支审查完成（10-01，feat/fastio-primitives-3 @ 4bb8ab42，报告 shared/work/review-fastio-p3-1001/REPORT.md） | 判定：P0=0；F1=P1（tar create 成员名用解析后绝对 guest 路径，建议修后再并，用户拍板）；F2/F4/F8=P2 登记；F3/F6/F7=P3 登记。slip/零写入/gate 安全面全绿。 |
| 10-01 07:51 | fastio 第三期 F1 已修并合并 main（10-01，merge debf06e9，main=debf06e9） | 用户三连拍板：核对（「核对一下，没问题就修」）→ 修复 → CI 绿后合并（「跑完之后就合并」「合并触发后就不用等了」）。 |
| 10-01 07:54 | fastio 第三期真机验证完成（10-01 凌晨，装包=第三期最新版，全绿） | 结果总表： |
| 10-01 08:31 | 第一档确定性批次 tier1-batch-1001（10-01）→ 已交付，停在分支 | 筛选：从 backlog 1363 行逐条打开源码核实（不是照抄文本），筛出符合「确定性修复池」6 门槛的 6 条 → 用户拍板打包修。报告 shared/work/deterministic-candidates-10… |
| 10-14 16:06 | FIX-3 完成：FastioTar + 只读挂载守卫（fix/sweep1014-sandbox-io @ bf01735cd，CI #2027 绿） | 报告：/var/minis/shared/sweep-1014/reports/FIX-3.md；装置 /var/minis/shared/work/sweep1014-fix/f3/jvm/（extract_f3.py… |
| 10-14 17:07 | FIX-R10 完成：B15+P1 bashism 误开 heredoc / B16 fastio mv 跨设备回退 / | FIX-R10 完成：B15+P1 bashism 误开 heredoc / B16 fastio mv 跨设备回退 / P5 pty 越界（fix/sweep1014-sandbox-low @ 2eb71c38，CI… |
| 10-14 17:13 | ↳ FIX-R15 完成：calendar B25a Instances 相交列举 + B25b all-day UTC 锚 | FIX-R15 完成：calendar B25a Instances 相交列举 + B25b all-day UTC 锚定（fix/sweep1014-calendar @ e4c6e83，CI #38538412677… |
| 10-15 21:13 | FIX-A 收口：audit3 F-01 FastioTar 根条目 tar-slip（P0）— 分支 CI #2129 绿，停在分支未合并（2026-10-15） | 任务：shared/work/audit3-1015/fix-tasks/FIX-A.md（基线 main ca8b95d1）。分支 fix/audit3-fastiotar-1015 @ 894ff37d（2 文件 +… |
| 10-17 07:29 | FIX-C 收口：deep-audit-1019 ISSUE-03（FastioTar USTAR 字段 off-by-one + 归档拒绝 exit 0）—— fix/da1019-fastio-tar-bounds @ 96e9f54，CI #2202 绿，停在分支 | 任务书 shared/work/deep-audit-1019/fix-tasks/FIX-C.md；报告 fix-reports/FIX-C.md；装置 repo-c/probe/（durable：jvm/harnes… |
| 10-17 10:50 | FIX-C 收口：deep-audit-1020（BrowserUse/Fastio 相对路径 + tar 前缀）—— fix/da1020-offload-relpath-tar @ be8def62，CI #2219 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-C.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-C.md；装置 repo-c/probe/… |
| 10-17 11:29 | 审计背景包 v3 更新（吸收 deep-audit-1020）— 2026-10-17 | 输入：用户令「又完成了一波修复，开始更新」。本轮 = deep-audit-1020：8 支修复（A models-cache 漏参 / B shizuku argv / C offload 相对路径+tar 前缀 / … |
| 10-20 12:43 | FIX-A2 收口（deep-audit-v14 ISSUE-03 P1）：`FastioTar.writeRegular` 先写 header 后开文件 → 单个坏文件毁整包 —— `fix/dv14-a2-tar-stream` @ `446dd9e1`，CI #2371 绿，停在分支 | 身份链三方逐字符：基线 76488f2d → 8cc278da（生产 + 出货测试）/ 446dd9e1（门扩展）→ 本地 == 远端 == CI head_sha = 446dd9e160e8d0381e6a933b3… |
| 10-21 17:21 | fastio 权限门设计批评（用户拍板中）—— 2026-10-21 | 触发：用户见 fastio_rm 首用弹窗（17:11 gate awaiting → allow_session），批「这不是敏感权限，类似沙箱/终端，不该要权限，甚至应该自动做」。 |
| 10-21 18:46 | fastio 权限门 BYPASS 收口（deep-audit 外单点）：分支 `fix/fastio-permission-bypass-1021` @ `81c70b68`，CI #2430 首轮 success，停在分支 | 触发：用户批「fastio 首用弹窗不对——这不是敏感权限，该像沙箱/终端一样；甚至该自动做」→ 主会话判定「批评成立、但『自动做』被否决」→ 用户令「方案 B，开始做」。 |
| 10-22 19:30 | FIX-A2 收口（deep-audit-1042 / A-02 P1）：`tarCreate` 顶层根循环三缺口 — `fix/dv22-a2-fastio-tar-toplevel` @ `5a144b2c`，CI #39768460593 success，停在分支 | 身份链：基线 main 5dd278da → 5 提交（生产 d4aa69cf / 测试 c573481f / 门 9535d2fc / 报告 f8b1590d + 5a144b2c）。本地 == ls-remote =… |
| 10-23 00:30 | FIX-A6 收口（deep-audit-1043 / A-07 P1）：`fix/dv23-a6-fastio-special-files` @ `2bd96269`，CI #39811912340 success，停在分支 | 身份链：基线 main 79ef6d59 → 3 提交（生产 3efb8f7d / 测试 a8abc3f1 / 门 2bd96269）→ 本地 == ls-remote == CI head_sha = 2bd96269… |
| 10-24 00:58 | FIX-A5 收口（deep-audit-1047 / A-07 P1）：5 个 offload handler 的 `--help` 恒 exit 2 | 身份链：基线 main 88ef9b27 → 5ec73d8f（生产 5 文件 +15/−5）→ 77248c41（测试 +78）→ dba13f46（门 +406/−2，CI 绿于此）→ f57111ce + 4199… |
| 10-24 23:57 | fastio 不被调用诊断 + 提示词修复（fix/prompt-fastio-rg @ 27c951db，CI #2628 绿，停分支） | 用户问题：AI 不主动用 minis-fastio，总用慢的原生方式（场景：清理修过程积累的数据）。 |
| 10-26 12:28 | B7-t06 收口：fastio_tar_bounds + marker_boundary 两门 12 条见证全 CAUGHT（分支 `fix/b7-fastiotar-marker`，门提交 `9101923ab`，报告 `fc5f728ac`，CI #2714 绿 @ 门提交，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+972/−481），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{lexer,ast,v… |
| 10-26 19:20 | R3-t14 收口：offload_cli_args + fastio_tar_bounds 两门判据加固（6/6 见证反攻 CAUGHT，分支 `fix/r3-offload-fastio` @ `c0039db84`，CI #2735 绿，停分支） | 基线 main f0f14d46d。改动面 = 2 个门脚本（+1017/−2）+ 报告，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 / 夹具 344 不变。 |

## 6. 流式回答中断 / 恢复 / 取消

**跨度** 2026-08-03 ～ 2026-10-27 · **187 条** · **状态** 已闭环（09-07 收口，含停止按钮竞态 + 多会话真并发真机验证）

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
| 09-22 14:44 | 09-22 补：文档类改动**不该**触发 CI（用户纠正，已取消） | 用户指出：文档类不用触发 CI。核对后两层都对，已取消 run 35695746144（conclusion cancelled）。 |
| 09-22 15:44 | 09-22：backlog 触发条件全量核查（「攒着的还有要修的吗」） | 设备版本 = 1.0.0+1745（14:18:28 装）。按修复门纪律逐条核 backlog 触发条件： |
| 09-23 01:08 | F5 族闭环审计完成（provider 流控族，loop-audit-0922） | 报告：shared/loop-audit-0922/reports/F5-provider-flow.md。6 环：L4=1 / L3=2 / L2=3 / L1=0 / L0=0。 |
| 09-23 17:20 | P1 三条批量处置：两条已被修、一条方向与 backlog 原判相反（09-23） | 用户指令：「p1，那就把他们都修了吧」（P1 = §27a 图片门 / §27c(2) canResume 检查 / §29 换源重试）。 |
| 09-25 15:12 | §38-B 收官 + 09-25 真机验证（用户确认 app 已装最新包 1.0.0+1800） | §38-B 收官 + 09-25 真机验证（用户确认 app 已装最新包 1.0.0+1800） |
| 09-25 17:39 | §流式恢复竞态修复分支 CI 绿（09-25，停止节点） | §流式恢复竞态修复分支 CI 绿（09-25，停止节点） |
| 09-25 22:04 | 09-25 全量回归审计：今天 24 提交 / 33 文件，无功能级 bug，2 条次生开销 P2 + 5 条边界登记 | 范围：基线 2022b0c5（昨 21:12）→ main 50b399c9，git log 实测今天进 main 的是 24 个提交 / 33 文件 +1511/−267（不止记忆里的 8 条 merge——上午还有 … |
| 09-26 15:23 | 日志分析（09-26，用户指派）→ 报告 /var/minis/shared/log-analysis-0926/REPORT.md | 范围 /var/minis/logs 173M（深挖 09-25/26）；背景=高强度开发期，先剥离开发活动信号。核心结论： |
| 09-26 18:03 | 从日志逆推信息流 — 黑盒系统辨识（09-26，用户元问题） | 任务：用户问「日志是什么（多视角）？能否只从日志逆推应用的输入/输出/信息流（含所有设置类输入）」→ 完成首次黑盒系统辨识。 |
| 09-28 01:50 | 任务 C 完成（offload 无收益重试收敛）→ 分支 `perf/offload-convergence` @ `2b5689e`，CI `36337271291` success，未合并 | 做了什么（ChatContextWindow.kt，1 文件 +87/−23）： |
| 10-01 16:58 | compact 安静优先修复收尾（10-01，分支 fix/compact-quiet-first-1001 @ 4d1 | compact 安静优先修复收尾（10-01，分支 fix/compact-quiet-first-1001 @ 4d1c9a25，用户拍板任务结束） |
| 10-02 15:24 | 停止按钮杀不到压缩 P1 已修（10-02，分支 fix/compact-cancel-on-stop-1002 @ 4bc1352，CI #1908 绿停） | 改动（5 文件 +231/−8，停在分支未合并）：①compactAll 的 launch 存 compactJob，cancelStream 连带 cancel（9 处调用点全统一——语义都是"当前轮工作立刻停"，fi… |
| 10-05 08:27 | 任务书 C 执行完成：压缩摘要截断守卫 + 吵闹候选 60s 预算（fix/compact-truncation-guard-1005，CI 绿停） | 状态：分支 CI 绿 @ tip，确定性停止节点已达成，未合并（合并权在用户）。 |
| 10-08 19:23 | T-edit-resend-attachment-loss-1008（长按编辑重发丢附件） | 现象：用户带附件发消息 → 长按气泡「重新编辑」→ 改完重发 → 指令在、附件消失。 |
| 10-08 23:57 | send 派发竞态诊断（D2 空历史，第 3 次撞上） | 根因（日志实锤）：minis-sessions-cli send 创建会话时，用户指令 append 到 DB 与 run 的历史加载并发——输者 runAgentLoop 以 historySize=0 启动（D2 实… |
| 10-12 14:59 | 切模型后「等待中但按钮已停止」= switchModelAndRerun 的 claim 竞态（2026-10-12 真机实测定位） | 症状（用户报告）：发消息→provider 慢→切模型→界面仍显示等待/生成中，但底部按钮已回到「发送」（停止态）→用户再点重试。 |
| 10-12 15:26 | 修复「切模型后流式状态被抹」竞态 → 分支 fix/streaming-claim-race-1012 @ 41f024e7（CI #1970 success） | 根因（真机日志 + 代码双证）：_isStreaming 清除权归属不清。旧 job 的 finally 用「streamJob === 自己 + jobEpoch == streamingClaimEpoch」双门判断… |
| 10-12 15:36 | 云端分支核查+合并：fix/streaming-claim-race-1012（2026-10-12，用户令「没问题就直接合并」「触发了就不用等」） | 分支 fix/streaming-claim-race-1012 @ 41f024e7（父=main tip f9c374ca，5 文件 +180/−91，全在 ui/chat：ChatModelRouting / Ch… |
| 10-12 20:02 | 「回答中途突然停止」排查结论（2026-10-12 晚，用户报其他会话） | 今天日志共 11–12 次真实中途取消（runAgentLoop CANCELLED → streamJob killed），全部是 cancelStream() 主动取消，0 崩溃/0 错误/0 OOM（今天 erro… |
| 10-12 23:58 | NextPlayer 定制二轮交付：首页胶囊 + Debug 字样 + 体积之谜（2026-10-12，用户 A+B 拍板后全落地） | 首页胶囊：MediaPickerScreen.kt topBar 槽内 NextTopAppBar(...) 之后并列加 TopLevelPageChipsRow()（Scaffold 内部就是 Column，两个 co… |
| 10-13 09:32 | 胶囊消失诊断 + 修复 fix/cancel-stale-publish-1013（2026-10-13） | 用户报告：模型执行任务正在调用工具时发消息打断，有一定概率正在调用的工具胶囊消失。 |
| 10-14 22:30 | RV-1014 真机验证完成（用户令「这个应用包含所有修改，开始验证」）—— 抓到一条系统性交付缺口 | 身份链（先钉死验证对象）：设备包 1.0.0+2094（装于 22:12:53）sha256 d15ea964…6604 = CI run #2094（38575026136，head_sha a7123cf82，8 支… |
| 10-14 23:10 | 根治 §97：内置技能资产落盘改内容哈希门控（fix/skill-asset-hash-gate @ 87db4a3，CI #2095 绿，停在分支未合并） | 用户令：选「根治」（二选一：即时 bump 版本 / 根治改内容哈希）。基线 main a7123cf82，3 文件 +351/−34。 |
| 10-15 07:36 | FIX-C 完成：AgentLoop 状态机 + 沙盒 Shell（audit-1015，分支 fix/audit-1015-loop-sandbox @ 3c5f916b，CI #2104 绿，停在分支未合并） | 用户令：读 /var/minis/shared/audit-1015/fix-tasks/FIX-C.md 并立刻执行。基线 main e5e733b3（核对一致）。 |
| 10-15 14:53 | §100 云端分支核查+合并：fix/audit-verify-1015 → main 4b4eb54a8b（2026- | §100 云端分支核查+合并：fix/audit-verify-1015 → main 4b4eb54a8b（2026-10-15） |
| 10-15 15:00 | ↳ 文档更新收尾（同会话续） | 本地：挂载 笔记/RikkaMinis开发档案/ 三件套已刷新（1747 条 / 74 天，重建→脱敏→SAGAS→复核 NONE→结构校验：行首围栏 64 偶数、anchors==header==1747、outOrd… |
| 10-15 16:44 | FIX-A1 收口：audit2 四项 P1 修复，分支 CI #2118 绿（2026-10-15） | 用户令：读 shared/work/verify-audit2-1015/fix-tasks/FIX-A1.md 并立刻执行 → 「构建完了」。 |
| 10-15 21:14 | FIX-F 收口：F-08 会话路径解析不再因拒绝而全局回退（fix/audit3-sessionpath-1015 @ 6a20a481，CI #2128 绿，停在分支未合并） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-F.md 执行。基线 main ca8b95d1。commits：c0f2afa9（本体）+ 6a20a481（同族第 8 站点 + … |
| 10-15 21:52 | FIX-D 收口：F-05 取消桥前移 + F-06 内存回收不重试（fix/audit3-cancel-retry-1015 @ a9631bf9，CI #2133 绿，停在分支） | 任务书 shared/work/audit3-1015/fix-tasks/FIX-D.md；报告 fix-reports/FIX-D.md；基线 main ca8b95d1；两提交 b76a0c5e（本体）+ a963… |
| 10-15 22:32 | audit3 收口完成：七支合并 main `984cbdd801dc` + 并集树 CI #2142 绿 + 文档归档进 main（2026-10-15，用户令「他们的任务都完成了」→「跑完了」） | 结果：七支（FIX-A..G）全部合并 → main 984cbdd801dc；并集树 CI run #2142（id 38749571877）success @ 984cbdd801dc，head_sha 逐字符一致、… |
| 10-15 23:33 | deep-audit-1015 第二道门核实 + 5 支派发（用户令「走流程吧」，附新审计报告 14 项） | 输入：用户附件 rikkaminis_deep_audit_report.md（1388 行，BUG-01..09 + ARCH-01..03 + PERF-01/02）。基线 main e10fb2aa53fb。工作树… |
| 10-16 00:01 | FIX-A 收口：deep-audit ARCH-01（T3 副作用安全重试接线，P0）+ BUG-09（二次截断）（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-A.md 立刻执行（基线 main e10fb2aa）。报告 fix-reports/FIX-A.md；装置 shared/w… |
| 10-16 10:07 | FIX-D 收口：deep-audit-1016 执行闸门三件——4a heavy permit 泄漏回滚 + 4b runInterruptible 可取消等待 + 4c 主线程 GC 分发（fix/da1016-exec-gate @ edb45cbd，CI #2158 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-D.md 立刻执行（基线 main 04e74c53）。报告 fix-reports/FIX-D.md；装置 fix-d/pr… |
| 10-16 12:12 | deep-audit-1017 第二道门 + 7 支派发（2026-10-16，基线 main `e748b14`） | 输入：附件 shared-4dc0dfc3.md（1993 行，ISSUE-01..10）。工作目录 shared/work/deep-audit-1017/（repo 浅克隆 + verify1..6.sh/out +… |
| 10-16 14:47 | VERIFY-DEVICE-1017 完成：deep-audit-1017 并集树真机验证（run #2174，身份链闭合） | 身份链：设备包 1.0.0+2174 base.apk sha256 6bc3c3bb…7e98（14,353,469 B）== CI run #2174 artifact 逐字节一致 = integration/da1… |
| 10-16 17:44 | FIX-C 收口：deep-audit-1016c ISSUE-03A/B/C（browser 并发三缺陷）—— fix/da1016-browser-concurrency @ c8a44bf，CI #2176 绿，停在分支 | 任务：读 shared/work/deep-audit-1016c/fix-tasks/FIX-C.md 并立刻执行（基线 main e3ef74545bcc）。报告 shared/work/deep-audit-101… |
| 10-16 17:46 | FIX-G 收口：deep-audit-1016c ISSUE-10(A) sysinfo 合并窗口线程竞态（fix/da1016-sysinfo-race @ 1785682e，CI #2178 绿，停在分支） | 任务：shared/work/deep-audit-1016c/fix-tasks/FIX-G.md（用户令「读这个文件并立刻执行」；中途换代理重克隆）。报告 fix-reports/FIX-G.md（216 行）；装置… |
| 10-16 22:36 | FIX-H 收口完成：deep-audit-1018 ISSUE-12a/b（:modelservice 重复读盘 + 无界线程，P2）—— fix/da1018-modelsvc-io @ 5fce91199109947504653926e30c274c8f5bb64b，CI #38914243668 绿，停在分支 | 任务书 shared/work/deep-audit-1018/fix-tasks/FIX-H.md；报告 fix-reports/FIX-H.md（14890 B，10 节齐全、待填 0）；装置 repo-h/repo… |
| 10-16 23:22 | FIX-A 收口：deep-audit-1018 ISSUE-01（P0 截断工具调用）+ ISSUE-02（P1 子代理 reasoning 丢失）+ ISSUE-12c（P2 叠楼守卫）—— fix/da1018-turn-integrity @ 00026c3 两提交，CI #2195 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-A.md（先证再改）。报告 fix-reports/FIX-A.md；装置 repo-a/probe/（durable，含 c… |
| 10-17 07:46 | FIX-G 收口：deep-audit-1019 ISSUE-07（KatexWebViewPool ready 竞态 + ChatViewModelStore 别名表锁外读，P1）—— fix/da1019-katex-store-race @ d440e9b，CI #2199 绿，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-G.md；报告 fix-reports/FIX-G.md（10964 B，14 节齐全）；装置 repo-g/repo/probe… |
| 10-17 10:44 | FIX-E 收口：da1020 ISSUE-05 语音链路四块（fix/da1020-voice-cancel-ua @ 97102a49，CI #2218 绿，停在分支） | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md；装置 repo-e/repo/p… |
| 10-18 10:09 | FIX-C 交付收口：deep-audit-1025 ISSUE-03（a11y 双表失步，P1）—— `fix/da1025-a11y-status-verbs` @ `8b73bee`，CI #2277 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md（9 节）；装置 repo-c/probe/{prefix,j… |
| 10-18 10:09 | FIX-E 交付：deep-audit-1025 ISSUE-05（EnvVarEntry.group 统一配置面漏层）—— fix/da1025-envvar-config-group @ 3eda8d9，CI #39087430292 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-E.md（先证再改）；报告 fix-reports/FIX-E.md（8 节 / 0 占位）；装置 repo-e/probe/（p… |
| 10-19 16:38 | FIX-B 收口（deep-audit-1031 ISSUE-02 [P1] 权限槽位并发竞态 + 会话 grants 生命周期） | 身份链：基线 2bc07ec → 分支 fix/da1031-b-permission-slots，tip 75b84d09af8dcf553b5ac602e41f75fadfa8aceb（两提交：6e29173 主体 … |
| 10-22 07:51 | deep-audit-1040（v19 波）收口完成：9 份全绿 → 并集 `integration/dv20` @ `4417999f`，并集 CI #2455 ALL GREEN，**未合并 main** | 九份构成（用户口径）：dv20 八支（a1 备份脱敏+语音成员 / a2 provider repo / a3 orphan busy / a4 挂载接线 / b1 settings live / b2 mcp offm… |
| 10-22 12:18 | FIX-A1 收口（deep-audit-1041 / v20 波 A-01 P1）：`fix/dv21-a1-config-registry` @ `75ec3bf5`，CI #2457 success 判读 10/10，停在分支 | 缺陷：ConfigRegistry.resolveField 用 path.split('.', limit = 3) 切集合子路径，把含点的复合 id 截断成 segments[1]。复合模型 id 形态 <insta… |
| 10-22 14:46 | deep-audit-1041 收口完成：并集 `7e0e9b58` + CI #2475 ALL GREEN（含一次测试竞态修复） | 收口链路：10 支四方核对 10/10（本地==远端==CI head_sha，全 success）→ 并集 13a2c5da（61 文件 +7316/−444，逐支逐字节 61/61 一致，冻结面 0/交集 0/残留 … |
| 10-23 11:37 | FIX-3 收口（deep-audit-1044 / dv24 波 A-03+A-04 P1×2）：`fix/dv24-a3-headless-send-contract` @ `8a4a38a5`，CI #2527 success，停在分支 | 身份链三方逐字符：基线 main f94413f5 → 5 提交（8f8da7f7 A-03 生产 / 4008328e A-04 生产 / f7fc1a70 测试 / 469bb7cf 门 criterion 15 /… |
| 10-23 11:51 | FIX-7 收口（deep-audit-1044 / dv24 波 B-01+B-02+B-05，P1×3）：`fix/dv24-b1-picker-quicktest-composer` @ `71dc0225f1`，CI #2529 (run `39894855850` / job `120222802096`) success，停在分支 | 身份链三方逐字符：基线 main f94413f5 → 5 提交（f49a541d B-01 生产 / 5ef21819 B-02 生产 / 1529ecae B-05 生产 / 5edf7021 测试 / 71dc02… |
| 10-23 14:20 | deep-audit-1045（v24 波）启动：第二道门 15/15 成立 0 驳回 + P0×1 + 失真 2 → 11 支派发完成（回读 11/11 在跑） | 输入：Rikkaminis_Audit_Report_V24_A.md（78,946 B，A-01..A-07）+ V24_B.md（102,201 B，B-01..B-08）。基线 main 1d44ec90（ls-r… |
| 10-23 17:43 | deep-audit-1045（v24 波）全批闭环：main `381d1026` + main CI #2553 success（Publish 真执行，v1.0.0+2553）→ 清理 14/14 分支 + 14/14 worktree | 用户在收口中两次干预：①「怎么分支又一次跑了，不是应该并入吗？」——点破我为 2 条测试文件编译警告（user.dir 可空性，P2）不必要地重跑了一轮并集 CI（装置比例失当；正确做法是登记不修，或合并后由 main … |
| 10-23 20:10 | FIX-G10 收口（deep-audit-1046 / S-10 P1）：`fix/dv26-g10-tts-cancel-guard` @ `eb7b9edd`，CI #39947618231 success，停在分支 | 身份链：基线 main f7aba218 → 73215401 生产+测试 / 188858b6 门（CI 绿于此）/ eb7b9edd 报告。git diff --name-only 188858b6 HEAD -- … |
| 10-23 20:33 | FIX-D7 收口（deep-audit-1046 / S-07 P1）：`fix/dv26-d7-webview-release-latch`，CI 两次绿，停在分支 | 缺陷：KaTeXView.kt:390-393 与 FilePreviewScreen.kt:490-494 的 AndroidView.onRelease 都调 wv.removeCallbacks(null)——AO… |
| 10-24 09:56 | FIX-A1 收口（deep-audit-1048 / A-01+A-08 P1）：`fix/dv28-a1-openai-toolcall-eof` @ `c17f0200`，CI #40050571513 success，停在分支 | 身份链：基线 eea9f407 → 4 提交（生产 a6d5cd21 +127/−10 / 测试 1d9ac1cf +222 / 门 07159efe +231/−34 = CI 绿 sha / 报告 c17f0200）… |
| 10-24 14:31 | VERIFY-2597 第 5 轮：CLI 可脚本化 6 条全部收尾 + 一个新发现 —— 2026-10-24 14:1x–14:4x | 总账（台账 ledger-1040-1048.json 已同步）：86 条中 CLEARED 9 / PARTIAL 11 / BLOCKED 8 / PENDING 58（84 口径：剩 58 条未动，全为 UI 35… |
| 10-24 14:40 | dv29 FIX-A1 收口（Anthropic 工具截断计数器）—— 2026-10-24 14:5x | 分支 fix/dv29-a1-anthropic-tool-truncation @ 绿 sha 828ff4ee（报告回填至 51895a70，源码树等价 0 diff）· CI run 40069749029 job… |
| 10-24 15:16 | FIX-B2 收口（deep-audit-1049 / B-02 P1）：`fix/dv29-b2-offmain-compose-state` @ `82373a43`，CI #40071869246 success，停在分支 | 缺陷：4 个写点（报告说 3 处，实为 4 —— 画廊两个标志各一处）在后台线程直写 Compose remember{mutableStateOf} 状态：ImageGalleryViewer.kt:324/360（a… |
| 10-25 06:22 | wide-1024 支 02（provider 对称门）收口：2 门 6 见证全抓，CI #2640 绿，停分支 | 分支 fix/meta-wide-02-provider-symmetry @ 6ff65aad（CI 绿树 02aad4c5，报告回填后 git diff 02aad4c5 HEAD -- . ':(exclude)f… |
| 10-25 12:19 | wide3-12 收口：4 门 12 见证全部加固，分支 `fix/meta-wide3-12-media-mount-trace` @ `1e3b9ca2`，CI #2648 绿（停分支） | 任务 TASK-12（meta-wide3 第三批 / 组 B）：把 4 道门的判据从「某物存在」升级到「该物的效果真的达成」，12 条见证全部固化成 --selftest 负向臂。生产代码 0 改动，scan.sh/t… |
| 10-25 18:16 | WIDE4-05 收口：IME-inset 门 6 条见证全数反攻，分支 `fix/wide4-05-ime-inset` @ `372baa29`，CI #40182328104 绿，停分支 | 基线 dbab7a3e（= 19e70dab + 两个共享 Kotlin 原语模块）。只改 1 个门脚本（scripts/scan/ime_inset_guard.py +423/−156），生产 .kt 0 行、sca… |
| 10-25 18:28 | wide4-04 收口：三门判据从「拼写存在」改到「效果达成」，19 见证 13 新抓 + 6 保持抓，分支 CI #2674 绿（2026-10-25） | 分支 fix/wide4-04-skillfile-loopstat-provider @ 6422102e（代码提交 9cf49582 = CI 绿树，报告回填后 diff --name-only 9cf49582..… |
| 10-26 10:13 | B6-t07 收口：modeluse_proto_single_source + thinking_rule_editor_preserve 两门 13 条见证全 CAUGHT（分支 `fix/b6-modeluse-thinkrule` @ 门提交 `ae0cdce9c` + 报告 `12e879c00`，CI #2703 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 ae0cdce9c（2 文件 +1237/−719，生产 .kt 0 行、scan.sh/test_sc… |
| 10-26 11:42 | B7-t01 收口：chat_session_id_binding + outcome_known_chain 两门 12 条见证全翻红（分支 `fix/b7-sessionbind-outcome` @ 报告 `1c2df293d`，CI #2710 绿，停分支） | 基线 base/batch7-shared（e145ec961）。门提交 926073cfc（2 文件 +795/−379，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 1… |
| 10-26 12:07 | B7-t03 收口：parts_json_funnel + modelsvc_single_read 两门 12 条见证全 CAUGHT（分支 `fix/b7-t03-partsjson-modelsvc` @ `cc92f6e43`，CI #2711 绿，停分支） | 基线 e145ec961。改动面 = 2 个门脚本（parts_json +682/−276，modelsvc +449/−122），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{le… |
| 10-26 13:10 | B7-t12 收口：webview_holder_url_source + chat_menu_prefs_listener 两门 12 条见证全翻红（分支 `fix/b7-webviewholder-chatmenu` @ `0dfb86e2b`，门提交 `378659481`，CI #2720 绿，停分支） | 基线 base/batch7-shared @ e145ec961。门提交 378659481（2 文件 +828/−437，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-27 00:30 | U4 收口：`file://` 契约「跨界编码 / 内部解码」（分支 `fix/fileurl-contract` @ `8ddb12e57`，绿 sha `621602d43`，CI #2757 绿，停分支） | 基点 T2 分支 8d00395af（含 T2 全部提交）。改动 8 文件 +450/−40：生产行为 4 文件（未越任务书白名单，全逐条登记）+ 3 测试 + 1 门脚本。 |
| 10-27 01:25 | U1 收口：三原语第二轮 A1-A4 修复（分支 `fix/primitive-ast-round2` @ `5bdbc48ff`，CI #2761 绿，停分支） | 基线 1a4eff14d（T1 收口后，本支延续而非基于 main）。三个提交：47f988cfa（A1-A4）→ 626985db9（branch_value 补正）→ 5bdbc48ff（报告）。改动面 3 文件 +… |

## 7. 思考 / 推理泄漏

**跨度** 2026-08-08 ～ 2026-10-26 · **98 条** · **状态** 已闭环（09-12 打包合并；同类新方言需重新进表）

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
| 09-22 15:44 | 09-22：backlog 触发条件全量核查（「攒着的还有要修的吗」） | 设备版本 = 1.0.0+1745（14:18:28 装）。按修复门纪律逐条核 backlog 触发条件： |
| 09-22 16:30 | 09-22：核实 `fix/tool-schema-required-empty-array` → 合并 main = `28e0df0a`（release CI 35705134017 已触发） | 用户指令：「检查核实一下分支上的那个修改」→ 核实后「记进去吧，并且已经跑完了，也要合并」。 |
| 09-23 08:47 | 思考强度档位与后台对不上（sensenova 诊断，09-23） | 用户报告：商汤科技/sensenova-6.8-flash-lite 后台支持五六档思考强度，应用聊天界面只有 中/低/高/自动。 |
| 09-23 10:00 | 思考档位折叠三处修复（分支 CI 绿 @722215ab，停在分支未合并） | 分支 fix/thinking-effort-gateway-truth @ 722215ab，CI run 35807594101 success（head_sha 与本地逐字符一致），全步骤 success + Pu… |
| 09-23 10:16 | 思考档位分支合并 main = 13e1c2d3（三道核查通过） | 用户指令：「检查核验云端那个分支，没问题就合并。」= 合并放行。 |
| 09-23 11:30 | §35/§36 thinking 档位两层修复完成（2026-09-23，分支 CI 绿停在分支） | 分支：fix/thinking-effort-gateway-truth @ 7b7f12cd，CI run #1760 success，head_sha 与本地逐字符一致，停在分支未合并（合并权在用户）。 |
| 09-23 11:59 | 审阅 `fix/thinking-effort-gateway-truth` → 抓到 1 处真实缺陷 → 修 → 合并 main `801f40b4`（09-23） | 用户指令：「检查验证，核实一下云端的分支的修改，是否有问题？如果有的话进行修改，修正，然后合并。」+「触发了，就收尾」。 |
| 09-24 14:44 | 思考强度冷启动变关调查（09-24，等用户分辨观察） | 用户报告：完全关闭 app 重进，原本调的思考强度变成关。实读 main @ 665877a 全链路： |
| 09-24 15:05 | 思考强度跨对话记住分支 CI 绿（09-24，停止节点） | 用户澄清需求：思考强度要跨对话记住（新/旧会话统一）；现状是 per-session，新草稿从 OFF 起步，分组默认没设时重开新对话显示关。用户拍板要「统一记住」。 |
| 09-24 15:06 | 分支：feat/thinking-global-remember @ 2d860b5e（1 commit，4 文件 +6 | 分支：feat/thinking-global-remember @ 2d860b5e（1 commit，4 文件 +66/−2，基点 main 665877a5）。 |
| 09-24 20:39 | 今日（09-24）诚实边界真机验证闭环：用户确认全部真机验证通过——滑杆可见吸附手感、存储页进出卡顿改善、思考强度跨对话 | 今日（09-24）诚实边界真机验证闭环：用户确认全部真机验证通过——滑杆可见吸附手感、存储页进出卡顿改善、思考强度跨对话记住、备份 3 份轮转。今日 8 笔合并（051a6b35..7b76c034）至此三源取二闭环（C… |
| 09-26 12:02 | senseaudio 档位缺陷修复收口（09-26，停止节点达成，未合并）：分支 fix/senseaudio-effo | senseaudio 档位缺陷修复收口（09-26，停止节点达成，未合并）：分支 fix/senseaudio-effort-truth @ 7675f2cd14d399b843c1143ecadb62b0f7bfdf2… |
| 09-26 13:01 | 真机日志取证能力（重大发现，2026-09-26） | app 的日志在沙箱里直接可读：/var/minis/logs/minis-YYYY-MM-DD.log（主进程）、minis-YYYY-MM-DD.modelservice.log（worker，34MB）、launc… |
| 09-26 18:03 | 从日志逆推信息流 — 黑盒系统辨识（09-26，用户元问题） | 任务：用户问「日志是什么（多视角）？能否只从日志逆推应用的输入/输出/信息流（含所有设置类输入）」→ 完成首次黑盒系统辨识。 |
| 09-27 17:18 | 两问题核查（思考标签泄漏 + 选择框复制失灵）→ /var/minis/shared/work/leak-0927/REPORT.md（09-27，基线 aeffcef9，未改代码） | 任务：用户报两个问题（① 像 …defects</thinking> 这样的内部文本「露出来」，且发生在正在跑的会话里；② 长按自动选择弹出的选项框里点「复制」有时候失效），「两个一起查」。 |
| 09-27 19:11 | §3.1「工具框消失」结案（09-27 晚，用户两张截图 + 设备实测）→ 真凶是思考文本落进正文 | 结论翻转：不是丢块，也不是重进时的消息合并主因 —— 是模型的动作/思考文本被打进 message content，把 pill 顶出视野。 |
| 09-27 19:47 | 修「思考泄漏」孤儿闭标签 → 分支 fix/thinktag-orphan-close @ df8bdf90（09-27，用户「修吧」，未合并） | 结论：机制定案 —— 不是「开标签被 chunk 劈开」（扫描器早就有跨 chunk 半标签缓冲 maxOpenTagPrefixLen()，且已有测试 buffers partial open tag across c… |
| 09-27 20:00 | 合并 fix/thinktag-orphan-close → main = 02a935a2（09-27，用户「没问题就合并，有问题就修」） | 核查 8 项全过 → 合并。分支 1 commit / 2 文件 +127/−4（基 7a5f54b9）：scanThinkTags 在 insideTag=false 时把孤儿闭合标签（</thinking> 等，开标… |
| 09-27 20:57 | 审计对象：02a935a2 + efac6f86（思考标签孤儿闭标签 Part A + Part B） | 最近两次合并审计 + 文档更新收口（09-27，用户「检查最近两次修改 + 更新文档」） |
| 09-27 22:54 | ↳ 第一档确定性修复完成（09-27） | 用户批准修复第一档三项：①§41-1 stripOrphanThinkClosers 去掉 lowercase() 坐标错位，改用原文 regionMatches(ignoreCase=true)；②恢复 parseRo… |
| 09-27 23:00 | ↳ 合并 fix/deterministic-tier1-cleanups → main（09-27） | 用户同意「没问题就合并，有问题修」；合入 fix/deterministic-tier1-cleanups @ 8369a9dfbe9c 到 main，--no-ff merge = c10f567522f75b62fd… |
| 09-27 23:15 | ↳ 文档三件套第二次重建 + 合并 main = 3a36c697（09-27 深夜，用户「更新一下文档…可以直接合并」）： | 文档三件套第二次重建 + 合并 main = 3a36c697（09-27 深夜，用户「更新一下文档…可以直接合并」）：三回归测试（test_rebuild/test_sanitize/test_sagas）先全 PAS… |
| 09-28 11:28 | TierFlow 兼容性实测（2026-09-28） | 对 https://tierflow.cn/v1/chat/completions 的实测：OpenAI SSE、tools、reasoning_content、assistant reasoning_content 回… |
| 09-28 12:45 | 思考折叠对照 rikkahub → 抄「实时标题 + 思考时长」（09-28，用户「能够实锤就抄」） | 实锤结论（真实数据，非推测）：①rikkahub 机制核实（extractThinkingTitle 取最后一个整行加粗 + showThinkingTitle = loading && title != null + … |
| 09-28 12:50 | 思考标题分支核查发现（2026-09-28） | 核查 feat/thinking-title-and-duration @ 4fe96bbfa62f14ee5751c6bb7c1276005d7d45e7：CI run 36377954011 success、head… |
| 09-28 13:22 | 思考标题分支：核查 → 修复 → 合并 main = 4ef343f6（2026-09-28，用户「没问题就合并」+「打包修」） | 对象：feat/thinking-title-and-duration @ 4fe96bbf（思考头部实时标题 + 阶段时长）→ 本会话修两块缺口 → 4d8e1aa9 → --no-ff merge = 4ef343f… |
| 09-28 14:24 | 云端分支检查：feat/thinking-header-toggles（09-28） | 检查时该分支已无进行中 CI。远端/本地均为 65b26bb24bdee70ad6724b37c15bd73e0f512e47；run #1873 36384832132 同 SHA completed/success，… |
| 09-28 14:24 | 思考头部两个显示开关完成 → 停止节点（2026-09-28，用户拍板「保持字符数，时长默认不显示」） | 分支 feat/thinking-header-toggles @ 65b26bb2（基于 main bd42e3df，12 文件 +300/−11），未合并（合并权在用户）。 |
| 09-28 14:44 | 合并 thinking-header-toggles，push 后不等 CI（09-28） | 用户明确补充：检查无问题后可以合并；main push 已触发构建后就不用等待结论，本轮到「已触发」即可停止。feat/thinking-header-toggles @ 65b26bb24bdee70ad6724b37… |
| 10-01 15:48 | 自动压缩「失效」诊断(10-01,用户报长思考模型上压缩失败) | 根因实锤(真机日志三源):压缩摘要单发调用(generateCompactSummary,ChatSessionLifecycle.kt:820+)走 active member,maxTokens=8192 硬上限 +… |
| 10-09 00:40 | sweep-B 修复完成（thinking 规则缓存冷启动预热，sweep-1008 唯一 HIGH） | 任务：/var/minis/shared/sweep-1008/fix-tasks/B.md。分支 fix/sweep-p1-thinking-cache-1008 @ 4f6646f8b6ac94e0081ee7279… |
| 10-16 23:22 | FIX-A 收口：deep-audit-1018 ISSUE-01（P0 截断工具调用）+ ISSUE-02（P1 子代理 reasoning 丢失）+ ISSUE-12c（P2 叠楼守卫）—— fix/da1018-turn-integrity @ 00026c3 两提交，CI #2195 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-A.md（先证再改）。报告 fix-reports/FIX-A.md；装置 repo-a/probe/（durable，含 c… |
| 10-18 00:46 | FIX-F 收口：deep-audit-1024 ISSUE-06（思考规则编辑器保存丢 reasoningEcho/id + 静默改写 wireFormat）—— fix/da1024-thinking-rule-editor-preserve @ 0babeaa，CI #2265 绿，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-F.md 立刻执行（先读 COMMON/TRIAGE §3.4）。报告 fix-reports/FIX-F.md（8 节齐、0… |
| 10-20 00:59 | FIX-E 收口（deep-audit-1033 ISSUE-06 + 07 P1 合支）：usage 模型身份取实时配置 + thinking 弹窗活值 —— `fix/da1033-e-settings-derived-state` @ `1a61b87a`，CI #2350 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 1a61b87a6c7c…；CI run 39278816691（#2350）success。7 文件 +629/−29（4 生产 .kt + 2 测试 .kt + 1 门扩… |
| 10-22 03:22 | 上下文极限自动停 — 压缩链超时取证（2026-10-22） | 现象：当日 8 次 finish=length ×3 empty output — giving up（run 自动停止）；Compact failed 32 次 / folded nothing 119 次 / sum… |
| 10-23 00:56 | FIX-2 收口（deep-audit-1043 / A-03 P1）：`minis-model-use run` 交给 `:modelservice` 的模型未富化 — `fix/dv23-a2-modeluse-effective-model` @ `fe3f9a7c`，CI #39815679884 success，停在分支 | 身份链：基线 main 79ef6d59 → 4 提交（210912f3 生产 / a6f7eb1f 测试 / 258fff8a 门 / fe3f9a7c 首跑红修复）→ 本地 == ls-remote == CI he… |
| 10-23 01:02 | FIX-3 收口（deep-audit-1043 / v22 波 A-04 P1）：`fix/dv23-a3-thinking-ceiling-enrich` @ `6b708e44`，CI run 39815917331 / job 119954364519 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 d80a812f / 测试 0364107e / 门 6b708e44）→ 本地 == ls-remote == CI head_sha = 6b7… |
| 10-23 20:12 | FIX-B3 收口（deep-audit-1046 / dv26 波 S-03 P1）：`fix/dv26-b3-anthropic-thinking-budget` @ `db3de4da`，CI #2559 success，停在分支 | 身份链：基线 main f7aba218 → 4 提交（8e3d361d 生产 / 22cb622a 测试 / c2c86207 门 = CI 验过的树 / db3de4da 报告）。git diff --name-on… |
| 10-24 02:49 | FIX-A2 收口（deep-audit-1047 / A-02 P1）：`fix/dv27-a2-modelsvc-thinking-level` @ `2fc80db6`，CI #2578 success，停在分支 | 缺陷：ModelExecutionDispatcher.buildRequestJson 对非 OFF 档位无条件 put("thinking_level", level.name)，但 worker 只有流式路径解析；… |
| 10-26 10:13 | B6-t07 收口：modeluse_proto_single_source + thinking_rule_editor_preserve 两门 13 条见证全 CAUGHT（分支 `fix/b6-modeluse-thinkrule` @ 门提交 `ae0cdce9c` + 报告 `12e879c00`，CI #2703 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 ae0cdce9c（2 文件 +1237/−719，生产 .kt 0 行、scan.sh/test_sc… |
| 10-26 19:06 | R3-t15 收口：anthropic_thinking_budget + thinking_wiring 两门补缝（7 条跨门缝见证全 CAUGHT，CI #2733 绿，停分支） | 支线 fix/r3-thinking-wiring @ 6bcb84cf4（门提交 3110d6e29），基线 main f0f14d46d。改动面 = 2 个门脚本 +912/−95，生产 .kt 0 行，scan.s… |

## 8. 上下文压缩 / 记忆 / 预算

**跨度** 2026-08-06 ～ 2026-10-27 · **212 条** · **状态** 稳定（09-12 差距清单收敛：D1 攒着、D3/D4/D5 归档不吸收）

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
| 09-24 21:12 | 用户指令「按流程检查云端正在跑的分支，没问题就合并」= 核实 + 合并放行。 | 用户指令「按流程检查云端正在跑的分支，没问题就合并」= 核实 + 合并放行。 |
| 09-25 15:12 | §38-B 收官 + 09-25 真机验证（用户确认 app 已装最新包 1.0.0+1800） | §38-B 收官 + 09-25 真机验证（用户确认 app 已装最新包 1.0.0+1800） |
| 09-25 15:26 | 用户报告：记忆编辑模块点键盘时光标/内容跳回最顶端。诊断结论（09-25）：Compose 框架已知 bug，非 app | 用户报告：记忆编辑模块点键盘时光标/内容跳回最顶端。诊断结论（09-25）：Compose 框架已知 bug，非 app 代码错——Google issuetracker 235693496「On focus, text… |
| 09-25 15:46 | memory-editor 跳顶修复分支 CI 绿（09-25，停止节点）：fix/memory-editor-jump | memory-editor 跳顶修复分支 CI 绿（09-25，停止节点）：fix/memory-editor-jump-to-top @ 13d8e9a8（1 commit，4 文件 +56/−34，基点 main 7… |
| 09-25 15:53 | memory-editor-jump-to-top 分支独立复核（09-25 第二轮，应要求重验）：fix/memory | memory-editor-jump-to-top 分支独立复核（09-25 第二轮，应要求重验）：fix/memory-editor-jump-to-top @ 13d8e9a8（远端分支仍在，未合并）。三道证据：①C… |
| 09-25 16:17 | 窗口化编辑分支 CI 绿（09-25 第二轮，停止节点）：fix/memory-editor-jump-to-top @ | 窗口化编辑分支 CI 绿（09-25 第二轮，停止节点）：fix/memory-editor-jump-to-top @ 17770a20（2 commits：13d8e9a8 跳顶修复 + 17770a20 窗口化，6… |
| 09-25 16:17 | memory-editor-windowed-edit 新提交复核（09-25，分支第三轮验证）：fix/memory- | memory-editor-windowed-edit 新提交复核（09-25，分支第三轮验证）：fix/memory-editor-jump-to-top 新增 17770a20「windowed editing on… |
| 09-25 16:22 | 用户真机确认：窗口化编辑（fix/memory-editor-jump-to-top @ 17770a20）验证没有问题 | 用户真机确认：窗口化编辑（fix/memory-editor-jump-to-top @ 17770a20）验证没有问题——大文件点 Edit 卡顿消失、窗口手感、splice 拼接全部通过。用户明示「你这里可以结束了，… |
| 09-25 16:23 | memory-editor 分支合并收口（09-25，用户放行）：fix/memory-editor-jump-to-t | memory-editor 分支合并收口（09-25，用户放行）：fix/memory-editor-jump-to-top 双提交（13d8e9a8 state API + 17770a20 窗口化编辑）--no-ff… |
| 09-25 17:02 | 09-25 应用优化盘点：流式恢复两条待真机验证风险 | 读远端 main 422914d 源码发现 PartialStreamRecovery.kt:81-99 的决策顺序有竞态：SKIP_EMPTY / SKIP_NO_META 早于 30 秒活跃宽限，并在 recover… |
| 09-25 17:36 | ↳ 检查环节完成（09-25，两分支）： | 检查环节完成（09-25，两分支）： |
| 09-25 18:07 | 09-25 真发现：卸载后自动压缩用旧 token 触发，两次可疑冗余 | 主线 1cc5ab1d 源码 AgentLoopEngine.kt 356→370 先 host.offloadContextIfNeeded(window,loopState.lastContextTokens) 再 … |
| 09-25 18:33 | 分支 CI 绿（09-25 停止节点，检查会话）：fix/offload-stale-token-compact @ f | 分支 CI 绿（09-25 停止节点，检查会话）：fix/offload-stale-token-compact @ f87d9102（1 commit，4 文件 +48/−8，基点 main 1cc5ab1d，非本会话… |
| 09-25 22:04 | 09-25 全量回归审计：今天 24 提交 / 33 文件，无功能级 bug，2 条次生开销 P2 + 5 条边界登记 | 范围：基线 2022b0c5（昨 21:12）→ main 50b399c9，git log 实测今天进 main 的是 24 个提交 / 33 文件 +1511/−267（不止记忆里的 8 条 merge——上午还有 … |
| 09-26 16:36 | CJK 压缩端到端观察闭环（09-26，日志取证）：挂起 3 天的「真实中文长对话涨到压缩线观察 [Compact] 触 | CJK 压缩端到端观察闭环（09-26，日志取证）：挂起 3 天的「真实中文长对话涨到压缩线观察 [Compact] 触发」已在真机日志印证——minis-2026-09-26.log 今天 13 次 AutoCompa… |
| 09-26 16:43 | ↳ GLOBAL.md 更新（09-26，用户拍板「就按你说的这套来」）：按「稳定/可复用/环境事实」标准分拣近几天日志后写 | GLOBAL.md 更新（09-26，用户拍板「就按你说的这套来」）：按「稳定/可复用/环境事实」标准分拣近几天日志后写入 4 处——①新增「真机日志取证能力」节（日志族 6 文件表格 + 两种行格式 + silent_… |
| 09-27 18:46 | 「工具调用框消失／被挤掉」定案：不是丢块，是重进时的消息合并（09-27） | 结论：工具块在数据层、重建层、渲染层全部完整，不存在「整批丢框」的路径。用户看到的「框被挤没」= 重进会话时多轮 assistant 行被合并成一条气泡的呈现后果。 |
| 09-28 09:50 | HF 语义记忆 + 知识图谱记忆更新（09-28，用户要求处理） | HF 语义记忆：索引曾停在 09-21 14:00（1239 条），7 天新条目未入索引。增量重建（build --incremental，复用 1231 条旧向量 + 新嵌 165 条）→ 1396 条 / 8MB，已… |
| 09-28 09:50 | memory MCP 服务器移除（09-28，用户拍板「日常使用中根本没发挥作用，去掉」） | minis-mcp-cli remove memory 已删，MCP 集成只剩 termux-dock。判据 = 一阶门：该服务器无持久化（每调用新 session 写入蒸发）+ daemon 不传 env，shell/… |
| 09-28 09:55 | ↳ 文档三件套第二次重建 + 合并 main = 346ebf59（09-28，用户「更新文档是低风险，直接合并」） | 三回归测试（test_sanitize/test_rebuild/test_sagas）全 PASS → rebuild 1399 条（前次 1386 → +13，dropped 75）→ sanitize main+I… |
| 09-28 23:28 | 减法审计（接手会话）：每会话固定注入 137KB，78% 在日报 | 审计报告：/var/minis/shared/retire-audit-0928/AUDIT.md（完整数据+清单）。 |
| 09-28 23:40 | GLOBAL.md 压缩执行完成（09-28，用户授权） | /var/minis/memory/GLOBAL.md 29,799 → 24,408 B（−18.1%）。备份 shared/retire-audit-0928/backup/GLOBAL.md.bak，diff GL… |
| 09-29 00:00 | ↳ 09-28 收尾欠账全部清零（23:5x，用户确认） | runtime.memoryInjectLines 已确认生效 = 120（schema 50..500，前两次确认面板超时未落，第三次已落）→ 日报注入削峰 A1 已执行，配合 A3 写入侧瘦身（memory_writ… |
| 09-29 00:13 | MEMORY-ROLLUP.md 去向定案（09-29，用户问「为什么不存在、去哪了」） | 结论：不是被删，是 09-10 换包名时没被带过来。 它曾存在——当前源码注释自己写着证据：MemoryRepository.kt:38-40「The rollup grows monotonically … (it r… |
| 09-29 00:21 | MEMORY-ROLLUP 重建预算实测 → 方向被数据否掉（09-29） | 方法：真源码离线预览（/var/minis/shared/work/rollup-preview-0929/，engine 0a58e4a5… / repository 28162b6f… 与 main 逐字节相同；ko… |
| 09-29 00:26 | MEMORY-ROLLUP (b) 重合度核对完成 → 建议不做（09-29） | 三个数字（全 58 天，/var/minis/shared/work/rollup-preview-0929/overlap_check.py）： |
| 09-29 00:33 | MEMORY-ROLLUP 重建形式调查：三种写法全测，都不成立（09-29） | 新决定性事实：memory_get 读不到 MEMORY-ROLLUP.md —— 源码显式排除（MemoryRepository.kt:169-178，dailyLogSizes() 同样排除），注释原文：「its c… |
| 09-29 00:53 | MEMORY-ROLLUP 重建完成（09-29，用户「按你的建议来吧」） | 产物：/var/minis/memory/MEMORY-ROLLUP.md = 8,390 B / 38 条规则 / 59 个 ## Rollup <date> 锚点（权限 0600 同 GLOBAL.md）。注入上限 … |
| 10-01 15:48 | 自动压缩「失效」诊断(10-01,用户报长思考模型上压缩失败) | 根因实锤(真机日志三源):压缩摘要单发调用(generateCompactSummary,ChatSessionLifecycle.kt:820+)走 active member,maxTokens=8192 硬上限 +… |
| 10-01 16:58 | compact 安静优先修复收尾（10-01，分支 fix/compact-quiet-first-1001 @ 4d1 | compact 安静优先修复收尾（10-01，分支 fix/compact-quiet-first-1001 @ 4d1c9a25，用户拍板任务结束） |
| 10-01 17:11 | 核查 fix/compact-quiet-first-1001（10-01，用户「检查一下云端正在跑的分支」+「构建完了」，CI 绿停） | 对象：main b4592399 + 2 commits（dd96f04c quiet-first 排序+三道预算；4d1c9a25 只修测试编译——1905 failure 是 CompactQuietFirstTes… |
| 10-01 17:12 | 合并 fix/compact-quiet-first-1001 → main（10-01，用户放行「没有问题，那就合并吧」，merge 6361b410） | 合并：--no-ff merge 6361b410（父 b4592399 + 4d1c9a25），合并树 vs 分支 tip 0 行差异，git diff --check 冲突标记 0，main 未漂移（merge-ba… |
| 10-02 14:51 | 自动压缩"双跑/停不掉/杠"诊断（10-02，未动手，待拍板） | 用户报：自动压缩失败时上面下面同时在跑、按暂停只停下面的、中间有条杠。 |
| 10-02 15:24 | 停止按钮杀不到压缩 P1 已修（10-02，分支 fix/compact-cancel-on-stop-1002 @ 4bc1352，CI #1908 绿停） | 改动（5 文件 +231/−8，停在分支未合并）：①compactAll 的 launch 存 compactJob，cancelStream 连带 cancel（9 处调用点全统一——语义都是"当前轮工作立刻停"，fi… |
| 10-02 15:26 | 分支审查 fix/compact-cancel-on-stop-1002（10-02，CI 绿停，未合并） | 对象：分支 fix/compact-cancel-on-stop-1002 @ 4bc13521（main 6361b410 + 1 commit），修 10-01「停止杀不到压缩」P1。CI run 369769828… |
| 10-02 15:30 | 合并 fix/compact-cancel-on-stop-1002 → main（10-02，用户放行「该合并的合并」，merge c01acd5） | 合并前核查：verify_branch.sh exit 0 全过（首次跑 FAIL=no merge base 是浅克隆 graft 断链，git fetch --deepen 1 origin <branch> 接上后… |
| 10-02 16:19 | 四条判定（10-02 登记项，分支 fix/clearchat-compact-ce-1002 @ cfed933e→e | 四条判定（10-02 登记项，分支 fix/clearchat-compact-ce-1002 @ cfed933e→ee2b2cea，CI run 36981575610 success 绿停未合并）：P2-2/P3-… |
| 10-02 16:21 | 复核 fix/clearchat-compact-ce-1002 @ ee2b2ce（force-push 后新 tip | 复核 fix/clearchat-compact-ce-1002 @ ee2b2ce（force-push 后新 tip）：开发者按前轮建议修了 CI 红的 1 行测试缺陷（全局首匹配 indexOf → 区域锚定 + … |
| 10-02 16:41 | P2 已修并合入分支 fix/clearchat-compact-ce-1002 @ 90fd2e72（10-02，CI | P2 已修并合入分支 fix/clearchat-compact-ce-1002 @ 90fd2e72（10-02，CI 绿停，未合并）：clearChat 补 _compactSummary.value = null（… |
| 10-02 17:05 | 审查（第三轮）fix/clearchat-compact-ce-1002 @ 90fd2e7（CI run 369840 | 审查（第三轮）fix/clearchat-compact-ce-1002 @ 90fd2e7（CI run 36984034118 success @ head_sha 逐字符，停止节点已达，未合并）：增量 1 comm… |
| 10-02 17:11 | 合并 fix/clearchat-compact-ce-1002 → main（10-02，用户放行「没检查出什么问题就 | 合并 fix/clearchat-compact-ce-1002 → main（10-02，用户放行「没检查出什么问题就合并」+「该攒着的攒着」，merge f4b9d3be）：合并前三项核查全过（diff 152 行全… |
| 10-02 22:12 | tall-1997/OpenMinis-Linux（Minis Ultra）第二次调查（10-02） | tall-1997/OpenMinis-Linux（Minis Ultra）第二次调查（10-02） |
| 10-03 23:55 | 「散落资产 → 下沉到代码层」盘点（10-03，用户问：有哪些散落在脚本/记忆中的可以下沉到 app 自带代码） | 「散落资产 → 下沉到代码层」盘点（10-03，用户问：有哪些散落在脚本/记忆中的可以下沉到 app 自带代码） |
| 10-04 22:48 | 压缩体系 vs《Compaction Cliff》对比分析完成（10-04，用户帖论文求对比改进） | 对象：用户发来一篇"2026 下半年智能体记忆/压缩研究"文章（arxiv 2608.22752 + Mem0 blog 等），要求对比 RikkaMinis 现压缩功能找改进。 |
| 10-05 07:56 | 压缩实验收尾 + 任务书 C（2026-10-05） | 压缩衰减实验（compact-exp-1004）v1/v2b 均完成：埋入曲线归一化后 bare/pin 两臂 6 轮零衰减；负控 0/10。核心结论：压缩管线信息保真没问题，真正的故障模式是输出通道 4096 toke… |
| 10-05 08:24 | 压缩实验 D+E 收官：阳性对照成立 + 视界实锤（2026-10-05） | D（衰减阳性对照，10 轮）：真值 R1 声明一次 + 变体冲刷 → R1 10/10 → R2 即 0/10，且答案确定性漂移成最近变体（覆盖而非淡忘）；每轮重申臂 10 轮全 10/10（pin 机制等效有效）。me… |
| 10-05 08:27 | 任务书 C 执行完成：压缩摘要截断守卫 + 吵闹候选 60s 预算（fix/compact-truncation-guard-1005，CI 绿停） | 状态：分支 CI 绿 @ tip，确定性停止节点已达成，未合并（合并权在用户）。 |
| 10-05 08:29 | 压缩改进任务书 C/D 状态（2026-10-05） | 任务书 C（stopReason=length 截断守卫 + 吵闹候选 60s 预算）已派发并施工完毕：分支 fix/compact-truncation-guard-1005，单 commit 6bdcff53，测试 … |
| 10-05 09:42 | 任务书 D 执行完成：压缩遥测 + SUPERSEDED 条款（fix/compact-telemetry-superseded-1005，CI 进行中） | 状态：两个 commit 已推远端 + CI 已 dispatch（run 37252315441），独立会话执行任务书 D 全文。 |
| 10-05 10:07 | 2026-10-05 compact 分支合并完成（用户令：检查今天分支修改，没问题就合并） | 任务：今天未合并分支 = C（fix/compact-truncation-guard-1005 @6bdcff5）+ D（fix/compact-telemetry-superseded-1005 @e71342ca，… |
| 10-05 11:05 | 远端 3 旧支 + rk-compact 残支清理完成（10-05 第二轮，用户令「旧的已合并的没用了，处理了吧」） | 执行：远端 3 支全删——fix/compact-truncation-guard-1005（6bdcff5）、fix/compact-telemetry-superseded-1005（e71342ca）、feat/s… |
| 10-05 15:29 | ↳ 应用功能缺口盘点完成（10-05，用户令「看看这应用还有什么功能缺口、哪方面需要强化」） | 报告：/var/minis/shared/work/app-gap-audit-1005/REPORT.md（审计产出，未改产品代码）。 |
| 10-05 16:19 | 压缩体系剩余队列 → 三份任务书（10-05，用户令「处理成任务，我手动派发」） | E pin v0（约束钉住）：/var/minis/shared/work/task-brief-1005-E-compact-pin-v0.md。设计已拍板：内嵌方案（<pinned-user-messages> 内嵌… |
| 10-05 16:48 | ↳ 任务 G 执行完成：SUPERSEDED 条款回归 PASS（零代码，D-repeat @ 生产 prompt 复跑） | 任务 G 执行完成：SUPERSEDED 条款回归 PASS（零代码，D-repeat @ 生产 prompt 复跑） |
| 10-05 17:41 | 压缩三任务收口（10-05 主会话独立核验，用户令「结果都出来了」） | 核验（三源全过）：①CI run 37284048540（E）/37289317688（F）均 success，head_sha 与分支 tip 逐字符一致（bridge+API+ls-remote）；②diff vs … |
| 10-05 18:35 | 压缩体系 E/F 两分支核查并合并 main（10-05，用户令「检查，没问题就合并，有问题就改」） | 结果：两分支均通过 6.5 全套 8 项核查 → 合并 main，main CI 绿。 |
| 10-05 21:24 | H 执行会话压缩实证 + 预算判定核实（10-05 晚，新包 6f357dc8） | 新包首次完整压缩 ×2（20:59 / 21:08，H 执行会话触发）：候选链 1/4 glm-5.3-flash 30s 超时 → 2/4 deepseek 30s 超时 → 3/4 fallback deepseek… |
| 10-05 22:02 | 21:32 压缩失败事件还原 + ponytail 天花板命中（10-05 晚） | 时间线：20:59/21:08/21:27 三轮压缩全成功（链=1/4→3/4 fallback，active=大写 GLM-5.3-Flash XHIGH）。21:31:07 用户在 H 会话（9b689468）开 M… |
| 10-05 22:06 | backlog 新增：压缩候选链 entry 级预算覆盖（10-05 晚，用户拍板「先攒着」） | 触发依据（已满足升级条件）：小写 glm-5.3-flash（tierflow entry 0df664ef，declared=low\|high\|max → quiet 分区→30s 预算）在压缩摘要任务上 5/5 TI… |
| 10-05 22:43 | ↳ H 分支核查并合并 main（10-05 晚，用户令「检查+有问题修+没问题合并」） | 对象：refactor/compact-orchestration-split-1005 @ 68f83ee6（ChatSessionLifecycle 拆分 CompactOrchestration，2 commits… |
| 10-05 23:05 | 压缩专用模型组分析（用户问「是否有必要给自动压缩专门开一个模型组/专用模型」）： | 压缩专用模型组分析（用户问「是否有必要给自动压缩专门开一个模型组/专用模型」）： |
| 10-06 00:42 | 压缩模型钉住功能施工完成（feat/compact-model-pin-1005 @ bba87f23，CI 绿，停在分 | 压缩模型钉住功能施工完成（feat/compact-model-pin-1005 @ bba87f23，CI 绿，停在分支待用户） |
| 10-06 01:28 | 压缩模型 pin 分支（feat/compact-model-pin-1005）审查+修复+合并 main 完成（10-06 凌晨） | 审查发现 2 缺陷（均修，commit f03b9a88）：①resolveCompactionPinProvider 缺 GroupRouter.isUsable 健康门——冷却/熔断/死钥的 pin 走全局解析仍骑链… |
| 10-06 19:52 | 报告/证据：/tmp/verify-ui_runtime-page-adjust-1006.scan.log（scan | 报告/证据：/tmp/verify-ui_runtime-page-adjust-1006.scan.log（scan 20/20）、分支 CI run 37457321155 @7de88189 success（bri… |
| 10-06 20:20 | 压缩模型 pin（sensenova-6.8-flash-lite）日志核查：今日 0 次压缩，pin 未被检验 | 结论：10-06 全天日志零压缩事件（主日志 [Compact] 遥测 0 条，对照 10-05 有 28 条 outTok 遥测；modelservice 日志 sensenova 0 命中；无会话越过压缩阈值）→ p… |
| 10-06 20:27 | 修正：65K→50K 下降 = offload 分支生效，摘要压缩链被 TAIL_TOO_SMALL 跳过 ×5 | 用户观察核实：20:23:54 本会话（session 0c7917d1，glm-5.3-flash，window=70000）Context Offload 触发：65256 tokens(93%) → 18 个工具结… |
| 10-10 14:54 | 记忆系统全面更新（2026-10-10 下午） | ① 开发档案三件套：重建 1605 条（08-03～10-10），清洗 + SAGAS 重建（24 saga / 148 orphans / multi4=88），结构核验 anchors==header==1605、f… |
| 10-10 15:01 | 两分支合并进 main = 81be7f62（2026-10-10，用户放行「分支的话就合并吧，毕竟只是文本更新」） | 合并：--no-ff ×2 序贯合入 main。①docs/dev-history-1010 @ 3c483aee → 23d4b2e0（4 文件 +961/−48，全部 docs/dev-history/）；②fix/… |
| 10-10 15:24 | MCP 知识图谱退役（2026-10-10，用户拍板「2」= 退役） | 决策：退役 KG（@modelcontextprotocol/server-memory），不复活。理由三条（合起来构成，单条不够）：①写路径从未打通（09-28 无持久化 + daemon 不传 env 给 STDIO… |
| 10-10 15:26 | GLOBAL 记忆架构章节 + 远端旧分支清理（2026-10-10，用户令「加进去，并且把云端的那个删掉吧」） | ① GLOBAL.md 新增「## 记忆架构（六层 + 两条辅助）—— 2026-10-10 定案」（用户明确授权编辑，插在「待办登记纪律」节后）。内容 = 六层表格（层 / 位置 / 记什么 / 谁来读·何时）：日报 … |
| 10-10 15:28 | 纯文本分支可直接合并——写入 GLOBAL 常驻纪律（2026-10-10 用户拍板「合，并把它记忆进全局记忆」） | 合并：chore/retire-kg-skill-note-1010 @ 38e905a1 → main 8cb19750（1 文件 +18/−14，纯 SKILL.md 文本）。核查：无冲突 · 合并树 vs 分支树 … |
| 10-10 23:44 | RikkaMinis 自动压缩/上下文超限排查（2026-10-10） | 日志位置：/var/minis/logs/minis-YYYY-MM-DD.log（主日志，含 ChatViewModel/CompactDiag/AutoCompactLoop）；memspike-.log 有 ui:… |
| 10-11 00:30 | ↳ AiGW（Android-AI-gateway）任务状态 | 仓库本地副本：/var/minis/shared/work/aigw-recovery/Android-AI-gateway（跨会话共享目录；旧会话 workspace 里的原件已被 cp -a 恢复出来） |
| 10-11 00:37 | 修复「上下文超限静默终止」：fix/compact-stale-context-tokens-1010（2026-10-10，用户令「就按照你说的修吧」） | 根因（与我的初判不同，记录纠偏）：不是本地字符估算（bytes÷2.2）偏高——loopState.lastContextTokens 只有一个写入源（AgentLoopEngine.kt:1023/1029，来自 pr… |
| 10-11 00:49 | 分支核查+合并：fix/compact-stale-context-tokens-1010（2026-10-11 用户令 | 分支核查+合并：fix/compact-stale-context-tokens-1010（2026-10-11 用户令「有问题就修，没问题就合并」+「触发了就不用等了」） |
| 10-12 17:37 | T1 完成：10-10 压缩修复验证装置已搬进 repo（PR #7，零生产改动） | 任务源：shared/proactive-1012/tasks/T1.md（用户令「严格按 T1.md 执行不要停」）。产物 = CompactStaleContextTokensReplayTest.kt（206 行 … |
| 10-14 00:19 | 上下文压缩循环诊断（backlog §93 登记） | 用户第 3 次遇到「上下文即将占满（~88%）」通知。日志取证：同会话 15 分钟触发 3 次压缩；链中间压缩近乎无效（191K→187K，近端工具输出受保护）；每轮 +16K~203K tokens；最深压缩地板 87… |
| 10-15 11:07 | 上下文预算缺口修复（fix/prewindow-budget-1015） | 现场：2026-10-15 T321 会话（16c39966）——同一份 4.9MB agent 历史，deepseek 中转报 191,856 tokens、GLM 直连报 920,577（4.8×）。本地账本持中转口… |
| 10-15 16:32 | FIX-A4 完成：消息级 token 估算双计/多路不一致 → 收敛为单一 wire 对齐实现（2026-10-15，分支 `fix/audit2-estimate-1015` @ `37533a3a`，CI #2115 绿，停在分支未合并） | 任务：shared/work/verify-audit2-1015/fix-tasks/FIX-A4.md（审计报告 BUG-06）。基线 main 891d755。报告 shared/work/verify-audit… |
| 10-16 09:43 | FIX-G 收口：deep-audit-1016 8c/8d（通知行去 AI 头像 + compact 分割线谓词读全部块）—— 分支 CI #2155 绿，停在分支（2026-10-16） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-G.md 立刻执行。基线 main 04e74c53325a；分支 fix/da1016-chat-sysinfo @ ee3… |
| 10-16 09:51 | FIX-F 收口：deep-audit-1016 B-7b/B-7c（定时任务/无头会话状态闭环）—— 分支 CI #2157 绿，停在分支（2026-10-16） | 任务书 shared/work/deep-audit-1016/fix-tasks/FIX-F.md（纪律文档实际在 shared/work/deep-audit-1016/COMMON.md 与 TRIAGE.md，不… |
| 10-16 18:19 | FIX-A 收口：deep-audit-1016c ISSUE-01（P0 续写分支持久化丢块 + dbId 回填）—— `fix/da1016-loop-persist-slice` @ `a509a491`，CI #2183 绿，停在分支 | 任务 shared/work/deep-audit-1016c/fix-tasks/FIX-A.md；报告 fix-reports/FIX-A.md；装置 fix-a/probe/；基线 main e3ef74545bc… |
| 10-16 18:50 | FIX-D 收口：deep-audit-1016c auto-compact 治理四支（`fix/da1016-compact-governance` @ `feef7cd`，CI #2184 绿，停在分支） | 任务：读 shared/work/deep-audit-1016c/fix-tasks/FIX-D.md（先证再改）。报告 fix-reports/FIX-D.md；装置 fix-d/probe/{jvm/,mutati… |
| 10-16 22:22 | FIX-C 收口：deep-audit-1018 ISSUE-07（CompactPin 同消息双钉 + 紧凑域裸真值陷阱，P1）—— `fix/da1018-compact-pin` @ `9b34241`，CI #38912121098 绿，停在分支 | 任务 shared/work/deep-audit-1018/fix-tasks/FIX-C.md（+ COMMON.md/TRIAGE.md 实读自上级目录）；报告 fix-reports/FIX-C.md；装置 re… |
| 10-16 22:43 | FIX-E 收口：deep-audit-1018 ISSUE-09（备份恢复漏重映射 + 漏打包蒸馏层，P1）—— `fix/da1018-backup-remap` @ `e3d16b4`，CI #2190 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-E.md 并执行（先读 COMMON/TRIAGE）。报告 shared/work/deep-audit-1018/fix-r… |
| 10-17 10:49 | FIX-G 收口：deep-audit-1020 ISSUE-07（memory_rollup 门禁 + provider UA 字段）—— fix/da1020-memory-gate-provider-fields @ fd3acf3，CI #2220 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-G.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-G.m… |
| 10-17 11:29 | 审计背景包 v3 更新（吸收 deep-audit-1020）— 2026-10-17 | 输入：用户令「又完成了一波修复，开始更新」。本轮 = deep-audit-1020：8 支修复（A models-cache 漏参 / B shizuku argv / C offload 相对路径+tar 前缀 / … |
| 10-17 20:49 | FIX-G 收口：deep-audit-1023 ISSUE-07（memory 两处 UI 接线断链 + 删除路径主线程 IO）—— fix/da1023-memory-ui-wiring @ 6469641，CI #2248 绿，停在分支 | 身份链三方逐字符一致：本地 HEAD = 远端 tip = CI head_sha = 64696416243378008b64d8b5ca2085efe619591e；run #2248（id 39032697053）… |
| 10-18 00:49 | FIX-C 交付收口：deep-audit-1024 ISSUE-03（P1 二级引用漏清理 + ModelGroupsScreen 无监听）—— `fix/da1024-compact-pin-cascade-and-listener` @ `f7f1419beb32`，CI #39049819858 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md；装置 repo-c/probe/（durable：jvm/{… |
| 10-18 09:59 | FIX-A 交付收口：deep-audit-1025 ISSUE-01（android-player 相对路径 100% 失败 + 跨会话读逃逸，P1） | 分支 fix/da1025-player-session-path @ 57945206c2b83dd6d321dffb294554e4b26f4e39（单提交，4 文件）；基线 main bcb6569。CI #390… |
| 10-19 07:41 | deep-audit-1029 FIX-B 收口：ISSUE-03（记忆编辑屏主线程同步 IO + SOUL 缓存回流）—— `fix/da1029-b-memory-io` @ `98c0fc5`，CI #39176155210 绿，停在分支 | 身份链：基线 main 83caac6 → 单提交 98c0fc59e2cbe307b5e539853dc2a2608b4988e8（3 文件 +254/−26）→ branch dispatch CI 39176155… |
| 10-20 13:04 | FIX-B1 收口（deep-audit-1035 ISSUE-01 + ISSUE-02）：宿主窗口静默空转 + 移动视口极性 —— `fix/dv14-b1-window-viewport` @ `70d7b904`，CI #2379 success，停在分支 | 身份链三方逐字符：基线 main 76488f2dfcbbc79db8eac6df60b7682e187121f6 → 3 提交（df34b84a 生产+出货测试 / 41b5fe00 门 W5e / 70d7b904 … |
| 10-22 03:22 | 上下文极限自动停 — 压缩链超时取证（2026-10-22） | 现象：当日 8 次 finish=length ×3 empty output — giving up（run 自动停止）；Compact failed 32 次 / folded nothing 119 次 / sum… |
| 10-22 04:09 | 修复：压缩摘要链超时导致「上下文极限自动停」（用户报告）— `fix/compact-timeout-budget` @ `2abb1592`，CI #2451 success，停在分支 | 用户报告：「上下文极限到达了。自动停下。在压缩机制的作用下不应该出现，为什么连续出现了几次？」取证结论 = 压缩机制确实在触发，是摘要生成那步系统性超时。 |
| 10-23 11:43 | FIX-8 收口（deep-audit-1044 / dv24 波 B-03+B-04 P1）：`fix/dv24-b2-chat-factory-effective` @ `7c82bc98`，CI #39894373704 success，停在分支 | 身份链：基线 main f94413f5 → 5 提交（生产 0e335456 / 测试 ba3611ef / 门 3d3bf5c1 / 报告 5c32285c+ef7d9d7e / 收口 7c82bc98）。CI 绿于… |
| 10-24 15:05 | FIX-A2 收口（deep-audit-1049 / dv29 波 A-02+A-03+A-04，含 1048 遗留 V-1）：`fix/dv29-a2-headless-binding-and-fk-crash`，分支 CI 绿 @ `0d0b7c80`，停在分支 | 范围（4 条 3 文件生产 + 4 文件测试 + 2 道既有门扩展，0 新建门 ⇒ 门 111 / 夹具 342 双不变） |
| 10-24 17:41 | 真机验证窗口 3（agent 自测批次）—— 2 条通过，1 条未取到样本，同一个假阳性踩了两次 | 通过： |
| 10-25 06:36 | wide-1024 支 10（4 门 12 见证）收口：分支 `fix/meta-wide-10-skill-backup-compact` @ `96ad4ab0`，CI #2643 / run `40137834021` success（head_sha 逐字符一致） | 12/12 见证反攻通过（修复前 rc=0 → 修复后 rc=1，restore=True 全部 sha256 一致）： |
| 10-25 12:15 | wide3-03 支（TASK-03-compact-loop）收口：4 门 14 见证反攻 14/14 CAUGHT，分支 CI #2646 绿，停分支（2026-10-25） | 分支 fix/meta-wide3-03-compact-loop（wt-03，基线 main 6b6c918f）；CI 绿树 f8ae7f6e（run #2646 / id 40160005688 / job 1210… |
| 10-25 12:27 | wide3-07（UI 交互四门）收口：13/13 见证反攻 CAUGHT，分支 CI #2649 绿，停分支 —— 2026-10-25 | 分支 fix/meta-wide3-07-ui-interaction，基线 main 6b6c918f。只改 4 个门脚本（ime_inset_guard / tool_title_error_branch_check… |
| 10-25 19:39 | 第五批「门见证第二轮」材料已备好（基线 wide4 并集 1319cce7，未合并） | 产物：/var/minis/mounts/背景包/发送-A\|发送-B/（00 规则 8.9KB / 01 索引 22.8KB·20.8KB / 02 diff 190.7KB·176B / repo-1319cce7.z… |
| 10-25 23:38 | B5-t02 收口：compact_decision + entry_id_rename 两门 12 条见证全 CAUGHT（分支 `fix/b5-compact-entryid` @ `c9ab770b4`，CI #40203205487 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享原语）。改动面 = 2 个门脚本（+720/−550），生产 .kt 0 行、scan.sh/test_sca… |
| 10-26 10:00 | B6-t08 收口：session_provider_enabled + memory_ui_wiring 两门 13 条见证全翻红（分支 `fix/b6-sessionprov-memoryui` @ `72955761c`，CI #2700? 实为 run 40254147183 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 2a237ae91（2 文件 +837/−640，生产 .kt 0 行、res/ 0 行、scan.sh… |
| 10-27 06:41 | 沙箱缺陷召回修复收口（分支 `fix/defect-recall-seeds` @ `9d7c0c85b`，报告 `acdeae6b8`，CI `40409678884` 绿，停分支）—— 2026-10-27 | 用户说「修吧」→ 走分支隔离全流程收口。改动 2 文件 +187/−15（SandboxDefectRegistry.kt +96、测试臂 15→20）。 |
| 10-27 07:02 | 两支合并 main + 发版 v1.0.0+2766（2026-10-27 07:0x） | 用户指令「检查云端的两个分支，没问题等他们跑完就合并」。核实到的两支 = 两个活动会话产出的分支（base/batch5-shared 是 main 祖先，无可合并内容，保留未动）。 |

## 9. Provider / 模型组 / 负载均衡

**跨度** 2026-08-04 ～ 2026-10-26 · **164 条** · **状态** 已闭环（09-12 收尾；负载均衡语义已在代码级核实为「生效但语义与用户预期不同」）

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
| 09-23 01:08 | F5 族闭环审计完成（provider 流控族，loop-audit-0922） | 报告：shared/loop-audit-0922/reports/F5-provider-flow.md。6 环：L4=1 / L3=2 / L2=3 / L1=0 / L0=0。 |
| 09-24 08:57 | ProviderList 拖拽排序 + ModelGroups 去滑动删除（分支 CI 绿停） | 分支：feat/provider-reorder-and-group-cleanup @ 6a990654（2 commits，4 文件 +253/−126）→ CI run #1772 success、head_sha… |
| 10-05 22:02 | 21:32 压缩失败事件还原 + ponytail 天花板命中（10-05 晚） | 时间线：20:59/21:08/21:27 三轮压缩全成功（链=1/4→3/4 fallback，active=大写 GLM-5.3-Flash XHIGH）。21:31:07 用户在 H 会话（9b689468）开 M… |
| 10-05 23:05 | 压缩专用模型组分析（用户问「是否有必要给自动压缩专门开一个模型组/专用模型」）： | 压缩专用模型组分析（用户问「是否有必要给自动压缩专门开一个模型组/专用模型」）： |
| 10-06 00:42 | 压缩模型钉住功能施工完成（feat/compact-model-pin-1005 @ bba87f23，CI 绿，停在分 | 压缩模型钉住功能施工完成（feat/compact-model-pin-1005 @ bba87f23，CI 绿，停在分支待用户） |
| 10-12 17:42 | T2 属性测试落地：ProviderToolPairingPropertyTest（PR #8，CI 全绿） | 产物：src/android/app/src/test/java/com/rikkaminis/app/provider/ProviderToolPairingPropertyTest.kt（+356 行，分支 test… |
| 10-12 18:14 | ↳ proactive-1012 三任务合并完成（2026-10-12 晚） | 用户放行后执行合并：PR #7/#8/#9 全部合入 main，顺序 #7 206e7110 → #8 38ad442d → #9 13949bb0（tip），逐支 merge-base --is-ancestor 核实… |
| 10-14 14:22 | FIX-7 完成：KeyRoulette 明文密钥落盘修复（fix/sweep1014-key-roulette @ 41e6a796f，CI #2004 绿） | 任务书 shared/sweep-1014/tasks/FIX-7.md；报告 shared/sweep-1014/reports/FIX-7.md；装置 shared/work/sweep1014-fix/f7/。 |
| 10-14 15:39 | FIX-R12 完成：D7 provider id 规范化 + D8 前缀守卫 + D9 脚本加固（fix/sweep1 | FIX-R12 完成：D7 provider id 规范化 + D8 前缀守卫 + D9 脚本加固（fix/sweep1014-security @ 60538e6，CI #2024 绿） |
| 10-16 00:09 | deep-audit FIX-E 收口：model-use 主进程 provider 与告警合并（`fix/da1015-modeluse-proc` @ `4a244d93`，CI #2144 绿，停在分支） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-E.md 立刻执行（deep-audit BUG-03，P1）。基线 main e10fb2aa；报告 shared/work… |
| 10-16 09:59 | FIX-E 收口：fallback 重算预算 + blocked-empty 不记 Success + shell 预算门终态（fix/da1016-agentloop-budget @ d996e19e，CI #2160 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-E.md 执行（6a/6b P1 + 5a + 5b/6c 判定）。基线 main 04e74c53；单 commit d99… |
| 10-17 07:38 | FIX-I 收口：deep-audit-1019 ISSUE-09（BrowserRssProbe/ProviderRssProbe 锁范围）—— fix/da1019-rssprobe-lock-scope @ ace1887，CI #2204 绿，停在分支（2026-10-17） | 任务书 shared/work/deep-audit-1019/fix-tasks/FIX-I.md；报告 fix-reports/FIX-I.md；装置 repo-i/probe/（durable）。基线 main e… |
| 10-17 10:39 | da1020 FIX-A 收口：ISSUE-01 一行修复（ProviderRepository.fetchModels 漏传 context）—— fix/da1020-provider-models-cache @ 17c7791，CI #2216 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-A.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-A.m… |
| 10-17 10:49 | FIX-G 收口：deep-audit-1020 ISSUE-07（memory_rollup 门禁 + provider UA 字段）—— fix/da1020-memory-gate-provider-fields @ fd3acf3，CI #2220 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-G.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-G.m… |
| 10-17 20:41 | deep-audit-1023 FIX-A 收口：FileProvider 路径覆盖 + 大消息导出（分支 CI #2246 绿，停在分支） | 交付：fix/da1023-fileprovider-paths @ af7ecfcddbf026b50b10fc2a8e583c5e214d6799（基于 main cd9e97bf），4 文件 +788/−15。CI… |
| 10-17 21:28 | FIX-C 收口：deep-audit-1023 ISSUE-03 + ISSUE-09（模型条目复合主键守卫 + 紧凑钉二级引用）—— fix/da1023-entry-key-provider-parity @ 7106210，CI #2254 绿，停在分支 | 任务：读 shared/work/deep-audit-1023/fix-tasks/{COMMON.md,FIX-C.md} 并立刻执行（基线 main cd9e97b，门禁 85/85；报告行号不可信，先证再改）。报… |
| 10-18 00:42 | FIX-A 交付：deep-audit-1024 ISSUE-01（share_inbound 三合一，P1）—— fix/da1024-share-inbound-fileprovider-io @ 11096d9，CI #2263/run 39049248443 绿，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-A.md 并立刻执行（先读 COMMON.md/TRIAGE.md §3.1/REPORT ISSUE-01）。基线 main… |
| 10-18 09:54 | FIX-F 收口：deep-audit-1025 ISSUE-06（P1 Compose remember 键陈旧：modelCount 卡 0 + 删除守卫失效）—— `fix/da1025-provider-remember-keys` @ `8460ef06`，CI #2277（run 39087103229）绿，停在分支 | 任务：读 shared/work/deep-audit-1025/fix-tasks/FIX-F.md（先读 COMMON/TRIAGE，基线 main bcb6569）。报告 fix-reports/FIX-F.md（… |
| 10-18 10:07 | FIX-I 交付收口：deep-audit-1025 ISSUE-09（P2 Provider RPC 守卫/字段不对称）—— fix/da1025-provider-rpc-symmetry @ `6b40595`，CI #2276（39087616148）success，停在分支 | 任务书 shared/work/deep-audit-1025/fix-tasks/FIX-I.md；报告 fix-reports/FIX-I.md（8 节齐）；装置 repo-i/probe/（durable）；bac… |
| 10-18 14:12 | FIX-E 交付收口：deep-audit-1026 ISSUE-05（ProvidersCollection customBaseURL 漏守卫，P1）—— `fix/da1026-providers-baseurl-guard` @ `53c9d08`，CI run 39101827796 绿，停在分支 | 任务书 fix-tasks/FIX-E.md（COMMON 实际在上级目录，已读）。基线 main dc04fc3。交付 2 文件 +261/−7。 |
| 10-18 14:18 | FIX-D 收口：deep-audit-1026 ISSUE-04（`minis-config` 加模型落库 provider 存实例 UUID + 未 trim + providerKeyMap 缺 displayName 键）—— `fix/da1026-models-provider-field` @ `4be9d09`，CI #2285 绿，停在分支 | 任务 fix-tasks/FIX-D.md（先读 COMMON，实际在上级目录）。报告 fix-reports/FIX-D.md；装置 repo-d/probe/（树内，.gitignore:141 已含 probe/）… |
| 10-18 16:40 | ARCH-01 交付：ProviderWriteRules 单源校验层（`fix/da1026-arch01-provider-write-rules` @ `5737f75`，CI run 39110944268 success，停在分支未合并） | 任务 ARCH-COMMON.md + 蓝图 ARCH-01 小节（Provider / Model 跨面写入规则收口）。报告 fix-reports/ARCH-01-REPORT.md（11223 B，§1–§9）；装… |
| 10-18 16:47 | ARCH-03 收口：ProviderType 编译期穷举元数据契约 —— `fix/da1026-arch03-provider-type-registry` @ `22a15ed`，CI run 39110831683 success，停在分支 | 任务：pinned 指令 = 读 shared/work/deep-audit-1026/ARCH-COMMON.md，执行同目录 ARCH-BLUEPRINT.md 的「ARCH-03」小节。基线 main 9d182… |
| 10-19 12:07 | FIX-D 收口：deep-audit-1030 ISSUE-06（Provider 出站单源：passthrough UA + Azure auth）—— `fix/da1030-d-provider-outbound` @ `d55aee5`，CI #2323 绿，停在分支 | 身份链：基线 main f7364ec → 2 提交（c646c0a 主体 + d55aee5 自查补丁）→ HEAD d55aee582aee4f27a8d566af94cc1ae50146c939 == 远端 tip… |
| 10-20 09:21 | FIX-A2 收口（deep-audit-1034 ISSUE-02 + ISSUE-04）：SAF 根判定统一规范化 id + Shizuku 特权路径/uid 回退 —— `fix/da1034-a2-docsprovider-shizuku` @ `69f435a2`，CI #2366 success，停在分支 | 身份链三方逐字符：基线 b7acf94a → 两提交（0aaae779 生产 + 出货测试 / 69f435a2 四门扩展）→ LOCAL == REMOTE == CI head_sha = 69f435a2396cf… |
| 10-21 00:45 | FIX-3 收口（deep-audit-1037 / dv16 波 A-06+A-07+A-08）：`fix/dv16-a-provider-config` @ `97e2655c`，CI #2406 绿，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 单提交 97e2655c069a30da6032924736b36b252278e0ef → CI #2406（run 39456718375，job 118745… |
| 10-21 13:53 | FIX-5 收口（deep-audit-1038 / v18 波 A-08）：Kimi 自定义 baseURL 模型行全落 "Custom" —— `fix/dv18-5-custom-baseurl-provider-name` @ `ae9190c8`，CI #2426 attempt 1 success，停在分支 | 身份链三方逐字符：基线 main 10dd329e → 单提交 → 本地 == ls-remote == CI head_sha = ae9190c83249beb03d10eba06e88bbbdffcba1d9。4 … |
| 10-21 16:03 | deep-audit-1038 面 A（v17 波）全流程闭环并合入 main（用户令「先处理文档，再合并」）：main `6bbbded`，main CI #2429 success（Publish 执行，v1.0.0+2429） | 输入 audit_report_v17_a.md（457 行 / 61.5 KB，自 docx 解包；A-01..A-08 全 P1）。基线 main 10dd329e（= 面 B 并集）。第二道门 8/8 成立 · 0… |
| 10-22 12:18 | FIX-B1 收口（deep-audit-1041 / dv21 波 / B-01 + B-04）：`fix/dv21-b1-provider-live` @ `61b2d2ae`，CI #2458 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（d2a09830 生产 / a6db2ac0 单测 / 61b2d2ae 门）→ 本地 == ls-remote == CI head_sha = 61b… |
| 10-22 16:27 | F1 收口（COLLECT-R1 同族普查第 3 处）：`fix/verify-2476-rssprobe-finally` @ `3a740442`，CI #2477 success，停在分支 | 身份链：基线 main 5dd278da → 单提交 3a740442 → 本地 == ls-remote == CI head_sha = 3a7404421163fd8deef84e35e103fd00dd71a31… |
| 10-23 15:27 | deep-audit-1045 / FIX-8（B-04 P1）收口：分支 `fix/dv25-b4-picker-member-resolve` @ `95d84870`，CI #2537 success，停在分支 | 缺陷：模型选择器群组头部解析「选中群组的 active entry」用落库裸 find，同文件成员解析用单源 SystemVoiceEntries.resolveIn ⇒ 默认 Voice 组（只由 System 哨兵组… |
| 10-24 01:23 | FIX-B3 收口（deep-audit-1047 / B-05 P1）：模型组详情屏系统语音三处断层 — `fix/dv27-b3-model-group-system-voice` @ `8feb5466`（CI 绿于 `77ba33fc`），停在分支 | 缺陷（真树 4 处，报告报 3 处）：ModelGroupDetailScreen.kt 对 SystemVoiceEntries（默认 Voice Input/Output 组的全部成员，虚拟、无 instance、不… |
| 10-24 02:08 | deep-audit-1047 A1 收口（dv27 A-01+A-03）：`fix/dv27-a1-provider-wire-parity`，CI 绿，停在分支 | 身份链：基线 88ef9b27 → 4 提交（eeff5770 生产+门+测试 / 2878a229 修源形状测试+报告 / 1d7ef15e 清编译警告 / 67066ce3 报告补证据）→ 绿 run 3999775… |
| 10-24 14:40 | FIX-A3 收口（deep-audit-1049 / dv29-A3）：offload 分页单源契约 + market fallback — `/var/minis/shared/work/deep-audit-1049/a3/fix-reports/dv29-FIX-A3.md` | 身份链：基线 main b82aa8dc → 分支 fix/dv29-a3-offload-page-size，db7c1dff 生产 → 0ee8dc6d 测试 → 39886a34 门 → 7a7a40a3（CI 绿… |
| 10-25 06:13 | wide-1024 支03（五道 provider 门）收口：17 条见证 PRE=BLIND → POST=CAUGHT，CI #2631 绿，停分支 | 分支 fix/meta-wide-03-provider-registry @ 7cdb750c（基线 main 564c8f3e；2 提交：d29cae00 五门加固 + 7cdb750c 报告），worktree s… |
| 10-25 06:22 | wide-1024 支 02（provider 对称门）收口：2 门 6 见证全抓，CI #2640 绿，停分支 | 分支 fix/meta-wide-02-provider-symmetry @ 6ff65aad（CI 绿树 02aad4c5，报告回填后 git diff 02aad4c5 HEAD -- . ':(exclude)f… |
| 10-25 12:58 | wide3-01 provider-a 收口：4 门 14 见证全抓住，分支 CI #2655 绿（2026-10-25） | 分支 fix/meta-wide3-01-provider-a @ 5674f3a6（代码提交 5ad1226b = CI 绿树，报告回填后 diff --name-only 只含报告）。基线 main 6b6c918f… |
| 10-25 13:33 | wide3 支 02（provider-b，4 门 12 见证）收口：分支 `fix/meta-wide3-02-provider-b` @ `a97a3dd4`，CI #40164642837 success（attempt 1，head_sha 逐字符一致），停分支 | 12/12 见证反攻 CAUGHT（修复前 12/12 STILL_BLIND；装置 probe/wit.py + probe/run_reattack.sh，restore_ok=True 全部）： |
| 10-26 00:03 | B5-t09 收口：file_provider_paths + offload_relative_path 两门加固（12 见证全转 CAUGHT，CI #2686 绿，停分支） | 分支 fix/b5-fileprov-offrel @ 7e9f2c66b（远端一致）· 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow.py）· 提交三段：c5… |
| 10-26 00:06 | BATCH5 t12 收口：provider_import_id_roundtrip + models_provider_field 两门 12 条见证全补强（2026-10-25 夜） | 分支 fix/b5-provimport-modelsp：门提交 9996fc32b（2 文件 +959/−228，生产 .kt 0 行）+ 报告提交 e1e2f6c4e（fix-reports/FIX-BATCH5-t… |
| 10-26 05:40 | B6-t02 收口：provider_rpc_symmetry + file_preview_url_encode 两门 12 条见证全翻红（分支 `fix/b6-rpcsym-urienc` @ `35f7bb008`，CI #2699 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 956f27b0a（2 文件 +841/−541，生产 .kt 0 行、scan.sh/test_sca… |
| 10-26 10:00 | B6-t08 收口：session_provider_enabled + memory_ui_wiring 两门 13 条见证全翻红（分支 `fix/b6-sessionprov-memoryui` @ `72955761c`，CI #2700? 实为 run 40254147183 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 2a237ae91（2 文件 +837/−640，生产 .kt 0 行、res/ 0 行、scan.sh… |
| 10-26 10:05 | B6-t09 收口：tool_result_image_branch + provider_write_rules 两门 12 条见证全翻红（分支 `fix/b6-toolimg-provwrite` @ `2b5cfe0ba`，CI #2704 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 2b5cfe0ba（2 文件，生产 .kt 0 行、scan.sh/test_scan.py/_kotl… |
| 10-26 10:12 | B6-t06 收口：deeplink_settings_routes + provider_type_registry 两门 12 条见证全翻红（分支 `fix/b6-deeplink-protype` @ 门提交 `92bc6ddbc`，CI #2705 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 92bc6ddbc（两文件 +974/−413，生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112 / … |
| 10-26 12:02 | B7-t04 收口：provider_ua_parity + auto_approve_bypass 两门 12 条见证全翻红（分支 `fix/b7-ua-autoapprove` @ 门提交 `579a99d86`，CI #2712 绿，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+992/−488，provider_ua_parity_check.py 1718→1960 行、auto_approve_… |
| 10-26 12:30 | B7-t07 收口：session_sheet_id_binding + provider_models_cache_wiring 两门 12 条见证全 CAUGHT（分支 `fix/b7-sheetsession-provmodels`，门提交 `56617187e`，CI #2713 绿，报告 `11ee82526`，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+785/−436），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{lexer,ast,v… |
| 10-26 19:57 | R3-t11 收口：discarded_return_guard + provider_remember_key 两门判据加固（第三轮 dual-gate · B 波） | 分支 fix/r3-discard-rememberkey（基线 main f0f14d46d）→ 门提交 b181fe44c（2 文件 +630/−116）→ 报告回填 79e4d781（源码树等价：git diff … |
| 10-26 20:12 | R3-t22 收口：file_provider_paths + schedule_date_source 两门加固（分支 `fix/r3-filepaths-schedule` @ `990343cdd`，CI #2746 success，停分支） | 基线 main f0f14d46d，worktree round3b/wt/t22。改动面 = 2 个门脚本（+753/−4）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零… |

## 10. 备份 / 多端同步

**跨度** 2026-08-03 ～ 2026-10-26 · **176 条** · **状态** 收敛（多端同步已砍除，自动备份按「资产」语义保留）

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
| 09-23 01:07 | F6 族闭环审计（loop-audit-0922）—— 4 环：L3=1/L2=2/L1=1 | 报告：shared/loop-audit-0922/reports/F6-crash-update.md。环1 崩溃检测 L2（LEARN 完整缺 EFFECT）；环2 更新恢复 L1 断环；环3 自动备份 L2；环4 … |
| 09-24 08:48 | 备份快照减量分支 CI 绿（2026-09-24） | 任务：用户认为恢复/备份后留下的快照冗余 → 对齐后确认事实：快照不是备份写的，是 restoreWithSnapshot 每次恢复前写的回滚安全网（[fix-audit-p0-2/p0-3]）。全删会推翻 P0 安全修… |
| 09-24 10:14 | 每日自动备份减量分支 CI 绿（2026-09-24 续） | 任务：用户指出「自动备份那里还拖着一长串」→ 查证：每日自动备份是独立机制（AutoBackupManager + WebDavSync），本地+远端各保留最近 7 份（AUTO_BACKUP_KEEP=7，两个同值常量… |
| 09-24 20:39 | 今日（09-24）全部修改统一核查（用户要求）：独立 clone /tmp/rk-audit-0924f @ main | 今日（09-24）全部修改统一核查（用户要求）：独立 clone /tmp/rk-audit-0924f @ main 7b76c034（远端逐字符一致，远端只剩 main + chore/release-notes-s… |
| 09-24 20:41 | release-notes 源头同步合并收口（用户放行）：diff 走查 1 文件干净（仅 release body 块 | release-notes 源头同步合并收口（用户放行）：diff 走查 1 文件干净（仅 release body 块，4 条新说明，debug 签名警告保留）、YAML safe_load 验过；--no-ff me… |
| 09-25 23:34 | skill-metadata-resync 核查 + 合并收口（09-25，用户放行）：远端分支 fix/skill-m | skill-metadata-resync 核查 + 合并收口（09-25，用户放行）：远端分支 fix/skill-metadata-resync-from-disk @ 56b65fe1（1 commit，3 文件 … |
| 09-25 23:51 | 09-25 三天改动真机验证闭环（用户确认 1/2/3 已验证）：①skill-metadata-resync（装 +e | 09-25 三天改动真机验证闭环（用户确认 1/2/3 已验证）：①skill-metadata-resync（装 +ee22115b 包，改 SKILL.md description → 重开 → 技能页显示新值）✅；… |
| 09-27 20:32 | B1 落地：清洗已落库的孤儿闭标签 → 分支 fix/persisted-think-closers @ 7a17a75d（09-27，用户「做吧」，未合并） | 背景：Part A（df8bdf90）只管新流；旧行仍带 token。用户拍板做 B1。Part A 已由别处会话检查后合并进 main（02a935a2），本分支基于新 main。 |
| 09-29 00:13 | MEMORY-ROLLUP.md 去向定案（09-29，用户问「为什么不存在、去哪了」） | 结论：不是被删，是 09-10 换包名时没被带过来。 它曾存在——当前源码注释自己写着证据：MemoryRepository.kt:38-40「The rollup grows monotonically … (it r… |
| 09-29 09:26 | 技能入口标签修正（09-29，用户报「导入技能→Minis Skills 指向不一致」） | 问题：09-23 package rebrand（squash 7b7f12cd）把添加菜单/浏览器标题从 "Minis Skills" 改成 "RikkaMinis Skills"，但默认 URL 仍是 OpenMin… |
| 10-04 23:48 | 定时任务 L0 施工完成（feat/scheduled-tasks-l0-1004，CI 绿，待真机清单） | 续接中断会话（HANDOFF-2026-10-04）完成简报 B 全部施工，交付报告 shared/work/rk-sched-1004/DELIVERY-REPORT-1004.md。 |
| 10-06 09:41 | dev-history 云端同步完成：docs-only 归档同步 82410cc 直推 main（沿用该目录 6 先例 | dev-history 云端同步完成：docs-only 归档同步 82410cc 直推 main（沿用该目录 6 先例惯例），remote sha==local 逐字符一致；docs-only 提交不触发 CI 是预期… |
| 10-08 21:01 | §74 修复 + §75 登记（2026-10-08） | §74 已修：/var/minis/skills/github-ops/scripts/gh_sync.sh L8 need_env 改用 _need_env_n/_need_env_v 前缀（原全局 name/val … |
| 10-08 23:39 | 两线 bug 成因结构分析（用户问「为什么会出现」） | A 线（编辑重发丢附件）+ B 线（PR #5 .minisbak 兼容层）的共同产生机制，四模式： |
| 10-09 00:09 | D5 域扫描完成（sweep-1008） | 任务：/var/minis/shared/sweep-1008/tasks/D5.md（data+provider+conversation+service+config），报告已写 sweep-1008/reports… |
| 10-09 00:42 | 任务 C 修复完成：WebDAV 空口令静默清口令（sweep-1008 fix C） | 分支 fix/sweep-p1-webdav-pw-1008 @ 61a50d9687f741e0090b6d859c0af3f5aa8b6a74，CI run 37808857774 completed/success… |
| 10-09 01:03 | P1 批量修复合并完成（2026-10-08 晚，用户令「没问题就合并」） | 闭环：3 个并行修复会话（任务书 sweep-1008/fix-tasks/A-C.md）→ 各自分支 1 commit + CI 绿 → 用户放行 → 合并前核查三项齐（diff 逐文件 / CI head_sha 逐… |
| 10-09 12:49 | 备份 OOM（512MB largeHeap 打满）根因与修复 — 2026-10-09 | 症状：真机报 写入失败: Failed to allocate a 4480 byte allocation … <1% of heap free after GC（target/growth limit 5368709… |
| 10-09 13:32 | 备份 OOM 修复合并收口（2026-10-09） | 合并完成（用户放行「修好了，合并吧」）：三项核查齐（diff 3 文件逐行读无夹带、0 冲突标记；分支 CI 37884504189 head_sha 逐字符 = 71f605e；独立证据=影子 harness 三路对照… |
| 10-10 21:51 | Android-AI-gateway 项目（用户 fork） | 仓库：/var/minis/workspace/Android-AI-gateway（fork 自 alarmedvine/Android-AI-gateway） |
| 10-12 17:36 | T3 完成：four_way_sync 门升级 v2（推导族）——PR #9，Scan Gate 绿 | 分支 ci/field-sync-derive-1012 @ 136ede2（父 = main 4fa0cb20），PR https://github.com/OWNER/RikkaMinis/pull/9，Scan G… |
| 10-13 13:12 | sweep-1013 收口：第二道门核实 + 三修复会话派发（2026-10-13） | 输入：三域审计报告齐（D1 数据层 10 条 / D2 安全面 11 条 / D3 UI 10 条，共 31 条），报告基线 main @ ad4a4beb。 |
| 10-13 14:02 | sweep-1013 修复收口：三支合并 main 9f4f5766（2026-10-13） | 用户令：「好了，他们都完成了，你这里继续吧。」→ 读作收口授权（核实 + 合并）。 |
| 10-13 14:17 | 两天改动审查（10-12 / 10-13，用户令「检查一下这两天进行的修改吧」） | 窗口：main 206e71105（10-12 早）→ 9f4f57666，34 文件 +2722/−198（11 开发提交 + 9 合并单元）。 |
| 10-14 18:05 | SWEEP-1014 收口核查完成（2026-10-14，32 分支） | 报告：shared/sweep-1014/VERIFY-REPORT.md。核查时刻 main 仍 d5d2be59f（未移动）。 |
| 10-14 18:26 | SWEEP-1014 全量完成：32 支合入 main（2026-10-14） | main = e63608ee9（32 个 sweep1014 修复分支全部经 Merge API 顺序合入；main 并集树 build-apk success @ e63608ee9，run 38547394427）… |
| 10-14 20:30 | ↳ RV-6 完成：回归审查 8 文件（2026-10-14） | RV-6 完成：回归审查 8 文件（2026-10-14） |
| 10-14 20:41 | ↳ RV-4 回归审查完成（UI/媒体/浏览器域，16 文件） | 发现总账：0 引入 / 3 同族 / 2 行为变化 / 2 观察项。报告 shared/review-1014/reports/RV-4.md。 |
| 10-14 22:11 | RV-1014 修复批次收口：8 支全绿合入 main a7123cf82（含备份/ContentCapture 决策分 | RV-1014 修复批次收口：8 支全绿合入 main a7123cf82（含备份/ContentCapture 决策分支） |
| 10-14 23:10 | 根治 §97：内置技能资产落盘改内容哈希门控（fix/skill-asset-hash-gate @ 87db4a3，CI #2095 绿，停在分支未合并） | 用户令：选「根治」（二选一：即时 bump 版本 / 根治改内容哈希）。基线 main a7123cf82，3 文件 +351/−34。 |
| 10-15 00:11 | §97 内容哈希门控在真机未生效——根因定性：DB 行来源不是 bundled（原本就有，非本次改出） | 验证对象：设备包 1.0.0+2095（lastUpdate 23:21:46）= CI run #2095 = fix/skill-asset-hash-gate @ 87db4a333（run #2096 = mai… |
| 10-15 00:42 | §97 根治二段合并收口：fix/bundled-row-adoption-1015 → main 4950a334（用户令「检查云端正在跑的分支，没有问题的话就合并」） | 核查对象：run #2097 = fix/bundled-row-adoption-1015 @ 5da7153f7（workflow_dispatch），核查时 in_progress、机械项阶段已 completed… |
| 10-15 01:05 | §98 文档同步收口 + 沙箱膨胀数据清理（2026-10-15） | 文档同步：dev-history 三件套重建（1734 条 → 24 saga，脱敏 3 遍 NONE）→ 分支 docs/dev-history-sync-1015 → --no-ff 合入 main e5e733b（… |
| 10-15 06:33 | FIX-B 完成：技能/备份恢复修复（fix/audit-1015-skillbackup @ 6645cce，CI #2099 绿，停在分支未合并） | 任务：shared/audit-1015/fix-tasks/FIX-B.md；报告 shared/audit-1015/fix-reports/FIX-B.md；工作树 shared/work/fix-b-1015/r… |
| 10-15 12:05 | dev-history 档案更新 + 仓库同步（2026-10-15，用户令「更新一下文档，包括仓库的」） | 执行：rebuild（1743 条 / 74 天 / 31071 行）→ sanitize（main 296 替换 + INDEX 35 替换，probes NONE）→ SAGAS（24 sagas / 171 orp… |
| 10-15 21:55 | FIX-C 交付收口：model-use 跨进程边界四块（F-03 P0 + F-04 + F-08 + F-10）—— 分支 CI #2135 绿，停在分支（2026-10-15） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-C.md 并立刻执行（基线 main ca8b95d1）。报告 shared/work/audit3-1015/fix-reports… |
| 10-15 22:58 | VERIFY-1015 真机验证（设备包 1.0.0+2142，用户令「现在这个应用就是包含了所有修改的，开始验证吧」） | 身份链闭合：设备 base.apk sha256 96792f34…5348b（14,349,225 B，装于 22:33:37）== CI run #2142（id 38749571877，success，main） … |
| 10-16 02:00 | deep-audit-1015 收口完成：5 支合并 + 文档云端同步 + CI #2150 跨支交互失败 fix-forward（2026-10-15，用户令「既然检查没有问题的话，那就合并吧。触发之后顺便更新文档，包括云端」） | 五支合并：A retry-wire / B agentloop-report / C sched-errorprop / D katex-bridge / E modeluse-proc 全部 --no-ff 进 mai… |
| 10-16 09:51 | FIX-F 收口：deep-audit-1016 B-7b/B-7c（定时任务/无头会话状态闭环）—— 分支 CI #2157 绿，停在分支（2026-10-16） | 任务书 shared/work/deep-audit-1016/fix-tasks/FIX-F.md（纪律文档实际在 shared/work/deep-audit-1016/COMMON.md 与 TRIAGE.md，不… |
| 10-16 17:58 | FIX-H 收口：OffloadRssProbe 锁范围（deep-audit-1016c ISSUE-11）—— fix/da1016-rssprobe-lock-scope @ c18d1b4，CI #2179 绿，停在分支 | 修法：record() 的 synchronized(st) 原来只护 4 个字段自增；LEAK-SUSPECT 检查-置位、govern 判定、累计复位全在锁外（NativeOffload.kt Semaphore(2… |
| 10-16 22:43 | FIX-E 收口：deep-audit-1018 ISSUE-09（备份恢复漏重映射 + 漏打包蒸馏层，P1）—— `fix/da1018-backup-remap` @ `e3d16b4`，CI #2190 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-E.md 并执行（先读 COMMON/TRIAGE）。报告 shared/work/deep-audit-1018/fix-r… |
| 10-17 00:26 | deep-audit-1018 合并 main + 文档同步（用户令「合并吧，顺便更新文档」） | 合并：integration/da1018c --no-ff → main 492c1be（合并树与已验 CI 的树逐字节一致 1227577dfc6f…；本地 HEAD == 远端 main）。main CI #219… |
| 10-17 07:17 | FIX-F 收口：deep-audit-1019 ISSUE-06（parts_json 三路漏斗绕过，P1）—— fix/da1019-partsjson-funnel @ d87169f，CI #2207 dispatch，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-F.md；报告 fix-reports/FIX-F.md；装置 repo-f/probe/（durable）。基线 main e4… |
| 10-17 07:37 | FIX-A 收口：deep-audit-1019 ISSUE-01（extra_headers/custom-endpoint 信封方言三份副本合一）—— fix/da1019-modeluse-proto @ 3bd0193，CI #2205 绿，停在分支 | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-A.md（先读 COMMON/TRIAGE，基线 main e416551）。报告 fix-reports/FIX-A.md（… |
| 10-17 08:41 | deep-audit-1019 合并 main + 文档同步（用户令「合并」→「不要忘了更新文档」→「不用等了」） | 合并：integration/da1019 --no-ff → main 97be4dc（合并树 1886183893f9… 与分支 tip 差异 0 行）。合并前核查三项过：远端 main 仍 e416551（无并发推… |
| 10-17 11:21 | deep-audit-1020 收口完成：8 支全绿 → 试合树 `integration/da1020` @ `bdc6d68`，CI #38997214449 **success**（停在分支，未合并 main）+ 文档三件套同步 | 输入：用户附件 shared-c9689fbd.md（8 工单）。基线 main 942b0111d（60 门）。用户「按流程来吧」→ 第一道门 8/8 成立 → 8 支派发（minis-sessions-cli sen… |
| 10-17 11:25 | ↳ deep-audit-1020 合并 main（用户放行「合并」，2026-10-17） | 合并前核查（三项全过）：①31 文件全在预期域（11 门脚本 + 18 生产/测试 .kt）逐支归属对齐、冲突标记/调试残留 0；②合并树 scan 66/0 + test_scan 238、head_sha 逐字符三方… |
| 10-17 13:32 | FIX-A 收口：deep-audit-1021 ISSUE-01（技能恢复 id 贯通）+ ISSUE-10（会话覆盖清理闭环）—— fix/da1021-skill-restore-session-cleanup @ 433430f，CI #2229 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-A.md 立刻执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pr… |
| 10-17 13:35 | FIX-B 收口：deep-audit-1021（ISSUE-02 merge 导入 id 规范化 + ISSUE-09 备份字段齐全）—— fix/da1021-import-id-roundtrip @ 6beb225，CI #2228 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-B.md 并执行。报告 fix-reports/FIX-B.md；装置 repo-b/probe/（留… |
| 10-17 17:28 | FIX-D 收口：deep-audit-1022 ISSUE-04（OpenMinis 兼容层 `appendV1Suffix` 极性反转 + 四字段漏迁）—— fix/da1022-openminis-compat @ 59fb69f，CI #2241 绿，停在分支 | 任务书 /var/minis/shared/work/deep-audit-1022/fix-tasks/FIX-D.md（+COMMON.md）；报告 fix-reports/FIX-D.md（8 节，16727→11… |
| 10-17 20:33 | FIX-I 收口：deep-audit-1023 ISSUE-10（自动备份流式导出/拉取净化/主线程列目录，P1）—— fix/da1023-backup-streaming-fetch @ `69b7d83fc053`，CI #2245 绿，停在分支 | 任务：读 shared/work/deep-audit-1023/fix-tasks/COMMON.md + FIX-I.md（先读 TRIAGE §2.10，报告行号不可信）。基线 main cd9e97b。工作树 s… |
| 10-17 20:41 | deep-audit-1023 FIX-A 收口：FileProvider 路径覆盖 + 大消息导出（分支 CI #2246 绿，停在分支） | 交付：fix/da1023-fileprovider-paths @ af7ecfcddbf026b50b10fc2a8e583c5e214d6799（基于 main cd9e97bf），4 文件 +788/−15。CI… |
| 10-18 00:44 | FIX-H 收口：deep-audit-1024 ISSUE-08（Reset All 后 UI 不刷新 + 内存 session 授权/拒绝未清，P2）—— fix/da1024-offload-permission-reset-sync @ db52d99，CI #2262 绿，停在分支 | 身份链：基线 main f688c97c6877e3b801eeacd33d58c2371d1b803f → 单提交 db52d99786f64171fcb4abe4643a60036787764a；远端 tip == … |
| 10-18 14:10 | FIX-G 收口：deep-audit-1026 ISSUE-07（ShareReceiverActivity 主线程同步导入+删暂存，P1）—— `fix/da1026-share-import-io` @ `2e680cd`，CI #2283 绿，停在分支 | 任务 fix-tasks/FIX-G.md（COMMON.md 实际在上级目录 deep-audit-1026/，不在 fix-tasks/）。报告 fix-reports/FIX-G.md（8 节 170 行，无待填）… |
| 10-18 14:30 | FIX-F 收口：deep-audit-1026 ISSUE-06（CSV 预览绕过 readCapped 无界读 + 两预览 remember 无键）—— `fix/da1026-csv-bounded-read` @ `011812a`，CI run 39103139228 绿，停在分支 | 任务 fix-tasks/FIX-F.md（COMMON 在上级目录）。报告 fix-reports/FIX-F.md（8 节 / 12794 B / 无占位符）；装置 repo-f/probe/（durable、不入库… |
| 10-18 15:05 | FIX-H 收口：deep-audit-1026 ISSUE-08（group context-limit 写漏斗不同步 + `get groups` 缺两键，P2）—— `fix/da1026-group-sync` @ `0353112`，CI #2288 绿，停在分支 | 任务 fix-tasks/FIX-H.md（COMMON 实读自上级目录）；报告 fix-reports/FIX-H.md（8 节 / 16002 B / 无 TODO）；装置 repo-h/probe/（jvm/{ex… |
| 10-19 01:28 | FIX-B 收口：deep-audit-1028 ISSUE-03 + ISSUE-04（配置作用域 / 外部写回流）—— `fix/da1028-b-config-sync` @ `7a17c5a`，CI #39148284718 绿，停在分支 | 身份链：基线 889d645 → 分支 @ 7a17c5a603da5e1f3590dbfab69bcd139a9f4be8（父=基线，单提交，11 文件 +714/−28）→ CI run 39148284718 wo… |
| 10-19 07:41 | deep-audit-1029 FIX-B 收口：ISSUE-03（记忆编辑屏主线程同步 IO + SOUL 缓存回流）—— `fix/da1029-b-memory-io` @ `98c0fc5`，CI #39176155210 绿，停在分支 | 身份链：基线 main 83caac6 → 单提交 98c0fc59e2cbe307b5e539853dc2a2608b4988e8（3 文件 +254/−26）→ branch dispatch CI 39176155… |
| 10-19 07:54 | FIX-E 收口：deep-audit-1029 I06+I08（UI 状态同步支）—— fix/da1029-e-ui-state @ be75a09，CI #39177457402 绿，停在分支 | 身份链：基线 main 83caac6（da1028 收口树）→ 分支 fix/da1029-e-ui-state @ be75a098fc2ab67a5723c17e797ed3370ee77abe（单提交，4 文件 … |
| 10-20 09:06 | FIX-A1 收口（deep-audit-1034 ISSUE-01 P0）：备份 zip 不再带走 MCP OAuth 明文桥 —— `fix/da1034-a1-backup-creds` @ `603d297c`，CI #2365 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 19b1c3e（生产+测试）/ 603d297（门扩展）→ 本地==远端==CI head_sha；CI #2365（run 39349095475）success… |
| 10-20 15:57 | §VERIFY-2388 终态：UI 批按用户证词结清 + §1034-A1 备份四面结清（backlog 269 段） | 纯 UI 观感批：用户日常使用未发现问题 → 按用户证词结清（观感类本就是人眼判据，~15 项不再逐项驱动）。 |
| 10-20 19:26 | FIX-B2 收口（deep-audit-1036 B-02 + B-08）：`fix/dv15-b2-settings-screens` @ `c9a9662`，CI #2393 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 2 提交（411d551 生产 5 文件 +338/−155 · c9a9662 测试 4 文件 +576）→ 本地 == 远端 == CI head_sha = … |
| 10-21 10:38 | FIX-5 收口（deep-audit-1038 / dv17 波 B 支）：`fix/dv17-5-prefs-prime-fullscreen-bars` @ `58bc0231`，CI #2420 success，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 三段提交（10799a31 生产 +48 · 14ca6c8f 测试 +281 · 58bc0231 门 +441/−20）→ 本地 == 远端 == CI hea… |
| 10-21 15:57 | 背景包 v21 + 提问模板 v18-A/B（用户点名：报告必须给可导出文件）—— 2026-10-21 下午 | 触发：用户「这一批已经消化完了，为下一批做准备吧」+「提示词中明确的要求他给出可以导出的那个文件……现如今，他偶尔会不给出那种可以导出的那个文件，就是有一定概率性」。 |
| 10-22 03:20 | FIX-A1 收口（deep-audit-1040 / dv20 波 A-01 P0 + A-02 P1）：`fix/dv20-a1-backup-voice` @ `b0640ad1`，CI #2449 绿，停在分支 | 身份链三方逐字符一致：基线 main 295cf778 → 7 提交 → 本地 == git ls-remote == CI head_sha = b0640ad1716018b130a24b6af4aebc714231… |
| 10-22 04:00 | FIX-B4 收口（deep-audit-1040 / dv20 波 B-08 P1）：`fix/dv20-b4-backup-busy` @ `cdfa3190`，CI #2450 **12/12 ALL GREEN**，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（ca1ea19b 生产 / 6b6f8e19 测试 / cdfa3190 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 12:52 | FIX-A3 收口（deep-audit-1041 / A-03 P1）：`fix/dv21-a3-import-timestamp` @ `a52b6d7c`，CI #2464 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（87956788 生产 / 58160c4f 测试 / a52b6d7c 门）→ 本地 == ls-remote == CI head_sha = a52… |
| 10-24 01:51 | deep-audit-1047 B1 收口（dv27 波 B-01+B-02+B-03 P1）：`fix/dv27-b1-preview-export-guards` @ `5bd22bfa`，CI #2574 success，停在分支 | 身份链：基线 main 88ef9b27 → 5 提交（24d9026e 生产 / 4ee6166c 测试 / 62b5212d 门 / 6825f9fd 报告 / 548354d4 警告修复 / 5bd22bfa L4… |
| 10-24 10:12 | FIX-A5 收口（deep-audit-1048 / A-05 P1）：Stage 9 覆盖 Stage 7 的 MCP 合并结果 — `fix/dv28-a5-mcp-restore-stage9` @ `8122ca84`，CI #40051341292 success，停在分支 | 缺陷：导出照打 mcp/servers.json；Stage 7 用 mcpRepo.importJSON( 合并（本机独有服务器保留、save() 落盘）；Stage 9 解压时 destByPrefix["mcp"]… |
| 10-24 21:56 | 门见证第二批任务书 v3（材料自足 + 工作量配额 + 可导出报告）— 2026-10-24 夜 | 用户三条纠正（第一波 12/12 全证伪之后）：①没要求可导出 .md 报告；②任务量轻了 5~10 倍（参考上一环 mounts/笔记/RikkaMinis审计背景包/，单份报告 80–135KB）；③不确定外部模型能… |
| 10-25 18:06 | wide4 支 12 收口：four_way + skill_import 两门判据加固（分支 `fix/wide4-12-fourway-skillimport`，CI #2671 绿，停分支） | 基线 19e70dab（worktree wt-12，基分支含共享 _kotlin_lexer/_kotlin_ast）。改动面：2 个门脚本（+1080/−166）+ 1 份报告；生产代码 0 行、scan.sh/te… |
| 10-26 11:19 | B7-t02 收口：skill_restore_session_cleanup + speech_active_engine 两门 12 条见证全红（分支 `fix/b7-skillrestore-speech` @ `8b865532e`，CI #2708 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8b865532e（2 文件 +1124/−720，生产 .k… |
| 10-26 19:14 | R3-t08 收口：parts_json_funnel + skill_restore 两门 6 条见证全 CAUGHT（分支 `fix/r3-partsjs-skillrestore` @ `a924214c2`，门提交 `1c3f9c497`，CI #2731 绿，停分支） | 基线 main @ f0f14d46d。改动面 = 2 个门脚本（+693/−13）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰，门 112 / 夹具 344 不变。 |
| 10-26 19:49 | R3-t18 收口：backup_remap_rollup / mcp_oauth_lifecycle / backup_streaming_export 三门 9 条见证全 CAUGHT（分支 `fix/r3-backup-mcp` @ `335faf435`，CI #2741 绿，停分支） | 基线 main f0f14d46d。改动面 = 3 个门脚本（+928/−43），生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112 / 夹具 344 不变。绿 sha 9c17f… |

## 11. 渲染性能 / Markdown / 长会话卡顿

**跨度** 2026-08-05 ～ 2026-10-26 · **136 条** · **状态** 已闭环 + 持续加固（09-15 七轴 sweep：纯逻辑层零新 bug，真发现全在接线/时序边界）

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
| 09-23 01:56 | T1（dispatch-0923 badge 补渲染）执行完毕：分支 fix/session-badge-drawer- | T1（dispatch-0923 badge 补渲染）执行完毕：分支 fix/session-badge-drawer-render @ 0049054，CI run 35762155750 success 且 head… |
| 09-23 01:58 | ↳ T2（dispatch-0923）完成：update.download 恢复半环接通。分支 fix/update-pen | T2（dispatch-0923）完成：update.download 恢复半环接通。分支 fix/update-pending-resume-shortcircuit @ 87299e8d，CI run 3576262… |
| 09-23 02:17 | dispatch-0923 两修复任务收口（badge 补渲染 + update 恢复半环） | T1 badge 补渲染 00490542：8 文件 +33/−1（DrawerSessionRow:408 圆点位 + 7 语言 sessionlist_badge_paused），scan 17/17，CI 3576… |
| 09-24 10:37 | 存储页进出卡顿修复分支 CI 绿（09-24，分支 CI 绿停） | 任务：用户报告「设置→存储→存储」进出历史对话卡顿、会话越大越明显。诊断（/tmp/rk-audit-0924x @5fb8a244）：StorageManagementScreen.reload() 挂 Launche… |
| 09-24 20:39 | 今日（09-24）诚实边界真机验证闭环：用户确认全部真机验证通过——滑杆可见吸附手感、存储页进出卡顿改善、思考强度跨对话 | 今日（09-24）诚实边界真机验证闭环：用户确认全部真机验证通过——滑杆可见吸附手感、存储页进出卡顿改善、思考强度跨对话记住、备份 3 份轮转。今日 8 笔合并（051a6b35..7b76c034）至此三源取二闭环（C… |
| 09-25 21:46 | typing-band-overlay 回归修复（09-25 深夜，停止节点）：用户报今天合并引入的回归——agent | typing-band-overlay 回归修复（09-25 深夜，停止节点）：用户报今天合并引入的回归——agent 执行期间底部 band 持续显示「正在思考/等待响应中」直到回合结束。根因（证据链闭合）：fix/t… |
| 09-27 18:46 | 「工具调用框消失／被挤掉」定案：不是丢块，是重进时的消息合并（09-27） | 结论：工具块在数据层、重建层、渲染层全部完整，不存在「整批丢框」的路径。用户看到的「框被挤没」= 重进会话时多轮 assistant 行被合并成一条气泡的呈现后果。 |
| 09-27 23:51 | 应用性能瓶颈盘点（09-27，源码 3a36c697 + 全天日志取证，只登记未修） | 已验证健康：flatten（100–960μs/frozenHits 100%）、markdown parse（全天仅 1 条 slow）、DB 加载（3–32ms）、offload/压缩端到端、KaTeX、首帧（han… |
| 09-28 01:54 | 任务 B（巨型 LazyColumn item）完成 = 停止节点 → 分支 `perf/tool-pill-fold` @ `418a0da`，未合并 | 交付：分支 perf/tool-pill-fold @ 418a0daf2b8c24682ab0d2a3b9bccfc268fdd1ed（基 main 3a36c697，10 文件 +322/−0），CI run 363… |
| 09-28 02:02 | ↳ 性能三分支核查 + 合并 main = 7990c0fc（09-28，用户「没有问题就合并」） | 对象：A perf/memory-page-io @369000f0（Memory 页 chunkText 双切）、B perf/tool-pill-fold @418a0da（冻结回合药丸折叠，分支名由 perf/gi… |
| 09-28 09:07 | B 折叠「重新设计」讨论 + 「只渲染可见部分」虚拟化路线分析（09-28） | 用户反馈：B 折叠生效但形态别扭——只折工具药丸、思考块留在外面；交替结构（思考→工具→思考）视觉上变成「靠近顶部一张折叠行 + 下方一串思考标题墙」。用户提出新方向：能否走「只渲染用户看到的那一部分」（item 级虚拟… |
| 09-28 09:39 | ↳ 工具药丸折叠 revert 合并 main = 8aaad10（09-28，用户拍板「负值交易，按你说的来」） | 决策链：用户判折叠为负值设计（UX 损害 > 性能收益）→ A/B 实测证实 → revert B 合并（3c72bc1a）→ 用户授权「跑完直接合并，检查环节在当初合并时已完成」→ main = 8aaad10（mer… |
| 09-28 09:43 | ↳ 折叠 revert 装包验证闭环（09-28，用户确认） | 用户确认真机安装的是 8aaad10e（折叠 revert 合并后的 main）编译的 APK，运行没有问题。两处欠账随之闭环： |
| 10-07 07:45 | usage-stats-perf-1007 施工完成：SQL 聚合 + 部分索引 + off-main（分支 CI 绿，未合并） | 交付：分支 fix/usage-stats-perf-1007 @ aabd3636（2 commits：0665c6b 主改动 6 文件 +286/−119 + aabd363 测试桩补齐 3 文件 +9），CI ru… |
| 10-12 20:25 | T5 派发：MarkdownParser 差分探针 + 重复派发事故（2026-10-12 晚） | 派发：MarkdownParser.kt（639 行纯 Kotlin object 零 import，String→List<Block>，宿主可测；已有覆盖仅 numbered list + KaTeX 上限）→ T5… |
| 10-12 20:40 | T5 完成：MarkdownParser 差分探针（PR #11，CI 绿，停在分支未合并） | 分支 test/md-parser-probe-1012 @ 85f3535c（2 commits：910ff2b 修复 / 85f3535 测试 58 例），PR #11（base main ← 分支），CI run … |
| 10-12 20:41 | ↳ PR 合并：test/md-parser-probe-1012 进 main（用户放行「没问题就合并」） | PR 合并：test/md-parser-probe-1012 进 main（用户放行「没问题就合并」） |
| 10-12 20:42 | T5 收口：MarkdownParser 探针完成 + 2 个 P1 修复已合并（2026-10-12 晚） | 产出：64 条探针语料 + 58 例测试（MarkdownParserBlockStructureTest，468 行）+ 2 个 P1 缺陷修复（①heading 尾部 # 双向：## C# 内容被吃、## title… |
| 10-14 09:13 | 「上游更流畅」诊断：fork 基线落后一整个 v1.14 周期（2026-10-14） | 用户问：为什么用官方 OpenMinis 时感觉比 fork 更流畅。 |
| 10-14 09:27 | v1.14 移植 ROI 分析：条带级挑拿（2026-10-14） | 背景：用户令「建议跟哪些 + 投入产出比」。对 v1.14（678 文件 +104K/−6K）做逐文件三态分类（fork vs 上游）：11 可直拿 / 150 两边都改 / 472 fork 没有——整域跟版不可能（f… |
| 10-14 14:28 | FIX-5 完成：链接解析 B6 + KaTeX 假就绪 P1b（fix/sweep1014-ui-links @ 12 | FIX-5 完成：链接解析 B6 + KaTeX 假就绪 P1b（fix/sweep1014-ui-links @ 12b6354，CI #2005 绿） |
| 10-14 16:00 | 报告：shared/sweep-1014/reports/FIX-R11.md；工作树 shared/work/swee | 报告：shared/sweep-1014/reports/FIX-R11.md；工作树 shared/work/sweep1014-fix/r11/。 |
| 10-15 16:30 | FIX-A6 完成：图片解码三处全尺寸位图 + 缩放算术出 0 尺寸（audit2-PERF-03，2026-10-15） | 用户令：「读这个文件并立刻执行：/var/minis/shared/work/verify-audit2-1015/fix-tasks/FIX-A6.md」。 |
| 10-16 00:33 | FIX-D 交付收口：KaTeX 桥 arity（BUG-05 P1）+ 缓存回收（ARCH-03 子项）—— 分支 CI #2147 绿，停在分支（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-D.md 并立刻执行（基线 main e10fb2aa）。报告 shared/work/deep-audit-1015/fix… |
| 10-16 09:43 | FIX-G 收口：deep-audit-1016 8c/8d（通知行去 AI 头像 + compact 分割线谓词读全部块）—— 分支 CI #2155 绿，停在分支（2026-10-16） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-G.md 立刻执行。基线 main 04e74c53325a；分支 fix/da1016-chat-sysinfo @ ee3… |
| 10-16 09:44 | crash-1016 修复交付：列表项吞缩进围栏 + `\(\)` 空 latex（分支 `fix/1016-md-list-fence-inline-math` @ `25e5400`，CI #2153 绿，停在分支） | 任务：用户「修」（承接上一轮的闪退诊断）。基线 main 04e74c53325a（= 设备版本 1.0.0+2152 的 head_sha，逐字符一致）。报告 shared/work/crash1016-fix/REP… |
| 10-17 07:46 | FIX-G 收口：deep-audit-1019 ISSUE-07（KatexWebViewPool ready 竞态 + ChatViewModelStore 别名表锁外读，P1）—— fix/da1019-katex-store-race @ d440e9b，CI #2199 绿，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-G.md；报告 fix-reports/FIX-G.md（10964 B，14 节齐全）；装置 repo-g/repo/probe… |
| 10-17 22:46 | CPU 占用取证（用户问「使用的时候 CPU 是一直占用的吗，是现在还是某次修改之后」） | 方法（可复用）：沙箱 /proc 对 app uid 可见 → 直接读 /proc/<pid>/stat 的 utime/stime(jiffies@100Hz) 做增量；宿主侧权威数据走 android-shizuku… |
| 10-17 23:11 | ↳ deep-audit-1023 修复合并后独立复核（用户令「验证一下修的那些东西」） | 身份链：main d71a904（代码）+ f688c97（docs），CI #2256 success @ d71a904；设备装 1.0.0+2255（lastUpdate 10-17 22:51）= integra… |
| 10-20 08:29 | FIX-B3 收口（deep-audit-1034 B-05+B-06 两支）：列表块 mathSpans 接线 + 技能面板增量 merge —— `fix/da1034-b3-markdown-sheets` @ `b906313`，CI #2359 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 4 提交（9b87adf markdown · 5032ebc sheet · 38fc024 门 · b906313 测试）→ HEAD b906313ad3a32312e… |
| 10-20 19:23 | FIX-B4 收口（deep-audit-v15 B-07）：MarkdownParser 块结构解析缺口 —— `fix/dv15-b4-markdown-parser` @ `64c115a8`，CI #2392 绿，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 单提交 64c115a88687a1e5d5e24dea305bd7a70e8c5c80（3 文件 = 1 生产 + 2 既有测试；生产 +114/−11，含 KD… |
| 10-23 11:51 | FIX-7 收口（deep-audit-1044 / dv24 波 B-01+B-02+B-05，P1×3）：`fix/dv24-b1-picker-quicktest-composer` @ `71dc0225f1`，CI #2529 (run `39894855850` / job `120222802096`) success，停在分支 | 身份链三方逐字符：基线 main f94413f5 → 5 提交（f49a541d B-01 生产 / 5ef21819 B-02 生产 / 1529ecae B-05 生产 / 5edf7021 测试 / 71dc02… |
| 10-25 12:48 | WIDE3-06 收口：浏览器/webapp/KaTeX 四门见证加固（分支 `fix/meta-wide3-06-browser-webapp` @ `687daa83`，CI #2656 绿，停分支） | 基线 main 6b6c918f。4 门 × 3~4 条外部见证，修复前 14/14 全绿（门瞎）→ 修复后 14/14 CAUGHT（shared/work/meta-wide3/reattack.py 逐条 appl… |
| 10-25 18:31 | WIDE4-02 收口：katex/budget/liveness 三门见证加固（分支 `fix/wide4-02-katex-budget-liveness` @ `9697a52e`，CI #40183453003 绿，停分支） | 基线 dbab7a3e（= 19e70dab + 共享原语 _kotlin_lexer.py/_kotlin_ast.py）。门提交 c35d62a4（3 门 +919/−325，生产 .kt 0 行改动）+ 报告 96… |
| 10-26 09:35 | B6-t05 收口：tts_voice_and_sanitizer + katex_ready_race 两门 13 条见证全翻红（分支 `fix/b6-tts-katex` @ 门 `c367faf6f`，CI #2700 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + _kotlin_valueflow）。门提交 c367faf6f（生产 .kt 0 行、scan.sh/test_… |
| 10-26 18:37 | R3-t05 施工：chat_menu_prefs_listener + katex_ready_race 两门缝判据加固（2026-10-26 18:3x） | 分支 fix/r3-menuprefs-katex @ 5eabfb51e（基线 main f0f14d46d）。改动面 = 2 个门脚本 +580/−3 + 报告，生产 .kt 0 行、冻结面零触碰、门 112/夹具 … |

## 12. 审计 / 整改（多轮）

**跨度** 2026-08-03 ～ 2026-10-27 · **376 条** · **状态** 常态机制（已固化为 security-audit-checklist / four-way-sync-check 等 skill）

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
| 09-22 16:53 | 09-22：shared/ 全量盘点 —— 三个「唯一存留副本」挡住直觉清理 | 用户指令：「整理一下这个应用的那些各种文件之类的东西」。 |
| 09-23 00:34 | 闭环审计框架落地 + spawn_agent 技能快照坑 | 工具坑（新，已实证） |
| 09-23 01:04 | F4 族闭环审计完成（loop-audit-0922） | 报告：/var/minis/shared/loop-audit-0922/reports/F4-session-concurrency.md。环数 5；L4/L3/L2/L1/L0 = 0/1/3/1/0。 |
| 09-23 01:07 | F6 族闭环审计（loop-audit-0922）—— 4 环：L3=1/L2=2/L1=1 | 报告：shared/loop-audit-0922/reports/F6-crash-update.md。环1 崩溃检测 L2（LEARN 完整缺 EFFECT）；环2 更新恢复 L1 断环；环3 自动备份 L2；环4 … |
| 09-23 01:08 | F5 族闭环审计完成（provider 流控族，loop-audit-0922） | 报告：shared/loop-audit-0922/reports/F5-provider-flow.md。6 环：L4=1 / L3=2 / L2=3 / L1=0 / L0=0。 |
| 09-23 01:23 | 闭环审计六族收口（39 环） | 总账：L4=3(7.7%) / L3=15 / L2=17(44%，全部断在 EFFECT「动作后无回读」) / L1=2 / L0=0。 |
| 09-24 09:56 | 用户拍板：对 RikkaMinis 应用做「冗余」系统性扫描（不是修，只要清单）。用独立 clone /tmp/rk-r | 用户拍板：对 RikkaMinis 应用做「冗余」系统性扫描（不是修，只要清单）。用独立 clone /tmp/rk-redund-scan（main 5fb8a24）跑 7 维冗余扫描，脚本归档 /var/minis/… |
| 09-24 10:24 | 冗余清理分支完成（09-24）：fix/redundancy-sweep-0924 @ e96876e（11 文件 +6 | 冗余清理分支完成（09-24）：fix/redundancy-sweep-0924 @ e96876e（11 文件 +6/−1714），CI run 35945942167 success + head_sha 逐字符一… |
| 09-24 12:22 | release CI 红灯真根因：guard 裸字节扫描假阳性（09-24，最终定论） | 迭代过程（3 次假设被推翻，最终靠本地解剖 dex 定案）： |
| 09-25 09:12 | 硬编码参数审计（09-25） | 用户问「应用还有哪些参数是硬编码的」→ 硬编码参数全量审计（09-25，main 2022b0c，独立 clone /tmp/rk-hardcode）。脚本 /tmp/scan_hardcode.py + /tmp/sc… |
| 09-25 21:28 | 分支清理 + 「检查=上全套」规则落地（09-25，用户拍板）：①feat/typing-status-band @ a | 分支清理 + 「检查=上全套」规则落地（09-25，用户拍板）：①feat/typing-status-band @ a54c2f78 API DELETE 204（已被 fix/typing-band-live 取代且… |
| 09-25 22:04 | 09-25 全量回归审计：今天 24 提交 / 33 文件，无功能级 bug，2 条次生开销 P2 + 5 条边界登记 | 范围：基线 2022b0c5（昨 21:12）→ main 50b399c9，git log 实测今天进 main 的是 24 个提交 / 33 文件 +1511/−267（不止记忆里的 8 条 merge——上午还有 … |
| 09-27 17:57 | 09-27 今日修改审计收口 | 对 main 8dcb82e8→aeffcef9 做全套核查：24 文件 +838/−53，工作树干净、冲突 0、scan 20/20、i18n 1488 keys×7 CLEAN、four-way CLEAN，rele… |
| 09-27 18:24 | 09-27 当日修改最终收口审计 | 远端 main=7a5f54b9，由代码合并 0d7be26e + 档案合并组成；代码线对基线的机械核查 20/20、four-way CLEAN、i18n 1488×7 CLEAN、CI run 36310708393… |
| 09-27 20:57 | 审计对象：02a935a2 + efac6f86（思考标签孤儿闭标签 Part A + Part B） | 最近两次合并审计 + 文档更新收口（09-27，用户「检查最近两次修改 + 更新文档」） |
| 09-27 22:54 | ↳ 第一档确定性修复完成（09-27） | 用户批准修复第一档三项：①§41-1 stripOrphanThinkClosers 去掉 lowercase() 坐标错位，改用原文 regionMatches(ignoreCase=true)；②恢复 parseRo… |
| 09-27 23:00 | ↳ 合并 fix/deterministic-tier1-cleanups → main（09-27） | 用户同意「没问题就合并，有问题修」；合入 fix/deterministic-tier1-cleanups @ 8369a9dfbe9c 到 main，--no-ff merge = c10f567522f75b62fd… |
| 09-27 23:15 | ↳ 文档三件套第二次重建 + 合并 main = 3a36c697（09-27 深夜，用户「更新一下文档…可以直接合并」）： | 文档三件套第二次重建 + 合并 main = 3a36c697（09-27 深夜，用户「更新一下文档…可以直接合并」）：三回归测试（test_rebuild/test_sanitize/test_sagas）先全 PAS… |
| 09-28 20:10 | Procedure Layer v0 · Wave1 S1（脱敏+经验提取）完成（2026-09-28） | 交付（仅这四个文件，未碰 contract.py/别人的文件）：scripts/redact_trace.py（sha 2be04f7cd5ed…）、scripts/extract_experience.py（6e645… |
| 09-28 23:28 | 减法审计（接手会话）：每会话固定注入 137KB，78% 在日报 | 审计报告：/var/minis/shared/retire-audit-0928/AUDIT.md（完整数据+清单）。 |
| 09-29 11:30 | 常驻通知精简 + 通知模块审计（09-29，分支 refactor/simplify-fgs-notification，未合并） | 停止节点：分支 CI 绿，未合并（合并权在用户）。 |
| 09-30 16:29 | 上游 A 档移植三分支合并后审计（09-30 晚） | 今天净账：main 0c78f00 → 0924445f = 27 文件 +2569/−88（3 功能分支 + chore fold 归一 strings 约定，fold 由用户推）。CI #1886/#1887/#18… |
| 09-30 17:23 | 两条审计发现修复完成（09-30，分支 fix/a11y-evergranted-gate-fgs-guidance @ 70a93bc3，CI 绿 #1889 未合并） | 修复（3 文件 +437/−12，基线 origin/main 0924445f）： |
| 10-01 01:22 | 修复 feat/fastio-primitives-2 的 `mv --force`（commit 98edf12，CI 绿停止节点） | 改动（4 文件 +92/−8，同一分支补 commit d74a49c1 → 98edf12）： |
| 10-02 16:19 | 四条判定（10-02 登记项，分支 fix/clearchat-compact-ce-1002 @ cfed933e→e | 四条判定（10-02 登记项，分支 fix/clearchat-compact-ce-1002 @ cfed933e→ee2b2cea，CI run 36981575610 success 绿停未合并）：P2-2/P3-… |
| 10-02 16:21 | 复核 fix/clearchat-compact-ce-1002 @ ee2b2ce（force-push 后新 tip | 复核 fix/clearchat-compact-ce-1002 @ ee2b2ce（force-push 后新 tip）：开发者按前轮建议修了 CI 红的 1 行测试缺陷（全局首匹配 indexOf → 区域锚定 + … |
| 10-02 16:41 | P2 已修并合入分支 fix/clearchat-compact-ce-1002 @ 90fd2e72（10-02，CI | P2 已修并合入分支 fix/clearchat-compact-ce-1002 @ 90fd2e72（10-02，CI 绿停，未合并）：clearChat 补 _compactSummary.value = null（… |
| 10-02 17:05 | 审查（第三轮）fix/clearchat-compact-ce-1002 @ 90fd2e7（CI run 369840 | 审查（第三轮）fix/clearchat-compact-ce-1002 @ 90fd2e7（CI run 36984034118 success @ head_sha 逐字符，停止节点已达，未合并）：增量 1 comm… |
| 10-02 17:11 | 合并 fix/clearchat-compact-ce-1002 → main（10-02，用户放行「没检查出什么问题就 | 合并 fix/clearchat-compact-ce-1002 → main（10-02，用户放行「没检查出什么问题就合并」+「该攒着的攒着」，merge f4b9d3be）：合并前三项核查全过（diff 152 行全… |
| 10-02 20:30 | shared 大清理（10-02，用户放行「就是处理一下吧」，5.1 G → 251 M）：61 个已完成战役目录删除， | shared 大清理（10-02，用户放行「就是处理一下吧」，5.1 G → 251 M）：61 个已完成战役目录删除，81 份最终报告（REPORT/FINDINGS/HANDOFF/DELIVERY/README）先… |
| 10-05 10:59 | RikkaMinis 主 clone 清理执行完成（10-05，用户令「清理」） | 执行：8 支已合并残支全部删除——tier1=f5009adf、fastio-2=d74a49c1、fastio-3=0080f420、offload=3e496953、a11y=70a93bc3、quiet-first… |
| 10-05 11:05 | 远端 3 旧支 + rk-compact 残支清理完成（10-05 第二轮，用户令「旧的已合并的没用了，处理了吧」） | 执行：远端 3 支全删——fix/compact-truncation-guard-1005（6bdcff5）、fix/compact-telemetry-superseded-1005（e71342ca）、feat/s… |
| 10-05 15:29 | ↳ 应用功能缺口盘点完成（10-05，用户令「看看这应用还有什么功能缺口、哪方面需要强化」） | 报告：/var/minis/shared/work/app-gap-audit-1005/REPORT.md（审计产出，未改产品代码）。 |
| 10-06 09:12 | dev-history 档案清理：新增 2 排除轴，去掉 21 条无关条目（10-06，用户报「最近几天无关的多」） | 触发：用户要求更新开发档案并去除与 RikkaMinis 开发无关的部分。 |
| 10-07 07:16 | 用量页（UsageStatsScreen）加载慢 5-6s 调查 — 审计完成未修（10-07） | 对象：设置→Token 用量（UsageStatsScreen）。每次进页面 LaunchedEffect 全表扫 messages（token_usage/created_at 无索引）+ org.json 逐行解析聚… |
| 10-07 23:50 | shared/work + logs 数据膨胀清理（10-07，用户报「应用数据膨胀厉害」） | 膨胀构成（实测）：shared/work 1004M + logs 200M（debug- modelservice 全量日志占 117M）+ mounts 811M（用户音频/笔记，非 app 数据）。设备 /data… |
| 10-08 13:13 | RikkaMinis 审计图表资产诊断 + 报告形态提案（10-08） | 任务：用户觉得「挂载/笔记/RikkaMinis审计图表」现状太可惜，想升级为"图表式报告"，先听看法。 |
| 10-08 17:21 | RikkaMinis 审计图表 → 对外报告站交付完成（2026-10-08 下午；上承「种子等拍板」） | 交付：自包含报告站 report/（31 页 / 128 PNG / 12M）：L1 叙事 index → L2 27 章节页 + 513 条可搜台账 → L3 五形态探针 → 诚实层 archive。工作副本 /var… |
| 10-09 00:03 | D2 域扫描完成（sweep-1008，子代理） | 产出：/var/minis/shared/sweep-1008/reports/D2.md。域 = ui/chat 其余 ~87 个（除 D1 状态核心），重点全读 ChatScreen/ChatViewModel/St… |
| 10-09 00:04 | D4 域扫描完成（sandbox + offload，main @ e59ed04a）：报告 /var/minis/sh | D4 域扫描完成（sandbox + offload，main @ e59ed04a）：报告 /var/minis/shared/sweep-1008/reports/D4.md。8 条发现（0 high / 3 med… |
| 10-09 00:08 | D3 扫描（ui/settings + backup）完成 — 12 发现（0H/5M/7L） | 报告：/var/minis/shared/sweep-1008/reports/D3.md（backups 全读 11 文件 + BackupSettingsScreen 全读 + 其余 settings grep 签名… |
| 10-09 00:09 | D5 域扫描完成（sweep-1008） | 任务：/var/minis/shared/sweep-1008/tasks/D5.md（data+provider+conversation+service+config），报告已写 sweep-1008/reports… |
| 10-09 00:18 | 全库四模式扫描收口（2026-10-08 晚） | 产物：/var/minis/shared/sweep-1008/{TRIAGE.md, reports/D1-D6.md}。6 域并行会话（llm-bug-audit 规则库+四结构模式）扫全库 197K 行，46 项发… |
| 10-10 15:26 | GLOBAL 记忆架构章节 + 远端旧分支清理（2026-10-10，用户令「加进去，并且把云端的那个删掉吧」） | ① GLOBAL.md 新增「## 记忆架构（六层 + 两条辅助）—— 2026-10-10 定案」（用户明确授权编辑，插在「待办登记纪律」节后）。内容 = 六层表格（层 / 位置 / 记什么 / 谁来读·何时）：日报 … |
| 10-12 15:44 | streaming-claim-race 同族审计结论（2026-10-12，用户问「以前修的 bug 是否也有修一边漏一边」） | 修复自身完备：7 认领点全部 mint-before-flip、5 finally 全加 claimToken 门、releaseSlot 无条件（正确）。26 处机械改动齐。 |
| 10-12 18:55 | 流式 think-tag 扫描器两个活缺陷（2026-10-12 晚，主会话侦察发现 → T4 修复已派发） | 在给「下一步该弄什么」做侦察时，在 ThinkTagScanning.kt（main @ 13949bb0）实测复现两个从未被测试覆盖的活缺陷： |
| 10-12 19:34 | T4 完成：think-tag 扫描器双缺陷修复（PR #10，CI 全绿，停在分支未合并） | 分支 fix/thinktag-scan-integrity-1012 @ 123e2cc（父 = main 13949bb0），3 commits：11e3ab6（A：删 bufLower 改源坐标搜索 indexOf… |
| 10-12 19:44 | PR #10 合并完成：think-tag 扫描器双缺陷修复进 main（2026-10-12 晚，用户令「检查→没问题就合并」+「合并后不用等」） | 分支：fix/thinktag-scan-integrity-1012（T4 会话产物）@ 123e2cc8，3 commit（A 源坐标搜索 / B 跨 chunk 状态携带 / 测试），仅 2 文件（ThinkTag… |
| 10-12 23:10 | RikkaMinis 优化空间审计（10-12，用户令「分析应用还有哪些优化空间」） | 报告：/var/minis/shared/work/app-opt-audit-1012/REPORT.md（全证据链，未改产品代码）。 |
| 10-13 16:08 | 债务清理批次（fix/debt-cleanup-1013，PR #13，6 commits @08f818f3c） | 范围：审查自报 4 条（D-1/D-2/D-3/D-4）+ backlog §89 择条 4 条（F-2/F-5/F-9/F-10），CI：scan-gate 38367123483 + build-apk 383671… |
| 10-14 17:18 | FIX-R13 完成：photos B22a 视频饿死 + B22b DateFormat 线程安全 + P8 recy | FIX-R13 完成：photos B22a 视频饿死 + B22b DateFormat 线程安全 + P8 recycle（fix/sweep1014-photos @ ba0ef82c，CI #2037 绿） |
| 10-15 01:05 | §98 文档同步收口 + 沙箱膨胀数据清理（2026-10-15） | 文档同步：dev-history 三件套重建（1734 条 → 24 saga，脱敏 3 遍 NONE）→ 分支 docs/dev-history-sync-1015 → --no-ff 合入 main e5e733b（… |
| 10-15 06:13 | §99 全仓审计第二道门核实 + 4 会话派发（2026-10-15） | 输入：用户丢来全仓审计报告（shared-e8a7c3f5.md，8 BUG + 5 ARCH + 6 PERF）令「检查核实」→ 核实后「工程量大的话用会话子代理安排出去」。 |
| 10-15 13:27 | 全仓审计报告第二道门核实（2026-10-15，附件 shared-04273e16.md） | 基线：main @ 6fe4771；装置 /var/minis/shared/work/verify-audit-1015/repo（分支 verify）；报告 shared/work/verify-audit-1015… |
| 10-15 15:54 | 第二道门核实：全仓审计报告（附件 25 项）→ VERIFY-REPORT.md（2026-10-15） | 用户令：「按照老流程来」+ 附件 rikkaminis_full_repository_audit_report.md（64.5KB，BUG×10 + DESIGN×8 + PERF×7 = 25 项）。基线 main … |
| 10-15 23:33 | deep-audit-1015 第二道门核实 + 5 支派发（用户令「走流程吧」，附新审计报告 14 项） | 输入：用户附件 rikkaminis_deep_audit_report.md（1388 行，BUG-01..09 + ARCH-01..03 + PERF-01/02）。基线 main e10fb2aa53fb。工作树… |
| 10-16 00:01 | FIX-A 收口：deep-audit ARCH-01（T3 副作用安全重试接线，P0）+ BUG-09（二次截断）（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-A.md 立刻执行（基线 main e10fb2aa）。报告 fix-reports/FIX-A.md；装置 shared/w… |
| 10-16 00:33 | FIX-D 交付收口：KaTeX 桥 arity（BUG-05 P1）+ 缓存回收（ARCH-03 子项）—— 分支 CI #2147 绿，停在分支（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-D.md 并立刻执行（基线 main e10fb2aa）。报告 shared/work/deep-audit-1015/fix… |
| 10-16 16:38 | deep-audit-1016c 启动：第二道门核实 + 8 支派发（用户令「按流程吧」，附新审计报告 12 项） | 输入：附件 shared-8c9ee41c.md（170KB / 2645 行，ISSUE-01..12）。基线 main e3ef74545bcc（= 1017 收口后最新树，报告声明 35 门/176 夹具与之吻合）… |
| 10-17 08:41 | 审计背景包 v2 更新（笔记/RikkaMinis审计背景包/audit-context.md）— 2026-10-17 | 背景：用户的全仓审计流程 = 源码 zip + 提示词 → 便宜模型审计 → 沙箱二道门核实 → 多支并行修复。v1（10-17 上午写的）加入「背景信息 .md」以提升审计质量。本轮用户令「把这个更新一下吧，又进行了一… |
| 10-17 08:46 | 旧分支清理：sweep1014 批次 32 个远端分支删除（用户令「清了吧」） | 安全删除流程（先核查后删）：sweep-cleanup.py 先用 GitHub compare API（compare/main...<branch>）逐支判 ahead_by==0（= 已并入 main、无独有提交）… |
| 10-17 11:29 | 审计背景包 v3 更新（吸收 deep-audit-1020）— 2026-10-17 | 输入：用户令「又完成了一波修复，开始更新」。本轮 = deep-audit-1020：8 支修复（A models-cache 漏参 / B shizuku argv / C offload 相对路径+tar 前缀 / … |
| 10-17 12:32 | 沙箱存储清理：/var/minis 11.2G → 580M（用户报「缓存数据又上去了」，2026-10-17） | 构成与处置（Android app cache 本身只有 23.9MB，真正的积累在沙箱）： |
| 10-17 13:20 | FIX-F 收口：deep-audit-1021（WebApp asset URL 单源 + AddToHome 清理闭环）—— fix/da1021-webapp-asset-url-cleanup @ ab9c247，CI #2226 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-F.md（8 支并发派发，我领 FIX-F）。报告 fix-reports/FIX-F.md；装置 r… |
| 10-17 13:32 | FIX-A 收口：deep-audit-1021 ISSUE-01（技能恢复 id 贯通）+ ISSUE-10（会话覆盖清理闭环）—— fix/da1021-skill-restore-session-cleanup @ 433430f，CI #2229 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-A.md 立刻执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pr… |
| 10-17 14:22 | 审计背景包 v4 更新（吸收 deep-audit-1021）— 2026-10-17 | 输入：用户令「刚刚又完成了一波修复，更新一下吧，为下一轮做准备」。本轮 = deep-audit-1021（11 工单 → 8 支，门 67→74），已并入 main 396adc442（10-17 14:13 SGT，… |
| 10-17 16:19 | deep-audit-1022 启动：第二道门 + 8 支派发（用户令「走流程」，附新审计报告 10 工单）— 2026-10-17 | 输入：附件 rikkaminis_full_audit_report.md（1529 行 / ISSUE-01..10）。基线 main ba4daf699fb4bcc89eb33cdeb788272283910b91（… |
| 10-17 17:22 | 审计趋势分析（用户问"越修越容易是不是错觉"）— 2026-10-17 | 数据源：main 合并历史 154 个 merge（compare API 逐支按路径分类：prod/test/gate/device）+ 9 轮 TRIAGE + API 实测各基线门数（scripts/scan/_c… |
| 10-17 18:13 | 审计背景包 v5 更新（吸收 deep-audit-1022 + 趋势分析结论）— 2026-10-17 | 文件：/var/minis/mounts/笔记/RikkaMinis审计背景包/audit-context.md（178 行/24386 B → 217 行/30.7 KB，v4→v5）。 |
| 10-17 18:15 | 审计提示词 v2（原提示词按九轮实测改进）— 2026-10-17 | 文件：/var/minis/mounts/笔记/RikkaMinis审计背景包/audit-prompt-v2.md（13.2 KB）。原提示词由用户提供（角色 = 首席架构师 + 安全/性能审计专家；四节：任务边界/求… |
| 10-17 18:27 | ↳ deep-audit-1022 合并 main + 文档归档完成（用户令「合并吧，不要忘了更新那个文档」） | 合并：integration/da1022（@ 8128896945fc）--no-ff 合入 main → 215daf0bf548db1972d94ac58d54e5b4596d625c，合并后 tree = cec… |
| 10-17 22:05 | 审计背景包 v6 + 提示词 v3 更新（为下一波审计准备，2026-10-17 22:0x） | 位置：/var/minis/mounts/笔记/RikkaMinis审计背景包/（挂载盘，用户笔记）。三份文件：audit-context.md（v6，45 KB）· audit-prompt-v3.md（v3，18 K… |
| 10-17 22:15 | deep-audit-1023 分支清理完成（用户令「清掉」，2026-10-17） | 全量扫描（shared/work/deep-audit-1023/loose_ends_1023.py check）：远端 12 分支 → 11 个非 main 全部 MERGED（compare/main...<b> … |
| 10-17 23:51 | deep-audit-1024 第二道门核实 + 9 支派发（用户令「走流程吧」，附新审计报告 9 项 v3） | 输入：REPORT-1024.md（1414 行，ISSUE-01..09）。基线 main f688c97（= da1023 收口树）。工作区 shared/work/deep-audit-1024/（本地克隆自 10… |
| 10-18 00:38 | FIX-B 交付收口：deep-audit-1024 ISSUE-02（Gemini base URL 四处分叉 → 统一出口）—— fix/da1024-gemini-base-single-source @ ac1bb9e，CI #2264 绿，停在分支 | 身份链：基线 main f688c97；单提交 ac1bb9e854dbbc01c74ef60d48f343de069de2c4；远端 tip == 本地 HEAD == CI head_sha 三方逐字符一致；CI r… |
| 10-18 00:49 | FIX-C 交付收口：deep-audit-1024 ISSUE-03（P1 二级引用漏清理 + ModelGroupsScreen 无监听）—— `fix/da1024-compact-pin-cascade-and-listener` @ `f7f1419beb32`，CI #39049819858 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md；装置 repo-c/probe/（durable：jvm/{… |
| 10-18 01:51 | RikkaMinis 审计背景包更新（v7 / prompt v4）+ deep-audit-1024 收口核验 | 用户指令原话：「为下一波的那个审计做准备吧，该更新的更新，该优化的优化，该改的改。你自己的判断。」随后明确：「在笔记文件夹挂载的那个文件夹里面 RikkaMinis审计背景包」——要更新的是发给审计模型的背景包与提示词（… |
| 10-18 01:53 | deep-audit-1024 分支清理终态（用户令「清掉吧」，2026-10-18） | 终态：远端只剩 main（bcb6569，tree 2ab5be8d），12 个分支（9×fix/da1024- + feat/trace-step-tag-1017 + integration/da1024 + doc… |
| 10-18 09:03 | deep-audit-1025 第二道门 + 9 支派发（用户令「走流程」，附审计报告 v4 9 项） | 输入：shared-45079a1b.md（135,630 B / 1874 行，ISSUE-01..09）。基线 main bcb6569（1024 收口树）。工作区 shared/work/deep-audit-10… |
| 10-18 10:00 | FIX-H 交付收口：deep-audit-1025 ISSUE-08（挂载探针 + 日志清理离开主线程）—— `fix/da1025-mount-probe-io` @ `d66c7a6`，CI #39087410687 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-H.md（P2，8 节报告齐）。基线 main bcb6569。交付 3 文件：2 生产（+32/−5）+ 1 门脚本扩展（238… |
| 10-18 10:05 | FIX-D 收口：deep-audit-1025 ISSUE-04（browser_tabs/<sid>.json 免疫生命周期清理，P1）—— fix/da1025-browser-tabs-lifecycle @ c1dbf37，CI #39087510098 success，停在分支 | 工单 shared/work/deep-audit-1025/fix-tasks/FIX-D.md（先证再改）；报告 fix-reports/FIX-D.md（8 节 / 169 行）；装置 repo-d/probe/（… |
| 10-18 10:21 | FIX-B 收口：deep-audit-1025 ISSUE-02（CrashFrequencyDetector 主线程长耗时 I/O + 漏挂 TTL 清理，P1）—— `fix/da1025-crash-share-io` @ `1612c0b`，CI 绿，停在分支 | 任务 fix-tasks/FIX-B.md（先证再改，COMMON 实际在上级目录）。报告 fix-reports/FIX-B.md（105 行，§1b 记首轮 CI 红）；装置 repo-b/probe/（durabl… |
| 10-18 11:27 | 审计背景包升级 v8 + prompt v5（用户令：为下一波外发审查准备背景文件与提示词） | 位置：/var/minis/mounts/笔记/RikkaMinis审计背景包/（该目录即审计发包目录，另有 /var/minis/mounts/笔记/RikkaMinis审计全记录/）。交付面 = GitHub 仓库源… |
| 10-18 11:39 | deep-audit-1025 分支清理完成（用户令「清掉」） | 结果：11/11 删除 http 204 —— 9×fix/da1025- + integration/da1025 + docs/dev-history-1025。判据 compare/main...<b> 的 ahe… |
| 10-18 13:06 | deep-audit-1026 启动：第二道门核实完成 + 7 支派发（用户令「按流程来」，附新审计报告 8 项） | 输入：shared-d2163f57.md（132KB，8 ISSUE）→ shared/work/deep-audit-1026/REPORT-1026.md。基线 main dc04fc3（= 1025 收口树）。门… |
| 10-18 16:40 | ARCH-01 交付：ProviderWriteRules 单源校验层（`fix/da1026-arch01-provider-write-rules` @ `5737f75`，CI run 39110944268 success，停在分支未合并） | 任务 ARCH-COMMON.md + 蓝图 ARCH-01 小节（Provider / Model 跨面写入规则收口）。报告 fix-reports/ARCH-01-REPORT.md（11223 B，§1–§9）；装… |
| 10-18 16:47 | ARCH-03 收口：ProviderType 编译期穷举元数据契约 —— `fix/da1026-arch03-provider-type-registry` @ `22a15ed`，CI run 39110831683 success，停在分支 | 任务：pinned 指令 = 读 shared/work/deep-audit-1026/ARCH-COMMON.md，执行同目录 ARCH-BLUEPRINT.md 的「ARCH-03」小节。基线 main 9d182… |
| 10-18 16:48 | ARCH-02 收口：PrimedPrefs 自治重预热契约（`fix/da1026-arch02-primed-prefs` @ `0f9f7cd`，CI #2293 绿，停在分支） | 任务 /var/minis/shared/work/deep-audit-1026/ARCH-COMMON.md（工单 = ARCH-BLUEPRINT.md 的 ARCH-02 小节）。报告 fix-reports/A… |
| 10-18 17:18 | ARCH 批次收口完成（用户「他们完成了」/「跑完了」）：3 支并入并集树，CI 绿 @ 407bdd4 | 身份链：基线 main 9d18269 → 3 支 → 并集树 integration/da1026-arch @ 407bdd4（3 次 --no-ff 零冲突 + 门注册提交）。 |
| 10-18 17:49 | ARCH 批次合并 main + 文档归档完成（用户「合并，并更新文档」）+ CI 假红取证（flaky） | 身份链：并集树 integration/da1026-arch 407bdd4（CI #2294 success）→ main 4822410（--no-ff，树对拍 9f793097 逐字节一致）→ 文档合并 → ma… |
| 10-18 17:52 | 1026 + ARCH 全批次收官：分支清理 15/15 + flaky 攒批（用户拍板） | 用户判问：flaky 是否影响应用正常运转？→ 确认不影响（两文件都在 src/android/app/src/test/ 测试源集，不进 APK；生产 BrowserRssProbe.kt 的 summary() 空态… |
| 10-18 18:13 | 下一批审计准备：背景包 v9 + 提示词 v6 + 三项提案评估（2026-10-18） | 用户令：为下一批审计做准备（挂载文件夹里的背景文件 + 提示词，该改的改）；检查本地与云端 dev-history 是否更新，没有则更新；并评估三项提案（UI 动作差分 / Set-of-Marks / 沙箱快照回滚）是… |
| 10-18 18:16 | 1026+ARCH 批次装机验证完成（用户令「老规矩验证，UI 先放着」） | 装机：1.0.0+2295（lastUpdate 17:53:08）= run #2295（39114863481，attempt 2）产物 @ 4822410。 |
| 10-18 19:47 | deep-audit-1027 启动：第二道门核实 + 7 支派发（用户令「走流程」，附新审计报告 8 项） | 【本条为 12:56 UTC 日报截断事故后由主会话重建：原文不可逐字节取回（app 日志仅存前 309 字符、语义索引最后 build=10-10），以下按本会话记录与 TRIAGE/DISPATCH 台账重写；事实以… |
| 10-18 21:57 | deep-audit-1027 合并 main + 文档归档 + 分支清理（用户「合并，清理，更新文档」）+ 日报被覆盖事故（第 3 次复发）与原文级恢复 | ① 合并：integration/da1027 @ 5030841 → main d026210（--no-ff，main tree 56d148ea == CI 验证过的 5030841^{tree} 逐字节一致 = … |
| 10-18 22:20 | 审计背景包升级（外部审计模型输入包）：context v9→v10 + prompt v6→v7 | 位置与用途：挂载 笔记/RikkaMinis审计背景包/——audit-context.md + audit-prompt-v7.md + 源码 zip 一起发给外部审计模型（不是本机会话）。产出报告走既有流程（第二道门… |
| 10-19 00:56 | FIX-C 收口：deep-audit-1028 ISSUE-02 + ISSUE-05 + ISSUE-06（UI 状态失步 / filesDir 清理接线 / 深链响应式）—— `fix/da1028-c-ui-state-wiring` @ `aaa4cc1`，CI #39145801329 绿，停在分支 | 身份链：基线 main 889d645（tree f12ca356）→ 单提交 aaa4cc1b6267a40e7683c2e4795a575d7e2e93ba（父 = 基线）→ 远端 tip == 本地 HEAD ==… |
| 10-19 03:08 | deep-audit-1028 全流程闭环（用户令「合并」「一并做」）：合并 + main CI 绿 + 分支清理 + 文档三件套 | 身份链终态：main 889d645 →（代码合并 83caac6）→（文档合并 05f6fc435910506f9d8c40300db9d57532772328）。远端只剩 main。 |
| 10-19 03:13 | 审计背景包 v11/v8 升级完成（用户令「为下一步的审计做准备：背景文件+提示词挂到文件夹子文件夹」） | 交付位置：/var/minis/mounts/笔记/RikkaMinis审计背景包/（= 挂载「笔记」的子文件夹） |
| 10-19 03:35 | ↳ deep-audit-1028 真机验证（部分闭环，余项攒波统一验） | 装机：v1.0.0+2310（versionCode 220002310）@ 03:16 == main CI #2310 @ 83caac6 产物；行为级旁证 = background.overlay 可读（旧包必报 … |
| 10-19 09:34 | 审计背景包升级 v11/v8 → **v12/v9**（用户令「为下一波审计做准备」+ 背景文件）：基线 `b353317`，两处引用失真被回树取证抓住当场修正 | 位置：挂载 /var/minis/mounts/笔记/RikkaMinis审计背景包/（发给外部审计模型，非本机会话）。终态根目录 = README.md + audit-context.md(v12, 521 行 / … |
| 10-19 09:37 | deep-audit-1029 两项收尾完成（用户令「把那两件事做了」）：分支清理 + 文档三件套重建 | ① 分支清理 7/7（cleanup_1029.py，判据 ahead_by == 0、fail-closed 拒绝预期外分支）：先 check 全量（MERGED ✓ ×7 / 未并入 0）→ delete 7/7 h… |
| 10-19 13:24 | deep-audit-1030 收尾闭环（用户令「继续推」）：合并 main + main CI 绿 + 文档三件套 + 分支清理 7/7 | 身份链终态：基线 main f7364ec → 5 支并集树 integration/da1030 @ 97e73e7（tree 7145a2bf）→ 合并 main 5032e6a（main^{tree} == 97e… |
| 10-19 13:34 | 审计背景包 v12→v13 升级（deep-audit-1030 收口后） | 位置：mounts/笔记/RikkaMinis审计背景包/。三件全升：context v12→v13（146→159 KB / 546 行）· prompt v9→v10（46→50 KB / 360 行）· zip b… |
| 10-19 17:44 | 审计背景包 v13→v14 升级（deep-audit-1031 收口后） | 位置：mounts/笔记/RikkaMinis审计背景包/。三件全升：context v13→v14（159→174 KB / 579 行）· prompt v10→v11（50→54 KB / 375 行）· zip … |
| 10-19 20:50 | 审计背景包 v14→v15 + prompt v11→v12：结构性重写（用户令「按这一套更新」） | 位置：mounts/笔记/RikkaMinis审计背景包/。三件全换：context v15（174→80 KB / 459 行）· prompt v12（53→21 KB / 226 行）· zip RikkaMini… |
| 10-19 20:56 | deep-audit-1032 分支清理完成（用户令「清了吧」）：远端只剩 main | cleanup_1032.py delete：7/7 http 204（5×fix/da1032- + docs/dev-history-1032 + integration/da1032，先 check 相全量核验 m… |
| 10-19 23:48 | FIX-C 收口（deep-audit-1033 ISSUE-03 P1）：android-speech --source 相对路径 —— `fix/da1033-c-speech-cwd` @ `d3037a76`，CI #2348 绿，停在分支 | 身份链三方逐字符：基线 main c6d426a7 → 2 提交（生产 + test/门）→ CI run 39275575813（#2348）success @ 2026-10-19T15:10:26Z→15:28:0… |
| 10-20 01:44 | deep-audit-1033 分支清理完成（用户令「清掉吧」）：远端只剩 main | loose_ends_1033.py delete：7/7 http 204（5×fix/da1033- + integration/da1033 + docs/dev-history-1033；先 check 全量核验… |
| 10-20 01:47 | 审计背景包 v16 + 提问模板 v13-A/B（首次一分为二）—— 2026-10-20 凌晨，用户令「更新背景资料+提示词，准备两份给两个会话」 | 位置：mounts/笔记/RikkaMinis审计背景包/。基线换代 c6d426a → b7acf94a（da1033 五支 + docs 已并入 main；src/ 与 integration/da1033 @ aa… |
| 10-20 13:19 | FIX-A4 收口（deep-audit-v14 ISSUE-07 + ISSUE-08）：`fix/dv14-a4-single-source` @ `571af04`，CI #2382 success，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 6 提交（ff3ae38 ISSUE-07 生产 · a0b5223 ISSUE-08 生产 · b7bd20b 出货测试 · 1147b20 门扩展 · f0b8… |
| 10-20 15:09 | deep-audit-v14 合并 main 完成（用户令「合并」）：main `71882a8e` + main CI #2388 success + 档案 2046 条 + 分支清理 10/10 | 执行顺序（沿用 da1034 的「文档先合、代码后合」）：三项核查（diff 56 文件逐文件过 / 冲突标记 0 / 冻结面 0 触碰 / 本地 HEAD == 并集 CI head_sha / 独立证据 = 新树本地… |
| 10-20 21:44 | deep-audit-1036 全批闭环（2026-10-20 晚）：main `7c38a79d` + main CI #2400 success（Publish 执行）+ 档案 2061 条 + 分支清理 9/9 | 合并链（用户令「合并吧」）：三项核查（46 文件逐文件过 / 冻结面 0 触碰 / 本地 HEAD==远端==CI head_sha / 独立证据 = 并集树本地 108 门+329 夹具双绿）→ 代码并集 --no-f… |
| 10-21 08:35 | deep-audit-1037 分支清理完成（用户令「清了」）：远端只剩 main @ `528558f4` | cleanup_1037.py delete（先重跑 check 全量核验：7 分支 ahead_by==0 / docs/dev-history-1037 404 = 本地合并未推送，无需删）→ 7/7 http 20… |
| 10-21 16:18 | deep-audit-1038 分支清理完成（用户令「先清了吧」，真机验证后置）：13/13 已删，远端只剩 main | check→delete→三源核实：cleanup_1038.py check 首跑 REFUSED——发现 6 个预期外分支 = 面 B（dv17 波）同样在等清账的欠账；逐个核 compare/main... 后 1… |
| 10-21 17:17 | 存储清理：shared/work 13G → 1.4G（删 189 个历史审计批次 repo 克隆）—— 2026-10-21 | 触发：用户「应用存储飙到 22G 了，处理一下」。 |
| 10-21 19:29 | FIX-B1 收口（deep-audit-1039 / dv19 面 B）：composer 实时源单一化 —— `fix/dv19-b1-chat-input-single-source` @ `e8bfb52b`，CI #2433 success（attempt 1），停止节点达成，未合并 main | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（e0b84e84 生产 / b9c531b9 测试 / e8bfb52b 门）→ LOCAL == git ls-remote == CI head_sh… |
| 10-21 21:25 | 审计背景包 v22 构建完成（为 deep-audit-1040 波准备） | 触发：用户「为下一波的那个审计做准备吧」。关键判断：dv19 八支已并入 main（295cf778，21:11），而背景包基线仍是 6bbbded2（1038 收口）—— 不重锚就会让下一波把刚修完的 16 条再挖一遍… |
| 10-22 08:21 | deep-audit-1040 全批闭环（用户令「合并清理，那个也一起」）：main `843ab917` + main CI #2456 success（Publish 执行）+ 发布 v1.0.0+2456 + 21 支清理归零 | ① 合并前的补核查（fastio）：fix/fastio-permission-bypass-1021 三方 sha OK（本地 == 远端 == CI 81c70b68，run #2430 success）；与 dv2… |
| 10-22 08:34 | 审计背景包 v23 构建完成（为 deep-audit-1041 / v20 波准备） | 触发：用户「又完成了一轮，为下一轮做准备」。基线：295cf778 → 843ab917（deep-audit-1040 收口，今晨 07:54；含 dv20 八支 + compact-timeout-budget + … |
| 10-22 15:06 | 审计背景包 v24 构建完成（为 deep-audit-1042 / v21 波准备） | 触发：用户「这一批已经吸收了，为下一批做准备」。基线：843ab917 → 5dd278da（deep-audit-1041 收口，14:48；含 dv21 十支并集 + fix/dv21-rssprobe-test-r… |
| 10-22 15:16 | deep-audit-1041 全批闭环（文档 + 合并 main + 清理 13 支）：main `5dd278da` + main CI #2476 success（Publish 执行）→ 发布 v1.0.0+2476 | ① 文档：档案 2131 条 / 81 天（08-03～10-22，2103→2131）；三轮 sanitize 全 NONE；SAGAS 24 主题 / orphans 193 / multi4 148；结构体检 an… |
| 10-22 19:30 | FIX-A2 收口（deep-audit-1042 / A-02 P1）：`tarCreate` 顶层根循环三缺口 — `fix/dv22-a2-fastio-tar-toplevel` @ `5a144b2c`，CI #39768460593 success，停在分支 | 身份链：基线 main 5dd278da → 5 提交（生产 d4aa69cf / 测试 c573481f / 门 9535d2fc / 报告 f8b1590d + 5a144b2c）。本地 == ls-remote =… |
| 10-22 20:45 | 审计背景包 v25 / 提示词 v22-A,B 构建完成（为 deep-audit-1043 / v22 波准备）—— 2026-10-22 晚 | 触发：用户「这一批消化完了，为下一批做准备吧……笔记子文件夹里的背景文件」。接手时发现 audit-pack-v25/ 已有半成品 stage1.py（20:32 另一会话留下，未跑、锚点失效），其余 stage2-6/… |
| 10-23 05:58 | FIX-8 收口（deep-audit-1043 / B-02+B-06+B-07 三条 P1）：`fix/dv23-b2-capability-single-source` @ `498eb889`，CI #39823833259 success（判读 15/15），停在分支 | 身份链：基线 main 79ef6d59 → 6 提交（d8dca1e0 B-02 / 84ab8bb6 B-06 / 90381f3c B-07 / 35e77140 报告 / d4a7ceb2 编译修复 / ee63… |
| 10-23 06:41 | 审计背景包 v26 / 提示词 v23-A,B 构建完成（为 deep-audit-1044 准备）—— 2026-10-23 晨 | 触发：用户「好了，吸收完了，为下一波做准备吧」（承接 v25 那轮的「为下一批做准备」）。关键前提：1043 那轮已被另一会话合并进 main —— f94413f5（bbd43596 dv23 并集 + eee9fce… |
| 10-23 06:52 | deep-audit-1043（v22 波）全批闭环：文档先行 → main `f94413f5` + main CI #39865696671 success（Publish 真执行，v1.0.0+2517）→ 清理 13/13 | ① 文档先行（用户令「先处理文档，再合并」）：先 memory_write 1043 收口（档案从日志生成，不先落盘条目会缺）→ rebuild 2153 条 / 82 天（2142→2153，08-03～10-23）/… |
| 10-23 12:45 | 审计背景包 v27 / 提示词 v24-A,B 构建完成（为 deep-audit-1045 / v24 波准备）—— 2026-10-23 午后 | 触发：用户「好了，现在已经合并进去了」（dv24 十支已并入 main）。基线核实：main 1d44ec90（= dv24 并集 f842dd13 + docs-1044；两树 src/+scripts/ 逐字节一致，… |
| 10-23 12:59 | deep-audit-1044（v23 波）全批闭环：main `1d44ec90` + main CI #2534 success（Publish 真执行，v1.0.0+2534）→ 清理 12/12 分支 + 10/10 worktree | 用户令「按流程走」= 放行合并（承接上一轮已完成的收口证据）。 |
| 10-23 17:43 | deep-audit-1045（v24 波）全批闭环：main `381d1026` + main CI #2553 success（Publish 真执行，v1.0.0+2553）→ 清理 14/14 分支 + 14/14 worktree | 用户在收口中两次干预：①「怎么分支又一次跑了，不是应该并入吗？」——点破我为 2 条测试文件编译警告（user.dir 可空性，P2）不必要地重跑了一轮并集 CI（装置比例失当；正确做法是登记不修，或合并后由 main … |
| 10-23 21:46 | 审计背景包 v28 构建完成（为下一波双路审计准备）—— 2026-10-23 夜 | 触发：用户「最新的报告消化完了，为下一批做准备吧，两份，在挂载文件夹的笔记的子文件夹」＝重建背景包 + 双路（A/B）提示词，落 /var/minis/mounts/笔记/RikkaMinis审计背景包/。上一版 v27… |
| 10-24 06:50 | 审计背景包 v29 构建完成（1047 波吸收后，为下一波双路审计准备）—— 2026-10-24 上午 | 触发：用户「好了，这一批已吸收完了，为下一批做准备吧」＝ 1047（dv27 九支）已合 main，重建背景包 + 双路提示词，落挂载笔记子文件夹。 |
| 10-24 10:36 | FIX-B2 收口（deep-audit-1048 / B-07 P1）：`fix/dv28-b2-model-picker-single-source` @ `57a3b9c2`，CI `40053325774` success 且干净，停在分支 | 缺陷：①ChatModelPickerSheet.kt 私有折叠集合 remember(config){ config.instances.filter{ it.isEnabled }… } = 第三份副本，且键派生自用… |
| 10-24 11:37 | 审计背景包 v30 构建完成（1048 波吸收后，为下一波双路审计准备）—— 2026-10-24 中午 | 触发：用户「这一批也吸收完了，为下一批做准备吧」＝ 1048（dv28 九支）已合 main，重建背景包 + 双路提示词，落挂载笔记子文件夹。 |
| 10-24 11:44 | deep-audit-1048（v26-A/B 波）全批闭环：main `b82aa8dc`（+ docs `d7e2493e`）+ main CI `40057945965` success（26 步 skipped=[] ⇒ Publish 真执行）→ 发布 v1.0.0+2597 → 清理 100% | 合并链：用户放行 → integration/dv28 @ 7275c81e --no-ff 合入 main（零冲突，合并树 vs 并集树 src+scripts+fix-reports diff 0 行，差异仅 doc… |
| 10-24 13:31 | 重复欠账扫描器（台账「单一事实源」缺失的机械对策）—— 2026-10-24 13:3x | 触发：用户问「这些是不是从开发以来积累的，我记得有一部分已经测过」。核实成立 —— backlog.md 里 R 行与 VERIFY 回执互不销账：§2476-VERIFY 明明验了 §1041-A5 override … |
| 10-24 15:24 | backlog 全量核账 + 装置瘦身审计（2026-10-24 下午）—— 两个反直觉结论，都是数据推翻直觉 | 触发：用户问「为什么 bug 好像修不完，一直扫一直有」，然后授权「按你说的做」。 |
| 10-24 16:18 | dv29 批次已闭环：main `6576c7fa` + main CI `40076239617` success（skipped=[]，Publish 真执行）→ 发布 v1.0.0+2612 → 分支/worktree 清理 100% | 合并链：用户「合并」放行 → docs 工作树 main 6c7c4636 --no-ff 合 integration/dv29 @ 3a051b46 → main 6576c7fa（合并树 vs 并集树 src+scr… |
| 10-24 18:22 | 2026-10-24 全日志：门有效性审计 + 三个真机验证窗口 + 台账批量结案 + 档案重建 | 当日主线（承接 backlog 腐账审计）： |
| 10-24 20:39 | 元审计环（门见证）第一波就位 — 2026-10-24 晚 | 用户约束修正（重要）：外部强模型（Google 闭源最新，官方 app 访问，无 API）不是便宜额度，是稀缺席位——成本≈0 但 2 次/5 小时。经济学相反：过期库存要烧光，稀缺席位空着零成本、乱用才亏。因此策略 =… |
| 10-24 20:47 | 元审计第一波改版：改为「提示词 + 逐文件材料」，并利用 agent 工具能力（2026-10-24 晚） | 用户两次纠正形式：①之前的形式是「各种材料 + 一条提示词 → 它自己吐报告」，我首版给的 7 个文件（含 388KB 合订本）形式不对；②逐文件直接发比 zip 好；③官方应用是 agent 模式、能调工具（可联网读公… |
| 10-24 20:53 | 元审计收口已交接：报告回来后不用原对话框（2026-10-24 晚） | 用户说明：报告约 1h+ 出，届时可能不在本会话 ⇒ 已备好自包含接手包，新会话无需本对话上下文。 |
| 10-24 20:59 | 元审计收口改成「拖报告即接手」——落地为新技能 meta-audit-witness（2026-10-24 晚，用户纠正） | 用户纠正：上一环的理想形态是「报告出来 → 我把两份报告发给新对话 → 它自然知道按流程走」。不需要路径、不需要交接文件、不需要额外指令。我先前那套「读 HANDOFF.md」的方案是错的形式。 |
| 10-26 10:13 | B6-t07 收口：modeluse_proto_single_source + thinking_rule_editor_preserve 两门 13 条见证全 CAUGHT（分支 `fix/b6-modeluse-thinkrule` @ 门提交 `ae0cdce9c` + 报告 `12e879c00`，CI #2703 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 ae0cdce9c（2 文件 +1237/−719，生产 .kt 0 行、scan.sh/test_sc… |
| 10-26 12:58 | TierFlow 第三批复查（2026-10-26 12:58）—— 18.3 小时烧掉 ¥64.80，1 家见底、1 家完全未动 | PHONE 剩 13,636,623（¥27.27）/ 235 次 ｜ PHONE 剩 11,190,975（¥22.38）/ 684 次 ｜ PHONE 剩 −901（已见底）/ 1,912 次 ｜ PHONE 剩 2… |
| 10-26 13:03 | B7-t10 收口：schedule_date_source + overlay_show_hide_latch 两门 12 条见证全翻红（分支 `fix/b7-scheduledate-overlay` @ `626eee8bf`，CI #2718 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 626eee8bf（2 文件 +1052/−631，生产 .kt 0 行、scan.sh/test_scan.py/三个 _kotlin_ 零触… |
| 10-26 13:10 | B7-t12 收口：webview_holder_url_source + chat_menu_prefs_listener 两门 12 条见证全翻红（分支 `fix/b7-webviewholder-chatmenu` @ `0dfb86e2b`，门提交 `378659481`，CI #2720 绿，停分支） | 基线 base/batch7-shared @ e145ec961。门提交 378659481（2 文件 +828/−437，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-26 20:12 | R3-t22 收口：file_provider_paths + schedule_date_source 两门加固（分支 `fix/r3-filepaths-schedule` @ `990343cdd`，CI #2746 success，停分支） | 基线 main f0f14d46d，worktree round3b/wt/t22。改动面 = 2 个门脚本（+753/−4）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零… |
| 10-26 20:30 | TierFlow 第三批二次复查（2026-10-26 20:29）—— 7.5 小时又烧 ¥52.61，只剩 2 家有量 | \| 手机号 \| 套餐剩余 \| 礼金 \| 7.5h 消耗 \| |
| 10-27 05:57 | 终局：U 批五支并集合 main + 发版 v1.0.0+2763 + 清理（2026-10-27 05:5x） | 用户补入第五支 test/agentloop-behavior-baseline @ 74b1ba657（CI #2758 绿，+753 行 JVM 行为测试——AgentLoopBaselineScenariosTes… |

## 13. 多会话并行协作 / 派发 / 交接

**跨度** 2026-08-04 ～ 2026-10-27 · **730 条** · **状态** 成熟（skill: task-dispatch / git-parallel-collaboration / rikkaminis-dev-methodology）

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
| 09-22 14:42 | 09-22：应用开发阶段收口 —— 总账梳理 + 档案更新（分支 CI 进行中） | 用户指令：「应用开发告一段落，帮我总结梳理这一段开发的具体情况，然后更新文档」。 |
| 09-23 01:23 | 闭环审计六族收口（39 环） | 总账：L4=3(7.7%) / L3=15 / L2=17(44%，全部断在 EFFECT「动作后无回读」) / L1=2 / L0=0。 |
| 09-23 02:04 | 三道核查全过：①检查 diff 逐文件（20 文件 +786/−36 与三任务范围吻合、无夹带/冲突标记）②验证 sca | 三道核查全过：①检查 diff 逐文件（20 文件 +786/−36 与三任务范围吻合、无夹带/冲突标记）②验证 scan.sh 18/18（合并树含 T3 新增 gate 18 Liveness static guar… |
| 09-23 02:17 | dispatch-0923 两修复任务收口（badge 补渲染 + update 恢复半环） | T1 badge 补渲染 00490542：8 文件 +33/−1（DrawerSessionRow:408 圆点位 + 7 语言 sessionlist_badge_paused），scan 17/17，CI 3576… |
| 09-24 15:16 | 合并收口（用户放行）：--no-ff merge docs/dev-history-0924 → main = 7b76 | 合并收口（用户放行）：--no-ff merge docs/dev-history-0924 → main = 7b76c034（3 文件 +1320/−46）；gh_sync.sh push --branch main… |
| 09-24 20:41 | release-notes 源头同步合并收口（用户放行）：diff 走查 1 文件干净（仅 release body 块 | release-notes 源头同步合并收口（用户放行）：diff 走查 1 文件干净（仅 release body 块，4 条新说明，debug 签名警告保留）、YAML safe_load 验过；--no-ff me… |
| 09-24 21:13 | cjk-token-estimate 分支收口（另一会话完成）：用户确认另一对话已检查并合并——远端 main = 20 | cjk-token-estimate 分支收口（另一会话完成）：用户确认另一对话已检查并合并——远端 main = 2022b0c5（13e5612c 之上的 merge commit），fix/cjk-token-es… |
| 09-25 10:01 | 合并收口（用户放行）：--no-ff merge docs/dev-history-0925 → main = 15b0 | 合并收口（用户放行）：--no-ff merge docs/dev-history-0925 → main = 15b06f3（3 文件 +171/−34）；gh_sync.sh push --branch main 成… |
| 09-25 16:23 | memory-editor 分支合并收口（09-25，用户放行）：fix/memory-editor-jump-to-t | memory-editor 分支合并收口（09-25，用户放行）：fix/memory-editor-jump-to-top 双提交（13d8e9a8 state API + 17770a20 窗口化编辑）--no-ff… |
| 09-25 17:39 | 合并收口（09-25，用户放行，两分支）：--no-ff 按序 merge fix/skills-prompt-budg | 合并收口（09-25，用户放行，两分支）：--no-ff 按序 merge fix/skills-prompt-budget（254ecc7c）→ 1a5d4096、fix/stream-recovery-grace-r… |
| 09-25 18:41 | 合并收口（09-25，用户放行）：--no-ff merge fix/offload-stale-token-compa | 合并收口（09-25，用户放行）：--no-ff merge fix/offload-stale-token-compact @ f87d9102 → main = bea9ae70（4 文件 +48/−8，merge … |
| 09-25 19:35 | drawer-row-slim 合并收口（09-25，用户放行）：--no-ff merge feat/drawer-r | drawer-row-slim 合并收口（09-25，用户放行）：--no-ff merge feat/drawer-row-slim @ 1a2285b4 → main = 8d56a927（1 文件 +107/−58… |
| 09-25 21:19 | typing-band-live 合并收口（09-25，用户放行）：8 项固定清单全跑后 --no-ff merge f | typing-band-live 合并收口（09-25，用户放行）：8 项固定清单全跑后 --no-ff merge fix/typing-band-live @ 092ebbdd → main = 45f0f39b（3… |
| 09-25 23:34 | skill-metadata-resync 核查 + 合并收口（09-25，用户放行）：远端分支 fix/skill-m | skill-metadata-resync 核查 + 合并收口（09-25，用户放行）：远端分支 fix/skill-metadata-resync-from-disk @ 56b65fe1（1 commit，3 文件 … |
| 09-26 00:08 | chore/remove-skill-detail-rescan 合并收口（09-26，用户放行）：全套 8 项核查在本 | chore/remove-skill-detail-rescan 合并收口（09-26，用户放行）：全套 8 项核查在本会话独立复跑（非沿用昨夜结论）——①diff 走查干净（1 个 .kt + 7 语言文件，+2/−4… |
| 09-26 00:13 | ↳ 教训（我犯的）：绕过 verify_branch.sh 编排器手跑子集（09-26 复发确认）：合并 chore/rem | 教训（我犯的）：绕过 verify_branch.sh 编排器手跑子集（09-26 复发确认）：合并 chore/remove-skill-detail-rescan 时只手跑了 diff/残留/scan.sh/冲突/括… |
| 09-26 09:25 | 吸收 Filterrr 改动 → 拆成两个派发任务（09-26，用户决定 A+B，B 整包不拆）：派发文件在 /var/ | 吸收 Filterrr 改动 → 拆成两个派发任务（09-26，用户决定 A+B，B 整包不拆）：派发文件在 /var/minis/shared/absorb-filterrr-0926/（00-DISPATCH-BOA… |
| 09-26 10:33 | B 分支 CI 三轮红→绿收口（09-26 02:30Z，停止节点达成，未合并） | B 分支 CI 三轮红→绿收口（09-26 02:30Z，停止节点达成，未合并） |
| 09-26 12:02 | senseaudio 档位缺陷修复收口（09-26，停止节点达成，未合并）：分支 fix/senseaudio-effo | senseaudio 档位缺陷修复收口（09-26，停止节点达成，未合并）：分支 fix/senseaudio-effort-truth @ 7675f2cd14d399b843c1143ecadb62b0f7bfdf2… |
| 09-26 15:53 | 日志报告办法 ①②③ 修复收口（09-26，用户拍板「就在这里做了」）→ 报告 §7 /var/minis/shared/log-analysis-0926/REPORT.md | ① beacon 保留 uptime_was（app 侧，停止节点，未合并）：分支 fix/launch-beacon-uptime @ 593dc5b0（2 文件 +62/−5，基 main c06cf402）。改动 … |
| 09-27 14:42 | 合并收口：fix/ime-overlay-focus-coverage → main = `aeffcef9`（09-27，用户「合并吧」+「触发了就不用等了」） | 流程：私有克隆 rkm-ime-0927（git clone --no-hardlinks，PRoot 不支持硬链接 → --local 报 failed to create link ... Operation not… |
| 09-27 17:57 | 09-27 今日修改审计收口 | 对 main 8dcb82e8→aeffcef9 做全套核查：24 文件 +838/−53，工作树干净、冲突 0、scan 20/20、i18n 1488 keys×7 CLEAN、four-way CLEAN，rele… |
| 09-27 18:24 | 09-27 当日修改最终收口审计 | 远端 main=7a5f54b9，由代码合并 0d7be26e + 档案合并组成；代码线对基线的机械核查 20/20、four-way CLEAN、i18n 1488×7 CLEAN、CI run 36310708393… |
| 09-28 19:56 | Procedure Layer v0 工程量评估 + 并行派发包（09-28） | 工程量判定：大。测算 ≈ 3100~3700 行新代码（Phase A 纯 Python ≈2100~2500 含测试；Phase B Kotlin ≈1000~1200），约 20 文件，≈ 仓库史上最大单次 feat… |
| 09-28 20:13 | S3 完成 + ★ 并行派发包的「共享夹具目录」碰撞（2026-09-28） | S3 交付：scripts/procedure_schema.py（纯函数 validate() -> Violations(list[str])，.warnings 通道）+ scripts/validate_proc… |
| 09-28 20:17 | ↳ S2 · Procedure Layer 检索器完成（09-28，任务书 task-A2-retrieve.md） | 产物（只写这三个文件）：scripts/retrieve.py（15022B）、scripts/tests/test_retrieve.py（33 用例）、eval/.gitkeep。 |
| 09-28 20:31 | ↳ FIX1 · 检索器状态守卫完成（2026-09-28，按 task-FIX1-status-guard.md 执行） | 改动（只碰两个文件，diff +77/−0）：scripts/retrieve.py +5（load_catalog() 在 id/steps 结构检查之后、procs.append 之前加文件级状态守卫 if obj.… |
| 09-28 23:18 | Procedure Layer v0 交接 · 范围从「史上最大 feature 1.4 倍」收缩到「一次人工复审」（2026-09-28，用户拍板「准备交接」） | 停止节点，原路线不再推进。 交接件：/var/minis/shared/procedure-layer-0928/HANDOFF-2026-09-28.md（README §十一 同内容）。 |
| 09-28 23:21 | Procedure Layer 交接核实（09-28 23:2x，接手会话 c67688e2） | 核实（独立跑，非复述交接件）：167 tests OK ✅；procedures/approved/ 目录根本不存在（只有 candidates/）✅；GLOBAL.md 290 行 / 29,799 字节 / 20 章… |
| 09-30 13:59 | 上游 A 档移植已派发（09-30，3 会话并行） | 用户拍板：无障碍已开启（真机复核 running:true）；工程量大 → 分派出去。 |
| 09-30 17:47 | 分支合并收口：fix/a11y-evergranted-gate-fgs-guidance → main 26b7ce1（09-30，全套 8 项核查通过） | 用户指令链：「检查一下云端上的那个分支，如果没有问题的话，就合并了吧」+「触发了就不用等了」= 核查 + 条件合并授权。 |
| 10-05 16:19 | 压缩体系剩余队列 → 三份任务书（10-05，用户令「处理成任务，我手动派发」） | E pin v0（约束钉住）：/var/minis/shared/work/task-brief-1005-E-compact-pin-v0.md。设计已拍板：内嵌方案（<pinned-user-messages> 内嵌… |
| 10-05 16:48 | ↳ 任务 G 执行完成：SUPERSEDED 条款回归 PASS（零代码，D-repeat @ 生产 prompt 复跑） | 任务 G 执行完成：SUPERSEDED 条款回归 PASS（零代码，D-repeat @ 生产 prompt 复跑） |
| 10-05 17:41 | 压缩三任务收口（10-05 主会话独立核验，用户令「结果都出来了」） | 核验（三源全过）：①CI run 37284048540（E）/37289317688（F）均 success，head_sha 与分支 tip 逐字符一致（bridge+API+ls-remote）；②diff vs … |
| 10-05 20:05 | 任务书 H 派发就绪 + 拆分策略登记（10-05） | 任务书 H 已写好待手动派发：/var/minis/shared/work/task-brief-1005-H-compact-orchestration-split.md——ChatSessionLifecycle 拆… |
| 10-08 20:14 | 两条并行线审查完成（2026-10-08）：#1948 + PR #5，均放行 | ① A 线 fix/edit-resend-attachment-loss-1008 @ ad45ac879f96（base main 9ab9f949）→ 放行，0 阻塞 |
| 10-08 20:39 | ↳ 两条线合并完成（2026-10-08 晚，用户令「合并吧」+「合并 PR 走经典流程」） | A 线 #1948 → main 6c969fae：本地 --no-ff（parents 9ab9f949 + ad45ac87）；merge 树 == 分支树逐字符；增量 +116/−1 精确吻合；push 远端核验一… |
| 10-08 23:57 | send 派发竞态诊断（D2 空历史，第 3 次撞上） | 根因（日志实锤）：minis-sessions-cli send 创建会话时，用户指令 append 到 DB 与 run 的历史加载并发——输者 runAgentLoop 以 historySize=0 启动（D2 实… |
| 10-09 00:03 | D2 域扫描完成（sweep-1008，子代理） | 产出：/var/minis/shared/sweep-1008/reports/D2.md。域 = ui/chat 其余 ~87 个（除 D1 状态核心），重点全读 ChatScreen/ChatViewModel/St… |
| 10-09 00:18 | 全库四模式扫描收口（2026-10-08 晚） | 产物：/var/minis/shared/sweep-1008/{TRIAGE.md, reports/D1-D6.md}。6 域并行会话（llm-bug-audit 规则库+四结构模式）扫全库 197K 行，46 项发… |
| 10-09 00:42 | fix-sweep-a-1008 修复收口（sweep-1008 附件契约族 P1 五项全修） | 任务：/var/minis/shared/sweep-1008/fix-tasks/A.md。分支 fix/sweep-p1-attach-1008 @ 77cf817e95ca29370aa5177174f9835c8… |
| 10-09 13:32 | 备份 OOM 修复合并收口（2026-10-09） | 合并完成（用户放行「修好了，合并吧」）：三项核查齐（diff 3 文件逐行读无夹带、0 冲突标记；分支 CI 37884504189 head_sha 逐字符 = 71f605e；独立证据=影子 harness 三路对照… |
| 10-09 14:15 | backlog 第1+2档批量修复合并收口（2026-10-09） | 分支：fix/backlog-batch-1009 @ 333a1989（基线 main b4647299），CI run 37890926335 success（05:55 dispatch → 06:09 绿，14.… |
| 10-09 19:13 | 症状：用户说「子代理」不指向会话子代理（spawn_agent）；且派发时「指令没发过去」。 | 症状：用户说「子代理」不指向会话子代理（spawn_agent）；且派发时「指令没发过去」。 |
| 10-09 20:16 | 分支审查+合并收口：fix/subagent-routing-delivery-1009（2026-10-09 晚，用户令「有问题就修，没有问题的话合并」） | 对象：fix/subagent-routing-delivery-1009 @ 5862b647（1 commit，7 文件 +209/−3，基线 main cbb9d2b4）。CI run 37925446699 su… |
| 10-09 20:21 | 子代理修复（7275bbd4）真机验证完成（2026-10-09 晚，构建 v1.0.0+1962 @20:16:52 装机） | A（frontmatter 全链路）✅：file_write 带 subagent:true 的 SKILL.md → auto-discover（20:18:21.835，与事故同时序）→ spawn_agent 直接… |
| 10-12 17:18 | proactive-1012 三项并行派发（2026-10-12 傍晚） | 用户令「用会话子代理并行、把任务安排出去」→ 已派发 3 个 headless 会话（minis-sessions-cli send --async），任务书在 /var/minis/shared/proactive-1… |
| 10-12 18:55 | 流式 think-tag 扫描器两个活缺陷（2026-10-12 晚，主会话侦察发现 → T4 修复已派发） | 在给「下一步该弄什么」做侦察时，在 ThinkTagScanning.kt（main @ 13949bb0）实测复现两个从未被测试覆盖的活缺陷： |
| 10-12 20:25 | T5 派发：MarkdownParser 差分探针 + 重复派发事故（2026-10-12 晚） | 派发：MarkdownParser.kt（639 行纯 Kotlin object 零 import，String→List<Block>，宿主可测；已有覆盖仅 numbered list + KaTeX 上限）→ T5… |
| 10-12 20:42 | T5 收口：MarkdownParser 探针完成 + 2 个 P1 修复已合并（2026-10-12 晚） | 产出：64 条探针语料 + 58 例测试（MarkdownParserBlockStructureTest，468 行）+ 2 个 P1 缺陷修复（①heading 尾部 # 双向：## C# 内容被吃、## title… |
| 10-13 08:59 | sweep-1013 三域并行派发（2026-10-13 上午） | 用户令「动手，用会话子代理，三个一起」（并纠正：要的是会话子代理 = minis-sessions-cli send，不是进程内 spawn_agent——08:37 上一轮误用 spawn_agent ×2 全败 v0… |
| 10-13 12:58 | 用户偏好：「子代理」默认指会话子代理（2026-10-13，用户明确） | 用户明确：说「子代理 / sub-agent」时默认指会话子代理 = minis-sessions-cli send（跨会话派发 headless 会话），不是进程内 spawn_agent。 |
| 10-13 13:12 | sweep-1013 收口：第二道门核实 + 三修复会话派发（2026-10-13） | 输入：三域审计报告齐（D1 数据层 10 条 / D2 安全面 11 条 / D3 UI 10 条，共 31 条），报告基线 main @ ad4a4beb。 |
| 10-13 14:02 | sweep-1013 修复收口：三支合并 main 9f4f5766（2026-10-13） | 用户令：「好了，他们都完成了，你这里继续吧。」→ 读作收口授权（核实 + 合并）。 |
| 10-13 17:53 | PR #13（fix/debt-cleanup-1013）修复+合并收口（2026-10-13） | 用户指令链：「检查一下云端正在跑的那个分支」→ 审查发现 P1 → 「修吧，修好跑完之后就合并」→ 修复+CI 绿+合并，全部完成。 |
| 10-14 10:01 | v1.14 Tier1+聊天健壮性 派发完成（4 会话，2026-10-14） | 用户令:「Tier 1 用子代理安排出去」+「聊天健壮性组合如果工作量不大也一起打包」→ 评估后打包成 4 个会话、4 个独立分支（文件集互不相交，可并行）： |
| 10-14 10:20 | S2 会话收口：port/v114-thumb-ceiling @ 4ffb437，CI run 38499083898 | S2 会话收口：port/v114-thumb-ceiling @ 4ffb437，CI run 38499083898 success（head_sha 逐字符一致），停在分支未合并。 |
| 10-14 14:48 | SWEEP-1014 第二/三轮核实与派发（2026-10-14，续第一轮 9 会话之后） | 报告：shared/sweep-1014/TRIAGE-R23.md（第一轮 TRIAGE.md）；任务书 tasks/FIX-R{1..12}.md；核实基线 main @ d5d2be59f（同一浅克隆）。 |
| 10-14 16:12 | SWEEP-1014 第四/五轮核实与派发（2026-10-14，续前 21 会话之后） | 报告：shared/sweep-1014/TRIAGE-R45.md；任务书 tasks/FIX-R{13..23}.md；基线 main @ d5d2be59f。 |
| 10-14 17:06 | ↳ FIX-R16 完成：sessions-cli booleanFlags 漏声明 B26a + 共享 SDF B26b（ | FIX-R16 完成：sessions-cli booleanFlags 漏声明 B26a + 共享 SDF B26b（fix/sweep1014-sessions-cli @ 595b4f3e，CI #2030 绿） |
| 10-14 18:05 | SWEEP-1014 收口核查完成（2026-10-14，32 分支） | 报告：shared/sweep-1014/VERIFY-REPORT.md。核查时刻 main 仍 d5d2be59f（未移动）。 |
| 10-14 21:00 | RV-1014 回归审查（今日大改动的最后一道闸门）+ 7 支修复会话派发 | 用户口径纠正（重要）：审查目的是给今日大改动（106 文件 +6317/−940）做最后把关，不是"看 CI 绿不绿"。审出来就说明有效。 |
| 10-14 22:11 | RV-1014 修复批次收口：8 支全绿合入 main a7123cf82（含备份/ContentCapture 决策分 | RV-1014 修复批次收口：8 支全绿合入 main a7123cf82（含备份/ContentCapture 决策分支） |
| 10-14 23:19 | §97 收口：fix/skill-asset-hash-gate 合并 main 15a1f1336（用户令「没有问题就 | §97 收口：fix/skill-asset-hash-gate 合并 main 15a1f1336（用户令「没有问题就合并」+「合并触发之后就不用等了」） |
| 10-15 00:42 | §97 根治二段合并收口：fix/bundled-row-adoption-1015 → main 4950a334（用户令「检查云端正在跑的分支，没有问题的话就合并」） | 核查对象：run #2097 = fix/bundled-row-adoption-1015 @ 5da7153f7（workflow_dispatch），核查时 in_progress、机械项阶段已 completed… |
| 10-15 01:05 | §98 文档同步收口 + 沙箱膨胀数据清理（2026-10-15） | 文档同步：dev-history 三件套重建（1734 条 → 24 saga，脱敏 3 遍 NONE）→ 分支 docs/dev-history-sync-1015 → --no-ff 合入 main e5e733b（… |
| 10-15 06:13 | §99 全仓审计第二道门核实 + 4 会话派发（2026-10-15） | 输入：用户丢来全仓审计报告（shared-e8a7c3f5.md，8 BUG + 5 ARCH + 6 PERF）令「检查核实」→ 核实后「工程量大的话用会话子代理安排出去」。 |
| 10-15 10:42 | audit-1015 四分支合并收口（2026-10-15） | 用户令：「检查云端上的4个分支，有问题就修，没问题就合并」+ 中途两次纠偏：「你是不是偏移最初目标了」「你这里承担的是合并前的检查环节」。 |
| 10-15 12:04 | 用户偏好：「子代理」默认指会话子代理（已固化进 GLOBAL.md） | 用户明确：说「子代理 / sub-agent / 派发任务」默认指会话子代理 = minis-sessions-cli send（跨会话 headless 派发），不是进程内 spawn_agent。派发用 --prom… |
| 10-15 15:03 | verify-1015 修复批次收口：P0/P1 全部 8 条，分支 CI #2112 绿（2026-10-15） | 任务：核实报告（shared/work/verify-audit-1015/VERIFY-REPORT.md）P0/P1 8 条 → 分支 fix/audit-verify-1015（基线 main 6fe4771）3 … |
| 10-15 15:59 | audit2 修复批次派发（2026-10-15，用户令「按你的建议来吧」） | 六个会话子代理（minis-sessions-cli send --async，全部新会话，模型=羊毛组）： |
| 10-15 16:44 | FIX-A1 收口：audit2 四项 P1 修复，分支 CI #2118 绿（2026-10-15） | 用户令：读 shared/work/verify-audit2-1015/fix-tasks/FIX-A1.md 并立刻执行 → 「构建完了」。 |
| 10-15 17:33 | FIX-A3 交付收口：BUG-08 cancelBridge 共享化（2026-10-15） | 任务：shared/work/verify-audit2-1015/fix-tasks/FIX-A3.md —— Anthropic/Gemini 缺 cancelBridge（首块之后用户点停止，协程停在 call.e… |
| 10-15 18:07 | FIX-A1-2 收口：注释级修正（"provably true" 不成立）—— 分支 CI #2120 绿（2026-10-15） | 用户令：读 shared/work/verify-audit2-1015/fix-tasks/FIX-A1-2.md 并立刻执行。 |
| 10-15 18:48 | FIX-A6-2 收口：精确缩放解码窗口改回 `< 2 × maxEdge`，分支 CI #2126 绿（2026-10-15） | 任务：读 shared/work/verify-audit2-1015/fix-tasks/FIX-A6-2.md 执行。修 audit2 第二道门发现的行为回归（PERF-03 的修法把 (maxEdge, 2×max… |
| 10-15 18:55 | audit2 修复批次收口：六支合并 main `ba9ed437`（2026-10-15，用户令「检查，没问题就合并，有问题就修」+「完成之后再更新文档，包括云端的」） | 输入：全仓审计报告第二道门核实后的 6 份任务书（shared/work/verify-audit2-1015/fix-tasks/FIX-A{1..6}.md，基线 main 891d755）。 |
| 10-15 20:26 | audit3 第二道门核实 + 七支派发（2026-10-15，基线 main `ca8b95d1`） | 输入：用户丢来 全量源码级深度架构、安全与性能审计报告（13 项：3×P0 / 4×P1 / 1×P2 / 3×架构 / 2×性能-内存），说「按流程来吧」。产物目录 /var/minis/shared/work/aud… |
| 10-15 21:13 | FIX-A 收口：audit3 F-01 FastioTar 根条目 tar-slip（P0）— 分支 CI #2129 绿，停在分支未合并（2026-10-15） | 任务：shared/work/audit3-1015/fix-tasks/FIX-A.md（基线 main ca8b95d1）。分支 fix/audit3-fastiotar-1015 @ 894ff37d（2 文件 +… |
| 10-15 21:14 | FIX-F 收口：F-08 会话路径解析不再因拒绝而全局回退（fix/audit3-sessionpath-1015 @ 6a20a481，CI #2128 绿，停在分支未合并） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-F.md 执行。基线 main ca8b95d1。commits：c0f2afa9（本体）+ 6a20a481（同族第 8 站点 + … |
| 10-15 21:18 | FIX-E 收口：audit3 F-07 mount guard 四处绕过全修，分支 CI 38739081695 绿（2026-10-15） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-E.md 执行（基线 main ca8b95d1）。报告 shared/work/audit3-1015/fix-reports/FI… |
| 10-15 21:19 | FIX-B 收口：audit3 F-02 预算停出环（P0）→ 分支 CI #2131 绿，停在分支（2026-10-15） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-B.md 执行。基线 main ca8b95d1，分支 fix/audit3-agentloop-1015 @ 09bb9df6，CI… |
| 10-15 21:52 | FIX-D 收口：F-05 取消桥前移 + F-06 内存回收不重试（fix/audit3-cancel-retry-1015 @ a9631bf9，CI #2133 绿，停在分支） | 任务书 shared/work/audit3-1015/fix-tasks/FIX-D.md；报告 fix-reports/FIX-D.md；基线 main ca8b95d1；两提交 b76a0c5e（本体）+ a963… |
| 10-15 21:52 | FIX-G 收口：NodeRegistry 摊销驱逐 + 图片解码有界化（fix/audit3-perf-small-1015 @ 993fed7e，CI #2134 绿，停在分支） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-G.md 立刻执行（audit3 F-12/F-13）。基线 main ca8b95d17，单提交 993fed7e，6 文件 +80… |
| 10-15 21:55 | FIX-C 交付收口：model-use 跨进程边界四块（F-03 P0 + F-04 + F-08 + F-10）—— 分支 CI #2135 绿，停在分支（2026-10-15） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-C.md 并立刻执行（基线 main ca8b95d1）。报告 shared/work/audit3-1015/fix-reports… |
| 10-15 22:32 | audit3 收口完成：七支合并 main `984cbdd801dc` + 并集树 CI #2142 绿 + 文档归档进 main（2026-10-15，用户令「他们的任务都完成了」→「跑完了」） | 结果：七支（FIX-A..G）全部合并 → main 984cbdd801dc；并集树 CI run #2142（id 38749571877）success @ 984cbdd801dc，head_sha 逐字符一致、… |
| 10-15 23:33 | deep-audit-1015 第二道门核实 + 5 支派发（用户令「走流程吧」，附新审计报告 14 项） | 输入：用户附件 rikkaminis_deep_audit_report.md（1388 行，BUG-01..09 + ARCH-01..03 + PERF-01/02）。基线 main e10fb2aa53fb。工作树… |
| 10-16 00:01 | FIX-A 收口：deep-audit ARCH-01（T3 副作用安全重试接线，P0）+ BUG-09（二次截断）（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-A.md 立刻执行（基线 main e10fb2aa）。报告 fix-reports/FIX-A.md；装置 shared/w… |
| 10-16 00:09 | deep-audit FIX-E 收口：model-use 主进程 provider 与告警合并（`fix/da1015-modeluse-proc` @ `4a244d93`，CI #2144 绿，停在分支） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-E.md 立刻执行（deep-audit BUG-03，P1）。基线 main e10fb2aa；报告 shared/work… |
| 10-16 00:31 | FIX-C（deep-audit-1015 BUG-02）收口：失败运行被当成「正常完成」→ 分支 CI #2146 绿，停在分支（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-C.md 执行。基线 main e10fb2aa；分支 fix/da1015-sched-errorprop @ 5483ec… |
| 10-16 00:33 | FIX-D 交付收口：KaTeX 桥 arity（BUG-05 P1）+ 缓存回收（ARCH-03 子项）—— 分支 CI #2147 绿，停在分支（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-D.md 并立刻执行（基线 main e10fb2aa）。报告 shared/work/deep-audit-1015/fix… |
| 10-16 00:47 | FIX-B 收口：deep-audit BUG-01a/01b + BUG-04（fix/da1015-agentloop-report @ b6dcd671d9，CI #2145 绿，停在分支） | 任务书 shared/work/deep-audit-1015/fix-tasks/FIX-B.md；报告 fix-reports/FIX-B.md；基线 main e10fb2aa53fb；3 commits（d209… |
| 10-16 00:58 | deep-audit-1015 收口核查完成（用户令「他们都完成了」）—— 5 支全绿，停在分支未合并 | 基线 main e10fb2aa53fb；试合树 scratch-merge @ 9596d7a9（5 支顺序 --no-ff，0 冲突）。报告 shared/work/deep-audit-1015/CLOSE.md。 |
| 10-16 02:00 | deep-audit-1015 收口完成：5 支合并 + 文档云端同步 + CI #2150 跨支交互失败 fix-forward（2026-10-15，用户令「既然检查没有问题的话，那就合并吧。触发之后顺便更新文档，包括云端」） | 五支合并：A retry-wire / B agentloop-report / C sched-errorprop / D katex-bridge / E modeluse-proc 全部 --no-ff 进 mai… |
| 10-16 09:43 | FIX-G 收口：deep-audit-1016 8c/8d（通知行去 AI 头像 + compact 分割线谓词读全部块）—— 分支 CI #2155 绿，停在分支（2026-10-16） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-G.md 立刻执行。基线 main 04e74c53325a；分支 fix/da1016-chat-sysinfo @ ee3… |
| 10-16 09:51 | FIX-F 收口：deep-audit-1016 B-7b/B-7c（定时任务/无头会话状态闭环）—— 分支 CI #2157 绿，停在分支（2026-10-16） | 任务书 shared/work/deep-audit-1016/fix-tasks/FIX-F.md（纪律文档实际在 shared/work/deep-audit-1016/COMMON.md 与 TRIAGE.md，不… |
| 10-16 09:55 | FIX-B 收口：deep-audit-1016 9a/9c/9d（+9b 顺路）—— 分支 CI #2156 绿，停在分支（2026-10-16） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-B.md 立刻执行（基线 main 04e74c53325a）。报告 shared/work/deep-audit-1016/… |
| 10-16 09:59 | FIX-E 收口：fallback 重算预算 + blocked-empty 不记 Success + shell 预算门终态（fix/da1016-agentloop-budget @ d996e19e，CI #2160 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-E.md 执行（6a/6b P1 + 5a + 5b/6c 判定）。基线 main 04e74c53；单 commit d99… |
| 10-16 10:07 | FIX-D 收口：deep-audit-1016 执行闸门三件——4a heavy permit 泄漏回滚 + 4b runInterruptible 可取消等待 + 4c 主线程 GC 分发（fix/da1016-exec-gate @ edb45cbd，CI #2158 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-D.md 立刻执行（基线 main 04e74c53）。报告 fix-reports/FIX-D.md；装置 fix-d/pr… |
| 10-16 10:21 | FIX-A 收口：deep-audit-1016 ISSUE-01（Gemini base 缺 `/v1beta`，P0）—— 分支 CI #2162 绿，停在分支（2026-10-16） | 任务书 shared/work/deep-audit-1016/fix-tasks/FIX-A.md；报告 fix-reports/FIX-A.md；装置 fix-a/probe/（jvm/run.sh 一键复跑 + g… |
| 10-16 10:31 | deep-audit-1016 FIX-C 交付收口：沙箱路径归一化 / 只读写守卫 / boot 门（`fix/da1016-sandbox-path` @ `cce8919`，CI #2163 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-C.md 立刻执行（基线 main 04e74c5）。报告 shared/work/deep-audit-1016/fix-r… |
| 10-16 10:50 | deep-audit-1016 收口完成：8 支合并 main `812d0587`（2026-10-16，用户令「合并，然后更新文档」） | 八支（7 审计支 + 用户点名的第 8 支闪退修复）全部 --no-ff 合入并 push 04e74c53..812d0587，远端 main 树 62390c82… 与本地验证过的试合树逐字节一致；8 个远端分支 D… |
| 10-16 11:03 | deep-audit-1016 最终收口（2026-10-16）：main `812d0587` + 并集树 CI #2164 green + Release 已发布 + 文档上云 | 并集树 CI #2164（id 38842323044）success @ 812d0587，24 步全 success。云端实证（日志抓取，绕 Authorization 重定向）：①第 3 步扫描门 Summary:… |
| 10-16 12:12 | deep-audit-1017 第二道门 + 7 支派发（2026-10-16，基线 main `e748b14`） | 输入：附件 shared-4dc0dfc3.md（1993 行，ISSUE-01..10）。工作目录 shared/work/deep-audit-1017/（repo 浅克隆 + verify1..6.sh/out +… |
| 10-16 13:05 | FIX-F 收口：deep-audit-1017 ISSUE-09 marker 分块边界误报完成（fix/da1017-marker-boundary @ 57dcd79a，CI #2165 绿，停在分支） | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-F.md 立刻执行（基线 main e748b14b）。报告 fix-reports/FIX-F.md；装置 fix-f/pr… |
| 10-16 13:13 | FIX-E 收口：deep-audit-1017 ISSUE-08 ShizukuOffloadHandler 三个 argv 缺陷（fix/da1017-shizuku-args @ 2a5cddd1，CI #2166 绿，停在分支） | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-E.md 立刻执行（基线 main e748b14）。报告 fix-reports/FIX-E.md；装置 fix-e/pro… |
| 10-16 13:15 | FIX-C 收口：deep-audit-1017 ISSUE-04（子代理回合交还契约）—— fix/da1017-subagent-residue @ bf124e8d，CI #2167 绿，停在分支 | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-C.md 立刻执行（基线 main e748b14，浅克隆）。报告 fix-reports/FIX-C.md（189 行）；装… |
| 10-16 13:21 | FIX-D 收口：deep-audit-1017 ISSUE-06（calendar freebusy 漏周期日程 + doUpdate 丢 --all-day）—— 分支 CI #2169 绿，停在分支 | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-D.md 立刻执行（基线 main e748b14）。报告 fix-reports/FIX-D.md；装置 fix-d/pro… |
| 10-16 13:24 | FIX-A 收口：deep-audit-1017 ISSUE-01（OpenAI 系 tool-result 图 100% 丢失，P0）—— 分支 CI #2170 绿，停在分支（2026-10-16） | 任务 shared/work/deep-audit-1017/fix-tasks/FIX-A.md；报告 fix-reports/FIX-A.md；装置 fix-a/probe/（pre-P1..P4 取证、pre-fi… |
| 10-16 13:31 | FIX-G 收口：deep-audit-1017 ISSUE-10（a11y 截图/点击 + speech，P2 4 子项）—— fix/da1017-a11y-speech @ a304097，CI #2171 绿，停在分支 | 任务书 shared/work/deep-audit-1017/fix-tasks/FIX-G.md（基线 main e748b14）；报告 fix-reports/FIX-G.md；装置 fix-g/probe/（pr… |
| 10-16 13:54 | deep-audit-1017 FIX-B 收口：per-turn 可见内容谓词 + turn-tail 终态收尾（`fix/da1017-loop-finalize` @ `9e6ba91`，CI #2173 绿，停在分支） | 任务：shared/work/deep-audit-1017/fix-tasks/FIX-B.md（ISSUE-03 累计状态污染 + ISSUE-02 终态过早）。报告 fix-reports/FIX-B.md（含 §… |
| 10-16 14:27 | deep-audit-1017 收口完成：七支全绿 + 并集树 CI #2174 绿，停在并集分支（2026-10-16，用户令「按流程」→「他们都完成了」→「跑完了」） | 输入：附件 shared-4dc0dfc3.md（1993 行，ISSUE-01..10）。工作目录 shared/work/deep-audit-1017/。 |
| 10-16 14:31 | ↳ deep-audit-1017 合并 main + 文档更新（2026-10-16，用户令「合并，并且更新文档」） | 合并（用户放行后三项核查全过）： |
| 10-16 16:38 | deep-audit-1016c 启动：第二道门核实 + 8 支派发（用户令「按流程吧」，附新审计报告 12 项） | 输入：附件 shared-8c9ee41c.md（170KB / 2645 行，ISSUE-01..12）。基线 main e3ef74545bcc（= 1017 收口后最新树，报告声明 35 门/176 夹具与之吻合）… |
| 10-16 17:44 | deep-audit-1016c FIX-B 收口：offload 路径/intent/日期（`fix/da1016-offload-path-intent` @ `17d1860`，CI #2177 绿，停在分支） | 任务：shared/work/deep-audit-1016c/fix-tasks/FIX-B.md（ISSUE-02 P0 读逃逸 + ISSUE-09 P1 intent 授权 + 子项 C 日期范围）。基线 e3e… |
| 10-16 17:44 | FIX-C 收口：deep-audit-1016c ISSUE-03A/B/C（browser 并发三缺陷）—— fix/da1016-browser-concurrency @ c8a44bf，CI #2176 绿，停在分支 | 任务：读 shared/work/deep-audit-1016c/fix-tasks/FIX-C.md 并立刻执行（基线 main e3ef74545bcc）。报告 shared/work/deep-audit-101… |
| 10-16 17:46 | FIX-G 收口：deep-audit-1016c ISSUE-10(A) sysinfo 合并窗口线程竞态（fix/da1016-sysinfo-race @ 1785682e，CI #2178 绿，停在分支） | 任务：shared/work/deep-audit-1016c/fix-tasks/FIX-G.md（用户令「读这个文件并立刻执行」；中途换代理重克隆）。报告 fix-reports/FIX-G.md（216 行）；装置… |
| 10-16 17:58 | FIX-H 收口：OffloadRssProbe 锁范围（deep-audit-1016c ISSUE-11）—— fix/da1016-rssprobe-lock-scope @ c18d1b4，CI #2179 绿，停在分支 | 修法：record() 的 synchronized(st) 原来只护 4 个字段自增；LEAK-SUSPECT 检查-置位、govern 判定、累计复位全在锁外（NativeOffload.kt Semaphore(2… |
| 10-16 18:04 | FIX-E 收口：deep-audit-1016c ISSUE-07（TTS --voice 失效 + VoiceTextSanitizer 零接线，P1）—— fix/da1016-tts-voice @ 8f6b5fb，CI #2180 绿，停在分支 | 任务书 shared/work/deep-audit-1016c/fix-tasks/FIX-E.md；报告 fix-reports/FIX-E.md；装置 probe/jvm/；背板 backlog §136。基线 m… |
| 10-16 18:16 | FIX-F 收口：deep-audit-1016c ISSUE-09A/09B（调度日 settle 泄漏 + 裸日期/DST 边界）—— fix/da1016-schedule-date-bounds @ c6df69c0，CI #2182 绿，停在分支 | 修法：09A = finalizeRun 的 completeRun 日期实参从 settle 时刻 LocalDate.now() 改为 fire 时刻捕获（Dispatch data class 增 schedule… |
| 10-16 18:19 | FIX-A 收口：deep-audit-1016c ISSUE-01（P0 续写分支持久化丢块 + dbId 回填）—— `fix/da1016-loop-persist-slice` @ `a509a491`，CI #2183 绿，停在分支 | 任务 shared/work/deep-audit-1016c/fix-tasks/FIX-A.md；报告 fix-reports/FIX-A.md；装置 fix-a/probe/；基线 main e3ef74545bc… |
| 10-16 18:50 | FIX-D 收口：deep-audit-1016c auto-compact 治理四支（`fix/da1016-compact-governance` @ `feef7cd`，CI #2184 绿，停在分支） | 任务：读 shared/work/deep-audit-1016c/fix-tasks/FIX-D.md（先证再改）。报告 fix-reports/FIX-D.md；装置 fix-d/probe/{jvm/,mutati… |
| 10-16 19:15 | deep-audit-1016c 收口完成：八支全绿 + 并集树 CI #2185 绿 + 文档已进 main，停在并集分支（用户令「他们都完成了，按流程走，不要忘了更新文档」） | 收口全链：独立核 CI 8/8（每支 conclusion=success 且 head_sha == 远端 tip == 报告 sha）→ diff 检查（8 支夹带 0 / 冲突标记 0 / println(main… |
| 10-16 19:18 | ↳ deep-audit-1016c 合并完成：main `c38cbba752c2`（用户令「合并」），9 分支已清 | 合并前核查（三项全过）：①检查 = 并集相对合并基（e3ef745）49 文件恰为授权集、docs 0 触碰、冲突标记 0、调试残留 0；②验证 = CI #2185 head_sha 三方逐字符一致 + 本地 scan… |
| 10-16 22:21 | FIX-G 收口：deep-audit-1018 ISSUE-11（OAuth 回调双解码 + socket FD 泄漏，P2）—— fix/da1018-oauth-callback @ d94cf63，CI #38912447230 绿，停在分支 | 修法两半：①parseCallbackQuery(uri.rawQuery) 纯函数（单次解码，key/value 各 URLDecoder.decode(raw.replace("+","%2B"))，WebDavCl… |
| 10-16 22:22 | FIX-C 收口：deep-audit-1018 ISSUE-07（CompactPin 同消息双钉 + 紧凑域裸真值陷阱，P1）—— `fix/da1018-compact-pin` @ `9b34241`，CI #38912121098 绿，停在分支 | 任务 shared/work/deep-audit-1018/fix-tasks/FIX-C.md（+ COMMON.md/TRIAGE.md 实读自上级目录）；报告 fix-reports/FIX-C.md；装置 re… |
| 10-16 22:36 | FIX-H 收口完成：deep-audit-1018 ISSUE-12a/b（:modelservice 重复读盘 + 无界线程，P2）—— fix/da1018-modelsvc-io @ 5fce91199109947504653926e30c274c8f5bb64b，CI #38914243668 绿，停在分支 | 任务书 shared/work/deep-audit-1018/fix-tasks/FIX-H.md；报告 fix-reports/FIX-H.md（14890 B，10 节齐全、待填 0）；装置 repo-h/repo… |
| 10-16 22:43 | FIX-E 收口：deep-audit-1018 ISSUE-09（备份恢复漏重映射 + 漏打包蒸馏层，P1）—— `fix/da1018-backup-remap` @ `e3d16b4`，CI #2190 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-E.md 并执行（先读 COMMON/TRIAGE）。报告 shared/work/deep-audit-1018/fix-r… |
| 10-16 22:49 | FIX-D 收口：deep-audit-1018 ISSUE-08（12 处 `.dao.loadMessages(` 直调收敛 + ChatRepository 容错入口）—— fix/da1018-dao-loadmessages @ fd5cfe6，CI #38916547458 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-D.md 并立刻执行（先读 COMMON/TRIAGE，基线 main c38cbba752c2）。报告 shared/wor… |
| 10-16 23:13 | FIX-F 收口：deep-audit-1018 ISSUE-10（GroupRouter health 无锁 + SCM 锁内 resume）—— fix/da1018-router-locks @ aed2560，CI #2194 绿，停在分支 | 任务 shared/work/deep-audit-1018/fix-tasks/FIX-F.md；报告 fix-reports/FIX-F.md；装置 repo-f/probe/（arms.py/arms.sh/ci_… |
| 10-16 23:22 | FIX-A 收口：deep-audit-1018 ISSUE-01（P0 截断工具调用）+ ISSUE-02（P1 子代理 reasoning 丢失）+ ISSUE-12c（P2 叠楼守卫）—— fix/da1018-turn-integrity @ 00026c3 两提交，CI #2195 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-A.md（先证再改）。报告 fix-reports/FIX-A.md；装置 repo-a/probe/（durable，含 c… |
| 10-16 23:36 | FIX-B 交付收口：deep-audit-1018 ISSUE-04/06/03（分支 CI #2196 绿，停在分支） | 身份链：分支 fix/da1018-offload-wiring，基线 main c38cbba，HEAD afee1fa37e08cb65a0244955eb803a4fdd8c61df（两提交：ac4e549 生产+… |
| 10-17 00:05 | deep-audit-1018 收口完成：8 支全绿 + 并集树 CI #2197 绿（停在并集分支，未合并 main） | 输入：用户附件 shared-d48335c9.md（2566 行 / 12 工单）。基线 main c38cbba752c2。 |
| 10-17 00:57 | verify-1018 真机验证收口：deep-audit-1018 八支全部通过可验证面，报告+backlog §156 落档（2026-10-17 凌晨，用户令「现在这个应用是包含了所有修改的，开始验证刚刚的那一批修改」） | 身份链：设备包（codePath ~~3MAPc4DHt38…）与 CI #2197 产物 ci-12057403461-app-release.apk sha256 逐字节一致 → 设备代码 == 合并树 492c1b… |
| 10-17 06:24 | deep-audit-1019 第一道门完成 + 10 支派发（用户令「按流程来吧」，附件 shared-28e12c77.md，2026-10-17 06:1x） | 输入：用户附件 AUDIT-REPORT.md（176KB / 2517 行 / 10 工单 ISSUE-01..10）。基线 main e416551（= da1017 七支 + da1018 八支 + dev-his… |
| 10-17 07:17 | FIX-F 收口：deep-audit-1019 ISSUE-06（parts_json 三路漏斗绕过，P1）—— fix/da1019-partsjson-funnel @ d87169f，CI #2207 dispatch，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-F.md；报告 fix-reports/FIX-F.md；装置 repo-f/probe/（durable）。基线 main e4… |
| 10-17 07:26 | FIX-H 交付收口：deep-audit-1019 ISSUE-08（BrowserUseManager.destroy() 漏唤醒 + data: URL `+` 损坏）—— fix/da1019-browser-destroy @ d04adcf，CI #2203 绿，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-H.md（+ COMMON/TRIAGE 实读）；报告 fix-reports/FIX-H.md；装置 repo-h/probe/… |
| 10-17 07:29 | FIX-C 收口：deep-audit-1019 ISSUE-03（FastioTar USTAR 字段 off-by-one + 归档拒绝 exit 0）—— fix/da1019-fastio-tar-bounds @ 96e9f54，CI #2202 绿，停在分支 | 任务书 shared/work/deep-audit-1019/fix-tasks/FIX-C.md；报告 fix-reports/FIX-C.md；装置 repo-c/probe/（durable：jvm/harnes… |
| 10-17 07:30 | FIX-B 收口：deep-audit-1019 ISSUE-02（Pass1 过早翻 RUNNING → 工具状态发布归位）—— fix/da1019-loop-tooltoolstatus… 实为 fix/da1019-loop-toolstatus @ eeea556，CI #2206 绿，停在分支 | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-B.md（先读 COMMON/TRIAGE，基线 main e416551）。报告 fix-reports/FIX-B.md；… |
| 10-17 07:37 | FIX-A 收口：deep-audit-1019 ISSUE-01（extra_headers/custom-endpoint 信封方言三份副本合一）—— fix/da1019-modeluse-proto @ 3bd0193，CI #2205 绿，停在分支 | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-A.md（先读 COMMON/TRIAGE，基线 main e416551）。报告 fix-reports/FIX-A.md（… |
| 10-17 07:38 | FIX-I 收口：deep-audit-1019 ISSUE-09（BrowserRssProbe/ProviderRssProbe 锁范围）—— fix/da1019-rssprobe-lock-scope @ ace1887，CI #2204 绿，停在分支（2026-10-17） | 任务书 shared/work/deep-audit-1019/fix-tasks/FIX-I.md；报告 fix-reports/FIX-I.md；装置 repo-i/probe/（durable）。基线 main e… |
| 10-17 07:38 | FIX-J 收口：deep-audit-1019 ISSUE-10（contacts argv 分发 + --file 相对路径）—— fix/da1019-offload-cli-args @ bfa6f8e0a9fc，CI #2208 绿，停在分支 | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-J.md（先证后改，基线 main e416551）。报告 fix-reports/FIX-J.md；装置 repo-j/pr… |
| 10-17 07:46 | FIX-G 收口：deep-audit-1019 ISSUE-07（KatexWebViewPool ready 竞态 + ChatViewModelStore 别名表锁外读，P1）—— fix/da1019-katex-store-race @ d440e9b，CI #2199 绿，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-G.md；报告 fix-reports/FIX-G.md（10964 B，14 节齐全）；装置 repo-g/repo/probe… |
| 10-17 08:04 | da1019 FIX-E 收口：session model resolution isEnabled 门（fix/da1019-session-enabled @ 9b2a013，CI #2209 绿，停在分支） | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 shared/work/deep-audit-1019/fix-repor… |
| 10-17 08:33 | deep-audit-1019 收口完成：10 支全绿 → 试合树 `integration/da1019` @ `3f73c61`，CI #2209 success（停在分支，未合并 main） | 输入：用户附件 shared-28e12c77.md（2517 行 / 10 工单，本批第三份报告）。基线 main e416551。用户「按流程来吧」→ 第一道门 10/10 成立（判级修正 1 条：ISSUE-01 … |
| 10-17 09:57 | deep-audit-1020 派发（8 工单，Batch-1 并行）— 用户令「按流程来吧」 | 输入：附件 shared-c9689fbd.md → shared/work/deep-audit-1020/AUDIT-REPORT.md（126KB / 8 工单）。基线 main 942b0111d（60 门全绿；… |
| 10-17 10:31 | FIX-H 收口：deep-audit-1020 ISSUE-08（打印路径无界读 OOM，P2）—— fix/da1020-print-bounded-read @ a59510c，CI #2212 绿，停在分支（2026-10-17） | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-H.md；报告 fix-reports/FIX-H.md；装置 repo-h/probe/（durable：extract.py… |
| 10-17 10:32 | deep-audit-1020 FIX-D 收口：WebViewHolder currentUrl 单源化（fix/da1020-webview-holder-url @ 11d326fd，CI #2213 绿，停在分支） | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-D.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-D.m… |
| 10-17 10:33 | FIX-F 收口：deep-audit-1020 ISSUE-06（深链缺 6 类设置分支 + 5 条 Routes 未 encode）—— fix/da1020-deeplink-routes @ 049a182，CI #2215 绿，停在分支 | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-F.md；报告 fix-reports/FIX-F.md；装置 repo-f/probe/（durable）。基线 main 9… |
| 10-17 10:36 | da1020 FIX-B 收口：Shizuku argv 20 开关声明 + group 按位置取（fix/da1020-shizuku-args @ 9990218，CI #2214 绿，停在分支） | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-B.md；报告 fix-reports/FIX-B.md；装置 repo-b/probe/（durable）。基线 main 9… |
| 10-17 10:39 | da1020 FIX-A 收口：ISSUE-01 一行修复（ProviderRepository.fetchModels 漏传 context）—— fix/da1020-provider-models-cache @ 17c7791，CI #2216 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-A.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-A.m… |
| 10-17 10:44 | FIX-E 收口：da1020 ISSUE-05 语音链路四块（fix/da1020-voice-cancel-ua @ 97102a49，CI #2218 绿，停在分支） | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md；装置 repo-e/repo/p… |
| 10-17 10:49 | FIX-G 收口：deep-audit-1020 ISSUE-07（memory_rollup 门禁 + provider UA 字段）—— fix/da1020-memory-gate-provider-fields @ fd3acf3，CI #2220 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-G.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-G.m… |
| 10-17 10:50 | FIX-C 收口：deep-audit-1020（BrowserUse/Fastio 相对路径 + tar 前缀）—— fix/da1020-offload-relpath-tar @ be8def62，CI #2219 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-C.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-C.md；装置 repo-c/probe/… |
| 10-17 11:21 | deep-audit-1020 收口完成：8 支全绿 → 试合树 `integration/da1020` @ `bdc6d68`，CI #38997214449 **success**（停在分支，未合并 main）+ 文档三件套同步 | 输入：用户附件 shared-c9689fbd.md（8 工单）。基线 main 942b0111d（60 门）。用户「按流程来吧」→ 第一道门 8/8 成立 → 8 支派发（minis-sessions-cli sen… |
| 10-17 11:25 | ↳ deep-audit-1020 合并 main（用户放行「合并」，2026-10-17） | 合并前核查（三项全过）：①31 文件全在预期域（11 门脚本 + 18 生产/测试 .kt）逐支归属对齐、冲突标记/调试残留 0；②合并树 scan 66/0 + test_scan 238、head_sha 逐字符三方… |
| 10-17 13:19 | FIX-E 收口：deep-audit-1021 ISSUE-06（SkillFileViewerScreen 保存丢返回值 + 主线程写盘，P1）—— fix/da1021-skill-file-save @ `dc3d30a`，CI #2224 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-E.md 并执行（先证再改 / 分支隔离 / 自建工作树 repo-e）。基线 main d0c1c9… |
| 10-17 13:20 | FIX-F 收口：deep-audit-1021（WebApp asset URL 单源 + AddToHome 清理闭环）—— fix/da1021-webapp-asset-url-cleanup @ ab9c247，CI #2226 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-F.md（8 支并发派发，我领 FIX-F）。报告 fix-reports/FIX-F.md；装置 r… |
| 10-17 13:21 | FIX-D 收口：deep-audit-1021 ISSUE-05（ChatMenuSettingsScreen 监听键前缀失配 + 排序 State 不重读，P1）—— fix/da1021-chatmenu-listener @ ecb2fc5，CI #2223 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-D.md 并执行（先证再改、分支隔离、自建工作树 repo-d）。基线 main d0c1c92。报告… |
| 10-17 13:30 | FIX-C 收口完成：deep-audit-1021（da1021-C 镜像详情页接线 + seg 全覆盖 + 冷启动兜底）— 2026-10-17 | 分支 CI 绿 = 停止节点达成，未合并 main。 |
| 10-17 13:32 | FIX-A 收口：deep-audit-1021 ISSUE-01（技能恢复 id 贯通）+ ISSUE-10（会话覆盖清理闭环）—— fix/da1021-skill-restore-session-cleanup @ 433430f，CI #2229 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-A.md 立刻执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pr… |
| 10-17 13:34 | FIX-H 收口：deep-audit-1021 ISSUE-11（MinisOpenUrlBroker.offer(Uri) 漏 scheme 校验，P2 半截修复）—— fix/da1021-open-url-broker-scheme @ b47fd77，CI #2230 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-H.md 立刻执行。报告 fix-reports/FIX-H.md；装置 repo-h/probe/（… |
| 10-17 13:35 | FIX-B 收口：deep-audit-1021（ISSUE-02 merge 导入 id 规范化 + ISSUE-09 备份字段齐全）—— fix/da1021-import-id-roundtrip @ 6beb225，CI #2228 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-B.md 并执行。报告 fix-reports/FIX-B.md；装置 repo-b/probe/（留… |
| 10-17 13:41 | FIX-G 收口：deep-audit-1021 ISSUE-08（SpeechRecognitionManager 录音中切引擎 → 在途引擎失控）—— fix/da1021-speech-active-engine @ 6b9e39e，CI #2231 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-G.md 并执行（先证再改、分支隔离、自建 repo-g）。报告 fix-reports/FIX-G.… |
| 10-17 14:10 | deep-audit-1021 收口完成：11 工单 / 8 支全绿 → 并集树 `integration/da1021` @ `315c8bd`，CI #39007801195 success（停在分支，未合并 main） | 输入：用户附件 Rikkaminis_Full_Audit_Report__4_.md（1580 行，ISSUE-01..11）+「按流程来吧」。基线 main d0c1c9219（= 1020 合并树，66 门）。用户… |
| 10-17 15:05 | verify-1021 真机回归收口：4/4 通过 0 回归 + 新发现预览屏裸拼 `#`（backlog §1021-V）—— 2026-10-17 | 输入：用户令「现在这个应用是包含了刚刚一波修复的应用开始测试」。deep-audit-1021 八支并集树 integration/da1021 @ 315c8bd（CI #2232）。 |
| 10-17 15:09 | verify-1021 全部收口：9 远端分支已删（用户拍板第 1 项）+ §1021-V 修复入下一批（用户拍板第 2 项）—— 2026-10-17 | 用户两项拍板：①远端分支删除授权 → 已执行；②§1021-V R1（预览屏 # 未编码）等下一批审计报告出来打包一起处理（不单独开支支）。 |
| 10-17 16:19 | deep-audit-1022 启动：第二道门 + 8 支派发（用户令「走流程」，附新审计报告 10 工单）— 2026-10-17 | 输入：附件 rikkaminis_full_audit_report.md（1529 行 / ISSUE-01..10）。基线 main ba4daf699fb4bcc89eb33cdeb788272283910b91（… |
| 10-17 16:53 | FIX-B 收口：deep-audit-1022 ISSUE-02（草稿晋升后子面板仍绑 `__new__` 路由参数）—— fix/da1022-draft-session-binding @ f700db8，CI #2234 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-B.md 并执行。报告 fix-reports/FIX-B.md（8 节）；装置 repo-b/pro… |
| 10-17 17:04 | FIX-H 收口：deep-audit-1022 ISSUE-10 + backlog §1021-V 打包项（fix/da1022-webview-share-url @ 0328f9d，CI #2235 绿，停在分支）—— 2026-10-17 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-H.md 并执行（先证再改、repo-h 自建）。报告 fix-reports/FIX-H.md（8 … |
| 10-17 17:05 | FIX-G 收口：deep-audit-1022 ISSUE-07（Cookie 域名列表+删除/清空 UI 闭环）+ ISSUE-09（四工具异常分支丢 toolTitle）—— fix/da1022-cookie-and-tooltitle @ 051da7f，CI #2236 绿，停在分支 | 任务 shared/work/deep-audit-1022/fix-tasks/FIX-G.md（+COMMON）；报告 fix-reports/FIX-G.md（8 节）；先证 pre-fix/G-evidence.… |
| 10-17 17:18 | FIX-A 收口：deep-audit-1022 ISSUE-01（跨进程媒体 base64 契约，DEFAULT 折行 vs 严格解码）—— fix/da1022-offload-base64-wire @ afbff5e，CI #2238 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-A.md 并执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pro… |
| 10-17 17:20 | FIX-E 收口：deep-audit-1022 ISSUE-05（自定义模型 ID 改名完整性，P2）—— fix/da1022-entry-id-integrity @ 61d201f7，CI #2237 绿，停在分支 | 任务书 shared/work/deep-audit-1022/fix-tasks/FIX-E.md；报告 fix-reports/FIX-E.md（8 节）；装置 repo-e/probe/（jvm/run.sh+ou… |
| 10-17 17:20 | FIX-C 收口：deep-audit-1022 ISSUE-03 + ISSUE-08（MediaPlayerManager 释放/锁 + BrowserHistory.save 快照）—— fix/da1022-player-history-locks @ d21ecab6，CI #2239 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-C.md 并执行（先证再改、分支隔离、装置留盘不入库）。报告 fix-reports/FIX-C.md… |
| 10-17 17:23 | FIX-F 收口：deep-audit-1022 ISSUE-06（镜像 ViewModel 跨线程快照写 + 主线程 IO + 测速自动选源半途）—— fix/da1022-mirror-vm-thread @ 0d4f11d，CI #2240 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-F.md 并执行（先证再改）。基线 main ba4daf69（克隆后逐字符核对）。报告 fix-re… |
| 10-17 17:28 | FIX-D 收口：deep-audit-1022 ISSUE-04（OpenMinis 兼容层 `appendV1Suffix` 极性反转 + 四字段漏迁）—— fix/da1022-openminis-compat @ 59fb69f，CI #2241 绿，停在分支 | 任务书 /var/minis/shared/work/deep-audit-1022/fix-tasks/FIX-D.md（+COMMON.md）；报告 fix-reports/FIX-D.md（8 节，16727→11… |
| 10-17 17:59 | deep-audit-1022 收口完成：8 支全绿 → 并集树 `integration/da1022` @ `8128896945fc`，CI #2242 success（停在分支，未合并） | 输入：用户附件 rikkaminis_full_audit_report.md（1529 行 / ISSUE-01..10）+「走流程」→「他们都完成了」。基线 main ba4daf69（74 门）。工作目录 shar… |
| 10-17 19:43 | deep-audit-1023 启动：第二道门核实 + 9 支派发（用户令「走流程吧」，附报告 v2 10 工单全 P1） | 输入：附件 rikkaminis_full_audit_report_v2.md（4230 行 / 10 卡 / 全部自称 P1）。基线 main cd9e97b，门禁基线 85 passed / 0 failed（实跑… |
| 10-17 20:28 | FIX-B 收口：deep-audit-1023 ISSUE-02（P1 模型详情页保存损毁模态/覆写）—— fix/da1023-model-override-parity @ 09e377a，CI #2244 绿，停在分支 | 任务：读 shared/work/deep-audit-1023/fix-tasks/COMMON.md 与 FIX-B.md 并执行。基线 main cd9e97b，门禁基线 85/85。报告 shared/work/… |
| 10-17 20:33 | FIX-I 收口：deep-audit-1023 ISSUE-10（自动备份流式导出/拉取净化/主线程列目录，P1）—— fix/da1023-backup-streaming-fetch @ `69b7d83fc053`，CI #2245 绿，停在分支 | 任务：读 shared/work/deep-audit-1023/fix-tasks/COMMON.md + FIX-I.md（先读 TRIAGE §2.10，报告行号不可信）。基线 main cd9e97b。工作树 s… |
| 10-17 20:41 | deep-audit-1023 FIX-A 收口：FileProvider 路径覆盖 + 大消息导出（分支 CI #2246 绿，停在分支） | 交付：fix/da1023-fileprovider-paths @ af7ecfcddbf026b50b10fc2a8e583c5e214d6799（基于 main cd9e97bf），4 文件 +788/−15。CI… |
| 10-17 20:47 | deep-audit-1023 FIX-H 收口：file:// URL 统一 decodeUriPath（fix/da1023-file-uri-decode @ 6f69546，CI #2249 绿，停在分支） | 修法（ISSUE-08，3 文件 +34/−9 生产）：①WebPreviewBottomSheet.openExternalFromSheet file:// 分支 Uri.parse(url).path → deco… |
| 10-17 20:47 | deep-audit-1023 FIX-F 收口（ISSUE-06 会话 id 绑定，fix/da1023-session-id-binding @ ab7e632，CI #2247 绿，停在分支） | 修法：ChatViewModelStore.resolveSessionId 公开委托（单源 delegate private resolveKey，katex 门钉形状保留）+ ChatScreen 4 组站点（图片 … |
| 10-17 20:49 | FIX-G 收口：deep-audit-1023 ISSUE-07（memory 两处 UI 接线断链 + 删除路径主线程 IO）—— fix/da1023-memory-ui-wiring @ 6469641，CI #2248 绿，停在分支 | 身份链三方逐字符一致：本地 HEAD = 远端 tip = CI head_sha = 64696416243378008b64d8b5ca2085efe619591e；run #2248（id 39032697053）… |
| 10-17 21:06 | deep-audit-1023 FIX-E 收口：SKILL.md frontmatter 写路径完整性（fix/da1023-skill-frontmatter-parity @ a02a445d，CI #2252 success，停在分支） | 修法三件（+同族新发现）：①极性单一出口 resolveMergeBase(newFrontmatter, diskContent) 内存新值优先（writeSkillMd 原为 existing ?: skill.fr… |
| 10-17 21:28 | FIX-C 收口：deep-audit-1023 ISSUE-03 + ISSUE-09（模型条目复合主键守卫 + 紧凑钉二级引用）—— fix/da1023-entry-key-provider-parity @ 7106210，CI #2254 绿，停在分支 | 任务：读 shared/work/deep-audit-1023/fix-tasks/{COMMON.md,FIX-C.md} 并立刻执行（基线 main cd9e97b，门禁 85/85；报告行号不可信，先证再改）。报… |
| 10-17 21:51 | deep-audit-1023 收口完成：9 支全绿 → 并集树 integration/da1023 @ 00f24b87，CI #2255 success（停在分支，未合并） | 输入：用户附件 rikkaminis_full_audit_report_v2.md（10 工单全 P1）+「走流程吧」→「他们都完成了」。基线 main cd9e97b（85 门 / 276 夹具）。工作目录 shar… |
| 10-17 23:51 | deep-audit-1024 第二道门核实 + 9 支派发（用户令「走流程吧」，附新审计报告 9 项 v3） | 输入：REPORT-1024.md（1414 行，ISSUE-01..09）。基线 main f688c97（= da1023 收口树）。工作区 shared/work/deep-audit-1024/（本地克隆自 10… |
| 10-18 00:38 | FIX-B 交付收口：deep-audit-1024 ISSUE-02（Gemini base URL 四处分叉 → 统一出口）—— fix/da1024-gemini-base-single-source @ ac1bb9e，CI #2264 绿，停在分支 | 身份链：基线 main f688c97；单提交 ac1bb9e854dbbc01c74ef60d48f343de069de2c4；远端 tip == 本地 HEAD == CI head_sha 三方逐字符一致；CI r… |
| 10-18 00:39 | FIX-E 收口：deep-audit-1024 ISSUE-05（MODEL_ENTRY_DETAIL 路由二次 decode，P2）—— fix/da1024-model-entry-route-no-double-decode @ e0ad364，CI #39048800163 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md（193 行 8 节齐全）；装置 re… |
| 10-18 00:40 | FIX-I 收口：deep-audit-1024 ISSUE-09（ModelUse 相对路径未接 resolveMaybeRelative，P1）—— `fix/da1024-model-use-relative-path` @ `0360290`，CI #39048853946 绿，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-I.md 并立刻执行（先读 COMMON / TRIAGE §3.3 / REPORT-1024 ISSUE-09）。基线 m… |
| 10-18 00:43 | FIX-G 交付收口：deep-audit-1024 ISSUE-07（cacheDir/shared 主线程 IO + 无 TTL）—— `fix/da1024-shared-cache-io-and-prune` @ `cf55123`，CI #39049093263 绿，停在分支 | 基线 main f688c97；单提交 5 文件 +487/−17（3 生产 + 1 测试 + 1 门）；报告 shared/work/deep-audit-1024/fix-reports/FIX-G.md；装置 re… |
| 10-18 00:44 | FIX-H 收口：deep-audit-1024 ISSUE-08（Reset All 后 UI 不刷新 + 内存 session 授权/拒绝未清，P2）—— fix/da1024-offload-permission-reset-sync @ db52d99，CI #2262 绿，停在分支 | 身份链：基线 main f688c97c6877e3b801eeacd33d58c2371d1b803f → 单提交 db52d99786f64171fcb4abe4643a60036787764a；远端 tip == … |
| 10-18 00:46 | FIX-F 收口：deep-audit-1024 ISSUE-06（思考规则编辑器保存丢 reasoningEcho/id + 静默改写 wireFormat）—— fix/da1024-thinking-rule-editor-preserve @ 0babeaa，CI #2265 绿，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-F.md 立刻执行（先读 COMMON/TRIAGE §3.4）。报告 fix-reports/FIX-F.md（8 节齐、0… |
| 10-18 00:49 | FIX-C 交付收口：deep-audit-1024 ISSUE-03（P1 二级引用漏清理 + ModelGroupsScreen 无监听）—— `fix/da1024-compact-pin-cascade-and-listener` @ `f7f1419beb32`，CI #39049819858 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md；装置 repo-c/probe/（durable：jvm/{… |
| 10-18 01:49 | deep-audit-1024 收口完成（用户令「一并处理 + 更新文档」）：10 分支并入 main，代码+文档双绿 | 终态：main bcb6569（代码 f1df9de + 文档 bcb6569）；main^{tree} == b3ed8a6^{tree}（并集树 CI 验证过的那个 commit）== 3c672538a2c47f0… |
| 10-18 01:51 | RikkaMinis 审计背景包更新（v7 / prompt v4）+ deep-audit-1024 收口核验 | 用户指令原话：「为下一波的那个审计做准备吧，该更新的更新，该优化的优化，该改的改。你自己的判断。」随后明确：「在笔记文件夹挂载的那个文件夹里面 RikkaMinis审计背景包」——要更新的是发给审计模型的背景包与提示词（… |
| 10-18 09:03 | deep-audit-1025 第二道门 + 9 支派发（用户令「走流程」，附审计报告 v4 9 项） | 输入：shared-45079a1b.md（135,630 B / 1874 行，ISSUE-01..09）。基线 main bcb6569（1024 收口树）。工作区 shared/work/deep-audit-10… |
| 10-18 09:06 | deep-audit-1025 派发更正（2026-10-18 凌晨） | deep-audit-1025 派发更正（2026-10-18 凌晨） |
| 10-18 09:53 | FIX-G 交付收口：deep-audit-1025 ISSUE-07（P1 工具错误分支丢失胶囊标题）—— fix/da1025-tool-title-error-branch @ e46cdee，CI #39087080210 绿，停在分支 | 任务：读 shared/work/deep-audit-1025/fix-tasks/FIX-G.md 立刻执行（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-G.md（8 节齐）；装置 re… |
| 10-18 09:54 | FIX-F 收口：deep-audit-1025 ISSUE-06（P1 Compose remember 键陈旧：modelCount 卡 0 + 删除守卫失效）—— `fix/da1025-provider-remember-keys` @ `8460ef06`，CI #2277（run 39087103229）绿，停在分支 | 任务：读 shared/work/deep-audit-1025/fix-tasks/FIX-F.md（先读 COMMON/TRIAGE，基线 main bcb6569）。报告 fix-reports/FIX-F.md（… |
| 10-18 09:59 | FIX-A 交付收口：deep-audit-1025 ISSUE-01（android-player 相对路径 100% 失败 + 跨会话读逃逸，P1） | 分支 fix/da1025-player-session-path @ 57945206c2b83dd6d321dffb294554e4b26f4e39（单提交，4 文件）；基线 main bcb6569。CI #390… |
| 10-18 10:00 | FIX-H 交付收口：deep-audit-1025 ISSUE-08（挂载探针 + 日志清理离开主线程）—— `fix/da1025-mount-probe-io` @ `d66c7a6`，CI #39087410687 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-H.md（P2，8 节报告齐）。基线 main bcb6569。交付 3 文件：2 生产（+32/−5）+ 1 门脚本扩展（238… |
| 10-18 10:05 | FIX-D 收口：deep-audit-1025 ISSUE-04（browser_tabs/<sid>.json 免疫生命周期清理，P1）—— fix/da1025-browser-tabs-lifecycle @ c1dbf37，CI #39087510098 success，停在分支 | 工单 shared/work/deep-audit-1025/fix-tasks/FIX-D.md（先证再改）；报告 fix-reports/FIX-D.md（8 节 / 169 行）；装置 repo-d/probe/（… |
| 10-18 10:07 | FIX-I 交付收口：deep-audit-1025 ISSUE-09（P2 Provider RPC 守卫/字段不对称）—— fix/da1025-provider-rpc-symmetry @ `6b40595`，CI #2276（39087616148）success，停在分支 | 任务书 shared/work/deep-audit-1025/fix-tasks/FIX-I.md；报告 fix-reports/FIX-I.md（8 节齐）；装置 repo-i/probe/（durable）；bac… |
| 10-18 10:09 | FIX-C 交付收口：deep-audit-1025 ISSUE-03（a11y 双表失步，P1）—— `fix/da1025-a11y-status-verbs` @ `8b73bee`，CI #2277 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md（9 节）；装置 repo-c/probe/{prefix,j… |
| 10-18 10:21 | FIX-B 收口：deep-audit-1025 ISSUE-02（CrashFrequencyDetector 主线程长耗时 I/O + 漏挂 TTL 清理，P1）—— `fix/da1025-crash-share-io` @ `1612c0b`，CI 绿，停在分支 | 任务 fix-tasks/FIX-B.md（先证再改，COMMON 实际在上级目录）。报告 fix-reports/FIX-B.md（105 行，§1b 记首轮 CI 红）；装置 repo-b/probe/（durabl… |
| 10-18 11:05 | deep-audit-1025 收口完成（用户令「他们都完成了」）：9 支并入并集树，CI 绿 @ 3bf0834 | 身份链：基线 main bcb6569 → 9 支（A..I）→ 并集树 integration/da1025 3bf08341717bf6817970a912efd4ee6c8046fd40（9 次 --no-ff 零… |
| 10-18 11:27 | ↳ deep-audit-1025 合并 main 完成（用户令「合并」）：main `dc04fc3`，代码+文档双落地 | 身份链：基线 bcb6569 → 并集树 integration/da1025 3bf0834（CI #2279 绿，树 ca4bff07）→ main e5c1712（--no-ff）→ 文档归档 → main dc0… |
| 10-18 13:06 | deep-audit-1026 启动：第二道门核实完成 + 7 支派发（用户令「按流程来」，附新审计报告 8 项） | 输入：shared-d2163f57.md（132KB，8 ISSUE）→ shared/work/deep-audit-1026/REPORT-1026.md。基线 main dc04fc3（= 1025 收口树）。门… |
| 10-18 14:10 | FIX-G 收口：deep-audit-1026 ISSUE-07（ShareReceiverActivity 主线程同步导入+删暂存，P1）—— `fix/da1026-share-import-io` @ `2e680cd`，CI #2283 绿，停在分支 | 任务 fix-tasks/FIX-G.md（COMMON.md 实际在上级目录 deep-audit-1026/，不在 fix-tasks/）。报告 fix-reports/FIX-G.md（8 节 170 行，无待填）… |
| 10-18 14:12 | FIX-E 交付收口：deep-audit-1026 ISSUE-05（ProvidersCollection customBaseURL 漏守卫，P1）—— `fix/da1026-providers-baseurl-guard` @ `53c9d08`，CI run 39101827796 绿，停在分支 | 任务书 fix-tasks/FIX-E.md（COMMON 实际在上级目录，已读）。基线 main dc04fc3。交付 2 文件 +261/−7。 |
| 10-18 14:13 | FIX-B 收口：deep-audit-1026 ISSUE-02（OffloadOutput.OUTPUT_FLAGS 漏 help/h + SpeakOffloadHandler 漏 booleanFlags，P1）—— `fix/da1026-offload-help-flag` @ `b3f9eb4`，CI 绿，停在分支 | 任务 fix-tasks/FIX-B.md（COMMON 实际在上级目录）；报告 fix-reports/FIX-B.md；装置 repo-b/probe/（durable）。基线 main dc04fc3。交付 3 文… |
| 10-18 14:18 | FIX-C 收口：deep-audit-1026 ISSUE-03（三屏 remember 漏绑字段值 + 刷新 Toast 读旧快照，A=P1/B=P2）—— `fix/da1026-detail-remember-keys` @ `05c9ade`，CI #2286 绿，停在分支 | 任务 fix-tasks/FIX-C.md（先读 COMMON，实际在上级目录 shared/work/deep-audit-1026/COMMON.md）；报告 fix-reports/FIX-C.md（8 节 / 1… |
| 10-18 14:18 | FIX-D 收口：deep-audit-1026 ISSUE-04（`minis-config` 加模型落库 provider 存实例 UUID + 未 trim + providerKeyMap 缺 displayName 键）—— `fix/da1026-models-provider-field` @ `4be9d09`，CI #2285 绿，停在分支 | 任务 fix-tasks/FIX-D.md（先读 COMMON，实际在上级目录）。报告 fix-reports/FIX-D.md；装置 repo-d/probe/（树内，.gitignore:141 已含 probe/）… |
| 10-18 14:30 | FIX-F 收口：deep-audit-1026 ISSUE-06（CSV 预览绕过 readCapped 无界读 + 两预览 remember 无键）—— `fix/da1026-csv-bounded-read` @ `011812a`，CI run 39103139228 绿，停在分支 | 任务 fix-tasks/FIX-F.md（COMMON 在上级目录）。报告 fix-reports/FIX-F.md（8 节 / 12794 B / 无占位符）；装置 repo-f/probe/（durable、不入库… |
| 10-18 15:05 | FIX-H 收口：deep-audit-1026 ISSUE-08（group context-limit 写漏斗不同步 + `get groups` 缺两键，P2）—— `fix/da1026-group-sync` @ `0353112`，CI #2288 绿，停在分支 | 任务 fix-tasks/FIX-H.md（COMMON 实读自上级目录）；报告 fix-reports/FIX-H.md（8 节 / 16002 B / 无 TODO）；装置 repo-h/probe/（jvm/{ex… |
| 10-18 15:32 | deep-audit-1026 收口完成（用户令「按流程来」→ 8 支全绿 → 并集树 CI 绿） | 身份链：基线 main dc04fc3 → 8 支（A..H，逐支 CI 绿）→ 并集树 integration/da1026 ad0c5d78c9024750df52bc7c57c3eba5e338b6f0（8 次 -… |
| 10-18 15:52 | ↳ deep-audit-1026 合并 main + 文档归档完成（用户令「合并，不要忘了文档」） | 合并：integration/da1026（@ ad0c5d7）--no-ff 合入 main → 3f99b30ce582c454754184405ad0db7c08d546f5；树对拍 main^{tree} == … |
| 10-18 16:47 | ARCH-03 收口：ProviderType 编译期穷举元数据契约 —— `fix/da1026-arch03-provider-type-registry` @ `22a15ed`，CI run 39110831683 success，停在分支 | 任务：pinned 指令 = 读 shared/work/deep-audit-1026/ARCH-COMMON.md，执行同目录 ARCH-BLUEPRINT.md 的「ARCH-03」小节。基线 main 9d182… |
| 10-18 16:48 | ARCH-02 收口：PrimedPrefs 自治重预热契约（`fix/da1026-arch02-primed-prefs` @ `0f9f7cd`，CI #2293 绿，停在分支） | 任务 /var/minis/shared/work/deep-audit-1026/ARCH-COMMON.md（工单 = ARCH-BLUEPRINT.md 的 ARCH-02 小节）。报告 fix-reports/A… |
| 10-18 17:18 | ARCH 批次收口完成（用户「他们完成了」/「跑完了」）：3 支并入并集树，CI 绿 @ 407bdd4 | 身份链：基线 main 9d18269 → 3 支 → 并集树 integration/da1026-arch @ 407bdd4（3 次 --no-ff 零冲突 + 门注册提交）。 |
| 10-18 19:47 | deep-audit-1027 启动：第二道门核实 + 7 支派发（用户令「走流程」，附新审计报告 8 项） | 【本条为 12:56 UTC 日报截断事故后由主会话重建：原文不可逐字节取回（app 日志仅存前 309 字符、语义索引最后 build=10-10），以下按本会话记录与 TRIAGE/DISPATCH 台账重写；事实以… |
| 10-18 20:05 | 并发派发踩门禁的解法：gate_run.sh（用户提出「多任务派发跑门禁会互相踩，怎么解决」） | 【本条为 12:56 UTC 日报截断事故后由主会话重建：原文不可逐字节取回，按本会话记录与落地文件重写】 |
| 10-18 20:55 | FIX-D 收口：deep-audit-1027 ISSUE-05（RootfsVM 两次 verifyIntegrity 主线程直调）—— fix/da1027-d-rootfs-io @ a7aa249，CI #39127888788 绿，停在分支 | 修法：repairRootfs(ctx) 整体并入一个 viewModelScope.launch，两处 manager.verifyIntegrity()（基线 :173 点击回调内 / :191 launch 内 M… |
| 10-18 21:02 | FIX-C 收口：deep-audit-1027 ISSUE-04（Xunfei 空转写被当成功结果返回）—— `fix/da1027-c-xunfei-transcript` @ `d01da0d`，CI #39128561098 绿，停在分支 | 任务：读 shared/work/deep-audit-1027/fix-tasks/FIX-C.md + COMMON.md（注意：COMMON.md 在 上级目录，不在 fix-tasks/）。报告 fix-repo… |
| 10-18 21:09 | deep-audit-1027 FIX-E 收口（ISSUE-02+08 合支，停在分支）2026-10-18 | 身份链：基线 main 1f6ca13 → 分支 fix/da1027-e-remember-keys @ 5c648634fb4b29bdaf431386bc00e7c00527120c（单提交，5 文件 +310/−… |
| 10-18 21:38 | deep-audit-1027 收口完成（用户「他们都跑完了」）：7 支并入并集树，CI 绿 @ 5030841 | 身份链：基线 main 1f6ca13 → 7 支（A–G）→ 并集树 integration/da1027 @ 5030841（7 次 --no-ff 零冲突）→ 并集 CI #2303（39131097372）suc… |
| 10-18 23:47 | deep-audit-1028 启动：第二道门核实 + 判级 + 8→3 合支派发（用户令「走流程吧，小的合并少开会话」） | 输入：REPORT-1028（8 工单/12 裂口，基线声明 889d645 = main 一致）。工作区 shared/work/deep-audit-1028/（repo 为 GitHub 新浅克隆——1027 本地… |
| 10-19 00:56 | FIX-C 收口：deep-audit-1028 ISSUE-02 + ISSUE-05 + ISSUE-06（UI 状态失步 / filesDir 清理接线 / 深链响应式）—— `fix/da1028-c-ui-state-wiring` @ `aaa4cc1`，CI #39145801329 绿，停在分支 | 身份链：基线 main 889d645（tree f12ca356）→ 单提交 aaa4cc1b6267a40e7683c2e4795a575d7e2e93ba（父 = 基线）→ 远端 tip == 本地 HEAD ==… |
| 10-19 01:28 | FIX-B 收口：deep-audit-1028 ISSUE-03 + ISSUE-04（配置作用域 / 外部写回流）—— `fix/da1028-b-config-sync` @ `7a17c5a`，CI #39148284718 绿，停在分支 | 身份链：基线 889d645 → 分支 @ 7a17c5a603da5e1f3590dbfab69bcd139a9f4be8（父=基线，单提交，11 文件 +714/−28）→ CI run 39148284718 wo… |
| 10-19 02:08 | deep-audit-1028 收口完成：8 工单 → 3 支会话（用户令「小的合并」），并集树 CI 全绿 @ 6e6fc9a | 身份链：基线 main 889d645 → 3 支（A/B/C）→ 并集树 integration/da1028 @ 6e6fc9a（tree a777fc76，3 次 --no-ff 零冲突）→ 并集 CI run 3… |
| 10-19 07:41 | deep-audit-1029 FIX-B 收口：ISSUE-03（记忆编辑屏主线程同步 IO + SOUL 缓存回流）—— `fix/da1029-b-memory-io` @ `98c0fc5`，CI #39176155210 绿，停在分支 | 身份链：基线 main 83caac6 → 单提交 98c0fc59e2cbe307b5e539853dc2a2608b4988e8（3 文件 +254/−26）→ branch dispatch CI 39176155… |
| 10-19 07:45 | FIX-F 收口：deep-audit-1029 ISSUE-07（`android-shizuku-cli exec` 选项作用域，两裂口均 P1）—— `fix/da1029-f-shizuku-argv` @ `df76dfc`，CI #39176352125 绿，停在分支 | 身份链：基线 main 83caac6 → 分支 fix/da1029-f-shizuku-argv @ df76dfc85bdf24e4046bc25ad9626b93df4f4d2a（父 = 基线，单提交，3 文件 … |
| 10-19 07:48 | FIX-D 收口：deep-audit-1029 ISSUE-05（语音族 UA 缺口）—— `fix/da1029-d-voice-ua` @ `4c7ad80`，CI #39176720024 绿，停在分支 | 身份链：基线 main 83caac6 → 单提交 19ceb02（3 生产 .kt +204/−2 + 测试 +204）→ 门扩展 4c7ad80（provider_ua_parity_check.py +261）→ … |
| 10-19 07:50 | FIX-C 收口：deep-audit-1029 ISSUE-04 + ISSUE-09（锁外写回 / 同文件不对称）—— `fix/da1029-c-lock-scope` @ `c09bbe9`，CI #39176651703 绿，停在分支 | 身份链：基线 83caac6 → 分支 @ c09bbe92630d8fbfb5cd7d4c1736aeffbbd3d518（父=基线，单提交，6 文件 +495/−6）→ CI run 39176651703 succ… |
| 10-19 07:54 | FIX-E 收口：deep-audit-1029 I06+I08（UI 状态同步支）—— fix/da1029-e-ui-state @ be75a09，CI #39177457402 绿，停在分支 | 身份链：基线 main 83caac6（da1028 收口树）→ 分支 fix/da1029-e-ui-state @ be75a098fc2ab67a5723c17e797ed3370ee77abe（单提交，4 文件 … |
| 10-19 08:10 | FIX-A (deep-audit-1029) 收口：I01 草稿单源 + I02 深链穷举，分支 CI 绿 @ e76330e，停在分支 | 身份链：基线 83caac6 → 2 提交（5ca7e1a 修法 + e76330e CI 编译红补丁）→ HEAD e76330e999e7daa8e9aa62a552b3117492351657 == 远端 tip … |
| 10-19 11:27 | deep-audit-1030 派发完成（8 ISSUE → 5 支，用户令「走流程吧，小任务合并」） | 输入：REPORT-1030.md（shared-63819e1a.md，110KB，8 ISSUE：P1×6/P2×2/P0×0，基线声明 b353317）。 |
| 10-19 11:43 | deep-audit-1030 FIX-B 收口：ISSUE-03 浏览器 UA 单源（池级分支 + 面板订阅）—— `fix/da1030-b-browser-ua-pool` @ `346e1be`，CI #2320 绿，停在分支 | 身份链：基线 main f7364ec → 单提交 346e1be5700ec5f8dabfed512048c557bf90f45f（4 文件 +476/−18 = 生产 2 / 门 1 / 测试 1 新建）→ 远端 t… |
| 10-19 11:59 | deep-audit-1030 FIX-A 收口：会话生命周期断链 + 同族调用点漏传（I01 + 02）—— 分支 CI 绿 @ ee6f987，停在分支 | 身份链：基线 main f7364ec → 分支 fix/da1030-a-session-callpoints @ ee6f9878d41ab73ac1c7f68a59aa669a9c40a6b3（单提交，9 文件 +… |
| 10-19 12:07 | FIX-D 收口：deep-audit-1030 ISSUE-06（Provider 出站单源：passthrough UA + Azure auth）—— `fix/da1030-d-provider-outbound` @ `d55aee5`，CI #2323 绿，停在分支 | 身份链：基线 main f7364ec → 2 提交（c646c0a 主体 + d55aee5 自查补丁）→ HEAD d55aee582aee4f27a8d566af94cc1ae50146c939 == 远端 tip… |
| 10-19 12:18 | FIX-E 收口（deep-audit-1030 ISSUE-07 + 08 合支）：`fix/da1030-e-guard-slots` @ `22849dc`，CI 39197110222 绿，停在分支 | 身份链三方逐字符：LOCAL == REMOTE == CI head_sha = 22849dc3ff52b630477fed3e56a996276c80c704（基线 f7364ec）。单提交，7 文件 +814/−… |
| 10-19 13:34 | 审计背景包 v12→v13 升级（deep-audit-1030 收口后） | 位置：mounts/笔记/RikkaMinis审计背景包/。三件全升：context v12→v13（146→159 KB / 546 行）· prompt v9→v10（46→50 KB / 360 行）· zip b… |
| 10-19 15:06 | deep-audit-1031 启动：第二道门（8/8 成立）+ 8→4 支合并派发（用户令「走流程」+「不要忘了把小的任务合并」） | 输入：附件 shared-8c2edc89.md（1551 行 / 107,816 B，ISSUE-01..08）。基线 main 2bc07ec（= 1030 收口树，本地工作副本自核一致）。工作区 shared/wo… |
| 10-19 16:25 | FIX-A 收口（deep-audit-1031 ISSUE-01 + ISSUE-03 合支）：`fix/da1031-a-refs-and-config` @ `86e6979`，CI #2330 绿，停在分支 | 身份链三方逐字符：LOCAL == REMOTE == CI head_sha = 86e69796e10d47c541dbe9d9bcc0f99388fccc46（基线 main 2bc07ec）。单提交 7 文件 +… |
| 10-19 16:33 | FIX-C 收口（deep-audit-1031 ISSUE-04 A1/A2/B1/B2 + ISSUE-05 B）：`fix/da1031-c-sandbox-guards` @ `5062174`，CI #2331 绿，停在分支 | 身份链三方逐字符：LOCAL == REMOTE == CI head_sha = 50621745fdcb66ffdc79e27709b64a4ef65c2701（基线 2bc07ec）。单提交，8 文件 +729/−… |
| 10-19 16:38 | FIX-B 收口（deep-audit-1031 ISSUE-02 [P1] 权限槽位并发竞态 + 会话 grants 生命周期） | 身份链：基线 2bc07ec → 分支 fix/da1031-b-permission-slots，tip 75b84d09af8dcf553b5ac602e41f75fadfa8aceb（两提交：6e29173 主体 … |
| 10-19 17:01 | FIX-D 收口（deep-audit-1031 D 支：ISSUE-06 + 07 + 08）—— 分支 CI 绿，停在分支 | 身份链：基线 main 2bc07ec → 4 提交（6cf7058 ISSUE-07 → 54951b3 ISSUE-06 → bc57d68 ISSUE-08 → 8a5f68d 门判据重绑）→ 分支 fix/da1… |
| 10-19 17:44 | 审计背景包 v13→v14 升级（deep-audit-1031 收口后） | 位置：mounts/笔记/RikkaMinis审计背景包/。三件全升：context v13→v14（159→174 KB / 579 行）· prompt v10→v11（50→54 KB / 375 行）· zip … |
| 10-19 17:56 | deep-audit-1031 收口完成（用户令「合并并整理」）：main `09b6c123` + 文档 2006 条 + 分支清零 | 身份链终态：基线 main 2bc07ec → 4 支 → 并集树 integration/da1031 @ 31206d41e80a（tree 8eb668ff832169b4261e30dc4d58b30bc7cdd… |
| 10-19 19:35 | FIX-A 收口（deep-audit-1032 ISSUE-01 WebApp 全屏单源 + ISSUE-02 SessionEditSheet 调用点接线）—— 分支 CI 绿，停在分支 | 身份链三方逐字符：LOCAL == REMOTE == CI head_sha = c6978d1dddf5c6de135908ab5701581a38a7ac73（基线 main 09b6c12）。CI run 392… |
| 10-19 19:38 | FIX-B 收口（deep-audit-1032 ISSUE-03 [P1] debug RPC 消息视图三处不对称）— 分支 CI 绿，停在分支 | 身份链：基线 main 09b6c12 → 分支 fix/da1032-b-debug-rpc-parts，单提交 6c46f968abdac5e5b6672a1e9a050f3fe708eb90；LOCAL == or… |
| 10-19 19:47 | FIX-D 收口（deep-audit-1032 ISSUE-05 + 07）：`fix/da1032-d-terminal-mainid` @ `af900f2`，CI #2341 绿，停在分支 | 身份链三方逐字符：基线 09b6c12 → 两提交（fa7e45b 主体 + af900f2 所有权守卫回填）→ LOCAL == REMOTE == CI head_sha = af900f26382dd058288a… |
| 10-19 19:54 | FIX-E 收口（deep-audit-1032 ISSUE-06 P1）：设置屏首屏 reload 线程 + 技能编辑器 remember 键 —— `fix/da1032-e-settings-io-keys` @ `a2106ab0`，CI #39245665334 绿，停在分支 | 身份链三方逐字符：基线 09b6c12 → 两提交（ad733d56 主体 + a2106ab0 告警清理）→ CI run 39245665334 / job 118036843575 success，local ==… |
| 10-19 20:35 | deep-audit-1032 启动+收口前夜：第二道门（8/8 成立）+ 8→5 支合并派发 + 五支全绿 + 并集树 CI 绿 | 输入：附件 shared-ee3fef8b.md（1306 行 / 84,341 B，ISSUE-01..08）。基线 main 09b6c12（= 报声明 2c8c174 的直接后代，diff 仅 docs/dev-h… |
| 10-19 20:54 | deep-audit-1032 收口完成（用户令「先更新文档，然后再合并」）：main `c6d426a` + 档案 2012 条 + 7 支待清 | 身份链终态：基线 main 09b6c12（= 报告 2c8c174 + docs，代码零差异）→ 5 支（A c6978d1 / B 6c46f96 / C ad24155 / D af900f2 / E a2106a… |
| 10-19 22:43 | deep-audit-1033 启动：第二道门（8/8 成立，0 驳回）+ 8→5 支合并派发 | 输入：附件 shared-19af2d68.md（518 行 / 69,668 B，ISSUE-01..08）。基线 main c6d426a——主会话 git rev-parse HEAD 自核与报告声明逐字符一致（免… |
| 10-19 23:13 | FIX-B 收口（deep-audit-1033 ISSUE-02）：`fix/da1033-b-media-guards` @ `a343b015`，CI #39273447241 绿，停在分支 | 身份链三方逐字符：基线 main c6d426a → 单提交 a343b01524b45a5d8cdc8c48c4405113a6a11a4d → CI run 39273447241（job 118131489194）… |
| 10-19 23:48 | FIX-C 收口（deep-audit-1033 ISSUE-03 P1）：android-speech --source 相对路径 —— `fix/da1033-c-speech-cwd` @ `d3037a76`，CI #2348 绿，停在分支 | 身份链三方逐字符：基线 main c6d426a7 → 2 提交（生产 + test/门）→ CI run 39275575813（#2348）success @ 2026-10-19T15:10:26Z→15:28:0… |
| 10-20 00:01 | FIX-D 收口（deep-audit-1033 ISSUE-04：offload 载荷 JSON 契约收敛）—— 分支 CI 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 分支 fix/da1033-d-offload-json @ 9b5af91151126a800b2c430d73d92e4450c4ae51（本地==远端==CI head… |
| 10-20 00:15 | FIX-E 收口（deep-audit-1033 ISSUE-06 + 07 合支）：`fix/da1033-e-settings-derived-state` @ `1a61b87a`，CI job 绿，停在分支 | 身份链三方逐字符：基线 c6d426a → 单提交 1a61b87a6c7ce6cf6222be1e88946e23884bae80（7 文件 +629/−29）→ LOCAL == REMOTE == CI head_… |
| 10-20 00:48 | FIX-A 收口（deep-audit-1033 I01+I05+I08）：`fix/da1033-a-ro-guard-mainio` @ `7b1b1722`，分支 CI #2353 绿，停在分支 | 身份链三方逐字符：基线 main c6d426a7 → 两提交（a99827d5 主体 + 7b1b1722 编译修复）→ LOCAL == REMOTE == CI head_sha = 7b1b172282a01f5… |
| 10-20 00:58 | FIX-A 收口（deep-audit-1033 ISSUE-01 + 05 + 08）：`fix/da1033-a-ro-guard-mainio` @ `7b1b1722`（两提交），CI #2353 绿，停在分支 | 身份链：基线 c6d426a7 → a99827d5（主体）→ 7b1b1722（修 CI 编译事故） → CI run 39284407398（#2353）success；三方逐字符一致。9 文件 +602/−82（7… |
| 10-20 00:58 | FIX-D 收口（deep-audit-1033 ISSUE-04 P1）：offload 载荷 JSON 契约收敛 —— `fix/da1033-d-offload-json` @ `9b5af911`，CI #2349 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 9b5af9115112…；CI run 39276128510（#2349）/ job 118140794036 success（15:13:57Z→15:36:21Z，2… |
| 10-20 00:59 | FIX-E 收口（deep-audit-1033 ISSUE-06 + 07 P1 合支）：usage 模型身份取实时配置 + thinking 弹窗活值 —— `fix/da1033-e-settings-derived-state` @ `1a61b87a`，CI #2350 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 1a61b87a6c7c…；CI run 39278816691（#2350）success。7 文件 +629/−29（4 生产 .kt + 2 测试 .kt + 1 门扩… |
| 10-20 01:19 | deep-audit-1033 收口：五支全绿 → 并集树 `aacdba10` 本地 108/108 + 329/329 → 并集 CI #2354 success（判定停止节点） | 身份链：基线 main c6d426a7（= da1032 收口树）→ 5 支（A 7b1b1722（两提交，修过 CI 编译事故）/ B a343b015 / C d3037a76 / D 9b5af911 / E 1… |
| 10-20 07:20 | deep-audit-1034 启动：首次 A/B 双报告第二道门（16/16 成立 · 判级 0 修正）+ 8 支派发 —— 2026-10-20 | 输入：首次「一分为二」按管辖面切的两份报告：REPORT-A.md（内核·进程·数据面，8 工单，P0×1/P1×6/P2×1）+ REPORT-B.md（UI·交互·呈现面，8 工单，P1×8）。基线 main b7a… |
| 10-20 08:10 | FIX-B4 收口（deep-audit-1034 B-07+B-08）：弹窗派生态与活数据源脱钩 —— `fix/da1034-b4-dialogs-derived` @ `77a5cae`，CI #2356 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 4 提交 → 77a5cae258921a7156c77dfbfebeb460e83580b8 == git ls-remote == CI head_sha；ru… |
| 10-20 08:29 | FIX-B3 收口（deep-audit-1034 B-05+B-06 两支）：列表块 mathSpans 接线 + 技能面板增量 merge —— `fix/da1034-b3-markdown-sheets` @ `b906313`，CI #2359 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 4 提交（9b87adf markdown · 5032ebc sheet · 38fc024 门 · b906313 测试）→ HEAD b906313ad3a32312e… |
| 10-20 08:31 | FIX-A3 收口（deep-audit-1034 ISSUE-05 + ISSUE-06）：`fix/da1034-a3-photos-video` @ `1fb9484`，CI #2360 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a5cd0bc7ac3e9fee722ebf99ed95a826b → 分支 fix/da1034-a3-photos-video @ 1fb94847885f39b4b2… |
| 10-20 08:39 | FIX-B1 收口（deep-audit-1034 B-01+B-02）：`fix/da1034-b1-onboarding-i18n` @ `81d5e0d0`，CI #2361 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 81d5e0d0ecbef8d9bc9c2f973556d2711884fe48（单提交，5 文件 +731/−30）→ CI #2361（run 39346518996）s… |
| 10-20 09:06 | FIX-A1 收口（deep-audit-1034 ISSUE-01 P0）：备份 zip 不再带走 MCP OAuth 明文桥 —— `fix/da1034-a1-backup-creds` @ `603d297c`，CI #2365 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 19b1c3e（生产+测试）/ 603d297（门扩展）→ 本地==远端==CI head_sha；CI #2365（run 39349095475）success… |
| 10-20 09:21 | FIX-A2 收口（deep-audit-1034 ISSUE-02 + ISSUE-04）：SAF 根判定统一规范化 id + Shizuku 特权路径/uid 回退 —— `fix/da1034-a2-docsprovider-shizuku` @ `69f435a2`，CI #2366 success，停在分支 | 身份链三方逐字符：基线 b7acf94a → 两提交（0aaae779 生产 + 出货测试 / 69f435a2 四门扩展）→ LOCAL == REMOTE == CI head_sha = 69f435a2396cf… |
| 10-20 10:25 | deep-audit-1034 收口完成（用户令「跑完了，没有问题的话就合并，执行后面的步骤吧」）：main `76488f2d` + main CI #2368 success（Publish 执行）+ 档案 2035 条 + 远端只剩 main | 批次终态：基线 main b7acf94a → 8 支（A1–A4 / B1–B4，三方逐字符一致 8/8）→ 并集树 integration/da1034 @ b0ce77591d578616d8d4eed3a0369… |
| 10-20 12:43 | FIX-A2 收口（deep-audit-v14 ISSUE-03 P1）：`FastioTar.writeRegular` 先写 header 后开文件 → 单个坏文件毁整包 —— `fix/dv14-a2-tar-stream` @ `446dd9e1`，CI #2371 绿，停在分支 | 身份链三方逐字符：基线 76488f2d → 8cc278da（生产 + 出货测试）/ 446dd9e1（门扩展）→ 本地 == 远端 == CI head_sha = 446dd9e160e8d0381e6a933b3… |
| 10-20 12:57 | FIX-B2 收口（deep-audit-v14 ISSUE-03 P1 + ISSUE-07 P2）：`fix/dv14-b2-dispose-order` @ `161baecc`，CI #2375 绿，停在分支 | 身份链三方逐字符：基线 76488f2d → 两提交（898312f 生产+装运测试 / 161baecc 门扩展）→ LOCAL == ls-remote == CI head_sha = 161baecc3eb9db… |
| 10-20 12:59 | FIX-B5 收口（deep-audit-v14 ISSUE-06 P1）：`fix/dv14-b5-skill-offmain` @ `db90b8f6`，CI #2374 success，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 3 提交（35cf999 生产+出货测试 · e7221d5 owner 门扩展 · db90b8f fix-forward）→ 远端 ls-remote 同 sh… |
| 10-20 13:04 | FIX-B1 收口（deep-audit-1035 ISSUE-01 + ISSUE-02）：宿主窗口静默空转 + 移动视口极性 —— `fix/dv14-b1-window-viewport` @ `70d7b904`，CI #2379 success，停在分支 | 身份链三方逐字符：基线 main 76488f2dfcbbc79db8eac6df60b7682e187121f6 → 3 提交（df34b84a 生产+出货测试 / 41b5fe00 门 W5e / 70d7b904 … |
| 10-20 13:04 | FIX-A3 收口（deep-audit-1035 / dv14 ISSUE-05 + ISSUE-06）：`fix/dv14-a3-date-perm` @ `6c1778d4`，CI #2377 绿，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 单提交 6c1778d49d098faef678d8d8d5cc8030023dd91f → 远端 tip 同 sha → CI run 39370866204（#… |
| 10-20 13:13 | FIX-A1 收口（deep-audit-v14 ISSUE-01+02+04 三处「名存实亡的别名」）：`fix/dv14-a1-offload-cli-args` @ `328507c6`，CI #2381 success，停在分支 | 身份链三方逐字符：基线 main 76488f2dfcbbc79db8eac6df60b7682e187121f6 → 2 提交（dd70071 生产+测试 / 328507c 门）→ 本地 == git ls-remo… |
| 10-20 13:19 | FIX-A4 收口（deep-audit-v14 ISSUE-07 + ISSUE-08）：`fix/dv14-a4-single-source` @ `571af04`，CI #2382 success，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 6 提交（ff3ae38 ISSUE-07 生产 · a0b5223 ISSUE-08 生产 · b7bd20b 出货测试 · 1147b20 门扩展 · f0b8… |
| 10-20 13:28 | FIX-B4 收口（deep-audit-1035 ISSUE-05 + ISSUE-08）：`fix/dv14-b4-remember-keys` @ `5d0d8ab`，CI #2384 success，停在分支 | 身份链三方逐字符：基线 main 76488f2 → 三提交（9af1e0c 生产+测试 / 5f921ae 门扩展 / 5d0d8ab 补 import）→ LOCAL == REMOTE == CI head_sha… |
| 10-20 14:35 | deep-audit-v14（1035）收口完成：16 工单 → 9 支，九支 + 并集 CI 全绿，未合并 main | 身份链：基线 main 76488f2d → 9 支 → 并集树 integration/dv14-1035 @ 059b63d4180d118c59028416edc5a11fa6d785b5 → 并集 CI #238… |
| 10-20 15:47 | §VERIFY-2388 真机验证收口（CLI/深链面全绿 + S34 新缺陷入册 + UI 视觉面交用户） | 身份：app 1.0.0+2388（15:11 装 = main CI #2388，含 dv14 九支并集 71882a8e）。工作区 shared/work/verify-2388/，回执已入 backlog（267→… |
| 10-20 17:53 | deep-audit-1036（v15 双报告）第二道门 + 16→8 支派发完成（2026-10-20 晚） | 输入：REPORT-A（91.8KB，A-01..08）+ REPORT-B（90.1KB，B-01..08）。基线 main 57c9619a（本地克隆自 1035 工作树，干净）。工作区 shared/work/de… |
| 10-20 19:23 | FIX-B4 收口（deep-audit-v15 B-07）：MarkdownParser 块结构解析缺口 —— `fix/dv15-b4-markdown-parser` @ `64c115a8`，CI #2392 绿，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 单提交 64c115a88687a1e5d5e24dea305bd7a70e8c5c80（3 文件 = 1 生产 + 2 既有测试；生产 +114/−11，含 KD… |
| 10-20 19:26 | FIX-B2 收口（deep-audit-1036 B-02 + B-08）：`fix/dv15-b2-settings-screens` @ `c9a9662`，CI #2393 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 2 提交（411d551 生产 5 文件 +338/−155 · c9a9662 测试 4 文件 +576）→ 本地 == 远端 == CI head_sha = … |
| 10-20 19:30 | FIX-A4 收口（deep-audit-1036 A-06+A-07）：`fix/dv15-a4-core-services` @ `59dad7c0`，CI #2390 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 两提交（c5d8e38 生产+测试 · 59dad7c 门扩展）→ CI #2390（run 39412287778，job 118592742939）succes… |
| 10-20 19:47 | FIX-A2 收口（deep-audit-1036 / dv15 A2 支）：`fix/dv15-a2-handler-guards` @ `e62e699e`，CI #39414690008 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 单提交 e62e699e5b8c0067c72711b04209f2fde527f4ed → CI run 39414690008（手动 dispatch，11:2… |
| 10-20 19:55 | FIX-A1 收口（deep-audit-1036 ISSUE A-01+A-02+A-08）：`fix/dv15-a1-cli-args` @ `f0e801b3`，CI #2396 绿，停在分支 | 身份链三方逐字符：基线 main 57c9619a8e17693f826f8050efa78ebfc8c0d3fd → 2 提交（080a408 生产 / f0e801b 测试+门）→ LOCAL == REMOTE =… |
| 10-20 20:09 | FIX-A3 收口（deep-audit-1036 ISSUE-A-05 P1）：通知台账同 id 幂等性 —— `fix/dv15-a3-notif-ledger` @ `4e5759ae`，CI #2398 绿，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 3 提交（86273fdb 生产 / 3f648851 测试 / 4e5759ae 门）→ 本地 == git ls-remote == CI #2398 head… |
| 10-20 20:52 | deep-audit-1036（v15）收口完成：16→8 支并入并集树 `7841303`，本地 108 门 + 329 夹具绿，并集 CI #2399 success（attempt 2），**未合并 main** | 身份链：基线 main 57c9619a → 8 支（A1 f0e801b3 / A2 e62e699e / A3 4e5759ae / A4 59dad7c0 / B1 dc632eb0 / B2 c9a96623 /… |
| 10-20 23:35 | deep-audit-1037（v16 波）启动：第二道门 16/16 成立 + 17 项（含 §1036-FLAKE 兑现）→ 6 支派发完成 | 输入：attachments/uploads/Audit_Report_V16_A.md（82,870 B）+ Rikkaminis_Audit_Report_V16_B.md（86,912 B）。基线 main 7c3… |
| 10-21 00:45 | FIX-3 收口（deep-audit-1037 / dv16 波 A-06+A-07+A-08）：`fix/dv16-a-provider-config` @ `97e2655c`，CI #2406 绿，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 单提交 97e2655c069a30da6032924736b36b252278e0ef → CI #2406（run 39456718375，job 118745… |
| 10-21 00:48 | FIX-1 收口（deep-audit-1037 / dv16-A 支）：A-01 宽松日期解析 + A-02 负数位置参数 + §1036-FLAKE 归档顺序 —— `fix/dv16-a-offload-hardening` @ `3db9dc778fe71f747b965124dc88c9fe3d59a127`，CI run 39457048630 **success**，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 3 提交（dcab1377 生产 / cd12a67e 测试 / 3db9dc77 门）→ 本地 == ls-remote == CI head_sha = 3db… |
| 10-21 00:59 | FIX-2 收口（deep-audit-1037 v16 波，A-03+A-04+A-05）：`fix/dv16-a-debug-modeluse-skill` @ `fe03ecb5`，CI #2411 ALL GREEN，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 4 提交（2b9309ff 生产 / 0d60f3e4 测试 / 79251553 门 / fe03ecb5 CI 编译修复）→ 本地 == ls-remote =… |
| 10-21 01:19 | FIX-5 收口（deep-audit-1037 / dv16 波 B 支）：chat 流式持久化三缺陷 —— `fix/dv16-b-chat-stream-persist` @ `d34d9928`，CI #2413 绿，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 4 提交（4e8ee68d 生产 / 272f8ffa 测试+门 / 9d7532cc 测试 re-anchor / d34d9928 fastio flake 兑… |
| 10-21 01:50 | deep-audit-1037 收口：并集 `integration/dv16` @ `660803f6` + 并集 CI #2414 success，停在分支（未合并 main） | 六支身份链三方核对（本地==ls-remote==CI head_sha）：FIX-1 3db9dc77 #2407 · FIX-2 fe03ecb5 #2411（报告落后 1 提交：CI 编译错后追了 fe03ecb … |
| 10-21 09:24 | deep-audit-1038（v17 波 / 面 B）启动：第二道门 8/8 成立（P1×6 · P2×2）+ 8 工单合并为 5 支派发 | 输入：附件 audit_report_v17_b.md（92,529 B / 540 行，面 B：UI·交互·呈现，B-01..B-08）。面 A 报告未到（用户「先把这一份处理了吧」）。基线 main 528558f4… |
| 10-21 10:01 | FIX-2 收口（deep-audit-1038 dv17 波 B-02）：冷启动播种无 remember —— `fix/dv17-2-appnav-coldstart` @ `a962fe16`，CI #2416 绿，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（a903c00 生产 / 61cb427 测试 / a962fe1 门）→ 本地 == git ls-remote == CI head_sha = a9… |
| 10-21 10:12 | FIX-3 收口（deep-audit-1038 / dv17 波 B-03）：4 处预览 WebView 宿主 AndroidView 缺数据源键 —— `fix/dv17-3-webview-androidview-keys` @ `e28b2242`，CI #2417 绿，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 4 提交（a176318d 生产 / 7b2eb60a 测试 258 行 / 2fc171a6 门扩展 / e28b2242 注释精化）→ 本地 == ls-rem… |
| 10-21 10:34 | FIX-1 收口（deep-audit-1038 / dv17 波）：风控提示无消费者 + 草稿晋升后下载记录指向已删目录 —— `fix/dv17-1-browser-risk-downloads` @ `09f46b602776a83b20fd461e8f1d93bced67733d`，CI #2419（39531036966）success，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（1c6bbbc2 生产 / 261fe80a 测试 / 09f46b60 门）→ LOCAL == ls-remote == CI head_sha = … |
| 10-21 10:38 | FIX-5 收口（deep-audit-1038 / dv17 波 B 支）：`fix/dv17-5-prefs-prime-fullscreen-bars` @ `58bc0231`，CI #2420 success，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 三段提交（10799a31 生产 +48 · 14ca6c8f 测试 +281 · 58bc0231 门 +441/−20）→ 本地 == 远端 == CI hea… |
| 10-21 10:45 | FIX-4 收口（deep-audit-1038 / dv17 波 B-04+B-05）：`fix/dv17-4-remember-keys` @ `58e71b2c`，CI #2418 绿，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（e7b8a7eb 生产 / 6684ca55 测试 re-anchor / 58e71b2c 门扩展）→ 本地 == git ls-remote == C… |
| 10-21 11:13 | deep-audit-1038 收口（v17 波 / 面 B 完成）：并集 `integration/dv17` @ `fecb66bb`，并集 CI #2421 **attempt 1 success**，停在分支 | 用户令「小任务合并」落实：8 工单 → 5 支（三类文件集两两互斥 + 同母题单一 owner）——FIX-1 B-01+B-06 / FIX-2 B-02 / FIX-3 B-03 / FIX-4 B-04+B-05（… |
| 10-21 13:52 | FIX-2 收口（deep-audit-1038 面 A / A-05+A-06）：`fix/dv18-2-rpc-exec-session-scope` @ `009fe78`，CI #2425 绿，停在分支 | 身份链三方逐字符：基线 main 10dd329e → 3 提交（584ba51 生产 / fea4cea 测试 / 009fe78 门）→ 本地 == ls-remote == CI head_sha = 009fe7… |
| 10-21 13:53 | FIX-5 收口（deep-audit-1038 / v18 波 A-08）：Kimi 自定义 baseURL 模型行全落 "Custom" —— `fix/dv18-5-custom-baseurl-provider-name` @ `ae9190c8`，CI #2426 attempt 1 success，停在分支 | 身份链三方逐字符：基线 main 10dd329e → 单提交 → 本地 == ls-remote == CI head_sha = ae9190c83249beb03d10eba06e88bbbdffcba1d9。4 … |
| 10-21 13:54 | FIX-3 收口（deep-audit-1038 / v18 波 A-02）：`--visible-only` 布尔/取值混淆 —— `fix/dv18-3-a11y-visible-only-flag` @ `ea88de826984ba231a681b650188726a614d8384`，CI #2423 success，停在分支 | 身份链三方逐字符：基线 main 10dd329e → 3 提交（72fed77 生产 / b7b836d 测试新增 / ea88de8 门扩展）→ 本地 == git ls-remote == CI head_sha … |
| 10-21 15:13 | FIX-1 收口（deep-audit-1038 / dv18 波面 A）：`fix/dv18-1-cli-arg-prescan` @ `fb87c52c`，CI #2427 attempt 1 success，停止节点达成，未合并 main | 身份链三方逐字符：基线 main 10dd329e → 3 提交（2355a29f 生产 / f692618d 测试 / fb87c52c 门）→ LOCAL == ls-remote == CI head_sha = … |
| 10-21 18:46 | fastio 权限门 BYPASS 收口（deep-audit 外单点）：分支 `fix/fastio-permission-bypass-1021` @ `81c70b68`，CI #2430 首轮 success，停在分支 | 触发：用户批「fastio 首用弹窗不对——这不是敏感权限，该像沙箱/终端一样；甚至该自动做」→ 主会话判定「批评成立、但『自动做』被否决」→ 用户令「方案 B，开始做」。 |
| 10-21 19:29 | FIX-B1 收口（deep-audit-1039 / dv19 面 B）：composer 实时源单一化 —— `fix/dv19-b1-chat-input-single-source` @ `e8bfb52b`，CI #2433 success（attempt 1），停止节点达成，未合并 main | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（e0b84e84 生产 / b9c531b9 测试 / e8bfb52b 门）→ LOCAL == git ls-remote == CI head_sh… |
| 10-21 19:31 | FIX-B3 收口（deep-audit-1039 / 面 B：B-03 + B-04 + B-08）—— `fix/dv19-b3-settings-live-subscribe` @ `63b25132`，CI run 39586968122 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（22230a93 生产 / fa727dac 测试 / 63b25132 门）→ 本地 == ls-remote == CI head_sha。7 文件 … |
| 10-21 20:07 | FIX-A2 收口（deep-audit-1039 / v19 波面 A / A-04）：customBaseURL 归一化单源 —— `fix/dv19-a2-custom-base-url-trim` @ `05acae4f`，CI #2437 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（a92ed9ba 生产 / dc346cef 测试 / 05acae4f 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-21 20:12 | FIX-A3 收口（deep-audit-1039 / dv19 波 A-05+A-06）：`fix/dv19-a3-voice-url-and-cancel` @ `0b699b18`，CI #2438 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（40dfabeb 生产 / c3afd83c 测试 / 0b699b18 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-21 20:29 | FIX-A4 收口（deep-audit-1039 / A-07+A-08 门判据盲区，零生产改动）：`fix/dv19-a4-gate-blindspots` @ `2eae5cb2`，CI #39594814613 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 2 提交（门 0c4cdcd1 / 测试 2eae5cb2）→ 本地 == git ls-remote == CI head_sha = 2eae5cb2d1d16… |
| 10-21 20:34 | FIX-B4 收口（deep-audit-1039 / dv19 波 B-05+B-07）：`fix/dv19-b4-offmain-io-and-refresh` @ `cc927dc2`，CI #2440 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 5 提交（f8d9696e 生产 / 77ecda6c 测试 / f67e05ef 门 / 3aa10bc3+cc927dc2 CI 红修复）→ 本地 == ls-… |
| 10-21 21:06 | deep-audit-1039（v18 波：A 面 8 + B 面 8）收口完成：16 工单 → 8 支全绿 → 并集 `integration/dv19` @ `ae92c12e`，并集 CI #2441 判读 8/8 ALL GREEN，**未合并 main**（用户令：合并小任务 + 分时考虑工程量） | 输入：Rikkaminis_Audit_Report.md（A 面，76,091 B）+ Rikkaminis_Audit_Report_V18_B.md（B 面，96,855 B）。基线 main 6bbbded21d… |
| 10-21 23:47 | deep-audit-1040（v19 波）启动：第二道门 16/16 成立 0 驳回 + 判级修正 1 + 报告失真 4 → 8 支派发完成（回读 8/8 送达） | 输入：Rikkaminis_Audit_Report_V19_A.md（103,018 B，A-01..A-08）+ rikkaminis_audit_report_v19_b.md（76,198 B，B-01..B-0… |
| 10-22 02:23 | FIX-A3 收口（deep-audit-1040 / dv20 波 A-03+B-02）：`fix/dv20-a3-orphan-busy` @ `1c7ec0e7`，CI #2445 判读 16/16 GREEN，**停止节点达成，未合并 main** | 身份链三方逐字符：基线 main 295cf778 → 4 提交（4a868c69 生产 / 8c28d1bb 测试 / 20ad7da4 门 / 1c7ec0e7 CI 编译错修复）→ 本地 HEAD == ls-re… |
| 10-22 03:01 | FIX-B1 收口（deep-audit-1040 / dv20 波 B-01+B-07）：`fix/dv20-b1-settings-live` @ `cb0d9136`，CI **run 2448 success**（judge 15/15），停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（1757b6bb B-01 / b0adab57 B-07 / cb0d9136 门）→ 本地 == git ls-remote == CI head_s… |
| 10-22 03:03 | FIX-B2 收口（deep-audit-1040 / dv20 波 B-04）：MCP 屏与会话 sheet 的仓/Keystore 调用回主线程 —— `fix/dv20-b2-mcp-offmain` @ `9a97895f`，CI #39645696095 success，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（34855a24 生产 / fcb54989 测试 / 9a97895f 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 03:20 | FIX-A1 收口（deep-audit-1040 / dv20 波 A-01 P0 + A-02 P1）：`fix/dv20-a1-backup-voice` @ `b0640ad1`，CI #2449 绿，停在分支 | 身份链三方逐字符一致：基线 main 295cf778 → 7 提交 → 本地 == git ls-remote == CI head_sha = b0640ad1716018b130a24b6af4aebc714231… |
| 10-22 04:00 | FIX-B4 收口（deep-audit-1040 / dv20 波 B-08 P1）：`fix/dv20-b4-backup-busy` @ `cdfa3190`，CI #2450 **12/12 ALL GREEN**，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（ca1ea19b 生产 / 6b6f8e19 测试 / cdfa3190 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 04:30 | FIX-B3 收口（deep-audit-1040 / dv20 波 B-05+B-06）：`fix/dv20-b3-render-input` @ `8420316f`，CI #2453 success，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 5 提交（77b22d01 生产 +53/−7 · 71b0a17e 测试新类 417 行 · 1fa737d2 门扩展 +420/−4 · 29d8f300+84… |
| 10-22 07:51 | deep-audit-1040（v19 波）收口完成：9 份全绿 → 并集 `integration/dv20` @ `4417999f`，并集 CI #2455 ALL GREEN，**未合并 main** | 九份构成（用户口径）：dv20 八支（a1 备份脱敏+语音成员 / a2 provider repo / a3 orphan busy / a4 挂载接线 / b1 settings live / b2 mcp offm… |
| 10-22 11:24 | deep-audit-1041（v20 波）派发完成：15 工单 → 10 支，10/10 送达回读确认，停在等回报 | 基线 main 843ab9170f7fc69e6d5777666fa46a110a96e476（ls-remote 自核一致；golden HEAD 同 SHA 干净）。输入 REPORT-A.md（A-01..A-0… |
| 10-22 12:18 | FIX-A1 收口（deep-audit-1041 / v20 波 A-01 P1）：`fix/dv21-a1-config-registry` @ `75ec3bf5`，CI #2457 success 判读 10/10，停在分支 | 缺陷：ConfigRegistry.resolveField 用 path.split('.', limit = 3) 切集合子路径，把含点的复合 id 截断成 segments[1]。复合模型 id 形态 <insta… |
| 10-22 12:18 | FIX-B1 收口（deep-audit-1041 / dv21 波 / B-01 + B-04）：`fix/dv21-b1-provider-live` @ `61b2d2ae`，CI #2458 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（d2a09830 生产 / a6db2ac0 单测 / 61b2d2ae 门）→ 本地 == ls-remote == CI head_sha = 61b… |
| 10-22 12:34 | FIX-A6 收口（deep-audit-1041 / dv21 波 · A-07 P2）：`fix/dv21-a6-open-intent` @ `ba559108`，CI #（run 39720876753）一次绿，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（db0b92d1 生产 +41/−3 · 5271928a 测试 +53/−2 · ba559108 门 +107/−4）→ 本地 == ls-remot… |
| 10-22 12:39 | FIX-B2 收口（deep-audit-1041 / dv21 波 B-02 + B-07）：`fix/dv21-b2-rootfs-log` @ `2d6b43ac`，CI #2462 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（8f0007d7 生产 / 2967fd7f 测试 / 2d6b43ac 门）→ 本地 == ls-remote == CI head_sha = 2d6… |
| 10-22 12:52 | FIX-A3 收口（deep-audit-1041 / A-03 P1）：`fix/dv21-a3-import-timestamp` @ `a52b6d7c`，CI #2464 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（87956788 生产 / 58160c4f 测试 / a52b6d7c 门）→ 本地 == ls-remote == CI head_sha = a52… |
| 10-22 13:05 | FIX-A4 收口（deep-audit-1041 / A-04 P1）：`fix/dv21-a4-gateway-effort` @ `995e15d6`，CI #2468 success，停在分支 | 缺陷本体的重新定位（本支最值钱的洞察）：REPORT-A 说「second gateway 缺 effort 真值表」——错。基线 843ab917 上 SENSEAUDIO_HOSTS(:82) / SENSEAUDI… |
| 10-22 13:05 | FIX-A2 收口（deep-audit-1041 / dv21 波 A-02 P1）：`fix/dv21-a2-empty-sweep` @ `13c8fccc`，CI #(run 39724086190) 16/16 ALL GREEN，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 4 提交（8b140806 生产 +55/−2 · dced789d 单测 · de536693 门 +216/−14 · 13c8fccc 单测补 1 条 hoo… |
| 10-22 13:09 | FIX-B4 收口（deep-audit-1041 / dv21 波 B-05+B-06+B-08）：`fix/dv21-b4-render-decode` @ `db111d34`，CI #2469 success，停在分支 | 身份链：基线 843ab917 → 4 提交（cb3717ff B-05 / 09ecc327 B-06 / 485cad2b B-08 / db111d34 B-08 CI 红修复）→ 本地 == ls-remote（… |
| 10-22 13:13 | FIX-B3 收口（deep-audit-1041 / dv21 波 B-03 P1）：`fix/dv21-b3-envvars-io` @ `a48fa300`，CI #39724689061 success，停在分支 | 身份链：基线 main 843ab917 → 3 提交（b6c707a3 生产 +197/−57 / 测试 373 行 / a48fa300 门 +281）→ 本地 == ls-remote == CI head_sha… |
| 10-22 13:36 | FIX-A5 收口（deep-audit-1041 / dv21 波 A-05 P1 + A-06 P2）：`fix/dv21-a5-modeluse-modality` @ `61d12096`，CI #2472 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 4 提交（b05728de 生产 / 1f2f45ab 测试 / cbb4e267 门 / 61d12096 CI 红修复）→ 本地 == ls-remote ==… |
| 10-22 14:46 | deep-audit-1041 收口完成：并集 `7e0e9b58` + CI #2475 ALL GREEN（含一次测试竞态修复） | 收口链路：10 支四方核对 10/10（本地==远端==CI head_sha，全 success）→ 并集 13a2c5da（61 文件 +7316/−444，逐支逐字节 61/61 一致，冻结面 0/交集 0/残留 … |
| 10-22 16:27 | F1 收口（COLLECT-R1 同族普查第 3 处）：`fix/verify-2476-rssprobe-finally` @ `3a740442`，CI #2477 success，停在分支 | 身份链：基线 main 5dd278da → 单提交 3a740442 → 本地 == ls-remote == CI head_sha = 3a7404421163fd8deef84e35e103fd00dd71a31… |
| 10-22 17:04 | deep-audit-1042（v21 波）启动：A 7 + B 7 = 14 工单 → 13 支派发完成（回读 13/13 送达） | 输入：Rikkaminis_Audit_Report_V21_A.md（90,097 B，A-01..A-07）+ Rikkaminis_Audit_Report_V21_B.md（96,879 B，B-01..B-07… |
| 10-22 18:57 | FIX-A4 收口（deep-audit-1042 / dv22 波 A-04 P1）：`fix/dv22-a4-effective-model-nonui` @ `abcf75d7`，CI run `39760667432` success，停在分支 | 身份链：基线 main 5dd278da → 3 提交（8dbbc498 生产 / 199d3aa6 测试 / abcf75d7 门）→ 本地 == ls-remote == CI head_sha = abcf75d7… |
| 10-22 19:05 | FIX-A6 收口（deep-audit-1042 / A-06 P1 剪贴板 clear）：`fix/dv22-a6-clipboard-clear` @ `a86e099e`，CI 两次绿，停在分支 | 身份链：基线 main 5dd278da → 5 提交（751eab0e 生产 / 78a61edf 测试 / 70233dab 门 / 1959c809 补日志 / a86e099e 修编译警告）→ 本地 == ls-… |
| 10-22 19:16 | FIX-B6 收口（deep-audit-1042 / dv22 波 B-07 P1）：`fix/dv22-b6-skills-browser-update` @ `7ff856d6`，CI #39764477753 success，停在分支 | 身份链三方逐字符：基线 main 5dd278da → 3 提交（1d9d5558 生产 / 0bd4d7b4 测试 / 7ff856d6 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 19:30 | FIX-A2 收口（deep-audit-1042 / A-02 P1）：`tarCreate` 顶层根循环三缺口 — `fix/dv22-a2-fastio-tar-toplevel` @ `5a144b2c`，CI #39768460593 success，停在分支 | 身份链：基线 main 5dd278da → 5 提交（生产 d4aa69cf / 测试 c573481f / 门 9535d2fc / 报告 f8b1590d + 5a144b2c）。本地 == ls-remote =… |
| 10-22 19:45 | FIX-B5 收口（deep-audit-1042 / dv22 波 B-05 P1）：`fix/dv22-b5-rootfs-progress-replay` @ `d503eeb2`，CI #39768763850 success，停在分支 | 身份链三方逐字符：基线 main 5dd278da → 3 提交（9b1a7a14 生产 +161/−21 · b0c3a5e4 测试 +216 · d503eeb2 门 +280）→ 本地 == ls-remote =… |
| 10-22 19:51 | FIX-A5 收口（deep-audit-1042 / dv22 波 A-05 P1）：`fix/dv22-a5-headless-session-ready`，分支 CI 绿，停在分支 | 身份链：基线 main 5dd278da → 8 提交（3 生产类 / 4 报告类 / 1 校正）→ 分支尖端 e00a42c6，源码树 = CI 验过的 b71265e7（git diff --name-only b7… |
| 10-23 00:30 | FIX-A6 收口（deep-audit-1043 / A-07 P1）：`fix/dv23-a6-fastio-special-files` @ `2bd96269`，CI #39811912340 success，停在分支 | 身份链：基线 main 79ef6d59 → 3 提交（生产 3efb8f7d / 测试 a8abc3f1 / 门 2bd96269）→ 本地 == ls-remote == CI head_sha = 2bd96269… |
| 10-23 00:43 | FIX-7 收口（deep-audit-1043 / B-01 P1）：Rootfs 修复结算把继承来的 `Failed` 当成本次判决 — `fix/dv23-b1-rootfs-repair-settle` @ `84e4f9ac`，CI #39813094967 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 f1425db6 / 测试 cbf2845b / 门 84e4f9ac）→ 本地 == ls-remote == CI run.head_sha =… |
| 10-23 00:54 | FIX-A4 收口（deep-audit-1043 / v22 波 A-05 P1）：`selectModel` / `uiPrompt` 冷会话窗口 — `fix/dv23-a4-headless-session-ready` @ `8cc263ae`，CI #39812861827 success，停在分支 | 身份链：基线 main 79ef6d59 → 3 提交（生产 72df9ec2 / 测试 87f4cc49 / 门 8cc263ae）→ 本地 == ls-remote == CI head_sha = 8cc263ae… |
| 10-23 00:56 | FIX-2 收口（deep-audit-1043 / A-03 P1）：`minis-model-use run` 交给 `:modelservice` 的模型未富化 — `fix/dv23-a2-modeluse-effective-model` @ `fe3f9a7c`，CI #39815679884 success，停在分支 | 身份链：基线 main 79ef6d59 → 4 提交（210912f3 生产 / a6f7eb1f 测试 / 258fff8a 门 / fe3f9a7c 首跑红修复）→ 本地 == ls-remote == CI he… |
| 10-23 01:02 | FIX-3 收口（deep-audit-1043 / v22 波 A-04 P1）：`fix/dv23-a3-thinking-ceiling-enrich` @ `6b708e44`，CI run 39815917331 / job 119954364519 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 d80a812f / 测试 0364107e / 门 6b708e44）→ 本地 == ls-remote == CI head_sha = 6b7… |
| 10-23 01:07 | FIX-9 收口（deep-audit-1043 / v22 波 B-03 P1）：`fix/dv23-b3-composer-single-writer` @ `8462e98d`，CI #2510 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 3900f9cd / 测试 a187a2a6 / 门 8462e98d）→ 本地 == ls-remote == CI head_sha = 846… |
| 10-23 01:16 | FIX-10 收口（deep-audit-1043 / v22 波 B-04+B-05 P1）：`fix/dv23-b4-browser-pool-isolation` @ `13c1fb6b`，CI #39817158221 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 2 提交（d387dc88 生产+测试+门 / 13c1fb6b 判据修复）→ 本地 == ls-remote == CI head_sha = 13c1fb6be… |
| 10-23 05:58 | FIX-8 收口（deep-audit-1043 / B-02+B-06+B-07 三条 P1）：`fix/dv23-b2-capability-single-source` @ `498eb889`，CI #39823833259 success（判读 15/15），停在分支 | 身份链：基线 main 79ef6d59 → 6 提交（d8dca1e0 B-02 / 84ab8bb6 B-06 / 90381f3c B-07 / 35e77140 报告 / d4a7ceb2 编译修复 / ee63… |
| 10-23 11:14 | deep-audit-1044 FIX-5 收口（A-06 语音错误码表错位 + A-07 TTS 按次默认值）：分支 CI 绿，停在分支 | 分支 fix/dv24-a5-speech-error-tts-defaults @ a816c23ae12ced1d6110a6a1938c1c500d63dc98（3 提交：生产 0f5bacd1 / 测试 d7a5… |
| 10-23 11:15 | FIX-1 收口（deep-audit-1044 / A-01 P1）：`fix/dv24-a1-voice-effective-model` @ `8c27fd25`（CI 绿于 `fe6ab36e`），run 39891898960 / job 120213372981 success，停在分支 | 身份链四方逐字符：基线 main f94413f5 → 3 提交（生产 4bce8f71 / 测试 67ed74b8 / 门 fe6ab36e）+ 2 个 docs 提交（af98f5a3/8c27fd25，只动 fix… |
| 10-23 11:17 | FIX-9 收口（deep-audit-1044 / B-06 **本波唯一 P0**）：`fix/dv24-b3-promote-draft-persist` @ `ed90a7f3`，CI run 39892254078 / job 120214646640 success，停在分支 | 身份链：基线 main f94413f5 → 3 提交（3df76c6f 生产 / 3dc0cf8a 测试 / ed90a7f3 门）→ 本地 == ls-remote == CI head_sha = ed90a7f3… |
| 10-23 11:24 | deep-audit-1044 FIX-10（B-07）收口：`fix/dv24-b4-browser-dispose-scope` @ `227e1576`，CI #39893017433 success，停在分支 | 缺陷：BrowserTabPool.dispose() 只释放标签页，不释放异步面 —— 两个实例自有 CoroutineScope（evictionScope/downloadScope）、downloadJobs、s… |
| 10-23 11:35 | FIX-2 收口（deep-audit-1044 / A-02 P1）：`fix/dv24-a2-terminal-ownership` @ `22b474ca`，CI run 39893791520 success，停在分支 | 身份链：基线 main f94413f5 → 2 代码提交（4ae5bf10 生产+门 / 0819d5ec 报告）+ 1 回填（22b474ca，仅 fix-reports/）。CI 绿于 0819d5ec，git d… |
| 10-23 11:37 | FIX-3 收口（deep-audit-1044 / dv24 波 A-03+A-04 P1×2）：`fix/dv24-a3-headless-send-contract` @ `8a4a38a5`，CI #2527 success，停在分支 | 身份链三方逐字符：基线 main f94413f5 → 5 提交（8f8da7f7 A-03 生产 / 4008328e A-04 生产 / f7fc1a70 测试 / 469bb7cf 门 criterion 15 /… |
| 10-23 11:43 | FIX-8 收口（deep-audit-1044 / dv24 波 B-03+B-04 P1）：`fix/dv24-b2-chat-factory-effective` @ `7c82bc98`，CI #39894373704 success，停在分支 | 身份链：基线 main f94413f5 → 5 提交（生产 0e335456 / 测试 ba3611ef / 门 3d3bf5c1 / 报告 5c32285c+ef7d9d7e / 收口 7c82bc98）。CI 绿于… |
| 10-23 11:51 | FIX-7 收口（deep-audit-1044 / dv24 波 B-01+B-02+B-05，P1×3）：`fix/dv24-b1-picker-quicktest-composer` @ `71dc0225f1`，CI #2529 (run `39894855850` / job `120222802096`) success，停在分支 | 身份链三方逐字符：基线 main f94413f5 → 5 提交（f49a541d B-01 生产 / 5ef21819 B-02 生产 / 1529ecae B-05 生产 / 5edf7021 测试 / 71dc02… |
| 10-23 11:54 | FIX-4 收口（deep-audit-1044 / v23 波 A-05 P1）：`fix/dv24-a4-config-session-writethrough` @ `90a0a064`，CI #2530 success（源码树 = `9a002abf`），停在分支 | 身份链：基线 main f94413f5 → 4 提交（b7979890 生产 / 0f5897e7 测试 / 2451a671 门 / 9a002abf→90a0a064 报告回填）。CI 绿于 9a002abf，HE… |
| 10-23 12:20 | FIX-6 收口（deep-audit-1044 / v23 波 A-08 P1）：`fix/dv24-a6-share-null-stream` @ `c810c907`，CI run 39897998963 / job 120232786685 success，停在分支 | 身份链：基线 main f94413f5 → 5 提交（07c314d0 生产 / 79126a95 测试 / 7b256666 门 / efc21523 签名修正 / 8a86cfc9+9c43065b+c810c90… |
| 10-23 12:32 | deep-audit-1044 收口末段：文档已合 main `239d0eb4`，代码并集 `f842dd13` 待放行 | 文档先行已完成：rebuild_dev_history.py → 2166 条 / 82 天 / 39391 行；脱敏三轮（主 + INDEX + SAGAS）Remaining sensitive: NONE；SAGA… |
| 10-23 12:59 | deep-audit-1044（v23 波）全批闭环：main `1d44ec90` + main CI #2534 success（Publish 真执行，v1.0.0+2534）→ 清理 12/12 分支 + 10/10 worktree | 用户令「按流程走」= 放行合并（承接上一轮已完成的收口证据）。 |
| 10-23 14:20 | deep-audit-1045（v24 波）启动：第二道门 15/15 成立 0 驳回 + P0×1 + 失真 2 → 11 支派发完成（回读 11/11 在跑） | 输入：Rikkaminis_Audit_Report_V24_A.md（78,946 B，A-01..A-07）+ V24_B.md（102,201 B，B-01..B-08）。基线 main 1d44ec90（ls-r… |
| 10-23 15:15 | FIX-1 收口（deep-audit-1045 / v24 波 A-01 P1）：MCP OAuth 配置读侧不认客机 CLI 的 camelCase — `fix/dv25-a1-mcp-oauth-keycase` @ `46b059be`，CI #2535 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec90 → 3 提交（6bb83a20 生产 / 143a88be 测试 / 46b059be 门）；无报告提交（本波 FIX-1..11 与上波 FIX-1..10 重号，入… |
| 10-23 15:27 | deep-audit-1045 / FIX-8（B-04 P1）收口：分支 `fix/dv25-b4-picker-member-resolve` @ `95d84870`，CI #2537 success，停在分支 | 缺陷：模型选择器群组头部解析「选中群组的 active entry」用落库裸 find，同文件成员解析用单源 SystemVoiceEntries.resolveIn ⇒ 默认 Voice 组（只由 System 哨兵组… |
| 10-23 15:34 | FIX-11 收口（deep-audit-1045 / v24 波 B-07 P1）：`fix/dv25-b7-saf-bounded-read` @ `17bb3f22`，CI #2538 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec90 → 4 提交（47780589 生产 / 74902651 测试 / 4956fbe0 门 / 17bb3f22 注释可扫描性修正）→ 本地 == ls-remote … |
| 10-23 15:39 | FIX-4 收口（deep-audit-1045 / v24 波 A-07 P1）：`fix/dv25-a4-player-json-contract` @ `4a0b6fa6`，CI #2545 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec905b8f5d21a215694e9a13910c74e1f645 → 1 提交（生产 + 测试 + 门）→ 本地 HEAD == git ls-remote == CI … |
| 10-23 15:42 | FIX-7 收口（deep-audit-1045 / v24 波 B-03 P1）：`fix/dv25-b3-storage-io-offmain` @ `d4d6886c`，CI #2541 success，停在分支 | 身份链：基线 main 1d44ec90 → 5 提交（f9811f46 生产 / 6e42e12f 测试 / 44f06cd9 门 / d4d6886c 门+测试修盲区 / 9b6df492+2542cf18 报告）。… |
| 10-23 15:42 | FIX-10 收口（deep-audit-1045 / v24 波 B-06 P1）：`fix/dv25-b6-mcps-merge-guard` @ `4b083c74`，CI #2543 success，停在分支 | 身份链：基线 main 1d44ec90 → 4 提交（bd574457 生产 / 0bbda65e 测试 / ea800a0a 门 / 4b083c74 报告）。CI 绿于 ea800a0a，HEAD 4b083c74… |
| 10-23 15:47 | FIX-5 收口（deep-audit-1045 / v24 波 B-01 P1）：`fix/dv25-b1-mirror-autodetect-ownership` @ `665e9b21`，CI #2540 success（head_sha `6e929cd3` 三方一致），停在分支 | 缺陷：autoDetectOnceIfNeeded（首启 rootfs.freshInstall 自动测速）是裸 scope.launch（基线 :478），Job 从未进字段 ⇒ runTest/runAllTests… |
| 10-23 15:48 | FIX-9 收口（deep-audit-1045 / dv25 波 B-08 P0 + B-05 P1）：`fix/dv25-b5-fastmode-promote-draft` @ `97642c3f`，CI #2544 (run 39917029875 / job 120293005852) success，停在分支 | 身份链：基线 main 1d44ec90 → 6 提交（b1d00555 B-08 生产 / dfe6e9e3 B-05 生产 / c930e94a 测试 / ad8e33de 门 / a8f34b11 失败恢复收紧 =… |
| 10-23 16:08 | deep-audit-1045 / FIX-6 支（B-02 P1）收口：分支 `fix/dv25-b2-webview-lifecycle` CI #2550 绿，停在分支（未合并 main） | 身份链：基线 main 1d44ec90 → 6 提交（34aa5aba 生产 / dd70f0ca 测试 / 0635d5a2 门 / 9d6f1dca 报告 / 42686631 修新测试编译警告 / ffd7cc0… |
| 10-23 17:43 | deep-audit-1045（v24 波）全批闭环：main `381d1026` + main CI #2553 success（Publish 真执行，v1.0.0+2553）→ 清理 14/14 分支 + 14/14 worktree | 用户在收口中两次干预：①「怎么分支又一次跑了，不是应该并入吗？」——点破我为 2 条测试文件编译警告（user.dir 可空性，P2）不必要地重跑了一轮并集 CI（装置比例失当；正确做法是登记不修，或合并后由 main … |
| 10-23 18:48 | deep-audit-1046 启动（v24-S 单路报告，8 支派发完成） | 输入：Rikkaminis_Audit_Report_V24_S.md（127,225 B / 849 行，S-01..S-11）。报告自报基线 main 1d44ec90。 |
| 10-23 20:10 | FIX-G10 收口（deep-audit-1046 / S-10 P1）：`fix/dv26-g10-tts-cancel-guard` @ `eb7b9edd`，CI #39947618231 success，停在分支 | 身份链：基线 main f7aba218 → 73215401 生产+测试 / 188858b6 门（CI 绿于此）/ eb7b9edd 报告。git diff --name-only 188858b6 HEAD -- … |
| 10-23 20:12 | FIX-B3 收口（deep-audit-1046 / dv26 波 S-03 P1）：`fix/dv26-b3-anthropic-thinking-budget` @ `db3de4da`，CI #2559 success，停在分支 | 身份链：基线 main f7aba218 → 4 提交（8e3d361d 生产 / 22cb622a 测试 / c2c86207 门 = CI 验过的树 / db3de4da 报告）。git diff --name-on… |
| 10-23 20:17 | FIX-A2 收口（deep-audit-1046 / S-02 P1）：`fix/dv26-a2-oauth-bridge-refresh` @ `8eb3a837`，CI #2562 success，停在分支 | 身份链：基线 main f7aba218 → 3 提交（d95f41c3 生产+测试+门 / e35408cb 报告 / 8eb3a837 补 L4）。CI 绿于 d95f41c3，报告提交后源码树等价（git diff… |
| 10-23 20:33 | FIX-D7 收口（deep-audit-1046 / S-07 P1）：`fix/dv26-d7-webview-release-latch`，CI 两次绿，停在分支 | 缺陷：KaTeXView.kt:390-393 与 FilePreviewScreen.kt:490-494 的 AndroidView.onRelease 都调 wv.removeCallbacks(null)——AO… |
| 10-23 20:51 | FIX-F9 收口（deep-audit-1046 / S-09 P1）：`fix/dv26-f9-intent-scheme-sanitize` @ `e97f77f9`，CI #39953818482 success，停在分支 | 身份链：基线 main f7aba218 → 5 提交（57203319 生产+门+测试 8 文件 +847/−95 / d552a8c0 测试名去点 / 0e74c57f KDoc 措辞 / 1843c1ca 可空性警… |
| 10-23 21:24 | deep-audit-1046 全批收口：8 支 CI 绿 → 并集 `integration/dv26` @ `bea3525b`，并集 CI #39957962219 全绿（111 门 + 340 夹具），未合并 main（起手权在用户） | 8 支 CI 绿（全 attempt 1，唯一 skipped=Publish，SRC_EQ_GREEN=True）：A2 39949090022 / B3 39947709325 / C6 39945776622 / … |
| 10-23 21:53 | deep-audit-1046 全批闭环：main `42af0ae6`（+ docs `88ef9b27`）+ main CI #39961698711 success（skipped=[]，Publish 真执行，v1.0.0+2567）→ 分支/worktree 清零 | 收口链：8/8 分支 CI 绿（attempt 1，唯一 skipped=Publish）→ 并集 integration/dv26 @ bea3525b（8 次 --no-ff 零冲突，49 文件 +8501/−522… |
| 10-23 23:45 | deep-audit-1047（v25-A/B 波）启动：第二道门 15/15 成立，判级修正 3 处，9 支派发完成（回读 9/9 送达） | 基线 main 88ef9b27（报告写 42af0ae6 = 父提交，src/+scripts/ 逐字节一致）。工作区 shared/work/deep-audit-1047/（REPORT-A/B、TRIAGE、CO… |
| 10-24 00:58 | FIX-A5 收口（deep-audit-1047 / A-07 P1）：5 个 offload handler 的 `--help` 恒 exit 2 | 身份链：基线 main 88ef9b27 → 5ec73d8f（生产 5 文件 +15/−5）→ 77248c41（测试 +78）→ dba13f46（门 +406/−2，CI 绿于此）→ f57111ce + 4199… |
| 10-24 01:23 | FIX-B3 收口（deep-audit-1047 / B-05 P1）：模型组详情屏系统语音三处断层 — `fix/dv27-b3-model-group-system-voice` @ `8feb5466`（CI 绿于 `77ba33fc`），停在分支 | 缺陷（真树 4 处，报告报 3 处）：ModelGroupDetailScreen.kt 对 SystemVoiceEntries（默认 Voice Input/Output 组的全部成员，虚拟、无 instance、不… |
| 10-24 01:51 | deep-audit-1047 B1 收口（dv27 波 B-01+B-02+B-03 P1）：`fix/dv27-b1-preview-export-guards` @ `5bd22bfa`，CI #2574 success，停在分支 | 身份链：基线 main 88ef9b27 → 5 提交（24d9026e 生产 / 4ee6166c 测试 / 62b5212d 门 / 6825f9fd 报告 / 548354d4 警告修复 / 5bd22bfa L4… |
| 10-24 02:08 | deep-audit-1047 A1 收口（dv27 A-01+A-03）：`fix/dv27-a1-provider-wire-parity`，CI 绿，停在分支 | 身份链：基线 88ef9b27 → 4 提交（eeff5770 生产+门+测试 / 2878a229 修源形状测试+报告 / 1d7ef15e 清编译警告 / 67066ce3 报告补证据）→ 绿 run 3999775… |
| 10-24 02:49 | FIX-A2 收口（deep-audit-1047 / A-02 P1）：`fix/dv27-a2-modelsvc-thinking-level` @ `2fc80db6`，CI #2578 success，停在分支 | 缺陷：ModelExecutionDispatcher.buildRequestJson 对非 OFF 档位无条件 put("thinking_level", level.name)，但 worker 只有流式路径解析；… |
| 10-24 05:39 | FIX-A3 收口（deep-audit-1047 / A-05+A-06）：`fix/dv27-a3-mcp-secret-handoff` @ `45b14121`，CI #40019485653 success，停在分支 | 缺陷（A-05）：客侧 CLI 写不了加密存储，只能把 MCP 客户端密钥落 <server>.secret 等宿主导入；全仓只有编辑弹窗一处调 importPendingClientSecret ⇒ 另两个读者（rec… |
| 10-24 06:38 | deep-audit-1047 收口：9 支全绿 → 并集 `integration/dv27` @ `e4ac1240` 并集 CI 全绿（111 门 / 342 夹具）→ docs 已合 main `7263dbee`，代码并集待用户放行 | 9 支回报核对：本地 HEAD == 远端 sha == 绿 run head_sha 9/9 逐字符；<绿sha>..HEAD 差异仅 fix-reports/；scan.sh/test_scan.py 冻结面 9/9… |
| 10-24 08:48 | deep-audit-1048（v26-A/B 波）启动：第二道门 16/16 成立、A-04 下调 P2、9 支派发完成（回读 9/9 送达） | 输入：Rikkaminis_Audit_Report_V26_A.md（8 条，A-01..A-08）+ _B.md（8 条，B-01..B-08）。基线 eea9f407（报告声明值与 git ls-remote 实测… |
| 10-24 09:47 | FIX-A6 收口（deep-audit-1048 / A-06 P1）：`fix/dv28-a6-contacts-max-args` @ `6f906d67`（代码 `97e5da76`），CI #40049408945 success，停在分支 | 缺陷（一个形状四条路径）：--max / 旧别名 -n 的解析把「拼了但用不了」折叠成「没拼」——①--max abc 走 getInt("max") ⇒ null ⇒ 默认页 50/20、exit 0；②search … |
| 10-24 09:56 | FIX-A1 收口（deep-audit-1048 / A-01+A-08 P1）：`fix/dv28-a1-openai-toolcall-eof` @ `c17f0200`，CI #40050571513 success，停在分支 | 身份链：基线 eea9f407 → 4 提交（生产 a6d5cd21 +127/−10 / 测试 1d9ac1cf +222 / 门 07159efe +231/−34 = CI 绿 sha / 报告 c17f0200）… |
| 10-24 10:01 | FIX-A7 收口（deep-audit-1048 / A-07 P1）：`fix/dv28-a7-headless-stale-assistant` @ `3e53b19a`，CI #40050292104 success，停在分支 | 缺陷：HeadlessChatRunner 三个结算入口（prompt:428 / retry:545 / rerunFromToolBlock:664）用 msgs.lastOrNull { role == "assi… |
| 10-24 10:02 | dv28-FIX-B3 收口（deep-audit-1048 / B-08 P1）：`ChatViewModel.resume()` claim 时序 + setup shell 未持有，`fix/dv28-b3-resume-claim-timing` @ `e34114bd`，CI #40050946272 success，停在分支 | 缺陷（两半同源）：resume() 的 _isStreaming claim 在 :4227，位于 :4221 viewModelScope.launch 之内、systemPromptForSession()（实体 :… |
| 10-24 10:12 | FIX-A5 收口（deep-audit-1048 / A-05 P1）：Stage 9 覆盖 Stage 7 的 MCP 合并结果 — `fix/dv28-a5-mcp-restore-stage9` @ `8122ca84`，CI #40051341292 success，停在分支 | 缺陷：导出照打 mcp/servers.json；Stage 7 用 mcpRepo.importJSON( 合并（本机独有服务器保留、save() 落盘）；Stage 9 解压时 destByPrefix["mcp"]… |
| 10-24 10:36 | FIX-B2 收口（deep-audit-1048 / B-07 P1）：`fix/dv28-b2-model-picker-single-source` @ `57a3b9c2`，CI `40053325774` success 且干净，停在分支 | 缺陷：①ChatModelPickerSheet.kt 私有折叠集合 remember(config){ config.instances.filter{ it.isEnabled }… } = 第三份副本，且键派生自用… |
| 10-24 10:40 | FIX-B1 收口（deep-audit-1048 / UI 交接五条 B-01·B-03·B-04·B-05·B-06）：`fix/dv28-b1-ui-handoff` @ `f182c766`，CI #2594 success，停在分支 | 身份链：基线 eea9f407 → 5 提交（99322a05 生产 10 文件 / 89c63058 四门扩展 / 0e7c0228 多删大括号 / 3c200099 ArchiveEntry 提升 / f182c76… |
| 10-24 11:20 | deep-audit-1048（v26-A/B 波）收口前状态：9/9 支 CI 绿 → 并集 `integration/dv28` @ `7275c81e` 并集 CI #40056047721 全绿 → 文档已自合 main `d7e2493e`，**代码并集待用户放行** | 9 支 CI（全 attempt 1，唯一 skipped=Publish=main 门控）：a1 40050571513 #2586 · a2 40051049467 #2589 · a3 40050931869 #2… |
| 10-24 14:37 | dv29-B4 收口（浏览器会话视口未重放，B-04）：`fix/dv29-b4-browser-viewport-reset` @ `fb6f6321`，分支 CI #2599 绿，停在分支 | 身份链：基线 main b82aa8dc → 4 提交（bbae0ada 生产 +74/−14 · 29bb5ded 测试 +317/−8 · 55d898d8 门 +315/−2 ← CI 绿于此 · fb6f6321… |
| 10-24 14:40 | dv29 FIX-A1 收口（Anthropic 工具截断计数器）—— 2026-10-24 14:5x | 分支 fix/dv29-a1-anthropic-tool-truncation @ 绿 sha 828ff4ee（报告回填至 51895a70，源码树等价 0 diff）· CI run 40069749029 job… |
| 10-24 14:40 | FIX-A3 收口（deep-audit-1049 / dv29-A3）：offload 分页单源契约 + market fallback — `/var/minis/shared/work/deep-audit-1049/a3/fix-reports/dv29-FIX-A3.md` | 身份链：基线 main b82aa8dc → 分支 fix/dv29-a3-offload-page-size，db7c1dff 生产 → 0ee8dc6d 测试 → 39886a34 门 → 7a7a40a3（CI 绿… |
| 10-24 14:58 | FIX-B3 收口（deep-audit-1049 / dv29 波 B-03·B-05·B-06，全 P1）：`fix/dv29-b3-share-print-composer` @ `0f6e6ba4`，CI #2605 success，停在分支 | 身份链：基线 main b82aa8dc → 4 提交（c5f211bc 生产 8 文件 +1008/−58 / ed2405d0 测试 / a183d691 门 3 文件 ← CI 绿于此 / 419ff840+0f6… |
| 10-24 15:05 | FIX-A2 收口（deep-audit-1049 / dv29 波 A-02+A-03+A-04，含 1048 遗留 V-1）：`fix/dv29-a2-headless-binding-and-fk-crash`，分支 CI 绿 @ `0d0b7c80`，停在分支 | 范围（4 条 3 文件生产 + 4 文件测试 + 2 道既有门扩展，0 新建门 ⇒ 门 111 / 夹具 342 双不变） |
| 10-24 15:08 | dv29-B1 收口（回合入口认领壳 + 撤销守卫）—— 2026-10-24 午后 | 结果：fix/dv29-b1-turn-entry-claim-shell @ 源码绿 sha 0609443a（HEAD 0c68ea17 仅报告），CI run 40071461073 / job 120805599… |
| 10-24 15:16 | FIX-B2 收口（deep-audit-1049 / B-02 P1）：`fix/dv29-b2-offmain-compose-state` @ `82373a43`，CI #40071869246 success，停在分支 | 缺陷：4 个写点（报告说 3 处，实为 4 —— 画廊两个标志各一处）在后台线程直写 Compose remember{mutableStateOf} 状态：ImageGalleryViewer.kt:324/360（a… |
| 10-24 15:18 | deep-audit-1049 / dv29-A4 收口：外部写盘到不了运行中的消费者（prefs 写后送达）—— 2026-10-24 15:2x | 分支 fix/dv29-a4-prefs-write-through @ 03840e3f（4 提交：生产 4d41f8d5 / 测试 686de04f / 门 b073e894 / 报告 03840e3f），CI ru… |
| 10-24 15:57 | deep-audit-1049（v27-A/B 波 + 真机 P1 合并）收口前状态：8 支 CI 绿 → 并集 `integration/dv29` @ `3a051b46` 并集 CI 全绿，文档已自合 main `6c7c4636`，**代码并集待用户放行** | 输入：v27-A（A-01..A-07）+ v27-B（B-01..B-06）+ 真机验证抓到的两条 P1（§2597-VERIFY-2 R4 prefs 写盘不生效 / §2597-VERIFY-3 不存在会话崩进程）… |
| 10-24 16:18 | dv29 批次已闭环：main `6576c7fa` + main CI `40076239617` success（skipped=[]，Publish 真执行）→ 发布 v1.0.0+2612 → 分支/worktree 清理 100% | 合并链：用户「合并」放行 → docs 工作树 main 6c7c4636 --no-ff 合 integration/dv29 @ 3a051b46 → main 6576c7fa（合并树 vs 并集树 src+scr… |
| 10-24 20:01 | feat/auto-approve-mode 合并收口（2026-10-24 晚，用户「合并吧，不放心的做准备」）→ main `20a9fbad` + CI #2618 绿 + v1.0.0+2618 | 收口链：分支 c51f8652（含报告提交，未推远端；源码树相对 CI 绿 42cc8b73 仅 fix-reports/）→ --no-ff 合 main（父 91bd879b）→ main 20a9fbad → pu… |
| 10-24 20:53 | 元审计收口已交接：报告回来后不用原对话框（2026-10-24 晚） | 用户说明：报告约 1h+ 出，届时可能不在本会话 ⇒ 已备好自包含接手包，新会话无需本对话上下文。 |
| 10-24 20:59 | 元审计收口改成「拖报告即接手」——落地为新技能 meta-audit-witness（2026-10-24 晚，用户纠正） | 用户纠正：上一环的理想形态是「报告出来 → 我把两份报告发给新对话 → 它自然知道按流程走」。不需要路径、不需要交接文件、不需要额外指令。我先前那套「读 HANDOFF.md」的方案是错的形式。 |
| 10-24 22:38 | FIX-META 支 M2（binding）收口：两门从「词共存」改到「集合/身份」 | 分支 fix/meta-audit-m2-binding @ CI 绿 0fcb8505（run 40102170254 / job 120896393210 / attempt 1 / 112-0 / ALL 344 … |
| 10-24 22:43 | M5 三见证收口：门失效三轴 = order / dataflow / spelling（2026-10-24 夜） | 分支 fix/meta-audit-m5-scope-order @ f64914d2（报告 d06a8db3），基线 main 20a9fbad，CI #2622 success（head_sha 逐字符一致，112 … |
| 10-24 23:03 | meta-audit-1024 M1 收口（dataflow 轴两门见证加固）—— 2026-10-24 | 分支 fix/meta-audit-m1-guard-consume @ 6a0543ce（源码树 = CI 绿树 cfef9224，git diff --name-only cfef9224 HEAD -- . ':(… |
| 10-24 23:07 | M6（offload_host_path 门见证）收口：R1b 表达式实参 + R7「消毒必须发生」— 2026-10-24 夜 | 分支 fix/meta-audit-m6-taint（wt-m6，基线 main 20a9fbad）→ HEAD 141bc739；CI run #2626 / id 40103957426 / job 12090166… |
| 10-24 23:54 | META-AUDIT-1024 第一波收口：12/12 门全被证伪且全部补强（2026-10-24 晚） | 判决：外部对抗审计（Google 闭源，2 席位）对 12 道 CI 门做「能过门但重新引入缺陷」最小补丁检验 → 12/12 GATE_BLIND_CONFIRMED，机械复证全过、restore 全 True、主克隆… |
| 10-25 00:21 | META-AUDIT-1024 第一波合并收口：main `564c8f3e` + main CI `40109463245` success（Publish 真执行）→ 分支/worktree 清零（2026-10-24 晚） | 合并链：用户放行「合并吧」→ 三项核查（diff 逐文件已核 / 本地 112/0 + ALL 344 + CI sha 三方一致 / 三源取二 = 机械复证 12/12 CAUGHT + CI + 本地全量）→ mai… |
| 10-25 05:32 | META-AUDIT wide-1024 第二波启动：50 门宽扫 157 条门瞎实锤，12 支派发（2026-10-25 凌晨） | 判决（机械复证 209 条尝试）：157 GATE_BLIND_CONFIRMED / 48 CAUGHT_VERIFIED / 3 GATE_CAUGHT_WITNESS（自称 FALSIFIED 却被门抓住 = 见证… |
| 10-25 05:56 | wide-1024 支 05（chat-session 三门）收口：10/10 见证 CAUGHT，CI #2633 绿，停分支 | 分支 fix/meta-wide-05-chat-session（基线 main 564c8f3e）。CI 绿 sha 4677e428（run 40134913784 / job 120994279978 / atte… |
| 10-25 05:58 | wide-1024 支 09-browser-webview 收口：5 门见证加固，16/16 全抓住，CI #2632 绿（2026-10-25 早） | 分支 fix/meta-wide-09-browser-webview @ 5f384731（源码树），报告提交 1059156e（fix-reports/ 5 份，源码与绿 sha 等价：git diff --name… |
| 10-25 06:01 | wide-1024 12-misc-b 收口：六门见证加固 20/20 CAUGHT（分支 `fix/meta-wide-12-misc-b` @ `7ef7291a`，CI #2634 run `40134937808` success，停分支） | 输入：任务书 /var/minis/shared/work/meta-wide/task-12-misc-b.md（6 门 / 20 条门瞎见证）。基线 564c8f3e。 |
| 10-25 06:12 | wide-1024 支 07（mcp-tools 三门）收口：CI 绿，停分支 — 2026-10-25 | 分支 fix/meta-wide-07-mcp-tools @ d1a90931（判据+臂）→ 49552874（报告回填，源码树等价 0 diff）。基线 564c8f3e。CI run 40135996652（#26… |
| 10-25 06:13 | wide-1024 支 04-offload 收口：offload 三门 11 条见证全被挡住（2026-10-25 早） | 分支 fix/meta-wide-04-offload，代码提交 23fd18ec（基线 564c8f3e）+ 报告提交 9fbf798f（tip，仅 3 份 fix-reports，源码树与 23fd18ec 逐字节一… |
| 10-25 06:13 | wide-1024 支03（五道 provider 门）收口：17 条见证 PRE=BLIND → POST=CAUGHT，CI #2631 绿，停分支 | 分支 fix/meta-wide-03-provider-registry @ 7cdb750c（基线 main 564c8f3e；2 提交：d29cae00 五门加固 + 7cdb750c 报告），worktree s… |
| 10-25 06:22 | wide-1024 支 02（provider 对称门）收口：2 门 6 见证全抓，CI #2640 绿，停分支 | 分支 fix/meta-wide-02-provider-symmetry @ 6ff65aad（CI 绿树 02aad4c5，报告回填后 git diff 02aad4c5 HEAD -- . ':(exclude)f… |
| 10-25 06:23 | wide-1024 支 11-misc-a 收口：5 道 CI 门判据加固，15/15 见证反攻 CAUGHT，分支 CI #2639 绿（2026-10-25 凌晨） | 分支 fix/meta-wide-11-misc-a → d6911e89（门+报告）+ 67761d40（报告回填 CI），已推远端，停在分支未合并 main（任务书明令）。基线 main 564c8f3e。生产代码零… |
| 10-25 06:35 | wide-1024 第 08 支收口：文件预览/URL/URI/分享/tar 五门见证加固（fix/meta-wide-08-file-preview @ 8b92eeee，CI #2641 success，停分支） | 基线 564c8f3e（main）。零生产代码改动，5 门 938+/47−，2 提交（5273a821 门 + 8b92eeee 报告）。 |
| 10-25 06:36 | wide-1024 支 10（4 门 12 见证）收口：分支 `fix/meta-wide-10-skill-backup-compact` @ `96ad4ab0`，CI #2643 / run `40137834021` success（head_sha 逐字符一致） | 12/12 见证反攻通过（修复前 rc=0 → 修复后 rc=1，restore=True 全部 sha256 一致）： |
| 10-25 07:19 | META-AUDIT wide-1024 收口：48 门全部加固，原见证反攻 154/154 抓住，并集 `ca1920b2` CI 绿（2026-10-25） | 12 支全部完成（会话子代理）：48 门 + 标杆 1 门 = 49 个门脚本 +16297/−530（99 文件，含 50 份报告）。 |
| 10-25 09:04 | wide-1024 全流程收口：main `6b6c918f` + main CI #2645 绿（Publish 真执行），分支/worktree 清零 —— 2026-10-25 | 合并链：用户「合并」放行 → 三项核查已过 → main worktree（deep-audit-1049/repo，auto-approve/repo detached @ 564c8f3e）--no-ff 合 ca1… |
| 10-25 12:15 | wide3-03 支（TASK-03-compact-loop）收口：4 门 14 见证反攻 14/14 CAUGHT，分支 CI #2646 绿，停分支（2026-10-25） | 分支 fix/meta-wide3-03-compact-loop（wt-03，基线 main 6b6c918f）；CI 绿树 f8ae7f6e（run #2646 / id 40160005688 / job 1210… |
| 10-25 12:19 | wide3-12 收口：4 门 12 见证全部加固，分支 `fix/meta-wide3-12-media-mount-trace` @ `1e3b9ca2`，CI #2648 绿（停分支） | 任务 TASK-12（meta-wide3 第三批 / 组 B）：把 4 道门的判据从「某物存在」升级到「该物的效果真的达成」，12 条见证全部固化成 --selftest 负向臂。生产代码 0 改动，scan.sh/t… |
| 10-25 12:27 | wide3-07（UI 交互四门）收口：13/13 见证反攻 CAUGHT，分支 CI #2649 绿，停分支 —— 2026-10-25 | 分支 fix/meta-wide3-07-ui-interaction，基线 main 6b6c918f。只改 4 个门脚本（ime_inset_guard / tool_title_error_branch_check… |
| 10-25 12:39 | wide3 支 04（loop/turn/flow/trace 四门）收口：13/13 见证 CAUGHT，分支 CI #2654 绿（2026-10-25） | 分支 fix/meta-wide3-04-loop-turn @ a5f6d93a（门+报告 3a2968f9 / 报告 ed63130d / 回填 a5f6d93a），基线 main 6b6c918f。生产代码 0 行… |
| 10-25 12:40 | META-WIDE3 支 08 收口：skill/backup 四门判据加固，12/12 原见证反攻 CAUGHT，分支 CI #2653 绿（2026-10-25） | 分支 fix/meta-wide3-08-skill-backup（基线 main 6b6c918f）→ 892474eb（门）+ d3f0bfb6（报告）+ 53cd28fb（CI 回填），已推远端，停分支不合并 ma… |
| 10-25 12:48 | WIDE3-06 收口：浏览器/webapp/KaTeX 四门见证加固（分支 `fix/meta-wide3-06-browser-webapp` @ `687daa83`，CI #2656 绿，停分支） | 基线 main 6b6c918f。4 门 × 3~4 条外部见证，修复前 14/14 全绿（门瞎）→ 修复后 14/14 CAUGHT（shared/work/meta-wide3/reattack.py 逐条 appl… |
| 10-25 12:58 | wide3-01 provider-a 收口：4 门 14 见证全抓住，分支 CI #2655 绿（2026-10-25） | 分支 fix/meta-wide3-01-provider-a @ 5674f3a6（代码提交 5ad1226b = CI 绿树，报告回填后 diff --name-only 只含报告）。基线 main 6b6c918f… |
| 10-25 13:33 | wide3 支 02（provider-b，4 门 12 见证）收口：分支 `fix/meta-wide3-02-provider-b` @ `a97a3dd4`，CI #40164642837 success（attempt 1，head_sha 逐字符一致），停分支 | 12/12 见证反攻 CAUGHT（修复前 12/12 STILL_BLIND；装置 probe/wit.py + probe/run_reattack.sh，restore_ok=True 全部）： |
| 10-25 14:57 | wide3 第三波门见证收口：49/50 门被证伪并全部补强，并集 `19e70dab` CI #2661 绿（2026-10-25） | 输入：A/B 两份报告（各 363KB / 25 门 × 4 尝试 = 200 条）。基线 main 6b6c918f。 |
| 10-25 17:33 | WIDE4-08 收口：exec_gate / truncated_call 两门 10 条见证全部加固，12/12 反攻 CAUGHT，分支 CI #2666 绿（2026-10-25） | 分支 fix/wide4-08-execgate-truncated @ ea4a6512（门 63e4f883 → 文案/臂 395630e6 → 报告 ea4a6512），基线 dbab7a3e（wide4 共享原语… |
| 10-25 17:34 | WIDE4-07 收口：gemini_base_version_check 五条见证全补强，分支 CI #40179534916 绿（2026-10-25 傍晚） | 分支 fix/wide4-07-gemini-base（基线 19e70dab + 共享模块 dbab7a3e）→ 代码 4fa606d7 / 报告 fbfd6643 / 回填 bf09b3ac。生产代码 0 行，sca… |
| 10-25 17:36 | WIDE4-06 收口：scheduled_store_release_guard 五轴加固，7/7 反攻 CAUGHT，分支 CI #2663 绿（2026-10-25） | 分支 fix/wide4-06-sched-store（基线 main 19e70dab + wide4 共享原语 dbab7a3e）→ 代码提交 4e1084b7（+387/−75，仅 1 文件）+ 报告回填 fa92… |
| 10-25 17:46 | WIDE4-10 收口：debug_leak_guard / legacy_pipeline_guard 判据加固（分支 `fix/wide4-10-debugleak-legacy` @ `c6ff4d82`，CI #2669 绿，停分支） | 基线 dbab7a3e（= main 19e70dab + 共享 Kotlin 词法/语法原语）。只改两个门脚本（+713/−248）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotli… |
| 10-25 18:06 | wide4 支 12 收口：four_way + skill_import 两门判据加固（分支 `fix/wide4-12-fourway-skillimport`，CI #2671 绿，停分支） | 基线 19e70dab（worktree wt-12，基分支含共享 _kotlin_lexer/_kotlin_ast）。改动面：2 个门脚本（+1080/−166）+ 1 份报告；生产代码 0 行、scan.sh/te… |
| 10-25 18:16 | WIDE4-05 收口：IME-inset 门 6 条见证全数反攻，分支 `fix/wide4-05-ime-inset` @ `372baa29`，CI #40182328104 绿，停分支 | 基线 dbab7a3e（= 19e70dab + 两个共享 Kotlin 原语模块）。只改 1 个门脚本（scripts/scan/ime_inset_guard.py +423/−156），生产 .kt 0 行、sca… |
| 10-25 18:19 | WIDE4-11 收口：`backup_remap_rollup_check` 5 条盲轴修正向判据（+23 臂 / 55 臂），分支 `fix/wide4-11-backup-remap` @ `b6e405ce`，CI #2672 绿，停分支 | 基线 dbab7a3e（= 19e70dab + 两个共享模块）。生产代码 0 行改动，只改本门 + 报告；scan.sh/test_scan.py/_kotlin_lexer.py/_kotlin_ast.py 零触碰… |
| 10-25 18:28 | wide4-04 收口：三门判据从「拼写存在」改到「效果达成」，19 见证 13 新抓 + 6 保持抓，分支 CI #2674 绿（2026-10-25） | 分支 fix/wide4-04-skillfile-loopstat-provider @ 6422102e（代码提交 9cf49582 = CI 绿树，报告回填后 diff --name-only 9cf49582..… |
| 10-25 18:31 | WIDE4-02 收口：katex/budget/liveness 三门见证加固（分支 `fix/wide4-02-katex-budget-liveness` @ `9697a52e`，CI #40183453003 绿，停分支） | 基线 dbab7a3e（= 19e70dab + 共享原语 _kotlin_lexer.py/_kotlin_ast.py）。门提交 c35d62a4（3 门 +919/−325，生产 .kt 0 行改动）+ 报告 96… |
| 10-25 20:11 | META-WIDE4 第三波门见证收口：24 门 110 条见证全被证伪并补强，并集 `52b1fdd8` CI #40189827049 绿（2026-10-25） | 输入：A/B 两份外部报告（225/260 KB，24 门 × 4-5 尝试 = 146 条见证）。基线 main 19e70dab（= wide3 并集，仍未合 main）。 |
| 10-25 20:55 | wide4 合并 main 完成：main `6ac77c6b2` + main CI #40192845792 全绿 + 发布 v1.0.0+2678 → 分支/worktree 清零（2026-10-25 12:5x） | 用户「合并」放行 → 三项核查 → --no-ff 合入 → 手动 dispatch → 发版 → 清理。 |
| 10-25 21:30 | 第六批（第二轮 · 24 门）材料已备好（基线 main 6ac77c6b2，与 wide5 施工正交可并行） | 产物：/var/minis/mounts/背景包/发送-A\|发送-B/（00 = spec_v8 / 01 索引 24.2KB·20.5KB 含已挡清单 91·41 条臂 / 02 diff 56.9KB·233B / … |
| 10-25 21:43 | META-B5 第五批门见证收口启动：144 条见证机械复证 132 实锤失明，共享层先行 + 13 支派发（2026-10-25 21:4x） | 输入：A/B 两份外部报告（221KB + 291KB，24 门 × 6 尝试 = 144 条见证，v7 格式无 === GATE: 块，自写解析器 parse_report.py 双格式支持）。声明基线 1319cce… |
| 10-25 23:28 | BATCH5-t08 收口：backup_streaming / loop_persist 两门 12 见证全翻红（分支 `fix/b5-backup-loop` @ `f1dfd92e4`，绿 sha `fcf9fef29`，CI #40203536128 success，停分支） | 基线 e145ec961（= main + 第五批共享原语 _kotlin_valueflow）。改动：只改 2 个门脚本（+1015/−681）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/… |
| 10-25 23:29 | B5-t07 收口：两门判据从「拼写/存在」改到「多路径效果」，12/12 见证反攻全 CAUGHT，分支 CI #2681 绿（2026-10-25） | 支：fix/b5-modelentry-memorygate（head 0848d1b1 = f77bab3b3 + 报告回填），基线 base/batch5-shared @ e145ec961。门 model_ent… |
| 10-25 23:38 | B5-t02 收口：compact_decision + entry_id_rename 两门 12 条见证全 CAUGHT（分支 `fix/b5-compact-entryid` @ `c9ab770b4`，CI #40203205487 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享原语）。改动面 = 2 个门脚本（+720/−550），生产 .kt 0 行、scan.sh/test_sca… |
| 10-25 23:53 | BATCH5-t03 收口：enum_parse / trace_eval 两门判据加固（12 条见证全反攻成红，CI #2684 绿，停分支） | 支线 fix/b5-enum-trace（wt/t03，基线 base/batch5-shared e145ec961）→ 门提交 e7a6e0d17（+449/−142 enum，+266/−35 trace，生产 .… |
| 10-26 00:03 | B5-t09 收口：file_provider_paths + offload_relative_path 两门加固（12 见证全转 CAUGHT，CI #2686 绿，停分支） | 分支 fix/b5-fileprov-offrel @ 7e9f2c66b（远端一致）· 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow.py）· 提交三段：c5… |
| 10-26 00:06 | BATCH5 t12 收口：provider_import_id_roundtrip + models_provider_field 两门 12 条见证全补强（2026-10-25 夜） | 分支 fix/b5-provimport-modelsp：门提交 9996fc32b（2 文件 +959/−228，生产 .kt 0 行）+ 报告提交 e1e2f6c4e（fix-reports/FIX-BATCH5-t… |
| 10-26 00:08 | B5-t06 收口：`chat_ui_interaction_check` 五条见证补牙（分支 `fix/b5-chatui` @ `d263e14c1`，CI #40208261139 绿，停分支） | 基线 base/batch5-shared @ e145ec961（main 6ac77c6b2 + 共享值流原语）。门提交 931b8d14f（+432/−460，生产 .kt 0 行）+ 报告 55fbb9cd3 →… |
| 10-26 00:47 | 第六批门见证复证：149 条见证 146 实锤 + 3 对照，零分歧（2026-10-25 深夜，与第五批并行） | 材料：report-A__4_.md（344KB，A 族 12 门）+ report-B__4_.md（325KB，B 族 12 门承重老门）。基线 = main 6ac77c6b2（与第五批施工基线同一提交）。与第五批… |
| 10-26 00:56 | BATCH5-t10 收口：offload_host_path / entry_composite_key 判据改值流驱动，12/12 见证翻红，CI 40212058131 success（停分支） | 分支 fix/b5-offhost-entrykey @ e87fd7e4f（源码树 = CI 绿树 e019194ad，git diff --name-only e019194ad..HEAD -- . ':(excl… |
| 10-26 01:03 | B5-t01 收口完成（batch5 门见证第五批，支 01） | 分支 fix/b5-tabs-dao @ 2c5ded7f7（门提交 8562a2682 + 报告 2c5ded7f7），基线 e145ec961（main 6ac77c6b2 + 共享 _kotlin_valueflo… |
| 10-26 01:09 | B5-t11 收口：mcp_oauth / envvar_group 两门 12 条见证效果化（分支 fix/b5-mcpoauth-envvar @ 716d14f15，CI #2693 绿） | 基线 e145ec961（= main 6ac77c6b2 + 共享原语 _kotlin_valueflow）。门提交 557c5b57f（两文件 +739/−461，生产 .kt 0 行，scan.sh/test_sc… |
| 10-26 01:20 | B5-t04 收口：sysinfo + tool_title 两门 12 条见证全翻红（分支 `fix/b5-sysinfo-title` @ `12d56f186`，CI #2694 绿） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + _kotlin_valueflow）。门提交 9f815f791（+834/−656，生产 .kt 0 行、sca… |
| 10-26 04:05 | 第五批收口到并集（CI 绿，停并集）+ 第七批 28 门复证 + 用户新约束「施工分批派」—— 2026-10-26 凌晨 | 用户新约束（重要，跨批生效）：①「之前派出的任务跑完了」②「整理出来的任务，一次不要排出太多，那些跑门禁验证的操作，会对沙箱造成很大的压力」⇒ 重活串行化纪律落到施工派发上：每波 4 支，上一波出结论再派下一波（此前 1… |
| 10-26 05:03 | B6-t04 收口：webapp_asset_url_cleanup + i18n 两门 12 条见证全翻红（分支 `fix/b6-webasset-i18n`，门提交 `8f1fb1862`，CI #2697 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8f1fb1862（i18n +277/−99，webapp … |
| 10-26 05:40 | B6-t02 收口：provider_rpc_symmetry + file_preview_url_encode 两门 12 条见证全翻红（分支 `fix/b6-rpcsym-urienc` @ `35f7bb008`，CI #2699 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 956f27b0a（2 文件 +841/−541，生产 .kt 0 行、scan.sh/test_sca… |
| 10-26 08:45 | 第六批第一波收口 + 第二波派发（4 支/波）—— 2026-10-26 04:5x | 第一波 4/4 完成收口（用户报「都做完成了」，机械核过）：t01 offloadcli 40231270498 / t02 rpcsym-urienc 40234206640 / t03 fileprint-rss 4… |
| 10-26 09:35 | B6-t05 收口：tts_voice_and_sanitizer + katex_ready_race 两门 13 条见证全翻红（分支 `fix/b6-tts-katex` @ 门 `c367faf6f`，CI #2700 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + _kotlin_valueflow）。门提交 c367faf6f（生产 .kt 0 行、scan.sh/test_… |
| 10-26 10:00 | B6-t08 收口：session_provider_enabled + memory_ui_wiring 两门 13 条见证全翻红（分支 `fix/b6-sessionprov-memoryui` @ `72955761c`，CI #2700? 实为 run 40254147183 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 2a237ae91（2 文件 +837/−640，生产 .kt 0 行、res/ 0 行、scan.sh… |
| 10-26 10:05 | B6-t09 收口：tool_result_image_branch + provider_write_rules 两门 12 条见证全翻红（分支 `fix/b6-toolimg-provwrite` @ `2b5cfe0ba`，CI #2704 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 2b5cfe0ba（2 文件，生产 .kt 0 行、scan.sh/test_scan.py/_kotl… |
| 10-26 10:12 | B6-t06 收口：deeplink_settings_routes + provider_type_registry 两门 12 条见证全翻红（分支 `fix/b6-deeplink-protype` @ 门提交 `92bc6ddbc`，CI #2705 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 92bc6ddbc（两文件 +974/−413，生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112 / … |
| 10-26 10:13 | B6-t07 收口：modeluse_proto_single_source + thinking_rule_editor_preserve 两门 13 条见证全 CAUGHT（分支 `fix/b6-modeluse-thinkrule` @ 门提交 `ae0cdce9c` + 报告 `12e879c00`，CI #2703 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 ae0cdce9c（2 文件 +1237/−719，生产 .kt 0 行、scan.sh/test_sc… |
| 10-26 10:14 | B6-t12 收口：skill_frontmatter / offload_b64 / subagent_handback 三门 18 条见证全翻红（分支 `fix/b6-skillfm-b64-handback` @ `914e8f05d`，CI #2706 绿，停分支） | 基线 base/batch6-shared @ e145ec961。门提交 64c4f46ba（3 文件 +1157/−560，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门… |
| 10-26 10:42 | B6-t10 收口：open_url_broker_scheme + openminis_compat_field_parity 两门 13 条见证全 CAUGHT（分支 `fix/b6-openurl-compat`，门提交 `66137f77e`，CI #2707 绿 @ `47a8b441d`，报告回填 `d5291e1ee`，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（open_url +604/−482，openminis +328/−161），生产 .kt 0 行、scan.sh/test… |
| 10-26 11:19 | B7-t02 收口：skill_restore_session_cleanup + speech_active_engine 两门 12 条见证全红（分支 `fix/b7-skillrestore-speech` @ `8b865532e`，CI #2708 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8b865532e（2 文件 +1124/−720，生产 .k… |
| 10-26 11:42 | B7-t01 收口：chat_session_id_binding + outcome_known_chain 两门 12 条见证全翻红（分支 `fix/b7-sessionbind-outcome` @ 报告 `1c2df293d`，CI #2710 绿，停分支） | 基线 base/batch7-shared（e145ec961）。门提交 926073cfc（2 文件 +795/−379，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 1… |
| 10-26 12:02 | B7-t04 收口：provider_ua_parity + auto_approve_bypass 两门 12 条见证全翻红（分支 `fix/b7-ua-autoapprove` @ 门提交 `579a99d86`，CI #2712 绿，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+992/−488，provider_ua_parity_check.py 1718→1960 行、auto_approve_… |
| 10-26 12:07 | B7-t03 收口：parts_json_funnel + modelsvc_single_read 两门 12 条见证全 CAUGHT（分支 `fix/b7-t03-partsjson-modelsvc` @ `cc92f6e43`，CI #2711 绿，停分支） | 基线 e145ec961。改动面 = 2 个门脚本（parts_json +682/−276，modelsvc +449/−122），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{le… |
| 10-26 12:28 | B7-t06 收口：fastio_tar_bounds + marker_boundary 两门 12 条见证全 CAUGHT（分支 `fix/b7-fastiotar-marker`，门提交 `9101923ab`，报告 `fc5f728ac`，CI #2714 绿 @ 门提交，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+972/−481），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{lexer,ast,v… |
| 10-26 12:29 | B7-t05 收口：share_dir_prune + browser_tab_exec_serial 两门 12 条见证全翻红（分支 `fix/b7-shareprune-tabexec` @ `95c7e952`，CI #2716 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 95c7e952（2 文件 +899/−427）+ 报告 94289cac（git diff --name-only 95c7e952..HEA… |
| 10-26 12:30 | B7-t07 收口：session_sheet_id_binding + provider_models_cache_wiring 两门 12 条见证全 CAUGHT（分支 `fix/b7-sheetsession-provmodels`，门提交 `56617187e`，CI #2713 绿，报告 `11ee82526`，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+785/−436），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{lexer,ast,v… |
| 10-26 12:31 | B7-t08 收口：browser_destroy_wakeup + mirror_category_route 两门 12 条见证全 CAUGHT（分支 `fix/b7-browserdestroy-mirrorroute` @ `fc5361eba`，CI #2717 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 381beee36（2 文件 +985/−461，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-26 12:48 | 三批派发全部完成 + 第五批已合 main + 第六批并集绿（2026-10-26 12:4x） | 第五批已合 main：git merge --no-ff dfa278a8bf99 → main fb7ad0c47，合并树与 CI 绿并集树逐字节相同（git diff --stat 为空）。注意 build-apk.… |
| 10-26 13:03 | B7-t10 收口：schedule_date_source + overlay_show_hide_latch 两门 12 条见证全翻红（分支 `fix/b7-scheduledate-overlay` @ `626eee8bf`，CI #2718 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 626eee8bf（2 文件 +1052/−631，生产 .kt 0 行、scan.sh/test_scan.py/三个 _kotlin_ 零触… |
| 10-26 13:08 | B7-t09 收口：browser_history_snapshot + collection_copy_on_mutate 两门 12 条见证全翻红（分支 `fix/b7-browserhistory-collectioncopy` @ `ca52fb937`，CI #2719 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 ca52fb937（2 文件 +1012/−387，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门… |
| 10-26 13:10 | B7-t12 收口：webview_holder_url_source + chat_menu_prefs_listener 两门 12 条见证全翻红（分支 `fix/b7-webviewholder-chatmenu` @ `0dfb86e2b`，门提交 `378659481`，CI #2720 绿，停分支） | 基线 base/batch7-shared @ e145ec961。门提交 378659481（2 文件 +828/−437，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-26 13:19 | B7-t11 收口：a11y_handler_contract + voice_cancel_guard 两门 12 条见证全翻红（分支 `fix/b7-a11y-voicecancel` @ 门提交 `e90ca37f3`，CI #2721 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 e90ca37f3（2 文件 +781/−336，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-26 13:38 | B7-t14 收口：mirror_vm_thread + file_uri_parse_guard 两门 12 条见证全 CAUGHT（分支 `fix/b7-mirrorvm-fileuri` @ `681bc2ac5`，门提交 `57a86390a`，CI #2722 绿，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+1035/−697），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 … |
| 10-26 13:53 | B7-t13 收口：calendar_events_nullable_predicate + router_lock 两门 12 条见证全 CAUGHT（分支 `fix/b7-calendar-routerlock` @ `8c95fd6ca`，CI #2723 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 8c95fd6ca（2 文件 +799/−265），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门… |
| 10-26 14:20 | 第七批收口到并集（CI 绿）+ 三批全部到达停止节点（2026-10-26 14:2x） | 第七批 14/14 支 CI success + src_diff=0；并集 integration/b7 @ 2a42ab264c（base/batch5-shared 上 14 支 --no-ff 零冲突，42 文件… |
| 10-26 14:53 | 第三轮 B 波（收口批 · 19 门）材料已备 —— 50/50 有缝门全覆盖（2026-10-26 14:55） | 回答用户的「这是最后一批了吧？」：是。109 门里 50 门有缝（出现在共享生产文件里）、59 门孤立（无共享文件，打第三轮只能门内变异=第二轮重复，按判据不打）。第三轮 A/B 波 32 门已发 + 本批 19 门 =… |
| 10-26 14:59 | meta-audit v5 项目收口：文档重建 + 三批（b5/b6/b7）全部合 main + main CI 全绿（2026-10-26 15:0x） | 文档：dev-history-sync 三件套重建 → 2325 条 / 24 sagas（orphans 197、multi4 177）；sanitize_dev_history.py 双验证 Remaining se… |
| 10-26 16:59 | 第三轮门见证收口：三份报告（157 条见证 / 51 门）解析 + 主会话预检 + 22 支派发（2026-10-26 17:0x） | 输入：Report.md（r19，19 门收口批 · 57 条 + 4 条跨批缝）、Report_A__6_.md（16 门 · 48 条）、Report_B__6_.md（16 门 · 48 条）→ 合计 157 条见… |
| 10-26 18:58 | R3-t03 收口：浏览器 tab 三件套 9 条见证（W76-W84）修前 BLIND / 修后 CAUGHT，分支 CI #2727 绿（2026-10-26） | 支 fix/r3-browser-tabs，基线 main f0f14d46d。只改 3 门脚本 + 报告：browser_tab_exec_serial_check（+332，15→20 臂）、browser_tabs… |
| 10-26 19:06 | R3-t05 收口：两门 6 条缝见证全 CAUGHT + 分支 CI #2728 绿（2026-10-26 19:1x） | 支 fix/r3-menuprefs-katex：门提交 5eabfb51e（CI 绿 sha）+ 报告回填 b32e88c36（git diff --name-only 5eabfb51e..HEAD = 仅 fix-… |
| 10-26 19:06 | R3-t15 收口：anthropic_thinking_budget + thinking_wiring 两门补缝（7 条跨门缝见证全 CAUGHT，CI #2733 绿，停分支） | 支线 fix/r3-thinking-wiring @ 6bcb84cf4（门提交 3110d6e29），基线 main f0f14d46d。改动面 = 2 个门脚本 +912/−95，生产 .kt 0 行，scan.s… |
| 10-26 19:14 | R3-t08 收口：parts_json_funnel + skill_restore 两门 6 条见证全 CAUGHT（分支 `fix/r3-partsjs-skillrestore` @ `a924214c2`，门提交 `1c3f9c497`，CI #2731 绿，停分支） | 基线 main @ f0f14d46d。改动面 = 2 个门脚本（+693/−13）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰，门 112 / 夹具 344 不变。 |
| 10-26 19:17 | R3-t10 收口：outcome_known_chain + stale_closure 两门跨门缝加固（6/6 BLIND→CAUGHT，CI #2732 绿，停分支） | 分支 fix/r3-outcome-stale @ 9820d8ac0（门提交 eec06ccf3，基线 main f0f14d46d）。改动面 = 2 个门脚本（+682/−110）+ 报告，生产 .kt 0 行、sc… |
| 10-26 19:17 | R3-t09 收口：loop_persist + loop_tool_status 两门加固，6 条见证全 CAUGHT（分支 `fix/r3-loop-persist` @ `0060a759d`，CI #2729 绿，停分支） | 基线 main f0f14d46d（第七批收口后）。改动面 = 2 个门脚本（loop_persist_slice_check.py +273/−6、loop_tool_status_transition_check.p… |
| 10-26 19:20 | R3-t14 收口：offload_cli_args + fastio_tar_bounds 两门判据加固（6/6 见证反攻 CAUGHT，分支 `fix/r3-offload-fastio` @ `c0039db84`，CI #2735 绿，停分支） | 基线 main f0f14d46d。改动面 = 2 个门脚本（+1017/−2）+ 报告，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 / 夹具 344 不变。 |
| 10-26 19:21 | R3-t07 收口：chat_session_id_binding + session_sheet_id_binding 两门 6 条 dual-gate 见证全 CAUGHT（分支 `fix/r3-sessionbinding` @ `80aef69a1`，CI #2734 绿，停分支） | 基线 main f0f14d46d。只改 2 个门脚本（+610/−10），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112/夹具 344 不变。报告 fix-repo… |
| 10-26 19:32 | R3-t06 收口：mirror_category_route + mirror_vm_thread 两门（第三轮 B 波 · 6 条见证全 CAUGHT，分支 CI 绿，停分支）（2026-10-26 19:2x） | 支 fix/r3-mirror-route：门提交 3c1bd1cee + 报告回填 c82c1397f（已推远端），基线 main f0f14d46d。改动面 = 2 个门脚本（+449/−5、+333/−0）+ 报告… |
| 10-26 19:38 | R3-t01（第三轮 B 波 · 跨门缝三件）收口：9/9 见证 BLIND→CAUGHT，分支 CI 绿（2026-10-26 19:3x） | 支 fix/r3-fileuri-webview（基线 main f0f14d46d，wt/t01）。三门：file_uri_parse_guard_check / webview_holder_url_source_c… |
| 10-26 19:49 | R3-t18 收口：backup_remap_rollup / mcp_oauth_lifecycle / backup_streaming_export 三门 9 条见证全 CAUGHT（分支 `fix/r3-backup-mcp` @ `335faf435`，CI #2741 绿，停分支） | 基线 main f0f14d46d。改动面 = 3 个门脚本（+928/−43），生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112 / 夹具 344 不变。绿 sha 9c17f… |
| 10-26 19:52 | R3-t21 收口：deeplink_settings_routes + open_url_broker_scheme 两门判据加固（CI #2740 绿，停分支） | 任务：round3b 第三轮 B 波（dual-gate bypass 收口批 19 门）支线 — 修 2 个门脚本，基线 main f0f14d46d，分支 fix/r3-deeplink-openurl。 |
| 10-26 19:57 | R3-t11 收口：discarded_return_guard + provider_remember_key 两门判据加固（第三轮 dual-gate · B 波） | 分支 fix/r3-discard-rememberkey（基线 main f0f14d46d）→ 门提交 b181fe44c（2 文件 +630/−116）→ 报告回填 79e4d781（源码树等价：git diff … |
| 10-26 19:58 | R3-t12 收口：log_url redaction + UA parity 两门值流化，6/6 见证修前 BLIND → 修后全抓，CI #2742 绿（停分支） | 支：fix/r3-logredact-ua（wt/t12，基线 main f0f14d46d）→ 门提交 0c65e85f7（2 门脚本 +737/−207，臂 17→28 / 56→60）+ 报告 4bd421bc4；… |
| 10-26 20:08 | R3-t17 收口：三门判据加固（auto_approve / offload_permission / debug_leak） | 任务：round3b/fix-tasks/R3-t17.md（第三轮 dual-gate bypass 收口的 3 门 / 9 条见证）。 |
| 10-26 20:08 | R3-t02 收口：三门缝隙判据加固（9 条 dual-gate 见证 9/9 反攻成红，CI #2745 绿，停分支） | 分支 fix/r3-preview-asset（基线 main f0f14d46d）→ 源码绿 sha e66acd235，回填 33cef83fd，远端 tip = 本地 HEAD。改动面 3 个门脚本（+949/−2… |
| 10-26 20:12 | R3-t22 收口：file_provider_paths + schedule_date_source 两门加固（分支 `fix/r3-filepaths-schedule` @ `990343cdd`，CI #2746 success，停分支） | 基线 main f0f14d46d，worktree round3b/wt/t22。改动面 = 2 个门脚本（+753/−4）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零… |
| 10-26 20:13 | R3-t19 收口：skill_file_save_result + skill_import_offmain 两门补 6 条 round-3 缝级判据（分支 `fix/r3-skillfilesave` @ `009916a26`，门提交 `f686031b5`，CI #2747 绿，停分支未合并） | 基线 main f0f14d46d。改动面 = 2 个门脚本（+808/−65），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 / 夹具 344 不变。 |
| 10-26 20:28 | R3-t04 收口：第三轮双门缝（downloads / share / mediaplayer 三门）9 条见证全 CAUGHT（分支 `fix/r3-fileprune-media` @ `66f9a20f0`，CI #2749 绿，停分支） | 基线 main f0f14d46d；工作树 round3b/wt/t04。门提交 5922aef95（3 文件 +1047/−3，生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112… |
| 10-26 20:40 | R3-t16 收口：xproc_boundary + exec_gate_release 两门判据加固（7 见证 7/7 CAUGHT，分支 CI #2750 绿，停分支） | 分支 fix/r3-xproc-execgate（基线 main f0f14d46d）→ 门提交 5cbb92105 + 081244e5f（打印每条判据）→ 报告 e46e3078e/576765d41/81aba54… |
| 10-26 21:15 | R3 收口 + P1 静态门禁并行化（2026-10-26 晚） | R3（第三轮收口批）：三份报告 51 门 / 157 条见证 → 22 支全部 CI success → 并集 integration/r3 @ 6d267d3aa，CI #2751 success（112 门头 / 1… |
| 10-26 23:15 | T1 收口：`_kotlin_ast.enclosing_conditions` else 取反优先级缺陷根治（分支 `fix/primitive-else-negation` @ `1a4eff14d`，绿 sha `b51cd03cf`，CI #2754 绿，停分支） | 基线 main 627f23f2e（R3+P1 合并后）。工作树 debt-1026/wt/primitive。改动面 = 2 个原语脚本（实现 1 行 + KDoc 契约 + 5 条臂），生产 .kt 0 行、scan… |
| 10-26 23:25 | T2 debt-sweep 收口：清三笔登记债务（#2 file:// 路径 / #3 图片附件有界读 / #5 weather 单出口），分支 `fix/debt-sweep-1026` @ `215c1d951`，CI #2755 绿，停分支（2026-10-26 夜） | 基线 main 627f23f2e。改动面 4 生产 .kt + 3 门脚本豁免表：+170/−194（7 文件，全部在任务书白名单内）。scan.sh/test_scan.py/_kotlin_ 零触碰。 |
| 10-26 23:28 | T1/T2 债务批收口 + 第三方 12 条新报告核实（2026-10-26 夜） | T1 fix/primitive-else-negation @ 门提交 b51cd03cf（CI 40343477296 success）：_kotlin_ast.enclosing_conditions else 取… |
| 10-27 00:13 | U3 收口：顺路清三笔登记债（分支 `fix/misc-debt-sweep`，绿 sha `b06bae548`，CI #2756 绿，停分支） | 基线 main 627f23f2e。工作树 debt-1026/wt/misc。改动面 2 生产 .kt + 1 门脚本（+49/−2），另 1 个脚本在仓库外。scan.sh/test_scan.py/_kotlin_… |
| 10-27 00:30 | U4 收口：`file://` 契约「跨界编码 / 内部解码」（分支 `fix/fileurl-contract` @ `8ddb12e57`，绿 sha `621602d43`，CI #2757 绿，停分支） | 基点 T2 分支 8d00395af（含 T2 全部提交）。改动 8 文件 +450/−40：生产行为 4 文件（未越任务书白名单，全逐条登记）+ 3 测试 + 1 门脚本。 |
| 10-27 00:43 | U2 图像链收口：分支 `fix/image-chain-oob` @ `9d8d70f77`（绿 sha `db8c0b202`），CI #2759 绿，停分支（2026-10-27 凌晨） | 四笔全修，1 提交生产 6 文件 +231/−87 + 测试 2 文件（ChatImageRestoreTest 8 臂 + DailyLogRollupMetricsTest 3 臂）。 |
| 10-27 01:24 | U 批四支收口（U1-U4 全部 CI 绿，2026-10-27 01:0x） | 四支全部 success、停分支未合 main： |
| 10-27 01:25 | U1 收口：三原语第二轮 A1-A4 修复（分支 `fix/primitive-ast-round2` @ `5bdbc48ff`，CI #2761 绿，停分支） | 基线 1a4eff14d（T1 收口后，本支延续而非基于 main）。三个提交：47f988cfa（A1-A4）→ 626985db9（branch_value 补正）→ 5bdbc48ff（报告）。改动面 3 文件 +… |
| 10-27 06:13 | agentloop 基线补 t09-t13 收口（分支 `test/agentloop-behavior-baseline` @ `9b608a95`，CI #2764 绿，停分支） | 基线 74b1ba65（首版 8 场景）。新增 5 场景 + 3 处 fake 见证面，生产 .kt 0 行：改动 2 个测试文件 +267/−1（AgentLoopBaselineScenariosTest.kt +2… |
| 10-27 06:41 | 沙箱缺陷召回修复收口（分支 `fix/defect-recall-seeds` @ `9d7c0c85b`，报告 `acdeae6b8`，CI `40409678884` 绿，停分支）—— 2026-10-27 | 用户说「修吧」→ 走分支隔离全流程收口。改动 2 文件 +187/−15（SandboxDefectRegistry.kt +96、测试臂 15→20）。 |

## 14. CI / 构建 / 发布流水线

**跨度** 2026-08-03 ～ 2026-10-27 · **736 条** · **状态** 稳定（Publish 步骤已加 `if: refs/heads/main` 门控）

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
| 09-22 14:42 | 09-22：应用开发阶段收口 —— 总账梳理 + 档案更新（分支 CI 进行中） | 用户指令：「应用开发告一段落，帮我总结梳理这一段开发的具体情况，然后更新文档」。 |
| 09-22 14:44 | 09-22 补：文档类改动**不该**触发 CI（用户纠正，已取消） | 用户指出：文档类不用触发 CI。核对后两层都对，已取消 run 35695746144（conclusion cancelled）。 |
| 09-22 16:30 | 09-22：核实 `fix/tool-schema-required-empty-array` → 合并 main = `28e0df0a`（release CI 35705134017 已触发） | 用户指令：「检查核实一下分支上的那个修改」→ 核实后「记进去吧，并且已经跑完了，也要合并」。 |
| 09-23 01:04 | 09-23：观测体系三缺口修复（分支 `fix/observability-rulers` @ `74613936`，CI 35756621579 绿，用户拍板不合并） | 用户指令：「三条一起吧」（①liveness 接 CI ②修 D1/D4 ③prune 证据保护）；「分支要手动触发」；收口「不要合并」。 |
| 09-23 10:00 | 思考档位折叠三处修复（分支 CI 绿 @722215ab，停在分支未合并） | 分支 fix/thinking-effort-gateway-truth @ 722215ab，CI run 35807594101 success（head_sha 与本地逐字符一致），全步骤 success + Pu… |
| 09-23 11:30 | §35/§36 thinking 档位两层修复完成（2026-09-23，分支 CI 绿停在分支） | 分支：fix/thinking-effort-gateway-truth @ 7b7f12cd，CI run #1760 success，head_sha 与本地逐字符一致，停在分支未合并（合并权在用户）。 |
| 09-23 17:31 | 远端只剩 main = 1ef3adc6（801f40b..1ef3adc6）；release CI run 35843 | 远端只剩 main = 1ef3adc6（801f40b..1ef3adc6）；release CI run 35843369730 (#1766) @ 1ef3adc6 已触发（in_progress 时未等完成，按「… |
| 09-23 18:33 | 全量 round-trip 测试进 main（09-23 分派任务，分支 CI 绿停在分支） | 全量 round-trip 测试进 main（09-23 分派任务，分支 CI 绿停在分支） |
| 09-23 18:45 | ↳ round-trip 分支核验 + 合并 main = 051a6b35（2026-09-23） | 用户指令：「任务完成了，检查核验，没问题就合并」= 核实 + 合并放行。 |
| 09-24 08:48 | 启动会话瘦身进 main（09-24 分派任务，分支 CI 绿停在分支） | 分支：fix/launch-session-slim @ 75dee757（基点 main 051a6b35，单 commit，9 文件 +6/−12）→ CI run 35939014960 (#1769) succe… |
| 09-24 08:48 | 备份快照减量分支 CI 绿（2026-09-24） | 任务：用户认为恢复/备份后留下的快照冗余 → 对齐后确认事实：快照不是备份写的，是 restoreWithSnapshot 每次恢复前写的回滚安全网（[fix-audit-p0-2/p0-3]）。全删会推翻 P0 安全修… |
| 09-24 08:57 | ProviderList 拖拽排序 + ModelGroups 去滑动删除（分支 CI 绿停） | 分支：feat/provider-reorder-and-group-cleanup @ 6a990654（2 commits，4 文件 +253/−126）→ CI run #1772 success、head_sha… |
| 09-24 09:00 | 三分支全套审查 3/3 无阻塞（09-24 凌晨，CI 全绿停在分支未合并） | 分支（均基于 main 051a6b35）：①feat/provider-reorder-and-group-cleanup @6a990654（拖拽重排 Provider 列表+删 Model Group 划删，run… |
| 09-24 09:02 | 三分支合并进 main = 805dab7c（09-24 01:02，release CI 35941107947 已触发） | 三笔 --no-ff 顺序合并（b1 重排→b2 launch-session→b3 snapshot-keep-1），零冲突（文件集互不相交）：182bcc80 + b660477e + 805dab7c，远端 051… |
| 09-24 09:19 | 运行时限制滑杆「可见吸附」统一（A 方案，分支 CI 绿停） | 分支：feat/slider-visible-snap @ 90bff556（2 文件 +87/−42：RuntimeLimitsScreen.kt + SliderStepsForTest.kt 重钉）→ CI run… |
| 09-24 09:30 | 滑杆可见吸附分支合并进 main = 5fb8a244（09-24 09:29，release CI 35943170719 已触发） | 用户指令：「合并」。合并前三道核查直接复用本会话审查轮取证（diff 走查干净 / scan 18/18 + i18n + four-way + test_scan 80/80 + JVM 5/5 / CI head_s… |
| 09-24 10:14 | 每日自动备份减量分支 CI 绿（2026-09-24 续） | 任务：用户指出「自动备份那里还拖着一长串」→ 查证：每日自动备份是独立机制（AutoBackupManager + WebDavSync），本地+远端各保留最近 7 份（AUTO_BACKUP_KEEP=7，两个同值常量… |
| 09-24 10:37 | 存储页进出卡顿修复分支 CI 绿（09-24，分支 CI 绿停） | 任务：用户报告「设置→存储→存储」进出历史对话卡顿、会话越大越明显。诊断（/tmp/rk-audit-0924x @5fb8a244）：StorageManagementScreen.reload() 挂 Launche… |
| 09-24 10:39 | 三分支合并进 main = d57643f4（09-24，release CI 35948116156 已触发） | 用户指令：「合并」。三笔 --no-ff 顺序合并（autobackup→redundancy→storage），零冲突（autobackup 与 redundancy 都动 7 个 strings.xml 但 hunk… |
| 09-24 11:19 | release CI 红灯诊断 + 修复（09-24）：R8 输入敏感保留 debug 字符串 | 失败：合并 main = d57643f4 后 release CI run 35948116156 挂在 Verify APK contents——debug_leak_guard apk 模式在 classes.de… |
| 09-24 12:22 | release CI 红灯真根因：guard 裸字节扫描假阳性（09-24，最终定论） | 迭代过程（3 次假设被推翻，最终靠本地解剖 dex 定案）： |
| 09-24 12:41 | debug-leak-guard 修复分支 CI 绿（09-24，停止节点） | fix/debug-leak-guard-dex-stringtable @ 6c72931b（1 文件 +116/−3，基于 main d57643f4）：CI run 35955382186 success + he… |
| 09-24 12:46 | ↳ guard 修复分支合并进 main = 350a9993（09-24） | 用户指令：「合并」。合并前三道核查直接复用本会话取证（diff 走查 1 文件干净 / 本地自检 12/12 + 真实诊断 APK ✅ + scan.sh 18/18 / CI head_sha 逐字符一致）。--no-… |
| 09-24 13:18 | 会话列表 LazyColumn 分支 CI 绿（09-24，停止节点） | 任务：用户反馈 rescan-gate 修复后「使用体验没有明显改善」→ 转会话列表 LazyColumn。main 已推进（350a9993 = 三个 merge 之上，前一修复 d57643f4 已进 main）。 |
| 09-24 13:21 | ↳ lazy-session-list 核验 + 合并 main = 665877a5（09-24） | 用户指令：「检查核实分支上的修改，没有问题就合并」= 核实 + 合并放行。 |
| 09-24 14:13 | 合并后 release CI 事故复盘（09-24，续） | 用户确认：新 run 35959947984（main @ 665877a5）已跑完，用户真机验证没问题。事故闭环。 |
| 09-24 15:05 | 思考强度跨对话记住分支 CI 绿（09-24，停止节点） | 用户澄清需求：思考强度要跨对话记住（新/旧会话统一）；现状是 per-session，新草稿从 OFF 起步，分组默认没设时重开新对话显示关。用户拍板要「统一记住」。 |
| 09-24 15:10 | 用户指令「更新一下文档」→ dev-history 三件套重建 + 推送公开仓库。 | 用户指令「更新一下文档」→ dev-history 三件套重建 + 推送公开仓库。 |
| 09-24 16:36 | 用户反馈：应用已很稳定，release 页的「⚠️ 已知问题与使用边界」（内存飙升警告 + 4 步处理阶梯）过期。定案： | 用户反馈：应用已很稳定，release 页的「⚠️ 已知问题与使用边界」（内存飙升警告 + 4 步处理阶梯）过期。定案：该文案在 .github/workflows/build-apk.yml 的 Publish to … |
| 09-24 20:41 | release-notes 源头同步合并收口（用户放行）：diff 走查 1 文件干净（仅 release body 块 | release-notes 源头同步合并收口（用户放行）：diff 走查 1 文件干净（仅 release body 块，4 条新说明，debug 签名警告保留）、YAML safe_load 验过；--no-ff me… |
| 09-24 20:57 | 分支 CI 忘记手动 dispatch（复发 3+ 次确认，用户拍板加措施）。根因：build-apk.yml 的 on | 分支 CI 忘记手动 dispatch（复发 3+ 次确认，用户拍板加措施）。根因：build-apk.yml 的 on:push 限定 branches:[main]，非 main 分支 push 永远不触发 CI；每… |
| 09-25 10:00 | 用户指令「更新一下文档」→ dev-history 三件套重建 + 推送公开仓库（09-25）。 | 用户指令「更新一下文档」→ dev-history 三件套重建 + 推送公开仓库（09-25）。 |
| 09-25 15:46 | memory-editor 跳顶修复分支 CI 绿（09-25，停止节点）：fix/memory-editor-jump | memory-editor 跳顶修复分支 CI 绿（09-25，停止节点）：fix/memory-editor-jump-to-top @ 13d8e9a8（1 commit，4 文件 +56/−34，基点 main 7… |
| 09-25 16:17 | 窗口化编辑分支 CI 绿（09-25 第二轮，停止节点）：fix/memory-editor-jump-to-top @ | 窗口化编辑分支 CI 绿（09-25 第二轮，停止节点）：fix/memory-editor-jump-to-top @ 17770a20（2 commits：13d8e9a8 跳顶修复 + 17770a20 窗口化，6… |
| 09-25 17:39 | §流式恢复竞态修复分支 CI 绿（09-25，停止节点） | §流式恢复竞态修复分支 CI 绿（09-25，停止节点） |
| 09-25 18:33 | 分支 CI 绿（09-25 停止节点，检查会话）：fix/offload-stale-token-compact @ f | 分支 CI 绿（09-25 停止节点，检查会话）：fix/offload-stale-token-compact @ f87d9102（1 commit，4 文件 +48/−8，基点 main 1cc5ab1d，非本会话… |
| 09-25 18:33 | 卸载后陈旧令牌修复分支 CI 绿（09-25，停止节点） | fix/offload-stale-token-compact @ f87d9102（1 commit，4 文件 +48/−8，基点 main 1cc5ab1d）。用户拍板「该修的修」→ 四边改动：①AgentLoopH… |
| 09-25 18:36 | 分支完整核查补跑（09-25，应要求）：上轮只做 diff 走查+CI 两项属实偷懒，补齐三块：①全量 diff 逐行（ | 分支完整核查补跑（09-25，应要求）：上轮只做 diff 走查+CI 两项属实偷懒，补齐三块：①全量 diff 逐行（ChatContextWindow 160-390 原文核实 currentTokens 同口径递减… |
| 09-25 19:09 | 对话历史抽屉行精简分支 CI 绿（09-25，停止节点） | 对话历史抽屉行精简分支 CI 绿（09-25，停止节点） |
| 09-25 20:41 | 09-25 晚三条 UI 线全部到停止节点（分支 CI 绿，均未合并）：①feat/typing-status-band | 09-25 晚三条 UI 线全部到停止节点（分支 CI 绿，均未合并）：①feat/typing-status-band @ a54c2f78——状态带：TypingIndicator 包进固定 36dp Box（TYP… |
| 09-25 20:43 | 09-25 晚收尾（用户明示「合并不在这里」）：本会话停止节点 = 分支 CI 绿为终态，合并由别处发起。两条就绪分支停 | 09-25 晚收尾（用户明示「合并不在这里」）：本会话停止节点 = 分支 CI 绿为终态，合并由别处发起。两条就绪分支停在远端等合并：feat/typing-status-band @ a54c2f78（状态带）+ fe… |
| 09-26 00:05 | chore/remove-skill-detail-rescan 分支 CI 绿（09-25 深夜，停止节点）：用户发现 | chore/remove-skill-detail-rescan 分支 CI 绿（09-25 深夜，停止节点）：用户发现技能详情页「从磁盘重新扫描」在 fix/skill-metadata-resync-from-dis… |
| 09-26 00:27 | dev-history 三件套重建 + 推送（09-26，任务「更新一下文档」）：三个回归测试先过（test_rebui | dev-history 三件套重建 + 推送（09-26，任务「更新一下文档」）：三个回归测试先过（test_rebuild/test_sanitize/test_sagas 全 PASS）→ rebuild（55 天，… |
| 09-26 09:59 | 会话 A：吸收 Filterrr 网络观测与预热包 → 分支 CI 绿（09-26，停止节点） | 会话 A：吸收 Filterrr 网络观测与预热包 → 分支 CI 绿（09-26，停止节点） |
| 09-26 10:33 | B 分支 CI 三轮红→绿收口（09-26 02:30Z，停止节点达成，未合并） | B 分支 CI 三轮红→绿收口（09-26 02:30Z，停止节点达成，未合并） |
| 09-26 16:33 | dev-history 三件套重建 + 推送（09-26，任务「更新一下文档」，第二次——00:27 已有一轮）：三个回 | dev-history 三件套重建 + 推送（09-26，任务「更新一下文档」，第二次——00:27 已有一轮）：三个回归测试先过（test_rebuild/test_sanitize/test_sagas 全 PASS… |
| 09-26 18:41 | evidence-discipline v1.4.1→1.5.0（09-26，用户「更新吧」）：日志逆推会话沉淀三条进 | evidence-discipline v1.4.1→1.5.0（09-26，用户「更新吧」）：日志逆推会话沉淀三条进 skill——①新增「三源取二的深化：独立性 = 失败模式不相交」节（操作化检验一句话：「第一条错了… |
| 09-27 14:22 | 键盘意外弹出修复（09-27，分支 CI 绿 = 停止节点，未合并） | 根因（代码级 + AOSP 源码双证据）：ModalBottomSheet/Dialog 是独立 focusable window——打开时主 window 失焦、关闭时重获焦点，焦点回到 composer 的 Text… |
| 09-27 14:44 | dev-history 三件套重建 + 推送（09-27，任务「更新一下文档」，今日第二次——14:40 曾有一轮同日重 | dev-history 三件套重建 + 推送（09-27，任务「更新一下文档」，今日第二次——14:40 曾有一轮同日重建落档，本轮为仓库同步） |
| 09-27 17:48 | 核查 fix/selection-toolbar-handle-overlap（09-27，用户「检查一下云端正在跑的那个分支」= CI in_progress 的那个） | 核查对象：远端 fix/selection-toolbar-handle-overlap @ 8a76278d16e4（1 commit，2 文件 +49/−9，基 main aeffcef9），CI run 36309… |
| 09-28 01:50 | 任务 C 完成（offload 无收益重试收敛）→ 分支 `perf/offload-convergence` @ `2b5689e`，CI `36337271291` success，未合并 | 做了什么（ChatContextWindow.kt，1 文件 +87/−23）： |
| 09-28 13:43 | 文档三件套第三次重建 + 推送（09-28，用户「整理更新一下文档吧」） | 三回归测试先全 PASS → rebuild 1405 条（前次 1399 → +6，dropped 75 → 76）→ sanitize main+INDEX 复扫 NONE clean → SAGAS 23 saga… |
| 09-28 14:44 | 合并 thinking-header-toggles，push 后不等 CI（09-28） | 用户明确补充：检查无问题后可以合并；main push 已触发构建后就不用等待结论，本轮到「已触发」即可停止。feat/thinking-header-toggles @ 65b26bb24bdee70ad6724b37… |
| 09-29 09:27 | 合并 fix/skills-entry-label-mismatch → main = b692f2f3（09-29，用户「检查没问题就等 CI 完合并」+「触发完不用等」） | 改动：7 locale strings.xml，21 处标签 "RikkaMinis Skills" → "Minis Skills"（skill_empty_action / skill_minis_skills_mo… |
| 09-29 13:52 | 会话 B 完成：CF Worker 上传客户端（feat/cf-worker-client @ 13f0b146，CI 绿，未合并） | 基线：main @ 559594d4（与任务书一致），独立 clone /var/minis/shared/work/ClashMetaForAndroid-task-B。 |
| 09-29 15:55 | fork 构建失败修复：签名 + workflow 权限三连环（09-29 晚，Pre-Release 全绿发 APK） | 用户报「构建失败了」→ 三个 fork 存量配置问题（与 merge 的代码无关，全部编译绿）：①fork 不继承上游 Actions secrets，SIGNING_ 三个 secret 为空 → 空密码签名失败（ke… |
| 09-30 07:09 | CI 三处 annotation 修复（09-30，分支 ci/fix-prerelease-publish @ 9565782，分支 CI 绿未合并） | 触发：用户给两张 GitHub Actions annotation 截图（预发布 3 错误 / Build APK 1 警告）说「解决一下」。 |
| 09-30 07:29 | CI annotation 全清 + 合并 main = 34aca49（09-30，含 tag 步骤线上首跑验证） | 承接：上一段修了 tag/缓存/噪音三处（分支 9565782），用户追加「把 ubuntu-latest 迁移 notice 也处理了，处理完就合并」。 |
| 09-30 12:45 | README 两处 P1 漂移已修（09-30，分支 `docs/fix-readme-drift` @ ad209a1，分支 CI 绿，**未合并**） | 用户拍板：「修了吧」（承接全量验收报告的两处 P1）。 |
| 09-30 14:47 | A4「中断尾巴 Case D + 跨 VM 活跃门」完成（09-30，分支 CI 绿，未合并） | 分支：fix/interrupted-tail-case-d @ 47b55750d09bfe1e0668f066d2ec12cf7ad3bd84（2 commits：c7ed723 修复 + 47b5575 收敛），4… |
| 09-30 15:01 | 上游 A 档移植·会话 1 完成（分支 CI 绿，未合并） | 分支 feat/a11y-restricted-and-recovery @ c3467b70cb56080326a3ef0f4969a4366a2bf588（基于 main 0c78f00） |
| 09-30 15:37 | 会话3 A5 数据库降级保护完成（09-30，分支 feat/db-version-downgrade-guard @ 07726db，CI 绿 #1885，未合并） | 停止节点到达：build-apk.yml run #1885，head=07726db14d8ce58563b6f14ff05734802f0bc4e3 == 本地 HEAD == 远端 tip，jobs[build:s… |
| 09-30 17:23 | 两条审计发现修复完成（09-30，分支 fix/a11y-evergranted-gate-fgs-guidance @ 70a93bc3，CI 绿 #1889 未合并） | 修复（3 文件 +437/−12，基线 origin/main 0924445f）： |
| 09-30 18:00 | 真机验证完成（09-30 晚，设备包 1.0.0+1889 = 分支 run #1889 构建，代码==合并树） | 包来源链（用户问「现在这个包是最新的那个」）：versionName 1.0.0+1889 ↔ CI run #1889（36694097030，分支 tip 70a93bc3）↔ 合并树 26b7ce1（合并树 vs … |
| 09-30 22:35 | minis-fastio offload handler 完成（09-30 深夜，分支 feat/minis-fastio-offload @ d2003f75，CI 绿未合并） | 停止节点：分支 feat/minis-fastio-offload，commit d2003f75，CI run #1891（id 36728328060）success @ head_sha == 本地 HEAD ==… |
| 10-01 00:52 | fastio 第二期完成（10-01，分支 feat/fastio-primitives-2 @ d74a49c1，CI 绿未合并） | 停止节点已到：分支 feat/fastio-primitives-2，commits 57693ba6(主体) + d74a49c1(CI 修复)，远端 == 本地 == d74a49c1ca51 逐字符，工作树 cle… |
| 10-01 01:22 | 修复 feat/fastio-primitives-2 的 `mv --force`（commit 98edf12，CI 绿停止节点） | 改动（4 文件 +92/−8，同一分支补 commit d74a49c1 → 98edf12）： |
| 10-01 02:45 | fastio 第三期完成（10-01，分支 feat/fastio-primitives-3 @ 4bb8ab42，CI 绿未合并） | 停止节点已到：CI run #1900 Build RikkaMinis Android APK completed success，head_sha 4bb8ab428232d517e64c5f46ad8c8f1201… |
| 10-01 17:11 | 核查 fix/compact-quiet-first-1001（10-01，用户「检查一下云端正在跑的分支」+「构建完了」，CI 绿停） | 对象：main b4592399 + 2 commits（dd96f04c quiet-first 排序+三道预算；4d1c9a25 只修测试编译——1905 failure 是 CompactQuietFirstTes… |
| 10-02 15:24 | 停止按钮杀不到压缩 P1 已修（10-02，分支 fix/compact-cancel-on-stop-1002 @ 4bc1352，CI #1908 绿停） | 改动（5 文件 +231/−8，停在分支未合并）：①compactAll 的 launch 存 compactJob，cancelStream 连带 cancel（9 处调用点全统一——语义都是"当前轮工作立刻停"，fi… |
| 10-02 15:26 | 分支审查 fix/compact-cancel-on-stop-1002（10-02，CI 绿停，未合并） | 对象：分支 fix/compact-cancel-on-stop-1002 @ 4bc13521（main 6361b410 + 1 commit），修 10-01「停止杀不到压缩」P1。CI run 369769828… |
| 10-02 16:41 | P2 已修并合入分支 fix/clearchat-compact-ce-1002 @ 90fd2e72（10-02，CI | P2 已修并合入分支 fix/clearchat-compact-ce-1002 @ 90fd2e72（10-02，CI 绿停，未合并）：clearChat 补 _compactSummary.value = null（… |
| 10-02 17:05 | 审查（第三轮）fix/clearchat-compact-ce-1002 @ 90fd2e7（CI run 369840 | 审查（第三轮）fix/clearchat-compact-ce-1002 @ 90fd2e7（CI run 36984034118 success @ head_sha 逐字符，停止节点已达，未合并）：增量 1 comm… |
| 10-04 23:48 | 定时任务 L0 施工完成（feat/scheduled-tasks-l0-1004，CI 绿，待真机清单） | 续接中断会话（HANDOFF-2026-10-04）完成简报 B 全部施工，交付报告 shared/work/rk-sched-1004/DELIVERY-REPORT-1004.md。 |
| 10-05 08:27 | 任务书 C 执行完成：压缩摘要截断守卫 + 吵闹候选 60s 预算（fix/compact-truncation-guard-1005，CI 绿停） | 状态：分支 CI 绿 @ tip，确定性停止节点已达成，未合并（合并权在用户）。 |
| 10-05 09:42 | 任务书 D 执行完成：压缩遥测 + SUPERSEDED 条款（fix/compact-telemetry-superseded-1005，CI 进行中） | 状态：两个 commit 已推远端 + CI 已 dispatch（run 37252315441），独立会话执行任务书 D 全文。 |
| 10-05 09:56 | 任务书 D 执行完成（终态）：CI 绿 @ tip，确定性停止节点达成，未合并（合并权在用户） | 分支 fix/compact-telemetry-superseded-1005 @ e71342ca（本地==远端==CI head_sha 逐字符）；CI run 37252315441 success（11m53s… |
| 10-05 22:01 | 任务书 H 交付：ChatSessionLifecycle 拆分完成，分支 CI 绿（10-05） | 结果：refactor/compact-orchestration-split-1005 @ 68f83ee6（2 commits：39e44822 拆分 + 68f83ee6 import 修复），CI run #19… |
| 10-06 00:42 | 压缩模型钉住功能施工完成（feat/compact-model-pin-1005 @ bba87f23，CI 绿，停在分 | 压缩模型钉住功能施工完成（feat/compact-model-pin-1005 @ bba87f23，CI 绿，停在分支待用户） |
| 10-06 07:14 | ui/polish-1006 审查+合并 main + main CI 绿（10-06 早，「检查+有问题修+没问题合并」指令） | 对象：ui/polish-1006 @05f31a0b（2 commits：①压缩模型行删「选择…」按钮→整行点击弹框；②与 LimitsSliderRow 风格对齐〔+分隔线/−textAlign〕；③定时任务编辑页删… |
| 10-06 08:00 | 定时任务"无法删除"修复（用户报告 → 根因+修复+分支 CI 绿，10-06 早） | 症状：用户报告「新加的那个定时任务怎么不能删除啊？」（07:42 新会话，"无法删除新增定时任务"）。 |
| 10-06 14:26 | chore/backlog-batch-1006 核查完成（云端分支检查，CI 绿 @5c5cd126） | 对象：chore/backlog-batch-1006 @ 5c5cd126（1 commit，相对 main 纯删除 2 文件 -20 行）：删 .commitmsg（backlog §57）+ 删 CompactOr… |
| 10-07 07:45 | usage-stats-perf-1007 施工完成：SQL 聚合 + 部分索引 + off-main（分支 CI 绿，未合并） | 交付：分支 fix/usage-stats-perf-1007 @ aabd3636（2 commits：0665c6b 主改动 6 文件 +286/−119 + aabd363 测试桩补齐 3 文件 +9），CI ru… |
| 10-08 12:31 | mount 卸载双 pop 修复完成（10-08，分支 CI 绿停，未合并） | 现象（用户报）：设置→挂载文件夹→详情页取消挂载确认后，跳回设置主页而非列表页。 |
| 10-08 12:43 | ↳ fix/mount-unmount-double-pop-1008 审查 + 合并完成（2026-10-08） | 对象：@9fd86c4b（1 文件 +19/−3，MountDetailScreen.kt）：删卸载路径显式 onBack()（null-guard 成唯一 pop）+ remove 包 withContext(NonC… |
| 10-09 00:40 | sweep-B 修复完成（thinking 规则缓存冷启动预热，sweep-1008 唯一 HIGH） | 任务：/var/minis/shared/sweep-1008/fix-tasks/B.md。分支 fix/sweep-p1-thinking-cache-1008 @ 4f6646f8b6ac94e0081ee7279… |
| 10-09 14:32 | docs 更新不需要触发分支 CI（用户纠正） | 实证：docs/dev-history-1009 档案推送后我惯性 dispatch 了 build-apk.yml（#1961），用户指出「文档更新不是不用触发 CI 吗」→ 已 API 取消。 |
| 10-09 20:21 | 子代理修复（7275bbd4）真机验证完成（2026-10-09 晚，构建 v1.0.0+1962 @20:16:52 装机） | A（frontmatter 全链路）✅：file_write 带 subagent:true 的 SKILL.md → auto-discover（20:18:21.835，与事故同时序）→ spawn_agent 直接… |
| 10-10 14:19 | 沙盒内构建 Android APK 完整配方（已验证跑通） | 无 Android Studio/Gradle，纯 CLI 工具链，全部单文件来自 Google Maven，aarch64 原生可跑： |
| 10-10 14:57 | CI 触发判据修正：记特例会漏，要记判据（2026-10-10，用户同日第二次纠正） | 事：assets/skills/semantic-memory 回灌（.py/.md/.json 三个纯文本文件）后，我惯性 dispatch 了 build-apk → run #1964，用户纠正「文本更新不需要触发… |
| 10-10 15:28 | 「无验证面就不等 CI」并入 GLOBAL（2026-10-10，用户：「像这种几乎没有风险的就不用等了」） | 事：合并 chore/retire-kg-skill-note-1010 → main 8cb19750 后，我用 delay 轮询等 #1966 出结论；用户叫停——纯文本改动不必等。 |
| 10-12 15:26 | 修复「切模型后流式状态被抹」竞态 → 分支 fix/streaming-claim-race-1012 @ 41f024e7（CI #1970 success） | 根因（真机日志 + 代码双证）：_isStreaming 清除权归属不清。旧 job 的 finally 用「streamJob === 自己 + jobEpoch == streamingClaimEpoch」双门判断… |
| 10-12 16:40 | 第 21 道 CI 门落地：claim_release_guard（2026-10-12，用户批准后实施） | 产物：scripts/scan/claim_release_guard.py（已合 main，PR #6 @ 4fa0cb20）。scan.sh 21 门 + 自测 89 用例全过；Scan Gate(PR) CI 绿。 |
| 10-12 16:46 | claim_release_guard 门合并状态独立复核（2026-10-12 晚，本会话） | 用户令「检查分支上那个新加的门，没问题就合并」——该分支（ci/claim-release-guard-1012 / PR #6）已于 16:40 由前会话合并进 main @ 4fa0cb20，远端分支已删，云端只剩 … |
| 10-12 17:42 | T2 属性测试落地：ProviderToolPairingPropertyTest（PR #8，CI 全绿） | 产物：src/android/app/src/test/java/com/rikkaminis/app/provider/ProviderToolPairingPropertyTest.kt（+356 行，分支 test… |
| 10-12 18:14 | ↳ proactive-1012 三任务合并完成（2026-10-12 晚） | 用户放行后执行合并：PR #7/#8/#9 全部合入 main，顺序 #7 206e7110 → #8 38ad442d → #9 13949bb0（tip），逐支 merge-base --is-ancestor 核实… |
| 10-12 19:34 | T4 完成：think-tag 扫描器双缺陷修复（PR #10，CI 全绿，停在分支未合并） | 分支 fix/thinktag-scan-integrity-1012 @ 123e2cc（父 = main 13949bb0），3 commits：11e3ab6（A：删 bufLower 改源坐标搜索 indexOf… |
| 10-12 20:40 | T5 完成：MarkdownParser 差分探针（PR #11，CI 绿，停在分支未合并） | 分支 test/md-parser-probe-1012 @ 85f3535c（2 commits：910ff2b 修复 / 85f3535 测试 58 例），PR #11（base main ← 分支），CI run … |
| 10-12 20:41 | ↳ PR 合并：test/md-parser-probe-1012 进 main（用户放行「没问题就合并」） | PR 合并：test/md-parser-probe-1012 进 main（用户放行「没问题就合并」） |
| 10-13 00:38 | 更新：personal-build.yaml 已改为只 rename/upload/publish nextplayer | 更新：personal-build.yaml 已改为只 rename/upload/publish nextplayer-arm64-v8a.apk（其余 ABI 构建后 rm -f 丢弃；gradle splits 未… |
| 10-13 00:42 | ↳ 分支核查+合并：feat/minimal-home-1012 进 main（用户令「有问题就修，没问题就合并」） | 审查结论：无可疑行，未发现 bug/次生问题。 diff 741 行全读（head 400 行 + sed 尾段，file_read 上限截断后补读过），核查项全过： |
| 10-13 09:32 | 胶囊消失诊断 + 修复 fix/cancel-stale-publish-1013（2026-10-13） | 用户报告：模型执行任务正在调用工具时发消息打断，有一定概率正在调用的工具胶囊消失。 |
| 10-13 09:45 | 分支核查+合并：fix/cancel-stale-publish-1013 进 main（2026-10-13，用户令「检查…没问题就合并」） | 机械项（verify_branch.sh，exit 0，核对时刻 2026-10-13T01:42:39Z）：工作树 clean、branch_tip=worktree_HEAD=00fe3f9c、scan 21 pas… |
| 10-14 10:20 | S2 会话收口：port/v114-thumb-ceiling @ 4ffb437，CI run 38499083898 | S2 会话收口：port/v114-thumb-ceiling @ 4ffb437，CI run 38499083898 success（head_sha 逐字符一致），停在分支未合并。 |
| 10-14 10:36 | S4 完成：v1.14 聊天健壮性组合移植（port/v114-chat-gates @ a7f6e8e7，CI #2001 绿） | 用户令：「读这个文件并立刻执行：/var/minis/shared/port-v114/tasks/S4.md」（+一次「继续」）→ 条目制移植，停止节点 = 分支 CI 绿。 |
| 10-14 11:25 | ↳ v1.14 Tier1+S4 已合并 main（2026-10-14） | 结果：4 分支（capture+hud / thumb-ceiling / session-index / chat-gates）→ 合并前核查 8 项×4 全过（含 4 套变异负控）→ merge 1cd1ff31b→… |
| 10-14 14:22 | FIX-7 完成：KeyRoulette 明文密钥落盘修复（fix/sweep1014-key-roulette @ 41e6a796f，CI #2004 绿） | 任务书 shared/sweep-1014/tasks/FIX-7.md；报告 shared/sweep-1014/reports/FIX-7.md；装置 shared/work/sweep1014-fix/f7/。 |
| 10-14 14:41 | FIX-1 完成：PersistentShell eval 特殊内建隔离（fix/sweep1014-shell @ 3e6f4824，CI #2008 绿） | 报告：/var/minis/shared/sweep-1014/reports/FIX-1.md；装置 shared/work/sweep1014-fix/f1/。 |
| 10-14 14:55 | FIX-9 完成：FileReadTool 流式读（fix/sweep1014-fileread @ 54ed955e，CI #38521242226 绿，停在分支） | 用户令「读任务书并立刻执行：/var/minis/shared/sweep-1014/tasks/FIX-9.md」。基线 d5d2be59f，1 文件 +144/−27。报告 shared/sweep-1014/rep… |
| 10-14 15:06 | ↳ 停止节点达成，停在分支未合并：fix/sweep1014-webapp @ dfd1cf29（2 commits，3 文 | 停止节点达成，停在分支未合并：fix/sweep1014-webapp @ dfd1cf29（2 commits，3 文件 +200/−8），CI build-apk #2013 success，head_sha 逐字符… |
| 10-14 16:06 | FIX-3 完成：FastioTar + 只读挂载守卫（fix/sweep1014-sandbox-io @ bf01735cd，CI #2027 绿） | 报告：/var/minis/shared/sweep-1014/reports/FIX-3.md；装置 /var/minis/shared/work/sweep1014-fix/f3/jvm/（extract_f3.py… |
| 10-14 17:13 | 验证：JVM 23/23 绿（planQueueAdvance + decideSingleSend verbatim | 验证：JVM 23/23 绿（planQueueAdvance + decideSingleSend verbatim 抽取，junit4 + kotlinx-coroutines 1.9.0 jar 复用 review… |
| 10-14 17:20 | FIX-R18 完成：media D11 回调 release + play() check-then-act（fix/ | FIX-R18 完成：media D11 回调 release + play() check-then-act（fix/sweep1014-media @ 406fac3，CI #2036 绿） |
| 10-14 17:21 | ↳ FIX-R20 完成：fileui B29 大小恒空白 + B31 二次 decode + D13 静态单例 + P9 | FIX-R20 完成：fileui B29 大小恒空白 + B31 二次 decode + D13 静态单例 + P9 读上限（fix/sweep1014-fileui @ bce2e21，CI #38539977894… |
| 10-14 17:34 | FIX-R21 完成：B30 日期漂移副本 + B33 播放器三面 + B35 分块解码 + P11 正则提升（fix/sweep1014-ui-misc @ b6c9cf96，CI #2040 绿） | 用户令「读 /var/minis/shared/sweep-1014/tasks/FIX-R21.md 并立刻执行」。基线 main d5d2be59f，4 文件 +198/−96。CI build-apk run 38… |
| 10-14 21:21 | A1/A2 已修（fix/rv1014-calendar @ d13ceef，2 commits，CI 2077 suc | A1/A2 已修（fix/rv1014-calendar @ d13ceef，2 commits，CI #2077 success，bridge 双源）：A1 = doList Instances URI 补 begin… |
| 10-14 21:27 | FIX-RV-C 完成：C1 reply 窗口 + C2 语义 0 条守卫 + C3/C4 OAuth 名对齐 + C5 NaN（CI #2082 绿） | 用户令「读 /var/minis/shared/review-1014/fix-tasks/FIX-RV-C.md 并立刻执行」。分支 fix/rv1014-mcp-security @ be76810c，6 文件 +1… |
| 10-14 21:33 | 用户令「读 FIX-RV-G 并立刻执行」。 基线 main e63608ee9，7 文件 +271/−15。CI bu | 用户令「读 FIX-RV-G 并立刻执行」。 基线 main e63608ee9，7 文件 +271/−15。CI build-apk run 38570303281（#2081）success，head_sha 逐字符… |
| 10-14 21:42 | FIX-RV-F 完成：CaptureEpochGuard 单 CAS 槽修复（fix/rv1014-speech @ a28559bb，CI #2085 绿） | 修复：ProviderSpeechRecognitionEngine.kt CaptureEpochGuard——flag+epoch 合并进单个 AtomicReference<Session?>（Session 持 … |
| 10-14 23:10 | 根治 §97：内置技能资产落盘改内容哈希门控（fix/skill-asset-hash-gate @ 87db4a3，CI #2095 绿，停在分支未合并） | 用户令：选「根治」（二选一：即时 bump 版本 / 根治改内容哈希）。基线 main a7123cf82，3 文件 +351/−34。 |
| 10-15 00:11 | §97 内容哈希门控在真机未生效——根因定性：DB 行来源不是 bundled（原本就有，非本次改出） | 验证对象：设备包 1.0.0+2095（lastUpdate 23:21:46）= CI run #2095 = fix/skill-asset-hash-gate @ 87db4a333（run #2096 = mai… |
| 10-15 00:37 | §97 根治二段：fix/bundled-row-adoption-1015 @ 5da7153，CI #38596976020 绿，停在分支未合并 | 修法：真机定性（DB 行来源=file）落地为行标签领养——installBundledSkill 在版本门前，若行带外来标签但声明（name/description/version）仍等于内置载荷（用 parseSki… |
| 10-15 06:33 | FIX-B 完成：技能/备份恢复修复（fix/audit-1015-skillbackup @ 6645cce，CI #2099 绿，停在分支未合并） | 任务：shared/audit-1015/fix-tasks/FIX-B.md；报告 shared/audit-1015/fix-reports/FIX-B.md；工作树 shared/work/fix-b-1015/r… |
| 10-15 07:04 | FIX-A 完成：上下文链路修复（fix/audit-1015-context @ 24fa628，CI #2101 绿，停在分支未合并） | 任务：/var/minis/shared/audit-1015/fix-tasks/FIX-A.md（核实报告 VERIFY-REPORT.md 先读）。基线 main e5e733b，分支 fix/audit-1015… |
| 10-15 07:36 | FIX-C 完成：AgentLoop 状态机 + 沙盒 Shell（audit-1015，分支 fix/audit-1015-loop-sandbox @ 3c5f916b，CI #2104 绿，停在分支未合并） | 用户令：读 /var/minis/shared/audit-1015/fix-tasks/FIX-C.md 并立刻执行。基线 main e5e733b3（核对一致）。 |
| 10-15 15:03 | verify-1015 修复批次收口：P0/P1 全部 8 条，分支 CI #2112 绿（2026-10-15） | 任务：核实报告（shared/work/verify-audit-1015/VERIFY-REPORT.md）P0/P1 8 条 → 分支 fix/audit-verify-1015（基线 main 6fe4771）3 … |
| 10-15 16:32 | FIX-A4 完成：消息级 token 估算双计/多路不一致 → 收敛为单一 wire 对齐实现（2026-10-15，分支 `fix/audit2-estimate-1015` @ `37533a3a`，CI #2115 绿，停在分支未合并） | 任务：shared/work/verify-audit2-1015/fix-tasks/FIX-A4.md（审计报告 BUG-06）。基线 main 891d755。报告 shared/work/verify-audit… |
| 10-15 16:44 | FIX-A1 收口：audit2 四项 P1 修复，分支 CI #2118 绿（2026-10-15） | 用户令：读 shared/work/verify-audit2-1015/fix-tasks/FIX-A1.md 并立刻执行 → 「构建完了」。 |
| 10-15 16:45 | FIX-A2 完成：browser bridge 凭证化 + dataUrl 上限（fix/audit2-bridge-1015 @ e4eccc4，CI 38705282566 绿，停在分支） | 任务书：shared/work/verify-audit2-1015/fix-tasks/FIX-A2.md；报告 fix-reports/FIX-A2.md；装置 shared/work/audit2-fix/a2/p… |
| 10-15 18:07 | FIX-A1-2 收口：注释级修正（"provably true" 不成立）—— 分支 CI #2120 绿（2026-10-15） | 用户令：读 shared/work/verify-audit2-1015/fix-tasks/FIX-A1-2.md 并立刻执行。 |
| 10-15 18:48 | FIX-A6-2 收口：精确缩放解码窗口改回 `< 2 × maxEdge`，分支 CI #2126 绿（2026-10-15） | 任务：读 shared/work/verify-audit2-1015/fix-tasks/FIX-A6-2.md 执行。修 audit2 第二道门发现的行为回归（PERF-03 的修法把 (maxEdge, 2×max… |
| 10-15 21:13 | FIX-A 收口：audit3 F-01 FastioTar 根条目 tar-slip（P0）— 分支 CI #2129 绿，停在分支未合并（2026-10-15） | 任务：shared/work/audit3-1015/fix-tasks/FIX-A.md（基线 main ca8b95d1）。分支 fix/audit3-fastiotar-1015 @ 894ff37d（2 文件 +… |
| 10-15 21:14 | FIX-F 收口：F-08 会话路径解析不再因拒绝而全局回退（fix/audit3-sessionpath-1015 @ 6a20a481，CI #2128 绿，停在分支未合并） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-F.md 执行。基线 main ca8b95d1。commits：c0f2afa9（本体）+ 6a20a481（同族第 8 站点 + … |
| 10-15 21:18 | FIX-E 收口：audit3 F-07 mount guard 四处绕过全修，分支 CI 38739081695 绿（2026-10-15） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-E.md 执行（基线 main ca8b95d1）。报告 shared/work/audit3-1015/fix-reports/FI… |
| 10-15 21:19 | FIX-B 收口：audit3 F-02 预算停出环（P0）→ 分支 CI #2131 绿，停在分支（2026-10-15） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-B.md 执行。基线 main ca8b95d1，分支 fix/audit3-agentloop-1015 @ 09bb9df6，CI… |
| 10-15 21:52 | FIX-D 收口：F-05 取消桥前移 + F-06 内存回收不重试（fix/audit3-cancel-retry-1015 @ a9631bf9，CI #2133 绿，停在分支） | 任务书 shared/work/audit3-1015/fix-tasks/FIX-D.md；报告 fix-reports/FIX-D.md；基线 main ca8b95d1；两提交 b76a0c5e（本体）+ a963… |
| 10-15 21:52 | FIX-G 收口：NodeRegistry 摊销驱逐 + 图片解码有界化（fix/audit3-perf-small-1015 @ 993fed7e，CI #2134 绿，停在分支） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-G.md 立刻执行（audit3 F-12/F-13）。基线 main ca8b95d17，单提交 993fed7e，6 文件 +80… |
| 10-15 21:55 | FIX-C 交付收口：model-use 跨进程边界四块（F-03 P0 + F-04 + F-08 + F-10）—— 分支 CI #2135 绿，停在分支（2026-10-15） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-C.md 并立刻执行（基线 main ca8b95d1）。报告 shared/work/audit3-1015/fix-reports… |
| 10-15 22:32 | audit3 收口完成：七支合并 main `984cbdd801dc` + 并集树 CI #2142 绿 + 文档归档进 main（2026-10-15，用户令「他们的任务都完成了」→「跑完了」） | 结果：七支（FIX-A..G）全部合并 → main 984cbdd801dc；并集树 CI run #2142（id 38749571877）success @ 984cbdd801dc，head_sha 逐字符一致、… |
| 10-16 00:09 | deep-audit FIX-E 收口：model-use 主进程 provider 与告警合并（`fix/da1015-modeluse-proc` @ `4a244d93`，CI #2144 绿，停在分支） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-E.md 立刻执行（deep-audit BUG-03，P1）。基线 main e10fb2aa；报告 shared/work… |
| 10-16 00:31 | FIX-C（deep-audit-1015 BUG-02）收口：失败运行被当成「正常完成」→ 分支 CI #2146 绿，停在分支（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-C.md 执行。基线 main e10fb2aa；分支 fix/da1015-sched-errorprop @ 5483ec… |
| 10-16 00:33 | FIX-D 交付收口：KaTeX 桥 arity（BUG-05 P1）+ 缓存回收（ARCH-03 子项）—— 分支 CI #2147 绿，停在分支（2026-10-15） | 任务：读 shared/work/deep-audit-1015/fix-tasks/FIX-D.md 并立刻执行（基线 main e10fb2aa）。报告 shared/work/deep-audit-1015/fix… |
| 10-16 00:47 | FIX-B 收口：deep-audit BUG-01a/01b + BUG-04（fix/da1015-agentloop-report @ b6dcd671d9，CI #2145 绿，停在分支） | 任务书 shared/work/deep-audit-1015/fix-tasks/FIX-B.md；报告 fix-reports/FIX-B.md；基线 main e10fb2aa53fb；3 commits（d209… |
| 10-16 02:00 | deep-audit-1015 收口完成：5 支合并 + 文档云端同步 + CI #2150 跨支交互失败 fix-forward（2026-10-15，用户令「既然检查没有问题的话，那就合并吧。触发之后顺便更新文档，包括云端」） | 五支合并：A retry-wire / B agentloop-report / C sched-errorprop / D katex-bridge / E modeluse-proc 全部 --no-ff 进 mai… |
| 10-16 09:43 | FIX-G 收口：deep-audit-1016 8c/8d（通知行去 AI 头像 + compact 分割线谓词读全部块）—— 分支 CI #2155 绿，停在分支（2026-10-16） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-G.md 立刻执行。基线 main 04e74c53325a；分支 fix/da1016-chat-sysinfo @ ee3… |
| 10-16 09:44 | crash-1016 修复交付：列表项吞缩进围栏 + `\(\)` 空 latex（分支 `fix/1016-md-list-fence-inline-math` @ `25e5400`，CI #2153 绿，停在分支） | 任务：用户「修」（承接上一轮的闪退诊断）。基线 main 04e74c53325a（= 设备版本 1.0.0+2152 的 head_sha，逐字符一致）。报告 shared/work/crash1016-fix/REP… |
| 10-16 09:51 | FIX-F 收口：deep-audit-1016 B-7b/B-7c（定时任务/无头会话状态闭环）—— 分支 CI #2157 绿，停在分支（2026-10-16） | 任务书 shared/work/deep-audit-1016/fix-tasks/FIX-F.md（纪律文档实际在 shared/work/deep-audit-1016/COMMON.md 与 TRIAGE.md，不… |
| 10-16 09:55 | FIX-B 收口：deep-audit-1016 9a/9c/9d（+9b 顺路）—— 分支 CI #2156 绿，停在分支（2026-10-16） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-B.md 立刻执行（基线 main 04e74c53325a）。报告 shared/work/deep-audit-1016/… |
| 10-16 09:59 | FIX-E 收口：fallback 重算预算 + blocked-empty 不记 Success + shell 预算门终态（fix/da1016-agentloop-budget @ d996e19e，CI #2160 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-E.md 执行（6a/6b P1 + 5a + 5b/6c 判定）。基线 main 04e74c53；单 commit d99… |
| 10-16 10:07 | FIX-D 收口：deep-audit-1016 执行闸门三件——4a heavy permit 泄漏回滚 + 4b runInterruptible 可取消等待 + 4c 主线程 GC 分发（fix/da1016-exec-gate @ edb45cbd，CI #2158 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-D.md 立刻执行（基线 main 04e74c53）。报告 fix-reports/FIX-D.md；装置 fix-d/pr… |
| 10-16 10:21 | FIX-A 收口：deep-audit-1016 ISSUE-01（Gemini base 缺 `/v1beta`，P0）—— 分支 CI #2162 绿，停在分支（2026-10-16） | 任务书 shared/work/deep-audit-1016/fix-tasks/FIX-A.md；报告 fix-reports/FIX-A.md；装置 fix-a/probe/（jvm/run.sh 一键复跑 + g… |
| 10-16 10:31 | deep-audit-1016 FIX-C 交付收口：沙箱路径归一化 / 只读写守卫 / boot 门（`fix/da1016-sandbox-path` @ `cce8919`，CI #2163 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-C.md 立刻执行（基线 main 04e74c5）。报告 shared/work/deep-audit-1016/fix-r… |
| 10-16 11:03 | deep-audit-1016 最终收口（2026-10-16）：main `812d0587` + 并集树 CI #2164 green + Release 已发布 + 文档上云 | 并集树 CI #2164（id 38842323044）success @ 812d0587，24 步全 success。云端实证（日志抓取，绕 Authorization 重定向）：①第 3 步扫描门 Summary:… |
| 10-16 13:05 | FIX-F 收口：deep-audit-1017 ISSUE-09 marker 分块边界误报完成（fix/da1017-marker-boundary @ 57dcd79a，CI #2165 绿，停在分支） | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-F.md 立刻执行（基线 main e748b14b）。报告 fix-reports/FIX-F.md；装置 fix-f/pr… |
| 10-16 13:13 | FIX-E 收口：deep-audit-1017 ISSUE-08 ShizukuOffloadHandler 三个 argv 缺陷（fix/da1017-shizuku-args @ 2a5cddd1，CI #2166 绿，停在分支） | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-E.md 立刻执行（基线 main e748b14）。报告 fix-reports/FIX-E.md；装置 fix-e/pro… |
| 10-16 13:15 | FIX-C 收口：deep-audit-1017 ISSUE-04（子代理回合交还契约）—— fix/da1017-subagent-residue @ bf124e8d，CI #2167 绿，停在分支 | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-C.md 立刻执行（基线 main e748b14，浅克隆）。报告 fix-reports/FIX-C.md（189 行）；装… |
| 10-16 13:21 | FIX-D 收口：deep-audit-1017 ISSUE-06（calendar freebusy 漏周期日程 + doUpdate 丢 --all-day）—— 分支 CI #2169 绿，停在分支 | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-D.md 立刻执行（基线 main e748b14）。报告 fix-reports/FIX-D.md；装置 fix-d/pro… |
| 10-16 13:24 | FIX-A 收口：deep-audit-1017 ISSUE-01（OpenAI 系 tool-result 图 100% 丢失，P0）—— 分支 CI #2170 绿，停在分支（2026-10-16） | 任务 shared/work/deep-audit-1017/fix-tasks/FIX-A.md；报告 fix-reports/FIX-A.md；装置 fix-a/probe/（pre-P1..P4 取证、pre-fi… |
| 10-16 13:31 | FIX-G 收口：deep-audit-1017 ISSUE-10（a11y 截图/点击 + speech，P2 4 子项）—— fix/da1017-a11y-speech @ a304097，CI #2171 绿，停在分支 | 任务书 shared/work/deep-audit-1017/fix-tasks/FIX-G.md（基线 main e748b14）；报告 fix-reports/FIX-G.md；装置 fix-g/probe/（pr… |
| 10-16 13:54 | deep-audit-1017 FIX-B 收口：per-turn 可见内容谓词 + turn-tail 终态收尾（`fix/da1017-loop-finalize` @ `9e6ba91`，CI #2173 绿，停在分支） | 任务：shared/work/deep-audit-1017/fix-tasks/FIX-B.md（ISSUE-03 累计状态污染 + ISSUE-02 终态过早）。报告 fix-reports/FIX-B.md（含 §… |
| 10-16 14:27 | deep-audit-1017 收口完成：七支全绿 + 并集树 CI #2174 绿，停在并集分支（2026-10-16，用户令「按流程」→「他们都完成了」→「跑完了」） | 输入：附件 shared-4dc0dfc3.md（1993 行，ISSUE-01..10）。工作目录 shared/work/deep-audit-1017/。 |
| 10-16 14:31 | ↳ deep-audit-1017 合并 main + 文档更新（2026-10-16，用户令「合并，并且更新文档」） | 合并（用户放行后三项核查全过）： |
| 10-16 17:44 | deep-audit-1016c FIX-B 收口：offload 路径/intent/日期（`fix/da1016-offload-path-intent` @ `17d1860`，CI #2177 绿，停在分支） | 任务：shared/work/deep-audit-1016c/fix-tasks/FIX-B.md（ISSUE-02 P0 读逃逸 + ISSUE-09 P1 intent 授权 + 子项 C 日期范围）。基线 e3e… |
| 10-16 17:44 | FIX-C 收口：deep-audit-1016c ISSUE-03A/B/C（browser 并发三缺陷）—— fix/da1016-browser-concurrency @ c8a44bf，CI #2176 绿，停在分支 | 任务：读 shared/work/deep-audit-1016c/fix-tasks/FIX-C.md 并立刻执行（基线 main e3ef74545bcc）。报告 shared/work/deep-audit-101… |
| 10-16 17:46 | FIX-G 收口：deep-audit-1016c ISSUE-10(A) sysinfo 合并窗口线程竞态（fix/da1016-sysinfo-race @ 1785682e，CI #2178 绿，停在分支） | 任务：shared/work/deep-audit-1016c/fix-tasks/FIX-G.md（用户令「读这个文件并立刻执行」；中途换代理重克隆）。报告 fix-reports/FIX-G.md（216 行）；装置… |
| 10-16 17:58 | FIX-H 收口：OffloadRssProbe 锁范围（deep-audit-1016c ISSUE-11）—— fix/da1016-rssprobe-lock-scope @ c18d1b4，CI #2179 绿，停在分支 | 修法：record() 的 synchronized(st) 原来只护 4 个字段自增；LEAK-SUSPECT 检查-置位、govern 判定、累计复位全在锁外（NativeOffload.kt Semaphore(2… |
| 10-16 18:04 | FIX-E 收口：deep-audit-1016c ISSUE-07（TTS --voice 失效 + VoiceTextSanitizer 零接线，P1）—— fix/da1016-tts-voice @ 8f6b5fb，CI #2180 绿，停在分支 | 任务书 shared/work/deep-audit-1016c/fix-tasks/FIX-E.md；报告 fix-reports/FIX-E.md；装置 probe/jvm/；背板 backlog §136。基线 m… |
| 10-16 18:16 | FIX-F 收口：deep-audit-1016c ISSUE-09A/09B（调度日 settle 泄漏 + 裸日期/DST 边界）—— fix/da1016-schedule-date-bounds @ c6df69c0，CI #2182 绿，停在分支 | 修法：09A = finalizeRun 的 completeRun 日期实参从 settle 时刻 LocalDate.now() 改为 fire 时刻捕获（Dispatch data class 增 schedule… |
| 10-16 18:19 | FIX-A 收口：deep-audit-1016c ISSUE-01（P0 续写分支持久化丢块 + dbId 回填）—— `fix/da1016-loop-persist-slice` @ `a509a491`，CI #2183 绿，停在分支 | 任务 shared/work/deep-audit-1016c/fix-tasks/FIX-A.md；报告 fix-reports/FIX-A.md；装置 fix-a/probe/；基线 main e3ef74545bc… |
| 10-16 18:50 | FIX-D 收口：deep-audit-1016c auto-compact 治理四支（`fix/da1016-compact-governance` @ `feef7cd`，CI #2184 绿，停在分支） | 任务：读 shared/work/deep-audit-1016c/fix-tasks/FIX-D.md（先证再改）。报告 fix-reports/FIX-D.md；装置 fix-d/probe/{jvm/,mutati… |
| 10-16 19:15 | deep-audit-1016c 收口完成：八支全绿 + 并集树 CI #2185 绿 + 文档已进 main，停在并集分支（用户令「他们都完成了，按流程走，不要忘了更新文档」） | 收口全链：独立核 CI 8/8（每支 conclusion=success 且 head_sha == 远端 tip == 报告 sha）→ diff 检查（8 支夹带 0 / 冲突标记 0 / println(main… |
| 10-16 19:18 | ↳ deep-audit-1016c 合并完成：main `c38cbba752c2`（用户令「合并」），9 分支已清 | 合并前核查（三项全过）：①检查 = 并集相对合并基（e3ef745）49 文件恰为授权集、docs 0 触碰、冲突标记 0、调试残留 0；②验证 = CI #2185 head_sha 三方逐字符一致 + 本地 scan… |
| 10-16 22:21 | FIX-G 收口：deep-audit-1018 ISSUE-11（OAuth 回调双解码 + socket FD 泄漏，P2）—— fix/da1018-oauth-callback @ d94cf63，CI #38912447230 绿，停在分支 | 修法两半：①parseCallbackQuery(uri.rawQuery) 纯函数（单次解码，key/value 各 URLDecoder.decode(raw.replace("+","%2B"))，WebDavCl… |
| 10-16 22:22 | FIX-C 收口：deep-audit-1018 ISSUE-07（CompactPin 同消息双钉 + 紧凑域裸真值陷阱，P1）—— `fix/da1018-compact-pin` @ `9b34241`，CI #38912121098 绿，停在分支 | 任务 shared/work/deep-audit-1018/fix-tasks/FIX-C.md（+ COMMON.md/TRIAGE.md 实读自上级目录）；报告 fix-reports/FIX-C.md；装置 re… |
| 10-16 22:36 | FIX-H 收口完成：deep-audit-1018 ISSUE-12a/b（:modelservice 重复读盘 + 无界线程，P2）—— fix/da1018-modelsvc-io @ 5fce91199109947504653926e30c274c8f5bb64b，CI #38914243668 绿，停在分支 | 任务书 shared/work/deep-audit-1018/fix-tasks/FIX-H.md；报告 fix-reports/FIX-H.md（14890 B，10 节齐全、待填 0）；装置 repo-h/repo… |
| 10-16 22:43 | FIX-E 收口：deep-audit-1018 ISSUE-09（备份恢复漏重映射 + 漏打包蒸馏层，P1）—— `fix/da1018-backup-remap` @ `e3d16b4`，CI #2190 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-E.md 并执行（先读 COMMON/TRIAGE）。报告 shared/work/deep-audit-1018/fix-r… |
| 10-16 22:49 | FIX-D 收口：deep-audit-1018 ISSUE-08（12 处 `.dao.loadMessages(` 直调收敛 + ChatRepository 容错入口）—— fix/da1018-dao-loadmessages @ fd5cfe6，CI #38916547458 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-D.md 并立刻执行（先读 COMMON/TRIAGE，基线 main c38cbba752c2）。报告 shared/wor… |
| 10-16 23:13 | FIX-F 收口：deep-audit-1018 ISSUE-10（GroupRouter health 无锁 + SCM 锁内 resume）—— fix/da1018-router-locks @ aed2560，CI #2194 绿，停在分支 | 任务 shared/work/deep-audit-1018/fix-tasks/FIX-F.md；报告 fix-reports/FIX-F.md；装置 repo-f/probe/（arms.py/arms.sh/ci_… |
| 10-16 23:22 | FIX-A 收口：deep-audit-1018 ISSUE-01（P0 截断工具调用）+ ISSUE-02（P1 子代理 reasoning 丢失）+ ISSUE-12c（P2 叠楼守卫）—— fix/da1018-turn-integrity @ 00026c3 两提交，CI #2195 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-A.md（先证再改）。报告 fix-reports/FIX-A.md；装置 repo-a/probe/（durable，含 c… |
| 10-16 23:36 | FIX-B 交付收口：deep-audit-1018 ISSUE-04/06/03（分支 CI #2196 绿，停在分支） | 身份链：分支 fix/da1018-offload-wiring，基线 main c38cbba，HEAD afee1fa37e08cb65a0244955eb803a4fdd8c61df（两提交：ac4e549 生产+… |
| 10-17 00:05 | deep-audit-1018 收口完成：8 支全绿 + 并集树 CI #2197 绿（停在并集分支，未合并 main） | 输入：用户附件 shared-d48335c9.md（2566 行 / 12 工单）。基线 main c38cbba752c2。 |
| 10-17 07:17 | FIX-F 收口：deep-audit-1019 ISSUE-06（parts_json 三路漏斗绕过，P1）—— fix/da1019-partsjson-funnel @ d87169f，CI #2207 dispatch，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-F.md；报告 fix-reports/FIX-F.md；装置 repo-f/probe/（durable）。基线 main e4… |
| 10-17 07:26 | FIX-H 交付收口：deep-audit-1019 ISSUE-08（BrowserUseManager.destroy() 漏唤醒 + data: URL `+` 损坏）—— fix/da1019-browser-destroy @ d04adcf，CI #2203 绿，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-H.md（+ COMMON/TRIAGE 实读）；报告 fix-reports/FIX-H.md；装置 repo-h/probe/… |
| 10-17 07:29 | FIX-C 收口：deep-audit-1019 ISSUE-03（FastioTar USTAR 字段 off-by-one + 归档拒绝 exit 0）—— fix/da1019-fastio-tar-bounds @ 96e9f54，CI #2202 绿，停在分支 | 任务书 shared/work/deep-audit-1019/fix-tasks/FIX-C.md；报告 fix-reports/FIX-C.md；装置 repo-c/probe/（durable：jvm/harnes… |
| 10-17 07:30 | FIX-B 收口：deep-audit-1019 ISSUE-02（Pass1 过早翻 RUNNING → 工具状态发布归位）—— fix/da1019-loop-tooltoolstatus… 实为 fix/da1019-loop-toolstatus @ eeea556，CI #2206 绿，停在分支 | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-B.md（先读 COMMON/TRIAGE，基线 main e416551）。报告 fix-reports/FIX-B.md；… |
| 10-17 07:37 | FIX-A 收口：deep-audit-1019 ISSUE-01（extra_headers/custom-endpoint 信封方言三份副本合一）—— fix/da1019-modeluse-proto @ 3bd0193，CI #2205 绿，停在分支 | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-A.md（先读 COMMON/TRIAGE，基线 main e416551）。报告 fix-reports/FIX-A.md（… |
| 10-17 07:38 | FIX-F 停止节点达成：CI run 2207 completed success @ d87169f3934d184 | FIX-F 停止节点达成：CI run #2207 completed success @ d87169f3934d184c7bff21e56c9017e82bf4cb7b，head_sha 三方逐字符一致（本地 HEA… |
| 10-17 07:38 | FIX-I 收口：deep-audit-1019 ISSUE-09（BrowserRssProbe/ProviderRssProbe 锁范围）—— fix/da1019-rssprobe-lock-scope @ ace1887，CI #2204 绿，停在分支（2026-10-17） | 任务书 shared/work/deep-audit-1019/fix-tasks/FIX-I.md；报告 fix-reports/FIX-I.md；装置 repo-i/probe/（durable）。基线 main e… |
| 10-17 07:38 | FIX-J 收口：deep-audit-1019 ISSUE-10（contacts argv 分发 + --file 相对路径）—— fix/da1019-offload-cli-args @ bfa6f8e0a9fc，CI #2208 绿，停在分支 | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-J.md（先证后改，基线 main e416551）。报告 fix-reports/FIX-J.md；装置 repo-j/pr… |
| 10-17 07:46 | FIX-G 收口：deep-audit-1019 ISSUE-07（KatexWebViewPool ready 竞态 + ChatViewModelStore 别名表锁外读，P1）—— fix/da1019-katex-store-race @ d440e9b，CI #2199 绿，停在分支 | 任务 shared/work/deep-audit-1019/fix-tasks/FIX-G.md；报告 fix-reports/FIX-G.md（10964 B，14 节齐全）；装置 repo-g/repo/probe… |
| 10-17 08:04 | da1019 FIX-E 收口：session model resolution isEnabled 门（fix/da1019-session-enabled @ 9b2a013，CI #2209 绿，停在分支） | 任务：读 shared/work/deep-audit-1019/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 shared/work/deep-audit-1019/fix-repor… |
| 10-17 08:33 | deep-audit-1019 收口完成：10 支全绿 → 试合树 `integration/da1019` @ `3f73c61`，CI #2209 success（停在分支，未合并 main） | 输入：用户附件 shared-28e12c77.md（2517 行 / 10 工单，本批第三份报告）。基线 main e416551。用户「按流程来吧」→ 第一道门 10/10 成立（判级修正 1 条：ISSUE-01 … |
| 10-17 10:31 | FIX-H 收口：deep-audit-1020 ISSUE-08（打印路径无界读 OOM，P2）—— fix/da1020-print-bounded-read @ a59510c，CI #2212 绿，停在分支（2026-10-17） | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-H.md；报告 fix-reports/FIX-H.md；装置 repo-h/probe/（durable：extract.py… |
| 10-17 10:32 | deep-audit-1020 FIX-D 收口：WebViewHolder currentUrl 单源化（fix/da1020-webview-holder-url @ 11d326fd，CI #2213 绿，停在分支） | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-D.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-D.m… |
| 10-17 10:33 | FIX-F 收口：deep-audit-1020 ISSUE-06（深链缺 6 类设置分支 + 5 条 Routes 未 encode）—— fix/da1020-deeplink-routes @ 049a182，CI #2215 绿，停在分支 | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-F.md；报告 fix-reports/FIX-F.md；装置 repo-f/probe/（durable）。基线 main 9… |
| 10-17 10:36 | da1020 FIX-B 收口：Shizuku argv 20 开关声明 + group 按位置取（fix/da1020-shizuku-args @ 9990218，CI #2214 绿，停在分支） | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-B.md；报告 fix-reports/FIX-B.md；装置 repo-b/probe/（durable）。基线 main 9… |
| 10-17 10:39 | da1020 FIX-A 收口：ISSUE-01 一行修复（ProviderRepository.fetchModels 漏传 context）—— fix/da1020-provider-models-cache @ 17c7791，CI #2216 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-A.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-A.m… |
| 10-17 10:44 | FIX-E 收口：da1020 ISSUE-05 语音链路四块（fix/da1020-voice-cancel-ua @ 97102a49，CI #2218 绿，停在分支） | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md；装置 repo-e/repo/p… |
| 10-17 10:49 | FIX-G 收口：deep-audit-1020 ISSUE-07（memory_rollup 门禁 + provider UA 字段）—— fix/da1020-memory-gate-provider-fields @ fd3acf3，CI #2220 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-G.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-G.m… |
| 10-17 10:50 | FIX-C 收口：deep-audit-1020（BrowserUse/Fastio 相对路径 + tar 前缀）—— fix/da1020-offload-relpath-tar @ be8def62，CI #2219 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-C.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-C.md；装置 repo-c/probe/… |
| 10-17 11:21 | deep-audit-1020 收口完成：8 支全绿 → 试合树 `integration/da1020` @ `bdc6d68`，CI #38997214449 **success**（停在分支，未合并 main）+ 文档三件套同步 | 输入：用户附件 shared-c9689fbd.md（8 工单）。基线 main 942b0111d（60 门）。用户「按流程来吧」→ 第一道门 8/8 成立 → 8 支派发（minis-sessions-cli sen… |
| 10-17 11:25 | ↳ deep-audit-1020 合并 main（用户放行「合并」，2026-10-17） | 合并前核查（三项全过）：①31 文件全在预期域（11 门脚本 + 18 生产/测试 .kt）逐支归属对齐、冲突标记/调试残留 0；②合并树 scan 66/0 + test_scan 238、head_sha 逐字符三方… |
| 10-17 11:50 | 真机验证：deep-audit-1020 八支修复（设备 1.0.0+2221 = CI #2221 / 试合树 bdc6d68） | 身份链闭合：设备 base.apk sha256 029be18c…81db97（14,370,617 B，装于 11:26:45）与 CI run #2221（id 38997214449）artifact 逐字节一致… |
| 10-17 12:32 | 沙箱存储清理：/var/minis 11.2G → 580M（用户报「缓存数据又上去了」，2026-10-17） | 构成与处置（Android app cache 本身只有 23.9MB，真正的积累在沙箱）： |
| 10-17 13:19 | FIX-E 收口：deep-audit-1021 ISSUE-06（SkillFileViewerScreen 保存丢返回值 + 主线程写盘，P1）—— fix/da1021-skill-file-save @ `dc3d30a`，CI #2224 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-E.md 并执行（先证再改 / 分支隔离 / 自建工作树 repo-e）。基线 main d0c1c9… |
| 10-17 13:20 | FIX-F 收口：deep-audit-1021（WebApp asset URL 单源 + AddToHome 清理闭环）—— fix/da1021-webapp-asset-url-cleanup @ ab9c247，CI #2226 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-F.md（8 支并发派发，我领 FIX-F）。报告 fix-reports/FIX-F.md；装置 r… |
| 10-17 13:21 | FIX-D 收口：deep-audit-1021 ISSUE-05（ChatMenuSettingsScreen 监听键前缀失配 + 排序 State 不重读，P1）—— fix/da1021-chatmenu-listener @ ecb2fc5，CI #2223 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-D.md 并执行（先证再改、分支隔离、自建工作树 repo-d）。基线 main d0c1c92。报告… |
| 10-17 13:32 | FIX-A 收口：deep-audit-1021 ISSUE-01（技能恢复 id 贯通）+ ISSUE-10（会话覆盖清理闭环）—— fix/da1021-skill-restore-session-cleanup @ 433430f，CI #2229 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-A.md 立刻执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pr… |
| 10-17 13:34 | FIX-H 收口：deep-audit-1021 ISSUE-11（MinisOpenUrlBroker.offer(Uri) 漏 scheme 校验，P2 半截修复）—— fix/da1021-open-url-broker-scheme @ b47fd77，CI #2230 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-H.md 立刻执行。报告 fix-reports/FIX-H.md；装置 repo-h/probe/（… |
| 10-17 13:35 | FIX-B 收口：deep-audit-1021（ISSUE-02 merge 导入 id 规范化 + ISSUE-09 备份字段齐全）—— fix/da1021-import-id-roundtrip @ 6beb225，CI #2228 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-B.md 并执行。报告 fix-reports/FIX-B.md；装置 repo-b/probe/（留… |
| 10-17 13:41 | FIX-G 收口：deep-audit-1021 ISSUE-08（SpeechRecognitionManager 录音中切引擎 → 在途引擎失控）—— fix/da1021-speech-active-engine @ 6b9e39e，CI #2231 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-G.md 并执行（先证再改、分支隔离、自建 repo-g）。报告 fix-reports/FIX-G.… |
| 10-17 14:10 | deep-audit-1021 收口完成：11 工单 / 8 支全绿 → 并集树 `integration/da1021` @ `315c8bd`，CI #39007801195 success（停在分支，未合并 main） | 输入：用户附件 Rikkaminis_Full_Audit_Report__4_.md（1580 行，ISSUE-01..11）+「按流程来吧」。基线 main d0c1c9219（= 1020 合并树，66 门）。用户… |
| 10-17 14:30 | deep-audit-1021 最终确认：main CI #2233 **completed success**（补上一条「在跑」的收尾） | main @ ba4daf69（= 代码合并 396adc4 + 文档合并 ba4daf6）触发的主 CI run #2233 success。至此 deep-audit-1021 全链闭合：8 支支 CI 绿 → 并集… |
| 10-17 16:53 | FIX-B 收口：deep-audit-1022 ISSUE-02（草稿晋升后子面板仍绑 `__new__` 路由参数）—— fix/da1022-draft-session-binding @ f700db8，CI #2234 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-B.md 并执行。报告 fix-reports/FIX-B.md（8 节）；装置 repo-b/pro… |
| 10-17 17:04 | FIX-H 收口：deep-audit-1022 ISSUE-10 + backlog §1021-V 打包项（fix/da1022-webview-share-url @ 0328f9d，CI #2235 绿，停在分支）—— 2026-10-17 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-H.md 并执行（先证再改、repo-h 自建）。报告 fix-reports/FIX-H.md（8 … |
| 10-17 17:05 | FIX-G 收口：deep-audit-1022 ISSUE-07（Cookie 域名列表+删除/清空 UI 闭环）+ ISSUE-09（四工具异常分支丢 toolTitle）—— fix/da1022-cookie-and-tooltitle @ 051da7f，CI #2236 绿，停在分支 | 任务 shared/work/deep-audit-1022/fix-tasks/FIX-G.md（+COMMON）；报告 fix-reports/FIX-G.md（8 节）；先证 pre-fix/G-evidence.… |
| 10-17 17:18 | FIX-A 收口：deep-audit-1022 ISSUE-01（跨进程媒体 base64 契约，DEFAULT 折行 vs 严格解码）—— fix/da1022-offload-base64-wire @ afbff5e，CI #2238 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-A.md 并执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pro… |
| 10-17 17:20 | FIX-E 收口：deep-audit-1022 ISSUE-05（自定义模型 ID 改名完整性，P2）—— fix/da1022-entry-id-integrity @ 61d201f7，CI #2237 绿，停在分支 | 任务书 shared/work/deep-audit-1022/fix-tasks/FIX-E.md；报告 fix-reports/FIX-E.md（8 节）；装置 repo-e/probe/（jvm/run.sh+ou… |
| 10-17 17:20 | FIX-C 收口：deep-audit-1022 ISSUE-03 + ISSUE-08（MediaPlayerManager 释放/锁 + BrowserHistory.save 快照）—— fix/da1022-player-history-locks @ d21ecab6，CI #2239 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-C.md 并执行（先证再改、分支隔离、装置留盘不入库）。报告 fix-reports/FIX-C.md… |
| 10-17 17:23 | FIX-F 收口：deep-audit-1022 ISSUE-06（镜像 ViewModel 跨线程快照写 + 主线程 IO + 测速自动选源半途）—— fix/da1022-mirror-vm-thread @ 0d4f11d，CI #2240 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-F.md 并执行（先证再改）。基线 main ba4daf69（克隆后逐字符核对）。报告 fix-re… |
| 10-17 17:28 | FIX-D 收口：deep-audit-1022 ISSUE-04（OpenMinis 兼容层 `appendV1Suffix` 极性反转 + 四字段漏迁）—— fix/da1022-openminis-compat @ 59fb69f，CI #2241 绿，停在分支 | 任务书 /var/minis/shared/work/deep-audit-1022/fix-tasks/FIX-D.md（+COMMON.md）；报告 fix-reports/FIX-D.md（8 节，16727→11… |
| 10-17 17:59 | deep-audit-1022 收口完成：8 支全绿 → 并集树 `integration/da1022` @ `8128896945fc`，CI #2242 success（停在分支，未合并） | 输入：用户附件 rikkaminis_full_audit_report.md（1529 行 / ISSUE-01..10）+「走流程」→「他们都完成了」。基线 main ba4daf69（74 门）。工作目录 shar… |
| 10-17 20:28 | FIX-B 收口：deep-audit-1023 ISSUE-02（P1 模型详情页保存损毁模态/覆写）—— fix/da1023-model-override-parity @ 09e377a，CI #2244 绿，停在分支 | 任务：读 shared/work/deep-audit-1023/fix-tasks/COMMON.md 与 FIX-B.md 并执行。基线 main cd9e97b，门禁基线 85/85。报告 shared/work/… |
| 10-17 20:33 | FIX-I 收口：deep-audit-1023 ISSUE-10（自动备份流式导出/拉取净化/主线程列目录，P1）—— fix/da1023-backup-streaming-fetch @ `69b7d83fc053`，CI #2245 绿，停在分支 | 任务：读 shared/work/deep-audit-1023/fix-tasks/COMMON.md + FIX-I.md（先读 TRIAGE §2.10，报告行号不可信）。基线 main cd9e97b。工作树 s… |
| 10-17 20:41 | deep-audit-1023 FIX-A 收口：FileProvider 路径覆盖 + 大消息导出（分支 CI #2246 绿，停在分支） | 交付：fix/da1023-fileprovider-paths @ af7ecfcddbf026b50b10fc2a8e583c5e214d6799（基于 main cd9e97bf），4 文件 +788/−15。CI… |
| 10-17 20:47 | deep-audit-1023 FIX-H 收口：file:// URL 统一 decodeUriPath（fix/da1023-file-uri-decode @ 6f69546，CI #2249 绿，停在分支） | 修法（ISSUE-08，3 文件 +34/−9 生产）：①WebPreviewBottomSheet.openExternalFromSheet file:// 分支 Uri.parse(url).path → deco… |
| 10-17 20:47 | deep-audit-1023 FIX-F 收口（ISSUE-06 会话 id 绑定，fix/da1023-session-id-binding @ ab7e632，CI #2247 绿，停在分支） | 修法：ChatViewModelStore.resolveSessionId 公开委托（单源 delegate private resolveKey，katex 门钉形状保留）+ ChatScreen 4 组站点（图片 … |
| 10-17 20:49 | FIX-G 收口：deep-audit-1023 ISSUE-07（memory 两处 UI 接线断链 + 删除路径主线程 IO）—— fix/da1023-memory-ui-wiring @ 6469641，CI #2248 绿，停在分支 | 身份链三方逐字符一致：本地 HEAD = 远端 tip = CI head_sha = 64696416243378008b64d8b5ca2085efe619591e；run #2248（id 39032697053）… |
| 10-17 21:06 | deep-audit-1023 FIX-E 收口：SKILL.md frontmatter 写路径完整性（fix/da1023-skill-frontmatter-parity @ a02a445d，CI #2252 success，停在分支） | 修法三件（+同族新发现）：①极性单一出口 resolveMergeBase(newFrontmatter, diskContent) 内存新值优先（writeSkillMd 原为 existing ?: skill.fr… |
| 10-17 21:28 | FIX-C 收口：deep-audit-1023 ISSUE-03 + ISSUE-09（模型条目复合主键守卫 + 紧凑钉二级引用）—— fix/da1023-entry-key-provider-parity @ 7106210，CI #2254 绿，停在分支 | 任务：读 shared/work/deep-audit-1023/fix-tasks/{COMMON.md,FIX-C.md} 并立刻执行（基线 main cd9e97b，门禁 85/85；报告行号不可信，先证再改）。报… |
| 10-17 21:51 | deep-audit-1023 收口完成：9 支全绿 → 并集树 integration/da1023 @ 00f24b87，CI #2255 success（停在分支，未合并） | 输入：用户附件 rikkaminis_full_audit_report_v2.md（10 工单全 P1）+「走流程吧」→「他们都完成了」。基线 main cd9e97b（85 门 / 276 夹具）。工作目录 shar… |
| 10-17 22:43 | 交付：trace 步骤标注门 `feat/trace-step-tag-1017` @ c046769，CI #2257 绿，停在分支 | 任务：用户对 trae-agent 研究结论拍板「做吧」→ 把种子收敛成正式门。基线 main f688c97（da1023 批次已并入，门 96 / 夹具 298）。 |
| 10-18 00:33 | 交付：deep-audit-1024 FIX-D（ISSUE-04 MCP OAuth 凭据/沙盒桥接文件生命周期，P1）—— `fix/da1024-mcp-oauth-bridge-lifecycle` @ `b611732a`，CI #39048727885 success，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-D.md 立刻执行（先证再改）。基线 main f688c97（da1023 收口树）。报告 fix-reports/FIX-… |
| 10-18 00:38 | FIX-B 交付收口：deep-audit-1024 ISSUE-02（Gemini base URL 四处分叉 → 统一出口）—— fix/da1024-gemini-base-single-source @ ac1bb9e，CI #2264 绿，停在分支 | 身份链：基线 main f688c97；单提交 ac1bb9e854dbbc01c74ef60d48f343de069de2c4；远端 tip == 本地 HEAD == CI head_sha 三方逐字符一致；CI r… |
| 10-18 00:39 | FIX-E 收口：deep-audit-1024 ISSUE-05（MODEL_ENTRY_DETAIL 路由二次 decode，P2）—— fix/da1024-model-entry-route-no-double-decode @ e0ad364，CI #39048800163 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md（193 行 8 节齐全）；装置 re… |
| 10-18 00:40 | FIX-I 收口：deep-audit-1024 ISSUE-09（ModelUse 相对路径未接 resolveMaybeRelative，P1）—— `fix/da1024-model-use-relative-path` @ `0360290`，CI #39048853946 绿，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-I.md 并立刻执行（先读 COMMON / TRIAGE §3.3 / REPORT-1024 ISSUE-09）。基线 m… |
| 10-18 00:42 | FIX-A 交付：deep-audit-1024 ISSUE-01（share_inbound 三合一，P1）—— fix/da1024-share-inbound-fileprovider-io @ 11096d9，CI #2263/run 39049248443 绿，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-A.md 并立刻执行（先读 COMMON.md/TRIAGE.md §3.1/REPORT ISSUE-01）。基线 main… |
| 10-18 00:43 | FIX-G 交付收口：deep-audit-1024 ISSUE-07（cacheDir/shared 主线程 IO + 无 TTL）—— `fix/da1024-shared-cache-io-and-prune` @ `cf55123`，CI #39049093263 绿，停在分支 | 基线 main f688c97；单提交 5 文件 +487/−17（3 生产 + 1 测试 + 1 门）；报告 shared/work/deep-audit-1024/fix-reports/FIX-G.md；装置 re… |
| 10-18 00:44 | FIX-H 收口：deep-audit-1024 ISSUE-08（Reset All 后 UI 不刷新 + 内存 session 授权/拒绝未清，P2）—— fix/da1024-offload-permission-reset-sync @ db52d99，CI #2262 绿，停在分支 | 身份链：基线 main f688c97c6877e3b801eeacd33d58c2371d1b803f → 单提交 db52d99786f64171fcb4abe4643a60036787764a；远端 tip == … |
| 10-18 00:46 | FIX-F 收口：deep-audit-1024 ISSUE-06（思考规则编辑器保存丢 reasoningEcho/id + 静默改写 wireFormat）—— fix/da1024-thinking-rule-editor-preserve @ 0babeaa，CI #2265 绿，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-F.md 立刻执行（先读 COMMON/TRIAGE §3.4）。报告 fix-reports/FIX-F.md（8 节齐、0… |
| 10-18 00:49 | FIX-C 交付收口：deep-audit-1024 ISSUE-03（P1 二级引用漏清理 + ModelGroupsScreen 无监听）—— `fix/da1024-compact-pin-cascade-and-listener` @ `f7f1419beb32`，CI #39049819858 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md；装置 repo-c/probe/（durable：jvm/{… |
| 10-18 09:53 | FIX-G 交付收口：deep-audit-1025 ISSUE-07（P1 工具错误分支丢失胶囊标题）—— fix/da1025-tool-title-error-branch @ e46cdee，CI #39087080210 绿，停在分支 | 任务：读 shared/work/deep-audit-1025/fix-tasks/FIX-G.md 立刻执行（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-G.md（8 节齐）；装置 re… |
| 10-18 09:54 | FIX-F 收口：deep-audit-1025 ISSUE-06（P1 Compose remember 键陈旧：modelCount 卡 0 + 删除守卫失效）—— `fix/da1025-provider-remember-keys` @ `8460ef06`，CI #2277（run 39087103229）绿，停在分支 | 任务：读 shared/work/deep-audit-1025/fix-tasks/FIX-F.md（先读 COMMON/TRIAGE，基线 main bcb6569）。报告 fix-reports/FIX-F.md（… |
| 10-18 10:00 | FIX-H 交付收口：deep-audit-1025 ISSUE-08（挂载探针 + 日志清理离开主线程）—— `fix/da1025-mount-probe-io` @ `d66c7a6`，CI #39087410687 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-H.md（P2，8 节报告齐）。基线 main bcb6569。交付 3 文件：2 生产（+32/−5）+ 1 门脚本扩展（238… |
| 10-18 10:05 | FIX-D 收口：deep-audit-1025 ISSUE-04（browser_tabs/<sid>.json 免疫生命周期清理，P1）—— fix/da1025-browser-tabs-lifecycle @ c1dbf37，CI #39087510098 success，停在分支 | 工单 shared/work/deep-audit-1025/fix-tasks/FIX-D.md（先证再改）；报告 fix-reports/FIX-D.md（8 节 / 169 行）；装置 repo-d/probe/（… |
| 10-18 10:07 | FIX-I 交付收口：deep-audit-1025 ISSUE-09（P2 Provider RPC 守卫/字段不对称）—— fix/da1025-provider-rpc-symmetry @ `6b40595`，CI #2276（39087616148）success，停在分支 | 任务书 shared/work/deep-audit-1025/fix-tasks/FIX-I.md；报告 fix-reports/FIX-I.md（8 节齐）；装置 repo-i/probe/（durable）；bac… |
| 10-18 10:09 | FIX-C 交付收口：deep-audit-1025 ISSUE-03（a11y 双表失步，P1）—— `fix/da1025-a11y-status-verbs` @ `8b73bee`，CI #2277 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md（9 节）；装置 repo-c/probe/{prefix,j… |
| 10-18 10:09 | FIX-E 交付：deep-audit-1025 ISSUE-05（EnvVarEntry.group 统一配置面漏层）—— fix/da1025-envvar-config-group @ 3eda8d9，CI #39087430292 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-E.md（先证再改）；报告 fix-reports/FIX-E.md（8 节 / 0 占位）；装置 repo-e/probe/（p… |
| 10-18 10:21 | FIX-B 收口：deep-audit-1025 ISSUE-02（CrashFrequencyDetector 主线程长耗时 I/O + 漏挂 TTL 清理，P1）—— `fix/da1025-crash-share-io` @ `1612c0b`，CI 绿，停在分支 | 任务 fix-tasks/FIX-B.md（先证再改，COMMON 实际在上级目录）。报告 fix-reports/FIX-B.md（105 行，§1b 记首轮 CI 红）；装置 repo-b/probe/（durabl… |
| 10-18 11:05 | deep-audit-1025 收口完成（用户令「他们都完成了」）：9 支并入并集树，CI 绿 @ 3bf0834 | 身份链：基线 main bcb6569 → 9 支（A..I）→ 并集树 integration/da1025 3bf08341717bf6817970a912efd4ee6c8046fd40（9 次 --no-ff 零… |
| 10-18 11:27 | ↳ deep-audit-1025 合并 main 完成（用户令「合并」）：main `dc04fc3`，代码+文档双落地 | 身份链：基线 bcb6569 → 并集树 integration/da1025 3bf0834（CI #2279 绿，树 ca4bff07）→ main e5c1712（--no-ff）→ 文档归档 → main dc0… |
| 10-18 14:03 | FIX-A 交付：deep-audit-1026 ISSUE-01（ConcurrencyPrefs 缺重预热监听 + 陈旧缓存回滚，P1）—— `fix/da1026-prefs-priming` @ `cf5c8fd`，CI #2281 绿，停在分支 | 任务书 shared/work/deep-audit-1026/fix-tasks/FIX-A.md（COMMON 实读自上级目录）；报告 fix-reports/FIX-A.md（149 行 8 节无 TODO）；装置… |
| 10-18 14:10 | FIX-G 收口：deep-audit-1026 ISSUE-07（ShareReceiverActivity 主线程同步导入+删暂存，P1）—— `fix/da1026-share-import-io` @ `2e680cd`，CI #2283 绿，停在分支 | 任务 fix-tasks/FIX-G.md（COMMON.md 实际在上级目录 deep-audit-1026/，不在 fix-tasks/）。报告 fix-reports/FIX-G.md（8 节 170 行，无待填）… |
| 10-18 14:12 | FIX-E 交付收口：deep-audit-1026 ISSUE-05（ProvidersCollection customBaseURL 漏守卫，P1）—— `fix/da1026-providers-baseurl-guard` @ `53c9d08`，CI run 39101827796 绿，停在分支 | 任务书 fix-tasks/FIX-E.md（COMMON 实际在上级目录，已读）。基线 main dc04fc3。交付 2 文件 +261/−7。 |
| 10-18 14:13 | FIX-B 收口：deep-audit-1026 ISSUE-02（OffloadOutput.OUTPUT_FLAGS 漏 help/h + SpeakOffloadHandler 漏 booleanFlags，P1）—— `fix/da1026-offload-help-flag` @ `b3f9eb4`，CI 绿，停在分支 | 任务 fix-tasks/FIX-B.md（COMMON 实际在上级目录）；报告 fix-reports/FIX-B.md；装置 repo-b/probe/（durable）。基线 main dc04fc3。交付 3 文… |
| 10-18 14:18 | FIX-C 收口：deep-audit-1026 ISSUE-03（三屏 remember 漏绑字段值 + 刷新 Toast 读旧快照，A=P1/B=P2）—— `fix/da1026-detail-remember-keys` @ `05c9ade`，CI #2286 绿，停在分支 | 任务 fix-tasks/FIX-C.md（先读 COMMON，实际在上级目录 shared/work/deep-audit-1026/COMMON.md）；报告 fix-reports/FIX-C.md（8 节 / 1… |
| 10-18 14:18 | FIX-D 收口：deep-audit-1026 ISSUE-04（`minis-config` 加模型落库 provider 存实例 UUID + 未 trim + providerKeyMap 缺 displayName 键）—— `fix/da1026-models-provider-field` @ `4be9d09`，CI #2285 绿，停在分支 | 任务 fix-tasks/FIX-D.md（先读 COMMON，实际在上级目录）。报告 fix-reports/FIX-D.md；装置 repo-d/probe/（树内，.gitignore:141 已含 probe/）… |
| 10-18 14:30 | FIX-F 收口：deep-audit-1026 ISSUE-06（CSV 预览绕过 readCapped 无界读 + 两预览 remember 无键）—— `fix/da1026-csv-bounded-read` @ `011812a`，CI run 39103139228 绿，停在分支 | 任务 fix-tasks/FIX-F.md（COMMON 在上级目录）。报告 fix-reports/FIX-F.md（8 节 / 12794 B / 无占位符）；装置 repo-f/probe/（durable、不入库… |
| 10-18 15:05 | FIX-H 收口：deep-audit-1026 ISSUE-08（group context-limit 写漏斗不同步 + `get groups` 缺两键，P2）—— `fix/da1026-group-sync` @ `0353112`，CI #2288 绿，停在分支 | 任务 fix-tasks/FIX-H.md（COMMON 实读自上级目录）；报告 fix-reports/FIX-H.md（8 节 / 16002 B / 无 TODO）；装置 repo-h/probe/（jvm/{ex… |
| 10-18 15:32 | deep-audit-1026 收口完成（用户令「按流程来」→ 8 支全绿 → 并集树 CI 绿） | 身份链：基线 main dc04fc3 → 8 支（A..H，逐支 CI 绿）→ 并集树 integration/da1026 ad0c5d78c9024750df52bc7c57c3eba5e338b6f0（8 次 -… |
| 10-18 15:52 | ↳ deep-audit-1026 合并 main + 文档归档完成（用户令「合并，不要忘了文档」） | 合并：integration/da1026（@ ad0c5d7）--no-ff 合入 main → 3f99b30ce582c454754184405ad0db7c08d546f5；树对拍 main^{tree} == … |
| 10-18 16:40 | ARCH-01 交付：ProviderWriteRules 单源校验层（`fix/da1026-arch01-provider-write-rules` @ `5737f75`，CI run 39110944268 success，停在分支未合并） | 任务 ARCH-COMMON.md + 蓝图 ARCH-01 小节（Provider / Model 跨面写入规则收口）。报告 fix-reports/ARCH-01-REPORT.md（11223 B，§1–§9）；装… |
| 10-18 16:47 | ARCH-03 收口：ProviderType 编译期穷举元数据契约 —— `fix/da1026-arch03-provider-type-registry` @ `22a15ed`，CI run 39110831683 success，停在分支 | 任务：pinned 指令 = 读 shared/work/deep-audit-1026/ARCH-COMMON.md，执行同目录 ARCH-BLUEPRINT.md 的「ARCH-03」小节。基线 main 9d182… |
| 10-18 16:48 | ARCH-02 收口：PrimedPrefs 自治重预热契约（`fix/da1026-arch02-primed-prefs` @ `0f9f7cd`，CI #2293 绿，停在分支） | 任务 /var/minis/shared/work/deep-audit-1026/ARCH-COMMON.md（工单 = ARCH-BLUEPRINT.md 的 ARCH-02 小节）。报告 fix-reports/A… |
| 10-18 17:18 | ARCH 批次收口完成（用户「他们完成了」/「跑完了」）：3 支并入并集树，CI 绿 @ 407bdd4 | 身份链：基线 main 9d18269 → 3 支 → 并集树 integration/da1026-arch @ 407bdd4（3 次 --no-ff 零冲突 + 门注册提交）。 |
| 10-18 17:49 | ARCH 批次合并 main + 文档归档完成（用户「合并，并更新文档」）+ CI 假红取证（flaky） | 身份链：并集树 integration/da1026-arch 407bdd4（CI #2294 success）→ main 4822410（--no-ff，树对拍 9f793097 逐字节一致）→ 文档合并 → ma… |
| 10-18 20:55 | FIX-D 收口：deep-audit-1027 ISSUE-05（RootfsVM 两次 verifyIntegrity 主线程直调）—— fix/da1027-d-rootfs-io @ a7aa249，CI #39127888788 绿，停在分支 | 修法：repairRootfs(ctx) 整体并入一个 viewModelScope.launch，两处 manager.verifyIntegrity()（基线 :173 点击回调内 / :191 launch 内 M… |
| 10-18 21:02 | FIX-C 收口：deep-audit-1027 ISSUE-04（Xunfei 空转写被当成功结果返回）—— `fix/da1027-c-xunfei-transcript` @ `d01da0d`，CI #39128561098 绿，停在分支 | 任务：读 shared/work/deep-audit-1027/fix-tasks/FIX-C.md + COMMON.md（注意：COMMON.md 在 上级目录，不在 fix-tasks/）。报告 fix-repo… |
| 10-18 21:38 | deep-audit-1027 收口完成（用户「他们都跑完了」）：7 支并入并集树，CI 绿 @ 5030841 | 身份链：基线 main 1f6ca13 → 7 支（A–G）→ 并集树 integration/da1027 @ 5030841（7 次 --no-ff 零冲突）→ 并集 CI #2303（39131097372）suc… |
| 10-19 00:56 | FIX-C 收口：deep-audit-1028 ISSUE-02 + ISSUE-05 + ISSUE-06（UI 状态失步 / filesDir 清理接线 / 深链响应式）—— `fix/da1028-c-ui-state-wiring` @ `aaa4cc1`，CI #39145801329 绿，停在分支 | 身份链：基线 main 889d645（tree f12ca356）→ 单提交 aaa4cc1b6267a40e7683c2e4795a575d7e2e93ba（父 = 基线）→ 远端 tip == 本地 HEAD ==… |
| 10-19 01:28 | FIX-B 收口：deep-audit-1028 ISSUE-03 + ISSUE-04（配置作用域 / 外部写回流）—— `fix/da1028-b-config-sync` @ `7a17c5a`，CI #39148284718 绿，停在分支 | 身份链：基线 889d645 → 分支 @ 7a17c5a603da5e1f3590dbfab69bcd139a9f4be8（父=基线，单提交，11 文件 +714/−28）→ CI run 39148284718 wo… |
| 10-19 02:08 | deep-audit-1028 收口完成：8 工单 → 3 支会话（用户令「小的合并」），并集树 CI 全绿 @ 6e6fc9a | 身份链：基线 main 889d645 → 3 支（A/B/C）→ 并集树 integration/da1028 @ 6e6fc9a（tree a777fc76，3 次 --no-ff 零冲突）→ 并集 CI run 3… |
| 10-19 03:04 | deep-audit-1028 合并 main 完成（用户令「合并」）+ main CI 全绿 | 合并：integration/da1028 @ 6e6fc9a（tree a777fc76）--no-ff 合入 main → 83caac66ca6606a3971635cce8f135e7b12a0489；main … |
| 10-19 03:08 | deep-audit-1028 全流程闭环（用户令「合并」「一并做」）：合并 + main CI 绿 + 分支清理 + 文档三件套 | 身份链终态：main 889d645 →（代码合并 83caac6）→（文档合并 05f6fc435910506f9d8c40300db9d57532772328）。远端只剩 main。 |
| 10-19 07:41 | deep-audit-1029 FIX-B 收口：ISSUE-03（记忆编辑屏主线程同步 IO + SOUL 缓存回流）—— `fix/da1029-b-memory-io` @ `98c0fc5`，CI #39176155210 绿，停在分支 | 身份链：基线 main 83caac6 → 单提交 98c0fc59e2cbe307b5e539853dc2a2608b4988e8（3 文件 +254/−26）→ branch dispatch CI 39176155… |
| 10-19 07:45 | FIX-F 收口：deep-audit-1029 ISSUE-07（`android-shizuku-cli exec` 选项作用域，两裂口均 P1）—— `fix/da1029-f-shizuku-argv` @ `df76dfc`，CI #39176352125 绿，停在分支 | 身份链：基线 main 83caac6 → 分支 fix/da1029-f-shizuku-argv @ df76dfc85bdf24e4046bc25ad9626b93df4f4d2a（父 = 基线，单提交，3 文件 … |
| 10-19 07:48 | FIX-D 收口：deep-audit-1029 ISSUE-05（语音族 UA 缺口）—— `fix/da1029-d-voice-ua` @ `4c7ad80`，CI #39176720024 绿，停在分支 | 身份链：基线 main 83caac6 → 单提交 19ceb02（3 生产 .kt +204/−2 + 测试 +204）→ 门扩展 4c7ad80（provider_ua_parity_check.py +261）→ … |
| 10-19 07:50 | FIX-C 收口：deep-audit-1029 ISSUE-04 + ISSUE-09（锁外写回 / 同文件不对称）—— `fix/da1029-c-lock-scope` @ `c09bbe9`，CI #39176651703 绿，停在分支 | 身份链：基线 83caac6 → 分支 @ c09bbe92630d8fbfb5cd7d4c1736aeffbbd3d518（父=基线，单提交，6 文件 +495/−6）→ CI run 39176651703 succ… |
| 10-19 07:54 | FIX-E 收口：deep-audit-1029 I06+I08（UI 状态同步支）—— fix/da1029-e-ui-state @ be75a09，CI #39177457402 绿，停在分支 | 身份链：基线 main 83caac6（da1028 收口树）→ 分支 fix/da1029-e-ui-state @ be75a098fc2ab67a5723c17e797ed3370ee77abe（单提交，4 文件 … |
| 10-19 08:10 | FIX-A (deep-audit-1029) 收口：I01 草稿单源 + I02 深链穷举，分支 CI 绿 @ e76330e，停在分支 | 身份链：基线 83caac6 → 2 提交（5ca7e1a 修法 + e76330e CI 编译红补丁）→ HEAD e76330e999e7daa8e9aa62a552b3117492351657 == 远端 tip … |
| 10-19 08:40 | deep-audit-1029 全流程闭环（用户令「走流程吧，小任务合并」）：9 工单 → 6 支 → 并集树 CI 绿 @ 933a0eb8 | 身份链：基线 main 83caac6（1028 收口树）→ 6 支 → 并集树 integration/da1029 @ 933a0eb8c2fcc7031af63d68dcc054987e6748ad（tree c2… |
| 10-19 09:33 | deep-audit-1029 合并 main 完成（用户令「合并」）+ main CI 全绿 | 合并：integration/da1029 @ 933a0eb8（tree c25d715c）--no-ff 合入 main → b3533179cd842b13de00cecdf6e93573afbe423a（父 = … |
| 10-19 11:43 | deep-audit-1030 FIX-B 收口：ISSUE-03 浏览器 UA 单源（池级分支 + 面板订阅）—— `fix/da1030-b-browser-ua-pool` @ `346e1be`，CI #2320 绿，停在分支 | 身份链：基线 main f7364ec → 单提交 346e1be5700ec5f8dabfed512048c557bf90f45f（4 文件 +476/−18 = 生产 2 / 门 1 / 测试 1 新建）→ 远端 t… |
| 10-19 11:59 | deep-audit-1030 FIX-A 收口：会话生命周期断链 + 同族调用点漏传（I01 + 02）—— 分支 CI 绿 @ ee6f987，停在分支 | 身份链：基线 main f7364ec → 分支 fix/da1030-a-session-callpoints @ ee6f9878d41ab73ac1c7f68a59aa669a9c40a6b3（单提交，9 文件 +… |
| 10-19 12:07 | FIX-D 收口：deep-audit-1030 ISSUE-06（Provider 出站单源：passthrough UA + Azure auth）—— `fix/da1030-d-provider-outbound` @ `d55aee5`，CI #2323 绿，停在分支 | 身份链：基线 main f7364ec → 2 提交（c646c0a 主体 + d55aee5 自查补丁）→ HEAD d55aee582aee4f27a8d566af94cc1ae50146c939 == 远端 tip… |
| 10-19 12:18 | FIX-E 收口（deep-audit-1030 ISSUE-07 + 08 合支）：`fix/da1030-e-guard-slots` @ `22849dc`，CI 39197110222 绿，停在分支 | 身份链三方逐字符：LOCAL == REMOTE == CI head_sha = 22849dc3ff52b630477fed3e56a996276c80c704（基线 f7364ec）。单提交，7 文件 +814/−… |
| 10-19 13:06 | deep-audit-1030 全流程闭环：8 ISSUE → 5 支 → 并集树 CI 绿 @ 97e73e7（用户令「走流程吧，小任务合并」） | 身份链：基线 main f7364ec（src/scripts 与报告基线 b353317 逐字节一致，diff 只在 docs）→ 5 支 --no-ff 零冲突 → 并集树 integration/da1030 @ … |
| 10-19 13:24 | deep-audit-1030 收尾闭环（用户令「继续推」）：合并 main + main CI 绿 + 文档三件套 + 分支清理 7/7 | 身份链终态：基线 main f7364ec → 5 支并集树 integration/da1030 @ 97e73e7（tree 7145a2bf）→ 合并 main 5032e6a（main^{tree} == 97e… |
| 10-19 16:25 | FIX-A 收口（deep-audit-1031 ISSUE-01 + ISSUE-03 合支）：`fix/da1031-a-refs-and-config` @ `86e6979`，CI #2330 绿，停在分支 | 身份链三方逐字符：LOCAL == REMOTE == CI head_sha = 86e69796e10d47c541dbe9d9bcc0f99388fccc46（基线 main 2bc07ec）。单提交 7 文件 +… |
| 10-19 16:33 | FIX-C 收口（deep-audit-1031 ISSUE-04 A1/A2/B1/B2 + ISSUE-05 B）：`fix/da1031-c-sandbox-guards` @ `5062174`，CI #2331 绿，停在分支 | 身份链三方逐字符：LOCAL == REMOTE == CI head_sha = 50621745fdcb66ffdc79e27709b64a4ef65c2701（基线 2bc07ec）。单提交，8 文件 +729/−… |
| 10-19 17:01 | FIX-D 收口（deep-audit-1031 D 支：ISSUE-06 + 07 + 08）—— 分支 CI 绿，停在分支 | 身份链：基线 main 2bc07ec → 4 提交（6cf7058 ISSUE-07 → 54951b3 ISSUE-06 → bc57d68 ISSUE-08 → 8a5f68d 门判据重绑）→ 分支 fix/da1… |
| 10-19 19:35 | FIX-A 收口（deep-audit-1032 ISSUE-01 WebApp 全屏单源 + ISSUE-02 SessionEditSheet 调用点接线）—— 分支 CI 绿，停在分支 | 身份链三方逐字符：LOCAL == REMOTE == CI head_sha = c6978d1dddf5c6de135908ab5701581a38a7ac73（基线 main 09b6c12）。CI run 392… |
| 10-19 19:38 | FIX-B 收口（deep-audit-1032 ISSUE-03 [P1] debug RPC 消息视图三处不对称）— 分支 CI 绿，停在分支 | 身份链：基线 main 09b6c12 → 分支 fix/da1032-b-debug-rpc-parts，单提交 6c46f968abdac5e5b6672a1e9a050f3fe708eb90；LOCAL == or… |
| 10-19 19:47 | FIX-D 收口（deep-audit-1032 ISSUE-05 + 07）：`fix/da1032-d-terminal-mainid` @ `af900f2`，CI #2341 绿，停在分支 | 身份链三方逐字符：基线 09b6c12 → 两提交（fa7e45b 主体 + af900f2 所有权守卫回填）→ LOCAL == REMOTE == CI head_sha = af900f26382dd058288a… |
| 10-19 19:54 | FIX-E 收口（deep-audit-1032 ISSUE-06 P1）：设置屏首屏 reload 线程 + 技能编辑器 remember 键 —— `fix/da1032-e-settings-io-keys` @ `a2106ab0`，CI #39245665334 绿，停在分支 | 身份链三方逐字符：基线 09b6c12 → 两提交（ad733d56 主体 + a2106ab0 告警清理）→ CI run 39245665334 / job 118036843575 success，local ==… |
| 10-19 20:35 | deep-audit-1032 启动+收口前夜：第二道门（8/8 成立）+ 8→5 支合并派发 + 五支全绿 + 并集树 CI 绿 | 输入：附件 shared-ee3fef8b.md（1306 行 / 84,341 B，ISSUE-01..08）。基线 main 09b6c12（= 报声明 2c8c174 的直接后代，diff 仅 docs/dev-h… |
| 10-19 23:13 | FIX-B 收口（deep-audit-1033 ISSUE-02）：`fix/da1033-b-media-guards` @ `a343b015`，CI #39273447241 绿，停在分支 | 身份链三方逐字符：基线 main c6d426a → 单提交 a343b01524b45a5d8cdc8c48c4405113a6a11a4d → CI run 39273447241（job 118131489194）… |
| 10-19 23:48 | FIX-C 收口（deep-audit-1033 ISSUE-03 P1）：android-speech --source 相对路径 —— `fix/da1033-c-speech-cwd` @ `d3037a76`，CI #2348 绿，停在分支 | 身份链三方逐字符：基线 main c6d426a7 → 2 提交（生产 + test/门）→ CI run 39275575813（#2348）success @ 2026-10-19T15:10:26Z→15:28:0… |
| 10-20 00:01 | FIX-D 收口（deep-audit-1033 ISSUE-04：offload 载荷 JSON 契约收敛）—— 分支 CI 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 分支 fix/da1033-d-offload-json @ 9b5af91151126a800b2c430d73d92e4450c4ae51（本地==远端==CI head… |
| 10-20 00:15 | FIX-E 收口（deep-audit-1033 ISSUE-06 + 07 合支）：`fix/da1033-e-settings-derived-state` @ `1a61b87a`，CI job 绿，停在分支 | 身份链三方逐字符：基线 c6d426a → 单提交 1a61b87a6c7ce6cf6222be1e88946e23884bae80（7 文件 +629/−29）→ LOCAL == REMOTE == CI head_… |
| 10-20 00:48 | FIX-A 收口（deep-audit-1033 I01+I05+I08）：`fix/da1033-a-ro-guard-mainio` @ `7b1b1722`，分支 CI #2353 绿，停在分支 | 身份链三方逐字符：基线 main c6d426a7 → 两提交（a99827d5 主体 + 7b1b1722 编译修复）→ LOCAL == REMOTE == CI head_sha = 7b1b172282a01f5… |
| 10-20 00:58 | FIX-A 收口（deep-audit-1033 ISSUE-01 + 05 + 08）：`fix/da1033-a-ro-guard-mainio` @ `7b1b1722`（两提交），CI #2353 绿，停在分支 | 身份链：基线 c6d426a7 → a99827d5（主体）→ 7b1b1722（修 CI 编译事故） → CI run 39284407398（#2353）success；三方逐字符一致。9 文件 +602/−82（7… |
| 10-20 00:58 | FIX-D 收口（deep-audit-1033 ISSUE-04 P1）：offload 载荷 JSON 契约收敛 —— `fix/da1033-d-offload-json` @ `9b5af911`，CI #2349 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 9b5af9115112…；CI run 39276128510（#2349）/ job 118140794036 success（15:13:57Z→15:36:21Z，2… |
| 10-20 00:59 | FIX-E 收口（deep-audit-1033 ISSUE-06 + 07 P1 合支）：usage 模型身份取实时配置 + thinking 弹窗活值 —— `fix/da1033-e-settings-derived-state` @ `1a61b87a`，CI #2350 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 1a61b87a6c7c…；CI run 39278816691（#2350）success。7 文件 +629/−29（4 生产 .kt + 2 测试 .kt + 1 门扩… |
| 10-20 01:19 | deep-audit-1033 收口：五支全绿 → 并集树 `aacdba10` 本地 108/108 + 329/329 → 并集 CI #2354 success（判定停止节点） | 身份链：基线 main c6d426a7（= da1032 收口树）→ 5 支（A 7b1b1722（两提交，修过 CI 编译事故）/ B a343b015 / C d3037a76 / D 9b5af911 / E 1… |
| 10-20 01:41 | deep-audit-1033 合并完成（用户令「检查如果没有问题的话，更新文档然后合并」）：main `b7acf94a` + main CI #2355 success（Publish 执行）+ 档案 2025 条 + 7 支待清 | 合并顺序（用户指令同 1032：文档先合 → 代码后合）： |
| 10-20 08:10 | FIX-B4 收口（deep-audit-1034 B-07+B-08）：弹窗派生态与活数据源脱钩 —— `fix/da1034-b4-dialogs-derived` @ `77a5cae`，CI #2356 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 4 提交 → 77a5cae258921a7156c77dfbfebeb460e83580b8 == git ls-remote == CI head_sha；ru… |
| 10-20 08:29 | FIX-B3 收口（deep-audit-1034 B-05+B-06 两支）：列表块 mathSpans 接线 + 技能面板增量 merge —— `fix/da1034-b3-markdown-sheets` @ `b906313`，CI #2359 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 4 提交（9b87adf markdown · 5032ebc sheet · 38fc024 门 · b906313 测试）→ HEAD b906313ad3a32312e… |
| 10-20 08:31 | FIX-A3 收口（deep-audit-1034 ISSUE-05 + ISSUE-06）：`fix/da1034-a3-photos-video` @ `1fb9484`，CI #2360 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a5cd0bc7ac3e9fee722ebf99ed95a826b → 分支 fix/da1034-a3-photos-video @ 1fb94847885f39b4b2… |
| 10-20 08:39 | FIX-B1 收口（deep-audit-1034 B-01+B-02）：`fix/da1034-b1-onboarding-i18n` @ `81d5e0d0`，CI #2361 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 81d5e0d0ecbef8d9bc9c2f973556d2711884fe48（单提交，5 文件 +731/−30）→ CI #2361（run 39346518996）s… |
| 10-20 09:06 | FIX-A1 收口（deep-audit-1034 ISSUE-01 P0）：备份 zip 不再带走 MCP OAuth 明文桥 —— `fix/da1034-a1-backup-creds` @ `603d297c`，CI #2365 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 19b1c3e（生产+测试）/ 603d297（门扩展）→ 本地==远端==CI head_sha；CI #2365（run 39349095475）success… |
| 10-20 09:21 | FIX-A2 收口（deep-audit-1034 ISSUE-02 + ISSUE-04）：SAF 根判定统一规范化 id + Shizuku 特权路径/uid 回退 —— `fix/da1034-a2-docsprovider-shizuku` @ `69f435a2`，CI #2366 success，停在分支 | 身份链三方逐字符：基线 b7acf94a → 两提交（0aaae779 生产 + 出货测试 / 69f435a2 四门扩展）→ LOCAL == REMOTE == CI head_sha = 69f435a2396cf… |
| 10-20 10:25 | deep-audit-1034 收口完成（用户令「跑完了，没有问题的话就合并，执行后面的步骤吧」）：main `76488f2d` + main CI #2368 success（Publish 执行）+ 档案 2035 条 + 远端只剩 main | 批次终态：基线 main b7acf94a → 8 支（A1–A4 / B1–B4，三方逐字符一致 8/8）→ 并集树 integration/da1034 @ b0ce77591d578616d8d4eed3a0369… |
| 10-20 12:43 | FIX-A2 收口（deep-audit-v14 ISSUE-03 P1）：`FastioTar.writeRegular` 先写 header 后开文件 → 单个坏文件毁整包 —— `fix/dv14-a2-tar-stream` @ `446dd9e1`，CI #2371 绿，停在分支 | 身份链三方逐字符：基线 76488f2d → 8cc278da（生产 + 出货测试）/ 446dd9e1（门扩展）→ 本地 == 远端 == CI head_sha = 446dd9e160e8d0381e6a933b3… |
| 10-20 12:57 | FIX-B2 收口（deep-audit-v14 ISSUE-03 P1 + ISSUE-07 P2）：`fix/dv14-b2-dispose-order` @ `161baecc`，CI #2375 绿，停在分支 | 身份链三方逐字符：基线 76488f2d → 两提交（898312f 生产+装运测试 / 161baecc 门扩展）→ LOCAL == ls-remote == CI head_sha = 161baecc3eb9db… |
| 10-20 12:59 | FIX-B5 收口（deep-audit-v14 ISSUE-06 P1）：`fix/dv14-b5-skill-offmain` @ `db90b8f6`，CI #2374 success，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 3 提交（35cf999 生产+出货测试 · e7221d5 owner 门扩展 · db90b8f fix-forward）→ 远端 ls-remote 同 sh… |
| 10-20 13:04 | FIX-B1 收口（deep-audit-1035 ISSUE-01 + ISSUE-02）：宿主窗口静默空转 + 移动视口极性 —— `fix/dv14-b1-window-viewport` @ `70d7b904`，CI #2379 success，停在分支 | 身份链三方逐字符：基线 main 76488f2dfcbbc79db8eac6df60b7682e187121f6 → 3 提交（df34b84a 生产+出货测试 / 41b5fe00 门 W5e / 70d7b904 … |
| 10-20 13:04 | FIX-A3 收口（deep-audit-1035 / dv14 ISSUE-05 + ISSUE-06）：`fix/dv14-a3-date-perm` @ `6c1778d4`，CI #2377 绿，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 单提交 6c1778d49d098faef678d8d8d5cc8030023dd91f → 远端 tip 同 sha → CI run 39370866204（#… |
| 10-20 13:13 | FIX-A1 收口（deep-audit-v14 ISSUE-01+02+04 三处「名存实亡的别名」）：`fix/dv14-a1-offload-cli-args` @ `328507c6`，CI #2381 success，停在分支 | 身份链三方逐字符：基线 main 76488f2dfcbbc79db8eac6df60b7682e187121f6 → 2 提交（dd70071 生产+测试 / 328507c 门）→ 本地 == git ls-remo… |
| 10-20 13:19 | FIX-A4 收口（deep-audit-v14 ISSUE-07 + ISSUE-08）：`fix/dv14-a4-single-source` @ `571af04`，CI #2382 success，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 6 提交（ff3ae38 ISSUE-07 生产 · a0b5223 ISSUE-08 生产 · b7bd20b 出货测试 · 1147b20 门扩展 · f0b8… |
| 10-20 13:28 | FIX-B4 收口（deep-audit-1035 ISSUE-05 + ISSUE-08）：`fix/dv14-b4-remember-keys` @ `5d0d8ab`，CI #2384 success，停在分支 | 身份链三方逐字符：基线 main 76488f2 → 三提交（9af1e0c 生产+测试 / 5f921ae 门扩展 / 5d0d8ab 补 import）→ LOCAL == REMOTE == CI head_sha… |
| 10-20 14:35 | deep-audit-v14（1035）收口完成：16 工单 → 9 支，九支 + 并集 CI 全绿，未合并 main | 身份链：基线 main 76488f2d → 9 支 → 并集树 integration/dv14-1035 @ 059b63d4180d118c59028416edc5a11fa6d785b5 → 并集 CI #238… |
| 10-20 15:09 | deep-audit-v14 合并 main 完成（用户令「合并」）：main `71882a8e` + main CI #2388 success + 档案 2046 条 + 分支清理 10/10 | 执行顺序（沿用 da1034 的「文档先合、代码后合」）：三项核查（diff 56 文件逐文件过 / 冲突标记 0 / 冻结面 0 触碰 / 本地 HEAD == 并集 CI head_sha / 独立证据 = 新树本地… |
| 10-20 19:23 | FIX-B4 收口（deep-audit-v15 B-07）：MarkdownParser 块结构解析缺口 —— `fix/dv15-b4-markdown-parser` @ `64c115a8`，CI #2392 绿，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 单提交 64c115a88687a1e5d5e24dea305bd7a70e8c5c80（3 文件 = 1 生产 + 2 既有测试；生产 +114/−11，含 KD… |
| 10-20 19:26 | FIX-B2 收口（deep-audit-1036 B-02 + B-08）：`fix/dv15-b2-settings-screens` @ `c9a9662`，CI #2393 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 2 提交（411d551 生产 5 文件 +338/−155 · c9a9662 测试 4 文件 +576）→ 本地 == 远端 == CI head_sha = … |
| 10-20 19:30 | FIX-A4 收口（deep-audit-1036 A-06+A-07）：`fix/dv15-a4-core-services` @ `59dad7c0`，CI #2390 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 两提交（c5d8e38 生产+测试 · 59dad7c 门扩展）→ CI #2390（run 39412287778，job 118592742939）succes… |
| 10-20 19:47 | FIX-A2 收口（deep-audit-1036 / dv15 A2 支）：`fix/dv15-a2-handler-guards` @ `e62e699e`，CI #39414690008 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 单提交 e62e699e5b8c0067c72711b04209f2fde527f4ed → CI run 39414690008（手动 dispatch，11:2… |
| 10-20 19:55 | FIX-A1 收口（deep-audit-1036 ISSUE A-01+A-02+A-08）：`fix/dv15-a1-cli-args` @ `f0e801b3`，CI #2396 绿，停在分支 | 身份链三方逐字符：基线 main 57c9619a8e17693f826f8050efa78ebfc8c0d3fd → 2 提交（080a408 生产 / f0e801b 测试+门）→ LOCAL == REMOTE =… |
| 10-20 20:09 | FIX-A3 收口（deep-audit-1036 ISSUE-A-05 P1）：通知台账同 id 幂等性 —— `fix/dv15-a3-notif-ledger` @ `4e5759ae`，CI #2398 绿，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 3 提交（86273fdb 生产 / 3f648851 测试 / 4e5759ae 门）→ 本地 == git ls-remote == CI #2398 head… |
| 10-20 20:52 | deep-audit-1036（v15）收口完成：16→8 支并入并集树 `7841303`，本地 108 门 + 329 夹具绿，并集 CI #2399 success（attempt 2），**未合并 main** | 身份链：基线 main 57c9619a → 8 支（A1 f0e801b3 / A2 e62e699e / A3 4e5759ae / A4 59dad7c0 / B1 dc632eb0 / B2 c9a96623 /… |
| 10-20 21:44 | deep-audit-1036 全批闭环（2026-10-20 晚）：main `7c38a79d` + main CI #2400 success（Publish 执行）+ 档案 2061 条 + 分支清理 9/9 | 合并链（用户令「合并吧」）：三项核查（46 文件逐文件过 / 冻结面 0 触碰 / 本地 HEAD==远端==CI head_sha / 独立证据 = 并集树本地 108 门+329 夹具双绿）→ 代码并集 --no-f… |
| 10-21 00:45 | FIX-3 收口（deep-audit-1037 / dv16 波 A-06+A-07+A-08）：`fix/dv16-a-provider-config` @ `97e2655c`，CI #2406 绿，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 单提交 97e2655c069a30da6032924736b36b252278e0ef → CI #2406（run 39456718375，job 118745… |
| 10-21 00:48 | FIX-1 收口（deep-audit-1037 / dv16-A 支）：A-01 宽松日期解析 + A-02 负数位置参数 + §1036-FLAKE 归档顺序 —— `fix/dv16-a-offload-hardening` @ `3db9dc778fe71f747b965124dc88c9fe3d59a127`，CI run 39457048630 **success**，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 3 提交（dcab1377 生产 / cd12a67e 测试 / 3db9dc77 门）→ 本地 == ls-remote == CI head_sha = 3db… |
| 10-21 00:59 | FIX-2 收口（deep-audit-1037 v16 波，A-03+A-04+A-05）：`fix/dv16-a-debug-modeluse-skill` @ `fe03ecb5`，CI #2411 ALL GREEN，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 4 提交（2b9309ff 生产 / 0d60f3e4 测试 / 79251553 门 / fe03ecb5 CI 编译修复）→ 本地 == ls-remote =… |
| 10-21 01:19 | FIX-5 收口（deep-audit-1037 / dv16 波 B 支）：chat 流式持久化三缺陷 —— `fix/dv16-b-chat-stream-persist` @ `d34d9928`，CI #2413 绿，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 4 提交（4e8ee68d 生产 / 272f8ffa 测试+门 / 9d7532cc 测试 re-anchor / d34d9928 fastio flake 兑… |
| 10-21 01:50 | deep-audit-1037 收口：并集 `integration/dv16` @ `660803f6` + 并集 CI #2414 success，停在分支（未合并 main） | 六支身份链三方核对（本地==ls-remote==CI head_sha）：FIX-1 3db9dc77 #2407 · FIX-2 fe03ecb5 #2411（报告落后 1 提交：CI 编译错后追了 fe03ecb … |
| 10-21 08:20 | deep-audit-1037 全批闭环：main `528558f4` + main CI #2415 首轮 success（Publish 执行，v1.0.0+2415）+ 档案 2072 条 / 80 天 | 用户令「更新文档，合并」。链条：main 前身 7c38a79d → 文档 eef3851（纯文本例外：不 dispatch / 不等 CI / 三件套与挂载源 sha256 逐文件 SAME / README 覆盖行 … |
| 10-21 10:01 | FIX-2 收口（deep-audit-1038 dv17 波 B-02）：冷启动播种无 remember —— `fix/dv17-2-appnav-coldstart` @ `a962fe16`，CI #2416 绿，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（a903c00 生产 / 61cb427 测试 / a962fe1 门）→ 本地 == git ls-remote == CI head_sha = a9… |
| 10-21 10:12 | FIX-3 收口（deep-audit-1038 / dv17 波 B-03）：4 处预览 WebView 宿主 AndroidView 缺数据源键 —— `fix/dv17-3-webview-androidview-keys` @ `e28b2242`，CI #2417 绿，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 4 提交（a176318d 生产 / 7b2eb60a 测试 258 行 / 2fc171a6 门扩展 / e28b2242 注释精化）→ 本地 == ls-rem… |
| 10-21 10:34 | FIX-1 收口（deep-audit-1038 / dv17 波）：风控提示无消费者 + 草稿晋升后下载记录指向已删目录 —— `fix/dv17-1-browser-risk-downloads` @ `09f46b602776a83b20fd461e8f1d93bced67733d`，CI #2419（39531036966）success，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（1c6bbbc2 生产 / 261fe80a 测试 / 09f46b60 门）→ LOCAL == ls-remote == CI head_sha = … |
| 10-21 10:38 | FIX-5 收口（deep-audit-1038 / dv17 波 B 支）：`fix/dv17-5-prefs-prime-fullscreen-bars` @ `58bc0231`，CI #2420 success，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 三段提交（10799a31 生产 +48 · 14ca6c8f 测试 +281 · 58bc0231 门 +441/−20）→ 本地 == 远端 == CI hea… |
| 10-21 10:45 | FIX-4 收口（deep-audit-1038 / dv17 波 B-04+B-05）：`fix/dv17-4-remember-keys` @ `58e71b2c`，CI #2418 绿，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（e7b8a7eb 生产 / 6684ca55 测试 re-anchor / 58e71b2c 门扩展）→ 本地 == git ls-remote == C… |
| 10-21 11:13 | deep-audit-1038 收口（v17 波 / 面 B 完成）：并集 `integration/dv17` @ `fecb66bb`，并集 CI #2421 **attempt 1 success**，停在分支 | 用户令「小任务合并」落实：8 工单 → 5 支（三类文件集两两互斥 + 同母题单一 owner）——FIX-1 B-01+B-06 / FIX-2 B-02 / FIX-3 B-03 / FIX-4 B-04+B-05（… |
| 10-21 11:39 | deep-audit-1038 已合并 main（用户令「先合吧」）：main `10dd329e` = 并集 `fecb66bb`，main CI #2422 首轮 success（Publish 执行，v1.0.0+2422） | 三项核查全过（28 文件全落 src/+scripts/scan/ 无夹带；本地 109 门+336 夹具双绿；五支 CI head_sha 逐字符一致；独立证据 = 逐支文件集在并集逐字节一致 5/5）→ --no-f… |
| 10-21 13:52 | FIX-2 收口（deep-audit-1038 面 A / A-05+A-06）：`fix/dv18-2-rpc-exec-session-scope` @ `009fe78`，CI #2425 绿，停在分支 | 身份链三方逐字符：基线 main 10dd329e → 3 提交（584ba51 生产 / fea4cea 测试 / 009fe78 门）→ 本地 == ls-remote == CI head_sha = 009fe7… |
| 10-21 13:53 | FIX-5 收口（deep-audit-1038 / v18 波 A-08）：Kimi 自定义 baseURL 模型行全落 "Custom" —— `fix/dv18-5-custom-baseurl-provider-name` @ `ae9190c8`，CI #2426 attempt 1 success，停在分支 | 身份链三方逐字符：基线 main 10dd329e → 单提交 → 本地 == ls-remote == CI head_sha = ae9190c83249beb03d10eba06e88bbbdffcba1d9。4 … |
| 10-21 13:54 | FIX-3 收口（deep-audit-1038 / v18 波 A-02）：`--visible-only` 布尔/取值混淆 —— `fix/dv18-3-a11y-visible-only-flag` @ `ea88de826984ba231a681b650188726a614d8384`，CI #2423 success，停在分支 | 身份链三方逐字符：基线 main 10dd329e → 3 提交（72fed77 生产 / b7b836d 测试新增 / ea88de8 门扩展）→ 本地 == git ls-remote == CI head_sha … |
| 10-21 15:13 | FIX-1 收口（deep-audit-1038 / dv18 波面 A）：`fix/dv18-1-cli-arg-prescan` @ `fb87c52c`，CI #2427 attempt 1 success，停止节点达成，未合并 main | 身份链三方逐字符：基线 main 10dd329e → 3 提交（2355a29f 生产 / f692618d 测试 / fb87c52c 门）→ LOCAL == ls-remote == CI head_sha = … |
| 10-21 16:03 | deep-audit-1038 面 A（v17 波）全流程闭环并合入 main（用户令「先处理文档，再合并」）：main `6bbbded`，main CI #2429 success（Publish 执行，v1.0.0+2429） | 输入 audit_report_v17_a.md（457 行 / 61.5 KB，自 docx 解包；A-01..A-08 全 P1）。基线 main 10dd329e（= 面 B 并集）。第二道门 8/8 成立 · 0… |
| 10-21 18:46 | fastio 权限门 BYPASS 收口（deep-audit 外单点）：分支 `fix/fastio-permission-bypass-1021` @ `81c70b68`，CI #2430 首轮 success，停在分支 | 触发：用户批「fastio 首用弹窗不对——这不是敏感权限，该像沙箱/终端一样；甚至该自动做」→ 主会话判定「批评成立、但『自动做』被否决」→ 用户令「方案 B，开始做」。 |
| 10-21 19:29 | FIX-B1 收口（deep-audit-1039 / dv19 面 B）：composer 实时源单一化 —— `fix/dv19-b1-chat-input-single-source` @ `e8bfb52b`，CI #2433 success（attempt 1），停止节点达成，未合并 main | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（e0b84e84 生产 / b9c531b9 测试 / e8bfb52b 门）→ LOCAL == git ls-remote == CI head_sh… |
| 10-21 19:31 | FIX-B3 收口（deep-audit-1039 / 面 B：B-03 + B-04 + B-08）—— `fix/dv19-b3-settings-live-subscribe` @ `63b25132`，CI run 39586968122 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（22230a93 生产 / fa727dac 测试 / 63b25132 门）→ 本地 == ls-remote == CI head_sha。7 文件 … |
| 10-21 20:07 | FIX-A2 收口（deep-audit-1039 / v19 波面 A / A-04）：customBaseURL 归一化单源 —— `fix/dv19-a2-custom-base-url-trim` @ `05acae4f`，CI #2437 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（a92ed9ba 生产 / dc346cef 测试 / 05acae4f 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-21 20:12 | FIX-A3 收口（deep-audit-1039 / dv19 波 A-05+A-06）：`fix/dv19-a3-voice-url-and-cancel` @ `0b699b18`，CI #2438 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（40dfabeb 生产 / c3afd83c 测试 / 0b699b18 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-21 20:29 | FIX-A4 收口（deep-audit-1039 / A-07+A-08 门判据盲区，零生产改动）：`fix/dv19-a4-gate-blindspots` @ `2eae5cb2`，CI #39594814613 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 2 提交（门 0c4cdcd1 / 测试 2eae5cb2）→ 本地 == git ls-remote == CI head_sha = 2eae5cb2d1d16… |
| 10-21 20:34 | FIX-B4 收口（deep-audit-1039 / dv19 波 B-05+B-07）：`fix/dv19-b4-offmain-io-and-refresh` @ `cc927dc2`，CI #2440 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 5 提交（f8d9696e 生产 / 77ecda6c 测试 / f67e05ef 门 / 3aa10bc3+cc927dc2 CI 红修复）→ 本地 == ls-… |
| 10-21 21:06 | deep-audit-1039（v18 波：A 面 8 + B 面 8）收口完成：16 工单 → 8 支全绿 → 并集 `integration/dv19` @ `ae92c12e`，并集 CI #2441 判读 8/8 ALL GREEN，**未合并 main**（用户令：合并小任务 + 分时考虑工程量） | 输入：Rikkaminis_Audit_Report.md（A 面，76,091 B）+ Rikkaminis_Audit_Report_V18_B.md（B 面，96,855 B）。基线 main 6bbbded21d… |
| 10-21 21:25 | 审计背景包 v22 构建完成（为 deep-audit-1040 波准备） | 触发：用户「为下一波的那个审计做准备吧」。关键判断：dv19 八支已并入 main（295cf778，21:11），而背景包基线仍是 6bbbded2（1038 收口）—— 不重锚就会让下一波把刚修完的 16 条再挖一遍… |
| 10-21 21:34 | deep-audit-1039 合并闭环（用户令「合并吧，合并之前先更新一下文档」）：main `295cf778` + 档案 2103 条 + main CI #2442 success（Publish 执行，v1.0.0+2442） | ① 文档先行：rebuild_dev_history.py → 2103 条（2089→2103）/ 80 天（08-03 ～ 10-21），INDEX 43 处替换、主文件+INDEX+SAGAS 三轮脱敏均 Rema… |
| 10-22 02:23 | FIX-A3 收口（deep-audit-1040 / dv20 波 A-03+B-02）：`fix/dv20-a3-orphan-busy` @ `1c7ec0e7`，CI #2445 判读 16/16 GREEN，**停止节点达成，未合并 main** | 身份链三方逐字符：基线 main 295cf778 → 4 提交（4a868c69 生产 / 8c28d1bb 测试 / 20ad7da4 门 / 1c7ec0e7 CI 编译错修复）→ 本地 HEAD == ls-re… |
| 10-22 02:44 | FIX-A4 支（deep-audit-1040 / dv20 波 A4）：代码+门+三层证据全绿，**推送被环境级出网中断挡住** | 分支 fix/dv20-a4-mount-wiring（私有 worktree shared/work/deep-audit-1040/a4），基线 main 295cf778。两提交：生产 03d941eb（5 文件）… |
| 10-22 02:49 | FIX-A1（deep-audit-1040 / dv20 波 · A-01 P0 + A-02 P1）交付状态：本地全绿，CI 停止节点被设备出口中断挡住 | 分支 fix/dv20-a1-backup-voice（基线 295cf778）· 本地最终 tip b0640ad1 · 远端 tip 停在 7a2705c1（其后两个纯装置提交推不上去） |
| 10-22 03:01 | FIX-B1 收口（deep-audit-1040 / dv20 波 B-01+B-07）：`fix/dv20-b1-settings-live` @ `cb0d9136`，CI **run 2448 success**（judge 15/15），停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（1757b6bb B-01 / b0adab57 B-07 / cb0d9136 门）→ 本地 == git ls-remote == CI head_s… |
| 10-22 03:03 | FIX-B2 收口（deep-audit-1040 / dv20 波 B-04）：MCP 屏与会话 sheet 的仓/Keystore 调用回主线程 —— `fix/dv20-b2-mcp-offmain` @ `9a97895f`，CI #39645696095 success，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（34855a24 生产 / fcb54989 测试 / 9a97895f 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 03:20 | FIX-A1 收口（deep-audit-1040 / dv20 波 A-01 P0 + A-02 P1）：`fix/dv20-a1-backup-voice` @ `b0640ad1`，CI #2449 绿，停在分支 | 身份链三方逐字符一致：基线 main 295cf778 → 7 提交 → 本地 == git ls-remote == CI head_sha = b0640ad1716018b130a24b6af4aebc714231… |
| 10-22 04:00 | FIX-B4 收口（deep-audit-1040 / dv20 波 B-08 P1）：`fix/dv20-b4-backup-busy` @ `cdfa3190`，CI #2450 **12/12 ALL GREEN**，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（ca1ea19b 生产 / 6b6f8e19 测试 / cdfa3190 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 04:09 | 修复：压缩摘要链超时导致「上下文极限自动停」（用户报告）— `fix/compact-timeout-budget` @ `2abb1592`，CI #2451 success，停在分支 | 用户报告：「上下文极限到达了。自动停下。在压缩机制的作用下不应该出现，为什么连续出现了几次？」取证结论 = 压缩机制确实在触发，是摘要生成那步系统性超时。 |
| 10-22 04:30 | FIX-B3 收口（deep-audit-1040 / dv20 波 B-05+B-06）：`fix/dv20-b3-render-input` @ `8420316f`，CI #2453 success，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 5 提交（77b22d01 生产 +53/−7 · 71b0a17e 测试新类 417 行 · 1fa737d2 门扩展 +420/−4 · 29d8f300+84… |
| 10-22 07:51 | deep-audit-1040（v19 波）收口完成：9 份全绿 → 并集 `integration/dv20` @ `4417999f`，并集 CI #2455 ALL GREEN，**未合并 main** | 九份构成（用户口径）：dv20 八支（a1 备份脱敏+语音成员 / a2 provider repo / a3 orphan busy / a4 挂载接线 / b1 settings live / b2 mcp offm… |
| 10-22 08:21 | deep-audit-1040 全批闭环（用户令「合并清理，那个也一起」）：main `843ab917` + main CI #2456 success（Publish 执行）+ 发布 v1.0.0+2456 + 21 支清理归零 | ① 合并前的补核查（fastio）：fix/fastio-permission-bypass-1021 三方 sha OK（本地 == 远端 == CI 81c70b68，run #2430 success）；与 dv2… |
| 10-22 08:34 | 审计背景包 v23 构建完成（为 deep-audit-1041 / v20 波准备） | 触发：用户「又完成了一轮，为下一轮做准备」。基线：295cf778 → 843ab917（deep-audit-1040 收口，今晨 07:54；含 dv20 八支 + compact-timeout-budget + … |
| 10-22 12:18 | FIX-A1 收口（deep-audit-1041 / v20 波 A-01 P1）：`fix/dv21-a1-config-registry` @ `75ec3bf5`，CI #2457 success 判读 10/10，停在分支 | 缺陷：ConfigRegistry.resolveField 用 path.split('.', limit = 3) 切集合子路径，把含点的复合 id 截断成 segments[1]。复合模型 id 形态 <insta… |
| 10-22 12:18 | FIX-B1 收口（deep-audit-1041 / dv21 波 / B-01 + B-04）：`fix/dv21-b1-provider-live` @ `61b2d2ae`，CI #2458 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（d2a09830 生产 / a6db2ac0 单测 / 61b2d2ae 门）→ 本地 == ls-remote == CI head_sha = 61b… |
| 10-22 12:34 | FIX-A6 收口（deep-audit-1041 / dv21 波 · A-07 P2）：`fix/dv21-a6-open-intent` @ `ba559108`，CI #（run 39720876753）一次绿，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（db0b92d1 生产 +41/−3 · 5271928a 测试 +53/−2 · ba559108 门 +107/−4）→ 本地 == ls-remot… |
| 10-22 12:39 | FIX-B2 收口（deep-audit-1041 / dv21 波 B-02 + B-07）：`fix/dv21-b2-rootfs-log` @ `2d6b43ac`，CI #2462 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（8f0007d7 生产 / 2967fd7f 测试 / 2d6b43ac 门）→ 本地 == ls-remote == CI head_sha = 2d6… |
| 10-22 12:52 | FIX-A3 收口（deep-audit-1041 / A-03 P1）：`fix/dv21-a3-import-timestamp` @ `a52b6d7c`，CI #2464 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 3 提交（87956788 生产 / 58160c4f 测试 / a52b6d7c 门）→ 本地 == ls-remote == CI head_sha = a52… |
| 10-22 13:05 | FIX-A4 收口（deep-audit-1041 / A-04 P1）：`fix/dv21-a4-gateway-effort` @ `995e15d6`，CI #2468 success，停在分支 | 缺陷本体的重新定位（本支最值钱的洞察）：REPORT-A 说「second gateway 缺 effort 真值表」——错。基线 843ab917 上 SENSEAUDIO_HOSTS(:82) / SENSEAUDI… |
| 10-22 13:05 | FIX-A2 收口（deep-audit-1041 / dv21 波 A-02 P1）：`fix/dv21-a2-empty-sweep` @ `13c8fccc`，CI #(run 39724086190) 16/16 ALL GREEN，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 4 提交（8b140806 生产 +55/−2 · dced789d 单测 · de536693 门 +216/−14 · 13c8fccc 单测补 1 条 hoo… |
| 10-22 13:09 | FIX-B4 收口（deep-audit-1041 / dv21 波 B-05+B-06+B-08）：`fix/dv21-b4-render-decode` @ `db111d34`，CI #2469 success，停在分支 | 身份链：基线 843ab917 → 4 提交（cb3717ff B-05 / 09ecc327 B-06 / 485cad2b B-08 / db111d34 B-08 CI 红修复）→ 本地 == ls-remote（… |
| 10-22 13:13 | FIX-B3 收口（deep-audit-1041 / dv21 波 B-03 P1）：`fix/dv21-b3-envvars-io` @ `a48fa300`，CI #39724689061 success，停在分支 | 身份链：基线 main 843ab917 → 3 提交（b6c707a3 生产 +197/−57 / 测试 373 行 / a48fa300 门 +281）→ 本地 == ls-remote == CI head_sha… |
| 10-22 13:36 | FIX-A5 收口（deep-audit-1041 / dv21 波 A-05 P1 + A-06 P2）：`fix/dv21-a5-modeluse-modality` @ `61d12096`，CI #2472 success，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 4 提交（b05728de 生产 / 1f2f45ab 测试 / cbb4e267 门 / 61d12096 CI 红修复）→ 本地 == ls-remote ==… |
| 10-22 14:46 | deep-audit-1041 收口完成：并集 `7e0e9b58` + CI #2475 ALL GREEN（含一次测试竞态修复） | 收口链路：10 支四方核对 10/10（本地==远端==CI head_sha，全 success）→ 并集 13a2c5da（61 文件 +7316/−444，逐支逐字节 61/61 一致，冻结面 0/交集 0/残留 … |
| 10-22 15:06 | 审计背景包 v24 构建完成（为 deep-audit-1042 / v21 波准备） | 触发：用户「这一批已经吸收了，为下一批做准备」。基线：843ab917 → 5dd278da（deep-audit-1041 收口，14:48；含 dv21 十支并集 + fix/dv21-rssprobe-test-r… |
| 10-22 15:16 | deep-audit-1041 全批闭环（文档 + 合并 main + 清理 13 支）：main `5dd278da` + main CI #2476 success（Publish 执行）→ 发布 v1.0.0+2476 | ① 文档：档案 2131 条 / 81 天（08-03～10-22，2103→2131）；三轮 sanitize 全 NONE；SAGAS 24 主题 / orphans 193 / multi4 148；结构体检 an… |
| 10-22 16:27 | F1 收口（COLLECT-R1 同族普查第 3 处）：`fix/verify-2476-rssprobe-finally` @ `3a740442`，CI #2477 success，停在分支 | 身份链：基线 main 5dd278da → 单提交 3a740442 → 本地 == ls-remote == CI head_sha = 3a7404421163fd8deef84e35e103fd00dd71a31… |
| 10-22 18:57 | FIX-A4 收口（deep-audit-1042 / dv22 波 A-04 P1）：`fix/dv22-a4-effective-model-nonui` @ `abcf75d7`，CI run `39760667432` success，停在分支 | 身份链：基线 main 5dd278da → 3 提交（8dbbc498 生产 / 199d3aa6 测试 / abcf75d7 门）→ 本地 == ls-remote == CI head_sha = abcf75d7… |
| 10-22 19:05 | FIX-A6 收口（deep-audit-1042 / A-06 P1 剪贴板 clear）：`fix/dv22-a6-clipboard-clear` @ `a86e099e`，CI 两次绿，停在分支 | 身份链：基线 main 5dd278da → 5 提交（751eab0e 生产 / 78a61edf 测试 / 70233dab 门 / 1959c809 补日志 / a86e099e 修编译警告）→ 本地 == ls-… |
| 10-22 19:16 | FIX-B6 收口（deep-audit-1042 / dv22 波 B-07 P1）：`fix/dv22-b6-skills-browser-update` @ `7ff856d6`，CI #39764477753 success，停在分支 | 身份链三方逐字符：基线 main 5dd278da → 3 提交（1d9d5558 生产 / 0bd4d7b4 测试 / 7ff856d6 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 19:30 | FIX-A2 收口（deep-audit-1042 / A-02 P1）：`tarCreate` 顶层根循环三缺口 — `fix/dv22-a2-fastio-tar-toplevel` @ `5a144b2c`，CI #39768460593 success，停在分支 | 身份链：基线 main 5dd278da → 5 提交（生产 d4aa69cf / 测试 c573481f / 门 9535d2fc / 报告 f8b1590d + 5a144b2c）。本地 == ls-remote =… |
| 10-22 19:45 | FIX-B5 收口（deep-audit-1042 / dv22 波 B-05 P1）：`fix/dv22-b5-rootfs-progress-replay` @ `d503eeb2`，CI #39768763850 success，停在分支 | 身份链三方逐字符：基线 main 5dd278da → 3 提交（9b1a7a14 生产 +161/−21 · b0c3a5e4 测试 +216 · d503eeb2 门 +280）→ 本地 == ls-remote =… |
| 10-22 19:51 | FIX-A5 收口（deep-audit-1042 / dv22 波 A-05 P1）：`fix/dv22-a5-headless-session-ready`，分支 CI 绿，停在分支 | 身份链：基线 main 5dd278da → 8 提交（3 生产类 / 4 报告类 / 1 校正）→ 分支尖端 e00a42c6，源码树 = CI 验过的 b71265e7（git diff --name-only b7… |
| 10-22 20:45 | 审计背景包 v25 / 提示词 v22-A,B 构建完成（为 deep-audit-1043 / v22 波准备）—— 2026-10-22 晚 | 触发：用户「这一批消化完了，为下一批做准备吧……笔记子文件夹里的背景文件」。接手时发现 audit-pack-v25/ 已有半成品 stage1.py（20:32 另一会话留下，未跑、锚点失效），其余 stage2-6/… |
| 10-22 20:50 | deep-audit-1042（dv22 波）全批闭环：14 支并集 → main `79ef6d59` + main CI #39778517080 success（Publish 真执行）→ 发布 v1.0.0+2500 | ① 并入范围（用户提醒补上的第 14 支）：13 支 fix/dv22-（A-01..A-07 / B-01..B-07）+ 遗留支 fix/verify-2476-rssprobe-finally @ 3a740442… |
| 10-23 00:30 | FIX-A6 收口（deep-audit-1043 / A-07 P1）：`fix/dv23-a6-fastio-special-files` @ `2bd96269`，CI #39811912340 success，停在分支 | 身份链：基线 main 79ef6d59 → 3 提交（生产 3efb8f7d / 测试 a8abc3f1 / 门 2bd96269）→ 本地 == ls-remote == CI head_sha = 2bd96269… |
| 10-23 00:43 | FIX-7 收口（deep-audit-1043 / B-01 P1）：Rootfs 修复结算把继承来的 `Failed` 当成本次判决 — `fix/dv23-b1-rootfs-repair-settle` @ `84e4f9ac`，CI #39813094967 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 f1425db6 / 测试 cbf2845b / 门 84e4f9ac）→ 本地 == ls-remote == CI run.head_sha =… |
| 10-23 00:54 | FIX-A4 收口（deep-audit-1043 / v22 波 A-05 P1）：`selectModel` / `uiPrompt` 冷会话窗口 — `fix/dv23-a4-headless-session-ready` @ `8cc263ae`，CI #39812861827 success，停在分支 | 身份链：基线 main 79ef6d59 → 3 提交（生产 72df9ec2 / 测试 87f4cc49 / 门 8cc263ae）→ 本地 == ls-remote == CI head_sha = 8cc263ae… |
| 10-23 00:56 | FIX-2 收口（deep-audit-1043 / A-03 P1）：`minis-model-use run` 交给 `:modelservice` 的模型未富化 — `fix/dv23-a2-modeluse-effective-model` @ `fe3f9a7c`，CI #39815679884 success，停在分支 | 身份链：基线 main 79ef6d59 → 4 提交（210912f3 生产 / a6f7eb1f 测试 / 258fff8a 门 / fe3f9a7c 首跑红修复）→ 本地 == ls-remote == CI he… |
| 10-23 01:02 | FIX-3 收口（deep-audit-1043 / v22 波 A-04 P1）：`fix/dv23-a3-thinking-ceiling-enrich` @ `6b708e44`，CI run 39815917331 / job 119954364519 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 d80a812f / 测试 0364107e / 门 6b708e44）→ 本地 == ls-remote == CI head_sha = 6b7… |
| 10-23 01:07 | FIX-9 收口（deep-audit-1043 / v22 波 B-03 P1）：`fix/dv23-b3-composer-single-writer` @ `8462e98d`，CI #2510 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 3900f9cd / 测试 a187a2a6 / 门 8462e98d）→ 本地 == ls-remote == CI head_sha = 846… |
| 10-23 01:16 | FIX-10 收口（deep-audit-1043 / v22 波 B-04+B-05 P1）：`fix/dv23-b4-browser-pool-isolation` @ `13c1fb6b`，CI #39817158221 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 2 提交（d387dc88 生产+测试+门 / 13c1fb6b 判据修复）→ 本地 == ls-remote == CI head_sha = 13c1fb6be… |
| 10-23 05:58 | FIX-8 收口（deep-audit-1043 / B-02+B-06+B-07 三条 P1）：`fix/dv23-b2-capability-single-source` @ `498eb889`，CI #39823833259 success（判读 15/15），停在分支 | 身份链：基线 main 79ef6d59 → 6 提交（d8dca1e0 B-02 / 84ab8bb6 B-06 / 90381f3c B-07 / 35e77140 报告 / d4a7ceb2 编译修复 / ee63… |
| 10-23 06:31 | deep-audit-1043（v22 波）10 支全绿闭环：并集 `integration/dv23` @ `bbd43596`，并集 CI #39863182499 success，**待用户放行合并 main** | 输入：Rikkaminis_Audit_Report_V22_A.md（102,625 B，A-01..A-07）+ V22_B.md（78,968 B，B-01..B-07）= 14 单。基线 main 79ef6d5… |
| 10-23 06:41 | 审计背景包 v26 / 提示词 v23-A,B 构建完成（为 deep-audit-1044 准备）—— 2026-10-23 晨 | 触发：用户「好了，吸收完了，为下一波做准备吧」（承接 v25 那轮的「为下一批做准备」）。关键前提：1043 那轮已被另一会话合并进 main —— f94413f5（bbd43596 dv23 并集 + eee9fce… |
| 10-23 06:52 | deep-audit-1043（v22 波）全批闭环：文档先行 → main `f94413f5` + main CI #39865696671 success（Publish 真执行，v1.0.0+2517）→ 清理 13/13 | ① 文档先行（用户令「先处理文档，再合并」）：先 memory_write 1043 收口（档案从日志生成，不先落盘条目会缺）→ rebuild 2153 条 / 82 天（2142→2153，08-03～10-23）/… |
| 10-23 11:14 | deep-audit-1044 FIX-5 收口（A-06 语音错误码表错位 + A-07 TTS 按次默认值）：分支 CI 绿，停在分支 | 分支 fix/dv24-a5-speech-error-tts-defaults @ a816c23ae12ced1d6110a6a1938c1c500d63dc98（3 提交：生产 0f5bacd1 / 测试 d7a5… |
| 10-23 11:15 | FIX-1 收口（deep-audit-1044 / A-01 P1）：`fix/dv24-a1-voice-effective-model` @ `8c27fd25`（CI 绿于 `fe6ab36e`），run 39891898960 / job 120213372981 success，停在分支 | 身份链四方逐字符：基线 main f94413f5 → 3 提交（生产 4bce8f71 / 测试 67ed74b8 / 门 fe6ab36e）+ 2 个 docs 提交（af98f5a3/8c27fd25，只动 fix… |
| 10-23 11:17 | FIX-9 收口（deep-audit-1044 / B-06 **本波唯一 P0**）：`fix/dv24-b3-promote-draft-persist` @ `ed90a7f3`，CI run 39892254078 / job 120214646640 success，停在分支 | 身份链：基线 main f94413f5 → 3 提交（3df76c6f 生产 / 3dc0cf8a 测试 / ed90a7f3 门）→ 本地 == ls-remote == CI head_sha = ed90a7f3… |
| 10-23 11:24 | deep-audit-1044 FIX-10（B-07）收口：`fix/dv24-b4-browser-dispose-scope` @ `227e1576`，CI #39893017433 success，停在分支 | 缺陷：BrowserTabPool.dispose() 只释放标签页，不释放异步面 —— 两个实例自有 CoroutineScope（evictionScope/downloadScope）、downloadJobs、s… |
| 10-23 11:35 | FIX-2 收口（deep-audit-1044 / A-02 P1）：`fix/dv24-a2-terminal-ownership` @ `22b474ca`，CI run 39893791520 success，停在分支 | 身份链：基线 main f94413f5 → 2 代码提交（4ae5bf10 生产+门 / 0819d5ec 报告）+ 1 回填（22b474ca，仅 fix-reports/）。CI 绿于 0819d5ec，git d… |
| 10-23 11:37 | FIX-3 收口（deep-audit-1044 / dv24 波 A-03+A-04 P1×2）：`fix/dv24-a3-headless-send-contract` @ `8a4a38a5`，CI #2527 success，停在分支 | 身份链三方逐字符：基线 main f94413f5 → 5 提交（8f8da7f7 A-03 生产 / 4008328e A-04 生产 / f7fc1a70 测试 / 469bb7cf 门 criterion 15 /… |
| 10-23 11:43 | FIX-8 收口（deep-audit-1044 / dv24 波 B-03+B-04 P1）：`fix/dv24-b2-chat-factory-effective` @ `7c82bc98`，CI #39894373704 success，停在分支 | 身份链：基线 main f94413f5 → 5 提交（生产 0e335456 / 测试 ba3611ef / 门 3d3bf5c1 / 报告 5c32285c+ef7d9d7e / 收口 7c82bc98）。CI 绿于… |
| 10-23 11:51 | FIX-7 收口（deep-audit-1044 / dv24 波 B-01+B-02+B-05，P1×3）：`fix/dv24-b1-picker-quicktest-composer` @ `71dc0225f1`，CI #2529 (run `39894855850` / job `120222802096`) success，停在分支 | 身份链三方逐字符：基线 main f94413f5 → 5 提交（f49a541d B-01 生产 / 5ef21819 B-02 生产 / 1529ecae B-05 生产 / 5edf7021 测试 / 71dc02… |
| 10-23 11:54 | FIX-4 收口（deep-audit-1044 / v23 波 A-05 P1）：`fix/dv24-a4-config-session-writethrough` @ `90a0a064`，CI #2530 success（源码树 = `9a002abf`），停在分支 | 身份链：基线 main f94413f5 → 4 提交（b7979890 生产 / 0f5897e7 测试 / 2451a671 门 / 9a002abf→90a0a064 报告回填）。CI 绿于 9a002abf，HE… |
| 10-23 12:20 | FIX-6 收口（deep-audit-1044 / v23 波 A-08 P1）：`fix/dv24-a6-share-null-stream` @ `c810c907`，CI run 39897998963 / job 120232786685 success，停在分支 | 身份链：基线 main f94413f5 → 5 提交（07c314d0 生产 / 79126a95 测试 / 7b256666 门 / efc21523 签名修正 / 8a86cfc9+9c43065b+c810c90… |
| 10-23 12:30 | deep-audit-1044（v23 波）10 支全绿闭环：并集 `integration/dv24` @ `f842dd13`，并集 CI #2533 success，**待用户放行合并 main** | 输入：Rikkaminis_Audit_Report_V23_A.md（68,767 B，A-01..A-08）+ V23_B.md（88,135 B，B-01..B-07）= 15 单。基线 main f94413f5… |
| 10-23 12:45 | 审计背景包 v27 / 提示词 v24-A,B 构建完成（为 deep-audit-1045 / v24 波准备）—— 2026-10-23 午后 | 触发：用户「好了，现在已经合并进去了」（dv24 十支已并入 main）。基线核实：main 1d44ec90（= dv24 并集 f842dd13 + docs-1044；两树 src/+scripts/ 逐字节一致，… |
| 10-23 12:59 | deep-audit-1044（v23 波）全批闭环：main `1d44ec90` + main CI #2534 success（Publish 真执行，v1.0.0+2534）→ 清理 12/12 分支 + 10/10 worktree | 用户令「按流程走」= 放行合并（承接上一轮已完成的收口证据）。 |
| 10-23 15:15 | FIX-1 收口（deep-audit-1045 / v24 波 A-01 P1）：MCP OAuth 配置读侧不认客机 CLI 的 camelCase — `fix/dv25-a1-mcp-oauth-keycase` @ `46b059be`，CI #2535 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec90 → 3 提交（6bb83a20 生产 / 143a88be 测试 / 46b059be 门）；无报告提交（本波 FIX-1..11 与上波 FIX-1..10 重号，入… |
| 10-23 15:27 | deep-audit-1045 / FIX-8（B-04 P1）收口：分支 `fix/dv25-b4-picker-member-resolve` @ `95d84870`，CI #2537 success，停在分支 | 缺陷：模型选择器群组头部解析「选中群组的 active entry」用落库裸 find，同文件成员解析用单源 SystemVoiceEntries.resolveIn ⇒ 默认 Voice 组（只由 System 哨兵组… |
| 10-23 15:34 | FIX-11 收口（deep-audit-1045 / v24 波 B-07 P1）：`fix/dv25-b7-saf-bounded-read` @ `17bb3f22`，CI #2538 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec90 → 4 提交（47780589 生产 / 74902651 测试 / 4956fbe0 门 / 17bb3f22 注释可扫描性修正）→ 本地 == ls-remote … |
| 10-23 15:39 | FIX-4 收口（deep-audit-1045 / v24 波 A-07 P1）：`fix/dv25-a4-player-json-contract` @ `4a0b6fa6`，CI #2545 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec905b8f5d21a215694e9a13910c74e1f645 → 1 提交（生产 + 测试 + 门）→ 本地 HEAD == git ls-remote == CI … |
| 10-23 15:42 | FIX-7 收口（deep-audit-1045 / v24 波 B-03 P1）：`fix/dv25-b3-storage-io-offmain` @ `d4d6886c`，CI #2541 success，停在分支 | 身份链：基线 main 1d44ec90 → 5 提交（f9811f46 生产 / 6e42e12f 测试 / 44f06cd9 门 / d4d6886c 门+测试修盲区 / 9b6df492+2542cf18 报告）。… |
| 10-23 15:42 | FIX-10 收口（deep-audit-1045 / v24 波 B-06 P1）：`fix/dv25-b6-mcps-merge-guard` @ `4b083c74`，CI #2543 success，停在分支 | 身份链：基线 main 1d44ec90 → 4 提交（bd574457 生产 / 0bbda65e 测试 / ea800a0a 门 / 4b083c74 报告）。CI 绿于 ea800a0a，HEAD 4b083c74… |
| 10-23 15:47 | FIX-5 收口（deep-audit-1045 / v24 波 B-01 P1）：`fix/dv25-b1-mirror-autodetect-ownership` @ `665e9b21`，CI #2540 success（head_sha `6e929cd3` 三方一致），停在分支 | 缺陷：autoDetectOnceIfNeeded（首启 rootfs.freshInstall 自动测速）是裸 scope.launch（基线 :478），Job 从未进字段 ⇒ runTest/runAllTests… |
| 10-23 15:48 | FIX-9 收口（deep-audit-1045 / dv25 波 B-08 P0 + B-05 P1）：`fix/dv25-b5-fastmode-promote-draft` @ `97642c3f`，CI #2544 (run 39917029875 / job 120293005852) success，停在分支 | 身份链：基线 main 1d44ec90 → 6 提交（b1d00555 B-08 生产 / dfe6e9e3 B-05 生产 / c930e94a 测试 / ad8e33de 门 / a8f34b11 失败恢复收紧 =… |
| 10-23 16:08 | deep-audit-1045 / FIX-6 支（B-02 P1）收口：分支 `fix/dv25-b2-webview-lifecycle` CI #2550 绿，停在分支（未合并 main） | 身份链：基线 main 1d44ec90 → 6 提交（34aa5aba 生产 / dd70f0ca 测试 / 0635d5a2 门 / 9d6f1dca 报告 / 42686631 修新测试编译警告 / ffd7cc0… |
| 10-23 17:43 | deep-audit-1045（v24 波）全批闭环：main `381d1026` + main CI #2553 success（Publish 真执行，v1.0.0+2553）→ 清理 14/14 分支 + 14/14 worktree | 用户在收口中两次干预：①「怎么分支又一次跑了，不是应该并入吗？」——点破我为 2 条测试文件编译警告（user.dir 可空性，P2）不必要地重跑了一轮并集 CI（装置比例失当；正确做法是登记不修，或合并后由 main … |
| 10-23 20:10 | FIX-G10 收口（deep-audit-1046 / S-10 P1）：`fix/dv26-g10-tts-cancel-guard` @ `eb7b9edd`，CI #39947618231 success，停在分支 | 身份链：基线 main f7aba218 → 73215401 生产+测试 / 188858b6 门（CI 绿于此）/ eb7b9edd 报告。git diff --name-only 188858b6 HEAD -- … |
| 10-23 20:12 | FIX-B3 收口（deep-audit-1046 / dv26 波 S-03 P1）：`fix/dv26-b3-anthropic-thinking-budget` @ `db3de4da`，CI #2559 success，停在分支 | 身份链：基线 main f7aba218 → 4 提交（8e3d361d 生产 / 22cb622a 测试 / c2c86207 门 = CI 验过的树 / db3de4da 报告）。git diff --name-on… |
| 10-23 20:17 | FIX-A2 收口（deep-audit-1046 / S-02 P1）：`fix/dv26-a2-oauth-bridge-refresh` @ `8eb3a837`，CI #2562 success，停在分支 | 身份链：基线 main f7aba218 → 3 提交（d95f41c3 生产+测试+门 / e35408cb 报告 / 8eb3a837 补 L4）。CI 绿于 d95f41c3，报告提交后源码树等价（git diff… |
| 10-23 20:33 | FIX-D7 收口（deep-audit-1046 / S-07 P1）：`fix/dv26-d7-webview-release-latch`，CI 两次绿，停在分支 | 缺陷：KaTeXView.kt:390-393 与 FilePreviewScreen.kt:490-494 的 AndroidView.onRelease 都调 wv.removeCallbacks(null)——AO… |
| 10-23 20:51 | FIX-F9 收口（deep-audit-1046 / S-09 P1）：`fix/dv26-f9-intent-scheme-sanitize` @ `e97f77f9`，CI #39953818482 success，停在分支 | 身份链：基线 main f7aba218 → 5 提交（57203319 生产+门+测试 8 文件 +847/−95 / d552a8c0 测试名去点 / 0e74c57f KDoc 措辞 / 1843c1ca 可空性警… |
| 10-23 21:24 | deep-audit-1046 全批收口：8 支 CI 绿 → 并集 `integration/dv26` @ `bea3525b`，并集 CI #39957962219 全绿（111 门 + 340 夹具），未合并 main（起手权在用户） | 8 支 CI 绿（全 attempt 1，唯一 skipped=Publish，SRC_EQ_GREEN=True）：A2 39949090022 / B3 39947709325 / C6 39945776622 / … |
| 10-23 21:46 | 审计背景包 v28 构建完成（为下一波双路审计准备）—— 2026-10-23 夜 | 触发：用户「最新的报告消化完了，为下一批做准备吧，两份，在挂载文件夹的笔记的子文件夹」＝重建背景包 + 双路（A/B）提示词，落 /var/minis/mounts/笔记/RikkaMinis审计背景包/。上一版 v27… |
| 10-23 21:53 | deep-audit-1046 全批闭环：main `42af0ae6`（+ docs `88ef9b27`）+ main CI #39961698711 success（skipped=[]，Publish 真执行，v1.0.0+2567）→ 分支/worktree 清零 | 收口链：8/8 分支 CI 绿（attempt 1，唯一 skipped=Publish）→ 并集 integration/dv26 @ bea3525b（8 次 --no-ff 零冲突，49 文件 +8501/−522… |
| 10-24 01:23 | FIX-B3 收口（deep-audit-1047 / B-05 P1）：模型组详情屏系统语音三处断层 — `fix/dv27-b3-model-group-system-voice` @ `8feb5466`（CI 绿于 `77ba33fc`），停在分支 | 缺陷（真树 4 处，报告报 3 处）：ModelGroupDetailScreen.kt 对 SystemVoiceEntries（默认 Voice Input/Output 组的全部成员，虚拟、无 instance、不… |
| 10-24 01:51 | deep-audit-1047 B1 收口（dv27 波 B-01+B-02+B-03 P1）：`fix/dv27-b1-preview-export-guards` @ `5bd22bfa`，CI #2574 success，停在分支 | 身份链：基线 main 88ef9b27 → 5 提交（24d9026e 生产 / 4ee6166c 测试 / 62b5212d 门 / 6825f9fd 报告 / 548354d4 警告修复 / 5bd22bfa L4… |
| 10-24 02:08 | deep-audit-1047 A1 收口（dv27 A-01+A-03）：`fix/dv27-a1-provider-wire-parity`，CI 绿，停在分支 | 身份链：基线 88ef9b27 → 4 提交（eeff5770 生产+门+测试 / 2878a229 修源形状测试+报告 / 1d7ef15e 清编译警告 / 67066ce3 报告补证据）→ 绿 run 3999775… |
| 10-24 02:49 | FIX-A2 收口（deep-audit-1047 / A-02 P1）：`fix/dv27-a2-modelsvc-thinking-level` @ `2fc80db6`，CI #2578 success，停在分支 | 缺陷：ModelExecutionDispatcher.buildRequestJson 对非 OFF 档位无条件 put("thinking_level", level.name)，但 worker 只有流式路径解析；… |
| 10-24 05:39 | FIX-A3 收口（deep-audit-1047 / A-05+A-06）：`fix/dv27-a3-mcp-secret-handoff` @ `45b14121`，CI #40019485653 success，停在分支 | 缺陷（A-05）：客侧 CLI 写不了加密存储，只能把 MCP 客户端密钥落 <server>.secret 等宿主导入；全仓只有编辑弹窗一处调 importPendingClientSecret ⇒ 另两个读者（rec… |
| 10-24 06:38 | deep-audit-1047 收口：9 支全绿 → 并集 `integration/dv27` @ `e4ac1240` 并集 CI 全绿（111 门 / 342 夹具）→ docs 已合 main `7263dbee`，代码并集待用户放行 | 9 支回报核对：本地 HEAD == 远端 sha == 绿 run head_sha 9/9 逐字符；<绿sha>..HEAD 差异仅 fix-reports/；scan.sh/test_scan.py 冻结面 9/9… |
| 10-24 06:50 | 审计背景包 v29 构建完成（1047 波吸收后，为下一波双路审计准备）—— 2026-10-24 上午 | 触发：用户「好了，这一批已吸收完了，为下一批做准备吧」＝ 1047（dv27 九支）已合 main，重建背景包 + 双路提示词，落挂载笔记子文件夹。 |
| 10-24 09:47 | FIX-A6 收口（deep-audit-1048 / A-06 P1）：`fix/dv28-a6-contacts-max-args` @ `6f906d67`（代码 `97e5da76`），CI #40049408945 success，停在分支 | 缺陷（一个形状四条路径）：--max / 旧别名 -n 的解析把「拼了但用不了」折叠成「没拼」——①--max abc 走 getInt("max") ⇒ null ⇒ 默认页 50/20、exit 0；②search … |
| 10-24 09:56 | FIX-A1 收口（deep-audit-1048 / A-01+A-08 P1）：`fix/dv28-a1-openai-toolcall-eof` @ `c17f0200`，CI #40050571513 success，停在分支 | 身份链：基线 eea9f407 → 4 提交（生产 a6d5cd21 +127/−10 / 测试 1d9ac1cf +222 / 门 07159efe +231/−34 = CI 绿 sha / 报告 c17f0200）… |
| 10-24 10:01 | FIX-A7 收口（deep-audit-1048 / A-07 P1）：`fix/dv28-a7-headless-stale-assistant` @ `3e53b19a`，CI #40050292104 success，停在分支 | 缺陷：HeadlessChatRunner 三个结算入口（prompt:428 / retry:545 / rerunFromToolBlock:664）用 msgs.lastOrNull { role == "assi… |
| 10-24 10:02 | dv28-FIX-B3 收口（deep-audit-1048 / B-08 P1）：`ChatViewModel.resume()` claim 时序 + setup shell 未持有，`fix/dv28-b3-resume-claim-timing` @ `e34114bd`，CI #40050946272 success，停在分支 | 缺陷（两半同源）：resume() 的 _isStreaming claim 在 :4227，位于 :4221 viewModelScope.launch 之内、systemPromptForSession()（实体 :… |
| 10-24 10:12 | FIX-A5 收口（deep-audit-1048 / A-05 P1）：Stage 9 覆盖 Stage 7 的 MCP 合并结果 — `fix/dv28-a5-mcp-restore-stage9` @ `8122ca84`，CI #40051341292 success，停在分支 | 缺陷：导出照打 mcp/servers.json；Stage 7 用 mcpRepo.importJSON( 合并（本机独有服务器保留、save() 落盘）；Stage 9 解压时 destByPrefix["mcp"]… |
| 10-24 10:36 | FIX-B2 收口（deep-audit-1048 / B-07 P1）：`fix/dv28-b2-model-picker-single-source` @ `57a3b9c2`，CI `40053325774` success 且干净，停在分支 | 缺陷：①ChatModelPickerSheet.kt 私有折叠集合 remember(config){ config.instances.filter{ it.isEnabled }… } = 第三份副本，且键派生自用… |
| 10-24 10:40 | FIX-B1 收口（deep-audit-1048 / UI 交接五条 B-01·B-03·B-04·B-05·B-06）：`fix/dv28-b1-ui-handoff` @ `f182c766`，CI #2594 success，停在分支 | 身份链：基线 eea9f407 → 5 提交（99322a05 生产 10 文件 / 89c63058 四门扩展 / 0e7c0228 多删大括号 / 3c200099 ArchiveEntry 提升 / f182c76… |
| 10-24 11:20 | deep-audit-1048（v26-A/B 波）收口前状态：9/9 支 CI 绿 → 并集 `integration/dv28` @ `7275c81e` 并集 CI #40056047721 全绿 → 文档已自合 main `d7e2493e`，**代码并集待用户放行** | 9 支 CI（全 attempt 1，唯一 skipped=Publish=main 门控）：a1 40050571513 #2586 · a2 40051049467 #2589 · a3 40050931869 #2… |
| 10-24 11:37 | 审计背景包 v30 构建完成（1048 波吸收后，为下一波双路审计准备）—— 2026-10-24 中午 | 触发：用户「这一批也吸收完了，为下一批做准备吧」＝ 1048（dv28 九支）已合 main，重建背景包 + 双路提示词，落挂载笔记子文件夹。 |
| 10-24 11:44 | deep-audit-1048（v26-A/B 波）全批闭环：main `b82aa8dc`（+ docs `d7e2493e`）+ main CI `40057945965` success（26 步 skipped=[] ⇒ Publish 真执行）→ 发布 v1.0.0+2597 → 清理 100% | 合并链：用户放行 → integration/dv28 @ 7275c81e --no-ff 合入 main（零冲突，合并树 vs 并集树 src+scripts+fix-reports diff 0 行，差异仅 doc… |
| 10-24 14:37 | dv29-B4 收口（浏览器会话视口未重放，B-04）：`fix/dv29-b4-browser-viewport-reset` @ `fb6f6321`，分支 CI #2599 绿，停在分支 | 身份链：基线 main b82aa8dc → 4 提交（bbae0ada 生产 +74/−14 · 29bb5ded 测试 +317/−8 · 55d898d8 门 +315/−2 ← CI 绿于此 · fb6f6321… |
| 10-24 14:58 | FIX-B3 收口（deep-audit-1049 / dv29 波 B-03·B-05·B-06，全 P1）：`fix/dv29-b3-share-print-composer` @ `0f6e6ba4`，CI #2605 success，停在分支 | 身份链：基线 main b82aa8dc → 4 提交（c5f211bc 生产 8 文件 +1008/−58 / ed2405d0 测试 / a183d691 门 3 文件 ← CI 绿于此 / 419ff840+0f6… |
| 10-24 15:05 | FIX-A2 收口（deep-audit-1049 / dv29 波 A-02+A-03+A-04，含 1048 遗留 V-1）：`fix/dv29-a2-headless-binding-and-fk-crash`，分支 CI 绿 @ `0d0b7c80`，停在分支 | 范围（4 条 3 文件生产 + 4 文件测试 + 2 道既有门扩展，0 新建门 ⇒ 门 111 / 夹具 342 双不变） |
| 10-24 15:16 | FIX-B2 收口（deep-audit-1049 / B-02 P1）：`fix/dv29-b2-offmain-compose-state` @ `82373a43`，CI #40071869246 success，停在分支 | 缺陷：4 个写点（报告说 3 处，实为 4 —— 画廊两个标志各一处）在后台线程直写 Compose remember{mutableStateOf} 状态：ImageGalleryViewer.kt:324/360（a… |
| 10-24 15:57 | deep-audit-1049（v27-A/B 波 + 真机 P1 合并）收口前状态：8 支 CI 绿 → 并集 `integration/dv29` @ `3a051b46` 并集 CI 全绿，文档已自合 main `6c7c4636`，**代码并集待用户放行** | 输入：v27-A（A-01..A-07）+ v27-B（B-01..B-06）+ 真机验证抓到的两条 P1（§2597-VERIFY-2 R4 prefs 写盘不生效 / §2597-VERIFY-3 不存在会话崩进程）… |
| 10-24 16:18 | dv29 批次已闭环：main `6576c7fa` + main CI `40076239617` success（skipped=[]，Publish 真执行）→ 发布 v1.0.0+2612 → 分支/worktree 清理 100% | 合并链：用户「合并」放行 → docs 工作树 main 6c7c4636 --no-ff 合 integration/dv29 @ 3a051b46 → main 6576c7fa（合并树 vs 并集树 src+scr… |
| 10-24 20:01 | feat/auto-approve-mode 合并收口（2026-10-24 晚，用户「合并吧，不放心的做准备」）→ main `20a9fbad` + CI #2618 绿 + v1.0.0+2618 | 收口链：分支 c51f8652（含报告提交，未推远端；源码树相对 CI 绿 42cc8b73 仅 fix-reports/）→ --no-ff 合 main（父 91bd879b）→ main 20a9fbad → pu… |
| 10-24 23:57 | fastio 不被调用诊断 + 提示词修复（fix/prompt-fastio-rg @ 27c951db，CI #2628 绿，停分支） | 用户问题：AI 不主动用 minis-fastio，总用慢的原生方式（场景：清理修过程积累的数据）。 |
| 10-25 00:21 | META-AUDIT-1024 第一波合并收口：main `564c8f3e` + main CI `40109463245` success（Publish 真执行）→ 分支/worktree 清零（2026-10-24 晚） | 合并链：用户放行「合并吧」→ 三项核查（diff 逐文件已核 / 本地 112/0 + ALL 344 + CI sha 三方一致 / 三源取二 = 机械复证 12/12 CAUGHT + CI + 本地全量）→ mai… |
| 10-25 05:56 | wide-1024 支 05（chat-session 三门）收口：10/10 见证 CAUGHT，CI #2633 绿，停分支 | 分支 fix/meta-wide-05-chat-session（基线 main 564c8f3e）。CI 绿 sha 4677e428（run 40134913784 / job 120994279978 / atte… |
| 10-25 05:58 | wide-1024 支 09-browser-webview 收口：5 门见证加固，16/16 全抓住，CI #2632 绿（2026-10-25 早） | 分支 fix/meta-wide-09-browser-webview @ 5f384731（源码树），报告提交 1059156e（fix-reports/ 5 份，源码与绿 sha 等价：git diff --name… |
| 10-25 06:01 | wide-1024 12-misc-b 收口：六门见证加固 20/20 CAUGHT（分支 `fix/meta-wide-12-misc-b` @ `7ef7291a`，CI #2634 run `40134937808` success，停分支） | 输入：任务书 /var/minis/shared/work/meta-wide/task-12-misc-b.md（6 门 / 20 条门瞎见证）。基线 564c8f3e。 |
| 10-25 06:12 | wide-1024 支 07（mcp-tools 三门）收口：CI 绿，停分支 — 2026-10-25 | 分支 fix/meta-wide-07-mcp-tools @ d1a90931（判据+臂）→ 49552874（报告回填，源码树等价 0 diff）。基线 564c8f3e。CI run 40135996652（#26… |
| 10-25 06:13 | wide-1024 支03（五道 provider 门）收口：17 条见证 PRE=BLIND → POST=CAUGHT，CI #2631 绿，停分支 | 分支 fix/meta-wide-03-provider-registry @ 7cdb750c（基线 main 564c8f3e；2 提交：d29cae00 五门加固 + 7cdb750c 报告），worktree s… |
| 10-25 06:22 | wide-1024 支 02（provider 对称门）收口：2 门 6 见证全抓，CI #2640 绿，停分支 | 分支 fix/meta-wide-02-provider-symmetry @ 6ff65aad（CI 绿树 02aad4c5，报告回填后 git diff 02aad4c5 HEAD -- . ':(exclude)f… |
| 10-25 06:23 | wide-1024 支 11-misc-a 收口：5 道 CI 门判据加固，15/15 见证反攻 CAUGHT，分支 CI #2639 绿（2026-10-25 凌晨） | 分支 fix/meta-wide-11-misc-a → d6911e89（门+报告）+ 67761d40（报告回填 CI），已推远端，停在分支未合并 main（任务书明令）。基线 main 564c8f3e。生产代码零… |
| 10-25 06:35 | wide-1024 第 08 支收口：文件预览/URL/URI/分享/tar 五门见证加固（fix/meta-wide-08-file-preview @ 8b92eeee，CI #2641 success，停分支） | 基线 564c8f3e（main）。零生产代码改动，5 门 938+/47−，2 提交（5273a821 门 + 8b92eeee 报告）。 |
| 10-25 06:36 | wide-1024 支 10（4 门 12 见证）收口：分支 `fix/meta-wide-10-skill-backup-compact` @ `96ad4ab0`，CI #2643 / run `40137834021` success（head_sha 逐字符一致） | 12/12 见证反攻通过（修复前 rc=0 → 修复后 rc=1，restore=True 全部 sha256 一致）： |
| 10-25 07:19 | META-AUDIT wide-1024 收口：48 门全部加固，原见证反攻 154/154 抓住，并集 `ca1920b2` CI 绿（2026-10-25） | 12 支全部完成（会话子代理）：48 门 + 标杆 1 门 = 49 个门脚本 +16297/−530（99 文件，含 50 份报告）。 |
| 10-25 08:50 | 门见证第三批背景包构建完成（2026-10-25 08:5x，基线 main `6b6c918f`） | 触发：用户「上一波报告已吸收，准备下一波，挂载文件夹背景包」。上一波 wide-1024 已并入 main（6b6c918f，49 门加固 + 154/154 原见证反攻）。 |
| 10-25 09:04 | wide-1024 全流程收口：main `6b6c918f` + main CI #2645 绿（Publish 真执行），分支/worktree 清零 —— 2026-10-25 | 合并链：用户「合并」放行 → 三项核查已过 → main worktree（deep-audit-1049/repo，auto-approve/repo detached @ 564c8f3e）--no-ff 合 ca1… |
| 10-25 12:15 | wide3-03 支（TASK-03-compact-loop）收口：4 门 14 见证反攻 14/14 CAUGHT，分支 CI #2646 绿，停分支（2026-10-25） | 分支 fix/meta-wide3-03-compact-loop（wt-03，基线 main 6b6c918f）；CI 绿树 f8ae7f6e（run #2646 / id 40160005688 / job 1210… |
| 10-25 12:19 | wide3-12 收口：4 门 12 见证全部加固，分支 `fix/meta-wide3-12-media-mount-trace` @ `1e3b9ca2`，CI #2648 绿（停分支） | 任务 TASK-12（meta-wide3 第三批 / 组 B）：把 4 道门的判据从「某物存在」升级到「该物的效果真的达成」，12 条见证全部固化成 --selftest 负向臂。生产代码 0 改动，scan.sh/t… |
| 10-25 12:27 | wide3-07（UI 交互四门）收口：13/13 见证反攻 CAUGHT，分支 CI #2649 绿，停分支 —— 2026-10-25 | 分支 fix/meta-wide3-07-ui-interaction，基线 main 6b6c918f。只改 4 个门脚本（ime_inset_guard / tool_title_error_branch_check… |
| 10-25 12:39 | wide3 支 04（loop/turn/flow/trace 四门）收口：13/13 见证 CAUGHT，分支 CI #2654 绿（2026-10-25） | 分支 fix/meta-wide3-04-loop-turn @ a5f6d93a（门+报告 3a2968f9 / 报告 ed63130d / 回填 a5f6d93a），基线 main 6b6c918f。生产代码 0 行… |
| 10-25 12:40 | META-WIDE3 支 08 收口：skill/backup 四门判据加固，12/12 原见证反攻 CAUGHT，分支 CI #2653 绿（2026-10-25） | 分支 fix/meta-wide3-08-skill-backup（基线 main 6b6c918f）→ 892474eb（门）+ d3f0bfb6（报告）+ 53cd28fb（CI 回填），已推远端，停分支不合并 ma… |
| 10-25 12:48 | WIDE3-06 收口：浏览器/webapp/KaTeX 四门见证加固（分支 `fix/meta-wide3-06-browser-webapp` @ `687daa83`，CI #2656 绿，停分支） | 基线 main 6b6c918f。4 门 × 3~4 条外部见证，修复前 14/14 全绿（门瞎）→ 修复后 14/14 CAUGHT（shared/work/meta-wide3/reattack.py 逐条 appl… |
| 10-25 12:58 | wide3-01 provider-a 收口：4 门 14 见证全抓住，分支 CI #2655 绿（2026-10-25） | 分支 fix/meta-wide3-01-provider-a @ 5674f3a6（代码提交 5ad1226b = CI 绿树，报告回填后 diff --name-only 只含报告）。基线 main 6b6c918f… |
| 10-25 13:33 | wide3 支 02（provider-b，4 门 12 见证）收口：分支 `fix/meta-wide3-02-provider-b` @ `a97a3dd4`，CI #40164642837 success（attempt 1，head_sha 逐字符一致），停分支 | 12/12 见证反攻 CAUGHT（修复前 12/12 STILL_BLIND；装置 probe/wit.py + probe/run_reattack.sh，restore_ok=True 全部）： |
| 10-25 14:57 | wide3 第三波门见证收口：49/50 门被证伪并全部补强，并集 `19e70dab` CI #2661 绿（2026-10-25） | 输入：A/B 两份报告（各 363KB / 25 门 × 4 尝试 = 200 条）。基线 main 6b6c918f。 |
| 10-25 17:33 | WIDE4-08 收口：exec_gate / truncated_call 两门 10 条见证全部加固，12/12 反攻 CAUGHT，分支 CI #2666 绿（2026-10-25） | 分支 fix/wide4-08-execgate-truncated @ ea4a6512（门 63e4f883 → 文案/臂 395630e6 → 报告 ea4a6512），基线 dbab7a3e（wide4 共享原语… |
| 10-25 17:34 | WIDE4-07 收口：gemini_base_version_check 五条见证全补强，分支 CI #40179534916 绿（2026-10-25 傍晚） | 分支 fix/wide4-07-gemini-base（基线 19e70dab + 共享模块 dbab7a3e）→ 代码 4fa606d7 / 报告 fbfd6643 / 回填 bf09b3ac。生产代码 0 行，sca… |
| 10-25 17:36 | WIDE4-06 收口：scheduled_store_release_guard 五轴加固，7/7 反攻 CAUGHT，分支 CI #2663 绿（2026-10-25） | 分支 fix/wide4-06-sched-store（基线 main 19e70dab + wide4 共享原语 dbab7a3e）→ 代码提交 4e1084b7（+387/−75，仅 1 文件）+ 报告回填 fa92… |
| 10-25 17:46 | WIDE4-10 收口：debug_leak_guard / legacy_pipeline_guard 判据加固（分支 `fix/wide4-10-debugleak-legacy` @ `c6ff4d82`，CI #2669 绿，停分支） | 基线 dbab7a3e（= main 19e70dab + 共享 Kotlin 词法/语法原语）。只改两个门脚本（+713/−248）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotli… |
| 10-25 18:06 | wide4 支 12 收口：four_way + skill_import 两门判据加固（分支 `fix/wide4-12-fourway-skillimport`，CI #2671 绿，停分支） | 基线 19e70dab（worktree wt-12，基分支含共享 _kotlin_lexer/_kotlin_ast）。改动面：2 个门脚本（+1080/−166）+ 1 份报告；生产代码 0 行、scan.sh/te… |
| 10-25 18:16 | WIDE4-05 收口：IME-inset 门 6 条见证全数反攻，分支 `fix/wide4-05-ime-inset` @ `372baa29`，CI #40182328104 绿，停分支 | 基线 dbab7a3e（= 19e70dab + 两个共享 Kotlin 原语模块）。只改 1 个门脚本（scripts/scan/ime_inset_guard.py +423/−156），生产 .kt 0 行、sca… |
| 10-25 18:19 | WIDE4-11 收口：`backup_remap_rollup_check` 5 条盲轴修正向判据（+23 臂 / 55 臂），分支 `fix/wide4-11-backup-remap` @ `b6e405ce`，CI #2672 绿，停分支 | 基线 dbab7a3e（= 19e70dab + 两个共享模块）。生产代码 0 行改动，只改本门 + 报告；scan.sh/test_scan.py/_kotlin_lexer.py/_kotlin_ast.py 零触碰… |
| 10-25 18:28 | wide4-04 收口：三门判据从「拼写存在」改到「效果达成」，19 见证 13 新抓 + 6 保持抓，分支 CI #2674 绿（2026-10-25） | 分支 fix/wide4-04-skillfile-loopstat-provider @ 6422102e（代码提交 9cf49582 = CI 绿树，报告回填后 diff --name-only 9cf49582..… |
| 10-25 18:31 | WIDE4-02 收口：katex/budget/liveness 三门见证加固（分支 `fix/wide4-02-katex-budget-liveness` @ `9697a52e`，CI #40183453003 绿，停分支） | 基线 dbab7a3e（= 19e70dab + 共享原语 _kotlin_lexer.py/_kotlin_ast.py）。门提交 c35d62a4（3 门 +919/−325，生产 .kt 0 行改动）+ 报告 96… |
| 10-25 20:11 | META-WIDE4 第三波门见证收口：24 门 110 条见证全被证伪并补强，并集 `52b1fdd8` CI #40189827049 绿（2026-10-25） | 输入：A/B 两份外部报告（225/260 KB，24 门 × 4-5 尝试 = 146 条见证）。基线 main 19e70dab（= wide3 并集，仍未合 main）。 |
| 10-25 20:55 | wide4 合并 main 完成：main `6ac77c6b2` + main CI #40192845792 全绿 + 发布 v1.0.0+2678 → 分支/worktree 清零（2026-10-25 12:5x） | 用户「合并」放行 → 三项核查 → --no-ff 合入 → 手动 dispatch → 发版 → 清理。 |
| 10-25 23:28 | BATCH5-t08 收口：backup_streaming / loop_persist 两门 12 见证全翻红（分支 `fix/b5-backup-loop` @ `f1dfd92e4`，绿 sha `fcf9fef29`，CI #40203536128 success，停分支） | 基线 e145ec961（= main + 第五批共享原语 _kotlin_valueflow）。改动：只改 2 个门脚本（+1015/−681）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/… |
| 10-25 23:29 | B5-t07 收口：两门判据从「拼写/存在」改到「多路径效果」，12/12 见证反攻全 CAUGHT，分支 CI #2681 绿（2026-10-25） | 支：fix/b5-modelentry-memorygate（head 0848d1b1 = f77bab3b3 + 报告回填），基线 base/batch5-shared @ e145ec961。门 model_ent… |
| 10-25 23:38 | B5-t02 收口：compact_decision + entry_id_rename 两门 12 条见证全 CAUGHT（分支 `fix/b5-compact-entryid` @ `c9ab770b4`，CI #40203205487 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享原语）。改动面 = 2 个门脚本（+720/−550），生产 .kt 0 行、scan.sh/test_sca… |
| 10-25 23:53 | BATCH5-t03 收口：enum_parse / trace_eval 两门判据加固（12 条见证全反攻成红，CI #2684 绿，停分支） | 支线 fix/b5-enum-trace（wt/t03，基线 base/batch5-shared e145ec961）→ 门提交 e7a6e0d17（+449/−142 enum，+266/−35 trace，生产 .… |
| 10-26 00:03 | B5-t09 收口：file_provider_paths + offload_relative_path 两门加固（12 见证全转 CAUGHT，CI #2686 绿，停分支） | 分支 fix/b5-fileprov-offrel @ 7e9f2c66b（远端一致）· 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow.py）· 提交三段：c5… |
| 10-26 00:08 | B5-t06 收口：`chat_ui_interaction_check` 五条见证补牙（分支 `fix/b5-chatui` @ `d263e14c1`，CI #40208261139 绿，停分支） | 基线 base/batch5-shared @ e145ec961（main 6ac77c6b2 + 共享值流原语）。门提交 931b8d14f（+432/−460，生产 .kt 0 行）+ 报告 55fbb9cd3 →… |
| 10-26 00:56 | BATCH5-t10 收口：offload_host_path / entry_composite_key 判据改值流驱动，12/12 见证翻红，CI 40212058131 success（停分支） | 分支 fix/b5-offhost-entrykey @ e87fd7e4f（源码树 = CI 绿树 e019194ad，git diff --name-only e019194ad..HEAD -- . ':(excl… |
| 10-26 01:09 | B5-t11 收口：mcp_oauth / envvar_group 两门 12 条见证效果化（分支 fix/b5-mcpoauth-envvar @ 716d14f15，CI #2693 绿） | 基线 e145ec961（= main 6ac77c6b2 + 共享原语 _kotlin_valueflow）。门提交 557c5b57f（两文件 +739/−461，生产 .kt 0 行，scan.sh/test_sc… |
| 10-26 01:20 | B5-t04 收口：sysinfo + tool_title 两门 12 条见证全翻红（分支 `fix/b5-sysinfo-title` @ `12d56f186`，CI #2694 绿） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + _kotlin_valueflow）。门提交 9f815f791（+834/−656，生产 .kt 0 行、sca… |
| 10-26 04:05 | 第五批收口到并集（CI 绿，停并集）+ 第七批 28 门复证 + 用户新约束「施工分批派」—— 2026-10-26 凌晨 | 用户新约束（重要，跨批生效）：①「之前派出的任务跑完了」②「整理出来的任务，一次不要排出太多，那些跑门禁验证的操作，会对沙箱造成很大的压力」⇒ 重活串行化纪律落到施工派发上：每波 4 支，上一波出结论再派下一波（此前 1… |
| 10-26 05:03 | B6-t04 收口：webapp_asset_url_cleanup + i18n 两门 12 条见证全翻红（分支 `fix/b6-webasset-i18n`，门提交 `8f1fb1862`，CI #2697 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8f1fb1862（i18n +277/−99，webapp … |
| 10-26 05:40 | B6-t02 收口：provider_rpc_symmetry + file_preview_url_encode 两门 12 条见证全翻红（分支 `fix/b6-rpcsym-urienc` @ `35f7bb008`，CI #2699 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 956f27b0a（2 文件 +841/−541，生产 .kt 0 行、scan.sh/test_sca… |
| 10-26 09:35 | B6-t05 收口：tts_voice_and_sanitizer + katex_ready_race 两门 13 条见证全翻红（分支 `fix/b6-tts-katex` @ 门 `c367faf6f`，CI #2700 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + _kotlin_valueflow）。门提交 c367faf6f（生产 .kt 0 行、scan.sh/test_… |
| 10-26 10:00 | B6-t08 收口：session_provider_enabled + memory_ui_wiring 两门 13 条见证全翻红（分支 `fix/b6-sessionprov-memoryui` @ `72955761c`，CI #2700? 实为 run 40254147183 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 2a237ae91（2 文件 +837/−640，生产 .kt 0 行、res/ 0 行、scan.sh… |
| 10-26 10:05 | B6-t09 收口：tool_result_image_branch + provider_write_rules 两门 12 条见证全翻红（分支 `fix/b6-toolimg-provwrite` @ `2b5cfe0ba`，CI #2704 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 2b5cfe0ba（2 文件，生产 .kt 0 行、scan.sh/test_scan.py/_kotl… |
| 10-26 10:12 | B6-t06 收口：deeplink_settings_routes + provider_type_registry 两门 12 条见证全翻红（分支 `fix/b6-deeplink-protype` @ 门提交 `92bc6ddbc`，CI #2705 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 92bc6ddbc（两文件 +974/−413，生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112 / … |
| 10-26 10:13 | B6-t07 收口：modeluse_proto_single_source + thinking_rule_editor_preserve 两门 13 条见证全 CAUGHT（分支 `fix/b6-modeluse-thinkrule` @ 门提交 `ae0cdce9c` + 报告 `12e879c00`，CI #2703 绿，停分支） | 基线 e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 ae0cdce9c（2 文件 +1237/−719，生产 .kt 0 行、scan.sh/test_sc… |
| 10-26 10:14 | B6-t12 收口：skill_frontmatter / offload_b64 / subagent_handback 三门 18 条见证全翻红（分支 `fix/b6-skillfm-b64-handback` @ `914e8f05d`，CI #2706 绿，停分支） | 基线 base/batch6-shared @ e145ec961。门提交 64c4f46ba（3 文件 +1157/−560，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门… |
| 10-26 10:42 | B6-t10 收口：open_url_broker_scheme + openminis_compat_field_parity 两门 13 条见证全 CAUGHT（分支 `fix/b6-openurl-compat`，门提交 `66137f77e`，CI #2707 绿 @ `47a8b441d`，报告回填 `d5291e1ee`，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（open_url +604/−482，openminis +328/−161），生产 .kt 0 行、scan.sh/test… |
| 10-26 11:19 | B7-t02 收口：skill_restore_session_cleanup + speech_active_engine 两门 12 条见证全红（分支 `fix/b7-skillrestore-speech` @ `8b865532e`，CI #2708 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8b865532e（2 文件 +1124/−720，生产 .k… |
| 10-26 11:42 | B7-t01 收口：chat_session_id_binding + outcome_known_chain 两门 12 条见证全翻红（分支 `fix/b7-sessionbind-outcome` @ 报告 `1c2df293d`，CI #2710 绿，停分支） | 基线 base/batch7-shared（e145ec961）。门提交 926073cfc（2 文件 +795/−379，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 1… |
| 10-26 12:02 | B7-t04 收口：provider_ua_parity + auto_approve_bypass 两门 12 条见证全翻红（分支 `fix/b7-ua-autoapprove` @ 门提交 `579a99d86`，CI #2712 绿，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+992/−488，provider_ua_parity_check.py 1718→1960 行、auto_approve_… |
| 10-26 12:07 | B7-t03 收口：parts_json_funnel + modelsvc_single_read 两门 12 条见证全 CAUGHT（分支 `fix/b7-t03-partsjson-modelsvc` @ `cc92f6e43`，CI #2711 绿，停分支） | 基线 e145ec961。改动面 = 2 个门脚本（parts_json +682/−276，modelsvc +449/−122），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{le… |
| 10-26 12:28 | B7-t06 收口：fastio_tar_bounds + marker_boundary 两门 12 条见证全 CAUGHT（分支 `fix/b7-fastiotar-marker`，门提交 `9101923ab`，报告 `fc5f728ac`，CI #2714 绿 @ 门提交，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+972/−481），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{lexer,ast,v… |
| 10-26 12:29 | B7-t05 收口：share_dir_prune + browser_tab_exec_serial 两门 12 条见证全翻红（分支 `fix/b7-shareprune-tabexec` @ `95c7e952`，CI #2716 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 95c7e952（2 文件 +899/−427）+ 报告 94289cac（git diff --name-only 95c7e952..HEA… |
| 10-26 12:30 | B7-t07 收口：session_sheet_id_binding + provider_models_cache_wiring 两门 12 条见证全 CAUGHT（分支 `fix/b7-sheetsession-provmodels`，门提交 `56617187e`，CI #2713 绿，报告 `11ee82526`，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+785/−436），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_{lexer,ast,v… |
| 10-26 12:31 | B7-t08 收口：browser_destroy_wakeup + mirror_category_route 两门 12 条见证全 CAUGHT（分支 `fix/b7-browserdestroy-mirrorroute` @ `fc5361eba`，CI #2717 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 381beee36（2 文件 +985/−461，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-26 13:03 | B7-t10 收口：schedule_date_source + overlay_show_hide_latch 两门 12 条见证全翻红（分支 `fix/b7-scheduledate-overlay` @ `626eee8bf`，CI #2718 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 626eee8bf（2 文件 +1052/−631，生产 .kt 0 行、scan.sh/test_scan.py/三个 _kotlin_ 零触… |
| 10-26 13:08 | B7-t09 收口：browser_history_snapshot + collection_copy_on_mutate 两门 12 条见证全翻红（分支 `fix/b7-browserhistory-collectioncopy` @ `ca52fb937`，CI #2719 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 ca52fb937（2 文件 +1012/−387，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门… |
| 10-26 13:10 | B7-t12 收口：webview_holder_url_source + chat_menu_prefs_listener 两门 12 条见证全翻红（分支 `fix/b7-webviewholder-chatmenu` @ `0dfb86e2b`，门提交 `378659481`，CI #2720 绿，停分支） | 基线 base/batch7-shared @ e145ec961。门提交 378659481（2 文件 +828/−437，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-26 13:19 | B7-t11 收口：a11y_handler_contract + voice_cancel_guard 两门 12 条见证全翻红（分支 `fix/b7-a11y-voicecancel` @ 门提交 `e90ca37f3`，CI #2721 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 e90ca37f3（2 文件 +781/−336，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-26 13:38 | B7-t14 收口：mirror_vm_thread + file_uri_parse_guard 两门 12 条见证全 CAUGHT（分支 `fix/b7-mirrorvm-fileuri` @ `681bc2ac5`，门提交 `57a86390a`，CI #2722 绿，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（+1035/−697），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 … |
| 10-26 13:53 | B7-t13 收口：calendar_events_nullable_predicate + router_lock 两门 12 条见证全 CAUGHT（分支 `fix/b7-calendar-routerlock` @ `8c95fd6ca`，CI #2723 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 8c95fd6ca（2 文件 +799/−265），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门… |
| 10-26 14:20 | 第七批收口到并集（CI 绿）+ 三批全部到达停止节点（2026-10-26 14:2x） | 第七批 14/14 支 CI success + src_diff=0；并集 integration/b7 @ 2a42ab264c（base/batch5-shared 上 14 支 --no-ff 零冲突，42 文件… |
| 10-26 14:59 | meta-audit v5 项目收口：文档重建 + 三批（b5/b6/b7）全部合 main + main CI 全绿（2026-10-26 15:0x） | 文档：dev-history-sync 三件套重建 → 2325 条 / 24 sagas（orphans 197、multi4 177）；sanitize_dev_history.py 双验证 Remaining se… |
| 10-26 18:58 | R3-t03 收口：浏览器 tab 三件套 9 条见证（W76-W84）修前 BLIND / 修后 CAUGHT，分支 CI #2727 绿（2026-10-26） | 支 fix/r3-browser-tabs，基线 main f0f14d46d。只改 3 门脚本 + 报告：browser_tab_exec_serial_check（+332，15→20 臂）、browser_tabs… |
| 10-26 19:06 | R3-t05 收口：两门 6 条缝见证全 CAUGHT + 分支 CI #2728 绿（2026-10-26 19:1x） | 支 fix/r3-menuprefs-katex：门提交 5eabfb51e（CI 绿 sha）+ 报告回填 b32e88c36（git diff --name-only 5eabfb51e..HEAD = 仅 fix-… |
| 10-26 19:06 | R3-t15 收口：anthropic_thinking_budget + thinking_wiring 两门补缝（7 条跨门缝见证全 CAUGHT，CI #2733 绿，停分支） | 支线 fix/r3-thinking-wiring @ 6bcb84cf4（门提交 3110d6e29），基线 main f0f14d46d。改动面 = 2 个门脚本 +912/−95，生产 .kt 0 行，scan.s… |
| 10-26 19:14 | R3-t08 收口：parts_json_funnel + skill_restore 两门 6 条见证全 CAUGHT（分支 `fix/r3-partsjs-skillrestore` @ `a924214c2`，门提交 `1c3f9c497`，CI #2731 绿，停分支） | 基线 main @ f0f14d46d。改动面 = 2 个门脚本（+693/−13）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰，门 112 / 夹具 344 不变。 |
| 10-26 19:17 | R3-t10 收口：outcome_known_chain + stale_closure 两门跨门缝加固（6/6 BLIND→CAUGHT，CI #2732 绿，停分支） | 分支 fix/r3-outcome-stale @ 9820d8ac0（门提交 eec06ccf3，基线 main f0f14d46d）。改动面 = 2 个门脚本（+682/−110）+ 报告，生产 .kt 0 行、sc… |
| 10-26 19:17 | R3-t09 收口：loop_persist + loop_tool_status 两门加固，6 条见证全 CAUGHT（分支 `fix/r3-loop-persist` @ `0060a759d`，CI #2729 绿，停分支） | 基线 main f0f14d46d（第七批收口后）。改动面 = 2 个门脚本（loop_persist_slice_check.py +273/−6、loop_tool_status_transition_check.p… |
| 10-26 19:20 | R3-t14 收口：offload_cli_args + fastio_tar_bounds 两门判据加固（6/6 见证反攻 CAUGHT，分支 `fix/r3-offload-fastio` @ `c0039db84`，CI #2735 绿，停分支） | 基线 main f0f14d46d。改动面 = 2 个门脚本（+1017/−2）+ 报告，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 / 夹具 344 不变。 |
| 10-26 19:21 | R3-t07 收口：chat_session_id_binding + session_sheet_id_binding 两门 6 条 dual-gate 见证全 CAUGHT（分支 `fix/r3-sessionbinding` @ `80aef69a1`，CI #2734 绿，停分支） | 基线 main f0f14d46d。只改 2 个门脚本（+610/−10），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112/夹具 344 不变。报告 fix-repo… |
| 10-26 19:32 | R3-t06 收口：mirror_category_route + mirror_vm_thread 两门（第三轮 B 波 · 6 条见证全 CAUGHT，分支 CI 绿，停分支）（2026-10-26 19:2x） | 支 fix/r3-mirror-route：门提交 3c1bd1cee + 报告回填 c82c1397f（已推远端），基线 main f0f14d46d。改动面 = 2 个门脚本（+449/−5、+333/−0）+ 报告… |
| 10-26 19:38 | R3-t01（第三轮 B 波 · 跨门缝三件）收口：9/9 见证 BLIND→CAUGHT，分支 CI 绿（2026-10-26 19:3x） | 支 fix/r3-fileuri-webview（基线 main f0f14d46d，wt/t01）。三门：file_uri_parse_guard_check / webview_holder_url_source_c… |
| 10-26 19:49 | R3-t18 收口：backup_remap_rollup / mcp_oauth_lifecycle / backup_streaming_export 三门 9 条见证全 CAUGHT（分支 `fix/r3-backup-mcp` @ `335faf435`，CI #2741 绿，停分支） | 基线 main f0f14d46d。改动面 = 3 个门脚本（+928/−43），生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112 / 夹具 344 不变。绿 sha 9c17f… |
| 10-26 19:52 | R3-t21 收口：deeplink_settings_routes + open_url_broker_scheme 两门判据加固（CI #2740 绿，停分支） | 任务：round3b 第三轮 B 波（dual-gate bypass 收口批 19 门）支线 — 修 2 个门脚本，基线 main f0f14d46d，分支 fix/r3-deeplink-openurl。 |
| 10-26 19:58 | R3-t12 收口：log_url redaction + UA parity 两门值流化，6/6 见证修前 BLIND → 修后全抓，CI #2742 绿（停分支） | 支：fix/r3-logredact-ua（wt/t12，基线 main f0f14d46d）→ 门提交 0c65e85f7（2 门脚本 +737/−207，臂 17→28 / 56→60）+ 报告 4bd421bc4；… |
| 10-26 20:08 | R3-t02 收口：三门缝隙判据加固（9 条 dual-gate 见证 9/9 反攻成红，CI #2745 绿，停分支） | 分支 fix/r3-preview-asset（基线 main f0f14d46d）→ 源码绿 sha e66acd235，回填 33cef83fd，远端 tip = 本地 HEAD。改动面 3 个门脚本（+949/−2… |
| 10-26 20:12 | R3-t22 收口：file_provider_paths + schedule_date_source 两门加固（分支 `fix/r3-filepaths-schedule` @ `990343cdd`，CI #2746 success，停分支） | 基线 main f0f14d46d，worktree round3b/wt/t22。改动面 = 2 个门脚本（+753/−4）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零… |
| 10-26 20:13 | R3-t19 收口：skill_file_save_result + skill_import_offmain 两门补 6 条 round-3 缝级判据（分支 `fix/r3-skillfilesave` @ `009916a26`，门提交 `f686031b5`，CI #2747 绿，停分支未合并） | 基线 main f0f14d46d。改动面 = 2 个门脚本（+808/−65），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 / 夹具 344 不变。 |
| 10-26 20:28 | R3-t04 收口：第三轮双门缝（downloads / share / mediaplayer 三门）9 条见证全 CAUGHT（分支 `fix/r3-fileprune-media` @ `66f9a20f0`，CI #2749 绿，停分支） | 基线 main f0f14d46d；工作树 round3b/wt/t04。门提交 5922aef95（3 文件 +1047/−3，生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112… |
| 10-26 20:40 | R3-t16 收口：xproc_boundary + exec_gate_release 两门判据加固（7 见证 7/7 CAUGHT，分支 CI #2750 绿，停分支） | 分支 fix/r3-xproc-execgate（基线 main f0f14d46d）→ 门提交 5cbb92105 + 081244e5f（打印每条判据）→ 报告 e46e3078e/576765d41/81aba54… |
| 10-26 21:49 | R3 + P1 合并 main 完成，main CI #2753 绿，发版 v1.0.0+2753，分支清零（2026-10-26 21:4x） | 用户「合并」放行 → 三项核查 → --no-ff 合入 → 手动 dispatch → 清理。 |
| 10-26 23:15 | T1 收口：`_kotlin_ast.enclosing_conditions` else 取反优先级缺陷根治（分支 `fix/primitive-else-negation` @ `1a4eff14d`，绿 sha `b51cd03cf`，CI #2754 绿，停分支） | 基线 main 627f23f2e（R3+P1 合并后）。工作树 debt-1026/wt/primitive。改动面 = 2 个原语脚本（实现 1 行 + KDoc 契约 + 5 条臂），生产 .kt 0 行、scan… |
| 10-26 23:25 | T2 debt-sweep 收口：清三笔登记债务（#2 file:// 路径 / #3 图片附件有界读 / #5 weather 单出口），分支 `fix/debt-sweep-1026` @ `215c1d951`，CI #2755 绿，停分支（2026-10-26 夜） | 基线 main 627f23f2e。改动面 4 生产 .kt + 3 门脚本豁免表：+170/−194（7 文件，全部在任务书白名单内）。scan.sh/test_scan.py/_kotlin_ 零触碰。 |
| 10-27 00:13 | U3 收口：顺路清三笔登记债（分支 `fix/misc-debt-sweep`，绿 sha `b06bae548`，CI #2756 绿，停分支） | 基线 main 627f23f2e。工作树 debt-1026/wt/misc。改动面 2 生产 .kt + 1 门脚本（+49/−2），另 1 个脚本在仓库外。scan.sh/test_scan.py/_kotlin_… |
| 10-27 00:30 | U4 收口：`file://` 契约「跨界编码 / 内部解码」（分支 `fix/fileurl-contract` @ `8ddb12e57`，绿 sha `621602d43`，CI #2757 绿，停分支） | 基点 T2 分支 8d00395af（含 T2 全部提交）。改动 8 文件 +450/−40：生产行为 4 文件（未越任务书白名单，全逐条登记）+ 3 测试 + 1 门脚本。 |
| 10-27 00:43 | U2 图像链收口：分支 `fix/image-chain-oob` @ `9d8d70f77`（绿 sha `db8c0b202`），CI #2759 绿，停分支（2026-10-27 凌晨） | 四笔全修，1 提交生产 6 文件 +231/−87 + 测试 2 文件（ChatImageRestoreTest 8 臂 + DailyLogRollupMetricsTest 3 臂）。 |
| 10-27 01:24 | U 批四支收口（U1-U4 全部 CI 绿，2026-10-27 01:0x） | 四支全部 success、停分支未合 main： |
| 10-27 01:25 | U1 收口：三原语第二轮 A1-A4 修复（分支 `fix/primitive-ast-round2` @ `5bdbc48ff`，CI #2761 绿，停分支） | 基线 1a4eff14d（T1 收口后，本支延续而非基于 main）。三个提交：47f988cfa（A1-A4）→ 626985db9（branch_value 补正）→ 5bdbc48ff（报告）。改动面 3 文件 +… |
| 10-27 06:13 | agentloop 基线补 t09-t13 收口（分支 `test/agentloop-behavior-baseline` @ `9b608a95`，CI #2764 绿，停分支） | 基线 74b1ba65（首版 8 场景）。新增 5 场景 + 3 处 fake 见证面，生产 .kt 0 行：改动 2 个测试文件 +267/−1（AgentLoopBaselineScenariosTest.kt +2… |
| 10-27 06:41 | 沙箱缺陷召回修复收口（分支 `fix/defect-recall-seeds` @ `9d7c0c85b`，报告 `acdeae6b8`，CI `40409678884` 绿，停分支）—— 2026-10-27 | 用户说「修吧」→ 走分支隔离全流程收口。改动 2 文件 +187/−15（SandboxDefectRegistry.kt +96、测试臂 15→20）。 |

## 15. 开发档案 / 记忆 / 工具链

**跨度** 2026-08-03 ～ 2026-10-27 · **200 条** · **状态** 常态维护（skill: dev-history-sync，每次 dev 会话收尾同步）

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
| 09-22 14:42 | 09-22：应用开发阶段收口 —— 总账梳理 + 档案更新（分支 CI 进行中） | 用户指令：「应用开发告一段落，帮我总结梳理这一段开发的具体情况，然后更新文档」。 |
| 09-22 15:44 | 09-22：backlog 触发条件全量核查（「攒着的还有要修的吗」） | 设备版本 = 1.0.0+1745（14:18:28 装）。按修复门纪律逐条核 backlog 触发条件： |
| 09-23 00:34 | 闭环审计框架落地 + spawn_agent 技能快照坑 | 工具坑（新，已实证） |
| 09-23 17:20 | P1 三条批量处置：两条已被修、一条方向与 backlog 原判相反（09-23） | 用户指令：「p1，那就把他们都修了吧」（P1 = §27a 图片门 / §27c(2) canResume 检查 / §29 换源重试）。 |
| 09-23 22:01 | skill description 收窄铺开：12/21 全部完成（2026-09-23 晚） | 结果：12 个有 evals 的 skill 全部压到 ≤200，description 总字符 4344→1664（−62%）， |
| 09-24 15:10 | 用户指令「更新一下文档」→ dev-history 三件套重建 + 推送公开仓库。 | 用户指令「更新一下文档」→ dev-history 三件套重建 + 推送公开仓库。 |
| 09-24 15:16 | 合并收口（用户放行）：--no-ff merge docs/dev-history-0924 → main = 7b76 | 合并收口（用户放行）：--no-ff merge docs/dev-history-0924 → main = 7b76c034（3 文件 +1320/−46）；gh_sync.sh push --branch main… |
| 09-25 10:00 | 用户指令「更新一下文档」→ dev-history 三件套重建 + 推送公开仓库（09-25）。 | 用户指令「更新一下文档」→ dev-history 三件套重建 + 推送公开仓库（09-25）。 |
| 09-25 10:01 | 合并收口（用户放行）：--no-ff merge docs/dev-history-0925 → main = 15b0 | 合并收口（用户放行）：--no-ff merge docs/dev-history-0925 → main = 15b06f3（3 文件 +171/−34）；gh_sync.sh push --branch main 成… |
| 09-25 16:33 | backlog 存量盘点（09-25，应问分析）：1079 行 ~36 条未关项，净「该修未修」只有 13 条 P2（§ | backlog 存量盘点（09-25，应问分析）：1079 行 ~36 条未关项，净「该修未修」只有 13 条 P2（§24a/26/27c(1)/27d/27i/30/32b/34-F1-F4/38A/39），其中值得… |
| 09-25 16:51 | §38A 落地（09-25）：fix/skills-prompt-budget @ 254ecc7（1 文件 +6/−1 | §38A 落地（09-25）：fix/skills-prompt-budget @ 254ecc7（1 文件 +6/−1，MAX_SKILLS_IN_PROMPT 20→40 + 来源注释），本地 scan.sh 18/… |
| 09-25 17:39 | 合并收口（09-25，用户放行，两分支）：--no-ff 按序 merge fix/skills-prompt-budg | 合并收口（09-25，用户放行，两分支）：--no-ff 按序 merge fix/skills-prompt-budget（254ecc7c）→ 1a5d4096、fix/stream-recovery-grace-r… |
| 09-25 22:15 | rikkaminis-dev-methodology 评审 + 修正（09-25，用户要求复核别处模型的技能优化） | 背景修正：用户澄清——今天的改动已经真机验证过（装的是分支包，不是等 main 的包）。所以上一条审计的"用户手里 1.0.0+1802 不含今天改动"应更正为：band 那条改动已经过真机验证（用分支 CI 产物）。 |
| 09-25 22:43 | ↳ rikkaminis-dev-methodology 第 1 批修复完成（v1.3.1 → v1.3.2，09-25） | 关键环境事实（纠正我上一条的说法）：/var/minis/skills 不在任何 git 仓库里（git rev-parse --show-toplevel → fatal）。技能改动没有分支/CI 可走，其等价物是：①… |
| 09-25 22:58 | ★ 技能元数据的真相：应用读的是 skills.db，不是 SKILL.md 的 frontmatter（09-25 用户实证发现） | 用户问「为什么我没看到这个技能对应的改变」——他发现技能显示 version 1.0.0 + 旧的长 description，但正文已经是我今天改过的新内容。 |
| 09-25 23:34 | skill-metadata-resync 核查 + 合并收口（09-25，用户放行）：远端分支 fix/skill-m | skill-metadata-resync 核查 + 合并收口（09-25，用户放行）：远端分支 fix/skill-metadata-resync-from-disk @ 56b65fe1（1 commit，3 文件 … |
| 09-25 23:51 | 09-25 三天改动真机验证闭环（用户确认 1/2/3 已验证）：①skill-metadata-resync（装 +e | 09-25 三天改动真机验证闭环（用户确认 1/2/3 已验证）：①skill-metadata-resync（装 +ee22115b 包，改 SKILL.md description → 重开 → 技能页显示新值）✅；… |
| 09-26 00:05 | chore/remove-skill-detail-rescan 分支 CI 绿（09-25 深夜，停止节点）：用户发现 | chore/remove-skill-detail-rescan 分支 CI 绿（09-25 深夜，停止节点）：用户发现技能详情页「从磁盘重新扫描」在 fix/skill-metadata-resync-from-dis… |
| 09-26 00:08 | chore/remove-skill-detail-rescan 合并收口（09-26，用户放行）：全套 8 项核查在本 | chore/remove-skill-detail-rescan 合并收口（09-26，用户放行）：全套 8 项核查在本会话独立复跑（非沿用昨夜结论）——①diff 走查干净（1 个 .kt + 7 语言文件，+2/−4… |
| 09-26 00:13 | ↳ 教训（我犯的）：绕过 verify_branch.sh 编排器手跑子集（09-26 复发确认）：合并 chore/rem | 教训（我犯的）：绕过 verify_branch.sh 编排器手跑子集（09-26 复发确认）：合并 chore/remove-skill-detail-rescan 时只手跑了 diff/残留/scan.sh/冲突/括… |
| 09-26 00:19 | remove-skill-detail-rescan 验证闭环（09-26 用户确认）：用户用分支包（68ef2d51 | remove-skill-detail-rescan 验证闭环（09-26 用户确认）：用户用分支包（68ef2d51 的 CI 产物）真机验证通过——main @ e16c5f6e 是 --no-ff merge，树内… |
| 09-26 00:19 | 技能元数据回灌修复（09-25 深夜，已进 main） | 根因（三源核实）：技能元数据存 skills.db，模型侧 <available_skills> 读库里的 description（截 200 字符）；reloadFromDisk()/loadAll() 只从库读行（仅… |
| 09-26 00:27 | dev-history 三件套重建 + 推送（09-26，任务「更新一下文档」）：三个回归测试先过（test_rebui | dev-history 三件套重建 + 推送（09-26，任务「更新一下文档」）：三个回归测试先过（test_rebuild/test_sanitize/test_sagas 全 PASS）→ rebuild（55 天，… |
| 09-26 16:33 | dev-history 三件套重建 + 推送（09-26，任务「更新一下文档」，第二次——00:27 已有一轮）：三个回 | dev-history 三件套重建 + 推送（09-26，任务「更新一下文档」，第二次——00:27 已有一轮）：三个回归测试先过（test_rebuild/test_sanitize/test_sagas 全 PASS… |
| 09-27 14:44 | dev-history 三件套重建 + 推送（09-27，任务「更新一下文档」，今日第二次——14:40 曾有一轮同日重 | dev-history 三件套重建 + 推送（09-27，任务「更新一下文档」，今日第二次——14:40 曾有一轮同日重建落档，本轮为仓库同步） |
| 09-27 17:55 | 合并 docs/dev-history-0927 → main = 7a5f54b9（09-27，用户「那也顺便处理了吧」） | 核查（纯文档，按影响面配比装置）：三个回归测试 test_rebuild / test_sanitize / test_sagas 全 PASS；条目数对账口径（易踩）：header「条目总数 1364」== parse… |
| 09-28 09:50 | HF 语义记忆 + 知识图谱记忆更新（09-28，用户要求处理） | HF 语义记忆：索引曾停在 09-21 14:00（1239 条），7 天新条目未入索引。增量重建（build --incremental，复用 1231 条旧向量 + 新嵌 165 条）→ 1396 条 / 8MB，已… |
| 09-28 09:50 | memory MCP 服务器移除（09-28，用户拍板「日常使用中根本没发挥作用，去掉」） | minis-mcp-cli remove memory 已删，MCP 集成只剩 termux-dock。判据 = 一阶门：该服务器无持久化（每调用新 session 写入蒸发）+ daemon 不传 env，shell/… |
| 09-28 09:55 | ↳ 文档三件套第二次重建 + 合并 main = 346ebf59（09-28，用户「更新文档是低风险，直接合并」） | 三回归测试（test_sanitize/test_rebuild/test_sagas）全 PASS → rebuild 1399 条（前次 1386 → +13，dropped 75）→ sanitize main+I… |
| 09-28 13:44 | 合并 main = bd42e3df（09-28，用户「合并」）：docs/dev-history-0928b @ d3 | 合并 main = bd42e3df（09-28，用户「合并」）：docs/dev-history-0928b @ d3d3231 --no-ff 合并（零冲突，parents 4ef343f + d3d3231），4 … |
| 09-28 20:48 | S4 · A/B 评测装置（run_eval + score_eval）完成（09-28，任务书 task-A4-eval-harness.md） | 交付（Lab 内只这四个，未碰他人文件；contract.py sha 未变）：scripts/run_eval.py 344 + scripts/score_eval.py 578 + scripts/tests/te… |
| 09-29 01:11 | dev-history 档案重建 + 合并（2026-09-29，用户授权「文档低风险，直接合并」） | 结果：docs/dev-history-0929 分支 = c921b15f → --no-ff 合并 → main = 7793f4a1（已 push，remote == local 逐字节）。远端分支已删，origi… |
| 09-29 09:26 | 技能入口标签修正（09-29，用户报「导入技能→Minis Skills 指向不一致」） | 问题：09-23 package rebrand（squash 7b7f12cd）把添加菜单/浏览器标题从 "Minis Skills" 改成 "RikkaMinis Skills"，但默认 URL 仍是 OpenMin… |
| 09-29 09:27 | 合并 fix/skills-entry-label-mismatch → main = b692f2f3（09-29，用户「检查没问题就等 CI 完合并」+「触发完不用等」） | 改动：7 locale strings.xml，21 处标签 "RikkaMinis Skills" → "Minis Skills"（skill_empty_action / skill_minis_skills_mo… |
| 09-30 20:37 | Backlog 防御性质占比定案（09-30，用户问「攒着的没修的有多少是防御性质」） | backlog 全文 1338 行逐条分类：登记未修缺陷类 42 条，防御性质 14 条（33%）；非防御 28（清理 8 / 观察 7 / 小 bug 6 / latent 2 / 性能 2 / 其他 3）。另有 2 … |
| 10-01 08:51 | dev-history 三件套更新（10-01，用户「把文档更新一下」） | 全流程重建：1492 条（上次 1137）/ 100 条被排除轴过滤 / 脱敏 main 245 + INDEX 30 处，探针双文件 NONE |
| 10-05 22:06 | backlog 新增：压缩候选链 entry 级预算覆盖（10-05 晚，用户拍板「先攒着」） | 触发依据（已满足升级条件）：小写 glm-5.3-flash（tierflow entry 0df664ef，declared=low\|high\|max → quiet 分区→30s 预算）在压缩摘要任务上 5/5 TI… |
| 10-05 22:43 | ↳ H 分支核查并合并 main（10-05 晚，用户令「检查+有问题修+没问题合并」） | 对象：refactor/compact-orchestration-split-1005 @ 68f83ee6（ChatSessionLifecycle 拆分 CompactOrchestration，2 commits… |
| 10-06 09:12 | dev-history 档案清理：新增 2 排除轴，去掉 21 条无关条目（10-06，用户报「最近几天无关的多」） | 触发：用户要求更新开发档案并去除与 RikkaMinis 开发无关的部分。 |
| 10-06 09:41 | dev-history 云端同步完成：docs-only 归档同步 82410cc 直推 main（沿用该目录 6 先例 | dev-history 云端同步完成：docs-only 归档同步 82410cc 直推 main（沿用该目录 6 先例惯例），remote sha==local 逐字符一致；docs-only 提交不触发 CI 是预期… |
| 10-06 14:25 | 触发：用户拍板筛选标准——「攒着的那些事项，确定需要修并且不引入副作用的，就是有根本原因之类的」= 对 backlog | 触发：用户拍板筛选标准——「攒着的那些事项，确定需要修并且不引入副作用的，就是有根本原因之类的」= 对 backlog 活项按三条件（确定要修/无副作用/有根因）筛出即修。 |
| 10-06 14:26 | chore/backlog-batch-1006 核查完成（云端分支检查，CI 绿 @5c5cd126） | 对象：chore/backlog-batch-1006 @ 5c5cd126（1 commit，相对 main 纯删除 2 文件 -20 行）：删 .commitmsg（backlog §57）+ 删 CompactOr… |
| 10-06 14:54 | chore/backlog-batch-1006 合并完成 + delete-branches 「1 failure」追查闭环（10-06 下午） | 合并：chore/backlog-batch-1006 → main df1f0fea（parents 82410cc3+5c5cd126；merge tree==branch tree 逐字符；推送 82410cc3.… |
| 10-06 17:43 | 外部项目吸收审查：OpenDots + Raven → backlog §67 全部登记不做（10-06） | 对象：OpenDots（CopilotKit，AI 同事模板，本质 = CopilotKit Intelligence 托管服务导流壳）+ Raven（EverMind，RSI 研究 harness，本质 = 云/容器基… |
| 10-08 21:20 | §75 技能快照回灌完成（2026-10-08） | 机制（RikkaMinis 内置技能）：assets/skills/ 是安装种子，SkillRepository.installBundledSkill（:1335）用语义版本比较做门——assets 的 SKILL.m… |
| 10-09 14:15 | backlog 第1+2档批量修复合并收口（2026-10-09） | 分支：fix/backlog-batch-1009 @ 333a1989（基线 main b4647299），CI run 37890926335 success（05:55 dispatch → 06:09 绿，14.… |
| 10-09 14:23 | 分支审查完成：fix/backlog-batch-1009（0 P0/P1，无次生问题，§80 登记） | 对象：fix/backlog-batch-1009 @ 333a1989（tier1+tier2 十项清账，基线 main b464729，14 文件 +90/−28）。CI run 37890926335 succes… |
| 10-10 14:54 | 记忆系统全面更新（2026-10-10 下午） | ① 开发档案三件套：重建 1605 条（08-03～10-10），清洗 + SAGAS 重建（24 saga / 148 orphans / multi4=88），结构核验 anchors==header==1605、f… |
| 10-10 15:24 | MCP 知识图谱退役（2026-10-10，用户拍板「2」= 退役） | 决策：退役 KG（@modelcontextprotocol/server-memory），不复活。理由三条（合起来构成，单条不够）：①写路径从未打通（09-28 无持久化 + daemon 不传 env 给 STDIO… |
| 10-12 20:11 | 「三类地方能不能一起覆盖」侦察判定（2026-10-12 晚，落 backlog §84） | 流式/chunk 生产文件 × 测试交叉对比（main @ 1074a399）：覆盖面比预期完整——T4 修完后流式解析几乎无纯逻辑真空。已覆盖：ThinkTagScanning、StreamingMarkdownTex… |
| 10-12 20:49 | 「T4/T5 效果好，是否继续推进」判定（2026-10-12 深夜，落 backlog §85） | 判定：停，不建全仓盲扫装置。 侦察数据（main @ 8637ea61）：577 生产类，无测试 import 引用差集 534——但校正后大部分是假象：①同包测试假阴性（ui/chat 79 / sandbox 51 … |
| 10-14 00:19 | 上下文压缩循环诊断（backlog §93 登记） | 用户第 3 次遇到「上下文即将占满（~88%）」通知。日志取证：同会话 15 分钟触发 3 次压缩；链中间压缩近乎无效（191K→187K，近端工具输出受保护）；每轮 +16K~203K tokens；最深压缩地板 87… |
| 10-14 15:38 | FIX-R6 完成：MCP 温热握手 + server_name 白名单 + k=v JSON 字面量（fix/swee | FIX-R6 完成：MCP 温热握手 + server_name 白名单 + k=v JSON 字面量（fix/sweep1014-mcp-http @ 8b4bfe1，CI #2018 绿） |
| 10-14 15:43 | FIX-R4 完成：Anthropic mid-tool-call 丢调用 B11 + ThinkTagScanning 围栏反转 B14a + SkillRepository frontmatter B14b | 用户令「读 /var/minis/shared/sweep-1014/tasks/FIX-R4.md 并立刻执行」。基线 main d5d2be59f，分支 fix/sweep1014-providers @ 11087… |
| 10-14 17:17 | FIX-R22 完成：skills-ui B32 更新路径 IO+zip parity/真实 id + D12 WebV | FIX-R22 完成：skills-ui B32 更新路径 IO+zip parity/真实 id + D12 WebView onRelease（fix/sweep1014-skills-ui @ 399a2d76，C… |
| 10-14 21:27 | FIX-RV-C 完成：C1 reply 窗口 + C2 语义 0 条守卫 + C3/C4 OAuth 名对齐 + C5 NaN（CI #2082 绿） | 用户令「读 /var/minis/shared/review-1014/fix-tasks/FIX-RV-C.md 并立刻执行」。分支 fix/rv1014-mcp-security @ be76810c，6 文件 +1… |
| 10-14 21:34 | FIX-RV-E 完成：E1 API33 readNBytes 崩溃 + E2 技能更新 id + E4 视频高度 no | FIX-RV-E 完成：E1 API33 readNBytes 崩溃 + E2 技能更新 id + E4 视频高度 no-op（fix/rv1014-ui-small @ dff1ddf，CI #2083/3857062… |
| 10-14 21:36 | ↳ FIX-RV-D 完成：D1 会话克隆 media 重映射 + D2 KeyRoulette 存量明文迁移（fix/rv | FIX-RV-D 完成：D1 会话克隆 media 重映射 + D2 KeyRoulette 存量明文迁移（fix/rv1014-fork-keys @ e39c66b，CI #2084 绿） |
| 10-14 23:10 | 根治 §97：内置技能资产落盘改内容哈希门控（fix/skill-asset-hash-gate @ 87db4a3，CI #2095 绿，停在分支未合并） | 用户令：选「根治」（二选一：即时 bump 版本 / 根治改内容哈希）。基线 main a7123cf82，3 文件 +351/−34。 |
| 10-14 23:19 | §97 收口：fix/skill-asset-hash-gate 合并 main 15a1f1336（用户令「没有问题就 | §97 收口：fix/skill-asset-hash-gate 合并 main 15a1f1336（用户令「没有问题就合并」+「合并触发之后就不用等了」） |
| 10-15 06:33 | FIX-B 完成：技能/备份恢复修复（fix/audit-1015-skillbackup @ 6645cce，CI #2099 绿，停在分支未合并） | 任务：shared/audit-1015/fix-tasks/FIX-B.md；报告 shared/audit-1015/fix-reports/FIX-B.md；工作树 shared/work/fix-b-1015/r… |
| 10-15 12:05 | dev-history 档案更新 + 仓库同步（2026-10-15，用户令「更新一下文档，包括仓库的」） | 执行：rebuild（1743 条 / 74 天 / 31071 行）→ sanitize（main 296 替换 + INDEX 35 替换，probes NONE）→ SAGAS（24 sagas / 171 orp… |
| 10-15 16:41 | FIX-A5 完成：MCP 守护进程三处（per-name 锁 / 请求上限 / 引号 args）（2026-10-15） | 用户令：「读 FIX-A5.md 并立刻执行」（子任务派发）。基线 main 891d755，分支 fix/audit2-mcpd-1015 @ 305d20cf2d45945975fc24736bf2d48f78036… |
| 10-17 00:57 | verify-1018 真机验证收口：deep-audit-1018 八支全部通过可验证面，报告+backlog §156 落档（2026-10-17 凌晨，用户令「现在这个应用是包含了所有修改的，开始验证刚刚的那一批修改」） | 身份链：设备包（codePath ~~3MAPc4DHt38…）与 CI #2197 产物 ci-12057403461-app-release.apk sha256 逐字节一致 → 设备代码 == 合并树 492c1b… |
| 10-17 07:31 | backlog.md 覆盖事故（2026-10-17）与修复范式 —— file_write 默认覆盖 | 事故：FIX-D 交付会话写 /var/minis/shared/backlog.md 时漏 append: true（file_write 默认覆盖），135 节负决策台账被覆盖成 13 行。唯一的救命来源 = 101… |
| 10-17 13:19 | FIX-E 收口：deep-audit-1021 ISSUE-06（SkillFileViewerScreen 保存丢返回值 + 主线程写盘，P1）—— fix/da1021-skill-file-save @ `dc3d30a`，CI #2224 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-E.md 并执行（先证再改 / 分支隔离 / 自建工作树 repo-e）。基线 main d0c1c9… |
| 10-17 13:32 | FIX-A 收口：deep-audit-1021 ISSUE-01（技能恢复 id 贯通）+ ISSUE-10（会话覆盖清理闭环）—— fix/da1021-skill-restore-session-cleanup @ 433430f，CI #2229 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-A.md 立刻执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pr… |
| 10-17 15:05 | verify-1021 真机回归收口：4/4 通过 0 回归 + 新发现预览屏裸拼 `#`（backlog §1021-V）—— 2026-10-17 | 输入：用户令「现在这个应用是包含了刚刚一波修复的应用开始测试」。deep-audit-1021 八支并集树 integration/da1021 @ 315c8bd（CI #2232）。 |
| 10-17 17:04 | FIX-H 收口：deep-audit-1022 ISSUE-10 + backlog §1021-V 打包项（fix/da1022-webview-share-url @ 0328f9d，CI #2235 绿，停在分支）—— 2026-10-17 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-H.md 并执行（先证再改、repo-h 自建）。报告 fix-reports/FIX-H.md（8 … |
| 10-17 21:06 | deep-audit-1023 FIX-E 收口：SKILL.md frontmatter 写路径完整性（fix/da1023-skill-frontmatter-parity @ a02a445d，CI #2252 success，停在分支） | 修法三件（+同族新发现）：①极性单一出口 resolveMergeBase(newFrontmatter, diskContent) 内存新值优先（writeSkillMd 原为 existing ?: skill.fr… |
| 10-18 00:33 | 交付：deep-audit-1024 FIX-D（ISSUE-04 MCP OAuth 凭据/沙盒桥接文件生命周期，P1）—— `fix/da1024-mcp-oauth-bridge-lifecycle` @ `b611732a`，CI #39048727885 success，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-D.md 立刻执行（先证再改）。基线 main f688c97（da1023 收口树）。报告 fix-reports/FIX-… |
| 10-18 11:46 | backlog 台账卫生：27 条「门未注册」类 stale 条目标闭（用户令 a11y 修复「攒着等下一批吧」之后顺手做的复核） | 背景：用户对 test_scan.py:2630 a11y 门标记遗留缺陷说「攒着等下一批吧」→ 按待办登记纪律把该条触发条件改绑到活的时点；顺手发现 backlog 里同类的「门未注册 scan.sh」条目全都没有标闭… |
| 10-18 11:52 | ↳ deep-audit-1025 R1 机验半边完成（用户装 2280 包后令「方便完成的验证验了」） | 装包身份链 ✓：设备 versionName=1.0.0+2280 / versionCode 220002280 / lastUpdateTime 2026-10-18 11:47:09 == release andr… |
| 10-18 21:10 | backlog 覆盖事故（同族复发第 2 次）：file_write 未加 append 覆盖 ~209 段台账 + 重建完成 | 事故：20:56 用 file_write（未设 append）写 §1027-D 到 shared/backlog.md，整文件被覆写成 25 行（原 ~209 段）。恢复 = backlog.md.bak-20261… |
| 10-19 19:54 | FIX-E 收口（deep-audit-1032 ISSUE-06 P1）：设置屏首屏 reload 线程 + 技能编辑器 remember 键 —— `fix/da1032-e-settings-io-keys` @ `a2106ab0`，CI #39245665334 绿，停在分支 | 身份链三方逐字符：基线 09b6c12 → 两提交（ad733d56 主体 + a2106ab0 告警清理）→ CI run 39245665334 / job 118036843575 success，local ==… |
| 10-19 20:54 | deep-audit-1032 收口完成（用户令「先更新文档，然后再合并」）：main `c6d426a` + 档案 2012 条 + 7 支待清 | 身份链终态：基线 main 09b6c12（= 报告 2c8c174 + docs，代码零差异）→ 5 支（A c6978d1 / B 6c46f96 / C ad24155 / D af900f2 / E a2106a… |
| 10-20 01:41 | deep-audit-1033 合并完成（用户令「检查如果没有问题的话，更新文档然后合并」）：main `b7acf94a` + main CI #2355 success（Publish 执行）+ 档案 2025 条 + 7 支待清 | 合并顺序（用户指令同 1032：文档先合 → 代码后合）： |
| 10-20 08:29 | FIX-B3 收口（deep-audit-1034 B-05+B-06 两支）：列表块 mathSpans 接线 + 技能面板增量 merge —— `fix/da1034-b3-markdown-sheets` @ `b906313`，CI #2359 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 4 提交（9b87adf markdown · 5032ebc sheet · 38fc024 门 · b906313 测试）→ HEAD b906313ad3a32312e… |
| 10-20 09:06 | FIX-A1 收口（deep-audit-1034 ISSUE-01 P0）：备份 zip 不再带走 MCP OAuth 明文桥 —— `fix/da1034-a1-backup-creds` @ `603d297c`，CI #2365 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 19b1c3e（生产+测试）/ 603d297（门扩展）→ 本地==远端==CI head_sha；CI #2365（run 39349095475）success… |
| 10-20 10:25 | deep-audit-1034 收口完成（用户令「跑完了，没有问题的话就合并，执行后面的步骤吧」）：main `76488f2d` + main CI #2368 success（Publish 执行）+ 档案 2035 条 + 远端只剩 main | 批次终态：基线 main b7acf94a → 8 支（A1–A4 / B1–B4，三方逐字符一致 8/8）→ 并集树 integration/da1034 @ b0ce77591d578616d8d4eed3a0369… |
| 10-20 12:59 | FIX-B5 收口（deep-audit-v14 ISSUE-06 P1）：`fix/dv14-b5-skill-offmain` @ `db90b8f6`，CI #2374 success，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 3 提交（35cf999 生产+出货测试 · e7221d5 owner 门扩展 · db90b8f fix-forward）→ 远端 ls-remote 同 sh… |
| 10-20 15:09 | deep-audit-v14 合并 main 完成（用户令「合并」）：main `71882a8e` + main CI #2388 success + 档案 2046 条 + 分支清理 10/10 | 执行顺序（沿用 da1034 的「文档先合、代码后合」）：三项核查（diff 56 文件逐文件过 / 冲突标记 0 / 冻结面 0 触碰 / 本地 HEAD == 并集 CI head_sha / 独立证据 = 新树本地… |
| 10-20 15:11 | deep-audit-v14 终态补记：main `57c9619a`（档案补录）+ 纯文本例外再证 | 档案补录（docs/dev-history-1035b → main 57c9619a8e17693f826f8050efa78ebfc8c0d3fd，79 天 / 2047 条）push 后实测未触发新 run（最新仍… |
| 10-20 15:57 | §VERIFY-2388 终态：UI 批按用户证词结清 + §1034-A1 备份四面结清（backlog 269 段） | 纯 UI 观感批：用户日常使用未发现问题 → 按用户证词结清（观感类本就是人眼判据，~15 项不再逐项驱动）。 |
| 10-20 21:44 | deep-audit-1036 全批闭环（2026-10-20 晚）：main `7c38a79d` + main CI #2400 success（Publish 执行）+ 档案 2061 条 + 分支清理 9/9 | 合并链（用户令「合并吧」）：三项核查（46 文件逐文件过 / 冻结面 0 触碰 / 本地 HEAD==远端==CI head_sha / 独立证据 = 并集树本地 108 门+329 夹具双绿）→ 代码并集 --no-f… |
| 10-21 00:59 | FIX-2 收口（deep-audit-1037 v16 波，A-03+A-04+A-05）：`fix/dv16-a-debug-modeluse-skill` @ `fe03ecb5`，CI #2411 ALL GREEN，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 4 提交（2b9309ff 生产 / 0d60f3e4 测试 / 79251553 门 / fe03ecb5 CI 编译修复）→ 本地 == ls-remote =… |
| 10-21 08:20 | deep-audit-1037 全批闭环：main `528558f4` + main CI #2415 首轮 success（Publish 执行，v1.0.0+2415）+ 档案 2072 条 / 80 天 | 用户令「更新文档，合并」。链条：main 前身 7c38a79d → 文档 eef3851（纯文本例外：不 dispatch / 不等 CI / 三件套与挂载源 sha256 逐文件 SAME / README 覆盖行 … |
| 10-21 21:34 | deep-audit-1039 合并闭环（用户令「合并吧，合并之前先更新一下文档」）：main `295cf778` + 档案 2103 条 + main CI #2442 success（Publish 执行，v1.0.0+2442） | ① 文档先行：rebuild_dev_history.py → 2103 条（2089→2103）/ 80 天（08-03 ～ 10-21），INDEX 43 处替换、主文件+INDEX+SAGAS 三轮脱敏均 Rema… |
| 10-22 03:03 | FIX-B2 收口（deep-audit-1040 / dv20 波 B-04）：MCP 屏与会话 sheet 的仓/Keystore 调用回主线程 —— `fix/dv20-b2-mcp-offmain` @ `9a97895f`，CI #39645696095 success，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（34855a24 生产 / fcb54989 测试 / 9a97895f 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 19:16 | FIX-B6 收口（deep-audit-1042 / dv22 波 B-07 P1）：`fix/dv22-b6-skills-browser-update` @ `7ff856d6`，CI #39764477753 success，停在分支 | 身份链三方逐字符：基线 main 5dd278da → 3 提交（1d9d5558 生产 / 0bd4d7b4 测试 / 7ff856d6 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-23 15:15 | FIX-1 收口（deep-audit-1045 / v24 波 A-01 P1）：MCP OAuth 配置读侧不认客机 CLI 的 camelCase — `fix/dv25-a1-mcp-oauth-keycase` @ `46b059be`，CI #2535 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec90 → 3 提交（6bb83a20 生产 / 143a88be 测试 / 46b059be 门）；无报告提交（本波 FIX-1..11 与上波 FIX-1..10 重号，入… |
| 10-23 15:42 | FIX-10 收口（deep-audit-1045 / v24 波 B-06 P1）：`fix/dv25-b6-mcps-merge-guard` @ `4b083c74`，CI #2543 success，停在分支 | 身份链：基线 main 1d44ec90 → 4 提交（bd574457 生产 / 0bbda65e 测试 / ea800a0a 门 / 4b083c74 报告）。CI 绿于 ea800a0a，HEAD 4b083c74… |
| 10-24 05:39 | FIX-A3 收口（deep-audit-1047 / A-05+A-06）：`fix/dv27-a3-mcp-secret-handoff` @ `45b14121`，CI #40019485653 success，停在分支 | 缺陷（A-05）：客侧 CLI 写不了加密存储，只能把 MCP 客户端密钥落 <server>.secret 等宿主导入；全仓只有编辑弹窗一处调 importPendingClientSecret ⇒ 另两个读者（rec… |
| 10-24 10:12 | FIX-A5 收口（deep-audit-1048 / A-05 P1）：Stage 9 覆盖 Stage 7 的 MCP 合并结果 — `fix/dv28-a5-mcp-restore-stage9` @ `8122ca84`，CI #40051341292 success，停在分支 | 缺陷：导出照打 mcp/servers.json；Stage 7 用 mcpRepo.importJSON( 合并（本机独有服务器保留、save() 落盘）；Stage 9 解压时 destByPrefix["mcp"]… |
| 10-24 15:24 | backlog 全量核账 + 装置瘦身审计（2026-10-24 下午）—— 两个反直觉结论，都是数据推翻直觉 | 触发：用户问「为什么 bug 好像修不完，一直扫一直有」，然后授权「按你说的做」。 |
| 10-24 18:22 | 2026-10-24 全日志：门有效性审计 + 三个真机验证窗口 + 台账批量结案 + 档案重建 | 当日主线（承接 backlog 腐账审计）： |
| 10-24 20:59 | 元审计收口改成「拖报告即接手」——落地为新技能 meta-audit-witness（2026-10-24 晚，用户纠正） | 用户纠正：上一环的理想形态是「报告出来 → 我把两份报告发给新对话 → 它自然知道按流程走」。不需要路径、不需要交接文件、不需要额外指令。我先前那套「读 HANDOFF.md」的方案是错的形式。 |
| 10-25 06:12 | wide-1024 支 07（mcp-tools 三门）收口：CI 绿，停分支 — 2026-10-25 | 分支 fix/meta-wide-07-mcp-tools @ d1a90931（判据+臂）→ 49552874（报告回填，源码树等价 0 diff）。基线 564c8f3e。CI run 40135996652（#26… |
| 10-25 06:36 | wide-1024 支 10（4 门 12 见证）收口：分支 `fix/meta-wide-10-skill-backup-compact` @ `96ad4ab0`，CI #2643 / run `40137834021` success（head_sha 逐字符一致） | 12/12 见证反攻通过（修复前 rc=0 → 修复后 rc=1，restore=True 全部 sha256 一致）： |
| 10-25 12:40 | META-WIDE3 支 08 收口：skill/backup 四门判据加固，12/12 原见证反攻 CAUGHT，分支 CI #2653 绿（2026-10-25） | 分支 fix/meta-wide3-08-skill-backup（基线 main 6b6c918f）→ 892474eb（门）+ d3f0bfb6（报告）+ 53cd28fb（CI 回填），已推远端，停分支不合并 ma… |
| 10-25 18:06 | wide4 支 12 收口：four_way + skill_import 两门判据加固（分支 `fix/wide4-12-fourway-skillimport`，CI #2671 绿，停分支） | 基线 19e70dab（worktree wt-12，基分支含共享 _kotlin_lexer/_kotlin_ast）。改动面：2 个门脚本（+1080/−166）+ 1 份报告；生产代码 0 行、scan.sh/te… |
| 10-26 01:09 | B5-t11 收口：mcp_oauth / envvar_group 两门 12 条见证效果化（分支 fix/b5-mcpoauth-envvar @ 716d14f15，CI #2693 绿） | 基线 e145ec961（= main 6ac77c6b2 + 共享原语 _kotlin_valueflow）。门提交 557c5b57f（两文件 +739/−461，生产 .kt 0 行，scan.sh/test_sc… |
| 10-26 10:14 | B6-t12 收口：skill_frontmatter / offload_b64 / subagent_handback 三门 18 条见证全翻红（分支 `fix/b6-skillfm-b64-handback` @ `914e8f05d`，CI #2706 绿，停分支） | 基线 base/batch6-shared @ e145ec961。门提交 64c4f46ba（3 文件 +1157/−560，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门… |
| 10-26 11:19 | B7-t02 收口：skill_restore_session_cleanup + speech_active_engine 两门 12 条见证全红（分支 `fix/b7-skillrestore-speech` @ `8b865532e`，CI #2708 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8b865532e（2 文件 +1124/−720，生产 .k… |
| 10-26 19:14 | R3-t08 收口：parts_json_funnel + skill_restore 两门 6 条见证全 CAUGHT（分支 `fix/r3-partsjs-skillrestore` @ `a924214c2`，门提交 `1c3f9c497`，CI #2731 绿，停分支） | 基线 main @ f0f14d46d。改动面 = 2 个门脚本（+693/−13）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰，门 112 / 夹具 344 不变。 |
| 10-26 19:49 | R3-t18 收口：backup_remap_rollup / mcp_oauth_lifecycle / backup_streaming_export 三门 9 条见证全 CAUGHT（分支 `fix/r3-backup-mcp` @ `335faf435`，CI #2741 绿，停分支） | 基线 main f0f14d46d。改动面 = 3 个门脚本（+928/−43），生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112 / 夹具 344 不变。绿 sha 9c17f… |
| 10-26 20:13 | R3-t19 收口：skill_file_save_result + skill_import_offmain 两门补 6 条 round-3 缝级判据（分支 `fix/r3-skillfilesave` @ `009916a26`，门提交 `f686031b5`，CI #2747 绿，停分支未合并） | 基线 main f0f14d46d。改动面 = 2 个门脚本（+808/−65），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 / 夹具 344 不变。 |
| 10-27 00:36 | AgentLoopEngine 行为基线 harness 建成（方案 A 落地）—— 2026-10-27 | 分支 test/agentloop-behavior-baseline @ 74b1ba65（2 文件 +753 行，纯测试、生产 .kt 0 行）。CI #2758 / run 40356871948 success，… |

## 16. 会话 / 导航 / 抽屉交互

**跨度** 2026-08-03 ～ 2026-10-27 · **169 条** · **状态** 已闭环（09-01 后未再复现）

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
| 09-23 17:20 | P1 三条批量处置：两条已被修、一条方向与 backlog 原判相反（09-23） | 用户指令：「p1，那就把他们都修了吧」（P1 = §27a 图片门 / §27c(2) canResume 检查 / §29 换源重试）。 |
| 09-24 08:48 | 启动会话瘦身进 main（09-24 分派任务，分支 CI 绿停在分支） | 分支：fix/launch-session-slim @ 75dee757（基点 main 051a6b35，单 commit，9 文件 +6/−12）→ CI run 35939014960 (#1769) succe… |
| 09-24 10:37 | 存储页进出卡顿修复分支 CI 绿（09-24，分支 CI 绿停） | 任务：用户报告「设置→存储→存储」进出历史对话卡顿、会话越大越明显。诊断（/tmp/rk-audit-0924x @5fb8a244）：StorageManagementScreen.reload() 挂 Launche… |
| 09-24 13:18 | 会话列表 LazyColumn 分支 CI 绿（09-24，停止节点） | 任务：用户反馈 rescan-gate 修复后「使用体验没有明显改善」→ 转会话列表 LazyColumn。main 已推进（350a9993 = 三个 merge 之上，前一修复 d57643f4 已进 main）。 |
| 09-24 13:21 | ↳ lazy-session-list 核验 + 合并 main = 665877a5（09-24） | 用户指令：「检查核实分支上的修改，没有问题就合并」= 核实 + 合并放行。 |
| 09-24 14:44 | 思考强度冷启动变关调查（09-24，等用户分辨观察） | 用户报告：完全关闭 app 重进，原本调的思考强度变成关。实读 main @ 665877a 全链路： |
| 09-25 12:11 | 用户指令「打包一起做了吧」→ 三个优化方向同日三个分支（09-25，基 main 15b06f3）： | 用户指令「打包一起做了吧」→ 三个优化方向同日三个分支（09-25，基 main 15b06f3）： |
| 09-25 12:34 | ↳ 用户指令「检查环节，云端 3 个分支，有问题修，没问题合并」→ 09-25 三个观测分支检查+合并放行，全流程闭环： | 用户指令「检查环节，云端 3 个分支，有问题修，没问题合并」→ 09-25 三个观测分支检查+合并放行，全流程闭环： |
| 09-25 15:12 | §38-B 收官 + 09-25 真机验证（用户确认 app 已装最新包 1.0.0+1800） | §38-B 收官 + 09-25 真机验证（用户确认 app 已装最新包 1.0.0+1800） |
| 09-25 19:09 | 对话历史抽屉行精简分支 CI 绿（09-25，停止节点） | 对话历史抽屉行精简分支 CI 绿（09-25，停止节点） |
| 09-25 20:41 | 09-25 晚三条 UI 线全部到停止节点（分支 CI 绿，均未合并）：①feat/typing-status-band | 09-25 晚三条 UI 线全部到停止节点（分支 CI 绿，均未合并）：①feat/typing-status-band @ a54c2f78——状态带：TypingIndicator 包进固定 36dp Box（TYP… |
| 09-25 20:58 | 三分支检查 + drawer-context-menu 合并 + typing-band 返工（09-25） | 三分支检查 + drawer-context-menu 合并 + typing-band 返工（09-25） |
| 09-25 21:19 | typing-band-live 合并收口（09-25，用户放行）：8 项固定清单全跑后 --no-ff merge f | typing-band-live 合并收口（09-25，用户放行）：8 项固定清单全跑后 --no-ff merge fix/typing-band-live @ 092ebbdd → main = 45f0f39b（3… |
| 09-25 21:28 | 分支清理 + 「检查=上全套」规则落地（09-25，用户拍板）：①feat/typing-status-band @ a | 分支清理 + 「检查=上全套」规则落地（09-25，用户拍板）：①feat/typing-status-band @ a54c2f78 API DELETE 204（已被 fix/typing-band-live 取代且… |
| 09-25 21:46 | typing-band-overlay 回归修复（09-25 深夜，停止节点）：用户报今天合并引入的回归——agent | typing-band-overlay 回归修复（09-25 深夜，停止节点）：用户报今天合并引入的回归——agent 执行期间底部 band 持续显示「正在思考/等待响应中」直到回合结束。根因（证据链闭合）：fix/t… |
| 09-25 21:47 | typing-band-overlay 核查 + 合并 main = 50b399c9（09-25，用户放行） | typing-band-overlay 核查 + 合并 main = 50b399c9（09-25，用户放行） |
| 09-25 22:04 | 09-25 全量回归审计：今天 24 提交 / 33 文件，无功能级 bug，2 条次生开销 P2 + 5 条边界登记 | 范围：基线 2022b0c5（昨 21:12）→ main 50b399c9，git log 实测今天进 main 的是 24 个提交 / 33 文件 +1511/−267（不止记忆里的 8 条 merge——上午还有 … |
| 09-26 13:01 | 真机日志取证能力（重大发现，2026-09-26） | app 的日志在沙箱里直接可读：/var/minis/logs/minis-YYYY-MM-DD.log（主进程）、minis-YYYY-MM-DD.modelservice.log（worker，34MB）、launc… |
| 09-26 13:25 | 内容丢失 + 抽屉行跳 修复到停止节点（09-26，用户拍板修，未合并） | 分支 fix/lost-turn-content-and-row-jump @ 78d766429cae5b232d5e3cea94f985848b4c0aa7（1 commit，5 文件 +93/−14，基点 main… |
| 09-26 14:23 | ↳ ★ /tmp 收割判据修复（09-26，用户拍板「按你的建议改」，已合并 main = c06cf402） | 你的怀疑被证实是机制缺陷：cleanupProotTmp → sweepGuestTmpDir 只看条目自己的 mtime（>1h 删），而目录 mtime 只在直接子项增删时变；git commit/checkout、… |
| 09-26 14:40 | ↳ 装包验证闭环（09-26，用户确认 1/2/4/5 已验证）：main = c06cf402 四项真机验证通过——①工具 | 装包验证闭环（09-26，用户确认 1/2/4/5 已验证）：main = c06cf402 四项真机验证通过——①工具执行期强杀 app 重进，已显示的助手文字仍在、不再误显示 PAUSED；②多 key 编辑入口消失… |
| 09-26 15:32 | 抽屉第二行长时间空白根因 + 修复到停止节点（09-26，用户拍板修法一，未合并） | 用户观察：抽屉会话行第二行（预览）会长时间空着——尤其慢模型/长推理时。我先给了错误初判（"运行中短暂，主要是中断尾"），用户纠正：运行中的会话本身就会长空白。 |
| 09-27 14:22 | 键盘意外弹出修复（09-27，分支 CI 绿 = 停止节点，未合并） | 根因（代码级 + AOSP 源码双证据）：ModalBottomSheet/Dialog 是独立 focusable window——打开时主 window 失焦、关闭时重获焦点，焦点回到 composer 的 Text… |
| 09-28 01:45 | 历史抽屉长按菜单「偶尔多出重命名」结案（09-28，用户报告，调查未改码） | 真相：第三个选项是「重新生成标题」（sessionlist_regenerate_title，刷新图标），用户误读为「重命名」——菜单里根本没有 rename 功能（8 语言 strings.xml 仅 pin/unpi… |
| 09-30 14:47 | A4「中断尾巴 Case D + 跨 VM 活跃门」完成（09-30，分支 CI 绿，未合并） | 分支：fix/interrupted-tail-case-d @ 47b55750d09bfe1e0668f066d2ec12cf7ad3bd84（2 commits：c7ed723 修复 + 47b5575 收敛），4… |
| 09-30 18:00 | 真机验证完成（09-30 晚，设备包 1.0.0+1889 = 分支 run #1889 构建，代码==合并树） | 包来源链（用户问「现在这个包是最新的那个」）：versionName 1.0.0+1889 ↔ CI run #1889（36694097030，分支 tip 70a93bc3）↔ 合并树 26b7ce1（合并树 vs … |
| 10-06 00:42 | 压缩模型钉住功能施工完成（feat/compact-model-pin-1005 @ bba87f23，CI 绿，停在分 | 压缩模型钉住功能施工完成（feat/compact-model-pin-1005 @ bba87f23，CI 绿，停在分支待用户） |
| 10-06 01:28 | 压缩模型 pin 分支（feat/compact-model-pin-1005）审查+修复+合并 main 完成（10-06 凌晨） | 审查发现 2 缺陷（均修，commit f03b9a88）：①resolveCompactionPinProvider 缺 GroupRouter.isUsable 健康门——冷却/熔断/死钥的 pin 走全局解析仍骑链… |
| 10-06 20:20 | 压缩模型 pin（sensenova-6.8-flash-lite）日志核查：今日 0 次压缩，pin 未被检验 | 结论：10-06 全天日志零压缩事件（主日志 [Compact] 遥测 0 条，对照 10-05 有 28 条 outTok 遥测；modelservice 日志 sensenova 0 命中；无会话越过压缩阈值）→ p… |
| 10-09 00:09 | D5 域扫描完成（sweep-1008） | 任务：/var/minis/shared/sweep-1008/tasks/D5.md（data+provider+conversation+service+config），报告已写 sweep-1008/reports… |
| 10-09 00:40 | sweep-B 修复完成（thinking 规则缓存冷启动预热，sweep-1008 唯一 HIGH） | 任务：/var/minis/shared/sweep-1008/fix-tasks/B.md。分支 fix/sweep-p1-thinking-cache-1008 @ 4f6646f8b6ac94e0081ee7279… |
| 10-12 20:02 | 「回答中途突然停止」排查结论（2026-10-12 晚，用户报其他会话） | 今天日志共 11–12 次真实中途取消（runAgentLoop CANCELLED → streamJob killed），全部是 cancelStream() 主动取消，0 崩溃/0 错误/0 OOM（今天 erro… |
| 10-12 23:07 | NextPlayer 定制：底部导航移除 + 个人签名出包基建（2026-10-12） | 用户需求：fork alarmedvine/nextplayer（= 上游 anilbeesetti/nextplayer 干净 fork，main 与上游一致）。要求：①底部「可切换的胶囊导航」整体搬到设置页、入口页只… |
| 10-16 10:31 | deep-audit-1016 FIX-C 交付收口：沙箱路径归一化 / 只读写守卫 / boot 门（`fix/da1016-sandbox-path` @ `cce8919`，CI #2163 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-C.md 立刻执行（基线 main 04e74c5）。报告 shared/work/deep-audit-1016/fix-r… |
| 10-16 16:38 | deep-audit-1016c 启动：第二道门核实 + 8 支派发（用户令「按流程吧」，附新审计报告 12 项） | 输入：附件 shared-8c9ee41c.md（170KB / 2645 行，ISSUE-01..12）。基线 main e3ef74545bcc（= 1017 收口后最新树，报告声明 35 门/176 夹具与之吻合）… |
| 10-16 22:22 | FIX-C 收口：deep-audit-1018 ISSUE-07（CompactPin 同消息双钉 + 紧凑域裸真值陷阱，P1）—— `fix/da1018-compact-pin` @ `9b34241`，CI #38912121098 绿，停在分支 | 任务 shared/work/deep-audit-1018/fix-tasks/FIX-C.md（+ COMMON.md/TRIAGE.md 实读自上级目录）；报告 fix-reports/FIX-C.md；装置 re… |
| 10-17 10:33 | FIX-F 收口：deep-audit-1020 ISSUE-06（深链缺 6 类设置分支 + 5 条 Routes 未 encode）—— fix/da1020-deeplink-routes @ 049a182，CI #2215 绿，停在分支 | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-F.md；报告 fix-reports/FIX-F.md；装置 repo-f/probe/（durable）。基线 main 9… |
| 10-17 10:44 | FIX-E 收口：da1020 ISSUE-05 语音链路四块（fix/da1020-voice-cancel-ua @ 97102a49，CI #2218 绿，停在分支） | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md；装置 repo-e/repo/p… |
| 10-17 13:12 | FIX-C 施工进行中：da1021（镜像详情页接线 + seg 全覆盖 + 冷启动兜底）— 2026-10-17 | 任务 shared/work/deep-audit-1021/fix-tasks/FIX-C.md。分支 fix/da1021-mirror-deeplink @ ab7f84941a3b…（基线 main d0c1c9… |
| 10-17 13:19 | FIX-E 收口：deep-audit-1021 ISSUE-06（SkillFileViewerScreen 保存丢返回值 + 主线程写盘，P1）—— fix/da1021-skill-file-save @ `dc3d30a`，CI #2224 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-E.md 并执行（先证再改 / 分支隔离 / 自建工作树 repo-e）。基线 main d0c1c9… |
| 10-17 13:20 | FIX-F 收口：deep-audit-1021（WebApp asset URL 单源 + AddToHome 清理闭环）—— fix/da1021-webapp-asset-url-cleanup @ ab9c247，CI #2226 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-F.md（8 支并发派发，我领 FIX-F）。报告 fix-reports/FIX-F.md；装置 r… |
| 10-17 13:30 | FIX-C 收口完成：deep-audit-1021（da1021-C 镜像详情页接线 + seg 全覆盖 + 冷启动兜底）— 2026-10-17 | 分支 CI 绿 = 停止节点达成，未合并 main。 |
| 10-17 16:19 | deep-audit-1022 启动：第二道门 + 8 支派发（用户令「走流程」，附新审计报告 10 工单）— 2026-10-17 | 输入：附件 rikkaminis_full_audit_report.md（1529 行 / ISSUE-01..10）。基线 main ba4daf699fb4bcc89eb33cdeb788272283910b91（… |
| 10-17 19:43 | deep-audit-1023 启动：第二道门核实 + 9 支派发（用户令「走流程吧」，附报告 v2 10 工单全 P1） | 输入：附件 rikkaminis_full_audit_report_v2.md（4230 行 / 10 卡 / 全部自称 P1）。基线 main cd9e97b，门禁基线 85 passed / 0 failed（实跑… |
| 10-17 20:47 | deep-audit-1023 FIX-F 收口（ISSUE-06 会话 id 绑定，fix/da1023-session-id-binding @ ab7e632，CI #2247 绿，停在分支） | 修法：ChatViewModelStore.resolveSessionId 公开委托（单源 delegate private resolveKey，katex 门钉形状保留）+ ChatScreen 4 组站点（图片 … |
| 10-18 00:39 | FIX-E 收口：deep-audit-1024 ISSUE-05（MODEL_ENTRY_DETAIL 路由二次 decode，P2）—— fix/da1024-model-entry-route-no-double-decode @ e0ad364，CI #39048800163 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md（193 行 8 节齐全）；装置 re… |
| 10-18 00:49 | FIX-C 交付收口：deep-audit-1024 ISSUE-03（P1 二级引用漏清理 + ModelGroupsScreen 无监听）—— `fix/da1024-compact-pin-cascade-and-listener` @ `f7f1419beb32`，CI #39049819858 绿，停在分支 | 任务 shared/work/deep-audit-1024/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md；装置 repo-c/probe/（durable：jvm/{… |
| 10-18 13:06 | deep-audit-1026 启动：第二道门核实完成 + 7 支派发（用户令「按流程来」，附新审计报告 8 项） | 输入：shared-d2163f57.md（132KB，8 ISSUE）→ shared/work/deep-audit-1026/REPORT-1026.md。基线 main dc04fc3（= 1025 收口树）。门… |
| 10-18 19:47 | deep-audit-1027 启动：第二道门核实 + 7 支派发（用户令「走流程」，附新审计报告 8 项） | 【本条为 12:56 UTC 日报截断事故后由主会话重建：原文不可逐字节取回（app 日志仅存前 309 字符、语义索引最后 build=10-10），以下按本会话记录与 TRIAGE/DISPATCH 台账重写；事实以… |
| 10-18 21:02 | FIX-C 收口：deep-audit-1027 ISSUE-04（Xunfei 空转写被当成功结果返回）—— `fix/da1027-c-xunfei-transcript` @ `d01da0d`，CI #39128561098 绿，停在分支 | 任务：读 shared/work/deep-audit-1027/fix-tasks/FIX-C.md + COMMON.md（注意：COMMON.md 在 上级目录，不在 fix-tasks/）。报告 fix-repo… |
| 10-18 23:47 | deep-audit-1028 启动：第二道门核实 + 判级 + 8→3 合支派发（用户令「走流程吧，小的合并少开会话」） | 输入：REPORT-1028（8 工单/12 裂口，基线声明 889d645 = main 一致）。工作区 shared/work/deep-audit-1028/（repo 为 GitHub 新浅克隆——1027 本地… |
| 10-19 00:56 | FIX-C 收口：deep-audit-1028 ISSUE-02 + ISSUE-05 + ISSUE-06（UI 状态失步 / filesDir 清理接线 / 深链响应式）—— `fix/da1028-c-ui-state-wiring` @ `aaa4cc1`，CI #39145801329 绿，停在分支 | 身份链：基线 main 889d645（tree f12ca356）→ 单提交 aaa4cc1b6267a40e7683c2e4795a575d7e2e93ba（父 = 基线）→ 远端 tip == 本地 HEAD ==… |
| 10-19 08:10 | FIX-A (deep-audit-1029) 收口：I01 草稿单源 + I02 深链穷举，分支 CI 绿 @ e76330e，停在分支 | 身份链：基线 83caac6 → 2 提交（5ca7e1a 修法 + e76330e CI 编译红补丁）→ HEAD e76330e999e7daa8e9aa62a552b3117492351657 == 远端 tip … |
| 10-19 08:40 | deep-audit-1029 全流程闭环（用户令「走流程吧，小任务合并」）：9 工单 → 6 支 → 并集树 CI 绿 @ 933a0eb8 | 身份链：基线 main 83caac6（1028 收口树）→ 6 支 → 并集树 integration/da1029 @ 933a0eb8c2fcc7031af63d68dcc054987e6748ad（tree c2… |
| 10-19 09:34 | 审计背景包升级 v11/v8 → **v12/v9**（用户令「为下一波审计做准备」+ 背景文件）：基线 `b353317`，两处引用失真被回树取证抓住当场修正 | 位置：挂载 /var/minis/mounts/笔记/RikkaMinis审计背景包/（发给外部审计模型，非本机会话）。终态根目录 = README.md + audit-context.md(v12, 521 行 / … |
| 10-19 10:04 | v1.0.0+2319 真机验证（deep-audit-1029 批次，无 a11y 口径）—— 2026-10-19 | 装机身份 byte 级锁定：versionCode 220002319 / versionName 1.0.0+2319 / lastUpdate 09:41:20；设备侧 sha256sum base.apk == G… |
| 10-19 15:06 | deep-audit-1031 启动：第二道门（8/8 成立）+ 8→4 支合并派发（用户令「走流程」+「不要忘了把小的任务合并」） | 输入：附件 shared-8c2edc89.md（1551 行 / 107,816 B，ISSUE-01..08）。基线 main 2bc07ec（= 1030 收口树，本地工作副本自核一致）。工作区 shared/wo… |
| 10-19 19:47 | FIX-D 收口（deep-audit-1032 ISSUE-05 + 07）：`fix/da1032-d-terminal-mainid` @ `af900f2`，CI #2341 绿，停在分支 | 身份链三方逐字符：基线 09b6c12 → 两提交（fa7e45b 主体 + af900f2 所有权守卫回填）→ LOCAL == REMOTE == CI head_sha = af900f26382dd058288a… |
| 10-19 20:35 | deep-audit-1032 启动+收口前夜：第二道门（8/8 成立）+ 8→5 支合并派发 + 五支全绿 + 并集树 CI 绿 | 输入：附件 shared-ee3fef8b.md（1306 行 / 84,341 B，ISSUE-01..08）。基线 main 09b6c12（= 报声明 2c8c174 的直接后代，diff 仅 docs/dev-h… |
| 10-19 20:54 | deep-audit-1032 收口完成（用户令「先更新文档，然后再合并」）：main `c6d426a` + 档案 2012 条 + 7 支待清 | 身份链终态：基线 main 09b6c12（= 报告 2c8c174 + docs，代码零差异）→ 5 支（A c6978d1 / B 6c46f96 / C ad24155 / D af900f2 / E a2106a… |
| 10-19 22:43 | deep-audit-1033 启动：第二道门（8/8 成立，0 驳回）+ 8→5 支合并派发 | 输入：附件 shared-19af2d68.md（518 行 / 69,668 B，ISSUE-01..08）。基线 main c6d426a——主会话 git rev-parse HEAD 自核与报告声明逐字符一致（免… |
| 10-20 00:58 | FIX-A 收口（deep-audit-1033 ISSUE-01 + 05 + 08）：`fix/da1033-a-ro-guard-mainio` @ `7b1b1722`（两提交），CI #2353 绿，停在分支 | 身份链：基线 c6d426a7 → a99827d5（主体）→ 7b1b1722（修 CI 编译事故） → CI run 39284407398（#2353）success；三方逐字符一致。9 文件 +602/−82（7… |
| 10-20 07:20 | deep-audit-1034 启动：首次 A/B 双报告第二道门（16/16 成立 · 判级 0 修正）+ 8 支派发 —— 2026-10-20 | 输入：首次「一分为二」按管辖面切的两份报告：REPORT-A.md（内核·进程·数据面，8 工单，P0×1/P1×6/P2×1）+ REPORT-B.md（UI·交互·呈现面，8 工单，P1×8）。基线 main b7a… |
| 10-20 15:47 | §VERIFY-2388 真机验证收口（CLI/深链面全绿 + S34 新缺陷入册 + UI 视觉面交用户） | 身份：app 1.0.0+2388（15:11 装 = main CI #2388，含 dv14 九支并集 71882a8e）。工作区 shared/work/verify-2388/，回执已入 backlog（267→… |
| 10-20 19:26 | FIX-B2 收口（deep-audit-1036 B-02 + B-08）：`fix/dv15-b2-settings-screens` @ `c9a9662`，CI #2393 success，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 2 提交（411d551 生产 5 文件 +338/−155 · c9a9662 测试 4 文件 +576）→ 本地 == 远端 == CI head_sha = … |
| 10-20 23:35 | deep-audit-1037（v16 波）启动：第二道门 16/16 成立 + 17 项（含 §1036-FLAKE 兑现）→ 6 支派发完成 | 输入：attachments/uploads/Audit_Report_V16_A.md（82,870 B）+ Rikkaminis_Audit_Report_V16_B.md（86,912 B）。基线 main 7c3… |
| 10-21 09:24 | deep-audit-1038（v17 波 / 面 B）启动：第二道门 8/8 成立（P1×6 · P2×2）+ 8 工单合并为 5 支派发 | 输入：附件 audit_report_v17_b.md（92,529 B / 540 行，面 B：UI·交互·呈现，B-01..B-08）。面 A 报告未到（用户「先把这一份处理了吧」）。基线 main 528558f4… |
| 10-21 10:01 | FIX-2 收口（deep-audit-1038 dv17 波 B-02）：冷启动播种无 remember —— `fix/dv17-2-appnav-coldstart` @ `a962fe16`，CI #2416 绿，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（a903c00 生产 / 61cb427 测试 / a962fe1 门）→ 本地 == git ls-remote == CI head_sha = a9… |
| 10-21 23:47 | deep-audit-1040（v19 波）启动：第二道门 16/16 成立 0 驳回 + 判级修正 1 + 报告失真 4 → 8 支派发完成（回读 8/8 送达） | 输入：Rikkaminis_Audit_Report_V19_A.md（103,018 B，A-01..A-08）+ rikkaminis_audit_report_v19_b.md（76,198 B，B-01..B-0… |
| 10-22 02:44 | FIX-A4 支（deep-audit-1040 / dv20 波 A4）：代码+门+三层证据全绿，**推送被环境级出网中断挡住** | 分支 fix/dv20-a4-mount-wiring（私有 worktree shared/work/deep-audit-1040/a4），基线 main 295cf778。两提交：生产 03d941eb（5 文件）… |
| 10-22 13:05 | FIX-A2 收口（deep-audit-1041 / dv21 波 A-02 P1）：`fix/dv21-a2-empty-sweep` @ `13c8fccc`，CI #(run 39724086190) 16/16 ALL GREEN，停在分支 | 身份链三方逐字符：基线 main 843ab917 → 4 提交（8b140806 生产 +55/−2 · dced789d 单测 · de536693 门 +216/−14 · 13c8fccc 单测补 1 条 hoo… |
| 10-22 15:06 | 审计背景包 v24 构建完成（为 deep-audit-1042 / v21 波准备） | 触发：用户「这一批已经吸收了，为下一批做准备」。基线：843ab917 → 5dd278da（deep-audit-1041 收口，14:48；含 dv21 十支并集 + fix/dv21-rssprobe-test-r… |
| 10-22 17:04 | deep-audit-1042（v21 波）启动：A 7 + B 7 = 14 工单 → 13 支派发完成（回读 13/13 送达） | 输入：Rikkaminis_Audit_Report_V21_A.md（90,097 B，A-01..A-07）+ Rikkaminis_Audit_Report_V21_B.md（96,879 B，B-01..B-07… |
| 10-23 11:17 | FIX-9 收口（deep-audit-1044 / B-06 **本波唯一 P0**）：`fix/dv24-b3-promote-draft-persist` @ `ed90a7f3`，CI run 39892254078 / job 120214646640 success，停在分支 | 身份链：基线 main f94413f5 → 3 提交（3df76c6f 生产 / 3dc0cf8a 测试 / ed90a7f3 门）→ 本地 == ls-remote == CI head_sha = ed90a7f3… |
| 10-23 14:20 | deep-audit-1045（v24 波）启动：第二道门 15/15 成立 0 驳回 + P0×1 + 失真 2 → 11 支派发完成（回读 11/11 在跑） | 输入：Rikkaminis_Audit_Report_V24_A.md（78,946 B，A-01..A-07）+ V24_B.md（102,201 B，B-01..B-08）。基线 main 1d44ec90（ls-r… |
| 10-23 15:47 | FIX-5 收口（deep-audit-1045 / v24 波 B-01 P1）：`fix/dv25-b1-mirror-autodetect-ownership` @ `665e9b21`，CI #2540 success（head_sha `6e929cd3` 三方一致），停在分支 | 缺陷：autoDetectOnceIfNeeded（首启 rootfs.freshInstall 自动测速）是裸 scope.launch（基线 :478），Job 从未进字段 ⇒ runTest/runAllTests… |
| 10-23 18:48 | deep-audit-1046 启动（v24-S 单路报告，8 支派发完成） | 输入：Rikkaminis_Audit_Report_V24_S.md（127,225 B / 849 行，S-01..S-11）。报告自报基线 main 1d44ec90。 |
| 10-23 23:45 | deep-audit-1047（v25-A/B 波）启动：第二道门 15/15 成立，判级修正 3 处，9 支派发完成（回读 9/9 送达） | 基线 main 88ef9b27（报告写 42af0ae6 = 父提交，src/+scripts/ 逐字节一致）。工作区 shared/work/deep-audit-1047/（REPORT-A/B、TRIAGE、CO… |
| 10-24 08:48 | deep-audit-1048（v26-A/B 波）启动：第二道门 16/16 成立、A-04 下调 P2、9 支派发完成（回读 9/9 送达） | 输入：Rikkaminis_Audit_Report_V26_A.md（8 条，A-01..A-08）+ _B.md（8 条，B-01..B-08）。基线 eea9f407（报告声明值与 git ls-remote 实测… |
| 10-24 10:12 | FIX-A5 收口（deep-audit-1048 / A-05 P1）：Stage 9 覆盖 Stage 7 的 MCP 合并结果 — `fix/dv28-a5-mcp-restore-stage9` @ `8122ca84`，CI #40051341292 success，停在分支 | 缺陷：导出照打 mcp/servers.json；Stage 7 用 mcpRepo.importJSON( 合并（本机独有服务器保留、save() 落盘）；Stage 9 解压时 destByPrefix["mcp"]… |
| 10-24 10:40 | FIX-B1 收口（deep-audit-1048 / UI 交接五条 B-01·B-03·B-04·B-05·B-06）：`fix/dv28-b1-ui-handoff` @ `f182c766`，CI #2594 success，停在分支 | 身份链：基线 eea9f407 → 5 提交（99322a05 生产 10 文件 / 89c63058 四门扩展 / 0e7c0228 多删大括号 / 3c200099 ArchiveEntry 提升 / f182c76… |
| 10-24 13:40 | deep-audit-1049（dv29 波：v27-A/B + 真机 VERIFY P1）启动 —— 2026-10-24 13:4x | 输入：用户上传 Rikkaminis_Audit_Report_V27_A.md（80,620 B，A-01..A-07，内核/进程/数据面）+ V27_B.md（89,422 B，B-01..B-06，UI/交互面），… |
| 10-24 18:22 | 2026-10-24 全日志：门有效性审计 + 三个真机验证窗口 + 台账批量结案 + 档案重建 | 当日主线（承接 backlog 腐账审计）： |
| 10-25 05:32 | META-AUDIT wide-1024 第二波启动：50 门宽扫 157 条门瞎实锤，12 支派发（2026-10-25 凌晨） | 判决（机械复证 209 条尝试）：157 GATE_BLIND_CONFIRMED / 48 CAUGHT_VERIFIED / 3 GATE_CAUGHT_WITNESS（自称 FALSIFIED 却被门抓住 = 见证… |
| 10-25 18:06 | wide4 支 12 收口：four_way + skill_import 两门判据加固（分支 `fix/wide4-12-fourway-skillimport`，CI #2671 绿，停分支） | 基线 19e70dab（worktree wt-12，基分支含共享 _kotlin_lexer/_kotlin_ast）。改动面：2 个门脚本（+1080/−166）+ 1 份报告；生产代码 0 行、scan.sh/te… |
| 10-25 21:43 | META-B5 第五批门见证收口启动：144 条见证机械复证 132 实锤失明，共享层先行 + 13 支派发（2026-10-25 21:4x） | 输入：A/B 两份外部报告（221KB + 291KB，24 门 × 6 尝试 = 144 条见证，v7 格式无 === GATE: 块，自写解析器 parse_report.py 双格式支持）。声明基线 1319cce… |
| 10-26 23:25 | T2 debt-sweep 收口：清三笔登记债务（#2 file:// 路径 / #3 图片附件有界读 / #5 weather 单出口），分支 `fix/debt-sweep-1026` @ `215c1d951`，CI #2755 绿，停分支（2026-10-26 夜） | 基线 main 627f23f2e。改动面 4 生产 .kt + 3 门脚本豁免表：+170/−194（7 文件，全部在任务书白名单内）。scan.sh/test_scan.py/_kotlin_ 零触碰。 |
| 10-27 00:30 | U4 收口：`file://` 契约「跨界编码 / 内部解码」（分支 `fix/fileurl-contract` @ `8ddb12e57`，绿 sha `621602d43`，CI #2757 绿，停分支） | 基点 T2 分支 8d00395af（含 T2 全部提交）。改动 8 文件 +450/−40：生产行为 4 文件（未越任务书白名单，全逐条登记）+ 3 测试 + 1 门脚本。 |

## 17. 平台适配 / 通知 / 图标 / 权限

**跨度** 2026-08-03 ～ 2026-10-26 · **103 条** · **状态** 部分放弃（灵动岛需申请小米白名单；其余已闭环）

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
| 09-26 09:13 | Filterrr/RikkaMinis 仓库调查（09-26，用户问「他改了什么」）：①不是本仓库 fork——API | Filterrr/RikkaMinis 仓库调查（09-26，用户问「他改了什么」）：①不是本仓库 fork——API fork=False、compare 404、fork 网络脱离；与我们的共同祖先只有 OpenMi… |
| 09-29 11:30 | 常驻通知精简 + 通知模块审计（09-29，分支 refactor/simplify-fgs-notification，未合并） | 停止节点：分支 CI 绿，未合并（合并权在用户）。 |
| 09-29 11:53 | FGS 通知展开箭头 = 系统强制，去不掉（09-29 实证，更正上次错误预测） | 用户报：常驻通知右侧的向下箭头（圆形 chevron）多余，要求去掉。 |
| 09-29 15:55 | fork 构建失败修复：签名 + workflow 权限三连环（09-29 晚，Pre-Release 全绿发 APK） | 用户报「构建失败了」→ 三个 fork 存量配置问题（与 merge 的代码无关，全部编译绿）：①fork 不继承上游 Actions secrets，SIGNING_ 三个 secret 为空 → 空密码签名失败（ke… |
| 09-30 13:59 | 上游 A 档移植已派发（09-30，3 会话并行） | 用户拍板：无障碍已开启（真机复核 running:true）；工程量大 → 分派出去。 |
| 09-30 16:13 | ★ 封存完成：上游 A 档移植 4 分支合并 main（09-30，main = 0924445f） | 用户指令链：「合并前核查 → 按你的建议来吧，合并 → 触发了就不用等了」。 |
| 09-30 16:29 | 上游 A 档移植三分支合并后审计（09-30 晚） | 今天净账：main 0c78f00 → 0924445f = 27 文件 +2569/−88（3 功能分支 + chore fold 归一 strings 约定，fold 由用户推）。CI #1886/#1887/#18… |
| 09-30 17:23 | 两条审计发现修复完成（09-30，分支 fix/a11y-evergranted-gate-fgs-guidance @ 70a93bc3，CI 绿 #1889 未合并） | 修复（3 文件 +437/−12，基线 origin/main 0924445f）： |
| 09-30 17:47 | 分支合并收口：fix/a11y-evergranted-gate-fgs-guidance → main 26b7ce1（09-30，全套 8 项核查通过） | 用户指令链：「检查一下云端上的那个分支，如果没有问题的话，就合并了吧」+「触发了就不用等了」= 核查 + 条件合并授权。 |
| 10-05 19:53 | 代码膨胀实测（10-05，基线=包名改名点 e24ca02b 09-10） | 测量坑两个：①浅仓 graft 假象（fastio/quiet-first 等 merge 节点被当根，numstat 把整树算新增——jieba +69万行的假数据），已 git fetch --unshallow 补… |
| 10-14 15:38 | FIX-R6 完成：MCP 温热握手 + server_name 白名单 + k=v JSON 字面量（fix/swee | FIX-R6 完成：MCP 温热握手 + server_name 白名单 + k=v JSON 字面量（fix/sweep1014-mcp-http @ 8b4bfe1，CI #2018 绿） |
| 10-16 09:43 | FIX-G 收口：deep-audit-1016 8c/8d（通知行去 AI 头像 + compact 分割线谓词读全部块）—— 分支 CI #2155 绿，停在分支（2026-10-16） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-G.md 立刻执行。基线 main 04e74c53325a；分支 fix/da1016-chat-sysinfo @ ee3… |
| 10-16 13:13 | FIX-E 收口：deep-audit-1017 ISSUE-08 ShizukuOffloadHandler 三个 argv 缺陷（fix/da1017-shizuku-args @ 2a5cddd1，CI #2166 绿，停在分支） | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-E.md 立刻执行（基线 main e748b14）。报告 fix-reports/FIX-E.md；装置 fix-e/pro… |
| 10-16 13:31 | FIX-G 收口：deep-audit-1017 ISSUE-10（a11y 截图/点击 + speech，P2 4 子项）—— fix/da1017-a11y-speech @ a304097，CI #2171 绿，停在分支 | 任务书 shared/work/deep-audit-1017/fix-tasks/FIX-G.md（基线 main e748b14）；报告 fix-reports/FIX-G.md；装置 fix-g/probe/（pr… |
| 10-17 09:21 | VERIFY-1019 真机验证（deep-audit-1019 十支）— 报告 `shared/work/verify-1019/VERIFY-DEVICE-1019.md` | 身份链闭合：设备 com.rikkaminis.app 1.0.0+2210（08:47 装）base.apk sha256 4605b9a3…（14,368,209 B）== CI #2210（id 389854333… |
| 10-17 10:36 | da1020 FIX-B 收口：Shizuku argv 20 开关声明 + group 按位置取（fix/da1020-shizuku-args @ 9990218，CI #2214 绿，停在分支） | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-B.md；报告 fix-reports/FIX-B.md；装置 repo-b/probe/（durable）。基线 main 9… |
| 10-18 10:09 | FIX-C 交付收口：deep-audit-1025 ISSUE-03（a11y 双表失步，P1）—— `fix/da1025-a11y-status-verbs` @ `8b73bee`，CI #2277 绿，停在分支 | 任务 shared/work/deep-audit-1025/fix-tasks/FIX-C.md（先证再改）；报告 fix-reports/FIX-C.md（9 节）；装置 repo-c/probe/{prefix,j… |
| 10-18 11:46 | backlog 台账卫生：27 条「门未注册」类 stale 条目标闭（用户令 a11y 修复「攒着等下一批吧」之后顺手做的复核） | 背景：用户对 test_scan.py:2630 a11y 门标记遗留缺陷说「攒着等下一批吧」→ 按待办登记纪律把该条触发条件改绑到活的时点；顺手发现 backlog 里同类的「门未注册 scan.sh」条目全都没有标闭… |
| 10-18 11:52 | ↳ deep-audit-1025 R1 机验半边完成（用户装 2280 包后令「方便完成的验证验了」） | 装包身份链 ✓：设备 versionName=1.0.0+2280 / versionCode 220002280 / lastUpdateTime 2026-10-18 11:47:09 == release andr… |
| 10-19 07:45 | FIX-F 收口：deep-audit-1029 ISSUE-07（`android-shizuku-cli exec` 选项作用域，两裂口均 P1）—— `fix/da1029-f-shizuku-argv` @ `df76dfc`，CI #39176352125 绿，停在分支 | 身份链：基线 main 83caac6 → 分支 fix/da1029-f-shizuku-argv @ df76dfc85bdf24e4046bc25ad9626b93df4f4d2a（父 = 基线，单提交，3 文件 … |
| 10-19 10:04 | v1.0.0+2319 真机验证（deep-audit-1029 批次，无 a11y 口径）—— 2026-10-19 | 装机身份 byte 级锁定：versionCode 220002319 / versionName 1.0.0+2319 / lastUpdate 09:41:20；设备侧 sha256sum base.apk == G… |
| 10-19 16:38 | FIX-B 收口（deep-audit-1031 ISSUE-02 [P1] 权限槽位并发竞态 + 会话 grants 生命周期） | 身份链：基线 2bc07ec → 分支 fix/da1031-b-permission-slots，tip 75b84d09af8dcf553b5ac602e41f75fadfa8aceb（两提交：6e29173 主体 … |
| 10-20 09:21 | FIX-A2 收口（deep-audit-1034 ISSUE-02 + ISSUE-04）：SAF 根判定统一规范化 id + Shizuku 特权路径/uid 回退 —— `fix/da1034-a2-docsprovider-shizuku` @ `69f435a2`，CI #2366 success，停在分支 | 身份链三方逐字符：基线 b7acf94a → 两提交（0aaae779 生产 + 出货测试 / 69f435a2 四门扩展）→ LOCAL == REMOTE == CI head_sha = 69f435a2396cf… |
| 10-20 15:47 | §VERIFY-2388 真机验证收口（CLI/深链面全绿 + S34 新缺陷入册 + UI 视觉面交用户） | 身份：app 1.0.0+2388（15:11 装 = main CI #2388，含 dv14 九支并集 71882a8e）。工作区 shared/work/verify-2388/，回执已入 backlog（267→… |
| 10-20 20:09 | FIX-A3 收口（deep-audit-1036 ISSUE-A-05 P1）：通知台账同 id 幂等性 —— `fix/dv15-a3-notif-ledger` @ `4e5759ae`，CI #2398 绿，停在分支 | 身份链三方逐字符：基线 main 57c9619a → 3 提交（86273fdb 生产 / 3f648851 测试 / 4e5759ae 门）→ 本地 == git ls-remote == CI #2398 head… |
| 10-21 13:54 | FIX-3 收口（deep-audit-1038 / v18 波 A-02）：`--visible-only` 布尔/取值混淆 —— `fix/dv18-3-a11y-visible-only-flag` @ `ea88de826984ba231a681b650188726a614d8384`，CI #2423 success，停在分支 | 身份链三方逐字符：基线 main 10dd329e → 3 提交（72fed77 生产 / b7b836d 测试新增 / ea88de8 门扩展）→ 本地 == git ls-remote == CI head_sha … |
| 10-21 17:21 | fastio 权限门设计批评（用户拍板中）—— 2026-10-21 | 触发：用户见 fastio_rm 首用弹窗（17:11 gate awaiting → allow_session），批「这不是敏感权限，类似沙箱/终端，不该要权限，甚至应该自动做」。 |
| 10-21 18:46 | fastio 权限门 BYPASS 收口（deep-audit 外单点）：分支 `fix/fastio-permission-bypass-1021` @ `81c70b68`，CI #2430 首轮 success，停在分支 | 触发：用户批「fastio 首用弹窗不对——这不是敏感权限，该像沙箱/终端一样；甚至该自动做」→ 主会话判定「批评成立、但『自动做』被否决」→ 用户令「方案 B，开始做」。 |
| 10-24 12:53 | VERIFY-2597 第 2 轮真机验证（悬浮窗 / 联系人）—— 2026-10-24 12:2x–12:5x | §1046-C6 R1 三场景 PASS：①后台贴窗 = 日志 fg=false enabled=true shouldShow=true shown=true + dumpsys window 实证 Window{cc… |
| 10-24 19:23 | 免确认模式 + 批量授权实现完成（feat/auto-approve-mode，停在分支） | 免确认模式 + 批量授权实现完成（feat/auto-approve-mode，停在分支） |
| 10-25 21:43 | 第七批（末批 · 28 门）材料已备 —— 两轮覆盖账闭合 63+28=91（2026-10-25 21:42） | 产物：/var/minis/mounts/背景包/发送-A\|发送-B/（00 spec_v9 8.6KB / 01 索引 27.2KB·25.2KB 已挡清单 34·34 条臂 / 02 diff 233B 各 / re… |
| 10-26 13:19 | B7-t11 收口：a11y_handler_contract + voice_cancel_guard 两门 12 条见证全翻红（分支 `fix/b7-a11y-voicecancel` @ 门提交 `e90ca37f3`，CI #2721 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 e90ca37f3（2 文件 +781/−336，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |

## 18. 国际化 / 文案 / 本地化

**跨度** 2026-08-04 ～ 2026-10-26 · **33 条** · **状态** 已闭环（08-28 全量 i18n 改造合并 main）

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
| 09-23 17:20 | P1 三条批量处置：两条已被修、一条方向与 backlog 原判相反（09-23） | 用户指令：「p1，那就把他们都修了吧」（P1 = §27a 图片门 / §27c(2) canResume 检查 / §29 换源重试）。 |
| 09-25 22:58 | ★ 技能元数据的真相：应用读的是 skills.db，不是 SKILL.md 的 frontmatter（09-25 用户实证发现） | 用户问「为什么我没看到这个技能对应的改变」——他发现技能显示 version 1.0.0 + 旧的长 description，但正文已经是我今天改过的新内容。 |
| 09-27 13:30 | 沙箱生命周期 5 个硬编码常量改为可调 + 内置提示词补全（09-27，用户拍板「5 个一起上、UI 滑条要、范围按建议」） | 分支 feat/runtime-sandbox-knobs @ a3dc9027（基 main 8dcb82e8，17 文件 +358/−23），CI run 36296621337 completed/success … |
| 09-29 09:27 | 合并 fix/skills-entry-label-mismatch → main = b692f2f3（09-29，用户「检查没问题就等 CI 完合并」+「触发完不用等」） | 改动：7 locale strings.xml，21 处标签 "RikkaMinis Skills" → "Minis Skills"（skill_empty_action / skill_minis_skills_mo… |
| 10-20 08:39 | FIX-B1 收口（deep-audit-1034 B-01+B-02）：`fix/da1034-b1-onboarding-i18n` @ `81d5e0d0`，CI #2361 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 81d5e0d0ecbef8d9bc9c2f973556d2711884fe48（单提交，5 文件 +731/−30）→ CI #2361（run 39346518996）s… |
| 10-20 13:28 | FIX-B4 收口（deep-audit-1035 ISSUE-05 + ISSUE-08）：`fix/dv14-b4-remember-keys` @ `5d0d8ab`，CI #2384 success，停在分支 | 身份链三方逐字符：基线 main 76488f2 → 三提交（9af1e0c 生产+测试 / 5f921ae 门扩展 / 5d0d8ab 补 import）→ LOCAL == REMOTE == CI head_sha… |
| 10-23 15:34 | FIX-11 收口（deep-audit-1045 / v24 波 B-07 P1）：`fix/dv25-b7-saf-bounded-read` @ `17bb3f22`，CI #2538 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec90 → 4 提交（47780589 生产 / 74902651 测试 / 4956fbe0 门 / 17bb3f22 注释可扫描性修正）→ 本地 == ls-remote … |
| 10-26 05:03 | B6-t04 收口：webapp_asset_url_cleanup + i18n 两门 12 条见证全翻红（分支 `fix/b6-webasset-i18n`，门提交 `8f1fb1862`，CI #2697 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8f1fb1862（i18n +277/−99，webapp … |

## 19. 上游 / 生态吸收 / 开源

**跨度** 2026-08-03 ～ 2026-10-26 · **104 条** · **状态** 常态（每轮吸收都有「不做」清单，比「做」清单更重要）

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
| 09-23 19:12 | 官方 OpenMinis 1.13 APK 分析（2026-09-23） | 上游 = OpenMinis/OpenMinis；fork 基线 e488b1b1 = 官方 v1.10 合并点（2026-07-25） |
| 09-23 19:29 | 上游沙箱/闪退修复对比（2026-09-23 补） | 上游 1.12 沙箱修复（commit df9cb59b）：SeccompFallbackPolicy.kt 新文件+129（seccomp 被拒时降级重试自愈早期启动崩溃，带 JVM 测试） + PersistentS… |
| 09-23 19:33 | fork vs 官方 1.13 定位差异（2026-09-23） | 提交差（GitHub compare 实测）：fork 相对上游 main ahead 1277 / behind 28（fork 在 v1.10 基线上自有 1277 提交；落后 = 1.12+1.13） |
| 09-23 21:20 | Jev（TypeSafe System One 模型）评估：不吸收，但撞到真痛点（2026-09-23） | Jev 是什么：TypeSafe AI 闭源模型，2026-09-15 限量早访（$40M 种子轮 DCVC 领投，$200M 估值）。不生成文本，输入 state + 预定义封闭选项集 → 返回 Choice/Scor… |
| 09-23 21:29 | ↳ §38 登记完成（2026-09-23，紧接 Jev 评估） | 用户拍板「按你说的改吧，不用改代码的话」⇒ 未改一行应用代码、未建分支、未跑 CI，只做 backlog 登记。 |
| 09-24 20:45 | 仓库生态盘点（09-24，API 实测）：本仓库 ***OWNER***/RikkaMinis 8 forks（4 | 仓库生态盘点（09-24，API 实测）：本仓库 OWNER/RikkaMinis 8 forks（4 个有过改动：MZHlongchu 09-19、ALT_USER 小号 09-24、hjhjd 改名 VCPMinis… |
| 09-26 09:13 | Filterrr/RikkaMinis 仓库调查（09-26，用户问「他改了什么」）：①不是本仓库 fork——API | Filterrr/RikkaMinis 仓库调查（09-26，用户问「他改了什么」）：①不是本仓库 fork——API fork=False、compare 404、fork 网络脱离；与我们的共同祖先只有 OpenMi… |
| 09-26 09:25 | 吸收 Filterrr 改动 → 拆成两个派发任务（09-26，用户决定 A+B，B 整包不拆）：派发文件在 /var/ | 吸收 Filterrr 改动 → 拆成两个派发任务（09-26，用户决定 A+B，B 整包不拆）：派发文件在 /var/minis/shared/absorb-filterrr-0926/（00-DISPATCH-BOA… |
| 09-26 09:59 | 会话 A：吸收 Filterrr 网络观测与预热包 → 分支 CI 绿（09-26，停止节点） | 会话 A：吸收 Filterrr 网络观测与预热包 → 分支 CI 绿（09-26，停止节点） |
| 09-26 11:03 | 吸收 Filterrr 两分支最高规格复核（09-26，用户要求「不省」）：报告 /var/minis/shared/a | 吸收 Filterrr 两分支最高规格复核（09-26，用户要求「不省」）：报告 /var/minis/shared/absorb-filterrr-0926/VERIFY-REPORT.md。A=fix/absorb-… |
| 09-26 13:25 | 两个吸收 Filterrr 分支真机验证结果（09-26，用户确认） | 用户装最新包 v1.0.0+1837（构建自 f2b00151 = 当前 main 最新）真机验证两个吸收能力： |
| 09-27 00:02 | RikkaMinis 观众画像 & 上游情报调查（09-26，用户发起：查关注者/fork 者的关注图谱当"信息富集地"） | 产物：/var/minis/shared/audience-research-20260926/REPORT.md（395 行）+ audience-0926/（56 份原始 JSON + collect.py/anal… |
| 09-28 12:45 | 思考折叠对照 rikkahub → 抄「实时标题 + 思考时长」（09-28，用户「能够实锤就抄」） | 实锤结论（真实数据，非推测）：①rikkahub 机制核实（extractThinkingTitle 取最后一个整行加粗 + showThinkingTitle = loading && title != null + … |
| 09-29 15:55 | fork 构建失败修复：签名 + workflow 权限三连环（09-29 晚，Pre-Release 全绿发 APK） | 用户报「构建失败了」→ 三个 fork 存量配置问题（与 merge 的代码无关，全部编译绿）：①fork 不继承上游 Actions secrets，SIGNING_ 三个 secret 为空 → 空密码签名失败（ke… |
| 09-30 13:42 | OpenMinis 上游调研（09-30，用户「看看有什么能抄的」） | 上游仓库事实：OpenMinis/OpenMinis = Minis 官方开源镜像（GPL-3.0，Swift+Kotlin 双端，4.8k star）。公开历史只有 53 个 squashed commit（v1.10… |
| 09-30 13:59 | 上游 A 档移植已派发（09-30，3 会话并行） | 用户拍板：无障碍已开启（真机复核 running:true）；工程量大 → 分派出去。 |
| 09-30 15:01 | 上游 A 档移植·会话 1 完成（分支 CI 绿，未合并） | 分支 feat/a11y-restricted-and-recovery @ c3467b70cb56080326a3ef0f4969a4366a2bf588（基于 main 0c78f00） |
| 09-30 15:47 | 上游 A 档移植：三分支合并前核查完成（09-30） | 报告：/var/minis/shared/openminis-port-0930/REPORT-merge-check.md（三项核查 + 逐条约束核验 + 诚实边界）。 |
| 09-30 16:13 | ★ 封存完成：上游 A 档移植 4 分支合并 main（09-30，main = 0924445f） | 用户指令链：「合并前核查 → 按你的建议来吧，合并 → 触发了就不用等了」。 |
| 09-30 16:29 | 上游 A 档移植三分支合并后审计（09-30 晚） | 今天净账：main 0c78f00 → 0924445f = 27 文件 +2569/−88（3 功能分支 + chore fold 归一 strings 约定，fold 由用户推）。CI #1886/#1887/#18… |
| 09-30 19:52 | 上游崩溃类修复对比定案（09-30，用户问「上游解决沙箱崩/闪退没有」） | GH#341 闪退（WebView 渲染进程死亡 → 整 app SIGTRAP abort）：上游 v1.14 用 ui/webview/WebViewRenderProcess.kt helper（79 行，只打日志… |
| 10-02 22:12 | tall-1997/OpenMinis-Linux（Minis Ultra）第二次调查（10-02） | tall-1997/OpenMinis-Linux（Minis Ultra）第二次调查（10-02） |
| 10-04 22:48 | 压缩体系 vs《Compaction Cliff》对比分析完成（10-04，用户帖论文求对比改进） | 对象：用户发来一篇"2026 下半年智能体记忆/压缩研究"文章（arxiv 2608.22752 + Mem0 blog 等），要求对比 RikkaMinis 现压缩功能找改进。 |
| 10-05 19:38 | 底子盘点数据：fork vs 上游 OpenMinis（10-05 实测，二次开发视角） | 上游参照 = openminis-ref 快照 @ v1.14（b4c0661，2026-09-29，单 commit 快照仓）；fork 基线 = 上游 v1.10 @ e488b1b1（2026-07-25），pac… |
| 10-06 12:25 | 对象：上游 OpenMinis v1.15-beta.1（10-01）+ beta.2（10-04）。逐项过修复门核查 | 对象：上游 OpenMinis v1.15-beta.1（10-01）+ beta.2（10-04）。逐项过修复门核查 fork 同族 @273aecc6。 |
| 10-06 17:43 | 外部项目吸收审查：OpenDots + Raven → backlog §67 全部登记不做（10-06） | 对象：OpenDots（CopilotKit，AI 同事模板，本质 = CopilotKit Intelligence 托管服务导流壳）+ Raven（EverMind，RSI 研究 harness，本质 = 云/容器基… |
| 10-09 22:43 | tall-1997/OpenMinis-Linux（Minis Ultra）调查结论 | 与用户同 fork 自 OpenMinis/OpenMinis，分岔点 9-02 (v1.13)。24 天 19 星（0.79/天）vs 用户 69 天 22 星（0.32/天）。 |
| 10-10 21:51 | Android-AI-gateway 项目（用户 fork） | 仓库：/var/minis/workspace/Android-AI-gateway（fork 自 alarmedvine/Android-AI-gateway） |
| 10-13 00:22 | NextPlayer fork（alarmedvine/nextplayer）运维要点 | push token：GITHUB_TOKEN（主号）和 GITHUB_TOKEN_FULL_RIGHT（ALT_USER）都无 push 权，必须用 GITHUB_TOKEN="$GITHUB_TOKEN_FULL_R… |
| 10-14 09:02 | M3 主题线二轮：工程量分级 + 视觉模拟交付 + 用户倾向不跟上游（2026-10-14） | 用户三问：各层工程量 / 上游修改（倾向不跟）/ 改动效果。 |
| 10-14 09:13 | 「上游更流畅」诊断：fork 基线落后一整个 v1.14 周期（2026-10-14） | 用户问：为什么用官方 OpenMinis 时感觉比 fork 更流畅。 |
| 10-14 09:27 | v1.14 移植 ROI 分析：条带级挑拿（2026-10-14） | 背景：用户令「建议跟哪些 + 投入产出比」。对 v1.14（678 文件 +104K/−6K）做逐文件三态分类（fork vs 上游）：11 可直拿 / 150 两边都改 / 472 fork 没有——整域跟版不可能（f… |
| 10-14 10:23 | S3 完成：v1.14 移植 sessions.updated_at 索引（port/v114-session-inde | S3 完成：v1.14 移植 sessions.updated_at 索引（port/v114-session-index @ cf672fe0） |
| 10-14 10:24 | S1 完成：v1.14 移植 ContentCapture 关闭 + 资源 HUD off-main（port/v114 | S1 完成：v1.14 移植 ContentCapture 关闭 + 资源 HUD off-main（port/v114-capture-hud @ 15d65007） |
| 10-14 10:36 | S4 完成：v1.14 聊天健壮性组合移植（port/v114-chat-gates @ a7f6e8e7，CI #2001 绿） | 用户令：「读这个文件并立刻执行：/var/minis/shared/port-v114/tasks/S4.md」（+一次「继续」）→ 条目制移植，停止节点 = 分支 CI 绿。 |
| 10-14 11:25 | ↳ v1.14 Tier1+S4 已合并 main（2026-10-14） | 结果：4 分支（capture+hud / thumb-ceiling / session-index / chat-gates）→ 合并前核查 8 项×4 全过（含 4 套变异负控）→ merge 1cd1ff31b→… |
| 10-17 11:29 | 审计背景包 v3 更新（吸收 deep-audit-1020）— 2026-10-17 | 输入：用户令「又完成了一波修复，开始更新」。本轮 = deep-audit-1020：8 支修复（A models-cache 漏参 / B shizuku argv / C offload 相对路径+tar 前缀 / … |
| 10-17 14:22 | 审计背景包 v4 更新（吸收 deep-audit-1021）— 2026-10-17 | 输入：用户令「刚刚又完成了一波修复，更新一下吧，为下一轮做准备」。本轮 = deep-audit-1021（11 工单 → 8 支，门 67→74），已并入 main 396adc442（10-17 14:13 SGT，… |
| 10-17 17:28 | FIX-D 收口：deep-audit-1022 ISSUE-04（OpenMinis 兼容层 `appendV1Suffix` 极性反转 + 四字段漏迁）—— fix/da1022-openminis-compat @ 59fb69f，CI #2241 绿，停在分支 | 任务书 /var/minis/shared/work/deep-audit-1022/fix-tasks/FIX-D.md（+COMMON.md）；报告 fix-reports/FIX-D.md（8 节，16727→11… |
| 10-17 18:13 | 审计背景包 v5 更新（吸收 deep-audit-1022 + 趋势分析结论）— 2026-10-17 | 文件：/var/minis/mounts/笔记/RikkaMinis审计背景包/audit-context.md（178 行/24386 B → 217 行/30.7 KB，v4→v5）。 |
| 10-17 22:06 | trae-agent（bytedance）研究：13 项机制 → 仅 1 项建议吸收，附三臂 A/B 实测 | 任务：用户给 https://github.com/bytedance/trae-agent，要求「研究一下，看看有没有这个应用能够吸收的」。 |
| 10-17 22:11 | ↳ deep-audit-1023 合并 main + 文档归档完成（用户令「合并，更新文档」） | 合并：integration/da1023（@ 00f24b87）--no-ff 合入 main → d71a9046f0a36cc6a8bf71912b77088b2618abab；main tree 98d48c14… |
| 10-20 08:10 | FIX-B4 收口（deep-audit-1034 B-07+B-08）：弹窗派生态与活数据源脱钩 —— `fix/da1034-b4-dialogs-derived` @ `77a5cae`，CI #2356 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 4 提交 → 77a5cae258921a7156c77dfbfebeb460e83580b8 == git ls-remote == CI head_sha；ru… |
| 10-24 06:50 | 审计背景包 v29 构建完成（1047 波吸收后，为下一波双路审计准备）—— 2026-10-24 上午 | 触发：用户「好了，这一批已吸收完了，为下一批做准备吧」＝ 1047（dv27 九支）已合 main，重建背景包 + 双路提示词，落挂载笔记子文件夹。 |
| 10-24 11:37 | 审计背景包 v30 构建完成（1048 波吸收后，为下一波双路审计准备）—— 2026-10-24 中午 | 触发：用户「这一批也吸收完了，为下一批做准备吧」＝ 1048（dv28 九支）已合 main，重建背景包 + 双路提示词，落挂载笔记子文件夹。 |
| 10-26 10:42 | B6-t10 收口：open_url_broker_scheme + openminis_compat_field_parity 两门 13 条见证全 CAUGHT（分支 `fix/b6-openurl-compat`，门提交 `66137f77e`，CI #2707 绿 @ `47a8b441d`，报告回填 `d5291e1ee`，停分支） | 基线 base/batch5-shared @ e145ec961。改动面 = 2 个门脚本（open_url +604/−482，openminis +328/−161），生产 .kt 0 行、scan.sh/test… |

## 20. UI 组件 / 设置页 / 交互微调

**跨度** 2026-08-04 ～ 2026-10-24 · **123 条** · **状态** 持续（09-12 参数化面板已合并 main）

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
| 09-24 08:57 | ProviderList 拖拽排序 + ModelGroups 去滑动删除（分支 CI 绿停） | 分支：feat/provider-reorder-and-group-cleanup @ 6a990654（2 commits，4 文件 +253/−126）→ CI run #1772 success、head_sha… |
| 09-24 13:18 | 会话列表 LazyColumn 分支 CI 绿（09-24，停止节点） | 任务：用户反馈 rescan-gate 修复后「使用体验没有明显改善」→ 转会话列表 LazyColumn。main 已推进（350a9993 = 三个 merge 之上，前一修复 d57643f4 已进 main）。 |
| 09-24 13:21 | ↳ lazy-session-list 核验 + 合并 main = 665877a5（09-24） | 用户指令：「检查核实分支上的修改，没有问题就合并」= 核实 + 合并放行。 |
| 09-25 20:41 | 09-25 晚三条 UI 线全部到停止节点（分支 CI 绿，均未合并）：①feat/typing-status-band | 09-25 晚三条 UI 线全部到停止节点（分支 CI 绿，均未合并）：①feat/typing-status-band @ a54c2f78——状态带：TypingIndicator 包进固定 36dp Box（TYP… |
| 09-25 22:04 | 09-25 全量回归审计：今天 24 提交 / 33 文件，无功能级 bug，2 条次生开销 P2 + 5 条边界登记 | 范围：基线 2022b0c5（昨 21:12）→ main 50b399c9，git log 实测今天进 main 的是 24 个提交 / 33 文件 +1511/−267（不止记忆里的 8 条 merge——上午还有 … |
| 09-27 17:18 | 两问题核查（思考标签泄漏 + 选择框复制失灵）→ /var/minis/shared/work/leak-0927/REPORT.md（09-27，基线 aeffcef9，未改代码） | 任务：用户报两个问题（① 像 …defects</thinking> 这样的内部文本「露出来」，且发生在正在跑的会话里；② 长按自动选择弹出的选项框里点「复制」有时候失效），「两个一起查」。 |
| 09-27 17:29 | ★ 复制按钮失效定案：手柄弹窗压住工具栏弹窗（09-27，用户给出特定场景后收敛） | 用户补充的场景（决定性）：失效只在「最前端的光标（选区起点手柄）与复制按钮重叠」时发生 —— 特定、可复现。这条把上一轮三个候选（按钮横移 / 空选区 / 弹层每帧重定位）收敛成一个：跨 Popup 窗口 z 顺序问题。 |
| 09-27 17:48 | 复制按钮失效修复落地 → 分支 fix/selection-toolbar-handle-overlap @ 8a76278d（09-27，用户「就按你说的来吧」，未合并） | 改动（2 文件 +49/−9，基 main aeffcef9）：A ChatScreen.kt —— MinisSelectionHandlesHost 提到 MinisSelectionToolbarHost 之前（P… |
| 09-28 01:45 | 历史抽屉长按菜单「偶尔多出重命名」结案（09-28，用户报告，调查未改码） | 真相：第三个选项是「重新生成标题」（sessionlist_regenerate_title，刷新图标），用户误读为「重命名」——菜单里根本没有 rename 功能（8 语言 strings.xml 仅 pin/unpi… |
| 09-28 14:24 | 思考头部两个显示开关完成 → 停止节点（2026-09-28，用户拍板「保持字符数，时长默认不显示」） | 分支 feat/thinking-header-toggles @ 65b26bb2（基于 main bd42e3df，12 文件 +300/−11），未合并（合并权在用户）。 |
| 09-30 16:13 | ★ 封存完成：上游 A 档移植 4 分支合并 main（09-30，main = 0924445f） | 用户指令链：「合并前核查 → 按你的建议来吧，合并 → 触发了就不用等了」。 |
| 10-02 15:24 | 停止按钮杀不到压缩 P1 已修（10-02，分支 fix/compact-cancel-on-stop-1002 @ 4bc1352，CI #1908 绿停） | 改动（5 文件 +231/−8，停在分支未合并）：①compactAll 的 launch 存 compactJob，cancelStream 连带 cancel（9 处调用点全统一——语义都是"当前轮工作立刻停"，fi… |
| 10-06 07:14 | ui/polish-1006 审查+合并 main + main CI 绿（10-06 早，「检查+有问题修+没问题合并」指令） | 对象：ui/polish-1006 @05f31a0b（2 commits：①压缩模型行删「选择…」按钮→整行点击弹框；②与 LimitsSliderRow 风格对齐〔+分隔线/−textAlign〕；③定时任务编辑页删… |
| 10-06 14:00 | 提问卡片降级（2026-10-06 用户拍板：不立项/花架子）：用户质疑「agent 日常直接问就行，卡片是炫酷功能」。 | 提问卡片降级（2026-10-06 用户拍板：不立项/花架子）：用户质疑「agent 日常直接问就行，卡片是炫酷功能」。重过一阶门证实判定修正：纯文本提问已覆盖「追问并等待」（turn 结束问、历史持久、杀掉重问上下文成… |
| 10-12 14:59 | 切模型后「等待中但按钮已停止」= switchModelAndRerun 的 claim 竞态（2026-10-12 真机实测定位） | 症状（用户报告）：发消息→provider 慢→切模型→界面仍显示等待/生成中，但底部按钮已回到「发送」（停止态）→用户再点重试。 |
| 10-12 23:07 | NextPlayer 定制：底部导航移除 + 个人签名出包基建（2026-10-12） | 用户需求：fork alarmedvine/nextplayer（= 上游 anilbeesetti/nextplayer 干净 fork，main 与上游一致）。要求：①底部「可切换的胶囊导航」整体搬到设置页、入口页只… |
| 10-14 00:10 | M3/Material You 转向评估报告交付（2026-10-13 深夜） | 交付：/var/minis/shared/work/m3-eval-1013/REPORT.md（14.5KB）+ 4 张用户真机截图 + a11y 截图（screenshots/fig1-4、current_chat.… |
| 10-14 09:02 | M3 主题线二轮：工程量分级 + 视觉模拟交付 + 用户倾向不跟上游（2026-10-14） | 用户三问：各层工程量 / 上游修改（倾向不跟）/ 改动效果。 |
| 10-14 09:27 | v1.14 移植 ROI 分析：条带级挑拿（2026-10-14） | 背景：用户令「建议跟哪些 + 投入产出比」。对 v1.14（678 文件 +104K/−6K）做逐文件三态分类（fork vs 上游）：11 可直拿 / 150 两边都改 / 472 fork 没有——整域跟版不可能（f… |
| 10-16 08:52 | crash-1016 闪退诊断：列表分支吞缩进围栏 + `\(\)` 空 latex → androidx appendInlineContent require 崩 | 现象：08:31:58 / 08:32:22 两次闪退（crash-.log，同一栈），版本 1.0.0+2152（main 04e74c53）。异常 IllegalArgumentException: alternat… |
| 10-16 09:44 | crash-1016 修复交付：列表项吞缩进围栏 + `\(\)` 空 latex（分支 `fix/1016-md-list-fence-inline-math` @ `25e5400`，CI #2153 绿，停在分支） | 任务：用户「修」（承接上一轮的闪退诊断）。基线 main 04e74c53325a（= 设备版本 1.0.0+2152 的 head_sha，逐字符一致）。报告 shared/work/crash1016-fix/REP… |
| 10-17 10:36 | da1020 FIX-B 收口：Shizuku argv 20 开关声明 + group 按位置取（fix/da1020-shizuku-args @ 9990218，CI #2214 绿，停在分支） | 任务书 shared/work/deep-audit-1020/fix-tasks/FIX-B.md；报告 fix-reports/FIX-B.md；装置 repo-b/probe/（durable）。基线 main 9… |
| 10-17 11:50 | 真机验证：deep-audit-1020 八支修复（设备 1.0.0+2221 = CI #2221 / 试合树 bdc6d68） | 身份链闭合：设备 base.apk sha256 029be18c…81db97（14,370,617 B，装于 11:26:45）与 CI run #2221（id 38997214449）artifact 逐字节一致… |
| 10-17 13:21 | FIX-D 收口：deep-audit-1021 ISSUE-05（ChatMenuSettingsScreen 监听键前缀失配 + 排序 State 不重读，P1）—— fix/da1021-chatmenu-listener @ ecb2fc5，CI #2223 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-D.md 并执行（先证再改、分支隔离、自建工作树 repo-d）。基线 main d0c1c92。报告… |
| 10-17 15:05 | verify-1021 真机回归收口：4/4 通过 0 回归 + 新发现预览屏裸拼 `#`（backlog §1021-V）—— 2026-10-17 | 输入：用户令「现在这个应用是包含了刚刚一波修复的应用开始测试」。deep-audit-1021 八支并集树 integration/da1021 @ 315c8bd（CI #2232）。 |
| 10-17 16:53 | FIX-B 收口：deep-audit-1022 ISSUE-02（草稿晋升后子面板仍绑 `__new__` 路由参数）—— fix/da1022-draft-session-binding @ f700db8，CI #2234 绿，停在分支 | 任务：读 shared/work/deep-audit-1022/fix-tasks/COMMON.md + FIX-B.md 并执行。报告 fix-reports/FIX-B.md（8 节）；装置 repo-b/pro… |
| 10-17 17:05 | FIX-G 收口：deep-audit-1022 ISSUE-07（Cookie 域名列表+删除/清空 UI 闭环）+ ISSUE-09（四工具异常分支丢 toolTitle）—— fix/da1022-cookie-and-tooltitle @ 051da7f，CI #2236 绿，停在分支 | 任务 shared/work/deep-audit-1022/fix-tasks/FIX-G.md（+COMMON）；报告 fix-reports/FIX-G.md（8 节）；先证 pre-fix/G-evidence.… |
| 10-17 22:05 | 审计背景包 v6 + 提示词 v3 更新（为下一波审计准备，2026-10-17 22:0x） | 位置：/var/minis/mounts/笔记/RikkaMinis审计背景包/（挂载盘，用户笔记）。三份文件：audit-context.md（v6，45 KB）· audit-prompt-v3.md（v3，18 K… |
| 10-19 11:43 | deep-audit-1030 FIX-B 收口：ISSUE-03 浏览器 UA 单源（池级分支 + 面板订阅）—— `fix/da1030-b-browser-ua-pool` @ `346e1be`，CI #2320 绿，停在分支 | 身份链：基线 main f7364ec → 单提交 346e1be5700ec5f8dabfed512048c557bf90f45f（4 文件 +476/−18 = 生产 2 / 门 1 / 测试 1 新建）→ 远端 t… |
| 10-19 19:54 | FIX-E 收口（deep-audit-1032 ISSUE-06 P1）：设置屏首屏 reload 线程 + 技能编辑器 remember 键 —— `fix/da1032-e-settings-io-keys` @ `a2106ab0`，CI #39245665334 绿，停在分支 | 身份链三方逐字符：基线 09b6c12 → 两提交（ad733d56 主体 + a2106ab0 告警清理）→ CI run 39245665334 / job 118036843575 success，local ==… |
| 10-20 00:59 | FIX-E 收口（deep-audit-1033 ISSUE-06 + 07 P1 合支）：usage 模型身份取实时配置 + thinking 弹窗活值 —— `fix/da1033-e-settings-derived-state` @ `1a61b87a`，CI #2350 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 1a61b87a6c7c…；CI run 39278816691（#2350）success。7 文件 +629/−29（4 生产 .kt + 2 测试 .kt + 1 门扩… |
| 10-20 08:10 | FIX-B4 收口（deep-audit-1034 B-07+B-08）：弹窗派生态与活数据源脱钩 —— `fix/da1034-b4-dialogs-derived` @ `77a5cae`，CI #2356 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 4 提交 → 77a5cae258921a7156c77dfbfebeb460e83580b8 == git ls-remote == CI head_sha；ru… |
| 10-20 08:29 | FIX-B3 收口（deep-audit-1034 B-05+B-06 两支）：列表块 mathSpans 接线 + 技能面板增量 merge —— `fix/da1034-b3-markdown-sheets` @ `b906313`，CI #2359 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 4 提交（9b87adf markdown · 5032ebc sheet · 38fc024 门 · b906313 测试）→ HEAD b906313ad3a32312e… |
| 10-21 18:46 | fastio 权限门 BYPASS 收口（deep-audit 外单点）：分支 `fix/fastio-permission-bypass-1021` @ `81c70b68`，CI #2430 首轮 success，停在分支 | 触发：用户批「fastio 首用弹窗不对——这不是敏感权限，该像沙箱/终端一样；甚至该自动做」→ 主会话判定「批评成立、但『自动做』被否决」→ 用户令「方案 B，开始做」。 |
| 10-22 15:46 | VERIFY-2476 真机窗口：攒着的欠账一次性过 11 条 PASS + 3 条新发现（app `1.0.0+2476`） | 触发：用户「现在这个应用是包含所有的修改的」「你把他们做了吧」。装机自核 = versionName=1.0.0+2476 / versionCode=220002476 / lastUpdateTime 15:17:5… |
| 10-23 01:07 | FIX-9 收口（deep-audit-1043 / v22 波 B-03 P1）：`fix/dv23-b3-composer-single-writer` @ `8462e98d`，CI #2510 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 3900f9cd / 测试 a187a2a6 / 门 8462e98d）→ 本地 == ls-remote == CI head_sha = 846… |
| 10-24 01:23 | FIX-B3 收口（deep-audit-1047 / B-05 P1）：模型组详情屏系统语音三处断层 — `fix/dv27-b3-model-group-system-voice` @ `8feb5466`（CI 绿于 `77ba33fc`），停在分支 | 缺陷（真树 4 处，报告报 3 处）：ModelGroupDetailScreen.kt 对 SystemVoiceEntries（默认 Voice Input/Output 组的全部成员，虚拟、无 instance、不… |
| 10-24 19:23 | 免确认模式 + 批量授权实现完成（feat/auto-approve-mode，停在分支） | 免确认模式 + 批量授权实现完成（feat/auto-approve-mode，停在分支） |
| 10-24 20:09 | 真机验证：免确认模式（v1.0.0+2618）阶段 A/B 全通过（2026-10-24 20:04–20:09） | 设备：1.0.0+2618 / versionCode 220002618，lastUpdateTime 20:01:25，pid 24690。 |
| 10-24 20:11 | ↳ 免确认模式负臂验证通过（2026-10-24 20:10）—— 子旗真在分级控制 | 同一字段、同一命令、唯一变量 = sensitive 子旗，两次结果相反： |

## 21. Token 用量 / 成本统计

**跨度** 2026-08-05 ～ 2026-10-23 · **84 条** · **状态** 已闭环（09-09 实时用量 + 预算上限；09-12 parseUsage 修复合并）

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
| 09-24 17:16 | Jev 分诊 backtest 跑完（09-24，key 已配 tokendance.space 网关 bocha-je | Jev 分诊 backtest 跑完（09-24，key 已配 tokendance.space 网关 bocha-jev-v1，~60 调用 ≈ 8k tok）：快成立（4-10ms，均值 7ms）好不成立。三轮：No… |
| 09-24 21:13 | cjk-token-estimate 分支收口（另一会话完成）：用户确认另一对话已检查并合并——远端 main = 20 | cjk-token-estimate 分支收口（另一会话完成）：用户确认另一对话已检查并合并——远端 main = 2022b0c5（13e5612c 之上的 merge commit），fix/cjk-token-es… |
| 09-25 18:00 | 09-25 三日日志实读：优化空间有定量候选，不把计数当故障 | 读取 /var/minis/logs/minis-{23,24,25}.log、memspike、launch-beacon（应用 INFO 正式行筛选，排除 ToolChain 命令回显）。自动压缩 23日 4/4、2… |
| 09-25 18:07 | 09-25 真发现：卸载后自动压缩用旧 token 触发，两次可疑冗余 | 主线 1cc5ab1d 源码 AgentLoopEngine.kt 356→370 先 host.offloadContextIfNeeded(window,loopState.lastContextTokens) 再 … |
| 09-25 18:33 | 分支 CI 绿（09-25 停止节点，检查会话）：fix/offload-stale-token-compact @ f | 分支 CI 绿（09-25 停止节点，检查会话）：fix/offload-stale-token-compact @ f87d9102（1 commit，4 文件 +48/−8，基点 main 1cc5ab1d，非本会话… |
| 09-25 18:33 | 卸载后陈旧令牌修复分支 CI 绿（09-25，停止节点） | fix/offload-stale-token-compact @ f87d9102（1 commit，4 文件 +48/−8，基点 main 1cc5ab1d）。用户拍板「该修的修」→ 四边改动：①AgentLoopH… |
| 09-25 18:38 | 系统 prompt 9 万字符实测：三条证据链齐，登记不修（09-25） | 用户问"9 万字符先测实际 token/缓存命中再谈精简"应该怎么来 → 实测三步（minis-model-use agentrouter-1/deepseek-v4-flash，中转站即 app 主用 API）：①缓存… |
| 09-25 18:41 | 合并收口（09-25，用户放行）：--no-ff merge fix/offload-stale-token-compa | 合并收口（09-25，用户放行）：--no-ff merge fix/offload-stale-token-compact @ f87d9102 → main = bea9ae70（4 文件 +48/−8，merge … |
| 09-26 18:03 | 从日志逆推信息流 — 黑盒系统辨识（09-26，用户元问题） | 任务：用户问「日志是什么（多视角）？能否只从日志逆推应用的输入/输出/信息流（含所有设置类输入）」→ 完成首次黑盒系统辨识。 |
| 09-26 19:12 | Trace 缝合成本 A/B 实验判定：H 不成立，trace id 登记不修（2026-09-26） | 判定：预注册规则要求 B 臂 ≥2/3 探针题「更正确」或「猜测 ≤ A 臂一半」。实测两臂探针题全部满分 23/23、猜测率均为 0，准确性零差距 → H 不成立 → trace id 降级为登记不修，问题闭环。 |
| 09-26 19:17 | ↳ §18 churn 用户侧核查闭环（用户确认）：省电策略一直是「无限制」、后台一直锁着 → 厂商策略配置不当假设排除，归 | §18 churn 用户侧核查闭环（用户确认）：省电策略一直是「无限制」、后台一直锁着 → 厂商策略配置不当假设排除，归因定案为设备级内存压力（与 01:24 am_mem_factor CRITICAL 观测一致）。b… |
| 09-29 00:21 | MEMORY-ROLLUP 重建预算实测 → 方向被数据否掉（09-29） | 方法：真源码离线预览（/var/minis/shared/work/rollup-preview-0929/，engine 0a58e4a5… / repository 28162b6f… 与 main 逐字节相同；ko… |
| 10-02 22:12 | tall-1997/OpenMinis-Linux（Minis Ultra）第二次调查（10-02） | tall-1997/OpenMinis-Linux（Minis Ultra）第二次调查（10-02） |
| 10-05 08:27 | 任务书 C 执行完成：压缩摘要截断守卫 + 吵闹候选 60s 预算（fix/compact-truncation-guard-1005，CI 绿停） | 状态：分支 CI 绿 @ tip，确定性停止节点已达成，未合并（合并权在用户）。 |
| 10-05 21:24 | H 执行会话压缩实证 + 预算判定核实（10-05 晚，新包 6f357dc8） | 新包首次完整压缩 ×2（20:59 / 21:08，H 执行会话触发）：候选链 1/4 glm-5.3-flash 30s 超时 → 2/4 deepseek 30s 超时 → 3/4 fallback deepseek… |
| 10-05 22:06 | backlog 新增：压缩候选链 entry 级预算覆盖（10-05 晚，用户拍板「先攒着」） | 触发依据（已满足升级条件）：小写 glm-5.3-flash（tierflow entry 0df664ef，declared=low\|high\|max → quiet 分区→30s 预算）在压缩摘要任务上 5/5 TI… |
| 10-05 22:43 | ↳ H 分支核查并合并 main（10-05 晚，用户令「检查+有问题修+没问题合并」） | 对象：refactor/compact-orchestration-split-1005 @ 68f83ee6（ChatSessionLifecycle 拆分 CompactOrchestration，2 commits… |
| 10-05 23:05 | 压缩专用模型组分析（用户问「是否有必要给自动压缩专门开一个模型组/专用模型」）： | 压缩专用模型组分析（用户问「是否有必要给自动压缩专门开一个模型组/专用模型」）： |
| 10-07 07:16 | 用量页（UsageStatsScreen）加载慢 5-6s 调查 — 审计完成未修（10-07） | 对象：设置→Token 用量（UsageStatsScreen）。每次进页面 LaunchedEffect 全表扫 messages（token_usage/created_at 无索引）+ org.json 逐行解析聚… |
| 10-07 07:45 | usage-stats-perf-1007 施工完成：SQL 聚合 + 部分索引 + off-main（分支 CI 绿，未合并） | 交付：分支 fix/usage-stats-perf-1007 @ aabd3636（2 commits：0665c6b 主改动 6 文件 +286/−119 + aabd363 测试桩补齐 3 文件 +9），CI ru… |
| 10-07 08:37 | fix/usage-stats-perf-1007 独立检查完成（08:25–08:40，第二双眼睛核查 @15b9a24a） | 对象：fix/usage-stats-perf-1007 @ 15b9a24a（3 commits：0665c6b1 主改动 + aabd3636 测试桩 + 15b9a24a P0 修复）。另一会话 0ba1cb89 … |
| 10-07 08:58 | fix/usage-stats-perf-1007 合并 main 完成（2026-10-07 08:41–08:57 SGT，用户放行「攒着的攒着、没问题就合并」） | 流程：独立检查 0 阻塞 → --no-ff merge 297fa516（parents ac927148+15b9a24a；merge 树==分支树 diff 0 行；增量 10 文件 +307/−120 精确吻合）… |
| 10-11 00:37 | 修复「上下文超限静默终止」：fix/compact-stale-context-tokens-1010（2026-10-10，用户令「就按照你说的修吧」） | 根因（与我的初判不同，记录纠偏）：不是本地字符估算（bytes÷2.2）偏高——loopState.lastContextTokens 只有一个写入源（AgentLoopEngine.kt:1023/1029，来自 pr… |
| 10-11 00:49 | 分支核查+合并：fix/compact-stale-context-tokens-1010（2026-10-11 用户令 | 分支核查+合并：fix/compact-stale-context-tokens-1010（2026-10-11 用户令「有问题就修，没问题就合并」+「触发了就不用等了」） |
| 10-15 11:07 | 上下文预算缺口修复（fix/prewindow-budget-1015） | 现场：2026-10-15 T321 会话（16c39966）——同一份 4.9MB agent 历史，deepseek 中转报 191,856 tokens、GLM 直连报 920,577（4.8×）。本地账本持中转口… |
| 10-15 16:32 | FIX-A4 完成：消息级 token 估算双计/多路不一致 → 收敛为单一 wire 对齐实现（2026-10-15，分支 `fix/audit2-estimate-1015` @ `37533a3a`，CI #2115 绿，停在分支未合并） | 任务：shared/work/verify-audit2-1015/fix-tasks/FIX-A4.md（审计报告 BUG-06）。基线 main 891d755。报告 shared/work/verify-audit… |
| 10-15 21:19 | FIX-B 收口：audit3 F-02 预算停出环（P0）→ 分支 CI #2131 绿，停在分支（2026-10-15） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-B.md 执行。基线 main ca8b95d1，分支 fix/audit3-agentloop-1015 @ 09bb9df6，CI… |
| 10-16 09:59 | FIX-E 收口：fallback 重算预算 + blocked-empty 不记 Success + shell 预算门终态（fix/da1016-agentloop-budget @ d996e19e，CI #2160 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-E.md 执行（6a/6b P1 + 5a + 5b/6c 判定）。基线 main 04e74c53；单 commit d99… |
| 10-20 00:59 | FIX-E 收口（deep-audit-1033 ISSUE-06 + 07 P1 合支）：usage 模型身份取实时配置 + thinking 弹窗活值 —— `fix/da1033-e-settings-derived-state` @ `1a61b87a`，CI #2350 绿，停在分支 | 身份链三方逐字符：基线 c6d426a7 → 1a61b87a6c7c…；CI run 39278816691（#2350）success。7 文件 +629/−29（4 生产 .kt + 2 测试 .kt + 1 门扩… |
| 10-23 00:43 | FIX-7 收口（deep-audit-1043 / B-01 P1）：Rootfs 修复结算把继承来的 `Failed` 当成本次判决 — `fix/dv23-b1-rootfs-repair-settle` @ `84e4f9ac`，CI #39813094967 success，停在分支 | 身份链三方逐字符：基线 main 79ef6d59 → 3 提交（生产 f1425db6 / 测试 cbf2845b / 门 84e4f9ac）→ 本地 == ls-remote == CI run.head_sha =… |

## 22. 人格 / Soul / 提示词 / 技能体系

**跨度** 2026-08-04 ～ 2026-10-26 · **174 条** · **状态** 稳定（技能体系已固化为 12+ 个 skill，跨会话持久）

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
| 09-23 00:34 | 闭环审计框架落地 + spawn_agent 技能快照坑 | 工具坑（新，已实证） |
| 09-23 22:01 | skill description 收窄铺开：12/21 全部完成（2026-09-23 晚） | 结果：12 个有 evals 的 skill 全部压到 ≤200，description 总字符 4344→1664（−62%）， |
| 09-25 09:12 | 硬编码参数审计（09-25） | 用户问「应用还有哪些参数是硬编码的」→ 硬编码参数全量审计（09-25，main 2022b0c，独立 clone /tmp/rk-hardcode）。脚本 /tmp/scan_hardcode.py + /tmp/sc… |
| 09-25 15:12 | §38-B 收官 + 09-25 真机验证（用户确认 app 已装最新包 1.0.0+1800） | §38-B 收官 + 09-25 真机验证（用户确认 app 已装最新包 1.0.0+1800） |
| 09-25 16:51 | §38A 落地（09-25）：fix/skills-prompt-budget @ 254ecc7（1 文件 +6/−1 | §38A 落地（09-25）：fix/skills-prompt-budget @ 254ecc7（1 文件 +6/−1，MAX_SKILLS_IN_PROMPT 20→40 + 来源注释），本地 scan.sh 18/… |
| 09-25 17:39 | 合并收口（09-25，用户放行，两分支）：--no-ff 按序 merge fix/skills-prompt-budg | 合并收口（09-25，用户放行，两分支）：--no-ff 按序 merge fix/skills-prompt-budget（254ecc7c）→ 1a5d4096、fix/stream-recovery-grace-r… |
| 09-25 22:15 | rikkaminis-dev-methodology 评审 + 修正（09-25，用户要求复核别处模型的技能优化） | 背景修正：用户澄清——今天的改动已经真机验证过（装的是分支包，不是等 main 的包）。所以上一条审计的"用户手里 1.0.0+1802 不含今天改动"应更正为：band 那条改动已经过真机验证（用分支 CI 产物）。 |
| 09-25 22:43 | ↳ rikkaminis-dev-methodology 第 1 批修复完成（v1.3.1 → v1.3.2，09-25） | 关键环境事实（纠正我上一条的说法）：/var/minis/skills 不在任何 git 仓库里（git rev-parse --show-toplevel → fatal）。技能改动没有分支/CI 可走，其等价物是：①… |
| 09-25 22:58 | ★ 技能元数据的真相：应用读的是 skills.db，不是 SKILL.md 的 frontmatter（09-25 用户实证发现） | 用户问「为什么我没看到这个技能对应的改变」——他发现技能显示 version 1.0.0 + 旧的长 description，但正文已经是我今天改过的新内容。 |
| 09-25 23:34 | skill-metadata-resync 核查 + 合并收口（09-25，用户放行）：远端分支 fix/skill-m | skill-metadata-resync 核查 + 合并收口（09-25，用户放行）：远端分支 fix/skill-metadata-resync-from-disk @ 56b65fe1（1 commit，3 文件 … |
| 09-25 23:51 | 09-25 三天改动真机验证闭环（用户确认 1/2/3 已验证）：①skill-metadata-resync（装 +e | 09-25 三天改动真机验证闭环（用户确认 1/2/3 已验证）：①skill-metadata-resync（装 +ee22115b 包，改 SKILL.md description → 重开 → 技能页显示新值）✅；… |
| 09-26 00:05 | chore/remove-skill-detail-rescan 分支 CI 绿（09-25 深夜，停止节点）：用户发现 | chore/remove-skill-detail-rescan 分支 CI 绿（09-25 深夜，停止节点）：用户发现技能详情页「从磁盘重新扫描」在 fix/skill-metadata-resync-from-dis… |
| 09-26 00:08 | chore/remove-skill-detail-rescan 合并收口（09-26，用户放行）：全套 8 项核查在本 | chore/remove-skill-detail-rescan 合并收口（09-26，用户放行）：全套 8 项核查在本会话独立复跑（非沿用昨夜结论）——①diff 走查干净（1 个 .kt + 7 语言文件，+2/−4… |
| 09-26 00:13 | ↳ 教训（我犯的）：绕过 verify_branch.sh 编排器手跑子集（09-26 复发确认）：合并 chore/rem | 教训（我犯的）：绕过 verify_branch.sh 编排器手跑子集（09-26 复发确认）：合并 chore/remove-skill-detail-rescan 时只手跑了 diff/残留/scan.sh/冲突/括… |
| 09-26 00:19 | remove-skill-detail-rescan 验证闭环（09-26 用户确认）：用户用分支包（68ef2d51 | remove-skill-detail-rescan 验证闭环（09-26 用户确认）：用户用分支包（68ef2d51 的 CI 产物）真机验证通过——main @ e16c5f6e 是 --no-ff merge，树内… |
| 09-26 00:19 | 技能元数据回灌修复（09-25 深夜，已进 main） | 根因（三源核实）：技能元数据存 skills.db，模型侧 <available_skills> 读库里的 description（截 200 字符）；reloadFromDisk()/loadAll() 只从库读行（仅… |
| 09-27 00:02 | RikkaMinis 观众画像 & 上游情报调查（09-26，用户发起：查关注者/fork 者的关注图谱当"信息富集地"） | 产物：/var/minis/shared/audience-research-20260926/REPORT.md（395 行）+ audience-0926/（56 份原始 JSON + collect.py/anal… |
| 09-27 00:14 | ★ 撤回上一条调查结论中的"soul.lang / 振动 commit 丢失"（09-27，内容级证据定案） | 上一条记忆条目里"⚠️ 你自己有 3 笔 commit 不在 main 里" 是错的，全部撤回。三笔全在 main： |
| 09-27 13:30 | 沙箱生命周期 5 个硬编码常量改为可调 + 内置提示词补全（09-27，用户拍板「5 个一起上、UI 滑条要、范围按建议」） | 分支 feat/runtime-sandbox-knobs @ a3dc9027（基 main 8dcb82e8，17 文件 +358/−23），CI run 36296621337 completed/success … |
| 09-28 09:50 | HF 语义记忆 + 知识图谱记忆更新（09-28，用户要求处理） | HF 语义记忆：索引曾停在 09-21 14:00（1239 条），7 天新条目未入索引。增量重建（build --incremental，复用 1231 条旧向量 + 新嵌 165 条）→ 1396 条 / 8MB，已… |
| 09-28 09:50 | memory MCP 服务器移除（09-28，用户拍板「日常使用中根本没发挥作用，去掉」） | minis-mcp-cli remove memory 已删，MCP 集成只剩 termux-dock。判据 = 一阶门：该服务器无持久化（每调用新 session 写入蒸发）+ daemon 不传 env，shell/… |
| 09-28 09:55 | ↳ 文档三件套第二次重建 + 合并 main = 346ebf59（09-28，用户「更新文档是低风险，直接合并」） | 三回归测试（test_sanitize/test_rebuild/test_sagas）全 PASS → rebuild 1399 条（前次 1386 → +13，dropped 75）→ sanitize main+I… |
| 09-28 19:56 | Procedure Layer v0 工程量评估 + 并行派发包（09-28） | 工程量判定：大。测算 ≈ 3100~3700 行新代码（Phase A 纯 Python ≈2100~2500 含测试；Phase B Kotlin ≈1000~1200），约 20 文件，≈ 仓库史上最大单次 feat… |
| 09-29 09:26 | 技能入口标签修正（09-29，用户报「导入技能→Minis Skills 指向不一致」） | 问题：09-23 package rebrand（squash 7b7f12cd）把添加菜单/浏览器标题从 "Minis Skills" 改成 "RikkaMinis Skills"，但默认 URL 仍是 OpenMin… |
| 09-29 09:27 | 合并 fix/skills-entry-label-mismatch → main = b692f2f3（09-29，用户「检查没问题就等 CI 完合并」+「触发完不用等」） | 改动：7 locale strings.xml，21 处标签 "RikkaMinis Skills" → "Minis Skills"（skill_empty_action / skill_minis_skills_mo… |
| 09-30 13:42 | OpenMinis 上游调研（09-30，用户「看看有什么能抄的」） | 上游仓库事实：OpenMinis/OpenMinis = Minis 官方开源镜像（GPL-3.0，Swift+Kotlin 双端，4.8k star）。公开历史只有 53 个 squashed commit（v1.10… |
| 10-08 21:20 | §75 技能快照回灌完成（2026-10-08） | 机制（RikkaMinis 内置技能）：assets/skills/ 是安装种子，SkillRepository.installBundledSkill（:1335）用语义版本比较做门——assets 的 SKILL.m… |
| 10-09 00:03 | D2 域扫描完成（sweep-1008，子代理） | 产出：/var/minis/shared/sweep-1008/reports/D2.md。域 = ui/chat 其余 ~87 个（除 D1 状态核心），重点全读 ChatScreen/ChatViewModel/St… |
| 10-09 19:13 | 症状：用户说「子代理」不指向会话子代理（spawn_agent）；且派发时「指令没发过去」。 | 症状：用户说「子代理」不指向会话子代理（spawn_agent）；且派发时「指令没发过去」。 |
| 10-09 19:56 | 分支施工完成（用户拍板「按你建议的来」，A 同族全修 + B 路由下沉 + C 根治+兜底）： | 分支施工完成（用户拍板「按你建议的来」，A 同族全修 + B 路由下沉 + C 根治+兜底）： |
| 10-09 20:16 | 分支审查+合并收口：fix/subagent-routing-delivery-1009（2026-10-09 晚，用户令「有问题就修，没有问题的话合并」） | 对象：fix/subagent-routing-delivery-1009 @ 5862b647（1 commit，7 文件 +209/−3，基线 main cbb9d2b4）。CI run 37925446699 su… |
| 10-09 20:21 | 子代理修复（7275bbd4）真机验证完成（2026-10-09 晚，构建 v1.0.0+1962 @20:16:52 装机） | A（frontmatter 全链路）✅：file_write 带 subagent:true 的 SKILL.md → auto-discover（20:18:21.835，与事故同时序）→ spawn_agent 直接… |
| 10-10 14:54 | 记忆系统全面更新（2026-10-10 下午） | ① 开发档案三件套：重建 1605 条（08-03～10-10），清洗 + SAGAS 重建（24 saga / 148 orphans / multi4=88），结构核验 anchors==header==1605、f… |
| 10-10 14:57 | CI 触发判据修正：记特例会漏，要记判据（2026-10-10，用户同日第二次纠正） | 事：assets/skills/semantic-memory 回灌（.py/.md/.json 三个纯文本文件）后，我惯性 dispatch 了 build-apk → run #1964，用户纠正「文本更新不需要触发… |
| 10-10 15:01 | 两分支合并进 main = 81be7f62（2026-10-10，用户放行「分支的话就合并吧，毕竟只是文本更新」） | 合并：--no-ff ×2 序贯合入 main。①docs/dev-history-1010 @ 3c483aee → 23d4b2e0（4 文件 +961/−48，全部 docs/dev-history/）；②fix/… |
| 10-10 15:24 | MCP 知识图谱退役（2026-10-10，用户拍板「2」= 退役） | 决策：退役 KG（@modelcontextprotocol/server-memory），不复活。理由三条（合起来构成，单条不够）：①写路径从未打通（09-28 无持久化 + daemon 不传 env 给 STDIO… |
| 10-10 15:26 | GLOBAL 记忆架构章节 + 远端旧分支清理（2026-10-10，用户令「加进去，并且把云端的那个删掉吧」） | ① GLOBAL.md 新增「## 记忆架构（六层 + 两条辅助）—— 2026-10-10 定案」（用户明确授权编辑，插在「待办登记纪律」节后）。内容 = 六层表格（层 / 位置 / 记什么 / 谁来读·何时）：日报 … |
| 10-10 15:28 | 纯文本分支可直接合并——写入 GLOBAL 常驻纪律（2026-10-10 用户拍板「合，并把它记忆进全局记忆」） | 合并：chore/retire-kg-skill-note-1010 @ 38e905a1 → main 8cb19750（1 文件 +18/−14，纯 SKILL.md 文本）。核查：无冲突 · 合并树 vs 分支树 … |
| 10-13 12:58 | 用户偏好：「子代理」默认指会话子代理（2026-10-13，用户明确） | 用户明确：说「子代理 / sub-agent」时默认指会话子代理 = minis-sessions-cli send（跨会话派发 headless 会话），不是进程内 spawn_agent。 |
| 10-13 13:12 | sweep-1013 收口：第二道门核实 + 三修复会话派发（2026-10-13） | 输入：三域审计报告齐（D1 数据层 10 条 / D2 安全面 11 条 / D3 UI 10 条，共 31 条），报告基线 main @ ad4a4beb。 |
| 10-14 15:38 | FIX-R6 完成：MCP 温热握手 + server_name 白名单 + k=v JSON 字面量（fix/swee | FIX-R6 完成：MCP 温热握手 + server_name 白名单 + k=v JSON 字面量（fix/sweep1014-mcp-http @ 8b4bfe1，CI #2018 绿） |
| 10-14 15:43 | FIX-R4 完成：Anthropic mid-tool-call 丢调用 B11 + ThinkTagScanning 围栏反转 B14a + SkillRepository frontmatter B14b | 用户令「读 /var/minis/shared/sweep-1014/tasks/FIX-R4.md 并立刻执行」。基线 main d5d2be59f，分支 fix/sweep1014-providers @ 11087… |
| 10-14 17:17 | FIX-R22 完成：skills-ui B32 更新路径 IO+zip parity/真实 id + D12 WebV | FIX-R22 完成：skills-ui B32 更新路径 IO+zip parity/真实 id + D12 WebView onRelease（fix/sweep1014-skills-ui @ 399a2d76，C… |
| 10-14 21:34 | FIX-RV-E 完成：E1 API33 readNBytes 崩溃 + E2 技能更新 id + E4 视频高度 no | FIX-RV-E 完成：E1 API33 readNBytes 崩溃 + E2 技能更新 id + E4 视频高度 no-op（fix/rv1014-ui-small @ dff1ddf，CI #2083/3857062… |
| 10-14 21:36 | ↳ FIX-RV-D 完成：D1 会话克隆 media 重映射 + D2 KeyRoulette 存量明文迁移（fix/rv | FIX-RV-D 完成：D1 会话克隆 media 重映射 + D2 KeyRoulette 存量明文迁移（fix/rv1014-fork-keys @ e39c66b，CI #2084 绿） |
| 10-14 22:30 | RV-1014 真机验证完成（用户令「这个应用包含所有修改，开始验证」）—— 抓到一条系统性交付缺口 | 身份链（先钉死验证对象）：设备包 1.0.0+2094（装于 22:12:53）sha256 d15ea964…6604 = CI run #2094（38575026136，head_sha a7123cf82，8 支… |
| 10-14 23:10 | 根治 §97：内置技能资产落盘改内容哈希门控（fix/skill-asset-hash-gate @ 87db4a3，CI #2095 绿，停在分支未合并） | 用户令：选「根治」（二选一：即时 bump 版本 / 根治改内容哈希）。基线 main a7123cf82，3 文件 +351/−34。 |
| 10-14 23:19 | §97 收口：fix/skill-asset-hash-gate 合并 main 15a1f1336（用户令「没有问题就 | §97 收口：fix/skill-asset-hash-gate 合并 main 15a1f1336（用户令「没有问题就合并」+「合并触发之后就不用等了」） |
| 10-15 00:11 | §97 内容哈希门控在真机未生效——根因定性：DB 行来源不是 bundled（原本就有，非本次改出） | 验证对象：设备包 1.0.0+2095（lastUpdate 23:21:46）= CI run #2095 = fix/skill-asset-hash-gate @ 87db4a333（run #2096 = mai… |
| 10-15 00:42 | §97 根治二段合并收口：fix/bundled-row-adoption-1015 → main 4950a334（用户令「检查云端正在跑的分支，没有问题的话就合并」） | 核查对象：run #2097 = fix/bundled-row-adoption-1015 @ 5da7153f7（workflow_dispatch），核查时 in_progress、机械项阶段已 completed… |
| 10-15 06:13 | §99 全仓审计第二道门核实 + 4 会话派发（2026-10-15） | 输入：用户丢来全仓审计报告（shared-e8a7c3f5.md，8 BUG + 5 ARCH + 6 PERF）令「检查核实」→ 核实后「工程量大的话用会话子代理安排出去」。 |
| 10-15 06:33 | FIX-B 完成：技能/备份恢复修复（fix/audit-1015-skillbackup @ 6645cce，CI #2099 绿，停在分支未合并） | 任务：shared/audit-1015/fix-tasks/FIX-B.md；报告 shared/audit-1015/fix-reports/FIX-B.md；工作树 shared/work/fix-b-1015/r… |
| 10-15 12:04 | 用户偏好：「子代理」默认指会话子代理（已固化进 GLOBAL.md） | 用户明确：说「子代理 / sub-agent / 派发任务」默认指会话子代理 = minis-sessions-cli send（跨会话 headless 派发），不是进程内 spawn_agent。派发用 --prom… |
| 10-15 15:03 | verify-1015 修复批次收口：P0/P1 全部 8 条，分支 CI #2112 绿（2026-10-15） | 任务：核实报告（shared/work/verify-audit-1015/VERIFY-REPORT.md）P0/P1 8 条 → 分支 fix/audit-verify-1015（基线 main 6fe4771）3 … |
| 10-15 15:59 | audit2 修复批次派发（2026-10-15，用户令「按你的建议来吧」） | 六个会话子代理（minis-sessions-cli send --async，全部新会话，模型=羊毛组）： |
| 10-15 16:41 | FIX-A5 完成：MCP 守护进程三处（per-name 锁 / 请求上限 / 引号 args）（2026-10-15） | 用户令：「读 FIX-A5.md 并立刻执行」（子任务派发）。基线 main 891d755，分支 fix/audit2-mcpd-1015 @ 305d20cf2d45945975fc24736bf2d48f78036… |
| 10-16 13:15 | FIX-C 收口：deep-audit-1017 ISSUE-04（子代理回合交还契约）—— fix/da1017-subagent-residue @ bf124e8d，CI #2167 绿，停在分支 | 任务：读 shared/work/deep-audit-1017/fix-tasks/FIX-C.md 立刻执行（基线 main e748b14，浅克隆）。报告 fix-reports/FIX-C.md（189 行）；装… |
| 10-16 23:22 | FIX-A 收口：deep-audit-1018 ISSUE-01（P0 截断工具调用）+ ISSUE-02（P1 子代理 reasoning 丢失）+ ISSUE-12c（P2 叠楼守卫）—— fix/da1018-turn-integrity @ 00026c3 两提交，CI #2195 绿，停在分支 | 任务：读 shared/work/deep-audit-1018/fix-tasks/FIX-A.md（先证再改）。报告 fix-reports/FIX-A.md；装置 repo-a/probe/（durable，含 c… |
| 10-17 10:49 | FIX-G 收口：deep-audit-1020 ISSUE-07（memory_rollup 门禁 + provider UA 字段）—— fix/da1020-memory-gate-provider-fields @ fd3acf3，CI #2220 绿，停在分支 | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-G.md（先读 COMMON/TRIAGE，基线 main 942b0111d）。报告 fix-reports/FIX-G.m… |
| 10-17 13:19 | FIX-E 收口：deep-audit-1021 ISSUE-06（SkillFileViewerScreen 保存丢返回值 + 主线程写盘，P1）—— fix/da1021-skill-file-save @ `dc3d30a`，CI #2224 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-E.md 并执行（先证再改 / 分支隔离 / 自建工作树 repo-e）。基线 main d0c1c9… |
| 10-17 13:32 | FIX-A 收口：deep-audit-1021 ISSUE-01（技能恢复 id 贯通）+ ISSUE-10（会话覆盖清理闭环）—— fix/da1021-skill-restore-session-cleanup @ 433430f，CI #2229 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-A.md 立刻执行。报告 fix-reports/FIX-A.md（8 节）；装置 repo-a/pr… |
| 10-17 18:15 | 审计提示词 v2（原提示词按九轮实测改进）— 2026-10-17 | 文件：/var/minis/mounts/笔记/RikkaMinis审计背景包/audit-prompt-v2.md（13.2 KB）。原提示词由用户提供（角色 = 首席架构师 + 安全/性能审计专家；四节：任务边界/求… |
| 10-17 18:27 | ↳ deep-audit-1022 合并 main + 文档归档完成（用户令「合并吧，不要忘了更新那个文档」） | 合并：integration/da1022（@ 8128896945fc）--no-ff 合入 main → 215daf0bf548db1972d94ac58d54e5b4596d625c，合并后 tree = cec… |
| 10-17 18:34 | deep-audit-1022 真机验证（设备包 1.0.0+2242，用户令「开始检查验证」） | 身份链三源闭合：设备 base.apk sha256 5926c64b8212…9e971（14,378,645 B）== CI #2242（run id 39021965840，head_sha 8128896945f… |
| 10-17 18:36 | ↳ da1022 合并后收尾：全量清分支（22 个）+ 浅克隆误判修补（用户令「继续推进吧」，2026-10-17） | 1. 远端分支全量清理 → 只剩 main（22 个已删）： |
| 10-17 21:06 | deep-audit-1023 FIX-E 收口：SKILL.md frontmatter 写路径完整性（fix/da1023-skill-frontmatter-parity @ a02a445d，CI #2252 success，停在分支） | 修法三件（+同族新发现）：①极性单一出口 resolveMergeBase(newFrontmatter, diskContent) 内存新值优先（writeSkillMd 原为 existing ?: skill.fr… |
| 10-17 22:05 | 审计背景包 v6 + 提示词 v3 更新（为下一波审计准备，2026-10-17 22:0x） | 位置：/var/minis/mounts/笔记/RikkaMinis审计背景包/（挂载盘，用户笔记）。三份文件：audit-context.md（v6，45 KB）· audit-prompt-v3.md（v3，18 K… |
| 10-17 23:51 | deep-audit-1024 第二道门核实 + 9 支派发（用户令「走流程吧」，附新审计报告 9 项 v3） | 输入：REPORT-1024.md（1414 行，ISSUE-01..09）。基线 main f688c97（= da1023 收口树）。工作区 shared/work/deep-audit-1024/（本地克隆自 10… |
| 10-18 00:33 | 交付：deep-audit-1024 FIX-D（ISSUE-04 MCP OAuth 凭据/沙盒桥接文件生命周期，P1）—— `fix/da1024-mcp-oauth-bridge-lifecycle` @ `b611732a`，CI #39048727885 success，停在分支 | 任务：读 shared/work/deep-audit-1024/fix-tasks/FIX-D.md 立刻执行（先证再改）。基线 main f688c97（da1023 收口树）。报告 fix-reports/FIX-… |
| 10-18 01:51 | RikkaMinis 审计背景包更新（v7 / prompt v4）+ deep-audit-1024 收口核验 | 用户指令原话：「为下一波的那个审计做准备吧，该更新的更新，该优化的优化，该改的改。你自己的判断。」随后明确：「在笔记文件夹挂载的那个文件夹里面 RikkaMinis审计背景包」——要更新的是发给审计模型的背景包与提示词（… |
| 10-18 09:03 | deep-audit-1025 第二道门 + 9 支派发（用户令「走流程」，附审计报告 v4 9 项） | 输入：shared-45079a1b.md（135,630 B / 1874 行，ISSUE-01..09）。基线 main bcb6569（1024 收口树）。工作区 shared/work/deep-audit-10… |
| 10-18 11:27 | 审计背景包升级 v8 + prompt v5（用户令：为下一波外发审查准备背景文件与提示词） | 位置：/var/minis/mounts/笔记/RikkaMinis审计背景包/（该目录即审计发包目录，另有 /var/minis/mounts/笔记/RikkaMinis审计全记录/）。交付面 = GitHub 仓库源… |
| 10-18 18:13 | 下一批审计准备：背景包 v9 + 提示词 v6 + 三项提案评估（2026-10-18） | 用户令：为下一批审计做准备（挂载文件夹里的背景文件 + 提示词，该改的改）；检查本地与云端 dev-history 是否更新，没有则更新；并评估三项提案（UI 动作差分 / Set-of-Marks / 沙箱快照回滚）是… |
| 10-18 18:24 | 「秩序/熵」框架的落点判断：一次实测 + 三根候选轴（登记不建装置）+ 归口纪律（2026-10-18） | 用户给的框架（结构秩序=依赖图无环/分层/主序列；信息秩序=可压缩性/每功能增量/重复率；行为秩序=可预测性/测试即约束行为空间；再生性=变更失败率/爆炸半径/DORA），问「之前修改的是否要因此加东西 / 有什么启发」… |
| 10-19 03:13 | 审计背景包 v11/v8 升级完成（用户令「为下一步的审计做准备：背景文件+提示词挂到文件夹子文件夹」） | 交付位置：/var/minis/mounts/笔记/RikkaMinis审计背景包/（= 挂载「笔记」的子文件夹） |
| 10-19 03:35 | ↳ deep-audit-1028 真机验证（部分闭环，余项攒波统一验） | 装机：v1.0.0+2310（versionCode 220002310）@ 03:16 == main CI #2310 @ 83caac6 产物；行为级旁证 = background.overlay 可读（旧包必报 … |
| 10-19 07:41 | deep-audit-1029 FIX-B 收口：ISSUE-03（记忆编辑屏主线程同步 IO + SOUL 缓存回流）—— `fix/da1029-b-memory-io` @ `98c0fc5`，CI #39176155210 绿，停在分支 | 身份链：基线 main 83caac6 → 单提交 98c0fc59e2cbe307b5e539853dc2a2608b4988e8（3 文件 +254/−26）→ branch dispatch CI 39176155… |
| 10-19 09:34 | 审计背景包升级 v11/v8 → **v12/v9**（用户令「为下一波审计做准备」+ 背景文件）：基线 `b353317`，两处引用失真被回树取证抓住当场修正 | 位置：挂载 /var/minis/mounts/笔记/RikkaMinis审计背景包/（发给外部审计模型，非本机会话）。终态根目录 = README.md + audit-context.md(v12, 521 行 / … |
| 10-19 17:56 | deep-audit-1031 收口完成（用户令「合并并整理」）：main `09b6c123` + 文档 2006 条 + 分支清零 | 身份链终态：基线 main 2bc07ec → 4 支 → 并集树 integration/da1031 @ 31206d41e80a（tree 8eb668ff832169b4261e30dc4d58b30bc7cdd… |
| 10-19 19:54 | FIX-E 收口（deep-audit-1032 ISSUE-06 P1）：设置屏首屏 reload 线程 + 技能编辑器 remember 键 —— `fix/da1032-e-settings-io-keys` @ `a2106ab0`，CI #39245665334 绿，停在分支 | 身份链三方逐字符：基线 09b6c12 → 两提交（ad733d56 主体 + a2106ab0 告警清理）→ CI run 39245665334 / job 118036843575 success，local ==… |
| 10-20 00:58 | FIX-A 收口（deep-audit-1033 ISSUE-01 + 05 + 08）：`fix/da1033-a-ro-guard-mainio` @ `7b1b1722`（两提交），CI #2353 绿，停在分支 | 身份链：基线 c6d426a7 → a99827d5（主体）→ 7b1b1722（修 CI 编译事故） → CI run 39284407398（#2353）success；三方逐字符一致。9 文件 +602/−82（7… |
| 10-20 01:47 | 审计背景包 v16 + 提问模板 v13-A/B（首次一分为二）—— 2026-10-20 凌晨，用户令「更新背景资料+提示词，准备两份给两个会话」 | 位置：mounts/笔记/RikkaMinis审计背景包/。基线换代 c6d426a → b7acf94a（da1033 五支 + docs 已并入 main；src/ 与 integration/da1033 @ aa… |
| 10-20 08:29 | FIX-B3 收口（deep-audit-1034 B-05+B-06 两支）：列表块 mathSpans 接线 + 技能面板增量 merge —— `fix/da1034-b3-markdown-sheets` @ `b906313`，CI #2359 绿，停在分支 | 身份链三方逐字符：基线 b7acf94a → 4 提交（9b87adf markdown · 5032ebc sheet · 38fc024 门 · b906313 测试）→ HEAD b906313ad3a32312e… |
| 10-20 09:06 | FIX-A1 收口（deep-audit-1034 ISSUE-01 P0）：备份 zip 不再带走 MCP OAuth 明文桥 —— `fix/da1034-a1-backup-creds` @ `603d297c`，CI #2365 绿，停在分支 | 身份链三方逐字符：基线 main b7acf94a → 19b1c3e（生产+测试）/ 603d297（门扩展）→ 本地==远端==CI head_sha；CI #2365（run 39349095475）success… |
| 10-20 12:59 | FIX-B5 收口（deep-audit-v14 ISSUE-06 P1）：`fix/dv14-b5-skill-offmain` @ `db90b8f6`，CI #2374 success，停在分支 | 身份链三方逐字符：基线 main 76488f2d → 3 提交（35cf999 生产+出货测试 · e7221d5 owner 门扩展 · db90b8f fix-forward）→ 远端 ls-remote 同 sh… |
| 10-20 16:16 | 背景包 v18 + 提问模板 v15-A/B（第三次双报告复盘后：新增「自补材料纪律」）—— 2026-10-20 傍晚 | 触发：用户「又消化了一轮，为下一轮做准备吧」+「根据已有信息自行调整」。上一轮 = deep-audit-v14（1035 波）：REPORT-A 8 + REPORT-B 8 → 第二道门 16/16 成立、0 驳回 … |
| 10-21 00:59 | FIX-2 收口（deep-audit-1037 v16 波，A-03+A-04+A-05）：`fix/dv16-a-debug-modeluse-skill` @ `fe03ecb5`，CI #2411 ALL GREEN，停在分支 | 身份链三方逐字符：基线 main 7c38a79d → 4 提交（2b9309ff 生产 / 0d60f3e4 测试 / 79251553 门 / fe03ecb5 CI 编译修复）→ 本地 == ls-remote =… |
| 10-21 09:24 | deep-audit-1038（v17 波 / 面 B）启动：第二道门 8/8 成立（P1×6 · P2×2）+ 8 工单合并为 5 支派发 | 输入：附件 audit_report_v17_b.md（92,529 B / 540 行，面 B：UI·交互·呈现，B-01..B-08）。面 A 报告未到（用户「先把这一份处理了吧」）。基线 main 528558f4… |
| 10-21 10:45 | FIX-4 收口（deep-audit-1038 / dv17 波 B-04+B-05）：`fix/dv17-4-remember-keys` @ `58e71b2c`，CI #2418 绿，停在分支 | 身份链三方逐字符：基线 main 528558f4 → 3 提交（e7b8a7eb 生产 / 6684ca55 测试 re-anchor / 58e71b2c 门扩展）→ 本地 == git ls-remote == C… |
| 10-22 03:03 | FIX-B2 收口（deep-audit-1040 / dv20 波 B-04）：MCP 屏与会话 sheet 的仓/Keystore 调用回主线程 —— `fix/dv20-b2-mcp-offmain` @ `9a97895f`，CI #39645696095 success，停在分支 | 身份链三方逐字符：基线 main 295cf778 → 3 提交（34855a24 生产 / fcb54989 测试 / 9a97895f 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 19:16 | FIX-B6 收口（deep-audit-1042 / dv22 波 B-07 P1）：`fix/dv22-b6-skills-browser-update` @ `7ff856d6`，CI #39764477753 success，停在分支 | 身份链三方逐字符：基线 main 5dd278da → 3 提交（1d9d5558 生产 / 0bd4d7b4 测试 / 7ff856d6 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 20:45 | 审计背景包 v25 / 提示词 v22-A,B 构建完成（为 deep-audit-1043 / v22 波准备）—— 2026-10-22 晚 | 触发：用户「这一批消化完了，为下一批做准备吧……笔记子文件夹里的背景文件」。接手时发现 audit-pack-v25/ 已有半成品 stage1.py（20:32 另一会话留下，未跑、锚点失效），其余 stage2-6/… |
| 10-23 06:41 | 审计背景包 v26 / 提示词 v23-A,B 构建完成（为 deep-audit-1044 准备）—— 2026-10-23 晨 | 触发：用户「好了，吸收完了，为下一波做准备吧」（承接 v25 那轮的「为下一批做准备」）。关键前提：1043 那轮已被另一会话合并进 main —— f94413f5（bbd43596 dv23 并集 + eee9fce… |
| 10-23 12:45 | 审计背景包 v27 / 提示词 v24-A,B 构建完成（为 deep-audit-1045 / v24 波准备）—— 2026-10-23 午后 | 触发：用户「好了，现在已经合并进去了」（dv24 十支已并入 main）。基线核实：main 1d44ec90（= dv24 并集 f842dd13 + docs-1044；两树 src/+scripts/ 逐字节一致，… |
| 10-23 15:15 | FIX-1 收口（deep-audit-1045 / v24 波 A-01 P1）：MCP OAuth 配置读侧不认客机 CLI 的 camelCase — `fix/dv25-a1-mcp-oauth-keycase` @ `46b059be`，CI #2535 success，停在分支 | 身份链三方逐字符：基线 main 1d44ec90 → 3 提交（6bb83a20 生产 / 143a88be 测试 / 46b059be 门）；无报告提交（本波 FIX-1..11 与上波 FIX-1..10 重号，入… |
| 10-23 15:42 | FIX-10 收口（deep-audit-1045 / v24 波 B-06 P1）：`fix/dv25-b6-mcps-merge-guard` @ `4b083c74`，CI #2543 success，停在分支 | 身份链：基线 main 1d44ec90 → 4 提交（bd574457 生产 / 0bbda65e 测试 / ea800a0a 门 / 4b083c74 报告）。CI 绿于 ea800a0a，HEAD 4b083c74… |
| 10-24 05:39 | FIX-A3 收口（deep-audit-1047 / A-05+A-06）：`fix/dv27-a3-mcp-secret-handoff` @ `45b14121`，CI #40019485653 success，停在分支 | 缺陷（A-05）：客侧 CLI 写不了加密存储，只能把 MCP 客户端密钥落 <server>.secret 等宿主导入；全仓只有编辑弹窗一处调 importPendingClientSecret ⇒ 另两个读者（rec… |
| 10-24 10:12 | FIX-A5 收口（deep-audit-1048 / A-05 P1）：Stage 9 覆盖 Stage 7 的 MCP 合并结果 — `fix/dv28-a5-mcp-restore-stage9` @ `8122ca84`，CI #40051341292 success，停在分支 | 缺陷：导出照打 mcp/servers.json；Stage 7 用 mcpRepo.importJSON( 合并（本机独有服务器保留、save() 落盘）；Stage 9 解压时 destByPrefix["mcp"]… |
| 10-24 20:47 | 元审计第一波改版：改为「提示词 + 逐文件材料」，并利用 agent 工具能力（2026-10-24 晚） | 用户两次纠正形式：①之前的形式是「各种材料 + 一条提示词 → 它自己吐报告」，我首版给的 7 个文件（含 388KB 合订本）形式不对；②逐文件直接发比 zip 好；③官方应用是 agent 模式、能调工具（可联网读公… |
| 10-24 20:59 | 元审计收口改成「拖报告即接手」——落地为新技能 meta-audit-witness（2026-10-24 晚，用户纠正） | 用户纠正：上一环的理想形态是「报告出来 → 我把两份报告发给新对话 → 它自然知道按流程走」。不需要路径、不需要交接文件、不需要额外指令。我先前那套「读 HANDOFF.md」的方案是错的形式。 |
| 10-24 23:57 | fastio 不被调用诊断 + 提示词修复（fix/prompt-fastio-rg @ 27c951db，CI #2628 绿，停分支） | 用户问题：AI 不主动用 minis-fastio，总用慢的原生方式（场景：清理修过程积累的数据）。 |
| 10-25 00:34 | 发送形态改为「提示词在外 + 材料整包在内」（2026-10-25，用户要求） | 用户原话：「复制提示词，然后粘贴进去，再在那里打开需要发给他的那些文件，全选之后全部发给他的……提示词可以放在外面，而要发过去的文件可以放到对应的那个文件夹里，我直接点进对应的文件夹，全选，发过去就可以了」。之前 wid… |
| 10-25 06:12 | wide-1024 支 07（mcp-tools 三门）收口：CI 绿，停分支 — 2026-10-25 | 分支 fix/meta-wide-07-mcp-tools @ d1a90931（判据+臂）→ 49552874（报告回填，源码树等价 0 diff）。基线 564c8f3e。CI run 40135996652（#26… |
| 10-25 06:36 | wide-1024 支 10（4 门 12 见证）收口：分支 `fix/meta-wide-10-skill-backup-compact` @ `96ad4ab0`，CI #2643 / run `40137834021` success（head_sha 逐字符一致） | 12/12 见证反攻通过（修复前 rc=0 → 修复后 rc=1，restore=True 全部 sha256 一致）： |
| 10-25 12:40 | META-WIDE3 支 08 收口：skill/backup 四门判据加固，12/12 原见证反攻 CAUGHT，分支 CI #2653 绿（2026-10-25） | 分支 fix/meta-wide3-08-skill-backup（基线 main 6b6c918f）→ 892474eb（门）+ d3f0bfb6（报告）+ 53cd28fb（CI 回填），已推远端，停分支不合并 ma… |
| 10-25 18:06 | wide4 支 12 收口：four_way + skill_import 两门判据加固（分支 `fix/wide4-12-fourway-skillimport`，CI #2671 绿，停分支） | 基线 19e70dab（worktree wt-12，基分支含共享 _kotlin_lexer/_kotlin_ast）。改动面：2 个门脚本（+1080/−166）+ 1 份报告；生产代码 0 行、scan.sh/te… |
| 10-25 21:30 | 第六批（第二轮 · 24 门）材料已备好（基线 main 6ac77c6b2，与 wide5 施工正交可并行） | 产物：/var/minis/mounts/背景包/发送-A\|发送-B/（00 = spec_v8 / 01 索引 24.2KB·20.5KB 含已挡清单 91·41 条臂 / 02 diff 56.9KB·233B / … |
| 10-26 01:09 | B5-t11 收口：mcp_oauth / envvar_group 两门 12 条见证效果化（分支 fix/b5-mcpoauth-envvar @ 716d14f15，CI #2693 绿） | 基线 e145ec961（= main 6ac77c6b2 + 共享原语 _kotlin_valueflow）。门提交 557c5b57f（两文件 +739/−461，生产 .kt 0 行，scan.sh/test_sc… |
| 10-26 10:14 | B6-t12 收口：skill_frontmatter / offload_b64 / subagent_handback 三门 18 条见证全翻红（分支 `fix/b6-skillfm-b64-handback` @ `914e8f05d`，CI #2706 绿，停分支） | 基线 base/batch6-shared @ e145ec961。门提交 64c4f46ba（3 文件 +1157/−560，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门… |
| 10-26 11:19 | B7-t02 收口：skill_restore_session_cleanup + speech_active_engine 两门 12 条见证全红（分支 `fix/b7-skillrestore-speech` @ `8b865532e`，CI #2708 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8b865532e（2 文件 +1124/−720，生产 .k… |
| 10-26 14:53 | 第三轮 B 波（收口批 · 19 门）材料已备 —— 50/50 有缝门全覆盖（2026-10-26 14:55） | 回答用户的「这是最后一批了吧？」：是。109 门里 50 门有缝（出现在共享生产文件里）、59 门孤立（无共享文件，打第三轮只能门内变异=第二轮重复，按判据不打）。第三轮 A/B 波 32 门已发 + 本批 19 门 =… |
| 10-26 19:14 | R3-t08 收口：parts_json_funnel + skill_restore 两门 6 条见证全 CAUGHT（分支 `fix/r3-partsjs-skillrestore` @ `a924214c2`，门提交 `1c3f9c497`，CI #2731 绿，停分支） | 基线 main @ f0f14d46d。改动面 = 2 个门脚本（+693/−13）+ 报告；生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰，门 112 / 夹具 344 不变。 |
| 10-26 19:49 | R3-t18 收口：backup_remap_rollup / mcp_oauth_lifecycle / backup_streaming_export 三门 9 条见证全 CAUGHT（分支 `fix/r3-backup-mcp` @ `335faf435`，CI #2741 绿，停分支） | 基线 main f0f14d46d。改动面 = 3 个门脚本（+928/−43），生产 .kt 0 行、scan.sh/test_scan.py/共享原语零触碰、门 112 / 夹具 344 不变。绿 sha 9c17f… |
| 10-26 20:13 | R3-t19 收口：skill_file_save_result + skill_import_offmain 两门补 6 条 round-3 缝级判据（分支 `fix/r3-skillfilesave` @ `009916a26`，门提交 `f686031b5`，CI #2747 绿，停分支未合并） | 基线 main f0f14d46d。改动面 = 2 个门脚本（+808/−65），生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 112 / 夹具 344 不变。 |

## 23. 语音 / 多模态输入

**跨度** 2026-08-03 ～ 2026-10-26 · **58 条** · **状态** 低优先级维护（语音输入可用，未再重点投入）

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
| 09-30 13:42 | OpenMinis 上游调研（09-30，用户「看看有什么能抄的」） | 上游仓库事实：OpenMinis/OpenMinis = Minis 官方开源镜像（GPL-3.0，Swift+Kotlin 双端，4.8k star）。公开历史只有 53 个 squashed commit（v1.10… |
| 10-08 19:23 | T-edit-resend-attachment-loss-1008（长按编辑重发丢附件） | 现象：用户带附件发消息 → 长按气泡「重新编辑」→ 改完重发 → 指令在、附件消失。 |
| 10-09 00:42 | fix-sweep-a-1008 修复收口（sweep-1008 附件契约族 P1 五项全修） | 任务：/var/minis/shared/sweep-1008/fix-tasks/A.md。分支 fix/sweep-p1-attach-1008 @ 77cf817e95ca29370aa5177174f9835c8… |
| 10-14 09:02 | M3 主题线二轮：工程量分级 + 视觉模拟交付 + 用户倾向不跟上游（2026-10-14） | 用户三问：各层工程量 / 上游修改（倾向不跟）/ 改动效果。 |
| 10-14 15:37 | 验证：JVM 13/13 绿（两纯类 CaptureEpochGuard/TtsDrainTracker 从生产文件 v | 验证：JVM 13/13 绿（两纯类 CaptureEpochGuard/TtsDrainTracker 从生产文件 verbatim 抽取 + in 匹配复核）；变异负控双条（MUT-A releaseIfCurren… |
| 10-14 21:25 | FIX-BACKUP-MEDIA 完成：D2D 换机保留附件 + 两处注释改准（fix/backup-media-d2d | FIX-BACKUP-MEDIA 完成：D2D 换机保留附件 + 两处注释改准（fix/backup-media-d2d-1014 @ 005e5942，CI #2079 绿） |
| 10-14 21:42 | FIX-RV-F 完成：CaptureEpochGuard 单 CAS 槽修复（fix/rv1014-speech @ a28559bb，CI #2085 绿） | 修复：ProviderSpeechRecognitionEngine.kt CaptureEpochGuard——flag+epoch 合并进单个 AtomicReference<Session?>（Session 持 … |
| 10-15 13:27 | 全仓审计报告第二道门核实（2026-10-15，附件 shared-04273e16.md） | 基线：main @ 6fe4771；装置 /var/minis/shared/work/verify-audit-1015/repo（分支 verify）；报告 shared/work/verify-audit-1015… |
| 10-15 15:54 | 第二道门核实：全仓审计报告（附件 25 项）→ VERIFY-REPORT.md（2026-10-15） | 用户令：「按照老流程来」+ 附件 rikkaminis_full_repository_audit_report.md（64.5KB，BUG×10 + DESIGN×8 + PERF×7 = 25 项）。基线 main … |
| 10-15 16:30 | FIX-A6 完成：图片解码三处全尺寸位图 + 缩放算术出 0 尺寸（audit2-PERF-03，2026-10-15） | 用户令：「读这个文件并立刻执行：/var/minis/shared/work/verify-audit2-1015/fix-tasks/FIX-A6.md」。 |
| 10-15 21:52 | FIX-G 收口：NodeRegistry 摊销驱逐 + 图片解码有界化（fix/audit3-perf-small-1015 @ 993fed7e，CI #2134 绿，停在分支） | 任务：读 shared/work/audit3-1015/fix-tasks/FIX-G.md 立刻执行（audit3 F-12/F-13）。基线 main ca8b95d17，单提交 993fed7e，6 文件 +80… |
| 10-16 13:31 | FIX-G 收口：deep-audit-1017 ISSUE-10（a11y 截图/点击 + speech，P2 4 子项）—— fix/da1017-a11y-speech @ a304097，CI #2171 绿，停在分支 | 任务书 shared/work/deep-audit-1017/fix-tasks/FIX-G.md（基线 main e748b14）；报告 fix-reports/FIX-G.md；装置 fix-g/probe/（pr… |
| 10-16 18:04 | FIX-E 收口：deep-audit-1016c ISSUE-07（TTS --voice 失效 + VoiceTextSanitizer 零接线，P1）—— fix/da1016-tts-voice @ 8f6b5fb，CI #2180 绿，停在分支 | 任务书 shared/work/deep-audit-1016c/fix-tasks/FIX-E.md；报告 fix-reports/FIX-E.md；装置 probe/jvm/；背板 backlog §136。基线 m… |
| 10-17 06:24 | deep-audit-1019 第一道门完成 + 10 支派发（用户令「按流程来吧」，附件 shared-28e12c77.md，2026-10-17 06:1x） | 输入：用户附件 AUDIT-REPORT.md（176KB / 2517 行 / 10 工单 ISSUE-01..10）。基线 main e416551（= da1017 七支 + da1018 八支 + dev-his… |
| 10-17 10:44 | FIX-E 收口：da1020 ISSUE-05 语音链路四块（fix/da1020-voice-cancel-ua @ 97102a49，CI #2218 绿，停在分支） | 任务：读 shared/work/deep-audit-1020/fix-tasks/FIX-E.md（先读 COMMON/TRIAGE）。报告 fix-reports/FIX-E.md；装置 repo-e/repo/p… |
| 10-17 13:41 | FIX-G 收口：deep-audit-1021 ISSUE-08（SpeechRecognitionManager 录音中切引擎 → 在途引擎失控）—— fix/da1021-speech-active-engine @ 6b9e39e，CI #2231 绿，停在分支 | 任务：读 shared/work/deep-audit-1021/fix-tasks/COMMON.md + FIX-G.md 并执行（先证再改、分支隔离、自建 repo-g）。报告 fix-reports/FIX-G.… |
| 10-19 07:48 | FIX-D 收口：deep-audit-1029 ISSUE-05（语音族 UA 缺口）—— `fix/da1029-d-voice-ua` @ `4c7ad80`，CI #39176720024 绿，停在分支 | 身份链：基线 main 83caac6 → 单提交 19ceb02（3 生产 .kt +204/−2 + 测试 +204）→ 门扩展 4c7ad80（provider_ua_parity_check.py +261）→ … |
| 10-19 23:48 | FIX-C 收口（deep-audit-1033 ISSUE-03 P1）：android-speech --source 相对路径 —— `fix/da1033-c-speech-cwd` @ `d3037a76`，CI #2348 绿，停在分支 | 身份链三方逐字符：基线 main c6d426a7 → 2 提交（生产 + test/门）→ CI run 39275575813（#2348）success @ 2026-10-19T15:10:26Z→15:28:0… |
| 10-20 15:47 | §VERIFY-2388 真机验证收口（CLI/深链面全绿 + S34 新缺陷入册 + UI 视觉面交用户） | 身份：app 1.0.0+2388（15:11 装 = main CI #2388，含 dv14 九支并集 71882a8e）。工作区 shared/work/verify-2388/，回执已入 backlog（267→… |
| 10-21 20:12 | FIX-A3 收口（deep-audit-1039 / dv19 波 A-05+A-06）：`fix/dv19-a3-voice-url-and-cancel` @ `0b699b18`，CI #2438 success，停在分支 | 身份链三方逐字符：基线 main 6bbbded2 → 3 提交（40dfabeb 生产 / c3afd83c 测试 / 0b699b18 门）→ 本地 == git ls-remote == CI head_sha =… |
| 10-22 03:20 | FIX-A1 收口（deep-audit-1040 / dv20 波 A-01 P0 + A-02 P1）：`fix/dv20-a1-backup-voice` @ `b0640ad1`，CI #2449 绿，停在分支 | 身份链三方逐字符一致：基线 main 295cf778 → 7 提交 → 本地 == git ls-remote == CI head_sha = b0640ad1716018b130a24b6af4aebc714231… |
| 10-23 11:14 | deep-audit-1044 FIX-5 收口（A-06 语音错误码表错位 + A-07 TTS 按次默认值）：分支 CI 绿，停在分支 | 分支 fix/dv24-a5-speech-error-tts-defaults @ a816c23ae12ced1d6110a6a1938c1c500d63dc98（3 提交：生产 0f5bacd1 / 测试 d7a5… |
| 10-23 11:15 | FIX-1 收口（deep-audit-1044 / A-01 P1）：`fix/dv24-a1-voice-effective-model` @ `8c27fd25`（CI 绿于 `fe6ab36e`），run 39891898960 / job 120213372981 success，停在分支 | 身份链四方逐字符：基线 main f94413f5 → 3 提交（生产 4bce8f71 / 测试 67ed74b8 / 门 fe6ab36e）+ 2 个 docs 提交（af98f5a3/8c27fd25，只动 fix… |
| 10-23 12:30 | deep-audit-1044（v23 波）10 支全绿闭环：并集 `integration/dv24` @ `f842dd13`，并集 CI #2533 success，**待用户放行合并 main** | 输入：Rikkaminis_Audit_Report_V23_A.md（68,767 B，A-01..A-08）+ V23_B.md（88,135 B，B-01..B-07）= 15 单。基线 main f94413f5… |
| 10-23 12:59 | deep-audit-1044（v23 波）全批闭环：main `1d44ec90` + main CI #2534 success（Publish 真执行，v1.0.0+2534）→ 清理 12/12 分支 + 10/10 worktree | 用户令「按流程走」= 放行合并（承接上一轮已完成的收口证据）。 |
| 10-23 20:10 | FIX-G10 收口（deep-audit-1046 / S-10 P1）：`fix/dv26-g10-tts-cancel-guard` @ `eb7b9edd`，CI #39947618231 success，停在分支 | 身份链：基线 main f7aba218 → 73215401 生产+测试 / 188858b6 门（CI 绿于此）/ eb7b9edd 报告。git diff --name-only 188858b6 HEAD -- … |
| 10-24 01:23 | FIX-B3 收口（deep-audit-1047 / B-05 P1）：模型组详情屏系统语音三处断层 — `fix/dv27-b3-model-group-system-voice` @ `8feb5466`（CI 绿于 `77ba33fc`），停在分支 | 缺陷（真树 4 处，报告报 3 处）：ModelGroupDetailScreen.kt 对 SystemVoiceEntries（默认 Voice Input/Output 组的全部成员，虚拟、无 instance、不… |
| 10-24 13:11 | VERIFY-2597 第 3 轮验证（CLI/工具面）+ 崩溃缺陷登记 —— 2026-10-24 12:57–13:2x | ★ 新缺陷（P1，已登记 backlog §2597-VERIFY-3）：minis-sessions-cli send --session <不存在的 id> 两次把 app 进程打崩（12:57:02 / 12:57… |
| 10-24 13:22 | VERIFY-2597 第 4 轮（UI 面解锁 + TTS 人耳判据）—— 2026-10-24 13:1x–13:2x | §1044-A07 R1 PASS：android-speak speak "甲二" --volume 0 几乎无声；紧接不传 volume 的 "乙二" 恢复正常响度 ⇒ 「--volume 0 后不传参恢复 1.0」… |
| 10-25 17:34 | WIDE4-07 收口：gemini_base_version_check 五条见证全补强，分支 CI #40179534916 绿（2026-10-25 傍晚） | 分支 fix/wide4-07-gemini-base（基线 19e70dab + 共享模块 dbab7a3e）→ 代码 4fa606d7 / 报告 fbfd6643 / 回填 bf09b3ac。生产代码 0 行，sca… |
| 10-25 21:30 | 第六批（第二轮 · 24 门）材料已备好（基线 main 6ac77c6b2，与 wide5 施工正交可并行） | 产物：/var/minis/mounts/背景包/发送-A\|发送-B/（00 = spec_v8 / 01 索引 24.2KB·20.5KB 含已挡清单 91·41 条臂 / 02 diff 56.9KB·233B / … |
| 10-25 21:43 | 第七批（末批 · 28 门）材料已备 —— 两轮覆盖账闭合 63+28=91（2026-10-25 21:42） | 产物：/var/minis/mounts/背景包/发送-A\|发送-B/（00 spec_v9 8.6KB / 01 索引 27.2KB·25.2KB 已挡清单 34·34 条臂 / 02 diff 233B 各 / re… |
| 10-26 09:35 | B6-t05 收口：tts_voice_and_sanitizer + katex_ready_race 两门 13 条见证全翻红（分支 `fix/b6-tts-katex` @ 门 `c367faf6f`，CI #2700 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + _kotlin_valueflow）。门提交 c367faf6f（生产 .kt 0 行、scan.sh/test_… |
| 10-26 11:19 | B7-t02 收口：skill_restore_session_cleanup + speech_active_engine 两门 12 条见证全红（分支 `fix/b7-skillrestore-speech` @ `8b865532e`，CI #2708 绿，停分支） | 基线 base/batch5-shared @ e145ec961（= main 6ac77c6b2 + 共享 _kotlin_valueflow）。门提交 8b865532e（2 文件 +1124/−720，生产 .k… |
| 10-26 13:19 | B7-t11 收口：a11y_handler_contract + voice_cancel_guard 两门 12 条见证全翻红（分支 `fix/b7-a11y-voicecancel` @ 门提交 `e90ca37f3`，CI #2721 绿，停分支） | 基线 base/batch5-shared @ e145ec961。门提交 e90ca37f3（2 文件 +781/−336，生产 .kt 0 行、scan.sh/test_scan.py/_kotlin_ 零触碰、门 … |
| 10-26 23:25 | T2 debt-sweep 收口：清三笔登记债务（#2 file:// 路径 / #3 图片附件有界读 / #5 weather 单出口），分支 `fix/debt-sweep-1026` @ `215c1d951`，CI #2755 绿，停分支（2026-10-26 夜） | 基线 main 627f23f2e。改动面 4 生产 .kt + 3 门脚本豁免表：+170/−194（7 文件，全部在任务书白名单内）。scan.sh/test_scan.py/_kotlin_ 零触碰。 |

## 24. 被否掉的方向（决策记录）

**跨度** 2026-08-04 ～ 2026-10-18 · **51 条** · **状态** 持续累积（每条否决都带触发条件，可被未来重新打开）

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
| 09-23 01:38 | sub2api 实测结论（2026-09-23，用户决定终止该方向） | 任务：评估能否用 Wei-Shaw/sub2api（Go+Vue 平台，113MB 二进制）替换 Render 上的 gcli2api。 |
| 09-27 00:14 | ★ 撤回上一条调查结论中的"soul.lang / 振动 commit 丢失"（09-27，内容级证据定案） | 上一条记忆条目里"⚠️ 你自己有 3 笔 commit 不在 main 里" 是错的，全部撤回。三笔全在 main： |
| 09-28 09:50 | memory MCP 服务器移除（09-28，用户拍板「日常使用中根本没发挥作用，去掉」） | minis-mcp-cli remove memory 已删，MCP 集成只剩 termux-dock。判据 = 一阶门：该服务器无持久化（每调用新 session 写入蒸发）+ daemon 不传 env，shell/… |
| 09-28 09:55 | ↳ 文档三件套第二次重建 + 合并 main = 346ebf59（09-28，用户「更新文档是低风险，直接合并」） | 三回归测试（test_sanitize/test_rebuild/test_sagas）全 PASS → rebuild 1399 条（前次 1386 → +13，dropped 75）→ sanitize main+I… |
| 09-29 00:21 | MEMORY-ROLLUP 重建预算实测 → 方向被数据否掉（09-29） | 方法：真源码离线预览（/var/minis/shared/work/rollup-preview-0929/，engine 0a58e4a5… / repository 28162b6f… 与 main 逐字节相同；ko… |
| 09-29 00:26 | MEMORY-ROLLUP (b) 重合度核对完成 → 建议不做（09-29） | 三个数字（全 58 天，/var/minis/shared/work/rollup-preview-0929/overlap_check.py）： |
| 10-06 17:43 | 外部项目吸收审查：OpenDots + Raven → backlog §67 全部登记不做（10-06） | 对象：OpenDots（CopilotKit，AI 同事模板，本质 = CopilotKit Intelligence 托管服务导流壳）+ Raven（EverMind，RSI 研究 harness，本质 = 云/容器基… |
| 10-10 15:26 | GLOBAL 记忆架构章节 + 远端旧分支清理（2026-10-10，用户令「加进去，并且把云端的那个删掉吧」） | ① GLOBAL.md 新增「## 记忆架构（六层 + 两条辅助）—— 2026-10-10 定案」（用户明确授权编辑，插在「待办登记纪律」节后）。内容 = 六层表格（层 / 位置 / 记什么 / 谁来读·何时）：日报 … |
| 10-11 00:37 | 修复「上下文超限静默终止」：fix/compact-stale-context-tokens-1010（2026-10-10，用户令「就按照你说的修吧」） | 根因（与我的初判不同，记录纠偏）：不是本地字符估算（bytes÷2.2）偏高——loopState.lastContextTokens 只有一个写入源（AgentLoopEngine.kt:1023/1029，来自 pr… |
| 10-12 23:07 | NextPlayer 定制：底部导航移除 + 个人签名出包基建（2026-10-12） | 用户需求：fork alarmedvine/nextplayer（= 上游 anilbeesetti/nextplayer 干净 fork，main 与上游一致）。要求：①底部「可切换的胶囊导航」整体搬到设置页、入口页只… |
| 10-14 00:20 | M3/Material You 用户拍板：L1 单独做意义不大，搁置（2026-10-14） | 用户拍板：「如果只是加一个动态取色开关，意义不大」——L1 不单独立项，评估结论攒着（报告 /var/minis/shared/work/m3-eval-1013/REPORT.md）。 |
| 10-14 22:11 | RV-1014 修复批次收口：8 支全绿合入 main a7123cf82（含备份/ContentCapture 决策分 | RV-1014 修复批次收口：8 支全绿合入 main a7123cf82（含备份/ContentCapture 决策分支） |
| 10-16 10:07 | FIX-D 收口：deep-audit-1016 执行闸门三件——4a heavy permit 泄漏回滚 + 4b runInterruptible 可取消等待 + 4c 主线程 GC 分发（fix/da1016-exec-gate @ edb45cbd，CI #2158 绿，停在分支） | 任务：读 shared/work/deep-audit-1016/fix-tasks/FIX-D.md 立刻执行（基线 main 04e74c53）。报告 fix-reports/FIX-D.md；装置 fix-d/pr… |
| 10-18 14:03 | FIX-A 交付：deep-audit-1026 ISSUE-01（ConcurrencyPrefs 缺重预热监听 + 陈旧缓存回滚，P1）—— `fix/da1026-prefs-priming` @ `cf5c8fd`，CI #2281 绿，停在分支 | 任务书 shared/work/deep-audit-1026/fix-tasks/FIX-A.md（COMMON 实读自上级目录）；报告 fix-reports/FIX-A.md（149 行 8 节无 TODO）；装置… |

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
| 09-22 16:36 | 09-22 续：§32a 真机验证闭环 —— 装包 `1.0.0+1748` 确认 `required: []` 可被主力网关接受 |
| 09-23 02:52 | 真机三分支验证（1.0.0+1753，用户装包后核查） |
| 09-23 18:04 | 验证体系四条提升（2026-09-23）：两条小在本会话落地，两条大分派 |
| 09-23 21:43 | §38-B 小样本验证：判据成立 + 三个真实发现（2026-09-23，2/21 已验，剩 19） |
| 09-24 09:26 | 用户指令：核查云端分支改动是否引入 Bug/第二类问题，上全套审查。 |
| 09-24 10:37 | 云端三分支核查 3/3 全绿无阻塞（09-24） |
| 09-24 17:04 | Jev 抓 bug 评估（09-24 续，jev-1.13 文档全拉）：官方最新 jev-1.13.0（$42/Btok |
| 09-25 16:31 | 用户问「应用与最佳实践的差别」→ 全库最佳实践对照（09-25，main 422914d1，/tmp/rk-bp-092 |
| 09-25 19:14 | drawer-row-slim 分支完整核查（09-25，检查会话，8/8 全跑）：feat/drawer-row-sl |
| 09-25 22:08 | §6.5 核查清单二次强化：机械化捆绑 + 判断项协议（09-25 晚，用户拍板） |
| 09-25 22:31 | rikkaminis-dev-methodology v1.3.1 评审：机械门禁有 7 处已实测漏拦（09-25，只评审未改） |
| 09-26 00:05 | 2026-09-26 00:05:00 |
| 09-26 00:25 | 2026-09-26 00:25:00 |
| 09-26 11:37 | senseaudio.cn deepseek-v4.1-flash 档位缺陷调查（09-26，实测定案，未修） |
| 09-26 11:51 | tool-bar-reserve-frame-skew 修复到停止节点（09-26，用户拍板修） |
| 09-26 13:54 | ★ 更正：/tmp 工作目录消失的真因不是「磁盘压力」，是 app 自己的 1 小时收割（09-26，源码级定案） |
| 09-26 15:27 | 日志分析报告 0926 核验（09-26，用户指派）→ 报告 /var/minis/shared/log-analysis-0926/REPORT.md（含 §6 核验记录） |
| 09-26 16:39 | 2026-09-26 16:39:00 |
| 09-27 13:33 | feat/runtime-sandbox-knobs 核查 + 双分支合并 main = 37c188ec（09-27，用户「没问题就合并」+「触发了就不用再等」） |
| 09-27 14:20 | 核查远端分支 fix/ime-auto-popup-on-overlay-close @ 2ad79408（09-27，用户「检查一下云端上的那个分支」） |
| 09-27 14:40 | 修 IME 守卫覆盖缺口 → fix/ime-overlay-focus-coverage @ 63f45fa0（09-27，用户「既然发现的问题，那就修一下吧」） |
| 09-27 15:52 | 2026-09-27 15:52:30 |
| 09-27 17:52 | 合并 fix/selection-toolbar-handle-overlap → main = 0d7be26e（09-27，用户「既然没有什么问题，那就合并吧」+「合并触发后就不用等了」） |
| 09-27 19:58 | 「Part B 需不需要样本」的实测回答：捞不到第二例（09-27，用户追问理解是否正确） |
| 09-27 20:34 | 核查分支 fix/persisted-think-closers @ 7a17a75d（09-27，用户「检查云端正在跑的那个」，未合并） |
| 09-27 20:42 | 合并 fix/persisted-think-closers → main = efac6f86（09-27，用户「合并了吧」+「触发了就不用等了」） |
| 09-27 23:04 | 独立确定性修复池 |
| 09-28 01:19 | 性能三分支拆分准备（09-27，用户「分三个分支，另外两个分配出去，我来分配」） |
| 09-28 09:45 | 口径更正（用户指出，09-28）：折叠 revert 后「3 个等拍板方向」应清零，不是挂起——①叙事折叠重设计：前提（ |
| 09-28 10:24 | 应用模块结构盘点（09-28，用户问「分多少模块」） |
| 09-28 17:23 | 远端分支删除：REST API DELETE 返 404，git 协议才是可靠路径（09-28 更正）：删 feat/t |
| 09-28 18:49 | 自主能力进化层的初步结论 |
| 09-28 20:23 | Procedure Layer v0 · Wave 1 验收（09-28） |
| 09-28 20:55 | Procedure Layer v0 · FIX1 + S4 验收（09-28） |
| 09-28 21:09 | FIX2 完成 · A/B 门槛可达性（09-28，按 task-FIX2-gate-reachability.md 执行） |
| 09-28 21:23 | Procedure Layer v0 · FIX2 验收 + Wave 3 预注册检查（09-28） |
| 09-28 21:51 | Procedure Layer v0 · Wave 3 实跑 + **仪器无分辨力**（09-28，本轮最重发现） |
| 09-28 22:08 | FIX3 · 门槛必须能说「测不出来」完成（09-28，task-FIX3-instrument-validity.md） |
| 09-28 22:32 | Procedure Layer v0 · Wave 3b：A+B 仪器重建 + 重跑 → **两条指标都不能给结论**（09-28） |
| 09-28 22:59 | ★ Wave 3c 筛题：三波收敛，结论成立（2026-09-28，用户「按你推荐的来，再往前推一段」） |
| 09-29 11:30 | 合并 refactor/simplify-fgs-notification → main = 0c78f007（09-29，用户「没问题就合并」） |
| 09-29 20:53 | 签名：每次安装都要先卸载的根因（09-29 晚，已修，main f6196b8） |
| 09-30 06:39 | 两个遗留全部处理完（09-30，main = f7053bc） |
| 09-30 12:10 | ★ 部署事故：wrangler 覆盖式部署丢掉了 KV 绑定（09-30，自查发现） |
| 09-30 13:53 | A 档核查结论（09-30，用户「感觉眼熟，去核查一下」）—— 6 项里 1 剔除 / 1 改判 / 1 降级 |
| 09-30 18:04 | 应用降级能力定案（09-30，用户问「这个应用能降级吗」） |
| 10-01 01:55 | rikkaminis-dev-methodology 补「分支审查（bug+次生问题）」检查项（10-01，v1.5.0，静态+行为 7/7 绿） |
| 10-01 08:42 | tier1 确定性批次审查 + 双仓合并完成（10-01，RM main=b4592399，CMFA main=d1d56f1） |
| 10-02 15:40 | 2026-10-02 15:40:10 |
| 10-02 15:55 | 2026-10-02 15:55:18 |
| 10-05 07:46 | feat/scheduled-tasks-l0-1004 已合并 main（10-05，merge 4f40e4af，main=4f40e4af） |
| 10-05 10:56 | RikkaMinis 主 clone 全景调查 + defect-registry 分支处理完毕（用户追问触发） |
| 10-05 16:45 | 交付物（单 commit，3 文件 +442/−3） |
| 10-05 17:37 | EXHAUSTED 停摆救援交付（任务书 F，2026-10-05） |
| 10-05 19:26 | 新包（6f357dc8）运行时验证巡检结果（10-05 19:20） |
| 10-05 19:57 | 五大文件拆分评估（10-05，二次开发视角，基线数据实测） |
| 10-06 06:47 | 分支：ui/polish-1006 @ 05f31a0b（2 commits：79d5956c 三处 UI 改动 + 0 |
| 10-06 08:22 | fix/scheduled-task-delete-1006 核查+合并 main（10-06 晚，「检查+有问题修+没问题合并」指令） |
| 10-06 16:41 | 应用现状指标全盘盘点（10-06 晚，用户问「各项指标什么样、推到哪一阶段」） |
| 10-06 20:06 | 云端分支核查：ui/runtime-page-adjust-1006（10-06 晚，用户令「检查一下云端正在跑的分支」） |
| 10-06 20:12 | ui/runtime-page-adjust-1006 合并 main 完成（10-06 晚，用户放行「没检查出问题就合并」） |
| 10-08 23:14 | Contributors 页眉不显示 haliChina 的原因（2026-10-08 实测） |
| 10-09 00:42 | Pattern-Key: sandbox-l2s-pack-symlink-broken |
| 10-09 21:13 | Filterrr/RikkaMinis 仓库调查（用户问「贡献者栏为什么我这边没有」） |
| 10-09 21:42 | Filterrr/RikkaMinis 深度比对（分岔分析与镜像行为） |
| 10-09 22:55 | 用户对 RikkaMinis 仓库的运营立场（重要偏好） |
| 10-10 12:16 | tg-tproxy 部署（2026-10-10） |
| 10-10 12:33 | tg-tproxy 域名绑定完成（2026-10-10） |
| 10-10 14:37 | 沙盒能力探测补充（2026-10-10 第二轮实验） |
| 10-10 15:21 | 设备端修复的产物级验证（2026-10-10）：解包 APK 验真 |
| 10-11 15:15 | RikkaMinis 现状与稳定度调查 + 报告集产出（2026-10-11，用户令「调查现状+开发史脱水，挂载文件夹开新文件夹存放」） |
| 10-12 19:54 | 今日改动独立复核（2026-10-12 晚，用户令「再检查一遍今天的各种修改」） |
| 10-12 19:57 | T3 报告勘误已修正（2026-10-12 晚，用户令「不要留下尾巴，处理了吧」） |
| 10-12 20:07 | 保留装置 vs 发现装置——产出追踪未覆盖面（2026-10-12 晚，用户问「今天都是发现类但没发现多少，为什么」） |
| 10-12 20:38 | 分支审查：test/md-parser-probe-1012（T5 产物）通过 |
| 10-13 00:43 | PR #12 审查：log-rotation 分支发现 2 个真实缺陷并修复 → 合并 main ad4a4beb（2026-10-13） |
| 10-13 01:15 | NextPlayer §86 拍板：「更多」页保留现状 |
| 10-13 13:32 | 4 项全修（R1/R2/R3/R4），无已消失项；P2 一律未修。 |
| 10-14 00:24 | 2026-10-14 00:24:00 |
| 10-14 11:49 | 应用现状快照（2026-10-14，对照 10-06 盘点） |
| 10-14 15:01 | 报告：/var/minis/shared/sweep-1014/reports/FIX-8.md；装置 /var/min |
| 10-14 15:14 | 用户令：「读 /var/minis/shared/sweep-1014/tasks/FIX-2.md 并立刻执行」。B3 |
| 10-14 15:33 | 2026-10-14 15:33:36 |
| 10-14 15:37 | 报告：shared/sweep-1014/reports/FIX-R2.md；工作树 shared/work/sweep |
| 10-14 15:38 | 任务书 shared/sweep-1014/tasks/FIX-R7.md；报告 shared/sweep-1014/r |
| 10-14 15:40 | 任务书 shared/sweep-1014/tasks/FIX-R3.md；报告 shared/sweep-1014/r |
| 10-14 15:42 | 任务书 shared/sweep-1014/tasks/FIX-R8.md；报告 shared/sweep-1014/r |
| 10-14 16:26 | 任务书 shared/sweep-1014/tasks/FIX-R1.md；报告 shared/sweep-1014/r |
| 10-14 17:19 | 任务书 shared/sweep-1014/tasks/FIX-R23.md；报告 shared/sweep-1014/ |
| 10-14 17:51 | SWEEP-1014 RikkaMinis 修复会话（FIX-R17 browser-sec） |
| 10-14 21:28 | 用户令「读 /var/minis/shared/review-1014/fix-tasks/FIX-RV-B.md 并立 |
| 10-15 00:43 | §97 端到端闭合（2026-10-15 00:39，设备包 1.0.0+2097） |
| 10-15 12:02 | 云端分支核查+修复+合并：fix/prewindow-budget-1015 → main 55344efa7（2026-10-15） |
| 10-15 23:10 | VERIFY-1015 U 类自动执行（用户令「也自动跑掉」，2026-10-15 23:0x） |
| 10-16 14:53 | A 支（P0 OpenAI tool-result 图）vision 路径真机端到端通过（用户切 vision 模型后补验） |
| 10-16 19:45 | E 支听感验证闭合（2026-10-16 19:5x，用户现场配合） |
| 10-17 13:28 | 2026-10-17 13:28:50 |
| 10-17 14:20 | deep-audit-1021 合并 main + 文档归档完成（用户令「合并，并更新文档」）—— main `ba4daf69`，两次树对拍一致 |
| 10-19 14:01 | deep-audit-1030 装机后独立复核（用户令「应用已含这波修改，开始验证」）2026-10-19 |
| 10-20 10:25 | 背景包 v17 + 提问模板 v14-A/B（首次双报告实战复盘后加严）—— 2026-10-20 上午 |
| 10-20 15:46 | 2026-10-20 15:46:58 |
| 10-20 15:55 | 2026-10-20 15:55:12 |
| 10-20 21:26 | 背景包 v19 + 提问模板 v16-A/B（配额换代：命中质量四问）—— 2026-10-20 深夜 |
| 10-20 23:26 | 2026-10-20 23:26:14 |
| 10-21 01:05 | 经验教训（可复用） |
| 10-21 07:35 | 2026-10-21 07:35:56 |
| 10-21 07:56 | 背景包 v20 + 提问模板 v17-A/B（1037 波复盘：§2.9 持续生效 + FLAKE 根治）—— 2026-10-21 早上 |
| 10-21 07:59 | 2026-10-21 07:59:25 |
| 10-21 08:02 | 2026-10-21 08:02:26 |
| 10-23 15:33 | 2026-10-23 15:33:45 |
| 10-23 17:33 | v27 追加：单任务版 v24-S（用户令「本波额度只有一份」） |
| 10-23 19:32 | FIX-F9（deep-audit-1046 / S-09，P1）施工中：`intent://` 消毒共享 + R2 scope 派生 |
| 10-24 12:07 | VERIFY-2597 真机验证窗口首轮（app `1.0.0+2597` = main `b82aa8dc`，含 dv22–dv28）—— 2026-10-24 12:0x |
| 10-24 15:48 | 腐账清算（2026-10-24 下午）—— 动手一条，纠正两条 |
| 10-24 17:09 | 真机验证窗口 1（2026-10-24 17:0x）—— 设备 1.0.0+2615 = main `fa59c008`，四验通过 + 抓到一个同族漏修 |
| 10-24 17:32 | 真机验证窗口 2（浏览器/WebView，2026-10-24 17:23–17:31）—— 四条通过 + 三条新 P2 |
| 10-24 22:53 | 门见证第四轮定案：宽扫优先（25 门/席 × zip 形态）— 2026-10-24 深夜，用户两次纠正 |
| 10-25 00:11 | 材料换新基线 564c8f3e + 第一波修复回归验证 12/12 通过（2026-10-25） |
| 10-25 18:39 | TierFlow 第三批 5 账号（2026-10-25 18:38）—— 全部全新满额，5/5 入选 |
| 10-26 14:39 | 第三轮（dual-gate bypass · 32 门）材料已备（2026-10-26 14:40） |
| 10-26 15:01 | 第三轮 B 波改单席（2026-10-26 15:01，用户纠偏「教条」） |
| 10-26 21:44 | 大文件拆分评估（2026-10-26，main @ 627f23f2） |
| 10-27 05:44 | 红标志（exit≠0）与误召回的机械定案 + 注册表侧修复（2026-10-27 晨） |
