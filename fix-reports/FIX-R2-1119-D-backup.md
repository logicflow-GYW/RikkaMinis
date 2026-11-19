# FIX-R2-1119-D — backup（F-22 / F-20 / F-21）

## 1 结论速览

| ID | 先证 | 修法 | 行为证据 |
|---|---|---|---|
| F-22 | 成立；基线对三个私网前缀域名返回 true，buildUrl 放行 HTTP | 严格四段 ASCII IPv4、逐段 0..255 后才判 RFC1918/127；保留 localhost、*.localhost、::1/[::1] 例外，其他域名不信任 | 三组字面量安全测试、私网实际 buildUrl 放行、公网形似域名拒绝；M01–M04 精确翻红 |
| F-20 | 成立；基线真实调用块遇 fatal 仍留下 restoredOk=true、成功摘要 | 调用处用 fatal 决策结果同时赋 restoredOk/restoredMsg；非 null（包括空串）失败，复用本地化回滚提示并附原因 | fatal/clean/空异常消息三组；真实赋值块 JVM 回放；M08–M14 精确翻红 |
| F-21 | 成立；基线禁用→启用仍 false，启用→禁用可成功 | 导入后比较 imported.isEnabled 与备份 desired，不同时写回 desired | 两个方向及相同状态；真实 Stage-5 调用块回放；M05–M07 精确翻红 |

三条均成立，无证伪。安全默认不依赖域名解析，不以体验为由放开 HTTP 凭据出口。

## 2 环境与边界

- worktree：`/var/minis/shared/work/r2rest/backup`。
- 分支：`fix/r2-backup-1119`；基线 `8771018e122205b7bf9db90e2a7ec83180616e3e`。
- 代码提交：`5d6877bd34c0170b4c6f66466e1b22d973e4d15b`、`c29158929f56b91898f3a6a77f13cca3c26440b8`。
- 生产只改 `backup/WebDavClient.kt`、`backup/ConfigBackup.kt`、`ui/settings/BackupSettingsScreen.kt`；新测试四文件。代码差异七文件 **+308 / −15**。
- 未改 `scripts/scan/**`、`provider/**`、`sandbox/**`、`webapp/**`、`data/**`、`ui/chat/**`。对 SkillRepository 只读核查导入保留开关和 setEnabled 行为，没有写入。
- 装置在 worktree 外：`/var/minis/shared/work/r2rest/backup-jvm/`，不混入代码提交。

## 3 先证与最小修法

### F-22（优先处理）

基线 `isLoopbackOrPrivateHost` 对 `127.`、`10.`、`192.168.` 直接 startsWith，对 `172.` 只读第二段。基线整份 WebDavClient 在 JVM 上编译，传入 `127.0.0.1.nip.io`、`10.example.com`、`192.168.mycloud.com`，分类均 true，HTTP URL 均可 build。只构建 URL，不查询 DNS、不发送请求。

现先验证恰好四段、每段 1–3 个 ASCII 数字且 0..255，再判断 127/8、10/8、192.168/16、172.16/12。例外只认 localhost、*.localhost 和 IPv6 回环两种输入形式；不把任意方括号剥掉后再当 IPv4/域名。没有 InetAddress.getByName 或 DNS 推断。HTTP 门仍在 buildUrl 入口、凭据构建之前。

安全测试使用 `literal-user` / `literal-fake-password`，Dns 桩一旦被调用即抛 AssertionError。测试不真实连接任何公网端点。CI/GitHub 与 Maven 的运维访问不属于安全测试的请求路径。

阶梯停在第 3 级：字符串拆分、数字字符及范围检查直接用标准库；不添依赖、网络探测或配置开关。

### F-20

`ConfigBackup.import` 的 stage catch 把 Throwable 记到 ImportResult.fatal 并返回；屏幕原先只有抛异常才设置 restoredOk=false。基线调用块回放证明 `fatal="literal-disk-error"` 仍产出成功 flag 和 `Restored 3 providers and 1 groups.`。

新增无 Android 依赖的 `restoreNotifyOutcome`，由原调用处传入真实 `report?.fatal`、现有成功摘要及现有本地化 `backup_done_fatal`（含快照回滚指引）。结果直接流入已有 restoredOk / restoredMsg，通知出口仍使用既有失败标题 `webdav_notify_title_failed`，正文替换为回滚提示和 fatal 摘要。没有新增持久状态、翻译 key 或通知接口。

判据严格为 fatal != null；空异常消息也不能被视为恢复完整。成功路径保持原摘要，异常 catch 路径仍为失败。importReport 仍保留供原有回滚对话框使用。

阶梯停在第 2 级：复用已存在的本地化回滚资源、失败通知出口；仅抽一个可独立测的纯决策。

### F-21

只读核查 SkillRepository：替换已有技能时 copy 保留 isEnabled，新建默认 enabled。基线 Stage-5 只有 `if (!isEnabled) setEnabled(false)`，故本地禁用+备份启用无法恢复。使用基线真实 Stage-5 开关块配内存仓库桩回放，确认两个方向不对称。

新增 `skillEnableWriteTarget(current, backupSaysEnabled)`：相同返回 null，不同返回备份值，调用处将返回值传给 `setEnabled(imported.id, desired)`。归档/正文导入在 imported 之后共用同一写点，不需要分别补丁。

阶梯停在第 6 级：一条比较式；不改仓库、不加表字段，不改变旧备份缺少 isEnabled 时默认 true 的语义。

## 4 定向测试与变异负控

本地：

- `baseline.sh`：**OK (3 tests)**，直接见证基线三条错误行为。
- `run.sh`：**OK (15 tests)** = 四个入库测试类 12 条 + 当前真实调用块回放 3 条。
- 四个入库类：WebDavPrivateHostSecurityTest / SkillEnableRestoreTest / BackupRestoreNotifyOutcomeTest / BackupR2WiringTest，每类 3 条。
- 回放提取真实 Stage-5 与通知赋值块，不重新手写该判断；Android Context/仓库依赖用最小内存桩隔离。只编译 WebDavClient 整文件和两段真实纯函数，不编译整个 Android 闭包。

变异对真实生产文件施加，finally 按原字节恢复并 SHA-256 对拍，禁止 git checkout 恢复。每臂要求 **JUnit exit=1 且失败类集合恰等于预期**；编译/抽取错误不算通过。

| 臂 | 缺陷 | 结果 |
|---|---|---|
| M01 | 恢复基线前缀门 | RED-PRECISE |
| M02 | 删除 octet 0..255 检查 | RED-PRECISE |
| M03 | HTTP 凭据门恒跳过 | RED-PRECISE |
| M04 | 将合法私网也全部拒绝 | RED-PRECISE |
| M05 | 开关仅能禁用 | RED-PRECISE |
| M06 | 开关仅能启用（反向棘轮） | RED-PRECISE |
| M07 | 写点强制 false | RED-PRECISE |
| M08 | fatal 恒成功 | RED-PRECISE |
| M09 | clean 恒失败 | RED-PRECISE |
| M10 | 空 fatal 当成功 | RED-PRECISE |
| M11 | 删除回滚提示 | RED-PRECISE |
| M12 | 删除 fatal 原因 | RED-PRECISE |
| M13 | report.fatal 不传入 | RED-PRECISE |
| M14 | 忽略结果 success flag | RED-PRECISE |

合计 **14/14**。结果文件 `backup-jvm/mutations.json`，逐臂日志 `M01.log`…`M14.log`。

装置曾自行暴露一处不合格见证：extract.py 最初要求提取后仍出现特定决策拼写，导致 M05 在 JUnit 前被拦截。这种抽取错误不计 RED；移除该语义前置、保留边界检查后完整重跑，以上 14 条均为行为断言精确失败。

## 5 验证范围与完备性

- 本地没有跑全量 scan.sh、test_scan.py、Gradle。
- F-22 生产分类定义和 buildUrl 唯一使用点均检查；未改网络请求和 Authorization 组装。
- F-21 两种内容导入分支汇入同一个 imported 后开关写点；两方向及两个相同状态都测。
- F-20 当前返回块、已有异常 catch、最终通知标题/正文均检查；同一个结果同时控制两条通知信号，不留只改正文/只改标题的半修。
- `git diff --check` 通过，提交树干净。报告提交只允许 fix-reports 路径，依靠非报告差异为空证明代码未漂移。

## 6 局限与未验证项

- 无真机通知栏视觉验证；没有在真实磁盘制造半写恢复、也没有对真实备份/技能数据库进行破坏性实验。证据是基线源码、真实调用块回放、纯函数/接线测试、变异与 CI。
- `runCatching { skillRepo.setEnabled(...) }` 的既有错误吞并策略未扩展修改，本支只修启用位单向恢复。其返回成功/失败报告策略属于另一个决策面。
- 不实现域名 DNS 解析后信任私网；普通域名即便实际解析到 LAN，也必须使用 HTTPS。localhost 及 *.localhost 是任务书指定例外，不是对任意域名豁免。

## 7 CI

代码 `c29158929f56b91898f3a6a77f13cca3c26440b8` 的分支 CI 已返回 success，API head_sha 与本地代码提交、远端分支逐字符一致。unit-tests checkout 日志也明确检出同一 SHA。

- `unit-tests`：日志 **10,209 条 JUnit PASSED / 0 FAILED**，`BUILD SUCCESSFUL`。新增四类各 **3 PASSED / 0 FAILED / 0 SKIPPED**，共 12 条逐条见证；本地额外调用块回放 3 条不混算为入库 CI 测试。
- `scan-gate`：**Summary: 115 passed, 0 failed**。日志明确 `scripts/scan/** untouched in this push — skipping the fixture self-test`；门在云端跑过，夹具自测未跑，本支没有拿全绿冒充夹具证据。
- `build-apk`：success，日志含 `BUILD SUCCESSFUL`。
- `lint`：首轮在 `generateDebugAndroidTestLintModel` 解析 hamcrest-integration 1.3、hamcrest-library 1.3、javawriter 2.1.1 三个 jar 时失败；同名 Maven URL 独立范围请求均返回 HTTP 206。没有修改构建文件或代码，仅对已完成运行调用 rerun-failed-jobs；重试 lint success、`BUILD SUCCESSFUL`，其余已成功 job 结果保留。
- `publish` job success，但 Move the rolling tag / Publish to Releases 两步均 skipped（分支 main 门控），没有发布主线资产。
- 原始证据：装置目录 `ci/runs.json`、`ci/jobs.json`、`ci/final-evidence.txt` 及 unit-tests / scan-gate / lint / build-apk 四份日志。代码不变的环境重试不产生新代码提交。

网络小插曲：推送首试遇当前配置 DNS 无响应；仅对一次 git 命令使用已查询的 GitHub 地址作 curloptResolve 覆盖后推送成功，未改 resolv.conf、用户代理变量或持久 git 配置。后续原 DNS 恢复，API 读取与 CI 正常。

## 8 交付

不合 main。报告独立提交后仅 push，**不再 dispatch CI**；用 `git diff c29158929f56b91898f3a6a77f13cca3c26440b8..HEAD -- . ':(exclude)fix-reports'` 验证非报告差异为空。
