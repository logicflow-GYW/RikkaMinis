# FIX-R3-t04：第三轮（dual-gate bypass）— downloads / share / mediaplayer 三门判据加固

- **分支**：`fix/r3-fileprune-media`　**基线**：`main` @ `f0f14d46d`（第七批收口后）
- **门提交**：`5922aef95`（3 个门脚本，+1047 / −3；生产 `.kt` 0 行、`scan.sh` / `test_scan.py` / 共享原语 0 触碰、门 112 / 夹具 344 不变）
- **判决**：修前 **9/9 BLIND_CONFIRMED**（真树全绿下重放）→ 修后 **9/9 CAUGHT**（每条红在自己那条新判据上）；restore 9/9、`git status` 全程只含本支三个门脚本
- **双门状态**：见证 #88（11-1）修后**两门同时红**（`mediaplayer_release_guard` arm11 + `share_dir_prune_check` C-STREAM）；其余 8 条的「另一门」均属「不该管这条缝」，逐条说明见 §3。

---

## 1. 三门在判什么（一句话）

| 门 | 主题 | 本轮之前它判的 | 本轮补上的 |
|---|---|---|---|
| `downloads_atomic_update_check.py` | `BrowserTabPool` 的下载注册表必须靠 `_downloads.update{}` 原子推进 | CAS 是原子的、CAS 参数被消费、无陈旧快照 | **选了谁**（R7）· **字节真落盘**（R9）· **横幅用哪个成员重定位**（R10） |
| `share_dir_prune_check.py` | 缓存目录的产出面：写完要 prune、交接要带 flag 与判定、失败要反馈 | chooser 有 `NEW_TASK`、启动函数有判定、catch 有反馈、产物目录 prune | **uri 有没有真挂上去**（C-STREAM）· **chooser 有没有真启动**（C-LAUNCH）· **暂存目录有没有真删**（C-STAGING） |
| `mediaplayer_release_guard.py` | 播放/分享路径的资源与状态纪律 | release 路径、`onRelease`、`rememberMdMediaFile` 缝、`ImageRequest` 头部 | **uri 交付**（arm11）· **解析结果被谁消费**（arm8g）· **pause↔resume 是否互逆**（arm9） |

---

## 2. 轴：三条失效轴，全部是「中间那一步没人判」

1. **存在 → 决定**：三门都判「某个拼写/某次调用在不在」（`_downloads.update` 有、`putExtra` 有、`rememberMdMediaFile` 有、`session.state =` 有），但**这个拼写做的决定**（匹配到哪一条、挂了什么、消费了谁、落到哪个成员）无人判。
2. **量词空真**：`check_derived_handoff_src` 遍历 `startActivity` 站点来判 catch 覆盖 —— 站点数为 0 时循环一次都不跑，于是「chooser 建好了但从不启动」全绿。
3. **伴生流/伴生对象无判据**：`_downloads` 有六条判据、`_activeDownload` 一条没有；`cacheDir/shared` 有 prune 判据、同一函数里 `export-staging/<uuid>` 一条没有；`FileProvider` 解析有判据、`ExternalIntent.share` 的委派调用点连派生集合都进不去。

---

## 3. 见证重放（pre → post）

| # | 见证 | 轴 | 补丁（工作树实测） | 修前 rc（各门） | 修后 rc | 红在哪 |
|---|---|---|---|---|---|---|
| 85 | 09-1 | dual-gate | `BrowserTabPool.kt:576` `if (it.id == id)` → `it.id == -1L` | downloads 0 · browser_tabs_lifecycle 0 | **1** · 0 | R7「entry ... selected by `it.id == -1L`」 |
| 86 | 09-2 | dual-gate | `:734` 注释掉 `out.write(buf, 0, n)` | downloads 0 · browser_tab_exec_serial 0 | **1** · 0 | R9「never writes them to the destination stream」 |
| 87 | 09-3 | symmetric | `:602` 横幅 `firstOrNull { DOWNLOADING }` → `COMPLETED` | downloads 0 | **1** | R10「re-selected on COMPLETED, but the in-flight member is DOWNLOADING」 |
| 94 | 12-1 | dual-gate | `LogManagementScreen.kt:667-674` 删 `putExtra` + `clipData` + `addFlags` | share 0 · filepreview_print 0 · chat_menu_prefs 0 | **1** · 0 · 0 | C-STREAM「uri `uri` is resolved but never handed to anything」 |
| 95 | 12-2 | dual-gate | `FullscreenImageViewer.kt:249` 注释 `context.startActivity(chooser)` | share 0 · mediaplayer 0 · webview_holder 0 | **1** · 0 · 0 | C-LAUNCH「chooser `chooser` is built but never started」 |
| 96 | 12-3 | symmetric | `ChatExporter.kt:138` 注释 `finally { workDir.deleteRecursively() }` | share 0 | **1** | C-STAGING「staging directory `workDir` … never removed」 |
| 88 | 11-1 | dual-gate | `InlineMediaPlayer.kt:898` 删 `putExtra(EXTRA_STREAM, uri)` | mediaplayer 0 · share 0 | **1** · **1** | arm11 + C-STREAM（**两门都红**） |
| 89 | 11-2 | dual-gate | `StreamingMarkdownText.kt:1489` `.data(file ?: block.url)` → `.data(block.url)` | mediaplayer 0 · file_uri_parse 0 | **1** · 0 | arm8g「does not take the resolved file `file` as its .data(...) source」 |
| 90 | 11-3 | symmetric | `MediaPlayerManager.kt:222` `state = PAUSED` → `PLAYING` | mediaplayer 0 | **1** | arm9「idempotent no-op instead of a transition」 |

**「另一门」为什么仍然绿（逐条判归属，不是漏判）**：

- **#85 / #86** ← `browser_tabs_lifecycle_check` / `browser_tab_exec_serial_check` 的主题是 **WebView 标签生命周期**（创建/销毁/恢复、同一标签串行执行），它们的判据面里没有下载注册表；下载条目与字节落盘的唯一 owner 是 `downloads_atomic_update_check`。
- **#94 / #95** ← `filepreview_print_bounded_check`（打印有界解码）与 `chat_menu_prefs_listener_check`（菜单 prefs 监听生命周期）判的是同一次分享动作的**另外两件事**；分享交接的 owner 是 `share_dir_prune_check` 的 arm6/arm6b。
- **#89** ← `file_uri_parse_guard_check` 的主题是 **uri 解析本身**（cut/decode 顺序、`File(uri.path)` 截断、裸串装注释），不包含「Coil 的 `.data(...)` 消费了谁」；该缝的 owner 是 `mediaplayer_release_guard` 的 arm8 家族（8d 缝、8f 头部、本轮新增 8g 数据源）。
- **#88** 是本轮唯一真正落在两门之间的缝：解析在 `InlineMediaPlayer`、启动在 `ExternalIntent`，分享门原先的派生集合只认「自己有 `createChooser` / 自己有 `startActivity`+GRANT」的文件，于是这个文件两边都不进。**修法是把委派启动器纳入派生面**（见 §4.2），不是只在播放器门里补一条。

---

## 4. 修法（判据从「语句存在」搬到「决定发生」）

### 4.1 `downloads_atomic_update_check.py`（+380）

- **R7 条目选择必须由调用方的键决定**（09-1）
  - 实现：`_enclosing_fun` 取包含 update lambda 的函数与其形参表 → `callables` = 在该 lambda 里被**当函数调用**的形参（`transform(it)`）→ 只有当存在这种「函子参数」时才启用本判据 → `_selector_conds` 收集 lambda 内**所有选择点**（`if` 块 + `filter/firstOrNull/first/find/single/any/none/indexOf` 的 lambda 体）→ 取**包住 transform 调用**的最内层选择点，要求其条件里出现「键」（标量形参 + 由它们一跳派生的局部别名），且不能被常量折叠。
  - 效果：`it.id == id` 绿；`it.id == -1L` 红（键不在条件里）；`1 == 2` 红（常量折叠）；无任何选择点（对每个条目都 transform）红。
- **R9 字节必须真落盘且进度在写之后**（09-2）
  - 实现：`startUrlDownload` 体内找 `while (...)` 块中含 `read(...)` 的循环 → 从 read 实参与其前面最近的 `val X =` 派生 `(buffer, count)` → 在同一循环块内要求一次 `write(<buffer>, …, <count>)` 且 `path_feasible` → 再要求 `updateDownloadProgress` 的位置晚于该写。
  - 效果：删 `out.write(buf, 0, n)` 红；把写挪到进度之后也红（精度臂 `r3-progress-early`）。
- **R10 横幅重定位成员必须是在飞成员**（09-3）
  - 实现：`enum class DownloadState` 成员集 → `registerDownload` 里 `state = DownloadState.X` 派生**在飞成员** → `settleDownload` 的每次 `_activeDownload.value = …` 用 `expr_end` 取完整右值（多行链式），其中 `firstOrNull/first/find { … }` 谓词里引用的每个 `DownloadState.Y` 必须等于 X。
  - 效果：换 `COMPLETED` 红；整族改名（枚举 + 赋值 + 谓词同步改 `PENDING`）仍绿（精度臂 `r3-rename-inflight`）——判据派生自树，不写死 `DOWNLOADING`。
- fail-closed：`class BrowserTabPool` 在而 `startUrlDownload` / `registerDownload` / `settleDownload` / 枚举任一缺失 → 报违规（片段夹具因没有该 class 而不启用，新臂用完整基座 `FULL_BASE` 覆盖）。

### 4.2 `share_dir_prune_check.py`（+296）

- **C-STREAM**：`URI_RESOLVER`（`(?:[\w.]*\.)?FileProvider.getUriForFile`）→ `_home_val` 派生绑定名 → `_value_consumed` 判「离开本函数」：作为调用实参（`_in_call_args`，**续行感知**：深度 0 换行只有行尾是 `,`/`(`/运算符才继续）、被 `return`、或当尾值。
- **C-LAUNCH**：`HANDOFF_CHOOSER` 绑定名同上 → 同一条 `_value_consumed` 判据（`startActivity(chooser)` / `launch(ctx, chooser, …)` / `return chooser` / 尾值都算交出去；`chooser.addFlags(…)` 只当接收者，不算）。这一条直接堵掉「`startActivity` 站点数 0 ⇒ 循环空真」。
- **C-STAGING**：把「建目录」从树里派生 —— `X.mkdirs()` 且 `val X = …File(` 且存在 `File(X, …)`（往里写文件）→ 要求 `X.deleteRecursively()/delete()` 落在某个 `finally { }` 区间内且可达。arm1/arm3 只判 `cacheDir/share[ d ]` 的 prune，`export-staging/<uuid>` 的空缺由此补上。
- **派生集合扩展**：`derive_handoff_files` 增加 `HANDOFF_DELEGATE`（`ExternalIntent.share(` 的调用方）。理由：委派给共享启动器同样是一次「把文件交给另一个应用」，这类文件自己没有 `startActivity` 站点，旧条件看不见它 —— 派生面 15 → 18 个文件。
- **绑定名派生升级**：`_binding_name`（按语句起点切）换成 `_home_val`（最后一条声明 + 除最后一条外每条换行都必须是续行）。`val uri = try { … }` 这种值表达式带块、调用在块内的形态由此可判，这正是 #88 在分享门里原本被静默跳过的原因。

### 4.3 `mediaplayer_release_guard.py`（+374）

- **arm11**：`shareMediaFile` 的 `FileProvider` uri 必须交出去（同 C-STREAM 的 `_handed_on`）；文件里 0 个 `getUriForFile` 站点 = 显式红（形态迁移不是静默通过）。
- **arm8g**：`val X = rememberMdMediaFile(...)` 所在函数若同时有 `ImageRequest.Builder(`，则该 request 链的 `.data(...)` 实参必须消费 X；没有 `.data(` 也红。视频/音频块（`setDataSource(file.path)`）因无 `ImageRequest.Builder` 而不启用。
- **arm9**：从 `enum class PlayerState` 派生成员集 → 对 `pause` / `resume` 各取「守卫要求的成员」与「实际赋给 session 的成员」与「上报成员」：
  - S1 赋值成员 ≠ 守卫成员（否则幂等空转）；
  - S2 `pause` 落到的成员 == `resume` 要求的成员，反之亦然（互逆）；
  - S3 上报成员 == 赋值成员；
  - 三个函数/守卫缺失、成员不在枚举内 → fail-closed。

### 4.4 自测臂（只增不减，冻结 token 原样保留）

| 门 | 既有标记（未动） | 本轮新增标记 | 新臂 |
|---|---|---|---|
| downloads | `SELFTEST=PASS (downloads_atomic_update: 4 fixtures)` | `DOWNLOAD_UPDATE_SEAM_SELFTEST=PASS (3 seams …; +3 witness arms, +1 precision arm)` | `r3-id-literal` / `r3-no-write` / `r3-progress-early` / `r3-banner` / `r3-rename-inflight`(clean) / `r3-full-base`(clean) |
| share | `SHARE_DIR_PRUNE_SELFTEST=PASS` | `SHARE_HANDOFF_SEAM_SELFTEST=PASS (3 seams …; 7 arms)` | `r3-stream uri detached` / `r3-chooser never launched` / `r3-staging never removed` / `r3-staging cleanup outside finally` + 3 clean 臂 |
| mediaplayer | `MEDIAPLAYER_RELEASE_SELFTEST=PASS` | `MEDIAPLAYER_SEAM_SELFTEST=PASS (3 seams …; 7 arms + 1 fail-closed)` | `r3-state pause is a no-op` / `reports the wrong member` / `resume guards its own target` / `r3-share uri detached` / `M8g1` + 4 clean/精度臂 + 1 fail-closed |

清洁臂保证判据不误伤：整族改名（`DOWNLOADING`→`PENDING`）、改名绑定（`file`→`resolved`）、换载体（`setDataAndType`）、把 uri 交给共享启动器 —— 全部仍绿。

---

## 5. 四层证据

1. **本门**：三门真树 `rc=0`；`--selftest` 全绿（臂数只增：4→4+6 / 既有+7 / 既有+8）；冻结 token 三条逐字符保留。
2. **邻门**：本支涉及的三门互跑全绿；语义相邻的 `filepreview_print_bounded`、`chat_menu_prefs_listener`、`webview_holder_url_source`、`file_uri_parse_guard`、`browser_tabs_lifecycle`、`browser_tab_exec_serial` 全绿。
3. **全量门循环（逐门独立进程，未用 `scan.sh`）**：**112 / 112 rc=0**。沙箱本轮再次遇到 `Errno 38 (ENOSYS)` 风暴（首遍 42/112 绿，其余报 fork/wait 失败），按既往做法分遍补跑：第二遍补 11 门、随后 7 批（8×7+2）逐门重跑，**最终 0 门未覆盖**（`probe/t04-gate-loop*.json`、`/tmp/loop*.log`）。
4. **`test_scan.py`**：沙箱侧两次尝试都没跑完（首遍无输出即被 ENOSYS 风暴杀掉；第二遍 `-u` 无缓冲跑到 `deep-audit-1017` 段 204 行后中断，未出 `ALL 344`）⇒ **以 CI 侧 `ALL 344 CASES PASS` 为权威证据**（见 §8）。
5. **CI**：见 §8。

---

## 6. 残留（每条带「触发条件 / 触发后方案 / 搁置理由」）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `_value_consumed` / `_handed_on` 只判到「离开本函数」，跨函数交付（A 函数解析、B 函数挂载）不追 | 出现「FileProvider 在 A、`putExtra` 在 B」的写法 | 用 `call_args` + 一跳调用闭包（`resumer_names` 式两跳闭包）把交付链追到第二跳 | 当前四处解析点都在同一函数内交付；跨函数会引入「谁来判第二跳」的新装置，影响面远小于成本 |
| R2 | C-STREAM 只否定「完全没有载体」，不判「挂到了错的载体」（如挂成字符串 extra） | 出现需要用接收方约定的自定义 extra 的分享 | 从接收方/Intent 消费者侧派生期望载体，做双向比对 | 「错了载体」在运行时立刻暴露（对方拿到空文件），不是静默失效；且当前树无此写法 |
| R3 | R7 只判最内层选择点，且要求「键出现在条件里」，不做键的**类型/来源**校验（`it.id == -1L` 红，但 `it.id == otherId` 若 otherId 也是形参则绿） | 出现「把另一个形参当键」的错误 | 用 `value_sources` 追到调用点的实参身份（caller 侧比对） | 需要跨函数值流；当前 `updateDownloadEntry` 只有一个标量形参 |
| R4 | R9 只判 `while` 循环里的 copy（`for`/`copyTo`/`transferTo` 不在判据内） | 下载路径改用 `copyTo` / `for` 循环 | 把循环形态泛化为「任何含 `read(` 的重复块」，并为 `copyTo` 增一条等价判据 | 现状是 `while (true)` + read；换形态时 CI 会红（无 copy 循环 = fail-closed 红），不会静默 |
| R5 | arm8g 只认 `rememberMdMediaFile` 一族解析器名 | 解析器改名/换实现 | 把「解析函数名」从常量改成从 `@Composable fun remember*File(` 派生 | 现只有一处解析器；改名会同时让 arm8d 红（同一文件） |
| R6 | arm9 只覆盖 pause / resume 两命令的互逆 | 新增 `stop`/`seek` 参与状态机或引入第三态 | 泛化为「所有读-改状态的命令按基础状态机建模」 | 当前状态机只有这两条转换；泛化会引入状态图配置，装置比例不合 |
| R7 | 门自证 stdout 不进 CI 日志（`*_SEAM_SELFTEST=PASS` 只有 `test_scan` 断言才可见） | 需要在 CI 日志里直接看到新臂 | 主会话在 `test_scan.py` 注册三条新标记（本支按硬约束不改 `test_scan.py`） | 冻结面纪律；注册项已列在此，主会话一处 append 即可 |

---

## 7. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t04

# 1) 修前/修后反攻（逐条改生产 .kt → 跑全部相关门 → 还原 + sha256 校验）
python3 probe/t04_replay.py pre      # → probe/t04-pre.json  9/9 BLIND_CONFIRMED
python3 probe/t04_replay.py post     # → probe/t04-post.json 9/9 CAUGHT

# 2) 本门自测 + 真树
python3 scripts/scan/downloads_atomic_update_check.py --selftest
python3 scripts/scan/share_dir_prune_check.py --selftest        # 需要 cwd = 仓库根
python3 scripts/scan/mediaplayer_release_guard.py --selftest

# 3) 全量门循环（逐门独立进程，沙箱下不要用 scan.sh）
python3 probe/t04_gate_loop.py .            # 首遍
python3 probe/t04_gate_loop2.py .           # 补跑首遍失败/未跑的
python3 probe/t04_run_subset.py . <gate>…   # 按批补跑（ENOSYS 风暴时用）

# 4) 夹具全量
python3 -u scripts/scan/test_scan.py

# 5) CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --workflow build-apk.yml --ref fix/r3-fileprune-media
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/r3-fileprune-media
```

---

## 8. CI

**终态 run** `40319403674` / **#2749** / job `121560177272` / attempt 1 **success**
**head_sha** `10a4ae36db09e6d2bca32686dece929ccfd076b3`（与本地 HEAD 逐字符一致）
日志 1,486,840 B / NUL 0 / 26 步 / 唯一 skipped = `Publish to Releases`

逐条读数（机械核过）：

| 读数 | 期望 | 实测 |
|---|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1 | **1** |
| `ALL 344 CASES PASS` | 恰 1 | **1** |
| `^ e: `（Kotlin 编译错误行） | 0 | **0** |
| 门头条数 / 编号互异 | 112 / 1..112 | **112 / 112（min 1 max 112）** |
| 含 `FAILED` 的行 | 全是 `…PASSED` 用例名子串 | **6 行，逐行确认全以 PASSED 结尾** |
| `SHARE_DIR_PRUNE_SELFTEST=PASS` | 1 | **1**（既有标记，test_scan 真断言） |
| `MEDIAPLAYER_RELEASE_SELFTEST=PASS` | 1 | **1** |
| `SELFTEST=PASS (downloads_atomic_update: 4 fixtures)` | 1 | **1** |

**首次 run 失败（诚实记录，我自己的自伤）**：`40317789130` / #2748 @ `5922aef955`，**step 3 `Run pre-build scan gate` failure** —— 日志 `SHARE_HANDOFF_DERIVED_SELFTEST=FAIL` → `SHARE_DIR_PRUNE_SELFTEST=FAIL` → `❌ FAILURES: 1`。根因：C-STREAM 上线后，**既有的 clean 夹具** `b04-compliant`（以及由它派生的 `b04-compliant OR-ed with an unknown boolean`）自己成了反例 —— 它把 `FileProvider` uri 解析出来后就建 chooser，从不挂载体。修法 `10a4ae36d`：夹具的 `ACTION_SEND` Intent 真的挂上 uri（`putExtra(EXTRA_STREAM, uri)` + `clipData` + `type`），并保持派生态变异依赖的四处精确字符串不变；修后本门 `--selftest` rc=0、四条聚合标记全 PASS。
**教训（写进本报告供后续复用）**：加新判据后必须核**全部既有聚合标记**（`*_SELFTEST=PASS` 不是只看新标记），而且要在**提交前**跑一次 `--selftest` 的 rc —— 我这次只 grep 了新缝标记，把既有 clean 夹具的假阳性留给了 CI 才发现，多花一轮 18 分钟。

**新缝标记在 CI 日志 0 命中**（`DOWNLOAD_UPDATE_SEAM_SELFTEST` / `SHARE_HANDOFF_SEAM_SELFTEST` / `MEDIAPLAYER_SEAM_SELFTEST` 各 0）：门自证 stdout 不进 CI 日志，`test_scan.py` 只回显它已注册的标记。注册项见 §6 R7，主会话一处 append 即可让 18 条新臂在 CI 里有见证。
