# FIX-1118C-C — 受保护区删除守卫的四处词法绕过（F-01…F-04）

- 分支：`fix/zone-guard-words-1118c`，基线 `97768739`，实现提交 `09fae786`（报告为第二个提交）
- 来源：`meta-audit/reports/r1118b/Fix_1118b_C_Zone_Guard_Report.md`（4 条，全部 P1）；台账 `meta-audit/r1118c/TRIAGE.md`
- 任务书：`meta-audit/r1118c/TASK-C-zone-guard.md`
- 上一轮：`fix/zone-guard-1118b`（已合 main）修了 `busybox` / 组合短 flag / 单对引号包裹整条路径 —— 本轮修的是**同一处 `wideDeleteHit` 的词法层**剩下的四个口子

---

## 1 范围

**这改变什么用户行为？** 11 种写法（`mv -t`、多源 `mv`、赋值前缀、`\rm`/`"rm"`、`nice`/`timeout`/`setsid` 等包装器、含空格的引号路径）以前**静默放行、真的会把整个受保护区删掉**；现在它们走与 `rm -rf /var/minis/shared` 完全相同的拒绝路径，文案一字不改。误拒面只有一处新增：**引号不配对的命令**，且句中同时出现 wide zone 字面量与破坏性动词时拒绝（窄兜底，见 §3.5）；另有一处误拒**减少**：重定向（`2>&1`、`> x`）以前会被当成相对路径参数，现在正确丢弃。

**碰的文件**（仅此三个）：

- `src/android/app/src/main/java/com/rikkaminis/app/sandbox/ProtectedZoneGuard.kt`（+301/−31）
- `src/android/app/src/test/java/com/rikkaminis/app/sandbox/ProtectedZoneGuardWordsTest.kt`（新，31 条用例）
- `fix-reports/FIX-1118C-C-zone-guard.md`（本报告）

**没碰**：`scripts/scan/**`（`grep -rn ProtectedZoneGuard scripts/` = 0 命中，扫描门与本守卫无文本锚点）、`FastioOffloadHandler.kt`、`ExecutionCoordinator.kt`、`ZoneDeleteGuardStore.kt`、任何 `strings.xml`、`ChatInputArea.kt`、`ChatPromptAndTools.kt`。

## 2 逐条先证（基线复现，不是照抄报告）

基线 = 分支起点 `97768739`，用主会话留下的 JVM 探针形态在本分支重跑（`r1118c/zoneguard-jvm/probe/Probe.kt`，输出 `out/probe_baseline.txt`）：

```
== A. must be REFUSED (11 bypasses) ==    mismatches: 11
mv -t /tmp /var/minis/shared              NOT refused  !! BYPASS
mv /tmp/a /var/minis/shared /tmp/dst/    NOT refused  !! BYPASS
env FOO=1 rm -rf /var/minis/shared       NOT refused  !! BYPASS
env -i FOO=1 rm -rf /var/minis/shared    NOT refused  !! BYPASS
FOO=1 rm -rf /var/minis/shared           NOT refused  !! BYPASS
\rm -rf /var/minis/shared                NOT refused  !! BYPASS
"rm" -rf /var/minis/shared               NOT refused  !! BYPASS
nice -n 10 rm -rf /var/minis/shared      NOT refused  !! BYPASS
timeout 5 rm -rf /var/minis/shared       NOT refused  !! BYPASS
setsid rm -rf /var/minis/shared          NOT refused  !! BYPASS
rm -rf "/var/minis/shared/my folder"     NOT refused  !! BYPASS
```

**11 条全部真实绕过，没有一条是「本来就拦住了」**；B 组（1118B 基线）与 C 组（不误伤）在基线上全绿，说明探针的对照是干净的。

| ID | 基线结论 | 根因（真码位置） | 修法一句 | 钉住的用例 |
|---|---|---|---|---|
| **F-01** | 成立，2 例绕过 | `mv` 的 targets 用 `args.filterNot{…}.take(1)`，只判第一个位置参数；`-t DIR` 把目标写成选项值，多源 `mv a b c` 的前两个都是源 | 新增纯函数 `mvSources(args)`：认 `-t`/`-tDIR`/`--target-directory[=DIR]`（全部位置参数都是源），否则除最后一个外全是源，逐个判定 | `mv with a target directory flags the zone as a source`、`mv with several sources judges all of them`、`mv long target directory flags the zone as a source`、`mv sources are every positional except the destination`、`mv with a target directory makes every positional a source` |
| **F-02** | 成立，3 例绕过 | wrapper 循环只跳 `-` 开头 token，遇到 `KEY=VALUE` 立刻 `break`，赋值串被当动词 | 赋值 token 在任何位置都跳过（verbIndex 循环 + wrapper 参数消费各一份，见 §3.2/§3.3） | `a leading assignment is not the verb`、`an assignment after a wrapper is not the verb` |
| **F-03** | 成立，5 例绕过 | `verb` 取自 `substringAfterLast('/')` 且**没做 unquote / 反斜杠剥离**；`WRAPPERS` 只枚举 7 个名字 | 词法层先解析：`\rm`、`"rm"`、`'rm'`、`/bin/rm` 统一成 `rm`；wrapper 集合补 `nice`/`ionice`/`timeout`/`setsid`/`stdbuf`/`taskset`/`chrt`/`doas` 并按类吃参数 | `a backslash or quoted verb is the same verb`、`process wrappers are peeled before the verb` |
| **F-04** | 成立，1 例绕过 | `statement.split(Regex("\\s+"))` 硬切，不认引号边界 ⇒ 含空格的引号路径被拆成两半各自引号失衡，`absoluteGuestPath` 返回 null 放行 | 改为词法层按引号成词，引号内的空白不切，引号内容原样保留为一个 word 的字符 | `a quoted path containing a space stays one wide target`、`quotes keep a path with a space in one word` |

## 3 修法：判据哲学换了 —— 先做词法，再做判定

**不再在 `split(Regex("\\s+"))` 上打补丁。** 旧判据把「字符串切割」和「语义判定」混在一步里，于是引号、反斜杠、赋值、重定向、包装器参数这些**词法事实**全部漏进判定层，每一个都成了口子。现在分两步：

### 3.1 `shellLex(command): ShellLex`（新增纯函数，词法层）

- 按空白分隔，但尊重 `'…'` 与 `"…"`：引号内空白不切，引号本身从它包裹的 word 里去掉 —— 拆词后每个 word 就是 shell 会交给 exec 的字面值；
- 引号外的反斜杠转义按 shell 语义处理（`\r` → `r`、`\ ` → 空格、`\\` → 单个 `\`）；引号内只有 `"` 里 `\` 是转义符（`'…'` 内 `\` 是字面量）；
- 一种引号里的另一种引号是字面量（`"it's here"` 不切分、`'a "b" c'` 是一个 word）；
- 分隔符 `; & | 换行` **只在引号外**切句（`echo "a;b" && rm …` 仍是两条语句）；
- 重定向：`<`/`>`（含 `>>`、`>&`、`2>&1`）与其目标作为整词丢弃 —— `2` 是文件描述符，不是参数；
- 引号不闭合 ⇒ `parseOk = false`，返回的 `statements` 是真实命令的前缀，不可信。

### 3.2 赋值不是动词

`[A-Za-z_][A-Za-z0-9_]*=…` 形式的 token 在动词识别前**任意位置**跳过（`FOO=1 rm …`、`env -i FOO=1 rm …`）。

### 3.3 wrapper 按类消费参数

`WRAPPERS` 扩展到 15 个名字（`sudo`/`doas`/`env`/`time`/`nohup`/`command`/`exec`/`busybox` + `nice`/`ionice`/`timeout`/`setsid`/`stdbuf`/`taskset`/`chrt`），`skipWrapperArgs` 吃下三类属于包装器自己的 token：选项（带 `=` 的吃一个，`WRAPPER_VALUE_FLAGS` 里的吃两个）、数字（`timeout 5`、`chrt 50`、`taskset 0x3`）、再一个赋值。

`WRAPPER_VALUE_FLAGS` 只收**真的吃下一个词**的选项。**`-i` 故意不在里面** —— `env -i` 是布尔选项，把它当「吃一个词」会把 verb 吃掉，等于亲手造出一个新的绕过（这正是本轮要修的病）。

### 3.4 `mvSources(args)`（新增纯函数）

认 `-t DIR` / `-tDIR` / `--target-directory[=DIR]` ⇒ 全部位置参数都是源；否则除最后一个外全是源。每个源逐个走既有的 `isWideTarget` 判定，任一命中即拒。

### 3.5 解析失败 fail-closed（窄）

`parseOk = false` 时不做逐参数推理，只在**同时**满足两条时拒绝：

1. 原始文本里有 zone 字面量，且紧跟其后的那段文字解析后是 **wide** 目标（zone 根 或 其一级子目录）；
2. 该行里出现 `DESTRUCTIVE ∪ {mv}` 里的词。

两条都只是「整条语句里存在」的粗判，但**命中面被 wide 目标这一条收窄**：`rm -rf /var/minis/shared/work/batch" && echo Don't`（未配对的撇号是普通文本）仍放行，因为它没有把任何 wide 目标写在命令行里。`parseOk = true` 的路径绝不走这个兜底。

### 3.6 没有做的事（任务书两条禁令）

- **没有**改成「语句里同时出现破坏性动词和 zone 路径就拒」的全扫描。`grep -rn 'rm -rf' /var/minis/shared` 必须放行，本支新增用例钉住；
- **没有**扩 `DESTRUCTIVE` 去接 `rsync --delete` / `tar --remove-files` / `find -delete` / `bash -c '…'`（见 §6）。

## 4 证据

### 4.1 本地 JVM 装置（`r1118c/zoneguard-jvm/`，非 gradle）

`sh run.sh` → **`OK (67 tests)`**：新 `ProtectedZoneGuardWordsTest` 31 条 + 既有 `ProtectedZoneGuardTest` 23 条 + `ZoneDeleteGuardStoreTest` 3 条 + `FastioZoneGuardTest` 10 条，**0 失败**。既有 36 条未改一字（包括 `the guard is a pure function of path and recursion` 对 `refuseReason` 签名的断言 —— 签名仍是 `(String, String)`）。

31 条新用例逐条 PASSED（`out/pertest.txt`，JUnit RunListener 逐条打印，非汇总）：

```
mv with a target directory flags the zone as a source
mv with several sources judges all of them
mv long target directory flags the zone as a source
mv sources are every positional except the destination
mv with a target directory makes every positional a source
mv flags do not become sources
a leading assignment is not the verb
an assignment after a wrapper is not the verb
a backslash or quoted verb is the same verb
process wrappers are peeled before the verb
a quoted path containing a space stays one wide target
the already fixed spellings still refuse
a single file inside a zone is still allowed
a second level subdirectory of a zone is still allowed
moving a file into a zone is not a delete of the zone
moving one deep file out of a zone is still allowed
mv with a target directory moving one file out is allowed
a search that quotes a delete is still allowed
a wrapped read only command is still allowed
a delete from a cd inside a zone still refuses and the deeper one does not
redirections are not arguments
quotes keep a path with a space in one word
backslash escapes resolve inside a word
a quote inside the other quote kind is literal
statement separators are only separators outside quotes
an unterminated quote is reported as unreadable
the ceiling is unchanged
an unreadable command that names a wide zone target and a verb is refused
an unreadable command naming a whole immediate child of a zone is refused
an unreadable command with a deep zone path is not refused
an unreadable command with no zone path is not refused
```

### 4.2 11 绕过 / 5 基线 / 8 不误伤（探针，`out/probe_fixed.txt`）

| 分组 | 条数 | 结果 |
|---|---:|---|
| A 11 条绕过（任务书 §2 的 `NOT refused` 全列） | 11 | **全部 refused**，`mismatches: 0` |
| B 1118B 基线（`rm -rf`、`busybox`、整对引号、`-rfv`、`cd … && rm -rf work`） | 5 | 全部仍 refused |
| C 不误伤（任务书 §4 的 8 条 + 5 条额外：`ls -la`、`find -name`、`cp -r`、`rm -rf $DIR`、`rm -rf /var/minis/shared/*`、`python3 -c`） | 13 | 全部仍 allowed |

C 组就是任务书点名的 8 条：单文件、二级子目录、移入、单个文件移出、`-t` 移出单文件、`grep -rn 'rm -rf'`、`nice -n 10 ls`、`cd … && rm -rf work`（后者是**拒绝**，属既有行为的回归保护，`work/sub` 那条才是放行）。

### 4.3 CI

分支 CI **#3271 / run `44341644370`**，`head_sha = 09fae78694cb46ec26dc2ecc31ecfe363a816847`，与本地 `git rev-parse HEAD` **逐字符一致**（实现提交 `09fae786`）。event = `workflow_dispatch`（分支 push 不触发 CI，按纪律手动 dispatch）。

| job | 结果 | 证据 |
|---|---|---|
| unit-tests | **success** | `BUILD SUCCESSFUL in 6m 24s`；`9740` 条 `> … PASSED`，`> … FAILED` **0** 条；31 条新用例逐条在列（用 `grep -cF "<用例名> PASSED"` 逐条核对，31/31 = 1） |
| scan-gate | **success** | `Summary: 115 passed, 0 failed`；`scripts/scan/** untouched in this push — skipping the fixture self-test`（本支未碰扫描门，夹具按机制跳过，故本次「绿」不含夹具证据） |
| lint | **success** | `Run :app:lintDebug` success |
| build-apk | **success** | 20 步全 success（含 `Build APK with Gradle`、`Verify APK signature`、`Verify APK contents`） |
| publish | **success** | `Move the rolling tag android-latest` / `Publish to Releases` 两步 **skipped**（main 门控生效，未污染 release） |

报告提交 `acc82f04` 是纯文档，按 GLOBAL 纪律不 dispatch、不等 CI；其 diff 仅 `fix-reports/FIX-1118C-C-zone-guard.md`（+200），「报告之外差异为空」。

## 5 变异负控（8 臂，`r1118c/zoneguard-jvm/mutate.py`）

每臂翻转**一个**决定，重跑整套 JVM，要求该臂自己的用例变红、其余（含不误伤组）仍绿。快照 + `finally` 还原 + sha256 逐字节校验；变异前后 `ProtectedZoneGuard.kt` 的 sha256 不变（`22c1a125…d44d`）。

| 臂 | 翻转的决定 | 结果 |
|---|---|---|
| M1 | `mv` 只判第一个位置参数（回退 `.take(1)`） | **RED-PRECISE** → `mv with several sources judges all of them`、`mv sources are every positional except the destination` |
| M2 | 去掉动词扫描前的赋值跳过 | **RED-PRECISE** → `a leading assignment is not the verb` |
| M3 | 引号/反斜杠不解析（词法层退回字面字符） | **RED-PRECISE** → 引号 5 条 + 词法 4 条（含 `a quoted path containing a space stays one wide target`、`a backslash or quoted verb is the same verb`、`an unterminated quote is reported as unreadable`） |
| M4 | 回退 `\s+` 硬切（用旧切法构造 `ShellLex`） | **RED-PRECISE** → 28 条，覆盖 A/B/C 三组（回归性臂，红面是并集，属预期） |
| M5 | `-t` 不改变哪些位置参数是源 | **RED-PRECISE** → `mv with a target directory flags the zone as a source`、`mv long target directory flags the zone as a source`、`mv with a target directory makes every positional a source` |
| M6 | fail-closed 兜底改成放行 | **RED-PRECISE** → `an unreadable command that names a wide zone target and a verb is refused`、`an unreadable command naming a whole immediate child of a zone is refused` |
| M7 | 重定向不当成分词（`<`/`>` 当普通字符） | **RED-PRECISE** → `redirections are not arguments` |
| M8 | 去掉 wrapper 侧的赋值跳过 | **RED-PRECISE** → `an assignment after a wrapper is not the verb` |

`BASELINE-AFTER-RESTORE: OK`，8 臂全 RED-PRECISE，无 BLIND、无 RED-IMPRECISE。

**M8 值得单独记一笔（本轮最有价值的自检发现）**：第一版该臂**BLIND**。原因不是测试没用，而是**覆盖有冗余** —— `env FOO=1 rm -rf …` 这条用例里，赋值紧跟动词，`verbIndex` 自己的赋值跳过已经把它救回来了，wrapper 侧那条跳过根本没有被用到。补上 `env FOO=1 -u HOME rm -rf …`（赋值夹在 wrapper 选项和动词之间，只有 wrapper 侧跳过能到达 `rm`）之后，该臂立刻 RED-PRECISE。**教训：变异 BLIND 时先问「这个决定是不是有两条路径都能兜住」，别急着删测试。**

## 6 未修边界（R7，词法守卫自认天花板，未变）

`rm -rf $X`、`rm -rf /var/minis/shared/*`、`find … -delete`、`xargs rm`、解释器一行命令（`python3 -c '…shutil.rmtree…'`、`bash -c 'rm -rf …'`、`rsync --delete`、`tar --remove-files` 这类**不在词表里的动词**）**仍然放行**，且本支新增用例 `the ceiling is unchanged` 把它们钉住。词法层只能看见字面词，看不见变量、glob 与被解释器展开的字符串；真要对齐这些形状要走路径解析层（`FastioOffloadHandler` 的 `isRefusedDelete(guestPath, recursive)` 那道，它是本守卫同一份谓词的调用方，两条路径不会对政策产生分歧）。这条边界与上一轮报告写明的一致，本轮没有扩大也没有收窄。

## 7 文件清单与收口

| 文件 | 状态 | 说明 |
|---|---|---|
| `src/…/sandbox/ProtectedZoneGuard.kt` | 改 | +301/−31：`shellLex` / `verbIndex` / `skipWrapperArgs` / `mvSources` / `unbalancedFallback` / `zoneWideLiteral`，`WRAPPERS` 扩到 15、`WRAPPER_VALUE_FLAGS` 扩到 12、`REDIRECT` 哨兵 |
| `src/…/sandbox/ProtectedZoneGuardWordsTest.kt` | 新 | 31 条用例（11 绕过 + 基线 + 8 不误伤 + 词法/`mvSources` 纯函数 + fail-closed + 天花板） |
| `fix-reports/FIX-1118C-C-zone-guard.md` | 新 | 本报告 |

**装置**（不在仓库内，`/var/minis/shared/work/r1118c/zoneguard-jvm/`）：`run.sh`、`mutate.py`、`RunTests.java`、`probe/Probe.kt`、`stub/`（Android `Context`/`SharedPreferences`/`VisibleForTesting` 桩，复用 r1118b 的编译产物）。

## 8 复现命令

```sh
# 单测（67 条，非 gradle）
sh /var/minis/shared/work/r1118c/zoneguard-jvm/run.sh

# 逐条打印（每条用例的 PASSED/FAILED）
javac -cp "$LIB/kotlin-stdlib-2.1.0.jar:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar" \
  -d /var/minis/shared/work/r1118c/zoneguard-jvm/out/runner \
  /var/minis/shared/work/r1118c/zoneguard-jvm/RunTests.java

# 11 绕过 / 5 基线 / 13 不误伤 探针
sh /var/minis/shared/work/r1118c/zoneguard-jvm/run.sh
kotlinc -no-stdlib -cp $LIB/kotlin-stdlib-2.1.0.jar \
  <worktree>/src/…/ProtectedZoneGuard.kt \
  /var/minis/shared/work/r1118c/zoneguard-jvm/probe/Probe.kt -d out/probe
java -cp out/probe:$LIB/kotlin-stdlib-2.1.0.jar ProbeKt

# 变异负控（8 臂）
nice -n 10 python3 /var/minis/shared/work/r1118c/zoneguard-jvm/mutate.py
```
