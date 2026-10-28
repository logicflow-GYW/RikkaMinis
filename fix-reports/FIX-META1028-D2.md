# FIX-META1028-D2 — offload/IPC P0 四条（B05 货架 F-01..F-04）

| 项 | 值 |
|---|---|
| 分支 | `fix/meta1028-d2-offload-p0` |
| 基点 | `bfb5b0e6`（`integration/1028b` 合并后的 main） |
| 来源 | 货架报告 B05 `reports/r2/Report__4__1.md` F-01..F-04（declared high，本任务按 P0 派单） |
| 报告文件 | `fix-reports/FIX-META1028-D2.md` |
| 探针 | `/var/minis/shared/work/meta-audit-1028/probe-d2/`（树外，不入库） |
| 工单 | `/var/minis/shared/work/meta-audit-1028/fix-tasks/TASK-D2.md` |
| 停止节点 | 分支 CI 绿 + head_sha 逐字符一致 |

---

## 1. 报告证据与本 Masjid 树的差异表（先证，再动手）

主会话在派单时已提示「证据转写有损」。四条全部回真码核过，**逐人工 fragment 对照**结果如下：

| ID | 报告声称 | 真码（本支基线 `bfb5b0e6`） | 差异性质 | 结论 |
|---|---|---|---|---|
| F-01 | `ModelExecutionDispatcher.kt:336` 的 `dispatch()` 在 `:337` 短路，`stale_key_cache` / `worker_queue_full` 永不重试 | 短路**语义等价存在**（`if (first.result != null) return first.result`），但行号不同、外形是 `if (first.result != null) … return if (first.workerDied)` 而非报告抄的 `|| !first.workerDied`；worker 两个写码点属实（`Service.kt:273-277` 准入、`Service.kt:930-932` `executeRun` 前置检查） | 报告引用的`||`形态是旧版/变送体液あの THAT; 短路後スの practical consequence 完全一致 | **真** |
| F-02 | `ModelUseOffloadHandler.kt:169` `--output` 未剥离 `file://`，写进 `<rootfs>/file:/…` | 真码 `:169-171` 确为 `if (raw.startsWith("file://")) raw else resolveMaybeRelative(...)`，KDoc 自称 "downstream stripping"；全文 `removePrefix("file://")` 只在 `:1234` `resolveImageUrl`；`writeRunOutput`/`shapeRawPassthrough` 零剥离 | 逐字节吻合（行号 169→170 偏 1） | **真** |
| F-03 | `AccessibilityOffloadHandler.kt:856` `waitAppear` 在 `for (root in rootNodes())` 内部判 `found == present`；无选择器恒空；多条件是 OR | 真码 `:848-870`：**原形与使用点完全属实**，但报告抄的参数是 `--timeout-ms`/`svc.findByText(...)`，真码是 `--timeout`/`findByTextOrDesc(...)`；`:866` 超时确是 `ok(...)` exit 0 | API 名字异同科夫尼尔(“hyaening ”)不影响缺陷 | **真**（三小缺陷全属实） |
| F-04 | `CalendarOffloadHandler.kt:1475` `invertedTimedRange` 的 `!isAllDay` 豁免让 `create --all-day` 倒置 `--end` 通过 | 真码 `:1475-1476` 逐字节一致；`doCreate:459` / `doUpdate:655` 两个调用点都在，注释确实误称 da1017-D2 已覆盖全天 | 逐字节吻合 | **真** |

**共同点**：四条都**不是**见证捏造，缺陷 procurement decisions 一致；差异只在于行号漂移与报告给了 GIT 历史某一版的 API spelling。**判定：全部属实，四条全修。**

---

## 2. F-01 — 非流式派发的重试分支永不走

### 2.1 先证（三源）

1. **短路确凿**：`dispatch()` 旧体 `if (first.result != null) return first.result` —— worker 写了 result.json 就返回，`workerDied` 分支只在 `result == null` 时可达。
2. **worker 确实写了 result.json**：`ModelExecutionService.onStartCommand` 准入分支（`:252-281`）写 `{"error": … , "exit_code":3}` + `terminal.json` + `stopSelf`；`executeRun`（`:929-932`）在读到 stale 后 return 错误 envelope，外层 `:419` `writeResultAtomically(dir, result)` 落盘。**两者都不是崩溃**。
3. **流式侧反证**：`Service.kt:1360-1365` 同一情况写 `ChatStreamJsonl.errorLine(STALE_KEY_CACHE, KIND_TRANSIENT)`，客户端 `ChatStreamErrorPolicy` 把 `transient` 映射成 `AUTO_RETRY`。⇒ **同一个 worker 状态，两条 IPC 路径给了相反的 retry 语义** —— 这是本报告最硬的一条不对称。

### 2.2 修法

新增 `ModelExecutionRetryPolicy.kt`（internal object + top-level `dispatchWithRetry`），把「什么能重试 / 重试几次 / 等多久」抽成纯函数，`ModelExecutionDispatcher.dispatch` 只做接线。

| 输入 | 决策 | 理由 |
|---|---|---|
| `workerDied == true`（无 result） | 立即重试一次 | 历史形状，`audit-s3l2` 已论证：证明 worker 死了 ⇒ 不会重复计费 |
| `error ∈ {stale_key_cache, worker_queue_full}` | 等 500 ms，最多再试 2 次 | worker 在**碰 provider 之前**写的这两个码 ⇒ 无 HTTP、无计费；500 ms = 毒化 worker 的 watchdog tick（500 ms 轮询 `activeRequests==0`） |
| `null` result 且未死（超时/IO/服务不可用） | **不重试** | worker 可能仍在生成 ⇒ 二次 provider 调用 = 重复计费/重复生图（`dispatch` KDoc 自 `audit-s3l2` 起就写着这条，本次没有放宽） |
| 其它错误码 / 空 error / 不可解析 JSON | 不重试，原样上抛 | 无法证明没计费；不可解析当负控处理 |

**没有**把 PATCH 建议的「等待旧 worker 退出」实现为额外等待 dead-detect 循环：worker 的 self-reap 由它自己的 watchdog 完成，客户端 poll 不到也不会更快 —— 加了是无 net 收益的额外装置。`WORKER_QUEUE_FULL` 常量搬进 policy，Service 侧不再写字面量（跨 unitsdr 漂移防一手）。顺带给 `executeRun` 的 stale 分支补了 `message`（重试耗尽时用户能看到人话，而不是裸 `stale_key_cache`）。

### 2.3 证据

- 14 例单测（`ModelExecutionRetryTest.kt`），**期望值全部写字面量**，不从被测常量推导（自指陷阱纪律）。
- 含一个 `FakeWorker` 驱动的**真循环**臂组：喂入 result 序列，断言 attempts / delays 序列 —— 预算是量出来的，不是从注释读出来的。
- 5 条变异臂 M1–M5，**每条只红钉它的用例**，baseline 复跑全绿、且 scratch 拷贝与工作树文件和 sha256 逐字节相同。

---

## 3. F-02 — `--output file://…` 写进 rootfs 幽灵路径

### 3.1 先证

链路逐段可读（`resolveGuestHostPath` I:1516、`normalizeLinuxPath` I:1358 同ファイル PRootKernel.kt）：

```
file:///var/minis/mounts/ro_vault/out.txt
  → normalizeLinuxPath ⇒ "file:/var/minis/mounts/ro_vault/out.txt"   ← 不以 / 开头 ⇒ 相对形态
  → isLinuxPathUnderReadOnlyMount      前缀 /var/minis/mounts/ 不匹配 ⇒ false（守卫瞎了）
  → classifySessionPath                正则 ^/var/minis/(attachments|…)$ 不匹配 ⇒ NotSessionScoped
  → resolveHostPath → resolveGuestHostPath 兜底 ⇒ rootfs/file:/var/minis/mounts/ro_vault/out.txt
```

⇒ 三重失效：**只读挂载可写**、**会话隔离丢失**、**字节落进全会话共享的 rootfs**，而响应体仍宣告原 URL。探针里三态都量到了。

### 3.2 修法

`stripFileUrlScheme(raw)`（top-level internal fun）→ `resolveMaybeRelative(strip…(raw), request.cwd)`。**不加** percent-decoding：与既有负决策 `FIX-FILEURL-CONTRACT §7-R2` 同夹具同结论 —— 这是外来输入、仓内无 `file://` 构造端，解码会把「文件名里本来就有 `%XX`」的裸路径反向破坏。HELP 补一条 `file://` 用法示例（契约只贴 häufigsten 用法，不宣称解码）。

### 3.3 证据

`ModelUseOutputFileUrlTest.kt` 8 例 + 探针三态；变异臂 S1/S2/S3 各自变红（S3 是「偷偷加解码」臂 —— 专门钉住上面那条负决策不被下一轮顺手改掉）。

---

## 4. F-03 — `android-a11y-cli wait` 的三个谎言

### 4.1 先证

`MinisAccessibilityService.rootNodes()`（`I:221`）**一个窗口一个 root**（状态栏 / IME / overlay 都在里，`windows` 遍历 + `rootInActiveWindow` 兜底）。真码把判定写在 root 循环体内 ⇒ 只要第一个窗口没有目标节点就返回。三条：

| 形态 | 触发 | 后果 |
|---|---|---|
| (a) `wait disappear` 按 root 判定 | 多窗口场景第一个 root 无目标 | 加载框仍在屏幕上却报「已消失」 |
| (b) 无选择器 | 位置参数 / 拼写错，匹配表恒空 | disappear 瞬间成功；appear 空转 10 s 报 found=false |
| (c) 多标志 OR 并集 | `--class X --text Y` | 屏上任意一个 Button 就让「目标文本按钮已出现」成立，与 `ui find` 的 AND 语义相反 |

### 4.2 修法

- 选择器缺失 → **exit 2** 拒绝（与 `waitStable`/`waitActivity` 的 missing-arg 形状一致），不再从空匹配表作答。
- 检索走新的 `findWaitTarget`：**先遍历完所有 root 得到整屏答案**，再调用一次性 `waitVerdict` 判定；逐节点 AND（`waitNodeMatches`），复用同一个 `textOrDescMatches`（顺手把 `findByTextOrDesc` 里重复的另一个 text/desc 实现合并掉，避免下次只改一处）。
- **超时保持 exit 0 + `timedOut: true`**（报告建议改 exit 1 未采纳）：`wait stable` / `wait activity` 就是这个契约，caller 读 flag；单改 wait appear/disappear 会让同族三条命令语义分裂，属 mindlessly 拓宽半径。
- 删掉失去唯一调用者的 `findByClassName`（本来只为 OR-union 服务），无其它引用。

### 4.3 证据

`AccessibilityWaitTargetTest.kt` 8 例 + 探针三态；变异臂 S4（极性反转）/ S5（重放行无选择器调用）各自变红。

---

## 5. F-04 — 全天日程的倒置区间能写进库

### 5.1 先证

`invertedTimedRange(isAllDay=true, …)` 因 `!isAllDay` 恒 false。但 producer 侧检查证明「保留零宽」和「豁免倒置」是**两件事**：

- `allDayEndRepairMs` KDoc 自己写的：provider 把规范单日行存成 `dtstart == dtend` ⇒ **等于**是合法形状；
- `createEndMs(null, start)` ⇒ `start + 1h` ⇒ 也是正的；
- 只有**负 span**（`--end` 早于 `--start`）才是 da1017-D2 实测会消失的那一行。

⇒ 报告给的正确等差 Approximate：不能直接删 `!isAllDay`，会顺拒 `dtstart == dtend` 这个**规范**形状。

### 5.2 修法

`invertedTimedRange` 改为：缺任一界 ⇒ false；全天 ⇒ `endMs < startMs`（只拒绝负 span）；定时 ⇒ 维持 `endMs <= startMs`。调用点（`doCreate:459` / `doUpdate:655`）不动，两个 KDoc 更新为准确描述。

### 5.3 证据

更新 `CalendarFreebusyInstancesTest.inverted timed ranges are refused…`（原最末两条断言正是这条豁免制度的盖章，现改为负 span 必红 + 零宽必绿），并补一条「create --all-day 无 `--end` 的 +1h 默认仍被接受」防止修过头。探针 S6（删全天分支→倒置回来）/ S7（全天也拒零宽→规范行被拒）/ S8（定时放宽→零宽回来）各红一条。

---

## 6. 验证矩阵

| 层 | 装置 | 读数 |
|---|---|---|
| L1 纯函数单测 | `ModelExecutionRetryTest`(14) / `ModelUseOutputFileUrlTest`(6) / `AccessibilityWaitTargetTest`(5) / 更新的 calendar 真值表 | 全部 PASS（本地 kotlinc + JUnit 实跑；CI 侧 14/6/5 亦全 PASSED，见 §8） |
| L2 三态真值表探针 | `probe-d2/Truths.kt` + `slice.py` 逐字切片 | **SLICE-TRUTHS: PASS**（36 断言；每条都同时跑「新实现」与「pre-fix 逐字转录」，证明夹具有牙且不修过头） |
| L3 变异 | 13 臂（M1–M5 策略 / S1–S8 谓词） | **13/13 各自变红、baseline 绿** |
| L4 结构检查 | `struct_check.py`（10 文件括号配平 + 新符号声明 + 死短路已删 + 无孤儿 helper） | `STRUCTCHECK: PASS` |
| L5 CI | build-apk（含 gate + 单测 + APK） | 见 §8 |

**切片提取带保障**：`slice.py` 断言每个切出的块是源文件的**逐字节子串**，否则中止；且加一条陈旧守卫（scratch 里出现未声明的 `.kt` 就拒跑）——本轮就是被它抓到一次 S8 残留把 baseline 假变红（见 §9 自伤）。

---

## 7. 与其它波的边界

- `scripts/scan/**`、`scan.sh`、`test_scan.py` **零触碰**（本任務 does not touch the gate surface）。
- 未见任何已有门读 `dispatch()` / `waitAppear` / `invertedTimedRange` 的决策结构；`xproc_boundary_check` 只读 `ModelExecutionDispatcher.kt` 的 put() 序列化，`modeluse_proto_single_source_check` 只读调用拼写 —— **本批三条 defensive gap 属审计这批的输入，不在本支出口**。是否固化成新门，属 W 波字形（不建议本支併)).

---

## 8. CI

| 项 | 值 |
|---|---|
| run id | `40697745032`（run_number 2838 / attempt 1） |
| head_sha | `4dc9fe16a5940887682c492ba34cade2b99f2e26`（与本地 HEAD 逐字符一致） |
| 结论 | **success**，27 步中唯一非 success = Publish（main 门控，分支上 skipped） |
| 时长 | 13:34:57Z → 13:56:09Z（21m12s） |

**日志读数**（1,508,679 B / NUL 0）：

- 门：`Summary: 113 passed, 0 failed`（分片 25/16/44/19/30/18 为并行池分段，**不是门数**）；`ALL 349 CASES PASS`；`^ e: ` **0**；含 `FAILED` 的行 **0**（连 PASSED 子串的都没有）。
- 单测：`ModelExecutionRetryTest` 14 PASSED / `ModelUseOutputFileUrlTest` 6 PASSED / `AccessibilityWaitTargetTest` 5 PASSED / `CalendarFreebusyInstancesTest` 19 PASSED / `OffloadCliArgsTest` 57 PASSED，**FAILED 0**。
- 相关门真树：`offload_relative_path_unify_check` / `a11y_handler_contract_check` / `calendar_events_nullable_predicate_check` / `modelsvc_single_read_check` / `modeluse_proto_single_source_check` / `offload_cli_args_check` / `offload_media_b64_wire_check` / `provider_boundary_guard` / `schedule_date_source_check` / `share_dir_prune_check` / `xproc_boundary_check` 全部 rc=0（含逐门 `--selftest`）。

### 8.1 首轮 CI 失败（run `40695565862`，HEAD `e2045587`）—— 「门把缺陷钉成了期望值」

首轮在 **Run unit tests (full suite)** 红灯，两条失败全在 `OffloadCliArgsTest`：

| 失败用例 | 根因 |
|---|---|
| `model-use relative paths are wired through the shared join` | 探针**要求**的正是 F-02 的缺陷写法：`code.contains("if (raw.startsWith(\"file://\")) raw else resolveMaybeRelative(raw, request.cwd)")` |
| `negative control - a re-inlined --output guard fails the probe` | 负控以该字面量为变异锚点，锚点不存在 ⇒ `mutated != original` 断言先炸 |

即**既有门不是漏判，是把缺陷写成了契约**：它强制 `file://` 值跳过 shared join、跳过所有下游路径语义消费者（只读挂载守卫 / 会话域正则都按相对路径形态判定），于是归一化成相对的 `file:/var/minis/…`、绕过两道守卫、被写进 `<rootfs>/file:/…`，而响应回显的还是原始 URL —— 正是 F-02 的静默失败形态，只是 CI 在读它。

**修法**（纯测试，零生产改动，commit `4dc9fe16`）：探针改成断言正解形态（`resolveMaybeRelative(stripFileUrlScheme(raw), request.cwd)` 恰好一次）+ **用自己的判据禁止 passthrough 回来** + 负控改为「把 strip 摘掉」（`resolveMaybeRelative(raw, …)`）必须让探针变红。齿不削，方向掰正。

**可复用判据**：形状探针的价值不在于「钉住某行原文」，而在于「钉住行为契约」。一条断言如果把**缺陷**写成期望值，它就是缺陷的守卫；写新判据时必须问一句「这个字面量是实现细节，还是我在意的那个契约」。旁证：`codeOnlyStrings` 会剥注释，所以探针的 KDoc 里引用旧字面量不会自伤 —— 引用与断言是两回事。

---

## 9. 残留（每条三件套）

**R1 — worker_queue_full 重试可能在毒化/繁忙期反复被同一个进程拒**
* 触发条件：两个并发流长时间占满 worker，`liveQueueAdmission()` 上限持续触顶。
* 触发后方案：让 dispatcher 在下一次尝试前 pk `Providerspeed the worker's reap`（或从 Service 侧把「为什么拒」+「当前 queued」返回，客户端据此 backoff，而非固定 500 ms）。
* 搁置理由：三次尝试的总延迟上界 1.5 s，对被 Scoreboard 的场景（≥6 排队）无救治价值；真要解是扩容调度侧（ProviderExecSlotPolicy），不是客户端重试次数。**P2**。

**R2 — `executeRun` 的 stale 分支仍不放 message 的兄弟形（`missing_api_key` 有 message）不对称**
* 触发条件：读者按 `message` 是否空来判断 error 是否有用户可见文案。
* 触发后方案：若后续统一 error envelope，把 `message` 变成 mandatory 字段并加一份 envelope 形状门。
* 搁置理由：本支已给 stale 补了 message，其它不动 —— 半径纪律。**P2**。

**R3 — a11y `wait` 的行为 CIenix-tree（多 root 真实场景）未在皿 —— CI MXique 未见 trash —— 只 pin 了层数**
* 触发条件：真机上 `wait disappear` 仍出现「闪报消失」。
* 触发后方案：先在 debug 日志里加一行每次 poll 的 root 数与整屏匹配数，用真机日志证明多窗口是常态而非边界。
* 搁置理由：沙箱无 AccessibilityService；`waitVerdict` 的 true/waiting 高于 that. **P1 触发条件**：真机观察到一次误报即升级。**P1**。

**R4 — `--input` / `--system-file` 的 `file://` 形态与 `--output` 现在不一致**
* 触发条件：调用方给 `--input file:///…`（`readLinuxPath` 不剥离，会把 host 当主机名拼进 rootfs）。
* 触发后方案：在 `readLinuxPath` 里同样调 `stripFileUrlScheme`，一并模块方法。
* 搁置理由：`--input` 是**读**路径，失败会 read fail 报错（fail-closed），不构成「数据落到错地方」的静默失败；本支出口是 `--output` 的 rootfs 写入。登记不修。**P2**。

**R5 — 本支未做时间控制的负担量值**
* 触发条件：用户报告「改了 API Key 后仍然报错」。
* 触发后方案：取 `minis-YYYY-MM-DD.modelservice.log` 里 `poisoning worker` / `rejecting runId=` 两行，与本支新增的 `dispatch attempt #N returned no usable result` 日志对齐，验证重试真的发生了。
* 搁置理由：沙箱无 Android。**P1**。

---

## 10. 结论

1. **四条全部属实**，但报告的**行号与 API 拼写普遍漂移**（见 §1）。先证的价值在这里不是形式主义 —— F-04 的报告建议（直接删 `!isAllDay`）**在本树上是错的**，会把 `dtstart == dtend` 这个规范行一起拒掉；照抄就引入了新缺陷。
2. **四条的 defensiveness gap 是同构的**：不是「没人写防御」，是防御**判错了对象**——F-01 判死锁却漏了 typed result；F-02 判单词却漏了形态；F-03 判存在却漏了作用域；F-04 判年至却漏了负东风。这个同构性值得进 W 波做一条通用判据。
3. **没有一处是为了美观而重构**：`textOrDescMatches` 的合并是因为它本来就是同一个规则的二分rets_owner 实现，下一次改either 一半必然复发；其余是新增。
4. **未验证面**：真机行为（多窗口 wait、rootfs 写入的实际宿主路径、inoa 的 automerge）——沙箱无 Android，三态探针 + CI 只保证编译/单测/静默 práce 不回归。
