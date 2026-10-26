# FIX-DEFECT-RECALL：沙箱缺陷自动召回——标题串位 + seed 判据过松 + 字段形态单薄

- 分支：`fix/defect-recall-seeds`（基点 `26d2c27a2`，= origin/main U 批并集后）
- 代码提交：`9d7c0c85b`
- 改动面：2 文件 +187/−15（`SandboxDefectRegistry.kt` +96/−15、`SandboxDefectRegistryTest.kt` 臂 15→20）
- 生产行为影响：仅 `ChatPromptAndTools.kt:443` 的失败路径提示（唯一消费方，已 grep 确认）

## 1. 背景（谁报的、什么现象）

用户在真机上连续看到：一条**读数正常**的 shell 命令，终端标志显示红，并附带一条
`[sandbox-defect-registry] 输出命中已知沙箱缺陷的报错特征（自动召回）`——
内容是「A1b 源文件含裸 NUL 字节」，与当次命令毫无关系。

原命令（真实使用）：

```
LOG=$(ls */*.txt | head -1); echo "LOG=$LOG size=$(wc -c <$LOG)"; grep -c "..." $LOG; ...
```

红标志由 `ChatShellExecution.kt:286`（`success = result.exitCode == 0`）决定，与本缺陷无关
（`$LOG` 未加引号 → 词分裂 → grep rc=2；且 `grep -c` 零命中本来就返回 1）。**召回只在
`!result.success` 时触发**，于是这条命令每次都会把 7 行 `grep: …: No such file or directory`
喂给匹配器。

## 2. 缺陷（三条，全部在 `SandboxDefectRegistry` 内）

### 2.1 头部串位（BLEED）
`ENTRY_RE` 只认 `###`，且**非条目标题不终结**当前条目。注册表 12 条写成
`## A19 …` / `## S28 …`，其 `- 症状：` 行被上一条 `###` 条目吸收：
**B5（陈旧视图，健康路径「错开启动 sleep 0.3」）带着 A19 的 seed 开火**（B5 本身无 seed）。

### 2.2 seed 判据过松（全通用词组 ≈ 万能命中）
分词器丢弃单字符与 `-n` 这类短参数，于是「举例式 seed」退化成通用词：

| 症状里的 seed | 分词结果 | 命中面 |
|---|---|---|
| `grep -n X file` | `[grep, file]` | 任何 `grep: …: No such file or directory` |
| `" CODE "` | `[code]` | 字面 `(exit code: 1)` |
| `wait` | `[wait]` | stall-guard 文本（含 waiting） |

### 2.3 字段形态过窄（漏召回）
字段行用 `startsWith("- 症状：")` 严格前缀。注册表里 **9 条**写 `- **症状**：`（加粗），
全部永久不可召回（含 S26/S28/S34/S35 这类高频条目）。
量化：35 条条目里 **只有 3 条**真正可召回（旧代码 23 条可解析、3 条有 seed）。

## 3. 修法（三级阶梯停在哪）

- 第 2 级（代码库里已有）：复用既有 `parse/tokensOf/seedGroupsOf/match` 结构，不新增管线。
- 只改三处判据 + 一处常量，未改 `Defect` 数据类、未改调用方、未新增门（注册表是仓库外
  用户数据，仓库侧门无法校验它——装置比例纪律）。

```
ENTRY_RE   ^#{2,3}\s+([A-Za-z]{1,3}\d+[A-Za-z]?)(?:\s+(.*))?$
HEADING_RE ^#{1,6}\s            ← 任意标题终结当前条目（防串位）
FIELD_RE   ^-\s*(?:\*\*)?(症状|健康路径|救不动)(?:\*\*)?\s*[:：]\s*(.*)$
SPECIFIC_MIN_LEN = 6            ← 无结构字符的拉丁 token 视作通用词的门槛
```

id 形态守卫是必需的：没有它，`## A 类：路径缺陷` 与 `## 元规则` 会被解析成条目。

## 4. 证据

### L1 单测（本地 kotlinc + JUnit4，真源码非复刻件）
`OK (20 tests)`，臂 15 → 20：双 `#` 条目标题 / 非条目标题不串位 / 加粗字段 /
全通用词组不入组 / 两段真实噪声不命中（各带正向对照）。

### L2 变异负控（5 条，逐条破坏一条新判据）

| 变异 | 红 |
|---|---|
| ENTRY_RE 只认 `###` | 1 条（`parse_accepts_double_hash_entry_heading`） |
| 标题不终结旧条目 | 9 条（含 `non_entry_heading_does_not_bleed_into_previous_entry`） |
| 允许全通用词组 | 1 条（`generic_only_seed_groups_never_become_match_groups`） |
| 不接受加粗字段 | 1 条（`parse_accepts_bold_field_lines`） |
| 锚点长度 6→20 | 6 条 |

还原后逐字节一致（sha256 `b800208d6dc5…`）、20 臂复绿。

### L3 真数据对上（两实现交叉验证）
用 Kotlin 驱动 + python 镜像各解析一次真实注册表，**逐条一致**：
35 条 / 12 条可召回 / 39 组（曾因镜像阈值写成 5 出现 41 vs 39，定位后统一为 6）。

### L4 前后对照（同一对噪声输出）

| 组合 | 可解析条目 | 可召回条目 | out1（grep 噪声） | out2（stall-guard） |
|---|---|---|---|---|
| 旧代码 + 旧注册表 | 23 | 3 | **A1b**（误） | **A1b, B5**（误） |
| 新代码 + **旧**注册表 | 35 | 11 | NO_HIT | NO_HIT |
| 新代码 + 新注册表 | 35 | 12 | NO_HIT | NO_HIT |

第三行同时验证真样本各自命中正确条目：`binary file matches`→A1b、
`/tmp/.native-offload-<pid>`→A19、`bad pack header`→A1、`invalid sha1 pointer`→S26、
`Function not implemented`→S28。**第二行是代码独立性的关键**：数据侧不修，误召回也不会再发生。

## 5. 注册表侧配套（`/var/minis/shared/sandbox-limits.md`，非仓库文件）

收紧会把「全部 seed 都是通用词」的条目打死，实测两条需补写逐字报错原文：

- A1：`bad pack` → `bad pack header`（`bad pack` 两词均 ≤4 无锚点，会被丢弃）
- S26：原 seed 是 `git log`/`git fsck`（举例词），改写为 `invalid sha1 pointer` / `bad object HEAD`

改动前备份：`/var/minis/shared/work/reg-backup/sandbox-limits.md.pre-1027fix`。
同一批还做了两件纯数据修复（12 条 `##`→`###`；去掉举例式反引号）。

## 6. 残留（登记，不在本支修）

- **R1**：短词组仍靠子串包含匹配（`pack` 命中 `package`）。词边界匹配会改匹配语义，
  需要独立证据支撑，登记为升级候选（触发条件：再出现一次由短词造成的误召回）。
- **R2**：`S29 http=000 size=0`、`S31 git log` 两组按新判据丢弃（条目各自仍有 ≥3 组，不盲）。
- **R3**：A2/A4/A8/A10/S34/S35 无 `- 症状：` 行（纯描述），旧代码同样是 0 组，非本次回归。
- **R4**：召回仍与退出码耦合（`!success` 才跑）。读数类命令的 rc=1 会无谓触发一次匹配
  （现在至少不会再产出错提示）；若要解耦需改 `ChatShellExecution` 的 success 语义，另立项。

## 7. 未验证

- 真机 UI 上的提示渲染（沙箱无 Android 运行时；渲染逻辑未改，`hintSuffix` 输出形态由既有
  臂 `hint_suffix_renders_id_title_path_and_registry_pointer` 守护）。
- 用户设备上 `filesDir` 里那份注册表副本是否与分享目录一致（若有独立副本需另行同步）。
