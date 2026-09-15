# 观测层设计（Observability）

> 编制于 2026-09-14。本文件描述 RikkaMinis 的观测设施：应用如何"讲述自己"、
> 人与 agent 如何消费这些讲述。与 `trace-schema-v2.md` 互补——那份定义
> **agent run 的结构化轨迹**，本份定义**应用全局的日志与审计设施**。

## 0. 为什么观测层需要一份专门的设计档

这个应用有一个工程上少见的结构性事实：**执行体与测量仪器是同一个东西**。
应用既在"干活"，又在产生关于自己怎么干活的痕迹。由此派生的纪律不是"记更
多日志"——恰恰相反，日志实测已达 13 万行/半日的量级，**详细度过剩、组织度
不足**。观测层的目标因此只有三个：

1. **沉默可区分**——"没发生"与"没被测"两种零，读数必须不同；
2. **归因可完成**——从症状到原因有链路可走；
3. **尺子可信**——观测设施自身可被审计。

## 1. 两条日志通道（实测口径）

| 通道 | 行格式 | 覆盖 |
|---|---|---|
| 直写通道 | `[时间] [LEVEL] [category] msg` | AppLogger 写 `files/logs/minis-*.log`；沙箱内经 bind 可见（`/var/minis/logs/`）；tag `Minis.*` 不进 logcat |
| logcat 捕获通道 | `[LOGCAT] MM-DD 时间 L/Tag(pid): msg` | 其他 tag 经 logcat tail 写入同一日志文件 |

**生产者永不碰盘**（2026-09-15）：两条通道的写侧都经 `LogWriteQueue`（纯 JVM，
有界单消费者队列，上限 8192）。生产者只 enqueue 预打时间戳的行，单个 daemon
drain 线程批量落盘。此前每次 `log()/writeFileLine()/writeLogcatLine()` 都走
`@Synchronized getWriter()`，把 UI 线程与 LogcatTailer 读者（实测峰值 427 行/分）
串行化并做同步文件 I/O。

**队列满时的取舍**：DEBUG/logcat 行丢弃，keep 行逐出最旧——磁盘卡住既不能阻塞
调用方也不能无界涨内存，丢行时补一条 resume 通知。`LogWriteQueueTest` 用
live drain 线程验证守恒不变量（delivered + dropped == enqueued）。

**铁律**：存活度审计必须取两通道并集——只扫一个通道会把活的判成死的。
实测实例：`Perf` 直写 4442 行 / logcat 0 行；`OffloadRssProbe` 直写 0 行 /
logcat 17 行。

## 2. 结构化自述：AgentTraceRecorder（通道 A）

- schema 2.0（见 `trace-schema-v2.md`）：state_transition / budget_consume /
  budget_refuse / resource_acquire / resource_release / retry_decision /
  persistence_result / terminal_state。
- 落盘：`minis-sessions/<sid>/workspace/.traces/agent-<ts>.jsonl`（每会话私有）。
- 消费侧：`scripts/scan/trace_eval_check.py`（scan 门禁第 5 项，golden 断言
  工具序列 / 终态 / 禁用工具）——产出侧与消费侧就此闭环。

## 3. 诊断埋点家族（通道 B）

| 设施 | 回答的问题 |
|---|---|
| HangDiag | 卡顿：症状 + 采样栈 + episode + 降级决策（**前台门控**，见下） |
| CompactDiag | 压缩：决策 + 参数 + 结果 |
| RenderPathCensus | 渲染路径：7 分支走向计数（"让沉默可区分"的参考实现） |
| StreamPerfMonitor | 流式性能：tick 级吞吐 |
| LaunchBeacon | 启动：silent_kill / crash_or_stall verdict + uptime + restartCount |
| MemorySpikeRecorder | 内存：spike 采样 + `k=v` 结构化事件 |

**共同纪律**：首见必报（first-hit）+ 窗口汇总携带未触发分支的 0 值；
事件与字段用 `k=v` 格式，便于 grep。

### HangDiag 的前台门控（2026-09-15）

**症状**：`HangDetector` 的心跳 watch 没有前台门控，而 HyperOS 会冻结后台进程
→ 主 Looper 停摆 → 3s 心跳 miss 被计成真 hang。当日 379 个 HANG 样本中，长挂起
（42–176s）的 mid-hang 栈全部停在 `nativePollOnce`（空闲态），且与前后台日志精确
对齐；持久化 counter 使该计数 survive restart，launch breaker（≥3 次强制 home）
与 render breaker（降级 markdown）同被误触。

**规则**：`BackgroundFreezePolicy`（纯函数决策表，三相 IDLE/HANG/BG_FREEZE）——
后台期间主线程静默 = background freeze，**只记日志**（不计数、不采样、不触
breaker）；回到前台仍静默才升级为真 hang。未知前台状态 **fail-open**（按前台
处理），因此门控永远不会压掉真 hang，最坏退化成修复前行为。

**可区分性**：后台冻结与真挂起在日志里是两种读数——前者写
`background freeze, not counted as hang` / `background freeze ENDED peak=…`，
后者走原 stall 样本路径。这正是"沉默可区分"在卡顿轴上的落实。

## 4. 存活度审计（liveness audit）

- 脚本：`scripts/diag/liveness_audit.py`（README 同目录）。
- 方法：代码侧声明（`AppLogger.*` / `Log.*` / `onEvent` / 全局常量表）与
  日志侧实测**取并集**；零命中判 `DEAD?`；条件型/稀有型登记 **RARE**。
- **RARE 有保质期**：登记项在新构建里复活或死透时必须更新条目，否则它从
  "已知例外"腐化为"永久盲区"。pending-install 机制（装包前静默、首见命中
  即提示 prune）即为此设计。

## 5. 日志治理（2026-09-14）

- 噪声分级：高频流式碎片（ToolInputDelta 每 delta 一行）门控到 debug——
  单日减 ~7.9 万行；骨架事件（会话转正 / appendMessage 链 / streamJob）
  升直写通道，保证"主链必落盘"。
- 时间锚点：每个 attempt 两行（first-content / done），慢请求可分解。
- 效果：行数 −59%；200MB 保留窗口在重度日的覆盖从 ~5 天到 ~10 天；
  主链检索范围 13 万行 → 1.6 万行。

## 6. 已知陷阱（血的教训）

1. **agent 自身输出混入同日志**：统计"标记出现次数"必须排除命令回显文本
   （连踩两次假阳性）。
2. **grep 方言**：BusyBox grep 不支持 `--include`，会静默 0 结果——把
   "搜不到"归因成"代码里没有"之前，先验证 `grep --version` 是 GNU。
3. **两通道并集**（§1）。
4. **尺子接错线是静默的**：测量点必须接在"预期会执行"的路径上（census
   实例，见 `DESIGN_PHILOSOPHY.md` §6.1）。

---

*关联文档：`trace-schema-v2.md`（run 轨迹 schema）、`ARCHITECTURE.md` §10
（质量基础设施）与 §12（开发环）、`DESIGN_PHILOSOPHY.md` §6（观测层原则）。*
