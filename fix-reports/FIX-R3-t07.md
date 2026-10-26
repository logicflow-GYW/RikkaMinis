# FIX-R3-t07 — 会话 ID 绑定两条门（第三轮 dual-gate 收口）

- 分支 `fix/r3-sessionbinding`，工作树 `wt/t07`，基线 `main` @ `f0f14d46d`（112 门 / 344 夹具）
- 改动面：**只改 2 个门脚本** —— `scripts/scan/chat_session_id_binding_check.py`、
  `scripts/scan/session_sheet_id_binding_check.py`。生产 `.kt` 0 行、`scan.sh` /
  `test_scan.py` / `_kotlin_lexer|ast|valueflow` 零触碰、门数 112 / 夹具 344 不变。

## §1 门

| 门 | 管辖面 | 本波修的见证 |
|---|---|---|
| `chat_session_id_binding_check.py` | ChatScreen 的「草稿晋升后谁是活会话」入口面（判据 1–19） | #109（判据 8）、#110（新增判据 8b）、#111（判据 16 扩展） |
| `session_sheet_id_binding_check.py` | 两个会话面板的 id 键控 + 每会话读写（4 个会话作用域调用块、SessionEditSheet 实参、增量播种） | #112（新增写点判据）、#113（编辑面板 live row）、#114（merge `lookup`） |

## §2 轴

六条见证全部落在**第三轮的主攻轴**上：**缝上的「值」**——门证明了「某个调用存在 /
某个函数可达」，却没证明「那次调用拿到的是哪个值」。按三轴归类：

| 轴 | 见证 | 一句话 |
|---|---|---|
| dataflow | #109 #111 #112 #113 #114 | 调用做了，实参/被消费的值是错的（路由参数、`"{}"`、`""`、常量 `false`） |
| dual-gate（缝） | #109 #110 #112 #113 | 一条补丁让两门同时绿：缺陷正好落在「X 门锚调用存在 / Y 门锚另一侧的令牌」之间 |
| symmetric | #111 #114 | 同一条契约的两个半边，只判了一边（行写入 vs 驻留刷新；`liveIds` vs `lookup`） |

## §3 见证重放（修前反攻）

驱动：`probe/t07_replay.py`（`pre` / `post` 两相）。每条：就地改生产 `.kt` → 存
`probe/t07-w<id>.patch` → 跑见证列出的**每一道门** → `git checkout --` → sha256 校验还原。

`probe/t07-pre.json`（6/6 实锤，restore 6/6，事后 `git status --porcelain` 只剩两个门脚本）：

| # | kind | gates | target | 修前 rc | 判决 |
|---|---|---|---|---|---|
| 109 | dual-gate | chat / session_sheet | ChatScreen.kt:1764 `resolvePathForSession(activeSessionId → sessionId)` | 0 / 0 | **BLIND_CONFIRMED** |
| 110 | dual-gate | chat / session_sheet | ChatScreen.kt:1856 `ChatLinkResolver.resolve(url, viewModel.currentSessionId → sessionId)` | 0 / 0 | **BLIND_CONFIRMED** |
| 111 | symmetric | chat | HeadlessChatRunner.kt:345 `applyBindingToResident(resident, bindingJson → "{}")` | 0 | **BLIND_CONFIRMED** |
| 112 | dual-gate | chat / session_sheet | SessionSkillsSheet.kt:238 `setSessionOverride(sessionId → "")` | 0 / 0 | **BLIND_CONFIRMED** |
| 113 | dual-gate | chat / session_sheet | ChatScreen.kt:4618 `firstOrNull { it.id == session.id → "" }` | 0 / 0 | **BLIND_CONFIRMED** |
| 114 | symmetric | chat / session_sheet | SessionMcpsSheet.kt:101 `lookup = { seeds[it] ?: false → false }` | 0 / 0 | **BLIND_CONFIRMED** |

6 条全部与档案散文补丁等价（锚点文字、实参位置逐字符对得上，无行号漂移、无 `NOT_REPRODUCIBLE`）。

## §4 修法

### 4.1 `chat_session_id_binding_check.py`

**#109 → 判据 8 增 `_resolve_session_arg_violations()`**
旧判据只问 `"resolvePathForSession(" in effect_body`。新判据按**第一个实参的求值**判定：
`call_args` 取实参 → `value_sources` 枚举所有可能值 → 每个来源必须是本 effect 已证明绑定到
活会话的局部名（`resolved_names`，即 `val activeSessionId = ChatViewModelStore.resolveSessionId(…)`）
或 `RE_LIVE_ID_SOURCE`（`viewModel.currentSessionId` / `resolveSessionId(…)` / `.activeSessionId`）。
路由参数 `sessionId` 单列一条更具体的报错（晋升后它就是孤儿草稿键）。实参解析失败 → fail-closed。

**#110 → 新增判据 8b `_url_click_violations()`**
锚 `val urlClickHandler = …` 的 lambda 体（花括号配平；缺失/不平衡 → fail-closed），体内每个
`ChatLinkResolver.resolve(` 取**第二个实参**（签名 `resolve(rawUrl, sessionId, context)`），
判据同 8。体内一个都不剩也红（锚点漂移不许静默）。

**#111 → 判据 16 增 `_refresh_binding_violations()`**
取该 writer 的 `updateSessionBinding(` 第二个实参（行里的 binding）与 `refresh = { … }` lambda 内
`applyBindingToResident(`/`restoreFromBinding(` 的 binding 实参，要求后者的**所有**可能值都在
前者之内。`"{}"` / `bindingJson.let { "{}" }` 立刻红；`bindingJson.also { log(it) }` 仍绿
（`value_sources` 剥丢弃型作用域）。

**共享原语采用**：`match_bracket` / `call_args` / `code_view` / `value_sources` /
`expr_end` / `strip_parens`。新增的 `_session_arg_span()` 统一「括号配平在 `code_view` 上做、
偏移回投原文」三件套用法，没有自写词法器。

### 4.2 `session_sheet_id_binding_check.py`

**#112 → 新增 `_judge_session_writes()`**（skills / mcps 两面各调一次）
判「每会话写点」的**第一个实参**必须是本面板自己的 `sessionId`：`setSessionOverride("", …)` 红、
`sessionId.let { "" }` 红（`_derives_only_from`）。家族正则覆盖 `set|update|save|write|persist + …Session…(`，
声明（`fun setSessionOverride(`）不计入站点；**家族一个站点都不剩也红**（写点消失同样是失明）。

**#113 → 新增 `_judge_live_row()`**（挂在 SessionEditSheet 的 `liveSession` 实参上）
实参是裸标识符时**跟一跳**到 `val <name> = …` 的初始值（生产就是 `liveSession = liveSession`），
再在 `firstOrNull { … }` 的 lambda 里找 `it.id == X` 比较，要求 `X` 只求值为 `session.id`。
`it.id == ""` 红；完全没有 id 比较（`{ it.title != null }`）也红（无法证明选中的是本行）。

**#114 → 新增 `_judge_merge_lookup()`**（挂在 `merge*Overrides(lookup = …)` 上）
判闭包**产出**而非实参拼写：闭包体自己调 `isEnabledForSession(` → 通过（实参由既有的
`_judge_seed_read_args` 判，一条缺陷一个 owner）；否则对**尾表达式的值源**做标识符回查，
要求命中一个「本 effect 里由 `isEnabledForSession(` 绑定的映射」。`{ false }`、
`{ seeds[it].let { false } }` 都红。

**装置升级（都在本文件内，未触碰冻结面）**
1. 绿色 ChatScreen fixture 增 `urlClickHandler`（判据 8b 的锚，缺失即 fail-closed）；
2. `FIXTURE_RUNNER` 的 `persistBinding` 从 `if (vm != null) applyBindingToResident(vm, sessionId)`
   改为**生产形态**：`SessionConfigWriteThrough.deliver(persist = …updateSessionBinding(sessionId, bindingJson, modelId),
   refresh = { resident -> applyBindingToResident(resident, bindingJson, sessionId) })` + 三参 helper —— 判据 16
   才有「行 binding vs 驻留 binding」这一对可比的值；
3. 两个 sheet fixture 由 `_with_toggle()` 注入**合规**的写点锚（`setSessionOverride(sessionId, …)`），
   否则 #112 的 fail-closed 会把「夹具里没有写点」误读成「写点消失」；带自己脏写点的臂原样透传。

**共享原语采用**：`expr_end` / `match_bracket` / `value_sources` / `strip_parens` / 既有 `_sources`。

## §5 四层证据

**L1 本门（真树 + 自测，臂数只增不减）**

```
chat_session_id_binding_check .          rc=0  ("all criteria satisfied")
chat_session_id_binding_check --selftest rc=0  arms 10 → 16（dirty 8→13 / clean 2→3）
CHAT_SESSION_ID_BINDING_SELFTEST=PASS (16 B7 arms)

session_sheet_id_binding_check .          rc=0
session_sheet_id_binding_check --selftest rc=0  臂 41 → 49（+8 R3 seam arms）
SESSION_SHEET_ID_BINDING_SELFTEST=PASS
```

新增臂（全部「恰好 N 条 + 命中自己那条判据」，撞错判据即 FAIL）：

| 门 | 新臂 |
|---|---|
| X | 109 route-arg（dirty）· 110 route-arg（dirty）· 110 `.let` 丢弃值（dirty）· 111 `"{}"`（dirty）· 111 `.let{…}` 丢弃（dirty）· 111 `.also{log}`（**clean**） |
| Y | 112 `""`（dirty）· 112 `.let { "" }`（dirty）· 112 合规写点（**clean**）· 113 `it.id == ""`（dirty）· 113 无 id 比较（dirty）· 113 一跳局部名（**clean**）· 114 `{ false }`（dirty）· 114 `{ seeds[it].let { false } }`（dirty） |

**L2 修后反攻（`probe/t07-post.json`）**：6/6 **CAUGHT**，restore 6/6，`git status src/` 全程干净，
且每条都红在自己的新判据上（报错行摘录）：

```
w109 chat : pinned-shortcut calls `resolvePathForSession(sessionId, …)` — the first argument selects
            the session tree and it is the bare route arg `sessionId` …
w110 chat : markdown link clicks resolve through `ChatLinkResolver.resolve(url, sessionId, …)` — …
w111 chat : the resident ViewModel is refreshed with `"{}"` while the Room row is written with
            `bindingJson` — …
w112 Y    : SessionSkillsSheet.kt:238: the per-session write is handed `""` instead of the sheet's
            own `sessionId` — …
w113 Y    : ChatScreen.kt: `SessionEditSheet` live row is looked up with `{ it.id == "" }` — …
w114 Y    : SessionMcpsSheet.kt: the merge helper's `lookup` produces `false` — it reads no
            per-session seed …
```

**dual-gate 的两侧归属（每条都写明谁管）**

| # | 缝合处 | 修后 | 归属说明 |
|---|---|---|---|
| 109 | 预览解析实参 | X rc=1 / Y rc=0 | X 管（判据 8 的锚就在 X）；Y 锚的是 `WebPreviewBottomSheet(pinSessionId = …)`，**看不到**这次解析 → 「该门不该管这条缝」 |
| 110 | 链接点击实参 | X rc=1 / Y rc=0 | X 新增 8b 专管点击闭包；Y 管的是渲染期 provider（`LocalMarkdownSessionId provides viewModel.currentSessionId`），点击是另一个闭包，归 X |
| 112 | 面板每会话写点 | Y rc=1 / X rc=0 | Y 管（读点 `isEnabledForSession` 已在 Y）；X 的管辖面是 ChatScreen 入口，面板内部写点**不该**由 X 管 |
| 113 | 编辑面板 live row | Y rc=1 / X rc=0 | Y 管（`SessionEditSheet` 实参判据在 Y）；X 只管 ChatScreen 侧的入口 |
| 111 | 行 / 驻留双写 | X rc=1 | 只有 X 有判据 16（对称性），Y 与本缝无关 |
| 114 | merge `lookup` | Y rc=1 | 只有 Y 有 merge 判据 |

**L3 邻门 + 全量（逐门独立进程，未跑 `scan.sh` 整份）**

```
probe/t07_gate_loop.py  → gates=91 red=2
    calendar_events_nullable_predicate_check rc=2  (--root 形态，见下)
    tts_voice_and_sanitizer_check           rc=2  (--root 形态，见下)
probe/t07_guard_loop.py（逐个跑） → *_guard.py 18/18 rc=0
i18n_check.py .                  rc=0
calendar_events_… --root .       rc=0
tts_voice_and_sanitizer --root . rc=0
```

两个 red 是**接口形态**（`argparse` 只吃 `--root`），兑成 `--root .` 后全绿，不是判据失败。

**`test_scan.py`（344 夹具）**：沙箱本轮 `OSError: [Errno 38] Function not implemented`
（进程叉炸 + `tail: Function not implemented`），三次尝试分别死在
`test_stale_closure` / `exec_gate_release_check` 附近，**全量 344 交 CI 承担**。
作为替代证据，直接复刻冻结套件对本门的两条断言（同样的 `--selftest` + `run_scanner` 调用）：

```
session_sheet_id_binding_check selftest rc=0 marker=True    real tree rc=0
chat_session_id_binding_check  selftest rc=0 marker=True    real tree rc=0
```

**L4 CI**：见 §6。

## §6 CI

| 项 | 值 |
|---|---|
| run | **#2734** / id `40310010014` / job `121528522496` / attempt **1** |
| 事件 | `workflow_dispatch`（分支 push 不触发，按纪律手动 dispatch） |
| head_sha | `80aef69a1da3f2ed6973a57ddbc4720ff2dddee5` —— 与本地 `git rev-parse HEAD` **逐字符一致** |
| 结论 | **success**（10:46:26Z → 11:11:08Z，24m42s；26 步，唯一非 success = 第 23 步 `Publish to Releases` **skipped**，main 门控符合预期） |
| 日志 | 1,486,498 B / NUL 0 / 17,852 行 |

逐条读数（判读口径见 evidence-discipline：「FAILED 行」先分辨用例名子串）：

```
Summary: 112 passed, 0 failed        恰 1 条
ALL 344 CASES PASS                   恰 1 条        ← 冻结夹具全绿（本波未跑本地全量，由 CI 承担）
^ e:                                 0 条           ← 无编译错误
CHAT_SESSION_ID_BINDING_SELFTEST=PASS    恰 1 条    ← 16 臂在 CI 里有见证
SESSION_SHEET_ID_BINDING_SELFTEST=PASS   恰 1 条    ← 49 臂
含 FAILED 的行                        6 条，全部以 PASSED 结尾（用例名子串，非失败）
```

两门在 CI 里真跑（不是被跳过）：

```
[93/112] F/ISSUE-06 chat session id binding
chat_session_id_binding_check: all criteria satisfied
session_sheet_id_binding_check: OK (4 session-scoped call blocks + LocalMarkdownSessionId …)
✅ chat_session_id_binding_check selftest has teeth (CHAT_SESSION_ID_BINDING_SELFTEST=PASS)
✅ session_sheet_id_binding_check selftest has teeth (SESSION_SHEET_ID_BINDING_SELFTEST=PASS)
```

**门自证 stdout 不进 CI 日志**（既知装置限制）：`test_scan` 只断言 marker，`R3 seam arms = 8`
这类计数行不会出现在 job 日志里（本次 grep `R3 seam` = 0 命中，符合预期）。臂数证据由本报告 §5
与 `--selftest` 本地输出承载。

## §7 残留（P2，各带触发条件 · 触发后方案 · 搁置理由）

**R1 `_refresh_binding_violations` 只判 `refresh = { … }` 写通形态**
- 触发条件：`persistBinding` 改成不走 `SessionConfigWriteThrough.deliver(refresh = …)`，而是把驻留刷新写成别的命名参数或直接裸调用。
- 触发后方案：把判据扩成「writer 内任何接收 binding 位置的驻留调用」，并把行 binding 作为唯一允许值集。
- 搁置理由：当前生产只有一种形态；删掉 `refresh =` 时判据 16 的 `refreshed` 令牌仍会红（方向安全），不会静默放行。

**R2 `_judge_live_row` 只在值里存在 lookup lambda 时判定**
- 触发条件：`liveSession = ` 改成传一个已在别处算好的行对象（`liveSession = liveRowProvider.current`），值里没有 `firstOrNull { }`。
- 触发后方案：跟到该 provider 的产出点（需要跨文件数据流，超出本门半径）。
- 搁置理由：这种拼写下的「选错行」缺陷等价于「provider 实现错」，属另一门（sessions 面）的缝；本门当前的 `liveSession = session`（参数默认）与 lookup 两形态已覆盖生产。

**R3 `_judge_merge_lookup` 只判 lambda 字面量**
- 触发条件：`lookup = seedLookup`（具名函数引用）代替闭包。
- 触发后方案：解析该函数体的返回值（同 R2 的跨符号数据流）。
- 搁置理由：两个面板都写成内联闭包；改成具名引用时闭包内的种子读通常会跟着搬走，缺陷面变成「那个函数错」而不是「缝」，属下一轮。

**R4 `lookup` 闭包「先直接读、再丢弃值」仍算合规**
- 触发条件：`lookup = { repo.isEnabledForSession(it, sessionId).let { false } }`。
- 触发后方案：把「闭包体含 `isEnabledForSession(`」这一条从「直接放行」降级为「读取来源命中」，再叠加尾表达式值源必须含该调用或其绑定。
- 搁置理由：这条与 `_judge_seed_read_args`（实参判据）共用同一批站点，叠判定会双重归属同一缺陷；本轮已有 `{ false }` 与 `{ seeds[it].let { false } }` 两臂，剩余形态价值低（0 命中成本也不低的正则化改造）。
- **注**：本轮的 W9 臂（`isEnabledForSession(…, sessionId.let { " " })`）在首版实现里被这条误判为 2 条，已按「一条缺陷一个 owner」收敛为 1 条。

**R5 `_judge_session_writes` 的家族正则覆盖面**
- 触发条件：写点改名成不含 `Session` 的词（`toggleSkillOverride(`）。
- 触发后方案：改成「第一实参等于面板 `sessionId` 且被 `overrides`/种子映射消费」的派生式判定。
- 搁置理由：改名同时会让「家族一个站点都不剩」的 fail-closed 分支报红，方向安全（误报而非放行）。

**R6 #110 的另一侧（渲染期 provider）不在本波范围**
- 触发条件：`LocalMarkdownSessionId provides sessionId`（渲染期裸路由参数）。
- 触发后方案：Y 的既有判据 646–659 已覆盖（`provides viewModel.currentSessionId` 必需）——即**已有人管**。
- 搁置理由：无。

**R7 X 的判据 8b 只判点击闭包**
- 触发条件：`ChatLinkRenderCache(resolveFn = { url, sid -> … })` 的 `sid` 来源被改错。
- 触发后方案：把 `sid` 的调用点跟到 `LocalMarkdownSessionId`（跨门）。
- 搁置理由：该侧的 id 来源由 Y 的 `LocalMarkdownSessionId` 判据钉住；本门只补点击侧，避免两门对同一行重复覆盖（spec §3 的「重复覆盖 ≠ 空白」）。

## §8 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t07

# 修前 / 修后反攻（6 条，两次跑，第二次对应本提交的判据）
python3 probe/t07_replay.py pre     # 期望 6 × BLIND_CONFIRMED，restore_ok
python3 probe/t07_replay.py post    # 期望 6 × CAUGHT，每条红在自己的判据上

# 本门
python3 scripts/scan/chat_session_id_binding_check.py .            # rc=0
python3 scripts/scan/chat_session_id_binding_check.py --selftest   # 16 arms
python3 scripts/scan/session_sheet_id_binding_check.py .           # rc=0
python3 scripts/scan/session_sheet_id_binding_check.py --selftest  # 49 arms

# 全量逐门（不要跑 scan.sh 整份）
python3 probe/t07_gate_loop.py .     # 91 门；calendar/tts 两个需 --root 形态
python3 probe/t07_guard_loop.py .    # 18 guard 门
python3 scripts/scan/calendar_events_nullable_predicate_check.py --root .
python3 scripts/scan/tts_voice_and_sanitizer_check.py --root .
python3 scripts/scan/i18n_check.py .
```
