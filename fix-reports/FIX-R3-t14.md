# FIX-R3-t14：`offload_cli_args_check` + `fastio_tar_bounds_check` 判据加固

> 第三轮（dual-gate bypass / symmetric）收口支线 t14。基线 `main` @ `f0f14d46d`，
> 分支 `fix/r3-offload-fastio`。**生产 `.kt` 零改动**，`scan.sh` / `test_scan.py` /
> `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` 零触碰，
> 门 112 / 夹具 344 不变。只改两个门脚本 + 本报告。

## 1. 门与见证

| 门 | 见证 | 轴 | 声明 | 我判谁管 |
|---|---|---|---|---|
| `offload_cli_args_check.py` | #151 | dual-gate | 归 X（argv 规范化面） | 本门 |
| | #152 | dual-gate | 归 X（grep 预算面） | 本门 |
| | #153 | symmetric | 单门内对称（短选项表） | 本门 |
| `fastio_tar_bounds_check.py` | #154 | dual-gate | 归 Y（tar 路径安全面） | 本门 |
| | #155 | dual-gate | 归 Y（特殊文件/类型面） | 本门 |
| | #156 | symmetric | 单门内对称（模仿者 vs 原型） | 本门 |

**dual-gate 归属说明（任务书 §2.2 要求的"该门不该管这条缝"裁定）**：两门共扫
`FastioOffloadHandler.kt` / `FastioSearch.kt`，但分工是**操作系统面**，不是同一个判据
的两份拷贝 —— X 管「argv 怎么被解释、预算怎么被花」（`normalizeTarShorts` /
`normalizeLongOptionSpelling` / `grepTree`+`grepFile`），Y 管「tar 路径与文件类型上的
安全边界」（两阶段解压原子性 / 特殊文件不得被打开或冒名 / 字段上限）。因此：

| 见证 | 缝的位置 | 修后 X | 修后 Y | 理由 |
|---|---|---|---|---|
| #151 | `normalizeLongOptionSpelling` 体 | **红 N1** | 绿（刻意） | 该函数只决定 argv 拼写，不碰 tar 路径；Y 若为它建判据就是把 argv 语义抄第二份 |
| #152 | `grepFile` 的 cap 生产者 | **红 S4** | 绿（刻意） | 预算只在 grep 面存在，Y 的 grep 判据是"打开前问 isRegularFile"，与 cap 无关 |
| #153 | `normalizeTarShorts` 的 `'C'` 臂 | **红 T1+T3** | 绿（刻意） | `-C` 的展开结果是 `--directory` 参数化，Y 已用 `args.get("directory")` 的消费面间接覆盖；在 Y 重建同一张臂表等于双份漂移 |
| #154 | 两阶段解压的 Pass-1 判决 | 绿（刻意） | **红** | argv 侧对 `tarValidate` 结果一无所知 |
| #155 | `consider` 的 `--type` 谓词 | 绿（刻意） | **红** | X 的 S 系只管 grep 预算；类型标签的消费者在 find 面，归 Y 的特殊文件族 |
| #156 | `writeHeader` 的 LINKNAME 上限 | 绿（刻意） | **红** | 字段上限是 Y 的 ISSUE-03a 族（`splitUstarName`）的原型，X 不读 FastioTar |

## 2. 失效轴

六条见证同族但分四轴，与前三批一致：

| 轴 | 见证 | 旧判据为什么瞎 |
|---|---|---|
| **签名/存在 ≠ 效果** | #151、#153 | F8/F7 只钉 `internal fun normalize…(argv: List<String>): List<String>` 这一行；函数体换成 `argv` / 某臂换成 `Unit` 后签名、调用点、`SHORT_VALUE_OPTIONS` 声明全部原封不动 |
| **只看消费者，不看生产者** | #152 | S1/S2/S3a/S3b 全读 `grepTree`（`if (out.matchesCapped) TERMINATE`）；唯一能把这个 flag 置真的 `grepFile` 一行都没人守 |
| **呼唤被调用 ≠ 判决被消费** | #154 | Y 已守 `if (Files.isSymbolicLink(...))` 的守卫后果，但 Pass-1 `tarValidate` 的返回值 `safe` 从未被判（谁消费它、极性对不对、拒绝分支是否退出） |
| **标签被算出 ≠ 过滤发生** | #155 | Y 的 `check_special_file_guards` 只要求 `visitFile` 把非普通文件标成 `'o'`；消费者 `consider` 的 `--type` 谓词从未被读 |
| **对称轴只守模仿者** | #156 | `splitUstarName` 的 `NAME_LENGTH - 1` 有算术级判据，而它 KDoc 点名的原型 `writeHeader` 的 `> LINKNAME_LENGTH - 1` 完全没判 |

## 3. 见证重放表（修前 / 修后，两门各跑）

`probe/t14-pre.json`（修前）与 `probe/t14-post.json`（修后），每条都走
「就地改生产 `.kt` → 两门各自独立进程跑 → 还原 → sha256 比对」。

| # | 见证 | 目标文件 | 改法 | 修前 X/Y | 修后 X/Y | 修后红在哪 |
|---|---|---|---|---|---|---|
| 151 | 15-1 | `FastioOffloadHandler.kt` | `normalizeLongOptionSpelling` → `argv` | 0/0 BLIND | **1**/0 | `N1 the normalizer no longer maps the argv at all` |
| 152 | 15-2 | `FastioSearch.kt` | 删 `out.matchesCapped = true` | 0/0 BLIND | **1**/0 | `S4 …returns WITHOUT setting out.matchesCapped` |
| 153 | 15-3 | `FastioOffloadHandler.kt` | `'C' -> Unit` | 0/0 BLIND | **1**/0 | `T1 'C' -> Unit drops the option while known stays true` + `T3` |
| 154 | 16-1 | `FastioOffloadHandler.kt` | `if (false && !safe)` | 0/0 BLIND | 0/**1** | `the unsafe-archive refusal is short-circuited (false && !safe folds to false)` |
| 155 | 16-2 | `FastioSearch.kt` | `&& type != 'o'` | 0/0 BLIND | 0/**1** | `-type f admits the type 'o' … reading one blocks open(2)` |
| 156 | 16-3 | `FastioTar.kt` | `> LINKNAME_LENGTH` | 0/0 BLIND | 0/**1** | `the long-link test fires only from 101 bytes` |

- 6/6 先复现为 `BLIND_CONFIRMED`（两门 rc=0），**无一条 CAUGHT、无一条 NOT_REPRODUCIBLE**。
- 修后 6/6 `CAUGHT`，每条引用的报错行就是本支新写的那条判据（单条命中，不是连带）。
- 还原后 `git status --porcelain` 为空，六个文件 sha256 与原始逐一相等（脚本内断言
  `restored_sha_ok` 全 True）。

## 4. 修法（判据从「拼写存在」→「效果达成」）

### 4.1 `offload_cli_args_check.py`

| 判据 | 形态 | 判什么 |
|---|---|---|
| **N0** | fail-closed | `SHORT_LONG_OPTIONS` 必须声明且成员都是单横线形态；函数体必须存在 |
| **N1** | 效果 | 函数值必须是**被 map 过的 argv**（表达式体即整条 map；块体必须 `return`/尾表达式），`argv` 直通 = 改写没发生 |
| **N2** | 决策 + 字面量通道 | lambda 里必须有对声明集合的成员判定，且**命中分支真的多加一个横线**（`"-$x"` / `"--" + x` / `replaceFirst("-","--")`，在 literal_view 上判，`mask_code` 会把 `"-$it"` 抹空） |
| **N3** | 双向 | else 分支必须原样透传 token（改写别的 = 非成员参数被破坏） |
| **S4** | 生产者 | `grepFile` 里 `out.matches.size >= limit` 的守卫：不可折叠、可达、分支内写 `matchesCapped = true` 且 `return/break`、函数内**末次**写不得是 false |
| **T0** | fail-closed | `SHORT_VALUE_OPTIONS` 必须声明；`when (ch)` 臂表必须存在 |
| **T1** | 效果 | 每个字母臂必须「产生展开」或「令 `known = false` 作废 token」；唯一例外 `-f`（`NOOP_OK_LETTERS`，源码 KDoc 记录其 value 必须留在 positional） |
| **T2** | 兜底 | `else` 臂必须作废 token（否则未知字母被静默吞掉） |
| **T3** | 数据流 | `SHORT_VALUE_OPTIONS` 里每个取值短选项必须有展开臂，且展开的 `--key` 必须被 handler 真读（`get`/`hasFlag`/`getInt`…） |
| **T4** | 消费 | 展开必须落在 `if (known)` 下，并保留 `out.add(token)` 回退 |
| **T5** | 词表 | 每个展开出的 `--key` 必须属于源码声明的 `TAR_OPTIONS` 或被 handler 读取（防 `--dir` 这类"扩了但没人认"） |

臂表解析的两个坑（已固化在门内注释里）：`when_branches` 的 label 取自 code 视图，
字符字面量会被抹成 `' '` → 本支 `_when_arms_with_labels` 从 `literal_view` 回填；
`if_blocks` 对单行 braceless `if/else` 不切 else（then 会吞掉 else）→
本支 `_split_if_else` 在 depth 0 上补切。

### 4.2 `fastio_tar_bounds_check.py`

| 判据 | 形态 | 判什么 |
|---|---|---|
| 解压两阶段闸门 | 判决消费 + 极性 + 可达 | `tarValidate(` 必须存在且可达；返回值必须绑定成名字；必须被一个提到该名字的 `if` 消费；条件不可折叠（`false && !safe` 红）；必须是**否定**极性的形态（`!safe` / `safe == false` / `safe.not()`，裸 `safe` 反了极性 = 拒绝合法包、写出非法包 → 也红）；拒绝分支必须带 `unsafe_archive` 且**退出外层函数**（`return@label` 不算，`brace_depth` 精确到分支自己的语句层）；`tarExtract(` 必须在该分支之后（早于它 = 预扫描只是装饰）；`catch` 兜底必须是 `false`（读不出的包不能当"校验通过"） |
| `--type` 谓词真值表 | 语义枚举 | 对 `{typeFilter ∈ null/f/d/l/o} × {type ∈ f/d/l/o}` 全部 20 种组合求值，必须**恰好不匹配才 return**。取最内层提到 `typeFilter`+`type` 且分支 return 的 `if`，有效谓词 = 外层条件与自身条件的合取（嵌套两段式 `if (typeFilter != null) { if (typeFilter != type) return }` 等价于平铺合取，不误伤）；谓词从 literal_view 取（否则 `type != 'o'` 被读成 `type != ' '` —— 一个什么都豁免不了的豁免）。无法判定的谓词 fail-closed |
| LINKNAME 上限 | 算术 + 发射器 | 条件必须量的是 `member.linkName` 的 **UTF-8 字节数**（`.length` = 字符数 → 单独一条"多字节会被拦腰截断"的红）；阈值必须不松于 100 字节（`> LINKNAME_LENGTH - 1` / `>= LINKNAME_LENGTH`，`> LINKNAME_LENGTH` 红）；条件不可折叠；`'K'` 长链接记录必须仍被发射并 `write(` 出去（`val carrier = if (cond) …` + `if (carrier != null) { 'K' … }` 两跳都认） |

## 5. 四层证据

1. **本门**：真树 `python3 scripts/scan/offload_cli_args_check.py .` → rc=0；
   `python3 scripts/scan/fastio_tar_bounds_check.py .` → `GATE=GREEN` rc=0。
   `--selftest`：`OFFLOAD_CLI_ARGS_SELFTEST=PASS`（新增 `R3_T14_ARMS=14` 全部带牙），
   `FASTIO_TAR_BOUNDS_SELFTEST=PASS (71 arms)`（54 → 71，只增不减）。
   臂的期望集合是手写的（不从未跑过的实现推导），并且每条臂断言**具体报错片段**
   （撞错判据 = 假臂 = FAIL）。
   **误伤检查**：对每条新判据做一次语义等价的正当改写（块体 + 显式 return +
   具名 lambda + 拼接写法的 normalizer；臂表重排；`safe == false` 拼写；
   嵌套两段式 `--type` 过滤；`>= LINKNAME_LENGTH` 上限）→ 5/5 仍绿
   （`probe/t14_fp_check.py`），其中嵌套形态固化为 `--selftest` 的 clean 臂
   （16-2d）。
2. **邻门**：本支另一门 + 语义相邻门（`_kotlin_*` 之外全部；相邻面 =
   `file_provider_paths_coverage` / `offload_host_path` / `parts_json_funnel` /
   `fastio_tar_bounds` / `offload_cli_args`）全 rc=0。
3. **全量**：逐门独立进程循环（`scripts/scan/*_check.py` + `*_guard.py`，跳过
   `_kotlin_*`）→ **109/109 绿，0 失败**（`probe/t14-gate-status.json`；
   `scan.sh` 整份在本机会 `OSError: [Errno 38]`，按既有健康路径分片跑）。
   另跑 `process_logging_gate.py` rc=0、`turn_tail_finalize_order.py` rc=0。
   `test_scan.py`（344 夹具）见 §6 结尾的说明。
4. **CI**：见 §6。

## 6. CI

| 项 | 值 |
|---|---|
| workflow | `build-apk.yml`（`workflow_dispatch`，`--ref fix/r3-offload-fastio`） |
| run | 见下（回填） |

（本节在 CI 出结论后回填 run id / job id / head_sha / 关键日志行。）

## 7. 残留（每条带触发条件 / 触发后方案 / 搁置理由）

| id | 内容 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | 共享 `_kotlin_ast.if_blocks` 对**单行 braceless** `if (c) A else B` 不切 else（then 的 `_branch_extent` 到行尾，else 被吞），`e_start` 返回 None | 另一个门判 braceless if/else 的 else 分支 | 上收本支的 `_split_if_else`（depth 0 上找 `else`，code 视图定界）成 `if_blocks` 的 else 切分 | 硬约束：共享原语本支不许改；门内兜底已够用，且上收要连带跑 112 门回归，装置比例不符 |
| R2 | 共享 `_kotlin_ast.when_branches` 的 label 取自 **code 视图**，字符字面量 label（`'c'`）被抹成 `' '` | 任何门要按 label 判 when 臂（尤其 char 分派表） | `when_branches` 增返 `(label_start, label_end)` 跨度，或提供 literal 通道版本 | 同上；本支门内 `_when_arms_with_labels` 按同偏移从 literal_view 回填 |
| R3 | 判 `setOf("…")` 成员时**必须**用 literal 视图（`mask_code` 会把字符串内容抹空 → 成员变成空串，判据静默通过） | 任何门读声明式字符串集 | 共享层提供 `declared_string_set(text, name)`，内部走 literal 通道 | 同上；本支已在 `_declared_members` 注释写明并要求调用方传 literal |
| R4 | N1/N2 只认 `map`/`mapNotNull` + `if/else`（加横线的三种写法：模板、拼接、`replaceFirst("-","--")`） | 真实重构该函数（如改成 `buildString` 或抽 helper） | 扩到 value-flow（`value_sources` 追 `map` 的实参来源与 lambda 返回值） | 当前唯一实现就是这一形态；现在扩等于为不存在的形态写装置 |
| R5 | T1 的 `NOOP_OK_LETTERS = {"f"}` 是门内常量（依据源码 KDoc：`-f` 的值必须留作 positional） | 合法地在表里新增**故意 no-op** 的字母 | 显式改门把该字母加入白名单（并发一条自证臂） | 这是刻意的"改门要人签字"设计，不是缺口 |
| R6 | S4 只判 `grepFile` 体内单处的预算守卫 | 把 cap 的生产挪进 helper（`grepFile` 调 `charge()` 之类） | 跟随一跳 helper（同文件 `_payload_helper_bodies` 已具备能力） | 当前实现就是内联三段；提前一跳会把"调用在但没设"的形态放回来 |
| R7 | #154 只判「第一个 `tarValidate(`」与「第一个提到该 binding 的 `if`」 | 出现第二段校验（例如 destPath 也预校验）或先有别的 if 提到 `safe` | 遍历所有 validate 调用与所有相关 if，逐个判极性/退出 | 真实实现只有一段；多段时误红方向是 fail-closed（不静默放行） |
| R8 | #155 真值表只枚举 type ∈ {`f`,`d`,`l`,`o`}（`visitFile` 当前只产出这四种） | `visitFile` 引入新类型字符（如 `'p'`） | 同步扩 `TYPE_FILTER_CHARS`（扩了才有枚举保护） | 新增字符必须有新的 label 语义，属刻意编辑 |
| R9 | #156 只认 `(>=|>) LINKNAME_LENGTH [± n]` 的字面算术；写成 `LINKNAME_LENGTH - 1 - 1` 或先算常量再比较会 fail-closed 红 | 重构该比较（抽常量） | 用 `const_value` 折叠右侧表达式后比较数值 | 当前实现是内联字面量；fail-closed 方向安全 |
| R10 | #152 的 S4 假定 cap 在 `grepFile` 内、且 `grepTree` 的 S1/S2/S3 保持不变 | `grepFile` 被内联或改名 | 按「cap 写入点的**所有**位置」判（`live_positions` 枚举），不绑函数名 | 现在两段结构清晰；改名属刻意编辑 |

## 8. 复现命令

```sh
# 工作树
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t14   # 分支 fix/r3-offload-fastio

# 本门
python3 scripts/scan/offload_cli_args_check.py .            # rc=0
python3 scripts/scan/fastio_tar_bounds_check.py .           # GATE=GREEN
python3 scripts/scan/offload_cli_args_check.py --selftest   # R3_T14_ARMS=14 + PASS
python3 scripts/scan/fastio_tar_bounds_check.py --selftest  # 70 arms + PASS

# 见证重放（修前 / 修后）：就地打补丁 → 两门跑 → 还原 → sha256 比对
python3 probe/replay_t14.py pre      # 6/6 BLIND_CONFIRMED
python3 probe/replay_t14.py post     # 6/6 CAUGHT

# 全量逐门（分片；scan.sh 整份在本机会 Errno 38）
python3 probe/t14_gate_chunks.py 0 13 ; python3 probe/t14_gate_chunks.py 13 26 ; # … 至 109
```
