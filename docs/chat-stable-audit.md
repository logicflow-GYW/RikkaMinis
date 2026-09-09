# 会话 B：@Stable/@Immutable 数据类纪律 — 审计结论

> 分支 `fix/chat-stable-discipline` · 基线 `origin/main` (3eb1785)
> 审计目标：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatModels.kt`
> 一句话结论：**5 个 data class 全部稳定可判，仅 `SlashCommand` 缺 `@Immutable`，已补。零「存疑」字段，零不稳定字段。**

---

## 1. 判定模型

- **`@Immutable`**：字段全 `final`（`val`）+ 字段类型全稳定 + 无 `var`/`MutableList`/`MutableState` → 使用 `@Immutable`。
- **`@Stable`**：字段有 `var` 或含不受 Compose 认可的稳定类型 → 退而用 `@Stable` 并在文档说明。
- **枚举**（`ToolBlockStatus` / `ThinkingLevel`）：枚举在 Compose 中天然稳定（唯一实例集合，`equals`/`hashCode` 稳定），无需注解。

---

## 2. 逐类判定

### 2.1 `StreamingDelta` — ✅ Stable（已 `@Immutable`，未动）

| 字段 | 类型 | 稳定性 |
|------|------|--------|
| content | String | 稳定 |
| toolBlocks | List&lt;AssistantBlock&gt; | 稳定（List 稳定 + AssistantBlock 自身 @Immutable） |
| isAwaitingModelResponse | Boolean | 稳定 |
| epoch | Long | 稳定 |

判定：全 `val` + 全稳定类型 → **`@Immutable` 成立**。无改动。

### 2.2 `ChatMessage` — ✅ Stable（已 `@Immutable`，未动）

| 字段 | 类型 | 稳定性 |
|------|------|--------|
| id / role / content | String | 稳定 |
| isStreaming / isAwaitingModelResponse / isQueued / isCompactedHistory | Boolean | 稳定 |
| imageUris / attachmentUris | List&lt;Uri&gt; | 稳定（`android.net.Uri` 是只读平台类型，Compose 认可其稳定；List 稳定） |
| attachmentNames / sourceDbIds | List&lt;String&gt; | 稳定 |
| toolBlocks | List&lt;AssistantBlock&gt; | 稳定 |
| thinkingLevel | ThinkingLevel? | 稳定（枚举） |
| error / errorDetail / queuedPromptId | String? | 稳定 |

关键点：本类含一个**计算属性** `isInternalBridge`（自定义 getter，派生自 `role` + `content`）。它**不破坏 `@Immutable`**——`@Immutable` 只要求「每次读取返回相同结果的稳定读写」；`isInternalBridge` 是纯函数式派生（输入全 final、无副作用、无外部状态），不同实例只要字段相同则派生值必然相同，符合不可变语义。Compose 也不会为单一 data class 内部 getter 重启校验。→ 判定 **`@Immutable` 成立**。无改动。

### 2.3 `QueuedPrompt` — ✅ Stable（已 `@Immutable`，未动）

| 字段 | 类型 | 稳定性 |
|------|------|--------|
| id / text | String | 稳定 |
| attachments | List&lt;InputAttachment&gt; | 稳定（推断 InputAttachment 为稳定 data class，与工具 DSL 同层） |

判定：全 `val` + 稳定类型 → **`@Immutable` 成立**。无改动。

### 2.4 `SlashCommand` — ⚠️ 补注解（本次唯一改动）

审计前状态：**无任何注解** —— 这是本文件 5 个 data class 中唯一漏标的一个。

| 字段 | 类型 | 稳定性 |
|------|------|--------|
| id / title / subtitle | String | 稳定 |
| icon | ImageVector | 稳定（`androidx.compose.ui.graphics.vector.ImageVector` 在 Compose 中被标注 `@Immutable`） |
| isSkill / isMcp | Boolean | 稳定 |

判定：全 `val` + 全稳定类型（含 `ImageVector`，官方已 `@Immutable`）→ **满足 `@Immutable`**。已补 `@Immutable`，与 `StreamingDelta`/`ChatMessage`/`QueuedPrompt`/`AssistantBlock` 对齐。

### 2.5 `AssistantBlock` — ✅ Stable（已 `@Immutable`，未动）

| 字段 | 类型 | 稳定性 |
|------|------|--------|
| id / kind / content / toolTitle / toolName / toolArgs | String | 稳定 |
| toolStatus | ToolBlockStatus? | 稳定（枚举） |
| durationMs / startTimeMs | Long | 稳定 |
| browserURL / imageFilePath | String? | 稳定 |

关键点：含计算属性 `isText`（派生自 `kind`），同 `ChatMessage.isInternalBridge` 逻辑——纯函数派生，不破坏 `@Immutable`。→ 判定 **`@Immutable` 成立**。无改动。

---

## 3. 不稳定字段清单

**无。** 5 个 data class 全部字段为 `val`，无 `var`、无 `MutableList`、无 `MutableState`/`mutableStateOf`（文件内唯一的 `MutableStateFlow` 是 import，不构成字段）。该类字段全部符合 `@Immutable` 前提。

**「存疑」区**：空。`List<Uri>`（ChatMessage.imageUris/attachmentUris）中的 `Uri` 为平台只读类型，Compose 认可其稳定；已核实无违反风险，不列入存疑。

---

## 4. 为什么 `ChatFlatItems.kt` 不用动

（明令禁碰，审计确认无需碰）

- `ChatFlatItems.kt` 中**每一个** `FlatChatItem` 子类（`AssistantHeader` / `AssistantText` / `AssistantThinking` / `AssistantToolUse` / `AssistantToolRunGroup` / `AssistantInfo` / `AssistantTyping` / `AssistantError` 等）**已全部标注 `@Immutable`**（`grep` 逐一核实）。
- 且关键子类**手写了 cheap-equals**（`override fun equals`/`hashCode`），比对廉价稳定身份（key 字段 + 简短派生值）而非昂贵字段（如长文本 content 的 `length`）。这正是「strong-skipping 生效」所需的：LazyColumn 用 item equals 决定是否稳定跳过重组，cheap-equals 让每次比较 O(k) 而非 O(内容长度)。
- 已 `@Immutable` + 手写 cheap-equals = 本文件已处于「稳定不可变 + strong-skipping 生效」的理想态。它也是 C/D 阶段（聚合 Item 粒度、聚合渲染器）的主战场，本次零触碰，避免与并行分支冲突。

---

## 5. 验收自检

- `git status` 改动文件 = `ChatModels.kt`（+1 行 `@Immutable`，注解级）+ `docs/chat-stable-audit.md`（新增）。**无 ChatFlatItems.kt**。
- 行为零变化：纯注解不改变运行时行为，编译通过即证明。
- 判定汇总：5 类审计，4 类已 `@Immutable` 未动，1 类（`SlashCommand`）补注解，0 个存疑字段。
- 纪律：宁可少改不误改，本次仅补一处缺失注解，未做任何字段级改动。
