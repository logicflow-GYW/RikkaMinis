# FIX-INCR1117-T5 · 子代理「恢复默认」与出厂默认相反（C-F02，P1）

## 1 身份链

| 项 | 值 |
|---|---|
| 来源 | `Incr_1117_C_Report.md` **C-F02**；判级 **P1**（用户点「恢复默认」，功能反而被关掉） |
| 基线 main | `949566ac`（worktree `git rev-parse --short HEAD` 起点一致） |
| worktree / 分支 | `/var/minis/shared/work/incr1117/wt5` · `fix/subagent-default-reset-1117` |
| 提交 | `437c1182` 生产 3 文件 + 测试 2 文件 +159/−4 |
| 绿 sha（三方逐字符） | 本地 HEAD == `git ls-remote` == CI `head_sha` = `437c11829e1ff3e39e71931ba99da9d9e3ec0224` |
| CI | run `44003934866` / **#3199** @ `437c1182`，5 job 全 success（attempt 1） |
| 合并状态 | **未合 main**（停止节点 = 本分支 CI 绿） |

## 2 缺陷与先证（三条事实逐条回源）

| # | 任务书断言 | 真树 | 结论 |
|---|---|---|---|
| 1 | `SubagentPrefs.isEnabled` 用 `getBoolean(KEY_ENABLED, true)` | `data/SubagentPrefs.kt:50` 修前正是该字面量 | **成立** |
| 2 | `ConfigBuiltins` 的 `PrefsBoolField(path="runtime.subagentEnabled")` 写 `defaultValue = true` | `config/ConfigBuiltins.kt:204` 修前 `defaultValue = true,` | **成立** |
| 3 | `RuntimeLimitsScreen` 的「Restore defaults」写 `setEnabled(context, false)` | 修前 `ui/settings/RuntimeLimitsScreen.kt:768` 正是 `SubagentPrefs.setEnabled(context, false)`，而同对话框内每个其它项都传 `*_DEFAULT` 常量（`:773` 起），注释本身写着「a default that moves in the data layer moves here too」 | **成立** |

**缺陷闭环**：默认值在 2026-11-17 从 OFF 翻到 ON（`SubagentPrefs` 的 KDoc 自己记着这次翻转），但 reset 那一行没跟着改 ⇒ 用户点「恢复默认」后，开关状态与全新安装相反：新装 = 开，恢复默认 = 关。这不是"文案错"，是写入了错误的值，重启后依然如此。

**全量入口扫描**（`grep -rn 'SubagentPrefs' --include='*.kt'`，逐条判）：

- 写入口**只有两处**：`RuntimeLimitsScreen:312`（开关行，写用户所选的 `it`）与 `:768`（reset，本次修复点）。
- 读入口：`SessionsOffloadHandler:297`（`send` 门控）、`ChatPromptAndTools:1394`（工具 schema 门控）、`ChatViewModel:1571`、`RuntimeLimitsScreen:85/194`。
- 注册表：`ConfigBuiltins:197-205`（唯一一处，本次改为读常量）。
- `grep -rn 'setEnabled(context, false)'` 修前仅命中 `RuntimeLimitsScreen:768` 与测试锚点 `PrefsListenerPrimeOrderTest:141`（后者正是"锚字面量"的陈旧抓手，见 §4）。
- 备份恢复走注册表 `defaultValue`，不另写死值（`grep -rn 'runtime.subagentEnabled'` = `ConfigBuiltins` + `ChatPromptAndTools:1405` 的提示词文本）。

## 3 修法（阶梯第 6 级：能一行/一处就不加结构）

一处常量，三处读取——没有新增函数、文件、配置项：

1. `SubagentPrefs`：新增 `const val DEFAULT = true`（KDoc 写明"三个调用点必须一致"），`isEnabled` 改 `getBoolean(KEY_ENABLED, DEFAULT)`。
2. `RuntimeLimitsScreen:768`：`SubagentPrefs.setEnabled(context, SubagentPrefs.DEFAULT)` —— 与该对话框内其它 40 个旋钮写 `*_DEFAULT` 的既有形状一致，UI 结构零改动。
3. `ConfigBuiltins:204`：`defaultValue = com.rikkaminis.app.data.SubagentPrefs.DEFAULT`（该文件用 FQN 引用，与相邻行的既有写法一致，不新增 import）。

**没做什么**（刻意）：不新增 `*_DEFAULT` 式的第二套命名（`SubagentPrefs.DEFAULT` 已是唯一真源）、不改 i18n（无新文案）、不碰 `scripts/scan/**`。

## 4 测试与变异负控

### 4.1 断言形状（禁止恒真匹配）

- `PrefsListenerPrimeOrderTest` 的 reset 断言原来是 `src.indexOf("SubagentPrefs.setEnabled(context, false)") >= 0` —— 这正是缺陷被藏住的原因：源码一改这个字面量就不存在，测试随之失明。
- 改为：以 `runtime_limits_restore_confirm_title`（确认对话框）为区间起点 → 取 `SubagentPrefs.setEnabled(` 的**第二个实参**（`secondArgument` 辅助，`parenBlock` 的切片）→ `assertEquals("SubagentPrefs.DEFAULT", arg)`。
- **同测试内负控**：把修前的字面调用 `"SubagentPrefs.setEnabled(context, false)"` 过同一个提取器，断言得到 `"false"` 且 `!= "SubagentPrefs.DEFAULT"` —— 证明这条判据能区分修前/修后。
- 新增 `data/SubagentPrefsDefaultTest`：
  - `assertEquals(true, SubagentPrefs.DEFAULT)` —— **期望值是字面量 `true`**，不从常量推导（常量翻转时测试必须红，而不是跟着动）。
  - 注册表形状：以 `path = "runtime.subagentEnabled"` 为锚 → `lastIndexOf("PrefsBoolField(", at)` 取构造器 → 平衡括号取块 → `defaultValue` 行必须逐字符等于 `defaultValue = com.rikkaminis.app.data.SubagentPrefs.DEFAULT,`；负控断言修前的 `defaultValue = true,` 不等于它。

### 4.2 三条变异（全红，且红的臂与预期精确对应）

| 变异 | 期望 | 实测 |
|---|---|---|
| M1 reset 写回字面量 `false` | `PrefsListenerPrimeOrderTest` 1 红 | ✅ `the reset must pass SubagentPrefs.DEFAULT … expected:<SubagentPrefs.DEFAULT> but was:<false>`；该文件 4 测试中**恰好 1** 红 |
| M2 `ConfigBuiltins` 写回 `defaultValue = true,` | `SubagentPrefsDefaultTest` 注册表 1 红 | ✅ `expected:<defaultValue = [com.rikkaminis.app.data.SubagentPrefs.DEFAULT],> but was:<defaultValue = [true],>` |
| M3 `const val DEFAULT = false`（真实翻转） | 仅字面量钉死那 1 条红（其余跟着常量走） | ✅ 6 测试中**恰好 1** 红：`the shipped sub-agent default is ON … expected:<true> but was:<false>` |

每次变异后都用 `cp` 快照还原并 `git diff --stat` 复核（未用 `git checkout -- .`，避免误伤同批改动）。

## 5 本地验证清单（实际跑过的）

| 项 | 命令/方式 | 结果 |
|---|---|---|
| 编译 | `kotlinc` 编 `android.content` 最小影子桩 + `SubagentPrefs.kt` + 两个测试类（`-no-stdlib -cp kotlin-stdlib-2.1.0:junit-4.13.2`） | 无 error |
| 跑测 | `JUnitCore SubagentPrefsDefaultTest PrefsListenerPrimeOrderTest`（cwd = worktree 根，供 `readRepoFile` 解析） | **OK (6 tests)** |
| 变异负控 | M1/M2 只需重跑（测试运行时读源码）；M3 需重编译 | 三条全红，见 §4.2 |
| 括号配平 | 脚本剥注释/字符串后统计 `() {} []` | 5 文件全 0 |
| 全量 scan / gradle | **未跑**（按任务书 §5.4） | — |

## 6 CI（run `44003934866` / #3199 @ `437c1182`）

| job | 结论 | job id | 关键证据 |
|---|---|---|---|
| scan-gate | success | `133774529677` | `Summary: 115 passed, 0 failed`；**夹具自测被跳过**（`scripts/scan/** untouched in this push — skipping the fixture self-test`）——本批未动门脚本，故「CI 绿」不含夹具证据，按既有纪律如实标注 |
| lint | success | `133774529905` | — |
| build-apk | success | `133774529911` | 产物 + `Inject build version` |
| unit-tests | success | `133774529952` | `BUILD SUCCESSFUL in 5m 24s`；**9300 条 `PASSED`**；日志中 4 条含 `FAILED` 的行逐条核过，全是**测试名子串**（`…produces FAILED without…` 等），真实失败 0 |
| publish | success | `133778119710` | `Move the rolling tag android-latest` / `Publish to Releases` 两步 **skipped**（main 门控生效，分支构建不污染 release） |

本支测试在 CI 日志中逐条命中：

```
SubagentPrefsDefaultTest > the registry registration passes the constant, not a literal PASSED
SubagentPrefsDefaultTest > the shipped sub-agent default is ON PASSED
PrefsListenerPrimeOrderTest > the runtime-limits reset writes defaults as constants, not state (dv22-B1 + live-apply) PASSED
```

## 7 残留与未修登记

| 项 | 判级 | 处置 |
|---|---|---|
| `sandbox/offload/SessionsOffloadHandler.kt:288` KDoc 仍写「OFF (default) returns a typed error envelope … inert until the user flips the switch」 | P2（文档漂移，无行为影响） | **未改**：不在本任务允许文件集内（`sandbox/**`），且与代码注释无功能后果。触发条件：下次因其它原因打开该文件时顺路改一行 |
| `ui/chat/**`、`debug/**`、7 语言 `strings.xml`、`scripts/scan/**` | — | **零触碰**（任务书禁碰项） |
| 用户已显式关闭过开关的设备 | — | 保持 `false`：`DEFAULT` 只用于"从未选择过"的种值与 reset，与 `SubagentPrefs` 既有契约一致 |

## 8 用户可见变化 + 复现步骤

**变化（就一句）**：设置 → Agent Runtime → 恢复默认后，子代理开关回到**开**，与全新安装一致（过去是恢复默认即关掉）。

**复现（修前）**：设置 → Agent Runtime → 把「Sub-agent / cross-session dispatch」打开 → 点「Restore defaults」→ 确认 → 开关变灰（关）；冷启动后仍是关。
**复现（修后）**：同路径 → 开关保持开。

**改这行代码改变了什么用户行为**：只改变 reset 写入的值，不改任何默认值的来源、不改 UI、不改提示词。
