# FEAT-INTERNAL-USAGE-LEDGER — 内部 LLM 调用的成本台账（压缩 / 标题 / 快速测试）

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 main | `108280cd` |
| 分支 | `fix/internal-usage-events`（worktree `wt-usage`） |
| 提交 | `ae501ec9` 单提交，20 文件 **+819 / −14** |
| CI | run **#2978**（分支手动 dispatch，非 main push 触发） |
| 门 | `scan.sh` **113 / 113** 本地全绿（worktree 上实跑）；`room_migration_check` 升级链 2..15 连续 + 3 条降级全接线 |
| 沙箱 JVM | `InternalUsageRecordTest` **10/10** + `InternalUsageWiringTest` **3/3**（kotlinc 2.1.0 + junit，LLMUsage.kt + InternalUsageRecord.kt 生产源逐字编译） |
| 变异臂 | **5 条**：M2 全零行保留→RED / M3 排序失稳→RED×2 / M4 标题调用点拆线→RED（wiring#1）/ M5 第二写路径→RED（wiring#3）/ M1 删 null 守卫→**编译器击杀**（可空接收者不可解引用——最强的 kill 形态） |

## 2 问题与判级

**P1（吸收判定 backlog §RHUB-1102）**：用量页从 `messages.token_usage` JOIN 派生——只看得见**产生了消息**的调用。压缩摘要、会话标题、连接测试都是真实的按 token 计费调用，但一个消息都不写，于是这三类支出在统计页上**系统性不可见**：用户拿统计页对中转站账单时永远对不上，且差额方向恒定（少报）。

**为什么是旁路表而不是大一统 usage_events（上游 rikkahub 的形状）**：聊天路径的 `token_usage` 在 persist 时已写一次、有测试、喂着 SQL 快路径（JSON1 聚合）。重写它 = 两次写同一事实 = 本项目反复付过的漂移税，且零用户可见收益。`ponytail` 标记在 `InternalUsageRecord.kt` / `UsageStatsScreen.kt`：天花板 = 无 per-model 内部用量拆分；升级触发 = 有人问"哪个模型烧掉了我的压缩 token"。

## 3 交付面

| 层 | 内容 |
|---|---|
| 纯逻辑 | `data/usage/InternalUsageRecord.kt`：`from()` 记账策略（null usage → 不记、全零 → 不记、cache null 折 0、负数钳 0）+ `aggregateInternalUsageBySource()` 确定性排序（总耗降序、source id 决胜）——**零 Room import**，沙箱 JVM 可测 |
| 实体/迁移 | `internal_usage_events`（v14→15，ADD TABLE + ADD INDEX；降级对 `MIGRATION_15_14` = real DROP，同 commit 落地，`HANDLED_DOWNGRADES` + `15 to 14`，守卫测试三件套同轮扩） |
| 写漏斗 | `InternalUsageRecorder.record()` 单一入口；写失败仅 `Log.w`（记账不能打断它记账的功能），`CancellationException` 照仓库惯例重抛 |
| 调用点 | 全部 3 处 `ProviderExecutionGateway.send`：压缩（**截断检查之前**记——截断的尝试也烧了 token，然后才落到下一候选）/ 标题（自动 + 手动重新生成共享）/ 快速测试（TEXT 路线，`entry.id` 同源） |
| 读面 | `ChatDao.internalUsageEventsBetween` 半开窗；`UsageStatsScreen` 独立分区「内部调用（不计入上方合计）」+ 来源行（请求数 · input / output）+ 90 天档；刻意**不并入** per-model 行（SQL 快路径的 distinct 天数/会话数是预聚合的纯计数，折叠会污染） |
| i18n | 6 键 × 7 语言（values / zh / zh-rTW / de / ja / ko / ru），XML 全部 parse 校验过 |

## 4 结构性防「只改一处」

`InternalUsageWiringTest`（读源码树的形状测试，同 `DatabaseVersionGuardTest` 风格）钉三条：
1. `ProviderExecutionGateway.send(` 的调用文件集合 == `InternalUsageRecorder.record(` 的调用文件集合（豁免表显式且当前为空）——**第 4 个调用点出现而没接线 = 红**；
2. `InternalUsageSources` 三个 id 必须各被至少一个调用点引用——声明了没写的常量 = 红；
3. `insertInternalUsageEvent(` 只允许出现在 `ChatDao.kt`（声明）+ `InternalUsageRecorder.kt`（漏斗）——**第二写路径 = 红**。

## 5 二阶门边清单（逐边 + 验证者）

| 边 | 验证 |
|---|---|
| Room 迁移链 | `room_migration_check` 门绿；实体列 vs 迁移 SQL 逐列对拍（AUTOINCREMENT PK / NOT NULL / 可空列一致） |
| 调用点 funnel | wiring 测试三不变量 + 变异臂 M4/M5 |
| 聚合 reader | 纯函数直测（分组求和 + 排序稳定性 + 空集） |
| 降级兼容 | 守卫测试表驱动行 15→17 行、25×25 sweep 豁免、白名单三对 |
| i18n | 7 locale × 6 键齐、XML parse OK、键唯一性校验 |

**未触碰**：`messages.token_usage` 写路径、既有 SQL 快路径、`scan.sh`/`test_scan.py`（门 113 / 夹具 349 数量不变）。

## 6 残留（登记，各带触发条件）

| # | 残留 | 触发条件 |
|---|---|---|
| R1 | ledger 无 per-model 拆分（按 source 聚合） | 有人要问"哪个模型烧的压缩 token" |
| R2 | 无自动清理（表增速 = 每次压缩/标题 1 行，年增千行级） | 表体积成为存储预算问题 |
| R3 | 不入 ConfigBackup / 聊天备份（备份导出 sessions+messages 语义；台账是本地诊断数据，恢复丢失可接受） | 用户要求跨机迁移成本历史 |
| R4 | 快速测试 IMAGE 路线不记（不经 gateway.send，无 usage 载体） | 图像路线也开始回报 usage |
| R5 | 无渠道 usage 回报（`usage == null`）的行——零行机制上有意为之 | 有渠道异步回报 usage |
| R6 | 真机端到端未验（依赖 CI 绿 + 用户真机升级验证：升级后统计页出现「内部调用」分区，做一次压缩/改标题后出非零行） | 下次真机验证窗口 |
