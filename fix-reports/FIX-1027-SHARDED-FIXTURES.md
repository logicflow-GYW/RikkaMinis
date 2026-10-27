# FIX-1027-SHARDED-FIXTURES — 夹具自测并行化（刀 2）

**分支** `ci/sharded-fixtures` @ `15f10e04`（源码提交 `c2a860db`）
**基线** `ci/apparatus-cheap-gate`（刀 1，叠放）
**CI** build-apk run `40501367075` / #2788 / **success 21.4 min**，head_sha 逐字符一致

---

## 1. 目标（承接刀 1 报告 §5 R3）

廉价通道 7.18 min 里夹具自测占 **5.25 min（73%）**；全量构建的 scan step 里它占 ~79%。刀 2 = 把这 347 例并行化。它同时作用于两条通道（廉价门 + 每次生产构建）。

## 2. 方案（1 个文件 + 3 个调用点）

`scripts/scan/test_scan.py`：
- **TEST_ORDER 单一事实源**：47 个测试函数名，串行/并行共用（不再有第二份清单可漂移）。
- **`--jobs N` 工作池**：每个测试函数在**独立子解释器**里跑（`test_scan.py --single NAME`），父进程 J 个线程调度，队列式认领 —— **动态负载均衡，不做静态分片表**（单测成本不可预知，每加一条门臂就漂移，静态表必然腐烂）。
- **架构选型**：与 `scan_parallel.py` 同血统（子进程 + 线程池），**刻意不用 multiprocessing.Pool** —— 池信号量在 PRoot 沙箱不可用（sem_open 受限，S28 族），runner 必须在 CI/本地/proot 三处同型工作。子进程隔离还意味着一个被硬杀的测试污染不了邻居的模块状态。
- **输出契约**：并行回放严格按 TEST_ORDER 顺序 → **并行日志与串行逐字节相同**（同行、同序、同 summary）。CI 判据 grep 的 `✅ ALL n CASES PASS` 与各门 selftest 标记全部存活。进度/调度行走 stderr。
- **fail-closed**：子进程硬死（被 OOM reaper 杀）或不回结果 trailer → 父进程侧该测试记红并附原因。绝不静默绿。
- 位置参数语义不变（第一个位置参数仍是 repo root，gate_run.sh 文档形态照旧）；`--jobs 0`→1；`--jobs abc` 大声退出。

三个调用点同改（fix-completeness）：`scan-gate-push.yml` / `build-apk.yml` / `scan-gate.yml` → `--jobs 4`。

## 3. 验证

**L1 本地（等价性 + 负控）**

| 臂 | 期望 | 读数 |
|---|---|---|
| 串行（仓库根）基准 | ALL 347 PASS | ✓ rc=0 |
| `--jobs 3` stdout vs 串行 | 逐字节相同 | **diff 空** ✓ |
| `--jobs 4` stdout vs 串行 | 逐字节相同 | **diff 空** ✓ |
| ✅ 行集合对拍（348 条） | 完全一致 | ✓ |
| `--jobs 0` / 无 flag | 恒等于串行 | jobs=1 ✓ |
| `--jobs abc` | 大声失败 | rc=1 带消息 ✓ |
| `--single` 未知名 | rc=2 | ✓ |
| 子进程全被 -9 硬杀 | fail-closed 全红 | FAIL=47，点名 "no result trailer (exit -9)" ✓ |
| 子进程输出缺 trailer | fail-closed | FAIL=47 ✓ |

（注：从 `scripts/scan/` 目录跑串行会有 4 个 selftest 红——它们按 cwd 读仓库文件；**必须从仓库根跑**，CI 一直如此，非本支引入。）

**L2 云端（真实 4 核 runner）**

| 读数 | 串行（刀 1 run #2） | 并行（run #2788） |
|---|---|---|
| 夹具段 | 5.25 min | **3.33 min（1.58x）** |
| scan step 整步 | ~6.8 min | **5.1 min** |
| 全量构建总长 | 16.6–24.6 min（同周噪声带） | 21.4 min（Gradle 段不变） |
| 判据 | — | `✅ ALL 347 CASES PASS` ×1、` e: ` 0、47/47 单元全完成、FAILED 行全为用例名、仅 Publish+tag skipped |

**守卫路由（真实 diff 的负控）**：本支改了 `build-apk.yml` ⇒ 廉价门守卫**正确拒付**（16 秒，点名两条 workflow 越界）——改生产构建路径的改动只能由全量构建自证，这条支在 build-apk 上验证。同日探针支 `ci/fixture-timing-probe`（仅白名单文件）则被守卫放行，用于取每单元耗时。

## 4. 瓶颈分析与地板（探针支实测）

诊断跑 `ci/fixture-timing-probe`（仅白名单文件，guard 放行）取到每单元耗时，**run `40505085137` success 2.93 min**：

| 读数 | 值 |
|---|---|
| 总功（47 单元之和） | **324s（5.4 min）** — 与串行 5.25–5.4 min 吻合，证明单测成本没被并行改变 |
| 夹具段墙钟 | **1.80 min（3.0x）** |
| 最重 4 个单元 | da1018_gates 75.3s · real_repo 59.6s · auto_approve_gates 47.9s · da1019_gates 36.3s（**合计 219s = 68% 的总功**） |
| 中位 / p90 | 0.2s / 21.2s（长尾极陡：47 个里 43 个在 22s 内） |

**结论**：4 个重单元恰好被 4 个 worker 各扛一个，所以墙钟 ≈ 最重单元（75s）+ 调度残差 = 108s。这是这套架构在此 runner 上的**实用地板**：把四个巨块全拆到均衡，理论下限也只有 324/4 ≈ 81s，最多再省 ~27s，而代价是重写那些测试函数的内部循环——不值得（装置比例）。
另一条更值钱的观察：**两轮 CI 的 runner 方差很大**（#2788 同代码同样 `--jobs 4` 得到 3.33 min，探针跑 1.80 min；`scan.sh` 历史区间 0.45–0.82 min 同理）。所以「刀 2 的真实收益」应报区间：**夹具段 5.25 → 1.8–3.3 min**，整条廉价门 **7.18 → ~3–4 min**。

## 5. 残留

- **R1（登记不修）**：拆四个巨块单元（da1018_gates / real_repo / auto_approve / da1019_gates）的内部循环最多再省 ~27s（324/4=81s vs 现 108s），代价是重写测试函数——按装置比例登记；触发条件 = 夹具段再次成为关键路径且 >2.5 min。
- **R2**：runner 核数没有日志级证据（公开仓标准 runner 理论 4 核）；两轮 runner 方差大（同代码 1.8 vs 3.3 min），要确证可在 scan step 打 `nproc`（一行，未做，不值得单独一轮）。
- **R3**：`--single` 子模式是调度器的内部契约（无文档入口）；若未来有人想单跑一个测试，`python3 scripts/scan/test_scan.py --single test_i18n` 可用，已写进本报告。
- **R4**：runner 方差本身（同代码同参数 1.8 vs 3.3 min）意味着单次 run 的读数不可作为性能证据，性能结论要两轮以上取中位。

## 6. 交付状态

build-apk run #2788 绿 = 停止节点达成，**停在分支未合并**（workflow 文件 = 真实验证面，合并起手权在用户）。
