# FIX-CLASSIFY-COMPOUND-1114：复合命令被误判 LIGHT，绕过 heavyGate

## 0 来源与判级

外部报告点 #3（"`classifyCommand` 的命令分类启发式盲区"）。**核实成立，且有量化**。

判级 **P1**（资源闸失效，虽无崩溃但也无任何保护）。分支 `fix/classify-compound-1114` @ `4b3082f0`。

## 1 根因与量化

`ExecutionCoordinator.kt:1867`（改前）：

```kotlin
val trimmed = command.trimStart()
trimmed.startsWith("python3") || trimmed.startsWith("apk ") || ... -> HEAVY
```

只读**首个 token**。**真机日志量化**（`minis-2026-11-01..14.log`，扫 4 775 条 `shell_execute`）：

| 形态 | 数量 | 占比 |
|---|---|---|
| 已判 HEAVY（旧判据） | 20 | 0.4% |
| **`cd X && python3 …`（判 LIGHT）** | **233** | **4.9%** |

LIGHT 的代价是三重的：绕过 `heavyGate`（`Semaphore(1)`，`ExecutionCoordinator.kt:282`）⇒ 多会话可并发跑
Python/Node 把内存推爆；跳过 CRITICAL 阶段「heavy 需 memAvail 充足才放行」（`:405-420`）；
从不按类强制回收（`shouldRecycleByClass`）。

同族漏网：`ENV=prod npm run build`（前导赋值）、`# 注释` 头、`for …; do python3 …; done`（前导关键字），
以及完全缺席的 `git clone/fetch`、编译器链、`tar/ffmpeg`。

## 2 修法

分类器移入独立文件 `CommandClassifier.kt`（原处留 5 行指针注释），改为**按语句分类取最高级**：

1. 按 `;` `|` `&` 换行切语句；
2. 每条语句剥前导 `KEY=value`、shell 关键字（`do`/`then`/`time`/`nohup`/`sudo`…）、
   `nice`/`ionice`/`chrt` 及其参数、注释；
3. **`git` 按子命令分**：`status`/`diff`/`log` 保持 LIGHT（日志实测 220/100/90 次，是最常见调用，
   判 HEAVY 只会白串行化），`clone`/`fetch`/`pull`/`push`/`gc`/`repack`/`fsck`/`submodule`/… 判 HEAVY；
   跳过 `git` 的取值全局旗标（`-C`/`-c`/`--git-dir`/`--work-tree`…）；
4. 补完全缺失的动词族：编译器（`gcc`/`clang`/`javac`/`kotlinc`/`make`/`cmake`）、
   归档（`tar`/`zip`/`unzip`/`7z`/`xz`/`gzip`/`zstd`）、媒体（`ffmpeg`/`ffprobe`）、
   构建包装（`gradle`/`gradlew`/`mvn`）、运行时（`deno`/`bun`/`rustc`）。

**刻意不做**：引号/heredoc 感知（heredoc 里的 `python3` 会被判 HEAVY —— 过度分类是安全方向）、
变量展开（`$PY x.py` 保持 LIGHT）、路径解析（按 basename 匹配，`./python3` 也算）。

## 3 差分（最重要的一条证据）

同一份语料（3 730 条去重命令），旧判据 vs 新判据：

```
total: 3730    unchanged: 3431
LIGHT -> HEAVY: 299      LIGHT -> LEAKY: 0
HEAVY -> LIGHT (downgrades): 0        ← 没有一条命令被降级
```

新判 HEAVY 的归因（前三）：`python3` 247 · `kotlinc` 11 · `git fetch` 10。
**零降级**这一条是关键：新判据只把命令往更严的方向挪，不会让原本受保护的命令脱离闸门。

## 4 证据

**L1 沙箱 JVM**
- `CommandClassifierTest`：`OK (16 tests)`
- `ExecutionCoordinatorSchedulerTest` 里**原先断言缺陷本身**的用例
  （`classifyCommand("cd /tmp && python3 run.py") == LIGHT`，注释还写着 "deliberately prefix-based"）
  已改写为断言修复后行为 —— 这是本次唯一需要改的既有测试。
- 变异负控 **5/5 红**，sha256 还原 `RESTORE_OK sha256 MATCH`：
  | 臂 | 变异 | 结果 |
  |---|---|---|
  | m1 | 去掉语句切分（退回只看整串） | 6 红 |
  | m2 | git 恒 LIGHT | 2 红 |
  | m3 | git 恒 HEAVY | 2 红 |
  | m4 | 去掉赋值剥离 | 1 红 |
  | m5 | 去掉 git 取值旗标处理 | 1 红 |
- 一个**被删掉的无效臂**：原计划把 `cd` 短路由删掉看是否变红 —— 结果绿。原因是语句切分已把
  `cd X && python3` 切成两条语句，`cd /tmp` 单条因 `cd` 不在 HEAVY 动词表里自然判 LIGHT，
  那个 `if` 分支是纯冗余。**删掉分支而不是保留它骗自己**（保留会让人以为有保护）。

**L3 分支 CI** run [`43572170045`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/43572170045)
head_sha `4b3082f0`，本地 == 远端 == run **三方逐字符一致**，五 job 全 success。逐条搜标记：
- unit-tests：**9180 PASSED / 0 FAILED**（基线 9164，+16 = 本支新增 16 例；改写的旧用例
  `classify chained command by its heaviest statement PASSED` 在册）
- scan-gate：`Summary: 115 passed, 0 failed`；`##[notice]scripts/scan/** untouched … skipping the fixture self-test`
  ⇒ **夹具没跑**（本支未动门脚本），不声称夹具全绿

## 5 残留

- **R1 真机行为级未验证**：新判据的**行为**（heavyGate 真的串行化、CRITICAL 真的按新类决定放行）
  在沙箱里只能验到纯函数层。
  **触发条件** = 装包含本支的包并让 agent 跑一条 `cd X && python3 …`。
  **触发后方案** = 日志出现 `Heavy gate timeout`（说明另一条 heavy 正占用，即闸门生效）
  或该命令执行后 `shouldRecycleByClass` 触发回收；对照修复前同样命令无任何闸门行为。
  **搁置理由** = 闸门争用是并发时序，沙箱无法复现。
- **R2 分类语义仍非完备**：`sh -c "python3 x"`、`xargs python3`、`eval …` 里的重命令仍判 LIGHT
  （需要引号/子命令语义，超出本次半径）。
  **触发条件** = 真机日志出现这类命令形状且伴随内存事件；**触发后方案** = 对 `sh -c`/`eval`/`xargs`
  递归分类其参数串；**搁置理由** = 递归会引入无限深度与引号解析两个新面，当前 0 次实证。
