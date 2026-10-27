# FIX-W1027-C — 存储预算回路 / 沙箱临时目录 / 缺陷召回 / 存储页孤儿装配 四面见证收口

分支 `fix/w1027-storage-guards`｜基线 main `4a48a6be`（D-A/D-B/D-C 合并后）｜标签 `[T-storage-budget-gate]` `[T-browser-tabs-c12]` `[T-defect-registry-colon]`
现场来源：外部对抗报告 `witness-1027/report-witness-1027.md`（W-01 / W-02 / W-03 / W-11 / W-12），机械复证记录 `witness-1027/VERIFY-1027.md`（12/12 BLIND_CONFIRMED）。

---

## 1. 现象：五条见证，四条静默、一条只是"看着干净"

| 见证 | 现场改动（一个 token） | 运行时后果 | 门/夹具当时 |
|---|---|---|---|
| **W-01** | `shouldNotify(now, lastNotifiedAt, lastNotifiedBytes, usage)` → `usage.appBytes` | 7 天静默窗内「暴涨 +25% 立即重发」分支变成 `sample >= sample + sample/4`，恒假 ⇒ 磁盘翻倍也不告警 | 112 门 / 347 夹具全绿 |
| **W-02** | `if (getNotificationChannel(CHANNEL_ID) != null) return` → `== null` | 新装不建渠道；Android 8+ 丢弃通知，而调用方已 `putLong(KEY_LAST_NOTIFIED_AT, now)` ⇒ 7 天窗被锁死，静默失效 | 同上 |
| **W-03** | `env["TMPDIR"] = "/tmp"` → `PRootKernel.getProotTmpDir(context).absolutePath` | 客机工具把临时文件写进宿主 cache 绝对路径，rootfs 内解析 ⇒ 再次逃出 /tmp 60min sweeper，永久堆积（10-27 实测 3.26 GiB） | 同上 |
| **W-11** | `FIELD_RE` 的 `[:：]` → `：` | 用半角冒号写的注册表条目（`- **症状**: …`）失配 ⇒ 症状/健康路径/seed 全丢，缺陷召回在需要它的地方哑掉 | `SandboxDefectRegistryTest` 15 例全绿（夹具只喂全角） |
| **W-12** | `scanOrphanBrowserTabFiles(liveIds)` → `emptySet()` | 所有**存活**会话的 `browser_tabs/<sid>.json` 被计入「可回收孤儿数据」；删除半用的是真 live ids ⇒ 横幅显示的垃圾永远删不掉，每次刷新都回来 | 门只扫 `SessionFileStore`，单测只测 store 方法 |

## 2. 结构性事实（比单条更重要）

按「文件 → 有几道门引用它」机械反查（`find scripts/scan -name '*_check.py' -o -name '*guard*.py' | xargs grep -l <file>`，排除 `test_scan.py`）：

| 文件 | 改动前 | 改动后 |
|---|---|---|
| `data/storage/StorageBudget.kt` | **0** | 1（`storage_budget_check` C1） |
| `notification/StorageBudgetNotifier.kt` | **0** | 1（`storage_budget_check` C2） |
| `sandbox/PersistentShell.kt` | 2（`claim_release_guard` / `marker_boundary`，都不看 env 赋值） | 3（+C3） |
| `ui/settings/StorageManagementScreen.kt` | 1（`sysinfo_state_thread`，只看线程/缓存，不看入参） | 2（+`browser_tabs_lifecycle` C12） |
| `tools/SandboxDefectRegistry.kt` | **0** | 0（本支用单测臂兜，见 §6 R2） |

即：本周新增的存储预算回路（采样/告警）、图像预算器、缺陷召回注册表**整条落在 112 门禁面之外**。W-12 更特殊——它不是"门写得差"，是**存储页手工复写了一遍 `scanOrphans`**（为省一次 media 树遍历），单测测的是 store 方法，门扫的是 store 文件，装配点两边都不覆盖。

## 3. 改动面

| 文件 | 变化 | 说明 |
|---|---|---|
| `scripts/scan/storage_budget_check.py` | **新增 846 行** | C1/C2/C3 三条判据 + 26 臂自测 + `<root>` / `--root` / `--selftest` 三种调用形态 |
| `scripts/scan/browser_tabs_lifecycle_check.py` | +277 / −1 | C12 判据（`ui/**` 派生遍历）+ 7 臂；**钉死的 `BROWSER_TABS_SELFTEST=PASS` 标记一字未动** |
| `src/android/app/src/test/java/.../tools/SandboxDefectRegistryTest.kt` | +30 | `parse_accepts_half_width_colon_field_lines`（W-11） |

**生产 `.kt` 0 行**（W-11 的生产代码本来就对，缺的是夹具宽度）。

## 4. 判据：锚「决策」而不是「拼写」

### 4.1 C1 —— 3rd 实参必须是**持久化基线**（不是当前采样）

`shouldNotify` 的签名参数名从同文件 `fun shouldNotify(` **派生**，命名/位置实参都支持。基线 key 不写死：文件里 `putLong(<KEY>, <采样>)` 的 key 就是"我记住的值"，`putLong(<KEY>, now)` 的时间戳自然落选。3rd 实参可以是 `getLong(<基线 key>, …)` 本身，也可以是 `val` 绑定（要求 `val`、声明在调用前、整条初始化式就是那次读）。

判据把「看起来对」的四种破口一起堵住：
- 直接传采样（W-01 原形）／藏在绑定后面传采样；
- 读错 key（读样例时间戳而不是通知字节）；
- **从读值派生**（`getLong(K,0L) + 1L`、`.coerceAtLeast(0)`）：保留读的拼写、交出别的值 —— 用"读必须就是整个表达式"堵；
- notify 分支压根不回写采样（没有基线可读）。

### 4.2 C2 —— 幂等守卫必须是**正向**（存在即早退）且建渠道可达

`ensureChannel` 体内定位 `getNotificationChannel(`，取包含它的 `if` 条件判定极性：`!= null` 或 `?.let { return }` 算正向；`== null` 直接判红并说明后果；其余条件**fail-closed**（极性不成立即红）。再判两条：守卫分支必须真的早退（`return`），且 `createNotificationChannel(` 必须在守卫**之后**且 `path_feasible`（`if (false)` 内的建渠道判红）。认 `?: run { 建 }` 这种等价形态。

### 4.3 C3 —— 客机 /tmp 必须是**客机字面量**，宿主侧必须是**宿主推导**（对称判据）

`env` 变量名从 `val <x> = …environment()` 派生（不写死 `env`）；`TMPDIR` / `TMP` 两条都必须存在且 RHS 是客机绝对路径字面量（允许一跳 `private const val GUEST_TMP = "/tmp"`）。出现 `absolutePath` / `getProotTmpDir` / `filesDir` / `cacheDir` / `context` / `File(` / `.path` 判宿主并点名锚点。**对称半边**：`env["PROOT_TMP_DIR"]` 必须仍是宿主推导 —— 这样"把几个临时目录统一成客机路径"这种看起来很自然的收拾动作也会被拦下（proot 本身跑在宿主上，它的临时目录必须是真目录）。

### 4.4 C12 —— 存储页的手工装配必须与删除半**同源**

遍历 `src/.../ui/**`（223 个 `.kt`，派生遍历不写死文件名），只看出现 `scanOrphanBrowserTabFiles(` 的文件：
- **C12a** 实参不得是常量空集/纯字面量集合（`emptySet()` / `setOf()` / `setOf("a")` …）= W-12 原形；
- **C12b** 同文件删除半 `reclaimOrphans(…)` 的实参必须与扫描半**规范形相等**：把本地 `val` 绑定递归内联（深度≤4，跳过 lambda 形参/`it`/`this`），再抹掉空白。这条判据是**符号名无关**的——当前树正是 `liveIds` 与 `live` 两个不同名字、同一个 `chatRepository.dao.listSessions().map { it.id }.toSet()`，逐字比名字会误红；比表达式才判得出"两半是否同源"；
- **C12c** 有扫描半却没有删除半 = 红（两个判据分开装配，没人保证一致）。

## 5. 验证读数（四层）

### L1 自测臂

| 门 | 臂 | 结果 |
|---|---|---|
| `storage_budget_check --selftest` | **26**（17 violating / 9 clean 等价形态） | `SELFTEST=PASS (26 arms)`，`STORAGE_BUDGET_SELFTEST=PASS` |
| `browser_tabs_lifecycle_check --selftest` | **83**（原 76 + C12 新增 7：3 clean / 4 violating） | `BROWSER_TABS_SELFTEST=PASS` + `BROWSER_TABS_RISK_NOTICE_AND_REMAP_SELFTEST=PASS` |
| `SandboxDefectRegistryTest`（沙箱 JVM，kotlinc 2.1.0 + JUnit 4.13.2 编译**真生产源码**） | 21 例（原 20 + 1） | `OK (21 tests)` |

clean 臂不是装饰：C1 认 `getLong(...)` 内联与全命名实参；C2 认 `?.let { return }` 与 `?: run { 建 }`；C3 认共享 `const val`；C12 认"两半名字不同但同源"与"两半全内联"。变异负控若把判据改成"比符号名"，这三条 clean 臂会立刻红。

### L2 反攻（在真树上打见证补丁 → 必须红在对应判据上 → `git checkout --` 还原）

```
W-01  shouldNotify(…, usage.appBytes, usage)   -> rc=1 RED on C1   line 183
W-02  getNotificationChannel(…) == null        -> rc=1 RED on C2   line 89
W-03a env["TMPDIR"] = …getProotTmpDir(…)…      -> rc=1 RED on C3   line 382
W-03b env["TMP"]    = …getProotTmpDir(…)…      -> rc=1 RED on C3   line 383
W-12  scanOrphanBrowserTabFiles(emptySet())    -> rc=1 RED on C12  line 286
W-12b reclaimOrphans(emptySet())               -> rc=1 RED on C12  line 286
还原后：storage gate rc=0 / browser_tabs rc=0；git status 干净
COUNTER_ATTACK=PASS (4/4)   COUNTER_ATTACK_W12=PASS (2/2)
```

W-11 的变异负控（把 `FIELD_RE` 的 `[:：]` 改回 `：`）：

```
kotlinc(生产 SandboxDefectRegistry.kt + 测试) -> java JUnitCore
  Tests run: 21, Failures: 1
  1) parse_accepts_half_width_colon_field_lines … kotlin.collections…single(): List is empty
```

**只有新臂红、其余 20 例绿** —— 正是"夹具宽度缺口"的形状。还原后 `git checkout --` → `OK (21 tests)`，生产文件 sha256 与提交态一致（`b800208d…`，`git diff --stat` 只剩测试文件 +30）。

**fail-closed 路径（手工验证，未固化为臂 —— 见 R6）**：

```
三个函数各自改名  -> C1/C2/C3 各返回 "… not found with a block body … — fail closed"
制造未闭合字符串   -> "C1: source not lexable (unterminated string literal at offset 7753) — refusing to fall back to raw scanning"
```

### L3 邻门

9 道 rc=0：`sysinfo_state_thread_check`（**只吃位置参数**，`--root` 会 rc=1 → 注册时注意形态）、`skill_restore_session_cleanup_check`、`i18n_check`（同上，位置参数）、`outcome_known_chain_check`、`deeplink_settings_routes_check`（同上）、`claim_release_guard`、`marker_boundary_check`、`four_way_sync_check`、`discarded_return_guard`。

### L4 全量

- `sh scripts/scan/scan.sh --jobs 3` → **Summary: 112 passed, 0 failed**（新门尚未注册，计数不变）。
- `test_scan.py` 单进程整跑在本机**撞 S28/ENOSYS 风暴**（`OSError: [Errno 38] Function not implemented`；同级另一会话同时在跑它自己的夹具循环，进程组共享）。按纪律最多重试 2 次后改用**逐函数独立进程**跑：**47/47 函数、347/347 例全绿**（其中 `test_da1025_gates` 8 例覆盖本支改动的 `browser_tabs_lifecycle_check` 标记断言；首轮超时的 `test_da1018_gates`(14) / `test_auto_approve_gates`(2) / `test_real_repo`(20) 在负载下降后单独复跑 rc=0，311+14+2+20=347）。
- 权威全量读数取 CI（下表）。

## 6. CI 读数

run **40499612598** / attempt **1** / **success**｜`build-apk.yml` @ `fix/w1027-storage-guards`｜窗口 `11:54:11Z → 12:10:45Z`（16m34s）
`head_sha` = `4e94012caa26af153b4946a607ac806f5096744f`，与本地 HEAD **逐字符一致**。

| 读数 | 值 |
|---|---|
| 步骤 | 27 步全部 success；唯一 2 个 skipped = `Move the rolling tag android-latest` + `Publish to Releases`（均 main 门控，分支上本就该跳） |
| job 日志 | 1,472,449 B，NUL 0 |
| `Summary: 112 passed, 0 failed` | **恰 1**（另有分批评测框线 25/16/44/19/30/16，scan 全量 = 112） |
| `ALL 347 CASES PASS` | **恰 1** |
| 编译错误 `^ e: ` | **0** |
| `Function not implemented`（S28） | **0** |
| 含 `FAILED` 的行 | 6 条，**逐条确认全是 PASSED 用例名的子串**（`… produces FAILED without calling provider PASSED` 之类） |
| 门头 | 112 个 `[N/112]`，编号与门名互异、序列 1..112 |
| W-11 的臂 | `SandboxDefectRegistryTest > parse_accepts_half_width_colon_field_lines PASSED` **恰 1**（新臂在 CI 有见证） |
| C12 生效 | `browser_tabs_lifecycle_check OK (… + C12 the UI-side manual orphan assembly uses the live ids and the SAME source as the delete half)` 出现在「real repo tree」用例里 |
| 全量单测 | 7,139 行 `PASSED` |
| `storage_budget_check` | **0 命中** = 尚未注册（本支按任务书不改 `scan.sh` / `test_scan.py`，注册说明见 §9） |

中间态另有一次 dispatch（run `40497206868`，sha `08192407`，C12 预过滤前）读数同形：`112 passed, 0 failed` ×1 / `ALL 347 CASES PASS` ×1 / `e: ` 0 / 6 条 FAILED 全为子串 / 门头 112 互异 —— 说明预过滤那笔性能改动没有改变门的判定面。

## 7. 残留（每条带触发条件 / 触发后方案 / 搁置理由）

- **R1（P2）`SandboxDefectRegistry.kt` 仍无门**：本支只加单测臂。触发 = 下次再动 `FIELD_RE`/`HEADING_RE`/`ENTRY_RE` 任一条正则；方案 = 把"注册表解析正则的字符类宽度"写成门（`[:：]` 这类双语标点必须成对出现）；搁置理由 = 修复门 P2（现有 15+1 例已能拦住本次形态的回落，加门属装置重复）。
- **R2（P2）C12 只认 `scanOrphanBrowserTabFiles` 一个装配点**：存储页还手工调了 `scanOrphanSessionDirs` / `scanMediaOnce`，它们同属"手工复写 `scanOrphans`"的家族。触发 = 下次动存储页 `reload` 的装配段；方案 = 把 C12 泛化成"任何 `scanOrphan*`/`scanMedia*` 调用的实参都必须是活 id 源且与删除半同源"；搁置理由 = 半径纪律（本次见证只有 tabs 那一条），且泛化前要先确认 `scanMediaOnce` 的入参形态。
- **R3（P2）C12 的 `_canonical` 只内联本地 `val`**：字段/参数/`by` 委托读不追。触发 = 有人把 live id 集合提到 ViewModel 字段；方案 = 接 `value_sources` 或补一层"字段初始化式"追踪；搁置理由 = 当前装配点在 composable 局部，跨层追踪属未触发路径。
- **R4（P2）C1 的基线 key 靠 `putLong(<KEY>, <采样>)` 派生**：若回写挪进 helper 函数（跨函数），门会判红（fail-closed 方向安全，但会误红）。触发 = 真的这么重构；方案 = 允许"回写在同一文件另一函数、入参是采样"；搁置理由 = 无此形态。
- **R5（P2）C2 不认识 `NotificationManagerCompat` 包装的等价写法**：触发 = 有人把 lookup 包一层；方案 = 放宽到"任一带 `getNotificationChannel` 的 wrapper 调用即视为 lookup"；搁置理由 = 现状直调。

- **R6（P2）fail-closed 路径只有手工验证、没有臂**：改名（3 条）+ 未闭合字符串（1 条）都实测红，但没写进 `--selftest`（写进去要再来一轮 CI）。触发 = 下次动 `_view` / `function_body` 抽取逻辑；方案 = 把这 4 条补成臂（`--selftest` 29 臂）；搁置理由 = 装置比例（P1 门，行为已实测，只差钉死）。

## 8. 未验证

- **真机行为**：三条判据都不改生产代码，故无行为面；但"W-01/W-02/W-03 若真的回退，用户会怎样感知"只在报告层推理，未做真机复现（改动本身没有行为差异，复现等价于手工打补丁装包，成本与收益不成比例）。
- **C12 的 UI 面**：`emptySet()` 版本的横幅在真机上会显示多少 KB 未测（例：44 个残留项 1.38 kB 是修好后的读数；W-12 形态会把存活会话的 tabs 文件一并算进去，具体数值取决于当时会话数）。

## 9. 注册说明（给主会话，本支不改 `scan.sh` / `test_scan.py`）

1. **`scan.sh` / `scan_blocks.sh`**：新增一块
   ```sh
   run_gate storage_budget_check.py "storage-budget loop: shouldNotify gets the PERSISTED baseline / ensureChannel guards on existence / TMPDIR-TMP on the guest path"
   ```
   形态：`storage_budget_check.py <root>`（位置参数与 `--root` 都支持；`scan_blocks.sh` 已统一传 root）。**追加会让 113 块的 `[N/112]` 分母全部变化**（`[N/113]`），属于机械更新，`test_scan.py` 不校验该计数（它只校验门名与标记）。
2. **`test_scan.py`**：加一组（建议紧跟 `DA1025_GATES` 之后的既有模板）
   ```python
   STORAGE_BUDGET_GATES = [
       ("storage_budget_check", "STORAGE_BUDGET_SELFTEST=PASS", (), (REPO_ROOT,)),
   ]
   def test_w1027_storage_budget():
       ...  # 与 test_da1025_gates 同形：--selftest 断言标记 + 真树 rc=0
   ```
   并在 `main()` 里加一行调用。**注意**：`storage_budget_check` 的 26 臂自证**不依赖任何外部夹具目录**，因此 `st_extra` 留空即可（不要传 `--root`，`--selftest` 与 `--root` 互斥）。
3. **`browser_tabs_lifecycle_check`**：无需注册，C12 已在门的 `main()` 内；`test_scan.py:2949` 的既有用例（标记 + 真树 rc=0）自动覆盖。
4. **性能**：C12 对 223 个 UI 文件先做 `SCAN_CALL not in src` 预过滤，实测 `check_ui_tree` **2.46 s → 0.36 s**，整门 ~0.8 s（预过滤前 ~2.9 s）。四 worker 并行下对 scan 总时长的影响在噪声内。
