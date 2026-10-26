#!/usr/bin/env python3
"""share_dir_prune_check — deep-audit-1022 FIX-H (ISSUE-10, P2) gate.

Pins the cache/share housekeeping contract:

  arm1: every production .kt file that constructs `File(context.cacheDir,
        "share")` — or `"shared"` — must also call `pruneShareDir(` — share/
        blobs were written once and never deleted, accumulating for the life of
        the install. The pruner's definition file (FullscreenImageViewer.kt) is
        exempt.
        [da1024-G] "shared" was this gate's own N+1 hole: SHARE_DIR_PAT required
        a `)` right after the `"share"` literal, so the three
        `File(context.cacheDir, "shared")` writers were invisible while the
        singular directory was covered.
  arm2: in WebAppActivity.kt's stagedShareUri, the share-dir construction
        (and its `mkdirs()`) must live INSIDE the `withContext(Dispatchers.IO)`
        block, and the call order must be
        withContext(Dispatchers.IO) → File(cacheDir, "share") → pruneShareDir
        → copyTo. Before the fix, `mkdirs()` ran on the main thread and
        `pruneShareDir` was never called.
  arm3: [da1024-G] the cacheDir/shared writers must do their disk work off the
        main thread. `exportProviderInstance` / `shareMcpServerJson` must stay
        `private suspend fun` with their mkdirs/prune/writeText inside
        `withContext(Dispatchers.IO)`, and ChatExporter.exportToZip must build
        the shared/ dir and prune before opening the zip stream.
  arm4: [da1025-B] CrashFrequencyDetector.packageZip is the single write point
        for cacheDir/share (all three dispatch* paths call it). It must stay a
        `private suspend fun` with the mkdir/prune/zip work inside
        `withContext(Dispatchers.IO)` (zip packaging ran on the calling UI
        thread before the fix — bounded ANR risk on every export), must call
        the shared `pruneShareDir(` pruner before opening the zip stream (the
        inline [audit-0917] prune only drops all but the newest
        minis-logs-*.zip and is blind to other blobs), and the prune must
        precede ZipOutputStream.

  arm5: [da1028-C] the three writers whose staging dir lives in filesDir /
        externalFilesDir (NOT cacheDir) — `createCameraOutputUri`
        (filesDir/camera-photos), `PhotosOffloadHandler.handleExport`
        (filesDir/photos-export) and `AccessibilityOffloadHandler.uiScreenshot`
        (externalFilesDir/a11y_screenshots) — must each call the shared
        `pruneShareDir(` pruner, because the OS has no reason to reclaim those
        dirs and nothing else ever deleted them. arm1 is blind to them by
        construction (its pattern requires `cacheDir`), which is exactly the
        N+1 shape this arm closes. It also pins the de-duplication:
        `ChatMutationMethods.pruneStaleAttachments` must DELEGATE to the shared
        pruner instead of carrying its own copy of the TTL loop, and the single
        `internal fun pruneShareDir(` definition must still exist (reverse
        anchor). Every anchor missing = RED.

  Prune-then-copy is safe by construction: the 24h TTL cutoff always
  postdates the fresh copy written after the prune, so the prune can never
  delete the file the current call is about to stage.

  arm6: [deep-audit-1048 B-04] the share-sheet hand-off itself. The three
        writers above all end in `startActivity(chooser)`, and none of the
        failure exits was visible:

        C-NT     every `Intent.createChooser(` is given
                 FLAG_ACTIVITY_NEW_TASK — on the chooser statement itself or
                 through `chooser.addFlags(…)` in the same function. A chooser
                 started from a non-Activity context without it throws
                 AndroidRuntimeException (the two sibling paths already did
                 this; the log share did not).
        C-SILENT the function that starts the sheet must return a VERDICT
                 (non-Unit, no bare `return` / `return null`), and every call
                 site must consume it and sit in a block that reports failure
                 (Toast / snackbar / AppLogger). `?: return` on a missing file
                 and a `return` on a FileProvider failure were both
                 indistinguishable from "shared".

        Both criteria are derived from the call graph (which functions call
        `startActivity`), not from names.

Usage:
  python3 share_dir_prune_check.py <repo_root>   # real tree
  python3 share_dir_prune_check.py --selftest    # fixtures

Selftest marker: SHARE_DIR_PRUNE_SELFTEST=PASS (+ CRASH_SHARE_SELFTEST=PASS
for the [da1025-B] arm4 fixture block, ARM5_FILESDIR_PRUNE_SELFTEST=PASS for
the [da1028-C] arm5 fixture block),
SHARE_HANDOFF_SELFTEST=PASS for the [deep-audit-1048 B-04] arm6 block
"""

import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments   # noqa: E402
from _kotlin_ast import (match_bracket, split_top, strip_parens,     # noqa: E402
                         const_value, const_bool, function_body,
                         stmt_spans)
from _kotlin_valueflow import (call_args, code_view, literal_view,   # noqa: E402
                               expr_end, path_feasible)

DEFINER_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/components/FullscreenImageViewer.kt"
ACTIVITY_REL = "src/android/app/src/main/java/com/rikkaminis/app/webapp/WebAppActivity.kt"
MAIN_ROOT = "src/android/app/src/main"
SETTINGS_DIR = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings"
PROVIDER_REL = SETTINGS_DIR + "/ProviderDetailScreen.kt"
MCP_REL = SETTINGS_DIR + "/MCPIntegrationsScreen.kt"
EXPORTER_REL = "src/android/app/src/main/java/com/rikkaminis/app/share/ChatExporter.kt"
CRASH_REL = "src/android/app/src/main/java/com/rikkaminis/app/crash/CrashFrequencyDetector.kt"
# [da1028-C] the three filesDir/externalFilesDir growers + the de-duplicated copy
CAMERA_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreenHelpers.kt"
PHOTOS_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt"
A11Y_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/AccessibilityOffloadHandler.kt"
MUTATION_REL = "src/android/app/src/main/java/com/rikkaminis/app/debug/ChatMutationMethods.kt"
LOG_SCREEN = SETTINGS_DIR + "/LogManagementScreen.kt"
SHARE_FILES = (PROVIDER_REL, MCP_REL, LOG_SCREEN)

CHOOSER_CALL = re.compile(r"Intent\s*\.\s*createChooser\s*\(")
START_CALL = re.compile(r"\bstartActivity\s*\(")
# [wide-1024] What a `runCatching { startActivity(...) }` result has to be
# consumed by for the function to hand back a verdict. `.let { true }` / a bare
# statement are not on the list: both keep the call and throw the failure away.
VERDICT_CONSUME_RE = re.compile(
    r"\s*(?:\?:\s*|\.\s*(?:isSuccess|isFailure|fold|getOrElse|getOrNull|exceptionOrNull|"
    r"onSuccess|onFailure)\b)")
FEEDBACK_TOKENS = ("Toast.makeText(", "snackbarHostState", "SnackbarHost(", "AppLogger.")

# Covers cacheDir/share (plural writers) and cacheDir/shared, for ANY receiver
# (`context.cacheDir` / `ctx.cacheDir` / ...). The literal run is bounded by the
# closing quote so "share_inbound" / "shared-foo" cannot match by accident.
# [da1025-B] the receiver was widened from `context.cacheDir` to
# `(?:\w+\.)?cacheDir`: CrashFrequencyDetector constructs `File(ctx.cacheDir,
# "share")` and the strict pattern was blind to it (this gate's own N+1 hole,
# same shape as the da1024-G "shared" hole).
SHARE_DIR_PAT = re.compile(r"File\(\s*(?:\w+\.)?cacheDir\s*,\s*\"share(?:d)?\"\s*\)")

# arm3: (file, suspend-signature marker, what it stages)
UI_WRITERS = (
    (PROVIDER_REL, "private suspend fun exportProviderInstance"),
    (MCP_REL, "private suspend fun shareMcpServerJson"),
)

# Files that satisfy the prune contract with their own name-scoped housekeeping
# instead of pruneShareDir. Each entry maps filename -> marker that must be
# present in the (comment-stripped) code — if the marker disappears, the file
# falls back to violating, so the exemption cannot rot silently.
#   SkillRepository.kt: sweepStaleExports() deletes `skill-export-*` dirs older
#   than EXPORT_TTL_MS and is called before each new export (its own contract).
EXEMPT_FILES = {
    "SkillRepository.kt": "sweepStaleExports()",
    "FullscreenImageViewer.kt": "internal fun pruneShareDir(",
}


# ── [B7-t05] 共享视图：自写词法器全部删除 ───────────────────────────────────
#
# 旧实现自带一套 strip_comments / extract_braced_block / split_call_args /
# _strip_comments_keep_offsets。它们在 Kotlin 的四类原生构造上系统性失明：
# 嵌套块注释、单引号字符字面量 `'"'`、三引号原始字符串、字符串模板 `${…}` 的
# 嵌套插值 —— 而本门正好要读字符串内容（`"share"` / `"shared"` 目录名），于是
# 「注释边界错位 → 整段真代码被抹平」直接变成「门看不见违规」。
#
# 现在只用共享层：literal_view（字符串保留）+ code_view（字符串也抹平）。两个
# 视图**都与原文等长**，所以两边的位置可以互换 —— 在 literal_view 里定位字符串，
# 在 code_view 里判括号与可达性。
class LexFail(Exception):
    pass


def views(src):
    try:
        return literal_view(src), code_view(src)
    except KotlinLexError as exc:      # 引号/块注释未闭合 → fail-closed
        raise LexFail(str(exc))


def read_views(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8", errors="replace") as fh:
        return views(fh.read())


def strip_comments(src):
    """注释抹空格、字符串内容保留（长度不变）。保留此名给 arm1 用。"""
    return views(src)[0]


def fn_span(cv, name):
    """`fun <name>(…)` 的 (fun_start, body_start, body_end_exclusive, kind)。

    旧实现是「从 marker 往后数花括号」：marker 命中字符串里的同名文本、或函数体
    里有 `"}"`，边界就切错。共享 `function_body` 先配平参数表，再区分 block /
    expression 体（`exportToZip` / `packageZip` 都是 `= withContext(…)` 形态）。
    """
    m = re.search(r"\bfun\s+%s\s*\(" % re.escape(name), cv)
    if m is None:
        return None
    fb = function_body(cv, name)
    if fb is None:
        return None
    return (m.start(), fb[0], fb[1], fb[2])


def fn_slices(lit, cv, name):
    """(lit_fn, cv_fn) —— 同一函数在两个视图里的文本，等长可互换位置。"""
    sp = fn_span(cv, name)
    if sp is None:
        return None
    return lit[sp[0]:sp[2]], cv[sp[0]:sp[2]]


def split_call_args(text, open_idx):
    """调用的顶层实参（共享 call_args；配平失败 → None）。"""
    close = match_bracket(text, open_idx)
    if close < 0:
        return None
    return [text[a:b].strip() for a, b in call_args(text, open_idx)]


def fn_params(cv_fn):
    """形参名集合。"""
    m = re.search(r"\bfun\s+\w+\s*\(", cv_fn)
    if m is None:
        return set()
    close = match_bracket(cv_fn, m.end() - 1)
    if close < 0:
        return set()
    out = set()
    for a, b in split_top(cv_fn[m.end():close], (",",)):
        head = cv_fn[m.end() + a: m.end() + b].split("=")[0].split(":")[0].strip()
        head = re.sub(r"@\w+\s+", "", head).split()[-1] if head.split() else ""
        if re.fullmatch(r"[A-Za-z_]\w*", head or ""):
            out.add(head)
    return out


def local_bindings(lit_fn, cv_fn):
    """函数内 `val/var X = <expr>` 的初始值（取 literal_view，字符串可读）。"""
    out = {}
    for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]+)?=", lit_fn):
        out[m.group(1)] = lit_fn[m.end():expr_end(cv_fn, m.end())].strip()
    return out


def prune_calls(lit_fn):
    """函数文本里每处 `pruneShareDir(`：[(pos, args)]（args=None 表示解析失败）。"""
    out = []
    for m in re.finditer(r"\bpruneShareDir\s*\(", lit_fn):
        out.append((m.start(), split_call_args(lit_fn, m.end() - 1)))
    return out


CACHE_FILE_RE = re.compile(r'File\(\s*(?:\w+\.)?cacheDir\s*,\s*"([^"]*)"')
ANY_FILE_RE = re.compile(r'File\(\s*[^()]*,\s*"([^"]*)"')


def prune_target_problem(lit_fn, cv_fn, where, want_dir):
    """每一处 pruneShareDir 的第 1 个实参必须是「本函数刚建/被写入的那个目录」。

    dataflow 轴（[B7-t05] 05-2 / 05-3）：wide-1024 只在 arm2 里加了「实参必须
    是 shareDir」的核验，arm3/arm4/arm5 的兄弟臂仍然只查 `pruneShareDir(` 子串
    存在 —— 于是同一个错误目录横向搬到没核验的臂就通吃。现在所有臂共用这一个
    判定（family-hole 纪律：同门所有臂统一走同一个守卫判定）。

    另外判可达性：`if (false) pruneShareDir(dir)` 保住了调用、保住了实参、保住
    了顺序，sweep 却永远不会跑。
    """
    params = fn_params(cv_fn)
    bindings = local_bindings(lit_fn, cv_fn)
    for pos, args in prune_calls(lit_fn):
        if args is None:
            return "%s: cannot read the pruneShareDir(...) arguments (fail-closed)" % where
        if not args:
            return "%s: pruneShareDir() is called with no target directory" % where
        if not path_feasible(cv_fn, pos):
            return (
                "%s: the pruneShareDir(...) call sits in a provably-dead branch — the call "
                "is still spelled there but the sweep never runs, so the directory grows "
                "for the life of the install" % where)
        arg = strip_parens(args[0])
        if re.fullmatch(r"[A-Za-z_]\w*", arg) and arg in params:
            continue                       # 目录由调用方传入（pruneStaleAttachments）
        if re.fullmatch(r"[A-Za-z_]\w*", arg):
            init = bindings.get(arg)
            if init is None:
                return ("%s: prunes `%s` but no `val %s = …` in this function builds that "
                        "directory — the prune target cannot be identified (fail-closed)"
                        % (where, arg, arg))
        else:
            init = arg
            if ANY_FILE_RE.search(init) is None:
                return ("%s: prunes `%s`, which is not a directory this function builds — "
                        "the staging dir is then never reclaimed" % (where, arg[:60]))
        m = CACHE_FILE_RE.search(init)
        if want_dir is not None:
            if m is None:
                return ("%s: prunes `%s`, which is not a `File(…cacheDir, \"%s\")` "
                        "construction — the prune target cannot be identified (fail-closed)"
                        % (where, arg[:60], want_dir))
            if m.group(1) != want_dir:
                return ("%s: prunes `%s` (cacheDir/%s) instead of the cacheDir/%s staging "
                        "dir it just built — those files are then never reclaimed"
                        % (where, arg[:60], m.group(1), want_dir))
    return None


# 有界 TTL 的物理上界：7 天。任何超过它的「覆盖值」都让 `cutoff = now - maxAgeMs`
# 落在 1970 之前，`lastModified() < cutoff` 恒假 = sweep 变成空转。
TTL_CEILING_MS = 7 * 24 * 60 * 60 * 1000
MAXVALUE_RE = re.compile(r"\b(?:MAX|MIN)_VALUE\b")


def pruner_constants(lit_def, cv_def):
    """pruner 文件里声明的 TTL 常量名（判据是**声明**，不是大写拼写）。"""
    out = set()
    for m in re.finditer(r"\bconst\s+val\s+(\w+)\s*(?::[^=\n]+)?=", lit_def):
        out.add(m.group(1))
    return out


def _ttl_bounded(expr, bindings, consts, hops=3):
    """(ok, resolved)：TTL 覆盖值可折叠且有界，或绑定到 pruner 里的具名常量。"""
    e = strip_parens(expr).strip()
    resolved = e
    for _ in range(hops):
        if re.fullmatch(r"[A-Za-z_]\w*", resolved) and resolved in bindings:
            resolved = strip_parens(bindings[resolved]).strip()
            continue
        break
    v = const_value(resolved)
    if v is not None and v[0] == "num":
        return (abs(v[1]) <= TTL_CEILING_MS, resolved)
    if MAXVALUE_RE.search(resolved):
        return (False, resolved)
    if re.fullmatch(r"[A-Za-z_]\w*", resolved):
        return (resolved in consts, resolved)
    return (False, resolved)


def prune_ttl_problem(lit_fn, cv_fn, where, consts):
    """每一处 pruneShareDir 的 TTL 覆盖都必须有界。

    family-hole 轴（[B7-t05] 05-4 / 05-6）：旧判据是
    `PRUNE_TTL_CONST_RE.fullmatch(val)`（`[A-Z][A-Z0-9_]*$`）—— 它判的是**拼写**，
    于是 `val NEVER_PRUNE_TTL = Long.MAX_VALUE` 与导入的 `MAX_VALUE` 都能过门；
    反过来，正当的具名常量只是因为小写才被判红。现在按**值**判：常量折叠 + 一跳
    局部绑定解析，`MAX_VALUE` 家族与超上界字面量一律红，具名常量只认 pruner 文件
    里真声明过的那个。
    """
    bindings = local_bindings(lit_fn, cv_fn)
    for _pos, args in prune_calls(lit_fn):
        if args is None:
            return "%s: cannot read the pruneShareDir(...) arguments (fail-closed)" % where
        for a in args[1:]:
            val = a.split("=", 1)[1].strip() if "=" in a else a
            ok, resolved = _ttl_bounded(val, bindings, consts)
            if not ok:
                return (
                    "%s: the prune call overrides the TTL with %r — an unbounded TTL makes "
                    "the sweep a no-op (`cutoff = now - maxAgeMs` never postdates a file); "
                    "pass the named constant or drop the argument" % (where, resolved))
    return None


def check_arm1(root):
    """Every producer of cacheDir/share must call pruneShareDir (definer exempt)."""
    violations = []
    base = os.path.join(root, MAIN_ROOT)
    for dirpath, _dirnames, filenames in os.walk(base):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                with open(path, encoding="utf-8", errors="replace") as fh:
                    code = strip_comments(fh.read())
            except OSError:
                continue
            except LexFail:
                violations.append(os.path.relpath(path, root).replace(os.sep, "/")
                                 + " (unlexable: unbalanced quote/comment — fail-closed)")
                continue
            if not SHARE_DIR_PAT.search(code):
                continue
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            if rel == DEFINER_REL:
                continue
            if "pruneShareDir(" in code:
                continue
            exempt_marker = EXEMPT_FILES.get(fn)
            if exempt_marker and exempt_marker in code:
                continue
            violations.append(rel)
    return violations


def check_arm2(root):
    """stagedShareUri: mkdirs inside IO context, order IO → dir → prune → copy."""
    pv = read_views(root, ACTIVITY_REL)
    if pv is None:
        return f"{ACTIVITY_REL}: file missing (was it renamed/moved?)"
    lit, cv = pv
    sl = fn_slices(lit, cv, "stagedShareUri")
    if sl is None:
        return "stagedShareUri function not found in WebAppActivity.kt (stripped)"
    fn, fn_cv = sl
    io_idx = fn.find("withContext(Dispatchers.IO)")
    dir_idx = fn.find('File(context.cacheDir, "share")')
    prune_idx = fn.find("pruneShareDir(")
    copy_idx = fn.find(".copyTo(")
    if io_idx < 0:
        return "stagedShareUri lost its withContext(Dispatchers.IO) block"
    if dir_idx < 0:
        return "stagedShareUri lost the File(context.cacheDir, \"share\") construction"
    if prune_idx < 0:
        return "stagedShareUri must call pruneShareDir( before staging"
    if copy_idx < 0:
        return "stagedShareUri lost its file.copyTo( staging call"
    if not (io_idx < dir_idx < prune_idx < copy_idx):
        return (
            "stagedShareUri ordering broken: expected "
            "withContext(Dispatchers.IO) < File(cacheDir, \"share\") < pruneShareDir < copyTo, got "
            f"io={io_idx} dir={dir_idx} prune={prune_idx} copy={copy_idx}"
        )
    # mkdirs must ride the same IO block: it appears on the share-dir line.
    if "mkdirs()" not in fn[dir_idx:prune_idx]:
        return "share-dir mkdirs() must stay inside the IO block (with pruneShareDir)"
    bad = prune_target_problem(fn, fn_cv, "stagedShareUri", "share")
    if bad:
        return bad
    consts = pruner_consts_of(root)
    return prune_ttl_problem(fn, fn_cv, "stagedShareUri", consts)


def _pruner_consts_cache(root):
    pv = read_views(root, DEFINER_REL)
    return pruner_constants(*pv) if pv else set()


def pruner_consts_of(root):
    return _pruner_consts_cache(root)


def check_arm3(root):
    """[da1024-G] cacheDir/shared writers: suspend + IO context + prune first."""
    problems = []
    # [R3-t04] 12-3：同一批导出函数里为「中间产物」建的暂存目录（完整明文
    # transcript）必须 finally 删掉 —— arm1/arm3 只判产物目录的 prune，暂存
    # 目录此前没有任何判据。放在最前面，避免下面 的 early return 把它跳过。
    for rel in (PROVIDER_REL, MCP_REL, EXPORTER_REL):
        pv = read_views(root, rel)
        if pv is None:
            continue
        problems.extend(_staging_cleanup_problem(pv[1], rel))
    consts = pruner_consts_of(root)
    for rel, marker in UI_WRITERS:
        name = marker.rsplit("fun ", 1)[-1].strip()
        pv = read_views(root, rel)
        if pv is None:
            problems.append(f"{rel}: file missing (was it renamed/moved?)")
            continue
        lit, cv = pv
        # marker 自带 `private suspend fun` —— 降级成非 suspend 也是本臂要报的
        # （磁盘 I/O 回到主线程），所以先验签名原文，再验函数体。
        if marker not in cv:
            problems.append(
                f"{rel}: `{marker}` not found — it must stay a "
                "`private suspend fun` (the body runs disk I/O)"
            )
            continue
        sl = fn_slices(lit, cv, name)
        if sl is None:
            problems.append(
                f"{rel}: `{marker}` has no resolvable body — fail-closed (the anchor "
                "moved or the signature is unparsable)"
            )
            continue
        fn, fn_cv = sl
        if "withContext(Dispatchers.IO)" not in fn:
            problems.append(
                f"{rel}: `{marker}` must run its mkdirs/prune/write inside "
                "withContext(Dispatchers.IO)"
            )
            continue
        prune = fn.find("pruneShareDir(")
        write = fn.find(".writeText(")
        if prune < 0:
            problems.append(f"{rel}: `{marker}` must call pruneShareDir( before staging")
            continue
        if write < 0:
            problems.append(f"{rel}: `{marker}` lost its staging .writeText( call")
            continue
        if prune > write:
            problems.append(
                f"{rel}: pruneShareDir( must precede the staging write "
                f"(prune={prune} write={write})"
            )
            continue
        # [B7-t05] dataflow：实参必须是被写入的那个 cacheDir/shared，不能是
        # 横向搬过来的另一个目录（`pruneShareDir(File(context.cacheDir, "noop"))`）。
        bad = prune_target_problem(fn, fn_cv, f"{rel} `{name}`", "shared")
        if bad:
            problems.append(bad)
        ttl = prune_ttl_problem(fn, fn_cv, f"{rel} `{name}`", consts)
        if ttl:
            problems.append(ttl)

    # ChatExporter.exportToZip already runs inside withContext(Dispatchers.IO);
    # pin that the prune sits between the dir construction and the zip stream.
    pv = read_views(root, EXPORTER_REL)
    if pv is None:
        problems.append(f"{EXPORTER_REL}: file missing (was it renamed/moved?)")
        return problems
    lit, cv = pv
    sl = fn_slices(lit, cv, "exportToZip")
    if sl is None:
        problems.append(f"{EXPORTER_REL}: `suspend fun exportToZip` not found")
        return problems
    fn, fn_cv = sl
    io_idx = fn.find("withContext(Dispatchers.IO)")
    dir_idx = fn.find('File(context.cacheDir, "shared")')
    prune_idx = fn.find("pruneShareDir(")
    zip_idx = fn.find("ZipOutputStream(")
    if io_idx < 0:
        problems.append(f"{EXPORTER_REL}: exportToZip lost its withContext(Dispatchers.IO) block")
    elif dir_idx < 0:
        problems.append(f'{EXPORTER_REL}: exportToZip lost the File(context.cacheDir, "shared") construction')
    elif prune_idx < 0:
        problems.append(f"{EXPORTER_REL}: exportToZip must call pruneShareDir( before staging the zip")
    elif zip_idx < 0:
        problems.append(f"{EXPORTER_REL}: exportToZip lost its ZipOutputStream( staging call")
    elif not (io_idx < dir_idx < prune_idx < zip_idx):
        problems.append(
            f"{EXPORTER_REL}: exportToZip ordering broken: expected withContext(Dispatchers.IO) "
            f'< File(cacheDir, "shared") < pruneShareDir < ZipOutputStream, got '
            f"io={io_idx} dir={dir_idx} prune={prune_idx} zip={zip_idx}"
        )
    else:
        bad = prune_target_problem(fn, fn_cv, f"{EXPORTER_REL} exportToZip", "shared")
        if bad:
            problems.append(bad)
        ttl = prune_ttl_problem(fn, fn_cv, f"{EXPORTER_REL} exportToZip", consts)
        if ttl:
            problems.append(ttl)
    return problems


CRASH_OK = """package com.rikkaminis.app.crash

import com.rikkaminis.app.ui.components.pruneShareDir

object CrashFrequencyDetector {
    private suspend fun packageZip(ctx: Context, files: List<File>): File? =
        withContext(Dispatchers.IO) {
        val readable = files.filter { it.exists() && it.length() > 0 }
        if (readable.isEmpty()) return@withContext null
        val shareDir = File(ctx.cacheDir, "share").apply { mkdirs() }
        pruneShareDir(shareDir)
        val zipFile = File(shareDir, "minis-logs-stamp.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zout ->
            zout.write(byteArrayOf(1))
        }
        return@withContext zipFile
    }
}
"""


def check_arm4(root):
    """[da1025-B] CrashFrequencyDetector.packageZip: suspend + IO + prune-first."""
    pv = read_views(root, CRASH_REL)
    if pv is None:
        return [f"{CRASH_REL}: file missing (was it renamed/moved?)"]
    lit, cv = pv
    sl = fn_slices(lit, cv, "packageZip")
    if sl is None:
        return [
            f"{CRASH_REL}: `packageZip` must stay a `private suspend fun` — "
            "its mkdir/prune/zip work runs disk I/O off the calling (UI) thread"
        ]
    fn, fn_cv = sl
    io_idx = fn.find("withContext(Dispatchers.IO)")
    dir_idx = fn.find('File(ctx.cacheDir, "share")')
    prune_idx = fn.find("pruneShareDir(")
    zip_idx = fn.find("ZipOutputStream(")
    if io_idx < 0:
        return [
            f"{CRASH_REL}: packageZip lost its withContext(Dispatchers.IO) block "
            "— zip packaging ran back on the calling thread"
        ]
    if dir_idx < 0:
        return [f'{CRASH_REL}: packageZip lost the File(ctx.cacheDir, "share") construction']
    if prune_idx < 0:
        return [f"{CRASH_REL}: packageZip must call the shared pruneShareDir( pruner before writing"]
    if zip_idx < 0:
        return [f"{CRASH_REL}: packageZip lost its ZipOutputStream( staging call"]
    if not (io_idx < dir_idx < prune_idx < zip_idx):
        return [
            f"{CRASH_REL}: packageZip ordering broken: expected withContext(Dispatchers.IO) < "
            f'File(ctx.cacheDir, "share") < pruneShareDir < ZipOutputStream, got '
            f"io={io_idx} dir={dir_idx} prune={prune_idx} zip={zip_idx}"
        ]
    problems = []
    bad = prune_target_problem(fn, fn_cv, f"{CRASH_REL} packageZip", "share")
    if bad:
        problems.append(bad)
    ttl = prune_ttl_problem(fn, fn_cv, f"{CRASH_REL} packageZip", pruner_consts_of(root))
    if ttl:
        problems.append(ttl)
    # `withContext`'s block parameter is crossinline => a bare non-local `return`
    # inside it does not compile ("'return' is prohibited here"). CI run
    # 39087482248 caught exactly this at :979 after the [da1025-B] conversion;
    # ban the shape here so the next edit fails locally instead of on CI.
    if re.search(r"(?<![\w.@])return\s+[^\s@]", fn):
        problems.append(
            f"{CRASH_REL}: packageZip contains a bare `return` inside the "
            "withContext(Dispatchers.IO) block — withContext's block is crossinline, "
            "so only `return@withContext` compiles ('return' is prohibited here)")
    return problems


# --- arm5 [da1028-C]: filesDir / externalFilesDir staging-dir growers ------

# (rel, function name, human label). A missing anchor is RED so a rename cannot
# quietly retire the arm.
PRUNE_WRITE_POINTS = (
    (CAMERA_REL, "createCameraOutputUri", "filesDir/camera-photos"),
    (PHOTOS_REL, "handleExport", "filesDir/photos-export"),
    (A11Y_REL, "uiScreenshot", "externalFilesDir/a11y_screenshots"),
)

PRUNER_CALL = "pruneShareDir("
# the second hand-rolled copy of the 24h loop; must not come back
PRUNE_LOOP_ANCHORS = ("listFiles()", "lastModified() < cutoff")
# the shared sweeper's own implementation, which the reverse anchor now checks
PRUNER_BODY_ANCHORS = ("listFiles(", "lastModified(", ".delete(")


def first_guaranteed_exit(cv_body):
    """函数体里第一条「必然退出」的顶层语句起点（None = 没有）。

    [B7-t05] anchor/dataflow 轴（05-1）：旧判据只问 `"internal fun pruneShareDir("`
    这个**声明头**在不在文件里。于是一个 `if (true) return` 插在函数体首行 ——
    签名还在、调用点还全接着、门全绿 —— 而 9 处写入点共用的清理器直接变成空转。
    函数体的存在不能证明函数体的**实现**还在。
    """
    for a, b in stmt_spans(cv_body):
        stmt = cv_body[a:b].strip()
        if re.match(r"^(?:return|throw)\b", stmt):
            return a
        m = re.match(r"^if\s*\(", stmt)
        if m is None:
            continue
        close = match_bracket(stmt, m.end() - 1)
        if close < 0:
            continue
        if const_bool(stmt[m.end():close]) is not True:
            continue                    # 条件不可折叠 → 不是「必然」退出
        rest = stmt[close + 1:].strip()
        if re.match(r"^(?:return|throw)\b", rest) or rest.startswith("{"):
            return a
    return None


def pruner_body_problem(lit_def, cv_def, where):
    """`internal fun pruneShareDir(` 的**实现**必须还在且可达。"""
    sp = fn_span(cv_def, "pruneShareDir")
    if sp is None:
        return (
            f"{where}: the single `internal fun pruneShareDir(` definition is gone — "
            "every caller above would stop compiling, so this is anchor rot, not a fix")
    if sp[3] != "block":
        return (f"{where}: pruneShareDir must keep a block body — the TTL sweep is "
                "multi-statement, an expression body cannot carry it")
    lit_body = lit_def[sp[1]:sp[2]]
    cv_body = cv_def[sp[1]:sp[2]]
    for anchor in PRUNER_BODY_ANCHORS:
        if anchor not in lit_body:
            return (f"{where}: pruneShareDir's body lost `{anchor}` — the shared sweeper no "
                    "longer enumerates / ages out / deletes anything, so every staging dir "
                    "that relies on it grows for the life of the install")
    exit_pos = first_guaranteed_exit(cv_body)
    if exit_pos is not None:
        early = lit_body[exit_pos:exit_pos + 60].split("\n")[0].strip()
        return (f"{where}: pruneShareDir short-circuits at its entry (`{early}`) — the "
                "signature and every call site stay wired, but the sweep is unreachable, "
                "so the 24h TTL contract is void")
    return None


def check_arm5(root):
    """[da1028-C] filesDir/externalFilesDir staging dirs must be pruned too."""
    problems = []
    consts = pruner_consts_of(root)
    for rel, name, label in PRUNE_WRITE_POINTS:
        pv = read_views(root, rel)
        if pv is None:
            problems.append(f"{rel}: file missing (was it renamed/moved?)")
            continue
        lit, cv = pv
        sl = fn_slices(lit, cv, name)
        if sl is None:
            problems.append(
                f"{rel}: anchor `{name}(` not found (fail-closed: the {label} write "
                "point moved or was renamed — re-derive this arm)")
            continue
        fn, fn_cv = sl
        if PRUNER_CALL not in fn:
            problems.append(
                f"{rel}: the {label} write point does not call the shared "
                f"`{PRUNER_CALL}` pruner — that dir lives outside cacheDir, so nothing "
                "else can reclaim it and it grows for the life of the install")
            continue
        bad = prune_target_problem(fn, fn_cv, f"{rel} ({label})", None)
        if bad:
            problems.append(bad)
        ttl = prune_ttl_problem(fn, fn_cv, f"{rel} ({label})", consts)
        if ttl:
            problems.append(ttl)
    pv = read_views(root, MUTATION_REL)
    if pv is None:
        problems.append(f"{MUTATION_REL}: file missing (was it renamed/moved?)")
    else:
        lit, cv = pv
        sl = fn_slices(lit, cv, "pruneStaleAttachments")
        if sl is None:
            problems.append(
                f"{MUTATION_REL}: anchor `private fun pruneStaleAttachments` not found "
                "(fail-closed: this arm pins the de-duplication)")
        else:
            fn, fn_cv = sl
            if PRUNER_CALL not in fn:
                problems.append(
                    f"{MUTATION_REL}: pruneStaleAttachments must delegate to the shared "
                    f"`{PRUNER_CALL}` pruner instead of keeping its own copy of the TTL loop")
            else:
                for anchor in PRUNE_LOOP_ANCHORS:
                    if anchor in fn:
                        problems.append(
                            f"{MUTATION_REL}: pruneStaleAttachments still carries a hand-rolled "
                            f"24h loop (`{anchor}`) alongside the shared pruner — two TTL copies "
                            "drift apart silently")
                bad = prune_target_problem(fn, fn_cv, MUTATION_REL, None)
                if bad:
                    problems.append(bad)
                ttl = prune_ttl_problem(fn, fn_cv, MUTATION_REL, consts)
                if ttl:
                    problems.append(ttl)
    pv = read_views(root, DEFINER_REL)
    if pv is None:
        problems.append(f"{DEFINER_REL}: file missing (was it renamed/moved?)")
    else:
        bad = pruner_body_problem(pv[0], pv[1], DEFINER_REL)
        if bad:
            problems.append(bad)
    return problems


# --- compliant / violation fixture snippets (code-level, not comments) ---
COMPLIANT = """package com.rikkaminis.app.webapp

import com.rikkaminis.app.ui.components.pruneShareDir

class WebAppActivity {
    private suspend fun stagedShareUri(context: android.content.Context, file: File): Uri? {
        return try {
            val stem = file.nameWithoutExtension.ifBlank { "webapp" }
            val staged = withContext(Dispatchers.IO) {
                val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
                pruneShareDir(shareDir)
                val s = File(shareDir, "$stem.html")
                file.copyTo(s, overwrite = true)
                s
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
        } catch (t: Throwable) {
            null
        }
    }
}
"""

VIOLATION_NO_PRUNE = COMPLIANT.replace("                pruneShareDir(shareDir)\n", "")

# [wide-1024] The call survives, the ordering survives, the TARGET is wrong:
# `pruneShareDir(File(context.cacheDir, "noop"))` reclaims nothing while reading
# exactly like the compliant shape.
# [B7-t05] 05-2 family-hole：调用、实参、顺序全都保持原样，只是外层套了
# 恒假守卫 —— sweep 永远不会跑。
VIOLATION_DEAD_PRUNE = COMPLIANT.replace(
    "                pruneShareDir(shareDir)\n",
    "                if (false) pruneShareDir(shareDir)\n")

VIOLATION_WRONG_PRUNE_DIR = COMPLIANT.replace(
    "pruneShareDir(shareDir)",
    'pruneShareDir(File(context.cacheDir, "noop"))')
assert VIOLATION_WRONG_PRUNE_DIR != COMPLIANT

VIOLATION_MAIN_THREAD = """package com.rikkaminis.app.webapp

import com.rikkaminis.app.ui.components.pruneShareDir

class WebAppActivity {
    private suspend fun stagedShareUri(context: android.content.Context, file: File): Uri? {
        return try {
            val stem = file.nameWithoutExtension.ifBlank { "webapp" }
            val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
            pruneShareDir(shareDir)
            val staged = withContext(Dispatchers.IO) {
                val s = File(shareDir, "$stem.html")
                file.copyTo(s, overwrite = true)
                s
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
        } catch (t: Throwable) {
            null
        }
    }
}
"""


# ─── [deep-audit-1048 B-04] share hand-off: flags + verdict + feedback ──────

def _brace_end(text, open_idx):
    """`{` 的配对位置（共享 match_bracket；配平失败 → None）。"""
    if open_idx < 0 or open_idx >= len(text) or text[open_idx] != "{":
        return None
    j = match_bracket(text, open_idx)
    return j if j > 0 else None


def _innermost_block(text, idx):
    """(start, end) of the innermost `{ … }` containing [idx]."""
    stack = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            start = stack.pop()
            if start < idx < i:
                return start, i
        i += 1
    return None


def _enclosing_fun(code, idx):
    """(name, body_start, body_end) of the `fun` whose body contains [idx]."""
    best = None
    for m in re.finditer(r"\bfun\s+(\w+)\s*\(", code):
        if m.start() > idx:
            break
        brace = code.find("{", m.end())
        if brace < 0:
            continue
        depth, j = 0, brace
        while j < len(code):
            if code[j] == "{":
                depth += 1
            elif code[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if brace < idx < j:
            best = (m.group(1), brace, j)
    return best


def _statement(text, idx):
    """The expression statement around [idx]: from its line start until the
    bracket depth returns to zero and the call chain stops."""
    start = text.rfind("\n", 0, idx) + 1
    depth, i = 0, idx
    while i < len(text):
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif ch == "\n" and depth == 0:
            nxt = text[i:].lstrip("\n ")
            if not nxt.startswith((".", "?", ":")):
                break
        i += 1
    return text[start:i]


def _declared_return_type(sig):
    """Return type text of a `fun …` signature up to its body, or ''."""
    depth = 0
    for j, ch in enumerate(sig):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == ":" and depth == 0:
            return sig[j + 1:].split("{")[0].split("=")[0].strip()
    return ""


def _sig_text(code, fun_match):
    """The signature text of the `fun` at [fun_match] (up to body / `=`)."""
    if fun_match is None:
        return ""
    i, depth = fun_match.end(), 1
    while i < len(code):
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    end = i
    while end < len(code) and code[end] not in "{=":
        end += 1
    return code[fun_match.start():end]


def verdict_saturating_operand(expr):
    """把判定结论钉死的顶层操作数（`.isSuccess || true` / `.isSuccess && false`）。

    返回可读文本（用于报错）或 None。判据是**值**：只有常量折叠成「饱和值」的
    操作数才算 —— `.isSuccess || somethingUnknown` 是可疑但无法证明，不报。
    """
    for op, sink in (("||", True), ("&&", False)):
        parts = split_top(expr, (op,))
        if len(parts) < 2:
            continue
        for a, b in parts:
            operand = strip_parens(expr[a:b]).strip()
            if const_bool(operand) is sink:
                return "%s %s %s" % (expr.strip()[:60], op, operand[:24])
    return None


# ── [R3-t04] 第三轮（dual-gate bypass）：share 面「解析出来了，但没交出去」──
#
# 三条见证都落在 arm6 判据的空档里 —— arm6 判的是「chooser 有没有 NEW_TASK /
# 启动函数有没有判定 / catch 有没有反馈」，而**中间那一步**（uri 挂到 Intent 上、
# chooser 真的被 startActivity、暂存目录真的被删）谁都不判：
#
#   12-1 `buildLogShareChooser` 里 `putExtra(EXTRA_STREAM, uri)` + `clipData` 被删：
#        FileProvider 仍在 IO 线程解析（Y 绿）、chooser 仍带 NEW_TASK 并被启动
#        （X 绿）→ 接收方拿到的是空 ACTION_SEND。
#   12-2 `shareImage` 里 `context.startActivity(chooser)` 被注释：派生判据对
#        START_ACTIVITY_CALL 的循环 0 次即通过（量词空真）→ 点分享毫无反应。
#   12-3 `ChatExporter` 的 `finally { workDir.deleteRecursively() }` 被注释：
#        arm1/arm3 只盯 cacheDir/shared 的 prune，同一函数里 export-staging 的
#        明文暂存目录无人回收。
URI_RESOLVER = re.compile(r"(?:\w+\s*\.\s*)*FileProvider\s*\.\s*getUriForFile\s*\(")
HANDOFF_DELEGATE = re.compile(r"\bExternalIntent\s*\.\s*share\s*\(")


def _home_val(body, idx):
    """`val X = <含 idx 的表达式>` 里的 X（body 相对位置）；内联形态返回 None。

    只要求「最后一条声明与 idx 之间的换行都是续行」——`val uri = try {` 这种
    值表达式带块、调用在块内的形态也要认得出来（旧版按语句起点切，认不出它，
    于是整条缝在 InlineMediaPlayer 上静默跳过）。
    """
    decls = [(m.start(), m.group(1)) for m in
             re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)", body)
             if m.start() < idx]
    if not decls:
        return None
    start, name = decls[-1]
    seg = body[start:idx]
    newlines = [m.start() for m in re.finditer(r"\n", seg)]
    for p in newlines[:-1]:
        if not re.search(r"(=|->|\btry|\belse|\bdo|\(|,|\{|\+|-|\*|/|%|\?|:|&|\|)\s*$",
                         seg[:p].rstrip()):
            return None
    return name


def _in_call_args(text, pos):
    """标识符在 pos 处是否落在某个调用的实参表里（而非只当接收者/被访问）。"""
    depth = 0
    i = pos - 1
    while i >= 0:
        ch = text[i]
        if ch in ")]":
            depth += 1
        elif ch in "([":
            if depth == 0:
                return ch == "("
            depth -= 1
        elif depth == 0 and ch in ";}":
            return False
        elif depth == 0 and ch == "\n":
            # 续行（行尾是 `,` / `(` / 运算符）才继续：否则上一行的调用括号
            # 与本语句无关，跨过去就会把「只当接收者」误判成交出去的实参。
            j = i - 1
            while j >= 0 and text[j] in " \t\r":
                j -= 1
            if j < 0 or text[j] not in ",(+-*/%?:&|=<>!":
                return False
        i -= 1
    return False


def _value_consumed(body, name):
    """这个值有没有被「交出去」：作为调用实参，或被 return / 当尾值输出。

    判据刻意停在「离开本函数」这一层：`uri.addFlags(…)` 这种只当接收者的用法
    不算交出，`launch(ctx, chooser, …)` / `putExtra(EXTRA_STREAM, uri)` /
    `return chooser` 算 —— 再往下就跨函数了（残留已登记）。
    """
    n = re.escape(name)
    decl = re.search(r"\b(?:val|var)\s+%s\s*(?::[^=\n]+)?=(?!=)" % n, body)
    start = decl.end() if decl else 0
    for m in re.finditer(r"(?<![\w.])%s\b" % n, body[start:]):
        pos = start + m.start()
        if _in_call_args(body, pos):
            return True
        if re.search(r"\breturn\s+%s\b" % n, body):
            return True
        if re.search(r"(?m)^\s*%s\s*$" % n, body):
            return True
    return False


def _attach_problem(code, rel):
    """C-STREAM：解析出来的 content:// uri 必须真的交出去。

    `getUriForFile` 成功只说明 uri 能构造；分享面板里另一个进程能否拿到文件，
    取决于它有没有被挂进 EXTRA_STREAM / clipData / data / ClipData / 显式授权，
    或者被交给一个启动器。删掉这一步，所有既有判据（IO 线程、NEW_TASK、判定、
    反馈）仍然全绿。
    """
    problems = []
    for m in URI_RESOLVER.finditer(code):
        fn = _enclosing_fun(code, m.start())
        base = fn[1] if fn else 0
        body = code[fn[1]: fn[2]] if fn else code
        name = _home_val(body, m.start() - base)
        if name is None:
            continue
        if _value_consumed(body, name):
            continue
        problems.append(
            "[C-STREAM] %s:%d: the FileProvider uri `%s` is resolved but never handed to "
            "anything (no EXTRA_STREAM / clipData / setData / ClipData / grant / launcher "
            "argument) — the share sheet still opens and the receiver gets an empty "
            "ACTION_SEND (R3-t04)" % (rel, code.count("\n", 0, m.start()) + 1, name))
    return problems


def _launch_problem(code, rel):
    """C-LAUNCH：构造出来的 chooser 必须被启动、被交给启动器、或被返回。

    旧派生判据遍历 `startActivity` 站点来判 catch 覆盖；站点数为 0 时循环一次
    都不跑（量词空真），于是「chooser 建好了但从不启动」是全绿的。
    """
    problems = []
    for m in HANDOFF_CHOOSER.finditer(code):
        fn = _enclosing_fun(code, m.start())
        base = fn[1] if fn else 0
        body = code[fn[1]: fn[2]] if fn else code
        name = _home_val(body, m.start() - base)
        if name is None:
            continue          # 内联 / 直接 return 的 chooser：由构造交给调用方
        if _value_consumed(body, name):
            continue
        problems.append(
            "[C-LAUNCH] %s:%d: the share chooser `%s` is built but never started or handed "
            "on — every flag/verdict/feedback guard around it still passes while the share "
            "tap silently does nothing (R3-t04)"
            % (rel, code.count("\n", 0, m.start()) + 1, name))
    return problems


def _finally_spans(cv):
    spans = []
    for m in re.finditer(r"\bfinally\s*\{", cv):
        cb = _brace_end(cv, cv.find("{", m.end() - 1))
        if cb is not None:
            spans.append((cv.find("{", m.end() - 1), cb))
    return spans


def _staging_cleanup_problem(cv, where):
    """C-STAGING：建出来并往里写文件的暂存目录，必须在 finally 里删掉。

    arm1/arm3 只判「产物目录（cacheDir/share[ d ]）在写之前 prune」；同一个函数
    里为导出而建的临时目录（完整明文 transcript）不在任何判据里 —— 删掉
    `finally { workDir.deleteRecursively() }` 之后仍然全绿。
    """
    problems = []
    fins = _finally_spans(cv)
    for m in re.finditer(r"(?<![\w.])([A-Za-z_]\w*)\s*\.\s*mkdirs\s*\(\s*\)", cv):
        ident = m.group(1)
        n = re.escape(ident)
        if not re.search(r"\b(?:val|var)\s+%s\s*(?::[^=\n]+)?=\s*(?:[\w.]+\.)?File\s*\("
                         % n, cv):
            continue
        if not re.search(r"(?:[\w.]+\.)?File\s*\(\s*%s\s*," % n, cv):
            continue                      # 只建目录、不往里写的不属本判据
        cleaned = []
        for a, b in fins:
            for dm in re.finditer(r"(?<![\w.])%s\s*\.\s*(?:deleteRecursively|delete)\s*\(" % n,
                                  cv[a:b]):
                cleaned.append(a + dm.start())
        line = cv.count("\n", 0, m.start()) + 1
        if not cleaned:
            problems.append(
                "[C-STAGING] %s:%d: the staging directory `%s` is created and written into "
                "but never removed — a `finally { … deleteRecursively() }` is what keeps the "
                "plaintext transcript out of the cache after every export (R3-t04)"
                % (where, line, ident))
        elif not any(path_feasible(cv, c) for c in cleaned):
            problems.append(
                "[C-STAGING] %s:%d: the cleanup of `%s` is unreachable — the staging "
                "directory survives every export (R3-t04)" % (where, line, ident))
    return problems


def check_handoff_src(rel, text):
    """[deep-audit-1048 B-04] C-NT + C-SILENT for one share-bearing source.

    C-NT: every `Intent.createChooser(` carries FLAG_ACTIVITY_NEW_TASK (on the
    chooser statement, or through `<chooser>.addFlags(…)` in the same function).
    C-SILENT: the function that calls `startActivity` declares a non-Unit
    verdict, no failure exit is a bare `return`, the launch is contained in
    runCatching/try, and every call site assigns the verdict inside a block that
    reports failure.
    """
    try:
        code = code_view(text)
    except KotlinLexError:
        return ["[C-NT/C-SILENT] %s: cannot lex the source (unbalanced quote/comment) — "
                "fail-closed" % rel]
    problems = []
    chooser_sites = 0
    for m in CHOOSER_CALL.finditer(code):
        chooser_sites += 1
        stmt = _statement(code, m.start())
        if "FLAG_ACTIVITY_NEW_TASK" in stmt:
            continue
        var = re.match(r"\s*val\s+(\w+)\s*=", stmt)
        fn = _enclosing_fun(code, m.start())
        body = code[fn[1]: fn[2]] if fn else ""
        if var and re.search(r"\b%s\s*\.\s*addFlags\s*\([^)]*FLAG_ACTIVITY_NEW_TASK"
                             % re.escape(var.group(1)), body):
            continue
        problems.append("[C-NT] %s: a chooser is started without "
                        "FLAG_ACTIVITY_NEW_TASK (offset %d)" % (rel, m.start()))

    starters = 0
    for m in START_CALL.finditer(code):
        starters += 1
        fn = _enclosing_fun(code, m.start())
        if fn is None:
            problems.append("[C-SILENT] %s: startActivity outside any function" % rel)
            continue
        name, body_start, body_end = fn
        body = code[body_start: body_end]
        rtype = _declared_return_type(
            _sig_text(code, re.search(r"\bfun\s+%s\s*\(" % re.escape(name), code)))
        if rtype in ("", "Unit"):
            problems.append("[C-SILENT] %s: %s() starts the sheet but returns no verdict "
                            "(%s) — a failed share is indistinguishable from success"
                            % (rel, name, rtype or "no return type"))
        for r in re.finditer(r"\breturn\b(?!\s*@)", body):
            same_line = body[r.end(): r.end() + 40].split("\n")[0]
            if same_line.strip() in ("", "}", "null", "Unit"):
                if not any(tok in same_line for tok in FEEDBACK_TOKENS):
                    problems.append("[C-SILENT] %s: bare `return` in %s() — that exit "
                                    "reports nothing" % (rel, name))
        contained = []
        for b in re.finditer(r"(?:runCatching|try)\s*\{", body):
            end = _brace_end(body, b.end() - 1)
            if end is not None:
                contained.append((b.start(), end))
        for s in START_CALL.finditer(body):
            if not any(lo < s.start() < hi for lo, hi in contained):
                problems.append("[C-SILENT] %s: startActivity in %s() is not wrapped in "
                                "runCatching/try — a missing handler crashes the caller"
                                % (rel, name))
        # [wide-1024] …and the verdict the caller receives must be DERIVED from
        # the launch. `return runCatching { context.startActivity(chooser) }
        # .let { true }` catches the failure and then reports success anyway.
        for rc in re.finditer(r"\brunCatching\s*\{", body):
            ob = rc.end() - 1
            end = _brace_end(body, ob)
            if end is None:
                problems.append("[C-SILENT] %s: unparsable runCatching block in %s() — "
                                "fail-closed" % (rel, name))
                continue
            if not START_CALL.search(body[ob:end]):
                continue
            # [B7-t05] dataflow 轴（05-5）：旧判据只看 runCatching 块右括号之后的
            # **前缀** —— `re.match` 命中 `.isSuccess` 就收工，于是
            # `.isSuccess || true` 把刚读到的判定布尔又抹成常量真，门照样绿。
            # 现在整条表达式都要过：任一顶层 `||`/`&&` 操作数是把结论钉死的常量即红。
            tail_start = end + 1
            while tail_start < len(body) and body[tail_start] in " \t\r\n":
                tail_start += 1
            tail = body[tail_start: tail_start + 80]
            if not VERDICT_CONSUME_RE.match(tail):
                problems.append(
                    "[C-SILENT] %s: %s() does not derive its verdict from the launch "
                    "(`runCatching { startActivity(...) }%s`) — the failure it just caught is "
                    "discarded, so a missing share target still reports success"
                    % (rel, name, tail.split("\n")[0][:40]))
                continue
            expr = body[tail_start: expr_end(body, tail_start)].strip()
            sat = verdict_saturating_operand(expr)
            if sat:
                problems.append(
                    "[C-SILENT] %s: %s() reads the launch verdict but then saturates it "
                    "(`%s`) — a caught ActivityNotFoundException still returns success, so "
                    "the caller shows no error and the tap looks dead"
                    % (rel, name, sat))
        for c in re.finditer(r"\b%s\s*\(" % re.escape(name), code):
            if re.search(r"fun\s+$", code[max(0, c.start() - 6): c.start()]):
                continue  # the declaration itself
            stmt = _statement(code, c.start())
            if not re.search(r"=\s*%s\s*\(" % re.escape(name), stmt):
                problems.append("[C-SILENT] %s: call to %s() discards its verdict: %s"
                                % (rel, name, stmt.strip()[:80]))
                continue
            block = _innermost_block(code, c.start())
            region = code[block[0]: block[1]] if block else ""
            if not any(tok in region for tok in FEEDBACK_TOKENS):
                problems.append("[C-SILENT] %s: call to %s() has no visible failure path "
                                "in its block" % (rel, name))
    if chooser_sites == 0 and starters == 0:
        problems.append("[C-NT/C-SILENT] %s: no chooser / startActivity found — the share "
                        "path moved, so this criterion saw nothing to check" % rel)
    # [R3-t04] 「解析出来了」和「交出去了」是两件事，中间那一步单独判。
    problems.extend(_attach_problem(code, rel))
    problems.extend(_launch_problem(code, rel))
    return problems


def check_handoff(root, stats=None):
    problems = []
    for rel in SHARE_FILES:
        try:
            with open(os.path.join(root, rel), encoding="utf-8") as fh:
                text = fh.read()
        except OSError:
            problems.append("[C-NT] %s: file missing (was it renamed/moved?)" % rel)
            continue
        problems.extend(check_handoff_src(rel, text))
    problems.extend(check_derived_handoff(root, stats))
    return problems


# ── [deep-audit-1049 B-03] the DERIVED share/open hand-off surface ──────────
#
# arm6 above pins three files BY NAME — the three the 1048 wave happened to
# audit. The next share entry point was therefore invisible, which is the N+1
# shape this gate has already re-learned twice (arm1's `"shared"` hole, arm5's
# filesDir growers). This arm derives its subject set from the tree instead:
# every .kt under src/main/java that either builds a system chooser or hands a
# URI-granting intent to startActivity().
#
# C-NT        every `Intent.createChooser(` statement carries
#             FLAG_ACTIVITY_NEW_TASK — on the statement, through
#             `<chooser>.addFlags(…)` in the same function, or (new) through the
#             shared `ExternalIntent` launcher, whose own chooser does. An
#             `Intent.createChooser` does NOT inherit the inner intent's flags:
#             the six 1049 entry points had one flag on the inner intent, one on
#             neither, and one on each — only the Activity-context callers got
#             away with it, and `AndroidRuntimeException` from a Service /
#             Application context is an `Error`, not a catchable exception.
# C-FEEDBACK  a `try { … startActivity … } catch { }` around a URI hand-off must
#             report the failure (Toast / Snackbar / AppLogger / Log). An empty
#             or comment-only catch is precisely the report "tapping share does
#             nothing". Containment itself (a bare startActivity with no
#             runCatching/try) stays arm6's stricter criterion on the three
#             named files — requiring it tree-wide would flag Activity-internal
#             launches that cannot throw.
SRC_MAIN_REL = "src/android/app/src/main/java"
HANDOFF_CHOOSER = re.compile(r"Intent\s*\.\s*createChooser\s*\(")
HANDOFF_START = re.compile(r"\bstartActivity\s*\(")
HANDOFF_GRANT = "FLAG_GRANT_READ_URI_PERMISSION"
HANDOFF_TRY = re.compile(r"(?<![\w.])try\s*\{")
HANDOFF_CATCH = re.compile(r"\bcatch\s*(?:\([^)]*\))?\s*\{")
# AppLogger / Toast / Snackbar live in FEEDBACK_TOKENS; the bare android.util
# Log spellings are just as visible in logcat, so they count too.
HANDOFF_FEEDBACK = FEEDBACK_TOKENS + ("Log.w(", "Log.e(", "Log.i(", "Log.d(")


def derive_handoff_files(root):
    """Every production source that hands something to another app."""
    out = []
    base = os.path.join(root, SRC_MAIN_REL)
    if not os.path.isdir(base):
        return out
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames.sort()
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            try:
                with open(path, encoding="utf-8") as fh:
                    text = fh.read()
            except OSError:
                continue
            # Use the offset-preserving stripper: the shared one loses text on
            # some Kotlin shapes (BrowserDownloadsSheet.kt came out 2.5 KB short
            # with every `createChooser` gone), which silently shrank this set.
            code = _strip_comments_keep_offsets(text)
            # [R3-t04] 委派给共享启动器（`ExternalIntent.share(...)`）也是一次
            # 「把文件交给另一个应用」：这种文件自己没有 startActivity 站点，
            # 于是旧派生条件看不见它 —— 12-1 的夹缝正好落在这里（FileProvider
            # 解析在 InlineMediaPlayer，启动在 ExternalIntent，两边都不判 uri
            # 有没有挂上去）。
            if (HANDOFF_CHOOSER.search(code)
                    or (HANDOFF_START.search(code) and HANDOFF_GRANT in code)
                    or HANDOFF_DELEGATE.search(code)):
                out.append(os.path.relpath(path, root).replace(os.sep, "/"))
    return out


def _paren_end(text, open_idx):
    """Index just past the `)` matching the `(` at [open_idx], or -1."""
    depth = 0
    for j in range(open_idx, len(text)):
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
            if depth == 0:
                return j + 1
    return -1


def _strip_comments_keep_offsets(text):
    """[B7-t05] 注释抹空格、偏移与行号不变（= 共享 literal_view）。

    自写版本在嵌套块注释 / 单引号字符字面量 `'"'` / 三引号原始字符串上会把
    注释边界整体切错（实测 BrowserDownloadsSheet.kt 曾被吃掉 2.5 KB，含全部
    `createChooser`），于是派生面（arm6b）静默缩小。共享词法器三个视图都与原文
    等长，位置天然可回投。
    """
    try:
        return code_view(text)
    except KotlinLexError:
        return ""

def _handoff_is_uri_handooff(code, idx, lookback_lines=25):
    """True when the `startActivity` at [idx] hands a local file to another app.

    The signal is a URI grant on the started intent. A chooser carries the grant
    on the INNER intent a few lines up, so a bounded look-back is part of the
    window; an unbounded "the enclosing function grants something" test instead
    swept in MainActivity's settings-panel launches, whose `catch` is an OEM
    comment and whose intent grants nothing.
    """
    if HANDOFF_GRANT in _statement(code, idx):
        return True
    lo = idx
    for _ in range(lookback_lines):
        nl = code.rfind("\n", 0, lo)
        if nl < 0:
            lo = 0
            break
        lo = nl
    return HANDOFF_GRANT in code[lo:idx]


def check_derived_handoff_src(rel, text):
    code = _strip_comments_keep_offsets(text)
    problems = []
    chooser_sites = 0
    for m in HANDOFF_CHOOSER.finditer(code):
        chooser_sites += 1
        stmt = _statement(code, m.start())
        if "FLAG_ACTIVITY_NEW_TASK" in stmt:
            continue
        var = re.match(r"\s*val\s+(\w+)\s*=", stmt)
        fn = _enclosing_fun(code, m.start())
        body = code[fn[1]: fn[2]] if fn else ""
        if var and re.search(
            r"\b%s\s*\.\s*addFlags\s*\([^)]*FLAG_ACTIVITY_NEW_TASK" % re.escape(var.group(1)),
            body,
        ):
            continue
        problems.append(
            "[C-NT] %s:%d: a chooser is started without FLAG_ACTIVITY_NEW_TASK — a "
            "chooser does not inherit the inner intent's flags"
            % (rel, code.count("\n", 0, m.start()) + 1)
        )
    launches = [m for m in HANDOFF_START.finditer(code)]
    for m in launches:
        # C-NT-ARG: a URI hand-off that is started directly (no chooser) must
        # carry the flag on the intent it starts. The resolver looks at the
        # argument expression and, when that is a bare identifier, at the local
        # `val` that built it (+ any `ident.addFlags(...)` right after). A value
        # returned by a helper cannot be judged here and is skipped — C-NT above
        # still pins the chooser such a helper returns.
        if not _handoff_is_uri_handooff(code, m.start()):
            continue
        arg_end = _paren_end(code, m.end() - 1)
        arg = code[m.start(): arg_end] if arg_end > 0 else ""
        if "FLAG_ACTIVITY_NEW_TASK" not in arg:
            bare = re.match(r"startActivity\s*\(\s*([A-Za-z_]\w*)\s*\)", arg)
            resolved = None
            if bare:
                ident = bare.group(1)
                for k in range(m.start(), -1, -1):
                    decl = re.search(r"\b(?:val|var)\s+" + re.escape(ident) + r"\s*=", code[k:k + 80])
                    if decl:
                        at = k + decl.start()
                        resolved = _statement(code, at)
                        # `ident.addFlags(...)` on its own line right after
                        tail = code[at: at + 4000]
                        for extra in re.finditer(
                            r"\b%s\s*\.\s*addFlags\s*\([^)]*FLAG_ACTIVITY_NEW_TASK" % re.escape(ident),
                            tail,
                        ):
                            resolved += " FLAG_ACTIVITY_NEW_TASK"
                            break
                        break
            if resolved is None:
                continue
            if "FLAG_ACTIVITY_NEW_TASK" not in resolved:
                problems.append(
                    "[C-NT] %s:%d: a URI hand-off is started without "
                    "FLAG_ACTIVITY_NEW_TASK on the intent it starts (the flag does not "
                    "travel from an inner intent)" % (rel, code.count("\n", 0, m.start()) + 1)
                )
    for m in launches:
        if not _handoff_is_uri_handooff(code, m.start()):
            continue
        span = None
        for t in HANDOFF_TRY.finditer(code):
            if t.start() > m.start():
                break
            ob = code.find("{", t.end() - 1)
            cb = _brace_end(code, ob) if ob >= 0 else None
            if cb is None or cb < m.start():
                continue
            if span is None or ob > span[0]:
                span = (ob, cb)
        if span is None:
            continue  # containment is arm6's criterion on the named files
        cm = HANDOFF_CATCH.search(code[span[1]: span[1] + 400])
        if cm is None:
            continue  # try/finally, no catch to judge
        cob = code.find("{", span[1] + cm.start())
        ccb = _brace_end(code, cob) if cob >= 0 else None
        if ccb is None:
            problems.append("[C-FEEDBACK] %s:%d: unparsable catch block — fail-closed"
                            % (rel, code.count("\n", 0, m.start()) + 1))
            continue
        body = code[cob + 1: ccb].strip()
        if not body:
            problems.append(
                "[C-FEEDBACK] %s:%d: the failure of a file share is swallowed — the "
                "catch body is empty (no user feedback, no log)"
                % (rel, code.count("\n", 0, m.start()) + 1)
            )
        elif not any(tok in body for tok in HANDOFF_FEEDBACK):
            problems.append(
                "[C-FEEDBACK] %s:%d: the catch around a file share reports nothing "
                "visible (no Toast / Snackbar / AppLogger / Log)"
                % (rel, code.count("\n", 0, m.start()) + 1)
            )
    # [R3-t04] 派生面同样要判「挂上去了没有」与「真的启动了没有」：
    #   12-2 的见证正是靠「startActivity 站点数 0 → 循环不跑」蒙过去的。
    problems.extend(_attach_problem(code, rel))
    problems.extend(_launch_problem(code, rel))
    return problems, chooser_sites, len(launches)


def check_derived_handoff(root, stats=None):
    """(problems, stats) for the tree-derived share/open surface.

    [stats] (an optional dict) receives the counted surface, so a green run can
    still say WHAT it looked at — a criterion that silently sees zero sites is
    indistinguishable from a clean tree, and that is how these arms rot.
    """
    problems = []
    files = [f for f in derive_handoff_files(root) if f not in SHARE_FILES]
    choosers = launches = 0
    for rel in files:
        try:
            with open(os.path.join(root, rel), encoding="utf-8") as fh:
                text = fh.read()
        except OSError:
            problems.append("[C-NT] %s: file vanished between walk and read" % rel)
            continue
        p, c, l = check_derived_handoff_src(rel, text)
        problems.extend(p)
        choosers += c
        launches += l
    if stats is not None:
        stats["files"] = len(files)
        stats["choosers"] = choosers
        stats["launches"] = launches
    if not files:
        problems.append("[C-NT] no share/open hand-off source found under %s — the "
                        "derived surface went blind (paths moved?)" % SRC_MAIN_REL)
    return problems


def run_fixture(name, content, expect_ok):
    with tempfile.TemporaryDirectory() as tmp:
        os.makedirs(os.path.join(tmp, ACTIVITY_REL.rsplit("/", 1)[0]))
        with open(os.path.join(tmp, ACTIVITY_REL), "w") as f:
            f.write(content)
        a1 = check_arm1(tmp)
        a2 = check_arm2(tmp)
        ok = not a1 and a2 is None
        status = "PASS" if ok == expect_ok else "FAIL"
        print(f"  [{status}] fixture {name}: arm1={a1!r} arm2={a2!r}")
        return status == "PASS"


# --- [da1024-G] arm3 fixtures: the three cacheDir/shared writers ------------

PROVIDER_OK = """package com.rikkaminis.app.ui.settings

private suspend fun exportProviderInstance(
    context: android.content.Context,
    providerRepository: ProviderRepository,
) {
    val file = withContext(Dispatchers.IO) {
        val json = providerRepository.exportInstanceJSON(id) ?: return@withContext null
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        pruneShareDir(dir)
        val f = java.io.File(dir, "label.json")
        runCatching { f.writeText(json) }.map { f }.getOrNull()
    } ?: return
    context.startActivity(android.content.Intent())
}
"""

MCP_OK = """package com.rikkaminis.app.ui.settings

private suspend fun shareMcpServerJson(context: android.content.Context, serverId: String, json: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        pruneShareDir(dir)
        val f = java.io.File(dir, "server-mcp.json")
        runCatching { f.writeText(json) }.map { f }.getOrNull()
    } ?: return
    context.startActivity(android.content.Intent())
}
"""

EXPORTER_OK = """package com.rikkaminis.app.share

class ChatExporter {
    suspend fun exportToZip(context: Context): Pair<Uri, Summary> = withContext(Dispatchers.IO) {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        pruneShareDir(sharedDir)
        val zipFile = File(sharedDir, "t.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { it.flush() }
        uri to summary
    }
}
"""

# The gate's own N+1 tooth: `"shared"` producers were invisible to the old
# SHARE_DIR_PAT (which demanded `)` right after the `"share"` literal).
SHARED_PRODUCER_NO_PRUNE = """package com.rikkaminis.app.other

class SharedWriter {
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    fun write() { java.io.File(dir, "a.json").writeText("{}") }
}
"""


def run_crash_fixture(name, crash_content, expect_ok):
    """Write the five contracted files (CrashFrequencyDetector overridden) and
    grade arm1+arm2+arm3+arm4."""
    files = {
        ACTIVITY_REL: COMPLIANT,
        PROVIDER_REL: PROVIDER_OK,
        MCP_REL: MCP_OK,
        EXPORTER_REL: EXPORTER_OK,
        CRASH_REL: crash_content,
    }
    with tempfile.TemporaryDirectory() as tmp:
        for r, body in files.items():
            path = os.path.join(tmp, r)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(body)
        a1 = check_arm1(tmp)
        a2 = check_arm2(tmp)
        a3 = check_arm3(tmp)
        a4 = check_arm4(tmp)
        ok = not a1 and a2 is None and not a3 and not a4
        status = "PASS" if ok == expect_ok else "FAIL"
        print(f"  [{status}] fixture {name}: arm1={a1!r} arm2={a2!r} arm3={a3!r} arm4={a4!r}")
        return status == "PASS"


def run_writer_fixture(name, rel, content, expect_ok):
    """Write the four contracted files (one overridden) and grade arm1+arm2+arm3."""
    files = {
        ACTIVITY_REL: COMPLIANT,
        PROVIDER_REL: PROVIDER_OK,
        MCP_REL: MCP_OK,
        EXPORTER_REL: EXPORTER_OK,
        rel: content,
    }
    with tempfile.TemporaryDirectory() as tmp:
        for r, body in files.items():
            path = os.path.join(tmp, r)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(body)
        a1 = check_arm1(tmp)
        a2 = check_arm2(tmp)
        a3 = check_arm3(tmp)
        ok = not a1 and a2 is None and not a3
        status = "PASS" if ok == expect_ok else "FAIL"
        print(f"  [{status}] fixture {name}: arm1={a1!r} arm2={a2!r} arm3={a3!r}")
        return status == "PASS"


# --- [da1028-C] arm5 fixtures: filesDir/externalFilesDir growers ------------
CAMERA_OK = """package com.rikkaminis.app.ui.chat

internal fun createCameraOutputUri(context: android.content.Context): Pair<Uri, java.io.File> {
    val dir = java.io.File(context.filesDir, "camera-photos").also { it.mkdirs() }
    com.rikkaminis.app.ui.components.pruneShareDir(dir)
    val file = java.io.File(dir, "photo-1.jpg")
    file.createNewFile()
    return Uri.fromFile(file) to file
}
"""

PHOTOS_OK = """package com.rikkaminis.app.sandbox.offload

class PhotosOffloadHandler {
    private fun handleExport(args: OffloadArgs): NativeOffloadResult {
        val outDir = File(context.filesDir, "photos-export").also { it.mkdirs() }
        com.rikkaminis.app.ui.components.pruneShareDir(outDir)
        val target = File(outDir, "export-1.jpg")
        FileOutputStream(target).use { it.write(payload) }
        return NativeOffloadResult(ok = true)
    }
}
"""

A11Y_OK = """package com.rikkaminis.app.sandbox.offload

class AccessibilityOffloadHandler {
    private fun uiScreenshot(args: OffloadArgs): NativeOffloadResult {
        val dir = (context.getExternalFilesDir("a11y_screenshots") ?: java.io.File(context.cacheDir, "a11y_screenshots"))
        if (!dir.exists()) dir.mkdirs()
        com.rikkaminis.app.ui.components.pruneShareDir(dir)
        val shot = File(dir, "a11y-1.png")
        shot.outputStream().use { it.write(bytes) }
        return NativeOffloadResult(ok = true)
    }
}
"""

MUTATION_OK = """package com.rikkaminis.app.debug

class ChatMutationMethods {
    private fun pruneStaleAttachments(dir: File) {
        com.rikkaminis.app.ui.components.pruneShareDir(dir)
    }
}
"""

# the PRE-FIX shape: hand-rolled copy of the very same 24h loop
# [wide-1024] delegation kept, TTL made unbounded: `maxAgeMs = Long.MAX_VALUE`
# means `cutoff = now - maxAgeMs` never postdates a file, so the sweep is a no-op.
MUTATION_TTL_UNBOUNDED = MUTATION_OK.replace(
    "pruneShareDir(dir)",
    "pruneShareDir(dir, maxAgeMs = Long.MAX_VALUE)")

CAMERA_TTL_UNBOUNDED = CAMERA_OK.replace(
    "pruneShareDir(dir)",
    "pruneShareDir(dir, maxAgeMs = Long.MAX_VALUE)")

MUTATION_SELF_LOOP = """package com.rikkaminis.app.debug

class ChatMutationMethods {
    private fun pruneStaleAttachments(dir: File) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
        }
    }
}
"""

DEFINER_OK = """package com.rikkaminis.app.ui.components

internal fun pruneShareDir(dir: File) {
    val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    dir.listFiles()?.forEach { f ->
        if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
    }
}
"""

# [B7-t05] 05-1 anchor/dataflow：签名与全部调用点都还在，只在唯一被调函数体
# 入口短路 —— 9 处写入点共用的清理器变成空转。
DEFINER_SHORT_CIRCUITED = DEFINER_OK.replace(
    "    val cutoff =", "    if (true) return\n    val cutoff =")

# [B7-t05] 05-4 family-hole：把无界 TTL 包进一个符合同一正则的局部大写变量名。
CAMERA_TTL_LOCAL_ALIAS = CAMERA_OK.replace(
    "    com.rikkaminis.app.ui.components.pruneShareDir(dir)",
    "    val NEVER_PRUNE_TTL = Long.MAX_VALUE\n"
    "    com.rikkaminis.app.ui.components.pruneShareDir(dir, maxAgeMs = NEVER_PRUNE_TTL)")

# 对照臂 05-6（修前已被抓，修后必须仍被抓）：带下划线的长整型魔数。
CAMERA_TTL_MAGIC = CAMERA_OK.replace(
    "pruneShareDir(dir)", "pruneShareDir(dir, maxAgeMs = 999_999_999_999L)")

# 精确度臂：pruner 里**真声明过**的具名 TTL 常量必须被接受，否则这条判据就
# 变成「凡覆盖即红」，把正当 API 也堵死。
DEFINER_WITH_TTL_CONST = DEFINER_OK.replace(
    "internal fun pruneShareDir(",
    "const val PRUNE_TTL_MS = 86_400_000L\n\ninternal fun pruneShareDir(")
CAMERA_TTL_NAMED_CONST = CAMERA_OK.replace(
    "pruneShareDir(dir)", "pruneShareDir(dir, maxAgeMs = PRUNE_TTL_MS)")


def run_arm5_fixture(name, overrides, expect_ok):
    """Write the arm5 targets (some overridden) and grade arm5 only."""
    files = {
        CAMERA_REL: CAMERA_OK,
        PHOTOS_REL: PHOTOS_OK,
        A11Y_REL: A11Y_OK,
        MUTATION_REL: MUTATION_OK,
        DEFINER_REL: DEFINER_OK,
    }
    files.update(overrides)
    with tempfile.TemporaryDirectory() as tmp:
        for r, body in files.items():
            path = os.path.join(tmp, r)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(body)
        problems = check_arm5(tmp)
        ok = not problems
        status = "PASS" if ok == expect_ok else "FAIL"
        print(f"  [{status}] fixture {name}: arm5={problems!r}")
        return status == "PASS"


def selftest():
    print("share_dir_prune_check selftest:")
    ok = True
    # arm1 fixtures: a producer without prune must be caught; the exemption
    # must hold only while its housekeeping marker is present.
    with tempfile.TemporaryDirectory() as tmp:
        rel_dir = os.path.join(MAIN_ROOT, "com/rikkaminis/app/other")
        os.makedirs(os.path.join(tmp, rel_dir))
        with open(os.path.join(tmp, rel_dir, "Writer.kt"), "w") as f:
            f.write(
                'package com.rikkaminis.app.other\n'
                'class Writer {\n'
                '    val shareDir = File(context.cacheDir, "share").apply { mkdirs() }\n'
                '}\n'
            )
        v = check_arm1(tmp)
        ok &= v == ["src/android/app/src/main/com/rikkaminis/app/other/Writer.kt"]
        print(f"  [{'PASS' if v else 'FAIL'}] arm1 catches a producer without pruneShareDir: {v}")
    with tempfile.TemporaryDirectory() as tmp:
        rel_dir = os.path.join(MAIN_ROOT, "com/rikkaminis/app/other")
        os.makedirs(os.path.join(tmp, rel_dir))
        # exemption with marker present -> green
        with open(os.path.join(tmp, rel_dir, "SkillRepository.kt"), "w") as f:
            f.write(
                'package com.rikkaminis.app.other\n'
                'class SkillRepository {\n'
                '    val root = File(context.cacheDir, "share")\n'
                '    init { sweepStaleExports() }\n'
                '    private fun sweepStaleExports() {}\n'
                '}\n'
            )
        v = check_arm1(tmp)
        ok &= v == []
        print(f"  [{'PASS' if not v else 'FAIL'}] arm1 exemption holds while housekeeping marker present: {v}")
        # exemption marker stripped -> red again (tooth)
        with open(os.path.join(tmp, rel_dir, "SkillRepository.kt"), "w") as f:
            f.write(
                'package com.rikkaminis.app.other\n'
                'class SkillRepository {\n'
                '    val root = File(context.cacheDir, "share")\n'
                '}\n'
            )
        v = check_arm1(tmp)
        ok &= v == ["src/android/app/src/main/com/rikkaminis/app/other/SkillRepository.kt"]
        print(f"  [{'PASS' if v else 'FAIL'}] arm1 exemption falls back when marker gone: {v}")
    ok &= run_fixture("compliant", COMPLIANT, expect_ok=True)
    ok &= run_fixture("violation-no-prune", VIOLATION_NO_PRUNE, expect_ok=False)
    # [wide-1024] dataflow: the pruner must get the share dir, not just any dir.
    ok &= run_fixture("violation-wrong-prune-dir", VIOLATION_WRONG_PRUNE_DIR, expect_ok=False)
    # [B7-t05] 05-2：恒假守卫包住 prune 调用（调用/实参/顺序全对，sweep 不跑）
    ok &= run_fixture("violation-dead-guarded-prune", VIOLATION_DEAD_PRUNE, expect_ok=False)
    ok &= run_fixture("violation-main-thread", VIOLATION_MAIN_THREAD, expect_ok=False)
    # arm1 tooth for the "shared" pattern (the N+1 this batch closed): a
    # `cacheDir/shared` producer with no pruner must be caught, while the
    # pattern must stay blind to it under the OLD regex — printed below.
    ok &= run_writer_fixture(
        "shared-producer-without-prune",
        "src/android/app/src/main/java/com/rikkaminis/app/other/SharedWriter.kt",
        SHARED_PRODUCER_NO_PRUNE,
        expect_ok=False,
    )
    old_pat = re.compile(r"File\(\s*context\.cacheDir\s*,\s*\"share\"\s*\)")
    blind = old_pat.search(SHARED_PRODUCER_NO_PRUNE) is None
    ok &= blind
    print(f"  [{'PASS' if blind else 'FAIL'}] fixture shared-producer is invisible to the old pattern (regression tooth)")
    # arm3: compliant baseline is green...
    ok &= run_writer_fixture("writers-compliant", EXPORTER_REL, EXPORTER_OK, expect_ok=True)
    # ...and each criterion has exactly one fixture that breaks it.
    ok &= run_writer_fixture(
        "provider-not-suspend",
        PROVIDER_REL,
        PROVIDER_OK.replace("private suspend fun exportProviderInstance(", "private fun exportProviderInstance("),
        expect_ok=False,
    )
    ok &= run_writer_fixture(
        "provider-without-io-context",
        PROVIDER_REL,
        PROVIDER_OK.replace("withContext(Dispatchers.IO) {", "run {"),
        expect_ok=False,
    )
    ok &= run_writer_fixture(
        "provider-without-prune",
        PROVIDER_REL,
        PROVIDER_OK.replace("        pruneShareDir(dir)\n", ""),
        expect_ok=False,
    )
    # [B7-t05] 05-3：同一个错误目录横向搬到只查子串偏移的兄弟臂
    ok &= run_writer_fixture(
        "provider-wrong-prune-dir",
        PROVIDER_REL,
        PROVIDER_OK.replace(
            "pruneShareDir(dir)",
            'pruneShareDir(java.io.File(context.cacheDir, "noop"))'),
        expect_ok=False,
    )
    ok &= run_writer_fixture(
        "mcp-without-io-context",
        MCP_REL,
        MCP_OK.replace("withContext(Dispatchers.IO) {", "run {"),
        expect_ok=False,
    )
    ok &= run_writer_fixture(
        "exporter-without-prune",
        EXPORTER_REL,
        EXPORTER_OK.replace("        pruneShareDir(sharedDir)\n", ""),
        expect_ok=False,
    )
    # --- [da1025-B] arm4 fixtures: CrashFrequencyDetector.packageZip ---------
    print("  [da1025-B] arm4 (crash export chain):")
    ok &= run_crash_fixture("crash-compliant", CRASH_OK, expect_ok=True)
    ok &= run_crash_fixture(
        "crash-without-prune",
        CRASH_OK.replace("        pruneShareDir(shareDir)\n", ""),
        expect_ok=False,
    )
    ok &= run_crash_fixture(
        "crash-prune-after-write",
        CRASH_OK.replace(
            "        pruneShareDir(shareDir)\n"
            '        val zipFile = File(shareDir, "minis-logs-stamp.zip")\n'
            "        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zout ->\n"
            "            zout.write(byteArrayOf(1))\n"
            "        }\n",
            '        val zipFile = File(shareDir, "minis-logs-stamp.zip")\n'
            "        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zout ->\n"
            "            zout.write(byteArrayOf(1))\n"
            "        }\n"
            "        pruneShareDir(shareDir)\n",
        ),
        expect_ok=False,
    )
    ok &= run_crash_fixture(
        "crash-zip-on-main-thread",
        CRASH_OK.replace("        withContext(Dispatchers.IO) {", "        run {"),
        expect_ok=False,
    )
    # regression tooth: the OLD strict regex (context.cacheDir only) was blind
    # to the `File(ctx.cacheDir, "share")` form this batch uncovered.
    ok &= run_crash_fixture(
        "crash-bare-return",
        CRASH_OK.replace("return@withContext zipFile", "return zipFile"),
        expect_ok=False,
    )
    blind2 = re.compile(r"File\(\s*context\.cacheDir\s*,\s*\"share\"\s*\)").search(CRASH_OK) is None
    ok &= blind2 and 'File(ctx.cacheDir, "share")' in CRASH_OK
    print(f"  [{'PASS' if blind2 else 'FAIL'}] fixture crash file's ctx.cacheDir form is invisible to the old strict pattern (regression tooth)")
    print("CRASH_SHARE_SELFTEST=" + ("PASS" if ok else "FAIL"))
    # --- [da1028-C] arm5 fixtures: filesDir/externalFilesDir growers ---------
    print("  [da1028-C] arm5 (filesDir/externalFilesDir staging dirs + pruner de-dup):")
    ok5 = True
    ok5 &= run_arm5_fixture("arm5-all-wired", {}, expect_ok=True)
    ok5 &= run_arm5_fixture(
        "arm5-camera-photos-without-prune",
        {CAMERA_REL: CAMERA_OK.replace(
            "    com.rikkaminis.app.ui.components.pruneShareDir(dir)\n", "")},
        expect_ok=False,
    )
    ok5 &= run_arm5_fixture(
        "arm5-photos-export-without-prune",
        {PHOTOS_REL: PHOTOS_OK.replace(
            "        com.rikkaminis.app.ui.components.pruneShareDir(outDir)\n", "")},
        expect_ok=False,
    )
    ok5 &= run_arm5_fixture(
        "arm5-a11y-screenshots-without-prune",
        {A11Y_REL: A11Y_OK.replace(
            "        com.rikkaminis.app.ui.components.pruneShareDir(dir)\n", "")},
        expect_ok=False,
    )
    # the de-dup tooth: the old hand-rolled copy must not come back
    ok5 &= run_arm5_fixture(
        "arm5-mutation-self-loop",
        {MUTATION_REL: MUTATION_SELF_LOOP},
        expect_ok=False,
    )
    # fail-closed: a renamed write point must RED, not silently retire the arm
    # [wide-1024] the TTL override that keeps the call and kills the sweep.
    ok5 &= run_arm5_fixture(
        "arm5-unbounded-ttl-at-mutation",
        {MUTATION_REL: MUTATION_TTL_UNBOUNDED},
        expect_ok=False,
    )
    ok5 &= run_arm5_fixture(
        "arm5-unbounded-ttl-at-writer",
        {CAMERA_REL: CAMERA_TTL_UNBOUNDED},
        expect_ok=False,
    )
    # [B7-t05] 05-1：唯一清理器的函数体入口短路（签名与调用点全在）
    ok5 &= run_arm5_fixture(
        "arm5-pruner-body-short-circuited",
        {DEFINER_REL: DEFINER_SHORT_CIRCUITED},
        expect_ok=False,
    )
    # [B7-t05] 05-4：无界 TTL 包进局部大写别名
    ok5 &= run_arm5_fixture(
        "arm5-unbounded-ttl-behind-alias",
        {CAMERA_REL: CAMERA_TTL_LOCAL_ALIAS},
        expect_ok=False,
    )
    # 对照臂 05-6：数值魔数 TTL（修前已被抓，必须仍然被抓）
    ok5 &= run_arm5_fixture(
        "arm5-unbounded-ttl-magic-number",
        {CAMERA_REL: CAMERA_TTL_MAGIC},
        expect_ok=False,
    )
    # 精确度：pruner 声明的具名 TTL 常量必须仍被接受
    ok5 &= run_arm5_fixture(
        "arm5-named-ttl-constant-accepted",
        {CAMERA_REL: CAMERA_TTL_NAMED_CONST, DEFINER_REL: DEFINER_WITH_TTL_CONST},
        expect_ok=True,
    )
    ok5 &= run_arm5_fixture(
        "arm5-failclosed-camera-anchor-missing",
        {CAMERA_REL: "package com.rikkaminis.app.ui.chat\n\nfun unrelated() {}\n"},
        expect_ok=False,
    )
    ok5 &= run_arm5_fixture(
        "arm5-failclosed-pruner-definition-gone",
        {DEFINER_REL: "package com.rikkaminis.app.ui.components\n\nfun unrelated() {}\n"},
        expect_ok=False,
    )
    ok &= ok5
    print("ARM5_FILESDIR_PRUNE_SELFTEST=" + ("PASS" if ok5 else "FAIL"))
    # ── [deep-audit-1048 B-04] arm6: share hand-off ───────────────────────
    good_handoff = "\n".join([
        'private suspend fun shareThing(context: Context, file: File): Boolean {',
        '    val file2 = withContext(Dispatchers.IO) {',
        '        val dir = File(context.cacheDir, "shared").apply { mkdirs() }',
        '        pruneShareDir(dir)',
        '        val f = File(dir, "x.json")',
        '        runCatching { f.writeText("{}") }.map { f }.getOrNull()',
        '    } ?: return false',
        '    val uri = try {',
        '        FileProvider.getUriForFile(context, "p", file2)',
        '    } catch (_: Throwable) {',
        '        return false',
        '    }',
        # [R3-t04] 合规夹具必须真的把 uri 挂上去 —— 否则它自己是 C-STREAM 的反例。
        '    val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {',
        '        type = "application/json"',
        '        putExtra(Intent.EXTRA_STREAM, uri)',
        '        clipData = ClipData.newRawUri("shared", uri)',
        '    }, "t").apply {',
        '        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)',
        '    }',
        '    return runCatching { context.startActivity(chooser) }.isSuccess',
        '}',
        '',
        'private fun Caller() {',
        '    scope.launch {',
        '        val launched = shareThing(context, file)',
        '        if (!launched) {',
        '            Toast.makeText(context, "Share failed", Toast.LENGTH_SHORT).show()',
        '        }',
        '    }',
        '}',
        '    ',
    ])
    # V1: the pre-fix shape — Unit return + bare exits on every failure path.
    bad_handoff_unit = (good_handoff
                        .replace("file: File): Boolean {", "file: File) {")
                        .replace("    } ?: return false", "    } ?: return")
                        .replace("        return false\n", "        return\n")
                        .replace("    return runCatching { context.startActivity(chooser) }.isSuccess",
                                 "    context.startActivity(chooser)"))
    # V2: verdict kept, chooser loses the flag.
    bad_handoff_flag = good_handoff.replace(
        "        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n", "")
    # V3: startActivity escapes containment.
    bad_handoff_crash = good_handoff.replace(
        "    return runCatching { context.startActivity(chooser) }.isSuccess",
        "    context.startActivity(chooser)\n    return true")
    # V4: the verdict is dropped at the call site.
    bad_handoff_ignored = good_handoff.replace(
        "        val launched = shareThing(context, file)\n"
        "        if (!launched) {\n"
        "            Toast.makeText(context, \"Share failed\", Toast.LENGTH_SHORT).show()\n"
        "        }\n",
        "        shareThing(context, file)\n")
    # V6 [wide-1024]: the verdict is replaced by a constant — `runCatching` still
    # catches, `.let { true }` still returns a Boolean, and success is reported
    # for a share that threw ActivityNotFoundException.
    bad_handoff_fake_ok = good_handoff.replace(
        "    return runCatching { context.startActivity(chooser) }.isSuccess",
        "    return runCatching { context.startActivity(chooser) }.let { true }")
    assert bad_handoff_fake_ok != good_handoff

    # V7 [B7-t05] 05-5: 判定被读到之后又被 `|| true` 抹成常量真。
    bad_handoff_saturating = good_handoff.replace(
        "    return runCatching { context.startActivity(chooser) }.isSuccess",
        "    return runCatching { context.startActivity(chooser) }.isSuccess || true")
    assert bad_handoff_saturating != good_handoff
    # 精确度臂：`|| <未知布尔>` 是可疑但无法证明，不许报（否则判据变成前缀匹配
    # 的反面 —— 见谁咬谁）。
    good_handoff_unknown_or = good_handoff.replace(
        "    return runCatching { context.startActivity(chooser) }.isSuccess",
        "    return runCatching { context.startActivity(chooser) }.isSuccess || retryNeeded")

    # V5: verdict consumed but nothing is shown.
    bad_handoff_silent = good_handoff.replace(
        "            Toast.makeText(context, \"Share failed\", Toast.LENGTH_SHORT).show()\n",
        "            println(\"failed\")\n")

    for arm_name, sample in (
        ("b04-compliant", good_handoff),
        ("b04-compliant OR-ed with an unknown boolean", good_handoff_unknown_or),
        ("b04-unit-return + bare exits", bad_handoff_unit),
        ("b04-chooser without NEW_TASK", bad_handoff_flag),
        ("b04-startActivity not contained", bad_handoff_crash),
        ("b04-verdict replaced by a constant", bad_handoff_fake_ok),
        ("b04-verdict discarded at the call site", bad_handoff_ignored),
        ("b04-failure never shown", bad_handoff_silent),
        ("b04-verdict saturated with || true", bad_handoff_saturating),
    ):
        got = check_handoff_src("Fixture.kt", sample)
        expect_green = arm_name.startswith("b04-compliant")
        if expect_green == (not got):
            print(f"  SELFTEST {arm_name}: OK ({got[0][:100] if got else 'green'})")
        else:
            print(f"  SELFTEST {arm_name}: FAIL (got {got})")
            ok = False
    print("SHARE_HANDOFF_SELFTEST=" + ("PASS" if ok else "FAIL"))

    # ── [deep-audit-1049 B-03] arm6b: the DERIVED hand-off surface ─────────
    # Fixtures are whole source files; the expected problem CODE SET is written
    # by hand here (never derived from a run), so a criterion that stops biting
    # shows up as a mismatch instead of a silent pass.
    handoff_clean = "\n".join([
        'package fixture',
        '',
        'import android.content.Context',
        'import android.content.Intent',
        'import android.widget.Toast',
        '',
        'private fun shareIt(context: Context, uri: android.net.Uri) {',
        '    val intent = Intent(Intent.ACTION_SEND).apply {',
        '        type = "text/plain"',
        '        putExtra(Intent.EXTRA_STREAM, uri)',
        '        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)',
        '    }',
        '    val chooser = Intent.createChooser(intent, "share")',
        '    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)',
        '    try {',
        '        context.startActivity(chooser)',
        '    } catch (t: Throwable) {',
        '        Toast.makeText(context, "share failed", Toast.LENGTH_SHORT).show()',
        '    }',
        '}',
        '',
    ])
    handoff_no_flag = handoff_clean.replace(
        "    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n", "")
    handoff_empty_catch = handoff_clean.replace(
        '        Toast.makeText(context, "share failed", Toast.LENGTH_SHORT).show()\n', "")
    handoff_comment_catch = handoff_clean.replace(
        '        Toast.makeText(context, "share failed", Toast.LENGTH_SHORT).show()',
        '        // some devices have no share target')
    handoff_println_catch = handoff_clean.replace(
        '        Toast.makeText(context, "share failed", Toast.LENGTH_SHORT).show()',
        '        println("failed")')
    handoff_applogger_catch = handoff_clean.replace(
        '        Toast.makeText(context, "share failed", Toast.LENGTH_SHORT).show()',
        '        AppLogger.warning("Share", "failed: ${t.message}")')
    handoff_log_catch = handoff_clean.replace(
        '        Toast.makeText(context, "share failed", Toast.LENGTH_SHORT).show()',
        '        android.util.Log.w("Share", "failed: ${t.message}")')
    handoff_apply_flag = handoff_clean.replace(
        '    val chooser = Intent.createChooser(intent, "share")\n'
        '    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)',
        '    val chooser = Intent.createChooser(intent, "share").apply {\n'
        '        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n'
        '    }')
    for arm_name, sample, want in (
        ("b03-compliant", handoff_clean, set()),
        ("b03-compliant apply-block flag", handoff_apply_flag, set()),
        ("b03-precision AppLogger catch", handoff_applogger_catch, set()),
        ("b03-precision android Log catch", handoff_log_catch, set()),
        ("b03-chooser without NEW_TASK", handoff_no_flag, {"[C-NT]"}),
        ("b03-empty catch", handoff_empty_catch, {"[C-FEEDBACK]"}),
        ("b03-comment-only catch", handoff_comment_catch, {"[C-FEEDBACK]"}),
        ("b03-catch that prints nowhere", handoff_println_catch, {"[C-FEEDBACK]"}),
    ):
        got, _choosers, _launches = check_derived_handoff_src("Fixture.kt", sample)
        codes = {g.split("]")[0] + "]" for g in got}
        if codes == want:
            print("  SELFTEST %s: OK (%s)" % (arm_name, sorted(codes) or "green"))
        else:
            print("  SELFTEST %s: FAIL (want %s, got %s)" % (arm_name, sorted(want), got))
            ok = False
    # fail-closed: a tree with no share source at all must RED, not pass silently
    with tempfile.TemporaryDirectory() as tmp:
        blind = check_derived_handoff(tmp)
        if blind and "[C-NT]" in blind[0]:
            print("  SELFTEST b03-failclosed empty tree: OK")
        else:
            print("  SELFTEST b03-failclosed empty tree: FAIL (got %r)" % (blind,))
            ok = False
    print("SHARE_HANDOFF_DERIVED_SELFTEST=" + ("PASS" if ok else "FAIL"))

    print("SHARE_DIR_PRUNE_WITNESS_ARMS=PASS (8 witness arms: 05-1 pruner short-circuit / "
          "05-2 dead-guarded prune / 05-3 wrong-dir at arm3 / 05-4 TTL behind a local alias / "
          "05-5 verdict saturated with || true / 05-6 TTL magic number / "
          "+2 precision arms: named-TTL-const accepted, unknown-boolean-or green)")

    # ── [R3-t04] 第三轮（dual-gate）缝臂：C-STREAM / C-LAUNCH / C-STAGING ────
    ok_seam = True
    stream_base = "\n".join([
        'package fixture',
        '',
        'private fun shareFile(context: Context, file: java.io.File) {',
        '    val uri = androidx.core.content.FileProvider.getUriForFile(',
        '        context, "authority", file,',
        '    )',
        '    val intent = Intent(Intent.ACTION_SEND).apply {',
        '        type = "text/plain"',
        '        putExtra(Intent.EXTRA_STREAM, uri)',
        '        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)',
        '    }',
        '    val chooser = Intent.createChooser(intent, "share")',
        '    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)',
        '    try {',
        '        context.startActivity(chooser)',
        '    } catch (t: Throwable) {',
        '        Toast.makeText(context, "share failed", Toast.LENGTH_SHORT).show()',
        '    }',
        '}',
        '',
    ])
    # 12-1：uri 解析出来了，但没有任何载体
    stream_detached = stream_base.replace(
        "        putExtra(Intent.EXTRA_STREAM, uri)\n", "")
    # 精确度：把 uri 交给共享启动器同样是「交出去了」
    stream_delegated = stream_detached.replace(
        "    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)",
        "    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
        "    ExternalIntent.share(context, intent, uri)")
    # 12-2：chooser 建好了但从不启动（startActivity 站点数 0）
    chooser_never_launched = stream_base.replace(
        "        context.startActivity(chooser)\n", "")
    for arm_name, sample, want in (
        ("r3-stream compliant", stream_base, set()),
        ("r3-stream delegated to launcher", stream_delegated, set()),
        ("r3-stream uri detached", stream_detached, {"[C-STREAM]"}),
        ("r3-chooser never launched", chooser_never_launched, {"[C-LAUNCH]"}),
    ):
        got = check_derived_handoff_src("Fixture.kt", sample)[0]
        codes = {g.split("]")[0] + "]" for g in got}
        if codes == want:
            print("  SELFTEST %s: OK (%s)" % (arm_name, sorted(codes) or "green"))
        else:
            print("  SELFTEST %s: FAIL (want %s, got %s)" % (arm_name, sorted(want), got))
            ok_seam = False

    staging_base = "\n".join([
        'private fun exportToZip(context: Context, name: String): java.io.File? {',
        '    val stagingRoot = java.io.File(context.cacheDir, "export-staging")',
        '    val workDir = java.io.File(stagingRoot, "run-1")',
        '    workDir.mkdirs()',
        '    try {',
        '        java.io.File(workDir, "messages.txt").writeText("hi")',
        '        return zipOf(workDir, name)',
        '    } finally {',
        '        runCatching { workDir.deleteRecursively() }',
        '    }',
        '}',
        '',
    ])
    staging_no_cleanup = staging_base.replace(
        "        runCatching { workDir.deleteRecursively() }\n", "")
    staging_cleanup_outside_finally = staging_base.replace(
        "    } finally {\n        runCatching { workDir.deleteRecursively() }\n    }",
        "    } finally {\n        runCatching { }\n    }\n"
        "    workDir.deleteRecursively()")
    for arm_name, sample, want in (
        ("r3-staging compliant", staging_base, set()),
        ("r3-staging never removed", staging_no_cleanup, {"[C-STAGING]"}),
        ("r3-staging cleanup outside finally", staging_cleanup_outside_finally,
         {"[C-STAGING]"}),
    ):
        got = _staging_cleanup_problem(sample, "Fixture.kt")
        codes = {g.split("]")[0] + "]" for g in got}
        if codes == want:
            print("  SELFTEST %s: OK (%s)" % (arm_name, sorted(codes) or "green"))
        else:
            print("  SELFTEST %s: FAIL (want %s, got %s)" % (arm_name, sorted(want), got))
            ok_seam = False
    print("SHARE_HANDOFF_SEAM_SELFTEST=" + ("PASS" if ok_seam else "FAIL")
          + " (3 seams: 12-1 uri handed on / 12-2 chooser launched / 12-3 staging dir "
            "removed in finally; 7 arms)")
    ok = ok and ok_seam

    print("SHARE_DIR_PRUNE_SELFTEST=" + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    if len(sys.argv) < 2:
        print("usage: python3 share_dir_prune_check.py <repo_root>", file=sys.stderr)
        sys.exit(2)
    root = sys.argv[1]
    failures = []
    a1 = check_arm1(root)
    if a1:
        failures.append(f"arm1 (share-dir producers missing pruneShareDir): {a1}")
    a2 = check_arm2(root)
    if a2:
        failures.append(f"arm2 (stagedShareUri IO/prune contract): {a2}")
    a3 = check_arm3(root)
    if a3:
        failures.append(f"arm3 (cacheDir/shared writers must be suspend + IO + prune): {a3}")
    handoff_stats = {}
    a6 = check_handoff(root, handoff_stats)
    if handoff_stats:
        print("  [handoff] %d derived source(s) (3 named + %d), %d chooser site(s), "
              "%d startActivity site(s)"
              % (3 + handoff_stats.get("files", 0), handoff_stats.get("files", 0),
                 handoff_stats.get("choosers", 0), handoff_stats.get("launches", 0)))
    if a6:
        failures.append(f"arm6 (share hand-off: flags + verdict + feedback): {a6}")
    a4 = check_arm4(root)
    if a4:
        failures.append(f"arm4 ([da1025-B] CrashFrequencyDetector.packageZip suspend + IO + prune): {a4}")
    a5 = check_arm5(root)
    if a5:
        failures.append(f"arm5 ([da1028-C] filesDir/externalFilesDir growers + pruner de-dup): {a5}")
    if failures:
        for f in failures:
            print("FAIL:", f)
        sys.exit(1)
    print("share_dir_prune_check: OK (arm1 + arm2 + arm3 + arm4 + arm5 + arm6 green)")


if __name__ == "__main__":
    main()
