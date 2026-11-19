# FIX-R2-1119-A — Provider URL 日志、Responses EOF 和 Fast Mode

## 1. 身份链与范围

- 任务：`fullscan1119r2/TASK-R2A-provider.md`，基线 `8771018e122205b7bf9db90e2a7ec83180616e3e`。
- Worktree：`/var/minis/shared/work/r2rest/provider`；分支 `fix/r2-provider-1119`。
- **F-09、F-18 已修；F-19 先证成立，但生产接线涉及禁改文件，仍未修。**
- 代码提交 `388011497ef334179176f69661ff39bc5cdab240` 改了 `provider/openai/OpenAIProvider.kt`、`scripts/scan/log_url_redaction_guard.py`、`OpenAIProviderUrlLoggingTest.kt`、`OpenAIResponsesEofTest.kt`（4 文件，+265/−16）。其它扫描门、`test_scan.py`、`ModelExecutionDispatcher.kt`、`ModelExecutionService.kt` 均未改。

## 2. 按语义先证

- **F-09（P0，C/X）**：基线 `OpenAIProvider.kt:728/1773/1880/2051` 把完整请求或下载 URL 插入 `AppLogger`；`AppLogger.log` 将同一消息发到 logcat，并在日志开启时排入持久化队列。旧门对 `OpenAIProvider.kt` 的两个多行调用首行全部放行。基线 Provider 原文加旧豁免得 rc=0；同一份源码只去掉豁免立刻得到四条发现。另核实：当前 `LLMRequestLog.redactURL` 认识 `signature`/`sig`，不认识原报告点名的 `X-Amz-Signature`。下载复用的网络追踪器也会记录请求 URL；不能只在 catch 里包现有函数就结案。
- **F-19（P1，C/Z）**：`MinisApp.kt:253` 启动 `:modelservice` 时预热 `FastModePrefs`；`setEnabled` 更新的是写入进程自己的缓存，`SharedPreferences` 的监听不跨进程。`ModelExecutionService.onStartCommand` 可在进程仍有请求时接收下一条；基线 `OpenAIProvider.kt:3317` 只读 worker 缓存，而派发 JSON 没有此字段。只用本地模拟 HTTP 验证实际出站 `service_tier`：缓存为 `true` 发 `"priority"`，缓存为 `false` 字段缺失。没有测真实计费或 Android 双进程切换；缺失的调用边从源码确认。
- **F-18（P1，D/Z）**：基线 `response.output_item.added`（`:1103–1118`）在缺 `item.id` 时把 `call_id` 当**索引键**，累加器本身仍保留空 `itemId`，发出 `ToolUseStart("call_a")`。正常结束分支读 `acc.itemId`，基线 EOF（`:1530–1538`）却读 map key，因而发出 `ToolCallComplete("call_a|call_a")`。下游按完整 id 配对工具块，不会替此分支去掉第二段。复现实例是 HTTP 读取正常走到 EOF、但 SSE 未发工具或响应终帧；不把抛出网络异常的分支混作 EOF。

## 3. 修法

- **F-09**：流式请求、原样转发、生图 POST 三个日志点复用 `LLMRequestLog.redactURL`，只改日志值，不改请求 URL。签名图片二跳的异常不能依赖已知 query 名单，因此用 OkHttp `HttpUrl.redact()`，只保留源站和占位路径；无法解析时仅显示 `<invalid URL>`，附 item 索引及异常类型，不带原始异常消息。二跳复用原客户端的连接池和超时，关闭该跳的通用 Provider trace（避免再次记录出站签名，也不再把对象存储打进模型健康统计）；非 2xx 只记 HTTP 状态和 item，不读可能回显签名的响应体。门移除三条仅匹配多行调用首行的豁免（其中一条是迁移后的旧追踪文件），无新增白名单；负臂使用生产相对路径，能击中路径级错误豁免。
- **F-18**：EOF 遍历 `responsesToolCalls.values`，用 `combineResponsesAPIIds(acc.callId, acc.itemId)`。有 item id 时组合格式不变；无 item id 时不凭空加第二段。保留既有截断信号和工具执行保护。
- **F-19**：未落半截字段。正确改法见 §7；不复用用户 `extra_body` 等无关字段夹带内部标志。

## 4. 本地行为与门输出

定向 `kotlinc` + JUnit（真实 Provider、OkHttp 追踪器和 `AppLogger`，Android 叶子用桩，HTTP 全是 MockWebServer 回环）：`OK (74 tests)`。七条新增测试中：五条采集真实 `LogWriteQueue` 的持久化前日志，覆盖三处请求日志、签名下载截断及 403 响应体回显；两条检查 SSE 缺/有 `item.id` 的 EOF 字面量 `call_a` / `call_a|fc_a`，同时断言 Start、Delta、Complete 同 id 并保持 `truncated=true`。所有凭据用例均为字面量假值；未读取真实 Provider token 或设备历史日志。

在 `git status --porcelain` 为空的代码树直接执行（门 CLI 的仓库根参数为 `.`）：

```text
$ python3 scripts/scan/log_url_redaction_guard.py .
✅ log url guard: no whole url value reaches a log — judged by value flow across all evaluation forms (interpolation / concatenation / brace-nested / aliased binding) (audited sites: 15)
$ python3 scripts/scan/test_scan.py --single=test_log_url_redaction
test_scan.py: unknown test 'test_log_url_redaction'
$ python3 scripts/scan/log_url_redaction_guard.py --self-test
URL_REDACTION_SELFTEST=PASS (31 arms)
```

退出码依次为 `0 / 2 / 0`。`test_scan.py` 基线没有登记这道门的单项夹具，不能把 rc=2 报成绿；在禁止改夹具调度器的约束下，用门自身单项自测补位。

## 5. 变异负控

全部在工作树外逐个运行；编译错误不计为红。已修项 **8/8 精确翻红**（F-09 七臂含门自身，F-18 一臂），另有 **F-19 基线诊断 1 臂**，不是修复验收。

| 条目 | 独立回退 | 预期且实测 |
|---|---|---|
| F-09 ×4 | 分别恢复三处请求的原始 URL、恢复下载异常的原始 URL | 每次只有对应日志行为断言失败；收紧的门 rc=1，恰 1 条、定位到变异调用 |
| F-09 ×2 | 二跳恢复有 trace 的客户端；另一次恢复下载错误体日志 | 分别只由签名下载负例、403 回显负例击中 |
| F-09 ×1 | 在门测试进程里恢复两个 Provider 多行首行豁免 | 自测 rc=1；精确击中生产相对路径的 INFO/WARN 两条负夹具 |
| F-18 ×1 | EOF 改回索引键 | 缺 item id 臂失败（`call_a` 变成 `call_a\|call_a`）；保留 item id 臂仍绿 |
| F-19 ×1 | 基线诊断把 Fast Mode 缓存读取改成恒 true | 缓存 false 应无字段的出站断言变红；只证明探针能看到字段差异，未证跨进程修复 |

## 6. 云端代码验证

`build-apk.yml` 的 API `head_sha` 与上述非报告代码提交逐字符一致，`conclusion=success`。`scan-gate` 日志实际出现 `[19/115] Log URL redaction guard` 及本门通过行，汇总 `115 passed, 0 failed`、`ALL 387 CASES PASS`（总夹具不包含本门未注册的独立自测）；unit-tests 日志 10,204 条 `PASSED`、0 条 `FAILED`，七条新增测试逐条 `PASSED`。lint、APK 构建成功；分支没有移动发布 tag 或发 Release。

## 7. 未修项与覆盖边界

- **F-19 继续开着**（既有 `§FULLSCAN-1119-R2`）：触发为长活 worker 上切换 Fast Mode 后发起新的 GPT Responses 请求；落地需在实际路径 `sandbox/offload/ModelExecutionDispatcher.kt` 写 `fast_mode`（显式 true 和 false），并在禁改的 `sandbox/offload/ModelExecutionService.kt` 的 `executeRun` 和 `executeStreamingRun` 两个构造点将布尔值交给当次 `OpenAIProvider`，只有缺字段才沿用 worker 缓存。流水线没有通用字段自动透传；只改写端和 Provider 读取点会静默失效。本支未获两处 worker 装配点的范围豁免。验收需覆盖 false 覆盖 true、true 覆盖 false、缺字段沿用缓存、至少一条断接线的负控；本次基线探针不能替代这些。
- **门注册**：本门 `--self-test` 未进公共夹具调度；若要 CI 也逐臂跑，须另行允许改 `test_scan.py`。本支保留现有其它已审计豁免，没有扩大扫描目录。
- **台账边界**：本支没有回读或处理历史日志；三处普通请求仍只按 `LLMRequestLog` 的既有敏感查询键约定脱敏，不声称覆盖所有日志、用户自定义键名或任意错误体。未作真机、真实上游或实际计费验证。

## 8. 复现与停止判据

在本 worktree 运行 §4 的门与自测命令。行为用例在 `src/android/app/src/test/java/com/rikkaminis/app/provider/openai/{OpenAIProviderUrlLoggingTest,OpenAIResponsesEofTest}.kt`。本会话定向探针位于 `/var/minis/workspace/r2a-provider-probe/`：`run.sh` 编译运行，`PYTHONDONTWRITEBYTECODE=1 python3 mutate.py` 跑已修项变异；`FastModeBaseline.kt` 和 `mutate-fast-baseline.sh` 仅用于诊断。

报告与代码等价判据：

```sh
git diff --exit-code 388011497ef334179176f69661ff39bc5cdab240..HEAD -- . ':(exclude)fix-reports'
```

只提交报告后核对非报告差异为空。不合 main；报告之后不再触发 CI。
