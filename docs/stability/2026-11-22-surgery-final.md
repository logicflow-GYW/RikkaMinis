# 大手术最终收口：完整审查、三处阻断修复与并集验收（2026-11-22）

STATUS: VERIFIED_COMBINED_CANDIDATE_PENDING_MAIN

## 对象与规模

- 公开基线 `fe72bb9cdad4a66b0d40e0dff8844202d7cf4fe8`；初始组合候选 `2396706cfdf98f75d024cf2a7635a8cc980966e5`；最终代码并集 **`303ac2d2f5c97e1f2bce61d45b4344eb6b059a2e`**（tree `fb2955bcd08e91423a871796c8c24a003a1f1ef8`）。
- 最终累计 **128 文件，+15,390/−1,756**（生产 70 文件 +6,194/−1,557；测试 58 文件 +9,196/−199）。比初始 122 文件多 6 个文件：三处终审阻断各新增专属回归测试。
- 初始完整 diff 20,094 行；最终 diff 21,494 行。工作全部落在既有架构内，没有更换 DI/全序列化/模块拆分。

## 覆盖方式

六个独立只读审查会话按代码域覆盖全部 122 个初始文件，各自独立 clone（真实 `.git` 目录）、完整本域 diff + 最终态关键控制流 + 符号调用点清单。父会话逐份核读报告、coverage 与真实源码 hash，覆盖匹配 122/122、338 个符号记录。后继补丁仍按「原实现 + 补丁」整体审查，未只审最后一轮 diff。

## 三处已证实并已闭合的阻断

| # | 缺陷 | 修法 | 独立证据 |
|---|---|---|---|
| F-E01 | 恢复默认模型组/语音组/Agent Loop 字段时未纳入本次持久化回执，可能未落盘就报成功或漏报失败 | Stage 3 复用既有 `persistence.capture { field.write(value) }` | `RestoreFieldsPersistenceTest` 11 例（六字段×四存储结果×写入/清空 = 48 场景 + 相邻快照/无关 UI/取消/拒绝），旧版与单臂 shadow 各恰 10 行为红、新版全绿 |
| A-RT-01（含后继） | 并发逆序最后 finisher 漏回收 worker；补上后跨 Service 实例误杀新实例请求 | 锁内当前 generation 采样；再把生命周期真值迁入与 executionSlots/killProcess 同 scope 的 companion，poison 延迟回收同锁复核，`selfReap` 加 `holdsLock` 不变量 | `WorkerFinalizerReapDecisionTest` 5 例 + `WorkerProcessOwnershipTest` 14 例，覆盖同 PID 换 receiver、active/queue/ACK、stopSelf 路径、controlled drain、poison 锁；旧版与变异各自精准红 |
| D01（含冷读后继） | 无工具回合 `model_context_window_exceeded` 被当正常完成；补上后空错误行冷加载被过滤 | Engine no-tool 入口复用 `pendingInlineError`/`terminalErrorSurfaced`/单持久化漏斗；冷读 skip 判据加 `errorInfo.isNullOrBlank()` | `ContextWindowTerminalTest` 6 例（真实 Anthropic SSE→JSONL→完整 Engine）；冷读 65 例 old/mutant AOSP+Maven 各恰 5 红、新各 65/65，原独立 PostD01Probe 逐字未改 |

这些缺陷不是同一类：恢复屏障漏边（本次手术未完备）、公开基线存量回收缺陷（本窗口首次补齐实证）、以及两处修复自身引出的次生问题（跨实例误回收、空错误行冷读丢失）。**没有宣称「原候选零回归」，也没有宣称修复后绝无问题。**

## 分支级证据（精确 SHA + 当前 attempt 全部 job 原日志）

| 支 | SHA | run / attempt | 父核原日志 |
|---|---|---|---|
| `fix/surgery-restore-test-init-1122`（测试初始化后继） | `477122ca51819dd0b35044063df4a0edf6b368f2` | 44964990450 / 1 | 10824 PASSED/0；恢复字段新 11 + 旧 6 |
| `fix/surgery-restore-fields-1122` | `d12a7f8124daf73eeb543c38e0d3d09b782ccf1d` | 44926159011 / 1 | 10824 PASSED/0；新 11 + 旧 6 |
| `fix/surgery-worker-reap-1122` | `d49ecb9c22941681071c142c2e5b879677b09f6b` | 44934803700 / **2** | 10832 PASSED/0；Ownership 14 + Finalizer 5 + 既有并发 5 |
| `fix/surgery-context-cold-1122` | `a6e1f5bc46c48b5f2f1f06ec1413c1500f41ed22` | 44933127803 / 1 | 10830 PASSED/0；冷读 8 + parts 28 + parser 24 |
| `fix/surgery-context-terminal-1122` | `feef2a99f4da3ae368305980a079446d47c89e9b` | 44932144060 / 1 | 10822 PASSED/0；Context 6 + funnel 21 |

- 前一轮 #3450 的两例单测失败只在组合后出现：`RestoreFieldsPersistenceTest` 的测试初始化仅等首次配置加载，未等仓储 `loadScope` 的选择迁移与语音模板协调，可能多一条提交或改写模型条目。三支与并集里的 `ProviderRepository` 生产字节一致。测试在写断言前 join 实际初始化 Job，**仅测试增加 7 行，11 条原 oracle 全部保留，生产未改**；后继 #3451 五 job 绿。
- `a6e1f5bc` 含 `feef2a99` 的单测试后继；`feef2a99` 与生产 `5e54c715` 的 `src/android/app/src/main` 子树逐字相同。
- 前一轮 #3446 唯一真红是 `SessionActivityStreamErrorTest` 的 `deferred>=6` 负控在新增第 7 站后失牙；按单文件授权改为**精确逐站清单 + 各站删除/重复补偿负控**（60 项负控），不是把下限 6→7，也没有改生产去凑测试。
- #3449 attempt 1 的 lint 红于 Maven Central 三 jar（hamcrest-integration/library 1.3、javawriter 2.1.1）；父独立 curl 三个 URL 均 200，判为环境红，仅 `rerun-failed-jobs`，attempt 2 全绿，未改代码。
- 每支 scan-gate 原日志均为 `Summary: 115 passed, 0 failed`，`scripts/scan/** untouched` ⇒ **349 夹具未跑**；publish 的滚动 tag 与 Publish to Releases 两步均 **skipped**，所以这些绿都不代表已发布。

## 并集核验

- 四个代码并集 merge 提交各有 **2 个父提交**：`0675c702`（239+E01）、`8ae46b75`（+A01）、`4856faa4`（+D01/冷读）、`303ac2d2`（+测试初始化后继）。零冲突。
- `verify_union.py`：18 个补丁文件互不相交，各 tip 贡献文件与并集 HEAD 逐字相等；初始 122 文件中未触及的 **110 个文件未漂移**。总窗口 128 文件统计与独立 numstat 一致。
- 对**已验生产源码**重编译（159 单元），完整真实 Provider/Engine + 逐字 VM/IPC 方法、明示 transport/Room 叶子，**AOSP 53/53、Maven 53/53**（原组合 8 + D01 6 + 邻控）。`probes/FINAL-UNION-REPLAY/verified.json`。
- 并集自身机械核验：**115 门全过、四层一致、7 语言 1687 key 集一致、debt 无缺触发、无冲突标记**。当时的 `rc=1` 仅因远端 CI 尚未终态而 UNKNOWN，不是门红；新并集 CI 另行核验。
- `303ac2d2` 相对已验 `4856faa4` 仅改上述一个测试文件。`bind_test_successor.py` 已证明所有生产、共享代码、依赖、门与 workflow 均字节等价，测试 11 条 oracle 数量不缩；因此复用同一 jar 的 53 例与同一生产树的 115 门证据，不伪称重新运行过这棵树。
- 最终代码并集自身 CI：build-apk.yml #3452 / run `44966974709` @ `303ac2d2…`（运行结论与原日志见 `evidence/ci/`，不以本文替代原日志）。

## 开发档案

三件套（主档/按天索引/主题索引）自记忆日志重建并独立脱敏，暂存副本与挂载源对拍后提交。档案是历史记录，不是对每条旧结论的重新审核。

## 明确未验证与保留边界

1. 真 Android `:modelservice` 重建/进程死亡后的 Room 与 ACK 竞争、rootfs reset 真窗口、备份真磁盘故障、调度长时交错；新增跨实例行为是真实源码方法 + 明示平台叶子，未在真机复现。
2. 官方 Anthropic 实际签名/redacted 采纳、preserved-prefix/账号绑定、远端停止计费均未证。
3. B03 精确 Undo/Replace 产品语义仍未拍板（现有快照 merge 非精确回滚）；C03 可信 redirect 可达、C04 真实 WebView child canary 未取证。
4. 条件 P2 已登记 backlog，未借此扩施工：超 8MiB terminal spool 恢复不闭合、载体/持久包装深度预算、AOSP 合成 DONE+1e400 夹具差异、裸 near-name 投影误剥、非规范坏行 fallback、IPC 双文档/sidecar/解析前能耗。下载单槽替换少一条失败反馈为刻意取舍（P3）。
5. 新发现但未修的四类 P3（DEAD 棘轮、锁外准入判据、终态锁内文件 IO、锁外 poison 写）均为公开基线同形存量，未扩半径。

## 发布边界

用户已明确授权：本轮完整审查、发现真实问题直接修、通过后合 main。未自动装机、未强杀当前 app、未删分支/工作树。main 推送将触发现有 main-only 滚动发布，须另核 main CI 与实际 publish 步骤，不把 job success 当作已发布。
