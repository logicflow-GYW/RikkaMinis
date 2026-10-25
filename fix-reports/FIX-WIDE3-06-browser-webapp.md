# FIX-WIDE3-06 — 浏览器 / webapp / KaTeX 四门见证加固

- **基线**：main `6b6c918f`（width-1024 第二波并集后的 main）
- **分支**：`fix/wide3-06-browser-webapp`
- **门**：`browser_tabs_lifecycle_check` · `webapp_asset_url_cleanup_check` ·
  `browser_cookie_ui_refresh_check` · `katex_bridge_arity_check`
- **生产代码改动**：**0 行**（只改判据 + 自证臂）
- **冻结面**：`scripts/scan/scan.sh` / `scripts/scan/test_scan.py` 零触碰
- **门数 / 夹具数**：112 / 344 不变（无新建门）

## 1. 结论

外部对抗审计给出 14 条「能过门但把缺陷放回来」的最小补丁（4 门 × 3~4 条），
**修复前 14/14 全部绿**（门瞎）。判据加固后：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 见证反攻（原补丁逐条重打） | 0/14 CAUGHT | **14/14 CAUGHT** |
| 四门 `--selftest` 负向臂 | 36 臂 | **49 臂**（+13，每见证至少一臂） |
| 真树（`6b6c918f`）四门 rc | 0 | **0**（无翻转） |
| 全量门 | — | **112 passed / 0 failed** |
| 冻结夹具 | — | **ALL 344 CASES PASS** |

失效轴分布（按审计方口径）：**dataflow 6 · parser-death 4 · scope 1 · order 2 ·
completeness 1**。四门原有的失效形态高度一致：判据锚「某个 token / 某个子串 /
某个名字出现过」，而不是锚「那个决定真的被执行了」。

## 2. 环境与装置

- 工作树 `wt-06`（`git worktree`），全程 `nice -n 10`，重活走
  `/var/minis/shared/tools/gate_run.sh`（全局 flock + ENOSYS 自动重试）。
- 见证反攻器 `/var/minis/shared/work/meta-wide3/reattack.py`
  （`--repo` + `--gates`，逐条 apply → 跑门 → restore → sha256 比对）。
- 本波 12 个支并行，沙箱 syscall 层出现 ENOSYS 风暴（S28）：
  `--full` 中途被冲掉一次、`test -f` 对存在文件返回假、`grep: Function not implemented`。
  处置 = 降并发重试（见 §4 L4 记录），**不把沙箱症状记成门红**。

## 3. 逐门改动

### 3.1 `browser_tabs_lifecycle_check.py`

| 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|
| C5 | dataflow | 只要求 `challengeNoticeLine(` 之后出现 `\btext\s*=`：`val text = stamped.text` 满足它，渲染出的风险提示被绑定后丢弃 | 解析渲染结果的**绑定名**（`val <notice> = …`，解析不出即 fail-closed），再要求其后某个 `text =` 的**右侧表达式**引用该名字 |
| C9 | dataflow | 只在 `publishAgentBusy` 体里找 `"_tabs.value.any { it.inUse }"` 子串 | 解析 `_agentBusy.value =` 的右侧表达式，要求它含该派生式**且**不被字面量短路（`_cond_dead_text`） |
| C4f | parser-death | `slice_body` 用 `src.find("{", idx)` 找函数体：签名里的**默认 lambda 参数** `onDone: () -> Unit = { if (isDisposed) return }` 成了「函数体」，真正体从未被检查 | 体起点从**参数列表右括号**派生；表达式体按 `_expr_end`（深度 0 换行 + 续行符规则）收尾；解析失败返回 `''`（调用方按违规处理） |
| C11 | dataflow/order | `save.find("if (isContextOnlySession) return")` 只证明子串与位置 | 先算 `_dead_spans`（`if (false …)` / `while (false)` 的体），守卫落在死块内即不计；`_c4_guard_pos` 同步加了这条 |

新增自证臂 4 条（C4f 默认 lambda、C5 绑定后丢弃、C9 常量短路、C11 死块守卫）。

### 3.2 `webapp_asset_url_cleanup_check.py`

| 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|
| arm13 | dataflow | `if (...)` 括号里含状态名就认守卫 | 条件必须**有效**（非 `false &&` / `true \|\|`），且该 `if` 之后是 `return` / `return@` / 带 return 的块——「守卫存在」升级为「守卫能拦住第二次提交」 |
| arm14 | dataflow | 同上（`pinShouldCommit` 关闭闩） | 同上，作用于关闭闩条件 |
| arm9 | dataflow | 只查 `produceState` 体内**出现过** `Dispatchers.IO` | 解码调用必须**落在** `withContext(Dispatchers.IO) { … }` 块内（复用 `enclosing_io_block`），把「IO 跳板存在但解码被提到外面」判红 |
| arm10 | parser-death | `fun_spans` 用 `find("\n    private fun ")` 切表达式体：`= null` 后面紧跟 `internal fun` 时，整个兄弟函数被吞进 `resolveMount` 的体里 | `_expr_end` 按表达式自身边界收尾（深度 0 换行 + 续行符），兄弟声明不再借出调用 |

新增自证臂 4 条。

### 3.3 `browser_cookie_ui_refresh_check.py`

| 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|
| F4 | parser-death + scope | ①注释剥离只认 `//`，`/* cm.flush() … */` 仍被计入；②`bumps >= 2` 是**全文件计数**，兄弟站点的 bump 顶替了被注释掉的那一个 | ①`strip_line_comments` 委派给字符串感知的**块注释**掩码器；②新增判据 4b：`setCookie(` 所在的**逐域删除动作块**（最小包含 `candidateUrls` 的花括号块）内部必须同时有 live 的 `flush()` 与 `cookieRefreshTick++`，解析不出块即 fail-closed |
| V4 | dataflow | `block.index(RESET_CALL)` 只证明子串与顺序：`if (false) tabPool.resetSessionViewport()` 满足它 | `_live_pos` 跳过 `_dead_spans` 内的命中；清会话覆盖必须真的执行 |
| V8 | order/dataflow | `guard < write` 只证明相对位置：`if (customUA != null) Unit` + 无条件写完全满足它 | `_inside_guard_body`：写必须落在守卫的**块体**（无花括号时=该行余下部分）内 |

新增自证臂 3 条（`compliant`/`good_delete` 夹具同步改成「动作块」形态，与生产同形）。

### 3.4 `katex_bridge_arity_check.py`

| 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|
| C2-scope | scope | `declared_arities` 在**整个 .kt 文件**里收集声明：真正注册的对象只声明 3 参，死嵌套类 `DeadBridge` 里的 4 参声明满足契约 | 声明按**注册点绑定**：解析 `addJavascriptInterface(obj, "Name")` 的首实参（内联 `object { }` / `val x = object { }` / 类型构造 `JsBridge { }`），只认该对象**成员层**（花括号深度 1）的 `@JavascriptInterface`；解析不出 → ROT 红（不是跳过） |
| C2-dead | dataflow | 只比 arity：方法体 `if (true) return` 仍然「匹配」 | 方法体必须可达：`if (true) …` 或裸前导 `return` 即判违规（空体 `{ }` 仍绿——`test_scan` 冻结夹具要求） |
| C2-parser | parser-death | `strip_comments` 三条正则、非字符串感知：`var _open = "/*"` 会开一段「注释」吃掉真正的 4 参调用点，`catch` 里的第二处调用让 `calls_found > 0` 的 rot 守卫继续满足 | 引号感知的逐字符扫描器；无法配平的调用点 / 注册点一律 ROT 红（fail-closed），不再静默跳过 |

新增自证臂 5 条，并给该门**首次装上 `--selftest`**（`KATEX_BRIDGE_ARITY_SELFTEST=PASS`）。

## 4. 证据分层

**L1 门本身**：四门在真树 `6b6c918f` 上 `rc=0`；四门 `--selftest` 全绿，
新增臂逐条「恰好红在该臂上」（每臂断言 needle + 期望 rc）。

**L2 外部见证反攻**（最硬证据，独立于 CI 与自证臂）：
`reattack.py` 把审计方给的每个补丁**逐字**打回真树 → 跑门 → 期望 `rc≠0` → 恢复并比对 sha256。

| 门 | 见证 | 结果 | 轴 |
|---|---|---|---|
| browser_tabs_lifecycle | C5 | CAUGHT rc=1 | dataflow |
| browser_tabs_lifecycle | C9 | CAUGHT rc=1 | dataflow |
| browser_tabs_lifecycle | C4f | CAUGHT rc=1 | parser-death |
| browser_tabs_lifecycle | C11 | CAUGHT rc=1 | dataflow |
| webapp_asset_url_cleanup | arm13 | CAUGHT rc=1 | dataflow |
| webapp_asset_url_cleanup | arm14 | CAUGHT rc=1 | dataflow |
| webapp_asset_url_cleanup | arm9 | CAUGHT rc=1 | dataflow |
| webapp_asset_url_cleanup | arm10 | CAUGHT rc=1 | parser-death |
| browser_cookie_ui_refresh | F4 | CAUGHT rc=1 | parser-death |
| browser_cookie_ui_refresh | V4 | CAUGHT rc=1 | dataflow |
| browser_cookie_ui_refresh | V8 | CAUGHT rc=1 | order |
| katex_bridge_arity | C2-scope | CAUGHT rc=1 | scope |
| katex_bridge_arity | C2-dead | CAUGHT rc=1 | dataflow |
| katex_bridge_arity | C2-parser | CAUGHT rc=1 | parser-death |

`14/14 CAUGHT`，`restore` 全 True（脚本退出码 0 才记通过），主克隆零污染
（每次反攻后 `git status --porcelain` 只余 4 个门脚本 M）。

> 过程记录（沙箱 S28）：第一次全量反攻在打 `AddToHomeSheet.kt` 补丁时被 ENOSYS 冲断，
> 补丁未恢复 → 已 `git checkout --` 恢复并核 `git status` 干净；随后按门串行重跑得到上表。
> **教训：并发施工窗口里，一次性跑 14 条反攻会被沙箱冲断在中途——按门分批是必需，不是优化。**

**L3 邻门**（同一批生产文件的其它门，防「加牙误伤」）：
`browser_tab_exec_serial_check` · `browser_destroy_wakeup_check` ·
`webview_holder_url_source_check` · `downloads_atomic_update_check` ·
`chat_session_id_binding_check` · `claim_release_guard` — **6/6 rc=0**。

**L4 全量**：`gate_run.sh --full`（112 门）+ `test_scan.py`（344 夹具），
分支 CI 复跑同一套。读数见 §6。

## 5. 与生产代码的关系

- 4 个门脚本 + 1 份报告，**无一行 `.kt` / `.cpp` / gradle / workflow 改动**：
  `git diff --name-only 6b6c918f..HEAD | grep -v '^scripts/scan/' | grep -v '^fix-reports/'` = 0 行。
- 所有新判据在真树（含 KaTeX/浏览器 webapp 的全部现有实现）上**零翻转**：
  没有一条既有实现被判红，说明加固锚的是「决定是否被执行」而不是「换一种合法写法」。

## 6. CI

- 分支：`fix/meta-wide3-06-browser-webapp` @ `b5d652b6`
- run **#2656 / id `40161952486`**，attempt 1，`conclusion = success`，
  `head_sha = b5d652b6`（与本地 HEAD 逐字符一致）
- 日志判读（2,971,601 B / 35,735 行，`judge` 逐条核过）：
  - `Summary: 112 passed, 0 failed` —— 2 处，分别是两个跑扫描的 job 各一份（同日全量套件被两个 step 各跑一次），无 `failed`
  - `ALL 344 CASES PASS` —— 2 处，同上
  - 门头 `━━━ [N/112]` —— **112 个互异编号**，全部门在册
  - ` e: `（Kotlin 编译错误行）—— **0 条**
  - 含 `FAILED` 的行 12 条，逐条确认**全是 PASSED 用例名子串**
    （`… produces FAILED without calling provider PASSED`、`reason PERSISTENCE_FAILED maps to …` 等）
  - 26 步唯一非 success = `Publish`（main 门控，分支构建预期跳过）

> 本地 L4 补充口径：12 支并行期间沙箱 ENOSYS 风暴反复冲掉 `gate_run.sh --full`
> 的单个长进程（进程被 SIGKILL，非门红）。处置 = 改按 28 门/批分三次跑，
> 全部 111 门 `rc=0`（`batch1 28 · batch2 28 · batch3 55`），
> 冻结夹具 `ALL 344 CASES PASS`；随后以 CI 的**单进程全量复跑**作为最终依据。

## 7. 残留与后续（每条带触发条件 / 触发后方案 / 搁置理由）

1. **`_dead_spans` 只覆盖 `if/while` 的常量条件**。
   触发条件：出现 `when` 分支常量短路（`when { false -> … }`）或 `?:` 常量兜底被用作「保留拼写删掉决定」。
   触发后方案：把 `_cond_dead_text` 扩展到 `when` 分支选择与 Elvis 右值。
   搁置理由：本波 14 条见证里 0 条命中该形态；扩面会提高误伤概率（`when` 分支常量在本仓有合法用法）。

2. **`browser_tabs` 的 C5 只接受 `val <name> = …` 的绑定形态**。
   触发条件：重构把渲染结果内联进 `text =` 的表达式（不绑局部）。
   触发后方案：加「调用点本身位于 `text =` 右侧语句内」的判定分支。
   搁置理由：当前实现是绑定形态；纯内联写法的判据需要语句边界推导，装置成本高于现状收益。

3. **`katex` 的实参切分不跟踪 `<>` 泛型**。
   触发条件：桥方法签名出现带顶层逗号的泛型参数（`List<Pair<A, B>>`）。
   触发后方案：在 `split_args` 里加 `<>` 深度（区分比较运算符）。
   搁置理由：`@JavascriptInterface` 方法只能用 JS 可映射的原始类型，本仓全部如此。

4. **`webapp` 的 arm9 复用了 `enclosing_io_block`，只认 `Dispatchers.IO` 这一种调度器名**。
   触发条件：改用 `Dispatchers.Default` 或自定义 dispatcher 注入做解码。
   触发后方案：判据改成「解码调用必须落在任意 `withContext(` 块内」并加白名单常量。
   搁置理由：图标解码用 IO 是本仓既有约定（KDoc 亦如此声明），换调度器属设计变更。

5. **注释掩码按 `'`/`"`/`` ` `` 判定引号**。
   触发条件：出现带裸引号的可疑生成代码（字符串拼接生成 Kotlin/JS）。
   触发后方案：对生成代码改走 Kotlin/JS 词法器。
   搁置理由：本仓无代码生成；`test_scan` 的「散文撇号」夹具证明当前掩码足够。

## 8. 交付边界

- 停在分支，**未合并 main**（合并起手权在用户）。
- `backlog.md` / `credibility.md` 由主会话统一登记，本支不写（防并发冲突）。
- 新门 0 个 —— 本支只加固既有判据，`scan.sh` / `test_scan.py` 冻结面零触碰。
