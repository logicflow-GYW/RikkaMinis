# FIX-W1027-B — `file://` 契约 + fastio tar 守卫护栏（W-07 / W-08 / W-09 / W-10）

分支 `fix/w1027-url-tar-wiring`｜基线 main `4a48a6be1`（`witness-1027` 第四轮对抗审的合并后树）｜标签 `[T-w1027-url-tar-wiring]`
源码提交 `8e8f8870`（分支 CI **#2789** / run `40501673438` success，`head_sha` 与本地逐字符一致）；本报告随后续提交回填，源码树与之差异为 0。
现场来源：`/var/minis/shared/work/witness-1027/report-witness-1027.md`（W-07/W-08/W-09/W-10 四条 witness patch）＋ `VERIFY-1027.md`（12/12 补丁机械复证）。

---

## 1. 现象

四条见证的共同形态一句话：**生产实现对，门只到「拼写存在」，不到「实参 / 极性」**。打完补丁后两门仍全绿、347 夹具仍全绿、CI 仍绿——因为没有任何判据读那四处的**实参、所有权、极性**。

| 见证 | 补丁（一行级） | 打完之后的世界 |
|---|---|---|
| `W-07` | `WebPreviewShortcut.pin` 删掉 `decodeUriPath(…)`，保留 `url.removePrefix("file://")` | 含中文 / 空格 / `#` 的本地 HTML 加桌面快捷方式：`Uri.Builder().path(absPath)` 把路径当未编码文本**二次编码**，点图标打不开 |
| `W-08` | `ChatScreen.openHtmlPreview` 写成 `decodeUriPath(fileUrlFor(file))` | 构造端把刚编码的 URL 又解回裸路径：holder 的 `currentUrl` 相等性永不成立（每次点都重建 WebView，滚动/输入丢失）；文件名含 `%XX` 字面量的被二次解码 → 404 |
| `W-09` | `tarCreateInto` 调 `tarCreate(…)` 漏掉第 5 实参 | `archivePath = null` ⇒ `isSelfArchive` 直接 `false` ⇒ §6 F8 自我入档防护整条短路：`tar -cf src/a.tar src` 边读边写、成员声明长度与载荷不符、后续成员错位，报告仍写 `skipped_self: 0`、退出码 0 |
| `W-10` | `tarExtract` 硬链接分支 `if (!force)` 翻成 `if (force)` | 不带 `--force` 时**静默删改**磁盘上已有文件（数据丢失）；带 `--force`（报错文案自己让你加的那个旗标）反而拒绝——自相矛盾的死循环 |

`W-10` 之所以从未被 347 夹具发现：**所有既有硬链接测试都解压到全新空目录**，`Files.exists(target)` 恒假，那段守卫一次都没跑过（见 §4 的测试补强）。

## 2. 根因

四条各自缺的是**不同类**的判据，但缺法同源：门的判据锚在「某个 token / 某个调用存在」，而见证保留全部 token、只改**实参、所有权、极性**。

- `W-07`：`decode_flow_problem()`（`file_uri_parse_guard_check.py:207`）对 `file://` 契约的**两个**消费者（`WebPreviewBottomSheet:646`、`StreamingMarkdownText:660`）都调了，**第三个**（`WebPreviewShortcut`）只被 F4（禁 `Uri.parse(...).path`）和 F4b（根前缀剥离）覆盖 —— 删掉 decode 两条都绿。
- `W-08`：F7（`file_url_construction_problem`）判的是**构造端用什么拼**（操作数必须 `encodeFileUrlPath(…)` / `fileUrlFor(…)`），`decodeUriPath(fileUrlFor(file))` 里操作数**仍是** `fileUrlFor(`，于是 F7 天然放行；`file_preview_url_encode_check.py` 只扫 `FilePreviewScreen.kt`。缺的是「编码产物**归谁**」这条方向性判据。
- `W-09`：`check_handler` 只查 `finishTar(deniedEntry(...), true, …)` 的**退出码 flag**，从不读 `tarCreate(…)` 的实参个数；而第 5 参有 `= null` 默认值，漏传在语法上完全合法、在语义上把整条防护关掉。
- `W-10`：门完全不扫 `tarExtract`。且「已存在时的行为」当时是**三份拷贝**（`'0'` / `'1'` / `'2'`），任何单点判据都只能盖住一份。

## 3. 改动面（源码 4 文件，合计 +999 / −54）

| 文件 | 改动 | 说明 |
|---|---|---|
| `scripts/scan/file_uri_parse_guard_check.py` | +273 / −9 | 新增判据 **F4c**（W-07）与 **F7b**（W-08）；`decode_flow_problem()` 增可选 `permit` 钩子 + `_chain_calls()` 整链走查；夹具 +5，自测臂 32 → **37** |
| `scripts/scan/fastio_tar_bounds_check.py` | +350 / −0 | 新增判据 `check_tar_create_archive_arg`（W-09）与 `check_extract_replace_decision`（W-10）；`GOOD_HANDLER` 夹具补上合规的 `tarCreate` 接线；夹具 +8，自测臂 71 → **81** |
| `src/.../sandbox/offload/FastioTar.kt` | +59 / −45 | **抽共享 helper**（任务书 §2 W-10 明示优先项）：三份「已存在目标」逻辑合成 `clearOccupiedTarget(target, force, out, member, reader, replacedKind)`，三个写分支改为 `if (!clearOccupiedTarget(…)) continue`；行为等价（既有 35 用例 + 新增 4 用例全绿） |
| `src/.../sandbox/offload/FastioTarTest.kt` | +120 / −0 | 新增 4 条：`'1'` 已存在文件 force=false 拒绝（字节不变）/ force=true 替换 / 已存在目录 force=true 仍拒绝 / 无 archivePath 时把正在写的归档打进去（W-09 负控） |

**为什么抽 helper 而不是给三分支各写一条判据**（任务书给了这条自由，这是采纳理由）：三份拷贝本身就是缺陷形态的一部分——`W-10` 翻的正是第 3 份，而第 1、2 份当时是对的。判据若按分支写，判据自己也会有三份，下次漏的是「判据的第 3 份」。抽成 helper 后判据只需判**一处语义** + 一条**路由**判据（每个写分支都必须走它），成本更低且覆盖更强（本轮 JVM 负控显示：极性一翻，5 条用例同时红，其中 3 条是 `'0'`/`'2'` 的老用例）。

## 4. 判据与测试

### 4.1 `file_uri_parse_guard_check.py`

**F4c**（W-07）：
```python
problem4c = decode_flow_problem(_body_text(shortcut[0], "pin"), _body_text(shortcut[1], "pin"),
                                'removePrefix("file://")', permit=_shortcut_chain_permit)
```
- 复用既有 `decode_flow_problem` 的三条子规则（① 调用链不得紧跟改写 ② local 绑定 ③ 绑定的读点不得被改写），scope 收在 `pin` 体内（切不出即 fail-closed）。
- **新增 `permit` 钩子是必须的，不是方便**：`pin` 解码之后合法地做两件事——`absPath.removePrefix("/var/minis")`（剥沙盒根）与 `absPath.trimEnd('/')`（与根比较前修剪）。不加 permit 时 F4c 在**修好的树上就是红的**，而「红在正确代码上的门」的唯一结局是被关掉。permit 只放行这两个形态：`trim*` 与参数**字面量为 `"/var/minis"` 的** `removePrefix`；截断族（`substringBefore('#')` / `split` / `replace` / 任意别的 `removePrefix`）照红。
- `permit=None` 时 F1a/F2a 行为逐字节不变（这两处的解码值就是直接进 `File(...)`，没有任何合法链）。
- **permit 只买一步，不买整条链**：`_chain_calls()` 沿链逐段走，每一个**改写**步骤（`POST_DECODE_POISON` 家族）都要单独被放行；`removePrefix("/var/minis")` 后面再跟 `substringBefore('#')` 一样红。初版只读第一个方法名 —— 那等于「放行第一步 = 放行整条链」，是加 permit 时自己挖的洞，自测臂 `w07-permit-then-cut` 专门钉它。`POST_DECODE_POISON` 之外的链步（`.isBlank()` 之类）是导航不是改写，不判。

**F7b**（W-08）：
```python
FILE_URL_ENCODERS = ("fileUrlFor(", "encodeFileUrlPath(")
# decodeUriPath( 的任一实参里出现编码器调用 ⇒ 违规
```
- 扫描面 = `analyse()` 已有的 `ui_views`（`ui/**` + `webapp/**` 派生 walk，与 F4/F7 同一派生面，不写死文件清单）。
- 方向性：编码产物只能被**消费端**（`WebViewHolder.fromAssetLoaderUrl` / sheet / markdown resolver）decode，不能在构造点自己解回。所以判据是「decode 的实参里有没有编码器调用」，不是「文件里有没有两者」。

### 4.2 `fastio_tar_bounds_check.py`

**`check_tar_create_archive_arg`**（W-09）：`tarCreateInto` 内 `tarCreate(…)` 必须**恰好一次**、括号切片（`match_bracket` + 共享 `call_args`，不是正则数逗号）、实参**恰好 5 个**、第 5 个解析后逐字等于 `archivePath`。省略 / `null` / 别的标识符 / 字面量都红；切不出函数体或调用数不为 1 一律 fail-closed（门看不见就不算绿）。

**`check_extract_replace_decision`**（W-10），判 helper 的**语义**而非拼写：
| 判据 | 红法 |
|---|---|
| `already_exists` 报错被**否定形式**的 force 守卫覆盖（`!force` / `!(force)` / `force.not()` / `force == false` / `false == force`） | 守卫是肯定形（`force` / `force == true`）→「极性 INVERTED：不带 --force 也删改、带 --force 反而拒绝」 |
| 守卫既非否定也非肯定（如 `shouldReplace`） | 红（判定不出就不许算绿） |
| 报错语句完全没有守卫 | 红 |
| `Files.delete(target)` 必须在报错**之后** | 之前 →「无论有没有 --force 都会删」 |
| 不得落在 `!force` 的拒绝块**内部** | 内部 →「拒绝的话讲了，文件照样没了」 |
| helper 必须存在、必须有 `Files.delete` | 缺 → 红（`--force` 再也替换不了任何东西） |
| `tarExtract` 内不得再出现 `already_exists` | 出现 →「规则又长回第 4 份拷贝」 |
| `'0'` / `'1'` / `'2'` 三个写分支必须**调用** helper | 某个分支绕过 → 红（含「分支切不出」的 fail-closed） |

两处细节，都是踩过的坑：
- 报错文案在 `mask_code` 视图里被抹平（字符串内部），所以文案用 `literal_view` 定位、结构用 `code_view` 判定；两个视图**等长同源**，位置可互换。
- `when` 分支标签 `'1'` 同理只能从 literal view 读（code view 里 char 字面量内部被抹成空）。

### 4.3 JVM（沙箱实跑真生产源码）

`FastioTar.kt` 是纯 `java.nio`（无 Android / org.json），因此本轮**编真文件、跑真测试**，只用影子替掉 `EntryBudget` + `charge`（它们在 `FastioOffloadHandler.kt` 里，属 Android 侧），影子与源切片逐字节相等由脚本断言（`probe-b/shadow_check.py`，371 字符）。
探针：`/var/minis/shared/work/witness-1027/probe-b/probe.sh`（编译 + JUnitCore 运行）、`mutate_jvm.sh`（变异负控）。

新增 4 条测试（期望值全部是写死字面量，不从被测常量推导）：
1. `a hard link member refuses to replace an existing file without force` —— 断言 `errors == ["b.txt: already_exists (pass --force to replace)"]`、`hardLinked == 0`、**磁盘字节仍是 `"keep me"`**。
2. `a hard link member replaces an existing file with force` —— 同一归档同一目标，`force=true`：无 error、`hardLinked == 1`、字节变成 `"hi"`。两条互为镜像臂，翻转的守卫不可能同时满足两者。
3. `a hard link member never replaces an existing directory even with force` —— `'1'` 分支继承的第三条决策（`--force` 只删文件，不删目录），断言目录与其中文件都还在。
4. `without the archive path the walk packs the file it is writing` —— W-09 的**机制负控**：同一棵树、同一归档文件，只缺第 5 实参 ⇒ `skippedSelf == 0`、`files == 2`（`a.txt` + 归档自己）、解压后 `pack.tar` 真的在里面。这证明「handler 传不传 archivePath」是**有观测后果**的，不是装饰。

## 5. 验证读数

### 5.1 两门（真树 + 自测）

```
file_uri_parse_guard_check.py  真树 rc=0  Summary: 18 passed, 0 failed   （原 16 → 18，新增 F4c/F7b）
file_uri_parse_guard_check.py  --selftest  FILE_URI_PARSE_GUARD_SELFTEST=PASS (37 fixtures)   原 32
fastio_tar_bounds_check.py     真树 rc=0  GATE=GREEN fastio_tar_bounds
fastio_tar_bounds_check.py     --selftest  FASTIO_TAR_BOUNDS_SELFTEST=PASS (81 arms)          原 71
```

### 5.2 四条见证反攻（打回原形 → 门必须红 → 还原逐字节一致）

脚本 `probe-b/apply_wirnesss.py <W-xx>`：把见证补丁打进真树 → 跑门 → 打印红判据 → 还原 → 复查 sha256 与 rc。

| 见证 | 门的读数 | 红在哪条判据 |
|---|---|---|
| `W-07` | `rc=1`，红 1 条 | `F4c … via decodeUriPath(url.removePrefix("file://")) — the file:// path must be resolved exactly once through decodeUriPath(…)` |
| `W-08` | `rc=1`，红 1 条 | `F7b — ChatScreen.kt decodes the result of 'fileUrlFor' at the site that built it (…)` |
| `W-09` | `rc=1`，红 1 条 | `VIOLATION FastioOffloadHandler.kt:55 tarCreate(…) is called with 4 argument(s), not 5 …` |
| `W-10` | `rc=1`，红 1 条 | `VIOLATION … the already_exists refusal in clearOccupiedTarget is guarded by 'force' — the polarity is INVERTED …` |

四次还原后 sha256 均 `BYTE-IDENTICAL`，门复查 `rc=0`。

**自测臂里另有 6 条独立反攻**（不依赖真树）：极性反 / `delete` 移到 force 检查之前 / `delete` 挪进拒绝块 / 规则被内联回某分支 / 分支干脆不问 helper / **permit 放行的那一步之后接一次截断** —— 每条都断言**红在对应判据上**（`expect_trip` 带 needle），不是「只要红了就算过」。反向还有两条**精度臂**（decode 的实参是 `fileUrl` 参数而非 `fileUrlFor(` 调用、pin 的根剥离 + 边缘 trim）必须**保持绿**，防「把正确代码判红」。

### 5.3 邻门 6 道（rc 全 0）

```
webapp_asset_url_cleanup_check.py      rc=0  PASS all 18 arms
file_preview_url_encode_check.py       rc=0  OK (arm1 + arm2 + arm3 green)
offload_cli_args_check.py              rc=0  PASS（含 fastio tar verdict shared / 9/9 payload …）
readonly_mount_write_guard.py          rc=0
file_provider_paths_coverage_check.py  rc=0  Summary: 0 violation(s)
i18n_check.py                          rc=0  ALL CLEAN
```
（首轮我把 `..` 当 repo root 传给门，6 道全红——那是我的调用错，不是门红；换 `../..` 后全绿。）

### 5.4 JVM

```
SHADOW_OK: EntryBudget + charge are byte-identical to FastioOffloadHandler.kt (371 chars)
OK (39 tests)            ← 基线 35 + 新增 4
```
变异负控（`mutate_jvm.sh`：把 helper 的 `!force` 翻成 `force` 后重跑**真测试**）：
```
Tests run: 39,  Failures: 5
1) a hard link member never replaces an existing directory even with force
2) --force cannot replace a directory member with a symlink of the same name
3) extraction reuses an existing directory but refuses to replace an existing file without force
4) a hard link member refuses to replace an existing file without force      ← 新增
5) a hard link member replaces an existing file with force                   ← 新增
```
新增的两条各自红在两个相反方向，另有三条老用例因共享 helper 一起变红——这正是「一分事实测三处」的收益。

### 5.5 全量门 + 夹具（本地）

```
sh scripts/scan/scan.sh --jobs 3     →  Summary: 112 passed, 0 failed
逐用例独立进程（见 5.6）             →  47/47 用例 OK，累计 pass=347 fail=0
```

### 5.6 `test_scan.py` 的运行方式（环境记录）

`test_scan.py` 全程不 print，单进程跑满 ~5–6 分钟，在沙箱里必然撞 180 s stall-guard；且本轮三个兄弟会话同时各跑一份 `test_scan.py`。三次尝试的结局：

| 尝试 | 结果 |
|---|---|
| 1. `nice python3 scripts/scan/test_scan.py \| tail` | 180 s 后 stall-guard 杀（输出被 `tail` 吞掉，心跳也没用） |
| 2. 后台 + 心跳包装（`probe-b/run_fixtures.py`，不改套件） | 跑到第 41 个用例被整壳杀（`[case] test_auto_approve_gates` 之后无任何输出，**无 traceback = 被 kill，不是断言失败**） |
| 3. 同上，兄弟会话空闲窗口重跑 | 跑到第 33 个用例同样被 kill |
| **最终**：逐用例独立进程（`probe-b/per_case.py`） | **47/47 OK，pass=347 fail=0**（1..38 pass=293 + 39..47 pass=54） |

`per_case.py` 的用例顺序**从 `main()` 自己的调用列表里解析出来**（不会与套件漂移），套件本身零改动；每个用例一个进程 ⇒ 一次 kill 只损失一个用例。两个细节：① 任何 `| grep`/`| tail` 都会把心跳吃掉，从而重新触发 stall-guard（本轮踩过一次）；② 子进程要 `cwd=repo`，否则门按相对路径找不到树。
本地读数合计 **pass=347** 与 CI 的 `ALL 347 CASES PASS` 完全吻合 —— 这也是「逐用例拆分没有漏跑用例」的交叉验证。

### 5.7 分支 CI（确定性停止节点）

`run 40501673438` / **#2789** / attempt 1 / **success**（2026-10-27T12:10:19Z → 12:34:52Z，24m33s）
`head_sha = 8e8f88704192…` 与本地 `HEAD` **逐字符一致**（`git rev-parse HEAD` = `8e8f88704192115999ebad92c0c92a6dcbce7af1`）。

| 读数 | 值 |
|---|---|
| 步骤 | 26 步；**仅 23 `Move the rolling tag android-latest`、24 `Publish to Releases` skipped**（均 main 门控），其余全 success |
| 门 | `║ Summary: 112 passed, 0 failed` 恰 **1** 次 |
| 夹具 | `✅ ALL 347 CASES PASS` 恰 **1** 次（无 `ALL 344`） |
| 新判据在册 | `[OK] F7b …` / `[OK] F4c …` 各 1 行（`--jobs 3` 下顺序打乱，内容在） |
| 自测带牙 | `✅ file_uri_parse_guard_check selftest has teeth (FILE_URI_PARSE_GUARD_SELFTEST=PASS)`、`✅ fastio_tar_bounds_check selftest has teeth (FASTIO_TAR_BOUNDS_SELFTEST=PASS)` |
| 新单测 | `FastioTarTest > without the archive path the walk packs the file it is writing PASSED`、`> a hard link member refuses to replace an existing file without force PASSED`、`> a hard link member replaces an existing file with force PASSED`、`> a hard link member never replaces an existing directory even with force PASSED` |
| 编译 | `^ e: ` **0** 行 |
| 日志 | 1,469,129 B / NUL 0；含 `FAILED` 且不含 `PASSED` 的行 **0** |

（#2787 是对前一提交 `3a39a7d5` 的 run，被 `concurrency: cancel-in-progress` 在 12:10:36 取消——gate 脚本随后又改了一次，所以作废是正确结果，不是失败。）

## 6. 残留（每条带触发条件 / 触发后方案 / 搁置理由）

- **R1 shortcut 的 permit 只认两种形态**：`trim*` 与参数为 `"/var/minis"` 的 `removePrefix`（`_chain_calls` 保证 permit 只买一步，后续截断照红）。触发条件 = 有人给 `pin` 的解码后路径加第三种改写（如 `substringAfterLast('/')`）。触发后方案 = 先在该处 KDoc 写清「为什么这条改写不会动文件名」，再把形态加进 `_shortcut_chain_permit`，并补一条「permit 之后接截断仍红」的臂。搁置理由 = 半径纪律，现在没有第三种形态。
- **R2 F7b 只看实参文本包含编码器调用**：`val u = fileUrlFor(f); decodeUriPath(u)` 这种「先落变量再解码」不红。触发条件 = 真出现这种两行写法。触发后方案 = 用 `_kotlin_valueflow.value_sources` 把 `u` 的来源追一层。搁置理由 = 现在两处构造点都是内联一表达式，加一层数据流是纯预留。
- **R3 W-09 判据只覆盖 `tarCreateInto`**：`tarCreate(` 若将来在别处被调用（另一条 CLI 路径），那条不受此判据管。触发条件 = 新增第二个调用点。触发后方案 = 把判据改成「全树 `tarCreate(` 调用点派生集合，逐点要求 5 参」。搁置理由 = 现在全仓唯一调用点（`rg` 实测 1 处），派生集合会引入「本来合法」的噪声。
- **R4 生产侧仍有一个「另一出口」**：`FastioOffloadHandler.kt` 里 `isSelfArchive` 的 `null → false` 默认语义没变（本支按硬约束只补门）。触发条件 = 有人再写一个新的 tar 调用点。搁置理由 = 改默认值会波及所有调用点的语义，属产品决策，且门已经挡住「漏传」这一具体形态。
- **R5 沙箱 `FastioOffloadHandler.kt` 不可编译**：`EntryBudget`/`charge` 走影子（逐字节校验）。触发条件 = 这两个声明搬家。触发后方案 = 更新 `shadow_check.py` 的切片锚点（脚本会当场 SHADOW DRIFT 报错，不会静默）。

## 7. 未验证

- **真机 / Android 运行时**：`FastioOffloadHandler` 的 `tarCreate` 接线、`WebPreviewShortcut.pin` 的深链、`ChatScreen.openHtmlPreview` 的 holder 复用都只有静态判据，没有真机行为证据（沙箱无 Android，无 WebView）。CI 只保证 Kotlin 编译 + 单测 + 112 门 + 347 夹具。
- **`tarExtract` 的 `'1'` 分支在真实设备文件系统上的行为**（FAT/exFAT 无硬链接 → 走 copy 路径）未复测；JVM 侧只验证了 `Files.copy` 语义。
- **`skipped_self` 的端到端计数**（handler 层 JSON 字段 `skipped_self`）未在真机验证；JVM 侧验证的是引擎的 `TarCreateOutcome.skippedSelf`。
- `W-10` 的 `'2'`（symlink）分支在 `--force` + 已存在**文件**时的替换路径：既有测试覆盖（`--force` 替换既有 symlink），本轮未新增。

## 8. 复现命令

```sh
R=/var/minis/shared/work/witness-1027/wt-url-tar-wiring
P=/var/minis/shared/work/witness-1027/probe-b

# 两门
cd $R && python3 scripts/scan/file_uri_parse_guard_check.py .        # rc=0, 18 passed
cd $R && python3 scripts/scan/file_uri_parse_guard_check.py --selftest   # 37 fixtures
cd $R && python3 scripts/scan/fastio_tar_bounds_check.py .           # GATE=GREEN
cd $R && python3 scripts/scan/fastio_tar_bounds_check.py --selftest  # 81 arms

# 四条见证反攻（自动还原 + sha 校验）
cd $P && python3 apply_wirnesss.py W-07    # 同理 W-08 / W-09 / W-10

# JVM（真生产源码 + 真测试，仅 EntryBudget/charge 影子）
cd $P && sh probe.sh          # OK (39 tests)
cd $P && sh mutate_jvm.sh     # 极性变异 → Failures: 5

# 全量
cd $R && sh scripts/scan/scan.sh --jobs 3
cd $R && python3 $P/per_case.py "$PWD/scripts/scan" "$PWD"        # 47/47, pass=347
```
