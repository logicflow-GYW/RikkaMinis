# FIX-LOOP1030-R2-M2 — 判死取消与用户取消分离（cancel.death）

**分支** `fix/cancel-death-semantics`　**基线** main `569e77a3`　**提交** `5fd7f080`
**来源** LOOP-1030 R2-worker 报告 M2（P0 candidate）

## 1. 症状与证据

2026-10-28 19:47 run `dc288040`：worker pid 25334 在 `in-flight>0` 时消失，客户端判死
`DIED_MID_STREAM`。此时 `hadChunks=true`，按既有策略**不重试、不 fallback**——用户只看到
一句错误横幅，而**完整的半截回答已经躺在 staging 目录的 `stream.jsonl` 里**（每行一个
`{"t":"text","v":…}` delta）。它再也没有出现过。

10-28 全天 64 个重负载 run 中，11 个走的是这条路径。

## 2. 根因：两个来源的 `cancel` 完全同形，三重丢弃

判死路径与用户点停路径写的是**同一个文件**：

```
ChatStreamOffloadHandler.finally {
    if (!terminalSeen) writeCancel(dir)     // 只此一句，不区分死因
    awaitTerminalAndWorkerExitThenDelete(dir, runId)
}
```

而下游三处各自把「有 cancel」读成「内容已被主人主动丢弃」：

| 环节 | 判据 | 后果 |
|---|---|---|
| `AwaitTerminalAndWorkerExitThenDelete` | `writeBarrierSeen = terminal ‖ result ‖ cancel.ack`，判死路径三者皆无 | 不删，留作 orphan（这一层是对的） |
| `PartialStreamRecoveryPolicy.decide` | `cancelPresent -> SKIP_CANCELLED`（排在 grace/empty/meta 之前） | 冷启动永不恢复 |
| `ModelExecutionOrphanReaper.shouldReapCancelledOrphan` | `cancel` 存在 + 无 beat + 无 pid + 龄 >10 min → **删目录** | ~10 分钟后连原始 payload 一起物理删除 |

于是「真的被用户取消」和「worker 崩了、内容没人收」在整条链上不可区分，后者被永久丢弃。

## 3. 修法：一个 wire 信号，一个语义标记

`ModelExecutionMailbox.writeCancelDeath(dir)`：**先照旧写 `cancel`**（协议不变——一个被冻结
后解冻的 worker 仍必须看到它并中止），**再写 `cancel.death`**（语义标记）。

| 位置 | 改动 |
|---|---|
| `ModelExecutionMailbox` | 新增 `FILE_CANCEL_DEATH = "cancel.death"` + `writeCancelDeath`（defensive，不抛） |
| `ChatStreamOffloadHandler` | `deathCleanup` 标志**只在 `ModelWorkerDiedException` 抛出点置位**（`var` 声明在 try 之外，finally 可读）；finally 据此二选一 |
| `PartialStreamRecoveryPolicy.decide` | `cancelPresent && !cancelDeathPresent -> SKIP_CANCELLED`；死亡取消**落回与静默死亡同一套闸门**（grace → empty → meta → RECOVER），不新开旁路 |
| `ModelExecutionOrphanReaper` | `cancelDeathPresent` → 永不走 cancelled-orphan 回收（内容交给冷启动恢复，恢复自身会删：SKIP_EMPTY/SKIP_NO_META 删、RECOVER 追加后删） |

关键设计取舍：

- **不豁免 grace**。冻结后解冻的 worker 可能还在追加内容，恢复必须让出这个窗口。
- **不改 wire 协议**。`cancel` 照写，worker 侧零改动，冻结假死场景回到既有自愈路径。
- **不改用户取消语义**。用户点停仍写裸 `cancel` → 仍然 SKIP_CANCELLED + 仍被收割器回收。
- **不放进 RECOVER 捷径**。死亡取消走的是和静默死亡一字不差的闸门，避免「新开一条只有它走的路」。

## 4. 验证

**本地门禁**：`scan.sh --jobs 3` 113 passed / 0 failed；`test_scan.py --jobs 4` ALL 349 CASES PASS。

**独立探针**（`/var/minis/shared/work/loop1030-r2/probe2/`，不依赖 CI）：
`extract.py` 按声明名 + 括号配平把三个判据**逐字**切出（回读源码断言逐字存在），
`Probe.kt` 跑 24 条表驱动断言（决策表 13 + 收割器 7 + 信箱 4，全部字面量期望）：

```
probe result: PASS=24 FAIL=0 / PROBE=GREEN
```

**变异臂 3/3 RED-AS-EXPECTED**（把缺陷塞回去，对应断言必须翻转）：

| 臂 | 变异 | 翻转的断言 |
|---|---|---|
| A1 | 判据退回 `cancelPresent -> SKIP_CANCELLED` | P4/P5/P6/P7（死亡取消不再恢复） |
| A2 | 删掉收割器 `if (cancelDeathPresent) return false` | W6/W7（死亡目录被回收） |
| A3 | 删掉 `cancel.death` 写入 | M2（语义标记丢失） |

**CI**：run `40978657068`（build-apk，`workflow_dispatch`，attempt 1）**success**
00:16:34Z → 00:38:40Z ≈ 22 min；`head_sha 5fd7f0804b0dff73e75cb1d5c9b902a2952eac22` 与本地 HEAD
逐字符一致。日志 1,675,785 B / **NUL 0**；`Summary: 113 passed, 0 failed` ×1、`ALL 349 CASES PASS` ×1、
`^e: ` 0、真 FAILED 行 0；新增用例逐条 PASSED（信箱 2 条、收割器 2 条、决策表 5 条）。

## 5. 残留（带触发条件，登记进 backlog）

- **R1 ack 假杀证据（原 M1）**：`cancel.ack` 在判死后出现即说明 worker 只是冻结。天然代价 =
  要在异常抛出前多等一个 ack 窗口（8s 级）才能改判，会拖慢 UI 的 busy 复位；且 ack 到达时
  `awaitTerminal…` 会按 `writeBarrierSeen` 删目录（内容仍丢，本支不覆盖）。触发条件 =
  真机上再出现「判死后 ack 迟到」日志，或用户报告「明明有回答却提示中断」。
- **R2 停滞判据（原 M4）**：判死与 5min 停滞看门狗都要求 `newLen == lastRead`，一个永不终结的
  尾行会让两者同时失效，只能等 30min 总上限。触发条件 = worker 写出超 grace 时长的不完整行
  （当前 worker 只写完整 JSONL 行，未观测到）。
- **R3 appendMessage 回读（原 M7）**：核实为**已被异常路径天然保护**（`appendMessage` 失败
  抛异常 → 跳过 stamp/delete，目录保留），不改。
- **R4 真机未验**：全部证据 = 编译级 + JVM 级 + 源码形状；未在真机复现「worker 被杀 → 重启后
  半截回答落库」。触发条件 = 下一次自然发生 DIED_MID_STREAM。
- **R5 回收所有权依赖冷启动**：`cancel.death` 目录只能由冷启动恢复回收；若 app 长期不重启，
  目录会滞留（有界：每个 run 一个目录，request.json 可能数 MB）。触发条件 = staging 目录
  体积告警。
