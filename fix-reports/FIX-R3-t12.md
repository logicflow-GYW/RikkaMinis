# FIX-R3-t12 —— `log_url_redaction_guard` + `provider_ua_parity_check`（第三轮 dual-gate 见证收口）

- 支线：`fix/r3-logredact-ua`（工作树 `round3b/wt/t12`）
- 基线：`main` @ `f0f14d46d`（第七批收口后）
- 门提交：`0c65e85f7`（2 文件 +737 / −207；**生产 `.kt` 0 行**）
- 门数 112 / `test_scan` 夹具 344 / `scan.sh` / `test_scan.py` / `_kotlin_*` **零触碰**
- 判决：**6/6 见证修前 BLIND_CONFIRMED，修后 6/6 被抓**（各红在自己的判据上）

## ① 门与轴

| # | 门 | 位置 | 见证 | 失效轴 |
|---|---|---|---|---|
| 11 | `log_url_redaction_guard.py` | scan.sh `[19/112]` | 11-1 / 11-2 / 11-3 | **spelling（名字）→ dataflow（值）** · 对称轴（narrow 白名单） |
| 12 | `provider_ua_parity_check.py` | scan.sh `[55/112] D/ISSUE-04 provider customUserAgent parity + EOF` | 12-1 / 12-2 / 12-3 | **dataflow（消费）· anchor（按函数名）** |

两条门修前是同一族失效的两种形态：

1. **判据锚「某拼写/某名字存在」而不是「值是否真的那样」**。门 11 只问「这个表达式里的名字含不含 `url`」——改名一行（`targetEndpoint`、`errMsg`、`basePath`）即可穿；门 12 只问「函数体里有没有 `.applyUserAgentOverride(`」——覆盖被建出来却送给别的值即可穿。
2. **决定点由人挑（函数名/名字后缀）而不是从树派生**。门 12 的 D1 只锚 `rawPassthroughRequest`，同一文件里对称的 `generateImage` builder 压根不在扫描面上（12-3）；门 11 的 `NARROW_NAME` 把 `path$` 当免罪符（12-3 的 `basePath` 装着 `https://api.openai.com/v1`）。

## ② 见证重放（pre-fix 反攻，全部机械构造）

命令：`python3 probe/t12_replay.py pre` → `probe/t12-pre.json`；补丁 `probe/t12-w139..w144.patch`（`git diff` 原样存档）。

| id | 见证 | 落点 | 修前 rc（门 11 / 门 12） | 判决 |
|---|---|---|---|---|
| 11-1 (#139) | `val targetEndpoint = url` + `Log.w(TAG, "uncaught for $targetEndpoint")` | `OpenOffloadHandler.kt:111` | 0 / — | **BLIND_CONFIRMED** |
| 11-2 (#140) | `val errMsg = "Download failed … for $rawURL"` + `Log.w(TAG, errMsg)` | `SkillRepository.kt:1082` | 0 / — | **BLIND_CONFIRMED** |
| 11-3 (#141) | `Log.i(… "endpoint base=$basePath")`（`basePath = "https://api.openai.com/v1"`） | `OpenAIProvider.kt:1717` | 0 / — | **BLIND_CONFIRMED** |
| 12-1 (#142) | `newCall(effectiveRequest)` → `newCall(request)` | `VoiceProvider.kt:253` | 0 / 0 | **BLIND_CONFIRMED** |
| 12-2 (#143) | `newWebSocket(wsRequest, …)` → 新建裸 `Request.Builder().url(signedUrl).build()` | `VoiceProviderVendors.kt:386` | — / 0 | **BLIND_CONFIRMED** |
| 12-3 (#144) | 删 `generateImage` 的 `builder.applyUserAgentOverride(customUserAgent)` | `OpenAIProvider.kt:1701-1715` | — / 0 | **BLIND_CONFIRMED** |

双门见证的邻居门（`offload_cli_args_check` / `skill_restore_session_cleanup_check` / `voice_cancel_guard_check`）修前也全 rc=0 —— 与报告一致：**缺陷落在缝上，缝两侧的门各自只看自己那一侧。**

**replay 后还原证据**：5 个被改的 `.kt` 与 `HEAD` 逐字节相同（sha256 前 16 位逐一比对：`OpenOffloadHandler c2ad81252e1b0158` / `SkillRepository bb9f2033c150da50` / `OpenAIProvider b1780cd2691ecebe` / `VoiceProvider 69dc9c3d3ac94dd3` / `VoiceProviderVendors d36651eb2309daa5`），`git status --porcelain` 全程只剩两条门脚本。

## ③ 判据改造（旧形态 → 新形态）

### 门 11 `log_url_redaction_guard.py`（+692/−207，臂 17 → 28）

| # | 旧 | 新 |
|---|---|---|
| 1 | 只看 `$ident` / `${expr}` / `+` 操作数里**名字**含 `url` | **每次日志调用的每个实参**都判（`Log.w(TAG, msg)` 的裸实参也算），名字不含 `url` 就**顺着绑定回溯值来源**（`_follow_name`，深度≤4，函数体优先、文件级兜底）：`targetEndpoint → url`、`errMsg → "… $rawURL"`、`basePath → "https://…"` |
| 2 | `NARROW_NAME`（`…path`/`…host`）无条件豁免 | narrow 名字**既不自证清白也不一概定罪**：追值流，追到整条 URL 才算（`basePath` 红，`absolutePath`/`dirPath` 绿） |
| 3 | 裸实参不判（只判插值/拼接） | 实参一律判；`_spine()` 抹掉括号/花括号**内部**，只判取值位标识符（`ContentUris.parseId(uri)` 的值是 `Long`，不是 `uri`） |
| 4 | `URLISH = url\|uri\|endpoint` 子串匹配 | 驼峰**词元**匹配（`URL_TOKENS`）：`ContentUris` 不再被当成 url 值 |
| 5 | 尾部换算调用不识别 | `NOT_VALUE_PRESERVING`（`toLong*`/`hashCode`/`parseId`/`startsWith`/`is[A-Z]*` …）：值被换算成非字符串，不再继承 URL 身份 |
| 6 | 自写 `_strip_span`（括号计数 + 手写字符串状态机）、`_scan_braced`（手写花括号计数） | 全删。结构走 `mask_code` + `match_bracket` + `split_top` + `call_args`；表达式文本取自 `mask_comments`（两视图**等长**，位置 1:1）；模板 `${…}` 用共享词法器的 `_skip_template` 配平 |
| 7 | 语句 12 行内不配平 → 报 red（词法器外的启发式） | 配平失败（日志调用括号 / `${`）一律 fail-closed 报违规；`KotlinLexError` → 报违规不回退原文 |
| 8 | 绑定靠 `name =` 裸正则 | 三类假阳收口：**命名实参**（`f(id = sourceURL)`）不算绑定（`_paren_depth_at`）、**声明类型不跨逗号**（`_ASSIGN_DECL: [^=,)\n]`）、**右值终点 = 顶层逗号/闭括号**（`_rhs_end`，`val id: String = UUID…,` 不再把整个构造参数列表卷进右值） |

**值流判据让 4 处旧漏网第一次可见**（是真泄漏，不是"安全"）：`SkillRepository.kt:1457`（`$msg ← $downloadURL`）、`:1501/:1511/:1515`（`$lastReason ← $apiURL`）、`PhotosOffloadHandler.kt:904`（`$uri`）。旧判据只问名字含不含 `url`，`msg`/`lastReason`/`uri` 一个都不含。**本支不动生产 `.kt`（radius）**，因此按既有 ALLOWED 政策钉住并在 ALLOWED 里写明「这是真泄漏 / 删条目会让门重报」；报告 §⑥ R1 登记。

### 门 12 `provider_ua_parity_check.py`（+252，臂 56 → 60）

| # | 旧 | 新 |
|---|---|---|
| 12-1 | V1：`executeRequest` 体里出现 `.applyUserAgentOverride(customUserAgent)` 即绿 | **V1b 消费判据**：函数体内每个 `.newCall(` 的**第一个实参**必须 `request_carries_ua` —— 变量 → 找本函数内声明，逐条看 `name.build(` **之前**有没有一次 `path_feasible` 的 override；内联链 → 链上必须有 override；形参/不可解析 → None（fail-closed 报违规） |
| 12-2 | V2：`.url(signedUrl)` 到 `.build()` 窗口里有 override 即绿 | **V2b**：`newWebSocket(` 的第一个实参必须 `request_carries_ua`（用的实参，不是上面建好的局部）；文件里没有 `newWebSocket(` 站点也报（锚点漂移） |
| 12-3 | D1 只锚 `rawPassthroughRequest`（按**函数名**） | **D4 `check_openai_builders`**：从树里枚举**每一个** `Request.Builder()`，回溯它的 builder 变量与本函数内所有 `build()` 点，要求每个 build 之前有可达 override；内联链同样要求；builder 在函数内从未 build → fail-closed。新增请求构建路径**自动进闸门** |
| — | 无豁免机制 | `OPENAI_BUILDER_EXEMPT` 钉住唯一一处**确实不该带 UA** 的 builder（`Request.Builder().url(urlStr).get().build()`，下载 provider 返回的第三方 CDN 资产），带理由；只按该行片段匹配 |

## ④ 四层证据

1. **本门（真树绿 + 自测带牙）**
   - `python3 scripts/scan/log_url_redaction_guard.py .` → rc=0（`audited sites: 18`）
   - `python3 scripts/scan/log_url_redaction_guard.py --selftest` → `URL_REDACTION_SELFTEST=PASS (28 arms)`（17 → 28，只增不减）
   - `python3 scripts/scan/provider_ua_parity_check.py .` → rc=0
   - `python3 scripts/scan/provider_ua_parity_check.py --selftest` → `PROVIDER_UA_PARITY_SELFTEST=PASS (60 arms)`（56 → 60；收尾标记原文保留，`test_scan.py:2761` 的 `PROVIDER_UA_PARITY_SELFTEST=PASS` 断言不受影响）
   - 新臂各自的 clean 对照（门 11）：`alias-path-suffix-real-path`（`val basePath = url.path`）、`alias-count-renamed`（`val hits = urlCount`）、`alias-unrelated-string`、`alias-unresolvable`、`alias-redacted-binding`、`alias-component-binding` —— 六条**必须在修后仍绿**的臂，专门防「把别名一律判红」这种过度修正。
2. **邻门**：`offload_cli_args_check` / `skill_restore_session_cleanup_check` / `voice_cancel_guard_check` / `i18n_check` 全部 rc=0；本支两门互相也是 rc=0（门 11 与门 12 语义相邻，同跑真树都绿）。
3. **全量（逐门独立进程，未跑 `scan.sh`）**：`python3 probe/t12_gate_loop.py [offset] [limit]`
   - `[1..30]` rc 全 0（第一段跑到 30 时沙箱爆 `OSError: [Errno 38] Function not implemented`，即已知 S28，见下）
   - `[31..70]` **RED: 0 of 40**
   - `[71..109]` **RED: 0 of 39**
   - 合计 **109/109 rc=0**（`*_check.py` + `*_guard.py`；`calendar_events_nullable_predicate_check`、`tts_voice_and_sanitizer_check` 用 `--root` 形态）。
   - `test_scan.py`（344 夹具）**本地跑不动**：连续两次被 `OSError: [Errno 38]` 打断（第二次连 `/bin/sh: can't create /tmp/.minis_cwd_*` 都失败）。第二次跑到 `test_da1019_gates` 时**已确认本支相关注册项通过**：
     ```
     ✅ provider_ua_parity_check selftest has teeth (PROVIDER_UA_PARITY_SELFTEST=PASS)
     ✅ provider_ua_parity_check real tree green
     ```
     随后在同一函数的下一个无关门（`katex_ready_race_check`）被 Errno 38 打断 ⇒ 全量 `ALL 344` 交 CI 承担（见 §⑤，CI 侧恰一条 `ALL 344 CASES PASS`）。
4. **反攻（post-fix）**：`python3 probe/t12_replay.py post` → `probe/t12-post.json`，6/6 抓到，每条**红在自己那条判据上**：

```
w139 FIXED  log_url_redaction_guard=1 offload_cli_args_check=0
     → "uncaught for $targetEndpoint: …"  泄漏形态: $targetEndpoint（名字即 URL 载体）
w140 FIXED  log_url_redaction_guard=1 skill_restore_session_cleanup_check=0
     → "Log.w(TAG, errMsg)"               泄漏形态: errMsg ← $rawURL（名字即 URL 载体）
w141 FIXED  log_url_redaction_guard=1 provider_ua_parity_check=0
     → "endpoint base=$basePath"          泄漏形态: basePath ← "https://api.openai.com/v1"
w142 FIXED  provider_ua_parity_check=1 voice_cancel_guard_check=0
     → VoiceProvider.kt:253 executeRequest hands '.newCall(' a request that does not carry the UA override (request)
w143 FIXED  provider_ua_parity_check=1 voice_cancel_guard_check=0
     → VoiceProviderVendors.kt:385 '.newWebSocket(' receives a request that does not carry the UA override
w144 FIXED  provider_ua_parity_check=1
     → OpenAIProvider.kt:1701 builder `builder` reaches `builder.build()` without '.applyUserAgentOverride(' on a reachable path
```

**双门要求（任务书 §2.2）**：6 条见证里 12-1 / 12-2 / 12-3 的**两门都归门 12**，修后门 12 红、邻居门（`voice_cancel_guard_check`）绿 —— 邻居管的是取消监听与空转写守卫，**这条缝不该它管**（`why: UA 覆盖是否被消费不在它的契约内`），由门 12 的 V1b/V2b/D4 接管。11-1/11-2/11-3 同理：`offload_cli_args_check` 管的是 argv 解析与 cwd，`skill_restore_session_cleanup_check` 管的是会话清理，URL 是否入日志由门 11 独占。

## ⑤ CI

- 分支 `fix/r3-logredact-ua`，门提交 **`0c65e85f775c70b7361728d4bfbc076f5df9fae8`**
- run **#2742** / id `40314536046` / job `121543712876` / **attempt 1** / `workflow_dispatch`（2026-10-26T11:22:05Z → 11:47:15Z，25m10s）
- **结论 `success`**；`head_sha = 0c65e85f775c70b7361728d4bfbc076f5df9fae8` 与本地 `HEAD` **逐字符一致**
- 26 步：唯一 `skipped` = **step 23 Publish to Releases**（`ref == main` 门控），其余 25 步全 `success`
- 日志 `probe/ci/job-121543712876.log`，1,486,851 B / **NUL 0**，逐条判读：

| 读数 | 值 | 判据 |
|---|---|---|
| `Summary: 112 passed, 0 failed` | **恰 1** | 112 门全绿 |
| `ALL 344 CASES PASS` | **恰 1** | 344 条夹具（= 冻结面未动） |
| `^\s*e: ` | **0** | 无编译错误 |
| 门头 `━━━ [N/112] … ━━━` | **112 条，编号 1..112 连续，显示名 112 互异** | 无门被跳过/重复 |
| 含 `FAILED` 的行 | **6 条，全部以 `PASSED` 结尾**（用例名子串） | 无真失败 |
| `PROVIDER_UA_PARITY_SELFTEST=PASS` | **恰 1**（+ `provider_ua_parity_check selftest has teeth` 1 条 + `real tree green` 1 条） | 门 12 的 60 条臂在 CI 里真跑 |
| `log url guard: … (audited sites: 18)` | **恰 1**（`[19/112]` 头同现 1 次） | 门 11 真树绿 |
| `URL_REDACTION_SELFTEST` | **0** | 预期：门 11 在 `test_scan.py` 无注册项（§⑦），门自证 stdout 不进 CI 日志 —— 这是**既有缺口**，不是本次遗漏 |

```
✅ log url guard: no whole url value reaches a log — judged by value flow across
   all evaluation forms (interpolation / concatenation / brace-nested / aliased
   binding) (audited sites: 18)
   ✅ provider_ua_parity_check selftest has teeth (PROVIDER_UA_PARITY_SELFTEST=PASS)
```

## ⑥ 残留（P2，均带「触发条件 / 触发后方案 / 搁置理由」三件）

**R1 · 四处旧漏网只有「钉住」没有「修好」**
- 内容：`SkillRepository.kt:1457`（`$msg ← $downloadURL`，`msg` 随后被 `outcome.recordFailure` 也吃一份）、`:1501/:1511/:1515`（`$lastReason ← $apiURL`）、`PhotosOffloadHandler.kt:904`（`$uri`）。这四条是真泄漏（`apiURL`/`downloadURL` 常带 `?token=`/签名查询），只在 ALLOWED 里钉住。
- 触发条件：有人改这几行（或删 ALLOWED 条目）→ 门立刻重报；或下一轮安全审计再次点到 `SkillRepository` 的日志面。
- 触发后方案：把消息体改成 `LLMRequestLog.redactURL(...)` 或只打 `host + path.lastPathSegment`；`$uri` 改成 `uri.lastPathSegment`（MediaStore 只关心 id）。改完删掉对应 ALLOWED 条目。
- 搁置理由：本支 radius 明令不动生产 `.kt`；四条都在**既有**代码里，不是本轮引入，且行内已无遗漏线索（值流判据已把它们显式化）。

**R2 · 值流只跟「本地声明 / 语句级赋值」，不跟 helper 返回值 / 属性 setter / 命名实参**
- 触发条件：下一轮见证用 `val x = buildEndpoint(instance)`（helper 返回 URL）再改名打日志；或把 URL 塞进 `data class` 字段后 `log("${cfg.endpoint}"+"-ish")` 的变体。
- 触发后方案：`_follow_name` 增加一跳——目标是函数调用时用 `function_body` 取体、看 `return` 的表达式再递归（现有共享原语够用）。
- 搁置理由：本批三条见证都落在 1–2 跳内；扩到函数返回会把「同名 helper 在别的类里」这类假阳风险抬上来，值不值需要下一轮的真实见证来定价。

**R3 · `NOT_VALUE_PRESERVING` 是黑名单：未知换算调用按「保值」处理**
- 触发条件：真树出现新的 false positive（门红、人工判定无泄漏），例如 `Log.d(TAG, url.encodeUtf8().size)` 这类链式换算之外的形态；或反过来出现 `x.parseToId()` 之类**语义换算但名字不在名单里**的漏判。
- 触发后方案：改成「未知 callee → 报『需人工确认』」的 fail-closed 形态，或把名单换成「按返回类型判」（需要类型信息，超出门脚本能力 → 那就退回白名单 + 门内注释写明天花板）。
- 搁置理由：当前名单覆盖 Android/Kotlin 标准换算 90%+ 的写法；改成 fail-closed 会立刻在真树上产生大量需人工确认项，装置比例不合（P2）。

**R4 · 门 12 `request_carries_ua` 不证明 override 作用在**同一个实例**上**
- 触发条件：下一轮见证写成「同函数两个 builder，`a` 带 UA、`b` 不带，`a.build()` 在前、`newCall(b.build())` 在后」——当前判据看的是「本函数内该变量声明之后、build 之前有可达 override」，对 `name` 一一对应，但如果变量被重新赋值成另一个 builder，仍可能误绿。
- 触发后方案：用 `value_sources`/`_ident_values` 解析 receiver 身份，要求 override 的 receiver 与被 build/newCall 的 receiver 是**同一个绑定**（`is_single_assignment` + `last_write` 已能覆盖大部分）。
- 搁置理由：要先把「同名变量重赋值」的真实形态收集齐；现在扩会把 `builder` 在 `if/else` 两臂各自构造的合法写法判红。

**R5 · D4 的 `OPENAI_BUILDER_EXEMPT` 按源码片段钉住（`Request.Builder().url(urlStr).get().build()`）**
- 触发条件：该行被重构（换变量名/拆行）→ 片段不匹配 → 门立刻红（fail-closed 方向正确，但会误报为「漏 UA」）。
- 触发后方案：改成从「builder 的 URL 来源是不是 provider 响应体给的第三方地址」派生，或给它加行内 `ua-exempt: <理由>` 标记（与 `url-ok:` 同族）。
- 搁置理由：单点、改动时立刻可见、不会静默失效；现在加标记机制属于新装置。

**R6 · 门 11 `_tail_names`/`URLISH` 仍假设「值载体在名字里能被认出来」**
- 触发条件：URL 被放进一个完全中性命名的变量且**值路径上也没有 url 命名的环节**（例如 `val t = "https://gw/v1" + suffix` 再 `log("$t")`；纯字面量拼接只有 `_follow_name` 的 scheme 判据能抓，插值形态抓不到）。
- 触发后方案：把「纯字面量 + `://` + 无插值」的 scheme 判据扩到「字面量里出现 `://` 且变量后续被用于 `Request.Builder().url(` 站点」（反查消费点）。
- 搁置理由：需要在门内建立「变量→请求构建点」的索引，成本超出一条日志判据；且这类写法在本仓 0 命中。

**R7 · `enclosing_span` 只认块体函数（表达式体 `fun f() = …` 退化为整份文件）**
- 触发条件：有人把 `Request.Builder()` 写进表达式体函数或 `= withContext(…) { }` 的尾巴。
- 触发后方案：补 `expr_end` 分支（`function_body` 已能返回 kind，直接按 `expr` 走 `expr_end`）。
- 搁置理由：既有 4 个 builder 全在块体函数里；退化方向是「多看几个同名绑定」，只可能造成误红、不会静默放行。

## ⑦ 注册项（交主会话）

- `test_scan.py` 已有 `("provider_ua_parity_check", "PROVIDER_UA_PARITY_SELFTEST=PASS", (), (REPO_ROOT,))`，**无需变更**（收尾标记原文保留）。
- `log_url_redaction_guard` 在 `test_scan.py` **没有**注册项。门自测新增 11 条臂（含 6 条必须绿的 clean 对照），建议主会话收口时补一行：
  `("log_url_redaction_guard", "URL_REDACTION_SELFTEST=PASS", (), (REPO_ROOT,))`
  ——否则这 11 条臂在 CI 日志里没有见证（门自证 stdout 不进 CI 日志，既有缺口）。
- `credibility.md`：门 11 / 门 12 的第三轮攻击结果（6/6 BLIND → 6/6 抓）由主会话统一登记；本支不写。

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t12

# 修前反攻（在工作树上就地改 .kt、跑双门、还原）
python3 probe/t12_replay.py pre     # → probe/t12-pre.json（期望 6/6 BLIND_CONFIRMED）

# 门自测 + 真树
python3 scripts/scan/log_url_redaction_guard.py --selftest     # PASS (28 arms)
python3 scripts/scan/log_url_redaction_guard.py .              # rc=0
python3 scripts/scan/provider_ua_parity_check.py --selftest     # PASS (60 arms)
python3 scripts/scan/provider_ua_parity_check.py .             # rc=0

# 修后反攻（期望 6/6 FIXED，各红在自己那条判据上）
python3 probe/t12_replay.py post    # → probe/t12-post.json

# 全量（逐门独立进程；沙箱 Errno 38 时分段跑）
python3 probe/t12_gate_loop.py            # 全跑
python3 probe/t12_gate_loop.py 30 40      # 第 31..70 门
python3 probe/t12_gate_loop.py 70 40      # 第 71..109 门

# CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --workflow build-apk.yml --ref fix/r3-logredact-ua
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/r3-logredact-ua
```
