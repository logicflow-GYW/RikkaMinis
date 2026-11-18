# FIX-1118B-C — 受保护区守卫全族（C1 C2 C4 C5 C6b C7 C8 C9）

- 分支：`fix/zone-guard-1118b`，基线 `059d27e8`，提交 `0707ebcf`
- 来源：`meta-audit/reports/r1118/Fix_1118_C_Report.md`；台账 `meta-audit/r1118/TRIAGE.md` C 节
- C7 由用户 2026-11-18 拍板选方案 C（独立开关）

---

## 1 缺陷（先证，按语义不按报告行号）

| ID | 结论 | 证据 |
|---|---|---|
| **C9** | **成立，P0**。`minis-fastio rm` 对受保护区零保护 | 实测 `minis-fastio rm -r /var/minis/shared/__fastio_probe__` → 删除成功、退出 0、零拒绝。`rmRefusal` 原有四条检查（external mount / bind root / not found / is_directory）没有一条是 zone。且 `ProtectedZoneGuard` 的 KDoc 明写「fastio 的 rm 是拼不过的那道」——注释承诺了不存在的保护 |
| **C7** | **成立，P0 级防护缺失**。守卫骑在 auto-approve 上，开关一开防护率 0% | 原接线 `if (!autoApprove) 拒绝 else 记录放行`。auto-approve 恰恰是「让删除不再弹窗」的开关，于是最需要保护的时刻（大量删除放行时）守卫退化为纯日志旁路 |
| **C5** | **成立**。`mv` 是死代码 | `isRecursive(verb, flags)` 只对 `rmdir`/`shred` 或 flag 命中生效，`mv` 无 `-r` 概念 → 恒 false → `mv <zone>/<child> /tmp/` 直接放行。实测 `mv /var/minis/shared/__probe__ /tmp/` 未拦截 |
| **C2** | **成立，四类写法全部绕过** | ① `busybox rm -rf` — `busybox` 不在 WRAPPERS，被当成 verb；② `rm -rfv` — `RECURSIVE_FLAGS` 是枚举集合，`-rfv` 不在其中；③ `rm -rf "<zone>"` — `absoluteGuestPath` 见 `"` 即返回 null；④ `env -i rm` — wrapper 后跟 `-i` 时 `-i` 被当成 verb |
| **C4** | **成立**。cwd 静默错判 | `ExecutionCoordinator` 调 `wideDeleteTarget(command)` 不传 cwd，默认 `/root`。而 shell 的真实 cwd 在 guest 里（proot `-w /root` + 自己写的 `cd`-trap 文件），app 进程读不到 → `rm -rf work` 恒被判成 `/root/work`，shell 实际站在 zone 里时是**静默漏放** |
| **C1** | **成立，但方向要收窄** | 威胁模型 KDoc 与实际拦截面不一致（实测动机是 `rm -v /var/minis/shared/README.md` 单文件，守卫放行）。**不改单文件放行行为**（那是写明的取舍），只把威胁模型写准 + 拒绝文案在无指代清空时提示确认 |
| **C6b** | **成立** | 拒绝文案把 `(Turn on auto-approve in Settings → Permissions to skip this check.)` 直接送进模型上下文，诱导模型建议用户关保护 |
| **C8** | **成立** | `mv`/`rmdir`/`shred`/`unlink`/`pushd` 零用例；`--`、`/bin/rm`、`ls && rm`、`..` 未覆盖 |
| **C6 前半** | **证伪，不改** | `CommandResult(output, -1, 0, truncated=true)` 是既有早退约定（`preExecReject` / `System busy` 同形），不是缺陷 |

**这改变什么用户行为？**
1. 开着免确认模式时，整目录删受保护区不再被放过（C7 核心）；
2. `mv` 把 zone 里的东西搬走会被拒（C5）；
3. `rm -rf <相对名>` 在 shell cwd 不可见时会被拒，并提示改用绝对路径（C4，原来是静默按 `/root` 放行）；
4. 设置页多一个默认开启的开关，文案写明与免确认模式相互独立（C7 UI）。

---

## 2 修法

### C9 + C5 — 两条路径共用一个判据

新增纯谓词 `ProtectedZoneGuard.isRefusedDelete(guestPath, recursive)`，shell 侧与 fastio 侧都调它，**不复制 zone 列表**。

`FastioOffloadHandler.rmRefusal` 加第五道检查（排在 `not_found` 之前，理由：宽删除「因为它是 zone」而被拒，与该目录当前是否存在无关）；`transferRefusal` 对 `mv` 的 **源** 用 `recursive = true` 判定。目标不判——`mv x /var/minis/shared` 是把 x 放进 zone，没删任何东西。

### C7 — 独立开关

新建 `sandbox/ZoneDeleteGuardStore.kt`，与 `AutoApproveStore` 同构（StateFlow + 私有 SharedPreferences + pre-init 排队），**默认 ON**，**刻意不注册进 `ConfigRegistry`**（否则 agent 被拒后能用 `minis-config` 关掉自己的守卫）。

接线：`if (wideHit != null) { if (ZoneDeleteGuardStore.isEnabled) 拒绝 else 记录 zone-delete-allowed }`。`AutoApproveStore` 从守卫逻辑里彻底摘除——`refuseReason` 的签名从 `(String, String, boolean)` 变成 `(String, String?)`，并用反射钉死（多一个 boolean 就会红）。

`init` 放在 `ExecutionCoordinator.init` 而非 `MinisApp`：两个读者都在这个 init 后面，且没跑 init 时 `isEnabled` 返回安全默认（开）。

### C4 — cwd 未知就承认未知

`wideDeleteHit(command, cwd: String?)`，`cwd = null` 表示不可见。绝对目标照常判定（`cwdKnown = true`）；相对目标遍历「可能让它变宽」的基准（五个 zone + `/var/minis`），命中即以 `cwdKnown = false` 拒绝，文案提示用绝对路径。

### C2 — 四类写法

① `busybox` 进 WRAPPERS，且 wrapper 比对走 `substringAfterLast('/')`（`/usr/bin/busybox` 同效）；② 递归 flag 改判「短选项簇里含 r/R」，长选项仍字面匹配（`--force` 含 r 但不是递归）；③ `unquote` 先剥一对引号再判字面量；④ wrapper 后跟 `-` 开头的选项时跳过，值型选项（`-u`/`--unset`/`-C`/`-S`）连值一起跳。

### C1 / C6b — 文案

KDoc 重写：明说拦的是容器不是文件，并逐条列出不在拦截面内的三种形状（单文件、glob、深层目录）。拒绝文案删掉「去设置里关掉」的指引，改为「说出要删哪些文件」；zone 根被整删时追加「先确认用户指的是哪些文件」；cwd 未知时追加「改用绝对路径」。

---

## 3 证据（测试）

`ProtectedZoneGuardTest` + `ZoneDeleteGuardStoreTest` + `FastioZoneGuardTest`，**36 条全绿**（`OK (36 tests)`）。

| 覆盖 | 用例 |
|---|---|
| C9 接线 | `fastio rm refuses a wide delete inside a protected zone`、`fastio mv refuses a wide source inside a protected zone`、`both fastio paths record the zone verdict` |
| C7 不可达 | `the zone switch is not reachable from minis-config` |
| C7 默认值 | `the default is protection on`、`an unset preference reads as on`、`toggling off persists and publishes` |
| C7 纯函数化 | `the guard is a pure function of path and recursion`（含反射钉死 `refuseReason` 参数表） |
| C5 mv | `rmdir shred and mv are recognised as tree removal`、`moving a single file out of a zone is refused too, and the cost is documented` |
| C2 busybox | `busybox prefix does not hide the verb` |
| C2 组合 flag | `combined short flags still count as recursive`（含反例 `rm --force <file>` 放行） |
| C2 引号 | `quoted targets are unquoted before judging` |
| C2 wrapper 选项 | `wrapper options are skipped before the verb` |
| C4 cwd | `a known cwd resolves a relative target`、`an unknown cwd fails closed on a relative wide target`、`an unknown cwd does not refuse a relative deep target` |
| C8 覆盖缺口 | `separators and absolute binary paths do not hide the delete`、`dot-dot is resolved before judging`、`pushd is tracked like cd` |
| C1 威胁模型 | `deleting files inside a zone is allowed`（含 `unlink` 单文件）、`non-literal targets fall through by design` |
| C6b 文案 | `the refusal names the target and does not point at a way around it`（断言不含 `auto-approve` / `Settings`） |

fastio 侧四条接线探针各带**负控**：删掉 zone 检查、把开关换成 `AutoApproveStore`、把 zone 检查移到 `not_found` 之后、把 `zone-delete-allowed` 改名、往 ConfigRegistry 里加一句——五种变异各自让对应探针失败。

**跑过的门**（全部 rc=0）：`i18n_check`（1686 定义 / 1678 引用，无孤儿；新增 4 个 key 只有 5 个语种缺翻译 = WARNING 级）、`auto_approve_bypass_check`、`offload_permission_reset_sync_check`、`offload_cli_args_check`、`fastio_tar_bounds_check`、`readonly_mount_write_guard`、`exec_gate_release_check`、`outcome_known_chain_check`、`process_logging_gate`、`liveness_static_guard`。

---

## 4 变异（11 臂，全部 RED-PRECISE）

装置 `shared/work/r1118b/zone-guard-1118b-jvm/{run.sh,mutate.py}`。纪律：快照 → 变异 → 跑 → `finally` 还原 → **sha256 逐字节比对**，禁 `git checkout`。

| 臂 | 变异 | 变红的用例 |
|---|---|---|
| M1 | `mv` 不再视为递归 | `rmdir shred and mv…`、`moving a single file out…` |
| M2 | 递归 flag 回退成枚举集合 | `combined short flags still count as recursive` |
| M3 | WRAPPERS 去掉 `busybox` | `busybox prefix does not hide the verb`、`separators and absolute binary paths…` |
| M4 | 不剥引号 | `quoted targets are unquoted before judging` |
| M5 | 不跳 wrapper 的值型选项 | `wrapper options are skipped before the verb` |
| M6 | `cwd ?: return null` 改成 `?: "/root"` | `relative and dotted paths resolve before judging` |
| M11 | 候选基准集合置空 | `an unknown cwd fails closed on a relative wide target` |
| M7 | `recursive &&` 去掉 | `the guard is a pure function of path and recursion` |
| M8 | 拒绝文案改成 `retry later.` | `the refusal names the target and does not point at a way around it` |
| M9 | `DEFAULT_ENABLED` 改 false | `the default is protection on`、`an unset preference reads as on` |
| M10 | fastio 的 zone 检查短路成 `false &&` | `negative control - dropping the zone check from rm…`、`fastio rm refuses a wide delete…` |

**M11 抓到一处真缺陷**：带引号的绝对路径 `rm -rf "/var/minis/shared"` 走进「猜基准」分支，于是被判成 `cwdKnown = false`，拒绝文案里出现「改用绝对路径」——而它本来就是绝对路径。修法：抽出 `unquote` 与 `isAbsoluteLiteral`，剥引号后再判是否绝对，两个判断共用同一个函数，不可能再分叉。

**M6 首轮是 RED-IMPRECISE**（红的是 `relative and dotted paths` 而不是我预期的 `unknown cwd fails closed`）。查明是我预期写错而非测试有洞：`cwd ?: "/root"` 直接证伪的是 `absoluteGuestPath(relative, null)` 那条断言；`unknown cwd fails closed` 覆盖的是同决策的另一半（候选基准扫描），由 M11 单独钉。预期表改准后两臂各自精确。

---

## 5 CI

- 分支 `fix/zone-guard-1118b`，最终提交 `c7e1ba09eb88e8385b151f9da86df4f4bbce0a42`
- 远端 `refs/heads/fix/zone-guard-1118b` = `c7e1ba09eb88e8385b151f9da86df4f4bbce0a42`（逐字符一致，run 的 `head_sha` 也是同一个）
- dispatch：`gh-actions-dispatch --workflow build-apk.yml --ref fix/zone-guard-1118b`

**run #3263 / `44324319915` @ `c7e1ba09`：五个 job 全 success**

| job | 结论 | 证据 |
|---|---|---|
| unit-tests | success | **9664 PASSED / 0 FAILED**；`ProtectedZoneGuardTest` 23 条、`ZoneDeleteGuardStoreTest` 3 条、`FastioZoneGuardTest` 10 条（含 5 条负控）逐条在列 |
| scan-gate | success | **115 passed, 0 failed**（`scripts/scan/**` 未改 ⇒ 夹具按机制跳过，本次绿不含夹具证据） |
| lint | success | 首轮 `0707ebcf` 在这里红过（见下） |
| build-apk | success | |
| publish | success，但「Move the rolling tag」/「Publish to Releases」**skipped** | main 门控生效，分支未污染 release |

**首轮 #3261 的 lint 红是真错，不是门抽风**：
```
values/strings.xml:1822: Error: "perm_section_zone_delete_guard" is not translated
in "de", "ru", "ko", "ja" [MissingTranslation]
```
lint 把 `MissingTranslation` 当 **error**（不是 i18n 扫描器那种 WARNING），而我只补了 en/zh。修法：四个 key 补进 de/ru/ko/ja/zh-rTW 五个语种（与相邻的 auto-approve 区块一致——那一块是全语种齐的），`i18n_check.py` 复查 `ALL CLEAN`。
**可复用**：新增 string key 必须一次补全 7 个语种，`i18n_check.py` 的 WARNING 不代表 CI 会放过；两套检查口径不同，别拿前者当后者。

---

## 6 残留（登记不修）

| 项 | 为什么不修 |
|---|---|
| glob（`rm -rf /var/minis/shared/*`）不拦 | 词法层无法把 glob 展开成确定的路径集合。拦它就得拒绝一切含 `*` 的命令，误伤面远大于收益。fastio 侧（路径已解析）能覆盖走 fastio 的同类删除 |
| `$VAR` 目标、`find -delete`、`xargs rm`、`python3 -c shutil.rmtree` 不拦 | 同上，值不可见。这是写明的天花板，测试里以 `non-literal targets fall through by design` 钉住，防止有人「顺手」把它改成 fail-closed 而误伤正常清理 |
| 单文件删除放行 | C1 明确要求不改。备份覆盖这一格 |
| `mv <zone>/<单文件>` 被误拒 | C5 写明的取舍：词法层分不清文件与目录，误拒代价（agent 换 `cp` + `rm`）远小于漏放整目录 |
| 相对路径在 cwd 未知时对「深层但恰好从 zone 起算就宽」的 token 可能误拒 | 只对单段 token 触发（多段必然不是一级子目录），且提示语给出确定解法。等 `PersistentShell` 真的暴露 cwd 后可收窄 |
| `ZoneDeleteGuardStore` 不在 `ConfigRegistry` ⇒ 备份/恢复不会带它 | 有意为之：能进备份就能被 `minis-config` 间接写。用户明确要求「agent 关不掉」。代价是恢复默认后需要手动确认开关状态 |

---

## 7 复现（真机）

1. 打开「设置 → 权限 → 受保护文件夹」，确认开关为**开**；
2. `shell_execute: rm -rf /var/minis/shared/work` → 应返回 `refusing to delete '/var/minis/shared/work': it is a protected zone…`，且 `MemorySpikeRecorder` 日志出现 `refuse-zone`；
3. `minis-fastio rm -r /var/minis/shared/work` → 应返回 `{"error":"protected_zone"}`，exit 1（修复前：删除成功、exit 0）；
4. `minis-fastio mv /var/minis/shared/work /tmp/x` → 应返回 `protected_zone`（修复前：实际搬走）；
5. `rm -rf /var/minis/shared/work/prompteval-1118` → 照常执行（深层目录不受影响）；
6. 打开「免确认模式」后再跑第 2 步 → **仍应被拒**（修复前：放行并只记日志）；
7. 关掉「受保护文件夹」开关后再跑第 2 步 → 放行，日志出现 `zone-delete-allowed`。

---

## 8 判级

| ID | 判级 | 依据 |
|---|---|---|
| C9 | **P0** | 一条命令删掉整个 `shared/`，无任何提示；且 KDoc 声称有保护，属「防护宣称与实际不符」，比没有更危险（会让人不做别的兜底） |
| C7 | **P0** | 开关一开防护率归零，而那个开关恰恰是用户为了减少打扰打开的，与「放弃数据保护」无关 |
| C5 | **P1** | 整目录被搬走，但需要 agent 主动用 `mv`；不静默、结果可见 |
| C4 | **P1** | 静默漏放（相对路径），触发需要 shell 站在 zone 里且用相对路径；不是数据损坏路径 |
| C2 | **P1** | 四类自然写法绕过，其中 `busybox` / 组合 flag 是 agent 真实会写的形态 |
| C1 | **P2** | 文档与文案不一致，无行为改变 |
| C6b | **P2** | 文案诱导，无直接数据风险 |
| C8 | **P2** | 覆盖缺口（不是缺陷本身），补齐后才有变异见证 |
| C6 前半 | **P3（证伪）** | 既有约定，不是缺陷 |
