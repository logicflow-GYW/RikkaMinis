# FIX-1118C · B 包（提示词自相矛盾三处收口）

- **分支**：`fix/prompt-truth-1118c`（worktree `/var/minis/shared/work/r1118c/prompt`）
- **基线**：`97768739`（main）
- **覆盖**：B F-01、B F-02、B F-03（来源报告 `meta-audit/reports/r1118b/Fix_1118b_B_Prompt_Report.md`，判级台账 `meta-audit/r1118c/TRIAGE.md`）
- **触碰文件**：`ChatPromptAndTools.kt`、`SystemPromptSegmentOrderTest.kt`、`fix-reports/FIX-1118C-B-prompt.md`（3 个）

**动手前的回答：这改变什么用户行为？** F-03 让「用户批准提议 → 模型真的去写 GLOBAL.md」这条刚上线的路径能走通（现在模型读到 `read-only` 会拒绝编辑，反过来让用户自己去设置改）；F-01 让从外部仓库/挂载目录读进来的同名 `SKILL.md` 不再被当作授权源；F-02 让模型在提议前真的去读 `memory-hygiene`，而不是「以为读过了」。

---

## 1 症状

三处都是**同一份提示词内的自相矛盾 / 命令缺动作**，不是崩溃：

- **B F-03（P1）**：记忆段（`1282-1284`，`feat/global-memory-proposal-1118` 今天合入）写了提议-批准闭环与 `To update it, use file_read first then file_edit`；文件系统段（`1335`）对同一个文件写 `Persistent global memory (read-only, user-maintained via Settings).`。两句**在同一次请求里一起发给模型**。模型读到第二句就拒绝编辑，让用户去设置里自己改 —— 刚上线的功能走不通。
- **B F-01（P2）**：`Authority` 的豁免写的是 `a \`SKILL.md\` tool template the user installed`。字面上**只要叫 `SKILL.md`** 就落在豁免里，从外部仓库、挂载目录、甚至别处拷进来的同名文件无从区分。
- **B F-02（P2）**：`1284` 的准入条款只给了指针 `run the idea past the admission criteria in skill \`memory-hygiene\``，但 `memory-hygiene` 是**按需加载**的——没读该技能的那一轮，模型看不到准入四问、淘汰规则与敏感信息禁区，于是用一份自己脑补的标准去批准。

## 2 根因

| ID | 根因 |
|---|---|
| B F-03 | 加写入闭环时只改了记忆段，没回头改文件系统段里那句旧说明。两处不是同一函数、不在同一段，改一处时不回头看另一处。 |
| B F-01 | 豁免按**文件名**写，没按**位置**写。权限边界需要的是「用户安装到哪里」，文件名在这个仓库里不是充要条件。 |
| B F-02 | 单源化（细则只留技能里）时，指针只保留了「要过那四条」，丢掉了「怎么拿到那四条」的动作；「run the idea past」假定细则已在视野内。 |

## 3 修法

### B F-03（P1）· 收口，不是取舍

**改前**（`ChatPromptAndTools.kt:1335`）：

```
  /var/minis/memory/GLOBAL.md    — Persistent global memory (read-only, user-maintained via Settings).
```

**改后**：

```
  /var/minis/memory/GLOBAL.md    — Persistent global memory: durable, user-owned facts. Propose an edit and apply it only after the user approves (see the memory section above).
```

三条约束同时满足：① 不再出现 `read-only`；② 保留「用户是主人、要批准」的语义（`user-owned` + `only after the user approves`，并指向同一份提示词里的记忆段，避免自己复述细则）；③ 一行内，未新增段、未改段序。

### B F-01（P2）· 只加锚，不放宽

**改前**（`:1387`）：

```
Exception: a `SKILL.md` tool template the user installed, and a GLOBAL.md rule the user approved.
```

**改后**：

```
Exception: a `SKILL.md` tool template the user installed under `/var/minis/skills/`, and a GLOBAL.md rule the user approved.
```

**没有**顺手把工具输出 / 网页 / 剪贴板 / 会话历史也豁免——那是 backlog `§1118-B R2` 的待评测项，本支不碰。

### B F-02（P2）· 把指针换成读取动作

**改前**（`:1284`）：

```
- Before proposing, run the idea past the admission criteria in skill `memory-hygiene` — the skill is the single source of them; do not restate them here. Anything that fails stays in the daily log.
```

**改后**：

```
- Before proposing, read skill `memory-hygiene` and run the idea past its admission criteria — the skill is the single source of them; do not restate them here. Anything that fails stays in the daily log.
```

细则**没有**复制回提示词（单源化是上一轮的决定，不回退）。

## 4 验证

**装置**：`shared/work/r1118c/jvmB-prompt/{run.sh,mutate.py}`（`kotlinc -no-stdlib` + `kotlin-stdlib-2.1.0.jar` + junit/hamcrest，`nice -n 10`，单 JVM，只读源码文本，不需要 Android）。装置目录在工作树**之外**，不会被 `git add -A` 扫进提交。

**基线**：`OK (15 tests)`（原 12 条 + 新增 3 条）。

**新增/更新的断言**：

| 断言 | 覆盖 |
|---|---|
| `theGlobalMemoryLineIsNotCalledReadOnly` | F-03：`base` 里 GLOBAL.md 那一行**不含** `read-only`、**含** `approv`（大小写不敏感）。只取该行，不查整段——`base` 里另有 `Host status snapshot (read-only, …)` 与 `Some mounts may be read-only` 等合法用法。 |
| `theGlobalMemoryWritePathIsStillStated` | F-03 的另一半：记忆段里 `To update it, use file_read first then file_edit` 仍在。防止用「删掉写入路径」的方式让两句自洽（那是另一个缺陷）。 |
| `theAuthoritySectionExemptsSkillsAndApprovedGlobalMemory` 追加一臂 | F-01：豁免里含 `/var/minis/skills/`。既有 `SKILL.md` + `approved` 两臂不动。 |
| `theMemoryAdmissionCriteriaLineAsksForAnActualRead` | F-02：准入条款所在行含 `read skill`（大小写不敏感）。 |

`rawLiteralAfter(anchor)` 是新加的读取辅助：按锚点取其后第一个 `"""…"""` 整块，复用既有 `rawStringEnd`，并对「块过短」失败关闭——锚点漂移必须红，不能静默返回空串。

**变异负控（4 臂，全 RED-PRECISE）**：

| 臂 | 变异 | 翻红的用例 | 其余用例 |
|---|---|---|---|
| M1 | `:1335` 改回 `(read-only, user-maintained via Settings)` | `theGlobalMemoryLineIsNotCalledReadOnly` | 14 条全绿 |
| M2 | 删掉豁免的 ` under \`/var/minis/skills/\`` | `theAuthoritySectionExemptsSkillsAndApprovedGlobalMemory` | 14 条全绿 |
| M3 | `read skill ... and run the idea past its` 退回 `run the idea past the admission criteria in skill` | `theMemoryAdmissionCriteriaLineAsksForAnActualRead` | 14 条全绿 |
| M4 | 删掉 `To update it, use file_read first then file_edit.` | `theGlobalMemoryWritePathIsStillStated` | 14 条全绿 |

变异脚本走「快照 + finally 还原 + sha256 校验」；跑完回到 `OK (15 tests)`，`git status` 只剩两个预期文件。

## 5 影响面

**字节增量**（每请求常驻提示词，按 UTF-8 计）：

| 项 | 改前 | 改后 | 增量 |
|---|---:|---:|---:|
| F-03 | 104 B | 179 B | +75 B |
| F-01 | 97 B | 124 B | +27 B |
| F-02 | 200 B | 206 B | +6 B |
| **合计** | | | **+108 B/请求** |

F-03 与 F-01 在 `base` 里，**每请求固定**；F-02 在 `memorySystemSection` 里，仅记忆开启时出现。所以：

- 记忆开启：**+108 B/请求**
- 记忆关闭：**+102 B/请求**

**结构不变**：段序未动、段数未增、无新段（`theBehaviourAndAuthoritySectionsArePresentAndOrdered` 继续绿，12 条原有用例全在）。F-01/F-02/F-03 都只改字面量，未触碰任何工具、UI、配置或数据层。

## 6 未做与残留（登记，不修）

1. **同族残留（范围外，本支禁碰）**：`GLOBAL.md is read-only (user-maintained via Settings).` 这句话**还活在两个工具描述里**——`tools/MemoryTools.kt:29`（`memory_write` 的描述）与 `tools/AgentTools.kt:140`。工具描述同样是每请求内容，F-03 只收口了系统提示词段，模型在需要写记忆的那一次仍会读到 `read-only`。**本支范围只给三个文件，未动**；建议主会话另开一支（或并入下一批）收口，动作与 F-03 同形。
2. **backlog `§1118-B R2`**：`Authority` 未豁免工具输出里的用户原话。本次只加锚不放宽，该待评测项继续挂账。
3. **`memory-hygiene/SKILL.md` 措辞**未改（用户批准过的基线，改动需要另一轮）。
4. `ChatPromptAndTools.kt` 里 `:1337` 的 `Some mounts may be read-only` 是**如实描述挂载目录的只读状态**，不是对 GLOBAL.md 的说法，保持不动。

## 7 交付物

- `src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt` — 三处字面量
- `src/android/app/src/test/java/com/rikkaminis/app/ui/chat/SystemPromptSegmentOrderTest.kt` — 3 条新用例 + 1 条既有用例补臂 + `rawLiteralAfter`
- `fix-reports/FIX-1118C-B-prompt.md` — 本报告

## 8 收口

- **代码提交**：`a10122ec`（`fix/prompt-truth-1118c`）；推送：`gh_sync.sh push` → 新分支建立；`gh_sync.sh gh-actions-dispatch --workflow build-apk.yml --ref fix/prompt-truth-1118c`
- **CI**：run **#3267 / `44335431749`**，五 job 全 `success` —— `lint` / `build-apk` / `unit-tests` / `scan-gate` / `publish`
- **head_sha 核对**：`a10122ecc90f2a079e9af763c6788b4d97f0bdd1`，与本地分支 tip **逐字符一致**
- **unit-tests**：**9712 PASSED 行 / 0 真 FAILED**（日志里 6 条含 `FAILED` 字样的行全部以 `PASSED` 结尾，是 `…produces FAILED without calling provider` 这类用例名）；本支 4 条用例逐条在列并 PASSED：`theGlobalMemoryLineIsNotCalledReadOnly`、`theGlobalMemoryWritePathIsStillStated`、`theMemoryAdmissionCriteriaLineAsksForAnActualRead`、`theAuthoritySectionExemptsSkillsAndApprovedGlobalMemory`；`BUILD SUCCESSFUL in 5m 36s`
- **scan-gate**：`Summary: 115 passed, 0 failed`；日志同时出现 `scripts/scan/** untouched in this push — skipping the fixture self-test` —— 本支未动门，**本次绿不含夹具跑过的证据**（门的存在性由 115 条门在树上跑过这一事实承担）
- **publish**：`Move the rolling tag android-latest` 与 `Publish to Releases` 两步均 `if: github.ref == 'refs/heads/main'`，本分支 **skipped**，日志止于 `Stage release asset`，release 未被污染
- 报告提交为第二个提交，push 后核对「报告之外差异为空」
- **未合并 main**（分支 CI 绿 = 确定性停止节点）
