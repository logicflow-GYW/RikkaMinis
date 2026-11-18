# FIX-2D-1119-1 — 受保护区守卫：父目录缺失（P0）+ 五处词法漏口

- 分支：`fix/zone-guard-parent-1119`，基线 `b82b2963`（= 当前 main，1118C 四支合并树）
- 实现提交 `c62fb74a`（+ 测试补针 `5ffe88ce`），本报告为第三个提交
- 来源：`meta-audit/reports/r2d1119/4_Sandbox_Report.md`（6 条，F-01 主会话已判 P0 数据丢失级）；台账 `meta-audit/r2d1119/TRIAGE.md`
- 任务书：`meta-audit/r2d1119/TASK-1-zone-guard.md`
- 上一轮：`fix/zone-guard-words-1118c`（已合 main）把守卫换成真词法层；本轮修**同一文件里剩下的 6 条**

---

## 1 范围

**这改变什么用户行为？** 以前 `rm -rf /var/minis` 一条命令清空全部 5 个受保护区、静默放行；另有 5 类写法（`mv -ft`、`(…)` / `{ …; }` 分组、`stdbuf -i`/`sudo -h`/`exec -a` 这类带值包装器选项、`eval`、`popd` 之后相对删除、引号不配对命令里**后半段**的 wide 目标）同样静默放行。现在这 6 类走与 `rm -rf /var/minis/shared` **完全相同**的拒绝路径（同一个 `wideDeleteHit` → 同一份文案、同一份 `protected_zone` 拒绝码），不新增任何文案、不动 7 语种资源、不动开关、不动 fastio 路径的判定口。

**误拒面**：没有新增。本轮六处修法**没有**把任何「应该放行」的形状变成拒绝——8 条任务书点名的阴性对照（含 `rm -rf /var/minis/logs`、`/var/minis/attachments` 这类**父目录下的一级目录**）逐条仍放行，并且各自有变异臂从「过度保护」方向钉住（见 §5 M2）。

**碰的文件**（仅此三个）：

- `src/android/app/src/main/java/com/rikkaminis/app/sandbox/ProtectedZoneGuard.kt`（+207/−53）
- `src/android/app/src/test/java/com/rikkaminis/app/sandbox/ProtectedZoneGuardWordsTest.kt`（+271，新增 `ProtectedZoneGuardParentZoneTest` 12 条）
- `fix-reports/FIX-2D-1119-1-zone-guard.md`（本报告）

**没碰**：`scripts/scan/**`（扫描门与本守卫无文本锚点）、`FastioOffloadHandler.kt`、`ExecutionCoordinator.kt`、`ZoneDeleteGuardStore.kt`、任何 `strings.xml`，以及本支以外其他支的文件。**没有跑全量 `scan.sh` / `test_scan.py` / gradle**（本地全量门现在按设计拒绝 rc=3，见 §8）。

## 2 逐条先证与结论

主会话已用 JVM 探针在基线上实证 6 条（`meta-audit/reports/r2d1119/probe/`）。本支先在**当前基线**上重跑其中关键形态确认仍成立（`r1119/zoneguard-jvm/probe/Probe3.kt` 的 13 条）：修复前全部 `PASS-THROUGH`，修复后全部 `REFUSED`，8 条阴性对照全 `PASS-THROUGH`，`mismatches: 0`。**没有一条证伪**（没有「在现代码里已经不成立」的，也没有改了判定方向才发现其实已经被别的路径兜住的）。

| ID | 基线（实证） | 根因（真码位置） | 修法一句 | 钉住的用例（期望值写字面量） |
|---|---|---|---|---|
| **F-01（P0）** | 成立：`rm -rf /var/minis`、`rm -rf /var/minis/`、`mv /var/minis /tmp/` 全放行 | `ZONES` 只列 5 个二级目录，`zoneOf()` 返回 null，`isWideTarget()` 第一行就 `return false` —— 父目录不在**任何**列表里 | 新增 `ROOT_ONLY_ZONES`（`/var/minis`、`/var`），`isWideTarget()` 里**先判相等**；父目录不进 `ZONES`，所以它下面**一级**目录（`logs`、`attachments`、`workspace`）仍然放行 | `deleting the parent of every zone is refused`、`a child of the parent is still ordinary cleanup`、`the zones themselves still refuse next to their parent` |
| **F-02** | 成立：`mv -ft`、`mv -vt` 放行 | `mvSources()` 只认 `a == "-t"` / `a.startsWith("-t")`；`-ft` 落进通用短选项分支，`targetForm` 保持 false，`dropLast(1)` 把真源当目的丢掉 | 短选项簇里**含 `t`** 即 target form（`mvShortCluster()`：`-ft`/`-vtf`/`-tf`，`-t` 后无内容时吃下一个词；`-T` 是相反语义，遇 `T`/`S` 即停） | `mv with a clustered target flag flags the zone as a source`、`mv uppercase T is not the target flag` |
| **F-03** | 成立：`(rm …)`、`{ rm …; }` 放行 | `(` 被拼进第一个词得 `"(rm"`（不是动词）；`{` 是独立首词也不是动词 | `(`/`)` 与**独立成词**的 `{`/`}` 都是语句分隔符（`endWord()` + `endStatement()`）；词内的 `{` 仍是花括号展开（天花板，见 §6）；`$(` 的 `$` 丢弃不当参数 | `subshell and group separators do not hide the verb`、`brace expansion stays a documented ceiling` |
| **F-04** | 成立：`stdbuf -i`、`sudo -h`、`exec -a` 等放行；`eval` 整条放行 | `WRAPPER_VALUE_FLAGS` 是**一张全局表**，`-i` 在表外（为救 `env -i`）⇒ `stdbuf -i <值>` 的值为被当 verb；`eval` 不在 `WRAPPERS` 里 ⇒ 动词被读成 `eval` | 值选项改成**按 wrapper 分表**（`Map<String, Set<String>>`）：`env` 的 `-i` 仍是布尔（吃词会造洞），`stdbuf` 的 `-i`/`-o`/`-e` 吃值；`eval` 加入 `WRAPPERS` | `wrapper value options are skipped per wrapper`、`eval peels to the verb and env dash i stays boolean` |
| **F-05** | 成立：`pushd <zone> && pushd /tmp && popd && rm -rf work` 放行 | `wideDeleteHit()` 只认 `cd`/`pushd` 会写 `current`，`popd` 没有分支 —— 弹栈后的目录**完全不在模型里** | 维护 `dirStack`：`pushd` 压栈后切目录（无操作数 = 交换栈顶两项），`popd` 弹栈切回；栈空时 `popd` 保持 `current` 不变（bash 行为） | `popd restores the directory the next delete runs in` |
| **F-06** | 成立：`ls <zone>/a/b && rm -rf <zone> '`（引号不配对）放行 | `zoneWideLiteral()` 用一次 `indexOf()` 取**第一个**出现，`unbalancedFallback()` 只看那一处 | 改为 `while` 循环扫**每一次**出现，任一处是 wide 即返回；每次至少前进 1 字符，不会死循环 | `an unreadable command is refused when any occurrence is wide` |

**F-01 关于 `/var` 的取舍**（任务书允许自主判断，这里写明理由）：加了，且是**只判相等**语义。`rm -rf /var` 会把 5 个受保护区连同 rootfs 的 `apk` 数据库一起清掉，而等价语义下 `rm -rf /var/tmp`、`rm -rf /var/cache/apk` 都**不**受影响（已在 §2 的用例里逐条放行）。若只加 `/var/minis` 不加 `/var`，`rm -rf /var` 仍会绕过。

## 3 修法细节（判据层）

### 3.1 父目录：相等才保护，绝不继承子目录语义

```kotlin
private val ROOT_ONLY_ZONES: List<String> = listOf("/var/minis", "/var")
private val ALL_ZONES: List<String> = ZONES + ROOT_ONLY_ZONES   // 只给原始文本兜底扫描用

fun isWideTarget(guestPath: String): Boolean {
    val path = if (guestPath.length > 1) guestPath.trimEnd('/') else guestPath
    if (path in ROOT_ONLY_ZONES) return true      // F-01：父目录只判自身
    val zone = zoneOf(path) ?: return false
    ...
}
```

`zoneOf()` **故意**不认识 `ROOT_ONLY_ZONES`：它返回的是「这个路径属于哪个 zone」，若把父目录也当 zone，`/var/minis/logs` 就会被读成「父目录的一级子目录 = 容器」而误拒。`refusalFor()` 的文案对 `/var/minis` 命中时 `zoneOf` 为 null，走不带 `confirm which files` 后缀的通用句 —— 句子仍然通顺（`…it is a protected zone (or a whole subdirectory of one)…`），**没有改文案、没有动 7 语种资源**。

`fastio` 路径自动受益：`FastioOffloadHandler` 调的是同一个 `isRefusedDelete(guestPath, recursive)`，所以 `minis-fastio rm -r /var/minis` 现在同样被拒（本支没改它，改的是它调用的那份谓词）。

### 3.2 `mv` 短选项簇

`mvShortCluster(cluster)` 逐个字符看：遇 `T`（`--no-target-directory`，**相反**语义）或 `S`（吃后缀）即停，遇 `t` 返回 `(targetForm, 值是否在下一个词)`。`-t/tmp`（值贴在簇后）与 `-ft /tmp`（值是下一个词）两条路都认。

### 3.3 括号是分隔符，词内花括号是展开

`(`/`)` 总是分隔符（`endWord()` 后 `endStatement()`，所以 `shared)` 的 `)` 不会粘进路径）。`{`/`}` **只在词首**是分组符；词内（`/var/minis/{shared,memory}`）原样留在 word 里 —— 任务书明令本次不扩到 glob，而 `absoluteGuestPath()` 对 `{` 返回 null 已经是声明过的天花板。`$(` 里的 `$` 单独丢弃，不让它变成一个假参数（`echo $(rm -rf …)` 因此能被看见 `rm`）。

### 3.4 值选项按 wrapper 分表

`WRAPPER_VALUE_FLAGS: Map<String, Set<String>>`，`skipWrapperArgs(words, from, wrapper)` 用**当前 wrapper 自己的表**判断「吃一个词还是两个词」。这条拆表是 F-04 的核心：**同一个 `-i` 在 `env` 是布尔、在 `stdbuf` 是带值**，一张全局表不可能同时成立，方向判错就会造洞（上一轮的 `env -i` 正是那个洞）。

### 3.5 目录栈

`pushd`：有操作数 → 压栈 + 切目录；无操作数 → 交换 `current` 与栈顶。`popd`：栈非空 → 弹栈切回；栈空 → 保持 `current`（bash 报错但不改目录）。`cd` 分支原样保留。

### 3.6 原始文本兜底扫全部出现

`zoneWideLiteral()` 从 `indexOf(zone)` 起循环，`start = indexOf(zone, start + 1)`，任一处 `isWideTarget` 为真即返回；`unbalancedFallback()` 的候选集合从 `ZONES` 换成 `ALL_ZONES`（父目录也要在未配对引号的兜底里被看见）。

### 3.7 没做的事（任务书两条禁令 + 既有纪律）

- **没有**改成「语句里同时出现破坏性动词 + zone 路径就拒」的全扫描：`grep -rn 'rm -rf' /var/minis/shared` 仍放行，并有专条用例钉住；
- **没有**扩 `DESTRUCTIVE` 去接 `rsync --delete` / `find -delete` / `bash -c`（backlog §1118-B R7 登记的天花板，未动）；
- **没有**动 `fastio`、`ExecutionCoordinator`、`ZoneDeleteGuardStore`、`strings.xml`、扫描门。

## 4 证据

### 4.1 本地 JVM 装置（`r1119/zoneguard-jvm/`，非 gradle）

`sh run.sh` → **79 条用例，0 失败**（`out/baseline.log`，JUnit RunListener 逐条打印）：

| 测试类 | 条数 | 结果 |
|---|---:|---|
| `ProtectedZoneGuardTest` | 23 | 全绿（**既有 36 条一字未改**） |
| `ZoneDeleteGuardStoreTest` | 3 | 全绿 |
| `offload/FastioZoneGuardTest` | 10 | 全绿（接线形状探针） |
| `ProtectedZoneGuardWordsTest` | 31 | 全绿（1118C 的 31 条） |
| `ProtectedZoneGuardParentZoneTest` | **12** | 全绿（**本支新增**） |
| **合计** | **79** | **FAILED 0** |

12 条新用例逐条 PASSED（`out/baseline.log` 的 `PASSED` 行，非汇总）：

```
deleting the parent of every zone is refused
a child of the parent is still ordinary cleanup
the zones themselves still refuse next to their parent
mv with a clustered target flag flags the zone as a source
mv uppercase T is not the target flag
subshell and group separators do not hide the verb
brace expansion stays a documented ceiling
wrapper value options are skipped per wrapper
eval peels to the verb and env dash i stays boolean
popd restores the directory the next delete runs in
an unreadable command is refused when any occurrence is wide
the eight ordinary commands still pass
```

### 4.2 探针（`out/probe_fixed.txt`）

| 分组 | 条数 | 结果 |
|---|---:|---|
| 13 条绕过（F-01 的 3 条父目录 + F-02/F-03/F-04/F-05/F-06 各代表形态） | 13 | **全部 REFUSED**，`mismatches: 0` |
| 8 条任务书阴性对照 | 8 | **全部 PASS-THROUGH** |

探针输出逐行（节选）：`rm -rf /var/minis → REFUSED`、`mv -ft /tmp /var/minis/shared → REFUSED`、`(rm -rf /var/minis/shared) → REFUSED`、`stdbuf -i 0 -o L rm -rf … → REFUSED`、`eval rm -rf /var/minis/shared → REFUSED`、`pushd … && popd && rm -rf work → REFUSED`、`ls /var/minis/shared/a/b && rm -rf /var/minis/shared ' → REFUSED`；阴性侧 `rm /var/minis/shared/README.md`、`rm -rf /var/minis/logs/x`、`mv /tmp/report.md /var/minis/shared/`、`grep -rn 'rm -rf' /var/minis/shared`、`nice -n 10 ls /var/minis/shared` 等 8 条全部 PASS-THROUGH。

### 4.3 CI

**代码提交**：`c62fb74a`（实现）+ `5ffe88ce`（测试补针），tip = `5ffe88cec5b004190332612d0adc0eee22c9168c`。分支 push 不触发 CI（`build-apk.yml` 只认 main），按纪律手动 dispatch。

**run #3278 / id `44362201626`**，event = `workflow_dispatch`，`head_sha = 5ffe88cec5b004190332612d0adc0eee22c9168c` —— 与本地 `git rev-parse HEAD` **逐字符一致**。`status=completed`，`conclusion=success`。

| job | conclusion | 证据（`out/ci-*.log`） |
|---|---|---|
| lint | **success** | — |
| build-apk | **success** | — |
| unit-tests | **success** | 含 `PASSED` 的行 **9762** 条，含 `FAILED` 的行 **0** 条；12 条新用例用 `grep -cF "<用例名> PASSED"` 逐条核对，**12/12 = 1 命中** |
| scan-gate | **success** | `Summary: 115 passed, 0 failed`；日志明写 `scripts/scan/** untouched in this push — skipping the fixture self-test (~121s); scan.sh ran all 115 gates over this tree`（本支未碰扫描门，夹具按机制跳过，故本次「绿」**不含**夹具证据） |
| publish | **success** | main 门控生效（分支构建不发布；run 级 conclusion 为 success，tag/release 两步按既有机制 skipped） |

**报告提交**：纯文档，按 GLOBAL 纪律不 dispatch、不等 CI；其 diff 仅 `fix-reports/FIX-2D-1119-1-zone-guard.md`，「报告之外差异为空」（§7 核对）。

### 4.3b 报告提交与 tip 的关系（口径写清，避免自指）

报告是本分支的第三个提交，提交后 tip 从 `5ffe88ce` 前进到报告提交本身。两种做法都有问题，本支选第二种：

- **把报告提交的 run id 写进报告** → 报告内容一变，tip 就变，写死的 id 立刻失真，改一次报告就要再 dispatch 一次（无限回退）；
- **（本支采用）** 报告的 CI 证据锚定**代码 tip**，并明确声明报告是纯 `.md` 提交 —— 按 GLOBAL 纪律「纯文本提交不 dispatch、不等 CI」，代码 tip 的五 job 绿 + `head_sha` 逐字符一致即停止节点。

补充实测（让收口人不必重跑）：本支在报告 tip 上也 dispatch 过一次，五 job 同样全 `success`、`head_sha` 与当时的 tip 逐字符一致。它证明的是「代码 + 报告」这棵树本身也绿，**不是**本支的验收依据；报告此后每次文字修订都会让 tip 前进，写死它的 run id 只会自相矛盾（改了报告 → id 失真 → 再 dispatch → 再改报告）。

## 5 变异负控（8 臂，`r1119/zoneguard-jvm/mutate.py`）

每臂翻转**一个**决定，重跑整套 JVM（79 条），要求该臂自己的用例变红、其余（含 8 条阴性对照）仍绿。快照 + `finally` 还原 + sha256 逐字节校验；变异前后 `ProtectedZoneGuard.kt` 的 sha256 不变（`f602b28f…1786`），`BASELINE-AFTER-RESTORE: OK`。

| 臂 | 翻转的决定 | 结果 |
|---|---|---|
| M1 | F-01 父目录完全不保护（`if (path in ROOT_ONLY_ZONES)` → `if (false)`） | **RED-PRECISE** → `deleting the parent of every zone is refused`、`an unreadable command is refused when any occurrence is wide` |
| M2 | F-01 父目录改成**子目录语义**（`path.startsWith("/var/minis")`）= 过度保护方向 | **RED-PRECISE** → `a child of the parent is still ordinary cleanup`（该臂同时红 8 条既有「不误伤」用例，正是过度保护的代价被看清楚） |
| M3 | F-02 簇里 `t` 不再算 target（只认词首 `-t`） | **RED-PRECISE** → `mv with a clustered target flag flags the zone as a source` |
| M4 | F-03 花括号重新当词内字符（`{` 不当分隔符） | **RED-PRECISE** → `subshell and group separators do not hide the verb` |
| M5 | F-04 `stdbuf` 的 `-i` 从值选项表里去掉（退回「`-i` 全局是布尔」） | **RED-PRECISE** → `wrapper value options are skipped per wrapper` |
| M6 | F-04 `eval` 不进 wrapper 表 | **RED-PRECISE** → `eval peels to the verb and env dash i stays boolean` |
| M7 | F-05 `popd` 不弹栈 | **RED-PRECISE** → `popd restores the directory the next delete runs in` |
| M8 | F-06 兜底扫描只扫第一次出现 | **RED-PRECISE** → `an unreadable command is refused when any occurrence is wide` |

**M5 的 BLIND→RED 值得记一笔（同 1118C 的 M8，第二次踩同一个坑）**：第一版 M5 **BLIND**。原因不是测试没用，而是**同一决定有两条路径兜住** —— `stdbuf -i 0 …` 里 `-i` 的值是数字，`WRAPPER_NUMBER` 那条规则已经把它跳过了，值选项表里有没有 `-i` 根本看不出来。补上 `stdbuf -i L rm -rf …`（值是**非数字**的 `L`，只有值选项表能救）后该臂立刻 RED-PRECISE。**教训复用：变异 BLIND 先问「这个决定是不是有两条路径都能兜住」，别急着删测试。**

## 6 未修边界与新增天花板

**未变**（1118C 已声明，本支没有扩大也没有收窄，并有 `the ceiling is unchanged` 用例钉住）：`rm -rf $X`、`rm -rf /var/minis/shared/*`、`find … -delete`、`xargs rm`、解释器一行命令（`python3 -c …`、`bash -c …`）。

**本次新增的一条天花板（如实登记，不试图解）**：

- **`eval "rm -rf <zone>"`（整条命令被引号包成一个词）仍放行**。此时 `eval` 之后只有一个 word `rm -rf /var/minis/shared`，动词读出来是**整串**而不是 `rm`；要看见它需要真正解析嵌套 shell，超出词法层的判据范围。`eval rm -rf <zone>`（未加引号）**已**被拦住，并有用例与变异臂 M6 钉住。
- 花括号展开 `rm -rf /var/minis/{shared,memory}` 仍放行（任务书明令本次不扩到 glob，见 §3.3）。这条与上一条一起写进本报告，避免下一轮审计把它当成新缺陷。

**已知未修（同族登记，非本支引入）**：`pushd`/`popd` 的状态只在一个 `wideDeleteHit` 调用内有效（跨语句的 shell 状态天然不可见）；`cwd` 未知时相对路径的 fail-closed 口径不变（沿用 1118B 的 `candidateWideTarget`）。

## 7 文件清单、CI 与收口

| 文件 | 状态 | 说明 |
|---|---|---|
| `src/…/sandbox/ProtectedZoneGuard.kt` | 改 | +207/−53：`ROOT_ONLY_ZONES` / `ALL_ZONES` / `isWideTarget` 父目录先判、`shellLex` 括号分隔符、`WRAPPERS` +`eval`、`WRAPPER_VALUE_FLAGS` 拆成 `Map`、`mvShortCluster`、`dirStack`（`pushd`/`popd`）、`zoneWideLiteral` 扫全出现 |
| `src/…/sandbox/ProtectedZoneGuardWordsTest.kt` | 改 | +271，新增 `ProtectedZoneGuardParentZoneTest` 12 条（期望值全是字面量路径，不从 `ZONES` 派生） |
| `fix-reports/FIX-2D-1119-1-zone-guard.md` | 新 | 本报告 |

**装置**（不在仓库内，`/var/minis/shared/work/r1119/zoneguard-jvm/`）：`run.sh`（编译 + 逐条打印，`javac` 编译 `RunTests.java`）、`mutate.py`（8 臂）、`probe/Probe3.kt`、`ci_poll.py`、`out/`（`baseline.log` / `mutate.log` / `probe_fixed.txt` / `ci_jobs.txt`）、`stub/`（Android `Context`/`SharedPreferences`/`VisibleForTesting` 桩，复用 r1118b 编译产物）。

**CI-RESULT**：验收依据是代码 tip 的那次 run —— `5ffe88ce` = **#3278 / `44362201626`**，五 job `success`、`head_sha` 逐字符一致，unit-tests 9762 PASSED / 0 FAILED，12 条新用例逐条 1 命中，scan-gate `115 passed, 0 failed`（夹具按机制跳过）。

**报告之外差异为空**：`git diff --name-status 5ffe88ce HEAD` = `A fix-reports/FIX-2D-1119-1-zone-guard.md` —— 只有本报告一个文件，`git diff --numstat` 的删除列是 `0`（行数不写死：报告自身的内容会改变它，写死就成了自指）。远端 tip = 本地 HEAD = 报告提交。

**收口**：**未合 main**。代码 tip 五 job 绿 + `head_sha` 逐字符一致 = 停止节点；报告提交是纯文档（单文件 `.md`），按既有纪律不 dispatch、不等 CI，等主会话放行。

## 8 复现命令

```sh
# 单测（79 条，非 gradle；逐条 PASSED/FAILED 打印在 out/verdicts.log）
sh /var/minis/shared/work/r1119/zoneguard-jvm/run.sh

# 13 条绕过 + 8 条阴性对照的行为探针
cd /var/minis/shared/work/r1119/zoneguard-jvm
LIB=/var/minis/shared/work/jvm-libs
java -cp "out/probe:$LIB/kotlin-stdlib-2.1.0.jar:out/classes" Probe3Kt

# 变异负控（8 臂，快照 + finally 还原 + sha256 校验）
nice -n 10 python3 /var/minis/shared/work/r1119/zoneguard-jvm/mutate.py

# CI（分支 push 不触发，必须手动 dispatch）
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch \
  --repo logicflow-GYW/RikkaMinis --workflow build-apk.yml --ref fix/zone-guard-parent-1119

# 全量扫描门：本支按任务书**不跑**（本地现在按设计拒绝 rc=3，属预期）
```

**全量门未跑的说明**：`scripts/scan/**` 本支未触碰，本地全量门现在会 rc=3 拒绝执行（1119 轮次起的设计），所以「绿」的证据来自 JVM 装置 + CI 的 `unit-tests` / `scan-gate` 两个 job，而非本地全量扫描。
