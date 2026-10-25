# FIX-WIDE3-04 — 四门判据加固（loop / turn / flow / trace）

- **分支**：`fix/meta-wide3-04-loop-turn`
- **基线**：main `6b6c918f`（wide-1024 第二波收口）
- **面**：`truncated_call_guard_check` · `stale_closure_guard` · `stream_flow_dispatch_guard` · `trace_step_tag_check`
- **见证**：13 条（C1/C4/C8/C10 · P1×2/S2 · C2×2/C1 · face_lock×2/face_schema）
- **生产代码**：**0 行改动**（只改 `scripts/scan/<本支四门>.py`）

---

## ① 门与判据

| 门 | 守的契约 | 判据数（前 → 后） |
|---|---|---|
| `truncated_call_guard_check.py` | 被截断的 tool_call 必须被拒绝：策略要同时读「协议层 ceiling」与「流层 EOF」两个源，引擎与子代理循环都要把结果真正当判定用，两家 provider 的「工具活动」权重必须来自流数据 | 15 → 19 |
| `stale_closure_guard.py` | 长生命周期 effect（`produceState`）不得捕获普通参数（陈旧闭包），滚动阈值必须真的进到底部判定 | 6 → 7 |
| `stream_flow_dispatch_guard.py` | 冷流（`callbackFlow`/`channelFlow`）的阻塞体必须离开收集者线程 | 1（+2 派生） → 3 面 |
| `trace_step_tag_check.py` | 标注器依赖的 trace 契约（4 类型 + 4 字段 + 两个写路径的锁归属）必须在 Kotlin 侧成立 | 6 → 9 |

## ② 失效轴（本支四门）

| 轴 | 门 | 形态一句话 |
|---|---|---|
| dataflow | truncated (C1/C4/C8) · stale (P1/S2) · trace (face_schema) | 表达式/调用/字段**还在**，但它的结果不再被消费（被 `if (false)` 包住、被单源早退绕过、被常量实参喂死） |
| spelling | stream (C2) | `flowOn(Dispatchers.Unconfined)` 不含 "Main" ⇒ 旧判据当安全 |
| scope | stream (C2) | 链上的 `flowOn` 正确，**体内** `withContext(Dispatchers.Main)` 把阻塞搬回 UI 线程 |
| parser-death | truncated (C10) · stale (P1) · stream (C1) · trace (face_lock) | 判据自己的解析器被反将：常量实参、`"/*"` 字面量开假注释、`callbackFlow({` 换形态、默认参数 lambda 冒充函数体 |
| completeness | trace (face_lock) | 只判读者、不判写者：`beginRun` 三字段失去同一把锁时全部判据仍绿 |

## ③ 逐条见证

| # | 见证 | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|---|
| 1 | C1 `if (!ceilingDeclared) return null` 插在真条件之后 | dataflow | 只查 `C1_CONDITION` 子串 + `[TRUNCATED]` 前缀计数 | `policy_single_source_returns()`：解析 `rejectionReason` 函数体，逐个 `if (…) { return null }` 求条件里出现的**源 token 集合**（ceiling: `ceilingDeclared`/`isTruncatedFinish`/`finishReason` vs 流: `turnTruncated`），只提到一侧 ⇒ `policy-single-source-early-return` 红 |
| 2 | C4 `val truncatedRefusal = if (false) …rejectionReason(…) else null` | dataflow | `loop.count(C4_POLICY_CALL) < 1` —— 子串在即算接线 | `operative_call()`：`val X =` 的**整个初始化式**必须是该调用（括号配平、语句在此结束、续行以 `.`/`?:`/`?` 开头判红），且第 3 实参必须是 `turnTruncated` |
| 3 | C8 `toolCallEventCount += chatCompletionsToolCallEventWeight(0)` | dataflow | 只算「callee 名字集合 ⊆ 声明集合」+ 两路都能喂 | `feed_calls()` + `is_constant_expr()`：`counter += fn(args)` 的实参不得是纯字面量 ⇒ `openai-count-derived` 红 |
| 4 | C10 `toolChars += toolUseStartActivityChars("", "")`（守卫仍在） | parser-death | 只判「守卫内存在一次写」 | 同上，作用于**守卫内的**写 ⇒ `anthropic-tool-start-feeds-counter` 红 |
| 5 | P1 `snapshotFlow { latestContent }` → `{ content }` | dataflow | 「附近有 `rememberUpdatedState(p)` ⇒ 跳过该参数」，于是包装存在、体内读原值全绿 | `read_zone()` 只取 effect 的**尾随 lambda 体**（调头 `initialValue = p` 与 key 列表不算读），`rememberUpdatedState(p)` 自身掩掉，裸读 p 一律红 |
| 6 | P1 上方插入 `val openMask = "/*"` | parser-death | `BLOCK_COMMENT_RE` 不认字面量：`"/*"` 开假注释吞掉整个 producer 体 | `strip_comments()` 换成字面量感知的 `code_only`（保留偏移/换行），`stale-ok:` 逃逸行原样保留 |
| 7 | S2 `if (false) nearBottomThresholdPx.toInt()` + 真调用传 `0` | dataflow | 数 `nearBottomThresholdPx` 出现次数 ≥2 —— 死分支里的一次也算 | `call_args()` + `threshold_param_index()`：remember 体内**每个** `isListAtBottom(…)` 都必须在（由函数签名派生的）阈值位置上传一个源自 `nearBottomThresholdPx` 的值 |
| 8 | C2 `flowOn(Dispatchers.Unconfined)` | spelling | 黑名单式：含 `Main` 才算坏 | 白名单 `SAFE_DISPATCHERS = (IO, Default)`；`Unconfined` / `EmptyCoroutineContext` / 读不出的表达式一律 fail-closed |
| 9 | C2 体内 `withContext(Dispatchers.Main) { call.execute() }` | scope | 只看链尾 `flowOn`，不看体内 | `body_dispatch_hazards()`：体内每个 `withContext/launch/async/flowOn/runBlocking` 派发器若不在白名单，且实参/尾随块命中阻塞模式 ⇒ 红 |
| 10 | C1 `= callbackFlow({`（尾随 `}.flowOn(…)` 保留） | parser-death | `FLOW_RE` 只认 `builder {` 尾随形态，括号形态整个流看不见 | `FLOW_BRACE_RE` + `FLOW_PAREN_RE` 双形态；括号形态要求 `)` 紧跟函数体（否则判定 unparseable → fail-closed），另加「扫到 0 个流点 / 源根缺失 ⇒ 红」 |
| 11 | face_lock：`beginRun` 的三字段更新去掉 `synchronized` | completeness | 只判 `write`/`writeTerminalOnce` 的**读**侧 | 新增写侧判据：`beginRun` 必须把 `activeRunId/activeSessionId/terminalWritten` 写在 `synchronized(writeLock)` 内，且三者都要被赋值（缺赋值 = ROT） |
| 12 | face_lock：`write(type, block = { sync { build(…) } })` 默认参数有锁、真体在锁外 | parser-death | `code.find("{", start)` 抓到默认参数里的 lambda 当函数体 | `body_brace()`：函数体 `{` 必须在**参数表右括号之后**，中间只允许返回类型（`_RET_TYPE_ONLY`），否则 ROT |
| 13 | face_schema：`if (false) put("args", sanitize(…))` | dataflow | `'put("args"' in src` —— 字符串在即算字段在 | `mask_comments()`（掩注释、留字段名）+ `if_bodies()`/`provably_false()`（恒假守卫 ⇒ 字段永远写不出）+ 值必须非常量（`put("args", "")` 同样红） |

## ④ 修法

四处都是「把子串判据换成结构判据」，**判据自己的解析器同样被加固**：

1. **新增结构工具（每门内自带，不跨文件共享——本支只准改自己那 4 个门文件）**：
   `mask_code()`（掩注释 + 掩字面量内容，保偏移）、`match_paren()`、`split_top()`、`if_bodies()`、`function_body()`/`body_brace()`、`is_constant_expr()`、`feed_calls()`、`operative_call()`、`read_zone()`、`dispatcher_verdict()`、`body_dispatch_hazards()`、`flow_sites()`、`provably_false()`。
2. **同族一并加固（半径内的同一条轴，不是新面）**：
   - 引擎 `C2` 与子代理 `C4` 共用正则判据 ⇒ 两处同时改成 `operative_call()`（子代理是见证本体，引擎是见证报告点名的同款盲区）。
   - Anthropic 的 stop/delta 两条喂点同样加了「实参不得为常量」。
   - `truncated` 的 C8 名字集合判据保留（它管「派生单一来源」），「实参被常量喂死」作为**新的独立判据**加入。
3. **fail-closed 方向**：凡解析不出（括号不配平、函数体定位失败、扫描面塌缩为 0）一律报红，绝不静默放行——这正是本波见证的共性是「静默」。
4. **臂**：每条新判据都自带负向臂，且断言「恰好该臂红、其余臂绿」：
   `truncated` 26 臂（新增 5）、`stale` 新增 4 臂（P1 control / P1 dataflow / P1 parser-death / N7）、`stream_flow` **新增 `--selftest`** 9 臂、`trace` 牙1–牙8（新增 4）。

## ⑤ 四层证据

**L1 四门真树绿 + 自带臂全绿**

```
$ python3 scripts/scan/truncated_call_guard_check.py .
✅ truncated-call gate: policy reads both truncation sources, …
$ python3 scripts/scan/truncated_call_guard_check.py --selftest | tail -2
✅ truncated-call gate --selftest: 26 arms flip exactly their criterion
TRUNCATED_CALL_GUARD_SELFTEST=PASS

$ python3 scripts/scan/stale_closure_guard.py .            → rc=0（两行 ✅）
$ python3 scripts/scan/stale_closure_guard.py --self-test  → ✅ self-test has teeth … ; rc=0

$ python3 scripts/scan/stream_flow_dispatch_guard.py .             → rc=0
$ python3 scripts/scan/stream_flow_dispatch_guard.py --selftest    → STREAM_FLOW_DISPATCH_SELFTEST=PASS（9 臂）

$ python3 scripts/scan/trace_step_tag_check.py .           → TRACE_STEP_TAG_CHECK: OK
$ python3 scripts/scan/trace_step_tag_check.py --selftest  → TRACE_STEP_TAG_SELFTEST=PASS（牙1–牙8）
```

**L2 原见证反攻（修复前 13/13 STILL_BLIND → 修复后 13/13 CAUGHT）**

```
$ python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" --gates <门>.py
truncated_call_guard_check.py  C1   CAUGHT rc=1 dataflow
truncated_call_guard_check.py  C4   CAUGHT rc=1 dataflow
truncated_call_guard_check.py  C8   CAUGHT rc=1 dataflow
truncated_call_guard_check.py  C10  CAUGHT rc=1 parser-death
stale_closure_guard.py         P1   CAUGHT rc=1 dataflow
stale_closure_guard.py         S2   CAUGHT rc=1 dataflow
stale_closure_guard.py         P1   CAUGHT rc=1 parser-death
stream_flow_dispatch_guard.py  C2   CAUGHT rc=1 spelling
stream_flow_dispatch_guard.py  C2   CAUGHT rc=1 scope
stream_flow_dispatch_guard.py  C1   CAUGHT rc=1 parser-death
trace_step_tag_check.py  face_lock_scope CAUGHT rc=1 completeness
trace_step_tag_check.py  face_lock_scope CAUGHT rc=1 parser-death
trace_step_tag_check.py  face_schema_fields CAUGHT rc=1 dataflow
CAUGHT=13 / RESULT: ALL CAUGHT
```

修复前同一 harness 的基线：`STILL_BLIND=13`（全部门 rc=0 —— 见证确实能过门）。

命中判据抽样（证明不是「被别的判据顺手抓住」）：

```
stale P1 : AnimationPreview.kt:8 content [B-01 P1] produceState 体直接读参数 content（陈旧闭包）
stale S2 : ChatScreen.kt:1000 [B-03 S2] isListAtBottom is called with '0' instead of a value derived from nearBottomThresholdPx
stream C2: AnthropicProvider.kt:162 (blocking at :223) call.execute() [flowOn(Dispatchers.Unconfined)]
stream C1: builder({ … }) 的调用不在函数体之后闭合 … [unparseable — fail-closed]
trunc C1: `if (!ceilingDeclared) return null` decides on ONE truncation source
trunc C8: 'toolCallEventCount' is fed through chatCompletionsToolCallEventWeight(0)
trunc C10: the tool_use announcement feeds 'toolChars' with a constant weight
trace 锁 : beginRun assigns 'activeRunId' outside 'synchronized(writeLock)'
trace 字段: 字段 put("args")只出现在恒不执行的 if 分支里（false）
```

**L3 邻门/邻面**：全量 111 道门在真树全部 rc=0（分片跑，见 ⑥ 前的命令），无新增红面。

**L4 全量 + CI**：见 ⑥。

## ⑥ CI

- 分支：`fix/meta-wide3-04-loop-turn`
- HEAD：见提交后回填（`git rev-parse HEAD` 与 run 的 `head_sha` 逐字符一致才算绿）
- run：见回填

## ⑦ 残留（触发条件 / 触发后方案 / 搁置理由）

1. **`truncated` 的旧 pin 仍用非字面量感知的 `strip_kotlin_comments`**（新判据用 `mask_code`）。
   触发条件：出现「provider 文件里靠近判据点写入 `"/*"` 字符串」的见证；触发后方案：把旧 pin 一并换 `mask_code`（同文件内改 3 处调用）；搁置理由：本波未见该轴作用于旧 pin，换掉会动既有臂的行号基线，属半径外。
2. **「实参非常量」只拒绝纯字面量**：`chatCompletionsToolCallEventWeight(n - n)` 这类自相消表达式仍算「非常量」。
   触发条件：下一个见证用算术自消；触发后方案：加常量折叠 + 同一表达式内同标识符差值为 0 的检测；搁置理由：文本级代数推理无边界，收益/装置比不划算（修复门 P2）。
3. **`stale` 的 `read_zone()` 只认尾随 lambda**：`produceState(initialValue = x, producer = { … })` 这种具名 lambda 会被判 fail-closed 红（方向安全，属误报）。
   触发条件：有人改写成具名实参形态；触发后方案：接受 `producer = {` 作为读区起点；搁置理由：当前树无此形态，误报方向是「多报」而不是「漏报」。
4. **`stale` 的阈值位置靠形参名含 `threshold` 派生**：改名后退化为「任一位实参含该值」（仍抓常量，位置盲）。
   触发条件：签名改名或重排；触发后方案：按调用点与签名联合匹配（参数个数 + 类型）；搁置理由：退化后仍能抓本波见证，且名字派生同时防「签名变更后静默失效」。
5. **`stream` 只查体内内联派发器切换**：`withContext(mainDispatcher())`（helper 返回 Main）不可见。
   触发条件：出现经 helper 间接切主线程的见证；触发后方案：同文件一跳 helper 返回式解析；搁置理由：跨文件数据流超出文本门的能力边界。
6. **`stream` 的阻塞模式表是固定集合**（`call.execute()` / `readLine()` 等）：新 API 形态不在表内。
   触发条件：新增阻塞式网络/IO 调用；触发后方案：随代码库演进补模式；搁置理由：这是该门既有契约，本波无见证触及。
7. **`trace` 的 `provably_false` 只认字面量条件**：`if (BuildConfig.DEBUG_NEVER)`、`if (false.also { })` 不算死分支。
   触发条件：见证改用常量折叠之外的恒假形式；触发后方案：接常量表（`const val X = false`）；搁置理由：需要符号表，装置比例超出单门范围。
8. **`trace` 的字段存在判据仍可能被「代码里的字符串常量」满足**（注释已排除）：如 `const val K = "put(\"args\""`。
   触发条件：出现该形态；触发后方案：只接受 `JSONObject.put` 接收者（需类型信息）；搁置理由：需要类型解析，纯文本门做不到。

## ⑧ 复现命令

```sh
cd /var/minis/shared/work/meta-wide3/wt-04          # 分支 fix/meta-wide3-04-loop-turn

# 1) 四门真树 + 自带臂
for g in truncated_call_guard_check stale_closure_guard stream_flow_dispatch_guard trace_step_tag_check; do
  python3 scripts/scan/$g.py . && echo "GREEN $g"
done
python3 scripts/scan/truncated_call_guard_check.py --selftest
python3 scripts/scan/stale_closure_guard.py --self-test
python3 scripts/scan/stream_flow_dispatch_guard.py --selftest
python3 scripts/scan/trace_step_tag_check.py --selftest

# 2) 13 条原见证反攻（修复前应全 STILL_BLIND，修复后应全 CAUGHT）
for g in truncated_call_guard_check stale_closure_guard stream_flow_dispatch_guard trace_step_tag_check; do
  python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" --gates $g.py
done

# 3) 全量（沙箱负载高时用分片驱动 / 持锁运行器）
python3 /tmp/gate_slice.py "$PWD" 1 28      # 91 道 *_check.py 分 6 片
python3 scripts/scan/test_scan.py            # ALL 344 CASES
```
