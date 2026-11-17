# INCR-1117 FIX-T6 — arm9「失败的下载必须终止」只锚箭头字面量（A-F11）

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 main | `949566ac`（`git log --oneline -1` 实测一致，worktree `wt6` 干净起步） |
| 分支 | `fix/scan-arm9-terminal-1117` |
| 提交 | `83513a1f`（`scripts/scan/skill_frontmatter_roundtrip_check.py` **+73 / −5**，唯一改动文件） |
| 分支尖端 / CI 绿 sha | `83513a1fdcef834c588e98c6184e149a8d7f3d6d`（run **#3198** / id `44003805083` / 5 job success / `115 passed, 0 failed` + `ALL 354 CASES PASS`） |
| 三方 sha | 本地 `git rev-parse HEAD` == `git ls-remote --heads origin` == `run.head_sha`：逐字符一致（§5.4 有原文行） |
| 改动面 | 门 1 文件（含其 selftest 夹具）。`scan.sh` / `test_scan.py` / 任何 `.kt` **零触碰**（`git diff --name-only 949566ac HEAD` 只有一行） |
| 臂数 / 夹具数 | 门执行臂 **23 不变**（实测计数见 §4）；selftest 夹具 **+1**（19 → 20 个 dirty 条目，实测 `grep -c 'selftest dirty'`） |

## 2 缺陷与先证

### 2.1 先证（改前状态，实测）

基线门（`git show 949566ac:scripts/scan/skill_frontmatter_roundtrip_check.py`，落地到
`scripts/scan/_tmp_baseline_gate.py` 以获得共享原语 `sys.path`）：

| 输入 | 基线门 rc | 结论 |
|---|---|---|
| 真树（main 原样） | 0 | 绿 |
| 真树 **只删 Failure 分支里的 `return`**（分支、`onStateChange(ERROR)`、`is … Failure ->` 箭头全部保留） | **0** | **BLIND** —— 门说「终态」，代码已经掉进成功路径 |

⇒ A-F11 判级 **P1 成立**：判据锚的是 `SB_FAILURE_BRANCH = "is SkillRepository.UpdateResult.Failure ->"` 这段**拼写**（改前 `:800-804` 的 `check(..., SB_FAILURE_BRANCH in sb_nc, ...)`），不是「该分支终止」这个**决定点**。只要箭头还在，删掉 `return` 后
`onStateChange(HudState.HIDDEN, "")` 之后控制流直接落到 `when` 之后 → 重新进入 `messageRes` 的成功路径 → HUD 报 SUCCESS，正是 `RC-U12` / `incr1116-A F-06` 想根除的那句谎。

### 2.2 为什么既有负控抓不到

改前的负控 `DIRTY_SB_NO_FAILURE_ARM`（`:1099`）是**整段删掉**分支：

```python
DIRTY_SB_NO_FAILURE_ARM = GOOD_SB.replace(
    """        is SkillRepository.UpdateResult.Failure -> {
            onStateChange(HudState.ERROR, result.reason)
            return
        }
""", "")
```

删整段 ⇒ 箭头也不在了 ⇒ 字面量判据当场红。"只删 `return`" 这个形态（本缺陷的真实形态）没有任何夹具覆盖，所以假保护在 selftest 里也是全绿的。

### 2.3 同族核查（半径纪律）

`SB_FAILURE_BRANCH` 在本门只出现在这一条判据与一条夹具里（`grep -c SB_FAILURE_BRANCH` = 4：常量定义 / 判据 / 新判据文案 / 新夹具文案段）。其它 arm（1–8、10）与失败分支无关，**不动**。

## 3 修法（阶梯第 6 级：最小实现，一个定位原语 + 一条判据换锚）

**停在第 6 级，因为**：不需要新门、不需要新常量解析器、不碰 `scan.sh` 注册；缺陷是「判据看的位置错了」，所以加法只有一个模块级定位函数（复用共享原语 `match_bracket`），判据由「含子串」改为「块体内有 `return`」。

### 3.1 新增定位原语 `branch_body_at(text_masked, marker)`（`:120`）

```python
def branch_body_at(text_masked, marker):
    """Body of the branch introduced by `marker` (a `when` condition ending in
    `->`), delimited by brace matching. Returns (body, problem)."""
    idx = text_masked.find(marker)
    if idx < 0:
        return None, ("'%s' not found in MinisSkillsBrowserScreen.kt — ... (ROT — update this gate)" % marker)
    k = idx + len(marker)
    ...skip ws...
    if text_masked[k] == "{":
        close = match_bracket(text_masked, k)
        if close < 0:
            return None, ("the '%s' branch body never closes (unbalanced braces) — fail-closed: ..." % marker)
        return text_masked[k + 1:close], None
    # single-expression body — to the end of the line; when the arrow ends the
    # line, the expression sits on the next non-empty line.
    ...
```

- **两种形态都读**（否则真实代码一改成单表达式就 ROT）：块体 `is Failure -> { … }` 取花括号内；单表达式 `is Failure -> return` 取该行；箭头独占一行时取下一非空行。
- **结构视图**：在 `mask_code` 视图上做括号匹配（字符串内容已抹平，`"{".repeat(9)` 之类不会破坏配平），判 `return` 也在同一视图上做（`"return"` 字符串字面量不会假满足）。
- **fail-closed**：标记消失 / 括号不配平 → 返回 `problem`，判据直接报违规，绝不「找不到就当干净」。

### 3.2 判据换锚（`:845-859`）

```python
    # [incr1117-A F-11] The third criterion used to be `SB_FAILURE_BRANCH in
    # sb_nc` — presence of the arrow, not the decision. ...
    failure_body, failure_problem = branch_body_at(sb_masked, SB_FAILURE_BRANCH)
    if failure_problem is not None:
        check("arm9 browser refresh treats a failed download as terminal",
              False, failure_problem)
    else:
        check("arm9 browser refresh treats a failed download as terminal",
              re.search(r"\breturn\b", failure_body) is not None,
              "the '%s' branch has no `return` in its body — a Failure that "
              "falls through re-enters the success path (ROT — update this gate)"
              % SB_FAILURE_BRANCH)
```

- **check 名未改**（`arm9 browser refresh treats a failed download as terminal`）—— `test_scan.py` 是冻结面，且既有夹具 needle 锚的就是这段文字。
- `\breturn\b` 同时覆盖 `return` 与 `return@label`（`@` 是非词字符，词边界成立）。
- **唯一新增的失败文案**（任务书 §5.2 要求给出原文）：

  - 分支消失（标记找不到）：
    `arm9 browser refresh treats a failed download as terminal — 'is SkillRepository.UpdateResult.Failure ->' not found in MinisSkillsBrowserScreen.kt — the failed-download branch is gone, so nothing stops it from falling through into the success path (ROT — update this gate)`
  - 括号不配平：
    `arm9 browser refresh treats a failed download as terminal — the 'is SkillRepository.UpdateResult.Failure ->' branch body never closes (unbalanced braces) — fail-closed: the drop-through cannot be judged (ROT — update this gate)`
  - **本缺陷形态**（掉下去）：
    `arm9 browser refresh treats a failed download as terminal — the 'is SkillRepository.UpdateResult.Failure ->' branch has no 'return' in its body — a Failure that falls through re-enters the success path (ROT — update this gate)`

  原文案里的两处关键词都保留（`falls through re-enters the success path` / `(ROT — update this gate)`），只把「expected '<arrow>'」换成「branch has no `return` in its body」。

### 3.3 新负控夹具 `DIRTY_SB_FAILURE_FALLTHROUGH`（`:1163-1172`）

```python
DIRTY_SB_FAILURE_FALLTHROUGH = GOOD_SB.replace(
    """            onStateChange(HudState.ERROR, result.reason)
            return
""",
    """            onStateChange(HudState.ERROR, result.reason)
""")
```

只删 `return` 这一行，分支与箭头原样保留 —— 与 `DIRTY_SB_NO_FAILURE_ARM`（整段删）构成两种形态。selftest 表登记（**带 needle**，第 5 个元素）：

```python
        ("browser Failure branch loses its return", GOOD_SR, GOOD_SM,
         DIRTY_SB_FAILURE_FALLTHROUGH, "failed download as terminal"),
```

needle 指 arm9 第三条的判据文案，防「红在别的臂」被算过。

### 3.4 为什么不是「加一条门判据」而是「换锚」

加一条判据会留下旧的假保护（旧判据仍会接受只删 `return` 的树，只靠新判据兜）；换锚让**同一条臂**从「拼写存在」变成「决定生效」，负控仍然指同一条臂的文字 —— 门的牙数不变（23），牙的位置对了。

## 4 覆盖面（普查计数）

| 面 | 口径 | 计数 |
|---|---|---|
| 门执行臂 | 运行时计数（把 `check()` 包装成记录器跑真树） | **23**，与 `main()` 打印的 `(23 arms)` 一致（未增未减） |
| selftest dirty 条目 | `--selftest` 打印行 | 改前 **19** → 改后 **20**（新增 1，实测计数） |
| `SB_FAILURE_BRANCH` 出现处 | `grep -c` | 4（定义 / 判据调用 / 判据文案 / 夹具区文案） |
| 读取 SB 文件的判据 | 该分支三条判据 + arm10 四条 | 其余判据路径未改（`sb_nc` 仍供 arm10 使用，未删视图） |
| 触碰文件 | `git diff --name-only 949566ac HEAD` | **1**（本门） |

## 5 验证

### 5.1 L1 本门 selftest（`--selftest`，rc=0）

`SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS`；clean 树 `PASS`；20 条 dirty 全 `RED`。新增那行原文：

```
selftest dirty browser Failure branch loses its return: RED ["arm9 browser refresh treats a failed download as terminal — the 'is SkillRepository.UpdateResult.Failure ->' branch has no `return` in its body — a Failure that falls through re-enters the success path (ROT — update this gate)"]
```

既有的 `browser download failure falls through`（整段删）仍 `RED`，且失败行变成新的「分支消失」文案 —— 两条负控各覆盖一种形态，互不吞并。

### 5.2 L2 真树（`python3 scripts/scan/skill_frontmatter_roundtrip_check.py .`，rc=0）

```
OK …/SkillRepository.kt + …/SkillsManagementScreen.kt + …/MinisSkillsBrowserScreen.kt (23 arms)
```

### 5.3 L3 变异反证（真树，单文件恢复）

| 步骤 | 命令 / 结果 |
|---|---|
| 变异前 sha256 | `1a9d7a520b6a20728e825554d6e26c6a2867f238fcc8287d1ea9aa1496497fba` |
| 变异 | 删 `MinisSkillsBrowserScreen.kt` Failure 分支里 `onStateChange(HudState.HIDDEN, "")` 之后的 `                return` 一行（分支与箭头保留） |
| 改后门 | **rc=1**，且**恰好一条**失败行，就是 arm9 第三条的「no `return` in its body」 |
| 基线门对同一变异体（对照臂） | **rc=0，BLIND**（§2.1 的先证即用这一步取的） |
| 恢复 | `git checkout -- src/android/app/src/main/java/com/rikkaminis/app/ui/settings/MinisSkillsBrowserScreen.kt`（单文件，未用 `-- .`） |
| 恢复后 sha256 | `sha256sum -c` → `OK`；`git status --short` 只剩 ` M scripts/scan/skill_frontmatter_roundtrip_check.py` |

### 5.4 L4 分支 CI（run #3198 / id `44003805083` / head_sha `83513a1f…`）

| 判据 | 结果 |
|---|---|
| 5 job | `unit-tests` / `scan-gate` / `lint` / `build-apk` / `publish` 全部 **success**；其中 `publish` 的 6/7 步（Move the rolling tag / Publish to Releases）**skipped** = main 门控生效（分支不发布，正常） |
| head_sha 三方 | 本地 `83513a1fdcef834c588e98c6184e149a8d7f3d6d` == `git ls-remote --heads origin fix/scan-arm9-terminal-1117` == `run.head_sha`，逐字符一致 |
| scan-gate 基线 | 日志首行 `scan-gate base=949566acb… head=83513a1fd…`（base 即本任务书基线 `949566ac`） |
| **本批动了 `scripts/scan/**`** | 日志有 `##[notice]scanners moved in this push (or the diff was unreadable) — running the fixture self-test`，且列出的改动文件第一行就是 `scripts/scan/skill_frontmatter_roundtrip_check.py` ⇒ `test_scan.py` **真跑** |
| 夹具套件 | `✅ ALL 354 CASES PASS`（恰 1 次） |
| 扫描汇总 | `115 passed, 0 failed`（本门在列）；全日志无任何 `N failed` 非零行 |
| **本门两行** | `✅ skill_frontmatter_roundtrip_check selftest has teeth (SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS)` + `✅ skill_frontmatter_roundtrip_check real tree green` |
| 新夹具的可见性（如实说明） | `test_scan.py` **捕获**门的 stdout、只断言 `SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS` 标记，因此日志里**搜不到** `browser Failure branch loses its return` 这一行（`grep -c` = 0）。因果关系是：新夹具若不红/红错臂 ⇒ `selftest()` 返回 1 且不打印该标记 ⇒ test_scan 那条 `✅` 变成红。所以上面那行 `✅` 就是新夹具被跑过的证据（但不是它打印行的证据） |
| unit-tests | `9298` 条 `PASSED`；含 `FAILED` 字样的 6 行全是**用例名**（如 `slot denied produces FAILED without calling provider PASSED`），无真实失败 —— 本批未碰 `.kt`，此处只作旁证 |

**源码树等价声明**：绿 run 之后只追加本报告，因此 `git diff --name-only 83513a1f HEAD -- . ':(exclude)fix-reports'` 输出为空 ⇒ CI 结论对当前代码树成立。

### 5.5 形态探针（两种分支形态都不会 ROT，脚本临时、不入仓）

```
single-expr WITH return            -> CLEAN
single-expr WITHOUT return         -> ["arm9 ... branch has no `return` in its body ..."]
arrow ends line, return next line  -> CLEAN
truncated body (unbalanced)        -> ["arm9 ... branch body never closes (unbalanced braces) — fail-closed ..."]
```

⇒ 未来把分支改成单表达式（`is Failure -> return`）**不会**假红；而单表达式里没有终止则照旧红；真正不配平则 fail-closed。

## 6 残留

| # | 残留 | 触发条件 | 触发后方案 |
|---|---|---|---|
| R1 | 只判 `return`：`throw` / `error(...)` / `exitProcess` 等同样终止的写法会被判红 | 真实代码把 Failure 分支改成 `throw IllegalStateException(...)` | 判据扩为 `\breturn\b|\bthrow\b|error(`，并各配一条夹具；届时先确认 `test_scan.py` 无措辞断言 |
| R2 | 块体内**任意** `return`（含嵌套 lambda 里的 `return@forEach`）即满足；极端形态「HUD 调用之后插一个不相关的 lambda + `return@itersu`」会假绿 | 分支体内出现嵌套 lambda 且其内部有 `return@…` | 判据升级为「体顶层（花括号深度 1）必须以 `return` 结尾」 |
| R3 | 分支体若被改写成 `when` 之外的形态（`if (result is Failure) { … }`），`is … Failure ->` 标记消失 → 判据报 ROT（红，不是假绿） | 真实重构 | 按报错行的 `ROT — update this gate` 改锚到新决定点，并把 `DIRTY_SB_NO_FAILURE_ARM` 改成新形态的删法 |
| R4 | `_tmp_baseline_gate.py` 只在本轮作为对照臂临时落地，已删除（未入仓） | 需要复现先证 | `git show 949566ac:scripts/scan/skill_frontmatter_roundtrip_check.py > scripts/scan/_tmp_baseline_gate.py`，跑完即删 |

## 7 未验证边界

- **未做真机验证**：本改动只涉及扫描门脚本，运行时行为零变化，真机无观察面。
- **未本地跑全量 `test_scan.py`**：按全局纪律（本地只跑本门 + 邻门，全量交云端）—— 本批动了 `scripts/scan/**`，云端 scan-gate 会真跑夹具，证据见 §5.4。
- **邻门只跑了 3 个**（`truncated_call_guard_check` / `skill_import_offmain_check` / `subagent_dispatch_check`，均 rc=0）：它们是最可能读同一批 SB 符号的门；其余门与本次改动无交集。

## 8 附装置

| 装置 | 路径 |
|---|---|
| 臂数实测（临时，入 `/tmp`） | `/tmp/armcount.py`（把 `check()` 包装成记录器）+ 一条 `run_checks_text` 调用 |
| 形态探针（临时） | `/tmp` 内联脚本（§5.5 四条腿） |
| 基线门对照臂 | `/tmp/gate_baseline.py` ← `git show 949566ac:…` |
| 邻门输出 | `/tmp/truncated_call_guard_check.out` 等 |
