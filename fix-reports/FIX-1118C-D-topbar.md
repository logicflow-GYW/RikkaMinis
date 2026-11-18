# FIX-1118C-D-Topbar — 顶栏玻璃遮挡层的合成数学（F-01）

**分支** `fix/topbar-occlusion-1118c`（基线 `97768739` = 1118B 并集树）
**范围** 仅 D 族 F-01；F-02/F-03 已在 TRIAGE 登记不修，本支不碰。

## 1 结论

F-01 成立，且**修法走 A 路**：`hazeEffect` 采样的是**声明的 `hazeSource`**（消息列表那层），
不是「绘制顺序上位于玻璃层之下的像素」。因此地板不需要随玻璃 alpha 变化，**地板恒为
`1f`**：`scrimAlpha(a) = 1 - a` 这个「补集动画」整条删掉。

原实现的病是**加法模型**：Compose 默认 `SrcOver`，地板 `f` 在玻璃 `a` 之下的覆盖率是

```
coverage(a) = 1 - (1 - a) * (1 - f)
```

旧实现 `f = 1 - a` ⇒ `coverage(a) = 1 - a(1-a)`，在 `a = 0.5` 处取最小值 **0.75** —— 玻璃半透的那一帧，
顶栏底下有 25% 的**未模糊原始消息行**透出来。而旧测试断言的是 `scrimAlpha(a) + a == 1f`，
「相加恒为 1」确实恒真，所以那条测试**永远绿，而它命名的性质从来不成立**：这是一个假绿装置，
比那 25% 更值得修，本次一并改掉。

## 2 取证：采样的是声明的 hazeSource（决策点 A/B）

证据分三层，全部来自 haze **1.6.10 源码**（`gradle/libs.versions.toml:347`
`implementation("dev.chrisbanes.haze:haze:1.6.10")`，逐文件核对，非运行时推断）：

**（1）`ChatScreen.kt` 里 haze 只有两端，源是消息列表**

- `ChatScreen.kt:275-288`（注释原文）：「Single HazeState shared by the two ends of the
  frosted-glass composer: the message list records into it (`hazeSource`, on the `LazyColumn`)
  and the composer card samples it (`hazeEffect`, in `ChatInputArea`). One state, one source」
  ⇒ `val hazeState = rememberHazeState()` 是**同一个 state**，玻璃层与卡片共用。
- `ChatScreen.kt:3901`（`LazyColumn` 的 modifier 尾）：
  `.then(if (composerGlassOn) Modifier.hazeSource(hazeState) else Modifier)` ⇒ **唯一源**是消息列表。
- `ChatScreen.kt:2311`（玻璃 Box）：`.graphicsLayer { alpha = topBarGlassAlpha }
  .hazeEffect(state = hazeState, style = topBarGlassStyle)` ⇒ 玻璃层只挂 effect，**不挂 source**。
- 地板 Box（`ChatScreen.kt:2296-2308`）只有 `matchParentSize()` + `background(...)`，
  **没有 `hazeSource`** ⇒ 地板不注册任何 area。

**（2）源节点录的是自己的子树（`HazeSourceNode.kt`，commonMain）**

```kotlin
contentLayer.record {
    this@draw.drawContentSafely()          // 源节点自己的子树
}
drawLayer(contentLayer)                     // 再画回画布
```

即 `HazeArea.contentLayer` 是**离屏副本**，内容 = `Modifier.hazeSource` 那层节点的子树绘制结果。

**（3）effect 只读这些 area 的层（`HazeEffectNode.kt` / `BlurEffect.kt`）**

- `HazeEffectNode.updateEffect()`：`backgroundBlurring = state != null`；
  `areas = state?.areas.orEmpty() ... filter { canDrawArea / zIndex }` ⇒ 输入**只**来自
  state 上登记的 source areas（本例只有 LazyColumn 一个），**没有**任何「取窗口像素」的路径。
- `BlurEffect.kt` 的离屏层录制：`for (area in node.areas) { ... drawLayer(area.contentLayer) }`
  ⇒ 模糊输入 = 各 source 的录制层。
- `RenderEffectBlurEffect`：`contentLayer.renderEffect = node.getOrCreateRenderEffect(...)`、
  `contentLayer.alpha = node.alpha`、`drawLayer(contentLayer)` ⇒ 拿**已录制的层**加模糊，
  不是拿它下面已合成的窗口。
- 回退路径 `ScrimBlurEffect.drawEffect()` 只 `drawScrim(tint...)`（纯着色，不读窗口）⇒
  **两条路径都不采样地板**。

**B 为什么不成立**：B 的前提是「玻璃必须透过地板看见内容」，即模糊输入包含地板像素。
上面三层证据说明输入恒为 `area.contentLayer`（源子树离屏副本），地板既不注册 area、
也不进入任何录制；`a` 从 0 到 1 时画面是「实色顶栏 → 实色顶栏上逐渐浮现模糊的列表行」，
任何一帧都没有未模糊原始行，`a = 1` 时地板被玻璃完全覆盖而玻璃照样是玻璃。故不需要
「压 bleed 的曲线」，`(1-a)^k` 这条路不存在。

## 3 修法

| 位置 | 改动 |
|---|---|
| `TopBarGlassOcclusion.kt` | `scrimAlpha(a) = (1f - a).coerceIn(0,1)` → **`floorAlpha(a) = 1f`**；KDoc 重写为 SrcOver 覆盖率模型 + 上述 haze 取证（不再出现「pair adds up to a fully opaque bar」） |
| `ChatScreen.kt` | 地板 `.background(topBarGlassBase.copy(alpha = ...))` 的调用点由 `scrimAlpha(topBarGlassAlpha)` 改为 `floorAlpha(topBarGlassAlpha)`；同块注释同步（尺寸/tail/渐隐带一律未动） |
| `TopBarGlassOcclusionTest.kt` | 合成模型换成 SrcOver：删掉 `floor plus glass always covers the bar`（加法断言）与越界 clamp 断言（地板已是常量，钳位无从谈起），新增覆盖率断言，期望值**全写字面量** |
| `TopBarGlassFloorWiringTest.kt` | 锚点字符串随函数改名更新；**400 字符窗口换成括号配对锚点**（取包裹地板调用的整个 `Box(...)` 块），消除「注释挪了、断言照样过」的窗口漂移 |

覆盖率最小值：**min = 1f**（`a ∈ [0,1]` 全程），阈值 `MIN_COVERAGE = 0.99f`，
字面量期望 `1f`（地板不透明 ⇒ SrcOver 数学上无空隙，不必容忍任何 bleed）。
旧模型同口径的最小值是 **0.75**。

**这改变什么用户行为？** 顶栏玻璃淡入的**中间帧**不再透出未模糊的原始消息行（原最多 25%）；
首帧仍是普通实色顶栏（裸闪已由 1118 的遮挡层解决，本次不改），末帧玻璃效果不变。

## 4 装置与变异负控

装置：`/var/minis/shared/work/r1118c/topbar-jvm/{run.sh,mutate.py}`（`TopBarGlassOcclusion.kt` 是
Android-free 的，单文件 kotlinc + junit；`TopBarGlassFloorWiringTest` 需 cwd = worktree 根，
从 cwd 向上找 repo）。基线 **OK (9 tests)**（4 条 `TopBarGlassOcclusionTest` + 5 条
`TopBarGlassFloorWiringTest`，后者新增 `the floor box carries no glass of its own`）。

变异 5 臂（每臂只红对应用例，变异后快照还原 + sha256 逐字节校验通过）：

| 臂 | 变异 | 结果 |
|---|---|---|
| M1 | 地板回到 `1 - a` | RED-PRECISE（floor opaque / window / trade-off 三条红；首帧仍绿 —— `a=0` 处旧模型恰好也覆盖） |
| M2 | 地板恒 `0f` | RED-PRECISE（四条全红，含首帧） |
| M3 | 测试断言改回加法 | RED-PRECISE（window + trade-off 红；首帧处加法恰好等于 1，未红） |
| M4 | 删掉首帧断言 | **GREEN（预期）**：与窗口断言在 `a = 0` 处重叠，这是刻意的——首帧断言锁的是根因那一帧的**命名契约**，窗口断言锁全窗，二者互补（删掉窗口断言后 M1 将全盲） |
| M5 | 伪修法：地板钳到 `≥ 0.5`（bleed 压小不消除） | RED-PRECISE（window 在 `a=0.05` 处 0.9975 ≥ 阈值但 ≠ 1f ⇒ 字面量牙抓住） |

## 5 证据（CI）

- run **#3269** / `44339131238` @ `5696c8aed062cf572791942cf4b2745c3345cd62`（= 远端 tip = 本地 HEAD，逐字符一致）
- 五 job 全 **success**：`build-apk` / `unit-tests` / `scan-gate` / `lint` / `publish`
- unit-tests：`9710` 条 PASSED 行、`BUILD SUCCESSFUL`；日志里 6 条含 `FAILED` 的行全部是**用例名**（如 `slot denied produces FAILED without calling provider PASSED`），**0 条真失败**
- 本支 9 条用例逐条 `PASSED`（`TopBarGlassOcclusionTest` 4 + `TopBarGlassFloorWiringTest` 5）
- `publish` job success，日志中**没有** `Move the rolling tag` / `Publish to Releases` 步骤组 ⇒ main 门控按预期跳过，未污染 release
- **未跑**全量 `scan.sh` / `test_scan.py` / gradle（任务硬约束）；`scripts/scan/**` 未触碰，scan-gate 夹具按机制跳过，本支 CI 绿**不含**夹具证据

## 6 纪律自查

- 触碰文件：仅任务书允许的 4 个（1 个生产 + 1 个调用点 + 2 个测试），`ChatScreen.kt` diff 只落在顶栏遮挡层那一块。
- 未碰 `scripts/scan/**`、其他支文件、任何 i18n。
- 阈值未放宽（0.99 / 字面量 1f），未为测试绿改实现。
- 未声称「效果确认」：视觉类改动沙箱无法验证，只声称数学覆盖率 + 首帧不透明 + 门/测试绿。

## 7 真机待验（用户确认）

1. 玻璃淡入的中间帧不再出现未模糊的原始消息行（重点看 `topBarGlassAlpha` 在 0.2~0.8 那段）。
2. 首帧仍是普通实色顶栏，无裸闪回退（1118 那条不回归）。
3. `a = 1` 时玻璃的模糊/色调与 1118B 版本一致（地板被完全覆盖，不应有任何变化）。
4. 顶栏玻璃开关关闭时行为不变。

## 8 回报

- **F-01 → 已修**：走 **A**（haze 采样声明的 `hazeSource`），证据 = §2 的 `hazeState` / `hazeSource` / `hazeEffect` 行 + haze 1.6.10 `HazeSourceNode.draw()`、`HazeEffectNode.updateEffect()`、`BlurEffect.kt` 的 area 层录制、`RenderEffectBlurEffect`、`ScrimBlurEffect`。
- 覆盖率最小值 **1f**（阈值 0.99，字面量期望 1f）；旧模型同口径最小值 **0.75**。
- 用例：`the floor is opaque at every glass alpha`、`the first frame of a chat open is fully occluded`、`the pair covers the bar across the whole fade window`、`the floor does not trade off against the glass`；接线支新增 `the floor box carries no glass of its own`。
- CI：`fix/topbar-occlusion-1118c` @ `5696c8aed062cf572791942cf4b2745c3345cd62`，run #3269（五 job success，head_sha 逐字符一致）。
- 单测：CI 9710 PASSED / 0 真失败；JVM 装置 9/9；变异 5 臂（4 臂 RED-PRECISE + 1 臂预期 GREEN 的文档化重叠）。
- 真机待验：§7 四条，由用户确认（视觉类，沙箱无法验证）。
