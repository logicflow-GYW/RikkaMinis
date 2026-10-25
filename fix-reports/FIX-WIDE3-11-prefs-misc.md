# FIX-WIDE3-11 — prefs / enum / legacy / i18n 四门见证加固

## 0. 基本信息

| 项 | 值 |
|---|---|
| 分支 | `fix/meta-wide3-11-prefs-misc` |
| 基线 | main `6b6c918f` |
| 改动面 | 4 个门脚本，`+1065 / −158`（`enum_parse_safety_check.py` / `legacy_pipeline_guard.py` / `i18n_check.py` / `prefs_listener_holder_guard.py`） |
| 生产代码 | **0 行改动** |
| 冻结面 | `scripts/scan/scan.sh`、`scripts/scan/test_scan.py`、`fix-reports/` 其他报告：**零触碰**（`git diff --name-only` 只列出这四个门） |
| 门数 / 夹具数 | 112 门 / 344 夹具（不变，未新建门） |
| 见证 | **13/13 CAUGHT**（修复前 13/13 STILL_BLIND） |

四门共同的病根只有一个：**判据锚的是「token 出现过」，不是「这个 token 决定的东西还在」**。13 条见证全部是同一个动作的不同拼写——保留全部关键字，删掉决定。

## 1. 门与判据（旧 → 新）

| 门 | 旧判据 | 新判据 |
|---|---|---|
| `enum_parse_safety_check` | 行级：本行含 `runCatching/getOrNull/decoded/…` 任一关键词 ⇒ 放行；或「`try {` 在左、`} catch` 在右」的同行形状；或统计上文的裸花括号数 | **结构化**：注释/字符串掩码后配平花括号，`.valueOf(` 位置必须落在「真有 `catch` 的 `try` 块」或「Result 被处理型接收者消费的 `runCatching` 块」内；无 `.kt` 文件 / 花括号不平衡 ⇒ fail-closed 红 |
| `legacy_pipeline_guard` | 行首锚定的单注解声明正则派生符号表；行类只匹配 `FlatChatItem.` 限定名（非限定名被从集合里扣掉）；`legacy-ok:` 豁免到**下一个空行** | 声明派生改为「顶层 = 花括号深度 0 且圆括号深度 0」，注解/修饰符集合化；**所有**符号一套 `\b名字\b` 判据（限定与非限定同权）；逃逸只覆盖「自身行 / 紧邻注释块 + 首个 use-site 到深度回落」，且**定义点（顶层/声明行）一律不免**；逐文件解析旋转 fail-closed |
| `i18n_check` | `keys_defined_in` 直接扫 XML 原文（注释里的 `<string>` 算定义）；`KEY_LANGUAGE` 臂内 `applyLanguageChange(` 子串存在即算接线；单行臂的 body 靠缩进出界（会吃进兄弟臂） | XML 注释先剥空；臂 body 先做 Kotlin 注释/字符串掩码，再要求存在**不在死守卫内**（`if (false)` / `true \|\| …`）的 apply 调用；单行臂 body = 该行本身，不越界 |
| `prefs_listener_holder_guard` | `cached… =` / `loadFromPreferences(` 子串存在（S3）、`x = ident` 存在（F-134）、`when (key)` 的 `->` 计数（C8b）、`PrefsWriteBus.publish(` 子串存在（C10a） | 四条都改成「**决定**还在」：S3/C10a 要求命中点不在死守卫内；F-134 改成**最后一次写**（同函数内后被别的值覆盖 = 没存住）；C8b 改成**逐臂标识符集合**（任一支重置 ≥2 个标识符，或「有重置的臂数 < 待重置标识符数」即红）；顺带修掉 `field_declarations` 的**前缀误判**（逐字符喂正则，把 `l` / `li` / `lis` / `listener` 全部记成字段名 ⇒ `val l` 被当成字段「held」） |

## 2. 失效轴

| 轴 | 条数 | 形态 |
|---|---|---|
| dataflow | 5 | 守卫/发布/刷新被调用但落在 `if (false)` 里——token 全在，行为没了 |
| parser-death | 5 | 注释里的代码被当代码（i18n C4/C1）、花括号/行首锚把扫描面切掉（enum C3、legacy C2、prefs 前缀误判） |
| spelling | 2 | 只认一种拼写（`FlatChatItem.X` 不认 `X`；局部名 `decoded` 买来豁免） |
| anchor | 1 | 同行「左边 `try {`、右边 `} catch`」形状（enum C2） |
| order | 1 | 只看「写过」，不看「最后一次写是什么」（F-134 立刻 `= null`） |
| completeness | 1 | 只数 `->` 的个数，不看每个臂真正重置了什么（C8b） |
| scope | 1 | `legacy-ok:` 豁免到下一个空行（可以无限延伸） |

## 3. 逐条见证（13/13 CAUGHT）

| # | 门 | criterion | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|---|---|
| 1 | prefs | S3 | dataflow | 例子：`if (false) cachedMaxSessions = clamp(...)` —— 子串 `cached… =` 仍在 | 命中点必须**不在**死守卫 span 内（`cached…=` 或 `loadFromPreferences(` 任一） |
| 2 | prefs | F-134 | order | `concurrencyPrefsListener = concurrencyListener` 后紧跟 `= null`，两条赋值都满足「赋给某处」 | 取**最后一次**写：同函数内若被其它值覆盖 ⇒ 不算存住 |
| 3 | prefs | C8b | completeness | 7 个臂照数（1 个臂重置 7 个控件 + 6 个 `"_n" -> Unit` 空臂） | 逐臂标识符集合：`widest >= 2` 或 `有重置的臂数 < 待重置数` ⇒ 红 |
| 4 | prefs | C10a | dataflow | `if (false) PrefsWriteBus.publish(prefs, key)` —— publish 子串在 | publish 必须有一个**live** 命中 |
| 5 | enum | C1_keyword_exempt | spelling | 关键词白名单含 `decoded`，于是 `val decoded = X.valueOf(it)`（裸调用）被放行 | 白名单整段删除，只认结构（`try`+`catch` / `runCatching`+消费者） |
| 6 | enum | C2_is_safe_line | anchor | `try { Unit } finally {}; val v = X.valueOf(it); try { Unit } catch (_: Exception) {}` —— 左有 try 右有 catch | 结构包含关系：调用必须**在**带 catch 的 try 体内 |
| 7 | enum | C3_is_safe_across_line | parser-death | `try { } catch (_: Exception) {` 同一行：裸计数把 `{` 当成 try 体，catch 体内的裸调用被算作受保护 | 花括号配平 + 帧类型（`try` / `catch` / `runCatching`），catch 帧不算保护 |
| 8 | legacy | C1_legacy_rows | spelling | 通配导入 + 非限定名 `x is AssistantMarkdownBlock`：行类只匹配 `FlatChatItem.` 前缀，且非限定集合把行类扣掉了 | 一套 `\b名字\b` 覆盖全部符号（限定/非限定同权） |
| 9 | legacy | C2_legacy_symbols | parser-death | 声明前多 1 个空格 / 多一个注解 ⇒ 符号从集合静默蒸发（而仓里没有引用点，门因此静默绿） | 结构派生（深度 0 + 圆括号 0，注解与修饰符集合化）+ 逐文件「声明到了却解析不出符号」fail-closed + 顶层声明头缩进即红（冻结包被改要人看） |
| 10 | legacy | C3_escape_hatch | scope | 一行 `// legacy-ok:` 豁免到**下一个空行**，贴着写多少行违规都连带豁免 | 只覆盖自身行 / 紧邻注释块 + 首个 use-site 到深度回落；**顶层声明行不免**（新死代码面必须显式改门） |
| 11 | i18n | C4（wiring） | dataflow | `if (false) applyLanguageChange(code)` —— 子串在 | 掩码后要求存在**不在死守卫内**的 apply 调用 |
| 12 | i18n | C4（wiring） | parser-death | `// applyLanguageChange(code)` 注释掉的调用算接线 | Kotlin 注释掩码后再判定；仅注释命中 ⇒ 红 |
| 13 | i18n | C1（orphan keys） | parser-death | `<!-- <string name="appearance_title">…</string> -->` 被当定义，孤儿键报告「✅ No orphan string keys」 | 先剥 XML 注释（`keys_defined_in` / `declared_locales` 同修） |

## 4. 修法（每门一句）

- **enum**：`mask_code()` + `parse_blocks()` → `guarded_spans()`（只承认「有 catch 的 try」和「结果被处理型接收者消费的 runCatching」）；`getOrThrow` **故意不算安全**（它把异常重新抛出，等于把本门防的崩溃交给调用方）。真树 0 误伤。
- **legacy**：`mask_code()` 做深度/声明判定（`strip_comments` 是行级、对 `"https://…"` 会截断，曾把构造参数/局部 `val` 误当顶层符号，产生 `rawText`/`ordinal` 这类灾难性模式）；声明派生 = 深度 0 且括号 0；逃逸区域函数化（`escape_regions`）。
- **i18n**：`strip_xml_comments()`；`mask_kt_comments()` + `dead_guard_spans()`；`listener_arm_body` 的单行臂短接（`rest not in ("", "{")`）。
- **prefs**：`_dead_guard_spans` / `_live_hits`（S3、C10a）；`stored_into_something` 改为「同函数内最后一次写」（`_enclosing_fn_span` + `_brace_match`）；`_b1_when_arm_idents` 返回逐臂标识符集合；`field_declarations` 只在**名字结束**（分隔符或换行）时记录，消除前缀误判。

## 5. 四层证据

### L1 本门真树绿 + 自证臂全过

```
$ for g in prefs_listener_holder_guard enum_parse_safety_check legacy_pipeline_guard i18n_check; do python3 scripts/scan/$g.py . ; done
prefs_listener_holder_guard rc=0 | OK: write-through delivery green (2 [dv29-a4] delivery line(s))
enum_parse_safety_check rc=0     | ✅ All valueOf calls are properly guarded
legacy_pipeline_guard rc=0       | ✅ Legacy pipeline guard: no new references to the dead path
i18n_check rc=0                  | Done: ALL CLEAN ✅

$ python3 scripts/scan/<gate>.py . --selftest
PREFS_PRIMING_SELFTEST=PASS
ENUM_PARSE_SELFTEST=PASS (7 fixtures, 0 mismatched)
LEGACY_PIPELINE_SELFTEST=PASS (7 fixtures, 0 mismatched)
I18N_CHECK_SELFTEST=PASS (13 fixtures, 0 mismatched)
```

新增负向臂（每条见证一个）：S3 dead refresher · F-134 store-then-null · C8b packed arm · C10a dead publish · enum 3 条（decoded 命名 / 假 try 行 / catch 体）+ 未消费 `runCatching` · legacy 4 条（通配非限定 / 缩进注解声明 / 顶层声明逃逸 / 空行断块）· i18n 4 条（死守卫 / 注释掉 / 死+活并存不误红 / XML 注释键）。

### L2 外部见证反攻（修复前 STILL_BLIND → 修复后 CAUGHT）

```
$ python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" --gates \
    enum_parse_safety_check.py,legacy_pipeline_guard.py,i18n_check.py,prefs_listener_holder_guard.py

prefs  S3    CAUGHT rc=1   prefs  F-134 CAUGHT rc=1   prefs  C8b CAUGHT rc=1   prefs  C10a CAUGHT rc=1
enum   C1    CAUGHT rc=1   enum  C2     CAUGHT rc=1   enum  C3   CAUGHT rc=1
legacy C1    CAUGHT rc=1   legacy C2   CAUGHT rc=1   legacy C3 CAUGHT rc=1
i18n   C4    CAUGHT rc=1   i18n  C4     CAUGHT rc=1   i18n C1    CAUGHT rc=1

CAUGHT=13   restore=True ×13   （逐门跑，结果合并进 probe/reattack-after.json）
```

> 合并成一次 4 门 13 见证的连跑时，本机（12 路并行施工）多次在 `subprocess.wait()` 上撞 PRoot `ENOSYS`
> （registry S28），中途还留下过 `ChatTuningScreen.kt` / `ConfigBuiltins.kt` 的补丁残留——已 `git checkout -- src/`
> 复原并用 `git status` 确认源码树干净。所以证据按门分开跑：每门单独跑时全部一次通过、`restored=True`。

### L3 邻门（同域/同文件族）rc=0

```
auto_approve_bypass_check        rc=0  AUTO_APPROVE_BYPASS_CHECK: OK (all 5 criteria hold)
envvar_config_group_check        rc=0  OK (13 criteria green)
backup_remap_rollup_check        rc=0  OK
provider_remember_key_check      rc=0  [C-SV2b] PASS
session_provider_enabled_check   rc=0  GATE=GREEN
```

### L4 全量（112 门 / 344 夹具）

```
$ sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"
GATE_RUN mode=full ran=112 pass=112 fail=0 sandbox=0 envretry=0 verdict=GREEN

$ python3 scripts/scan/test_scan.py
✅ ALL 344 CASES PASS
```

## 6. CI run

| 项 | 值 |
|---|---|
| workflow run | _（提交后回填）_ |
| head_sha | _（提交后回填）_ |
| 结论 | _（回填）_ |

## 7. 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

- **R1 enum：`runCatching` 结果经局部变量两跳消费**
  触发条件：出现 `val r = runCatching { X.valueOf(s) }` 且下一行/later `r.getOrNull()`（当前仓 0 处）。
  触发后方案：允许「同函数内绑定局部 + 后续处理型接收者」两跳。
  搁置理由：两跳数据流要跨语句追踪，容易把「绑定了但从未消费」也放过；当前方向保守（红），不会静默放行。
- **R2 legacy：逃逸区间可跨同深度多行**
  触发条件：有人在 `legacy-ok:` 之下连写多条**同深度**（不换块）的独立语句 —— 区间会一起豁免（真实树是 `when` 臂，两行同属一支）。
  触发后方案：把区间收缩到「单条语句」（需要语句边界，不能只看深度）。
  搁置理由：现形态（when 臂）就是这个区间的存在理由，收紧会误伤真树；且定义点已经一律不免，新死代码面无法借道。
- **R3 prefs：死守卫只认字面量**
  触发条件：出现 `!true` / `false == true` / `BuildConfig.DEBUG && false` 之外的常量折叠形态。
  触发后方案：加一层薄常量折叠（`!true`、`==`/`!=` 字面量比较）。
  搁置理由：本批 3 条 dataflow 见证全是裸 `false`；折叠要写表达式求值，风险（误判活守卫为死）大于收益。
- **R4 i18n：wiring 只认臂体内直接出现 apply 调用**
  触发条件：把调用抽成同文件 helper（臂内只调 `applyNow()`）。
  触发后方案：允许一跳同文件委托（与 `parts_json_funnel` R11 同形态）。
  搁置理由：当前仓 0 处，且 fail-closed 方向是「红」——真的抽了 helper 会立刻被看见，不会静默失效。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-wide3/wt-11
git log --oneline -3                       # 分支 HEAD
for g in prefs_listener_holder_guard enum_parse_safety_check legacy_pipeline_guard i18n_check; do
  python3 scripts/scan/$g.py .; echo "$g rc=$?"
  python3 scripts/scan/$g.py . --selftest
done
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
  --gates prefs_listener_holder_guard.py --json probe/ra-prefs.json
# （其余三门同上，逐门跑，避开并行窗口的 ENOSYS）
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"
python3 scripts/scan/test_scan.py
```
