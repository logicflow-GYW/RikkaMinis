#!/usr/bin/env python3
"""
provider_rpc_symmetry_check.py — deep-audit-1025 FIX-I gate
                                (+ deep-audit-1026 ISSUE-05 C4/C5).

[da1025-I] Provider RPC 层守卫/字段不对称（debug vs mutation）的三条判据：

  C1  customBaseURL 支持谓词单源化：
      - `val ProviderType.supportsCustomBaseURL` 在全仓恰好定义一次，且位于
        ProviderMutationMethods.kt；
      - ProviderDebugMethods.kt / ProviderMutationMethods.kt 的代码（剥注释后）
        不再含内联 `type == ProviderType.openAI || type == ProviderType.anthropic`
        形状（openAI 在前 — supportedCredentials 的 oauth 行是 anthropic 在前，
        不受此判据约束）；
      - typeSpec（Debug）与 instancesCreate 守卫（Mutation）引用
        supportsCustomBaseURL。
  C2  instancesUpdate 守卫：函数体内引用 supportsCustomBaseURL，且 openRouter
      守卫消息来源单一 —— 要么与 instancesCreate 逐字一致的字面量
      "customBaseURL is not supported for providerType=openRouter"，要么
      [ARCH-01] 引用共享常量 CUSTOM_BASE_URL_OPENROUTER_MESSAGE
      （ProviderWriteRules.kt 单源；create/update 两面都不许再写字面量拷贝，
      也不许两面对不上）。
  C3  instanceToJson（或其委托的 instanceJsonCore）的 put 键集合 ⊇
      instancesList 每实例 JSONObject 的 put 键集合（fail-closed）。

[da1026-ISSUE-05] 第三张脸（config collection）接入同一条谓词，两条判据：

  C4  ProvidersCollection.kt 的每个 customBaseURL 写入点，其**所在函数体**
      必须引用守卫 token（supportsCustomBaseURL / customBaseURLWritable /
      resolveAddCustomBaseURL / [ARCH-01] isCustomBaseURLWriteAllowed）。
      fail-closed：剥注释后找不到任何写入点 ⇒ 红。
      写入点两形态：`copy(customBaseURL = …)`（字段 writer）与
      `customBaseURL = <expr>`（add 里的 ProviderInstance 构造实参）。
  C5  ProviderMutationMethods.kt 的 `supportsCustomBaseURL` 出现次数 ≥
      基线下限（防「把谓词搬到别处 / 内联否定掉」后静默退化）。

Selftest：≥4 夹具，每条判据至少一个「恰好被它打破」的形态。
注：C5 与 C1/C2 共享同一 token，协同变化（见 F10 注释）——它作为「下限守卫」
而非独立可分辨判据存在，报告里如实分层。

[da1030-C ISSUE-04] 第四组：实例字段在**四张脸**上的可见性/可写性对称。
实例级字段只有同时出现在「读两面（debug 列表 + mutation 响应）」「写两面
（create + update）」才算接通；缺哪面都在另一面撒谎。

  C6  azureMode 四面对称：
      - instancesList 每实例块含 `put("azureMode"`；
      - instanceJsonCore apply 块含 `put("azureMode"`；
      - instancesCreate / instancesUpdate 体含 `azureMode` 且按
        `optBoolean("azureMode"` 解析（缺 = 该字段对 harness 只读不可写）。
  C7  instancesTest 的认证头分叉：函数体必须**按 instance.azureMode 分叉**——
      含 `azureMode`，且认证头来源在允许清单内
      （`probeAuthHeader(` / 共享单源 `applyOpenAIKeyAuth(` / 字面 `"api-key"`）。
      允许清单是刻意的：单源化落地后形状合法，但「不分叉地一律 Bearer」必须红。
  C10 [da1031-D ISSUE-07] 认证头**只有一个本体**：OpenAIAuthHeader.kt 必须定义
      `openAIKeyAuthHeader(…)`，且它的 `applyOpenAIKeyAuth` 与 debug 面的
      `probeAuthHeader` 都必须**委托**它、自己不再内联 `"api-key"` 字面（表达式体
      函数用 decl_window 取窗口，extract_function 的括号配平取不到）。
  C8  config 面（ConfigBuiltins.kt）`providers` 的**实例字典块**（`path =
      "providers"` 之后最近的 `linkedMapOf(`，括号配平）含 `"azureMode"` /
      `"imageEndpointMode"` / `"imageEndpointResolved"` 三键（与 Debug RPC 的
      实例键集对齐）。锚点缺失 = fail-closed 红。
  C9  ProviderConnectionScreen.kt 的 customUserAgent 可见性：`customUserAgentWritable(`
      必须存在（fail-closed），且**不得**落在
      `if (instance.providerType != ProviderType.openRouter)` 的块体内——UA 覆盖
      在 ProviderFactory 的 openRouter 臂上同样生效，藏在 base-URL 门控里 = 用户
      改不到一个真被发送的字段。守卫块整体消失同样 fail-closed 红（须重新推导）。

[dv24-A05] 第五组：config 面的 `session.*` 写入必须**写穿**到驻留 VM。

  C18 `ConfigBuiltins` 的 `session.thinkingLevel` / `session.primaryModel`
      writer 必须同时具备两半（合取，缺一即红）：
        - Room 写（`updateThinkingOverride(` / `updateSessionBinding(`）——
          行是唯一跨重启的存储；
        - 驻留 `ChatViewModel` 的刷新——经**非创建型**查找
          （`peekViewModel(`，允许一层同文件委托）拿到 VM，再走 VM 自己的入口
          （`setThinkingLevel` / `restoreFromBinding` / `applyNewChatDefaultModel`
          / `select*` 家族）。
      `session.primaryModel` 的清空/设置两支**分别**判定（`return@ClosureField`
      切界）——只补一支 = 另一支仍然静默。两个 writer 都必须保留
      `No active session` 拒绝契约。
      `ChatViewModelStore.peekViewModel` 的**函数体**不得含任何分配路径
      （`getOrPut(` / `ViewModelProvider` / `.put(`）：刷新一个不在屏上的会话
      必须是无操作，不能凭空 materialise 一个 ChatViewModel。
      原因：`ChatViewModel` 只在 `loadSession()` 读一次这两个字段，且 store
      全进程缓存实例 ⇒ 只写行 = 界面永远看不到配置写入，用户随后点选模型
      还会把内存旧值写回覆盖行。

用法：
  python3 provider_rpc_symmetry_check.py <repo_root>   # 真树模式（位置参数）
  python3 provider_rpc_symmetry_check.py --selftest    # 夹具模式
退出码：0 = 全绿；1 = 有判据红；2 = 用法错误。
锚点缺失 = 红（fail-closed），不许静默跳过。
"""

import os
import re
import sys  # [dv20-a1 A-02] fixture re-anchor
import json
import shutil
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_ast import (                                        # noqa: E402
    const_bool,
    const_value,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_lexer import analyse                                # noqa: E402
from _kotlin_valueflow import (                                  # noqa: E402
    call_args,
    contains_ident,
    live_positions,
    value_sources,
)

DEBUG_FILE = "ProviderDebugMethods.kt"
MUTATION_FILE = "ProviderMutationMethods.kt"
PROVIDERS_COLLECTION_FILE = "ProvidersCollection.kt"
PREDICATE_DEF_PAT = re.compile(r"val\s+ProviderType\.supportsCustomBaseURL")
# openAI-first order: only the customBaseSupported hardcode uses this order.
INLINE_HARDCODE = "type == ProviderType.openAI || type == ProviderType.anthropic"
OPENROUTER_MSG = "customBaseURL is not supported for providerType=openRouter"
# [ARCH-01] The shared wording constant. Word-boundary anchored: a drifted copy
# named `…_MESSAGE_V2` must NOT satisfy the C2 arm (fixture F14 pins this).
SHARED_MSG_REF = re.compile(r"\bCUSTOM_BASE_URL_OPENROUTER_MESSAGE\b")
SELFTEST_MARKER = "PROVIDER_RPC_SELFTEST=PASS"

# ---- [da1026-ISSUE-05] collection-face write sites & guard tokens ----
# A write site is either the field writer's `mutate(id) { it.copy(customBaseURL = …) }`
# or the `add` payload's `customBaseURL = <expr>,` constructor argument. A bare
# declaration (`val customBaseURL = …`) is NOT a write — hence the line-anchored
# form for the constructor case (rules out `val …`).
BASE_URL_WRITE_PATS = (
    re.compile(r"copy\(\s*customBaseURL\s*="),
    re.compile(r"^\s*customBaseURL\s*=\s*[A-Za-z_]", re.M),
)
# Any of these inside the enclosing function body counts as the guard being wired.
# [ARCH-01] isCustomBaseURLWriteAllowed (ProviderWriteRules.kt) is the shared
# predicate the collection writer now routes through — it encodes the same
# rule, so it counts as a wired guard exactly like the raw extension.
BASE_URL_GUARD_TOKENS = (
    "supportsCustomBaseURL",
    "customBaseURLWritable",
    "resolveAddCustomBaseURL",
    "isCustomBaseURLWriteAllowed",
)
# Baseline use-count floor in ProviderMutationMethods.kt (create + update + def).
MUTATION_PREDICATE_MIN_USES = 3
FUN_DECL_PAT = re.compile(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")

# ---- [da1030-C ISSUE-04] instance-field face symmetry (C6-C9) ----
CONFIG_BUILTINS_FILE = "ConfigBuiltins.kt"

# ---- [dv24-A05] session config write-through (C18) ----
# (label, `path = "…"` marker in ConfigBuiltins, the DAO write token that field
#  must keep). Both rows of this table are judged independently.
SESSION_FIELDS = (
    ("thinkingLevel", 'path = "session.thinkingLevel"', "updateThinkingOverride("),
    ("primaryModel", 'path = "session.primaryModel"', "updateSessionBinding("),
)
# The VM-side entries that legitimately apply session config to the live chat:
# the thinking picker's own entry, both priorities of loadSession's model chain,
# and the explicit model pickers. Anchored on the FAMILY — a rename inside it
# must not turn this criterion into a spelling test.
VM_REFRESH_TOKENS = (
    "setThinkingLevel(",
    "restoreFromBinding(",
    "applyDefaultModelFallback(",
    "applyNewChatDefaultModel(",
    "selectEntry(",
    "selectGroup(",
    "selectGroupEntry(",
)
VM_PEEK_TOKENS = ("peekViewModel(",)
VM_STORE_FILE = "ChatViewModelStore.kt"
# A peek that can CREATE is not a peek: these are the allocation paths a
# read-only resident lookup must never carry.
VM_STORE_CREATION_TOKENS = ("getOrPut(", "ViewModelProvider", ".put(")
SESSION_NO_ACTIVE_GUARD = "No active session"
SESSION_BRANCH_SPLIT = "return@ClosureField"
CONNECTION_SCREEN_FILE = "ProviderConnectionScreen.kt"
AZURE_MODE_TOKEN = "azureMode"
AZURE_MODE_PUT = 'put("azureMode"'
AZURE_MODE_PARSE = re.compile(r'optBoolean\(\s*"azureMode"')
# C7 allowlist: the azure/bearer fork may come from the local pure helper (with its
# own single literal), from a shared single-source helper, or be written inline.
PROBE_AUTH_ROUTE_TOKENS = ("probeAuthHeader(", "applyOpenAIKeyAuth(", '"api-key"')
# ---- [da1031-D ISSUE-07] the fork has exactly one body (C10) ----
AUTH_HEADER_FILE = "OpenAIAuthHeader.kt"
AUTH_SINGLE_SOURCE_FN = "openAIKeyAuthHeader"
PROBE_AUTH_FN = "probeAuthHeader"
APPLY_AUTH_FN = "applyOpenAIKeyAuth"
AUTH_FORK_TOKENS = ('"api-key"',)
# ---- [da1031-D ISSUE-06] group session-default fields on every face (C11) ----
GROUP_LIST_FN = "groupsList"
GROUP_TO_JSON_FN = "groupToJson"
GROUP_CREATE_FN = "groupsCreate"
GROUP_UPDATE_FN = "groupsUpdate"
# Read/echo faces: JSON keys as emitted by the debug + mutation RPC faces.
GROUP_RPC_KEYS = ('put("defaultThinkingLevel"', 'put("contextLimitTokens"')
# Write faces: the same fields must be ACCEPTED by create/update, not just
# echoed back (an echo-only face is read-only in disguise).
GROUP_RPC_PARAM_KEYS = ('"defaultThinkingLevel"', '"contextLimitTokens"')
# Config face (minis-config `groups` collection): snake_case equivalents.
GROUP_CONFIG_KEYS = ('"default_thinking_level"', '"context_limit_tokens"')
GROUPS_PATH_MARKER = 'path = "groups"'
PATH_MARKER_PAT = re.compile(r'path\s*=\s*"')
# C8: the per-instance dictionary inside the read-only `providers` aggregate.
PROVIDERS_PATH_MARKER = 'path = "providers"'
INSTANCE_DICT_MARKER = "linkedMapOf("
CONFIG_FACE_KEY_TOKENS = ('"azureMode"', '"imageEndpointMode"', '"imageEndpointResolved"')
# C9: the base-URL gate the User-Agent field must NOT live inside.
OPENROUTER_UI_GUARD = re.compile(
    r"if\s*\(\s*instance\.providerType\s*!=\s*ProviderType\.openRouter\s*\)"
)
UA_WRITABLE_TOKEN = "customUserAgentWritable("
# ---- [fix/dv16-a A-06] group write face entry-id existence check (C12-C13) ----
GROUPS_COLLECTION_FILE = "GroupsCollection.kt"
GROUP_RULES_FILE = "GroupWriteRules.kt"
ENTRY_ID_HELPER = "requireKnownEntryIds"
ENTRY_ID_MSG = "Unknown model entry uuid"
# ---- [fix/dv16-a A-07] the two instance write faces must accept the same
# optional key set (C14); the collection face must register + parse the same
# keys (C15). ----
INSTANCE_OPTIONAL_KEYS = (
    '"imageEndpointMode"', '"azureMode"', '"useResponsesAPI"',
    '"appendV1Suffix"', '"isEnabled"', '"customUserAgent"', '"customBaseURL"',
)
IMAGE_KEYS = ("imageEndpointMode", "imageEndpointResolved")


# --------------------------------------------------------------------------
# shared primitives (`_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`)
# --------------------------------------------------------------------------
class LexFail(Exception):
    """A file/snippet the shared lexer cannot read → fail-closed red."""


def mask_source(src, what=None):
    """Comment-stripped view with string/char contents preserved.

    Length is strictly preserved, so offsets stay valid against the raw file.
    A file the shared lexer rejects (unterminated string / comment / char
    literal) raises [LexFail] instead of being scanned half-blind — the
    hand-written stripper this replaces treated `val q = '"'` as the start of
    a double-quoted string and swallowed the rest of the line.
    """
    ok, reason, views = analyse(src)
    if not ok:
        raise LexFail("%s: %s" % (what or "source", reason))
    return views["comments"]


def code_of(text, what=None):
    """Structure channel: comments AND literal interiors blanked, length kept."""
    ok, reason, views = analyse(text)
    if not ok:
        return None
    return views["code"]


def _fn_text(code, name):
    """Body text of the first declaration named [name], or None.

    Block bodies (`{ … }`) and expression bodies (`= …`) are both handled by
    the shared `function_body`; the bracket matcher this replaces mis-sliced
    expression-bodied helpers (it ran into the next declaration's braces).
    """
    fb = function_body(code, name)
    if fb is None:
        return None
    start, end, _kind = fb
    return code[start:end]


def _fn_params(code, name):
    """Parameter-list text of the first declaration named [name], or None."""
    m = re.search(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    close = match_bracket(code, m.end() - 1)
    return None if close < 0 else code[m.end():close]


def _brace_after(text, idx):
    """The `{ … }` starting at the first `{` at/after [idx] (match_bracket)."""
    start = text.find("{", idx)
    if start < 0:
        return None
    end = match_bracket(text, start)
    return None if end < 0 else text[start:end + 1]


def _paren_after(text, idx):
    """The `( … )` starting at the first `(` at/after [idx] (match_bracket)."""
    start = text.find("(", idx)
    if start < 0:
        return None
    end = match_bracket(text, start)
    return None if end < 0 else text[start:end + 1]


def _apply_block_after(block, marker):
    """The `{ … }` of the `apply {` that follows [marker] (match_bracket).

    None when the marker or its block is missing (callers fail-closed).
    """
    idx = block.find(marker)
    if idx < 0:
        return None
    start = block.find("{", idx + len(marker) - 1)
    if start < 0:
        return None
    end = match_bracket(block, start)
    return None if end < 0 else block[start:end + 1]


def _split_expr(expr, op):
    """`split_top` string adapter (this gate's messages quote the split parts)."""
    return [expr[a:b] for a, b in split_top(expr, (op,))]


def find_files(root, name, skip=(".git", "build", "node_modules", "probe", "slice")):
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in skip]
        if name in filenames:
            hits.append(os.path.join(dirpath, name))
    return sorted(hits)


PUT_KEY_PAT = re.compile(r'put\("([^"]+)"')


def put_keys(block):
    if block is None:
        return None
    return set(PUT_KEY_PAT.findall(block))


def enclosing_function(code, pos):
    """Name of the last ``fun <name>(`` declared before ``pos`` in
    comment-stripped code. Returns None (fail-closed) when the write site sits
    outside any function."""
    best = None
    for m in FUN_DECL_PAT.finditer(code, 0, pos):
        best = m.group(1)
    return best


# Top-level declaration start: the optional visibility/suspend words, then
# fun/val/object/class/interface. Used to bound a declaration's window.
TOP_DECL_PAT = re.compile(
    r"\n(?=(?:internal\s+|private\s+|public\s+|suspend\s+|internal\s+suspend\s+)*"
    r"(?:fun|val|object|class|interface|enum class|data class)\b)"
)


def base_url_write_offsets(code):
    """Sorted offsets of every customBaseURL write site (see BASE_URL_WRITE_PATS)."""
    offs = set()
    for pat in BASE_URL_WRITE_PATS:
        for m in pat.finditer(code):
            offs.add(m.start())
    return sorted(offs)


# ---- [wide-1024] C4: a guard is only a guard when it decides the branch ----
def _decl_text(code, name):
    """Text of the [name] declaration — block body when present, else the
    expression body (`fun f() = …`) up to the next top-level declaration.
    `extract_function` only understands `{ … }` bodies, so an expression-bodied
    helper would come back as None and its dataflow would look absent."""
    body = _fn_text(code, name)
    if body is not None:
        return body
    m = re.search(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    rest = code[m.end():]
    end = len(rest)
    for stop in ("\ninternal ", "\nprivate ", "\npublic ", "\nfun ",
                 "\nobject ", "\nclass ", "\n@"):
        idx = rest.find(stop)
        if 0 <= idx < end:
            end = idx
    return rest[:end]


def _bound_initialisers(code, body):
    """identifier → list of right-hand sides for every `val|var id[: T] = …` in
    [body]. Used to follow one hop of local delegation without pinning names."""
    out = {}
    for m in re.finditer(
            r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*"
            r"(?::\s*[A-Za-z0-9_.<>?, ]+)?=\s*([^\n]*)", body):
        out.setdefault(m.group(1), []).append(m.group(2))
    return out


def _guard_branches(body, aliases):
    """[(condition, if-body)] for every `if (…)` whose condition bears a guard
    token — directly or through one local alias (the `val ok = isAllowed(…)`
    delegation is a legitimate refactor, judging it red would punish it)."""
    out = []
    for m in re.finditer(r"\bif\s*\(", body):
        cond = _paren_after(body, m.end() - 1)
        if cond is None:
            continue  # unbalanced — the caller reports fail-closed
        bears = any(tok in cond for tok in BASE_URL_GUARD_TOKENS) or any(
            re.search(r"\b%s\b" % re.escape(a), cond) for a in aliases)
        if not bears:
            continue
        tail = body[m.end() + len(cond):]
        dm = re.match(r"\s*\{", tail)
        if dm:
            blk = _brace_after(tail, dm.end() - 1)
            if_body = "" if blk is None else blk
        else:
            if_body = tail.split("\n", 1)[0]
        out.append((cond, if_body))
    return out


def check_group_member_faces(mut_code, debug_code, groups_code):
    """[dv20-a1 A-02] Two decisions on the group member faces.

    C16 — member-id validity is judged through the ONE shared predicate
    `SystemVoiceEntries.isKnownGroupMemberId` on every write face
    (GroupsCollection.requireKnownEntryIds, groupsCreate, groupsUpdate), and the
    read face (groupsList member rows) resolves the virtual System entries
    through `SystemVoiceEntries.resolve` instead of dropping them. The
    sentinels are never persisted, so a face that re-derives "known" from
    `modelEntries` alone calls every voice-group member unknown.

    C17 — none of those faces re-spells the sentinel id literal; the id has one
    home, or the five readers drift apart again.

    Returns (v16, v17, fail_closed).
    """
    v16, v17, vfc = [], [], []
    helper = _fn_text(groups_code, ENTRY_ID_HELPER)
    if helper is None:
        vfc.append("%s: %s not found (fail-closed) — the single implementation moved"
                   % (GROUPS_COLLECTION_FILE, ENTRY_ID_HELPER))
    elif "SystemVoiceEntries.isKnownGroupMemberId" not in helper:
        v16.append(
            "%s: %s judges member ids from the persisted entry list alone — the "
            "virtual System voice ids are never persisted, so a voice group written "
            "by the harness is refused with '%s'; consult "
            "SystemVoiceEntries.isKnownGroupMemberId (single source)"
            % (GROUPS_COLLECTION_FILE, ENTRY_ID_HELPER, ENTRY_ID_MSG))
    for fn in (GROUP_CREATE_FN, GROUP_UPDATE_FN):
        body = _fn_text(mut_code, fn)
        if body is None:
            vfc.append("%s: %s not found (fail-closed)" % (MUTATION_FILE, fn))
        elif "SystemVoiceEntries.isKnownGroupMemberId" not in body:
            v16.append(
                "%s: %s filters member ids by the persisted entry list alone — a System "
                "voice member is silently dropped from the group it was just handed; "
                "consult SystemVoiceEntries.isKnownGroupMemberId"
                % (MUTATION_FILE, fn))
    members = _fn_text(debug_code, GROUP_LIST_FN)
    if members is None:
        vfc.append("%s: %s not found (fail-closed)" % (DEBUG_FILE, GROUP_LIST_FN))
    elif "SystemVoiceEntries.resolve(" not in members:
        v16.append(
            "%s: %s never resolves a virtual System voice member — the member row is "
            "silently missing from the group the harness just read back; resolve it "
            "through SystemVoiceEntries.resolve" % (DEBUG_FILE, GROUP_LIST_FN))
    for label, code in ((GROUPS_COLLECTION_FILE, groups_code),
                        (MUTATION_FILE, mut_code),
                        (DEBUG_FILE, debug_code)):
        if "__builtin_system_speech__" in code:
            v17.append(
                "%s: spells the System sentinel id literal — it must come from "
                "SystemVoiceEntries, or one of the five readers drifts the moment the "
                "id changes" % label)
    return v16, v17, vfc


def session_writer_block(code, path_marker):
    """The `writer = { … }` block of the field whose `path = "…"` is
    [path_marker] (comment-stripped code). None when not locatable."""
    at = code.find(path_marker)
    if at < 0:
        return None
    w = code.find("writer = {", at)
    if w < 0:
        return None
    return _brace_after(code, w)

def _live_sites(text, pattern):
    """Positions of [pattern] in [text] whose enclosing conditions are feasible.

    Shared `live_positions` (path_feasible underneath): `if (false) call()` and
    `if (1 == 2) call()` produce no live hit. A snippet the shared lexer cannot
    read produces no live hit either — fail-closed, the caller reports red.
    """
    view = code_of(text)
    if view is None:
        return []
    return live_positions(view, re.compile(pattern))


def _live_refresh_call(branch):
    """The first VM_REFRESH_TOKENS call that can actually RUN in [branch]."""
    for tok in VM_REFRESH_TOKENS:
        if _live_sites(branch, re.escape(tok)):
            return tok
    return None


def check_session_write_through(cfg_code, store_code):
    """[dv24-A05] C18 — every `session.*` config write reaches BOTH surfaces.

    `minis-config set session.thinkingLevel|primaryModel` used to write the Room
    row and nothing else. ChatViewModel reads both fields exactly once (inside
    `loadSession()`) and ChatViewModelStore caches that instance for the whole
    process, so (a) the open chat kept the old model/level, and (b) the next
    in-app picker tap wrote that stale memory back over the row.

    Both halves are required, hence a conjunction per branch:
      - the Room row (only cross-restart store), and
      - a resident-VM refresh reached through the NON-CREATING lookup.

    Returns (c18 violations, fail-closed violations).
    """
    v18, vfc = [], []
    peek_cache = {}

    def reaches_peek(block):
        """True when [block] peeks, directly or through one same-file helper."""
        if any(t in block for t in VM_PEEK_TOKENS):
            return True
        for m in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", block):
            name = m.group(1)
            if name == "peekViewModel":
                continue
            if name not in peek_cache:
                body = _fn_text(cfg_code, name)
                peek_cache[name] = bool(
                    body and any(t in body for t in VM_PEEK_TOKENS))
            if peek_cache[name]:
                return True
        return False

    for label, path_marker, dao_token in SESSION_FIELDS:
        block = session_writer_block(cfg_code, path_marker)
        if block is None:
            vfc.append(
                "%s: the %s writer block is not extractable (fail-closed) — the "
                "criterion must be re-derived, not deleted"
                % (CONFIG_BUILTINS_FILE, label))
            continue
        if SESSION_NO_ACTIVE_GUARD not in block:
            v18.append(
                "%s: %s writer no longer refuses with '%s' — the no-foreground "
                "contract must survive the write-through rewrite"
                % (CONFIG_BUILTINS_FILE, label, SESSION_NO_ACTIVE_GUARD))
        branches = [block]
        if label == "primaryModel":
            branches = block.split(SESSION_BRANCH_SPLIT)
            if len(branches) != 2:
                v18.append(
                    "%s: %s writer lost its clear/echo split (expected exactly one "
                    "%s) — both branches are judged separately, so a collapsed "
                    "writer hides whichever half stops refreshing"
                    % (CONFIG_BUILTINS_FILE, label, SESSION_BRANCH_SPLIT))
        for idx, branch in enumerate(branches):
            if label == "primaryModel":
                where = "%s[%s]" % (label, "clear" if idx == 0 else "set")
            else:
                where = label
            if dao_token not in branch:
                v18.append(
                    "%s: %s writer dropped its Room write (%s) — the row is the only "
                    "cross-restart store, so a VM-only refresh evaporates on the next "
                    "load" % (CONFIG_BUILTINS_FILE, where, dao_token))
            if not reaches_peek(branch):
                v18.append(
                    "%s: %s writer never reaches the resident ChatViewModel through the "
                    "non-creating lookup — the open chat keeps the OLD value and the next "
                    "in-app pick writes it back over the row"
                    % (CONFIG_BUILTINS_FILE, where))
            elif _live_refresh_call(branch) is None:
                # [batch6] the refresh call had to be *spelled*, not *reached*:
                # `refresh = { vm -> if (false) vm.setThinkingLevel(v) }` kept the
                # token and never ran it. liveness = shared path_feasible.
                v18.append(
                    "%s: %s writer peeks but never applies the value to the live chat "
                    "(no reachable call among %s — a dead branch around the refresh is "
                    "the same silent drop)"
                    % (CONFIG_BUILTINS_FILE, where, "/".join(VM_REFRESH_TOKENS)))

    peek = _fn_text(store_code, "peekViewModel")
    if peek is None:
        vfc.append(
            "%s: peekViewModel not found (fail-closed) — the non-creating resident "
            "lookup is the whole point of the refresh half" % VM_STORE_FILE)
    else:
        for tok in VM_STORE_CREATION_TOKENS:
            if tok in peek:
                v18.append(
                    "%s: peekViewModel can allocate (%s) — refreshing a session that is "
                    "not on screen must not materialise a ChatViewModel"
                    % (VM_STORE_FILE, tok))
        if "stores[" not in peek:
            v18.append(
                "%s: peekViewModel no longer reads the existing store map — a lookup "
                "that cannot find an existing VM is not a peek" % VM_STORE_FILE)
    return v18, vfc

# ---- [dv25-A06] session config READ-through (C19) ----
# The reader-side mirror of SESSION_FIELDS. A `session.*` write lands on two
# surfaces (C18), so a reader that only consults the Room row answers a
# different value than the write just reported as accepted — and for a draft
# session (no row) it answered the empty default while the resident VM held the
# real level / binding. Every reader must therefore:
#   - reach the resident VM through the NON-CREATING lookup (the peek),
#   - keep the row as its no-live-chat fallback,
#   - produce a value FROM the VM in its resident arm — peeking and then
#     answering a constant is the same defect with extra steps.
SESSION_READ_FIELDS = (
    ("thinkingLevel", 'path = "session.thinkingLevel"'),
    ("primaryModel", 'path = "session.primaryModel"'),
)
SESSION_ROW_READ_TOKEN = "getSession("
SESSION_READ_ARM_MARKER = "ResidentViewModel ->"
SESSION_READ_ARM_PREFIX = "SessionConfigReadSource."


def session_reader_block(code, path_marker):
    """The `reader = { … }` block of the field whose `path = "…"` is
    [path_marker] (comment-stripped code). None when not locatable."""
    at = code.find(path_marker)
    if at < 0:
        return None
    r = code.find("reader = {", at)
    if r < 0:
        return None
    return _brace_after(code, r + len("reader = "))


def _reaches_token(code, block, tokens):
    """True when one of [tokens] is reachable from [block] — directly, or through
    one same-file helper (the readers delegate the peek to `residentViewModel`,
    so the criterion follows the delegation instead of pinning helper names)."""
    if any(t in block for t in tokens):
        return True
    for m in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", block):
        name = m.group(1)
        body = _fn_text(code, name)
        if body and any(t in body for t in tokens):
            return True
    return False


def _resident_arm(block):
    """The text of the `… ResidentViewModel -> …` arm of a reader's `when`
    (up to the next arm), or None when the reader has no such arm."""
    at = block.find(SESSION_READ_ARM_MARKER)
    if at < 0:
        return None
    rest = block[at + len(SESSION_READ_ARM_MARKER):]
    nxt = rest.find(SESSION_READ_ARM_PREFIX)
    return rest if nxt < 0 else rest[:nxt]


def _vm_handles(code, block):
    """Identifiers bound to the resident VM inside [block]: `val vm = <peek>(…)`,
    where the callee is the non-creating lookup itself or a same-file helper that
    reaches it. Derived, not spelled — renaming `vm` or the helper keeps working."""
    names = set()
    for m in re.finditer(
            r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*"
            r"(?::\s*[A-Za-z0-9_.<>?, ]+)?=\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(", block):
        callee = m.group(2)
        if callee == "peekViewModel":
            names.add(m.group(1))
            continue
        helper = _decl_text(code, callee)
        if helper is not None and any(t in helper for t in VM_PEEK_TOKENS):
            names.add(m.group(1))
    return names


def _if_arm_parts(arm, view):
    """The (then, else) spans of a brace-less `if (c) a else b` arm, [] otherwise."""
    m = re.match(r"\s*if\s*\(", view)
    if not m:
        return []
    close = match_bracket(view, m.end() - 1)
    if close < 0:
        return []
    rest_start = close + 1
    em = re.search(r"\belse\b", view[rest_start:])
    if not em:
        return []
    return [(rest_start, rest_start + em.start()),
            (rest_start + em.end(), len(view))]


def _arm_sources(arm):
    """Evaluation sources of a when-arm value — brace-less `if`/`else` included.

    `value_sources` splits the block form (`if (c) { a } else { b }`) but not
    `if (c) a else b`: its `else\\b` probe looks at the start of the remainder,
    where a bare expression sits. Arms are written both ways in this repo, so
    the arm's own `else` is located here and each side is handed back to
    `value_sources` (which still peels `?:` / scope calls / nesting).
    """
    view = code_of(arm)
    if view is None:
        return [arm]
    out = []
    for start, end in _if_arm_parts(arm, view):
        out.extend(value_sources(arm[start:end]))
    return out or value_sources(arm)


def _arm_derives_from_vm(code, block, arm):
    """[wide-1024] C19 — the resident arm's value must be READ from the VM.

    `"(" in arm` used to stand in for "this is a call, so it is a read": it also
    accepted `(ThinkingLevel.OFF)`, i.e. a parenthesised constant, which is the
    row-only defect wearing a call's clothes. Require the arm to consume the
    identifier bound to the peek; when the peek is inlined (`peekViewModel(…)?.let
    { … }`) accept the peek itself.

    [batch6] The identifier had to *appear* in the arm, and a null test is an
    appearance: `if (vm != null) ThinkingLevel.OFF else ThinkingLevel.OFF`
    answered a constant on both paths and stayed green. Judge the *evaluation
    sources* instead (`value_sources` drops the if/else condition and keeps only
    the branch values, peeling `.also {}` / `.let { … }` scope calls), so a
    condition can no longer stand in for a read."""
    handles = _vm_handles(code, block)
    srcs = [strip_parens(s.strip()) for s in _arm_sources(arm)]
    srcs = [s for s in srcs if s]
    if not srcs:
        return False
    if handles:
        return any(contains_ident(s, h) for s in srcs for h in handles)
    return any(any(t in s for t in VM_PEEK_TOKENS) for s in srcs)


def check_session_read_through(cfg_code):
    """[dv25-A06] C19 — every `session.*` config READ answers from the live value.

    `minis-config get session.thinkingLevel|primaryModel` used to read the Room
    row only, while the writers (C18) push into the resident ChatViewModel. For
    an open chat the two surfaces disagree until the next `loadSession()`, and
    for a draft (no row at all) the read reported the empty default while the VM
    held the value the write had just accepted.

    Returns (c19 violations, fail-closed violations).
    """
    v19, vfc = [], []
    for label, path_marker in SESSION_READ_FIELDS:
        block = session_reader_block(cfg_code, path_marker)
        if block is None:
            vfc.append(
                "%s: the %s reader block is not extractable (fail-closed) — the "
                "criterion must be re-derived, not deleted"
                % (CONFIG_BUILTINS_FILE, label))
            continue
        if not _reaches_token(cfg_code, block, VM_PEEK_TOKENS):
            v19.append(
                "%s: the %s reader never reaches the resident ChatViewModel through "
                "the non-creating lookup — it reports the row value while the open "
                "chat (and a draft's only copy) lives in the VM, so a read disagrees "
                "with the write that just succeeded" % (CONFIG_BUILTINS_FILE, label))
        if not _reaches_token(cfg_code, block, (SESSION_ROW_READ_TOKEN,)):
            v19.append(
                "%s: the %s reader dropped the row fallback — a session with no live "
                "chat has only the row, so the read would answer the default instead "
                "of the persisted value" % (CONFIG_BUILTINS_FILE, label))
        arm = _resident_arm(block)
        if arm is None:
            v19.append(
                "%s: the %s reader has no resident-VM arm (no `%s`) — without an "
                "explicit precedence the VM half is either unreachable or applied "
                "unconditionally" % (CONFIG_BUILTINS_FILE, label, SESSION_READ_ARM_MARKER))
        elif not _arm_derives_from_vm(cfg_code, block, arm):
            v19.append(
                "%s: the %s reader's resident arm answers a CONSTANT ('%s') instead "
                "of reading the resident ChatViewModel — peeking, then ignoring the "
                "value, is the row-only defect with extra steps"
                % (CONFIG_BUILTINS_FILE, label, arm.strip()))
    return v19, vfc

def _azure_value_flows(code, body):
    """[wide-1024] C6 — the `azureMode` the function stores must BE the parse.

    The old criterion asked only that `optBoolean("azureMode"` appears somewhere
    in the function body, so parking the parse in an unused local and writing
    `azureMode = false` stayed green. Follow the value instead: the initialiser
    of the stored `azureMode` — through local aliases and one same-file helper —
    has to reach the parse.

    [batch6] "reaches the parse" is now asked of the *evaluation sources*
    (`value_sources`: elvis / if-else / scope-call both sides), and a source
    that folds to a constant disqualifies the whole initialiser — the old
    substring test accepted `params.optBoolean("azureMode", false) && false`
    and `.let { false }` because the parse was still spelled inside the text.
    """
    binds = _bound_initialisers(code, body)
    roots = binds.get("azureMode")
    if not roots:
        return False
    seen, stack = set(), list(roots)
    while stack:
        expr = stack.pop()
        srcs = [strip_parens(s.strip()) for s in value_sources(expr)]
        if not srcs:
            srcs = [strip_parens(expr.strip())]
        for src in srcs:
            if not src:
                continue
            if const_value(src) is not None:
                # `… && false` / `.let { false }` / `if (…) false else false`:
                # the stored value is a constant, the parse is decoration.
                return False
            if AZURE_MODE_PARSE.search(src):
                return True
            for ident in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\b", src):
                if ident in seen:
                    continue
                seen.add(ident)
                stack.extend(binds.get(ident, []))
            for callee in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", src):
                helper = _decl_text(code, callee)
                if helper is not None and AZURE_MODE_PARSE.search(helper):
                    return True
    return False



def check_tree(root):
    """Returns (rc, detail dict). rc=0 all green."""
    detail = {"c1": [], "c2": [], "c3": [], "c4": [], "c5": [], "c6": [], "c7": [],
              "c8": [], "c9": [], "c10": [], "c11": [], "c12": [], "c13": [],
              "c14": [], "c15": [], "c16": [], "c17": [], "c18": [], "c19": [],
              "fail_closed": []}
    try:
        return _check_tree(root, detail)
    except LexFail as exc:
        # [batch6] fail-closed: a file the shared lexer cannot read is a red,
        # never a silent skip (the hand-written stripper used to swallow the
        # rest of the line after `val q = '"'` and call the result green).
        detail["fail_closed"].append(str(exc))
        return 1, detail


def _check_tree(root, detail):
    debug_paths = find_files(root, DEBUG_FILE)
    mut_paths = find_files(root, MUTATION_FILE)
    if len(debug_paths) != 1 or len(mut_paths) != 1:
        detail["fail_closed"].append(
            "expected exactly one %s and one %s, found %d/%d"
            % (DEBUG_FILE, MUTATION_FILE, len(debug_paths), len(mut_paths))
        )
        return 1, detail

    with open(debug_paths[0], encoding="utf-8") as f:
        debug_code = mask_source(f.read())
    with open(mut_paths[0], encoding="utf-8") as f:
        mut_code = mask_source(f.read())

    # ---- C1 ----
    all_defs = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "build", "node_modules", "probe", "slice")]
        for fn in filenames:
            if fn.endswith(".kt"):
                p = os.path.join(dirpath, fn)
                try:
                    with open(p, encoding="utf-8", errors="replace") as f:
                        code = mask_source(f.read())
                except OSError:
                    continue
                if PREDICATE_DEF_PAT.search(code):
                    all_defs.append(p)
    if len(all_defs) != 1:
        detail["c1"].append(
            "predicate definition count = %d (expected exactly 1): %s"
            % (len(all_defs), all_defs)
        )
    elif os.path.basename(all_defs[0]) != MUTATION_FILE:
        detail["c1"].append(
            "predicate defined in %s (expected %s)" % (all_defs[0], MUTATION_FILE)
        )
    for label, code in (("debug", debug_code), ("mutation", mut_code)):
        if INLINE_HARDCODE in code:
            detail["c1"].append("inline openAI||anthropic hardcode present in %s file" % label)

    type_spec = _fn_text(debug_code, "typeSpec")
    if type_spec is None:
        detail["fail_closed"].append("typeSpec not found in %s" % DEBUG_FILE)
    elif "supportsCustomBaseURL" not in type_spec:
        detail["c1"].append("typeSpec does not reference supportsCustomBaseURL")

    create_body = _fn_text(mut_code, "instancesCreate")
    if create_body is None:
        detail["fail_closed"].append("instancesCreate not found in %s" % MUTATION_FILE)
    elif "supportsCustomBaseURL" not in create_body:
        detail["c1"].append("instancesCreate guard does not reference supportsCustomBaseURL")

    # ---- C2 ----
    update_body = _fn_text(mut_code, "instancesUpdate")
    if update_body is None:
        detail["fail_closed"].append("instancesUpdate not found in %s" % MUTATION_FILE)
    else:
        if "supportsCustomBaseURL" not in update_body:
            detail["c2"].append("instancesUpdate guard does not reference supportsCustomBaseURL")
        # [ARCH-01] The openRouter guard message must be single-sourced: either
        # the create-identical literal, or a reference to the shared constant
        # in ProviderWriteRules.kt. A hand-written third copy (or a message
        # that drifted) fails both arms.
        if OPENROUTER_MSG not in update_body and not SHARED_MSG_REF.search(update_body):
            detail["c2"].append(
                "instancesUpdate lacks the create-identical openRouter guard message"
            )

    # ---- C3 ----
    per_inst_block = None
    core_block = None
    list_body = _fn_text(debug_code, "instancesList")
    if list_body is None:
        detail["fail_closed"].append("instancesList not found in %s" % DEBUG_FILE)
        per_instance_keys = None
    else:
        per_inst_block = _apply_block_after(list_body, "arr.put(JSONObject().apply {")
        if per_inst_block is None:
            detail["fail_closed"].append("per-instance JSONObject().apply block not found in instancesList")
            per_instance_keys = None
        else:
            per_instance_keys = put_keys(per_inst_block)
            if not per_instance_keys:
                detail["fail_closed"].append("no put( keys found in instancesList per-instance block")
                per_instance_keys = None

    to_json_body = _fn_text(mut_code, "instanceToJson")
    if to_json_body is None:
        detail["fail_closed"].append("instanceToJson not found in %s" % MUTATION_FILE)
        mut_keys = None
    elif "instanceJsonCore(" in to_json_body:
        core_body = _fn_text(mut_code, "instanceJsonCore")
        if core_body is None:
            detail["fail_closed"].append(
                "instanceToJson delegates to instanceJsonCore but its body was not found"
            )
            mut_keys = None
        else:
            marker = ("return JSONObject().apply {"
                      if "return JSONObject().apply {" in core_body
                      else "JSONObject().apply {")
            core_block = _apply_block_after(core_body, marker)
            if core_block is None:
                # Expression-body function: extract_function already returned
                # exactly the JSONObject().apply { … } block.
                core_block = core_body
            mut_keys = put_keys(core_block)
            if not mut_keys:
                detail["fail_closed"].append("no put( keys found in instanceJsonCore apply block")
                mut_keys = None
    else:
        mut_keys = put_keys(to_json_body)
        if not mut_keys:
            detail["fail_closed"].append("no put( keys found in instanceToJson body")
            mut_keys = None

    if per_instance_keys is not None and mut_keys is not None:
        missing = per_instance_keys - mut_keys
        if missing:
            detail["c3"].append(
                "instanceToJson put-keys missing %s (required by instancesList)"
                % sorted(missing)
            )

    # ---- C4 ---- [da1026-ISSUE-05] collection-face customBaseURL guards
    coll_paths = find_files(root, PROVIDERS_COLLECTION_FILE)
    if len(coll_paths) != 1:
        detail["fail_closed"].append(
            "expected exactly one %s, found %d" % (PROVIDERS_COLLECTION_FILE, len(coll_paths))
        )
    else:
        with open(coll_paths[0], encoding="utf-8") as f:
            coll_code = mask_source(f.read())
        write_offs = base_url_write_offsets(coll_code)
        if not write_offs:
            detail["fail_closed"].append(
                "no customBaseURL write site found in %s (fail-closed)" % PROVIDERS_COLLECTION_FILE
            )
        for off in write_offs:
            fn = enclosing_function(coll_code, off)
            if fn is None:
                detail["c4"].append(
                    "customBaseURL write site at char %d is outside any function" % off
                )
                continue
            fn_start = coll_code.rfind("fun %s(" % fn, 0, off)
            body = coll_code[fn_start:off] if fn_start >= 0 else ""
            # [wide-1024] the guard must DECIDE the write, not merely exist:
            #   (a) a live `if` guard — its condition is not short-circuited by
            #       a literal, and the branch terminates (throw/return);
            #   (b) or the value written is the guard's own result
            #       (`val x = resolveAddCustomBaseURL(raw)` → `x` written).
            inits = _bound_initialisers(coll_code, body)
            aliases = set(
                name for name, rhs in inits.items()
                if any(tok in r for r in rhs for tok in BASE_URL_GUARD_TOKENS))
            branches = _guard_branches(body, aliases)
            live = [c for c, b in branches
                    if const_bool(c) is None and re.search(r"\b(?:throw|return)\b", b)]
            if live:
                continue
            stmt_end = coll_code.find("\n", off)
            stmt = coll_code[off:stmt_end if stmt_end > 0 else off + 240]
            derived = any(
                any(tok in r for r in inits.get(ident, [])
                    for tok in BASE_URL_GUARD_TOKENS)
                for ident in re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\b", stmt))
            if derived:
                continue
            if branches:
                # [batch6] constant folding now goes through the shared AST
                # (`const_bool`): the hand-written `_constant_condition` only
                # knew the literals `true`/`false`, so `!true && !guard(...)`
                # — a guard that can never run — read as a live guard.
                dead = [(c, const_bool(c)) for c, _ in branches if const_bool(c) is not None]
                if dead:
                    detail["c4"].append(
                        "%s: customBaseURL write site in fun %s is guarded by a "
                        "condition that folds to %s ('%s') — the guard call is dead "
                        "code and the write is ungated"
                        % (PROVIDERS_COLLECTION_FILE, fn, str(dead[0][1]).lower(),
                           dead[0][0].strip()))
                else:
                    detail["c4"].append(
                        "%s: customBaseURL write site in fun %s has a guard that "
                        "does not terminate (no throw/return in the guarded branch) — "
                        "the write runs either way"
                        % (PROVIDERS_COLLECTION_FILE, fn))
            else:
                detail["c4"].append(
                    "%s: customBaseURL write site in fun %s has no supportsCustomBaseURL guard"
                    % (PROVIDERS_COLLECTION_FILE, fn)
                )

    # ---- C5 ---- [da1026-ISSUE-05] predicate use-count floor in mutation file
    mut_uses = mut_code.count("supportsCustomBaseURL")
    if mut_uses < MUTATION_PREDICATE_MIN_USES:
        detail["c5"].append(
            "ProviderMutationMethods.kt references supportsCustomBaseURL %d times (floor %d) — guard moved/inlined?"
            % (mut_uses, MUTATION_PREDICATE_MIN_USES)
        )

    # ---- C6 ---- [da1030-C ISSUE-04] azureMode on all four faces
    if per_inst_block is None:
        detail["c6"].append("instancesList per-instance block not extractable (fail-closed)")
    elif AZURE_MODE_PUT not in per_inst_block:
        detail["c6"].append(
            "%s: instancesList per-instance block lacks %s (read face 1/2)"
            % (DEBUG_FILE, AZURE_MODE_PUT)
        )
    if core_block is None:
        detail["c6"].append("instanceJsonCore apply block not extractable (fail-closed)")
    elif AZURE_MODE_PUT not in core_block:
        detail["c6"].append(
            "%s: instanceJsonCore apply block lacks %s (read face 2/2)"
            % (MUTATION_FILE, AZURE_MODE_PUT)
        )
    if create_body is None:
        detail["c6"].append("instancesCreate body not extractable (fail-closed)")
    elif AZURE_MODE_TOKEN not in create_body or not AZURE_MODE_PARSE.search(create_body):
        detail["c6"].append(
            "%s: instancesCreate does not parse/store azureMode (write face 1/2)"
            % MUTATION_FILE
        )
    elif not _azure_value_flows(mut_code, create_body):
        detail["c6"].append(
            "%s: instancesCreate stores an azureMode that is NOT the parsed value "
            "— the parse is parked in an unused local and the instance gets a "
            "constant (write face 1/2)" % MUTATION_FILE
        )
    if update_body is None:
        detail["c6"].append("instancesUpdate body not extractable (fail-closed)")
    elif AZURE_MODE_TOKEN not in update_body or not AZURE_MODE_PARSE.search(update_body):
        detail["c6"].append(
            "%s: instancesUpdate does not parse/store azureMode (write face 2/2)"
            % MUTATION_FILE
        )
    elif not _azure_value_flows(mut_code, update_body):
        detail["c6"].append(
            "%s: instancesUpdate stores an azureMode that is NOT the parsed value "
            "— the parse is parked in an unused local and the instance gets a "
            "constant (write face 2/2)" % MUTATION_FILE
        )

    # ---- C7 ---- [da1030-C ISSUE-04] probe auth-header fork
    test_body = _fn_text(mut_code, "instancesTest")
    if test_body is None:
        detail["fail_closed"].append("instancesTest not found in %s" % MUTATION_FILE)
    else:
        if AZURE_MODE_TOKEN not in test_body:
            detail["c7"].append(
                "instancesTest does not key the auth header on the instance's azureMode"
            )
        elif not any(tok in test_body for tok in PROBE_AUTH_ROUTE_TOKENS):
            detail["c7"].append(
                "instancesTest auth header is not sourced from any accepted fork shape %s"
                % (PROBE_AUTH_ROUTE_TOKENS,)
            )

    # ---- C8 ---- [da1030-C ISSUE-04] config face carries the same instance keys
    cfg_paths = find_files(root, CONFIG_BUILTINS_FILE)
    if len(cfg_paths) != 1:
        # [dv24-FIX-4 R8] C11 below reads cfg_code unconditionally; leaving it
        # unbound here made the gate CRASH (UnboundLocalError, a NameError
        # subclass) instead of failing closed, so a tree with a missing or
        # duplicated ConfigBuiltins.kt took the whole scan down. Bound to an
        # empty string: every `.find()` misses, the anchors report fail-closed,
        # rc=1 (the reason is already recorded above).
        cfg_code = ""
        detail["fail_closed"].append(
            "expected exactly one %s, found %d" % (CONFIG_BUILTINS_FILE, len(cfg_paths))
        )
    else:
        with open(cfg_paths[0], encoding="utf-8") as f:
            cfg_code = mask_source(f.read())
        path_idx = cfg_code.find(PROVIDERS_PATH_MARKER)
        if path_idx < 0:
            detail["fail_closed"].append(
                "%s: anchor %s not found (fail-closed)" % (CONFIG_BUILTINS_FILE, PROVIDERS_PATH_MARKER)
            )
        else:
            dict_idx = cfg_code.find(INSTANCE_DICT_MARKER, path_idx)
            instance_dict = None if dict_idx < 0 else _paren_after(cfg_code, dict_idx)
            if instance_dict is None:
                detail["fail_closed"].append(
                    "%s: %s block after %s not found (fail-closed)"
                    % (CONFIG_BUILTINS_FILE, INSTANCE_DICT_MARKER, PROVIDERS_PATH_MARKER)
                )
            else:
                missing = [t for t in CONFIG_FACE_KEY_TOKENS if t not in instance_dict]
                if missing:
                    detail["c8"].append(
                        "%s: providers instance dict missing %s"
                        % (CONFIG_BUILTINS_FILE, missing)
                    )

    # ---- C9 ---- [da1030-C ISSUE-04] User-Agent visibility on the connection screen
    conn_paths = find_files(root, CONNECTION_SCREEN_FILE)
    if len(conn_paths) != 1:
        detail["fail_closed"].append(
            "expected exactly one %s, found %d" % (CONNECTION_SCREEN_FILE, len(conn_paths))
        )
    else:
        with open(conn_paths[0], encoding="utf-8") as f:
            conn_code = mask_source(f.read())
        if UA_WRITABLE_TOKEN not in conn_code:
            detail["c9"].append(
                "%s: %s not present (fail-closed: the UA visibility gate must stay "
                "anchored and re-derived, not deleted)"
                % (CONNECTION_SCREEN_FILE, UA_WRITABLE_TOKEN)
            )
        guard_spans = []
        for m in OPENROUTER_UI_GUARD.finditer(conn_code):
            body = _brace_after(conn_code, m.end())
            if body is not None:
                guard_spans.append(body)
        if not guard_spans:
            detail["fail_closed"].append(
                "%s: %s guard block not found (fail-closed)"
                % (CONNECTION_SCREEN_FILE, "instance.providerType != ProviderType.openRouter")
            )
        for body in guard_spans:
            if UA_WRITABLE_TOKEN in body:
                detail["c9"].append(
                    "%s: %s sits INSIDE the `!= openRouter` base-URL gate — openRouter "
                    "instances cannot see/edit a User-Agent the request path honors"
                    % (CONNECTION_SCREEN_FILE, UA_WRITABLE_TOKEN)
                )

    # ---- C10 ---- [da1031-D ISSUE-07] the azure/bearer fork has ONE body
    # Two faces of the same decision: `openAIKeyAuthHeader` (the shared pair
    # builder) and the debug probe's `probeAuthHeader`. Both must be thin
    # wrappers — a face that carries its own `"api-key"` literal is the second
    # copy this arm exists to catch (C7 only pins WHERE the probe's header
    # comes from, this pins that there is one body behind it).
    auth_paths = find_files(root, AUTH_HEADER_FILE)
    if len(auth_paths) != 1:
        detail["fail_closed"].append(
            "expected exactly one %s, found %d" % (AUTH_HEADER_FILE, len(auth_paths))
        )
    else:
        with open(auth_paths[0], encoding="utf-8") as f:
            auth_code = mask_source(f.read())
        if re.search(r"\bfun\s+" + AUTH_SINGLE_SOURCE_FN + r"\s*\(", auth_code) is None:
            detail["c10"].append(
                "%s: %s(…) is not defined — callers cannot delegate to a missing "
                "single source (fail-closed)" % (AUTH_HEADER_FILE, AUTH_SINGLE_SOURCE_FN)
            )
        apply_win = _fn_text(auth_code, APPLY_AUTH_FN)
        if apply_win is None:
            detail["fail_closed"].append(
                "%s: %s not found (fail-closed)" % (AUTH_HEADER_FILE, APPLY_AUTH_FN)
            )
        elif AUTH_SINGLE_SOURCE_FN + "(" not in apply_win:
            detail["c10"].append(
                "%s: %s does not delegate to %s(…)"
                % (AUTH_HEADER_FILE, APPLY_AUTH_FN, AUTH_SINGLE_SOURCE_FN)
            )
        elif any(tok in apply_win for tok in AUTH_FORK_TOKENS):
            detail["c10"].append(
                "%s: %s still inlines the azure/bearer literal(s) %s"
                % (AUTH_HEADER_FILE, APPLY_AUTH_FN, AUTH_FORK_TOKENS)
            )
    probe_win = _fn_text(mut_code, PROBE_AUTH_FN)
    if probe_win is None:
        detail["fail_closed"].append(
            "%s: %s not found (fail-closed)" % (MUTATION_FILE, PROBE_AUTH_FN)
        )
    elif AUTH_SINGLE_SOURCE_FN + "(" not in probe_win:
        detail["c10"].append(
            "%s: %s does not delegate to %s(…) — the debug fork is back"
            % (MUTATION_FILE, PROBE_AUTH_FN, AUTH_SINGLE_SOURCE_FN)
        )
    elif any(tok in probe_win for tok in AUTH_FORK_TOKENS):
        detail["c10"].append(
            "%s: %s still inlines the azure/bearer literal(s) %s"
            % (MUTATION_FILE, PROBE_AUTH_FN, AUTH_FORK_TOKENS)
        )
    else:
        # [batch6] the delegation had to *exist*, not *carry the flag*:
        # `openAIKeyAuthHeader(azure = false, token = key)` delegates and then
        # ignores the azureMode the caller resolved — the fork decision is
        # re-made one hop behind the value flow (C7 still sees the probe as the
        # single face and C10's literal arm still sees one body).
        probe_params = _fn_params(mut_code, PROBE_AUTH_FN)
        pm = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)", probe_params or "")
        first_param = pm.group(1) if pm else None
        dm = re.search(r"\b" + re.escape(AUTH_SINGLE_SOURCE_FN) + r"\s*\(", probe_win)
        if first_param and dm:
            passed, seen_args = False, []
            for pos, (a, b) in enumerate(call_args(probe_win, dm.end() - 1)):
                arg = probe_win[a:b]
                nm = re.match(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*", arg)
                if nm:
                    name, value, is_azure = nm.group(1), arg[nm.end():], nm.group(1) in (
                        "azure", "azureMode")
                else:
                    name, value, is_azure = None, arg, pos == 0
                if is_azure:
                    seen_args.append(value.strip())
                    if contains_ident(value, first_param):
                        passed = True
            if not passed:
                detail["c10"].append(
                    "%s: %s ignores its `%s` parameter — the azure argument passed to "
                    "%s(…) is %r, not derived from the parameter the caller resolved "
                    "(the fork is re-decided one hop behind the value flow)"
                    % (MUTATION_FILE, PROBE_AUTH_FN, first_param, AUTH_SINGLE_SOURCE_FN,
                       (seen_args or ["<none>"])[0])
                )
        elif first_param and not dm:
            detail["fail_closed"].append(
                "%s: %s does not call %s(…) in a readable shape (fail-closed)"
                % (MUTATION_FILE, PROBE_AUTH_FN, AUTH_SINGLE_SOURCE_FN)
            )

    # ---- C11 ---- [da1031-D ISSUE-06] the group session-default fields live on
    # every face that reads or writes a group. The config collection already
    # carried them; the two RPC faces (read `groupsList`, echo `groupToJson`)
    # and the write path (`groupsCreate`/`groupsUpdate`) did not — so a group's
    # thinking level / context limit was invisible and unsettable over RPC while
    # minis-config could set both. Field-set equality is what this arm pins.
    group_list = _fn_text(debug_code, GROUP_LIST_FN)
    if group_list is None:
        detail["fail_closed"].append(
            "%s: %s not found (fail-closed)" % (DEBUG_FILE, GROUP_LIST_FN)
        )
    else:
        missing = [t for t in GROUP_RPC_KEYS if t not in group_list]
        if missing:
            detail["c11"].append("%s: %s missing %s" % (DEBUG_FILE, GROUP_LIST_FN, missing))
    to_json = _fn_text(mut_code, GROUP_TO_JSON_FN)
    if to_json is None:
        detail["fail_closed"].append(
            "%s: %s not found (fail-closed)" % (MUTATION_FILE, GROUP_TO_JSON_FN)
        )
    else:
        missing = [t for t in GROUP_RPC_KEYS if t not in to_json]
        if missing:
            detail["c11"].append("%s: %s missing %s" % (MUTATION_FILE, GROUP_TO_JSON_FN, missing))
    for fn in (GROUP_CREATE_FN, GROUP_UPDATE_FN):
        body = _fn_text(mut_code, fn)
        if body is None:
            detail["fail_closed"].append("%s: %s not found (fail-closed)" % (MUTATION_FILE, fn))
            continue
        missing = [t for t in GROUP_RPC_PARAM_KEYS if t not in body]
        if missing:
            detail["c11"].append("%s: %s does not accept %s" % (MUTATION_FILE, fn, missing))
    groups_idx = cfg_code.find(GROUPS_PATH_MARKER)
    if groups_idx < 0:
        detail["fail_closed"].append(
            "%s: anchor %s not found (fail-closed)" % (CONFIG_BUILTINS_FILE, GROUPS_PATH_MARKER)
        )
    else:
        nxt = PATH_MARKER_PAT.search(cfg_code, groups_idx + len(GROUPS_PATH_MARKER))
        groups_region = cfg_code[groups_idx: nxt.start()] if nxt else cfg_code[groups_idx:]
        missing = [t for t in GROUP_CONFIG_KEYS if t not in groups_region]
        if missing:
            detail["c11"].append(
                "%s: groups face missing %s" % (CONFIG_BUILTINS_FILE, missing)
            )

    # ---- C12 ---- [fix/dv16-a A-06] groupsUpdate routes the context-limit
    # write through the canonical face, and the clear branch does NOT null the
    # "last selected" memory. The canonical write face
    # (GroupWriteRules.applyContextLimitWrite) KEEPS lastContextLimitTokens on
    # a clear — the UI's OFF branch stashes rather than erases — so an update
    # that nulled it made the next OFF→ON toggle snap back to the 128K default
    # instead of the last value the user set.
    groups_update = _fn_text(mut_code, GROUP_UPDATE_FN)
    if groups_update is None:
        detail["fail_closed"].append(
            "%s: %s not found (fail-closed)" % (MUTATION_FILE, GROUP_UPDATE_FN)
        )
    else:
        if "lastContextLimitTokens = null" in groups_update:
            detail["c12"].append(
                "%s: %s clears lastContextLimitTokens in the contextLimitTokens branch "
                "— the canonical write face (applyContextLimitWrite) keeps the "
                "last-selected memory the UI restores on an OFF→ON toggle"
                % (MUTATION_FILE, GROUP_UPDATE_FN)
            )
        if not _live_sites(groups_update, r"applyContextLimitWrite\s*\("):
            # [batch6] the canonical face had to be *named*, not *reached*:
            # `mutatedGroupCopy(updated) { if (false) applyContextLimitWrite(it, v) }`
            # kept the token and the write never ran.
            detail["c12"].append(
                "%s: %s does not route the context-limit write through the canonical "
                "applyContextLimitWrite face (single source for clear/seed semantics)%s"
                % (MUTATION_FILE, GROUP_UPDATE_FN,
                   "" if "applyContextLimitWrite(" not in groups_update
                   else " — the call is present but sits on an unreachable path")
            )

    # ---- C13 ---- [fix/dv16-a A-06] the group write face's entry-id existence
    # check must be ONE implementation (the shared helper) reached from both
    # `add` and the entries writer — `add` used to accept groups whose members
    # referenced nonexistent entry ids while the entries writer rejected them.
    groups_paths = find_files(root, GROUPS_COLLECTION_FILE)
    rules_paths = find_files(root, GROUP_RULES_FILE)
    if len(groups_paths) != 1 or len(rules_paths) != 1:
        # Same defect shape as C8 above (see the dv24-FIX-4 R8 note): C13 and
        # check_group_member_faces read these two unconditionally, so a missing
        # GroupsCollection.kt / GroupWriteRules.kt crashed the gate with
        # UnboundLocalError instead of failing closed.
        groups_code = rules_code = ""
        detail["fail_closed"].append(
            "expected exactly one %s and one %s, found %d/%d"
            % (GROUPS_COLLECTION_FILE, GROUP_RULES_FILE, len(groups_paths), len(rules_paths))
        )
    else:
        with open(groups_paths[0], encoding="utf-8") as f:
            groups_code = mask_source(f.read())
        with open(rules_paths[0], encoding="utf-8") as f:
            rules_code = mask_source(f.read())
        n_msg = groups_code.count(ENTRY_ID_MSG) + rules_code.count(ENTRY_ID_MSG)
        if n_msg == 0:
            detail["fail_closed"].append(
                "%s: entry-id existence message %r not found (fail-closed)"
                % (GROUPS_COLLECTION_FILE, ENTRY_ID_MSG)
            )
        elif n_msg != 1:
            detail["c13"].append(
                "entry-id existence error message appears %d times across %s/%s "
                "— the write face must keep exactly one implementation"
                % (n_msg, GROUPS_COLLECTION_FILE, GROUP_RULES_FILE)
            )
        else:
            helper_pat = re.compile(r"fun\s+" + re.escape(ENTRY_ID_HELPER) + r"\s*\(")
            if not (helper_pat.search(groups_code) or helper_pat.search(rules_code)):
                detail["fail_closed"].append(
                    "%s not found (fail-closed) — the single implementation moved; "
                    "update this gate" % ENTRY_ID_HELPER
                )
        coll_add = _fn_text(groups_code, "add")
        if coll_add is None:
            detail["fail_closed"].append(
                "%s: add not found (fail-closed)" % GROUPS_COLLECTION_FILE
            )
        elif ENTRY_ID_HELPER + "(" not in coll_add:
            detail["c13"].append(
                "%s: add does not run the shared entry-id existence check (%s) — it "
                "accepts groups whose members reference nonexistent entry ids"
                % (GROUPS_COLLECTION_FILE, ENTRY_ID_HELPER)
            )
        entries_win = _fn_text(groups_code, "entriesField")
        if entries_win is None:
            detail["fail_closed"].append(
                "%s: entriesField not found (fail-closed)" % GROUPS_COLLECTION_FILE
            )
        elif ENTRY_ID_HELPER + "(" not in entries_win:
            detail["c13"].append(
                "%s: entriesField no longer routes through the shared entry-id "
                "existence check (%s)" % (GROUPS_COLLECTION_FILE, ENTRY_ID_HELPER)
            )

    # ---- C16/C17 ---- [dv20-a1 A-02] the group member faces must judge through
    # the ONE shared predicate, and must not re-spell the sentinel id.
    v16, v17, vfc = check_group_member_faces(mut_code, debug_code, groups_code)
    detail["c16"].extend(v16)
    detail["c17"].extend(v17)
    detail["fail_closed"].extend(vfc)

    # ---- C14 ---- [fix/dv16-a A-07] the two instance write faces must accept
    # the SAME optional key set — the create face previously dropped
    # imageEndpointMode entirely while the update face accepted it (an
    # input/output asymmetry: instanceJsonCore already emitted the keys).
    for key in INSTANCE_OPTIONAL_KEYS:
        if create_body is not None and key not in create_body:
            detail["c14"].append(
                "%s: instancesCreate does not accept %s while instancesUpdate does "
                "— the two write faces must accept the same key set"
                % (MUTATION_FILE, key)
            )
        if update_body is not None and key not in update_body:
            detail["c14"].append(
                "%s: instancesUpdate does not accept %s while instancesCreate does"
                % (MUTATION_FILE, key)
            )

    # ---- C15 ---- [fix/dv16-a A-07] the minis-config collection face must
    # register + parse the same instance keys the RPC write faces accept:
    # fields(forId) must list the image-endpoint fields and add() must decode
    # the writable one (before the fix both were missing, so the read/write
    # faces disagreed on where the keys live). The cached probe result stays a
    # derived read-only field on this face.
    img_coll_paths = find_files(root, PROVIDERS_COLLECTION_FILE)
    if len(img_coll_paths) != 1:
        detail["fail_closed"].append(
            "expected exactly one %s, found %d" % (PROVIDERS_COLLECTION_FILE, len(img_coll_paths))
        )
    else:
        with open(img_coll_paths[0], encoding="utf-8") as f:
            img_coll_code = mask_source(f.read())
        img_fields = _fn_text(img_coll_code, "fields")
        if img_fields is None:
            detail["fail_closed"].append(
                "%s: fields not found (fail-closed)" % PROVIDERS_COLLECTION_FILE
            )
        else:
            missing = [k for k in IMAGE_KEYS if k not in img_fields]
            if missing:
                detail["c15"].append(
                    "%s: fields does not register providers.<id>.%s"
                    % (PROVIDERS_COLLECTION_FILE, missing)
                )
        img_add = _fn_text(img_coll_code, "add")
        if img_add is None:
            detail["fail_closed"].append(
                "%s: add not found (fail-closed)" % PROVIDERS_COLLECTION_FILE
            )
        elif IMAGE_KEYS[0] not in img_add:
            detail["c15"].append(
                "%s: add does not decode %s — the write face must accept the same "
                "keys the RPC create face does" % (PROVIDERS_COLLECTION_FILE, IMAGE_KEYS[0])
            )
        resolved_win = _fn_text(img_coll_code, "imageEndpointResolved")
        if resolved_win is None or "ReadOnlyField" not in resolved_win:
            detail["c15"].append(
                "%s: imageEndpointResolved must stay a derived read-only field on the "
                "collection face (it is written by the auto-probe, never directly)"
                % PROVIDERS_COLLECTION_FILE
            )

    # ---- C18 ---- [dv24-A05] the config face's `session.*` writes must reach
    # the resident ChatViewModel, not just the Room row.
    store_paths = find_files(root, VM_STORE_FILE)
    cfg18_paths = find_files(root, CONFIG_BUILTINS_FILE)
    if len(store_paths) != 1:
        detail["fail_closed"].append(
            "expected exactly one %s, found %d" % (VM_STORE_FILE, len(store_paths)))
    elif len(cfg18_paths) != 1:
        # C8 already reported a missing/duplicated ConfigBuiltins.
        pass
    else:
        with open(cfg18_paths[0], encoding="utf-8") as f:
            cfg18 = mask_source(f.read())
        with open(store_paths[0], encoding="utf-8") as f:
            store18 = mask_source(f.read())
        v18, vfc18 = check_session_write_through(cfg18, store18)
        detail["c18"].extend(v18)
        detail["fail_closed"].extend(vfc18)
        # ---- C19 ---- [dv25-A06] the same two fields on the READ side.
        v19, vfc19 = check_session_read_through(cfg18)
        detail["c19"].extend(v19)
        detail["fail_closed"].extend(vfc19)

    rc = 0
    for k in ("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10", "c11",
              "c12", "c13", "c14", "c15", "c16", "c17", "c18", "c19", "fail_closed"):
        if detail[k]:
            rc = 1
    return rc, detail


# ---------------------------------------------------------------------------
# Selftest fixtures — each breaks exactly one criterion.
# ---------------------------------------------------------------------------

GOOD_DEBUG = '''package com.rikkaminis.app.debug

import com.rikkaminis.app.data.model.ProviderType
import org.json.JSONArray
import org.json.JSONObject

internal object ProviderDebugMethods {

    fun types(): JSONObject {
        val arr = JSONArray()
        for (type in ProviderType.values()) {
            arr.put(typeSpec(type))
        }
        return JSONObject().put("types", arr)
    }

    private fun typeSpec(type: ProviderType): JSONObject {
        val customBaseSupported = type.supportsCustomBaseURL
        return JSONObject().apply {
            put("id", type.name)
            put("customBaseURLSupported", customBaseSupported)
        }
    }

    fun instancesList(context: android.content.Context, params: JSONObject): JSONObject {
        val arr = JSONArray()
        for (inst in listOf<ProviderInstance>()) {
            arr.put(JSONObject().apply {
                put("id", inst.id)
                put("label", inst.label)
                put("providerType", inst.providerType.name)
                put("credentialType", inst.credentialType.name)
                put("isEnabled", inst.isEnabled)
                put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
                put("appendV1Suffix", inst.appendV1Suffix)
                put("useResponsesAPI", inst.useResponsesAPI)
                put("azureMode", inst.azureMode)
                put("imageEndpointMode", inst.imageEndpointMode.name)
                put("imageEndpointResolved", inst.imageEndpointResolved?.name ?: JSONObject.NULL)
                put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
                put("createdAt", inst.createdAt)
                put("hasCredential", false)
                put("modelEntryCount", 0)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("instances", arr)
        }
    }

    fun groupsList(context: android.content.Context, params: JSONObject): JSONObject {
        val arr = JSONArray()
        val resolvedMember = com.rikkaminis.app.data.model.SystemVoiceEntries.resolve("m")
        for (group in listOf<com.rikkaminis.app.data.model.ModelGroupStub>()) {
            arr.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("defaultThinkingLevel", group.defaultThinkingLevel?.name ?: JSONObject.NULL)
                put("contextLimitTokens", group.contextLimitTokens ?: JSONObject.NULL)
            })
        }
        return JSONObject().put("groups", arr)
    }
}
'''

GOOD_MUTATION = '''package com.rikkaminis.app.debug

import com.rikkaminis.app.data.model.ProviderType
import org.json.JSONObject

internal object ProviderMutationMethods {

    fun instancesCreate(context: android.content.Context, params: JSONObject): JSONObject {
        val type = ProviderType.openAI
        val customBaseURL: String? = params.optString("customBaseURL", "").ifEmpty { null }
        val azureMode = params.optBoolean("azureMode", false)
        val useResponsesAPI = params.optBoolean("useResponsesAPI", false)
        val appendV1Suffix = params.optBoolean("appendV1Suffix", true)
        val isEnabled = params.optBoolean("isEnabled", true)
        val customUserAgent = params.optString("customUserAgent", "").ifEmpty { null }
        val imageEndpointMode = if (params.has("imageEndpointMode")) parseImageEndpointMode(params.optString("imageEndpointMode", "auto")) else com.rikkaminis.app.data.model.ImageEndpointMode.auto
        // [da1023-C] guard comment (verbatim-ish)
        if (customBaseURL != null && !type.supportsCustomBaseURL) {
            throw RPCException(-32602, "customBaseURL is not supported for providerType=openRouter")
        }
        return JSONObject()
    }

    fun instancesUpdate(context: android.content.Context, params: JSONObject): JSONObject {
        val current = ProviderInstanceFix()
        val azureMode = if (params.has("azureMode")) params.optBoolean("azureMode", current.azureMode) else current.azureMode
        val imageEndpointMode = if (params.has("imageEndpointMode")) parseImageEndpointMode(params.optString("imageEndpointMode", "")) else current.imageEndpointMode
        val useResponsesAPI = if (params.has("useResponsesAPI")) params.optBoolean("useResponsesAPI", current.useResponsesAPI) else current.useResponsesAPI
        val appendV1Suffix = if (params.has("appendV1Suffix")) params.optBoolean("appendV1Suffix", current.appendV1Suffix) else current.appendV1Suffix
        val isEnabled = if (params.has("isEnabled")) params.optBoolean("isEnabled", current.isEnabled) else current.isEnabled
        val customUserAgent = if (params.has("customUserAgent")) params.optString("customUserAgent", "") else current.customUserAgent
        if (params.has("customBaseURL") && !params.isNull("customBaseURL")) {
            val newBase = params.optString("customBaseURL", "").ifEmpty { null }
            if (newBase != null && !current.providerType.supportsCustomBaseURL) {
                throw RPCException(-32602, "customBaseURL is not supported for providerType=openRouter")
            }
        }
        return instanceToJson(current)
    }

    private fun instanceToJson(inst: com.rikkaminis.app.data.model.ProviderInstanceStub): JSONObject {
        return instanceJsonCore(inst, false, 0)
    }

    suspend fun instancesTest(context: android.content.Context, params: JSONObject): JSONObject {
        val instance = current()
        val key: String? = null
        val builder = okhttp3.Request.Builder().url(probeURL).get()
        when (instance.providerType) {
            ProviderType.anthropic -> if (!key.isNullOrEmpty()) builder.header("x-api-key", key)
            ProviderType.openAI -> if (!key.isNullOrEmpty()) {
                val (name, value) = probeAuthHeader(instance.azureMode, key)
                builder.header(name, value)
            }
            ProviderType.openRouter -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            ProviderType.gemini -> { }
        }
        return JSONObject()
    }

    fun groupsCreate(context: android.content.Context, params: JSONObject): JSONObject {
        val thinking = params.optString("defaultThinkingLevel", "")
        val membersOk = SystemVoiceEntries.isKnownGroupMemberId("m", emptySet())
        val rawLimit = params.opt("contextLimitTokens")
        val group = com.rikkaminis.app.data.model.ModelGroupStub(name = params.optString("name", "New group"))
        return groupToJson(group)
    }

    fun groupsUpdate(context: android.content.Context, params: JSONObject): JSONObject {
        var limitValue = 0
        val membersOk = SystemVoiceEntries.isKnownGroupMemberId("m", emptySet())
        if (params.has("defaultThinkingLevel")) {
            val t = params.optString("defaultThinkingLevel", "")
        }
        if (params.has("contextLimitTokens")) {
            val raw = params.opt("contextLimitTokens")
            limitValue = 0
        }
        current = com.rikkaminis.app.config.collections.mutatedGroupCopy(current) { applyContextLimitWrite(it, limitValue) }
        val current = com.rikkaminis.app.data.model.ModelGroupStub()
        return groupToJson(current)
    }

    private fun groupToJson(group: com.rikkaminis.app.data.model.ModelGroupStub): JSONObject =
        JSONObject().apply {
            put("id", group.id)
            put("name", group.name)
            put("defaultThinkingLevel", group.defaultThinkingLevel?.name ?: JSONObject.NULL)
            put("contextLimitTokens", group.contextLimitTokens ?: JSONObject.NULL)
        }
}

internal fun probeAuthHeader(azureMode: Boolean, key: String): Pair<String, String> =
    openAIKeyAuthHeader(azure = azureMode, token = key)

internal fun instanceJsonCore(inst: ProviderInstanceFix, hasCredential: Boolean, entryCount: Int): JSONObject =
    JSONObject().apply {
        put("id", inst.id)
        put("label", inst.label)
        put("providerType", inst.providerType.name)
        put("credentialType", inst.credentialType.name)
        put("isEnabled", inst.isEnabled)
        put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
        put("appendV1Suffix", inst.appendV1Suffix)
        put("useResponsesAPI", inst.useResponsesAPI)
        put("azureMode", inst.azureMode)
        put("imageEndpointMode", inst.imageEndpointMode.name)
        put("imageEndpointResolved", inst.imageEndpointResolved?.name ?: JSONObject.NULL)
        put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
        put("createdAt", inst.createdAt)
        put("hasCredential", hasCredential)
        put("modelEntryCount", entryCount)
    }

internal val ProviderType.supportsCustomBaseURL: Boolean
    get() = this != ProviderType.openRouter

internal fun parseImageEndpointMode(raw: String): com.rikkaminis.app.data.model.ImageEndpointMode =
    when (raw) {
        "auto" -> com.rikkaminis.app.data.model.ImageEndpointMode.auto
        "imagesGenerations", "images_generations" -> com.rikkaminis.app.data.model.ImageEndpointMode.imagesGenerations
        "chatCompletions", "chat_completions" -> com.rikkaminis.app.data.model.ImageEndpointMode.chatCompletions
        else -> throw RPCException(-32602, "imageEndpointMode must be auto | images_generations | chat_completions (got '$raw')")
    }
'''

# ---------------------------------------------------------------------------
# [da1026-ISSUE-05] Collection-face fixture (ProvidersCollection.kt shape).
# ---------------------------------------------------------------------------

GOOD_GROUP_RULES = '''package com.rikkaminis.app.config.collections

internal fun applyContextLimitWrite(g: ModelGroup, tokens: Int) {
    if (tokens > 0) {
        g.contextLimitTokens = tokens
        g.lastContextLimitTokens = tokens
    } else {
        g.contextLimitTokens = null
    }
}

internal fun parseMemberEntryIds(arr: List<ConfigValue>): List<String> = emptyList()
'''

GOOD_GROUPS = '''package com.rikkaminis.app.config.collections

internal class GroupsCollection {
    override fun add(payload: ConfigValue): String {
        val entryArr = (payload as? ConfigValue.Arr)?.value
        val members = entryArr?.let { parseMemberEntryIds(it).toMutableList() } ?: mutableListOf()
        requireKnownEntryIds(members)
        val group = ModelGroup(name = "g", memberEntryIds = members)
        repo.addGroup(group)
        return group.id
    }

    private fun requireKnownEntryIds(ids: List<String>) {
        val valid = repo.config.value.modelEntries.map { it.id }.toSet()
        for (eid in ids) {
            if (!SystemVoiceEntries.isKnownGroupMemberId(eid, valid)) {
                throw ConfigError.InvalidValue("Unknown model entry uuid: $eid")
            }
        }
    }

    private fun entriesField(id: String): ConfigField =
        ClosureField(
            path = "groups." + id + ".entries",
            writer = { v ->
                val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                val ids = parseMemberEntryIds(arr)
                requireKnownEntryIds(ids)
                mutate(id) {
                    it.memberEntryIds.clear()
                    it.memberEntryIds.addAll(ids)
                }
            },
        )
}
'''

GOOD_COLLECTION = '''package com.rikkaminis.app.config.collections

internal class ProvidersCollection {
    override fun add(payload: ConfigValue): String {
        val providerType = resolveType(payload)
        val customBaseURL = resolveAddCustomBaseURL(rawBase(payload), providerType)
        val imageEndpointMode = imageEndpointModeFromToken(rawToken(payload)) ?: ImageEndpointMode.auto
        val instance = ProviderInstance(
            id = newId(),
            label = label,
            customBaseURL = customBaseURL,
            imageEndpointMode = imageEndpointMode,
        )
        repo.addInstance(instance)
        return instance.id
    }

    override fun fields(forId: String): List<ConfigField> = listOf(
        customBaseURL(forId),
        imageEndpointMode(forId),
        imageEndpointResolved(forId),
    )

    private fun customBaseURL(id: String): ConfigField =
        ClosureField(
            path = "providers." + id + ".customBaseURL",
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(inst.customBaseURL ?: "")
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                val inst = repo.instance(id) ?: throw ConfigError.InvalidValue("Provider not found")
                if (!inst.providerType.supportsCustomBaseURL) {
                    throw ConfigError.InvalidValue("customBaseURL is not supported for providerType=openRouter")
                }
                mutate(id) { it.copy(customBaseURL = s.ifEmpty { null }) }
            },
        )

    private fun imageEndpointMode(id: String): ConfigField =
        ClosureField(
            path = "providers." + id + ".imageEndpointMode",
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(imageEndpointModeToToken(inst.imageEndpointMode))
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { it.copy(imageEndpointMode = imageEndpointModeFromToken(s) ?: ImageEndpointMode.auto) }
            },
        )

    private fun imageEndpointResolved(id: String): ConfigField =
        ReadOnlyField(
            path = "providers." + id + ".imageEndpointResolved",
            reader = {
                val inst = repo.instance(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Null
            },
        )
}

'''

# [dv24-A05] C18's resident lookup: reads the store map, allocates nothing.
GOOD_VM_STORE = '''package com.rikkaminis.app.ui.chat

object ChatViewModelStore {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun peekViewModel(sessionId: String): ChatViewModel? {
        val store = stores[sessionId] ?: return null
        for (key in store.keys()) {
            val vm = store.get(key)
            if (vm is ChatViewModel) return vm
        }
        return null
    }
}
'''

# Minimal stand-ins so the fixture compiles conceptually (never compiled here);
# the gate only reads text.
GOOD_MUTATION = GOOD_MUTATION.replace("ProviderInstanceFix", "com.rikkaminis.app.data.model.ProviderInstanceStub")


# ---------------------------------------------------------------------------
# [da1030-C ISSUE-04] Config-face fixture: the read-only `providers` aggregate
# instance dictionary (mirrors ConfigBuiltins.registerProviderCollections).
# ---------------------------------------------------------------------------

GOOD_CONFIG_BUILTINS = '''package com.rikkaminis.app.config

internal object ConfigBuiltins {
    private fun registerProviderCollections(r: ConfigRegistry) {
        r.register(
            ReadOnlyField(
                path = "providers",
                reader = {
                    val instances = providerRepo.config.value.instances
                    ConfigValue.Arr(
                        instances.map { inst ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(inst.id),
                                    "useResponsesAPI" to ConfigValue.Bool(inst.useResponsesAPI),
                                    "azureMode" to ConfigValue.Bool(inst.azureMode),
                                    "imageEndpointMode" to ConfigValue.Str(inst.imageEndpointMode.name),
                                    "imageEndpointResolved" to (inst.imageEndpointResolved?.let { ConfigValue.Str(it.name) } ?: ConfigValue.Null),
                                    "customUserAgent" to (inst.customUserAgent?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                                )
                            )
                        }
                    )
                },
            )
        )
        r.register(
            ReadOnlyField(
                path = "groups",
                reader = {
                    ConfigValue.Arr(
                        repo.config.value.modelGroups.map { g ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(g.id),
                                    "name" to ConfigValue.Str(g.name),
                                    "default_thinking_level" to (g.defaultThinkingLevel?.let { ConfigValue.Str(it.name) } ?: ConfigValue.Null),
                                    "context_limit_tokens" to (g.contextLimitTokens?.let { ConfigValue.Num(it.toDouble()) } ?: ConfigValue.Null),
                                )
                            )
                        }
                    )
                },
            )
        )
    }
    }

    // [dv24-A05] the two `session.*` writers C18 judges. Both halves present:
    // the Room write (cross-restart store) + a refresh through the
    // non-creating resident lookup.
    private fun registerSession(r: ConfigRegistry, chatRepo: ChatRepository) {
        r.register(
            ClosureField(
                path = "session.thinkingLevel",
                reader = {
                    val sid = sessionIdProvider()
                    if (sid == null) {
                        "off"
                    } else {
                        val vm = residentViewModel(sid)
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        when (
                            thinkingLevelReadSource(
                                hasLiveViewModel = vm != null,
                                vmAdoptedRow = vm?.sessionLoaded?.value == true,
                                hasPersistedRow = session != null,
                            )
                        ) {
                            SessionConfigReadSource.ResidentViewModel ->
                                vm!!.requestedThinkingLevel.value
                            SessionConfigReadSource.PersistedRow -> session?.thinkingOverride ?: "off"
                            SessionConfigReadSource.Absent -> "off"
                        }
                    }
                },
                writer = { v ->
                    val sid = sessionIdProvider()
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    writeSessionConfig(
                        sessionId = sid,
                        persist = { chatRepo.dao.updateThinkingOverride(sid, v) },
                        refresh = { vm -> vm.setThinkingLevel(v) },
                    )
                },
            )
        )
        r.register(
            ClosureField(
                path = "session.primaryModel",
                reader = {
                    val sid = sessionIdProvider()
                    if (sid == null) {
                        ""
                    } else {
                        val vm = residentViewModel(sid)
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        when (
                            modelBindingReadSource(
                                hasLiveViewModel = vm != null,
                                hasPersistedRow = session != null,
                            )
                        ) {
                            SessionConfigReadSource.ResidentViewModel ->
                                residentBindingToken(vm!!)
                            SessionConfigReadSource.PersistedRow -> formatBinding(session?.modelBinding)
                            SessionConfigReadSource.Absent -> ""
                        }
                    }
                },
                writer = { v ->
                    val sid = sessionIdProvider()
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    if (v == "") {
                        writeSessionConfig(
                            sessionId = sid,
                            persist = { chatRepo.dao.updateSessionBinding(sid, "", "") },
                            refresh = { vm -> vm.applyNewChatDefaultModel() },
                        )
                        return@ClosureField
                    }
                    writeSessionConfig(
                        sessionId = sid,
                        persist = { chatRepo.dao.updateSessionBinding(sid, v, v) },
                        refresh = { vm -> vm.restoreFromBinding(v) },
                    )
                },
            )
        )
    }

    private fun residentViewModel(sid: String): ChatViewModel? {
        return runBlocking { ChatViewModelStore.peekViewModel(sid) }
    }

    private fun residentBindingToken(vm: ChatViewModel): String {
        val groupId = vm.selectedGroupId.value
        if (!groupId.isNullOrEmpty()) return "group:$groupId"
        return vm.activeEntryId.value?.let { "entry:$it" } ?: ""
    }

    private fun writeSessionConfig(
        sessionId: String,
        persist: () -> Unit,
        refresh: (ChatViewModel) -> Unit,
    ) {
        persist()
        val vm = ChatViewModelStore.peekViewModel(sessionId)
        if (vm != null) refresh(vm)
    }
}

'''

# ---------------------------------------------------------------------------
# [da1030-C ISSUE-04] Connection-screen fixture: the base-URL gate and the
# User-Agent gate as SEPARATE sibling guards (the required shape).
# ---------------------------------------------------------------------------

GOOD_CONNECTION = '''package com.rikkaminis.app.ui.settings

@Composable
fun ProviderConnectionScreen(instance: ProviderInstance, providerRepository: ProviderRepository) {
    var customBaseURL by rememberSaveable(instance.id, instance.customBaseURL) { mutableStateOf(instance.customBaseURL ?: "") }
    var customUserAgent by rememberSaveable(instance.id, instance.customUserAgent) { mutableStateOf(instance.customUserAgent ?: "") }
    Column {
        if (instance.providerType != ProviderType.openRouter) {
            SettingsSection(header = "Base URL") {
                SectionTextField(value = customBaseURL)
            }
        }
        if (customUserAgentWritable(instance.credentialType)) {
            SettingsSection(header = "User-Agent") {
                SectionTextField(value = customUserAgent)
            }
        }
    }
}
'''


# ---------------------------------------------------------------------------
# [da1031-D ISSUE-07] Auth single-source fixture: the shared pair builder
# `openAIKeyAuthHeader` plus a delegating `applyOpenAIKeyAuth` — the required
# two-face shape on the provider/openai side. Defaults for every fixture root
# (write_fixture writes it unless omit_auth=True).
# ---------------------------------------------------------------------------

GOOD_AUTH_HEADER = '''package com.rikkaminis.app.provider.openai

import okhttp3.Request

internal fun openAIKeyAuthHeader(azure: Boolean, token: String): Pair<String, String> =
    if (azure) "api-key" to token else "Authorization" to "Bearer $token"

internal fun Request.Builder.applyOpenAIKeyAuth(
    azure: Boolean,
    token: String,
): Request.Builder =
    openAIKeyAuthHeader(azure, token).let { (name, value) -> header(name, value) }
'''

# -- [dv20-a1 A-02] group member-face fixtures -------------------------------
# Self-contained on purpose: the shared GOOD_* fixtures above keep judging
# exactly what they judged before this batch.

GOOD_MEMBER_FACES_COLL = '''
package fake.collections

import com.rikkaminis.app.data.model.SystemVoiceEntries

class GroupsCollection {
    private fun requireKnownEntryIds(ids: List<String>) {
        val valid = repo.config.value.modelEntries.map { it.id }.toSet()
        for (eid in ids) {
            if (!SystemVoiceEntries.isKnownGroupMemberId(eid, valid)) {
                throw ConfigError.InvalidValue("Unknown model entry uuid: $eid")
            }
        }
    }
}
'''

GOOD_MEMBER_FACES_MUT = '''
package fake.debug

import com.rikkaminis.app.data.model.SystemVoiceEntries

object ProviderMutationMethods {
    fun groupsCreate(repo: Any, params: Any): Any {
        val knownEntryIds = repo.config.value.modelEntries.map { it.id }.toSet()
        if (id.isNotEmpty() && SystemVoiceEntries.isKnownGroupMemberId(id, knownEntryIds)) {
            members.add(id)
        }
        return members
    }

    fun groupsUpdate(repo: Any, params: Any): Any {
        val knownIds = repo.config.value.modelEntries.map { it.id }.toSet()
        if (mid.isNotEmpty() && SystemVoiceEntries.isKnownGroupMemberId(mid, knownIds)) {
            members.add(mid)
        }
        return members
    }
}
'''

GOOD_MEMBER_FACES_DBG = '''
package fake.debug

import com.rikkaminis.app.data.model.SystemVoiceEntries

object ProviderDebugMethods {
    fun groupsList(cfg: Any): Any {
        val members = JSONArray()
        for (memberId in group.memberEntryIds) {
            val persisted = cfg.modelEntries.find { it.id == memberId }
            val entry = persisted ?: SystemVoiceEntries.resolve(memberId) ?: continue
            members.put(JSONObject().apply { put("entryId", entry.id) })
        }
        return members
    }
}
'''


def write_fixture(debug_text, mutation_text, collection_text=None,
                  config_text=None, connection_text=None,
                  auth_text=None, omit_auth=False, groups_text=None, rules_text=None,
                  store_text=None):
    root = tempfile.mkdtemp(prefix="rpcsym-")
    os.makedirs(os.path.join(root, "src", "debug"))
    os.makedirs(os.path.join(root, "src", "collections"))
    os.makedirs(os.path.join(root, "src", "config"))
    os.makedirs(os.path.join(root, "src", "ui"))
    if not omit_auth:
        os.makedirs(os.path.join(root, "src", "provider"))
        with open(os.path.join(root, "src", "provider", AUTH_HEADER_FILE), "w", encoding="utf-8") as f:
            f.write(GOOD_AUTH_HEADER if auth_text is None else auth_text)
    with open(os.path.join(root, "src", "debug", DEBUG_FILE), "w", encoding="utf-8") as f:
        f.write(debug_text)
    with open(os.path.join(root, "src", "debug", MUTATION_FILE), "w", encoding="utf-8") as f:
        f.write(mutation_text)
    if collection_text is None:
        collection_text = GOOD_COLLECTION
    with open(os.path.join(root, "src", "collections", PROVIDERS_COLLECTION_FILE), "w", encoding="utf-8") as f:
        f.write(collection_text)
    # [fix/dv16-a A-06] the group write face (C13) — always the good shape;
    # C13-specific teeth mutate these via the groups_text rules_text params.
    with open(os.path.join(root, "src", "collections", GROUPS_COLLECTION_FILE), "w", encoding="utf-8") as f:
        f.write(GOOD_GROUPS if groups_text is None else groups_text)
    with open(os.path.join(root, "src", "collections", GROUP_RULES_FILE), "w", encoding="utf-8") as f:
        f.write(GOOD_GROUP_RULES if rules_text is None else rules_text)
    config_text = GOOD_CONFIG_BUILTINS if config_text is None else config_text
    with open(os.path.join(root, "src", "config", CONFIG_BUILTINS_FILE), "w", encoding="utf-8") as f:
        f.write(config_text)
    connection_text = GOOD_CONNECTION if connection_text is None else connection_text
    with open(os.path.join(root, "src", "ui", CONNECTION_SCREEN_FILE), "w", encoding="utf-8") as f:
        f.write(connection_text)
    # [dv24-A05] the resident-VM store that C18's refresh half must go through.
    with open(os.path.join(root, "src", "ui", VM_STORE_FILE), "w", encoding="utf-8") as f:
        f.write(GOOD_VM_STORE if store_text is None else store_text)
    return root


def run_selftest():
    results = []

    # Contrast: good pair must be fully green.
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION)
    rc, detail = check_tree(root)
    results.append(("CONTRAST-good-green", rc == 0, detail))
    shutil.rmtree(root)

    # F1: typeSpec back to inline hardcode → only C1 red.
    bad = GOOD_DEBUG.replace(
        "val customBaseSupported = type.supportsCustomBaseURL",
        "val customBaseSupported = " + INLINE_HARDCODE,
    )
    root = write_fixture(bad, GOOD_MUTATION)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c1"] and not detail["c2"] and not detail["c3"] and not detail["fail_closed"]
    results.append(("F1-inline-hardcode-C1", ok, detail))
    shutil.rmtree(root)

    # F2: update guard deleted entirely → only C2 red.
    bad = GOOD_MUTATION.replace(
        '''        if (params.has("customBaseURL") && !params.isNull("customBaseURL")) {
            val newBase = params.optString("customBaseURL", "").ifEmpty { null }
            if (newBase != null && !current.providerType.supportsCustomBaseURL) {
                throw RPCException(-32602, "customBaseURL is not supported for providerType=openRouter")
            }
        }
''',
        "",
    )
    root = write_fixture(GOOD_DEBUG, bad)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c2"] and not detail["c1"] and not detail["c3"] and not detail["fail_closed"]
    results.append(("F2-update-guard-deleted-C2", ok, detail))
    shutil.rmtree(root)

    # F3: imageEndpoint keys removed from instanceJsonCore → only C3 red.
    bad = GOOD_MUTATION.replace(
        '''        put("imageEndpointMode", inst.imageEndpointMode.name)
        put("imageEndpointResolved", inst.imageEndpointResolved?.name ?: JSONObject.NULL)
''',
        "",
    )
    root = write_fixture(GOOD_DEBUG, bad)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c3"] and not detail["c1"] and not detail["c2"] and not detail["fail_closed"]
    results.append(("F3-imageEndpoint-keys-removed-C3", ok, detail))
    shutil.rmtree(root)

    # F4: update guard message changed → only C2 red (predicate ref still there).
    bad = GOOD_MUTATION.replace(
        'throw RPCException(-32602, "customBaseURL is not supported for providerType=openRouter")\n            }\n        }\n        return instanceToJson',
        'throw RPCException(-32602, "customBaseURL not allowed here")\n            }\n        }\n        return instanceToJson',
        1,
    )
    root = write_fixture(GOOD_DEBUG, bad)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c2"] and not detail["c1"] and not detail["c3"] and not detail["fail_closed"]
    results.append(("F4-guard-message-changed-C2", ok, detail))
    shutil.rmtree(root)

    # F5: predicate definition removed → only C1 red.
    bad = GOOD_MUTATION.replace(
        '''internal val ProviderType.supportsCustomBaseURL: Boolean
    get() = this != ProviderType.openRouter
''',
        "",
    )
    root = write_fixture(GOOD_DEBUG, bad)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c1"] and not detail["c2"] and not detail["c3"] and not detail["fail_closed"]
    results.append(("F5-predicate-def-removed-C1", ok, detail))
    shutil.rmtree(root)

    # F6: fail-closed — instanceToJson missing entirely.
    bad = GOOD_MUTATION.replace("private fun instanceToJson(", "private fun instanceToJsonX(")
    bad = bad.replace("return instanceToJson(current)", "return instanceToJsonX(current)")
    root = write_fixture(GOOD_DEBUG, bad)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["fail_closed"]
    results.append(("F6-fail-closed-missing-json", ok, detail))
    shutil.rmtree(root)

    # F7: collection `add` guard removed → only C4 red.
    bad_coll = GOOD_COLLECTION.replace(
        "val customBaseURL = resolveAddCustomBaseURL(rawBase(payload), providerType)",
        "val customBaseURL = rawBase(payload)",
    )
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, bad_coll)
    rc, detail = check_tree(root)
    ok = (rc == 1 and detail["c4"] and not detail["c1"] and not detail["c2"]
          and not detail["c3"] and not detail["c5"] and not detail["fail_closed"])
    results.append(("F7-collection-add-guard-deleted-C4", ok, detail))
    shutil.rmtree(root)

    # F8: collection field-writer guard removed → only C4 red.
    bad_coll = GOOD_COLLECTION.replace(
        '''                if (!inst.providerType.supportsCustomBaseURL) {
                    throw ConfigError.InvalidValue("customBaseURL is not supported for providerType=openRouter")
                }
''',
        "",
    )
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, bad_coll)
    rc, detail = check_tree(root)
    ok = (rc == 1 and detail["c4"] and not detail["c1"] and not detail["c2"]
          and not detail["c3"] and not detail["c5"] and not detail["fail_closed"])
    results.append(("F8-collection-writer-guard-deleted-C4", ok, detail))
    shutil.rmtree(root)

    # F9: collection has NO customBaseURL write site at all → fail-closed red.
    bad_coll = GOOD_COLLECTION.replace("customBaseURL = customBaseURL,\n", "")
    bad_coll = bad_coll.replace(
        "mutate(id) { it.copy(customBaseURL = s.ifEmpty { null }) }", "mutate(id) { it }")
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, bad_coll)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["fail_closed"] and not detail["c4"]
    results.append(("F9-collection-no-write-site-fail-closed", ok, detail))
    shutil.rmtree(root)

    # F10: mutation guards inlined to a raw `!= openRouter` compare → the
    # predicate use-count drops below its floor. C5 shares this token with
    # C1/C2, so it is a floor guard rather than an independently
    # distinguishable criterion — the assertion only requires C5 to fire
    # (C1 fires too, by construction).
    bad = GOOD_MUTATION.replace(
        "if (customBaseURL != null && !type.supportsCustomBaseURL) {",
        "if (customBaseURL != null && type != ProviderType.openRouter) {",
    )
    root = write_fixture(GOOD_DEBUG, bad)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c5"]
    results.append(("F10-mutation-predicate-inlined-C5", ok, detail))
    shutil.rmtree(root)

    # F11 [ARCH-01]: collection writer routes the guard through the shared
    # predicate (ProviderWriteRules.isCustomBaseURLWriteAllowed) — the new
    # accepted shape must be GREEN (old gate red here: acceptance broadening).
    new_coll = GOOD_COLLECTION.replace(
        '''                if (!inst.providerType.supportsCustomBaseURL) {
                    throw ConfigError.InvalidValue("customBaseURL is not supported for providerType=openRouter")
                }
                mutate(id) { it.copy(customBaseURL = s.ifEmpty { null }) }''',
        '''                val newBase = s.ifEmpty { null }
                if (!isCustomBaseURLWriteAllowed(inst.providerType, newBase)) {
                    throw ConfigError.InvalidValue(CUSTOM_BASE_URL_OPENROUTER_MESSAGE)
                }
                mutate(id) { it.copy(customBaseURL = newBase) }''',
    )
    assert new_coll != GOOD_COLLECTION, "F11 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, new_coll)
    rc, detail = check_tree(root)
    results.append(("F11-writer-guard-via-shared-predicate-green", rc == 0, detail))
    shutil.rmtree(root)

    # F12 [ARCH-01]: teeth — shared-predicate writer with the guard call
    # removed must stay C4-red (the F11 acceptance did not blind C4).
    bad_coll = new_coll.replace(
        '''                if (!isCustomBaseURLWriteAllowed(inst.providerType, newBase)) {
                    throw ConfigError.InvalidValue(CUSTOM_BASE_URL_OPENROUTER_MESSAGE)
                }
''',
        "",
    )
    assert bad_coll != new_coll, "F12 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, bad_coll)
    rc, detail = check_tree(root)
    ok = (rc == 1 and detail["c4"] and not detail["c1"] and not detail["c2"]
          and not detail["c3"] and not detail["c5"] and not detail["fail_closed"])
    results.append(("F12-shared-predicate-writer-guard-deleted-C4", ok, detail))
    shutil.rmtree(root)

    # F13 [ARCH-01]: update guard message via the shared constant reference —
    # the new accepted shape must be GREEN (literal copy removed by ARCH-01).
    new_mut = GOOD_MUTATION.replace(
        '''            if (newBase != null && !current.providerType.supportsCustomBaseURL) {
                throw RPCException(-32602, "customBaseURL is not supported for providerType=openRouter")
            }''',
        '''            if (newBase != null && !current.providerType.supportsCustomBaseURL) {
                throw RPCException(-32602, CUSTOM_BASE_URL_OPENROUTER_MESSAGE)
            }''',
        1,
    )
    assert new_mut != GOOD_MUTATION, "F13 anchor missing"
    root = write_fixture(GOOD_DEBUG, new_mut)
    rc, detail = check_tree(root)
    results.append(("F13-update-message-via-shared-constant-green", rc == 0, detail))
    shutil.rmtree(root)

    # F14 [ARCH-01]: teeth — a *near-miss* constant name (drifted copy) is
    # neither the literal nor the shared constant → C2-red (F4 covers the
    # plain-literal drift; this one pins the constant-identifier arm).
    bad_mut = GOOD_MUTATION.replace(
        '''            if (newBase != null && !current.providerType.supportsCustomBaseURL) {
                throw RPCException(-32602, "customBaseURL is not supported for providerType=openRouter")
            }''',
        '''            if (newBase != null && !current.providerType.supportsCustomBaseURL) {
                throw RPCException(-32602, CUSTOM_BASE_URL_OPENROUTER_MESSAGE_V2)
            }''',
        1,
    )
    assert bad_mut != GOOD_MUTATION, "F14 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c2"] and not detail["c1"] and not detail["c3"] and not detail["fail_closed"]
    results.append(("F14-update-message-near-miss-constant-C2", ok, detail))
    shutil.rmtree(root)

    # --- [da1030-C ISSUE-04] C6: azureMode read faces (list + mutation JSON) ---

    def only(detail, key):
        """True when [key] is the ONLY criterion that fired."""
        return not any(detail[k] for k in detail if k != key)

    bad_debug = GOOD_DEBUG.replace('                put("azureMode", inst.azureMode)\n', "")
    bad_mut = GOOD_MUTATION.replace('        put("azureMode", inst.azureMode)\n', "")
    assert bad_debug != GOOD_DEBUG and bad_mut != GOOD_MUTATION, "F15 anchor missing"
    root = write_fixture(bad_debug, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c6"] and only(detail, "c6")
    results.append(("F15-azureMode-invisible-on-both-read-faces-C6", ok, detail))
    shutil.rmtree(root)

    # --- C6: azureMode write faces (create + update) ---
    # (the same mutation also trips C14 — the new key-parity gate sees the same
    # missing key — so the ok check tolerates c14, still pins c6)
    bad_mut = GOOD_MUTATION.replace(
        '        val azureMode = params.optBoolean("azureMode", false)\n', "")
    bad_mut = bad_mut.replace(
        '        val azureMode = if (params.has("azureMode")) params.optBoolean("azureMode", current.azureMode) else current.azureMode\n',
        "")
    assert "val azureMode = params.optBoolean" not in bad_mut, "F16 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c6"] and not detail["c15"] and not detail["fail_closed"]
    results.append(("F16-azureMode-not-writable-C6", ok, detail))
    shutil.rmtree(root)

    # --- C7: probe falls back to unconditional Bearer ---
    bad_mut = GOOD_MUTATION.replace(
        '''            ProviderType.openAI -> if (!key.isNullOrEmpty()) {
                val (name, value) = probeAuthHeader(instance.azureMode, key)
                builder.header(name, value)
            }
''',
        '''            ProviderType.openAI -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
''',
    )
    assert "probeAuthHeader(instance.azureMode, key)" not in bad_mut, "F17 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c7"] and only(detail, "c7")
    results.append(("F17-probe-unconditional-bearer-C7", ok, detail))
    shutil.rmtree(root)

    # --- C8: config face drops the image-endpoint keys ---
    bad_cfg = GOOD_CONFIG_BUILTINS.replace(
        '''                                    "imageEndpointMode" to ConfigValue.Str(inst.imageEndpointMode.name),
                                    "imageEndpointResolved" to (inst.imageEndpointResolved?.let { ConfigValue.Str(it.name) } ?: ConfigValue.Null),
''', "")
    assert bad_cfg != GOOD_CONFIG_BUILTINS, "F18 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, config_text=bad_cfg)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c8"] and only(detail, "c8")
    results.append(("F18-config-face-missing-imageEndpoint-keys-C8", ok, detail))
    shutil.rmtree(root)

    # --- C9: UA visibility gate nested back inside the base-URL gate ---
    bad_conn = GOOD_CONNECTION.replace(
        '''        if (customUserAgentWritable(instance.credentialType)) {
            SettingsSection(header = "User-Agent") {
                SectionTextField(value = customUserAgent)
            }
        }
''', "")
    bad_conn = bad_conn.replace(
        '''                SectionTextField(value = customBaseURL)
            }
        }
''',
        '''                SectionTextField(value = customBaseURL)
            }
            if (customUserAgentWritable(instance.credentialType)) {
                SettingsSection(header = "User-Agent") {
                    SectionTextField(value = customUserAgent)
                }
            }
        }
''',
    )
    assert bad_conn.count("customUserAgentWritable(") == 1, "F19 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, connection_text=bad_conn)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c9"] and only(detail, "c9")
    results.append(("F19-ua-gate-inside-openrouter-block-C9", ok, detail))
    shutil.rmtree(root)

    # --- C9: visibility gate deleted entirely → fail-closed (anchor must be re-derived) ---
    bad_conn = GOOD_CONNECTION.replace(
        "if (customUserAgentWritable(instance.credentialType)) {", "if (true) {")
    assert "customUserAgentWritable(" not in bad_conn, "F20 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, connection_text=bad_conn)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c9"] and not detail["fail_closed"]
    results.append(("F20-ua-visibility-anchor-deleted-C9", ok, detail))
    shutil.rmtree(root)

    # --- C9: base-URL guard block gone → fail-closed ---
    bad_conn = GOOD_CONNECTION.replace(
        '''        if (instance.providerType != ProviderType.openRouter) {
            SettingsSection(header = "Base URL") {
                SectionTextField(value = customBaseURL)
            }
        }
''', "")
    assert "ProviderType.openRouter" not in bad_conn, "F21 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, connection_text=bad_conn)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["fail_closed"] and only(detail, "fail_closed")
    results.append(("F21-openrouter-ui-guard-missing-fail-closed", ok, detail))
    shutil.rmtree(root)

    # --- C8: `providers` path anchor gone → fail-closed ---
    bad_cfg = GOOD_CONFIG_BUILTINS.replace('path = "providers"', 'path = "providersX"')
    assert 'path = "providers"' not in bad_cfg, "F22 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, config_text=bad_cfg)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["fail_closed"] and only(detail, "fail_closed")
    results.append(("F22-config-providers-anchor-missing-fail-closed", ok, detail))
    shutil.rmtree(root)

    # --- [da1031-D ISSUE-07] C10: the probe grows its own azure/bearer fork ---
    bad_mut = GOOD_MUTATION.replace(
        '''internal fun probeAuthHeader(azureMode: Boolean, key: String): Pair<String, String> =
    openAIKeyAuthHeader(azure = azureMode, token = key)''',
        '''internal fun probeAuthHeader(azureMode: Boolean, key: String): Pair<String, String> =
    if (azureMode) "api-key" to key else "Authorization" to "Bearer $key"''',
    )
    assert bad_mut != GOOD_MUTATION, "F23 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c10"] and only(detail, "c10")
    results.append(("F23-probe-auth-fork-re-inlined-C10", ok, detail))
    shutil.rmtree(root)

    # --- C10: applyOpenAIKeyAuth stops delegating (second body on the okhttp face) ---
    bad_auth = GOOD_AUTH_HEADER.replace(
        '''): Request.Builder =
    openAIKeyAuthHeader(azure, token).let { (name, value) -> header(name, value) }''',
        '''): Request.Builder =
    if (azure) header("api-key", token) else header("Authorization", "Bearer $token")''',
    )
    assert bad_auth != GOOD_AUTH_HEADER, "F24 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, auth_text=bad_auth)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c10"] and only(detail, "c10")
    results.append(("F24-apply-auth-not-delegating-C10", ok, detail))
    shutil.rmtree(root)

    # --- C10: the shared pair builder vanishes (callers delegate to nothing) ---
    bad_auth = GOOD_AUTH_HEADER.replace(
        '''internal fun openAIKeyAuthHeader(azure: Boolean, token: String): Pair<String, String> =
    if (azure) "api-key" to token else "Authorization" to "Bearer $token"

''', "")
    assert "fun openAIKeyAuthHeader(" not in bad_auth, "F25 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, auth_text=bad_auth)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c10"] and only(detail, "c10")
    results.append(("F25-auth-single-source-undefined-C10", ok, detail))
    shutil.rmtree(root)

    # --- C10: the auth file itself is gone → fail-closed ---
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, omit_auth=True)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["fail_closed"] and only(detail, "fail_closed")
    results.append(("F26-auth-header-file-missing-fail-closed", ok, detail))
    shutil.rmtree(root)

    # --- [da1031-D ISSUE-06] C11: the read face drops a session-default key ---
    bad_debug = GOOD_DEBUG.replace(
        '''                put("defaultThinkingLevel", group.defaultThinkingLevel?.name ?: JSONObject.NULL)\n''',
        "",
    )
    assert bad_debug != GOOD_DEBUG, "F27 anchor missing"
    root = write_fixture(bad_debug, GOOD_MUTATION)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c11"] and only(detail, "c11")
    results.append(("F27-groupsList-missing-thinking-level-C11", ok, detail))
    shutil.rmtree(root)

    # --- C11: the mutation echo face stops mirroring the read face ---
    bad_mut = GOOD_MUTATION.replace(
        '''            put("defaultThinkingLevel", group.defaultThinkingLevel?.name ?: JSONObject.NULL)
            put("contextLimitTokens", group.contextLimitTokens ?: JSONObject.NULL)
''',
        "",
    )
    assert bad_mut != GOOD_MUTATION, "F28 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c11"] and only(detail, "c11")
    results.append(("F28-groupToJson-missing-both-keys-C11", ok, detail))
    shutil.rmtree(root)

    # --- C11: create echoes the fields but never accepts them (read-only in disguise) ---
    bad_mut = GOOD_MUTATION.replace(
        '''        val thinking = params.optString("defaultThinkingLevel", "")\n''', "",
    )
    assert bad_mut != GOOD_MUTATION, "F29 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c11"] and only(detail, "c11")
    results.append(("F29-groupsCreate-does-not-accept-thinking-level-C11", ok, detail))
    shutil.rmtree(root)

    # --- C11: update accepts neither field ---
    bad_mut = GOOD_MUTATION.replace(
        '''        if (params.has("defaultThinkingLevel")) {
            val t = params.optString("defaultThinkingLevel", "")
        }
        if (params.has("contextLimitTokens")) {
            val raw = params.opt("contextLimitTokens")
            limitValue = 0
        }
''',
        "",
    )
    assert bad_mut != GOOD_MUTATION, "F30 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c11"] and only(detail, "c11")
    results.append(("F30-groupsUpdate-accepts-neither-field-C11", ok, detail))
    shutil.rmtree(root)

    # --- C11: the config face loses a key (the asymmetry that started this) ---
    bad_cfg = GOOD_CONFIG_BUILTINS.replace(
        '''                                    "default_thinking_level" to (g.defaultThinkingLevel?.let { ConfigValue.Str(it.name) } ?: ConfigValue.Null),
''', "",
    )
    assert bad_cfg != GOOD_CONFIG_BUILTINS, "F31 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, config_text=bad_cfg)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c11"] and only(detail, "c11")
    results.append(("F31-config-groups-face-missing-key-C11", ok, detail))
    shutil.rmtree(root)

    # --- C11: the `path = "groups"` anchor is gone → fail-closed ---
    bad_cfg = GOOD_CONFIG_BUILTINS.replace('path = "groups"', 'path = "groupz"')
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, config_text=bad_cfg)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["fail_closed"] and only(detail, "fail_closed")
    results.append(("F32-config-groups-anchor-missing-fail-closed", ok, detail))
    shutil.rmtree(root)

    # --- [fix/dv16-a A-06] C12: the clear branch wins back its old shape and
    # nulls lastContextLimitTokens (the exact regression the gate exists for).
    bad_mut = GOOD_MUTATION.replace(
        '        current = com.rikkaminis.app.config.collections.mutatedGroupCopy(current) { applyContextLimitWrite(it, limitValue) }\n',
        '        current = current.copy(contextLimitTokens = null, lastContextLimitTokens = null)\n',
    )
    assert bad_mut != GOOD_MUTATION, "F33 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c12"] and only(detail, "c12")
    results.append(("F33-groupsUpdate-clears-lastContextLimitTokens-C12", ok, detail))
    shutil.rmtree(root)

    # --- C12: binding-decisions-not-spelling — the canonical write face applied
    # through a plain working copy (different spelling, same decision) must stay
    # green.
    alt_mut = GOOD_MUTATION.replace(
        '        current = com.rikkaminis.app.config.collections.mutatedGroupCopy(current) { applyContextLimitWrite(it, limitValue) }\n',
        '        current = current.copy()\n'
        '        com.rikkaminis.app.config.collections.applyContextLimitWrite(current, limitValue)\n',
    )
    assert alt_mut != GOOD_MUTATION, "F34 anchor missing"
    root = write_fixture(GOOD_DEBUG, alt_mut)
    rc, detail = check_tree(root)
    results.append(("F34-context-limit-alt-spelling-green", rc == 0, detail))
    shutil.rmtree(root)

    # --- [fix/dv16-a A-06] C13: `add` stops running the shared existence check
    # (the exact hole the gate exists for) → only C13 red.
    bad_groups = GOOD_GROUPS.replace("        requireKnownEntryIds(members)\n", "")
    assert bad_groups != GOOD_GROUPS, "F35 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, groups_text=bad_groups)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c13"] and only(detail, "c13")
    results.append(("F35-add-skips-shared-entry-id-check-C13", ok, detail))
    shutil.rmtree(root)

    # --- C13: a second copy of the existence check appears (the drift the
    # single-source criterion catches) → only C13 red.
    dup_rules = GOOD_GROUP_RULES + (
        '\ninternal fun legacyEntryGuard(ids: List<String>) {\n'
        '    for (e in ids) {\n'
        '        if (e.isEmpty()) throw ConfigError.InvalidValue("Unknown model entry uuid: $e")\n'
        '    }\n'
        '}\n'
    )
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, rules_text=dup_rules)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c13"] and only(detail, "c13")
    results.append(("F36-second-entry-id-check-copy-C13", ok, detail))
    shutil.rmtree(root)

    # --- [fix/dv16-a A-07] C14: the create face drops imageEndpointMode again
    # (the asymmetry the gate exists for) → only C14 red.
    bad_mut = GOOD_MUTATION.replace(
        '        val imageEndpointMode = if (params.has("imageEndpointMode")) parseImageEndpointMode(params.optString("imageEndpointMode", "auto")) else com.rikkaminis.app.data.model.ImageEndpointMode.auto\n',
        "",
    )
    assert bad_mut != GOOD_MUTATION, "F37 anchor missing"
    root = write_fixture(GOOD_DEBUG, bad_mut)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c14"] and only(detail, "c14")
    results.append(("F37-create-drops-imageEndpointMode-C14", ok, detail))
    shutil.rmtree(root)

    # --- [fix/dv16-a A-07] C15: the collection face stops registering the
    # derived probe field → only C15 red.
    bad_coll = GOOD_COLLECTION.replace("        imageEndpointResolved(forId),\n", "")
    assert bad_coll != GOOD_COLLECTION, "F38 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, collection_text=bad_coll)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c15"] and only(detail, "c15")
    results.append(("F38-fields-missing-resolved-key-C15", ok, detail))
    shutil.rmtree(root)

    # --- C15: the collection add stops decoding the writable image key ---
    bad_coll = GOOD_COLLECTION.replace(
        "        val imageEndpointMode = imageEndpointModeFromToken(rawToken(payload)) ?: ImageEndpointMode.auto\n"
        "        val instance = ProviderInstance(\n"
        "            id = newId(),\n"
        "            label = label,\n"
        "            customBaseURL = customBaseURL,\n"
        "            imageEndpointMode = imageEndpointMode,\n"
        "        )\n",
        "        val instance = ProviderInstance(\n"
        "            id = newId(),\n"
        "            label = label,\n"
        "            customBaseURL = customBaseURL,\n"
        "        )\n",
    )
    assert bad_coll != GOOD_COLLECTION, "F39 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, collection_text=bad_coll)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c15"] and only(detail, "c15")
    results.append(("F39-add-does-not-decode-imageEndpointMode-C15", ok, detail))
    shutil.rmtree(root)

    # --- [dv20-a1 A-02] C16/C17: the group member faces judge through the one
    # shared predicate, and none of them re-spells the sentinel id.
    def member_face_arm(label, key, mut, dbg, grp):
        v16, v17, vfc = check_group_member_faces(mut, dbg, grp)
        got = {"c16": v16, "c17": v17, "fail_closed": vfc}
        if key is None:
            clean = not any(got.values())
            results.append((label, clean, {} if clean else got))
            return
        ok = bool(got[key]) and not any(got[k] for k in got if k != key)
        results.append((label, ok, got))

    member_face_arm("F40-member-faces-good-green", None,
                    GOOD_MEMBER_FACES_MUT, GOOD_MEMBER_FACES_DBG, GOOD_MEMBER_FACES_COLL)
    member_face_arm(
        "F41-collection-refuses-sentinels-C16", "c16",
        GOOD_MEMBER_FACES_MUT, GOOD_MEMBER_FACES_DBG,
        GOOD_MEMBER_FACES_COLL.replace(
            "!SystemVoiceEntries.isKnownGroupMemberId(eid, valid)", "eid !in valid"))
    member_face_arm(
        "F42-create-drops-sentinels-C16", "c16",
        GOOD_MEMBER_FACES_MUT.replace(
            "SystemVoiceEntries.isKnownGroupMemberId(id, knownEntryIds)", "id in knownEntryIds"),
        GOOD_MEMBER_FACES_DBG, GOOD_MEMBER_FACES_COLL)
    member_face_arm(
        "F43-list-drops-sentinels-C16", "c16",
        GOOD_MEMBER_FACES_MUT,
        GOOD_MEMBER_FACES_DBG.replace(" ?: SystemVoiceEntries.resolve(memberId)", ""),
        GOOD_MEMBER_FACES_COLL)
    member_face_arm(
        "F44-sentinel-literal-respelled-C17", "c17",
        GOOD_MEMBER_FACES_MUT.replace(
            "        val knownIds = repo.config.value.modelEntries.map { it.id }.toSet()",
            "        val pinned = \"__builtin_system_speech__\"\n"
            "        val knownIds = repo.config.value.modelEntries.map { it.id }.toSet()"),
        GOOD_MEMBER_FACES_DBG, GOOD_MEMBER_FACES_COLL)
    member_face_arm(
        "F45-member-face-anchor-absent-fail-closed", "fail_closed",
        GOOD_MEMBER_FACES_MUT, GOOD_MEMBER_FACES_DBG,
        "package fake\n\nclass GroupsCollection {\n}\n")

    # --- [dv24-A05] C18: the config face's `session.*` writers must reach the
    # resident ChatViewModel (non-creating lookup), not just the Room row.
    def session_arm(label, cfg_text, store_text, want_key):
        v18, vfc = check_session_write_through(cfg_text, store_text)
        got = {"c18": v18, "fail_closed": vfc}
        if want_key is None:
            clean = not any(got.values())
            results.append((label, clean, {} if clean else got))
            return
        ok = bool(got[want_key]) and not any(got[k] for k in got if k != want_key)
        results.append((label, ok, got))

    THINK_REFRESH = """                        refresh = { vm -> vm.setThinkingLevel(v) },
"""
    CLEAR_REFRESH = """                            refresh = { vm -> vm.applyNewChatDefaultModel() },
"""
    SET_REFRESH = """                        refresh = { vm -> vm.restoreFromBinding(v) },
"""
    THINK_PERSIST = """                        persist = { chatRepo.dao.updateThinkingOverride(sid, v) },
"""
    THINK_CALL = """                    writeSessionConfig(
                        sessionId = sid,
                        persist = { chatRepo.dao.updateThinkingOverride(sid, v) },
                        refresh = { vm -> vm.setThinkingLevel(v) },
                    )"""
    CLEAR_CALL = """                        writeSessionConfig(
                            sessionId = sid,
                            persist = { chatRepo.dao.updateSessionBinding(sid, "", "") },
                            refresh = { vm -> vm.applyNewChatDefaultModel() },
                        )"""
    SET_CALL = """                    writeSessionConfig(
                        sessionId = sid,
                        persist = { chatRepo.dao.updateSessionBinding(sid, v, v) },
                        refresh = { vm -> vm.restoreFromBinding(v) },
                    )"""
    GUARD = """                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
"""

    session_arm("F46-session-write-through-green", GOOD_CONFIG_BUILTINS, GOOD_VM_STORE, None)
    for label, needle in (("thinking", THINK_REFRESH), ("clear", CLEAR_REFRESH),
                          ("set", SET_REFRESH)):
        assert GOOD_CONFIG_BUILTINS.count(needle) == 1, "F47/F48/F49 anchor missing: " + label
        session_arm(
            "F47-session-%s-refresh-dropped-C18" % label,
            GOOD_CONFIG_BUILTINS.replace(needle, ""), GOOD_VM_STORE, "c18")
    assert GOOD_CONFIG_BUILTINS.count(THINK_PERSIST) == 1, "F50 anchor missing"
    session_arm(
        "F50-session-think-refresh-only-no-row-C18",
        GOOD_CONFIG_BUILTINS.replace(THINK_PERSIST, ""), GOOD_VM_STORE, "c18")
    baseline = GOOD_CONFIG_BUILTINS.replace(
        THINK_CALL, "                    chatRepo.dao.updateThinkingOverride(sid, v)")
    baseline = baseline.replace(
        CLEAR_CALL, '                        chatRepo.dao.updateSessionBinding(sid, "", "")')
    baseline = baseline.replace(
        SET_CALL, "                    chatRepo.dao.updateSessionBinding(sid, v, v)")
    assert baseline.count("writeSessionConfig(") == 1, "F51 anchor missing"
    v18, vfc = check_session_write_through(baseline, GOOD_VM_STORE)
    results.append(("F51-session-baseline-dao-only-C18",
                    bool(v18) and not vfc and len(v18) >= 3,
                    {"c18": v18, "fail_closed": vfc}))
    session_arm(
        "F52-peek-allocates-C18",
        GOOD_CONFIG_BUILTINS,
        GOOD_VM_STORE.replace(
            "stores[sessionId]", "stores.getOrPut(sessionId) { ViewModelStore() }"),
        "c18")
    session_arm(
        "F53-peek-missing-fail-closed",
        GOOD_CONFIG_BUILTINS,
        GOOD_VM_STORE.replace("fun peekViewModel", "fun peekViewModelRenamed"),
        "fail_closed")
    assert GOOD_CONFIG_BUILTINS.count(GUARD) == 2, "F54 anchor missing"
    session_arm(
        "F54-session-no-active-guard-dropped-C18",
        GOOD_CONFIG_BUILTINS.replace(GUARD, "", 1), GOOD_VM_STORE, "c18")
    # Tree-level wiring: the baseline shape written to disk (proves the locate +
    # read path, not just the checker function).
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, config_text=baseline)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c18"] and only(detail, "c18")
    results.append(("F55-session-baseline-tree-wiring-C18", ok, detail))
    shutil.rmtree(root)

    # --- [dv25-A06] C19: the READ side of the same two fields -------------
    def read_arm(label, cfg_text, want_key):
        v19, vfc = check_session_read_through(cfg_text)
        got = {"c19": v19, "fail_closed": vfc}
        if want_key is None:
            clean = not any(got.values())
            results.append((label, clean, {} if clean else got))
            return
        ok = bool(got[want_key]) and not any(got[k] for k in got if k != want_key)
        results.append((label, ok, got))

    read_arm("F56-session-read-through-green-C19", GOOD_CONFIG_BUILTINS, None)

    THINK_VM_DECOMP = """                        val vm = residentViewModel(sid)
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        when (
                            thinkingLevelReadSource("""
    THINK_VM_NULL = """                        val vm: ChatViewModel? = null
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        when (
                            thinkingLevelReadSource("""
    assert GOOD_CONFIG_BUILTINS.count(THINK_VM_DECOMP) == 1, "F57 anchor missing"
    read_arm(
        "F57-session-think-reader-row-only-C19",
        GOOD_CONFIG_BUILTINS.replace(THINK_VM_DECOMP, THINK_VM_NULL), "c19")

    THINK_ROW_READ = """                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        when (
                            thinkingLevelReadSource("""
    THINK_NO_ROW = """                        val session: SessionRow? = null
                        when (
                            thinkingLevelReadSource("""
    assert GOOD_CONFIG_BUILTINS.count(THINK_ROW_READ) == 1, "F58 anchor missing"
    read_arm(
        "F58-session-think-reader-no-row-fallback-C19",
        GOOD_CONFIG_BUILTINS.replace(THINK_ROW_READ, THINK_NO_ROW), "c19")

    BIND_ARM = """                            SessionConfigReadSource.ResidentViewModel ->
                                residentBindingToken(vm!!)"""
    BIND_ARM_CONST = '                            SessionConfigReadSource.ResidentViewModel -> ""'
    assert GOOD_CONFIG_BUILTINS.count(BIND_ARM) == 1, "F59 anchor missing"
    read_arm(
        "F59-session-binding-reader-constant-arm-C19",
        GOOD_CONFIG_BUILTINS.replace(BIND_ARM, BIND_ARM_CONST), "c19")

    baseline_readers = (
        GOOD_CONFIG_BUILTINS.replace(THINK_VM_DECOMP, THINK_VM_NULL)
        .replace(BIND_ARM, BIND_ARM_CONST)
    )
    read_arm("F60-session-baseline-room-only-readers-C19", baseline_readers, "c19")

    # --- [wide-1024] witnesses handed over by the wide-sweep audit ---------
    # F61 — C4: `false && <guard>` keeps every token the old criterion looked
    # for (`isCustomBaseURLWriteAllowed` + `throw ConfigError`) and writes
    # anyway. Blind before this batch (rc=0 on exactly this patch).
    witness_coll = new_coll.replace(
        "if (!isCustomBaseURLWriteAllowed(inst.providerType, newBase)) {",
        "if (false && !isCustomBaseURLWriteAllowed(inst.providerType, newBase)) {")
    assert witness_coll != new_coll, "F61 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, witness_coll)
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c4"] and only(detail, "c4")
    results.append(("F61-collection-guard-short-circuited-by-false-C4", ok, detail))
    shutil.rmtree(root)

    # F62 — C4: the same guard laundered through a local alias (`val ok = …`)
    # is a legitimate refactor and must stay GREEN (acceptance, not teeth).
    alias_coll = new_coll.replace(
        "if (!isCustomBaseURLWriteAllowed(inst.providerType, newBase)) {",
        "val ok = isCustomBaseURLWriteAllowed(inst.providerType, newBase)\n"
        "                if (!ok) {")
    assert alias_coll != new_coll, "F62 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, alias_coll)
    rc, detail = check_tree(root)
    results.append(("F62-collection-guard-via-local-alias-green", rc == 0, detail))
    shutil.rmtree(root)

    # F63 — C19: a parenthesised constant satisfies `"(" in arm`; the old
    # criterion read that as "this arm calls something, so it reads the VM".
    THINK_ARM = """                            SessionConfigReadSource.ResidentViewModel ->
                                vm!!.requestedThinkingLevel.value"""
    THINK_ARM_PAREN = """                            SessionConfigReadSource.ResidentViewModel ->
                                (ThinkingLevel.OFF)"""
    assert GOOD_CONFIG_BUILTINS.count(THINK_ARM) == 1, "F63 anchor missing"
    read_arm(
        "F63-session-think-reader-parenthesised-constant-C19",
        GOOD_CONFIG_BUILTINS.replace(THINK_ARM, THINK_ARM_PAREN), "c19")

    # F64/F65 — C6: the parse parked in an unused local while the stored value
    # is a literal. Both faces.
    AZ_CREATE = "        val azureMode = params.optBoolean(\"azureMode\", false)\n"
    AZ_CREATE_PARKED = (
        "        val _parsedAzure = params.optBoolean(\"azureMode\", false)\n"
        "        val azureMode = false\n")
    assert GOOD_MUTATION.count(AZ_CREATE) == 1, "F64 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION.replace(AZ_CREATE, AZ_CREATE_PARKED))
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c6"] and only(detail, "c6")
    results.append(("F64-create-parks-parse-then-constant-C6", ok, detail))
    shutil.rmtree(root)

    AZ_UPDATE = ('        val azureMode = if (params.has("azureMode")) '
                 'params.optBoolean("azureMode", current.azureMode) else current.azureMode\n')
    AZ_UPDATE_PARKED = (
        '        val _parsedAzure = params.optBoolean("azureMode", current.azureMode)\n'
        "        val azureMode = current.azureMode\n")
    assert GOOD_MUTATION.count(AZ_UPDATE) == 1, "F65 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION.replace(AZ_UPDATE, AZ_UPDATE_PARKED))
    rc, detail = check_tree(root)
    ok = rc == 1 and detail["c6"] and only(detail, "c6")
    results.append(("F65-update-parks-parse-then-constant-C6", ok, detail))
    shutil.rmtree(root)

    # F66 — C6: reading the parse through one local alias is legitimate
    # (acceptance arm: the dataflow criterion must follow the value, not the
    # spelling. Delegating the parse to a helper is NOT accepted — C6/C14 pin
    # the key literal on each write face by design).
    assisted = GOOD_MUTATION.replace(
        AZ_CREATE,
        "        val rawAzure = params.optBoolean(\"azureMode\", false)\n"
        "        val azureMode = rawAzure\n")
    root = write_fixture(GOOD_DEBUG, assisted)
    rc, detail = check_tree(root)
    results.append(("F66-create-azureMode-via-local-alias-green", rc == 0, detail))
    shutil.rmtree(root)

    shutil.rmtree(root) if os.path.isdir(root) else None

    # ------------------------------------------------------------------
    # [batch6 B6-t02] six witnesses that used to leave this gate at rc=0.
    # Each one is the *narrowest* edit that re-introduces the defect the
    # criterion claims to stop, so a green here means the arm is blind again.
    # ------------------------------------------------------------------

    # A07-1 (family-hole): C4 guard spelled `!true && !guard(…)` — the
    # condition can never be true, so the throw is dead and the write runs.
    b6_col = GOOD_COLLECTION.replace(
        "                if (!inst.providerType.supportsCustomBaseURL) {",
        "                if (!true && !inst.providerType.supportsCustomBaseURL) {",
    )
    assert b6_col != GOOD_COLLECTION, "B6/A07-1 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, b6_col)
    rc, detail = check_tree(root)
    results.append((
        "F67-collection-guard-negated-true-C4",
        rc == 1 and detail["c4"] and only(detail, "c4")
        and "folds to false" in " ".join(detail["c4"]),
        detail,
    ))
    shutil.rmtree(root)

    # A07-2 (dataflow): C6 `azureMode` initialiser folded with `&& false` —
    # the parse is still spelled inside the expression, the stored value is a
    # constant.
    b6_mut = GOOD_MUTATION.replace(
        'val azureMode = params.optBoolean("azureMode", false)',
        'val azureMode = params.optBoolean("azureMode", false) && false',
    )
    assert b6_mut != GOOD_MUTATION, "B6/A07-2 anchor missing"
    root = write_fixture(GOOD_DEBUG, b6_mut)
    rc, detail = check_tree(root)
    results.append((
        "F68-create-azureMode-folded-constant-C6",
        rc == 1 and detail["c6"] and only(detail, "c6"),
        detail,
    ))
    shutil.rmtree(root)

    # A07-3 (dataflow): C19 resident arm answers a constant on both paths —
    # `vm != null` is a mention of the handle, not a read of it.
    b6_cfg = GOOD_CONFIG_BUILTINS.replace(
        '                            SessionConfigReadSource.ResidentViewModel ->\n'
        '                                vm!!.requestedThinkingLevel.value',
        '                            SessionConfigReadSource.ResidentViewModel ->\n'
        '                                if (vm != null) "off" else "off"',
    )
    assert b6_cfg != GOOD_CONFIG_BUILTINS, "B6/A07-3 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, config_text=b6_cfg)
    rc, detail = check_tree(root)
    results.append((
        "F69-session-think-reader-null-test-constant-C19",
        rc == 1 and detail["c19"] and only(detail, "c19"),
        detail,
    ))
    shutil.rmtree(root)

    # A07-4 (family-hole): C18 refresh call parked behind `if (false)` — the
    # call is spelled inside the writer and never runs.
    b6_cfg = GOOD_CONFIG_BUILTINS.replace(
        "refresh = { vm -> vm.setThinkingLevel(v) },",
        "refresh = { vm -> if (false) vm.setThinkingLevel(v) },",
    )
    assert b6_cfg != GOOD_CONFIG_BUILTINS, "B6/A07-4 anchor missing"
    root = write_fixture(GOOD_DEBUG, GOOD_MUTATION, config_text=b6_cfg)
    rc, detail = check_tree(root)
    results.append((
        "F70-session-thinking-refresh-dead-branch-C18",
        rc == 1 and detail["c18"] and only(detail, "c18"),
        detail,
    ))
    shutil.rmtree(root)

    # A07-5 (family-hole): C12 canonical write face parked behind `if (false)`.
    b6_mut = GOOD_MUTATION.replace(
        "mutatedGroupCopy(current) { applyContextLimitWrite(it, limitValue) }",
        "mutatedGroupCopy(current) { if (false) applyContextLimitWrite(it, limitValue) }",
    )
    assert b6_mut != GOOD_MUTATION, "B6/A07-5 anchor missing"
    root = write_fixture(GOOD_DEBUG, b6_mut)
    rc, detail = check_tree(root)
    results.append((
        "F71-group-update-canonical-face-dead-branch-C12",
        rc == 1 and detail["c12"] and only(detail, "c12"),
        detail,
    ))
    shutil.rmtree(root)

    # A07-6 (scope): C10 — the probe takes `azureMode` and delegates with
    # `azure = false`: one body (C10's literal arm is happy), one caller-facing
    # flag that is thrown away (the fork is re-decided behind the value flow).
    b6_mut = GOOD_MUTATION.replace(
        """internal fun probeAuthHeader(azureMode: Boolean, key: String): Pair<String, String> =
    openAIKeyAuthHeader(azure = azureMode, token = key)""",
        """internal fun probeAuthHeader(azureMode: Boolean, key: String): Pair<String, String> =
    openAIKeyAuthHeader(azure = false, token = key)""",
    )
    assert b6_mut != GOOD_MUTATION, "B6/A07-6 anchor missing"
    root = write_fixture(GOOD_DEBUG, b6_mut)
    rc, detail = check_tree(root)
    results.append((
        "F72-probe-ignores-azure-parameter-C10",
        rc == 1 and detail["c10"] and only(detail, "c10")
        and "ignores its" in " ".join(detail["c10"]),
        detail,
    ))
    shutil.rmtree(root)

    # [dv24-FIX-4 R8] Two branches read a variable that only their `len(...) == 1`
    # success path bound (`cfg_code` for C8/C11, `groups_code`/`rules_code` for
    # C13): deleting the file raised UnboundLocalError, so the gate CRASHED
    # instead of failing closed. A traceback is not a verdict — CI would report a
    # broken scanner rather than the missing anchor. The arms remove one input
    # file each and assert both halves of the fix: rc=1 carrying the fail-closed
    # reason, and no exception escaping. Per-file arms (not one aggregate) so a
    # regression names the file it broke.
    # `needle` proves the code got PAST the branch that used to raise.
    for victim, needle in (
            (DEBUG_FILE, None),
            (MUTATION_FILE, None),
            (CONFIG_BUILTINS_FILE, "anchor %s not found" % GROUPS_PATH_MARKER),
            (CONNECTION_SCREEN_FILE, None),
            (PROVIDERS_COLLECTION_FILE, None),
            (GROUPS_COLLECTION_FILE, "requireKnownEntryIds not found"),
            (GROUP_RULES_FILE, "requireKnownEntryIds not found"),
            (AUTH_HEADER_FILE, None),
            (VM_STORE_FILE, None),
    ):
        root = write_fixture(GOOD_DEBUG, GOOD_MUTATION)
        victims = [os.path.join(dirpath, victim) for dirpath, _d, files in os.walk(root)
                   if victim in files]
        for path in victims:
            os.remove(path)
        crashed = None
        detail = {"fail_closed": []}
        try:
            rc, detail = check_tree(root)
        except Exception as exc:                                  # noqa: BLE001
            crashed = "%s: %s" % (type(exc).__name__, exc)
        fc = " || ".join(detail["fail_closed"])
        ok = (victims
              and crashed is None
              and rc == 1
              and ("expected exactly one" in fc and victim in fc)
              and (needle is None or needle in fc))
        results.append((
            "F73-missing-%s-fails-closed-not-crash" % victim.replace(".kt", "").replace("_", "-").lower(),
            ok,
            {"victim": victim, "removed": len(victims), "crashed": crashed,
             "rc": None if crashed else rc, "fail_closed": fc[:400]},
        ))
        shutil.rmtree(root)

    all_ok = True
    for name, ok, detail in results:
        print("%s %s" % ("PASS" if ok else "FAIL", name))
        if not ok:
            all_ok = False
            print(json.dumps(detail, indent=2))
    if all_ok:
        print(SELFTEST_MARKER)
        # [batch6 B6-t02] arm count is printed separately so the legacy marker
        # string (asserted in scripts/scan/test_scan.py) stays byte-identical.
        print("PROVIDER_RPC_SYMMETRY_SELFTEST_ARMS=%d" % len(results))
        return 0
    return 1


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--selftest":
        return run_selftest()
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    root = sys.argv[1]
    if not os.path.isdir(root):
        print("repo root not a directory: %s" % root)
        return 2
    rc, detail = check_tree(root)
    if rc == 0:
        print("provider_rpc_symmetry: ALL GREEN (C1 predicate single-source, "
              "C2 update guard, C3 key symmetry, C4 collection guard, "
              "C5 mutation predicate floor, C6 azureMode four faces, "
              "C7 probe auth fork, C8 config-face keys, C9 UA visibility, "
              "C10 auth single source, C11 group field faces, "
              "C12 context-limit canonical face, C13 entry-id check single-source, "
              "C14 instance key parity, C15 collection image keys, "
              "C16 group member predicate single-source, C17 no sentinel literal, "
              "C18 session config write-through to the resident view model, "
              "C19 session config read-through from the resident view model)")
        return 0
    for k in ("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10", "c11",
              "c12", "c13", "c14", "c15", "c16", "c17", "c18", "c19", "fail_closed"):
        for line in detail[k]:
            print("[%s] %s" % (k.upper(), line))
    return 1


if __name__ == "__main__":
    sys.exit(main())
