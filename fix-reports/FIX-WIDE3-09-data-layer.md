# FIX-WIDE3-09 — 数据层四门见证加固（room_migration / dao_loadmessages / four_way_sync / entry_id_rename）

- 分支：`fix/meta-wide3-09-data-layer`
- 基线 main：`6b6c918fae6c338e3091d0d3870af3cad9e2b881`
- 门改动提交：`c880ea7a`
- 生产代码改动：**0 行**（只改 4 个门脚本；`scan.sh` / `test_scan.py` 冻结面零触碰；门数 112、夹具 344 不变）

---

## ① 门与判据

| 门 | 守护的行为 | 修前判据 | 修后判据 |
|---|---|---|---|
| `room_migration_check.py` | 两个 Room DB（AppDatabase / ProviderDatabase）的迁移声明、配对、接线、**实际迁移动作** | 版本号 vs 最大迁移对、`object : Migration` 声明、`addMigrations(...)` 列表成员（原文正则）、重复目标 | ①注释先剥离（字面量感知）②**升级体必须含至少一条非死守卫的 `db.*` 语句**③接线从真实 `Room.databaseBuilder(...).addMigrations(...).build()` 链解析（链外/死分支`apply{}` 不算）④解析失败 fail-closed |
| `dao_loadmessages_check.py` | 生产代码不得绕过 `ChatRepository` 直读大 session 记录 | 逐行正则 `\.dao\.loadMessages\(` + 整文件白名单 `{ChatRepository.kt, ChatDao.kt}` | ①注释剥离②**跨行链拼接**（`.` 开头的续行）③**DAO 别名解析**（`val rawDao = repo.dao`）④DAO 作用域函数（`with(repo.dao){}` / `.let{}`）⑤**白名单整体删除**（声明无 receiver 天然不匹配）⑥未解析 receiver → fail-closed⑦零文件 → 违规 |
| `four_way_sync_check.py` | 模型/实体/toSnapshot/toProviderConfig 四处字段不丢 | 构造点命名实参「存在」+ 集合差；`balanced()` 返回 −1 时静默跳过 | ①载入即剥离注释②解析失败记账为违规（fail-closed）③**新增派生判据**：四向字段在**每个映射形构造点**都取常量 ⇒ 值从未离开源对象，判红（合成构造点不受影响） |
| `entry_id_rename_integrity_check.py` | 复合键改名的空白守卫与碰撞拒绝 | 四条臂 = 字符串锚点（存在即绿）；`code_only` 只剥 `//`；`function_text` 取头部后第一个 `{` | ①`/* */` 也剥离（字面量感知，字符串保留）②函数体改为「先过参数表再取体」③臂 1 增加**决定被消费**：`return@synchronized` 必须由 `if (...decision.allowed...)` 拥有且该守卫**能拒绝**（裸字面量短路 ⇒ 死守卫）④臂 2 增加**碰撞决定被消费**：空白守卫必须真的 `allowed = false`；`others.any{}` 谓词必须比较 `updatedInstanceId` 与 `updatedModelId`；计算结果必须被一个能拒绝的活守卫测试 |

---

## ② 失效轴（12 条见证的分布）

| 轴 | 条数 | 门 |
|---|---|---|
| `dataflow`（守卫/结果被调用但决定没了） | 5 | room `CHAIN`、dao `PATTERN`、four_way `G2`、entry `Arm 1`、entry `Arm 2` |
| `parser-death`（读注释当代码 / 结构解析提前截断） | 5 | room `WIRED`、dao `is_production`、four_way `G2`、four_way `G1`、entry `Arm 3` |
| `anchor`（锚「调用的存在」而非「在真链上的位置」） | 1 | room `WIRED` |
| `scope`（整文件豁免 = 法外之地） | 1 | dao `WHITELIST_BASENAMES` |

对照组（修前就已 `CAUGHT_VERIFIED`、本轮仍需保持抓住）：room `NAME_VS_PAIR`（spelling）、dao `PATTERN`（spelling）、four_way `G2`（completeness）、entry `Arm 1`（completeness）。

---

## ③ 逐条见证（criterion / 轴 / 旧判据为何瞎 / 新判据形态）

### room_migration_check.py

| witness | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|
| `CHAIN` | dataflow | 声明、配对、接线三面全部保留，只把 `override fun migrate` 体清空 → 门只看头部，`Migration(13,14)` 照样「合规」，而 Room 在打开时跑一个 no-op、随后 schema 校验失败 | 升级体必须有 ≥1 条 `db.*` 调用且不在 `if (false)` 之类死守卫内；空体/死守卫体判红。降级体空（`MIGRATION_13_12`）按设计合法 |
| `WIRED` | parser-death | `addMigrations(...)` 成员用原文正则取，`// MIGRATION_13_14,` 在参数列表里就是注释，却被当成已注册 | 先 `mask_comments`（`//`、`/* */`，字符串感知），再解析；注释掉的参数 → 未接线 |
| `WIRED` | anchor | `.apply { if (false) addMigrations(A, B) }` 里 `addMigrations(` 字面存在 ⇒ 旧判据绿；实际链是 `databaseBuilder(...).apply{}.build()`，Room 一个迁移都没注册 | 解析真实 builder 链元素序列，`addMigrations` 必须是链深度 0 的元素且链以 `.build()` 收尾；链外调用额外报「NOT on the databaseBuilder→build() chain」 |

### dao_loadmessages_check.py

| witness | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|
| `PATTERN` | dataflow | 旧正则只认 `<x>.dao.loadMessages(` 字面；`val rawDao = repo.dao` + `rawDao.loadMessages(…)` 是同一绕过，拼写不同 | 从语句内收集 DAO 绑定标识符（`val X = ….dao`、`val x: ChatDao`），receiver 末段命中即判红；receiver 既非 DAO 也非 repository 形 → fail-closed 判红 |
| `is_production` | parser-death | 逐行扫描看不到跨行链：`repo.dao` 在一行、`.loadMessages(` 在下一行（ktlint 的续行风格） | 逻辑语句拼接（深度>0 或下一行以 `.`/`?`/`:`/`|` 开头则为续行），在拼接视图上匹配 |
| `WHITELIST_BASENAMES` | scope | `ChatRepository.kt` 整文件豁免 ⇒ 仓库内部新增 helper 可无声重犯 ISSUE-08 | 白名单删除；DAO 声明 `fun loadMessages(` 由「声明无 receiver」自然排除，仓库合法调用 `dao.loadMessagePage*` 不匹配；新增 selftest 臂 E 专守住「仓库文件不是法外之地」 |

### four_way_sync_check.py

| witness | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|
| `G2 (Entity -> to-DB)` | parser-death | 在原文上抽命名实参：`// pinned = if (inst.pinned) 1 else 0,` 被注释掉了，仍算「该字段已映射」 | 载入即剥离注释，注释掉的实参 → `toSnapshot missing` |
| `G2 (Entity -> to-DB)` | dataflow | 只判「实参名存在」：`pinned = 0` 名字在、值不来自模型，用户设置永不落库 | 派生判据：对每个四向字段，在**映射形构造点**（覆盖目标字段 ≥ 一半）若取值为常量（无任何标识符引用；`${…}` 模板不算常量）⇒ 判红并指名文件:行。合成构造点（`ProviderInstance(label=…, pinned=false)`）不参与，避免误伤 |
| `G1 (Model -> Entity)` | parser-death | `balanced()` 在原文上配平，注释里的 `// 1) …` 提前闭合参数表 ⇒ 其后所有字段**静默消失**，整族判据扫的是残体 | 注释掩码后配平（掩码与原文等长、行号对齐）；配平失败不再 `continue`，而是记 `PARSE_FAILURES` → 计为违规（fail-closed） |

### entry_id_rename_integrity_check.py

| witness | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|
| `Arm 1` | dataflow | 锚 `decideEntryRename(` + `return@synchronized` 存在即绿；`if (false && !decision.allowed)` 保留全部拼写、丢掉决定，冲突条目照写 | `return@synchronized` 必须被某个 `if (...)` 拥有；该守卫条件必须引用 `.allowed`；且条件不得被裸字面量决定（`&&`/`||` 顶层拆项后出现裸 `true`/`false` ⇒ 死守卫） |
| `Arm 2` | dataflow | 锚 `others.any` 存在即绿；`if (false && collides)` 不拒绝，`others.any { false }` 更是删掉了测试本体 | 三判据：①空白守卫必须是活的 `allowed = false` 拒绝②`others.any{}` 谓词必须同时引用 `updatedInstanceId` 与 `updatedModelId`③该结果名必须被某个「能拒绝（`allowed = false` 体）且非死守卫」的 `if` 测试 |
| `Arm 3` | parser-death | `code_only` 只剥 `//`，`/* enabled = … */ enabled = true` 里被注释的旧表达式仍当活代码 | `code_only` 同时剥离 `/* */`（字符串感知，`"modelId must not be blank"` 这类锚点字面量保留） |

---

## ④ 修法（停在哪一级）

全部停在第 2 级「代码库里已有」之后的**判据改造**：不新增门、不改生产代码、不动 `scan.sh` / `test_scan.py`（冻结面），只在 4 个门文件内把「存在」判据换成「效果」判据，并把每条新判据固化成自带负向臂。

- 共同手法 1：**`mask_comments` 字面量感知剥离**（等长、行号对齐、`//` 与 `/* */` 都处理、`"""` 原始字符串与转义正确处理）。四门里三门的中毒点都是「读注释当代码」。
- 共同手法 2：**解析失败 = 违规**，不再 `continue`。空／残扫描面自己会报绿，这是盲门最常见的伪装。
- 共同手法 3：**判据落到决定点**：迁移体要有活的 `db.*` 语句；`addMigrations` 要在真链上；DAO 调用要由 receiver 解析决定；四向字段的值要来自源对象；拒绝决定要被活守卫消费。
- 共同手法 4：**每个新判据配一个 needle 断言**（selftest 臂里既判 rc，也判「红在对应判据上」），否则红在别的臂上也当证据。

selftest 臂数：room 13 → **18**；dao 4 → **10**（重写）；four_way 0 → **7**（新增 `--selftest`）；entry 8 → **12**。
收尾标记按任务书冻结：`DAO_LOADMESSAGES_SELFTEST=PASS`、`ENTRY_ID_RENAME_INTEGRITY_SELFTEST=PASS` 一字不差保留；room/four_way 新增 `SELFTEST` 汇总行。

---

## ⑤ 四层证据

### L1 本门真树绿 + 自证臂全过

```
$ cd /var/minis/shared/work/meta-wide3/wt-09
$ for g in room_migration_check dao_loadmessages_check four_way_sync_check entry_id_rename_integrity_check; do
    python3 scripts/scan/$g.py . ; echo "$g rc=$?"; done
room_migration_check rc=0
dao_loadmessages_check rc=0
four_way_sync_check rc=0
entry_id_rename_integrity_check rc=0
```

selftest（`room` 用 `--self-test`，其余 `--selftest`）：

```
room_migration_check              -> ok=True  (18 arms, 全部 PASS，含 wide3 五臂)
DAO_LOADMESSAGES_SELFTEST=PASS    (10 arms)
FOUR_WAY_SYNC_SELFTEST=PASS       (7 arms)
ENTRY_ID_RENAME_INTEGRITY_SELFTEST=PASS  (12 arms)
```

`four_way` 真树读数：`Done: ALL CLEAN ✅ [checked 10/10]`（10 个派生族全覆盖）。

### L2 外部见证反攻（修复前 STILL_BLIND → 修复后全 CAUGHT）

```
$ python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
    --gates room_migration_check.py,dao_loadmessages_check.py,four_way_sync_check.py,entry_id_rename_integrity_check.py
```

| 门 | 修复前 | 修复后 |
|---|---|---|
| room_migration_check | 3/3 STILL_BLIND | **3/3 CAUGHT**（CHAIN、WIRED×2） |
| dao_loadmessages_check | 3/3 STILL_BLIND | **3/3 CAUGHT**（PATTERN、is_production、WHITELIST_BASENAMES） |
| four_way_sync_check | 3/3 STILL_BLIND | **3/3 CAUGHT**（G2×2、G1） |
| entry_id_rename_integrity_check | 3/3 STILL_BLIND | **3/3 CAUGHT**（Arm 1、Arm 2、Arm 3） |

合计 **12/12 CAUGHT**，每次 `restore=True`（补丁撤回、sha256 与原文一致，工作树零污染）。

**needle 级复核（防「红在别的判据上」）**：对 four_way 的两条见证逐条打补丁并读门的输出行——
- `pinned = 0` → 红在 `is a constant at every mapping site`（新派生判据），**不是** `toSnapshot missing`；
- `// pinned = …` → 红在 `toSnapshot missing`（注释掩码生效），**不是**派生判据。

### L3 邻门 / 相关单测

- `test_scan.py` 相关用例逐个跑：`test_four_way`、`test_four_way_derived`、`test_four_way_entity_self`、`test_room_migration`、`test_da1018_gates`（dao 门注册）、`test_da1022_gates`（entry 门注册）→ 全部 PASS（`FAILED: []`）。
- 说明：`test_da1018_gates` / `test_da1022_gates` 覆盖我改的两门的「marker 有牙 + 真树绿」断言，标记串保持一字不差。

### L4 全量

本地全量（`gate_run.sh --full` / `scan.sh` / `test_scan.py` 全跑）在本支施工窗口反复被 PRoot `OSError: [Errno 38] Function not implemented`（registry S28，12 支并行时的已知症状）打断，未能在本地拿到一次性完整读数；由于 CI 的 `scan.sh`（112 门）+ `test_scan.py`（344 夹具）就是同一套全量，**以 CI 读数为 L4 证据**（见 ⑥）。

---

## ⑥ CI run

分支 dispatch（`build-apk.yml`，非 main 分支不自动触发）：

| 项 | 值 |
|---|---|
| run | **#2650** / id `40160696915` / job `121071435808` |
| 结论 | **success**（attempt 1） |
| head_sha | `cb90f669f1641299d2b0b40466ee414e054b2e23`（本地 HEAD 逐字符一致） |
| 步骤 | 26 步，唯一非 success = **Publish to Releases / skipped**（main 门控，符合预期） |
| 门 | 门头 `[N/112]` **112 条、互不重复** |
| 全量 | `║  Summary: 112 passed, 0 failed`（恰一条带框线汇总） |
| 夹具 | `ALL 344 CASES PASS`（恰一条） |
| 编译 | ` e: ` **0 条** |
| 伪 FAILED | 含 `FAILED` 的 6 行全部是 **PASSED 用例名的子串**（如 `slot denied produces FAILED without calling provider PASSED`） |

→ 分支 CI 绿 = 本支确定性停止节点，**未合并 main**。

---

## ⑦ 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

1. **`four_way` 派生判据只覆盖「映射形构造点」**
   - 触发条件：某个族出现两个都覆盖 ≥50% 字段的不同映射函数（例如备份导入侧的第二个映射器），且其中一个开始写常量。
   - 触发后方案：把 `mapping_shaped_sites` 的阈值改为按族取相对最大值 ×0.9，或对「同族多映射器」显式登记白名单。
   - 搁置理由：当前树每族只有一个映射形构造点（真树 10/10 全绿、零误伤），提前加复杂度属于为未触发的情形建装置。
2. **`dao` 门的 receiver 分类是形状判定（`repo` 子串 / `dao` 后缀）**
   - 触发条件：出现既不叫 `*Repository` 也不叫 `*Dao` 的中间层变量持有 DAO（如 `private val store: ChatDao`）。
   - 触发后方案：把 `val x: ChatDao` 的声明式绑定扩展到构造参数与 class 属性（当前只扫 `val/var` 与赋值行）。
   - 搁置理由：fail-closed 已兜住未知 receiver（会判红而不是放过），代价是可能误红而不会漏红——方向安全。
3. **`room` 门只判「体里有活的 `db.*` 语句」，不判 SQL 语义**
   - 触发条件：迁移体改成执行一条与目标 schema 无关的 SQL（例如 `db.execSQL("SELECT 1")`）。
   - 触发后方案：要求 `execSQL` 的实参字面量包含 `CREATE`/`ALTER`/`DROP`/`UPDATE`/`DELETE`/`INSERT` 之一，且与声明的版本对至少共享一个对象名。
   - 搁置理由：当前 11 个升级迁移全部是 `ALTER`/`CREATE`，加语义判据会把门变成 SQL 解析器；外部见证也未以此攻击。
4. **`entry` 门臂 3/臂 4 仍是锚点 + 注释掩码**
   - 触发条件：出现 `enabled = true` 与正确表达式并存、或 RPC 侧空白消息被挪到别处的等价重构。
   - 触发后方案：把「保存按钮 enabled 必须由 `modelId.isNotBlank()` 参与」提升为结构判据（解析 `enabled =` 表达式的操作数）。
   - 搁置理由：本轮两条见证的失效点只在注释（`/* */` 未剥），掩码后该形态已被覆盖；未观察到「表达式被替换成常量」的见证。

---

## ⑧ 复现命令

```sh
cd /var/minis/shared/work/meta-wide3/wt-09        # 分支 fix/meta-wide3-09-data-layer，基线 6b6c918f

# L1 真树 + 自证臂
python3 scripts/scan/room_migration_check.py . && python3 scripts/scan/room_migration_check.py --self-test
python3 scripts/scan/dao_loadmessages_check.py --root . && python3 scripts/scan/dao_loadmessages_check.py --selftest
python3 scripts/scan/four_way_sync_check.py . && python3 scripts/scan/four_way_sync_check.py --selftest
python3 scripts/scan/entry_id_rename_integrity_check.py . && python3 scripts/scan/entry_id_rename_integrity_check.py --selftest

# L2 外部见证反攻（12 条，期望 ALL CAUGHT / exit 0）
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
  --gates room_migration_check.py,dao_loadmessages_check.py,four_way_sync_check.py,entry_id_rename_integrity_check.py

# L2' 修复前对照（在基线树上跑一次即可看到 12/12 STILL_BLIND）
#   期望：RESULT: GATE BLIND CONFIRMED (12)

# L4 全量（沙箱 ENOSYS 风暴期请用带锁运行器，别裸跑 scan.sh）
sh /var/minis/shared/tools/gate_run.sh --full     # 期望 112 passed, 0 failed
python3 scripts/scan/test_scan.py                 # 期望 ALL 344 CASES PASS
```
