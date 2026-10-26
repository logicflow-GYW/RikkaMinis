# FIX-BATCH6-t10 — `open_url_broker_scheme_check` + `openminis_compat_field_parity_check`

- 支线：`fix/b6-openurl-compat`（基线 `base/batch5-shared` @ `e145ec961232c7898644346ff4bc0c3f70980369`
  = main `6ac77c6b2` + 共享原语 `scripts/scan/_kotlin_valueflow.py`）
- 工作树：`/var/minis/shared/work/meta-audit-v5/batch6/wt6/t10`
- 改动面：**只改 2 个门脚本**（`open_url_broker_scheme_check.py` +604/−482，
  `openminis_compat_field_parity_check.py` +328/−161）。生产 `.kt` **0 行**，
  `scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` **0 行**，
  门数 112 / 夹具 344 不变，未新建门。

## §1 门与轴

| 门 | 见证数 | 轴分布 |
|---|---|---|
| `open_url_broker_scheme_check.py`（report-B 门 02） | 7 | dataflow 3（02-1/02-5/02-7）· family-hole 1（02-2）· completeness 1（02-3）· anchor 1（02-4）· parser-death 1（02-6） |
| `openminis_compat_field_parity_check.py`（report-B 门 12） | 6 | family-hole 3（12-1/12-2/12-4）· completeness 1（12-3）· dataflow 1（12-5）· parser-death 1（12-6） |

两门共症一句话：**判据锚「某个 token 被写出」，缺陷动「这个 token 求值/传递/可达的效果」**。
`isSupportedScheme` 还在、只是判的是常量；`put("k", …)` 还在、只是写进临时对象 / 死在 `if (false)` /
写的是一个折叠后的常量；`resolveSessionId(...)` 还在、只是返回值被 `.also { }` 丢掉；
`consume()` 还在、只是被一个原始字符串把花括号账目挪出去了。

## §2 每条见证（原补丁 → 旧判据为何瞎 → 新判据 → 新臂名）

### 门 02 `open_url_broker_scheme_check.py`

| # | 轴 | find（原文首行） | replace | 旧判据为何瞎 | 新判据 / 臂 |
|---|---|---|---|---|---|
| 02-1 | dataflow | `if (!isSupportedScheme(uri.scheme)) return` | `if (!isSupportedScheme("http")) return` | U1/U3/U8 只要求「有 isSupportedScheme 调用 + 顺序 + 臂里有 return」；判的是常量，白名单从未被咨询 | **U8b**：实参必须由本重载自己的形参（`uri`）派生（`value_sources` + 一跳局部 val），常量实参一律红 · `BAD_GUARD_CONST_ARG` |
| 02-2 | family-hole | `fun offer(uriString: String) { … offer(parsed) … }` | `if (false) offer(parsed)` + `_pendingUrl.compareAndSet(_pendingUrl.value, parsed)` | U2a 只问「`offer(parsed)` 子串在不在」（死分支也算）；U2b 只数 `.value =`（CAS 这种发布拼写不计） | **U2a** 委派必须 `live_positions` 可达；**U2b** 发布点按全部拼写统计（`.value=`/`compareAndSet`/`tryEmit`/`emit`/`update`/`setValue`） · `BAD_DEAD_DELEGATE` |
| 02-3 | completeness | `return s == "http" \|\| … \|\| s == "minis"` | 追加 `\|\| s == "javascript" \|\| s == "file"` | U4 只做「四个必需项是否都在」（子集断言），**看不见白名单被加宽** | **U4** 判 `return` 表达式里的字面量**集合恰等于** {http, https, about, minis}（多一项即红） · `BAD_WHITELIST_WIDENED` |
| 02-4 | anchor | `if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {` | `if (MinisOpenUrlBroker.isWebScheme("http") \|\| true) {` | U5b 只做「锚点 + 分支内 consume」文本定位，条件恒真/判常量都读不出来 | **U5b** 条件经 `const_value` 折叠（恒真 → 红「不再筛 scheme」；恒假 → 死门）+ 实参必须派生自本块的 pending Uri · `BAD_TERMINAL_CONST_COND` |
| 02-5 | dataflow | `val terminalSessionId = currentChatSessionId\n ?.let { resolveSessionId(it) }` | `.also { ChatViewModelStore.resolveSessionId(it ?: "") }` | U6c 用 `re.search` 在绑定语句的**文本**里找 resolve 调用；`.also` 丢弃接收者，值根本没传下去 | **U6c** 绑定右值走 `value_sources`（剥 `also/apply/onEach` 只留接收者、`let/run` 取尾表达式），解析结果必须存活到实参 · `BAD_ACTIVITY_ALSO` |
| 02-6 | parser-death | `if (isWebScheme(uri.scheme)) {\n previewUrl = uri.toString()\n // Only claim…` | 分支首行插入 `val raw = """\"}"""` | 自写 `strip_comments`/`match_delim` 的花括号账目穿过字面量就漂；`consume()` 的位置靠它算 | **U5d** 锚点区间必须「被建模且无歧义」：语句只能是赋值 / `consume()` / 控制流 / 日志（`val/var` 声明 = 红），且字面量内容不得携带 `{ } " '`（fail-closed：不认的语句一律红） · `BAD_TERMINAL_RAW_STRING` |
| 02-7 | dataflow | `sessionId = ChatViewModelStore.activeSessionId,` | `sessionId = ChatViewModelStore.activeSessionId.let { null },` | U7c 只问「实参里有 resolve 文本」；`.let { null }` 保留文本、丢掉值 | **U7c** 实参按 `value_sources` 折叠后必须仍含 resolve 源（或绑到解析过的局部 val） · `BAD_NAV_LET_NULL` |

### 门 12 `openminis_compat_field_parity_check.py`

| # | 轴 | replace | 旧判据为何瞎 | 新判据 / 臂 |
|---|---|---|---|---|
| 12-1 | family-hole | `if (false && inst.has("appendV1Suffix")) { … }`（守卫、写入、极性全在） | arm1 只做子串三连（`has(k)` / `put(k, optBoolean(k))` / 无 default-false），分支不可达也能全绿 | arm1 新增**可达性**：`put("appendV1Suffix", …)` 至少一个 site 经 `path_feasible` · `appendV1Suffix pass-through unreachable` |
| 12-2 | family-hole | `if (inst.has("pinned")) obj.put("pinned", !true)` | `is_bare_literal` 是字面白名单（`true/false` 等），`!true` 不在名单；`guarded_by_self` 只要守卫测过键（`has`）就豁免 | `is_bare_literal` → **`const_value` 可折叠即常量**（`!true`/`1==2` 全折）；`guarded_by_self` → 守卫必须**读值**（`optBoolean("k"` / `getBoolean("k"`），`has(k)` 不再豁免 · `pinned written as a folded constant (!true)` |
| 12-3 | completeness | `if (false) obj.put(RCF_PINNED, instance.pinned)` | arm2 的键集是「导出侧写什么、转换侧就得写什么」；写死在死分支里，键集层面**看不出缺**（转换侧仍留着给旧备份） | `write_run_config_keys` 只收**可达**写入，并额外要求「仓库里定义的每个 `RCF_*` 常量都被 seam 真写过」，否则红（`dead_rcf`） · `RCF constant written only in a dead branch` |
| 12-4 | family-hole | `?.takeIf { it < 0 }?.let { o.put("pinnedAt", it) }` | `write_values` 只看「有 put(k, …)」；谓词方向反了不漏 | 新增 `chain_predicate_dead`：`takeIf/takeUnless/filter/filterNot` 的谓词按**非负整数域**（时间戳/计数/token）判可行性，`it < 0` 不可满足 → 红 · `pinnedAt write gated by takeIf { it < 0 }` |
| 12-5 | dataflow | `JSONObject().put("fallbackStrategy", …)` | 「按 key 找 put」不看接收者，写进垃圾也算写了 | 新增 `receiver_ident`：接收者必须是命名对象（`obj.put`），构造器调用/链式结果（`…).put(`）= 红 · `group key written into a throwaway JSONObject()` |
| 12-6 | parser-death | `/* if (inst.has("azureMode")) obj.put("azureMode", …) */` | 自写 `code_only` 只剥 `//`，块注释原样留在「代码」里，被注释掉的写入照样算写了 | 全部结构/键读取改走共享 `analyse()` 的 `code`/`comments` 双视图（块注释、原始字符串、字符字面量、嵌套注释由共享词法器负责），被注释掉的 put 从 `comments` 视图消失 · `provider key commented out with /* */` |

## §3 判据改造（旧形态 → 新形态，函数名级）

**门 02（`open_url_broker_scheme_check.py`）**

删除（自写词法/结构层，全部被共享层替代）：
`strip_comments()`、`match_delim()`、`match_brace()`、`_body_terminates()`、`_consequent_span()`、
`_binding_statement()` 与 `RE_CONT_*` 续行猜测、`is_bare_literal()` 式的字面白名单。

新增/替换：

| 函数 | 作用 |
|---|---|
| `views(src)` | `_kotlin_lexer.analyse` → (`code`, `comments`) 两个**等长**视图；`KotlinLexError` 上抛（调用方 fail-closed 报违规） |
| `extract_span` / `extract_fun` | 声明 → 体外花括号区间，用 `match_bracket`（不再自己数花括号） |
| `param_names` | 从声明取形参名（`roots`），供「守卫必须判调用方的 Uri」使用 |
| `_local_init` + `derives_from` | 实参是否由 roots 派生；含**一跳局部 val**（`val scheme = uri.scheme` 仍绿），常量/字面量一律 False |
| `return_literals` | `return` 表达式里的字面量集合（值通道读内容，配合 `expr_end` 取表达式边界） |
| `RE_PUBLISH` | 发布点全拼写（`.value=` / `compareAndSet` / `tryEmit` / `emit` / `update` / `setValue`），`= null`（consume）不计 |
| `analyse_guard_termination` | U8：`if_blocks` 取臂区间 → 反向臂必须「真退出外层函数」(`returns_exiting` + 深度 0 `throw`)，正向臂必须**包住**发布点；并新增「每个发布点都必须被某个守卫保护」。U8b：`call_arg` + `derives_from` |
| `_arm_terminates` / `_arm_inner` | 臂是否终止（`return@label`/lambda 内裸 return 不算） |
| `analyse_terminal` | U5a-d：`match_bracket` 定位块与条件区间；条件 `const_value` 折叠；`consume()` 必须在分支体区间内；`U5d` = `stmt_spans` + `_modelled_statement`（`val/var` 与未建模语句 → 红）+ `_hostile_literals`（字面量内容带定界符 → 红） |
| `resolved_local_names` | 局部 val 的**值**（`value_sources` 折叠后）是否来自别名表——`.also { resolve(…) }` 不再算解析过 |
| `analyse_terminal_deeplink` | U6/U7（criterion 后缀 a/b/c 由 label 参数生成）：实参按 `split_top` 切、具名实参剥名后按 `value_sources` 折叠，再判 resolve 源 / 局部名 |

判据数：**12 → 17**（新增 U8b、U5d；U8 增加「每个发布点必须被保护」；U5b/U6c/U7c/U2a/U2b/U4 判据换实现）。
`--selftest` 臂：**41 → 86**，收尾标记 `OPEN_URL_BROKER_SCHEME_SELFTEST=PASS (86 arms)`。

**门 12（`openminis_compat_field_parity_check.py`）**

删除：`code_only()`（只剥 `//`）、`_balanced()` / `function_text()`（自写括号配平）、
`is_bare_literal()`（字面白名单）、`guarded_by_self()` 的 `has(key)` 豁免、`write_values()` 的「只按 key 找 put」。

新增/替换：

| 函数 | 作用 |
|---|---|
| `views` / `function_slice` / `block_slice` | 共享双视图 + `match_bracket` 取函数体/锚点块；缺失 → ROT |
| `receiver_ident` | 写入接收者必须是命名对象（`JSONObject().put` → 红） |
| `value_expr` | 值表达式（结构边界取 `code` 视图、内容取 `comments` 视图） |
| `guard_condition_span` / `guard_reads_key_value` | 守卫条件区间（同语句前缀 / 外层块头两种形态），并判它是否**读了键的值**（`optBoolean("k", …)`），`has(k)` 不算 |
| `chain_predicate_dead` | `takeIf/takeUnless/filter/filterNot` 谓词按非负整数域判可行性（`it < 0` → 红；`it > 0` / `it.isNotEmpty()` → 绿） |
| `identifier_written_keys` | 批发拷贝形态（`for (k in arrayOf(…)) put(k, …)`）保留为「写了」，避免反向误伤 |
| `missing_writes(required, pair)` | 一个键只有在**所有**可达写入都无效时才算丢；无效 = 死分支 / 临时接收者 / 与源无关的常量值 / 不可满足谓词 / 写后 `remove(k)` |
| `write_run_config_keys` | 只收可达的 `RCF_*` / 字面量写入，并返回「定义了但没被真写」的常量清单（12-3 的盲点） |
| `run()` | 全部文本判据改读 `comments` 视图，结构判据读 `code` 视图（位置共享） |

`--selftest` 臂：**19 → 25**（新增 6 条见证臂），收尾标记仍是 `OPENMINIS_COMPAT_FIELD_PARITY_SELFTEST=PASS`。

## §4 四层证据

**L1 本门**（真实树，本树）：

```
python3 scripts/scan/open_url_broker_scheme_check.py --selftest   → OPEN_URL_BROKER_SCHEME_SELFTEST=PASS (86 arms)
python3 scripts/scan/open_url_broker_scheme_check.py .            → all 17 criteria green            (rc=0)
python3 scripts/scan/openminis_compat_field_parity_check.py --selftest → OPENMINIS_COMPAT_FIELD_PARITY_SELFTEST=PASS
python3 scripts/scan/openminis_compat_field_parity_check.py .     → openminis-compat-parity: OK (53 keys pinned) (rc=0)
```

**L2 反攻（硬验收）**：用官方驱动 `verify_batch5.py --mode full`，按任务书给的输出路径逐门跑：

| 门 | 见证 | 结果 |
|---|---|---|
| `open_url_broker_scheme_check.py` | 02-1 … 02-7 | **7/7 CAUGHT**（rc=1, exact），`restored` 7/7 |
| `openminis_compat_field_parity_check.py` | 12-1 … 12-6 | **6/6 CAUGHT**（rc=1, exact），`restored` 6/6 |

产物：`probe/t10-open_url_broker_scheme_check.json`、`probe/t10-openminis_compat_field_parity_check.json`。
反攻后 `git status --porcelain src/` **为空**（无变异残留）。

**L3 邻门（13 道，全 rc=0）**

- open_url 侧 5 道：`chat_session_id_binding_check` · `deeplink_settings_routes_check` ·
  `mirror_category_route_check` · `ime_inset_guard` · `debug_leak_guard`
- openminis 侧 8 道：`backup_streaming_export_check` · `backup_remap_rollup_check` · `parts_json_funnel_check` ·
  `provider_import_id_roundtrip_check` · `provider_ua_parity_check` · `entry_composite_key_guard_check` ·
  `file_provider_paths_coverage_check` · `four_way_sync_check`

**L4 全量**

| 项 | 读数 | 说明 |
|---|---|---|
| 门脚本（110 个 `python3 scripts/scan/*.py` 调用） | **110/110 rc=0，RED 0** | 逐门独立进程循环（`/tmp/t10-gates2.txt`） |
| i18n 一致性 | **PASS**（defined 1591 / code 1582 / orphans 0） | 与 scan.sh `[2/112]` 同一判据 |
| `test_scan.py` | **PASS=344 FAIL=0**（46 个 test 函数全跑） | 逐 test 函数驱动，逐函数记录 `cases=+N fails=+0` |

**替代说明（必须记录）**：`bash scripts/scan/scan.sh` 在本沙箱被整壳打死 ——
`parts_json_funnel_check.py` 里 `open()` 报 `OSError: [Errno 38] Function not implemented`（跑到 `[57/112]`），
`test_scan.py` 单进程整跑被 stall-guard 判「无输出且无 CPU」杀掉。
这是 `sandbox-limits.md` 已登记的 **S28（Errno 38 风暴）** 环境缺陷，不是门红；健康路径就是本支采用的
「逐门独立进程 / 逐 test 函数驱动」。`scan.sh` 与 `test_scan.py` 的全量读数以分支 CI job 日志为准（见 §5）。

## §5 CI run

（CI 出结论后回填，本段在 CI 绿后单独提交。）

## §6 残留（触发条件 / 触发方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | 任务书 §2 列出的共享 API `last_write(text, name)` 在 `_kotlin_valueflow.py` 里**不存在**（本支改用门内 `_local_init` 做一跳局部 val） | 主会话准备给别的门用 `last_write` 时 | 二选一：补共享函数，或把任务书 §2 清单改成实际导出集 | 共享层冻结（硬约束 2），本支不许改；门内 8 行等价实现足够 |
| R2 | 门 02 的 U8 只判「发布点被某个守卫保护」，未判「该守卫自身可达」（`if (false) { if (!isSupportedScheme(…)) return }` 形态仍算保护） | 出现真实补丁把守卫嵌进死分支 | 用 `if_blocks` 的 cond 偏移 + `path_feasible(body, cond_off)` 判守卫可达 | 非本波见证；方向是 fail-open 但需要同时伪造死分支与发布点，radius 纪律外 |
| R3 | 门 02 的 **U5d** 是「fail-closed 区域模型」：锚点区间出现未建模语句（`val/var` 声明、非日志调用）即红 | 真实代码在 `isWebScheme` 分支里新增合法语句（例如 `val url = uri.toString()` 再赋值） | 把该语句形态并入 `_modelled_statement` 白名单（或显式允许「纯局部别名」） | 现在红是安全方向（吵醒人比静默放行好）；真实树只有 2 条语句 |
| R4 | 门 12 的 `_CHAIN_GAP` 允许链式空隙里出现标识符，理论上可能把**前一条语句**的 `takeIf` 也算进来（多红不少红） | 出现「合法 takeIf 之后紧跟同键写入」的误红 | 改成从语句起点起算的链式解析（复用 `stmt_spans`） | 方向 fail-closed；真树只有 `it > 0` / `it.isNotEmpty()` 两种谓词，全绿 |
| R5 | 门 12 的 `remove("<key>")` 判据是「函数体内出现即红」，不看位置/可达性 | 真实代码出现条件性 `remove` 同键 | 比较写入位置 + `path_feasible` | 真树 `OpenMinisBackupCompat.kt` 无 `remove(` |
| R6 | 谓词可行性用固定正域 `POSITIVE_DOMAIN = {"it": (1, None)}` 建模（时间戳/计数/token 都正） | 出现合法**负值**字段（如偏移量）被谓词判红 | 按 key 分组域，或从导出侧表达式推符号 | 当前键集全是非负语义；写死域换来 12-4 这条真缺陷可判 |
| R7 | 门 12 未建模 `put(key, null)` / `accumulate` / `optNull` 等 JSONObject 变体写入口 | 转换器改用这些 API 写必需键 | 扩 `PUT_KEY` 家族（并保留可达性过滤） | 真树一处不用；加进去反而增加误判面 |
| R8 | 门自证 stdout 不进 CI 日志（只有 `test_scan` 的 ✅ 行可见），臂数在 CI 侧不可见 | 需要核对「新臂真的在 CI 里跑」时 | 由 `test_scan.py` 断言带臂数的完整标记串（见 §7） | `test_scan.py` 冻结，本支不许改 |

## §7 `test_scan.py` 注册项（交主会话统一处理）

`test_scan.py` **已有**两处注册（`scripts/scan/test_scan.py:2812` / `:2834`），本支未改：

```python
    ("open_url_broker_scheme_check", "OPEN_URL_BROKER_SCHEME_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("openminis_compat_field_parity_check", "OPENMINIS_COMPAT_FIELD_PARITY_SELFTEST=PASS", (), (REPO_ROOT,)),
```

本支的标记串**保留了这两个子串**，因此注册项无需变更即可继续生效（已本地实跑 `test_scan` 344/0 验证）。
臂数（供主会话选择是否收紧断言）：

- `open_url_broker_scheme_check`：**86 arms**，实际输出 `OPEN_URL_BROKER_SCHEME_SELFTEST=PASS (86 arms)`
  → 若收紧，可把断言串改成带 `(86 arms)` 的完整行。
- `openminis_compat_field_parity_check`：**25 arms**，实际输出仍是裸 `OPENMINIS_COMPAT_FIELD_PARITY_SELFTEST=PASS`
  （臂数只体现在逐臂 `[ok]` 行）。

## §8 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch6/wt6/t10

# L1 本门
python3 scripts/scan/open_url_broker_scheme_check.py --selftest
python3 scripts/scan/open_url_broker_scheme_check.py .
python3 scripts/scan/openminis_compat_field_parity_check.py --selftest
python3 scripts/scan/openminis_compat_field_parity_check.py .

# L2 反攻（13 条见证必须全 CAUGHT、restore 全 True）
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-B.json --repo . --mode full \
  --only open_url_broker_scheme_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch6/probe/t10-open_url_broker_scheme_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch6/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch6/witnesses-B.json --repo . --mode full \
  --only openminis_compat_field_parity_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch6/probe/t10-openminis_compat_field_parity_check.json
git status --porcelain src/          # 必须为空

# L3 邻门（逐条 rc=0）
for g in chat_session_id_binding_check deeplink_settings_routes_check mirror_category_route_check \
         ime_inset_guard debug_leak_guard backup_streaming_export_check backup_remap_rollup_check \
         parts_json_funnel_check provider_import_id_roundtrip_check provider_ua_parity_check \
         entry_composite_key_guard_check file_provider_paths_coverage_check four_way_sync_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1; echo "$g rc=$?"
done

# L4 全量（S28 窗口用逐门独立进程；平静窗口可直接跑 scan.sh）
sh /tmp/run_gates3.sh 1 110        # 110/110 rc=0
python3 -u /tmp/t10_run_fns.py .   # test_scan 46 函数 → PASS=344 FAIL=0
```

## §9 一句话结论

改了 2 门（`open_url_broker_scheme_check` / `openminis_compat_field_parity_check`）、13 条见证
（dataflow 4 · family-hole 4 · completeness 2 · anchor 1 · parser-death 2）从 `BLIND_CONFIRMED` 全部变
**CAUGHT（13/13）**，生产代码 0 行，残留 8 条（R1–R8，均带触发条件/方案/理由），停在分支等 CI。
