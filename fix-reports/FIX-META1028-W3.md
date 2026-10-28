# FIX-META1028-W3 — 三条 P0/P2 见证固化为门判据（Provider / 存储 / 缺陷召回）

分支 `fix/meta1028-w3-provider-gates` · 基线 main `bfb5b0e6` · 门提交 `8725755d` + 清理提交 `eb5c8e46`（CI 绿 sha = `eb5c8e46`）· 只改门脚本，生产 `.kt` 零改动。

## 1. 范围与改动面

| 文件 | 变化 | 归属 |
|---|---|---|
| `scripts/scan/entry_composite_key_guard_check.py` | +296 / −7 | W-B00-08 → **Arm 10** |
| `scripts/scan/browser_tabs_lifecycle_check.py` | +392 / −1 | W-B00-09 → **C13** |
| `scripts/scan/sandbox_defect_field_contract_check.py` | +347（新） | W-11 → 专用门（**未注册**，见 §7） |

`scripts/scan/scan.sh`、`test_scan.py`、`scan_blocks.sh` 零触碰（`git status` 冻结面 0 行）；生产 `.kt` 零触碰。门数仍是 113、夹具仍是 349 —— 本支只加判据，不动注册表。

三条见证的共同形态：**生产值是对的，门只到「名字还在」**。所以三条判据都不是锚拼写，而是各自读「值/控制流/正则可覆盖集合」。

## 2. 三条判据

### W-B00-08（P0，dataflow）→ `entry_composite_key_guard_check` Arm 10

缺陷：`ProviderRepository.persistToDbAndMirror` 把 `preserveEntryIds` 传成 `emptySet()`。加载期被隔离的损坏模型行不在 `entries` 里，DAO 的删除集从 `entries` 派生 ⇒ 任何无关保存（改名/排序/刷新）都会把它物理删掉。

判据（判**值**，不判名字）：
1. **参数顺序从 DAO 声明派生**：在 `ProviderConfigDao.kt` 里找到 `fun syncAllIncremental(` 的形参表，取 `preserveEntryIds` 的下标 ⇒ 命名实参和**位置实参**两种写法都能判（写死「第 6 个」就是把签名复制进门里）。
2. **不许缺**：没有该实参 ⇒ 红（DAO 默认值 `emptySet()` 是静默的，漏传等于传空）。
3. **不许常量空集**：`emptySet()` / `setOf()` / `mutableSetOf<String>()` / `emptyList()` … 一律红（见证本体）。
4. **不许永不填充**：实参的根标识符若在文件里有声明，则它的所有赋值 RHS 里必须至少有一条不是常量空集（`emptySet()` 声明后就再没被赋值 = 每次保存传的都是空的，同一缺陷抬一层）。
5. 其它写法一律绿：成员属性 / 本地 `val` 绑定 / `.toSet()` 防御性拷贝 / 全位置实参。
6. **fail-closed**：DAO 里 `preserveEntryIds` 被改名 ⇒ 红并报 gate ROT（判不了的不能算绿）。

### W-B00-09（P0，completeness）→ `browser_tabs_lifecycle_check` C13

缺陷：存储页算出 `orphanInfo.blocked` 后硬写 `val orphanBlocked = false` ⇒ 空库（DB 重置/迁移失败）时把用户真实会话目录渲染成「N 个残留目录，点击清理」，而底层 `reclaimOrphans` 的空集地板会拒绝 ⇒ 点了确认什么都不发生，读作成功。

判据（判**消费者**，site 集合派生 = `ui/**` 里调用 `withEmptyLiveFloor(` 的文件）：
- **C13a 判词必须被读**：存在一条 `.blocked` 读取，其接收者能沿赋值链（≤3 跳）追回 `withEmptyLiveFloor(…)` 的结果。`= false` 不是读取 ⇒ 红。
- **C13b 判「证明为不可点」**：找到打开回收确认弹窗的那个控件（推导：`reclaimOrphans(` 的外层条件标识符 ∩ 被赋过 `= true` 的状态 ⇒ `showReclaimDialog`），取它的 `enabled = …`，把每个判词拼写（绑定名 + `.blocked` 读表达式）代成 `true` 后常量折叠；**结果必须是 `false`**。未知不算「已证明」⇒ 红。
- **C13c 判拒绝理由**：`storage_orphan_banner_blocked` 必须被渲染，且在被 block 的世界里**可达**（`if (!blocked) 理由` 这种反接 ⇒ 红）。
- **tree 级 fail-closed**：`ui/**` 里没有任何 `withEmptyLiveFloor(` 消费者 ⇒ 红（拒绝分支没有出口不算修好）。

### W-11（P2，spelling）→ 新门 `sandbox_defect_field_contract_check`

缺陷：`SandboxDefectRegistry.FIELD_RE` 的 KDoc 承诺三种字段行写法（`- 症状：` / `- **症状**：` / `- **症状**: `），把宽度类 `[:：]` 收成 `：` 后 15 个单测全绿（夹具只喂全角粗体），半角冒号条目全部静默失配 ⇒ seed 空、recovery null ⇒ 失败时的自动召回永不触发。

判据（**正则当数据跑**，不锚拼写）：
- A **派生**：取 registry 对象里源码含字段名（症状/健康路径/救不动）的 `Regex(…)` 字面量，不按变量名找。
- B **契约 vs KDoc**：把该字面量上方注释里所有反引号包住的字段行样例（`…`→占位值）拿去做 `match`，必须命中且 group1 = 字段名、group2 非空 —— **文档写了什么，就必须真的能解析什么**（见证正是在这里破的）。
- C **等价类**：三字段 × 全角/半角 × 普通/粗体 共 12 条全部必须命中。
- D **分隔符必填**：`- 症状 x` 必须**不**命中 —— 否则 `[:：]?` 这种「变宽」会静默把值并进字段名，不能被当成修好。
- E **接线**：该字面量必须仍被 `fun parse(` 引用（名字还在页面上的死正则不算）。

## 3. 装置与自测臂

| 门 | 臂 | 结果 |
|---|---|---|
| entry（Arm 10） | G10-A1 见证 `emptySet()` / A2 漏传实参（**全位置实参形式**）/ A3 `mutableSetOf<String>()` / A4 集合永不填充 | 4 红，各自只红 arm 10（`_arm_only` 断言无旁支） |
| entry（tolerant） | T1 全位置实参（Kotlin 不允许「位置在命名之后」，因此是整个实参表都写成无命名的形态）/ T2 本地 val 绑定 / T3 `.toSet()` 拷贝 | 3 绿 |
| entry（ROT） | DAO 形参改名 → 报 gate ROT | 红（fail-closed） |
| browser（C13） | 见证 `= false` / 去掉 `enabled` 里的判词 / 理由换回普通文案 / 理由反接 `if (!blocked)` | 4 红，各自命中 A/B/C 中对应一条 |
| browser（tolerant） | shipped 形态 / 判词内联读取（无绑定） | 2 绿 |
| browser（tree） | 空 UI 树 ⇒ 红；shipped 横幅夹具 ⇒ 绿 | 2 臂 |
| W-11 门 | 见证（半角被删）/ 镜像（全角被删）/ `[:：]?` / 死正则（parse 不再引用） | 4 红，理由各不相同 |
| W-11 门（tolerant） | `(?::\|：)` 非捕获交替 / 更紧的空白类 | 2 绿 |
| W-11 门（ROT） | registry 文件消失 ⇒ 红 | 臂 |

真树自测全绿：`ENTRY_COMPOSITE_KEY_SELFTEST=PASS`、`BROWSER_TABS_SELFTEST=PASS`、`BROWSER_TABS_RISK_NOTICE_AND_REMAP_SELFTEST=PASS`（原有 pin 标记未改）、`SANDBOX_DEFECT_FIELD_CONTRACT_SELFTEST=PASS (7 fixtures)`。

## 4. 真树见证回放（三条都「绿→红→逐字节恢复」）

探针 `w3-probe/probe_w3_witness_replay.py`（树外，不随仓库提交）：

```
W-B00-08  green rc=0 | witness rc=1 | sha256 restored | OK
          red: ProviderRepository.kt:508 [arm 10] `preserveEntryIds = emptySet()` is a CONSTANT EMPT…
W-B00-09  green rc=0 | witness rc=1 | sha256 restored | OK
          red: browser_tabs_lifecycle_check RED: C13a … no `blocked` verdict … is read
W-11      green rc=0 | witness rc=1 | sha256 restored | OK
          red: sandbox_defect_registry_field_check RED (SandboxDefectRegistry.kt) …
W3_WITNESS_REPLAY=PASS
```

三条见证的 `patch_find` / `patch_replace` 原文取自 `reports/Report__2_.md`（W-B00-08/09）与 `reports/Report__1_.md`（W-11）的 JSON 块，逐字打入真树。

## 5. 邻门与真树状态（九门，均秒级）

| 门 | `--selftest` | 真树 |
|---|---|---|
| entry_composite_key_guard_check（改） | 0 | 0（10 arms clean） |
| browser_tabs_lifecycle_check（改） | 0 | 0 |
| sandbox_defect_field_contract_check（新） | 0 | 0 |
| provider_models_cache_wiring_check（邻） | 0 | 0 |
| sysinfo_state_thread_check（邻） | 0 | 0 |
| outcome_known_chain_check（邻） | 0 | 0 |
| compact_entry_pin_migration_check（邻） | 0 | 0 |
| provider_import_id_roundtrip_check（邻） | 0 | 0 |
| entry_id_rename_integrity_check（邻） | 0 | 0 |

耗时（真树）：browser 门 1.48 s、entry 门 0.38 s、新门 0.19 s —— 未引入可观成本。按任务书 §5 纪律：**未跑** `scan.sh`、`test_scan.py`、gradle。

## 6. 残留与诚实边界

- **R1（P2）** Arm 10 判的是「值非空」，**不是来源**：`quarantinedEntryRowIds = someOtherSet` 也能过。追「这个集合必须由 load 路径的 `loaded.quarantinedEntryRowIds` 填充」需要跨函数/跨类数据流，当前判据只保证「不是常量空集 / 不是永不填充」。触发条件：出现一次「传了非空但不是隔离集」的真实事故。
- **R2（P2）** 见证 `self_doubt` 的另一半未修（不改生产代码）：`quarantinedEntryRowIds` 只在 `loadFromDbOrMigrate` 的 `instanceCount() > 0` 分支赋值；JSON 镜像回退路径既不清零也不同步 ⇒ 进程内换路径时集合可能过期。这是数据层缺陷，不是门缺陷，登记待修。
- **R3（P2）** C13b 只认能被 `const_bool` 折叠的 `enabled` 形态。若将来重构成 `enabled = if (blocked) false else canReclaim`（表达式 if），会**误红**（未知不算已证明 = fail-closed 方向的假阳性）。触发条件：真的出现这种 refactor。
- **R4（P2）** C13c 只判「blocked 时理由可达」，不判「非 blocked 时不显示理由」。真实实现用块 `if`，`enclosing_conditions` 能取到；若改成表达式 if 三元，两段文案互换会漏。与 R3 同源（表达式 if 不在原语覆盖内）。
- **R5（设计选择）** W-11 门把 KDoc 当契约：删掉 KDoc 里的样例 ⇒ 门红（判不了不算绿）。代价是「改文档必须同步改门」，收益是文档漂移不再静默。
- **R6（已知）** 新门未注册 ⇒ **CI 不跑它**，本支只有自测 + 真树证据（见 §7）。
- 未验证：真机行为（本支一行生产代码没改，行为面与 main 相同）。

## 7. 注册申请（新门未注册）

`scripts/scan/sandbox_defect_field_contract_check.py` 是 W-11 唯一可能的家 —— 113 道门里没有任何一道读 `SandboxDefectRegistry.kt`（`outcome_known_chain_check` 只在夹具里出现 `defectHint` 调用点，不读正则）。

按任务书「新门若必须创建：不注册 `scan_blocks.sh`，报告里申请」，本支**没有**改 `scan_blocks.sh` / `scan_parallel.py` / `test_scan.py`。申请由收口方在并集做（三处同步：`scan_blocks.sh` 加块 + 全量 `[N/113]→[N/114]` 重编号、`scan_parallel.py` 的 `HEADER_RE` 与块数期望、`test_scan.py` 加一条只断言 `SANDBOX_DEFECT_FIELD_CONTRACT_SELFTEST=PASS` 前缀的夹具）。门自带 7 夹具 + ROT 臂，注册后即可进 CI 见证面。

## 8. CI 判读

- 分支：`fix/meta1028-w3-provider-gates`，门提交 **`eb5c8e46`**（纯清理提交，见下）。
- 第一次 dispatch（`8725755d`，run `40676248833`）后被后续提交取代，已 cancel；终局 run = **`40676464259`**，attempt 1，**completed success**，11:00→11:16（~16 min）。
- `head_sha eb5c8e4628f2ab6fd9793dafb29b7de717db374c` 与本地 HEAD **逐字符一致**。

判读（`build/3_Run pre-build scan gate.txt`，144,404 B / NUL 0）：

| 读数 | 计数 |
|---|---|
| `Summary: 113 passed, 0 failed` | 恰 1（门数未变：本支只加判据，不注册新门） |
| `ALL 349 CASES PASS` | 恰 1 |
| `^ e: `（Kotlin 编译错） | 0 |
| 含 `FAILED` 的行 | 0（在 scan 步骤内） |
| `entry_composite_key_guard_check: OK (10 arms clean)` | 1（9 → **10**，新臂生效） |
| `[102/113] browser_tabs_lifecycle_check OK (… + C13 the empty-live floor's blocked verdict …)` | 1（criteria 串带上了 C13） |
| `✅ entry_composite_key_guard_check selftest has teeth (ENTRY_COMPOSITE_KEY_SELFTEST=PASS)` | 1 |
| `✅ browser_tabs_lifecycle_check selftest has teeth (BROWSER_TABS_SELFTEST=PASS)` | 1 |
| 两条真树绿断言 | 各 1 |
| `sandbox_defect_field` / `preserveEntryIds` / `withEmptyLiveFloor` / `FIELD_RE` | **0 命中** —— 与 §7 一致：新门未注册，CI 不跑它；它的证据只有本地自测（7 夹具）+ 真树 rc=0 + 见证回放。这是本支唯一没有云端自变量度的部分，按纪律写明。 |

`build/15_Run unit tests`：`BUILD SUCCESSFUL in 5m 12s`，14,859 行；6 条含 `FAILED` 的行逐条确认**全是 PASSED 用例名子串**（`slot denied produces FAILED without calling provider`、`reason PERSISTENCE_FAILED maps to …` 等）。`ProviderConfigCorruptRowQuarantineTest` 6 条全 PASSED —— 注意这正好印证 witness appraisal：这些测试在测试代码里**自己扮演 ProviderRepository**（手工把 `load.quarantinedEntryRowIds` 传给 DAO），因此在今天的树上它们仍全绿；真正的生产调用点此前无 linker 侧证据，现在由 Arm 10 守。

Publish 按 main 门控 skip（`android-latest` 仍停在 `v1.0.0+2817` / `2026-10-28T10:10:15Z` = main 那轮的产物，分支 run 不该动它）—— **本支没有任何发布动作**。

**停止节点达成**：分支 CI 绿 + head_sha 逐字符一致 ⇒ 停分支，不合 main。

## 9. 施工中的两次自伤（记录给后来者）

1. **T1（位置实参容错臂）初版改成「只把那一行 named 实参换掉」**：Kotlin 禁用 positional-after-named，这样改出来的夹具既不合法也让门误判成「没传参数」。位置实参的真正形态是**整个实参表去掉命名**，必须整段换掉 6 行；这条同时也是提醒：容错臂不能违反源语言的语法，否则恒绿。
2. **第一条 run 推出后才发现 `_constant_empty` 有一段多余的字符串特殊分支**（`e in ("emptySet()", …)` 且 `"(" not in e[:-2]`）—— 与后面的 `_CTOR_RE` 完全重复。清掉后自测与真树读数字面一致。代价：多一轮 ~16 min 的 CI run（`40676248833` 已 cancel）。

## 10. 回报摘要

| ID | 门 | 判据形态 | 臂数 | 变异红/绿 | CI |
|---|---|---|---|---|---|
| W-B00-08 | `entry_composite_key_guard_check` Arm 10 | DAO 形参顺序派生 → 值非空、非常量空、非「永不填充」 | +4 红 +3 绿 +1 ROT | 4/3/1 全 OK | `40676464259` success，scan 步骤 `entry_composite_key_guard_check: OK (10 arms clean)` + selftest 有牙断言各 1 |
| W-B00-09 | `browser_tabs_lifecycle_check` C13 | 派生的 floor 消费者 → 判词被读 + `enabled` 证明为 false + 理由在 blocked 世界可达 | +4 红 +2 绿 +2 tree | 4/2/2 全 OK | 同上，criteria 串带 C13 |
| W-11 | 新门 `sandbox_defect_field_contract_check` | 正则当数据跑：KDoc 样例 × 两宽度×两粗体×三字段 + 分隔符必填 + parse 接线 | +4 红 +2 绿 +1 ROT | 4/2/1 全 OK | 未注册 ⇒ CI 无（申请见 §7） |

一句话总收：三条外部见证（两条 P0 数据/UI完整性、一条 P2 正则宽度）全部从「token 还在」改成「值/可达性/可覆盖集合」判据并固化成 19 条自测臂 + 真树见证回放三层齐备，分支 CI 绿 + head_sha 逐字符一致；唯一缺口：W-11 的新门按分支纪律未注册，CI 不跑它，其证据只有本地自测 + 真树 + 见证回放三条，注册申请见 §7。
