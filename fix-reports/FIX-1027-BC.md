# FIX-1027-BC — 备份自举死锁（D-B）与导出/导入上限单位不一致（D-C）

- 分支：`fix/backup-bootstrap-size`，基线 main `62caedb4c`（2026-10-27 release 签名轮换后的 tip）
- 任务书：`/var/minis/shared/work/restore-1027/fix-tasks/TASK-1027-BC.md`
- 事故档案：`/var/minis/shared/work/restore-1027/DEFECTS-1027.md`（D-B / D-C 两节）
- 停止节点：**分支 CI 绿**（到点即结束，不合并 main）

---

## 1. 摘要

两个缺陷都是「恢复它自己所需的凭据 / 判定它自己的尺子」这一类**自举陷阱**：

- **D-B**：`webdavConfig` 整段被密钥门（`includeSecrets`）罩住，而这份配置存在
  `EncryptedSharedPreferences` 里 —— 换签名重装必然丢。于是「想恢复远程备份，得先有远程
  凭据」，而凭据正是刚丢的东西。修法：url / username / path 属于**配置**不是凭据，永远随
  备份走；只有 password 受密钥门控制；导入侧做**合并**而不是覆盖，绝不把「备份没有密码」
  变成 `save(blank)`（那会把用户刚重输的密码清掉）。
- **D-C**：`MAX_PAYLOAD_BYTES` 在导出侧按 `String.length`（UTF-16 码元）比，在导入侧按
  UTF-8 **字节**比（SAF 读上限 + `import` 的门）。中文 payload 的 bytes/chars ≈ 1.08 ⇒
  71,427,943 B / 66,092,088 chars 的备份**导出成功、永远导不回**（本次事故现场，最后靠
  手工裁到 17.2 MB）。修法：`utf8Length` 成为唯一尺子，导出预算按字节打包，导入门按字节判，
  `export()` 由「最后一刻拒绝」改为「按序降级」（chat ×0.7 最多 4 轮 → 丢 artifacts →
  才抛错，且错误文案点名是**哪一段**超的、能怎么办）。

「导出成功 ⇒ 一定读得进」这条契约，对 chat 超限（现实里唯一会超的部分）现在由**同一把尺子
在两个方向上校验**保证；骨架自身超限的剩余缝隙见 §6。

## 2. 现场证据（修前，全部回本树核过）

| 面 | 证据 |
|---|---|
| D-B 导出 | `buildPayloadObject` 里 `if (includeSecrets && webDavConfig != null)` 整段门控，无密钥自动备份里 `webdavConfig` 整段缺失 |
| D-B 导入 | `import` 只读 `url/username/password/path` 直接 `onWebDavConfig(...)`；`WebDavConfigStore.save` 契约明写「空密码 = 清除已存密码」 |
| D-B 配置存放 | `WebDavConfigStore` 基于 `EncryptedSharedPreferences` ⇒ 换签名重装即丢，与 D-A（env var 无密钥空壳）同源 |
| D-C 导出 | `export()`：`if (payload.length > MAX_PAYLOAD_BYTES)` —— 码元 |
| D-C 导入 | `import()`：`if (json.length > MAX_PAYLOAD_BYTES)` —— 码元；但上层 `readSafBytes(..., MAX_PAYLOAD_BYTES)` 与 `querySafSize` 全是字节 |
| D-C 预算 | `ChatBackupBudget` 全员 `.toString().length`，`budgetTotalChars` 名为 chars、与 `MAX_PAYLOAD_BYTES`（字节）直接相减 |
| 事故读数 | 71,427,943 B vs 66,092,088 chars（比值 1.0807，正是中文的 UTF-8 膨胀率） |

## 3. 修法

### 3.1 一个单位（D-C）

- `ConfigBackup.utf8Length(CharSequence): Long` —— 遍历字符计费（1/2/3 字节，代理对 4），
  **不** `toByteArray()`：给 70MB 字符串再分配一份拷贝正是流式导出要规避的 OOM 形状。
  孤立代理项按 3 字节计而 Android 编码器只写 `?`（1 字节）⇒ 只会**多算**、不会少算。
- `ChatBackupBudget`：`budgetChars/skeletonChars/sessionChars/messageChars` 全部改为
  `*Bytes`，骨架、每个会话、每条消息一律用 `utf8Length` 计费；文件头写清「本文件所有计费
  都是 UTF-8 字节，因为读侧是字节」。
- `buildSections` 的骨架测长同样改字节；`budgetTotalBytes = documentCapBytes - SAFETY_MARGIN_BYTES`。
- `import()` 的门改为 `documentExceedsPayloadCap(json)`（内部 `utf8Length > cap`），
  与 SAF 读上限同一把尺子。

### 3.2 降级而不是拒绝（D-C）

`export()` = `buildWithinCap(MAX_PAYLOAD_BYTES) { cap, includeArtifacts -> ... }`：

1. 每次尝试都用 `cap(×scale)` 驱动 chat 打包器（所以 chat 会**真的**被裁短，而不是事后切字符串）；
2. 超限时先判断「chat 是不是罪魁」（`chatBytes > 0` 且 `nonChat + margin < cap`）；
   是 ⇒ ×0.7 重来，最多 4 轮（0.7^4 ≈ 24%，够吸收任何估算漂移且不会自旋）；
3. 不是（骨架自己就超）⇒ 直接跳到丢 artifacts（唯一「按设计可选」的大段），只丢一次；
4. 仍超 ⇒ 抛错，文案含**逐段字节明细**（`describeSections`，惰性 lambda，成功路径不付代价）
   + 可操作出路（删/缩 skill、裁 memory 日志、缩短聊天历史窗口、排除 artifacts）。
5. `includeArtifacts=false` 时 artifacts 段为空，导入侧本就把它当「旧版备份」，无需配套改动。

### 3.3 WebDAV 自举（D-B）

- `buildWebDavConfigSection(includeSecrets, webDavConfig)`：url/username/path 永远写，
  `password` 仅密钥导出写。**无密钥导出不写 `password` 键**（而不是写空串）——导入侧以
  「键缺失」为信号。
- `resolveWebDavRestore(backup, existing)` 三档：
  1. 备份密码非空 ⇒ 备份赢（与 provider / model group 的 restore-is-restore 同族规则）；
  2. 备份无密码 + 本机有 ⇒ **沿用本机密码**，url/username/path 取备份，`skipped` 里给
     「密码沿用本机现有配置（备份未包含密钥）」；
  3. 备份无密码 + 本机也没有（重装现场）⇒ 写入三个非密字段，`skipped` 里给
     「webdav 密码需重新填写（备份未包含密钥）」。
- `import()` 新增可选入参 `existingWebDavConfig`；调用点（`BackupSettingsScreen`）在
  `Dispatchers.Default` 内再用 `Dispatchers.IO` 从 store 读当前值，避免用可能仍为 null 的
  Compose 状态。
- **为什么不像 provider 那样直接 backup-wins**：`WebDavConfigStore.save` 把空密码解释为
  「清除」，而网盘密码在界面上是预填的（用户清空 = 明确要清）。让「备份没带密码」走清空语义
  ＝ 静默销毁用户刚重输的凭据，且用户无从发现。所以这里必须三分支，而不是两个分支。

### 3.4 文案（D-C 第 3 条）

`backup_import_too_large` 七个语言文件全部补「缩短聊天历史窗口（或关掉）后重新导出」的出路，
沿用既有键、既有占位符，不新增 key。

## 4. 验证

### L1 沙箱 JVM（真源码影子编译，不是复刻件）

影子装置 `probe-bc/`：`extract_shadow.py` 从 `ConfigBackup.kt` **按成员原样抽取**纯函数
（`utf8Length` / `documentExceedsPayloadCap` / `buildWebDavConfigSection` /
`resolveWebDavRestore` / `buildWithinCap` / `oversizeMessage` / `describeSections` /
`ExportSections` / `BuiltPayload` / 常量），`ChatBackupBudget.kt` 原文件直接编译，
`WebDavConfig` 原样抽取，`android.util.Log` 打桩。

```
ConfigBackupWebDavGateTest      OK (9 tests)
ConfigBackupPayloadSizeTest     OK (12 tests)
ChatBackupBudgetTest            OK (14 tests)   ← 既有 14 臂改名后在字节语义下全绿
```

关键臂（挑重点）：

- `a document the char ruler accepts is rejected by the byte ruler`：683 个「中」= 683 chars
  / 2049 bytes，cap 1024 —— 旧尺子放过、新尺子拦住（就是事故形态本身）；
- `a cjk message a char budget would keep is dropped by the byte budget`：100 个「中」的
  消息 = 248 chars / 448 bytes，预算 350 —— 字符尺子会留下，字节尺子必须丢；配
  `the same body in ascii still fits the same budget` 作**对照臂**，证明红的是尺子不是尺寸；
- `utf8Length matches the byte encoding, not the char count`：以
  `toByteArray(UTF_8).size` 为独立 oracle（含代理对）；
- 阶梯三臂：能 fit 一次就返回 / chat 可救时按 0.7 收 4 轮后**有界**放弃（6 次 build）/
  骨架已超时**跳过 chat 轮**直接丢 artifacts / 永不返回超限文档且产物过导入门；
- WebDAV 9 臂：无密钥导出无 `password` 键、密钥导出四字段、无配置不写段、
  重装只回三字段+提示重填、**本机密码永不被清**、备份密码优先、显式空密码等价缺失、
  空 url 什么都不做、以及 `export and import agree on the section shape`（导出段过一遍
  JSON 线再喂给导入决策，防两半漂移）。

### L2 变异负控（3 组，12 条臂转红，改动前后 sha 一致复绿）

| 变异 | 红臂 |
|---|---|
| A：`utf8Length` 改回 `value.length`（字符尺子） | 6 条（含边界臂、oracle 臂、两条 CJK 预算臂） |
| B：`ChatBackupBudget` 计费改回 `.length` | 2 条（CJK 丢臂 + 前沿臂）；**ASCII 对照臂保持绿**（正是设计意图） |
| C：`buildWebDavConfigSection` 退回整段密钥门 | 4 条（无密钥导出段、密码不被清、round-trip、重装三字段+提示） |

还原后基线全绿，`git status` 干净。

### L3 静态门

- `sh scripts/scan/scan.sh` → **Summary: 112 passed, 0 failed**（本树实跑）。
- 备份域 8 门单独复跑全 rc=0：`backup_streaming_export_check`、`backup_remap_rollup_check`、
  `envvar_config_group_check`、`provider_import_id_roundtrip_check`、`parts_json_funnel_check`、
  `openminis_compat_field_parity_check`、`mcp_oauth_lifecycle_check`、`provider_ua_parity_check`。
- `test_scan.py`（344 夹具）：本地跑到 **300 例全 ✅ 后** 在 `test_da1025_gates` 的子进程里
  停摆（沙箱 S28：无输出无 CPU，被 stall-guard 杀）——环境层，与本支改动无关（本支不碰
  `scripts/scan/**`）；全量证据交 CI。

### L4 CI

见 §5（待回填）。

## 5. CI 记录

（待回填：run id / attempt / head_sha 三方一致 / skipped 列表 / 日志逐条读数）

## 6. 残留与登记（不在本支修）

1. **P2 流式导出仍是字符封顶**：`BackupStreamWriter.CapEnforcingWriter` 按 `write(CharSequence)`
   的字符数封顶，而 `import` 按字节判。chat 超限这条路已被预算修好（预算按字节 ⇒ 文档
   字符数 ≤ 字节数 ⇒ 不会误触发），但**骨架自身超 64MiB 字节**时流式导出仍会写出一个导入不
   了的文件（非流式 `export()` 已有降级）。修法方向：`CapEnforcingWriter` 改按
   `utf8Length` 累加。**本支按任务书不动它**（改它会牵动 `backup_streaming_export_check`
   的 4 条判据与其自测），触发条件 = 下次动 `BackupStreamWriter` 或再次出现「导出成功导不回」。
2. **P2 `OpenMinisBackupCompat.MAX_OUTPUT_CHARS`** 仍是「字符」上限（与
   `MAX_PAYLOAD_BYTES` 自述对齐但单位不同），转换输出可能 64M chars / 69MB bytes ⇒ 导入侧
   拒绝。本支文件面不含该文件，登记不修。
3. **P2 `chatTruncated.budgetBytes` 语义**：写入的是 `MAX_PAYLOAD_BYTES`（文档上限），
   而不是实际 chat 预算（`documentCapBytes - SAFETY_MARGIN`）。名字已叫 bytes（修前就是），
   数值一直是「文档上限」，降级轮次下会与实际预算不符。信息字段，不影响判定。
4. **P2 文档漂移**：`BackupStreamWriter` 头注释仍写「`export()` refuses a payload over
   MAX_PAYLOAD_BYTES」，而 `export()` 现在是优先降级、只有骨架超限才拒。与本支文件面无关，
   顺手不修（半径纪律）。
5. **观察**：`export()`（非流式）在当前生产代码里**没有调用点**（三处生产路径都走
   `exportToWriter`）。所以 §3.2 的降级阶梯现实收益是「契约与防线」，不是「修好了一条线上路径」；
   真正让线上不再产出超限文件的是 §3.1 的字节预算。

## 7. 未验证面（需要真机）

- 真机「设置 → 备份 → 导出」产出的文件能否在另一台设备导入（字节预算的端到端）；
- 无密钥自动备份恢复后，WebDAV 三个字段是否真的落到新装设备的 store 里、`skipped` 是否
  显示提示行；
- 大备份导出时降级路径是否触发（现行实现下预期不触发）。

## 8. 文件清单

改动面 13 文件 +976/−104（生产 3 + 文案 7 + 测试 3）：

| 文件 | 变化 |
|---|---|
| `backup/ConfigBackup.kt` | +417（字节尺子 / 降级阶梯 / webdav 拆门与合并 / 导入字节门） |
| `backup/ChatBackupBudget.kt` | 51（全部计费改字节，符号改名） |
| `ui/settings/BackupSettingsScreen.kt` | 32（读当前 webdav 配置并传入；注释与单位对齐） |
| `res/values*/strings.xml` ×7 | 2 each（超大导入文案补出路） |
| `test/.../ConfigBackupWebDavGateTest.kt` | 新增 193 行 / 9 臂 |
| `test/.../ConfigBackupPayloadSizeTest.kt` | 新增 325 行 / 12 臂 |
| `test/.../ChatBackupBudgetTest.kt` | 48（随 API 改名 + 测长改 utf8Length） |

装置（不进仓）：`/var/minis/shared/work/restore-1027/probe-bc/`（影子提取器、变异脚本、
红臂名单）。
