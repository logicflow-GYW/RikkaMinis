# FIX-META1028-W2：凭据域三见证固化成门（`envvar_config_group_check.py` C14–C16）

分支 `fix/meta1028-w2-cred-gates`｜基线 main `bfb5b0e6`（v1.0.0+2813 之后）｜门提交 `5beb2586`
见证来源：`meta-audit-1028/reports/Report__2_.md` 的 W-B00-05 / W-B00-06 / W-B00-07（三条机械验证均为 `BLIND`）
改动面：`scripts/scan/envvar_config_group_check.py` **+601/−8（唯一被改文件）**；生产 `.kt` 零触碰；`scan.sh` / `test_scan.py` / `_kotlin_*` 零触碰。

---

## 1. 先证：三条见证在真树上确实是"门瞎"

不采信报告行号，按 witness JSON 的 `patch_find` 逐条回本树机械核实（`probe` 脚本对每条：`patch_find` 命中次数 = 1 → 打入 → 跑本门 → 逐字节恢复 → sha256 比对）：

| ID | 文件（真实路径） | 修前 `patch_find` 命中 | 修前本门 rc | 判定 |
|---|---|---|---|---|
| W-B00-05 | `data/EnvVarRedactor.kt`（`redactIfEnabled` 的 `metadataUnreadable` 分支） | 1 | **0**（绿） | BLIND 复现 |
| W-B00-06 | `data/EnvVarRedactor.kt`（`redactDegradedWithReminder` 两遍叠加处） | 1 | **0**（绿） | BLIND 复现 |
| W-B00-07 | `data/repository/EnvVarRepository.kt`（`recoverKeysFromValues` 过滤谓词） | 1 | **0**（绿） | BLIND 复现 |

三条都成立的原因**同一条**：本门（以及 113 道门里任何一道）**根本不扫这两个文件**。`locate()` 此前只定位 `EnvVarsCollection.kt` / `ConfigBuiltins.kt` / `GroupsCollection.kt` / `ConfigBackup.kt`；`skill_import_offmain_check.py` 的 arm9 只判"`EnvVarRepository` 的磁盘方法是否跑在 Main 上"，与谓词极性无关。所以三条见证不是"判据被绕过"，而是"没有判据"——这也是它们能在全绿树上存活的结构原因。

## 2. 判据设计：绑决定点，不绑拼写

三条见证的共同形态是 **token 一个不少、只把决定去掉**（`false && …` / `if (valueHits > 0) … else` / `!in` → `in`）。因此三条判据都判"效果/极性"，并为每条配一个**等价改写必绿**的臂，避免把将来的等价重构判成回归。

### C14 `check_degraded_branch`（`redactIfEnabled`）

- **决定点一：分支还活不活** —— `if_blocks(body)` 找出条件含 `metadataUnreadable` 的 if，用 `path_feasible` 判"这条 then 是否可达"。`false && repo.metadataUnreadable` 的 `const_bool` 折叠为假 ⇒ 判"分支已死"，报 RED 并回显那个条件。
- **决定点二：分支把输出交给谁** —— 取分支内**可达** `return` 的表达式，要求存在一条**调用链**（`reaches_shape_mask`，跨函数一跳以上）能到达 `redactUnindexed`。于是：
  - `return output to 0` → 判 RED（原文直出）；
  - `return redactWithReminder(...)`（只做值匹配）→ 判 RED（降级路径退化）；
  - `return redactDegradedWithReminder(...)` 或**新提取的任意名字 helper** → 绿（FIX-38 钉住）。
- 空转防护：不看函数名，看"能否到达按形态脱敏的那一个函数"；`fun_bodies()` 从文件内枚举函数体，helper 抽离/内联都判对。

### C15 `check_both_passes`（`redactDegradedWithReminder`）

四个子判据全部落在"两遍是否都真跑"上：

1. 形态遍存在（`redactUnindexed(` 至少一处），否则 RED；
2. **顺序**：形态遍首处必须在值遍首处之后；
3. **不被值遍短路**：形态遍调用处 `path_feasible` 必须为真（死守卫 RED），且 `enclosing_conditions` 里**任何**条件都不许引用值遍自己的结果名（`val (byValue, valueHits) = redact(...)` 的左侧名，从解构**派生**，不锚拼写）——`if (valueHits > 0) (byValue to 0) else redactUnindexed(byValue)` 覆盖条件为 `!(valueHits > 0)` ⇒ RED；
4. **吃的是值遍输出**：`call_args` 取的实参必须引用值遍结果名（`redactUnindexed(output)` ⇒ RED），且形态遍结果名必须出现在某个可达 `return` 的表达式里（算了但丢掉 ⇒ RED）。

### C16 `check_recover_polarity`（`recoverKeysFromValues`）

- **决定点：谓词极性**。锚点是 `_entries.value…key` 这个**初始化表达式**派生的集合变量名（`val known = …`，改名成 `present` 仍判对，FIX-45）。
- 分类：`filter` 内的 `it !in known` / `!known.contains(it)` / `contains(...).not()` ⇒ **差集**（绿）；`filterNot { it in known }` ⇒ 同一件事的另一拼写（绿，FIX-44）；`filter` 内的 `it in known` / `known.contains(it)` ⇒ **交集**（RED，见证原形）。
- **常量短路**：谓词按顶层 `||` 切分后任一段 `const_bool` 为真（`it !in known || true`）⇒ RED（决定没了）。
- 兜底：集合变量与 `known` 完全脱钩（`filter { it.isNotEmpty() }`）⇒ RED；`.keys - known` / `minus` ⇒ 视为等价写法（绿）。
- 全部取不到锚点（函数改名/初始化表达式改了/值存储换了）⇒ `EXTRACT_FAIL` **fail-closed**，不静默跳过。

### 接线（fail-closed）

`check()` 新增两个可选实参（`redactor_path` / `repo_path`），只在调用方给出时判 C14–C16（保持 1028 之前的 3/4 文件夹具形状不变）；`locate()` 在**真树模式**下必须找到这两个文件，找不到直接 `LOCATE_FAIL`（防止"改名即失去唯一看守它的判据"）。

## 3. 装置与臂（13 条，FIX-35…FIX-47）

夹具：`FIXTURE_REDACTOR_OK`（`redactIfEnabled` + `redact` + `redactWithReminder` + `redactDegradedWithReminder` + `redactUnindexed`）、`FIXTURE_ENVVAR_REPO_OK`（`loadMetadata` → `recoverKeysFromValues`），装入既有 `full_fixture()` 六源树，跑完整 `check()`（16 条判据）。

| 臂 | 形态 | 期望 |
|---|---|---|
| FIX-35 | 六源夹具完整 | 全绿（16 条） |
| FIX-36 | **W-B00-05 原样见证** `if (false && repo.metadataUnreadable)` | 恰 C14 RED |
| FIX-37 | 同一决定点的第二种去掉方式：分支活着但 `return output to 0` | 恰 C14 RED |
| FIX-38 | 等价改写：降级脱敏抽成 `maskDegradedOutput(...)`（名字完全不同） | **绿** |
| FIX-39 | **W-B00-06 原样见证** `if (valueHits > 0) (byValue to 0) else redactUnindexed(byValue)` | 恰 C15 RED |
| FIX-40 | 形态遍整条删掉 | C14 + C15 双红（C14 的"到达形态脱敏"同时失守，属预期，用 `expect_prefixes` 精确断言两条顺序） |
| FIX-41 | 两遍都在，但形态遍吃原文 `redactUnindexed(output)` | 恰 C15 RED |
| FIX-42 | token 在、条件恒假 `if (false) redactUnindexed(byValue) else …` | 恰 C15 RED |
| FIX-43 | **W-B00-07 原样见证** `it in known` | 恰 C16 RED |
| FIX-44 | 等价改写：`filterNot { it in known }` | **绿** |
| FIX-45 | 等价改写：集合变量改名 `known` → `present` | **绿** |
| FIX-46 | 谓词与 known 脱钩（`filter { it.isNotEmpty() }`） | 恰 C16 RED |
| FIX-47 | 常量短路 `it !in known || true` | 恰 C16 RED |

"恰好红"用既有两套断言：单条 `expect()`（失败列表长度 = 1 且前缀匹配）与 `expect_prefixes()`（多条时按"顺序 + 头部"精确比对）。

## 4. 验证读数

| 项 | 命令（均 `nice -n 10`） | 读数 |
|---|---|---|
| 门自测（含新臂） | `python3 scripts/scan/envvar_config_group_check.py --selftest` | `ENVVAR_GROUP_SELFTEST=PASS`，48 条 `[SELFTEST]` 行（+13） |
| 真树 | `python3 scripts/scan/envvar_config_group_check.py .` | `OK (16 criteria green)`，rc=0 |
| **真源三变异** | `/tmp/w2_mutate.py`（把三条见证 patch 打进真树） | `W-B00-05 rc=1 C14 RED` / `W-B00-06 rc=1 C15 RED` / `W-B00-07 rc=1 C16 RED`，三条 **restored=True（sha256 逐字节一致）**，`PROBE=PASS` |
| 邻门 selftest（6 道） | `backup_remap_rollup_check` / `skill_import_offmain_check` / `provider_import_id_roundtrip_check` / `openminis_compat_field_parity_check` / `provider_models_cache_wiring_check` / `four_way_sync_check` | 各自 rc=0，标记逐个 PASS |
| 邻门真树（3 道） | 同上中 `skill_import_offmain_check`（arm9 主体就是 `EnvVarRepository`）、`backup_remap_rollup_check`、`four_way_sync_check` | rc=0 |
| 真源恢复 | `git status --short` | 仅 `M scripts/scan/envvar_config_group_check.py`（无残留 patch） |

## 5. CI

分支 push 不触发 `build-apk.yml`（`on.push` 只认 main），按流程手动 dispatch：

- run **`40676242213`**（job `122775279109`，attempt 1）= **success**，10:58:00Z → 11:18:34Z（20m34s）
- `head_sha` = `5beb2586b43417f8e7f1ce4f07d07ec1292e0ea9` = 本地门提交**逐字符一致**
- 27 个步骤唯一非 success = `23 Move the rolling tag` + `24 Publish to Releases`（**均 skipped**，`if: refs/heads/main` 门控，分支上按设计跳过）
- 日志 1,504,624 B / NUL 0：`Summary: 113 passed, 0 failed` ×1、`ALL 349 CASES PASS` ×1、`[113/113]` ×1、`^ e: ` **0 行**、含 `FAILED` 但非 `PASSED` 的行 **0 条**
- 新判据在云端真跑过两次可核：`ENVVAR_GROUP_SELFTEST=PASS` ×1（selftest 带牙断言）与 `envvar_config_group_check: OK (16 criteria green)` ×1（真树 arm）

## 6. 诚实边界

1. **本批是纯静态门**：没有一行生产代码改动，故无真机验证面；判据的正确性由"真源三变异必红 + 等价改写必绿 + 真树必绿"三面共同支撑，而不是由"工具跑通"支撑。
2. **C14 只判分支内的 `return`**：若把原文装进局部变量再返回（`val safe = output; return safe`），`reaches_shape_mask` 会误判绿。真实代码没有这种形态；已在 §7 R1 登记触发条件。
3. **C15 只判形态遍首处**：若将来出现"一处活、一处死"的双形态遍，判据只看第一处（活）会漏第二处。登记 R2。
4. **C16 的常量短路只覆盖 `||` 方向**：`it !in known && true` 不构成短路；`(known == known) && it !in known` 这类"恒真且"不会被识别为常量（方向是 fail-open，不是 fail-closed）。登记 R3。
5. **`skill_import_offmain_check.py` 未改**（任务书允许改）：通读后确认它的 arm9 主体是"`EnvVarRepository` 的磁盘方法是否在 Main 上调用"，与三条见证的决定点（分支活性 / 两遍短路 / 谓词极性）无交集；为它硬塞凭据判据只会把一个专职门变成杂物间。已在门脚本 KDoc 里写明这条取舍，供下一个读者复核。
6. **夹具 ≠ 生产**：夹具是"行为等价的最小形状"，不是真文件切片。C14/C15 的 `fn_body` 抽取、C16 的 `filter` 定位都在真树上真跑过（§4 真源变异），所以这里的抽象风险已经被真树读数覆盖一次。

## 7. 残留登记（P2，三件套齐全；本支冻结，由主会话并入 backlog）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | C14 只认分支内**直接** `return` 原文；`val safe = output` 再返回会误绿 | 某次改动在降级分支里引入中间变量再加返回 | 把"原文直出"判据改成值流：`peel_discarding` + `value_sources` 追到 `output` 即判 RED | 现状无此形态，改判据会引入 valueflow 依赖（本门当前只用 `literal_view` + `path_feasible`） |
| R2 | C15 只看形态遍**首处**的闸门/实参/结果流向 | 出现第二处 `redactUnindexed(` | 把 §2 的四个子判据对**每一处**形态遍循环执行（现结构已是 for 循环可平移） | 一处形态遍是当前唯一形态；两处时反而要先问"为什么叠加两遍" |
| R3 | C16 常量短路只识别 `\|\|` 方向；`&&`-恒真形态 fail-open | 出现 `(known == known) && it !in known` 之类写法 | 对顶层 `&&` 也做 `const_bool` 折叠；恒假段直接 RED（谓词永假 = 恢复通道关闭） | 无现实形态；`||` 方向才是"掩盖决定"的常见手写 |

## 8. 结论

三条 BLIND 见证的根因同为"没有判据"，本批把判据补在**唯一动得了它们的位置**：`envvar_config_group_check.py` 的凭据面（C14–C16，真树 fail-closed 定位）。三条真实见证 patch 打回真树后门当场变红并逐字节恢复；三条等价改写臂（helper 抽离 / filterNot / 变量改名）证明判据钉的是决定点而不是拼写。

> 收口行：run `40676242213` / head_sha `5beb2586b43417f8e7f1ce4f07d07ec1292e0ea9` = 本地门提交逐字符一致 / 门头 113 互异 / `Summary: 113 passed, 0 failed` ×1 / `ALL 349 CASES PASS` ×1 / `^ e: ` 0 / 唯一 skipped = `Move the rolling tag` + `Publish to Releases`（main 门控）。
>
> **停止节点达成：分支 CI 绿。未合并 main**（合并起手权在用户）。
