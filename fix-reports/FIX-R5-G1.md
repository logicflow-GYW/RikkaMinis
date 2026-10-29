# FIX-R5-G1 —— 配置/Debug RPC 面三条（E-F-03 · E-F-04 · F-F-08）

- 基线：main `35509888`
- 分支：`fix/r5e-config-rpc`（worktree `witness-1027/wt-r5e-config-rpc`）
- 代码提交：`45cd127f`（报告另提交）
- 改动面：生产 4 文件 **+340/−68**（ConfigBridge 142/53 · ModelsCollection 50/0 · ProviderMutationMethods 75/14 · DebugRPCHandler 73/1）+ 新测试 5 文件 732 行
- 禁碰面零触碰：`scripts/scan/**`、`scan.sh`、`test_scan.py`、`assets/skills/**`、`strings.xml`

## §1 修复台账（每条一行）

| 条目 | 判级 | 先证结论 | 修法 | 主证据 |
|---|---|---|---|---|
| E-F-03 `performWriteBatch` 部分失败谎报成功 + 吞取消 | P1 | **成立** | 单一 catch + `classifyWriteFailure`（取消→null→重抛）+ `failed[]` 逐行 + `buildWriteEnvelope` 四臂 | 探针 `oneAppliedOneFailedIsPartialWrite` + 真跑「1 成功 1 失败」用例 |
| E-F-04 `models` 缺 `supportsReasoning` + `modelsAdd` 丢 overrides | P2 | **成立**（两条同根：写入口径不统一） | 注册可写 override 字段 + `modelsAdd` 构造 `ModelOverrides`（与 `ModelsCollection.add` 同口径） | `models_provider_field_check` 6 arms clean + 探针 3 例 |
| F-F-08① groups `name` 无 trim | P2 | **成立**（`"   "` 可落盘） | 复用同文件 `requireNonBlankParam`（trim-then-refuse，写盘用修剪值） | 源码形状 + 门 `provider_remember_key_check` |
| F-F-08② `read_file` head 页 UTF-8 边界 | P2 | **成立**（头部混 U+FFFD / 小 limit 全 U+FFFD） | `decodeUtf8Page`：先丢 ≤3 个续字节再解码，尾部不完整字符截断 | 探针 4 例（任意 offset 无 U+FFFD / 对齐重建） |

**commit message 说明**：`45cd127f` 消息里括注的「ok=true + 非空 failed[]」是草稿措辞；真实合同见 §2.1 = **`ok=false` + `error="partial_write"`**（理由同节）。以本报告与真源为准。

## §2 契约（原契约 / 被破坏方式 / 新契约）

### 2.1 `performWriteBatch` 返回体（E-F-03）

| | 契约 |
|---|---|
| 原 | 只要 `applied` 非空就返回 `{ok:true, applied, user_message:"Settings updated."}`；行级失败进 `earlyError` 或静默；`catch(Throwable)` 不重抛 `CancellationException` |
| 被破坏方式 | 批量 N 项中第 2 项被拒：其余照写，整体报 ok，`ok` 是唯一映射进程退出码的字段（`ConfigOffloadHandler.kt:349` `optBoolean("ok", false) → EXIT_OK`）⇒ CLI **exit 0** + 「Settings updated.」；取消被记成 `write_failed` 且已落盘部分仍报 ok |
| 新 | 四臂：①nothing applied + earlyError → 该行信封（原样）；②nothing applied 无 error → `all_rejected`（原样）；③**some applied + some failed → `ok=false` + `error="partial_write"` + `applied[]` + `failed[{path,error,reason}]` + `user_message`**；④全部成功 → `ok=true` + `applied[]`（原样）。`CancellationException` 永不进 `failed[]`（重抛） |

**为什么选 `ok=false` 而不是 `ok=true + 非空 failed[]`（约束 C 二选一）**：`ok` 是唯一决定进程退出码的字段，`ok=true` 会让部分失败继续以 exit 0 骗过调用方——那正是本条要修的症状。选 `ok=false` 后，既有成功路径（④）与两种空路径（①②）逐字不变，`already_exists`/`permission_denied` 等既有错误码也逐字保留（`classifyWriteFailure` 的臂序即此保证）。

**消费面普查**：生产侧解析 `ok` 的唯一位置 = `ConfigOffloadHandler.exitCodeFromEnvelope`（:349）；`assets/skills/**` 无 `minis-config` 调用点、无 `ok` 依赖（唯一 `"ok"` 命中是 `skill-creator/check_evals.py` 的状态字符串，与配置信封无关）。⇒ 语义变更的唯一影响面 = 部分失败从 exit 0 变为非 0（有意方向）。

### 2.2 `models.<id>.supportsReasoning`（E-F-04）

| | 契约 |
|---|---|
| 原 | 路径不存在（`get` 报 `unknown_path`）；`modelsAdd` 收到参数但不落 `ModelOverrides`；`.remove` 的 `old` 快照走 `fields()` 遍历 ⇒ 该属性凭空丢失 |
| 新 | `ClosureField`，`path = "models.$id.supportsReasoning"`，`ConfigSchema.Bool`；reader = `effectiveModel(e).supportsReasoning == true`（override > registry）；writer = `withSupportsReasoningOverride(b)` ⇒ 只写 `overrides`，不动 `baseModel`；写 `false` 是 **pin** 不是 clear（bool 无第三值，KDoc 写明）。快照侧不另写一套——它走 `fields()` 自动覆盖 |

### 2.3 groups `name`（F-F-08①）

| | 契约 |
|---|---|
| 原 | `groupsCreate`/`groupsUpdate` 只 `isEmpty()`，`"   "` 落盘成空白组名 |
| 新 | 与 `instancesCreate/Update` 的 `label` 同一条 `requireNonBlankParam`：**trim-then-refuse**（trim 后为空抛 `-32602`，调用点自带文案），落盘值是修剪后的串 |

### 2.4 `read_file` head 页（F-F-08②）

| | 契约 |
|---|---|
| 原 | `offset` 落在多字节字符中间时 `String(bytes, UTF_8)` 从头解码 ⇒ `content` 头部混 `U+FFFD`；`limit ≤ 2` 只读到续字节 ⇒ 全 `U+FFFD` |
| 新 | `decodeUtf8Page`：先丢弃开头连续的续字节（`0x80..0xBF`，最多 3 个）再解码，尾部不完整字符截断丢弃 ⇒ **页内永不出现 U+FFFD**。选择写清：不回退 offset，而是**前移到下一字符边界**（页从对齐点开始）；丢弃后为空 ⇒ 空串 |

## §3 验证证据（多源，互不替代）

1. **JVM 探针（真源逐字切片，11/11 OK）**：`oneAppliedOneFailedIsPartialWrite`、`allAppliedIsPlainSuccess`、`nothingAppliedWithFailureKeepsTheFirstEnvelope`、`cancellationIsNotClassifiedAsWriteFailure`、`classifiedCodesMatchTheOldArms`、`decodeNeverAnswersUfffdForAnyOffset`、`alignedWalkReconstructsTheFile`、`skipCountsContinuationBytesAndCapsAtThree`、`tailOnlyFragmentIsEmptyNotReplacement`、`validityAndDecodeAgree`、`nulStillClassifiesBinary`。
2. **真跑的「1 成功 1 失败」用例**（约束 C）：`ConfigWriteEnvelopeTest.oneAppliedOneFailedReportsPartialWrite` 直接调用生产 `buildWriteEnvelope(applied=1, failed=1, earlyError=null)`，断言 `ok=false` / `error=partial_write` / `applied[1]` / `failed[{path,error,reason}]` / `user_message` 含 "Retry"；期望值为写死字面量。
3. **形状测试（缩进域 owner，无固定字符窗口）**：`ConfigWriteBatchWiringTest` 5 例；owner 判据 = 从目标行向上走到第一个缩进更浅的行。用 Python 在真源上逐字复刻同一逻辑跑过（`sim_wiring.py`，全 PASS，含 T1 catch 归属 / T2 Approved 分支归属 / T3 旧内联判决消失 / T5 单定义）。
4. **括号配平**：改动的 9 个 .kt 文件（跳过字符串/注释/字符字面量后）`{}` `()` `[]` 全平衡。
5. **门禁**：7 门改后全 rc=0（详见 §4）。
6. **编译面核对**：`LLMModel` 构造参数一致；`withSupportsReasoningOverride` 为 `internal` 且测试同包；测试不调用 `private fun requireNonBlankParam`；`ModelEntry.model` = `baseModel.withOverrides(overrides)`（纯函数，JVM 可跑）。

## §4 门禁与 CI 判读

**7 门（约束 A；最终树复跑，日志 `probe-g1/logs/fix2_*.log`）**：

| 门 | rc | 标记 |
|---|---|---|
| `models_provider_field_check` | 0 | `OK (6 arms clean)` |
| `provider_rpc_symmetry_check` | 0 | `ALL GREEN (C1 … C19)` |
| `provider_write_rules_check` | 0 | `ALL GREEN (W1 … W15)` |
| `entry_composite_key_guard_check` | 0 | `OK (10 arms clean)` |
| `collection_copy_on_mutate_check` | 0 | `OK (all mutate sites persist a copy)` |
| `debug_leak_guard` | 0 | `✅ debug read path is minSdk-26 safe` |
| `provider_remember_key_check` | 0 | `[C-SV2b] PASS — all reads through the System single source` |

约束④：`ProviderMutationMethods`/`DebugRPCHandler` 改动**前后**各跑 `provider_remember_key_check.py`，均 rc=0。

**CI 轮次**：

| run | 提交 | 结论 | 读数 |
|---|---|---|---|
| #2925 / `40890860019` | `45cd127f` | **failure**（2.5 min） | Step 15（单测步骤）内 `compileReleaseKotlin` 编不过：`DebugRPCHandler.kt:1408:10 / 1423:10 Conflicting overloads`（`fun looksLikeUtf8Text(bytes: ByteArray): Boolean` 两份）+ `:435:38 Overload resolution ambiguity` ⇒ 重复定义，编译期即死（未到门/单测） |
| dispatch `14:38:03Z` | `45cd127f` | **作废** | push 因仓库 local config 的代理端口过期失败，dispatch 打到旧 tip；修 proxy 变量引用后 push 成功（`45cd127f..7bb2c423`） |
| dispatch `14:38:33Z` | `7bb2c423` | **success**（22.2 min，14:38:33Z→15:00:46Z） | run `40893519882`，`head_sha` 与本地 HEAD 逐字符一致；日志 3,310,492 B / 27 文件 / NUL 0；`Summary: n passed, 0 failed` 共 14 处（含 `113 passed, 0 failed`）；`ALL 349 CASES PASS` ×2；`^e: ` / `e: file://` 0；真 `FAILED` 结尾行 0（`PASSED` 子串已滤）；5 个新测试类 PASSED / 0 FAILED；7 门全部出现且无 suspicious；`BUILD SUCCESSFUL` 在场 |

**run 1 的修复**（`7bb2c423`，门是对的 ⇒ 改写法）：两处 `looksLikeUtf8Text` 中，带 R4-R F-10 KDoc 的是**既有实现**——就地改内部（内联 skip 循环 → 调用共享 `utf8SkipLeadingContinuation`）；无 KDoc 的那份是编辑事故留下的**重复定义**——删除。结果：单一定义、单条规则、三个消费者（有效性检查 / content 解码 / 页尾裁剪）。
局部验证：`extract.py` 重切片 → `kotlinc` → JUnit 探针 **11/11 OK**；新增 `scan_dupes.py`（重复声明扫描装置）9 文件 0 命中；7 门复跑 rc=0（§4 表）。

**run 1 暴露的流程洞（已补装置）**：括号配平与切片探针都看不见「同文件两份同名定义」——两份各自配平、切片器只取第一份，所以本地全绿、云端编译红。`scan_dupes.py` 就是补这个洞的机械门（按「函数名 + 归一化参数类型」判重，同名不同签名的重载单独列出以人工确认）。

- 判读口径（绿后逐条核）：`head_sha` 与本地 HEAD 逐字符一致；`Summary: n passed, 0 failed`；`ALL m CASES PASS`；新用例逐条 `PASSED`；`^ e: ` 0；真 ` FAILED$` 0（`PASSED` 子串不算）；日志无 NUL。

## §5 残留（均带触发条件）

- **R1** `partial_write` 的 `user_message` 是英文固定串（约束 C 禁新增 i18n key）。触发条件：产品决定本地化该提示 ⇒ 新增 key 时 7 locale 同步 + 改 `buildWriteEnvelope` 一处。
- **R2** arm①（nothing applied + earlyError）只承载单行失败，无法区分「只有一行失败」与「提前终止」。触发条件：需要在信封里表达「批被提前终止」时新增 error 值（需消费面同步）。
- **R3** `supportsReasoning` reader 对 unknown（`null`）返回 `false`（`ConfigValue.Bool` 无 null 臂，与 `supportsTools/Vision` 同策略）。触发条件：需要三态语义时加字段。
- **R4** `read_file` 页尾截断丢弃不完整字符（≤3 字节）⇒ 极小 limit 下页内容短于 limit。触发条件：客户端要求「页字节数严格 == limit」时改用 `nextOffset` 对齐消费。
- **R5** 真机未验（证据 = CI 编译级 + JVM 级 + 源码形状）。触发条件：下次真机回归覆盖 `minis-config` 批量写与 `read_file` head 页。
- **R6** `snapshotCollectionChild` 的 leaf 同名后覆盖前属既有行为（当前 `fields()` 无同名 leaf）。触发条件：新增同 leaf 字段时先改键策略。

## §6 登记不修（半径外）

- 其它 RPC 参数（`description` 等）允许空白 = 正常，不动。
- 单行 `performWrite` 的信封形状未改（与批量本合同无关）。
- `probePayloadIsModelsList` 是新增的纯判定（无行为变更），不属修复面。

## §7 这改变什么用户行为

1. **批量改配置部分失败**：从「显示 Settings updated. / exit 0」变为「`ok=false` + `partial_write` + 哪个路径失败、为什么 + 提示重试失败子集」——agent 不再把半成功当全成功。
2. **取消批量写**：不再被记为「写失败」，已落盘部分与取消原因分离。
3. **`minis-config` 可读写 `models.<id>.supportsReasoning`**，删除模型时快照不再丢该属性；RPC `modelsAdd` 加模型不再丢能力标记。
4. **建/改组名**：纯空白名被拒（-32602），首尾空格被修剪后落盘。
5. **`read_file` head 页**：任意 offset 页内不再出现 U+FFFD（此前中间偏移会破坏首字符、极小 limit 会整页乱码）。

## §8 停止节点

分支 CI 绿 + `head_sha` 与本地 HEAD 逐字符一致 = 本支结束，**不合并 main**。报告提交在绿之后（判据：`<绿sha>..HEAD` 非 `fix-reports/` 差异为空）。
