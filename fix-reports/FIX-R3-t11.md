# FIX-R3-t11 — discarded_return_guard / provider_remember_key_check 两门判据加固

- **批次**：第三轮（dual-gate bypass 收口 · B 波）· 支线 `t11`
- **分支**：`fix/r3-discard-rememberkey`，基线 `main` @ `f0f14d46d`
- **改动面**：`scripts/scan/discarded_return_guard.py`（+579/−116）、
  `scripts/scan/provider_remember_key_check.py`（+51/−4）——合计 **2 文件 +630/−120**；
  **生产 `.kt` 0 行**，`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py`
  零触碰，门数 109（本树 `*_check|*_guard` 计）/ 夹具 344 不变。
- **结论**：6 条见证 → 修前 **6/6 BLIND_CONFIRMED**；修后 **5/6 CAUGHT**（红线各落在自己那条
  判据上），第 6 条（#137）的缝在 `chat_session_id_binding_check`，**不在本支两门里**，
  按硬约束 #2 登记 §7 交主会话派发。
- **自证臂**：`discarded_return_guard` 60 → **69 arms**；`provider_remember_key_check`
  139 → **141 arms**（另有 1 条既有臂的期望从 `("C34",)` 扩成 `("C34","C35")`）。

---

## 1. 门（本支负责的两道）

| 门 | 职责 | 本轮被攻的缝 |
|---|---|---|
| `discarded_return_guard.py` | ①「必须消费的返回值」黑名单（`recordFirstToken` / `copySharedStream`）；②队列排空/插队的持久化不变量（B-04 D1/D2/D3）；③分享暂存字节账本（A-07 S1/S2/S3）；④二进制/编码判决路由 | 返回值被**绑定到局部**后丢弃；账本 `credit()` 的**金额**；队列**第二个消费方**（`injectQueuedPromptsAsNewTurn`）无人看管 |
| `provider_remember_key_check.py` | Provider 实例的「活实例 / 持久列表 / 合成 System 条目」单源纪律（C1…C-SV2b 141 臂） | C26（详情页写 → 值来源）与 C35（id-keyed 解析）两处判据锚在**拼写/面**上，而非值/面覆盖率 |

两门共同点：**判据问的是「某个名字/某种拼写/某个面存在吗」，而缺陷落在「值真的被消费了吗 /
这个面也被覆盖了吗」**。这正是第三轮 dual-gate 攻击的落点——一个补丁让两门同时绿。

## 2. 轴（每条见证的失效轴）

| 见证 | 轴 | 一句话 |
|---|---|---|
| #133 | missing-consumer（面覆盖） | D1/D3 只锚 `drainQueuedPrompts`，孪生消费方 `injectQueuedPromptsAsNewTurn` 上同样形状的代码零判据 |
| #134 | dataflow（值 vs 存在） | S2 只判三个名字存在 + 顺序，`credit(0L)` 与 `credit(textBytes)` 同分 |
| #135 | legacy-axis（绑定即消费的错觉） | 基扫描器只认语句位置的调用，`val x = call()` 让调用凭空消失 |
| #136 | missing-surface（面覆盖） | C35 只挂在 picker / group-detail 两个面，聊天页自己的 `entryById` 无人判 |
| #137 | anchor（整文件子串） | 门 Y 对整文件数 `resolveSessionId` 子串，同文件另一处裸传即可满足 —— **缝在门 Y，不在本支** |
| #138 | spelling（值 vs 拼写） | C26 只问实参文本里有没有 `live` 这个名字，`.also { live }`（值仍是快照）通过 |

## 3. 见证重放表

产物：`probe/t11-w{133..138}.patch`（最小编辑的 `git diff`）、`probe/t11-pre.json`、
`probe/t11-post.json`、`probe/t11-w*-<gate>.log`（门的原始输出）。

修前：把两门文件退回 `f0f14d46d` 版本（`git checkout -- scripts/scan/…`），逐条应用补丁重放。

| # | 目标（生产文件:行） | 修前 rc（门 → rc） | 判决 | 修后 rc | 红线归属 |
|---|---|---|---|---|---|
| 133 | `ChatQueueInterruption.kt:202`（inject 里 `dbMessageId = userEntity.id` → `null`） | DRG 0 / stale 0 | **BLIND_CONFIRMED** | DRG **1** / stale 0 | `[B-04 D1] injectQueuedPromptsAsNewTurn: the bound userEntity.id does not flow into the agentHistory entry` |
| 134 | `ShareReceiverActivity.kt:322`（`credit(textBytes)` → `credit(0L)`） | DRG 0 | **BLIND_CONFIRMED** | DRG **1** | `[A-07 S2] … credits a CONSTANT (credit(0L)) instead of the admitted byte count` |
| 135 | `ProviderHealthTraceListener.kt:45`（`attempt = attempt?.let {…}` → `val ignoredAttempt = …recordFirstToken(it, ms())`） | DRG 0 | **BLIND_CONFIRMED** | DRG **1** | `W-D: 返回值被绑定到 val ignoredAttempt，但该局部从未被读取` |
| 136 | `ChatScreen.kt:4983`（`entryById` 的 `SystemVoiceEntries.resolveIn` → `config.modelEntries.find { it.id == entryId }`） | PRK 0 / csib 0 | **BLIND_CONFIRMED** | PRK **1** / csib 0 | `[C35] FAIL — id-keyed lookup into modelEntries without the System source at line(s) 4983` |
| 137 | `AppNavigation.kt:759`（`onOpenTerminalWithCommand` 裸传 `sessionId`） | PRK 0 / csib 0 | **BLIND_CONFIRMED** | PRK 0 / csib 0 | **未修（缝在 csib）→ §7 R1** |
| 138 | `ProviderDetailScreen.kt:263`（`live.copy(…)` → `instance.copy(…).also { live }`） | PRK 0 | **BLIND_CONFIRMED** | PRK **1** | `[C26] FAIL — L263: instance.copy(isEnabled = it).also { live } is the render-frame object` |

**复现要点（三条与散文补丁不同、必须按报告原文形态写的地方）**：

1. **#135 必须写成单行**：把 `recordFirstToken` 的调用折到下一行（`val x =\n    call(...)`）
   会让它重新落进旧扫描器的 `_CALL` 行首正则、当场变 CAUGHT —— 那不是见证的形态。
   见证的形态是**声明与调用同一行**，调用被 `val … =` 前缀挡住（第一版重放我写错了，门 red，
   已改正后复现为 BLIND）。
2. **#133/#138 的锚点在同文件里出现多次**：`dbMessageId = userEntity.id`、`live.copy(...)`
   都在别处有同形行，`str.replace` 必须带 `count`，否则改到别处、红的原因失真。
3. **#137 是「同文件另一处仍合规」型缝**：补丁只动 `onOpenTerminalWithCommand`，
   `onOpenTerminal` 原样保留 —— 这正是门 Y 的整文件子串计数为何仍绿。

restore：六条全部 `git status --porcelain src` 干净、`sha256` 与原始一致（`restored: true`）。

## 4. 修法（判据形态对照）

### 4.1 `discarded_return_guard.py`

| 位置 | 旧判据 | 新判据 |
|---|---|---|
| 词法器 | 自写 `code_only`（一次扫描、遇未闭合即静默截断） | **共享 `_kotlin_lexer.mask_code`**（`code_view`）；另加等长的 `_literal_only`（`literal_view`）用来读 `role = "user"` 这类字面量——两个视图等长，**位置可以互相回投**。`KotlinLexError` → 各段 fail-closed 报违规 |
| B-04 D1 | 只在 `drainQueuedPrompts` 里：`val userEntity = appendMessage(` + 文本里有 `dbMessageId = userEntity.id` | 判据跑在**两个消费方**上（`QUEUE_CONSUMERS`）；id 用 `_named_arg` 取**实参值**，再用 `value_sources` 判「求值来源里有没有 `<绑定的appendMessage返回值>.id`」，`const_value` 命中即红（`dbMessageId = null` / 字面量 id） |
| B-04 D3 | 只在 drain：`removeQueuedPlaceholders(` 存在 + 一条 `_messages.value = _messages.value + ChatMessage(` | 两个消费方；气泡从**追加语句**派生：内联 `+ ChatMessage(…)` 和 `val x = ChatMessage(…)` 再由 `+ x` 追加**两种形状都解析**（后者按出现次数计），`role` 从字面量视图读，行数 >1 红、id 不同源红 |
| B-04 D2 | 守卫存在 + 顺序 | 不变（既有 dataflow 版：守卫必须**终结**），只是循环体也跑到两个消费方 |
| A-07 S2 | `writeText(` / `admitAttachText(` / `stagedBytes.credit(` 三个名字存在 + 顺序 | 加 `_attach_credit_gaps()`：`credit()` 的实参（`call_args`）不得是常量，且必须与 `admitAttachText()` 的首实参**同源**（`value_sources` 交集非空）；账本侧 `_ledger_credit_lands()`：`fun credit(param)` 的 `addAndGet(...)` 入参必须含该 param |
| 黑名单扫描 | 只认**语句位置**的调用行 | 加 `_scan_bound_locals()`：`val/var x = <∈黑名单调用>` → 该局部必须有**非日志**读取（`when (val x = …)` 主题视为消费；`AppLogger./Log./println/System.out/logger/debugLog` 前缀不算）。零读取 = 死绑定，只日志读取 = 假消费 |

### 4.2 `provider_remember_key_check.py`

| 位置 | 旧判据 | 新判据 |
|---|---|---|
| C26（详情页写） | 实参文本里出现 `live` 名字，或出现 `currentInstance(` | 改调 `_live_write_arg(args, live_locals)`（C22 已在用的同款：`value_sources` + `peel_discarding` 剥掉 `also/apply/let` 这类丢弃性 lambda）。`instance.copy(isEnabled = it).also { live }` 的**值**是快照 → 红 |
| C35（id-keyed 解析单源） | `CHECKERS` 只挂 `ChatModelPickerSheet` / `ModelGroupDetailScreen` | 再挂 `REL_CHAT_SCREEN`（第 136 条见证的面）。聊天页的 `entryById` 一旦退化成 `config.modelEntries.find { it.id == … }` 即红，而 C34（成员块）仍然绿——正是见证的「一门绿一门该红」形态 |

## 5. 四层证据

**L1 本门**（`probe/t11-*.log`）

- `python3 scripts/scan/discarded_return_guard.py .` → **rc=0**，六段全 ✅；
  `--self-test` → **rc=0 / 69 arms**，收尾标记 `DISCARDED_RETURN_GUARD_SELFTEST=PASS (69 arms)`
  （本轮新增 8 条：D1c / D1d / D3c / D3d / D3e + S2f / S2g / S2h；另把 A08-6 扩成
  「消费型控制臂绿 + 死绑定红 + 日志绑定红」三段，仍计 1 条）。
- `python3 scripts/scan/provider_remember_key_check.py .` → **rc=0**；
  `--selftest` → **rc=0 / 141 arms**，标记 `PROVIDER_REMEMBER_SELFTEST=PASS (141 arms)`
  （+2：`c26-detail-live-mentioned-in-also`、`c35-screen-entrybyid-bare-find`；
  `c34-screen-no-system-fallback` 期望扩为 `("C34","C35")`——夹具补了 `entryById` 后该变异把两处
  System 源一起降级）。

**L2 邻门**：两门的缝伙伴 `stale_closure_guard`、`chat_session_id_binding_check` 均 **rc=0**；
语义相邻（同为分享/会话/Provider 面）的 `share_dir_prune`、`chat_session_id_binding`、
`provider_write_rules`、`provider_rpc_symmetry`、`route_*`/`*_binding` 族在 L3 的全量循环里全部 0。

**L3 全量**（逐门独立进程，**未跑 `scan.sh`**）：`probe/gate_loop.py` 分批 109 门 →
**109/109 rc=0**（`probe/t11-gates.jsonl`，最后一轮读数）。`tts_voice_and_sanitizer_check` 需
`--root .` 形态，循环里一次 rc=2 系重试形态问题，手工 `--root .` → PASS。失败列表：**空**。

**`test_scan.py`**：整跑在本沙箱被 `OSError: [Errno 38]` 打死（输出 32 行后卡在子进程 wait），
按既有惯例**交 CI 承担**（CI 跑 `scan.sh` + `test_scan.py`）。本地改为只跑两门的注册用例，
全绿（`probe/t11-registration.log`）：

```
✅ discarded_return_guard selftest has teeth (binary-write self-test has teeth)
✅ discarded_return_guard real tree green
✅ provider_remember_key_check selftest has teeth (PROVIDER_REMEMBER_SELFTEST=PASS)
✅ provider_remember_key_check real tree green
```

**L4 CI**：见 §6。

## 6. CI

（提交后回填）

## 7. 残留（均带 触发条件 / 触发后方案 / 搁置理由）

**R1（未修 · 见证 #137 · 缝在门 Y）** `onOpenTerminalWithCommand` 裸传未晋升的 `sessionId`。
- 触发条件：`chat_session_id_binding_check.py` 的第 5 条判据仍是「整文件 `count(resolveSessionId)` +
  禁用拼写表」，且该文件出现第二处裸传 `sessionId` → 门绿而路由指向不存在的草稿会话，终端命令
  打不开 / 打到别的会话。
- 触发后方案：把该判据改成**按 `Routes.terminal(...)` 调用点逐处判实参值**（`call_args` 取
  `sessionId =` 的实参，`value_sources` 必须含 `resolveSessionId`），而不是整文件计数。
  **由谁管**：`chat_session_id_binding_check` 的支线（本支硬约束 #2 不许改其他门脚本）。
- 搁置理由：本支 radii 只覆盖任务书 §3 点名的两门；跨门修改会与同批其他支线撞文件
  （第三轮 B 波按门分席，`chat_session_id_binding_check` 属另一席）。

**R2（W-D 日志白名单不全）** `_LOG_STMT` 只认 `AppLogger/Log./println/print/System.out|err/
logger/debugLog`。
- 触发条件：仓里出现 `val x = <黑名单调用>` 且该局部只被**自定义**日志函数（`report(…)`、
  `Timber.*`、模块自己的 `logX(…)`）读取 → W-D 判为「已消费」而放行（假绿方向）。
- 触发后方案：把白名单换成「从树派生」——凡 `fun <name>(` 的体只做 `AppLogger.*`/`Log.*` 转发
  即视为日志函数，或直接判「唯一的读取发生在某个 `XxxLogger` 类的方法调用实参里」。
- 搁置理由：当前仓内唯一两处绑定形态（`copySharedStream` 的 `when` 主语、
  `recordFirstToken` 的赋值）都被覆盖；再写派生逻辑属于给未触发的形态建装置（修复门 P2）。

**R3（W-D 只覆盖一级绑定）** `val x = <call>` 的读取必须在声明之后可见。
- 触发条件：出现 `val a = <黑名单调用>` + `val b = a`（二级绑定，`b` 从未被读）这类链式死绑定。
- 触发后方案：把「读取」判据换成 `_kotlin_valueflow.live_positions` 的传递闭包。
- 搁置理由：一级绑定已覆盖本轮见证；二级链在同族里未观测到（P2 登记）。

**R4（`_named_arg` 的顶层逗号依赖）** 实参切分用 `kast.split_top(inner, (",",))`。
- 触发条件：实参里出现含**顶层逗号**的构造（Kotlin 里主要见于 `foo(if (a) x,y else z)` 这类
  非法写法，或 lambda 参数列表被写在同一实参位）→ 某个具名实参被切碎，取不到值 → 判红
  （**方向安全**：fail-closed，修法提示会连带给出）。
- 触发后方案：改成按 `stmt_spans` 逐实参解析，或对 `dbMessageId`/`id`/`role` 三处直接做
  「从名字回溯到实参结束」的定位。
- 搁置理由：真树 1308 个 `.kt` 目前零命中；误红方向安全，不会静默放行。

**R5（`_appended_bubbles` 只认一种追加形态）** 追加语句锚 `_messages.value = _messages.value + …`。
- 触发条件：追加语句改写成 `_messages.update { it + … }` / `_messages.value += …` → 判据看不到
  气泡 → 报「does not append the merged user bubble」（**误红**，不是漏）。
- 触发后方案：把锚扩成「任何以 `_messages` 为目的地的写」+ `value_sources` 判新增元素。
- 搁置理由：故意收窄以保精度（收窄的代价是误红而非漏判）；两处消费方当前都是同一形态。

**R6（S2 记账金额要求「与准入同源」）**
- 触发条件：把记账改成 `credit(textBytes + overhead)` → `value_sources` 返回整表达式，与
  `admitted` 的交集可能为空 → **误红**。
- 触发后方案：允许「同源 + 常量增量」（`peel` 出基础项后再判）。
- 搁置理由：现在记账就是同一绑定；提前支持未出现的形态＝预估性装置（P2）。

**R7（C35 挂到 ChatScreen 的误报面）**
- 触发条件：聊天页新增「**非 entry** 的 id-keyed 查找」（例如按 `providerId` 在
  `config.modelEntries` 里找 provider 的默认模型）→ C35 会红。
- 触发后方案：`check_active_entry_single_source` 增加「查找键类型」判据（`it.id ==` 且实参来自
  `entryId`/`entry.id` 语境的才计入）。
- 搁置理由：真树当前 0 站点（C35 在 ChatScreen 上 rc=0 已验证）；等出现再加。

**R8（门自证 stdout 不进 CI 日志 —— 既知）** 新标记 `DISCARDED_RETURN_GUARD_SELFTEST=PASS (69 arms)`
只在本地可见；CI 侧 `test_scan` 只断言旧的 `binary-write self-test has teeth`。
- 触发条件：有人把某条新臂删掉 → 本地 `--self-test` 仍会红，但 CI 与本地脱钩时不报。
- 触发后方案：主会话在 `test_scan.py` 的 `DV27_GATES` 把 marker 换成
  `DISCARDED_RETURN_GUARD_SELFTEST=PASS`（DRG 的新收尾标记）。**本支不许改 `test_scan.py`**。
- 搁置理由：`test_scan.py` 冻结；现有 marker 仍真（`binary-write self-test has teeth` 依旧打印）。

**R9（共享层缺口：`_named_arg` 可上收）** 本支在门内实现了「取调用里某个具名实参的值文本」。
- 触发条件：再有一门需要读具名实参（如 `dbMessageId =` / `id =` / `role =`）时重复实现。
- 触发后方案：上收到 `_kotlin_ast`（`named_arg(span, name)`），并补进共享层自测。
- 搁置理由：硬约束 #2 不许改共享模块；先攒到主会话评估迁移动作（此前 shared 层
  adoption=0 的教训：加 API 必须配套迁移，否则等于没加）。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t11

# 0) 从基线重建整个改动集（splice 全脚本化，可重放）
python3 probe/rebuild1.py && python3 probe/rebuild2.py && python3 probe/rebuild3.py

# 1) 修前反攻（把两门退回 f0f14d46d 后逐条重放；pinned 见 probe/t11-pre.json）
git stash            # 或：git checkout -- scripts/scan/discarded_return_guard.py \
                     #             scripts/scan/provider_remember_key_check.py
PHASE=pre  python3 probe/replay.py            # → t11-pre.json（6/6 BLIND_CONFIRMED）
git stash pop

# 2) 修后反攻
PHASE=post python3 probe/replay.py            # → t11-post.json（5/6 CAUGHT）

# 3) 本门
python3 scripts/scan/discarded_return_guard.py .            ; echo rc=$?
python3 scripts/scan/discarded_return_guard.py --self-test   ; echo rc=$?
python3 scripts/scan/provider_remember_key_check.py .        ; echo rc=$?
python3 scripts/scan/provider_remember_key_check.py --selftest

# 4) 全量（逐门独立进程；scan.sh 在本沙箱会 Errno 38）
python3 probe/gate_loop.py 0 25 && python3 probe/gate_loop.py 25 25 \
  && python3 probe/gate_loop.py 50 30 && python3 probe/gate_loop.py 80 29
python3 probe/retry.py 6            # 补 fork 失败（rc 98/99）的门

# 5) 两门在 test_scan 的注册用例（整跑交 CI）
python3 -c "import sys; sys.argv=['x']; sys.path.insert(0,'scripts/scan'); \
import test_scan as t; t.test_dv27_gates(); t.test_da1025_gates()"

# 6) CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --branch fix/r3-discard-rememberkey
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/r3-discard-rememberkey
```

**自伤记录（供主会话参考，均已闭环）**

1. `probe/replay.py` 的 restore 原本写 `git checkout -- .` —— 一次 SIGBUS 中断后把
   **两个门脚本的一小时改动全抹掉**（只剩 `src/` 需要还原）。已改成 `checkout -- src`，
   并用 `probe/rebuild{1,2,3}.py`（全锚点 + assert 的可重放重建）找回全部改动。
   教训：**harness 的还原范围必须显式收窄到被测对象**。
2. `str.replace(old, new)` 不传 `count` 时改到同文件的多处（`dbMessageId = userEntity.id`
   在 drain 与 inject 各一处），臂因此红在**错误的对象**上。凡是跨函数重复的同形行，
   一律带 count 或带更长的上下文锚。
3. #135 的第一版重放把调用折成两行 → 旧正则当场抓住（假 CAUGHT）。见证的形态必须**逐字**
   复刻（声明与调用同一行），否则「修前是 BLIND」这个前提本身是假的。
