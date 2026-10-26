# FIX-R3-t13 — voice_cancel_guard_check + modelsvc_single_read_check（第三轮 dual-gate bypass 收口）

## 1. 门与缝（本支管什么）

| 门 | 判据中心 | 本波缝 |
|---|---|---|
| `voice_cancel_guard_check` | 语音/HTTP 取消传播：watchdog hook、OpenRouter 取消归属、Xunfei 空转写守卫 | ①阻塞读搬进吞异常的 catch-all；②finally 少 cancel 看门狗 job；③对称厂商（Doubao/Deepgram）的空串守卫 |
| `modelsvc_single_read_check` | offload 单次读 + 有界池 + 捕获包装 | ④handleClient 装配段把 cwd/sessionId 换常量（accept loop 与 handler 两门之间）；⑤包装类方法体返回常量；⑥submit 的入队半边 |

## 2. 失效轴（本波 6 条的分布）

| 轴 | 条目 | 本质 |
|---|---|---|
| dual-gate（跨门缝） | #145 #146 #148 | 补丁落在两门判据之间的空白：门 X 管前半段链、门 Y 管后半段链，**装配/收尾段没人管** |
| symmetric（对称轴） | #147 #149 #150 | 同文件里对称的另一半（另一厂商解析器 / 包装类方法体 / 队列半边）没有对应判据 |

与前三轮同族：判据锚「某物存在」而非「效果达成」——修法全部按锚「从树/数据流派生决定点 + 效果断言」重写。

## 3. 见证重放表（先反攻后动手）

每条在工作树上手工构造最小编辑（`probe/t13-w<k>.patch` 存档），跑 `gates_involved` 的每一道门，还原后 sha256 校验一致（`git status` 全程干净）。**6/6 全部 BLIND_CONFIRMED（两门都 rc=0），无一 CAUGHT、无一 NOT_REPRODUCIBLE。**

| # | kind | 目标文件 | 修前 rc（门X/门Y） | 判决 |
|---|---|---|---|---|
| 145 | dual-gate | OpenRouterModelsApi.kt | voice 0 / provider_ua_parity 0 | BLIND_CONFIRMED |
| 146 | dual-gate | VoiceProvider.kt | voice 0 / provider_ua_parity 0 | BLIND_CONFIRMED |
| 147 | symmetric | VoiceProviderVendors.kt | voice 0 | BLIND_CONFIRMED |
| 148 | dual-gate | NativeOffload.kt | modelsvc 0 / offload_cli_args 0 | BLIND_CONFIRMED |
| 149 | symmetric | ModelExecutionService.kt | modelsvc 0 | BLIND_CONFIRMED |
| 150 | symmetric | NativeOffload.kt | modelsvc 0 | BLIND_CONFIRMED |

主会话复证标注均为 NEEDS_MANUAL；本支逐条手工重放后全部实锤。数据：`probe/t13-pre.json`。

## 4. 修法（逐条，全部走共享原语，无自写词法器）

**改动面**：2 个门脚本，+747/−10；生产 `.kt` 0 行；`scan.sh` / `test_scan.py` / `_kotlin_*` 三件套零触碰；门 112 / 夹具 344 不变。

### voice_cancel_guard_check（+413）

1. **#145 阻塞读的取消归属**：新增 `try_blocks()`（只用 `match_bracket` 配平解析 try/catch/finally）+ `swallowing_ce_handler(cv, pos)`——按 Kotlin 子句匹配顺序，CE 由**最先能接住它**的 catch 子句决定归属；该子句类型 ∈ {CancellationException, IllegalStateException, RuntimeException, Exception, Throwable} 且块内**无可达 `throw`** ⇒ 吞。判据：每个阻塞读（`body?.string()/bytes()/charStream/…`，`BLOCKING_READ_RE`）都必须不在这种子句的罩内。旧判据只看 `executeOrCancel()` 有没有待在带 CE 重抛的 try 里，把 `response.body?.string()` 整体搬进 `catch (_: Exception) { return emptyList() }` 两门全绿。
2. **#146 看门狗必须被停**：新增 `watcher_binding()`（派生 `val <X> = launch { … awaitCancellation() … }` 的名字，不写死 `watchJob`）+ `finally_span()`。判据：派生出的 job 必须有**活的** `<X>.cancel()/cancelAndJoin()`（`path_feasible`），且落在包裹 `executeOrCancel()` 的那个 try 的 **finally 块内**。`dispose()` 只解绑完成回调，不取消 job——少了这半条链，结构化并发让 withContext 永远等 `awaitCancellation()`，HTTP 200 读完照样挂死。
3. **#147 厂商族对称判据**：新增 `vendor_parsers()` + `check_vendor_transcripts()`——对文件里**每一个** `parseVoiceInputResponse` 覆盖（按最近 class 声明归属厂商），返回的 `text = <expr>`（`named_args()` 取实参、绑定回溯一跳到声明右值）必须同时有：空串守卫（`takeIf{isNotEmpty}/takeUnless{isEmpty}/ifEmpty/ifBlank`，`GUARD_OK_RE`）+ 拒绝路径（`throw`）。缺守卫 ⇒ 红并点名厂商；有守卫无 throw ⇒ 红带「拒绝路径没了」。旧判据只认 Xunfei 一个类，同文件对称的 Doubao/Deepgram 只活在注释里。
4. **误伤修复（cause_gated 角色拆分）**：完成回调形参（`cause`）只可能非 null——`if (cause != null) call.cancel()` / `cause is CancellationException` 是**活**守卫，旧版把任何引用 cause 的条件都判死（档案「误伤检查」第 1 条实测）；只有 `cause == null` 才死。`why_dead(..., role="hook"|"catch")`：hook 角色仅 `_is_null_test` 判死；catch 角色保持原行为（`e.message ==` 内容开关仍死）。clean 臂固化。
5. 自测臂数 27 → **37**（新增：body-read try/finally clean、rethrow-catch clean、swallow-catch red、watcher-not-cancelled red、cancel-outside-finally red、cause-nonnull clean、vendor-family clean、unguarded red、no-rejection red）。收尾标记：`VOICE_CANCEL_GUARD_SELFTEST=PASS (37 arms)`。

### modelsvc_single_read_check（+336）

1. **#148 装配段转发**：新增 `check_dispatch(code)`——对文件里每处 `NativeOffloadRequest(` 构造（跳过类型声明本身），具名实参（`named_args`）必须含 `cwd` 与 `sessionId`；`const_value` 折叠为常量 ⇒ 红；`value_sources` 派生不出 `cwd`/`env` 标识符 ⇒ 红。缺实参 fail-closed。accept loop（池侧）与各 `*OffloadHandler`（消费侧）都合规时，装配段是两门之间的空白。
2. **#149 包装类方法体**：新增 `captured_body_methods()`（class 体定位 + `function_body` 逐方法）+ `check_capture_body(code, lit)`——`streamingFlagOrFalse` 必须 `const_bool/const_value` 折叠不出常量、必须引用 `json`、必须含 `optBoolean("streaming"`（字面量视图判字符串）；`jsonOrThrow` 同族（须 `json.getOrThrow()`）。调用点全在、值全错：常量 false 让队列帧守卫与流式 ACK 屏障都走非流式路径。
3. **#150 入队半边**：新增 `check_enqueue(code)`——`submit` 函数体内每个 `pending.add*(…)` 必须：可达（`path_feasible`）、**不被 permit 判定包住**（`enclosing_conditions` 撞 `permits`/`tryAcquire`/派生的 permit 局部变量 ⇒ 红）、携带 `item` 实参（`_first_param` 派生形参名）。信号量半边查得再细，队列半边 `if (!acquired) pending.addLast(…)` 让池未满时的几乎每次提交丢 item。
4. **误伤修复（capture_binding 类型标注）**：`val requestBody: CapturedRequestBody = capture { … }` 是 100% 等价写法；旧正则只认 `val x =`，显式类型标注被误判「结果未绑定」（档案「误伤检查」第 2 条实测）。绑定的身份由名字决定，类型标注可选。clean 臂固化。
5. 夹具扩充：GOOD_SERVICE 补 `CapturedRequestBody` 类（真树同构）、GOOD_OFFLOAD 补 handleClient 装配段（真树同构）——让新判据有 clean 基线。自测臂数 22 → **28**。收尾标记：`MODELSVC_SINGLE_READ_SELFTEST=PASS (28 arms)`。

### dual-gate 缝的归属说明（任务书 §2.2 要求）

- **#145**：`provider_ua_parity_check` 管 UA 转发对称性，**不该管**取消归属这条缝；由 `voice_cancel_guard_check` 认领（判据 1）。修后 voice rc=1 / provider_ua_parity rc=0（读数见 §3.3）。
- **#148**：`offload_cli_args_check` 管 handler 侧实参消费，**不该管** socket 帧装配段；由 `modelsvc_single_read_check` 认领（判据 1）。修后 modelsvc rc=1 / offload_cli_args rc=0。

## 5. 四层验证证据

### 5.1 修后反攻（L2）

6/6 补丁重新应用，全部 rc=1 且红在自己那条判据上；还原后 sha256 一致、`git status` 干净（`probe/t13-post.json` + `probe/t13-w*.patch`）：

| # | 门的报错行（摘） |
|---|---|
| 145 | `OpenRouterModelsApi.kt:84: the blocking body read 'body?.string(' sits inside a 'catch (Exception) { … }' with no reachable rethrow — … (da1020 ISSUE-05 D)` |
| 146 | `VoiceProvider.kt: the watcher job 'watchJob' is never cancelled — dispose() only detaches the completion hook, … (da1020 ISSUE-05 A)` |
| 147 | `VoiceProviderVendors.kt: DoubaoVoiceProvider.parseVoiceInputResponse returns an UNGUARDED transcript ('json?.optJSONObject(…)?.optString("text") ?: throw …') — optString yields "" …` |
| 148 | `NativeOffload.kt:337: 'cwd = " "' is a constant — the frame-derived value is discarded, so every relative path resolves against '/' (R3 14-2)` |
| 149 | `ModelExecutionService.kt:2055: CapturedRequestBody.streamingFlagOrFalse(…) returns a CONSTANT — the queue-frame guard and the streaming ACK barrier both read this flag; …` |
| 150 | `NativeOffload.kt:464: pending.addLast(…) sits behind the permit verdict 'if (!acquired)' — on the permit-granted path … (R3 14-3)` |

### 5.2 本门自测（L1）

真树 rc=0；`--selftest` 全臂带牙：voice **37 arms** / modelsvc **28 arms**（只增不减：27+10、22+6）。含两条误伤回归 clean 臂（cause≠null hook、typed capture binding）与一条 precision clean 臂（rethrowing catch 内的阻塞读保持绿）。

### 5.3 邻门（L2b）

9 个语义相邻门真树全 rc=0：gemini_base_version、model_entry_override_parity、modeluse_proto_single_source、offload_media_b64_wire、provider_boundary_guard、provider_import_id_roundtrip、provider_ua_parity、rss_probe_lock_scope、xproc_boundary。另加 witness 配对门 offload_cli_args rc=0。

### 5.4 变异轮（L3，`probe/mutate.py`，4/4 被抓）

| 变异 | 门 | 结果 |
|---|---|---|
| m1 `if (false) watchJob.cancel()` | voice | 红（never cancelled）|
| m2 Doubao `takeIf { it.isEmpty() }`（守卫反转） | voice | 红（UNGUARDED）|
| m3 `if (false) pending.addLast(…)` | modelsvc | 红（unreachable enqueue）|
| m4 `jsonOrThrow() = json.getOrDefault(JSONObject())` | modelsvc | 红（`json.getOrThrow()` 派生缺失）|

### 5.5 全量门循环（L4 本地）

`scan.sh` 整份在沙箱 Errno 38 风暴下不可用（本次死于第 13 门，与前四批同症状）；按任务书 §2.4.3 用逐门独立进程循环（`probe/gate_loop.py`，每门独立 subprocess + 0.2s 间隔）：**111/111 rc=0**（109 `*_check|*_guard` + i18n_check + process_logging_gate + turn_tail_finalize_order；分段 0-25/25-50/50-70/70-111，其中 0-25 与 50-70 两段各被环境杀过一次后错峰重跑通过）。读数：`probe/t13-gate-loop.json`。

### 5.6 test_scan

本地跑 `test_scan.py` 被同一 ENOSYS 风暴杀在 test_da1018_gates（`OSError: [Errno 38]`，`/tmp/testscan.log` 237 行）——**ALL 344 交分支 CI 承担**（CI 侧跑同一份 test_scan.py）。

## 6. CI

- **run** `40313395897` / **#2739** / job `121539878999` / **attempt 1** / **success**（`workflow_dispatch`，2026-10-26T11:13:03Z 创建）
- **head_sha 三方逐字符一致**：本地 HEAD = `origin/fix/r3-voice-modelsvc` = CI `head_sha` = **`77ce5ca121e0560988ec8121f040bf7201785b83`**
- **日志** `probe/ci/job-2739.log`，1,487,090 B / NUL 0：

| 读数 | 命中 |
|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1（其余 6 条小 Summary 是子套件 5/10/14/22/28/33 passed）|
| `ALL 344 CASES PASS` | 恰 1 |
| `^ e: `（编译错误） | 0 |
| `VOICE_CANCEL_GUARD_SELFTEST=PASS` | 恰 1 |
| `MODELSVC_SINGLE_READ_SELFTEST=PASS` | 恰 1 |
| 含 `FAILED` 的 6 行 | 全部是 PASSED 用例名子串（根因判读）|

- **步骤**：26 步，唯一非 success = step 23 `Publish to Releases` → **skipped**（main 门控，符合预期）
- 两门新臂在 CI 里有见证（标记各恰 1）⇒ 37 臂 / 28 臂不是只在本地自测里成立。
- 源码树与 CI 绿 sha 的差异 = 0（本节回填提交只动 `fix-reports/`）。

## 7. 残留（每条带触发条件·触发后方案·搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `streamingFlagOrFalse` 的 **default 翻转**（`optBoolean("streaming", true)`）不被抓——判据只断言 `optBoolean("streaming"` 存在 | 第三轮之后的新攻击用 default 翻转打同一条缝 | 断言 `.getOrDefault(false)` 字面量或用 `value_sources` 追默认值实参 | 对称轴主形态（整方法换常量）已封；default 翻转无攻击实例，先记录 |
| R2 | `BLOCKING_READ_RE` 只认 `body` 接收者链上的五种读法；`response.peekBody(1 shl 20).string()` 之类间接读不匹配 | 同族见证用 peekBody/cache 形态出现 | 扩成接收者链分析（`value_sources` + 方法名白名单） | 现有五种覆盖真实代码全部读点；间接读属新形态 |
| R3 | `check_dispatch` 的来源锚是标识符 `cwd`/`env`——装配段把帧值改名（`val dir = cwd`）会 fail-closed 红 | 正当重构改装配段局部名 | 加一跳绑定回溯（`decl_bindings`）后再判 | fail-closed 方向安全（红了会逼出显式改门），不是静默放行 |
| R4 | `check_vendor_transcripts` 只覆盖 `VoiceInputResponse(text = …)` 构造形态；走 builder/另类返回的厂商解析器静默跳过 | 新厂商解析器不用构造器形态返回 | 加「每个 override 必须至少贡献一个 text 实参」的站点计数断言 | 当前文件三个解析器全是构造器形态；站点计数防的是未来形态 |
| R5 | 门内新增的 `named_args`/`try_blocks`/`finally_span`/`watcher_binding` 是通用形态，voice 与 modelsvc 各写了一份 `named_args` | 主会话并集收口时统一上收 | 提升为 `_kotlin_ast`（try_blocks/finally_span）与 `_kotlin_valueflow`（named_args/watcher_binding）共享 API，两门改 import | 任务书硬约束 2：不许自己改共享层 |

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t13
python3 scripts/scan/voice_cancel_guard_check.py .          # 真树绿 rc=0
python3 scripts/scan/modelsvc_single_read_check.py .        # 真树绿 rc=0
python3 scripts/scan/voice_cancel_guard_check.py --selftest # VOICE_CANCEL_GUARD_SELFTEST=PASS (37 arms)
python3 scripts/scan/modelsvc_single_read_check.py --selftest # MODELSVC_SINGLE_READ_SELFTEST=PASS (28 arms)
python3 probe/replay.py pre   # 6/6 BLIND_CONFIRMED（修前）
python3 probe/replay.py post  # 6/6 CAUGHT rc=1（修后，红在自己判据上）
python3 probe/mutate.py       # 4/4 变异被抓
python3 probe/gate_loop.py    # 111/111 rc=0（逐门独立进程；分段 --from N --count M）
```
