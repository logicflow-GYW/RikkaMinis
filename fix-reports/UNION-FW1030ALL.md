# UNION-FW1030ALL — 七支并集（含用户自开的换 key 支）

基线 main `039c9ab2` · 并集分支 `integration/fw1030all` · 2026-10-30

## 1. 并集构成

| # | 支 | 代码绿 sha | CI | 内容 |
|---|---|---|---|---|
| 1 | `fix/ff07-math-currency-unify` | `098d6765` | — | 数学/货币渲染统一 |
| 2 | `fix/fw-chat-clear-tabs` | `f3474e16` | — | 聊天清空后的 tab 回收 |
| 3 | `fix/fw-dead-shutdown-lever` | `b5cca16d` | — | 删内存压力→回收 worker 的死杠杆 |
| 4 | `fix/worker-key-intent-handoff` | `accb0fbf` | `41016096955` success | **换 key 不再中断在跑的会话**（密钥随请求下发） |
| 5 | `fix/fw-nonstream-terminal` | `6056adc0` | `41018271913` success | 非流式终局判定（Anthropic 200+error / 三家上限截断） |
| 6 | `fix/fw-oauth-utf8` | `a419f5c9` | `41017470845` success | OAuth 回调页 UTF-8 字节长度 + error 出现即终止 |
| 7 | `fix/fw-file-date-future` | `ac488650` | `41016258010` success | 未来 mtime 不再显示成星期名 |

前 3 支先并在 `integration/fw1030b`（`21c0362f`，CI 绿），并集从它起再合后 4 支。

## 2. 合并形态

`--no-ff` 逐支自动合并，**零冲突**。45 文件 +3973/−436，非报告文件 39 个。

跨支重叠文件只有 2 个，都在 `fw-dead-shutdown-lever` × `worker-key-intent-handoff`（同一 offload 面，天然相邻）：

| 文件 | lever | key-handoff | 结论 |
|---|---|---|---|
| `ModelExecutionService.kt` | 6+/22−（删 lever） | 64+/26−（密钥下发） | hunk 区间 [`43,45`…`651,667`] vs [`226`…`1389`] **无交集** |
| `ChatStreamOffloadHandler.kt` | 20+/12− | 10+ | `[116,121]` vs `[111,111]`/`[198,198]` **无交集**（最近间距 5 行） |

合并后符号交叉核：两文件 `shutdownRequested` = 0（lever 真删干净）、`WorkerKeyHandoff` = 6/2、`apiKey` = 10/2、`FIX-FW-DEAD-SHUTDOWN-LEVER` 注释保留 1/2 处 —— 双方改动都在，且 lever 的注释正好解释了 key-handoff 新参数为何不再依赖那个 reader。

## 3. 验证

| 项 | 结果 |
|---|---|
| 本地门 `scan.sh --jobs 3` | **113 passed, 0 failed** |
| 本地夹具 `test_scan.py --jobs 3` | **ALL 349 CASES PASS** |
| 绿 sha..tip 非报告差异 | key-handoff / oauth / file-date 均 = 0（C 支 tip 即绿 sha） |
| 并集 CI | ``41021708433` success 21.9min @ 7a8f5c9f（25 success + 2 skipped = main 门控的 rolling tag/Publish）` |

## 4. 各支要点与残留

### 4.1 worker-key-intent-handoff（用户报告的真机症状）

现象：多开 agents 跑任务 → 换 key → **部分**在跑的会话 mid-stream 中断。根因不是进程被杀，而是**请求级拒绝**：Android SharedPreferences 是进程内单例缓存，worker 的副本冻结在出生时刻，主进程写新 key 后 worker 读不到 ⇒ `WorkerKeyFreshness` 判 stale ⇒ 毒化进程；而毒化进程只在 `activeRequests == 0` 时自杀，多开时另外几条流正跑着 ⇒ 3 条 `DIED_MID_STREAM`。

修法：密钥随派发请求下发（`WorkerKeyHandoff` + `ProviderExecutionGateway` + `apiKey` 参数），worker 不再依赖自己那份冻结副本；legacy 路径（无 `instance_id`、旧主进程）行为不变。

残留：R1 真机未验 / R2 一次 dispatch 内重试共用 key 快照 / R3 排队中请求带 dispatch 时刻的 key / R4 legacy 毒化窗口仍在 / R5 `minis-model-use` 无 instance_id 走 legacy / R6 Intent extras 内存态暴露面 / R7 新增 dispatch 站点忘解析 key 会静默退 legacy（有形状测试钉住）。

### 4.2 fw-nonstream-terminal

- **C1**：Anthropic 非流式 200 + `{"type":"error"}` 原会被解析成「空回答 + stop_reason=null」当成功落库；现按 Gemini 同口径判 error 并抛。
- **C2**：`stop_reason` 从 worker 返回体一路传到调用方（原来在 handler 重建 JSON 时丢掉），并把 `max-tokens`/`max-output-tokens` 等连字符拼写归一进同一张词表。三家非流式输出上限截断因此有信号；**不抛异常**（重试无意义）。
- 附带：`TruncatedToolCallPolicy` 改用归一化后的判据。

### 4.3 fw-oauth-utf8

`Content-Length` 用字节数（原用 `String.length` 字符数，中文页头会短读）+ `charset=utf-8`；`error` 参数出现即终止（原 `isNullOrBlank()` 与渲染分支 `!= null` 不同条件 ⇒ `?error=` 空值渲染失败页却不停服务，白等 5 分钟）。

### 4.4 fw-file-date-future

未来 mtime 在「未来 <24h」时 `daysAgo == 0`（Long 向零截断）⇒ 落到 weekday 分支 ⇒ 刚写的文件显示 "Thursday"。现抽 `fileDateBucket` 纯函数 + 注入 `nowMs`，未来时间显式早退，不落 weekday。

## 5. 未合并、待放行

- 本并集 `integration/fw1030all`
- 合并 main 起手权在用户；放行后三源核查 → `--no-ff` 合 main → push 自动触发 main CI（Publish 真执行）→ 绿后清理 7 支 + 工作树
