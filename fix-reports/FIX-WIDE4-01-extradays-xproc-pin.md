# FIX-WIDE4-01 — extra_days_container_guard · xproc_boundary_check · compact_pin_truth_check

基线 `19e70dab` 之后的共享词法提交 `dbab7a3e`（`_kotlin_lexer` / `_kotlin_ast` 就位）。
分支 `fix/wide4-01-extradays-xproc-pin`。**生产代码 0 行改动**，门数 112 / 夹具 344 不变，
`scan.sh` / `test_scan.py` / 两个共享模块**零触碰**。

判决：三门的 **13 条 FALSIFIED 见证 → 13/13 抓住**，另 6 条 CAUGHT 见证仍被抓住
（19/19 全数反攻成功，`restore=True` 全部，sha256 逐条复核）。

## 1. 门与判据

| 门 | 旧判据的形态 | 新判据的形态 |
|---|---|---|
| `extra_days_container_guard.py` | 自己写 `mask_comments`；取**声明点**绑定；`rfind("{")` 找 λ 接收者；`split_args` 只认单引号；writer 存在性按子串 | 共享词法器三视图 + fail-closed；**末次写**优先；**括号深度反向**找未闭合 `{`；参数切分在 code 视图上；writer 必须**不在恒假分支** |
| `xproc_boundary_check.py` | 自己写 `blank()`；`DEAD_RE` 枚举死代码拼写；`derives_field` 看 320 字符窗口子串；construction 只查「span 里出现过 key」 | 共享词法器 + fail-closed；**常量折叠**判活性（含 `when` 臂）；`derives_field` = 生产链**活性** + **绑定**；construction 逐**具名实参**验证 `get*/opt*(key)` |
| `compact_pin_truth_check.py` | 自己写 `mask_code()`；只认 `add/addAll`；只查 `clear/removeAll/retainAll`；极性只看 `any/all` | 共享词法器 + fail-closed；写面扩展到 `+= / []= / addLast / set`；破坏面扩到 `removeIf/removeAt/removeLast/removeFirst/remove/subList/无守卫 return emptyList()`；极性面扩到 `none/filter/find/count/...` 全族 |

三条共用的纪律（本波 §4 口径）：

1. **parser-death**：门自己再写正则剥注释 = 违规。三门的手写词法器整体删除，
   统一走 `analyse()`；`ok=False` → **报违规**，绝不静默返回短了半截的结构。
2. **family-hole**：不再枚举「哪些恒假写法要挡」，改成对守卫/链**求值**——
   `const_bool` / `const_value`（短路语义 + `when` 臂常量匹配）判定「这个写法能不能跑」。
3. **dataflow**：判据锚**末次写**与**生产表达式**，不锚「作用域里出现过这个标识符」。

## 2. 失效轴（本支实测口径）

- `parser-death` — 4 条：`split_args`（三引号原始字符串里的 `)`/`,`）、`blank`（`${")"}` 模板
  提前闭合 `data class` 参数表）、`pin-collector-found`（`${"}"}` 模板把函数体切短）、
  以及三门各自的注释/字符串掩码在嵌套块注释上的偏差（顺带根治，不单列见证）。
- `dataflow` — 4 条：`find_local_binding`（只看声明不看末次写）、`derives_field`
  （`.takeIf { false }` 让值恒 null 却仍算「携带该字段」）、`pin-dedup`（`+=` 直写结果）、
  `construction_spans`（两个分支都是 `null`）。
- `family-hole` — 4 条：`lambda_receiver`（空 λ 骗走 `it`）、`run_checks`（`if (false)`）、
  `dead_guarded`（`when (false) { true -> … }`）、`pin-both-sources`（同一条 `when` 轴）。
- `completeness` — 1 条：`pin-collector-intact`（`removeIf { true }` 不在破坏列表里）。
- `scope` / `spelling` — 各 1 条：`single-truth-source`（`none { it !is ToolResult }`
  是极性判据的另一种拼写，却不在 `any|all` 之外）。

## 3. 逐条见证（19 → 19）

反攻装置：`probe/reattack_w01.py`（python 只在内存里做字节级 apply/restore，
门在子进程里跑，restore 后 sha256 逐条复核；`.w4.bak` 快照 + `heal()` 自愈）。

### 3.1 `extra_days_container_guard.py`（4 盲 → 抓住；2 已挡 → 仍挡）

| `criterion` | 旧判决 | 新判决 | 新判据抓住它的那句 |
|---|---|---|---|
| `find_local_binding` | FALSIFIED | CAUGHT | `var days = arrayListOf(1); days = intArrayOf(1,2,3)` → 取**末次写** → `IntArray (parcels as int[])` |
| `lambda_receiver` | FALSIFIED | CAUGHT | 空 λ `daysForRepeatMode(r)?.let {}` 不再骗走 `it` → 深度反向扫描 → `IntArray (parcels as int[])` |
| `split_args` | FALSIFIED | CAUGHT | 三引号原始字符串里的 `)`/`,` 被抹平 → 真正的第二实参 `intArrayOf(...)` → `IntArray` |
| `run_checks` | FALSIFIED | CAUGHT | `if (false) putExtra(EXTRA_DAYS, it)` → 恒假分支 → `dead branch` + 每文件 writer 归零 |
| `classify_value` | CAUGHT | CAUGHT | `it.toIntArray()` 仍然红 |
| `call_spans_whole` | CAUGHT | CAUGHT | `arrayListOf(...).let { … }` 包裹仍然红 |

### 3.2 `xproc_boundary_check.py`（4 盲 → 抓住；2 已挡 → 仍挡）

| `criterion` | 旧判决 | 新判决 | 新判据抓住它的那句 |
|---|---|---|---|
| `dead_guarded` | FALSIFIED | CAUGHT | `when (false) { true -> … }` → 常量折叠判死 → `inside a constant-false guard` |
| `derives_field` | FALSIFIED | CAUGHT | `?.takeIf { false }?.let { put(k, it) }` → 链上常量折叠 → `takeIf { false } — the value is always null` |
| `construction_spans` | FALSIFIED | CAUGHT | `if (req.has(k)) null else null` → 逐具名实参查 `get*/opt*(k)` → `SITE … drops max_output_tokens` |
| `blank` | FALSIFIED | CAUGHT | `val contextWindow: Int? = "${")"}".toIntOrNull()` → 后续字段不再被切掉 → `unbridged_new_field` |
| `dead_guarded` (if-false) | CAUGHT | CAUGHT | `if (false) model.x?.let { put(...) }` 仍然红 |
| `construction_spans` (site) | CAUGHT | CAUGHT | 第二个构造点 `= null` 仍然红 |

### 3.3 `compact_pin_truth_check.py`（5 盲 → 抓住；2 已挡 → 仍挡）

| `criterion` | 旧判决 | 新判决 | 新判据抓住它的那句 |
|---|---|---|---|
| `pin-dedup` | FALSIFIED | CAUGHT | `texts += msg.content` → 写面含 `+=` → `writes message text without the de-dup carrier` |
| `pin-both-sources` | FALSIFIED | CAUGHT | `when (false) { true -> … parts … }` → when 臂判死 → `no live add inside the parts branch` |
| `pin-collector-intact` | FALSIFIED | CAUGHT | `texts.removeIf { true }` → 破坏面（含 λ 形态 `(?:\(\|{)`）→ `emptied after the collection` |
| `single-truth-source` | FALSIFIED | CAUGHT | `contentParts.none { it !is ToolResult }` → 极性全族 → `non-standard tool-result polarity test` |
| `pin-collector-found` | FALSIFIED | CAUGHT | `${"}"}` 模板不再切短函数体 → 其后的 `texts.clear()` 被看见 → `pin-collector-intact` |
| `pin-both-sources` (content-only) | CAUGHT | CAUGHT | 去掉 parts 循环仍然红 |
| `pin-collector-intact` (clear) | CAUGHT | CAUGHT | `texts.clear()` 仍然红 |

## 4. 修法（怎么改的）

- **词法层**：三门删除各自的 `mask_comments` / `blank` / `mask_code`，改
  `views_of()` / `lex_code()` 包一层 `analyse()`，解析失败进 problems（fail-closed）。
  `extra_days` 用 `views['code']`（字符串内容也抹，结构才可靠）；`xproc` 双视图
  （`comments` 供 key 字面量、`code` 供括号结构）；`compact_pin` 用 `code` 供 `fun_body`。
- **活性层**：`dead_site` / `dead_guarded` / `dead_here` 三个同名不同门的判定，统一
  「`enclosing_conditions` 的 `if` 条件 + `when_branches` 的 subject/arm 常量匹配」，
  常量折叠走 `_kotlin_ast.const_bool` / `const_value`（短路语义内置）。
- **数据流层**：`find_local_binding` → 末次写（声明 / 重赋值 / 复合赋值，取最后一条）；
  `derives_field` → 生产表达式窗口（跨 `?.let {` 链头，`producing_start` 深度反向走出来）
  先判「值有没有被链上常量折叠掉」，再判「字段名是否真的在这条生产链里」；
  `construction_spans` → `named_args()` 逐实参 + `key_read_in()` 要求 `get*/opt*(key)`，
  `has(key)` 只是探测，不算取值。
- **完备层**：`compact_pin` 的写面/破坏面/极性面三张表都改成「族」（写：`add/addAll/
  addLast/addFirst/set/plusAssign/+=/[]=`；破坏：`clear/removeAll/retainAll/removeIf/
  removeAt/removeLast/removeFirst/remove/subList` + 无守卫 `return emptyList()`；
  极性：`any/all/none/count/filter/filterNot/find/first*/last*/single*/indexOf*/partition/
  takeWhile/dropWhile/sumOf/fold`）。
- **见证固化**：每条被证伪的见证都成为门 `--selftest` 的一臂，命名带 `w4-` 前缀，
  期望**恰好**红它对应的判据。臂数：`extra_days` 11→15、`xproc` 7→11、`compact_pin` 10→15。
  `COMPACT_PIN_TRUTH_SELFTEST=PASS` 逐字未改。

## 5. 四层证据

**L1 门自证 + 真树**（本支三门的 `--selftest` 与真树 rc）：

```
python3 scripts/scan/_kotlin_lexer.py --selftest   → KOTLIN_LEXER_SELFTEST=PASS (20 arms)
python3 scripts/scan/_kotlin_ast.py  --selftest    → KOTLIN_AST_SELFTEST=PASS (27 arms)
python3 scripts/scan/extra_days_container_guard.py --selftest → EXTRA_DAYS_SELFTEST=PASS (15 fixtures)
python3 scripts/scan/xproc_boundary_check.py       --selftest → XPROC-BOUNDARY SELFTEST=PASS (11 arms)
python3 scripts/scan/compact_pin_truth_check.py    --selftest → COMPACT_PIN_TRUTH_SELFTEST=PASS (15 arms)
三门在真树（本 worktree）        → rc=0 / rc=0 / rc=0
```

fail-closed 抽查（构造不可解析源）：

```
extra_days 喂 `val s = "unterminated`      → rc=1  "refusing to judge a file whose structure it cannot read"
compact_pin 喂同一坏源（logic 用真文件）   → rc=1  "[pin-collector-found] … cannot lex (unterminated string literal at offset 77)"
```

**L2 外部见证反攻**（`probe/reattack_w01.py`，19 条逐条 apply → 跑门 → restore → sha256）：

```
rows=19 captured=19 bad=0 not_restored=0
extra_days  6/6   (4 CAUGHT_NOW + 2 CAUGHT_STILL)
xproc       6/6   (4 CAUGHT_NOW + 2 CAUGHT_STILL)
compact_pin 7/7   (5 CAUGHT_NOW + 2 CAUGHT_STILL)
```

并对每条 FALSIFIED 见证单独核对**报错正文**（不是只看 rc=1），确认红的是它对应的判据
（见表 §3 最右列）。

**L3 冻结面夹具**（`test_scan.py` 一个字节未改）：

```
python3 -c "import test_scan as t; t.test_extra_days_container(); t.test_xproc_boundary()"
  ✅ int[] writer is caught (exit 1)
  ✅ listOf writer is caught (exit 1)
  ✅ clean fixture exits 0 / unwritten field caught / comment comma does not truncate / bare put() inside apply{}
  → PER_GATE_FIXTURES_OK
```

**L4 CI**：见 §6（本地全量 `scan.sh` 与全量 `test_scan.py` 在 S28 窗口被打断，见 §7 R2）。

## 6. CI

见提交后的回填（run id / head_sha / 112 passed 0 failed / ALL 344 CASES PASS / 门数 112）。

## 7. 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

- **R1 — 新臂标记需主会话注册（0 条待办，仅登记）**
  - 触发条件：并集收口时统一注册新臂标记。
  - 触发后方案：在 `test_scan.py` 的 `DA####_GATES` 里为本支三门补
    （`extra_days_container_guard`, `EXTRA_DAYS_SELFTEST=PASS`）、
    （`xproc_boundary_check`, `XPROC-BOUNDARY SELFTEST=PASS`）——
    `compact_pin_truth_check` 的 `COMPACT_PIN_TRUTH_SELFTEST=PASS` **已存在**，不重复加。
  - 搁置理由：任务书 §2 明令 `test_scan.py` 冻结，注册权在主会话。

- **R2 — 本地全量套件未跑完（环境缺陷，非代码缺陷）**
  - 触发条件：S28 并发窗口（12 支并行）下 `open()/write()/fork` 随机 `Errno 38`。
  - 触发后方案：错峰后重跑 `scan.sh`（112 门）+ `test_scan.py`（344 例）；以 CI 单进程
    全量复跑为权威读数。
  - 搁置理由：本地读数已被环境证伪（同一命令两次都在不同门处 `Function not implemented`），
    强行解读会得出「门红」的假结论。

- **R3 — 见证补丁中途自杀会把工作树留在变异态（装置已加固）**
  - 触发条件：`reattack` 在「打补丁后、还原前」被打断。
  - 触发后方案：`probe/reattack_w01.py` 的 `.w4.bak` 快照 + `heal()`，
    `open/write/remove` 全部带指数退避重试；仍失败则手工 `mv .w4.bak` 回原位并 `git status` 复核。
  - 搁置理由：本轮两次留下变异态（`AlarmOffloadHandler.kt` / `MinisApp.kt`），均已 heal 且
    `git diff` 复核为 0 行 —— 属装置问题，已在装置内解决，不再占缺陷额度。

- **R4 — 共享 probe 目录会被别的支覆盖**
  - 触发条件：`meta-wide4/probe/` 是 12 支共用目录，同名脚本会互相覆盖（本轮
    `probe/reattack.py` 被支 08 覆盖）。
  - 触发后方案：本支产物一律带 `w01` 后缀（`reattack_w01.py` / `w01-witnesses.json`）。
  - 搁置理由：不进仓库，属协作约定，写入本报告即可。

**0 条待修残留**（R1–R4 均为登记项，无门判据缺陷遗留）。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-wide4/wt-01

# 共享模块自证
python3 scripts/scan/_kotlin_lexer.py --selftest
python3 scripts/scan/_kotlin_ast.py --selftest

# 三门自证 + 真树
python3 scripts/scan/extra_days_container_guard.py --selftest
python3 scripts/scan/extra_days_container_guard.py .
python3 scripts/scan/xproc_boundary_check.py --selftest
python3 scripts/scan/xproc_boundary_check.py .
python3 scripts/scan/compact_pin_truth_check.py --selftest
python3 scripts/scan/compact_pin_truth_check.py .

# 冻结面夹具（两个门）
cd scripts/scan && python3 -c "import test_scan as t; t.test_extra_days_container(); t.test_xproc_boundary()"

# 外部见证反攻（19 条）
cd /var/minis/shared/work/meta-wide4
python3 -u probe/reattack_w01.py wt-01 probe/w01-witnesses.json
```
