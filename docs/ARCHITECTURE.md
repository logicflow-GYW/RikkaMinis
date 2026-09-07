# RikkaMinis 架构总览（ARCHITECTURE）

> 这份文档回答两个问题：**这个 app 由哪些系统组成**，以及**哪些复杂度是继承的、
> 哪些是本 fork 自己长出来的**。它与 README（功能视角）、DESIGN_PHILOSOPHY.md
> （决策视角）互补，本文件是**结构视角**。

## 0. 一句话

**你的私有、端侧 AI 智能体**：一个 Android 应用，把 Claude/GPT/Gemini 等模型、
一台真正的 Linux 电脑（PRoot 沙箱）、浏览器自动化、可扩展技能、持久记忆和
深度设备集成装进一部手机；并通过 GitHub / Cloudflare / Hugging Face 三个平台
把智能体的"手"伸出设备。

## 1. 血统与规模基线

| 项 | 值 |
|---|---|
| 上游 | [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)（fork 基线 = 上游 v1.10，`e488b1b1`，2026-07-25） |
| fork 日期 | 2026-08-01（上游恰好同日停止推送，fork 即接管） |
| UI 灵感 | RikkaHub（左滑会话抽屉、极简顶栏、消息流布局；借鉴灵感非代码） |
| 平台 | Android-only（上游 iOS 树与第三方 C 源码已删除） |
| 提交构成 | 全仓 934 commits ≈ 上游 12 + fork 后自写 ~922（8/1 起 36 天） |
| Android 代码量 | fork 基线 413 文件 ≈ 146.7K 行 → 当前 487 文件 ≈ 166.5K 行（**净 +74 文件 / +19.8K 行**，另有大量修改） |

**关键认知**：这个 app 60% 以上的复杂度（沙箱、多进程、offload、浏览器）来自
上游架构，fork 当天就已存在。本 fork 36 天的工作集中在三块：**功能增量**
（备份/同步/模型组/平台集成）、**可靠性硬化**（流错误自愈/护栏/审计）、
**质量基础设施**（scan 门禁/测试/发布链路）。没有一次"从零重写"。

## 2. 系统形态：一端脑 · 三进程 · 三平台手

```
┌───────────────────── 设备（Android 8.0+, arm64）────────────────────────┐
│                                                                          │
│  app 主进程（UI · ChatViewModel · AgentLoopEngine · 沙箱宿主）            │
│    │                                                                      │
│    ├─ :modelservice ── 全部模型 HTTP 请求（Provider 只许在此进程持有）      │
│    ├─ :toolservice  ── 工具执行服务                                        │
│    │                                                                      │
│    ├─ PRoot 沙箱：Alpine minirootfs（8.5MB 随 APK 解包）                   │
│    │    shell_execute · 文件工具 · 终端 · 每会话私有 workspace             │
│    ├─ 浏览器自动化：BrowserUseManager + TabPool（≤3 tab，风险控制）        │
│    └─ NativeOffloadHandler ×24：android-* 能力（日历/相册/剪贴板/闹钟/      │
│         通知/定位/TTS/语音/无障碍/Shizuku/媒体…）                           │
└───────────────────────────────────────────────────────────────────────────┘
     │ GitHub（CI·发布）  │ Cloudflare（CI 状态桥 Worker）  │ HF（语义记忆）
     └────────── 能力等级按 token 判级、动态注入 system prompt ──────────┘
```

### 2.1 为什么拆三个进程

- **`:modelservice`**：模型请求的**唯一持证进程**——Provider 适配器、API key、
  流式解析全部隔离在这里，主进程 UI 永不因网络阻塞；跨进程传参有严格序列化
  纪律（见 §5.3 第五层同步）。
- **`:toolservice`**：工具执行服务（与沙箱解耦的辅助执行面）。
- **主进程**：UI + 智能体编排（AgentLoopEngine 状态机）+ 沙箱宿主 + 数据层。
- 静态扫描门禁守护这条边界：**app 进程代码不得直调 provider 网络入口**。

## 3. 包结构地图（com.openminis.app）

| 包 | 职责 | 体量信号 |
|---|---|---|
| `ui/chat/` | 聊天主屏 + 智能体循环 + 流式渲染 | 87 文件（最大包）；ChatScreen 6.4K 行、ChatViewModel 3.8K、AgentLoopEngine 2.5K、StreamingMarkdownText 3.8K |
| `sandbox/` | PRoot 沙箱：RootfsManager（rootfs 解包/恢复/事件日志）、ExecutionCoordinator、PRootKernel | 14 + 43(offload) 文件 |
| `sandbox/offload/` | NativeOffloadHandler 家族 + ModelExecutionService(:modelservice 宿主) | 43 文件 |
| `data/` | Room（双库）+ Repository + 路由 + 用量 + 存储 | db 17 / model 14 / repository 11 |
| `provider/` | Provider 适配器：openai/anthropic/gemini/openrouter/xai/antigravity/voice + thinking 规则 | 14 + 子包 |
| `tools/` | Agent 工具定义（shell/file/browser/memory/spawn） | 13 文件 |
| `agent/runtime/` `agent/shell/` | 预算/重试/恢复策略、bashism 纪律 | 7+3 |
| `config/` | 可配置字段系统 + 内建项 + 确认流程 | 12 文件 |
| `backup/` | ConfigBackup（JSON 导出）+ WebDAV + SyncMerge | 7 文件 |
| `browser/` | BrowserUseManager + TabPool | 11 文件 |
| `mcp/` | MCP 客户端 + OAuth | 5+ |
| `ui/settings/` `ui/navigation/` `ui/sandbox/` `ui/browser/` `ui/terminal/` | 设置 15+ 屏、导航、文件预览、WebView、终端 | 40+6+6 |
| `offload/ service/ accessibility/ speech/ share/ webapp/ debug/ crash/ logging/ i18n/ diagnostics/` | 进程边界工具、前台服务、无障碍、语音、分享、WebApp、调试、崩溃、日志、本地化 | — |

## 4. 数据层：双 Room 库 + 四层同步纪律

- **ProviderDatabase v10**：ProviderInstance / ModelEntry / ModelGroup /
  ThinkingRule / 关联 ID 表——**provider 配置**。
- **AppDatabase v12**：sessions / messages / compact_markers（压缩标记 v2
  锚点模型）/ usage / webapp shortcuts——**会话与聊天**。
- 迁移全部手写 `MIGRATION_n_(n+1)`；历史上有过加列又 DROP COLUMN 摘除的
  v8→9→10 记录，minSdk 26 安全。
- **持久化四层同步**（Model → Entity → toSnapshot → toProviderConfig）：
  字段少同步任何一层都会**静默蒸发**。这条由 CI 前静态扫描
  （`scripts/scan/scan.sh`）硬性检查，并有 `four-way-sync-check` 技能兜底
  审计——是反复踩坑后固化的纪律。
- 第五层同步：**worker 进程边界**。dispatcher 序列化 → worker 重建 →
  providerRouteChanged 三处必须同改（thinking rules / custom fields 均在此
  翻过车，scan 门禁覆盖到 provider 边界）。

## 5. 智能体核心（本 fork 投入最重的一层）

### 5.1 执行链

```
用户消息 → ChatViewModel（队列/流状态机）
   → AgentLoopEngine（多 turn agent 循环，含工具调用）
   → ModelExecutionService(:modelservice)
   → Provider 适配器（OpenAI/Gemini/Anthropic/…，统一 LLMStreamChunk 流）
   → 流式回传 UI（StreamingMarkdownText 渲染）
   → 工具调用 → 沙箱 shell/文件/浏览器 / 原生 offload → 结果回合 → 循环
   → turn 收尾（预算记账/验证门控）→ 回复完成
```

### 5.2 护栏体系（T7 观测器，防失控不防任务长度）

| 护栏 | 值 | 失效形态 |
|---|---|---|
| 单轮 turn 上限 / provider 调用预算 | T7_OBSERVE_MAX_TURNS / **128 次** | 耗尽 → finalizeBudgetStop：人话横幅 + 内容保留 + 可 Resume |
| EOF 续写（断流） | MAX_EOF_STUB_CONTINUES = 2 | SSE 断流静默停 → 保留半截 + stub 提醒续写 |
| 长度墙续写 | MAX_LENGTH_WALL_TEXT_CONTINUES = 4 | 超长截断 → 续写直至完成或放弃并可见报错 |
| 重复中止 | Hermes repetition_guard 移植 | 60+ 字符窗口重复 ≥50% → 中止 |
| 确定性空响应 | outputTokens==0 ×2 → 快出 | 空回合串联放大器 |
| 并发槽 | JDK Semaphore(2, fair) + 准入上界 6 | 多会话并发；超限给 typed 错误 |
| 终态横幅 | terminalErrorSurfaced | 错误横幅被 "Stopped after N turns" 覆盖 |

> 并发槽的历史教训：kotlinx.coroutines 1.9.0 的 timed-acquire 会**吃许可**
> （KTKU-354，1.10 修），换 java.util.concurrent.Semaphore 后 2000 轮探针
> 零丢失——需要 timed acquire 时别用 kotlinx 信号量。

### 5.3 错误自愈三形态（近期主线）

| 形态 | 现象 | 处理 |
|---|---|---|
| **EOF 断流** | finish_reason 缺失、流中断 | 有半截 → stub 提醒续写（共享预算）；空 → 一次性重试；超限可见报错 |
| **stream-error** | error 线带 kind 分类 | network/transient → rollback 半截自动重试；rate_limit/provider → 直接 fallback |
| **error-shaped finish** | 如 `finish_reason=network_error` 伪正常结束 | 空回合 → 一次性重试；有半截 → stub 续写 |
| 内容拦截 | 三方言 content_filter / SAFETY / refusal | 空输出 → 消费 fallback 链；有文本视为完成 |
| 收尾验证 | 代码编辑无新鲜通过证据 | verification_stop 门控注入有界 nudge（≤2） |

## 6. 模型路由与 Provider

- **Provider 家族**：openai / anthropic / gemini / openrouter / xai /
  antigravity / voice + 任意 OpenAI 协议兼容网关。
- **模型组（group）**：组内多成员；**per-message 负载均衡轮转**（每条新消息
  依次切换成员，健康门控；retry/rerun/手动选人不轮转——避免重复服务）。
- **fallback 链**：单模型失败 → 组内 fallback / 跨组 fallback（入口精确解析 +
  toast + capsule flash）。
- **思考档位**：OFF/AUTO/LOW/MEDIUM/HIGH/ULTRA/MAX；AUTO 追加 guard；
  自定义 thinking_rules 经序列化跨进程传递；KeyRoulette 多 key 401 修复后
  单点收口在 ProviderFactory（旋转只此一处）。
- **标题/压缩子模型**独立解析，不走组路由。

## 7. 沙箱与原生能力

- **PRoot**：用户态 chroot（OpenMinis fork，含 Android 10+ W^X 绕过补丁），
  CI 里用 NDK r28 从 `deps/proot` 子模块编译——**不提交预编译沙箱二进制**。
- **rootfs**：Alpine minirootfs 作为资产随 APK 提交；RootfsManager 解包、
  完整性校验、定向恢复；**rootfs 事件日志**（宿主侧 `filesDir/logs/`，
  沙箱内 `/var/minis/logs/`）持久记录 INSTALL/重置/恢复事件，供排查
  "环境又没了"类问题归因。
- **apk 包持久化**：`apk add` 的包在 rootfs 重建后由 APKWORLD_RESTORE
  自动重装（host 快照）；`/tmp` 与 pip 包不保证。
- **offload 三族**：原生 offload（NativeOffloadHandler，与沙箱解耦的设备能力）、
  模型 offload（`:modelservice`）、Shizuku/无障碍 offload（特权系统操作）。
- **目录语义**：workspace/attachments/offloads/browser **每会话私有**
  （`minis-sessions/<sid>/`）；shared/memory/skills/mcp-servers/mounts 跨会话共享。
- **终端**：Termux terminal-view 0.118.0（ANSI/CSI/OSC 解析交给上游），
  输出双层截断（PersistentShell 128KB + 消毒层 50KB）。

## 8. UI（Compose）

- 聊天主屏：消息流 + 回合聚合（工具回合折叠）+ 思考折叠框 + 流式 Markdown
  （KaTeX 数学）+ auto-follow（**双条件守卫**：userScrolledAway +
  isNearBottom，防内容插入顶走视口）。
- 左滑会话抽屉（历史切换/新建/长按删除）、极简顶栏、输入栏内模型选择器。
- 设置 15+ 屏；**7+1 语言**（en/zh/zh-rTW/de/ja/ko/ru + 默认）。
- 无分析 SDK；崩溃报告本地 ACRA（无网络发送器）+ 崩溃频率检测。

## 9. 本 fork 差异化功能层（fork 后自写，区别于继承复杂度）

1. **本地备份/恢复**（JSON 可移植：配置/凭据可选/技能/记忆/MCP/聊天纯文本 N 天）
2. **WebDAV 远程备份**（应用级持久 scope，通知收尾，防重复触发）
3. **多设备自动同步**（SyncMerge：Lamport 式版本折叠冲突合并；范围只含
   配置/提供方/分组/环境变量/GLOBAL.md，每日日志按设备审计副本不纳入）
4. **三大平台集成**（GitHub ops / Cloudflare 运维 / HF 语义记忆：requirements.json
   判级 → [IntegrationStatus] 日志 + system prompt「内置集成」表格注入）
5. **长对话压缩**（AI 摘要器折叠最老回合为 `<context-summary>`，硬裁剪仅兜底；
   原则：用户消息永不压缩）
6. **UX 打磨群**（草稿持久化、链接消息聚焦、导出会话、设置页去箭头、输入栏
   聚焦语义、工具结果缩略预览开关…）
7. **质量基础设施**（见 §10）

## 10. 质量基础设施

- **scan.sh 四项门禁**（CI 构建前，任一硬失败中止构建）：
  四层同步 / i18n 孤儿键 / 枚举解析禁裸 valueOf / provider 进程边界。
- **JVM 单测全量先跑**：红 = 红，无静默跳过；沙箱内可用 kotlinc + 桩闭包
  做纯逻辑预验证（真 Gradle 编译裁决以分支 CI 为准）。
- **发布链路**：分支 → 分支 CI（head_sha 双核对：Cloudflare 桥 + API）→
  ff 合并 main → push 自动触发 release CI → 固定密钥签名 APK 发布到
  `android-latest`（纯 docs 改动经 paths 过滤不触发构建）→ **用户真机验证**。
- **验证纪律**：CI 绿 ≠ 逻辑对；ground truth 在用户侧（真机安装验证）；
  装包必验版本。

## 11. 演进阶段（fork 后 36 天的形态变化）

| 阶段 | 时间 | 主线 | 复杂度性质 |
|---|---|---|---|
| 功能周 | 8/03–8/08 | UX polish、备份/WebDAV、模型组、记忆模块、改名 | 用户可见功能 |
| 平台+可靠周 | 8/08–8/13 | 三平台集成、语义记忆、沙箱可靠性、并行审计 | 一半可见一半防御 |
| 防御性工程 | 8/13 至今 | 错误自愈三形态、护栏、并发、验证纪律、scan 门禁 | **几乎全部不可见** |

第三阶段的产出在 UI 上几乎没有体现——app 外观与 8 月中差别不大，但底层多了
数百个边界处理。这是"代码清单比观感复杂得多"的主要原因。

## 12. 复杂度边界声明（维护者必读）

- **继承复杂度（别动，除非出 bug）**：三进程架构、PRoot 沙箱、offload 机制、
  浏览器 TabPool、Provider 适配层、Termux 终端。这些是上游 v1.10 的设计，
  36 天只做了修补没做重构。
- **原创复杂度（你心里有数）**：备份/同步、平台集成、护栏体系、错误自愈、
  scan 门禁、开发档案。每一条都对应一个真实事故或真实需求。
- **修改任何跨层字段前**：先画执行路径矩阵（哪条路径在哪个进程发 HTTP），
  再对照四层同步 + worker 边界五处。
- **加任何能力前**：问"这个工具让 agent 能做什么之前做不到的事？如果答案
  是 shell 也能做，就不该加"——本 fork 的复杂度上限由这条决策纪律控制。

---

*本文档与 docs/dev-history/（开发日志档案）配套：档案回答"每天发生了什么"，
本文档回答"现在整体长什么样"。*
