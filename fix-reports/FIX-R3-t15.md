# FIX-R3-t15 — anthropic_thinking_budget_check / thinking_wiring_guard

支线 `fix/r3-thinking-wiring`（worktree `round3b/wt/t15`），基线 `main` @ `f0f14d46d`。
第三轮（dual-gate bypass）收口批。**只改本任务书点名的两个门脚本 + 本报告**。

## 1. 门

| 门 | 判据 | 见证 |
|---|---|---|
| `scripts/scan/anthropic_thinking_budget_check.py` | C1/C2/C3（Anthropic 侧）+ C-OAI-1/C-OAI-2（跨文件 pair 契约）+ **C-OAI-3 / C4 / C5（本波新增）** = 8 条 | #0 #1 #2 |
| `scripts/scan/thinking_wiring_guard.py` | 判据 1-7（picker 数据流 + measured gateway 表）+ **判据 8-11（本波新增）** | #51 #52 #53 #60 |

改动面：`git diff --stat` = **2 文件 +912 / −95**，两边都是 `scripts/scan/*.py`。
`src/**` 生产 `.kt` **0 行**、`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` /
`_kotlin_valueflow.py` 零触碰；门数 112 / `test_scan` 夹具 344 不变。

## 2. 轴

七条见证全部落在**跨门缝**上（第三轮主攻面），共同形态是「两门各自守的锚都还在，但缝里的
决定没人付钱」：

| witness | 轴 | 缝的形态 |
|---|---|---|
| #0 | dual-gate | C-OAI-1 锚「wire max_tokens == injectThinkingParams 第 3 实参」；注入器**拿到之后怎么用**没人管 |
| #1 | symmetric | C1/C2 警 caller；helper 本体（`thinkingBudget`）没人管 |
| #2 | legacy-axis | budget 与 temperature 是同一契约的两半，只警了一半 |
| #51 | dual-gate | 判据 4-5 钉「measured 分支里出现 resolveTier(」；**结果有没有被写到线上**没人管 |
| #52 | dual-gate | AUTO 卫语句存在即绿；**极性**没人判 |
| #53 | symmetric | KDoc 写明 learned > measured > declared > fallback；**顺序**没人判 |
| #60 | dual-gate | 规则引擎调用存在即绿；**可达性**没人判 |

即「存在 → 效果」「拼写 → 值流」「有这一行 → 这一行决定的事真的发生」，与前三批同源。

## 3. 见证重放表

探针 `probe/t15_replay.py pre|post`（每条就地构造最小编辑 → 跑 `gates_involved` 全部门 →
记录 rc → `git checkout` 还原 → sha256 比对）。原始读数 `probe/t15-pre.json` /
`probe/t15-post.json`，逐条补丁 `probe/t15-w{0,1,2,51,52,53,60}.patch`。

| # | 轴 | 目标文件 | 涉门 | pre | post | 红在哪条判据 |
|---|---|---|---|---|---|---|
| 0 | dual-gate | OpenAIProvider.kt | anthropic_thinking_budget + provider_ua_parity | BLIND（0/0） | **CAUGHT**（1/0） | C-OAI-3「forwards `65536` as the budget ceiling, but the wire max_tokens comes from its 3rd argument `maxTokens`」 |
| 1 | symmetric | AnthropicProvider.kt | anthropic_thinking_budget | BLIND（0） | **CAUGHT**（1） | C4「returns `cap` without clamping it below its own `maxTokens`」 |
| 2 | legacy-axis | AnthropicProvider.kt | anthropic_thinking_budget | BLIND（0） | **CAUGHT**（1） | C5「`temperature` is written as `0.2` next to `budget_tokens`」 |
| 51 | dual-gate | OpenAIProvider.kt | thinking_wiring + provider_ua_parity | BLIND（0/0） | **CAUGHT**（1/0） | 判据 8「puts reasoning_effort=`"medium"` … instead of the …resolveTier(...) result」 |
| 52 | dual-gate | OpenAIProvider.kt | thinking_wiring + log_url_redaction | BLIND（0/0） | **CAUGHT**（1/0） | 判据 9「exits on `level != ThinkingLevel.AUTO` — the polarity is inverted」 |
| 53 | symmetric | GatewayEffortTruth.kt | thinking_wiring | BLIND（0） | **CAUGHT**（1） | 判据 11「precedence must be learned < measured < … but the chain ranks [2, 0, 1, 3]」 |
| 60 | dual-gate | OpenAIProvider.kt | thinking_wiring + log_url_redaction | BLIND（0/0） | **CAUGHT**（1/0） | 判据 10「exits with `return echo` before it reaches ThinkingRuleResolver」 |

合计：**7/7 BLIND_CONFIRMED → 7/7 CAUGHT（rc=1 exact）**，restore 7/7 True，事后
`git status src/` 为空。**每条只红自己那一条判据**（无连带、无误伤），副门
`provider_ua_parity` / `log_url_redaction` 全程 rc=0 —— 它们锚 UA 头与 URL 脱敏面，
与 thinking 面不相交（跨批缝的正解是「另一门也看不见」，不是「另一门也该红」）。

## 4. 修法

### 4.1 anthropic_thinking_budget_check.py（+3 判据，全走共享原语）

- **C-OAI-3**：`function_body` 取 `injectThinkingParams` 本体 → `call_args` 取
  `ThinkingResolveContext(...)` 具名实参 → `maxTokens =` 的 RHS 必为**第 3 形参**
  （`value_sources` 洗值）；字面量 / `.let { 字面量 }` 洗白 / 同名重绑定全红。
  重绑定扫描走 **code_view（`mask_code`）**：注入器日志里 `"… maxTokens=$maxTokens"`
  在 literal 视图下会被读成赋值（本波自伤，已由 clean 臂 `FWD_GREEN` 钉住）；
  命名实参（前导 `,`）不算赋值。无 ctx 构造 / 无 `maxTokens` 字段 / 形参不足 3 个 → fail-closed。
- **C4**：`stmt_spans` 逐个 return，值必须**可证 ≤ 自身 max 实参且 <**：接受顶层
  `minOf/min/coerceAtMost/coerceIn(…, maxTokens)`、`if (…maxTokens…) 双分支有界 else`、
  单赋值绑定递归（`val clamped = minOf(...); return clamped`）。
  **clamp 必须位于表达式顶层**（`_depth_at == 0`）—— `when` 某个臂里的 clamp 只封一个臂，
  正是见证同族陷阱；`return maxTokens`（非法等对）也红。
- **C5**：定位 `budget_tokens` 写点所在**最内层花括号 if 块**，块内每条
  `put("temperature", v)` 必须 `const_value(v) == ("num", 1.0)`；没写温度 = API 默认 1（绿）；
  写在块外不涉此契约（绿）。无写点 fail-closed。

### 4.2 thinking_wiring_guard.py（共享层 adoption + 4 判据）

**adoption（硬约束 #1）**：门内自写词法器/扫描器全删 ——
`strip_comments` → `mask_comments`（**长度保持**，偏移即真文件行号，`raw_call_span`
不再需要第二遍括号走查）；新增 `code_view = mask_code`；
`initializer_text` → 共享 `expr_end`；`split_top_level_args` → `split_top`；
`find_call_args` / `raw_call_span` → `match_bracket`；`find_function_body` → `function_body`
（表达式体不再吞后续声明）。**换实现后原 15 条 wide3 臂全绿**，判据 1-7 行为零变化。

新增判据（全部挂在 `check_measured_gateway_effort` 内，共用 `thinking-effort-ok` 豁免）：

- **8**：含 `isMeasuredHost(` 的函数内，`reasoning_effort` 的**两种写法**
  （`put("reasoning_effort", v)` 与 `["reasoning_effort"] = v`）的值必须经
  `value_sources` 解析出**表达式自身就是** `GatewayEffortTruth.resolveTier(...)`
  （**锚在表达式头**：`"medium".also { resolveTier(…) }` 的诱饵提及不绿，本波专门补了臂），
  或经单赋值绑定中转；`!x.isEnabled` 守卫下的常量 off-tier 属 OFF 契约不红；
  分支内一条活写都没有也红。
- **9**：`ThinkingLevel.AUTO` 的 if 卫语句若 then 分支 return/throw，比较必须是 `==`
  （`!=` 与 `!(...)` 前置都算极性反转）。
- **10**：`ThinkingRuleResolver.apply(` 之前的**顶层**语句若为无条件 return/throw → 红；
  条件包裹（`if (...) { apply }`）不红。
- **11**：`resolveTier` 的 `?:` 链逐段排名 learned(0) < measured(1) < declared(2) <
  fallback(3)，非严格递增即红；未知来源 fail-closed。

### 4.3 自测装置

| 门 | 收尾标记 | 本波前 → 后 |
|---|---|---|
| anthropic | `ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS (48 arms)` | 29 → **48** 臂（+19） |
| wiring | `THINKING_EFFORT_SELFTEST=PASS (25 fixtures)` | 15 → **25** fixtures（+10） |

anthropic 侧每臂断言「期望红/绿」，另加 16 条「夹具 replace 真的生效」守卫
（漏改 = FAIL，不静默变 no-op）；wiring 侧新臂的 `want` 文本各自指向**唯一**新判据
（8 条 violation 文案 / 9 极性 / 10 可达性 / 11 排名），另有 3 条 precision 臂
（`tier` 绑定中转、`if` 条件包裹、`EffortTierLearner.learned` 提前）与 1 条诱饵臂。

## 5. 四层证据

| 层 | 证据 | 结果 |
|---|---|---|
| **L1 本门** | `--selftest` × 2 + 真树 | 48 arms PASS / 25 fixtures PASS；`GATE=GREEN anthropic_thinking_budget (8 criteria)`；`✅ thinking-level wiring OK (1 call site; 2 effort families)` |
| **L2 反攻** | `probe/t15_replay.py post` | **7/7 CAUGHT（rc=1 exact）**，restore 7/7，`git status src/` 空 |
| **L3 邻门** | 同扫三生产文件的 6 门 | `debug_leak_guard` / `log_url_redaction_guard` / `provider_ua_parity_check` / `tool_result_image_branch_check` / `truncated_call_guard_check` / `provider_remember_key_check` **全 rc=0** |
| **L3b test_scan 相关** | `test_thinking_wiring()` 7 检查 + `test_da1046_gates()` 4 检查 | 11/11 ✅（含 anthropic selftest 带牙 + 真树绿） |
| **L4 全量门线** | 逐门独立进程（`probe/t15_gate_loop.py`，结果 `probe/t15-gates.json`） | 跑到 **42/112 全绿**后撞 **`OSError: [Errno 38] Function not implemented`**（沙箱缺陷 S28，13 路并行窗口同族复发：`subprocess.wait` 与 `/bin/sh` 同时 ENOSYS）⇒ 按健康路径**全量读数交 CI 承担**，不拿「门红」污名 |
| **L4 CI** | 见 §6 | 分支 CI 绿 = 停止节点 |

## 6. CI

见文末回填（推送后填写 run id / head_sha / 关键日志行）。

## 7. 残留（P2，登记不修）

| id | 内容 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | C-OAI-3 只认 `ThinkingResolveContext(` **构造点**，经工厂函数间接构造不覆盖 | 生产改用工厂/包装函数构造 ctx | 工厂函数体代入展开后再判 maxTokens 字段 | 当前唯一构造点就是注入器本体；提前建工厂支持 = 为不可达路径造装置 |
| R2 | C4 的 `if (cond) <有界> else <cap>` 若 cond 已排除越界区间（`if (cap >= max) max-1 else cap`）会被判红（fail-closed 方向） | helper 被重构成该形态 | 给 else 分支补「条件已排除越界」的可行性判定 | 真实形态是 `minOf` + 三元；为一种未出现的写法放宽会削弱见证臂 |
| R3 | 判据 8 的 `["reasoning_effort"] =` 索引写法只认字面 key | 出现常量 key（`KEY` 常量引用） | key 常量折叠后再匹配 | 生产两处写点都是字面 key |
| R4 | 判据 11 的排名是**形态锚**（`EffortTierLearner.learned` / `tiersFor(` / `declaredTiers` / `_FALLBACK_TIERS`），重命名会 fail-closed 红 | 上述任一符号改名 | 按红改锚（fail-closed 方向安全，不会静默漏） | 名字本身就是契约的一部分（KDoc 已写明四级来源） |
| R5 | C5 的温度值必须是**编译期常量 1**；`const isThinkingTemperature = 1f` 之类会被判红 | 温度被提成常量 | `const_value` 前先做同文件常量折叠 | 当前是字面 `1`；fail-closed 方向 |
| R6 | 判据 8/9/10 只扫 `OpenAIProvider.kt` 一个文件（`PROVIDER_REL`） | 注入逻辑被抽到第二个 provider 文件 | 按 `isMeasuredHost(` / `ThinkingRuleResolver.` 在全仓派生文件集 | 本波见证全部落在这一个文件；扩面属新判据 |
| R7 | 沙箱 S28（ENOSYS）使本地全量门线在 42/112 处中断 | 再次需要本地全量读数 | 错开/串行/更小批量（见 sandbox-limits S28 健康路径），否则以 CI 日志为准 | 环境层缺陷，与本门判据无关；CI 侧 112/0 是权威读数 |

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t15

# 0) 基线：两门真树绿
python3 scripts/scan/anthropic_thinking_budget_check.py .
python3 scripts/scan/thinking_wiring_guard.py .

# 1) 自测（带牙）
python3 scripts/scan/anthropic_thinking_budget_check.py --selftest   # ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS (48 arms)
python3 scripts/scan/thinking_wiring_guard.py --selftest             # THINKING_EFFORT_SELFTEST=PASS (25 fixtures)

# 2) 见证重放（pre = 修前树 / post = 本提交树）
#    每条：应用 probe/t15-w<k>.patch → 跑涉门 → 记 rc → git checkout 还原 → sha256 校验
python3 probe/t15_replay.py pre      # 基线树上 7/7 BLIND_CONFIRMED
python3 probe/t15_replay.py post     # 本树上 7/7 CAUGHT（rc=1 exact），restore 7/7
python3 -c "import json;print([(r['arm'],r['verdict'],r['rc']) for r in json.load(open('probe/t15-post.json'))])"

# 3) 邻门
for g in debug_leak_guard log_url_redaction_guard provider_ua_parity_check \
         tool_result_image_branch_check truncated_call_guard_check provider_remember_key_check; do
  python3 scripts/scan/$g.py . >/dev/null || echo "NEIGHBOUR RED: $g"
done

# 4) 全量逐门（沙箱可行时的健康路径；中断按 sandbox-limits S28 处理，读数以 CI 为准）
python3 -u probe/t15_gate_loop.py 0 42
```

单条见证手工复现（以 #0 为例）：`git apply probe/t15-w0.patch &&
python3 scripts/scan/anthropic_thinking_budget_check.py . ; git checkout -- src/`。
