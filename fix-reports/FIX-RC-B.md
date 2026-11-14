# FIX-RC-B — RC-1114 批 B 支（入口补齐 + 状态持久化）

- **分支**：`fix/rc-1114-b-entry-persist`
- **基线**：`acb8bdd`（R8 并集，不是 main）
- **覆盖**：10 条（U-06 / U-08 / U-09 / U-10 / U-11 / U-15 / U-16 / U-17 / U-18 / U-19）
- **结果**：10/10 动手，0 条证伪，0 条降级登记（2 条局部不改，见 §5）

---

## 1 逐条结论

### 第一组：补入口

每条先回答「按了之后用户得到什么」。答不出的一律不加，只登记 —— 本批没有落到这一格。

#### U-06 文件浏览器删除目录 —— **真修（P1）**

**先证（缺陷真实存在）**：`FileBrowserScreen.kt` 的 trash 按钮被 `if (!item.isDirectory)` 门控，而 `FileBrowserViewModel.deleteItem` 已经有 `shouldDeleteRecursively(...) ? deleteRecursively() : delete()` 分支。⇒ **递归分支在 UI 上不可达**：用户在同一个页面能建文件夹（行菜单 "New folder"），但建完删不掉 —— 长按菜单只有 "Copy Absolute Path"。

**「刻意 vs 缺陷」判定：缺陷。** 反向证据：`deleteItem` 的递归分支不是死码，`R4-V F-11` 的 KDoc 专门为它写了「符号链接必须按名字 unlink」的守卫，说明写它的人预期它会被调用；只是入口忘了放开。

**修法**：放开 trash（所有条目），但**只有确认框说清代价后才允许删非空目录**。
- 新增 `FileDeletionSummary.countEntriesUnder(item)`：用**与删除同一个谓词** `shouldDeleteRecursively` 决定是否递归（计数与删除不可能对「哪些形态是递归」产生分歧）；符号链接计 0（按名字 unlink）；不可读子项贡献 0 而不抛（调用方在画对话框，stat 失败不能崩它）。
- 三种文案状态，绝不给猜测：正在计数 / 空目录 / N 项。计数期间**确认按钮 disabled** —— 误触不可能在用户看到数字之前落地。
- 计数走 `Dispatchers.IO`：会话工作区可能上万文件，遍历不能卡住弹框那一帧。

**证据**：`FileDeletionSummaryTest` 6 例（文件 / 符号链接 / 空目录 / 平铺 / 嵌套 / **计数与实际删除条目数相等**）；变异 m3（去掉递归谓词）与 m4（`count += 2`）各变红。

**遗留**：无。

---

#### U-08 会话存储页打开媒体目录 —— **真修（P1）**

**先证**：`SessionStorageDetailScreen` 的 Media 行只有 `title = "Media"` + `value = size`，**没有 onClick**。而会话文件浏览器 root 在 `minis-sessions/<sid>`，其子目录是 workspace/attachments/offloads/browser —— **不含 media/**。⇒ 想省下一张几十 MB 的图，只能「Clear session」，连工作区一起删。

**「刻意 vs 缺陷」判定：缺陷。** Media 行是**唯一**一个显示数字却不可点的行（同屏的 Minis Files 行是可点的）。

**修法**：
- `SessionFileStore.mediaSessionDirs` 从 `private` 放开为 `internal`，新增 `mediaRootDir`。二者都与 `mediaSize` / `deleteSessionFiles` 共用同一份目录枚举，显示的大小与删掉的字节不会漂移。
- 新增 `MediaBrowseRoot.mediaBrowseRoot(dirs, root)`：**一个叶 ⇒ 落在叶上**（省得点三层日期目录）；**零个或多个 ⇒ 落在共享父目录**。多叶时选任一叶都是任意选择，而浏览器会把 Back 钳制在打开时那个目录，选叶会让其他日期不可达。
- 目录列表**与大小同一次 IO hop 产出**（`LoadedSession`），否则「显示的字节」与「打开的目录」可能来自两次扫描。
- 新增文案 `storage_media_browse` / `_subtitle`（7 语言）。

**顺带修掉一个我自己引入的次生问题**：nav 回调把 `displayPrefix = "/var/minis"` **写死**。这对工作区树是对的（其子目录**就是** PRoot 的 `/var/minis/*`），但对媒体树是错的 —— `filesDir/media/<yyyy>/<MM>/<dd>/<sid>/` 在 guest 里**没有对应物**，「Copy Absolute Path」会给出一个在沙箱里解析不到任何东西的路径。改成 prefix **随请求传**：工作区传 `"/var/minis"`，媒体传 `null`。

**证据**：`MediaBrowseRootTest` 4 例（单叶 / 多叶 / 零叶 / **性质断言：选中的 root 必须包含每个叶**）；变异 m5（`singleOrNull()` → `firstOrNull()`）变红 2 例。

**遗留**：无。

---

#### U-10 日志页说明隐藏文件 —— **真修（P1，修法比报告更宽）**

**先证**：页面只列 `minis-` / `crash-` / `native-crash-`，而 `AppLogger.clearLogs()` 是 `logDir?.listFiles()?.forEach { it.delete() }` —— **整个目录**。实测目录里还有 `debug-*.log`（SSE 全量 delta，~100 MB/天）、`error-snapshot-*.log`、`launch-beacon.log`、`memspike-*.log`，以及任何超出各族 100 条上限的文件。⇒ 总数与目录不一致，且「Delete All」删掉了用户从未见过的文件；目录里只有 debug 日志时，Storage 区整块消失。

**「刻意 vs 缺陷」判定：两半。** 「不列出 debug/快照」是**刻意的**（一天 100 MB，列 100 行会让页面卡）；「不计数、不说明、且 Delete All 覆盖它们」是**缺陷**。

**修法（比报告建议的更宽）**：
- 两个总数：`listedSize`（行之和）与 `hiddenLogSize`（隐藏族）。Delete-All 的数字与确认框显示**两者之和**（那才是真正离开磁盘的量），footer 用 count + size 说明隐藏部分。
- 隐藏侧用**补集**算，不用枚举前缀表。理由：`clearLogs()` 删整个目录，枚举表在出现新族时会静默少算 —— 那正是本次要修的缺陷的缩小版。
- `anyFiles` 也考虑隐藏族：只剩 debug 日志的目录仍有字节可回收，该区不该消失。
- **不加扩展名过滤**：`clearLogs()` 删每个条目，补集也必须覆盖每个条目；只算 `*.log` 会让一个非 log 杂项两边都不计。

**证据**：静态核对了 `logDir` 实际内容族（5 族 + 上限溢出）；i18n 门 ALL CLEAN。

**遗留**：无。

---

#### U-17 运行时限制页恢复默认 —— **真修（P2）**

**先证**：页面 40 个可编辑项全是粗步长滑杆，拖过之后**回不到出厂值**（例：`guestTmpMaxAgeMin` 按 30 分钟吸附，60 分钟默认值只有「从没碰过」才在）。没有任何回退途径，除非整机恢复备份。

**「刻意 vs 缺陷」判定：缺陷（可用性）。** 反向证据：同类的 `SoulSettingsScreen` / `AppearanceScreen` 都有 restore-default，这一页没有。

**修法**：
- 恢复默认是 **STATE 变更，不是写盘**。这页是草稿式页面：每个滑杆改本地 state，顶栏 Save 统一持久化。立即写盘会让这一行自己落盘、而其余三十九行等 Save —— 那才是不一致的那个选择。
- 每个值都取 `AgentRuntimeLimitsPrefs` / `ConcurrencyPrefs` 的命名 `*_DEFAULT` 常量，UI 里**没有第二份数字**。
- **步长一个没动**（任务书 §3 明令：只登记）。已登记为遗留。

**证据（机械核对，非目测）**：
- 40 个引用的 `*_DEFAULT` 常量逐一在数据层存在（38 个 `AgentRuntimeLimitsPrefs.*` + `ConcurrencyPrefs.DEFAULT`）。
- 恢复块赋值集合 == 页面 state 变量集合，**双向差集为空**（40 == 40，无遗漏、无多余）。

**遗留**：滑杆步长仍然粗（`guestTmpMaxAgeMin` 30 分钟一格、若干 MB 级滑杆一格 1 MB）—— 任务书 §3 要求只登记，不改。建议 backlog 记为 P2。

---

#### U-18 模型详情页视频输出 + 最高思考挡位 —— **真修（P1）**

**先证（两处）**：
1. **视频输出没有开关**。屏幕把该维度**原样透传**（`deriveModalityList` 的 passthrough），所以基座声明 video 的模型**永远**被算作输出视频，而基座不声明 video 的**永远**不能被标成输出视频 —— 尽管模型列表 badge 和 group sheet 都渲染这个能力。
2. **`maxThinkingLevel` 没有任何 Android 写入面**。它是思考解析链的**最高优先级来源**（`ThinkingLevelCatalog:138`），但只有备份导入 / 配置恢复 / agent RPC 能写。catalog 允许到 ULTRA 的模型，用户没法手动封顶。

**「刻意 vs 缺陷」判定：缺陷。** 两者都是「数据层有字段、解析链在读、UI 无入口」的同族形态（与 INCR-1113 的「假保护」族同源）。

**修法**：
- 视频输出加开关，seed 自 `effectiveOutput`（与其他输出开关同一套归一化）。
- 最高思考挡位用 `SectionDropdown`，选项 = `null`（跟随模型默认）+ 真实强度阶梯；**排除 OFF**（上面的 switch 已承载「关思考」语义）与 **AUTO**（它不是强度，是「没有意见」；把模型封顶在「没有意见」是自相矛盾的指令）。
- **没有新增持久化字段** —— 两者都是 `ModelOverrides` 既有字段，故不触发四层同步（任务书 §3 的闸门正是这个）。

**关于两个新参数的默认值（需要你判断的取舍）**：`buildModelEntryOverrides` 新增 `videoOutput` 与 `maxThinkingLevel`，默认值分别是「由 base 推导」与 `previous.maxThinkingLevel` —— 这样 9 个既有调用点**零改动**、字节等价。但**默认值本身是行为**：将来谁删掉显式传参，就会静默走默认分支。已写进参数 KDoc，并用测试把两个方向都钉住（见证据）。如果你认为应当改成必填参数（强制每个调用点表态），说一声我改 —— 那会让 diff 变大但更难静默失效。

**证据**：
- `ModelEntryOverrideBuilderTest` 22 例全 PASSED，其中 5 例本批新增/改写：video 默认仍继承、video 关掉产生 override 并去掉 video、video 打开对纯文本模型产生 override、`maxThinkingLevel` 省略时保留、`maxThinkingLevel = null` 时真清除。
- **先证臂（pre）**：`VideoOutputPreProbe` 2 例，在**基线签名**上编译运行并通过 —— 证明基线**没有任何参数组合**能去掉 video（`assertNull(result.outputModalities)` 在基座声明 video 时成立），即该能力在基线不可达。
- 变异 m1（`"video" to videoOutput` → `"video" to true`）红 9 例；m2（maxThinking 回落成 `?: ThinkingLevel.MAX`）红 2 例。

**遗留**：`withOverrides` 不折叠 `maxThinkingLevel`（`ProviderConfig.kt:436-444` 只折 6 个字段）。这不是本批引入的，也不影响本批（新字段读的是 `overrides.maxThinkingLevel` 本身），但意味着「override 的思考挡位」不会反映在 `entry.model` 上。**登记不修**（P2，跨层，无用户可观察症状）。

---

#### U-19 新建自定义模型页补能力输入 —— **真修（P2）**

**先证**：`AddCustomModelScreen` 铸的 entry 是 `ModelEntry(... isCustom = true)` —— **overrides 全 null**。所以要给一个自定义模型设上下文窗口 / 输出上限，只能：保存 → 在列表里找到它 → 打开详情页 → 再填一遍。

**「刻意 vs 缺陷」判定：缺陷（摩擦，两步做一件事）。**

**修法**：把详情页已有的两个输入框**前移**到新建表单。空白 = 不写 override（继承目录/运行期解析），所以只想填「名字 + id」的用户行为完全不变。**没有新增字段**。

**自己修掉的一处冗余**：初版还写了 `overrides.displayName = name`。但在这个页面上 `baseModel.displayName` **就是**用户刚输入的名字，再写一份等值 override 是 no-op，却会把 entry 标成 `isUserModified` 并冻结名字（后续目录更新不再生效）—— 违反了详情页 builder 的「只在偏离 base 时持久化」规则。已删除。

**顺带补齐**：两个输入框补 `KeyboardOptions(keyboardType = KeyboardType.Number)`，与详情页一致（输入是 token 数，且 `filter { isDigit }` 会静默丢弃非数字，给文本键盘是邀请用户白打）。

**证据**：新增文案 `add_custom_model_capabilities_footer` 7 语言齐全；静态检查（R.string 引用存在性 + import 完整性 + 括号配平）全清。

**遗留**：无。

---

### 第二组：状态持久化

#### U-09 Soul 恢复默认写盘 —— **真修（P1）**

**先证**：Restore Default 只重设 composable 内的编辑器状态并关弹框，**SOUL.md 没动**。用户看到编辑器变成默认值、以为已经恢复，一离开页面就静默丢弃。

**「刻意 vs 缺陷」判定：缺陷。** 确认框**就是**确认；再要求按 Save 是对同一个动作的第二次确认。

**修法**：确认分支里 `withContext(Dispatchers.IO) { SoulStore.save(...) }`（照 `AppearanceScreen` 的先例），成功 toast `soul_restored_default`，失败写 `saveError`（不留「编辑器显示默认、文件还是旧内容」的错位）。

**证据**：`Dispatchers` / `scope` / `saveError` 在该文件均已存在（既有 Save 路径在用同一套）；文案 7 语言。

**遗留**：无。

---

#### U-11 环境变量需重启终端 —— **只做 B（提示），A 未做**

**先证**：变量在**终端会话启动时**注入。TZ 与代理有广播路径（`broadcastTimezoneChange` / `broadcastProxyChange` → `TerminalSession.broadcastProxy` → `applyEnvMap` → `queueEnvUpdate` → `pumpEnvUpdates`），任意变量**没有**。用户加完 token 在已开终端里跑命令看到旧环境，会以为没保存成功。

**「刻意 vs 缺陷」判定：半刻意。** 没有通用 env 广播是**设计边界**（`pumpEnvUpdates` 只在 shell 处于 `IDLE_AT_PROMPT` 时才注入，最多等 `ENV_BROADCAST_MAX_DEFER_MS`，超时**丢弃**并记 log —— 因为往有前台程序的 PTY 里打字等于键盘输入）。「不告诉用户」是缺陷。

**修法（B，已做）**：页面顶部一段说明（标题 / 正文 / footer），明确「已打开的终端保留旧环境，需关闭重开」，并**点名**哪两族会实时推送，避免暗示「什么都不生效」。

**A（广播）为什么没做**：`broadcastProxy` 能复用，但**批量变量的语义没想清楚**——代理/TZ 是「整块覆盖」，而任意变量集是增删混合：`unset` 一个已经 export 给运行中进程的变量，与「新开终端里它不存在」不是同一件事（子进程可能已经继承）。在没定清楚「删变量时运行中终端该怎么办」之前动手，是在造一个新的一致性洞。任务书写的是「能复用就顺手做」，我判断这不是「顺手」。**需要你拍板**：要不要做 A，以及删变量时运行中终端的行为（unset / 保留旧值 / 只提示）。

**证据**：文案 7 语言；说明文案与代码事实逐句核对过（`buildTermuxEnv` 在会话启动时读仓库；`pumpEnvUpdates` 有超时丢弃分支）。

**遗留**：A 未做（见上）。

---

#### U-15 OAuth 授权后自动落盘 —— **真修（P1）**

**先证**：OAuth `Success` 分支只 `oauthAuthorized = true` + toast「Authorized」，**不写盘**。用户付完完整代价（外部浏览器往返 + 真实授权同意页）后，若关掉 sheet，服务器**从列表消失**。

**「刻意 vs 缺陷」判定：缺陷。** 与 U-09 同形：两步做完、第三步没人提。

**修法**：
- `buildServerFromForm` 从 `Column` 体内提到外面（OAuth 回调要复用**Save 用的同一个构造**，不能另写一份）。
- 成功后 IO 上 `add` / `update`，**三种结果分开报**：已保存 / 字段不完整（name 或 url 空 —— 明说，不静默丢弃）/ 写入被拒（`update()` 在条目已被外部删除时返回 false）。

**契约核对**：动这个文件前读了 `mcp_oauth_lifecycle_check.py` 的 U1/U2 判据（`check_ui`：每个 `remember` 若 seed 自 `editServer` 必须带 key；必须有 `val <live> = mcpServers.find { … }`），改完门仍 PASS。

**证据**：文案 3 key × 7 语言；`mcp_oauth_lifecycle_check` 真树 + selftest 均绿。

**遗留**：无。

---

#### U-16 HTTP/SSE 合并为一段 —— **真修（P2）**

**先证**：三段选择器提供的选择**存储层表达不了**：`MCPServer` 只有 `url` 与 `command`，`isStdio = !command.isNullOrBlank()`，两个 URL 分支构造**逐字节相同**的条目。⇒「SSE」是个保存时就蒸发的标签，编辑表单永远回到 HTTP。

**「刻意 vs 缺陷」判定：缺陷（假选择）。** 报告建议「加 transport 字段」——**不加**：guest 侧 `minis-mcp-cli` 连接后自己协商协议，从不读这个字段，加了也只是存一个没人读的标签（任务书 §3 也明令方案 = 合并）。

**修法**：合并为一段 `HTTP/SSE`（新 key `mcp_transport_url`，2 段布局）。

**证据**：`mcp_transport_http` / `mcp_transport_sse` 两个 key 现已**零代码引用**（`rg` 核实），i18n 门 ALL CLEAN。

**遗留**：两个旧 key 的**定义**仍留在 7 个 locale 里（i18n 门只对「代码引用但未定义」HARD FAIL，对未引用的定义不报）。保留是有意的：删掉会改动 7 个文件的 14 行、且无收益。若要求清理，说一声。

---

## 2 门（任务书 §5 的 18 个，真树 + `--selftest`）

| 门 | 真树 | selftest |
|---|---|---|
| `readonly_mount_write_guard` | rc=0 | `MOUNT_PROBE_IO_SELFTEST=PASS (76 arms)` |
| `sysinfo_state_thread_check` | rc=0 | `SYSINFO-STATE SELFTEST=PASS` |
| `browser_tabs_lifecycle_check` | rc=0 | `…_SELFTEST=PASS` |
| `chat_menu_prefs_listener_check` | rc=0 | `…_SELFTEST_ARMS=31` |
| `filepreview_print_bounded_check` | rc=0 | `…_SELFTEST=PASS (7 witness arms)` |
| `share_dir_prune_check` | rc=0 | `SHARE_DIR_PRUNE_SELFTEST=PASS` |
| `prefs_listener_holder_guard` | rc=0 | `PREFS_PRIMING_SELFTEST=PASS` |
| `deeplink_settings_routes_check` | rc=0 | `…_SELFTEST=PASS (51 arms)` |
| `entry_id_rename_integrity_check` | rc=0 | `…_SELFTEST=PASS (17 fixtures)` |
| `gateway_context_metadata_check` | rc=0 | `GATEWAY_CONTEXT_METADATA_SELFTEST=PASS` |
| `model_entry_override_parity_check` | rc=0 | `MODEL_ENTRY_OVERRIDE_SELFTEST=PASS (26 arms)` |
| `provider_remember_key_check` | rc=0 | `PROVIDER_REMEMBER_SELFTEST=PASS (141 arms)` |
| `entry_composite_key_guard_check` | rc=0 | `ENTRY_COMPOSITE_KEY_SELFTEST=PASS` |
| `models_provider_field_check` | rc=0 | `MODELS_PROVIDER_FIELD_SELFTEST=PASS` |
| `provider_type_registry_check` | rc=0 | `PROVIDER_TYPE_REGISTRY_SELFTEST=PASS` |
| `envvar_config_group_check` | rc=0 | `ENVVAR_GROUP_SELFTEST=PASS` |
| `mcp_oauth_lifecycle_check` | rc=0 | `MCP_OAUTH_LIFECYCLE_SELFTEST=PASS` |
| `i18n_check` | `Done: ALL CLEAN ✅` | `I18N_CHECK_SELFTEST=PASS (20 fixtures, 0 mismatched)` |

**18/18 真树绿 + 18/18 selftest 绿**（提交后重跑一次，同一结果）。

## 3 i18n

本批新增 17 个 key，全部 7 语言同步（en / zh / zh-rTW / ja / ko / de / ru）：
`filebrowser_delete_dir_message` · `filebrowser_delete_counting` · `storage_media_browse` · `storage_media_browse_subtitle` · `soul_restored_default` · `log_hidden_files_note` · `env_var_restart_terminal_title` · `env_var_restart_terminal_body` · `env_var_restart_terminal_note` · `mcp_form_oauth_saved` · `mcp_form_oauth_saved_incomplete` · `mcp_form_oauth_saved_failed` · `mcp_transport_url` · `runtime_limits_section_reset` · `runtime_limits_restore_defaults` · `runtime_limits_restore_defaults_desc` · `runtime_limits_restore_defaults_footer` · `runtime_limits_restore_confirm_title` · `runtime_limits_restore_confirm_body` · `modeldetail_max_thinking_level` · `modeldetail_max_thinking_inherit` · `add_custom_model_capabilities_footer`

（实际 22 个 key；提交后 7 个 locale 各 1652 条，**数量完全对齐** —— 这是「键集对齐」的机械证据，比逐 key 检查更强。）

## 4 JVM 装置与证据

沙箱无 Android SDK，装置在 `/var/minis/shared/work/rc-1114/b-jvm/`：

- **生产源码是真源码**：`LLMModel.kt` / `ProviderConfig.kt` / `ProviderCredentialMeta.kt` / `ModelContextWindowHeuristic.kt` / `ModelEntryOverrideBuilder.kt` / `MediaBrowseRoot.kt` / `FileDeletionSummary.kt` 全部从工作树直接编译，只补 2 个桩（`androidx.compose.runtime.Stable` 注解、`FileItem` + `shouldDeleteRecursively`）。
- **post 臂**：32 例全 PASSED（`ModelEntryOverrideBuilderTest` 22 + `FileDeletionSummaryTest` 6 + `MediaBrowseRootTest` 4）。
- **pre 臂（先证）**：`VideoOutputPreProbe` 2 例在**基线签名**上编译运行通过 —— 证明基线无参数组合能去掉 video。
- **5 个变异全部变红**（各自只命中自己那组）：

| 变异 | 改动 | 红 |
|---|---|---|
| m1 | `"video" to videoOutput` → `"video" to true` | 9 例 |
| m2 | `maxThinkingLevel = maxThinkingLevel` → `?: ThinkingLevel.MAX` | 2 例 |
| m3 | 计数去掉递归谓词（改判 `isDirectory`） | 1 例 |
| m4 | `count++` → `count += 2` | 3 例 |
| m5 | `singleOrNull()` → `firstOrNull()` | 2 例 |

- **静态一致性检查器**（`static_sweep.py`，补 SDK 缺失）：R.string 引用存在性 / 必须 import 的符号 / 括号配平（**相对基线**判 delta）/ 冲突标记 / XML 可解析 / 调试打印。23 个改动文件全清。
  **它自己也做了变异自检**：删掉一个 import → 抓到；引用不存在的 string key → 抓到；少一个右括号 → 抓到。

### 装置踩到的两个坑（可复用）

1. **绝对括号计数在这个库上是无用的**：`AppLogger.kt` 在**基线**就是 342-vs-341（字符串/注释剥离的残渣）。判据必须是**相对基线的 delta** —— 否则每个碰过这个文件的批次都会看到一条假红，然后学会忽略这个检查。
2. **KDoc 里的符号不是 use**：`AppLogger` 的 KDoc 两处写着 `Dispatchers.IO` 却没有 import。import 检查必须只看剥离注释后的代码，否则「缺失 import」会常态假红。

## 5 刻意 vs 缺陷判定汇总

| ID | 判定 | 依据 |
|---|---|---|
| U-06 | 缺陷 | 递归分支有专门的 R4-V F-11 守卫 KDoc，说明预期被调用；入口忘了放开 |
| U-08 | 缺陷 | 同屏 Minis Files 行可点，Media 行不可点 —— 唯一不一致的那个 |
| U-09 | 缺陷 | 确认框已是确认，Save 是第二次确认 |
| U-10 | **两半** | 「不列出」刻意（100 MB/天）；「不计数不说明且 Delete All 覆盖」缺陷 |
| U-11 | **半刻意** | 无通用 env 广播是设计边界（PTY 前景程序时注入等于键盘输入）；「不告诉用户」是缺陷 |
| U-15 | 缺陷 | 与 U-09 同形 |
| U-16 | 缺陷 | 假选择：存储层表达不了，保存即蒸发 |
| U-17 | 缺陷 | 同类页面都有 restore-default，这页没有 |
| U-18 | 缺陷 | 字段有、解析链在读、UI 无入口（假保护族） |
| U-19 | 缺陷 | 两步做一件事 |

**证伪：0 条。** 10 条先证全部落地为「缺陷确实存在」。

## 6 遗留（需要你判断 / 已登记）

1. **U-11 的 A 半（广播）未做** —— 见 U-11 节。需要拍板「删变量时运行中终端的行为」。
2. **U-18 两个新参数用默认值而非必填** —— 取舍已写在 U-18 节。改必填会让 diff 变大但更难静默失效。
3. **`withOverrides` 不折叠 `maxThinkingLevel`**（P2，跨层，无用户可观察症状，非本批引入）。
4. **U-17 滑杆步长仍粗**（任务书 §3 明令只登记）。
5. **`mcp_transport_http` / `mcp_transport_sse` 两个旧 key 的定义仍在 7 个 locale 里**（零代码引用，i18n 门不报）。

## 7 提交

一条一个 ID（U-09+U-15 与 U-18+U-19 按任务书允许合并；U-15+U-16 因落在同一文件的同一 hunk 合并，理由写在 commit body）：

```
aef24e2  fix(rc-1114-U06): let the file browser delete a directory, and say what goes with it
1838df6  fix(rc-1114-U08): make the session Media row open the media tree
4c030aa  fix(rc-1114-U09): write SOUL.md when the reset is confirmed
bf920ce  fix(rc-1114-U10): count the log files the page hides, and say so
f03853f  fix(rc-1114-U11): say that environment variables need a new terminal
c5a0313  fix(rc-1114-U15,U16): persist the server once OAuth completes; two transports, not three
c4e33d0  feat(rc-1114-U17): restore shipped defaults from the runtime limits page
24d7bb7  feat(rc-1114-U18,U19): edit video output and the thinking ceiling; carry capabilities into the new-model form
```

**tip `24d7bb7bd9794bcac4f23023a721c799c0df150e`**
