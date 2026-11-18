# FIX-2D-1119-2 — 提示词与工具描述：GLOBAL.md 可写性三处收口 + 记忆淘汰规则冲突

## 1 范围与身份链

| 项 | 值 |
|---|---|
| 基线 main | `b82b2963`（开工时 worktree `/var/minis/shared/work/r1119/prompt` 干净、HEAD 逐字符一致） |
| 分支 | `fix/prompt-memory-truth-1119`（worktree `r1119/prompt`） |
| 审计范围 | 累积窗口 `2a8d746d`（11-16 收口点）→ `289b7b59`，本支只处理其中 `2_Prompt_Memory_Report.md` 的两条 P1 |
| 允许触碰 | `tools/MemoryTools.kt`、`tools/AgentTools.kt`、`assets/skills/memory-hygiene/SKILL.md`、`data/repository/MemoryRepository.kt`、`SystemPromptSegmentOrderTest.kt` |
| 禁碰 | `scripts/scan/**`（零触碰，本支不是门支）、其他支的域、任何 `strings.xml` |
| 提交 | `9ed4928d`（实现 + 三条回归用例，5 文件 +185/−10）→ 本报告（仅 `fix-reports/`） |
| 门/夹具总数 | 不变（0 新建门、0 改建门） |

**这改变什么用户行为？** 唯一的用户可见行为变化是：模型不再因为「工具描述说 GLOBAL.md 只读」而拒绝执行**用户已经批准**的 GLOBAL.md 编辑、把用户推回设置页手工改；以及模型不再把技能文档里的「不必等用户即可删」当成删改用户全局记忆的授权。都是**已有承诺的兑现**，没有新增能力。

## 2 逐条先证（回树核实，非报告转述）

### F-01（P1）三处「read-only」措辞

| # | 位置 | 真树原文（修复前） | 判定 |
|---|---|---|---|
| 1 | `MemoryTools.kt:29` | `… Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).` | **成立**（逐字符命中） |
| 2 | `AgentTools.kt:140` | `"Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).",` | **成立**（逐字符命中） |
| 3 | `MemoryRepository.kt:439` | `return "Global memory (GLOBAL.md — read-only, user-maintained). Treat these as background context, …` | **成立**（逐字符命中） |
| 附 | `MemoryRepository.kt:13` | KDoc `- GLOBAL.md: read-only for agent, user-maintained via Settings` | **成立**（不注入，顺手同步） |

- 注入路径核实：`MemoryTools.memoryWriteToolDefinition()` 的 description 被 `memoryWriteOpenAIDefinition()` 直接复用（`getString("description")`），所以第 1 处同时覆盖 Anthropic / OpenAI 两条格式；`AgentTools.memoryWriteDefinition()` 是 Gemini 侧的同义变体。两者都在「记忆开启」的每次请求里随工具 schema 下发。
- 第 3 处是 `loadGlobalMemoryFragment()` 的**注入头**：GLOBAL.md 只要非空就每次请求带一遍，且它直接压在「把这些当 standing instructions」的声明上方——三处里信号最强的一处。
- 与系统提示词的冲突核实：`ChatPromptAndTools.kt` 记忆段已写 `propose an edit and apply it only after the user approves`、文件系统清单行同句（1118C B 支已收口），与上述三处相反。
- 全仓搜索：`grep -rn 'read-only, user-maintained' src/` 修复前命中 3 处（两工具 + 注入头），测试里无该字符串断言（`grep` 零命中）；`scripts/`、`docs/` 零命中 ⇒ **改这三处不会撞任何既有断言或门**。

### F-02（P1）`memory-hygiene/SKILL.md` 淘汰规则与 Authority 冲突

- 真树原文（修复前 `:38-43`）：

```markdown
## 淘汰（我主动做，不必等用户）

- 这条规则已经写成了扫描门或测试 → 从 GLOBAL **删掉**（门是新家）。
```

- 同文件 `:15` 写「全局 `GLOBAL.md` | **提议，不擅写**」，`:18` 写「**我提议，用户批准**」；系统提示词 `ChatPromptAndTools.kt:1387` 的 Authority 段把 `under \`/var/minis/skills/\`` 下的 SKILL.md 列为**唯一**豁免之一 ⇒ 模型读到技能后按它行事、可绕过提议制直接改 GLOBAL.md。**成立**。

**证伪检查**：两处都在现代码里精确命中，无一条失真；报告给出的行号（`MemoryTools.kt:29`、`AgentTools.kt:140`、`MemoryRepository.kt:439`、`SKILL.md:38-43`）全部与真树一致。

## 3 修法（停在第 6 级：一行字面量替换 + 段落改写）

| # | 位置 | 新措辞 |
|---|---|---|
| 1 | `MemoryTools.kt:29` | `… Keep entries concise and general-purpose. GLOBAL.md is user-owned: propose an edit and apply it only after the user approves.` |
| 2 | `AgentTools.kt:140` | 同上一句 |
| 3 | `MemoryRepository.kt:439` | `Global memory (GLOBAL.md — user-owned; edit only with the user's approval). Treat these as background context, …` |
| 4 | `MemoryRepository.kt:13` | KDoc：`- GLOBAL.md: user-owned for agent — edit only after the user approves a proposal` |
| 5 | `SKILL.md:38-43` | `## 淘汰（我主动提议，仍需用户批准）`，四条全部改成「**提议** …，用户批准后再动手」；保留「主动提议、不必等用户**提醒**」这层意思，去掉「不必等用户**批准**」；frontmatter `version` 1.0.0 → 1.0.1 |

**不扩写**：工具描述是每请求成本。字节账（UTF-8）：第 1、2 处各 **+29 B**（54 → 83），记忆开启时两处合计 **+58 B/请求**；第 3 处 **+20 B**，且只在 GLOBAL.md 非空时注入；KDoc 与技能正文不计每请求成本（技能按需 `file_read`）。

**共用函数 caller 普查**：`memoryWriteToolDefinition()` 的 caller 只有 `memoryWriteOpenAIDefinition()`（description 直接复用）与装配处；`AgentTools.memoryWriteDefinition()` 只在工具表里注册一次。三处替换均无第二调用点需要同步。

## 4 证据

### L1 本地沙箱（kotlinc + JUnit，非 CI）

装置：`shared/work/r1119/prompt-jvm/{run.sh,RunTests.java,mutate.py,ci_logs.py}`（在工作树**之外**，不会被 `git add -A` 扫走）。`SystemPromptSegmentOrderTest.kt` 只依赖 JUnit + `java.io`，单文件 `kotlinc -no-stdlib -cp …/kotlin-stdlib-2.1.0.jar:junit-4.13.2.jar:hamcrest-core-1.3.jar` 编译，`nice -n 10`，cwd = 工作树根。

**`RUN 18 FAILURES 0 / RESULT OK`**（既有 15 条一字未改，新增 3 条）。

### L2 分支 CI（停止节点）

- run **#3277**，`workflow_dispatch`，run id `44355846863`，结论 `success`。
- `head_sha` = `9ed4928d0fca8f9a35d60a1172f69646f112891d`，与本地 `git rev-parse HEAD`、`git ls-remote origin fix/prompt-memory-truth-1119` **三方逐字符一致**。
- 五个 job 全 `success`：`scan-gate` / `build-apk` / `lint` / `unit-tests` / `publish`。
- **unit-tests**：日志 **9753 行 PASSED**，**0 真 FAILED**；日志里 6 行含 `FAILED` 字样全部是 PASSED 的用例名子串（`RealAgentAdapterSkeletonTest > slot denied produces FAILED without calling provider PASSED` 等），无一条真失败。
- **三条新用例逐条 PASSED**（每条 1 命中）：

```
SystemPromptSegmentOrderTest > theGlobalMemoryInjectionHeaderDoesNotCallItReadOnly PASSED
SystemPromptSegmentOrderTest > theMemoryWriteToolDescriptionsDoNotCallGlobalMemoryReadOnly PASSED
SystemPromptSegmentOrderTest > theMemoryHygieneSkillCallsPruningAProposal PASSED
```

- **scan-gate**：`Summary: 115 passed, 0 failed`。日志含 `scripts/scan/** untouched in this push — skipping the fixture self-test`（本支未动门，**此绿不含夹具跑过的证据**，如实记录）。
- **publish**：`success`，但日志止于 Setup，**无 `Move the rolling tag` / `Publish to Releases` 步骤组**——分支受 main 门控，未产生任何 release 资产。

## 5 变异负控（6 臂，全部 RED-PRECISE）

每臂：快照字节 → 替换 → 跑全套 18 条 → `finally` 还原 → **sha256 前后一致**（不用 `git checkout --`）。变异前本体已 commit（`9ed4928d`）。

| 臂 | 变异 | 红的用例 | 期望 | 结论 |
|---|---|---|---|---|
| M1 | `MemoryTools.kt` description 退回 `read-only (user-maintained via Settings)` | `theMemoryWriteToolDescriptionsDoNotCallGlobalMemoryReadOnly` | 同 | **RED-PRECISE** |
| M2 | `AgentTools.kt` description 退回旧句 | 同上（同一断言，两条路径共用） | 同 | **RED-PRECISE** |
| M3 | 注入头退回 `read-only, user-maintained` | `theGlobalMemoryInjectionHeaderDoesNotCallItReadOnly` | 同 | **RED-PRECISE** |
| M4 | SKILL.md 淘汰段退回「我主动做，不必等用户」 | `theMemoryHygieneSkillCallsPruningAProposal` | 同 | **RED-PRECISE** |
| M5 | 注入头保留 user-owned 但**去掉批准**（`user-owned; edit only…` → `user-owned`） | `theGlobalMemoryInjectionHeaderDoesNotCallItReadOnly` | 同 | **RED-PRECISE** |
| M6 | 工具描述保留 user-owned 但**去掉批准**（`GLOBAL.md is user-owned.`） | `theMemoryWriteToolDescriptionsDoNotCallGlobalMemoryReadOnly` | 同 | **RED-PRECISE** |

- M1/M2 是**刻意保留的重叠臂**：同一条断言守两个注入点，两臂各红一次才能证明两处都被钉住（不是「一条测试同时兜住」的粗网漏检）。
- M5/M6 证明断言钉的是**批准要求本身**，不是「不再出现 read-only」——只把措辞改成用户所有但不写批准，仍红。
- 收尾：`BASELINE-AFTER-RESTORE: OK (18 tests)`，还原后 sha256 与提交态一致。

## 6 未修与取舍（P2 一律不碰，只登记）

| ID | 判级 | 内容 | 处置 |
|---|---|---|---|
| F-03 | P2 | 系统提示词允许「用户说记到全局」时**直写**，而 `read skill memory-hygiene` 只挂在「提议前」→ 直写路径绕开准入四问与去重 | 登记，不改（属 `ChatPromptAndTools.kt`，非本支域） |
| F-04 | P2 | `memoryOn = false` 时静态文件系统清单行仍指引「提议编辑 GLOBAL.md（见上方记忆段）」，而上方是 DISABLED 段 | 登记，不改（同上，非本支域） |
| F-05 | P2 | Authority 对 `/var/minis/skills/` 与 GLOBAL.md 的豁免只在提示词层，工具层无写入确认门控、无软链来源校验 | 登记（属工程改造，需用户拍板） |
| F-06 | P2 | `Tool call style`「直接调用别问」与 `Behaviour`/`Authority`「猜错会改结果必须先问」对带副作用调用存在局部张力 | 登记 |
| F-07 | P2 | 段序门漏测 `skillFragment` 的相对位置 | 登记（属测试覆盖面，非本支 5 文件） |

**本支范围外的残留（重要，主会话/用户需知）**：设备侧技能副本 `/var/minis/skills/memory-hygiene/SKILL.md` 的淘汰段**仍是「我主动做，不必等用户」**。它不随 APK 走、是设备侧文件，本支禁碰仓库外文件，故未改。**下次更新设备技能副本时必须同步这份措辞**，否则 APK 内的 skills 与设备侧 skills 两份内容会继续互相矛盾（同族：技能文件双份的同步风险）。

## 7 文件清单

| 文件 | 改动 |
|---|---|
| `src/android/app/src/main/java/com/rikkaminis/app/tools/MemoryTools.kt` | 1 行（description 末句） |
| `src/android/app/src/main/java/com/rikkaminis/app/tools/AgentTools.kt` | 1 行（description 末句） |
| `src/android/app/src/main/java/com/rikkaminis/app/data/repository/MemoryRepository.kt` | 2 行（注入头 + KDoc） |
| `src/android/app/src/main/assets/skills/memory-hygiene/SKILL.md` | 淘汰段改写 + frontmatter 版本 |
| `src/android/app/src/test/java/com/rikkaminis/app/ui/chat/SystemPromptSegmentOrderTest.kt` | +175（3 条用例 + 2 个 fail-closed 取文本辅助函数） |
| `fix-reports/FIX-2D-1119-2-prompt-memory.md` | 本报告 |

合计 5 个生产/测试文件 + 1 报告；**零新增文件、零删除文件、零 i18n、零 `scripts/scan/**` 触碰**。

## 8 复现

```sh
# 本地（沙箱，无需 gradle / 无需全量 scan）
cd /var/minis/shared/work/r1119/prompt-jvm && sh run.sh          # RUN 18 FAILURES 0 / RESULT OK
cd /var/minis/shared/work/r1119/prompt-jvm && python3 mutate.py  # 6 臂 RED-PRECISE + BASELINE-AFTER-RESTORE: OK

# 只跑某一条
sh run.sh theMemoryHygieneSkillCallsPruningAProposal

# 断言面（不需要 CI）：
grep -rn 'GLOBAL.md is read-only\|read-only, user-maintained' src/android/app/src/main/   # 应为 0 命中
```

CI 复核：`gh api repos/logicflow-GYW/RikkaMinis/actions/runs?branch=fix/prompt-memory-truth-1119` → #3277 / `44355846863`，`head_sha` 与分支 tip 逐字符一致。
