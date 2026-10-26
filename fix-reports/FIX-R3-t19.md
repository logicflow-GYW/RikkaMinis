# FIX-R3-t19 — `skill_file_save_result_check.py` + `skill_import_offmain_check.py`

- 支线：`fix/r3-skillfilesave`（工作树 `round3b/wt/t19`）
- 基线：`main` @ `f0f14d46de09f8dfd1f4a58c0d44f116413104fb`（第七批收口后的 main，112 门 / 344 夹具）
- 门提交：`f686031b54a320e2efd2cb3d31a438045bc720e5`
- 改动面：**2 个门脚本**（`skill_file_save_result_check.py` +466、`skill_import_offmain_check.py` +407/−65，合计 +808/−65）
  —— **生产 `.kt` 0 行**；`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰；门 112 / 夹具 344 不变；未新建门。
- 见证：6 条（#45–#50）。**pre-fix：5 条 BLIND_CONFIRMED + 1 条 CAUGHT（#45，与主会话自动复证同判）**；post-fix：**6/6 全部 CAUGHT**，另加一条自造的 #45 半补丁（`editContent = ""` 单独应用）= 实锤盲，一并修好。

---

## ① 门

| 门 | 只读对象 | 原有臂 | 新增臂 | 本支职责边界 |
|---|---|---|---|---|
| `skill_file_save_result_check.py` | `SkillsManagementScreen.kt`（整文件 + 从 `fun SkillFileViewerScreen(` 起的段） | 5 结果臂 + offmain 3 臂 + arm6（a/b/c 异步加载链） | **arm7 / arm8 / arm9 / arm10** | 保存/更新的**结果与载荷**、查看器加载链的**可达性与取值** |
| `skill_import_offmain_check.py` | `SkillsManagementScreen.kt` + `MCPIntegrationsScreen.kt` + env-var 屏 | 15 臂（paste ×2 / reload ×2 / all-repo-calls / editor-keys ×3 / key-alignment / B-04 ×4 / B-03） | **arm10 / arm11** | 线程路由（IO off-main）+ `remember` 键对齐 + 表单取值**是否离开表单** |

两门原本的失效面是同一句：**判据锚「某个 token / 某种拼写出现在文件里」，而不是「这个 token 背后的决定真的发生了、值真的被消费」**。第三轮的攻击把这一点推到**门与门之间的缝**上——每条补丁都让本门所有既有臂保持绿，缺陷落在「一门负责拼写、另一门负责调用点」的空档里。

---

## ② 轴

| 轴 | 条数 | 见证 | 缝在哪 |
|---|---|---|---|
| `dataflow`（值被丢弃 / 被换成旧值） | 3 | #46、#47、#50 | 写入/展示的**载荷**从 parse 结果换回旧记录或空串；结果变量被丢弃后无条件成功 |
| `family-hole`（对称面只覆盖一侧） | 2 | #45 的 `editContent` 半边、#49 | 同一「空白基底」缺陷只堵了 `enabled` 一侧；`remember(editServer)` 回显被 C4 判过，但组装/保存一侧无人判 |
| `order`（早退极性） | 1 | #48 | 早退守卫**存在**且写法合法，只有极性反了；既有臂判“守卫存在”，无人判“兄弟文件走不走得到读盘” |
| `symmetric`（判据选了一个位点） | 1 | #47 | 门盯 `version`/`frontmatter` 已被别门覆盖的两个字段，`name/description/body` 三个真正决定内容的字段无人判 |

**共同点**：六条补丁全部是「**看起来仍然正确**」的一行级编辑——`enabled = isLoaded`、`when (val r = …)`、`parsed.name`、`if (isSkillMd)`、`redirectUri = oauthRedirectUri…`、`loadedDiskBase ?: ""` 这六种写法都是本仓既有的正确形态，攻击者只是把它们换成同族的另一种合法写法。

---

## ③ 见证重放表

`probe/t19-pre.json` / `probe/t19-post.json`，补丁 `probe/t19-w<k>-{pre,post}.patch`。还原一律 `git checkout --` + sha256 比对（`restore_ok=True` 全 6 条 + 2 条半补丁）。

| # | kind | 涉及门（X / Y） | pre-fix rc | pre 判决 | post-fix rc | post 判决 | 红在哪条判据 |
|---|---|---|---|---|---|---|---|
| 45 | dual-gate | save / import | **1** / 0 | **CAUGHT**（主会话同判） | 1 / 0 | CAUGHT | `arm6c`（`enabled = true`）+ 新增 `arm10`（`editContent = ""`） |
| 45a（半补丁，自造） | symmetric | save / import | **0** / 0 | **BLIND_CONFIRMED** | **1** / 0 | **CAUGHT** | `arm10` |
| 45b（半补丁，自造） | — | save / import | 1 / 0 | CAUGHT | 1 / 0 | CAUGHT | `arm6c` |
| 46 | dual-gate | save / import | **0** / 0 | **BLIND_CONFIRMED** | **1** / 0 | **CAUGHT** | `arm8`（结果被丢弃） |
| 47 | symmetric | save | **0** | **BLIND_CONFIRMED** | **1**（3 条 finding） | **CAUGHT** | `arm7`（name / description / body 三字段逐条） |
| 48 | dual-gate | import / save | 0 / **0** | **BLIND_CONFIRMED** | 0 / **1** | **CAUGHT** | `arm9`（`if (!isSkillMd)` 折叠为 true） |
| 49 | dual-gate | import / mcp_oauth_lifecycle | **0** / 0 | **BLIND_CONFIRMED** | **1** / 0 | **CAUGHT** | `arm11`（`redirectUri = null`） |
| 50 | symmetric | import | **0** | **BLIND_CONFIRMED** | **1** | **CAUGHT** | `arm10`（`skillFileViewerBase` 兄弟路径返回 `""`） |

**关于 #45 的处理（诚实登记）**：整条见证是 CAUGHT（`arm6c` 抓住 `enabled = true`），按纪律不动它对应的判据。但拆开重放发现它的**第二半单独应用是实锤盲**（`enabled` 保持 `isLoaded`、只把 `editContent = initialContent` 改成 `""` ⇒ 两门 rc=0）：`arm6c` 的声明契约里写着「防空白基底」，实际只堵了「什么时候能点 Edit」，没堵「点开的是什么」。这条不属 §3 的 BLIND 清单，属**同一条见证的第二面**，我按 §2.3 的半径把它做成新臂 `arm10` 并在 §7 R1 登记判定面（不是为 CAUGHT 见证改判据，而是补它自己声明了一半的契约）。

**dual-gate 两门红的说明（§2.2 允许的那条路）**：

| 见证 | 只由一门覆盖 | 另一门为何不该管 |
|---|---|---|
| #46 | `save` arm8 | `import` 的臂全是线程路由（IO off-main）与 `remember` 键对齐；`updateFromURL` 已在 `scope.launch` 里满足它，结果映射不是它的主语 |
| #48 | `save` arm9 | 同上；「读盘是否到达」是 arm6 家族（arm6a/b/c）的既有主语，本支把它补到可达性一层，重复覆盖会被判为「重复覆盖 ≠ 空白」 |
| #49 | `import` arm11 | `mcp_oauth_lifecycle_check` 的 C4 判的是 `remember(editServer)` **回显**（见证原文也写明它绿），判「组装后的 config 是否带出用户输入」不属它的主语——但见 §7 R7 的交叉提示 |

---

## ④ 修法（旧形态 → 新形态，逐条）

### `skill_file_save_result_check.py`

**arm7 — 更新必须携带**被解析**的文档（#47）**

- 旧：门只判 `version = parsed.version` / `frontmatter = parsed.frontmatter …`（别门也判），`name/description/body` 三字段无人判。
- 新：**判据主体从树派生**——取 `parseSkillMdPublic(` 的绑定名（`_binding_before`：紧邻的 `val <x> = <receiver>.` 形态），取该解析点**所在块**（`_enclosing_block`）里的 `skillRepository.update(` 站点；对 `name/description/body` 逐个要求 `value_sources(实参)` 里出现解析绑定（或解析调用本身）。字段缺失、块内无写调用、解析结果无绑定 → 全部报红（fail-closed）。
- 为什么这样锚：写调用可能被挪到别的块（那本身就是「解析结果没用上」），所以**用块而非用函数认为**；rename 对话框的 `update(skill.id, name = trimmed)` 在同一函数但在另一块，天然出界，不会误伤。

**arm8 — 更新结果必须驱动状态映射（#46）**

- 旧：无（门只管“保存结果是否被检查”，URL 更新这条入口没人管）。
- 新：`updateFromURL(` 的结果必须被消费——① `when (val r = …)` 或 `val r = …` 绑定；② 存在 `Success` 键控分支映射到 `UpdateStatus.Done`；③ 存在 `Failure`/`PartialSuccess` 键控分支映射到 `UpdateStatus.Failed`；④ 任何 `updateStatus = UpdateStatus.Done` 赋值必须落在 `Success` 键控分支内（`enclosing_conditions` 逐站点判定）。四条任一不满足即红；无绑定 = 结果被丢弃。

**arm9 — 兄弟文件必须走得到读盘（#48）**

- 旧：arm6b/6c 判「读盘来自 `LaunchedEffect`」「`isLoaded` 在读之后翻转」——都是**文本顺序**，极性反了照样绿。
- 新：先派生「这个屏怎么表示‘这就是 SKILL.md’」——`val <flag> = …` 且初始化表达式里出现字面量 `SKILL.md`（在 comments 视图上找，字符串未抹平）；再对每个含 `return@LaunchedEffect` 的 `if`，把该 flag **代入 false**（`_fold_with` → 共享 `const_bool`）折叠守卫条件：折叠为 `true` ⇒ 兄弟文件被早退、读盘不可达 ⇒ 红；折叠不出 ⇒ fail-closed。

**arm10 — Edit 必须用加载到的基底做种子（#45 第二半）**

- 旧：arm6c 只判 `enabled = <活标志>`。
- 新：定位 Edit 按钮 = 含 `R.string.skill_file_edit` 的 `MinisTextButton(…)`（`_enclosing_call` 含 **trailing lambda**，标签通常不在参数表里）；其中每个 `editContent = <expr>` 的右值必须派生自加载基底（`initialContent` / `loadedDiskBase` / `skillFileViewerBase`），且允许**一跳**局部解析（`_one_hop`：`val displayContent = skillFileViewerBase(…)` 这种写法仍算合法）。

### `skill_import_offmain_check.py`

**arm10 — 查看器基底在兄弟路径上必须**就是**加载到的盘面（#50）**

- 旧：臂 5 只判 `remember(...)` 的**键**里含 `loadedDiskBase`（键对齐）。
- 新：`fun skillFileViewerBase(` 的**参数身份由调用点派生**（读 `skillFileViewerBase(` 的具名实参：哪个参数接收 md 标志、哪个接收 `loadedDiskBase`），然后把 md 参数代入 false，`_value_on_path` 折 `if/else` 取兄弟路径的返回值（**表达式体**直接折；**块体**用 `enclosing_conditions` + `_subst_fold` 过滤可达 `return`，逐个要求读 `loadedDiskBase`）；折叠不出 / 无调用点 / 无可达 return → fail-closed。

**arm11 — 组装出的 OAuth config 必须带出用户输入（#49）**

- 旧：无（C4 只判回显键）。
- 新：`fun currentOAuthConfig()` 体内 `MCPOAuthConfig(` 的 `redirectUri = <expr>` 必须 `value_sources` 派生自草稿态 `oauthRedirectUri`；参数缺失、表达式为常量、草稿态消失 → 全红。

**共享原语迁移（硬约束 1）**：`_match_paren` / `_match_brace` / `_split_top` / `_unparen` / `_lit_value` 五个自写扫描器全部改成委托共享实现（`match_bracket` / `split_top` / `strip_parens` / `const_bool`），删掉第二套括号配平与第二套字面量白名单。迁移后 15 条既有臂 + 76 行 fixture 全绿，真树 rc=0 —— 证明是等价替换而非行为变更。`_const_truth` / `_literal_decides` 未动（见 §7 R6）。


---

## ⑤ 四层证据

**L1 本门（`--selftest`，臂数只增不减，收尾标记保持不变）**

| 门 | 收尾标记 | 臂/fixture 数（前 → 后） | 本波新增逐条判定 |
|---|---|---|---|
| `skill_file_save_result_check` | `SKILL_FILE_SAVE_RESULT_SELFTEST=PASS` | 11 violation + 6 offmain → **+7 round-3**（1 clean + 6 dirty） | 7 条新 fixture 各自**只**翻红自己那条臂（`arm7`/`arm8`/`arm9`/`arm10` 用 `startswith` 严格归属） |
| `skill_import_offmain_check` | `SKILL_IMPORT_OFFMAIN_SELFTEST=PASS` | 65 行 fixture → **76 行**（+11） | `arm10` ×7（含 2 条 clean 对照 + 2 条 fail-closed）、`arm11` ×4（含 2 条 clean 对照） |

gate2 的 11 条新 fixture 逐行读数（`probe/st2.log`）：

```
arm10 sibling path reads loadedDiskBase (expr)          : CLEAN
arm10 sibling path returns "" instead                   : RED
arm10 polarity: !isSkillMd takes the md branch          : RED
arm10 block body returns loadedDiskBase (clean control) : CLEAN
arm10 block body returns "" on the sibling path         : RED
arm10 block body has no sibling return (fail-closed)    : RED
arm10 no caller passes the SKILL.md flag (fail-closed)  : RED
arm11 redirectUri comes from the draft (clean)          : CLEAN
arm11 redirectUri = null wipes the typed value          : RED
arm11 redirectUri argument dropped entirely             : RED
arm11 redirectUri via the draft chain stays legal       : CLEAN
```

**L2 真树**：两门 `python3 scripts/scan/<gate>.py .` 均 rc=0（新增判据在真树上零误报）。

```
skill_file_save_result_check: OK (5/5 arms + offmain 3/3 + round3 4/4) on …/SkillsManagementScreen.kt
OK …/SkillsManagementScreen.kt (17 arms: … R3 viewer-base-carry x1, R3 oauth-config-assembly x1, fixture gates)
```

**L3 邻门 + 全量（逐门独立进程，任务书 §2.4.3 的健康路径）**

- 语义相邻门（扫同两个文件的门）：`filepreview_print_bounded_check` / `ime_inset_guard` / `mcp_oauth_lifecycle_check` / `share_dir_prune_check` / `skill_frontmatter_roundtrip_check` —— **5/5 rc=0**。
- 逐门循环 `probe/gate_loop.sh`（`scripts/scan/*_check.py` + `*_guard.py`，跳过 `_kotlin_*`）：**109 个脚本全部 rc=0**——其中 107 个位置参数形态直接绿，`calendar_events_nullable_predicate_check` 与 `tts_voice_and_sanitizer_check` 需 `--root .` 形态，按该形态亦 rc=0。
- 附加（超出任务书要求，本轮沙箱窗口健康，无 Errno 38）：整份 `bash scripts/scan/scan.sh .` 一次跑通 **`Summary: 112 passed, 0 failed`**（`probe/scan-sh.log`）。
- `test_scan.py`：**未在本地跑**（约 15 分钟的重活，按负载纪律不自主开），`ALL 344 CASES PASS` 的证据交 CI 承担 —— 见 ⑥。

**L4 CI**：见 ⑥。

---

## ⑥ CI（分支 `fix/r3-skillfilesave`，门提交 `f686031b5`）

- **run `40317450056` / #2747 / job `121553634094` / attempt 1 / conclusion `success`**（event `workflow_dispatch`，2026-10-26T11:45:37Z → 12:07:43Z，约 22 分钟）
- **`head_sha` 逐字符一致**：远端 run 的 `f686031b54a320e2efd2cb3d31a438045bc720e5` = 本地 `git rev-parse HEAD`
- **26 步中唯一非 success = step 23 `Publish to Releases`（skipped，main 门控）**，其余全 success
- **日志 `probe/ci/job.log` 1,482,719 B / 裸 NUL 0**，逐条读数：
  - `Summary: 112 passed, 0 failed` 恰 **1** 次（框线行）
  - `ALL 344 CASES PASS` 恰 **1** 次 ⇒ 冻结的 `test_scan.py` 在 CI 真跑且全绿（本地按负载纪律未跑，见 ⑤ L3）
  - `^ e: `（Kotlin 编译错误）**0** 条
  - `[N/112]` 门头 **112 条、编号互异**（1..112 全覆盖）
  - 含 `FAILED` 的行共 6 条，**逐条确认全部以 `PASSED` 结尾**（是测试用例名字里的 `FAILED` 子串，如 `reason EXECUTION_FAILED maps to all_fallbacks_exhausted PASSED`），非门红
  - 两门自证标记各恰 **1** 次：`SKILL_FILE_SAVE_RESULT_SELFTEST=PASS`、`SKILL_IMPORT_OFFMAIN_SELFTEST=PASS` ⇒ 新臂（gate1 7 条 round-3 fixture / gate2 11 条 r3 fixture）在 CI 里真跑
  - 两门真树读数行在 CI 里可见：`skill_file_save_result_check: OK (5/5 arms + offmain 3/3 + round3 4/4)`、`OK …SkillsManagementScreen.kt (17 arms: … )` ⇒ 新增判据在 CI 树上零误报

**停止节点达成**：分支 CI 绿 = 本轮开发结束。**未合并 main**（本支改动面是 `scripts/scan/*.py`，有真实验证面，不属纯文本例外，合并起手权在用户/主会话）。CI 后核查：`git diff --name-only f686031b5..HEAD -- . ':(exclude)fix-reports'` = **0 行**（源码树与绿 sha 逐字节等价，后续提交只动报告）。

---

## ⑦ 残留（P2，每条带「触发条件 / 触发后方案 / 搁置理由」）

**R1 · `arm7` 只认 `skillRepository.update(` 这一种写入口拼写**
- 触发条件：该文件出现第二个写入口（repository 别名、扩展函数、`with(repo) { update(…) }`）。
- 触发后方案：改为「收集所有写调用（按 `call_args` 反查接收者类型/名字集合），断言解析绑定在**至少一个**写调用的实参里被消费」，而不是钉死接收者拼写。
- 搁置理由：当前唯一写入口就是它；扩面会把 rename 对话框的 `update(skill.id, name = trimmed)` 纳入判据面，直接制造误报（那条不带 body/description 是**正确**设计）。

**R2 · `arm8` 只识别 `when` / `if` 两种决策结构**
- 触发条件：`updateFromURL` 的映射改成函数式（`runCatching{}.getOrElse{}`、`fold`、`map`）或抽成独立 `fun applyUpdateResult(r: UpdateResult)`。
- 触发后方案：改为从「状态赋值点」反查——对 `updateStatus = …` 的每个右值做 `value_sources` + 分支常量折叠，要求 `Done` 的到达条件含 Success、`Failed` 的到达条件含 Failure/PartialSuccess，与写法无关。
- 搁置理由：现网只有 `when` 一形；函数式映射要正确建模必须引入 lambda 参数绑定解析（`value_sources` 目前不解析 lambda 形参），属共享层缺口（见 R5）。

**R3 · `arm9` 的「SKILL.md 标志」靠字面量派生**
- 触发条件：判断改成不含字面量的写法（`relativePath.substringAfterLast('/') == "SKILL" + ".md"`、`nameWithoutExtension == "SKILL"`）。
- 触发后方案：改为「从参数/常量表还原文件名比较」，或干脆把「兄弟文件」定义成**调用点传进来的实参**（谁传给 viewer 的 `isSkillMd`）并向下追踪。
- 搁置理由：当前是字面量比较；且判据是 **fail-closed**（派生不出就报红，不会静默放行），失效方向安全。

**R4 · 分支折叠深度上限 4，且只折 `if/else`（不折 `when`)**
- 触发条件：基底选择或早退守卫改成 `when` 形态（`when { isSkillMd -> …; else -> … }`），或 `if` 嵌套 >4 层。
- 触发后方案：把 `when` 分支折叠与常量传播上收到 `_kotlin_valueflow`（真正的共享层修复），两门共用。
- 搁置理由：硬约束 2 禁止本支改共享层；现状两处都是 `if/else`；折叠失败时臂取整段表达式 = 保守（宁可放过也不误报），且 `arm10`(gate2) 另有 fail-closed 兜底。

**R5 · `value_sources` 不解析局部标识符绑定（共享层缺口，B5-t13 已登记）**
- 触发条件：第二个门需要「实参 → 局部变量 → 真实来源」的解析（本支 `arm10` 的 `_one_hop` 就是每个门自己补一遍的形态，已在 gate1 内实现）。
- 触发后方案：上收为共享 API `resolve_binding(code, ident, hops=1)`，两门共用，删掉门内副本。
- 搁置理由：任务书禁止改 `_kotlin_valueflow.py`；门内 `_one_hop` 已满足本波需要（含 `val displayContent = skillFileViewerBase(…)` 的对照 fixture）。

**R6 · gate2 的 `_const_truth` / `_literal_decides` 仍在门内（第二套死分支判定）**
- 触发条件：同族第三处复发（再有一个门需要「分支被字面量定死」的判定）。
- 触发后方案：整体换成 `const_bool` + 共享的字符串等价判定，删掉门内 `a == a` 同写检测。
- 搁置理由：本次只把 `_lit_value`（纯字面量）换成 `const_bool`；`_const_truth` 还含「同一拼写自比较」的额外语义，直接替换会改变既有 8 条臂的判定面，需要单独一轮回归。

**R7 · 交叉提示（不是残留，给主会话的账）：#49 的 Y 门仍绿**
- 事实：`mcp_oauth_lifecycle_check` 对 `redirectUri = null` 补丁 rc=0；该缝现由 gate2 `arm11` 覆盖。
- 若主会话认为「组装后的 config 是否带出用户输入」也该由 mcp 门判（它的 C4 只判回显），需 mcp 支自行在其半径内加判据；本支不越界（radius）。
- 同理 #46/#48 的 Y 门（`skill_import_offmain`）保持绿，理由见 ③ 的两门红说明表。

**R8 · `arm10`(gate1) 的按钮锚是 `R.string.skill_file_edit` + `MinisTextButton(`**
- 触发条件：Edit 抽成独立 `@Composable`（`SkillFileEditButton(…)`），标签不在 `MinisTextButton` 的 trailing lambda 里。
- 触发后方案：改为「按 `editContent` 的所有写入点分组，取进入编辑态（`isEditing = true`）之前的那次写入」——不依赖标签与组件名。
- 搁置理由：当前形态稳定（同一 lambda 内 `editContent = …; isEditing = true`）；失效方向是 fail-closed 报红而非静默放行。

---

## ⑧ 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t19

# pre-fix 重放（6 条见证 + 2 条自造半补丁；每条 = 应用→两门→还原+sha256）
python3 probe/replay.py pre      # → probe/t19-pre.json + probe/t19-w<k>-pre.patch
# post-fix 反攻（同样的 6 条补丁，必须全部 rc=1）
python3 probe/replay.py post     # → probe/t19-post.json + probe/t19-w<k>-post.patch
# 单条诊断（打印门的报错行，不改判据）
python3 probe/armcheck.py 45a 46 47 48 49 50

# L1 本门自证 + 真树
python3 scripts/scan/skill_file_save_result_check.py --selftest
python3 scripts/scan/skill_import_offmain_check.py --selftest
python3 scripts/scan/skill_file_save_result_check.py .
python3 scripts/scan/skill_import_offmain_check.py .

# L3 邻门 + 逐门循环（scan.sh 整份是 Errno 38 的高危路径，本轮侥幸跑通仅作加分证据）
for g in filepreview_print_bounded_check ime_inset_guard mcp_oauth_lifecycle_check \
         share_dir_prune_check skill_frontmatter_roundtrip_check; do
    python3 scripts/scan/$g.py . ; done
sh probe/gate_loop.sh

# L4 CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --workflow build-apk.yml --ref fix/r3-skillfilesave
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/r3-skillfilesave
```
