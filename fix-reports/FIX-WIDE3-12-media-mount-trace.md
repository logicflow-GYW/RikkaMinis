# FIX-WIDE3-12 — 读挂载写守卫 / MediaPlayer 释放 / 执行闸释放恢复 / trace 契约：四门判据加固

- **分支**：`fix/meta-wide3-12-media-mount-trace`
- **基线**：main `6b6c918fae6c338e3091d0d3870af3cad9e2b881`（meta-audit wide-1024 第二波并集）
- **改动面**：4 个门脚本（`scripts/scan/*.py`），**生产代码 0 行改动**，`scan.sh` / `test_scan.py` 冻结面 0 触碰
- **门数 / 夹具数**：112 门 / 344 例不变（无新建门、无新建夹具文件；新判据以门内 `--selftest` 臂固化）
- **来源**：组 B 外部对抗审计（`/var/minis/shared/work/meta-audit-v5/reports/report-B.md`），主会话机械复证 157 条见证全部成立，本支分得 12 条

---

## ① 门与判据（改后全貌）

| 门 | 管什么 | 本支改动的判据 |
|---|---|---|
| `readonly_mount_write_guard.py` | 只读挂载下的写逃逸：路径参数写点必须有挂载守卫、阻塞 IO 必须在 IO 线程、卷根单源 | C1（路径参数写点的函数枚举）、C5（挂载守卫是否**决定**）、C11（installState 重放终态是否被**跳过**） |
| `mediaplayer_release_guard.py` | MediaPlayer 生命周期：stop 与 release 的 try 关系、锁内取放、share 的 Uri 来源 | arm1（release 相对 stop 的 try 的**可达性**）、arm3（`releaseSession` **调用点**是否在锁内）、arm6（函数体定位：先找形参右括号） |
| `exec_gate_release_check.py` | 执行闸 4a/4b/4c：重闸许可回滚、等待可中断、主线程不内联 GC | G2（阻塞 acquire 必须在 `runInterruptible` **lambda 内**）、G4（catch 里的回滚是否被常量短路）、G5（`isOnMainThread` **实参**是否恒假） |
| `trace_eval_check.py` | golden trace 契约：工具序列、终态、失败面 | 新增工具调用/结果**配对**、`success` 严格布尔、坏行 fail-closed |

---

## ② 失效轴（12 条见证的分布）

| 轴 | 条数 | 门 × 见证 |
|---|---|---|
| `dataflow`（调用在、决定没了） | 6 | readonly C5 / readonly C11 / mediaplayer arm3 / exec G4 / exec G5 / trace C5#1 / trace C5#2 |
| `parser-death`（解析器把被判对象切掉） | 3 | readonly C1 / mediaplayer arm6 / exec G2 |
| `anchor`（判据锚在结构外观而非可达性） | 1 | mediaplayer arm1 |
| `order / 配对`（多事件之间的关系） | 2 | trace C5#1（配对）/ trace C3（逐行契约） |

**共同根因一句话**：四道门的判据都只问「某物**存在**」——调用出现过、子串在、count 达标——而缺陷复发只需要让**该物的效果**失效（短路条件、嵌套位置、跨行体、错误归属），拼写全部保留。

---

## ③ 逐条见证（criterion / 轴 / 旧判据为何瞎 / 新判据形态）

### C1 · `readonly_mount_write_guard` · parser-death

- **见证**：把 `ModelUseOffloadHandler.writeRunOutput` 的块体改成 `): NativeOffloadResult =` 换行 `run { … }`，同时删掉函数内的挂载守卫调用。
- **旧判据为何瞎**：`functions()` 的表达式体分支只把**签名行 `=` 之后的尾巴**当作 body seed（`depth_of("") == 0`），`while depth > 0` 一次都不进 ⇒ body = `": NativeOffloadResult ="` ⇒ 该函数既无写点也无守卫，整函数掉出扫描；其余 7 个写点仍满足 `checked >= 3` 的防腐下限，门报 GREEN。
- **新判据**：`= ` 之后若为空（下一行才是表达式），继续吞行直到**表达式真的开始**且括号/花括号配平；走不完或配平失败 → `PARSE_FAILURES` → `scan()` 直接报违规（fail-closed），不再把「解析不到的函数」当作「没有写点的函数」。

### C5 · `readonly_mount_write_guard` · dataflow

- **见证**：`DebugRPCHandler` 与 `FileBrowserViewModel` 的守卫改成 `if (false && PRootKernel.isLinuxPathUnderReadOnlyMount(path))`。
- **旧判据为何瞎**：判据是「守卫调用在写点**之前**」（子串位置比较）。短路条件保留调用、保留位置、保留抛错体，写的每一个只读挂载路径都照写，门 GREEN。
- **新判据**：`guard_decides()` —— 守卫的**返回值必须被一个可达且终结的分支消费**：解析包住调用点的最近 `if (…)` 条件 + 一跳别名（`val ro = guard(x)` → `if (ro) return`），条件必须 `constant_truth(cond) is not False`，分支体内必须有 `return` / `throw`（含 `return@launch`）。常量恒假 → 单独报「守卫被常量短路」。

### C11 · `readonly_mount_write_guard` · dataflow

- **见证**：`if (replay) return@collect` → `if (false && replay) return@collect`。
- **旧判据为何瞎**：判据是「采集前抓过快照 + 采集体内**提到**快照变量」。快照在、`isStaleInstallReplay` 在、`return@collect` 在，只有跳过重放的决定没了 ⇒ 每次订阅都把上一次操作的失败终态当活事件应用一遍（抹掉刚发布的 `installProgress`、清 `isProcessing`）。
- **新判据**：`decision_branches()` —— 快照的**载体**（`priorTerminal` 及其一跳别名 `replay`）必须流向一个 `constant_truth(cond) is not False` 且体含 `return`/`continue` 的分支；恒假条件单独报「快照与 return 都在，跳过重放的决定被常量短路」。

### arm1 · `mediaplayer_release_guard` · anchor

- **见证**：把 `try { release() } catch {}` 整块**嵌套**进 `try { stop() } catch {}` 之内。
- **旧判据为何瞎**：判据是 `stop_try != release_try`（两个**不同的** try span）。嵌套满足「不同」，而 `stop()` 在离开可播放状态时抛 `IllegalStateException`（onError 终态正好走这条路）⇒ release 永不执行，native codec 泄漏。
- **新判据**：改判**可达性**——release 调用点不得落在 stop 调用的 try span 之内；两块必须是**兄弟**，stop 抛异常的那条路径上 release 仍要跑。

### arm3 · `mediaplayer_release_guard` · dataflow

- **见证**：`val existing = synchronized(lock) { sessions[sessionId]?.let { it } }` + `if (existing != null) releaseSession(existing)`（读在锁内、**释放**在锁外）。
- **旧判据为何瞎**：判据只判「`sessions[sessionId]?.` 这个**读**在锁内」+「`releaseSession(existing)` 字样存在」。被竞态的从来是释放动作，不是读。
- **新判据**：判**每个** `releaseSession(...)` 调用点：在锁内 → 通过；在锁外 → 必须在此之前有同锁区间内的 `sessions.remove(` / `sessions.put(`（实例已从 map 摘除，回调线程不再可达）。这同时保住 `stop()` 里「锁内 remove、锁外 release」的正当写法。

### arm6 · `mediaplayer_release_guard` · parser-death

- **见证**：`shareMediaFile` 的形参表里加一个默认值 lambda（`guardStub: () -> Unit = { try { FileProvider.getUriForFile(...) } catch { … return } }`），真实函数体改首行为 `val uri = Uri.fromFile(file)` + `if (false) try {`。
- **旧判据为何瞎**：`function_body()` 取「声明名之后的**第一个** `{`」——默认值 lambda 的 `{` 被当成函数体，于是门在审那个 lambda（它恰好含 `getUriForFile` 与完整抛错体），真正的 `Uri.fromFile(file)` 落在扫描面之外。
- **新判据**：先找形参表的 `(` 配平到 `)`，**再**找函数体的 `{`（表达式体同理）。

### G2 · `exec_gate_release_check` · parser-death

- **见证**：`): Boolean = run { runInterruptible(Dispatchers.IO) { true }; heavyGate.tryAcquire(timeoutMs, MILLISECONDS) }`。
- **旧判据为何瞎**：`runInterruptible(` 在体内 + `tryAcquire(` 全文件恰好两次 —— 两条子串/计数判据同时满足，而真正的阻塞 acquire 跑到 lambda 外面，取消的协程照样卡满超时（4b 复活）。
- **新判据**：`lambda_after_call()` 解析 `runInterruptible(...)` 的实参表后紧跟的 `{…}` 区间，要求**每一个** `tryAcquire(` 落在其中；同一条规则对称应用到全局槽 wrapper 的 `internalAcquireGlobalShellSlot(`。

### G4 · `exec_gate_release_check` · dataflow

- **见证**：`if (heavyGateHeld) heavyGate.release()` → `if (false && heavyGateHeld) heavyGate.release()`。
- **旧判据为何瞎**：判据是「函数体里有 `heavyGate.release()`」+「有 `throw`」。调用在、throw 在，回滚的决定没了（4a：等待全局槽被中断 ⇒ 抛出时仍持着重闸许可）。
- **新判据**：改判 **catch 块**（不是整函数）：释放调用必须在 catch 体内、其外层 `if` 条件不得被常量短路、且 `throw` 必须出现在释放**之后**（顺序轴）。

### G5 · `exec_gate_release_check` · dataflow

- **见证**：`isOnMainThread = false && Looper.myLooper() == Looper.getMainLooper()`。
- **旧判据为何瞎**：两条子串判据（`Looper.getMainLooper()` 在、`recycleDispatchMode(` 在）被完整保留；实参被钉死为 `false` ⇒ 主线程调用者内联跑 GC 循环（4c 复活）。
- **新判据**：解析 `recycleDispatchMode(...)` 的**实参表达式**：必须同时提到 `Looper.myLooper()` 与 `Looper.getMainLooper()`，且 `constant_truth(实参) is None`（恒真恒假都报）；并要求 `when` 的主语**就是**这个调用（决定被消费，而不是算完丢掉）。

### trace C5#1 · `trace_eval_check` · dataflow / 配对

- **见证**：`truncated-tool-call-refused.jsonl` 里把某条 `tool_result` 的 `"tool":"file_write"` 改成 `"tool":"unrelated_tool"`（也试过删掉 t2 的 `tool_result`）。
- **旧判据为何瞎**：`tool_calls` 与 `tool_results` 是**两堆各自独立**的事件：`tools` 序列只看调用侧，`all_tools_succeed` 只看结果侧，两者之间没有任何链接。结果挂到另一个工具、或某个调用根本没有结果，读起来仍然是「全部成功」。
- **新判据**：`check_event_integrity()` —— 按 `tool_id` 一对一配对：结果的 `tool`/`turn` 必须与同名调用一致；孤儿结果（无对应调用）报红；已派发调用缺结果报红（`Cancelled`/`Interrupted` 终态除外，那是「派发后中断」的合法形状）；一侧有 id 另一侧没有 → fail-closed。

### trace C5#2 · `trace_eval_check` · dataflow

- **见证**：`offloaded-payload-refused.jsonl` 里 `"success":false` → `"success":null`。
- **旧判据为何瞎**：`all(r.get("success") for r in tool_results)` 与 `all(...)` 都是 falsy 语义，`None` 在两侧都「不像问题」；更糟的是 `some_tool_failed` 从不看失败那条结果的 `output`，因此「被守卫拒绝」与「录制器丢了判决」在上游无法区分。
- **新判据**：`success` 必须是**真布尔**（缺字段 / `null` / 非布尔 → 报红，fail-closed）；`some_tool_failed` 要求存在 `success is False` 的结果，且其 `output` 非空（拒绝必须说清为什么）。

### trace C3 · `trace_eval_check` · parser-death

- **见证**：在 golden 文件末尾追加一行截断的 JSON `{"type":"tool_call","turn":0,"tool":"shell_execute","args":"TRUNCATED_JSON`。
- **旧判据为何瞎**：坏行只进 `warnings`（`{bad_lines} malformed JSONL line(s) skipped`），verdict 不变 ⇒ 损坏的入库 golden 与「被隐藏的截断事件」都能过门。
- **新判据**：golden 是**入库夹具**不是真机日志 —— 坏行直接计入失败；`trace_lines` 内联形态的 `json.loads` 也为解析异常显式报红（不再以 traceback 崩掉）。

---

## ④ 修法（改动清单）

| 文件 | 净变化 | 关键实现 |
|---|---|---|
| `scripts/scan/readonly_mount_write_guard.py` | +386 / −? | 新增 `match_paren` / `if_branches` / `split_top` / `constant_truth` / `alias_names` / `decision_branches` / `guard_decides` / `guard_message`；`functions()` 表达式体跨行化 + `PARSE_FAILURES`；`scan()` 把解析失败转违规；C5 改 `guard_decides`（主守卫 + `EXTRA_GUARDS` 都判）；C11 改 `decision_branches` |
| `scripts/scan/mediaplayer_release_guard.py` | +155 / −? | `matching_paren` + `function_body` 先配平形参再找体；arm1 加「release 不得落在 stop 的 try 内」；arm3 改判每个 `releaseSession(` 调用点 + 锁内 `remove/put` 豁免 |
| `scripts/scan/exec_gate_release_check.py` | +469 / −? | 新增 `match_paren` / `brace_span_from` / `skip_ws` / `lambda_after_call` / `if_branches` / `enclosing_if` / `split_top` / `strip_outer_parens` / `constant_truth` / `catch_blocks`；G2/G2b/G3b 容器判定；G4 改判 catch 块 + 死守卫 + 顺序；G5 改判实参常量性与 `when` 主语；新增 `--selftest`（4 臂） |
| `scripts/scan/trace_eval_check.py` | +285 / −? | `_tool_id` / `check_event_integrity`（配对 + 孤儿 + 未应答 + 重复）；`success` 严格布尔；`some_tool_failed` 的 `output` 非空；坏行 fail-closed；新增 `--selftest`（10 臂） |

**未动**：任何 `src/**` 生产代码、`scan.sh`、`test_scan.py`、其他门的脚本。

---

## ⑤ 四层证据

### L1 · 真树 GREEN + 各自 selftest PASS

```
$ python3 scripts/scan/readonly_mount_write_guard.py .
OK: 8 path-parameter write site(s) guarded (646 files scanned)
    17 blocking-IO call site(s) inside Dispatchers.IO
    1 volume-root definition(s), single source in data/MountedFoldersStore.kt
rc=0
$ python3 scripts/scan/mediaplayer_release_guard.py .            → rc=0
$ python3 scripts/scan/exec_gate_release_check.py .
✅ Execution-gate guard: 4a rollback + 4b interruptible waits + 4c main-thread dispatch all wired
rc=0
$ python3 scripts/scan/trace_eval_check.py .                     → rc=0

$ python3 scripts/scan/readonly_mount_write_guard.py --selftest  → MOUNT_PROBE_IO_SELFTEST=PASS (69 arms)
$ python3 scripts/scan/mediaplayer_release_guard.py --selftest   → MEDIAPLAYER_RELEASE_SELFTEST=PASS
                                                                   FULLSCREEN_WINDOW_SELFTEST=PASS
$ python3 scripts/scan/exec_gate_release_check.py --selftest     → EXEC_GATE_SELFTEST=PASS (4 arms)
$ python3 scripts/scan/trace_eval_check.py --selftest            → TRACE_EVAL_SELFTEST=PASS (10 arms)
```

### L2 · 12 条见证反攻（**这是本支最硬的验收判据**）

`/var/minis/shared/work/meta-wide3/reattack.py`：逐条把见证补丁打到真树 → 跑对应门 → 记录 rc → 恢复 → sha256 比对。

```
修复前：12/12 STILL_BLIND   （/tmp/reattack-12-before.json）
修复后：12/12 CAUGHT        （/tmp/reattack-12-after.json），restore=True ×12
```

逐门：`readonly_mount_write_guard` 3/3 · `mediaplayer_release_guard` 3/3 · `exec_gate_release_check` 3/3 · `trace_eval_check` 3/3。

### L3 · 邻门回归（同一批文件的近邻判据没有被新解析器带翻）

```
trace_step_tag_check rc=0 · offload_cli_args_check rc=0 · file_uri_parse_guard_check rc=0
share_dir_prune_check rc=0 · scheduled_store_release_guard rc=0 · offload_host_path_check rc=0
```

`test_scan.py` 中与四门直接相关的四组夹具全部通过（无 ❌）：

```
$ python3 -c "...; test_trace_eval(); test_readonly_mount_write_guard(); test_exec_gate(); test_trace_step_tag()"
━━━ trace_eval_check ━━━ / ━━━ readonly_mount_write_guard ━━━ / ━━━ exec_gate_release_check ━━━ / ━━━ trace_step_tag_check ━━━
ALL FOUR OK
```

### L4 · 全量 112 门 + 344 夹具

**本地全量运行被沙箱缺陷打断**（并发窗口内 PRoot 对 `open` 返回 `ENOSYS: Function not implemented`，`/tmp` 写不进、输出被截断；`shared/sandbox-limits.md` 已登记该症状，`gate_run.sh --full` 带重试仍拿不到完整读数）。**权威读数取自分支 CI 日志**（job 日志里同时有 112 个门头、`Summary: 112 passed, 0 failed`、`ALL 344 CASES PASS`）——详见 §⑥。

---

## ⑥ CI run

- 分支：`fix/meta-wide3-12-media-mount-trace`
- 代码提交（CI 绿于此）：`95f94b91dabfa9f6671ff4bf9001a59cbd48a2f7`
- 触发：非 main 分支必须手动 dispatch（`gh-actions-dispatch --ref fix/meta-wide3-12-media-mount-trace`）
- **run #2648 / id `40160261615` / job `121070207839` / attempt 1 → `conclusion: success`**，`head_sha = 95f94b91dabf`（与本地 HEAD 逐字符一致）

机器判读（job 日志 1,485,232 B，`tmp12/job2648.log`）：

| 判据 | 读数 |
|---|---|
| 门头 | `━━━ [N/112] … ━━━` **112 条互异**（1..112） |
| 门汇总 | `Summary: 112 passed, 0 failed` **恰 1 条** |
| 夹具 | `ALL 344 CASES PASS` 出现 |
| 编译错 | ` e: ` **0** 行 |
| 26 步 | 唯一非 success = `23 Publish to Releases` → **skipped**（main 门控，分支构建的正常形态） |
| 含 `FAILED` 的 6 行 | 逐条确认全是 **PASSED 用例名子串**（`slot denied produces FAILED without calling provider`、`reason PERSISTENCE_FAILED maps to persistence_failed`、`phase FAILED maps to Failed`、`terminal FAILED maps to Failed`、`reason EXECUTION_FAILED maps to all_fallbacks_exhausted`、`the fixed tail still reaches a legal FAILED terminal`） |

> 本报告的回填提交（§⑥ 表格 + 这份文件）**只动 `fix-reports/`**，源码树与 CI 绿树逐字节一致，故不重新 dispatch。

---

## ⑦ 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `readonly` C5 只认「`if (…)` 条件或一跳别名」形态；`when (guard(x))` / `runCatching { guard(x) }` 会被判红（方向安全：误红不放行） | 生产出现 `when`/`runCatching` 形态的挂载守卫并被 CI 打红 | 把 `guard_decides` 的「终结」判据扩到 `when` 分支（`-> ret` 形态）与 `runCatching` + `?: return` | 现树无此形态；扩判据要新的解析器分支，收益为 0 |
| R2 | `readonly` C11 的「锁外释放不许」豁免只看锁内 `sessions.remove/put` 字样，不判 key 是否同一个 | 出现 `stop()` 里换 key 的写法（`sessions.remove(other)`） | 解析实参比较 key 表达式 | 单文件单函数族；误红方向是「多报一条」，不是静默放行 |
| R3 | `if` 的 **else 分支**里的守卫（`if (true) … else release()`）不在 `if_branches` 的可达性判定里 | 生产出现 else 分支守卫 | `if_branches` 返回 else span，可达性判定覆盖两分支 | 现树无此形态 |
| R4 | `readonly` 的 `functions()` 对嵌套 `object`/`companion` 内的 `fun` 仍按顶层处理（作用域未建模） | 出现同文件同名 `fun` 重载导致判据取错体 | 加作用域栈 | 同名重载在 `src/` 里 0 例；改作用域解析风险大于收益 |
| R5 | `mediaplayer` arm1 只拦「release 在 stop 的 try 内」，不拦「stop 在子 try、release 在父 catch」 | 出现父 catch 里 release 的重构 | 判「release 与 stop 的最近公共 try 是否是 stop 的 try 的祖先」 | 现树无此形态 |
| R6 | `trace` 配对仅在事件带 `tool_id` 时生效（无 id 的手写内联夹具不做身份校验） | 新 golden 采用无 id schema | 要求 schema 2.0 强制 id（生成器侧） | 强制 id 会改 `test_scan.py` 既有夹具（冻结面） |
| R7 | `exec` G4 只判「catch 体首个 catch 块」 | 出现多 catch 分支的重构 | 遍历全部 catch span 并要求每个抛出路径都释放 | EvolutionCoordinator 单 catch；多分支时误红方向安全 |
| R8 | 本支**未**做真机行为级验证（纯门判据 + 静态解析，无运行时面） | —— | —— | 门脚本改动不产生运行时行为；真机面在对应生产缺陷的支线（4a/4b/4c/da1022-C）已覆盖 |

---

## ⑧ 复现命令

```sh
# 0) 定位
cd /var/minis/shared/work/meta-wide3/wt-12
git rev-parse --abbrev-ref HEAD   # fix/meta-wide3-12-media-mount-trace
git rev-parse HEAD                # 见回报
git diff --stat                   # 4 个 scripts/scan/*.py

# 1) L1：真树 + 自证
for g in readonly_mount_write_guard mediaplayer_release_guard exec_gate_release_check trace_eval_check; do
  TMPDIR=/var/minis/shared/work/tmp12 python3 scripts/scan/$g.py .
  TMPDIR=/var/minis/shared/work/tmp12 python3 scripts/scan/$g.py --selftest
done

# 2) L2：12 条见证反攻（打补丁 → 跑门 → 恢复 → sha256 校验）
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
  --gates readonly_mount_write_guard.py,mediaplayer_release_guard.py,exec_gate_release_check.py,trace_eval_check.py \
  --json /tmp/reattack-12-after.json

# 3) L3：邻门
for g in trace_step_tag_check offload_cli_args_check file_uri_parse_guard_check \
         share_dir_prune_check scheduled_store_release_guard offload_host_path_check; do
  TMPDIR=/var/minis/shared/work/tmp12 python3 scripts/scan/$g.py .
done

# 4) L4（本地受沙箱 ENOSYS 影响，权威读数见 CI）
TMPDIR=/var/minis/shared/work/tmp12 sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"

# 5) 推送 + 手动 dispatch + 查 run
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --ref fix/meta-wide3-12-media-mount-trace
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/meta-wide3-12-media-mount-trace
```
