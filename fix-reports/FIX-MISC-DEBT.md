# FIX-MISC-DEBT — 顺路清三笔登记债（C1 压缩模型富化 / C2 门变量初始化 / C3 KDoc 形态 ponytail 覆盖）

- 分支：`fix/misc-debt-sweep`
- 基线：main `627f23f2e`（= R3 + P1 并行门禁合并后）
- 改动面：2 个生产 `.kt` + 1 个门脚本（+49 / −2），**另 1 个脚本在仓库外**（`scan_debt.py`，见 §4.3 —— 任务书写的 `scripts/scan/scan_debt.py` 在仓库里不存在）
- 冻结面：`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰；门 112 / 夹具 344 不变
- 结果：三笔各自「登记出处 → 修前证据 → 修后证据」齐备；C2 顺路发现同族两处（同一函数、同缺陷形态）一并修掉

---

## ① 交付概览

| 笔 | 目标 | 改动 | 修前 | 修后 |
|---|---|---|---|---|
| C1 | `conversation/CompactOrchestration.kt:957` | `entry.model` → `providerModelFor(entry)`（+7 行注释） | 门判据判「hands over the RAW entry model」1 条违规 | 0 条违规（同一判据函数对拍） |
| C2 | `scripts/scan/provider_rpc_symmetry_check.py` | `cfg_code = ""`（C8 分支）+ `groups_code = rules_code = ""`（C13 分支）+ 9 条自测臂（73→82） | 9 类输入文件缺任一：3 类崩（UnboundLocalError）、6 类 fail-closed | 9/9 fail-closed rc=1，零崩溃 |
| C3 | `provider/openai/OpenAIModelsApi.kt:234` + 仓库外 `scan_debt.py` | `\| escalate:` → `\| upgrade trigger:`；扫描器扩 KDoc 形态 + 两态自测臂 | 仓库 KDoc 形态 27 处全盲 | 覆盖 33→60 条，no-trigger 0 |

三笔都属 P2 登记债（触发路径窄、无用户可见回归），本支按「顺路清账」处理：改动最小、每笔带机械对拍。

---

## ② C1 — 压缩 pin provider 用裸 `entry.model`

**登记出处**：`fix-reports/dv24-FIX-8.md:281`（R3）。任务书另称「scan.sh 第 4 门的 info 提示」——**这条前提不成立**，见 §4.1。

**改动**

```kotlin
// before
ProviderFactory.create(instance, apiKey, entry.model, context)
// after
ProviderFactory.create(instance, apiKey, providerModelFor(entry), context)
```

全仓 `ProviderFactory.create` 共 12 个站点：2 处在 worker（`ModelExecutionService:948/1386`，model 来自 IPC 反序列化，是另一个源）、10 处在 app 侧。修前 app 侧 9 处已富化（`ChatModelRouting:195/467/540`、`ChatSessionLifecycle:433/1159/1249/1346`、`ChatPromptAndTools:2125`、`ChatViewModel:2789` 走 `liveEffectiveModel()`），只剩 `CompactOrchestration:957` 是裸 `entry.model` —— 修后 10/10 富化。

**为什么这一处真的会造成行为差异（三条代码链，逐条可指认）**

1. `ModelsDevApi.enrichModel` 才把 registry 行折进来（`applyDevData` 填 `inputModalities` 等）；内建 entry 的模态/effort 字段「null by construction」（`ModelsDevApi.kt:195` 附近 KDoc 自述）。
2. IPC 写端只写非空值：`ModelExecutionDispatcher.kt:128` `model.inputModalities.orEmpty().let { if (it.isNotEmpty()) put("input_modalities", …) }` → 裸模型发过去时该键**根本不出现**；worker 侧 `ModelExecutionService.kt:841` 反序列化得到 `null`。
3. provider 侧按序列化对象重新判能力：`OpenAIProvider.kt:1891` / `AnthropicProvider.kt:795` / `GeminiProvider.kt:424` 三处同形 `val supportsImages = "image" in (model.inputModalities ?: emptyList())` → 裸模型判假 ⇒ 附件降级成 `[attached image: …]` 占位文本；`clampThinkingLevel` 同理把 MAX 钳回 XHIGH。

**修前/修后证据（机械，用门自己的判据函数，不新增判据、不改门）**

`probe/c1_gate_judge.py`：`import session_provider_enabled_check as gate` → `gate.check_factory_models({rel: 文件文本}, min_calls=0)`，同一函数分别喂「`git show HEAD:` 的修前文本」与修后工作树文本：

```
BEFORE sites=1 violations=1
   - conversation/CompactOrchestration.kt: `ProviderFactory.create(..., entry.model, ...)` hands over
     the RAW entry model — it must be resolved through providerModelFor( / ModelsDevApi.effectiveModel(
AFTER  sites=1 violations=0
VERDICT: PASS
```

即：**该门早就把这条写进判据（`RAW_MODEL_ARGS = ("entry.model", …)`），只是它的扫描面只有 3 个文件（`LIFECYCLE_REL` / `ROUTING_REL` / `PROMPT_TOOLS_REL`），`CompactOrchestration.kt` 在面外**——这正是它能活下来的原因，登记为残留 R1。

**邻门复核**（rc 全 0）：`compact_decision_gate_check`、`compact_pin_truth_check`、`compact_entry_pin_migration_check`（12 臂）、`provider_remember_key_check`、`session_provider_enabled_check`（8 站点仍全绿）、`provider_boundary_guard`。

---

## ③ C2 — `provider_rpc_symmetry_check.py` 未初始化变量（+1 处同族）

**登记出处**：`fix-reports/FIX-4.md:268`（R8）。

**修前机制（实测，非推断）**：C8 的 `len(cfg_paths) != 1` 分支只 `detail["fail_closed"].append(...)`，`cfg_code` 只在 `else` 里绑定；C11 无条件读它 → `UnboundLocalError`。任务书说「NameError」——实际异常名是 `UnboundLocalError`（`NameError` 的子类），且崩点比任务书说的更靠后（C11 的 `cfg_code.find(GROUPS_PATH_MARKER)`，不是 C9）。另：任务书要求的 `cfg_lit` **在该文件里不存在**（`grep -n cfg_lit` 零命中），只补 `cfg_code`。

**同族探针（本支新增，值得复用的手法）**：不靠静态推理，直接把该门依赖的 9 类输入文件逐个从夹具里删掉，看「崩」还是「fail-closed」——`probe/c2_family_missing_inputs.py` 调的是门自己的 `write_fixture` / `check_tree`：

```
ProviderDebugMethods.kt        fail-closed rc=1
ProviderMutationMethods.kt     fail-closed rc=1
ConfigBuiltins.kt              fail-closed rc=1   ← 修前：CRASH UnboundLocalError 'cfg_code'
ProviderConnectionScreen.kt    fail-closed rc=1
ProvidersCollection.kt         fail-closed rc=1
GroupsCollection.kt            CRASH              ← 修前：CRASH UnboundLocalError 'groups_code'
GroupWriteRules.kt             CRASH              ← 修前：CRASH UnboundLocalError 'groups_code'
OpenAIAuthHeader.kt            fail-closed rc=1
ChatViewModelStore.kt          fail-closed rc=1
VERDICT: PASS (0/9 类输入缺失时崩或放行)   ← 修后
```

C13 的 `groups_paths/rules_paths` 分支是**同一缺陷形态**（变量只在成功路径绑定，下游 `check_group_member_faces` 无条件读）。同一函数、同一缺陷、成本 1 行 ⇒ 一并修（半径纪律：同族默认登记，但这里是同一行级、同一提交内的同一判据）。

**自测臂 73 → 82**：九个输入文件各一条 `F73-missing-<file>-fails-closed-not-crash`，每条断言三件事——① 无异常逃逸；② `rc == 1`；③ `fail_closed` 里同时有「expected exactly one …」与一条**只有走到曾经崩的那一行才会产生**的下游锚点（`anchor path = "groups" not found` / `requireKnownEntryIds not found`）。逐臂而不是一条聚合臂：回归时能直接点名坏在哪个文件。

**变异负控（逐条，只翻自己的臂）**

| 变异 | 结果 |
|---|---|
| 删 `cfg_code = ""` | `FAIL F73-missing-configbuiltins-…`（恰 1 条），其余 81 臂全绿 |
| 删 `groups_code = rules_code = ""` | `FAIL F73-missing-groupscollection-…` + `FAIL F73-missing-groupwriterules-…`（恰 2 条），其余 80 臂全绿 |

还原走**字节快照**（`probe/rpcsym.final2.py`，sha256 `479d9083…`），变异前后文件 sha256 逐字符一致；**未用 `git checkout --`**。

---

## ④ C3 — `scan_debt.py` 对 KDoc 形态 `* ponytail:` 全盲

### 4.1 位置偏差（必须先说清）

任务书写 **`scripts/scan/scan_debt.py`** —— 该路径在仓库里**不存在**：`find . -name 'scan_debt*'` 零命中、`git log --all -- scripts/scan/scan_debt.py` 空、`grep -rn ponytail scripts/` 零命中。它实际住在 **`/var/minis/skills/change-ladder/scripts/scan_debt.py`**（agent 侧 skill 脚本，GLOBAL.md 的改动阶梯纪律里也是按这个路径调用的）。

后果：C3 的扫描器改动**无法进 git、也不进 CI**，本支只能交付「同一份修改 + 前后读数」；改动本身已直接落在 skill 目录（跨会话可见），见 §7-R3。

### 4.2 覆盖扩展

`is_comented()` 原来只认 `COMENT_DELIMS = ('//', '/*', '<!--', '--')` 出现在标记前；KDoc 续行（`     * ponytail: …`）的 head 里一个都没有（`/**` 在上一行），所以整类标记全盲。新增分支 `KDOC_HEAD = re.compile(r'^\s*\*\s*$')`：**只认「head 恰好是空白 + 星 + 空白」**，因此

- `* ponytail: …`（行首形态）✅ 计入；
- `*  total, … (ponytail: merged counter | ceiling: …)`（句中提及）❌ 不计；
- `` * its `ponytail:` note said … ``（历史叙述）❌ 不计；
- `// ponytail:` 既有行为**逐字节不变**（见下等价性对拍）。

### 4.3 读数（扩前 / 扩后）

| 扫描 | 扩前 | 扩后 | 期望 | 结论 |
|---|---|---|---|---|
| `code src`（仓库） | 33 条 / 0 no-trigger / rc=0 | **60 条 / 0 no-trigger / rc=0** | +27 | ✅ 恰好 +27 |
| `code`（默认 `/var/minis/skills`） | 2 条 / 0 no-trigger / rc=0 | 2 条 / 0 no-trigger / rc=0 | 不增债务 | ✅（见下「自伤」） |
| `notes`（默认 backlog） | 438 段 / no-触发条件 5 / 豁免 11 / **rc=1** | 438 段 / 5 / 11 / **rc=1** | 不变 | ✅ |

**「notes 仍 0」这条任务书预期不成立**：`notes` 在**扩覆盖之前**就是 `rc=1`（backlog 有 5 段无触发条件，属既有状态，与本支无关）。交付判据改为「扩前扩后逐项相同」，而不是「等于 0」。

**唯一真不合规的那条**（正是靠盲区逃逸的）：`provider/openai/OpenAIModelsApi.kt:229-236` 的第三段写成 `| escalate:`，而扫描器的 `TRIGGERS` 只认 `升级触发 / 触发条件 / 触发重估 / trigger: / triger: / triggers on`。修前证据（把 `git show HEAD:` 的原文喂给**已扩覆盖**的扫描器）：

```
| …/OpenAIModelsApi.kt:229 | **no-trigger** | * ponytail: cache rows are only relabelled, never re-enriched — capability |
计: 1 条标记 / 1 条 no-trigger     rc=1
```

改为 `| upgrade trigger:` 后全仓 60 条 0 no-trigger。**改的是标签拼写，语义原样**（"a report that a Kimi model's context window / effort ladder disagrees with models.dev… then the cache path needs ModelsDevApi.enrichModels too"）。

**26 处 KDoc 标记的第三段逐个判**：扩覆盖后新增的 26 条**全部 OK**（详情见 §7-R2 的两条例外），没有任何一条需要补写——说明存量写得够，只是看不见。

### 4.4 `//` 形态等价性（扩覆盖不能改既有判定）

新扫描器在 `//` 形态上的每一条判定必须与旧扫描器逐列一致。`probe/debt_equiv.py` 比对两份输出的 `文件:行 → (状态, 摘录)`：

```
old rows=33 new rows=60
only-in-new=27  only-in-old=0  verdict-changed=0
equiv rc=0
```

即：旧报告 33 行**全部**出现在新报告里且状态/摘录一字不差，新增的恰是 27 条 KDoc 形态。

### 4.5 自测（脚本自带 `--self-test`）

新增两态 KDoc 夹具（带触发 / 无触发各一条）+ 一条反向夹具（句中提及**不得**计为标记，防「收得太宽把历史叙述当新债务」）：

```
[self-test] code KDoc form: 2 标记 / 1 no-trigger（期望 2 / 1）
PASS: 两态识别正确 + 反向对照通过          rc=0
```

**变异负控**：把新分支退回 `return False`（= 扩展前判定）→ 自测当场 `FAIL: KDoc 形态扫描计数不符`（rc=1），且仓库读数回落到 `33 条标记`（分毫不差地复现扩展前的覆盖）。还原走字节快照，sha256 `16adb4e9…` 前后一致。

**自伤一例（已闭环，值得记）**：第一次扩覆盖后，`code`（skills 域）读数从 2 条 / 0 变成 4 条 / 2 —— 新增的两条**是我自己写的文档**：usage 行 `  * ponytail: <同上>` 命中了新判据，注释里的 `` `// ponytail:` `` 命中了既有 `//` 判据。改法=改文案（不写行首星号形态、去掉反引号里的字面量），**不加豁免、不改判据**；改后回到 2 条 / 0 no-trigger / rc=0。教训：给自指扫描器写文档，先跑一遍它。

---

## ⑤ CI

| 项 | 值 |
|---|---|
| run | **#2756** / id `40352690402` / job `121676730551` / attempt 1 |
| 结论 | **success**（15:46:58 → 16:11:28，24m30s） |
| head_sha | `b06bae5485324170ca0fc07d8c20bc09aadeba35` —— 与本地 HEAD 逐字符一致 |
| 触发 | `workflow_dispatch`（分支 push 不触发：`build-apk.yml` 只绑 main push） |
| 26 步 | 唯一 skipped = `Publish to Releases`（main 门控），其余全 success |
| 日志 | 1,492,331 B / **NUL 0** |

逐条判读（`probe/ci-2756.log`）：

| 判据 | 读数 | 结论 |
|---|---|---|
| `║  Summary: 112 passed, 0 failed` | 恰 **1** 条（15:48:51） | 112 门在真树全绿 |
| `✅ ALL 344 CASES PASS` | 恰 **1** 条（15:55:21） | 夹具全绿 |
| 门头 `━━━ [N/112]` | **112** 个编号互异，`uniq -d` 空 | 无重号、无缺号 |
| `^ e: `（Kotlin 编译错） | **0** | C1 的 `providerModelFor(entry)` 成员解析成立 |
| 含 `FAILED` 的行 | **6** 条 | 逐条核对**全是 PASSED 用例名子串**（`slot denied produces FAILED…` / `reason PERSISTENCE_FAILED maps…` / `phase FAILED maps…` / `terminal FAILED maps…` / `reason EXECUTION_FAILED maps…` / `the fixed tail still reaches a legal FAILED terminal`） |
| `PROVIDER_RPC_SELFTEST=PASS` | 恰 **1** | `test_scan.py:2936` 的既有标记断言在 73→82 臂后仍成立（即 9 条新 F73 臂在 CI 里真跑了） |
| `F73-missing…` 臂名 | **0** | 已知事实：门自证 stdout 不进 CI 日志（只有 test_scan 的 ✅ 行）——故臂数靠标记 + 报告承载 |
| `BUILD SUCCESSFUL` | **4** | 生产编译面 |
| 产物 | `RikkaMinis-arm64-v8a.apk` **14,507,677 B** | 出包成功 |
| 分段 | scan `15:47:23.55 → 15:48:51.77` = **88.2 s**（并行池）· `ALL 344` @ +7m57s · `assembleRelease` 16:03:47 → 16:11:21 | 与 P1 并行门禁的收益一致 |

**CI 覆盖不到的部分**：C3（`scan_debt.py` 在仓库外，见 §4.1 / R3）与 C1 的运行期效果（见 §7）。

---

## ⑥ 残留（P2，登记不修，各带三件）

**R1 — `session_provider_enabled_check` 的扫描面不含 `CompactOrchestration.kt`**
触发条件：出现第 10 个 `ProviderFactory.create` 站点、或该门的 `MIN_FACTORY_CALLS` 因其它原因变动时。触发后方案：把 `conversation/CompactOrchestration.kt` 加进 `run()` 的三文件列表并把 `MIN_FACTORY_CALLS` 8→9（这会把 C1 的判据从「一次性人工对拍」变成 CI 常驻）。搁置理由：本支硬约束只允许改 `provider_rpc_symmetry_check.py` 与 `scan_debt.py` 两个脚本；且改门扫描面 = 改 112 门里另一门的判据面，属独立决策。

**R2 — 两处「句中提及」形态的 `ponytail:` 仍然不可见（有意）**
- `backup/ArtifactBackupScope.kt:68`：`*  total, not a per-reason split (ponytail: merged counter | ceiling: …)` —— **是真标记**（带 ceiling 段），只因标记不在行首而仍盲；
- `debug/ProviderMutationMethods.kt:848`：`* The former local fork is discharged: its \`ponytail:\` note said …` —— 历史叙述，**不看是对的**（任务书担心的「误判成新债务」在行首判据下不会发生，无需改文案）。

触发条件：下次审计认为 ArtifactBackupScope 那条必须被扫到时。触发后方案：把判据从「head 恰好是空白+星+空白」放宽到「`*` 开头的续行 + 标记不在反引号内」，并把 ProviderMutationMethods 的表述排除掉；两个方案（放宽判据 / 把该条改成行首形态）都试一遍，选不引入特例的那个。搁置理由：放宽判据会改变「历史叙述 vs 标记」的边界，需要反向夹具覆盖，装置比例高于本条 P2 的价值。

**R3 — C3 的扫描器改动在仓库外，无 CI 见证**
触发条件：`scan_debt.py` 被纳入仓库（或某次 skill 同步把它覆盖）时。触发后方案：纳入仓库后补一条 `test_scan.py` 注册（`--self-test` 标记断言），让扩覆盖有常驻见证。搁置理由：它现在住在 `/var/minis/skills/change-ladder/scripts/`（agent 侧、跨会话共享、不属任何 git 仓），本支无法提交——已在原地改好并附前后读数，但**没有 CI 面**。

**R4 — `ModelExecutionService` 两处 `model` 传参是否该富化**
结论：**不该**，且本支未动。理由（代码可指认）：worker 侧的 `model` 来自 IPC 反序列化（`ModelExecutionService.kt:837/1259` 自 `req` 重建 `LLMModel`），它反映的是**调用方传过来的那份对象**；富化必须发生在 app 侧写 payload 之前。真正的判据是「app 侧 handing over 的 LLMModel 是否已富化」——这正是 C1 修的、也是 R1 想让 CI 常驻守的东西。若未来有人在 worker 侧调 `ModelsDevApi`，那会撞上 `ChatViewModel.kt:2055` 的既有论证（worker 进程从不初始化 registry），属架构决策而非本债。

**R5 — `scan_debt.py` 对其余 26 条 KDoc 标记的第三段质量只做「存在性」判定**
即：有「升级触发/天花板」字样即绿，不判第三段是否可观测。触发条件：出现「写了触发条件但无法裁决」的实例时。触发后方案：复用 `boundaries` 子命令的 `OBS_MARKS` 判据（日志特征 / 应出现 / 判定方法）给 code 形态也加一层。搁置理由：本次 26 条逐条人工读过（§4.3），无实例。

---

## ⑦ 未验证边界（诚实边界）

- **真机**：C1 的实际行为变化（压缩链上带图会话是否恢复上传原图、thinking 是否不再被钳位）**未在真机验证**。判定方法：用一个带图会话触发压缩（pin 一个 vision 模型），观察 `:modelservice` 侧 body 里是否出现 `image_url`/`input_image`；或看压缩摘要是否还出现 `[attached image: …]` 占位。沙箱无 Android 运行时。
- **CI 只保证**：Kotlin 编译过、112 门在真树全绿、344 夹具全绿、`provider_rpc_symmetry_check --selftest` 82 臂全绿（标记断言）、APK 能出包。**不保证** C1 的运行期效果，也不覆盖 C3（脚本在仓库外）。
- **C2 的九条臂**在 CI 里的执行时间会增加（每条一次 `check_tree`）；本地实测：`--selftest` 82 臂合计 **7 s**（加 9 臂前后差 ~1 s），真树 **12 s**。CI runner 上的绝对耗时未测。
- **C1 的附加成本**：`providerModelFor` 会走 `ModelsDevApi.loadRegistry()`（内存缓存 → 磁盘缓存 → bundled fallback）。压缩链在 suspend 上下文里构造候选链（`CompactOrchestration.kt:788`，外层是 `sendVia` 所在的 suspend 函数），实际路径上 registry 早已由聊天模型解析加载过（内存命中）；未预热时最坏多一次磁盘读，与其它 9 个 create 站点同代价。**未在真机测**这一处的耗时。

---

## ⑧ 复现命令

```sh
cd /var/minis/shared/work/debt-1026/wt/misc

# C1：用门自己的判据函数对拍修前/修后（探针内外都只调 check_factory_models）
git show HEAD:src/android/app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt \
  > /var/minis/shared/work/debt-1026/probe/c1-before.kt
python3 /var/minis/shared/work/debt-1026/probe/c1_gate_judge.py

# C2：九类输入缺失探针 + 自测 + 真树
python3 /var/minis/shared/work/debt-1026/probe/c2_family_missing_inputs.py
python3 scripts/scan/provider_rpc_symmetry_check.py --selftest
python3 scripts/scan/provider_rpc_symmetry_check.py .

# C3：扩覆盖读数 + 等价性 + 自测
python3 /var/minis/skills/change-ladder/scripts/scan_debt.py code src | tail -1
python3 /tmp/scan_debt_orig.py code src > /tmp/a.txt      # 旧版（probe/scan_debt.orig.py 的副本）
python3 /var/minis/skills/change-ladder/scripts/scan_debt.py code src > /tmp/b.txt
python3 /var/minis/shared/work/debt-1026/probe/debt_equiv.py /tmp/a.txt /tmp/b.txt
python3 /var/minis/skills/change-ladder/scripts/scan_debt.py --self-test
```

装置目录：`/var/minis/shared/work/debt-1026/probe/`（`c1_gate_judge.py`、`c2_family_missing_inputs.py`、`kdoc_probe.py`、`debt_equiv.py`、`rpcsym_mut2.py`、`debt_mutate.py`、字节快照 `*.final*.py` / `scan_debt.orig.py`、日志 `debt-*-before/after.txt`）。
