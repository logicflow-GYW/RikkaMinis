# FIX-WORKER-KEY-HANDOFF — 多开时换 API key，几个（不是全部）会话在下一轮请求上中断

分支 `fix/worker-key-intent-handoff`（基线 main `039c9ab2`）
代码绿 sha `accb0fbf` · CI `41016096955` success · 2026-10-30
报告提交在绿之后，`git diff --name-only accb0fbf..HEAD -- . ':(exclude)fix-reports'` = 空。

## 1. 现象与取证

用户报告：多开 agents 跑任务 → 模型组前一个成员额度耗尽 → 到设置里换成有额度的新 key
→ **几个正在跑的会话在 mid-stream 中断，不是全部**，概率不低。

代码路径（全部在当前树上逐行读过，不是按报告行号）：

| 环节 | 位置 | 事实 |
|---|---|---|
| key 存放 | `ProviderRepository.saveApiKey/loadApiKey` | `EncryptedSharedPreferences("provider_secrets")`，槽位 `apikey_<instanceId>` |
| worker 取 key | `ModelExecutionService.kt` 两条 run 路径 | 各自 `safeCreate(this,"provider_secrets").getString("apikey_...")` |
| 陈旧检测 | `WorkerKeyFreshness` | 记录 `provider_secrets.xml` mtime 基线，被主进程改写即判 stale |
| stale 处理 | `abortOnStaleKeyCache` | 置 `keyCachePoisoned` + 本请求写 transient 错误 + 延迟自愈 |
| 准入闸门 | `onStartCommand` | `queuedNow >= liveQueueAdmission() || poisoned` → 拒绝并 `stopSelf` |
| 进程回收 | quiescence 看门狗 | 只在 `activeRequests == 0` 时自杀 |
| 客户端重试 | `ChatViewModel` | 5 次，`transientRetryDelaysSec` = 1+2+4+8+16s ≈ **31s** |

日志证据（`/var/minis/logs/`）：

- **多开受害面**（10-28 21:26，pid 3064）：`active=3` → 某条流结束后 269ms 触发
  `stale_key_cache: poisoning worker` → 随后 3 条 `DIED_MID_STREAM`。
- **单开无感面**（10-29 15:15，会话 4a8978e3）：
  `chat stream offload` → 298ms 后 `Transient error on tierflow, retry 1/5 in 1s:
  stale_key_cache` → 1s 后重派 → `first-content after=9691ms`，用户什么都没看到。
- 反面排除：同一窗口 `rss=381–440MB / native=87–92MB`（anon≈215–275MB ≪ ELEVATED 450），
  无 Fatal signal / lmkd / tombstone ⇒ 与内存门、系统杀无关。

## 2. 根因：请求级拒绝，不是进程级死亡

Android 的 SharedPreferences 是**进程内单例缓存**，worker 的副本冻结在进程出生时刻。
主进程写入新 key 后 worker 读不到 ⇒ `WorkerKeyFreshness` 必须让这条请求失败、靠客户端
重试落到新进程。而毒化进程只有在 `activeRequests == 0` 时才死，于是多开时：

| 改 key 那一刻 agent 在做什么 | 结果 |
|---|---|
| 正在流式收 token | **不受影响**（检查只发生在新请求起点） |
| 正在跑工具（无在飞请求） | 不受影响 |
| 正要发下一轮请求 / 排队等槽位 | 撞上 `stale_key_cache` → 重试 5 次 ≈31s 全落在窗口内 → 该轮失败停下 |

兄弟会话的流把毒化进程一直撑住 ⇒ 窗口 ≫ 31s ⇒ 窗口内换轮次的几个必死，其余活下来。
**这正好是"几个停、不是全停"**，也解释了为什么概率不低。单开时没人拖住进程，0.5s 内
进程死掉，第一次重试（1s）就落到新进程 → 完全无感（10-29 15:15 的实证）。

## 3. 修法：密钥随 dispatch Intent 逐请求下发

- 新增 `WorkerKeyHandoff`（单一事实源）：`keyFor(context, instanceId)` /
  `attachKey(intent, apiKey)` / `pickKey(carried){prefs}` / `carriesKey(carried)`。
- `ProviderExecutionGateway` 是唯一持有 `ProviderInstance` 的主进程层，`send` 与 `stream`
  各解析一次 key 并显式下传（`ModelExecutionDispatcher.dispatch(..., apiKey)` /
  `ChatStreamOffloadHandler.stream(..., apiKey)`）。
- worker：`onStartCommand` 读 `EXTRA_API_KEY` → 传入两条 run 路径 → `pickKey` 优先用
  carried，缺失才读 prefs；带 key 的请求跳过 `WorkerKeyFreshness`（它本来就是最新的）。
- 毒化准入放宽：`poisoned = keyCachePoisoned && !carriesKey(carried)`——一种 keyless
  dispatch（如无 `instance_id` 的 `minis-model-use`）不能再饿死同池兄弟。

三个设计决定及其依据：

1. **不走 request.json**：那份文件落盘，项目有明文密钥不落盘的既定规则（KeyRoulette
   KDoc 明写"the plaintext never touches disk"，request.json 链正是照着这条设计的）。
   Intent extra 走 Binder（RAM），且服务返回 `START_NOT_STICKY`，系统不会重投带 key 的 start。
2. **不在 dispatch 层重新解析请求包取 `instance_id`**：聊天包带 base64 图可达数十 MB，
   实测 org.json **≈53ms / 2MB**（本仓 JVM 实测），另加同量级临时对象图，且每 attempt 一次。
   改为网关显式下传（它本来就有 instance）。
3. **双向向后兼容**：旧主进程 + 新 worker（无 extra）→ 老路径；新主进程 + 旧 worker
   （extra 被忽略）→ 老路径。不需要版本握手。

## 4. 验证

| 项 | 结果 |
|---|---|
| 本地门 `scripts/scan/scan.sh` | **113 passed, 0 failed** |
| 门 51 `modelsvc_single_read_check` | 原判据把执行器签名钉成两参字面量；按其"执行器接收已解析 body"的立场放宽为前两参前缀匹配，`--selftest` **28 臂全过**，真树 PASS |
| 纯判据 JVM（逐字切片自真源） | `WorkerKeyHandoffTest` **6/6** |
| 变异臂 | A1 去掉 carried 优先 / A2 `carriesKey` 恒真 / A3 carried 被 trim → **3/3 RED-AS-EXPECTED** |
| 形状判据（Python 复刻 + JVM 双跑） | 14/14，每条带负控（摘掉 attach / 阉掉早退闸门都会红） |
| 括号与重名检查 | 6 文件 OK |
| **CI** `41016096955` | **success** 20.7min，`head_sha accb0fbf…` 与本地 HEAD 逐字符一致；27 步 = 25 success + 2 skipped（rolling tag / Publish，main 门控）；日志 `Summary: 113 passed, 0 failed`、`ALL 349 CASES PASS`、`e: file` 0、真 `FAILED` 0、新测试 18 条 PASSED |

首轮 CI（`8a00867e` → run `41014009976`）红在**既有形状测试**
`ModelExecutionSingleReadTest.serviceSource_handsTheAlreadyParsedBodyToBothExecutors`
（它和门 51 钉同一件事，同样两参字面量）；按同一立场放宽为前缀匹配后绿。
8563 个用例中唯一失败就是它 —— 这轮红的定位价值：**门与单测各钉一遍同一契约，
改签名必须两处同步收口**。

## 5. 残留（都带触发条件）

- **R1 真机未验**：证据 = CI 编译级 + JVM 级 + 源码形状；未在真机复现"多开 + 换 key"。
  ｜触发条件：用户下次换 key 时观察；或 `grep "stale key cache" minis-*.modelservice.log`
  仍能命中即说明有路径没走新通道。
- **R2 非流式内部重试复用同一 key 快照**：`dispatchWithRetry` 的各 attempt 共用网关解析的
  那一个值（一次 dispatch 内换 key 不生效）。｜触发条件：重试次数或退避大幅加长时。
- **R3 排队中的请求带的是 dispatch 时刻的 key**：排队期间再换 key，这条仍用旧 key（旧 key
  已失效 ⇒ provider 401 ⇒ 走既有 fallback）。｜触发条件：出现"排队几分钟"的长队列。
- **R4 毒化窗口仍在（legacy 路径）**：老版本主进程派发的请求行为完全不变。
- **R5 `minis-model-use` 无 `instance_id`** ⇒ 不走新通道（走 legacy）。
- **R6 Intent extras 的内存态暴露面**：与任何 Binder IPC 同级；不进 `dumpsys` 的常规日志，
  但崩溃/完整 bugreport 可能包含。已保证不写日志、不落盘。
- **R7 新增 dispatch 站点若忘记解析 key** 会静默退回 legacy（有形状测试 + 网关计数钉住，
  但新增第三个 dispatch 形态时需要人工同步）。
