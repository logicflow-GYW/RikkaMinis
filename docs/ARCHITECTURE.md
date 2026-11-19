# RikkaMinis 架构总览（ARCHITECTURE）

> 这份文档回答三个问题：**这个 app 由哪些系统组成**、**哪些复杂度是继承的、
> 哪些是本 fork 自己长出来的**、以及**这个 app 是怎么被造出来的**（§12 开发环）。
> 它与 README（功能视角）、DESIGN_PHILOSOPHY.md（决策视角）互补，本文件是
> **结构视角**（含产品结构与生产结构）。

## 0. 一句话

**你的私有、端侧 AI 智能体**：一个 Android 应用，把 Claude/GPT/Gemini 等模型、
一台真正的 Linux 电脑（PRoot 沙箱）、浏览器自动化、可扩展技能、持久记忆和
深度设备集成装进一部手机；并通过 GitHub / Cloudflare / Hugging Face 三个平台
把智能体的"手"伸出设备。

## 1. 血统与规模基线

仓库规模统计的基准是 **2026-11-19 / `f75cceaa`**，不代表更晚版本。

| 项 | 值 |
|---|---|
| 上游 | [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)（fork 基线 = 上游 v1.10，`e488b1b1`，2026-07-25） |
| fork 日期 | 2026-08-01 |
| UI 灵感 | RikkaHub（左滑会话抽屉、极简顶栏、消息流布局；借鉴灵感非代码） |
| 平台 | Android-only（上游 iOS 树与第三方 C 源码已删除，保留部分兼容字段与注释） |
| 提交数 | 4,318 个可达提交（`git rev-list --count`，含上游基线与合并记录） |
| Kotlin 文件（`f75cceaa`） | 主源码 744；单元测试 991；仪器测试 14。 |
| fork 基线文件数（`e488b1b1`） | 主源码 413；单元测试 33；仪器测试 8。 |

只计 `src/android/app/src/` 下 `main/java`、`test`、`androidTest` 对应目录中 Git 跟踪的 `.kt` 文件。

沙箱、多进程、offload 和浏览器结构在 fork 前已经存在。本 fork 主要投入四块：
**功能增量**（备份/同步/模型组/平台集成）、**可靠性**（流恢复/守卫/审计）、
**质量基础设施**（静态扫描/测试/发布）与**观测**（诊断/日志治理）。
最近一轮的范围和验证见[稳定性复盘](stability/2026-11-19-review.md)。

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
│    └─ Android 原生能力（android-* 工具：日历/相册/剪贴板/闹钟/           │
│         通知/定位/TTS/语音/无障碍/Shizuku/媒体…）                           │
└───────────────────────────────────────────────────────────────────────────┘
     │ GitHub（CI·发布）  │ Cloudflare（CI 状态桥 Worker）  │ HF（语义记忆）
     └────────── 能力等级按 token 判级、动态注入 system prompt ──────────┘
```

### 2.1 为什么拆三个进程

- **`:modelservice`**：模型请求的独立进程。聊天模型的请求和流式解析在这里执行，
  主进程负责 UI 和任务编排；跨进程传参见 [§4](#4-数据层双-room-库--四层同步纪律)。
- **`:toolservice`**：工具执行服务（与沙箱解耦的辅助执行面）。
- **主进程**：UI + 智能体编排（AgentLoopEngine 状态机）+ 沙箱宿主 + 数据层。
- 静态扫描门禁守护这条边界：**app 进程代码不得直调 provider 网络入口**。

### 2.1.1 为什么 Android-only 树里保留 iOS 引用

上游是双平台的，本 fork 删除了 iOS 树与第三方 C 源码，但**保留了 iOS 兼容的
序列化契约**。这些引用分两类，删掉任何一类都会破坏数据互通：

1. **协议契约（必须保留）**：`ProviderRepository` 的备份/导出序列化
   （modalityBitfield、"so iOS can faithfully restore it"）、`MCPOAuthConfig`
   的字段镜像（"Mirrors iOS MCPOAuthConfig"）、`ProviderDatabase` 的 iOS
   互操作位。私有仓的 iOS 线与 Android 线消费同一份备份/配置格式——删掉
   这些字段，小号线产出的备份在 iOS 侧无法恢复。
2. **历史注释（无害噪音）**：`build.gradle.kts` 的 copyBashismRules 注释
   （"iOS references the same files as bundle resources"）等。保留它们是因为
   与私有仓的注释同源，删除会造成公私注释漂移。

**判别方法**：注释里出现 "so iOS can faithfully restore" / "Mirrors iOS" /
"iOS readers" 的是协议契约；只在讲文件来源的是历史注释。纯 Android 读者
可以把它们当作"私有仓 iOS 线存在的证据"读。

## 3. 包结构地图（com.rikkaminis.app）

| 包 | 职责 |
|---|---|
| `ui/chat/` | 聊天主屏、智能体循环、队列与流式渲染 |
| `sandbox/` | PRoot 管理、执行协调、rootfs 安装与恢复 |
| `sandbox/offload/` | 原生设备能力与模型执行服务的 Android 桥接 |
| `data/` | Room 数据库、Repository、路由、用量与存储 |
| `provider/` | OpenAI、Anthropic、Gemini 等模型 API 适配及思考档位 |
| `tools/` | shell、文件、浏览器、记忆与代理派发工具 |
| `agent/runtime/` `agent/shell/` | 循环预算、重试、恢复策略与 shell 规则 |
| `config/` | 可配置字段系统、内建设置与确认流程 |
| `backup/` | JSON 备份、WebDAV 与自动备份 |
| `browser/` | BrowserUseManager 与标签页生命周期 |
| `mcp/` | MCP 客户端与 OAuth |
| `ui/settings/` `ui/navigation/` `ui/sandbox/` `ui/browser/` `ui/terminal/` | Compose 设置、导航、文件预览与终端 |
| `service/` `accessibility/` `speech/` `share/` `webapp/` `debug/` `crash/` `logging/` `i18n/` `diagnostics/` | 系统服务、可访问性、语音、分享、WebApp、崩溃与诊断基础设施 |

此图描述职责，不列容易过期的文件数；以目录和类名核对当前拆分。

## 4. 数据层：双 Room 库 + 四层同步纪律

- **ProviderDatabase v11**：ProviderInstance / ModelEntry / ModelGroup /
  ThinkingRule / 关联 ID 表——**provider 配置**。
- **AppDatabase v15**：sessions / messages / compact_markers（压缩标记 v2
  锚点模型）/ usage / webapp shortcuts——**会话与聊天**。
- Room 迁移在两个数据库类中显式登记；改 schema 时连同迁移路径一起验证。
- **持久化四层同步**（Model → Entity → toSnapshot → toProviderConfig）：
  字段少同步任何一层都会**静默蒸发**。这条由 CI 前静态扫描
  （`scripts/scan/scan.sh`）硬性检查，并有 `four-way-sync-check` 技能兜底
  审计——是反复踩坑后固化的纪律。
- 第五层同步：**worker 进程边界**。新增请求字段要核对 dispatcher 序列化、
  所有消费端和缺字段的默认规则。以 Fast Mode 为例，流式与非流式路径都能接收
  显式开启或关闭；仅旧请求没有字段时才用本地默认值。

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
>
> **可调性**：上表的全部数值不再是代码常量——Settings → Agent Runtime →
> Runtime Limits 面板（19 项，四组：会话与派发 / 循环预算 / 流恢复 / 网络与
> worker）把它们暴露为运行时设置，默认值等于原硬编码值，改后下一条消息生效。
> 实现模式：`AgentRuntimeLimitsPrefs` prime 缓存 + `ConfigBuiltins` 注册
> `runtime.*` 路径（minis-config 可读写）+ 引擎每 run 入口快照预算（横幅读
> 快照而非实时值，防 run 中改设置导致数字漂移）+ worker 进程侧同样 prime
> （槽位池/准入在 `:modelservice` 执行，曾因主进程侧 prime 而静默回落默认）。

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
  按「子系统收口」旋转：聊天走 ProviderFactory.create、模型列表走
  ModelListProviderRegistry.fetchModels、语音走 VoiceProviderFactory.make、
  debug 探测走 ProviderMutationMethods —— 每个门都有自己的收口点，规则见
  KeyRoulette KDoc（只收聊天那一处曾导致模型刷新/语音继续发 401）。
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
- **受保护目录的删除保护**：shell 与 fastio 共用策略，默认开启，独立于免确认模式。
  这是对危险删除的保护，不改变 Android 的权限边界。文件浏览、会话和技能清理
  还各自检查目录边界。
- **终端**：Termux terminal-view 0.118.0（ANSI/CSI/OSC 解析交给上游），
  输出双层截断（PersistentShell 128KB + 消毒层 50KB）。

## 8. UI（Compose）

- 聊天主屏：消息流 + 回合聚合（工具回合折叠）+ 思考折叠框 + 流式 Markdown
  （KaTeX 数学）+ auto-follow（**显式状态机**：`followReducer` 的
  FOLLOWING/DETACHED，只有 Send/Resume/Retry/FAB/首开这些**显式意图**能重新接上；
  底部判定是「锚点行底边距**可见底**的距离 ≤ `scrollNearBottomDp`」，
  可见底 = `viewportEndOffset - LayoutInfo.afterContentPadding` ——
  详见 `docs/scroll-follow-simplification.md` 最后一节）。
- 左滑会话抽屉（历史切换/新建/长按删除）、极简顶栏、输入栏内模型选择器。
- 设置 15+ 屏；**7+1 语言**（en/zh/zh-rTW/de/ja/ko/ru + 默认）。
- **单行输入框契约**：`SectionTextField` / `DialogTextField` 的单行模式在
  `onValueChange` 把粘贴进来的 CR/LF 折叠为单个空格
  （`sanitizeSingleLineInput`，[T-android-singleline-paste-newline]）——
  Compose 的 `singleLine=true` 只裁剪**显示**、不清洗**数据**，不折叠时
  第二行起的粘贴内容会"隐形驻留"（删掉可见字符后字段看起来空了、实际没空）。
  新增单行输入控件必须走这两个组件（或同一清洗函数）。
- 无分析 SDK；崩溃报告本地 ACRA（无网络发送器）+ 崩溃频率检测。

## 9. 本 fork 差异化功能层（fork 后自写，区别于继承复杂度）

1. **本地备份/恢复**（JSON 可移植：配置/凭据可选/技能/记忆/MCP/聊天纯文本 N 天）
2. **WebDAV 远程备份**（应用级持久 scope，通知收尾，防重复触发；自动备份独立
   `auto/` 子目录、远端恢复/拉取/删除）
3. **三大平台集成**（GitHub ops / Cloudflare 运维 / HF 语义记忆：requirements.json
   判级 → [IntegrationStatus] 日志 + system prompt「内置集成」表格注入）
4. **长对话压缩**（AI 摘要器折叠最老回合为 `<context-summary>`，硬裁剪仅兜底；
   原则：用户消息永不压缩）
5. **UX 打磨群**（草稿持久化、链接消息聚焦、导出会话、设置页去箭头、输入栏
   聚焦语义、工具结果缩略预览开关…）
6. **质量基础设施**（见 §10）

## 10. 质量基础设施

- **静态扫描与夹具**：`scan-gate` 运行 `scripts/scan/scan.sh`；扫描器有改动或无法
  证明未改动时再运行夹具。独立的 `scan-gate-push.yml` 同时跑检查和夹具。四类检查为
  数据与资源契约、结构与接线、行为见证（含 agent trace golden）及共享解析原语。
  当前注册数量和近期运行证据见[稳定性复盘](stability/2026-11-19-review.md#验证证据)。
- **观测与自述层**（设计档：[观测规范](stability/observability.md)）：日志同时走
  应用直写与 logcat 捕获；诊断埋点让「未执行」和「未采到」有机会区分，
  `scripts/diag/liveness_audit.py` 对照源码声明和日志实测。
- **测试与发布**：静态扫描、JVM 单测、Android Lint 与 APK 构建并行；四项通过后，
  仅 `main` 移动 `android-latest` 标签并发布固定签名的 APK。仪器测试在 CI 只编译。
  候选分支的 CI 按改动范围选择；纯 Markdown 不发包。见[构建指南](../BUILDING.md#continuous-integration)。
- **验证纪律**：查 CI 是否运行了所引用的步骤，关键修复做失败侧对照；装包必验
  版本，真机验证只覆盖实际走到的路径。流程见[开发生命周期](DEVELOPMENT_LIFECYCLE.md#审计与验收)。

## 11. 演进阶段

| 阶段 | 时间 | 主线 | 复杂度性质 |
|---|---|---|---|
| 功能周 | 8/03–8/08 | UX polish、备份/WebDAV、模型组、记忆模块、改名 | 用户可见功能 |
| 平台+可靠周 | 8/08–8/13 | 三平台集成、语义记忆、沙箱可靠性、并行审计 | 一半可见一半防御 |
| 防御性工程 | 8/13–8/31 | 错误自愈三形态、护栏、并发、验证纪律、scan 门禁 | **几乎全部不可见** |
| 观测期 | 9/01–9/13 | 存活度审计、RenderPathCensus、内存门口径（RssAnon）、死尺子清理 | 不可见（测量设施的建设） |
| 自述期 | 9/14 起 | 日志治理（骨架事件升直写通道 / 噪声分级 / attempt 级 timing）、开发环显式化 | 不可见（应用开始"讲述自己"） |

后三阶段的产出在 UI 上几乎没有体现——app 外观与 8 月中差别不大，但底层多了
数百个边界处理、一整套观测设施、以及一份可被消费的自述日志。这是"代码清单
比观感复杂得多"的主要原因。

## 12. 开发环（人 · agent · 应用 · 平台）

> 本节描述的不是产品结构，而是**生产这个产品的结构**——它回答"这个 app
> 是怎么被造出来的"，以及"为什么它能以天级节奏被改进"。

### 12.1 环的形状

```
                 ① 用户（意图 / 施压）
                      │  使用
                      ▼
                 ② 应用（执行体 + 产痕迹）
                      │  痕迹
                      ▼
                 ③ 材料（日志 / 代码 / CI 记录）
                      │  读
                      ▼
                 ④ agent（归因 → 改动）
                      │  改动
                      ▼
                 ⑤ 裁决（CI 门 ‖ 用户真机门）
                      │  装包
                      └──────▶ 回到 ①
```

**五个节点的唯一角色**：

| 节点 | 谁 | 唯一角色 | 不可替代性 |
|---|---|---|---|
| ① 意图/施压 | 用户 | ground truth 的唯一产地 | agent 不能自证"好用" |
| ② 执行体 | 应用 | 行为 + 痕迹的产生器 | 沙箱跑不了 Compose，模拟替代不了真机 |
| ③ 材料 | 日志/代码/CI | 事实的痕迹 | "感觉慢"不是材料，grep 出来才是 |
| ④ 归因 | agent | 环上唯一双向节点：读材料 + 改对象 | CI 能判不能改；应用不能自改 |
| ⑤ 裁决 | CI ‖ 用户 | 两道性质相反的门 | 不可互替（见 12.2） |

**关键**："使用"不在环外——它是 ①→② 这条边。没有使用就没有新痕迹，环停。
使用不是消费，是燃料。

### 12.2 两道门

|  | CI 门（机器） | 用户门（人） |
|---|---|---|
| 判什么 | 能编译 / 断言过 / 门禁过 | 真好用 / 真符合预期 |
| 成本 | 低、快、可重复 | 高、慢、不可重复 |
| 失手风险 | 假阳性高（绿 ≠ 逻辑对） | 假阳性低（但必须真装包真用） |

机器门拦截"不可能对"的部分（便宜、可无限重复），剩下的交给人门。
两门串行、不同性质、不可互替。

### 12.3 环的四个性质

1. **回环周期 = 一次对话级**：症状 → 归因 → 分支 → CI → 装包 → 复现，
   可在同一会话内完成。周期短到"使用"与"修改"还在同一个心智连续里。
2. **应用双重身份**：既是执行体又是测量仪器（改造对象与传感器同体），
   观测层的全部纪律由此派生（见 `docs/stability/observability.md`）。
3. **agent 的眼与手都靠环供给**：日志是眼的分辨率，CI/发布链是手的长度；
   工具边界已由 shell + android-* 覆盖，故**加日志比加工具值钱**。
4. **单人多角色**：需求/维护/观测/验收均为一人——决策链极短（优点），
   **带宽 = 一个人**（唯一不可扩的瓶颈）。

### 12.4 失效模式

| 失效 | 形态 | 对策 |
|---|---|---|
| 环断 | 停止使用 → 无新痕迹 → 环停 | 使用是燃料（自驱任务可部分填充） |
| 假门 | CI 绿但逻辑错 / 跳过真机验证 | 三源取二；门不可降级 |
| 仪器错线 | 传感器接在死代码上，读数恒 0 被误判"路径未跑" | 尺子自身定期被审计（存活度审计） |
| 单点 | 一人带宽封顶 | 把记忆/验证/知识/观测外置（见 12.5） |

### 12.5 演化方向（据既有数据）

早期提交和审计记录显示，开发重点从功能扩展逐步转向可靠性、验证和观测。团队用
GitHub Actions 执行构建与测试，用技能沉淀操作方法，用 `scripts/diag/` 保留诊断工具。

结构上不可外置的只剩两个端点：**施压**（定义 ground truth）与**拍板**
（宣告"够了"）。环的终形不是更大，是更短——中间全段被机器与 agent 承接。

## 13. 借鉴登记（参考项目的思想来源）

本 fork 大量借鉴同类 agent 项目的**设计思想**（非代码复制——每个落地都
按 RikkaMinis 的架构重写并有测试）。登记于此，便于追溯"这个设计为什么长这样"：

| 参考项目 | 借鉴内容 | 落地形态 |
|---|---|---|
| [Hermes Agent](https://github.com/NousResearch/hermes-agent)（Nous Research，MIT） | **harness 纪律**：真机事故→守卫模块；prompt cache 不变量；表驱动错误恢复 | repetition_guard 移植（重复中止）、empty_response_guard 确定性空快出、continuation ceiling、session 级 system prompt 冻结、verification_stop 验证门控、预算护栏（turn/provider 上限可见化+Resume）、并发槽 |
| [OmniBot](https://github.com/omnimind-ai/OmniBot) | 工具并发白名单、回合折叠 UI、自动压缩、记忆 rollup | 工具回合折叠、上下文压缩器（用户消息永不压缩）、子代理系统 |
| [Operit](https://github.com/AAswordman/Operit)（LGPL-3.0） | 浏览器工具补缺、CI 脚本自测、语义记忆混合评分 | get_console_messages / get_network_requests / file_upload（BrowserTouchPlanner 真实手势）、scripts/scan/test_scan.py、semantic_memory.py v1.1.0 混合评分（skill 层） |
| [RikkaHub](https://github.com/rikkahub/rikkahub)（AGPL-3.0） | 聊天 UI 交互（左滑抽屉、消息流跟随、输入栏聚焦） | 左滑会话抽屉、极简顶栏、auto-follow 双条件守卫 |
| [OpenClaw](https://github.com/riley0122/OpenClaw)（参考于上游技能体系） | 技能目录化、触发式 skill | 可扩展技能系统（/var/minis/skills/） |

> 完整参考清单（含未落地备选）见 README「致谢」与 `docs/dev-history` 中
> 各调研条目（OmniBot 调研 08-12、Hermes 吸收分析 09-06 等）。

## 14. 复杂度边界声明（维护者必读）

- **继承复杂度（别动，除非出 bug）**：三进程架构、PRoot 沙箱、offload 机制、
  浏览器标签页管理和 Provider 适配层来自上游。本 fork 主要做定向修补，并把
  内置终端的仿真引擎替换为 Termux `terminal-view`。
- **原创复杂度（你心里有数）**：备份/同步、平台集成、护栏体系、错误自愈、
  scan 门禁、观测/审计体系（census / 存活度审计 / 日志治理）、开发环与
  开发档案。每一条都对应一个真实事故或真实需求。
- **修改任何跨层字段前**：先画执行路径矩阵（哪条路径在哪个进程发 HTTP），
  再对照四层同步 + worker 边界五处。
- **加任何能力前**：问"这个工具让 agent 能做什么之前做不到的事？如果答案
  是 shell 也能做，就不该加"——本 fork 的复杂度上限由这条决策纪律控制。

---

*本文档与 docs/dev-history/（开发日志档案）配套：档案回答"每天发生了什么"，
本文档回答"现在整体长什么样"，以及"它是怎么被造出来的"（§12）。*
