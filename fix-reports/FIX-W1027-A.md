# FIX-W1027-A · 图像链接线护栏（W-04 / W-05 / W-06）

- 分支：`fix/w1027-image-wiring`（基线 main `4a48a6be12c0bbd46e3b128b7f055b3c8f88d0c1`）
- 工作树：`/var/minis/shared/work/witness-1027/wt-image-wiring`
- 输入：`report-witness-1027.md`（W-04/W-05/W-06 三条 witness patch）+ `VERIFY-1027.md`
- 停止节点：分支 CI success 且 `head_sha` 与本支 HEAD 逐字符一致（不合并 main）

## 1. 背景

外部模型对本周新代码做了第四面对抗审：给出 12 条「打完补丁 112 门 + 347 夹具全绿、行为坏」的
补丁，用来证明本周的修复没有护栏。主会话机械复证 12/12 打得上、还原 sha 逐字节一致。

本支承接其中三条，它们同属**图像链**，共同形态是「一处正确写法 + 一个恒真默认参数」：

| 见证 | 严重度 | 现场 |
|---|---|---|
| W-04 | P1 | `buildLlmMessagesFromParsed(..., resizeForInference = { _, _ -> null })` 默认值 = 不降采样 |
| W-05 | P0 | `if (newParts[ref.partIdx] !== originalPart) droppedHere.add(id)`，翻转成 `===` 后 `imageParts` 不再同步 |
| W-06 | P1 | `if (!saveOutcome(streamOpened = true, compressed = encoded))`，删 `!` 后 JPEG 阶梯整条失效 |

## 2. 见证反攻（修前 → 修后）

| 见证 | 修前为什么绿 | 修后挡住它的东西 | 反攻读数 |
|---|---|---|---|
| W-04 | 默认参数让「漏传第三实参」合法编译；`ChatImageRestoreTest` 只测注入的 lambda | **编译期**（默认值删除，两处签名都没有 `= { _, _ -> null }`） | 真实 helper + 漏传调用 → `error: no value passed for parameter 'resizeForInference'`；同一调用打在修复前副本（默认值恢复）→ 编译通过（复现洞） |
| W-05 | 单测只测 `filterElidedImageParts(parts, 手工 id 集)`，从没测 `droppedHere` 的采集 | **`ImageElisionPlanTest` 第 1/3/5/6 臂**（记录的 id 集）+ 第 2 臂（不该记录的不记录） | 变异 A（`!==` → `===`，即见证原形）：6 臂 **5 红**；变异 B（忽略预算判定、全量重写）：第 2/3 臂红 |
| W-06 | `scan_encode_verdict` 只查 C-B2（verdict 被绑定）与 C-B3（函数体出现 `saveOutcome(`），不看极性 | **新判据 C-B4**（保留原始字节的出口必须挂在 `saveOutcome(...)` 的否定上），另有 8 条自测臂 | 见证补丁打在真树上：**HEAD 版门 rc=0（瞎）**，本支门 rc=1 并点名 `ImageBudget.kt:106 [B4] compressBytes() keeps the ORIGINAL bytes (input) on the SUCCESS path`；还原 `!` 后 rc=0 |

## 3. 修法

### W-04（编译期门禁，不加静态门）

`ui/chat/ChatStreamToolHelpers.kt`：`buildLlmMessagesFromParsed` 与 `buildSingleLlmMessage`
的 `resizeForInference` **都删掉默认值**，改成必传。KDoc 记下「为什么这里不能有默认值」：
默认值是 `{ _, _ -> null }`，即「不降采样」——漏传在编译、112 门、全部单测上都是绿的。

生产调用点两处本来就显式传了 `::inferenceImageResizer`（`MessageEntity.toLLMMessage()` /
`buildLlmMessages`，`ChatViewModel.kt:4506/:4514`），无需改；`ChatTurnPartsJsonTest` 四处依赖默认值的调用改为显式传
`resizeForInference = { _, _ -> null }`（诚实标出「这几条测的确实不需要降采样」）。

判据读数（本支 HEAD）：

```
$ rg -n "buildLlmMessagesFromParsed|buildSingleLlmMessage" src/android/app/src | grep -v /test
ChatViewModel.kt:4509   buildSingleLlmMessage(…, ::inferenceImageResizer,)
ChatViewModel.kt:4516   buildLlmMessagesFromParsed(parsed, mediaStore.mediaBaseDir, ::inferenceImageResizer)
ChatStreamToolHelpers.kt:68   buildSingleLlmMessage(…, resizeForInference,)   # 内部透传
$ rg -c "resizeForInference: \(ByteArray, String\) -> ByteArray\? =" src/android/app/src
0
```

### W-05（抽纯函数 + JVM 单测）

新增 `ui/chat/ImageElisionPlan.kt`（无 Android 依赖）：

```kotlin
internal fun planImageElision(
    messages: List<LLMMessage>,
    droppedIds: Set<Int>,              // 预算判定淘汰的字节 identity hash
    placeholder: (String?) -> String,  // 生产传 ImageBudget::elidedImagePlaceholder
    pathFor: (Int) -> String?,         // 生产传 spillover / 已有 linuxPath
): ImageElision                        // messages + 每条消息真的丢了哪些 id
```

`applyRequestImageBudget` 只剩接线：收集候选图 → `ImageBudget.planRequestBudget` →
解析 spillover 路径 → `planImageElision`。判据本身（`replaced !== original` 才记录）搬进
纯函数，成为可被 JVM 驱动的一行，并附「为什么不是 `===`」的 KDoc（翻转后 `imageParts`
过滤集恒空 = 三家 provider 全尺寸重发）。

行为保持不变：占位文本仍由 `ImageBudget.elidedImagePlaceholder` 生成；`ToolResult` 保留
结构、只清字节；未被淘汰的消息返回**同一实例**；`droppedIds` 为空时返回输入列表实例。

附带效果：**W-05 的见证补丁从此打不上**（`rg -c "newParts\[ref\.partIdx\] !== originalPart" src` = 0）——
判定点从「调用点内联的一行」变成「纯函数里带 KDoc 的那一行」，其等价形态由第 1/3/5/6 臂 + 变异 A 钉住。

### W-06（门新增 C-B4）

`scripts/scan/discarded_return_guard.py` 新增 `scan_failure_polarity(root)`（C-B4），
作用域限定在「能把 ByteArray 参数原样交回」的函数（`return input` / `else input` / `?: input`）：

- 该出口的守卫若派生自 `saveOutcome(...)`，极性必须是**否定**（失败分支才交回原始字节）；
- 覆盖形态：`if (saveOutcome(...)) { … return input }`（红）、`val ok = saveOutcome(...); if (ok) { return input }`（红）、
  原始字节落在 `else` 分支（红）、`if (!saveOutcome(...)) return input`（绿）、`if (!ok) return input`（绿）；
- 非判定守卫（`if (input.isEmpty())`、decode 失败 `?: return input`）不在作用域内 → 绿；
- 派生自判定但读不出极性（`saveOutcome(...) && retry`）→ **fail-closed 报违规**，不默认绿。

`--selftest` 内新增 8 条臂（violating 3 条 + 等价绿 3 条 + fail-closed 1 条 + 作用域外 1 条），
臂计数进同一条注册标记（主会话的 `test_scan` 夹具断言该标记仍在）。

## 4. 四层证据

### L1 本门

```
$ python3 scripts/scan/discarded_return_guard.py --self-test
[ok] C-B4 negated verdict guarding `return input` passes
[ok] C-B4 the witness patch (polarity inverted) trips [B4]
[ok] C-B4 the inline `if (!saveOutcome(...)) return input` passes
[ok] C-B4 a positive guard through a local boolean trips [B4]
[ok] C-B4 the same local boolean negated passes
[ok] C-B4 original bytes on the `else` (success) branch trips [B4]
[ok] C-B4 an unreadable (compound) outcome guard fails closed
[ok] C-B4 a non-verdict guard (empty input) is out of scope
DISCARDED_RETURN_GUARD_SELFTEST=PASS (77 arms)        # 修前 69 arms
$ python3 scripts/scan/discarded_return_guard.py .
✅ discarded-return guard: failure-polarity section green (every keep-the-original exit hangs off a NEGATED saveOutcome)   rc=0
```

### L2 邻门（六道，全 rc=0）

`filepreview_print_bounded_check` / `outcome_known_chain_check` / `i18n_check` /
`memory_gate_provider_fields_check` / `session_provider_enabled_check` / `chat_ui_interaction_check`。

### L3 编译 + JVM 实跑（沙箱 kotlinc 2.1.0 / JDK 17）

**a) W-05 纯函数：真源码 + 真数据类 + 生产测试文件**

```
$ sh probe/kc.sh … AgentContentPart.kt LLMMessage.kt ImageElisionPlan.kt ImageElisionPlanTest.kt
$ java -cp … org.junit.runner.JUnitCore com.rikkaminis.app.ui.chat.ImageElisionPlanTest
OK (6 tests)
```

变异负控（改的是**副本**，生产文件 sha256 `5979ce19…fabbf4` 全程未动）：

| 变异 | 内容 | 读数 |
|---|---|---|
| A | `if (replaced !== original)` → `===`（见证原形） | `Tests run: 6, Failures: 5`（臂 1/2/3/5/6 红，臂 4 空集早退仍绿） |
| B | 去掉 `if (id !in droppedIds) continue`（全量重写） | `Tests run: 6, Failures: 2`（臂 2/3 红） |

**b) W-05 接线：从生产文件机械切片的函数 + 真 ImageBudget / 真 ImageElisionPlan**

`probe/w05/build_probe.py` 按字节区间从 `ChatPromptAndTools.kt` 切出
`applyRequestImageBudget`（不经手抄，不会漂移），只对 `ChatViewModel`/`Context`/`AppLogger`/
`AgentRuntimeLimitsPrefs`/`ImageDecoding`/`android.graphics.Bitmap` 打桩。编译干净通过 ⇒
`planImageElision(messages=…, droppedIds=HashSet<Int>, placeholder = ImageBudget::elidedImagePlaceholder, pathFor = { … })`
这套调用形状与 `it.identityHash` / `msg.copy(contentParts=…, imageParts=…)` 对真签名类型正确。

**c) W-04 编译期门禁（形状编译探针）**

| 编译单元 | 结果 |
|---|---|
| 真 helper + 传 resizer 的调用点 | clean |
| 真 helper + 漏传（见证 patch 形态） | `error: no value passed for parameter 'resizeForInference'` |
| **修复前**副本（默认值恢复）+ 同一个漏传调用 | clean（洞就是这样进来的） |

**d) W-06 见证补丁在真树上的前后对照**

| 门版本 | 见证补丁后的真树 |
|---|---|
| HEAD（`git show HEAD:…`） | `rc=0`（全绿，看不见） |
| 本支 | `rc=1`，`ImageBudget.kt:106 [B4] …` |
| 本支（还原 `!`） | `rc=0`，`failure-polarity section green` |

**e) 哪些文件被「沙箱实编」、哪些由 CI 真 Gradle 兜底（诚实边界）**

| 改动文件 | 沙箱 kotlinc | 说明 |
|---|---|---|
| `ui/chat/ImageElisionPlan.kt` | 整文件编过 + 单测实跑 | 真源码，sha256 `5979ce19…fabbf4` |
| `ui/chat/ChatStreamToolHelpers.kt` | 整文件编过（依赖打桩） | 三臂形状探针（见 c） |
| `test/…/ImageElisionPlanTest.kt` | 整文件编过 + 6/6 实跑 | — |
| `ui/chat/ChatPromptAndTools.kt` | 函数级切片编译（真 `ImageBudget` + 真 `ImageElisionPlan`） | 整个文件依赖 Android/Compose，沙箱不可编 |
| `test/…/ChatTurnPartsJsonTest.kt` | 未编 | 依赖 `ChatViewModelMessageParser` 等 Android 面；由 CI `Run unit tests (full suite)` 编译通过（`BUILD SUCCESSFUL`） |

### L4 全量

```
$ sh scripts/scan/scan.sh --jobs 3
║  Summary: 112 passed, 0 failed
```

`python3 scripts/scan/test_scan.py`：见 §4.1。

#### 4.1 全量夹具套件（347 例）

单进程整跑第二次被环境打断（在 `auto-approve gates` 一段静默 7 分钟无输出、无 CPU，
同时机器上另有两个兄弟会话在跑 `test_scan`/`run_fixtures` —— 已登记的并发场景）。按
sandbox-limits 的健康路径改**逐函数独立进程**跑（`probe/test_scan_loop.sh`，每个
`test_*` 一个进程、各自 420 s 超时）：

```
$ sh probe/test_scan_loop.sh ; tail -2 /tmp/testscan_loop.log
RC test_xproc_boundary 0
DONE
$ 47 functions ; PASS 347 FAIL 0   （零 ❌，零非零退出）
```

被打断的那个 case 也在隔离下复跑过：`auto_approve_bypass_check --selftest` →
`AUTO_APPROVE_BYPASS_SELFTEST: PASS (20 arms, 18 violating)` rc=0 ⇒ 是环境卡顿，不是门红。
云端 `test_scan`（CI 步 3 内的 `Run pre-build scan gate`）**整跑通过**，读数见 §5。

## 5. CI

- run：`40493992889` / job `122154687109` / **attempt 1** / `success`（11:09:21Z → 11:33:36Z，24m15s）
- head_sha：`015ccace7ca97f79ae5480e560c004af1bccd6ba`（与本地 HEAD 逐字符一致）
- 步骤：21 步 success（含 `Restore release signing keystore` / `Run unit tests (full suite)` /
  `Compile instrumented tests` / `Build APK with Gradle` / `Verify APK signature`），
  仅 2 步 skipped = `Move the rolling tag android-latest` + `Publish to Releases`（两者均 main 门控）
- 日志（1,473,071 B / NUL 0）逐条核：

| 判据 | 读数 |
|---|---|
| `║ Summary: 112 passed, 0 failed` | **1**（步 3 预检门） |
| `✅ ALL 347 CASES PASS` | **1** |
| `^ e: ` 编译错误行 | **0** |
| `FAILED` 行 | 6 条，逐条确认全是用例名里的 `PASSED`（`slot denied produces FAILED…` / `reason PERSISTENCE_FAILED…` / `phase FAILED…` / `terminal FAILED…` / `reason EXECUTION_FAILED…`） |
| `failure-polarity section green` | **1**（新判据在云端真树上跑过） |
| `discarded_return_guard selftest has teeth` | **1**（夹具注册面仍在；门自证 stdout 按既有约定不进 CI 日志） |
| `ImageElisionPlanTest > … PASSED` | **6/6**（arm 1–6 在真 Gradle/JDK 上全绿） |
| `BUILD SUCCESSFUL` | 3（单测 5m47s / instrumented 编译 1m28s / APK 7m28s） |

## 6. 残留登记（半径纪律：只登记，不动手）

- **R1 本机 `test_scan.py` 未跑完**：首次前台整跑在 180 s 无输出处被工具壳 stall-guard 打断
  （`KeyboardInterrupt` 在 `subprocess.communicate`，即某个 case 的子进程静默，非门红），
  已通过 case 计数确认当时 **0 个失败**；第二次改后台 `-u` 跑（见 CI 段同期读数）。
  本支未新增/修改任何夹具，唯一的注册面是 `discarded_return_guard --self-test` 的标记
  —— 该标记仍在（`DISCARDED_RETURN_GUARD_SELFTEST=PASS`），并已单独跑过
  `test_dv27_gates()` 两个断言全绿。
- **R2 `filterElidedImageParts` 现在只有测试在用**：`ImageElisionPlan.kt` 内部用
  `filterImagePartsByHash`（int 键，避免本文件依赖 `ImageBudget`，也因为
  `Set<ImagePartId>` / `Set<Int>` 在 JVM 侧擦除后同名重载会撞）。两份实现在语义上是同一件事。
  合并需要让 `ImageBudget.ImagePartId` 脱离 Android 依赖（或把 ash 键类型上提到 data.model），
  属结构改动，登记不动手。
- **R3 `applyRequestImageBudget` 现在多走一遍 `contentParts`**：纯函数自己遍历全部 parts
  按 id 匹配，而调用点已经先建了 `images` 列表（含 `msgIdx/partIdx`）。O(n) 且 n 很小，
  但两趟遍历的「候选集」判据是重复的（一处 `when` 判 ImageData/ToolResult，一处 `imageIdOf`）。
  触发条件 = 下次动这块或出现「候选集与重写集不一致」类缺陷时，统一成一次性产出。
- **R4 W-06 判据的已知边界**：`?: input` 形态只在「同一行有 inline `if (…)`」时可判定；
  真树当前没有这种落点（唯一命中是 `compressBytes` 的否定形态）。若将来出现
  「`val x = if (cond) null else bytes` + `return x ?: input`」这类跨行写法，C-B4 会
  在守卫不可读时 fail-closed 报违规（会红，不会漏）。另外单条件守卫用「出现 `!`」作
  否定代理：`saveOutcome(...) == false`（语义是否定但无 `!`）会被**误红**（fail-closed
  方向，安全侧），`!= true` 会被正确判否定；真树无此写法。签名里带 lambda 默认值
  （`= { … }`）时 `body.split("{")[0]` 会截短参数表、可能漏认 ByteArray 参数 —— 真树
  无此写法，出现即漏判（不误红）。
- **R5 W-04 的编译期门禁只覆盖「漏传」**：显式传 `{ _, _ -> null }` 仍是合法的（
  `ChatTurnPartsJsonTest` 就是这么用的，因为那些用例不关心降采样）。要禁止显式退化成
  「不降采样」需要另一层（门或类型），超出本见证范围。

## 7. 复现命令

```sh
cd /var/minis/shared/work/witness-1027/wt-image-wiring

# L1 本门
python3 scripts/scan/discarded_return_guard.py --self-test | tail -12
python3 scripts/scan/discarded_return_guard.py .

# L2 邻门
for g in filepreview_print_bounded_check outcome_known_chain_check i18n_check \
         memory_gate_provider_fields_check session_provider_enabled_check chat_ui_interaction_check; do
  python3 scripts/scan/$g.py . >/dev/null; echo "$g rc=$?"; done

# L3a W-05 JVM（真源码）
R=src/android/app/src
sh ../probe/kc.sh /tmp/jvmout \
  ../probe/shadow/LLMUsage.kt \
  $R/main/java/com/rikkaminis/app/data/model/AgentContentPart.kt \
  $R/main/java/com/rikkaminis/app/data/model/LLMMessage.kt \
  $R/main/java/com/rikkaminis/app/ui/chat/ImageElisionPlan.kt \
  $R/test/java/com/rikkaminis/app/ui/chat/ImageElisionPlanTest.kt
java -cp "/tmp/jvmout:/var/minis/shared/work/jvm-libs/{kotlin-stdlib-2.1.0,json-20231013,junit-4.13.2,hamcrest-core-1.3}.jar" \
  org.junit.runner.JUnitCore com.rikkaminis.app.ui.chat.ImageElisionPlanTest

# L3b W-05 接线切片编译
python3 ../probe/w05/build_probe.py
sh ../probe/kc.sh /tmp/w05out ../probe/w05/stub/*.kt $R/main/java/com/rikkaminis/app/provider/{ImageBudgetLadder,ImageBudget}.kt \
  $R/main/java/com/rikkaminis/app/util/SaveOutcome.kt $R/main/java/com/rikkaminis/app/ui/chat/ImageElisionPlan.kt \
  ../probe/w05/SlicedBudgetWiring.kt

# L3c W-04 编译期探针（三臂）
committed=src/android/app/src/main/java/com/rikkaminis/app
#   见 ../probe/w04/{caller_after,caller_witness}.kt 与 HelperBefore.kt

# L3d W-06 见证补丁对照
git show HEAD:scripts/scan/discarded_return_guard.py > /tmp/gate_before.py
# 把 ImageBudget.kt:101 的 ! 去掉后：
PYTHONPATH=scripts/scan python3 /tmp/gate_before.py . ; echo "before rc=$?"
python3 scripts/scan/discarded_return_guard.py . ; echo "after rc=$?"

# L4 全量
sh scripts/scan/scan.sh --jobs 3
python3 scripts/scan/test_scan.py
```

## 8. 为什么这么修

1. **W-04 修在「签名」而不是「加一道门」**：这是唯一能覆盖**未来调用点**的层。任何静态门
   都要人去维护扫描面（本条见证正是「没有门检查第 3 个实参」——再加一道门，下次换个
   函数名/换个包装又会漏）。删默认值是 O(1) 的改动，且失效形态是**编译错误**而不是静默行为退化。
   代价是 `buildSingleLlmMessage` 的每个调用点都要显式表态，这恰好是我们想要的。
2. **W-05 修在「把判定搬到可测的层」**：见证的本质不是拼错了 `!==`，而是这条判定**从来没有
   被任何一层覆盖**（单测测的是它上游的产物 `filterElidedImageParts`）。抽成无 Android 依赖
   的纯函数后，生产代码与 JVM 装置跑的是**同一个文件**（sha 一致），不是复刻件——这比再写
   一条「源码形状」静态门更硬：门只能判拼写，臂能判行为。
3. **W-06 修在「门看极性」**：C-B2/C-B3 之所以瞎，是把「结构存在」当成「语义达成」
   （verdicted 绑定了 / 出现了 `saveOutcome(`）。极性是这条修复里唯一的语义位，所以判据
   必须锚在「哪个分支才是失败分支」，并且读不出来时**报红**而不是默认绿——否则下一次
   改写（复合条件）会重新落回盲区。
4. **三条都刻意不做「顺手扩大」**：`filterElidedImageParts` 的重复实现、双趟遍历、
   `?: input` 的跨行形态，全部只登记（§6），因为它们的触发路径今天不存在；把它们一起改
   会让本支的验证面从「三条见证」膨胀成「一次图像链重构」，与见证的边界不符。
