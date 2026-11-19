# FIX-3D1119-A — 受保护区守卫：`$(…)` 截断（P0）+ 兜底词法不同步 + 三处误拒/文案

- 分支：`fix/zone-lex-1119b`，基线 `ecb03d92`（= 当前 main）
- 代码提交：`582555ff`（单提交，实现 + 测试 + 本报告之外的说明都在里面；报告随后单独提交）
- 来源报告：`meta-audit/reports/r3d1119/1_Guard_Report.md`（形态 B，5 条）
- 台账：`meta-audit/r3d1119/TRIAGE.md`（主会话已实测每条）
- 任务书：`meta-audit/r3d1119/TASK-A-guard.md`
- 上一轮同一文件：`fix/zone-guard-parent-1119`（2D-1119，已合 main `ecb03d92`）——本轮修的是**那轮修复在最终态下剩下的 5 条**

---

## 1 范围

**这改变什么用户行为？**

- **P0（F-02）**：`rm -rf $(echo /tmp/x) /var/minis/shared` 以前**静默放行** —— 一条命令清空整个受保护区。现在与 `rm -rf /var/minis/shared` 走完全相同的拒绝路径（同一个 `wideDeleteHit` → 同一份文案 → 同一个 `protected_zone` 拒绝码）。
- **F-03a**：`(rm -rf /var/minis/shared) && echo 'unclosed` 同样静默放行（子 shell + 未闭合引号），现在被拒。
- **F-01（误拒方向）**：`rm -rf /tmp/var && echo 'unclosed` 以前被**误拒**（`indexOf("/var")` 命中 `/tmp/var` 中段），现在放行。误拒的代价不是「少删一个目录」：被误拒的 agent 会改用 `find -delete` 绕开守卫，那比绕过本身更糟。
- **F-04**：`rm -rf /var/minis` / `rm -rf /var` 的拒绝文案以前**缺** `confirm which files` 那句（比删单个区还弱），现在与单区同级。
- **F-05（误拒方向）**：`mv -S <suffix> <src> <dst>` / `mv --suffix <suffix> <src> <dst>` 以前把后缀值当源路径 ⇒ 误拒，现在放行。

**误拒面**：F-02/F-03a 收窄了漏放行面，F-01/F-05 收窄了误拒面。三条声明过的天花板**一条没扩**（`$VAR`、glob、`find -delete`、`rsync --delete`、`bash -c`），并且各有专条用例 + 变异臂从两个方向钉住（见 §5）。

**碰的文件**（仅此三个）：

- `src/android/app/src/main/java/com/rikkaminis/app/sandbox/ProtectedZoneGuard.kt`（+153/−25）
- `src/android/app/src/test/java/com/rikkaminis/app/sandbox/ProtectedZoneGuardWordsTest.kt`（+237/−1，新增 `ProtectedZoneGuardLexTest` 11 条 + 1 条既有断言随语义更正）
- `fix-reports/FIX-3D1119-A-guard.md`（本报告）

**没碰**：`scripts/scan/**`（已 grep 确认没有门锚定本文件或其文案）、`ExecutionCoordinator.kt`、`FastioOffloadHandler.kt`、`ZoneDeleteGuardStore.kt`、任何 `strings.xml`、其它支独占文件。**没有跑全量 `scan.sh` / `test_scan.py` / gradle**；JVM 装置全部 `nice -n 10`，串行单实例。

---

## 2 逐条先证与结论

先证方式：改代码**之前**先跑探针 `shared/work/r3d1119/guard-jvm/Probe.kt`（45 例，含 12 条必须保持不变的边界），在**当前基线** `ecb03d92` 上得到 `MISMATCHES: 14`。每条的机制都是探针实测出来的，不是照报告行号改的。

| ID | 基线（探针实证） | 根因（真码位置） | 修法一句 | 钉住的用例 |
|---|---|---|---|---|
| **F-02（P0）** | `rm -rf $(echo /tmp/x) /var/minis/shared` → **放行**；`$((1+2))` 同；嵌套 `$(cat $(echo x))` 同 | `shellLex` 的 `c == '(' \|\| c == ')'` 分支**无条件** `endWord(); endStatement()` | 按上下文分三类：`$((` 算术（一个带 `$` 的不透明词）、`$(` 替换（interior 按自己的语句判定，`)` 之后**继续外层语句**，用词表栈支持嵌套）、裸 `(`/`)` 才是分隔符 | `a substitution does not cut the verb off from its targets`、`the interior of a substitution is judged and arithmetic stays opaque`、`the words after a substitution stay arguments of the outer verb`、`an unclosed substitution is refused` |
| **F-03a（P1）** | `(rm -rf /var/minis/shared) && echo 'unclosed` → **放行** | `unbalancedFallback` 的词切分用 `[\s;&\|\n]+`、`zoneWideLiteral` 的终止符不含 `)` ⇒ 动词读成 `(rm`、路径读成 `…shared)`，双双不匹配 | 抽 `RAW_WORD_DELIMITERS`（含 `()` `{}` `<>`）作**唯一**分隔符集，词切分与路径扫描共用一个常量 | `the fail-closed scan reads parentheses as delimiters` |
| **F-01（P2，误拒）** | `rm -rf /tmp/var && echo 'unclosed` → **被拒**（应放行）；`/opt/var/minis`、`/tmp/var/minis` 同 | `zoneWideLiteral` 用裸 `indexOf(zone)`，不校验左边界 | 命中位置必须满足 `start == 0 \|\| isRawPathBoundary(command[start-1])`（分隔符 / 引号 / 反斜杠 / `=`） | `a zone literal must start at a word boundary in the fail-closed scan` |
| **F-04（P2，文案）** | `/var/minis` 文案缺 `confirm which files`，`/var/minis/shared` 有 | `refusalFor` 用 `zoneOf(hit.path)` 判断，而 `zoneOf` 刻意不含 `ROOT_ONLY_ZONES` ⇒ 父目录恒为 null | 判据改成 `(zone != null && hit.path == zone) \|\| hit.path in ROOT_ONLY_ZONES`；**其余措辞一字未动** | `the parent zones get the same referent confirmation as a zone root` |
| **F-05（P2，误拒）** | `mvSources(listOf("-S","/var/minis/shared","/tmp/a","/tmp/b"))` = `[/var/minis/shared, /tmp/a]`；`--suffix` 同病 | `mvShortCluster` 遇 `'S'` 返回 `Pair(false,false)`（不吃下一词）；`mvSources` 无 `--suffix` 分支 | 簇尾的 `S` 返回 `Pair(false, true)`；`--suffix` / `--suffix=` 按带值长选项消费 | `mv short suffix option consumes its value instead of treating it as a source`、`mv long suffix flag consumes its value`、`stdbuf -o alone still reaches the verb`（任务书点名的独立锚点） |

**证伪情况**：**0 条**。任务书提到的 `1 F-03b` 已被主会话证伪，本支**没有复活**（探针里 `ls … && rm /var/minis/shared/README.md && echo "don't"` 仍放行，与 TRIAGE 一致）。

**两条边界同时钉**：每条修法都有「放行侧」用例。F-02 的放行侧是 `mv $(echo /tmp/a) /var/minis/shared`（移入不是删）、`rm -rf $(echo /tmp/x) /tmp/build`；F-03a 的放行侧是 `(rm -rf /var/minis/shared/work/batch) && 'unclosed`（深路径）；F-01 的放行侧是 5 条 `/tmp/var` 家族；F-05 的放行侧是 `-S` 两种形态。

---

## 3 修法细节（判据层）

### 3.1 `$(` / `$((` / `(` 三分类（F-02）

```kotlin
c == '(' -> {
    if (wordStarted && word.length == 1 && word[0] == '$') {
        word = StringBuilder(); wordStarted = false
        if (i + 1 < command.length && command[i + 1] == '(') {
            // `$((…))`：表达式不是命令，整体当一个带 `$` 的词
            val close = arithmeticEnd(command, i)
            word.append(command, i - 1, close + 1)   // 保留 `$`，让它对 absoluteGuestPath 不透明
            wordStarted = true; i = close
        } else {
            // `$(`：interior 自己跑（它的词要被判定），`)` 之后回到外层语句
            outerWords.add(words); words = ArrayList()
        }
    } else { endWord(); endStatement() }   // 裸 `(` 才是真分隔符
}
c == ')' -> {
    if (outerWords.isNotEmpty()) {         // 替换结束：先落 interior 的语句，再取回外层词表
        endWord()
        if (words.isNotEmpty()) statements.add(stripRedirections(words))
        words = outerWords.removeAt(outerWords.size - 1)
    } else { endWord(); endStatement() }
}
```

要点三条：

1. **`$((` 的词带 `$`**：`absoluteGuestPath` 对含 `$` 的 token 返回 null（`$VAR` 同一处天花板），所以算术结果永远不会被当成路径 —— 但它是**一个词**，不会切断语句。
2. **`$(` 的 interior 是真的会跑**：`echo $(rm -rf /var/minis/shared)` 仍被拒（interior 的 `rm` 是独立语句，动词+路径都在）。
3. **未闭合的 `$(` 判 `parseOk = false`**：与未闭合引号同一条 fail-closed 路径，不拿前缀下判断。

### 3.2 兜底路径共用一套分隔符（F-03a）

```kotlin
private const val RAW_WORD_DELIMITERS = " \t\r\n;&|(){}<>"
private fun rawWords(command: String): List<String>   // unbalancedFallback 用
private fun zoneWideLiteral(...)                      // 同一个常量判终止符
```

两处各写一份正则正是这条 bug 的成因：词切分只认空白 ⇒ 动词是 `(rm`；路径扫描不认 `)` ⇒ 路径是 `shared)`。抽成常量后两者不可能再分叉。

### 3.3 左边界（F-01）

```kotlin
if (start == 0 || isRawPathBoundary(command[start - 1])) { … }
```

`isRawPathBoundary` = 分隔符 / 引号 / 反斜杠 / `=`（`DST=/var/minis` 这种赋值值也要能被看见）。右边界原本就有（遇分隔符或引号即止），所以 `/var/minis2` 不会被读成 `/var/minis` —— 这条在基线就已经正确，本支只是补了左侧。

### 3.4 文案（F-04）

只改判据，不改字符串。`ProtectedZoneGuardTest.theRefusalNamesTheTargetAndDoesNotPointAtAWayAroundIt`（既有）继续绿，说明「不指向开关 / 不指向 Settings」的约束没被破坏。

### 3.5 `mv` 的 `-S` / `--suffix`（F-05）

`-S.bak` 仍按「簇内后续字符全是后缀」处理（`Pair(false, false)`，不吞下一词）；只有**簇尾**的 `S` 才返回 `Pair(false, true)`。长选项 `--suffix` 单独一支，`--suffix=x` 不吞下一词。

---

## 4 证据

### 4.1 先证探针（改代码前，基线 `ecb03d92`）

`shared/work/r3d1119/guard-jvm/Probe.kt`（45 例），基线跑出 **14 处 MISMATCH**：

```
F-02 subst           true   false  MISMATCH   rm -rf $(echo /tmp/x) /var/minis/shared
F-02 arith           true   false  MISMATCH   rm -rf $((1+2)) /var/minis/shared
F-02 subst-deep      true   false  MISMATCH   rm -rf $(echo /tmp/x) /var/minis/shared/work
F-02 nested          true   false  MISMATCH   rm -rf $(cat $(echo /tmp/x)) /var/minis/shared
F-03a subshell       true   false  MISMATCH   (rm -rf /var/minis/shared) && echo 'unclosed
F-01 /tmp/var        false  true   MISMATCH   rm -rf /tmp/var && echo 'unclosed
F-01 /opt/var/minis  false  true   MISMATCH   rm -rf /opt/var/minis && echo 'unclosed
F-01 /tmp/var/minis  false  true   MISMATCH   rm -rf /tmp/var/minis && echo 'unclosed
F-05 -S next-word    false  true   MISMATCH   mv -S /var/minis/shared /tmp/a /tmp/b
F-05 --suffix …      false  true   MISMATCH   mv --suffix /var/minis/shared /tmp/a
F-05 -fS mix         false  true   MISMATCH   mv -fS /var/minis/shared /tmp/a
F-04 /var/minis      confirm-sentence=false
F-04 /var            confirm-sentence=false
lex rm+$(echo)  = [[rm, -rf], [echo, /tmp/x], [/var/minis/shared]]
MISMATCHES: 14
```

### 4.2 修后探针（`guard-jvm/probe-final.log`）

**`MISMATCHES: 0`**（45 例全对）。关键行：

```
lex rm+$(echo)  = [[echo, /tmp/x], [rm, -rf, /var/minis/shared]]
lex echo$(rm)   = [[rm, -rf, /var/minis/shared], [echo]]
lex $((1+2))    = [[rm, -rf, $((1+2)), /var/minis/shared]]
mvSources(-S …)        = [/tmp/a]
mvSources(--suffix …)  = []
mvSources(-S.bak …)    = [/var/minis/shared]
stdbuf -o L rm -rf zone = true
F-04 /var/minis      confirm-sentence=true
F-04 /var            confirm-sentence=true
F-04 /var/minis/shared confirm-sentence=true
```

12 条必须保持不变的边界全绿：`case … in foo) rm -rf <zone> ;; esac` 仍拒、`awk '{print $1}'` 不误判、`python3 -c 'shutil.rmtree'` / `find -delete` / glob / 花括号展开 / `rm -rf <zone>/work/one-batch-dir` / `rm <zone>/README.md` / `grep -rn 'rm -rf' <zone>` 全部仍放行。

### 4.3 本地 JVM 装置（`shared/work/r3d1119/guard-jvm/`，非 gradle）

`sh run.sh` → **90 条用例，0 失败**（`local-green.log`，JUnit RunListener 逐条打印，不是汇总）：

| 测试类 | 条数 | 结果 |
|---|---:|---|
| `ProtectedZoneGuardTest` | 23 | 全绿（既有，一字未改） |
| `ZoneDeleteGuardStoreTest` | 3 | 全绿（既有） |
| `offload/FastioZoneGuardTest` | 10 | 全绿（既有，接线形状探针） |
| `ProtectedZoneGuardWordsTest` | 31 | 全绿（既有） |
| `ProtectedZoneGuardParentZoneTest` | 12 | 全绿（既有，2D-1119 新增） |
| **`ProtectedZoneGuardLexTest`** | **11** | 全绿（**本支新增**） |
| **合计** | **90** | **FAILED 0** |

11 条新用例逐条 PASSED：

```
a substitution does not cut the verb off from its targets
the interior of a substitution is judged and arithmetic stays opaque
the substitution does not widen the refusal surface
an unclosed substitution is refused
the words after a substitution stay arguments of the outer verb
the fail-closed scan reads parentheses as delimiters
a zone literal must start at a word boundary in the fail-closed scan
the parent zones get the same referent confirmation as a zone root
mv short suffix option consumes its value instead of treating it as a source
mv long suffix flag consumes its value
stdbuf -o alone still reaches the verb
```

### 4.4 改了一条既有断言（必须写清楚）

`ProtectedZoneGuardParentZoneTest.subshell and group separators do not hide the verb` 里有一条形状断言：

```kotlin
// 旧（2D-1119 写的）：[[echo], [rm, -rf, /var/minis/shared]]
ProtectedZoneGuard.shellLex("echo \$(rm -rf /var/minis/shared)").statements
```

旧断言把 `echo` 的**空参数**当成独立语句先落，再把 `$(…)` 的 interior 落成第二条 —— 那是「`$(` 切断了外层语句」的副产物，正是本支要修的 P0 形态。改成 `[[rm, -rf, /var/minis/shared], [echo]]`（interior 先跑、外层后完），并**新增**一条断言钉住 `rm -rf $(echo /tmp/x) /var/minis/shared` 是「interior 一条 + 外层一条，动词与目标同在外层」。拒绝侧断言（5 条 `assertNotNull`）一字未动。

### 4.5 CI

**代码提交**：`582555ff`（实现 + 测试）。其后所有提交都是**纯报告文本**（`git diff 582555ff..HEAD -- src/` 为空，非报告差异为 0 —— 这个不变量在 §7 有核验命令）。分支 push 不触发 CI（`build-apk.yml` 只认 main），按纪律手动 dispatch。

**证据链**（每个 tip 都真跑了一次，head_sha 与当时的分支 tip 逐字符一致）：

| run | 提交 | 结论 |
|---|---|---|
| **#3313** / `44439038155` | `582555ff`（代码 tip） | 五 job success |
| #3315 / `44442875147` | `e5134609`（报告） | 五 job success |
| #3317 / `44444893574` | `e7567085`（报告修订） | **lint 失败** —— 见下方说明，与本支无关 |
| #3319 / `44447240843` | `e7567085`（重跑） | 五 job success |
| **#3321** / `44449914872` | `b4869a3325f800d62e4508b17e88dcdb71ce7919` | 五 job success |

**#3317 的真失败不是本支造成的**：lint job 挂在 `:app:generateDebugAndroidTestLintModel`，原因是 `Could not find hamcrest-integration-1.3.jar` / `hamcrest-library-1.3.jar` / `javawriter-2.1.1.jar`（`debugAndroidTestRuntimeClasspath` 依赖解析失败，Maven 侧抖动）。同一棵树前后两次 run 的 lint 均 success，重跑即恢复。**没有改任何 build 文件来「救」它**。

**最终代码树的证据 = #3321**：`.kt` 文件自 `582555ff` 起一字未改，所以 #3321 覆盖的就是本支交付的全部代码。

| job | conclusion | 证据（#3321） |
|---|---|---|
| unit-tests | **success** | 含 `PASSED` 的行 **9835** 条；含 `FAILED` 字样的行 **6** 条，`grep FAILED \| grep -vc 'PASSED$'` = **0**，全部是 PASSED 用例名里带 FAILED 字样（`slot denied produces FAILED without calling provider` 等）；11 条新用例用 `grep -cF "<用例名> PASSED"` 逐条核对，**11/11 = 1 命中** |
| scan-gate | **success** | `Summary: 115 passed, 0 failed`；日志明写 `scripts/scan/** untouched in this push — skipping the fixture self-test (~121s); scan.sh ran all 115 gates over this tree`（本支未碰扫描门，夹具按机制跳过，故本次「绿」**不含**夹具证据） |
| lint | **success** | — |
| build-apk | **success** | — |
| publish | **success** | main 门控生效：`Move the rolling tag android-latest` / `Publish to Releases` 两步 **skipped**，run 级 conclusion 仍为 success |

**未合 main**（任务书 §5：CI 绿 + head_sha 一致即停止节点）。

---

## 5 变异负控（8 臂，8/8 RED-PRECISE）

装置：`shared/work/r3d1119/guard-jvm/mutate.py`（**工作树之外**）。每条臂回退**一处**修复子句，跑完整 JVM 装置，记录哪些用例变红；还原方式是**字节快照 + `finally` 还原 + sha256 逐字节校验**（不用 `git checkout`，脚本崩溃也不会把变异烧进工作区）。脚本还有两道保护：

- **preflight**：跑之前先 `git show HEAD:<file>` 与工作区逐字节比对，不一致直接 `exit 4`（防止上一次崩溃留下的残留让「基线」看起来是红的）；
- **harness 重试**：PRoot 偶发 `Errno 38 ENOSYS` 让 `subprocess` 崩，重试 3 次，且还原在 `finally` 里。

| 臂 | 回退的子句 | 变红的用例 | 结果 |
|---|---|---|---|
| M1 | 整个 `(` 分支退回 `endWord(); endStatement()`（即 2D-1119 的 P0 形态） | 5 条（F-02 的 4 条 + ParentZone 的形状断言） | RED-PRECISE |
| M2 | `)` 不再把外层词表交回去 | 3 条 | RED-PRECISE |
| M3 | 兜底词切分退回 `[\s;&\|\n]+` | 1 条 | RED-PRECISE |
| M4 | 兜底路径扫描不认 `)` | 1 条 | RED-PRECISE |
| M5 | 去掉左边界校验 | 1 条 | RED-PRECISE |
| M6 | 父目录丢掉 `noReferent` | 1 条 | RED-PRECISE |
| M7 | `-S` 不吃下一词 | 1 条 | RED-PRECISE |
| M8 | `--suffix` 不消费 | 1 条 | RED-PRECISE |

```
PRECISION: 8/8 arms RED-PRECISE
final sha256: 387378d29f9fa0e821c42639821606be712afb90ada055a3dc0b5717b7d14100
base  sha256: 387378d29f9fa0e821c42639821606be712afb90ada055a3dc0b5717b7d14100
```

M1/M2 各翻多条是**文档化预期重叠**（不止一条用例钉同一子句），不是精度不足：多红集合完全落在该臂语义覆盖内，没有一条无关用例变红。**修脚本时踩到两个真坑，值得记下来**：

1. **第一次 8 臂全报 `RESTORE-FAILED`** —— 原因是脚本先比对 sha 再还原，比对的那一刻文件里还是变异内容，这个检查**永远**会失败。改成「先还原再校验」。这类「顺序写反导致恒失败」的检查很容易被当成环境问题忽略掉。
2. **第一次跑完 M5 后进程崩在 `Errno 38`**，变异残留在工作区；下一次跑直接把「基线红」当成发现。两道保护（preflight + `finally`）是这次真踩到之后加的，不是预防性设计。

---

## 6 残留与天花板

**本支没覆盖、且报告没点名的词法洞（只登记不修，避免扩半径）**：

1. **`$((` 未闭合**：`rm -rf $((1+2 /var/minis/shared` 会把 `$` 之后整段吞成一个词，结果是不透明词 ⇒ 放行。真 shell 会报语法错不执行，所以实际风险为零；但严格说这是「未闭合判据只覆盖 `$(` 与引号」的缺口。
2. **`$(…)` 内的重定向**：`rm -rf $(cat x > /tmp/y) <zone>` 的 interior 有 `<`，`shellLex` 的 `<`/`>` 分支在 interior 里同样按重定向处理（interior 是独立语句，行为与顶层一致），这条**已覆盖**，列在此处只为说明我检查过。
3. **`--suffix` 的短形式 `-S` 与 `-t` 同簇**：`mv -St /tmp <zone>` —— `S` 在簇中间时后续字符全是后缀，`t` 不被读成 target flag。真实 `mv` 的 `-S` 确实吃掉剩余字符，所以这条与 GNU 行为一致，**不是缺陷**，但它是「簇内顺序敏感」的一个易错点。
4. **`zoneWideLiteral` 的右边界不校验 `/` 后字符**：`/var/minis2` 已正确（`isWideTarget` 的 `zoneOf` 要求 `== zone` 或 `zone + "/"`），但 `/var/minis.bak` 这类会被读成 `/var/minis` 然后 `.bak` 留在路径里 ⇒ 不是 wide ⇒ 放行。真实风险极低（引号不平衡时才会走到这里），**登记不修**。

**既有天花板（一条没扩）**：`$VAR` 目标、glob、`find -delete`、`rsync --delete`、`bash -c '…'`、`eval "整条命令"`、花括号展开。每条都有专条用例钉住「仍放行」，其中三条（`find -delete` / glob / 花括号展开）在 `ProtectedZoneGuardParentZoneTest` 里，一条（`python3 -c`）在 `ProtectedZoneGuardTest` 里。

**没跑的东西**：全量 `scan.sh` / `test_scan.py` / gradle（任务书明令禁止）。因此本支的本地证据只覆盖守卫家族，其余模块的行为变化由 CI 的 unit-tests job（9835 PASSED / 0 真失败）承担。

---

## 7 复现步骤

```sh
# 1) 先证（基线代码）
cd /var/minis/shared/work/r3d1119/guard
git stash list                      # 应当为空；本支工作区 = 582555ff
cd /var/minis/shared/work/r3d1119/guard-jvm
# 把 PROD 换成 git show ecb03d92:<file> 的版本即可复现「修前」
nice -n 10 python3 mutate.py        # 变异负控（8 臂），会自己跑 preflight

# 2) 本地装置（90 条用例）
sh run.sh

# 3) 探针（45 例，含 12 条边界）
WT=/var/minis/shared/work/r3d1119/guard
LIB=/var/minis/shared/work/jvm-libs
nice -n 10 kotlinc -no-stdlib -cp "$LIB/kotlin-stdlib-2.1.0.jar:$PWD/stub" \
  "$WT/src/android/app/src/main/java/com/rikkaminis/app/sandbox/ProtectedZoneGuard.kt" \
  Probe.kt -d pout
nice -n 10 java -cp "pout:$LIB/kotlin-stdlib-2.1.0.jar:$PWD/stub" ProbeKt
```

**真机复现（用户视角）**：设置里「保护文件夹」开启时，让 agent 跑
`rm -rf $(echo /tmp/x) /var/minis/shared` —— 修前会真的删掉（不要真跑），修后返回
`refusing to delete '/var/minis/shared': it is a protected zone …`。
