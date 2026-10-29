# FIX-R5-A2 — 凭据红线二段：大小写 / 凭据目录 / 根内软链（A-F-04 · A-F-06，P0）

- 分支：`fix/r5-a2-credential-redline2`（worktree `/var/minis/shared/work/r5-1029/wt-a2`）
- 基线：main `f3def6ce`（R5 修后复扫批，零漂移）
- 来源：`reports/R5-A.md` A-F-04（M10）/ A-F-06（M10），判级 `TRIAGE.md` 两条均 P0
- 承接：2026-10-29 上午 P0 修复 `1fa9dff8`（`DENY_NAME_PATTERNS` 名字边界匹配）的**残余缺口**
- 改动面：生产 2 文件（+82/−6）+ 测试 2 文件（+161）；`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰，门 113 / 夹具 349 不变

## 1. 一句话：这改变什么用户行为？

任意大小写的凭据文件（`API_KEYS.md` / `Secrets.json` / `.ENV` / `apiKey.json`）、
放在 `oauth/` `secrets/` `credentials/` 这类**目录即凭据**位置的无害文件名、
以及根内指向它们的软链，**不再进入公共 `Download/RikkaMinis/backup/mirror/` 的明文镜像，
也不再进入无密钥自动备份与它的 WebDAV 副本**；普通文档与 `tierflow-key-harvest`
技能目录照常镜像。

## 2. 先证（按语义复核真码，再动手）

修前同一套 JVM 断言（`a2-probe/test/.../CredentialRedline2HarnessTest.kt`，11 例）
跑在**未修改**的生产源上：**6 红 5 绿**，红的三族正是机制本身，不是行号巧合。

| 用例 | 修前实测输出 | 结论 |
|---|---|---|
| `A_F04_denyTable_coversCaseAndExtraRoots` | `isDeniedName("API_KEYS.md") == false`（首个断言即挂） | 正则无 `IGNORE_CASE`，大写族全放行 |
| `A_F04_selection_dropsCapitalisedCredentialFiles` | `[memory/2026-10-29.md, memory/API_KEYS.md]` | 大小写文件真被选进镜像 |
| `A_F04_selection_deniesCredentialDirectoriesAtAnyDepth` | `[memory/Secrets/config.json, memory/credentials/nested/deep.json, memory/keep.md, memory/oauth/linear.json]` | 目录分支不查拒绝规则 ⇒ `oauth/linear.json` 真被选入 |
| `A_F04_negativeControl_keyInTheNameIsNotACredentialDirectory` | `[... SKILL.md, ... keys/ledger.json, ... tf_status.py]` | `keys/` 子目录未被拒（同一机制的另一面） |
| `A_F06_fileSymlinkIntoOauthInsideTheRootIsNotArchived` | `[keep.md, linear_bak.json]` | 根内文件软链 → `oauth/linear.json` 真被打包 |
| `A_F06_symlinkToTopLevelSandboxEnvIsNotArchived` | `[env_link/apk-world.txt, keep.md]` | 目录软链绕开 root-only 的 `EXCLUDED_DIRS` |

机制层复核（真码，非报告行号）：

- `MirrorScope.kt`：`DENY_NAME_PATTERNS` 两个 `Regex` 无 `RegexOption.IGNORE_CASE`；`select` 的**目录分支**只调 `includeAllDirs`，从不调 `isDeniedName`；文件分支对所有小写名字调 `isDeniedName`——所以「叶子名无害、目录即凭据」的路径结构上不可达。
- `ArtifactBackupScope.kt`：`resolveInside` 只比 `path == rootCanon || path.startsWith(rootPrefix)`；`DENIED_DIRS` 检查只写在 `if (child.isDirectory)` 分支，且只比 `child.name` / `target.name` 的**叶子名**——文件软链走文件分支，永远碰不到这条检查；`EXCLUDED_DIRS` 同理只看当前层名。

## 3. 修法（为什么不是照抄报告的补丁建议）

### A-F-04 — `MirrorScope.kt`

1. `DENY_NAME_PATTERNS` 两个正则全部 `RegexOption.IGNORE_CASE`，词根补 `auth` / `cookies?` / `bearer` / `apikeys?`（`apiKey` 驼峰在小写化后由 `apikeys?` 命中；`api_key` 本来就被 `keys?` 的 `_` 边界命中）。
2. **目录分支新增 `isDeniedDir(name)` 拒绝**（`select` 内，walk 之前）。

**这里与报告的写法有分歧，且是刻意的**：报告建议「目录分支同样拒绝 `isDeniedName(name)`」。
按真实树验证，那会**把 `skills/tierflow-key-harvest/` 整个踢出镜像**——该目录名命中
`([\\-_.])(keys?)([\\-_.])`（`-key-`），而它是用户最常用的技能（SKILL.md + 脚本）。
于是改成**独立的窄规则** `DENY_DIR_NAMES`：整名匹配（大小写无关）一组「容器名」
（`oauth` / `secrets` / `credentials` / `keys` / `apikeys` / `.ssh` / `.aws` / `.gnupg` /
`.docker` / `.kube` / `keychain`）。整名匹配对 `tierflow-key-harvest` 这种复合名天然不命中，
而 `oauth/linear.json` 这种真形态照样命中。变异臂 M3 把这条决策钉住（见 §4）。

真实树误伤扫描（`a2-probe/sim.py`，只读，2026-10-29）：三棵树在册文本文件 **283** 个，
新文件规则新增拒绝 **0** 个（原本就没有大写凭据文件，零误伤）；按 `DENY_DIR_NAMES`
命中的真实目录 **NONE**；按文件规则命中的目录只有 `skills/tierflow-key-harvest`（1 个，
正是要保住的）。

### A-F-06 — `ArtifactBackupScope.kt`

`resolveInside` 从「在根内」升级为「根内 **且逐段清白**」：

```
path == rootCanon                              -> resolved            // 保留原语义
!path.startsWith(rootPrefix)                    -> null                // 越根，不变
else: segments = path[rootPrefix..].split(sep)
      segments.any { it.lowercase() in DENIED_DIRS }   -> null          // 任一段是凭据目录
      segments.first().lowercase() in EXCLUDED_DIRS     -> null          // 首段是环境载荷
      否则 resolved
```

- 文件软链 `linear_bak.json -> oauth/linear.json`：解析后的**段**里有 `oauth` ⇒ null（修前只看叶子名 `linear_bak.json`，无论怎么改叶子名检查都拦不住）。
- 目录软链 `env_link -> sandbox-env`：解析后首段是 `sandbox-env` ⇒ null（修前 `EXCLUDED_DIRS` 只比较链接自身的名字）。
- 段比较**大小写无关**：`OAuth/` 在大小写敏感文件系统上是另一个目录，但秘密的形状相同；镜像侧规则已是大小写无关，两侧口径要一致（遗漏它就是又造一个「两份规则、一处生效」的洞）。
- 目录分支原有的 `child.name in DENIED_DIRS || target.name in DENIED_DIRS` 保留为**第二道带**（注释已写明它现在被段规则覆盖），不删除以免额外改动面。

`EXCLUDED_DIRS` 仍是**首段**判据（不是任意段）：既有单测 `skipsExcludedDirectoriesAndDotFiles`
要求 `nested/sandbox-env/notes.md` 继续被归档，语义不变。

## 4. 验证

| 装置 | 命令 | 结果 |
|---|---|---|
| 修前红（先证） | `sh a2-probe/run.sh pre` | 11 例 **6 红 / 5 绿**（§2 表） |
| 修后绿 | `sh a2-probe/run.sh post`（含后续补的 1 例，共 12） | **OK (12 tests)** |
| 变异臂 7 条 | `python3 a2-probe/arm.py` | **7/7 MATCH**，`rc=0`（日志 `a2-probe/arms.log`） |
| 真实树误伤扫描 | `python3 a2-probe/sim.py` | 新增拒绝 0 / 命中容器名目录 0 |
| 云端 CI | build-apk `40851736604` | `success（09:42:26Z → 10:02:53Z，20.4 min，attempt 1，27 步中唯一非 success = 滚动 tag + Publish 两条 skip）`（详见 §6） |

变异臂逐条（每条都要求「名为 X 的用例变红」，全绿即判 ROT）：

| 臂 | 把什么塞回去 | 变红的用例 |
|---|---|---|
| M1 | 去掉第一个 pattern 的 `IGNORE_CASE` | `denyTable_coversCaseAndExtraRoots` + `selection_dropsCapitalisedCredentialFiles` |
| M2 | 目录分支退回 `if (false) continue` | `selection_deniesCredentialDirectoriesAtAnyDepth` + 技能目录负例（`keys/` 回来了） |
| M3 | 目录分支改用 `isDeniedName`（**报告的写法**） | `negativeControl_keyInTheNameIsNotACredentialDirectory` ⇒ 证明「照抄会把 tierflow 技能踢出镜像」 |
| M4 | `resolveInside` 的段判定退成恒 `resolved` | 三条 F-06 用例全红（文件软链 / 环境载荷软链 / 大小写目录） |
| M5 | 去掉 `excludedTop` | `symlinkToTopLevelSandboxEnvIsNotArchived` |
| M6 | 段比较改成大小写敏感 | `oauthDirectoryIsDeniedRegardlessOfCase` |
| M7 | 去掉 `apikeys?` 词根 | `denyTable_coversCaseAndExtraRoots`（`apiKey.json`） |

期望值全部写死字面量（`[keep.md]`、`[alias.md, keep.md, real/report.md]` 等），不从被测常量推导。

## 5. 防误伤负例矩阵（红线不能靠「关掉功能」实现）

| 负例 | 断言 | 说明 |
|---|---|---|
| `monkeys.md` / `keyboard-notes.md` | `isDeniedName == false` | 2026-10-29 上午那道门的既有负例，必须继续绿 |
| `author-notes.md` / `authored-by-agent.md` | `false` | 新增 `auth` 词根的边界纪律（`auth` 后面必须跟边界） |
| `cookiecutter.md` | `false` | 新增 `cookies?` 词根的边界纪律 |
| `bearing-capacity.md` | `false` | 新增 `bearer` 词根的边界纪律 |
| `skills/tierflow-key-harvest/SKILL.md`、`.../scripts/tf_status.py` | 仍被选入 | 目录规则不误伤复合名 |
| `real/report.md` ← `alias.md` 软链 | 仍被归档 | 段规则不把「根内合法软链」一并杀掉 |
| `nested/sandbox-env/notes.md` | 仍被归档 | `EXCLUDED_DIRS` 仍是首段判据 |
| `shared/work/batch-a/fix-tasks/task.md` 等 | 仍被选入 | 镜像主体功能未受影响 |

## 6. 云端 CI

- run `40851736604`：`success（09:42:26Z → 10:02:53Z，20.4 min，attempt 1，27 步中唯一非 success = 滚动 tag + Publish 两条 skip）`
- `head_sha` = `0f3cb49f9d123dd3b16846713934fc8be6fa4f4b`（与本地 HEAD 逐字符一致）
- 判读口径：`Summary: 113 passed, 0 failed` / `ALL 349 CASES PASS` / `^ e: ` 0 / 真 FAILED 0 / NUL 0 / 27 步中唯一非 success = 分支上 Publish 门控 skip
- 新增/改名的用例在日志里逐条 `PASSED`：`isDeniedName_table_coversCaseAndTheMissingRoots`、`capitalisedCredentialNamesAreNeverMirrored`、`credentialDirectoriesAreNotWalkedAtAnyDepth`、`skillDirectoryWithKeyInItsNameStillMirrors`、`fileSymlinkIntoTheOauthBridgeInsideTheRootIsNotArchived`、`symlinkToTheTopLevelSandboxEnvDirectoryIsNotArchived`、`oauthDirectoryIsDeniedRegardlessOfCase`、`symlinkToAnOrdinaryFileInsideTheRootIsStillArchived`

## 7. 残留（登记用，均不在本支半径内）

- **R1 目录规则是整名匹配**：`oauth2/`、`my-secrets/` 这类变体不命中。放宽会与 `tierflow-key-harvest` 冲突，故停在「容器名精确匹配」；触发条件：出现真实的 `oauth2/` 型凭据目录。
- **R2 内容级扫描仍不做**：文档正文讨论凭据（日报、backlog、脱敏脚本）会大面积误伤——沿用 2026-10-29 上午的既有决策，非本支引入。
- **R3 ArtifactBackupScope 无文件名规则**：`DENIED_DIRS` 只保护 `oauth/` 目录；`shared/credentials.json` 这类「顶层凭据文件」仍会进无密钥备份。属 A-F-06 之外的另一条线（镜像侧有 `DENY_NAME_PATTERNS`，备份侧没有），未在本支扩张半径。
- **R4 软链别名会双份归档**：`alias.md -> real/report.md` 两份都进 zip（既有行为，循环守卫只按目录 canonical 去重）。本支只保证不再归档凭据。
- **R5 真机未验**：全部证据 = JVM 级 + 源码形状 + CI 编译级；MediaStore 目标与 WebDAV 上传侧未在真机复现。

## 8. 未触碰的边界

`MirrorStore` / `MirrorPlan` / `AutoBackupManager` / `BackupSettingsScreen`（A1 与 A3 的独占文件）、
`scripts/scan/**`、`scan.sh`、`test_scan.py`、`ConfigBackup.kt`、`strings.xml`（无新用户可见文案）。
门 113 / 夹具 349 未变；分支 CI 绿 = 本支停止节点，未合 main。
