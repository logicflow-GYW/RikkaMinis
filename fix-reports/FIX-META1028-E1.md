# FIX-META1028-E1：SkillRepository frontmatter 与脚本生命周期三条

**分支** `fix/meta1028-e1-skill-frontmatter`（基线 main `bfb5b0e6`）
**来源** 第二轮货架 B07 报告 F-06(high) / F-09(medium) / F-10(medium)，同一文件
`src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt`

---

## 1. 证据可信度核实（先证，不按行号施工）

任务书已警示转写有损。实际核对结论：**三条的核（缺陷陈述）成立，但报告给的"第二个位置"全是转写产物**。

| ID | 报告给的第二个位置 | 真码里那个位置实际是什么 | 结论 |
|---|---|---|---|
| F-06 | `:2272-2295` | `parseSkillMd` 的 **T151 块标量解析 + 返回 ParsedSkill**（即已经正确的一侧） | 报告把"读者"当成了第二处缺陷；真正的坏面只有 `mergeFrontmatter` 一侧 |
| F-09 | `:1287-1311` | `renameSkill` 的 DB 更新 + `readSkillFile` | 全仓 `.length + 1` 只有 `:259` 一处（真缺陷处），不存在第二份 offset 算法 |
| F-10 | `:1061-1125` | `updateFromURL`（确实相关，但它是**调用点**，不是第二份缺陷实现） | 相关，但定性不同 |

所以本支一律**按语义先证**：用逐字抽出的真源码跑同一份场景文件，pre-fix 必须红、post-fix 必须绿。

### 三条的共同形态

三条都不是"逻辑写错"，是**同一份数据结构的 reader 与 writer 对"行/块边界"没有达成一致的 Liabilities**：

- F-06：读者认块标量，写者按行走 ⇒ body 行变成"既不是值也不是键"的残骸；
- F-09：读者 split 掉整个 `\r\n` 两个字符，写者按 `+1` 补 ⇒ offset 系统性短（偏差 = 分隔符前的行数）；
- F-10：两侧（zip / GitHub）都会写，"删"这一侧从来没人干过。

**可复用判据**：同一结构的读与写两份代码，务必逐项核对它们对分隔符语义的**共同假设**：块边界、行 terminator、集合的完整语义。三条都可以在 review 阶段用这一个问句挑出来。

---

## 2. F-06：YAML 块标量

### 2.1 先证

真码缺陷有两个形态（都比报告写的"截断/破坏"更重）：

1. **managed 块标量的 body 留在文档里**。`mergeFrontmatter` 逐行走 `1 until frontmatterEnd`，遇到 `description: |` 把它重写成 `description: fresh desc`，但下面那几行 `  line one` / `  line two` 既不在 `managed` 里、也不含冒号 ⇒ **原样穿过**，永久留在 frontmatter。
2. **多行值被发射成裸行**。`parseSkillMd` 自 T151 起把 `|` / `>` / `>-` 解析成**带换行**的值；写回时拼 `description: <value>` ⇒ continuation 行落到 column 0 ⇒ 下一次读把它们当**键**。

第 2 条是 injection，不是格式化问题：**一个 description 里的 `version: 9.9` 会真的把 version 改掉**。

**pre-fix 探针（`probe-e1/ScenFrontmatter.kt` 跑 extract 出来的 `bfb5b0e6` 成员）**：

```
F06 block: continuation lines of the managed key are consumed   FAIL
F06 block: no orphan '  line one' left in the document           FAIL
F06 folded: '>-' body dropped with the key                       FAIL
F06 multiline: emitted as a block scalar                         FAIL
F06 multiline: round-trip description equals the value           FAIL
F06 multiline: worked example — 'second: injected' is NOT a key  FAIL    ← injection 成真
SCENARIO-SUMMARY: 18 passed, 9 failed
```

### 2.2 修法（change-ladder 停在第 6 级）

新增三个 companion 内的纯函数，**不引 YAML 库、不做 full parse**：

- `frontmatterKey(line)` —— **键只在 column 0 认**。旧判据 `indexOf(':') > 0` 会把 `  name: x` 这种块 body 行当成键，这正是上面 injection 能成真的机制；column 0 同时就是 YAML 自己的规则。
- `isBlockScalarHeader(value)` —— `|` / `>` 后跟 digits / `-` / `+`，与 `parseSkillMd` 的 T151 同一套。
- `blockScalarBodyEnd(src, from, limit)` —— body 边界**复用读者的同一条规则**（空行或缩进行继续），不自己发明第二条。
- `frontmatterEntry(key, value)` —— 单行值 → `key: value`；含 `\n` → `key: |` + 两空格缩进。

行走主干只加一行：`if (i < blockBodyUntil) continue`。停在第 6 级而不是拉个 YAML 解析器：本仓唯一的读者就是 `parseSkillMd`，它的 negotiated set 已经写死在那里，双份解析只会制造第二处分歧。

### 2.3 证据

- post-fix 同一份场景文件 **27/27 绿**（没有为通过改预期）。
- 新增 `SkillFrontmatterBlockScalarAndEolTest`（12 例），期望值全字面量。
- 变异臂：
  - `m1`（`i < blockBodyUntil && false`）→ 恰红 2 例（`managed block scalar body…` / `folded block scalar…`），其余 42 绿。
  - `m2`（`if (true) return listOf("$key: $value")`，退回裸发射）→ 恰红 2 例（两个多行场景）。

---

## 3. F-09：CRLF 下的偏移量

### 3.1 先证

真码 `:259`：`for (i in 0 until frontmatterEnd) frontmatterEndOffset += lines[i].length + 1`。
`String.lines()` **吃掉整个 `\r\n`**，所以 CRLF 文档每行实际比这个算式多 1 字符，偏差 = 分隔符之前的行数。

最小复现（真树 pre-fix 探针，实际输出）：

```
md     = "---\r\na: 1\r\nname: old-name\r\n---\r\nBody\r\nline2\r\n"
OUTPUT = "---\r\na: 1\r\nname: newe\r\n---\r\nBody\r\nline2\r\n"   ← 'newe'：old-name 的尾段残留在那
```

不是"错位一格"那么温和：head 落进 value 内部 ⇒ `replaceFirst` 的产物与 tail 拼起来得到 `name: new` + **旧值残尾**。纯 CR 文档同样受害（`lines()` 也认 `\r`）。LF 路径不受影响，缺陷严格限定在 CRLF/CR。

### 3.2 修法

改为 **(行内容, 自己的 terminator) 对 逐个重建**（`splitKeepingLineEndings`）：

- `\r\n` / `\n` / `\r` 混排文档每一行的 terminator byte-for-byte 不变 —— 顺便修掉了"重 join 会统一行尾"这个副作用；
- 消灭了 offset 这个量纲本身：不再有任何 char 算术 ⇒ 该类缺陷不可能再次从同一处复发。

### 3.3 证据

- 场景 `F09 CRLF` / `F09 CRLF min` / `F09 mixed EOL` / `F09 LF`（regression）/ 无 name 返回 null / 同名返回 null：pre-fix 红 2，post-fix 全绿。
- 变异臂 `m3`：**把整个函数体还原成 `bfb5b0e6` 的原文**（不是改一行，是把修复前的实现整体塞回去，1056→1148 字符）→ 恰红 1 例（`crlf document is renamed without corrupting the value`）。其余 CRLF-adjacent 用例仍绿是正确的：它们断言的是"行尾不变"这类该功能在两条实现下都成立的属性，只有 `m3` 单独钉住 offset 那一处。

---

## 4. F-10：覆盖导入 / 在线更新残留废弃脚本

### 4.1 先证（结论与报告不完全相同）

先做机械核对：切出三个落盘路径的函数体，grep 有没有任何 `.delete*` 调用。

```
pre  importFromArchive           delete-calls=[]
pre  downloadGitHubDirectory     delete-calls=[]
pre  downloadSiblingFiles        delete-calls=[]
F10-PREFIX: no deletion in any landing path = True
```

⇒ **"只写不删"是实锤**，且三条与该 file 自己的 KDoc 冲突：`updateFromURL` 的 KDoc 明写 "bundled scripts/references stay in sync with upstream"，纯写路径在定义上交付不了这句话。

但同一 file 里也扫到了报告没提的**反向契约**，我把它当成建筑约束而不是借口：
`syncBundledSiblings`（APK assets 侧）不做删除，`SkillAssetSync` 的 KDoc 明写 *"user edits to a bundled script survive every launch"* —— 用户在 `scripts/foo.py` 里自己写的改动是被支持的用法。也就是说：**"删"在这份文件里是有杀伤面的**，不能顺手两边一起删。

### 4.2 修法

按伤害分层，把 destructive 那半锁在"调用方能给出完整凭证"的前提里：

1. **纯决策** `planStaleSiblingDeletions(existing, keep)`：
   - on-disk 减去 keep 的集合差；
   - **`SKILL.md` 永不进删除集**（`writeSkillMd` owns 它；GitHub 目录 walk 刻意跳过根 SKILL.md，若因此把它判 stale 就正好反向破坏了既有契约）；
   - **空 manifest ⇒ 空删除**（fail-closed）：空目录列表与"API 返回被截断"不可区分，猜错就是清空 payload；
   - 路径 normalize（`\`→`/`、去 leading `/` 与 `./`）后再比。
2. **执行** `deleteStaleSiblingFiles`：canonical 包含性守卫（防 `..` / 符号链接删到目录外）+ 删除后 `pruneEmptyParents`（空 `scripts/` 在 UI 里仍读成"这个技能带脚本"）+ 失败只 Log 不抛。

**两个调用点各自都被要求拿出同形式的凭证**：

- `importFromArchive`：仅当 `writeFailures == 0 && writtenRelPaths.isNotEmpty()` 才清（部分写出、或只有 SKILL.md 的 zip ⇒ 不清）。
- `downloadSiblingFiles`：仅当 walk 可证**完整**（`filesFailed == 0 && reason == null`）且 manifest 非空。这一句把「GitHub 403 rate-limit 导致列表为空」与「上游真的删光了」两个世界分开 —— 而这正是 W5"假成功/不可信第三方元数据"那一族在同步场景里的落点。

新增 `AggregateOutcome.seenRelPaths` 记录**实际落地**的每个路径（含 byte-identical 跳过写入的那些）：manifest 必须是"真正落地的那批"，不是"试图下载的名字列表"。

### 4.3 证据

- 纯函数 8 个场景全绿。
- **真实临时树**（同一份删除代码逐字搬进探针，只 stub 了 `android.util.Log`，18 行）：5 文件 fixture → 删 2 个 stale（`scripts/stale.py`、`scripts/legacy/old.py`）→ 空 `scripts/legacy` 被 prune、非空 `scripts/` 保留、`SKILL.md` 原字节不变 → 第二次跑 0 删除（幂等）。
- 变异臂：
  - `m4`（去掉空 manifest 的 fail-closed）→ 恰红 1 例。
  - `m5`（去掉 SKILL.md 豁免）→ 红 3 例。这条刻意落在"同一个约束被 3 处不同断言同时钉住"这个事实上（删除用例 + 豁免用例 + 保留用例），说明 SKILL.md 保护不是靠单一用例兜着。
- 第三条真实树场景作为**负控**：skill root 之外的 `e1-outside-*/victim.txt` 不被删（删除循环的 containment 守卫）。

---

## 5. 验证矩阵

| 层 | 内容 | 读数 |
|---|---|---|
| L1 纯函数 JVM | 4 个测试类本地实跑（既有 `SkillFrontmatterMergeTest` 12 + `SkillFrontmatterPolarityTest` 12 + 新增两个类 20） | **OK (44 tests)** |
| L1 探针 pre/post | `extract.py` 逐字抽 companion body + `ParsedSkill` + `parseSkillMd` + `extractFrontmatterBlock`（每个 member 打 sha256），`ScenFrontmatter` 27 场景 | pre-fix **18 passed / 9 failed** → post-fix **27 passed / 0 failed** |
| L1 真实树 | `ScenStale` 15 场景，`deleteStaleSiblingFiles` / `listFilesUnder` / `pruneEmptyParents` 逐字搬进探针 | **15/15 绿** |
| L2 变异 | 5 臂 m1..m5 | 每臂都红，且只红它该红的用例（见各节） |
| L3 邻门 | `skill_frontmatter_roundtrip_check`（23 臂）、`skill_import_offmain_check`（17 臂）、`log_url_redaction_guard`、`chat_ui_interaction_check`、`skill_restore_session_cleanup_check`、`share_dir_prune_check` | **6/6 rc=0**（含前两门 `--selftest` 均 PASS） |
| L4 CI | build-apk `40691529839` | 见 §6 |

**纪律遵守**：`scripts/scan/**` / `scan.sh` / `test_scan.py` 零触碰；未跑全量 scan / test_scan / gradle；一律单文件 kotlinc；全程 `nice -n 10`；同一时刻只一个 JVM；`data/db/*`、`sandbox/**`、`ui/**` 零改动。唯一生产改动面 = `SkillRepository.kt`（+284/−32）+ 2 个新测试文件。

---

## 6. CI 判读

run **`40691529839`** / **#2831** / job `build` / attempt 1 — **success**，12:53:09 → 13:14:56（**21m41s**）。

| 判据 | 读数 |
|---|---|
| `head_sha` 逐字符一致 | `3b1ecbdb6fce0a5919822302a647c9e1c3affe42` == 本地 HEAD ✅（报告提交 `e86e262c` 是 CI 已经起跑之后才推送的：本 run 钉住的是**代码提交** `3b1ecbdb`，报告不进任何 step 的输入） |
| 27 步非 success | 恰 2：**rolling tag + Publish 双双 skipped**（main 门控，分支构建的正确形状） |
| 日志体量 | zip 656,449 B → 解压 3,006,396 B / 3,007,796 字符 / **NUL 0** |
| 静态门 | `║  Summary: 113 passed, 0 failed` 恰 1 次；`[N/113]` 编号 **1..113 连续互异**；80 条 selftest 标记；其中 `SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS` / `SKILL_IMPORT_OFFMAIN_SELFTEST` 均在 |
| 夹具 | `ALL 349 CASES PASS` 恰 1 次（12:58:40） |
| Kotlin 编译 | gradle step `BUILD SUCCESSFUL`、`^ *e: ` **0** |
| 判读陷阱复核 | UT step 含 `FAILED` 的行 **6 条，全部是 PASSED 用例名子串**（`RealAgentAdapterSkeletonTest > slot denied produces FAILED…`、`ChatAgentTraceObserverSchemaTest > reason PERSISTENCE_FAILED…` 等）——本仓库的常规误读点，逐条看过 |
| 本支四个测试类 | `SkillFrontmatterMergeTest` 11/`SkillFrontmatterPolarityTest` 13/`SkillFrontmatterBlockScalarAndEolTest` 12/`SkillStaleSiblingCleanupTest` 8 = **44 PASSED，0 非 PASSED**（与本地 harness 读数同数） |

> 注：第一版判读脚本把整个 zip 拼成一份全文再 grep，于是 summary / 夹具计数被 `0_build.txt` 与各 step 分文件同时命中而翻倍（看起来像"计数异常"）。改成**按 step 文件读**（`build/3_Run pre-build scan gate.txt`、`build/15_Run unit tests (full suite).txt`）后，上表才是每个计数器的真实出现次数。判本仓 CI 一律按 step 文件读，不要全文 grep。

---

## 7. 残留与登记（未修，交主会话收口）

1. **R1 `syncBundledSiblings`（APK assets 侧）仍不删** —— P2 登记。出货侧把脚本从 assets 删掉后，沙箱目录里的旧脚本会一直活着。**触发条件**：有用户报"我升级了 app，技能的旧脚本还在被用"。**升级路径**：直接复用本支的 `planStaleSiblingDeletions`（它的 KDoc 已写明这份克制 —— user edits to bundled scripts 是被支持的用法，这也是本支刻意没动它的原因）。
2. **R2 paste / 文件导入（`importFromContent`）不清理** —— 不是缺陷：这条路径没有 manifest，无从知道"完整清单是什么"。报告 F-10 把它和另外两条并列，**严格说是 over-claim 的一半**。保持现状。
3. **R3 `frontmatterEntry` 只发射 `|`** —— clip chomping（是否保留尾部换行）没有建模：读者的 `trim('\n')` 使尾部换行在 round-trip 里不可见，所以两种写法在当前都无法区分。真实 newline-semantics 需求出现前不动。
4. **R4 空 document 的 `renameFrontmatterName`** 返回 null 而非空串 —— 与旧实现一致（`lines.firstOrNull()` 也返回 null），caller 语义"没得改就不动"不变。
5. **R5 normalize 不做 case-folding** —— manifest 条目大小写不一致会被判 stale。两条产生 manifest 的路径都吐真实 OS 路径，未见触发，未在 unrealistic scenarios 上造装置。

**不想采用的做法（负决策）**：任务书建议的"导入到临时目录 + 交换"。交换会把用户可能在 `scripts/` 下自己写的笔记一并端掉，比我们修的这个缺陷破坏面更大，成本也高一个数量级，收益却只是"删得更整齐"。

---

## 8. 探针

`probe-e1/`（**树外存放，不进仓库**）：

- `extract.py` —— 逐字抽出 companion body + `ParsedSkill` + `parseSkillMd` + `SubagentSkill.extractFrontmatterBlock`，每个 member 打 sha256（`_kotlin_lexer` 做花括号配平，所以生产 KDoc 里的样例 ``` code ``` 不会打断切分）
- `ScenFrontmatter.kt` —— F-06/F-09 契约场景，同一份文件跑 pre / post 两棵树
- `extract10.py` + `ScenStale.kt` —— F-10 删除代码逐字搬进探针，跑真实临时树（只 stub `android.util.Log`）
- `extract_all.py` + `run_junit.sh` —— 本地 JUnit harness（抽出真源码成员放原 package，让仓库里那 4 个测试类不用 Gradle 就能跑）
- `mut.py` —— 5 个变异臂
