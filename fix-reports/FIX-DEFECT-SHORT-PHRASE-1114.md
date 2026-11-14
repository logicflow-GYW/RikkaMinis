# FIX-DEFECT-SHORT-PHRASE-1114：全短词 seed 组永不召回

## 0 来源与判级

外部报告点 #14（"SandboxDefectRegistry 的短词边界召回增强"）。**核实成立，且我用真机语料量化了误报风险。**

判级 **P2**（agent 回归恢复路径的可观测性损失，非用户直感）。分支 `fix/defect-short-phrase-1114` @ `6cba0245`。

## 1 根因

`SandboxDefectRegistry.hasSpecificToken`（`:204`）要求组内至少一个 token 满足
「结构字符 / ≥6 字符 latin / CJK」。**全短词的 seed 组被整组丢弃**。

用探针扫真注册表（37 条，12 条有 seed）：

```
seed groups admitted: 64   dropped: 25
-- dropped --
[bad, pack]        <= `bad pack`                    ← A1
[git, log] [git, fsck] [git, push] ...              ← S26 / S31
[no, merge, base]   <= `no merge base`
[http, 000, size]  <= `http=000 size=0`              ← S29
[data] [file] [wait] [000] [len] [depth] ...        ← 单词组（本就不该救）
```

**实证这条盲区真的咬过人**：`2026-11-13 16:06` 真机日志里
`! [remote rejected] main -> main (bad pack)` **出现两次**，而同一天 `sandbox-defect-registry`
召回计数 = 0 —— A1 的健康路径当时就在注册表里，没人看到。`bad` 和 `pack` 都是 4 字符。

## 2 修法

新增 `Defect.phraseGroups`：**全部 token 都短**的 phrasing，重新拼成一个短语，按**相邻**匹配。

```kotlin
// 生产 match()：
val hit = d.seedGroups.any { group -> group.all { hay.contains(it) } } ||   // 原有：无序 AND
          d.phraseGroups.any { phrase -> phraseMatches(hay, phrase) }       // 新增：相邻短语
```

**三个关键设计决定**（每个都有反例支撑）：

1. **单词组绝不进 phraseGroups**。实测 132 条真实失败输出里，`data` 命中 8 条、`file` 命中 35 条 ——
   单个通用词无法靠相邻性借用特异性，正是 2026-10-27 那道门存在的理由。
2. **相邻而非无序 AND**。`[git, log]` 作为无序 AND 会命中任何同时出现这两个词的输出
   （注册表自己的示例行就够）；作为短语只命中 `git log`。
3. **相邻的分隔符要容忍标点**。注册表自己的 S29 写法是 `http=000 size=0`，token 之间是 `=` 不是空格。
   所以短语用 `[^a-z0-9]+` 连接，每个 token `Regex.escape`（token 里合法地含 `.`，不能当通配符）。

## 3 误报实测（本条最值钱的证据）

真注册表（37 条）× 从当天真机日志挖出的 **132 条真实 tool 失败输出**：

| 规则 | 命中文件 | 命中条目 |
|---|---|---|
| 修复前 | 18/132 | A1b(17) / S30(1) |
| **修复后** | **19/132** | **A1(1)** + A1b(17) + S30(1) |

**新增误报 0，多召回 1 条，而那条正是 11-13 真撞上过的 `bad pack`。**

反向对照（说明为什么必须排除单词组）：若把单词组也放进来，`data` 8/132、`file` 35/132 会立刻变成噪声。

## 4 证据

**L1 沙箱 JVM**
- 新增 `SandboxDefectPhraseRecallTest` 9 例；既有 `SandboxDefectRegistryTest` 21 例 —— **合计 30/30 绿**
- 变异负控 **5/5 红**，sha256 还原 `RESTORE_OK sha256 MATCH`：
  | 臂 | 变异 | 结果 |
  |---|---|---|
  | m1 | match() 忽略 phraseGroups | 4 红 |
  | m2 | 填充时把特异性门反过来（仍是空集合） | 5 红 |
  | m3 | 单词组也进 phraseGroups | 1 红 |
  | m4 | 短语改用无序 AND | 2 红 |
  | m5 | 去掉分隔符容忍（退化成单空格） | 1 红 |

**L3 分支 CI** run [`43572485421`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/43572485421)
head_sha `6cba0245`，本地 == 远端 == run **三方逐字符一致**，五 job 全 success。逐条搜标记：
- unit-tests：**9173 PASSED / 0 FAILED**（基线 9164，+9 = 本支九例）
- `SandboxDefectPhraseRecallTest` 九条用例逐条在册 PASSED
- scan-gate：`Summary: 115 passed, 0 failed`；`##[notice]scripts/scan/** untouched … skipping the fixture self-test`
  ⇒ **夹具没跑**（本支未动门脚本），不声称夹具全绿
- 该 CI 有一条 Kotlin 编译**告警**（`SandboxDefectPhraseRecallTest.kt:127 Java type mismatch`），
  已在报告提交里把那段三元/`let` 抽成 `findRegistryAncestor()` 消除；属纯整洁修正，不改变断言。

## 5 残留

- **R1 召回的实际收益未在真机验证**：沙箱只能证明「同样的失败文本现在能命中 A1」，不能证明
  「agent 拿到提示后少烧了几轮」。**触发条件** = 下次真机出现 `bad pack` / `git fsck` / `merge base` 类失败。
  **触发后方案** = 日志应出现 `sandbox-defect-registry` + `· A1 本地 git push / clone --local 失败`；
  对照 11-13 同类失败（计数 0）。**搁置理由** = 需要真实失败场景自然发生，不可构造。
- **R2 仍有一类召回不上**：`[grep, file]`（来自 `grep -n X file` 这种示例式 seed）被排除了 —— 它虽然是多词，
  但作为短语会命中任何 `grep -n foo file`（合法命令输出），作为无序 AND 会命中每条
  `grep: …: No such file or directory`。**这是刻意保留的盲区**（`hasSpecificToken` 的 KDoc 原文就举了这个例子）。
  **触发条件** = 有人报告「grep 找不到文件」类失败需要自动召回；**触发后方案** = 把那条 seed 改写成逐字报错原文
  （注册表维护义务已经这么要求），而不是放宽判据；**搁置理由** = 放宽判据的代价是每条 grep 失败都误报。
