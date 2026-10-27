# FIX-1027-ENVVAR-RESTORE — env var 恢复死锁（D-A）

分支 `fix/envvar-restore-deadlock`｜基线 main `62caedb4c7add956148a6746ee1eb04d5fda8cd9`｜标签 `[T-envvar-restore-deadlock]`
现场来源：2026-10-27 换签名重装事故，`/var/minis/shared/work/restore-1027/DEFECTS-1027.md` §D-A。

---

## 1. 现象

换签名重装后（Keystore 换绑 → `EncryptedSharedPreferences` 里 21 个 env var 的值全丢），连续两次导入备份：

```
14:55  applied=... envVars=0  skipped=21   （无密钥备份）
14:58  applied=... envVars=0  skipped=21   （含密钥备份，hadSecrets=true）
        每条：env var "XXX": already exists, left as-is
```

把 21 个空壳条目手工删掉再导同一份备份：

```
15:01  applied=109 providers=30 envVars=21 skills=32 memoryFiles=91 ... skipped=0
```

值恢复成功且与备份逐字节一致（sha 一致）。**值一直在备份里，是导入路径拒绝写入。**

## 2. 根因

`ConfigBackup.import` Stage 4（`:1258`）对「已存在的 key」整条 `continue`：

```kotlin
if (envVarRepo.isDuplicateKey(key)) {
    skipped.add("env var \"$key\": already exists, left as-is")
    continue
}
```

这与本仓「restore-is-restore（合并而非跳过）」的家族规则相悖——provider（`ProviderRepository:2738/2950`）与 model group（`ConfigBackup.kt:1108`）都已改成合并，env var 是这一族里唯一没跟着改的一支。

死锁链（关键在于它**自己锁住自己**）：

1. 换签名 → 加密 prefs 不可解 → 所有值读出来是 null；
2. 第一次恢复是无密钥备份 → 按设计把 21 个 key 建成**空壳**（保元数据、让用户填值）；
3. 之后任何**含密钥**备份都命中 `isDuplicateKey` 被跳过 → 值永远进不来。

「建空壳」和「跳过已存在的 key」各自都合理，合起来就是一条闭环：恢复功能把恢复自己所需的路径堵死了。

## 3. 改动面

`git show --stat` = 3 文件 +339/−4：

| 文件 | 变化 | 内容 |
|---|---|---|
| `backup/EnvVarRestorePlan.kt` | 新增 118 行 | 逐条决策的纯函数（无 Android 类型）：`EnvVarRestorePlan` + `planEnvVarRestore()`，KDoc 写清与 provider backup-wins 的**刻意差异**及理由 |
| `backup/ConfigBackup.kt` | +46/−4 | 只动 Stage 4 重复键分支：`isDuplicateKey → continue` 换成「取 existing → 规划 → 按 plan 调 `update()`」，创建路径（`add` + `restored without value` 行）原样保留 |
| `test/.../ConfigBackupEnvvarsAndOverlayTest.kt` | +179 | 6 条新臂（5 条行为臂 + 1 条 Stage 4 形状臂，含 2 个 in-test 负控），类内 `@Test` 6 → 12 |

分支语义（决策见任务书）：

| 本地值 | 备份值 | 行为 |
|---|---|---|
| 空（null 或 `""`） | 非空 | **写入备份值**（本次要解的死锁），计入 `envVarsImported` |
| 非空且不同 | 非空 | **保持本地**，报一行 `local value kept (backup differs) — replace it manually if the backup is right` |
| 非空且相同 | 非空 | 什么都不做（幂等，无 skip 噪声） |
| 空 | 空 | 什么都不做（无密钥备份不写值；空壳创建路径不变） |

note/group 是明文元数据（导出不加密、UI 不掩码）：本地为空则从备份补齐（`trim` 后比较），本地非空一律不覆盖。

**刻意不照抄 provider 的 backup-wins**：provider 的配置在 UI 里逐字段肉眼可比对、可再编辑；env var 的 value 是掩码字段，被旧备份值静默覆盖后用户既看不出存的是哪一个、也拿不回被覆盖的那个（恢复前快照是唯一退路）。一条可见的 skip 行可回滚，一次不可见的覆盖不可回滚。

**半径纪律**：只修触发路径。D-B（WebDAV 自举死锁）、D-C（大小门单位不一致）、D-D（memoryFiles 无条件覆盖）三条按任务书不动，已登记在 `DEFECTS-1027.md` / backlog。

## 4. 判据与测试

`@Test` 共 12 条，其中本次新增 6 条（期望值全部写死字面量，不从被测常量推导）：

1. `empty local value takes the backup value` — `localValue ∈ {null, ""}` × 备份带值 → `plan.value` 等于备份值、`writesValue=true`、无 report。
2. `live local value is kept when the backup differs` — 本地 `sk-current` / 备份 `sk-stale-from-backup` → `plan.value == null`、`touchesAnything=false`、report **逐字符等于**那段英文。
3. `equal values are idempotent` — 两侧相同 → 无写入、无 report。
4. `secret free backup writes nothing` — 备份无值 × 本地 ∈ {null, "", "sk-kept"} → 不写值、不 report（空壳创建路径由第 6 条形状臂钉住）。
5. `note and group are backfilled into blanks only` — 本地空 note / 空白 group → 补齐（`"  primary key  "` → `primary key`）；本地非空 → 不动。
6. `stage four routes existing keys through the planner and keeps the create path` — 源码形状：Stage 4 段落必须含 `planEnvVarRestore(`、`envVarRepo.add(key, value, note, group)`、`restored without value — re-enter it`，且**不得**再出现 `already exists, left as-is`；两个 in-test 负控（把 planner 换回 `isDuplicateKey(`、把 `add` 换成 `Unit`）必须被判红。

## 5. 验证读数

**L1 单测（沙箱真跑生产源码）** — `probe-a/`
- 生产文件字节一致：`probe-a/src/EnvVarRestorePlan.kt` sha256 **与仓库文件相同**（`3605059eef135044…`，脚本现算比对 `identical=True`）。
- 测试臂来源：从**已提交的测试文件**第 3 节机械抽取（7155 字节 / 6 个 `@Test`）+ 同名 helper 花括号抽取，无手抄。
- `java ... JUnitCore com.rikkaminis.app.backup.EnvVarRestorePlanSandboxTest` → **OK (6 tests)**。
- 覆盖面声明：整个测试类编不动（另外 6 条臂断言 `ConfigBackup.BACKED_UP_SCOPES` / `ConfigBuiltins`，需要 Android + AGP），本地只跑新增的 6 条；全文件编译由 CI 承担。

**L1 变异负控（6/6 红，且红在对应臂上）** — `probe-a/mutate.py`（改**生产文件副本**后重跑同一批臂）

| 变异 | 结果 | 变红的臂 |
|---|---|---|
| M1 本地空值分支改成永不写入（原死锁保留给空值） | RED | `empty local value takes the backup value` |
| M2 值相同也报 differs | RED | `equal values are idempotent` |
| M3 本地非空时 backup-wins（provider 规则） | RED | `live local value is kept…`, `equal values are idempotent` |
| M4 note/group 覆盖本地非空 | RED | `note and group are backfilled into blanks only` |
| M5 note/group 补齐整段删掉 | RED | 同上 |
| M6 Stage 4 整条跳过回来 | RED | `stage four routes existing keys…` |

**L2 改动面自查**
- `git show --stat`：生产 `.kt` 2 个（1 新增 + 1 改动 46 行）+ 测试 1 个，无其它文件。
- 冻结面零触碰：`scripts/scan/scan.sh`、`scripts/scan/test_scan.py`、`_kotlin_*.py` 均不在 diff 内（本分支也没改任何门）。
- 同族扫描（fix-completeness）：`rg -n "isDuplicateKey|already exists|left as-is" src/android/app/src/main/java/com/rikkaminis/app/backup/`
  - `ConfigBackup.kt:1267` 只剩**注释**里的历史说明；`isDuplicateKey` 在 `backup/` 内 **0 处代码使用**（唯一调用点就是本次改掉的 Stage 4）。
  - 其他命中全部无关（`WebDavClient.kt:280` HTTP 405 注释、`AutoBackupManager.kt:200` 远程推送注释）。
  - env var 的**写入点全仓唯一**：`ConfigBackup.kt` Stage 4 的 `add`/`update`。`OpenMinisBackupCompat.convertEnvVars` 只是把 OpenMinis 归档转成同一份 payload（不写库），`AutoBackupManager` 走同一 `importConfig`。⇒ 本族没有第二个需要同步修改的入口。
- 行长：新增行全部 ≤ 100 列（文件内超长行均为改动前既有）。

**L3 静态门（本地）** — `nice bash scripts/scan/scan.sh`（未触发 S28 ENOSYS）：
`║ Summary: 112 passed, 0 failed`，112 道门全绿（新增文件未被任何门判红，含 `envvar_config_group_check`）。

**L4 CI** — 见 §5.1（回填）。

### 5.1 CI 读数（回填）

| 项 | 读数 |
|---|---|
| run | **#2780 / id `40466819219`**，`build-apk.yml` @ `fix/envvar-restore-deadlock` |
| 结论 | `conclusion = success`（`2026-10-27T07:23:22Z` → `07:47:08Z`，23m46s） |
| head_sha | `ca30677cc1ce15283453f0336ee077541cf0fbe6`，与本地 `git rev-parse HEAD` **逐字符一致** |
| job | `122064126981`，26 步中 **skipped = 2**：`Move the rolling tag android-latest`（main 门控）+ `Publish to Releases`（main 门控）——分支上这两个按设计跳过 |
| 门 | `Summary: 112 passed, 0 failed` 恰 **1** 条；门头 `━━━ [N/112] ━━━` **112** 条、编号 1..112 互异且有序 |
| 夹具 | `ALL 347 CASES PASS` 恰 **1** 条（任务书写的是 344，那是 R3 之前的读数；当前树 347） |
| 编译 | `^ e: ` **0** 条 |
| FAILED 行 | 6 条，逐条确认全是 `PASSED` 用例名的子串（如 `...produces FAILED without calling provider PASSED`）——无真失败 |
| 沙箱症状 | `Function not implemented` **0** 条（本次未触发 S28） |
| 日志 | 1,464,883 B / NUL 0 |
| 本支测试 | `ConfigBackupEnvvarsAndOverlayTest` 在 CI 里 **12 条全 PASSED**，含新增的 6 条（逐条名字核过：`empty local value takes the backup value` / `live local value is kept when the backup differs` / `equal values are idempotent` / `secret free backup writes nothing` / `note and group are backfilled into blanks only` / `stage four routes existing keys through the planner and keeps the create path`）⇒ 沙箱里只跑过其中 6 条这件事，不再有覆盖面缺口 |

日志取法（经代理时 `urllib` 会 401，用 curl）：

```sh
curl -s  -H "Authorization: Bearer $GITHUB_TOKEN" \
  https://api.github.com/repos/logicflow-GYW/RikkaMinis/actions/runs/40466819219
curl -sL -H "Authorization: Bearer $GITHUB_TOKEN" \
  https://api.github.com/repos/logicflow-GYW/RikkaMinis/actions/jobs/122064126981/logs -o ci-2780.log
```

## 6. 残留（每条带触发条件 / 触发后方案 / 搁置理由）

- **R1 值比较用的是原始字符串，不是仓库 `sanitizeValue` 之后的形态。**
  触发条件：备份里的 value 含非可打印控制字符（0x00–0x1F，tab 除外）——iOS 粘贴可能带出这类标量，仓库写入时会把它们过滤掉。
  触发后方案：把 `EnvVarRepository.sanitizeValue` 提成 `internal` 并在 planner 里比较净化后的形态（或直接对比「写入后读回」）。
  搁置理由：需要改 `EnvVarRepository`（多调用方共享类），半径超出本次「Stage 4 一段」；且后果只是多一行无害的 `local value kept` 提示，不会写错值。
- **R2 `envVarsImported` 只统计「写入了值」的条目，纯 note/group 补齐不计。**
  触发条件：有人拿日志里的 `envVars=N` 去核对元数据变更条数。
  触发后方案：加独立计数器（如 `envVarsMetadataFilled`）并进 summary 行。
  搁置理由：summary 行是既有日志契约（用户/脚本都在读），加字段属另一个决策；当前没有消费方需要这个数。
- **R3 差异值保持本地后，没有「用备份值覆盖」的一键入口。**
  触发条件：用户真的想用备份里的旧值（例如换回旧机器上的凭据）。
  触发后方案：在恢复报告页给该行一个「replace with backup value」动作。
  搁置理由：需要 UI + 产品语义（是否要二次确认掩码字段）；本次只保证「不静默覆盖 + 可见」。
- **R4 恢复报告页只显示 `skipped` 前 8 条（D-E，既有问题）。**
  触发条件：差异条目 > 8 条时，超出部分用户看不到。
  触发后方案：报告页分页/展开，或落一份常驻的恢复报告文件。
  搁置理由：D-E 已在缺陷清单登记为「观察，非缺陷主张」，属呈现面，半径外。
- **D-B / D-C / D-D 未处理**（WebDAV 自举死锁 / 大小门单位 / memoryFiles 无条件覆盖）。
  触发条件、方案、搁置理由见 `DEFECTS-1027.md` 与 backlog；任务书明确要求不顺手修。

## 7. 未验证

- **真机端到端**：没有在真机上跑一次「21 个空壳 + 含密钥备份 → 导入」的恢复。沙箱无 Android 运行时；`EnvVarRepository.update()`（`EncryptedSharedPreferences` 真写）与 Stage 4 应用段（10 行直调）只有源码形状臂与代码评审，没有运行时证据。CI 只保证编译 + 单测 + 112 门。
- 真机上「备份里有值、本地也有值且不同」那条报告的呈现（第 8 条截断之外的可见性）未验证。
- 安卓 `org.json` 与沙箱 Maven `org.json` 的 optString 行为差异对本改动无影响（本改动不解析新字段），未单独跑对拍。

## 8. 复现命令

```sh
# 沙箱装置（生产文件按 sha256 校验一致后再编译）
cd /var/minis/shared/work/restore-1027
python3 probe-a/build.py wt-a/repo            # 生成 probe-a/src/*.kt + 打印 sha256 identical=True
L=/tmp/libs
kotlinc probe-a/src/EnvVarRestorePlan.kt probe-a/src/EnvVarRestorePlanSandboxTest.kt \
  -cp "$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar:$L/kotlin-stdlib-2.1.0.jar" -d probe-a/out.jar
cd wt-a/repo && java -cp "/var/minis/shared/work/restore-1027/probe-a/out.jar:$L/kotlin-stdlib-2.1.0.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar" \
  org.junit.runner.JUnitCore com.rikkaminis.app.backup.EnvVarRestorePlanSandboxTest

# 变异负控（6 组，全部应变红）
python3 /var/minis/shared/work/restore-1027/probe-a/mutate.py wt-a/repo

# 静态门
cd wt-a/repo && nice bash scripts/scan/scan.sh   # → Summary: 112 passed, 0 failed

# CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push --branch fix/envvar-restore-deadlock
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch \
  --repo logicflow-GYW/RikkaMinis --workflow build-apk.yml --ref fix/envvar-restore-deadlock
```

**一句话「这改变了什么用户行为」**：换签名/换设备重装后，再导入一份含密钥的备份，21 个 env var 的值会**真的被写回去**（日志 `envVars=21 skipped=0`），不再需要用户手工删空壳再重导；而本地已有活值且与备份不同时，导入改为**保留本地并明确告知**，不再静默覆盖掩码字段。
