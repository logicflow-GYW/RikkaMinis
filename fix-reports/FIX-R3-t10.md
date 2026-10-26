# FIX-R3-t10 —— `outcome_known_chain_check` / `stale_closure_guard` 两门判据加固

- **批次**：meta-audit 第三轮 B 波（跨门缝 · dual-gate bypass）· 支线 `t10`
- **分支**：`fix/r3-outcome-stale`（基线 main `f0f14d46d`）
- **改动面**：`scripts/scan/outcome_known_chain_check.py`、`scripts/scan/stale_closure_guard.py`
  —— **生产 `.kt` 0 行**，`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰，
  门总数 109 / 夹具 344 不变
- **结论**：6 条见证 → 修前 **6/6 BLIND_CONFIRMED**，修后 **6/6 CAUGHT（rc=1 exact）、
  restore 6/6**，每条红在**自己那条判据**上（`probe/t10-attribution.json`）。

---

## 1. 门

| 门 | 文件 | 判据结构 | 本轮新增/改造 |
|---|---|---|---|
| `outcome_known_chain_check.py` | 654 → 976 行 | L0 源头 → L1/L1b 载体 → L4/L4b 出口的 `outcomeKnown` 值流链条 | `L0` 值来源不是拼写 · `L1b` 载体形参绑定 · `L4b` 出口值 + 局部再打标 |
| `stale_closure_guard.py` | 1021 → 1381 行 | S1–S4（底部判定）+ V1–V5（可见内容）+ 编辑/重试 cutoff 算术 | `S4` 阈值真被消费（含一跳别名）· `V4`/`V5` cutoff 算术即边界 · 可达性判据 |

两道门都是「旧长生命周期对象 + 新参数」这一族的守卫：门 A 守
`outcomeKnown` 从判定（`verdict`）经三个宿主一路传到 agent loop 不被洗白；
门 B 守滚动/删除边界不在参数更新后读到陈旧闭包值。第三轮的攻击面是
**两门之间的缝**——一个补丁让两门同时绿，缺陷落在各自判据的交接处。

## 2. 轴

6 条见证按失效轴归三类（与前三批同源，但都落在**缝**上）：

| 轴 | 见证 | 旧判据为何瞎 |
|---|---|---|
| **anchor（锚拼写不锚值）** | 129 `outcomeKnown = true` 写在源头、127 在载体上再打标、132 函数体丢参数 | 旧判据只问「`verdict.outcomeKnown` 是否出现在某处」，就地改写成常量仍然绿 |
| **scope（作用域错位）** | 128 出口 `return result.copy(outcomeKnown = true)` | 旧判据读 `ToolFinished` 站点，而**agent loop 拿到的是 `return` 的值**——两者之间是缝 |
| **dataflow / 算术** | 130 `+ 1`、131 丢 `+1` | 旧判据只判 `cutoffSortOrder` 被赋值，不判**赋的算术是不是删除边界** |

## 3. 见证重放表

修前 = 门脚本回退到基线版本后重放（`probe/t10-pre.json`）；
修后 = 加固版本重放（`probe/t10-post.json`）；归属 = `probe/t10-attribution.json`。

| # | 类型 | 涉及门 | 最小补丁要点 | 修前 | 修后 | 红在哪条判据 |
|---|---|---|---|---|---|---|
| 127 | dual-gate | outcome + loop_tool_status_transition | `executeShellCommandEngine` 里载体 `result` 在返回前被 `copy(outcomeKnown = true)` 再打标 | BLIND rc=0 | **CAUGHT rc=1** | `L1->L2 the coordinator's result carrier reaches ToolResultShell unmodified`（:252） |
| 128 | dual-gate | outcome + skill_restore_session_cleanup | `executeTool` 两个出口都 `copy(outcomeKnown = true)`（含 `defectHint` 分支与末尾 `return`） | BLIND rc=0 | **CAUGHT rc=1** | `L4b executeTool hands the carrier's outcomeKnown back unmodified`（:392） |
| 129 | symmetric | outcome | `executeWithShellRetry` 源头把 `verdict.outcomeKnown` 写成 `true` | BLIND rc=0 | **CAUGHT rc=1** | `L0 executeWithShellRetry stamps the retry decision's outcomeKnown`（:820） |
| 130 | dual-gate | stale + discarded_return | 编辑路径 `cutoffSortOrder = entity.sortOrder + 1`（把重试偏移用在编辑上，多删一轮） | BLIND rc=0 | **CAUGHT rc=1** | `[B-05 V4] the edit cutoff is ... + 1`（ChatQueueInterruption.kt:69） |
| 131 | dual-gate | stale + skill_restore_session_cleanup | 重试路径丢 `+ 1`（`= entity.sortOrder`，把刚重试的用户轮自己删掉） | BLIND rc=0 | **CAUGHT rc=1** | `[B-05 V5] the retry cutoff is ...`（ChatViewModel.kt:3459） |
| 132 | symmetric | stale | `isListAtBottom` 体变成 `distance <= 0`，`thresholdPx` 参数成为死参 | BLIND rc=0 | **CAUGHT rc=1** | `[B-03 S4] isListAtBottom never reads its 'thresholdPx' parameter`（ChatScreen.kt:5361） |

restore 6/6 通过 sha256 校验，事后 `git status --porcelain` 只剩两个门脚本。

## 4. 修法

### 4.1 `outcome_known_chain_check.py`（anchors → values）

1. **L0（源头）**：判 `outcomeKnown = <值>` 的**值表达式**，要求它来自 `verdict`
   （`const_bool` 折叠后非恒真/恒假）；再补一条 **X1 洗白**判据——值里拼了
   `verdict.outcomeKnown` 但被包了一层（`.let { true }`）时，必须逐字透传。
2. **L1b（载体）**：`executeShellCommandEngine` 里把 coordinator 的返回值绑到局部后，
   **载体变量的 RHS 必须逐字是 coordinator 调用**（`stamp` 常量打标被抓）。
3. **L4b（出口）**：判 `executeTool` 的**每个 `return` 值**（不判 `ToolFinished` 站点），
   并要求 `outcomeKnown` 若出现必须是 `result.outcomeKnown` 的透传；
   再补一跳：**任何局部绑定的 RHS 若再打标即红**（`val handed = result.copy(outcomeKnown = true); return handed`）。
4. **容忍臂**（防误伤，本次一并固化）：`val handed = result; return handed`、
   `val isOutcomeKnown = result.outcomeKnown`、`.copy()` 保留标志的写法全部保持绿。

### 4.2 `stale_closure_guard.py`（拼写 → 算术 / 可达性）

1. **切共享原语**：删除自写词法器/花括号计数/分词，改走
   `_kotlin_lexer.mask_code`、`_kotlin_ast.{split_top,stmt_spans,const_bool}`、
   `_kotlin_valueflow.path_feasible`；解析失败一律 fail-closed 报红。
2. **S4（阈值真被消费）**：`isListAtBottom` 的**返回值**必须读到阈值形参
   （不是「形参存在」），并对 `const_bool` 折叠后的常量体报红；
   一跳别名（`val limit = thresholdPx`）容忍，避免误伤正当重构。
3. **V4 / V5（算术即边界）**：编辑/重试两条路径的 `cutoffSortOrder` 赋值，
   判**算术形状**（编辑 = `entity.sortOrder`；重试 = `entity.sortOrder + 1`），
   并要求该值**被 `deleteMessagesAfter` 消费**（可经一跳局部）；
   赋值落在不可达分支（`if (false)` / 矛盾守卫）时单独报红。
4. 修了自身两处实现缺陷：`split_top` 的偏移基址（曾在整文件上切片，参数被解析成
   `tState`）、`main()` 的 `--selftest` / `--root` 分派顺序。

## 5. 四层证据

| 层 | 判据 | 结果 |
|---|---|---|
| L1 本门 | 真树 `rc=0` + `--selftest` 全臂带牙（臂数只增不减） | `OUTCOME_KNOWN_CHAIN_SELFTEST=PASS (20 fixtures)`（原 13 → 20）；`STALE_CLOSURE_SELFTEST=PASS (19 arms)`（原 12 → 19）；两门真树 `RC=0`，`--root .` 形态同样 `RC=0` |
| L2 邻门 | 语义相邻门 `rc=0` | 14 门全绿（`backup_remap_rollup`、`chat_session_id_binding`、`chat_ui_interaction`、`compact_decision_gate`、`compact_pin_truth`、`exec_gate_release`、`file_uri_parse_guard`、`marker_boundary`、`session_provider_enabled`、`session_sheet_id_binding`、`discarded_return_guard`、`thinking_wiring_guard` + 本支两门） |
| L3 变异 | 臂表之外的补充变异（`probe/t10_mutations.py` → `probe/t10-mutations.json`） | X1 `.let { true }` 洗白 **CAUGHT**（据此新增臂 B19）· X2 常量局部打标 **CAUGHT** · X3 `also{}` 里再打标 **CAUGHT** · X4 换名实参 **CAUGHT**；**X5 矛盾守卫 / X6 `* 0` 仍 BLIND → 已登记 §7 R1/R2** |
| L4 全量 | `scan.sh` 整份 + `test_scan.py` | **沙箱层失败，改由 CI 承担**：`scan.sh` 与逐门循环均在 1–6 门内被杀（`OSError: [Errno 38]` / 进程消失 exit −1），`test_scan.py` 后台跑 3 分钟日志 0 字节后进程消失。健康路径（分批 14 门）可用。按负载纪律停止重试，全量读数取分支 CI 日志（§6）。 |

## 6. CI

（回填见下）

## 7. 残留

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | V4/V5 的可达性判据不折叠**跨条件的矛盾守卫**（`entity.sortOrder >= 0 && entity.sortOrder < 0`，X5 仍绿） | 出现把 cutoff 赋值挂在互斥条件上的补丁，或 `path_feasible` 扩到 `conds_feasible`（联合求交）之后 | 把 `loop_persist_slice_check` 里的 `conds_feasible` 上收为共享 API，V4/V5 改调它 | 半径纪律：本轮见证里没有矛盾守卫形态；`path_feasible` 已在共享层之外自建过一轮（BATCH5-t08 记录），重复造装置收益低于影响面 |
| R2 | S4 只看「阈值是否被读」，不看**算术洗白**（`thresholdPx * 0`，X6 仍绿） | 出现乘零/加负之类的取值洗白补丁 | 对返回表达式做常量传播（`const_num`），把乘/加零折叠后再判读取 | 同 R1：属「表达式求值器」级改造，超出本轮两门的判据半径；当前形态只否「完全不读」这一族 |
| R3 | 一跳别名容忍是**全函数体范围**（不按作用域），理论上可借同名局部洗白 | 有人写 `val cutoffSortOrder = entity.sortOrder + 1` 之类的影子绑定 | 别名解析改走 `mask_code` + 作用域树（`_kotlin_ast` 已有块区间） | 影子绑定同时会被 V4/V5 的赋值形状判据判红（双保险），实测无漏 |
| R4 | 门自证 stdout 不进 CI 日志（已知）：新臂计数只能靠报告承载 | 主会话注册 `test_scan.py` 夹具时 | 注册时把 marker 断言写成 `OUTCOME_KNOWN_CHAIN_SELFTEST=PASS` / `STALE_CLOSURE_SELFTEST=PASS`（不带数字），避免加臂破断言 | 冻结面不许动（本支不碰 `test_scan.py`） |
| R5 | `scan.sh` / `test_scan.py` 本地整跑在本窗口稳定失败（Errno 38 / 静默消失） | 下次本地需要全量读数 | 分批 14 门确认邻门，全量以 CI 日志为准 | 环境层失败，非判据红；已在 §5 L4 如实登记 |

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t10      # 分支 fix/r3-outcome-stale

# 本门
python3 scripts/scan/outcome_known_chain_check.py --selftest   # 20 fixtures
python3 scripts/scan/stale_closure_guard.py --selftest         # 19 arms
python3 scripts/scan/outcome_known_chain_check.py .            # RC=0
python3 scripts/scan/stale_closure_guard.py .                  # RC=0

# 见证反攻（修前 / 修后两轮，自动还原 + sha256 校验）
python3 probe/t10_replay.py pre     # 6/6 BLIND_CONFIRMED（需把两门回退到基线版本）
python3 probe/t10_replay.py post    # 6/6 CAUGHT

# 臂表之外的补充变异（X1–X6）
python3 probe/t10_mutations.py      # X1–X4 CAUGHT；X5/X6 见 §7 R1/R2

# 邻门（分批，沙箱整跑会 Errno 38）
python3 probe/t10_neighbours.py . backup_remap_rollup_check.py chat_session_id_binding_check.py \
  chat_ui_interaction_check.py compact_decision_gate_check.py compact_pin_truth_check.py \
  exec_gate_release_check.py file_uri_parse_guard_check.py marker_boundary_check.py \
  session_provider_enabled_check.py session_sheet_id_binding_check.py \
  stale_closure_guard.py outcome_known_chain_check.py discarded_return_guard.py thinking_wiring_guard.py
```
