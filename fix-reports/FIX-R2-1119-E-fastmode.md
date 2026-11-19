# FIX-R2-1119-E — Fast Mode 跨进程下发（F-19）

分支 `fix/r2-fastmode-1119` · 基线 `900a7b92` · worktree `/var/minis/shared/work/r2rest/fastmode`
代码提交 `93c70bea4b683cdceca01d189342217712b2ba0e`

## 1 缺陷与成立性（先证）

**成立。** 证据（本地 JVM 切片装置 `fastmode-jvm/`，真实生产切片 + `FastModePrefs` 语义复刻）：

| 观测 | 修复前 | 修复后（同一探针） |
|---|---|---|
| 主进程 off、worker 缓存 true、请求体无 `fast_mode` | 非流式 `service_tier=priority`，流式 `priority` | 请求体带 `fast_mode=false`；非流式/流式均**无** `service_tier` |

复现命令：`sh fastmode-jvm/run.sh probe.BaselineTest`
修复前 `OK (1 test)` 并打印 `BASELINE main=false request.fast_mode=ABSENT worker=true nonStream=priority stream=priority`；
修复后同一用例 `Failures: 1`（旧行为已不可复现）。基线输出留在 `baseline-after.log`（修复后跑）与首轮输出记录在本节。

根因与报告一致：`FastModePrefs.prime()` 每进程一次；`SharedPreferences` 监听不跨进程；`OpenAIProvider` 的
`buildResponsesAPIBody` 是唯一读取点，读的是本进程缓存。`:modelservice` 一旦存活，主进程的开关变更永远到不了它。

## 2 修法（三端同改，缺一静默失效）

1. **写端** `ModelExecutionDispatcher.buildRequestJson`（`:310`）：
   `put("fast_mode", FastModePrefs.isEnabled())` —— **无条件**，显式 `true` 与显式 `false` 都写。
   与 `enhanced_cache` 的 present-only 形状有意不同（注释写明原因：present-only 无法表达「关掉」）。
2. **读端 ×2** `ModelExecutionService`：`executeRun`（非流式）与 `executeStreamingRun`（流式）
   各一处 `if (req.has("fast_mode")) { (provider as? OpenAIProvider)?.fastModeOverride = req.optBoolean("fast_mode") }`。
   `has()` 而不是 `optBoolean(..., false)`：缺字段 = 旧调用方 = 回落本进程缓存。
3. **Provider** `OpenAIProvider`：新增 `internal var fastModeOverride: Boolean? = null`；
   原唯一读取点改为 `val fastMode = fastModeOverride ?: FastModePrefs.isEnabled()`，判断条件改用该局部值。
   `grep FastModePrefs` 全仓核对：`provider/` 下**仅此一处**读取；未新增第二份判断。

改动阶梯：停在**第 7 级（最小实现）**——第 2 级（现有 `enhanced_cache` 透传链）形状可复用但语义不同（present-only vs 三态），
故复用其装配位置、不复用其判据；未新增协议层、配置项或依赖。

## 3 必测四条（逐条证据）

| 用例 | 结果 | 证据 |
|---|---|---|
| worker 缓存 true + 请求体 `fast_mode=false` | **不带** `priority` | 流式/非流式各一条：`nonStreamExplicitOffBeatsStaleTrue` / `streamExplicitOffBeatsStaleTrue` 绿 |
| worker 缓存 false + 请求体 `fast_mode=true` | **带** `priority` | `nonStreamExplicitOnBeatsStaleFalse` / `streamExplicitOnBeatsStaleFalse` 绿 |
| 请求体无字段 | 回落缓存（true→带 / false→不带） | 四条：`{nonStream,stream}AbsentFallsBack{True,False}` 全绿 |
| 断线负控：删任一读端赋值 | 对应方向变红 | M2（删非流式）→ 2 red；M3（删流式）→ 2 red；见 §5 |

本地装置：`fastmode-jvm/run.sh`（9 例全绿）+ `mutate.py`（5 臂）。仓库内测试：
`FastModeRequestPropagationTest`（真实 `MockWebServer` 上抓 `/responses` 请求体，4 条线路断言 + 2 条形状断言）、
`FastModeWirePayloadTest`（写端 3 条 + 两个装配点独立断言 1 条）。

**期望值写死**：断言的是请求体里 `service_tier` 的存在/字面值 `"priority"`，不是布尔中转值。

## 4 变异负控（5/5 RED-PRECISE）

| 臂 | 变异 | 期望失败数 | 实测 |
|---|---|---|---|
| M1 | 删写端 `fast_mode` 行（退回 present-only） | 5 | 5 |
| M2 | 删**非流式**读端赋值块 | 2 | 2 |
| M3 | 删**流式**读端赋值块 | 2 | 2 |
| M4 | 两处 `req.has(...)` 改成恒真（缺字段被读成显式 OFF） | 2 | 2 |
| M5 | provider 忽略 override，直接读缓存 | 4 | 4 |

M4 预期第一次被我写成 4，实测 2：`optBoolean` 缺省 false ⇒ 去掉 `has` 后「缺字段」变成显式 **OFF**，
只有两条「缺字段回落 true」的臂能看到它。按实测语义修正预期并在脚本注释里写明——不是调预期凑绿。

装置纪律：快照字节 + `finally` 写回 + sha256 复核（`mutate.py` 断言还原成功），禁用 `git checkout --`。

## 5 未改项 / 边界（诚实分层）

- `FastModePrefs` 本身**未动**（本支禁碰）：worker 内缓存依旧不会自更新；本修法的语义是「每次请求由主进程携带现值」，
  不是「让 worker 自己感知变更」。若将来有绕过 dispatcher 的直连路径（同进程内），仍读本地缓存——这是既定契约。
- 未真机验证跨进程实际行为（需安装构建并切换开关观察账单字段）；本地证据是生产切片 + 真实 provider 线路断言。
- `LLMModel` 的 gpt 家族判据（`model.id.contains("gpt")`）未改，属既有语义。

## 6 变更面

5 文件 +368/−1：生产 3（`OpenAIProvider.kt` +23/−1、`ModelExecutionDispatcher.kt` +10、`ModelExecutionService.kt` +19），
测试 2（新增 165 + 152）。未触碰 `scripts/scan/**`、`backup/**`、`ui/**`、`data/**`、其它 `sandbox/offload/*`。

## 7 CI

代码 `93c70bea` 分支 CI：run **#3355 / `44517081092`**，五 job 全 success（unit-tests / build-apk / lint / scan-gate / publish），
`head_sha` 逐字符 = `93c70bea4b683cdceca01d189342217712b2ba0e`；unit-tests **10259 PASSED / 0 真 FAILED**，
新增两个类逐条 PASSED（`FastModeRequestPropagationTest` 6/6、`FastModeWirePayloadTest` 4/4）；
scan-gate `115 passed, 0 failed`，日志带 `scripts/scan/** untouched in this push — skipping the fixture self-test`（本支未动门，绿不含夹具证据）；
publish 的 `Move the rolling tag` / `Publish to Releases` 两步 skipped（main 门控）。
**报告提交后不再 dispatch**；分支绿 + 非报告差异 0 = 停止节点，不合 main。

## 8 残留登记

- 无功能性残留。可复用一条：**present-only 透传只能表达「开」，任何「关也要生效」的跨进程开关都必须写成显式三态**，
  且读端判据要用 `has()`——用 `optBoolean(..., false)` 会把「旧客户端缺字段」误读成显式关闭（M4 实测）。
