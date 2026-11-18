# FIX-2D-1119-5-effort — 学习结果的 endpoint 作用域与档位收窄

分支 `fix/effort-key-1119`，基线 `b82b2963`（= 当前 main），worktree
`/var/minis/shared/work/r1119/effort`。

## 1 范围

审计窗口 `2a8d746d`（11-16 收口点）→ `289b7b59`，本支只取报告
`reports/r2d1119/5_Provider_Model_Report.md` 的 **F-01 / F-02** 两条 P1。
F-03（Responses SSE 不 record）已在 `backlog.md §9830 E-C-03` 登记，按任务书不重开；
F-04/F-05/F-06 是 P2/P3，不碰。

改动文件（9 个，含 2 个测试）：

| 文件 | 性质 |
|---|---|
| `provider/thinking/EffortTierLearner.kt` | F-01 规范化 key + F-02 形状落盘与收窄入口 |
| `provider/thinking/ThinkingEffortLadder.kt` | F-02 纯函数 `learnedWithinDeclared` |
| `provider/thinking/GatewayEffortTruth.kt` | `resolveTier` 增加 `scope` 形参（默认 = host，保持旧行为） |
| `provider/thinking/ThinkingRuleResolver.kt` | `declaredEffortValues` 的 null 语义 KDoc 同步 |
| `provider/LLMProvider.kt` | `requestHost` → `requestScope` |
| `provider/openai/OpenAIProvider.kt` | 4 个调用点换 scope；两条线端路径改读 `learnedWithin` |
| `ui/chat/ChatViewModel.kt` | 选择器上限折叠改读 `requestScope` |
| `test/.../EffortScopeDeclaredSetTest.kt` | 新增，18 条 |
| `test/.../ThinkingEffortLadderCeilingTest.kt` | 形状断言随 `requestScope` 更新 |

## 2 先证（逐条）

### F-01 — key 只有 host，跨端口/跨路径前缀污染

**成立。** `key(host, model) = host.trimEnd('/') + "/" + model`（原
`EffortTierLearner.kt:347`），四个调用点传的全是 `HttpUrl.host`：

| 位置 | 传入 |
|---|---|
| `OpenAIProvider.kt:832`（HTTP 非 2xx，record） | `request.url.host` |
| `OpenAIProvider.kt:1343`（Chat SSE inline error，record） | `request.url.host` |
| `OpenAIProvider.kt:2831`（Chat 线端，learned） | `basePath.toHttpUrlOrNull()?.host` |
| `OpenAIProvider.kt:3217`（Responses 线端，learned） | `basePath.toHttpUrlOrNull()?.host` |

`okhttp3.HttpUrl.host` 是**主机名**，不含 port 也不含 path。于是
`127.0.0.1:8790/v1` 与 `127.0.0.1:8791/v1`（本机两个中转的实测形态）在模型 ID
相同时命中同一条目。UI 侧 `ChatViewModel.withLearnedCeiling` 通过
`provider.requestHost` 读同一个 key，因此**选择器上限也一起串**。

### F-02 — `ceilingFromText` 的连续前缀直接替代 `reasoningEffortValues`

**成立。** `ceilingFromText` 返回 `ThinkingEffortLadder.tiersBelow(rejected)`，即
`LADDER` 的连续前缀；两条线端路径都用 `learned ?: declared` 整表替换：

- `OpenAIProvider.kt:2831` → `ctx.declaredEffortValues`（`ThinkingRuleResolver` 用它 clamp）
- `OpenAIProvider.kt:3945` → `clampResponsesEffort` 的 `learnedEffortValues ?: declaredEffortValues`

模型声明 `["low","high"]` 时，一次 `THINKING_LEVEL_XHIGH` 拒绝会写出
`["none","minimal","low","medium","high"]`，此后 `clampEffort("medium", …)` 命中
`medium` 并**原样发出** —— 而这个模型从未声明 `medium`，即重新触发同一个 400。

### 一条在报告之外、但同族必须一起收口的：形状

修 F-02 时若一律取交集，会砍掉**另一条更重要的一手真相**：网关把合法枚举说全了
（`should be one of: low, high, xhigh`）时，它的集合**比 catalog 宽是常态**
（sensenova 一行 7 个模型全部少声明档位，这正是 `EffortTierLearner` 存在的理由）。
取交集等于把学到的答案又扔掉。因此判据不是「learned 是否该被收窄」，而是
**「这条 learned 是什么形状」**：

- `ENUM`（`enumFromText` 命中）→ 一手真相，**不收窄**；
- `CEILING`（`ceilingFromText` 命中）→ 是上限的近似，**必须被 catalog 收窄**。

形状随条目落盘（`"shape": "enum"|"ceiling"`）；升级前没有该字段的条目按 `ENUM`
处理（行为与修前逐字节相同），下一次 400 用带 shape 的新记录覆盖 —— 自愈，无迁移。

## 3 修法

### F-01

- 新增 `EffortTierLearner.scopeOf(raw)`：把 endpoint 规范化成
  `scheme://host[:port]/path-prefix`（scheme/host 小写、query 与 fragment 丢弃、
  路径尾斜杠裁掉、`https://` 后面无主机名返回 null）。**裸主机名原样返回**
  （裁尾斜杠 + 小写），所以 legacy 条目仍可被 host 口径的调用方读到。
- `key(scope, model)` 内部走 `scopeOf`，**写侧与读侧共用同一个规范化函数** ——
  两侧口径不一致会让学习结果静默失效。
- `record` / `learned` 的形参从 `host` 改名 `scope`（KDoc 写明口径）。
- `LLMProvider.requestHost` → `requestScope`；`OpenAIProvider` 用
  `private val effortScope get() = EffortTierLearner.scopeOf(basePath)`，
  `requestScope = effortScope ?: basePath.toHttpUrlOrNull()?.host`（解析失败退回裸 host，
  与 record 侧的回退**同一条**）。
- `GatewayEffortTruth.resolveTier` 增加第 5 个形参 `scope: String = host`：`host`
  继续选测量表（表是按 host 建的），`scope` 只用于读 learner。默认值保证
  `GatewayEffortTruthTest` 的 20 余条既有断言逐字节不变。

**迁移**：旧 key 是 `host/model`，新 key 是 `scheme://host[:port]/path/model`。
旧条目不再被命中 → 上限重新学一次（安全方向的错：少一个档位，不会炸请求）。
旧条目本身留在文件里，由 `write()` 的 3×TTL 清扫统一清掉；文件是 KB 级，不值得
为它写迁移逻辑。这条取舍有测试钉住（`a legacy host-keyed entry is still readable
through a host, not through a scope`）。

### F-02

- 新增纯函数 `ThinkingEffortLadder.learnedWithinDeclared(learned, declared)`：
  - `declared` 空 → `learned`（模型没声明，网关的话最大权重）；
  - `learned` 空 → `declared`（没学到，catalog 照旧）；
  - 否则取交集、按 `LADDER` 排序去重；
  - **交集为空** → 最弱的一个**声明**档位（只取一个，不取整个声明）。
    理由：这个函数存在的目的就是「不要把模型没声明的档位发出去」，回退到 learned
    会重新引入那个 400；取最弱一个则让请求尽量低，且 `clampEffort` 保持有定义。
    未知词在选最弱时排最后（`clampEffort` 本身忽略未知词，只返回未知词会让请求不 clamp）。
- 新增 `EffortTierLearner.learnedWithin(scope, modelId, declared)`：按形状分派 ——
  `CEILING` 走 `learnedWithinDeclared`，`ENUM` 原样返回，没学到返回 `declared`。
  这是 F-02 的**唯一落点**，两条线端路径都只调它，不再各自决定两表怎么合。
- `OpenAIProvider` 两处改为 `EffortTierLearner.learnedWithin(...)`；`clampResponsesEffort`
  保持 `learned ?: declared` 不动（形状位只有调用方持有，重复收窄要么是 no-op、
  要么会砍掉 ENUM 的一手真相），KDoc 写明为什么。

### 用户行为改变（任务书要求的一句话）

**F-01**：配两个同主机不同端口/路径前缀的 provider 时，A 端点学到的上限不再压低
B 端点的选择器与线端档位。**F-02**：网关只报「xhigh 不支持」时，重试不会再把模型
从未声明的中间档（如 `medium`）发出去 —— 用户看到的档位会落到声明范围内的最强合法档；
而网关把合法枚举说全时，选择器仍能看到 catalog 少声明的那些档（学习价值不退化）。

## 4 证据

### 装置

| 装置 | 覆盖 | 结果 |
|---|---|---|
| `shared/work/r1119/effort-jvm/run.sh` | `EffortTierLearnerTest`(23) + `ThinkingEffortLadderCeilingTest`(12) + `GatewayEffortTruthTest`(24) + `EffortScopeDeclaredSetTest`(18) + 3 个桩 | **OK (101 tests)** |
| `shared/work/r1119/effort-jvm/run-responses.sh` | 从生产文件**机械抽取** `clampResponsesEffort`（按声明行定位 + 配对收尾）编译，跑 `ResponsesEffortClampTest`(8) | **OK (8 tests)** |
| `shared/work/r1119/effort-jvm/run-wiring.sh` | 既有的 `MetaR3B08WiringTest`(11)（纯源码形状测试，可独立编译） | **OK (11 tests)** |

`run-responses.sh` 特意不是手抄副本：手抄副本会验证一份可能与生产脱节的旧代码。

### 新增用例（18 条，全部字面量期望）

F-01 / scope：
`the scope keeps the scheme host port and path prefix` ·
`the scope is canonicalized - lowercase host, no trailing slash, no query` ·
`a bare host keeps its legacy scope` · `a string that is not an endpoint has no scope` ·
`two ports of one host are two scopes` · `two path prefixes of one host are two scopes` ·
`the stored key is the normalized endpoint, not the raw argument` ·
`an entry learned on one port is not read from another port` ·
`an entry learned on one path prefix is not read from another prefix` ·
`recording a raw base URL and reading the normalized scope agree` ·
`a legacy host-keyed entry is still readable through a host, not through a scope`

F-02 / 形状：
`a ceiling-shaped answer is narrowed by the declaration` ·
`an enumerated answer is NOT narrowed - it is wider than the catalog on purpose` ·
`nothing learned returns the declaration untouched` ·
`an entry written before the shape field existed is treated as an enum` ·
`a later enum replaces a learned ceiling and stops being narrowed` ·
`the shape survives a restart` · `the wire value for an undeclared request degrades
downward, not upward`（端到端：真实 `record` → `learnedWithin` → `clampEffort`）

纯函数 `learnedWithinDeclared`：`a declared set narrows the learned ladder prefix` ·
`a tier the model never declared stays off the wire` · `with nothing declared …` ·
`with nothing learned …` · `a contradiction degrades to the weakest declared tier` ·
`a declaration naming an off-ladder word never wins the weakest pick` ·
`the narrowed set is ordered by the ladder regardless of the input order` ·
`a declaration that repeats a tier does not duplicate it`

接线形状（注释先剥离，KDoc 提不能满足）：
`both wire paths read the learner through the shape-aware narrowing` ·
`the learner is read through the endpoint scope on every call site` ·
`no production file still keys the learner by a bare host`

`ThinkingEffortLadderCeilingTest` 里原有的
`the learned scope host comes from the provider, not from customBaseURL` 改为
`…scope endpoint…`，断言换成 `requestScope` / `EffortTierLearner.scopeOf(basePath)`。
**这是唯一一处既有测试被改**：它断言的正是被本支替换掉的 `requestHost` 拼写，
属于「该测试断言的是旧模型」，报告在此显式说明。

### 门（单门自测，未跑全量）

| 门 | 结果 |
|---|---|
| `thinking_wiring_guard.py .` | `✅ thinking-level wiring OK (1 call site(s) checked; 2 measured-gateway effort family/families pinned)` rc=0 |
| `thinking_wiring_guard.py --selftest` | `THINKING_EFFORT_SELFTEST=PASS (25 fixtures)` rc=0 |
| `test_scan.py --single test_thinking_wiring` | 7 条全绿 `@@TEST_RESULT@@ test_thinking_wiring 7 0` |
| `anthropic_thinking_budget_check.py .` | `GATE=GREEN` (8 criteria) |
| `provider_boundary_guard.py .` | rc=0（只有 3 条既有 info 行） |
| `tool_result_image_branch_check` / `truncated_call_guard_check` / `log_url_redaction_guard` / `model_defaults_capability_source_check` / `provider_ua_parity_check` / `models_provider_field_check` / `debug_leak_guard` / `discarded_return_guard` / `modeluse_proto_single_source_check` / `session_provider_enabled_check` | 全部 rc=0（引用 `OpenAIProvider` 的门逐条跑过） |

`scripts/scan/**` 未改动。

### CI

run **#3299 / `44377006462`**，`head_sha = a4b11945b1650c2847c8318dafe817905fc7256e`
（= 本地 HEAD = `origin/fix/effort-key-1119`，逐字符一致）：

| job | 结论 |
|---|---|
| lint | success |
| build-apk | success |
| unit-tests | success |
| scan-gate | success |
| publish | success（两步 `skipped`：`Move the rolling tag android-latest` / `Publish to Releases` —— main 门控，分支构建不产 release） |

- unit-tests：**9779 条 PASSED / 0 条 ` FAILED` 行**（第一版 CI 那次是 9778 PASSED + 1 FAILED，
  见下）。本支新增/改动的 13 条逐条 `grep -cF` = 1 命中。
- scan-gate：`Summary: 115 passed, 0 failed`。日志有
  `scripts/scan/** untouched in this push — skipping the fixture self-test`，
  说明夹具按机制跳过（本支未动 `scripts/scan/**`，按任务书也不该动）——**因此这次
  绿不含夹具证据**，夹具证据来自本地 `test_scan.py --single test_thinking_wiring`。

#### 第一次 CI 红（真错，已修）

run #3297 / `44374321955` @ `9b0d43b0`：`unit-tests` 失败，唯一红是
`MetaR3B08WiringTest > F-08 the Responses builder resolves its effort through the shared clamp`
（断言 `"EffortTierLearner.learned(" in code.substring(...)`）。

**它不是实现错，是既有形状测试锚在拼写上**：它守的决策是「Responses 路径要读学到的
档位集合，而不是只有 family clamp」——本支仍然满足（现在是 `learnedWithin`，正是
F-02 修法）。锚点改成
`Regex("EffortTierLearner\\.learned(Within)?\\(")`，KDoc 里写明为什么更宽的名字才是
正确锚点（裸 `learned(` 现在恰恰是 F-02 回归）。收窄本身由
`EffortScopeDeclaredSetTest` 钉住。

**该锚有牙（本地变异验证）**：把 Responses 路径的 `learnedWithin(...)` 整段删成
`learnedEffortValues = null`，`MetaR3B08WiringTest` 立刻红（11 tests, 1 failure）；
恢复后 11 条全绿。

**这是本支唯一一处改动既有测试的地方（共两处，另一处见 §4「新增用例」末尾）**，
两处都断言的是被本支替换掉的旧拼写，决策本身未被削弱。

## 5 变异负控

装置 `shared/work/r1119/effort-jvm/mutate.py`：快照 + `finally` 还原 + sha256 校验
（不用 `git checkout --`，那曾把变异烧进提交）。每条臂只允许红对应集合。

| 臂 | 变异 | 结果 |
|---|---|---|
| M1 | `key()` 不规范化（退回 host-only，即 F-01 原状） | RED-PRECISE (2) |
| M2 | `scopeOf` 丢掉 path 前缀（只留 scheme+host+port） | RED-PRECISE (6) |
| M3 | `learnedWithin` 对所有形状都收窄（砍掉 ENUM 一手真相） | RED-PRECISE (3) |
| M4 | `learnedWithin` 从不收窄（F-02 未修） | RED-PRECISE (4) |
| M5 | `write()` 不落 `shape` 字段 | RED-PRECISE (4) |
| M8 | Responses 路径退回裸 `learned` | RED-PRECISE (1) |
| M9 | `resolveTier` 退回 host 默认（测量主机读不到自己写的东西） | RED-PRECISE (1) |
| M6 | 交集为空时回退整个声明 | RED-PRECISE (2) |
| M7 | 交集退化为「直接用 learned」（功能缺失） | RED-PRECISE (10) |

`BASELINE: OK` / `BASELINE-AFTER-RESTORE: OK` / `arms: 9, failures: 0`。

**M1 第一次跑是 BLIND**（只红 1 条，而期望 3 条）：那两条「不同端口 / 不同前缀不串」
的用例在 host-only key 下**仍然通过** —— 写和读用同一个原始串，两边一致。这是装置
给出的真缺口，不是测试写错：我补了
`the stored key is the normalized endpoint, not the raw argument`（直接断言
`learnedKeys()` 的落盘形态），并把 M1 的期望收缩到它 + `recording a raw base URL …`。
**「两条不同的 scope 不串」只能证明隔离，不能证明规范化**——同族以后再写这类断言要注意。

## 6 未修与取舍

1. **F-03（Responses SSE `error` / `response.failed` 不 record）**：`backlog.md §9830
   E-C-03` 已登记，按任务书不重开。注意它与本支相邻：本支让「学到的上限」被正确
   消费，F-03 是「这条上限在 Responses 流里学不到」。两者都修完，Responses 路径才算闭环。
2. **旧 key 迁移不做**（见 §3 F-01）。学习结果会重新学；条目由 3×TTL 清扫。已在测试里
   钉住「旧条目仍可被 host 口径读到、不会被 scope 口径误读」。
3. **`ChatViewModel.withLearnedCeiling` 不做形状收窄**：它只下调**选择器上限**
   （`lowerCeiling` 取最高档），不产生 wire 值，因此不存在「发出未声明档位」的问题；
   对 CEILING 形状它会显示得比实际可发档位略宽一档（选择器仍列出 `medium`，线端会 clamp
   到 `low`）。要彻底一致需要把形状也传进 UI，属于新的一层状态同步，超出本支范围，
   在此登记。
4. **`EffortTierLearner.learned()` 保留**：诊断日志与 `GatewayEffortTruth.resolveTier`
   的默认口径仍用它（host 口径）。它不再是线端的来源，但删掉会让
   `thinking_wiring_guard` 的 precedence 断言失去一个臂，且 `learnedKeys()` 诊断需要它。
5. **`resolveTier` 的 `scope` 是可选形参**：默认 = `host`。生产只有一处调用且显式传了
   `scope`（M9 臂钉住）。保持默认值是为了不动 `GatewayEffortTruthTest` 的 24 条既有断言
   —— 它们测的是测量表，与 learner 的 key 无关。

## 7 文件清单

```
src/android/app/src/main/java/com/rikkaminis/app/provider/LLMProvider.kt                    |  17 +-
src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt          |  67 +-
src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/EffortTierLearner.kt     | 141 +-
src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/GatewayEffortTruth.kt    |  10 +-
src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/ThinkingEffortLadder.kt  |  49 +-
src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/ThinkingRuleResolver.kt  |   9 +-
src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt                   |  15 +-
src/android/app/src/test/java/.../provider/MetaR3B08WiringTest.kt                          |  12 +-
src/android/app/src/test/java/.../provider/thinking/EffortScopeDeclaredSetTest.kt          | 505 + (新)
src/android/app/src/test/java/.../provider/thinking/ThinkingEffortLadderCeilingTest.kt     |  27 +-
fix-reports/FIX-2D-1119-5-effort.md                                                        | (新)
```

未触碰：`scripts/scan/**`、任何 `strings.xml`、其他支的域。

## 8 复现

```sh
# 单元测试（三个装置，各一个 JVM）
sh /var/minis/shared/work/r1119/effort-jvm/run.sh          # OK (101 tests)
sh /var/minis/shared/work/r1119/effort-jvm/run-responses.sh # OK (8 tests)
sh /var/minis/shared/work/r1119/effort-jvm/run-wiring.sh    # OK (11 tests)

# 变异负控（9 臂；基线同时校验上面两个装置）
nice -n 10 python3 /var/minis/shared/work/r1119/effort-jvm/mutate.py

# 单门（勿跑全量）
cd /var/minis/shared/work/r1119/effort
python3 scripts/scan/thinking_wiring_guard.py .
python3 scripts/scan/thinking_wiring_guard.py --selftest
python3 scripts/scan/test_scan.py --single test_thinking_wiring
```

手工复现 F-01（真机或 JVM）：配两个 `http://127.0.0.1:8790/v1` 与
`http://127.0.0.1:8791/v1` 的 provider，让 A 端点对同一模型回
`Thinking level is unsupported: THINKING_LEVEL_XHIGH`，然后看
`filesDir/learned/gateway_efforts.json` —— 修前只有一个
`"127.0.0.1/<model>"` 条目，修后是
`"http://127.0.0.1:8790/v1/<model>"`，且 B 端点的选择器上限不变。
