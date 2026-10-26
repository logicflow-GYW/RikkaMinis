#!/usr/bin/env python3
"""
Gate: OpenMinis .minisbak conversion field parity
       (deep-audit-1022 ISSUE-04, branch fix/da1022-openminis-compat).

The OpenMinis importer (OpenMinisBackupCompat) is a THIRD serializer of the same
backup contract as ProviderRepository.exportInstanceJSON / importInstanceJSON and
ConfigBackup. Adding a field to the Android export silently evaporates for every
cross-platform (.minisbak) restore unless the converter is updated too. This gate
derives the required key sets from the Android exporters and asserts the
converter still writes them — so the NEXT dropped field turns this red instead
of turning up on a device.

Arms
  1  appendV1Suffix polarity: default-TRUE on both sides (the RikkaMinis importer
     reads it with optBoolean("appendV1Suffix", true); the Android export writes
     the key only when false). The 3-state passthrough (has-guarded write of the
     explicit value) is required; the regression shape — a default-FALSE read
     feeding a write-when-true — is forbidden. A default-false read is correct
     for useResponsesAPI (whose import default IS false) and was copy-pasted onto
     this opposite-polarity field, inverting explicit `false` into `true` (every
     request then gained a spurious /v1 suffix). The write must also be
     REACHABLE (a guard short-circuited by a constant writes nothing) and must
     not be guarded by the field's own value.
  2  provider/model key parity: every key exportInstanceJSON writes must appear
     in convertProviders (exemptions listed with reasons below), and every
     run-config constant the repository defines must still be WRITTEN by
     writeRunConfigFields (a dead/`if (false)` write drops the key silently —
     the exporter keeps defining it, so key-set parity alone cannot see it).
  3  chat-session key parity: every key ConfigBackup's session export block writes
     must appear in convertChats (exemptions listed with reasons below).
  4  [deep-audit-1039 A-07] model-group key parity: every key ConfigBackup's
     modelGroups export block writes must appear in convertGroups. The group
     run-config fields (strategy / fallbackStrategy / defaultThinkingLevel /
     contextLimitTokens / lastContextLimitTokens) had a decode-side fix
     (readGroupRunConfig / applyGroupRunConfig, [fix/dv18-4 A-07]) with no
     export-side guard: a group key dropped from buildSections would silently
     stop reaching the importer that now knows how to read it.

What counts as a WRITE (deep-audit-1024 B 12-1..12-6)
  A `put("<key>", …)` call is only a write when it can actually carry the value:
    * it sits on a REACHABLE path (`path_feasible`; `if (false && has(k))` hides
      it from every literal-presence criterion);
    * its receiver is a NAMED object (`obj.put`), not a throwaway
      (`JSONObject().put(…)` writes into garbage);
    * its value is not a bare constant — unless the guarding condition READS the
      key's value with a default (`if (optBoolean("k", false)) put("k", true)`),
      which is a legitimate polarity write. `!true`, `1 == 2` and friends fold to
      a constant, so the old literal whitelist cannot smuggle one past;
    * it is not gated by a predicate that can never hold for the field's domain
      (`takeIf { it < 0 }` on a timestamp drops every real value — the integer
      domain is modelled as non-negative);
    * the same function does not `remove("<key>")` afterwards.

Implementation note (fail-closed): all structural reads go through the shared
lexer (`_kotlin_lexer.analyse`, so nested block comments / raw strings /
char literals are masked by the one implementation that has tests) plus the
shared AST/value-flow primitives. Key literals are read from the literal view
and positions are shared with the code view, which is why a commented-out write
(`/* obj.put("azureMode", …) */`) now disappears instead of counting.

Usage:
    python3 scripts/scan/openminis_compat_field_parity_check.py <repo_root>
    python3 scripts/scan/openminis_compat_field_parity_check.py --root <repo_root>
    python3 scripts/scan/openminis_compat_field_parity_check.py --selftest
"""

import argparse
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_ast import const_value, match_bracket, strip_parens
from _kotlin_lexer import KotlinLexError
from _kotlin_lexer import analyse as lex_kotlin
from _kotlin_valueflow import expr_end, guard_feasible, path_feasible

APP = "src/android/app/src/main/java/com/rikkaminis/app"
BM_REL = APP + "/backup/OpenMinisBackupCompat.kt"
PR_REL = APP + "/data/repository/ProviderRepository.kt"
CB_REL = APP + "/backup/ConfigBackup.kt"

CONV_HEADER = "private fun convertProviders("
GROUP_HEADER = "private fun convertGroups("
CHAT_HEADER = "private fun convertChats("
EXPORT_HEADER = "fun exportInstanceJSON("
SESSIONS_ANCHOR = "val sessionJsons = sessions.map"
# [deep-audit-1039 A-07] The provider run-config keys moved behind the pure
# JVM-testable seam writeRunConfigFields (RC5 / P0-pinned + GH#68), where they
# are written as `put(RCF_*, …)` — identifier form, invisible to PUT_KEY.
RCF_HEADER = "fun writeRunConfigFields("
RCF_CONST_RE = re.compile(r'(?:private\s+)?const\s+val\s+(RCF_\w+)\s*=\s*"([^"]+)"')
RCF_PUT_RE = re.compile(r"put\(\s*(RCF_\w+)\s*,")
# [deep-audit-1039 A-07] The modelGroups export block lives inside buildSections.
GROUPS_ANCHOR = "for (group in providerRepo.config.value.modelGroups)"

# Arm 1 anchors — code anchors, never comment text.
V1_REQUIRED_GUARD = 'has("appendV1Suffix")'
V1_REQUIRED_WRITE = 'put("appendV1Suffix", inst.optBoolean("appendV1Suffix"))'
V1_FORBIDDEN = re.compile(r'optBoolean\("appendV1Suffix",\s*false\)')

# Arm 2/3 exemptions. Direction is one-way (exporter key ⊆ converter body), so a
# converter-only key (oauth*, credential base64…) is NOT an exemption — it is a
# superset and passes by construction. Anything listed here needs a reason.
PROVIDER_EXEMPT = {
    # The overrides block is copied through wholesale from a key allow-list
    # (`arrayOf("maxThinkingLevel", …)`), so those keys legitimately appear as
    # bare quoted literals rather than per-key put( calls. Presence check below
    # is literal-based precisely so this stays green.
}
SESSION_EXEMPT = {}
GROUP_EXEMPT = {}

PUT_KEY = re.compile(r'put\(\s*"(\w+)"')
# 非负整数域：时间戳 / 计数 / token 上限都落在这里，用来判「谓词是否把整段域排除」。
POSITIVE_DOMAIN = {"it": (1, None)}
# 链式谓词与写入之间只允许出现链式语法（`?.let { ` / `.filter { … }?.` …）。
_CHAIN_GAP = re.compile(r"^[\s?.,{}()]*(?:[A-Za-z_]\w*[\s?.,{}()]*)*$")


def views(src):
    """(code, literal) —— 两个等长视图；解析失败抛 KotlinLexError（fail-closed）。"""
    ok, reason, v = lex_kotlin(src)
    if not ok:
        raise KotlinLexError(reason)
    return v["code"], v["comments"]


def function_slice(code, lit, header):
    """`[header … ]` 所在函数（含体外花括号）的两个视图切片；缺失 → None。"""
    i = code.find(header)
    if i < 0:
        return None
    open_paren = i + len(header) - 1
    close_paren = match_bracket(code, open_paren)
    if close_paren < 0:
        return None
    brace = code.find("{", close_paren)
    if brace < 0:
        return None
    end = match_bracket(code, brace)
    if end < 0:
        return None
    return code[i:end + 1], lit[i:end + 1]


def block_slice(code, lit, anchor):
    """[anchor] 之后紧跟的花括号块（含 anchor 前缀）的两个视图切片。"""
    i = code.find(anchor)
    if i < 0:
        return None
    brace = code.find("{", i)
    if brace < 0:
        return None
    end = match_bracket(code, brace)
    if end < 0:
        return None
    return code[i:end + 1], lit[i:end + 1]


def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def receiver_ident(code, pos):
    """`put(` 前面的接收者标识符；临时对象（`JSONObject()`、链式结果）→ ""。"""
    i = pos - 1
    while i >= 0 and code[i] in " \t\r\n":
        i -= 1
    if i < 0 or code[i] != ".":
        return ""
    i -= 1
    while i >= 0 and code[i] in " \t\r\n":
        i -= 1
    if i < 0 or code[i] in ")]}":
        return ""
    j = i
    while j >= 0 and (code[j].isalnum() or code[j] == "_"):
        j -= 1
    name = code[j + 1:i + 1]
    return name if (name and not name[0].isdigit()) else ""


def value_expr(lit, code, pos):
    """写入值表达式（结构边界取自 code 视图，内容取自 lit 视图）。"""
    return lit[pos:expr_end(code, pos)].strip()


def guard_condition_span(code, pos):
    """守护 pos 的 `if (…)` 条件区间（两种形态：同语句前缀 / 外层块头）。"""
    depth = 0
    i = pos - 1
    limit = max(0, pos - 400)
    while i >= limit:
        c = code[i]
        if c in ")]":
            depth += 1
        elif c in "([":
            depth -= 1
        elif depth == 0 and c in ";\n{}":
            break
        i -= 1
    prefix_start = i + 1
    prefix = code[prefix_start:pos]
    last = None
    for m in re.finditer(r"if\s*\(", prefix):
        o = m.end() - 1
        c = match_bracket(prefix, o)
        if c < 0:
            continue
        last = (o + 1, c)
    if last:
        return (prefix_start + last[0], prefix_start + last[1])
    depth = 0
    i = pos - 1
    while i >= 0 and pos - i < 800:
        c = code[i]
        if c in "}]":
            depth += 1
        elif c in "[{":
            if depth > 0:
                depth -= 1
            else:
                hdr_start = max(0, i - 300)
                hdr = code[hdr_start:i]
                found = None
                for m in re.finditer(r"if\s*\(", hdr):
                    o = m.end() - 1
                    c2 = match_bracket(hdr, o)
                    if c2 < 0:
                        continue
                    if hdr[c2 + 1:].strip() == "":
                        found = (o + 1, c2)
                if found:
                    return (hdr_start + found[0], hdr_start + found[1])
                return None
        i -= 1
    return None


def guard_reads_key_value(code, lit, pos, key):
    """守卫条件是否读了这个键的**值**（不是只 `has(key)`）。

    `if (optBoolean("useResponsesAPI", false)) put("useResponsesAPI", true)`
    是正当的极性写入；`if (has("pinned")) put("pinned", true)` 不是 —— 后者把
    所有实例都钉成同一个常量。
    """
    span = guard_condition_span(code, pos)
    if span is None:
        return False
    cond = lit[span[0]:span[1]]
    return re.search(r'(?:opt|get)Boolean\s*\(\s*"%s"' % re.escape(key), cond) is not None


def chain_predicate_dead(code, pos):
    """写入是否挂在一条「对非负整数域永不成立」的 takeIf/filter 谓词后面。"""
    start = max(0, pos - 400)
    win = code[start:pos]
    for m in re.finditer(r"\b(takeIf|takeUnless|filter|filterNot)\s*\{", win):
        o = start + m.end() - 1
        c = match_bracket(code, o)
        if c < 0 or c >= pos:
            continue
        if not _CHAIN_GAP.match(code[c + 1:pos]):
            continue
        pred = code[o + 1:c].strip()
        if not pred:
            continue
        negate = m.group(1) in ("takeUnless", "filterNot")
        ok = guard_feasible("!(%s)" % pred if negate else pred, POSITIVE_DOMAIN)
        if not ok:
            return True
    return False


def identifier_written_keys(lit):
    """Keys written through a VARIABLE whose value set is a literal allow-list in
    the same body — the wholesale-copy shape
    (`for (k in arrayOf("a","b")) { …put(k, …) }`).

    Without this the write test would be spelling-dependent in the other
    direction: a converter that copies a sub-object field-by-field through one
    loop writes every key correctly and never spells `put("maxThinkingLevel"`.
    """
    out = set()
    for m in re.finditer(r"put\(\s*([A-Za-z_]\w*)\s*,", lit):
        var = m.group(1)
        for lm in re.finditer(
                r"for\s*\(\s*%s\s+in\s+(?:arrayOf|listOf|setOf)\s*\(([^)]*)\)"
                % re.escape(var), lit):
            out |= set(re.findall(r'"([A-Za-z_]\w*)"', lm.group(1)))
    return out


def missing_writes(required, pair):
    """Keys the converter READS but never really WRITES — the field-evap shape.

    A key is gone when EVERY live write site for it is ineffective (dead branch,
    temporary receiver, unrelated constant value, unsatisfiable predicate, or the
    key is removed afterwards). One effective write keeps the key alive.
    """
    code, lit = pair
    gone = []
    ident_written = identifier_written_keys(lit)
    for k in sorted(required):
        sites = [m for m in re.finditer(r'put\(\s*"%s"\s*,' % re.escape(k), lit)]
        if not sites:
            if k in ident_written:
                continue
            gone.append(k)
            continue
        live = [m for m in sites if path_feasible(code, m.start())]
        if not live:
            gone.append(k + " (dead write)")
            continue
        bad = []
        for m in live:
            val = value_expr(lit, code, m.end())
            if re.search(r'remove\(\s*"%s"\s*\)' % re.escape(k), lit):
                bad.append("removed after the write")
            elif not receiver_ident(code, m.start()):
                bad.append("written into a temporary receiver")
            elif (const_value(strip_parens(val)) is not None
                  and not guard_reads_key_value(code, lit, m.start(), k)):
                bad.append("constant value %r (nothing to do with the source)"
                           % val[:40])
            elif chain_predicate_dead(code, m.start()):
                bad.append("gated by a predicate that never holds for the field")
        if len(bad) == len(live):
            gone.append("%s (%s)" % (k, bad[0]))
    return gone


def rcf_const_table(pr_src):
    """{RCF_<NAME>: "wireKey"} derived from ProviderRepository's own constants —
    never a hardcoded copy (the point of the arm is that a SIXTH run-config
    constant is covered the day it lands)."""
    return {name: key for name, key in RCF_CONST_RE.findall(pr_src)}


def write_run_config_keys(rcf, pr_lit):
    """(keys, dead_constants) for writeRunConfigFields.

    keys  = literal puts + RCF_* puts that are on a REACHABLE path
    dead  = RCF_* constants the repository defines but the seam never writes
            (guarded by `if (false)` / deleted outright) — the exporter keeps
            defining the key so key-set parity alone stays green, which is
            exactly how a pinned flag can vanish from every cross-platform
            restore.
    Raises KeyError on an identifier the table cannot resolve; the caller turns
    that into a fail-closed ROT.
    """
    if rcf is None:
        return set(), []
    code, lit = rcf
    table = rcf_const_table(pr_lit)
    keys, live_idents = set(), set()
    for m in RCF_PUT_RE.finditer(lit):
        ident = m.group(1)
        if not path_feasible(code, m.start()):
            continue
        if ident not in table:
            raise KeyError(ident)
        live_idents.add(ident)
        keys.add(table[ident])
    for m in PUT_KEY.finditer(lit):
        if path_feasible(code, m.start()):
            keys.add(m.group(1))
    return keys, sorted(set(table) - live_idents)


def run(root):
    """Returns (problems, checked_count)."""
    problems = []
    bm = read(BM_REL, root)
    pr = read(PR_REL, root)
    cb = read(CB_REL, root)
    if bm is None or pr is None or cb is None:
        return ["ROT: source file missing (bm=%s pr=%s cb=%s)"
                % (bm is not None, pr is not None, cb is not None)], 0
    try:
        bm_code, bm_lit = views(bm)
        pr_code, pr_lit = views(pr)
        cb_code, cb_lit = views(cb)
    except KotlinLexError as exc:
        return ["ROT: Kotlin source is not lexable (%s) — fail-closed"
                % exc], 0

    conv = function_slice(bm_code, bm_lit, CONV_HEADER)
    groups_conv = function_slice(bm_code, bm_lit, GROUP_HEADER)
    chat = function_slice(bm_code, bm_lit, CHAT_HEADER)
    exp = function_slice(pr_code, pr_lit, EXPORT_HEADER)
    rcf = function_slice(pr_code, pr_lit, RCF_HEADER)
    sess = block_slice(cb_code, cb_lit, SESSIONS_ANCHOR)
    grp = block_slice(cb_code, cb_lit, GROUPS_ANCHOR)
    for name, txt in (("convertProviders", conv), ("convertGroups", groups_conv),
                      ("convertChats", chat), ("exportInstanceJSON", exp),
                      ("sessionJsons", sess), ("modelGroups block", grp)):
        if txt is None:
            problems.append("ROT: anchor not found for %s" % name)
    if problems:
        return problems, 0

    conv_code, conv_lit = conv
    exp_lit = exp[1]

    # Arm 1 — appendV1Suffix three-state polarity.
    if V1_REQUIRED_GUARD not in conv_lit:
        problems.append(
            "arm1: convertProviders lost the 3-state guard %s"
            % V1_REQUIRED_GUARD)
    if V1_REQUIRED_WRITE not in conv_lit:
        problems.append(
            "arm1: convertProviders lost the passthrough write %s"
            % V1_REQUIRED_WRITE)
    if V1_FORBIDDEN.search(conv_lit):
        problems.append(
            "arm1: default-false read on a default-true field "
            "(optBoolean(\"appendV1Suffix\", false)) — explicit false would be "
            "dropped and re-imported as true")
    # [1024-B 12-1] The passthrough must be REACHABLE: `if (false && has(k))`
    # keeps the guard text, the write text and the default-true polarity while
    # writing the key for no instance at all.
    v1_sites = [m for m in re.finditer(r'put\(\s*"appendV1Suffix"\s*,', conv_lit)]
    v1_live = [m for m in v1_sites if path_feasible(conv_code, m.start())]
    if v1_sites and not v1_live:
        problems.append(
            "arm1: the appendV1Suffix passthrough sits in an unreachable branch "
            "(guard short-circuited by a constant) — the key is written for no "
            "instance, so every restore re-imports the default")
    # [wide-1024 12-misc-b] The 3-state passthrough also has to be UNCONDITIONAL
    # on the value: `if (has(k) && optBoolean(k)) { put(k, optBoolean(k)) }` keeps
    # the guard, the write and the round-trip, and skips the write for an explicit
    # `false` — the importer's default-true then flips it back to true. Judge the
    # guarding condition, not the spelling of the call.
    for m in v1_live:
        span = guard_condition_span(conv_code, m.start())
        if span is None:
            continue
        if re.search(r'optBoolean\s*\(\s*"appendV1Suffix"',
                     conv_lit[span[0]:span[1]]):
            problems.append(
                "arm1: the passthrough write is guarded by the VALUE "
                "(optBoolean(\"appendV1Suffix\") in the `if`) — an explicit false is "
                "skipped, the key goes missing and the importer re-imports it as true")
            break

    # Arm 2 — provider/model key parity (derived from the Android export).
    # [deep-audit-1039 A-07] exportInstanceJSON delegates the run-config keys to
    # writeRunConfigFields, so the derived set is literal puts ∪ delegated puts
    # (RCF_* identifiers mapped through the constants table). Fail-closed: a
    # delegation that cannot be resolved is reported, never silently skipped.
    try:
        run_config_keys, dead_rcf = write_run_config_keys(rcf, pr_lit)
    except KeyError as exc:
        problems.append(
            "ROT: exportInstanceJSON's run-config delegation is unreadable (%s) "
            "— the five RCF_* keys would silently drop out of arm2" % exc)
        run_config_keys, dead_rcf = set(), []
    if dead_rcf:
        problems.append(
            "arm2: run-config key(s) defined but never written by "
            "writeRunConfigFields (dead/guarded write or deleted put): %s — the "
            "export keeps the constant, the converter keeps writing it for old "
            "backups, and the key silently stops being exported"
            % ", ".join(dead_rcf))
    if "writeRunConfigFields(" in exp_lit and not run_config_keys:
        problems.append(
            "ROT: exportInstanceJSON delegates to writeRunConfigFields but no "
            "run-config keys could be derived from it — arm2 lost its blind spot")
    if "writeRunConfigFields(" in pr_lit and "writeRunConfigFields(" not in exp_lit:
        problems.append(
            "arm2: exportInstanceJSON no longer calls writeRunConfigFields — the "
            "five run-config keys would stop being exported (converter keeps "
            "them for old backups, so parity alone cannot see the drop)")
    provider_keys = (set(PUT_KEY.findall(exp_lit)) | run_config_keys) - set(PROVIDER_EXEMPT)
    gone = missing_writes(provider_keys, conv)
    if gone:
        problems.append("arm2: convertProviders dropped export key(s): %s"
                        % ", ".join(gone))

    # Arm 3 — chat-session key parity (derived from ConfigBackup's export block).
    session_keys = set(PUT_KEY.findall(sess[1])) - set(SESSION_EXEMPT)
    gone_s = missing_writes(session_keys, chat)
    if gone_s:
        problems.append("arm3: convertChats dropped session export key(s): %s"
                        % ", ".join(gone_s))

    # Arm 4 [deep-audit-1039 A-07] — model-group key parity. Exported from
    # ConfigBackup's modelGroups block; the OpenMinis side reads the same
    # provider_config.json shape, so the two key sets must agree.
    group_keys = set(PUT_KEY.findall(grp[1])) - set(GROUP_EXEMPT)
    gone_g = missing_writes(group_keys, groups_conv)
    if gone_g:
        problems.append("arm4: convertGroups dropped group export key(s): %s"
                        % ", ".join(gone_g))

    checked = len(provider_keys) + len(session_keys) + len(group_keys)
    if checked == 0:
        problems.append("ROT: derived 0 keys — the extraction anchors went stale")
    return problems, checked

FIX_EXPORT = """fun exportInstanceJSON(instance: ProviderInstance): JSONObject {
    return JSONObject().apply {
        put("providerType", instance.providerType.slug)
        put("label", instance.label)
        put("appendV1Suffix", false)
        put("customBaseURL", instance.customBaseURL)
        put("models", modelsArr)
        put("costTier", 1)
    }
}
"""

# [deep-audit-1039 A-07] Export shape that delegates the run-config keys the way
# the real tree does (literal puts are invisible; the RCF_* identifiers carry
# the keys). The derived-set arm must see all five.
FIX_EXPORT_DELEGATING = """private const val RCF_IS_ENABLED = "isEnabled"
private const val RCF_AZURE_MODE = "azureMode"
private const val RCF_IMG_MODE = "imageEndpointMode"
private const val RCF_IMG_RESOLVED = "imageEndpointResolved"
private const val RCF_PINNED = "pinned"

internal fun writeRunConfigFields(obj: org.json.JSONObject, instance: ProviderInstance) {
    obj.put(RCF_IS_ENABLED, instance.isEnabled)
    obj.put(RCF_AZURE_MODE, instance.azureMode)
    obj.put(RCF_IMG_MODE, instance.imageEndpointMode.name)
    instance.imageEndpointResolved?.name?.let { obj.put(RCF_IMG_RESOLVED, it) }
    obj.put(RCF_PINNED, instance.pinned)
}

fun exportInstanceJSON(instance: ProviderInstance): JSONObject {
    return JSONObject().apply {
        put("providerType", instance.providerType.slug)
        put("label", instance.label)
        writeRunConfigFields(this, instance)
    }
}
"""

# Same, but with the delegation dropped — the pre-fix blind shape: literal
# extraction finds only the two keys, the five run-config keys vanish.
FIX_EXPORT_NO_DELEGATION = """private const val RCF_IS_ENABLED = "isEnabled"
private const val RCF_AZURE_MODE = "azureMode"
private const val RCF_IMG_MODE = "imageEndpointMode"
private const val RCF_IMG_RESOLVED = "imageEndpointResolved"
private const val RCF_PINNED = "pinned"

internal fun writeRunConfigFields(obj: org.json.JSONObject, instance: ProviderInstance) {
    obj.put(RCF_IS_ENABLED, instance.isEnabled)
    obj.put(RCF_AZURE_MODE, instance.azureMode)
    obj.put(RCF_IMG_MODE, instance.imageEndpointMode.name)
    instance.imageEndpointResolved?.name?.let { obj.put(RCF_IMG_RESOLVED, it) }
    obj.put(RCF_PINNED, instance.pinned)
}

fun exportInstanceJSON(instance: ProviderInstance): JSONObject {
    return JSONObject().apply {
        put("providerType", instance.providerType.slug)
        put("label", instance.label)
    }
}
"""

# A put(IDENT) the constants table cannot resolve — fail-closed, never a skip.
FIX_EXPORT_UNMAPPED_RCF = FIX_EXPORT_DELEGATING.replace(
    "    obj.put(RCF_PINNED, instance.pinned)",
    "    obj.put(RCF_SOMETHING_NEW, instance.pinned)")

FIX_SESSIONS = """fun exportData(): JSONArray {
    val sessionJsons = sessions.map { session ->
        JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("pinnedAt", session.pinnedAt)
            put("editCount", session.editCount)
        }
    }
    return sessionJsons
}
"""

# [deep-audit-1039 A-07] The modelGroups export block inside buildSections.
FIX_GROUPS = """    private suspend fun buildSections(repo: ProviderRepository): JSONObject {
        val groups = JSONArray()
        for (group in providerRepo.config.value.modelGroups) {
            groups.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("memberEntryIds", JSONArray().apply {
                    for (eid in group.memberEntryIds) put(eid)
                })
                put("strategy", group.strategy.name)
                put("fallbackStrategy", group.fallbackStrategy.name)
                group.defaultThinkingLevel?.let { put("defaultThinkingLevel", it.name) }
                group.contextLimitTokens?.let { put("contextLimitTokens", it) }
                group.lastContextLimitTokens?.let { put("lastContextLimitTokens", it) }
            })
        }
        return JSONObject().put("groups", groups)
    }
"""

FIX_GROUPS_DROPPED = FIX_GROUPS.replace(
    '                put("fallbackStrategy", group.fallbackStrategy.name)\n', "")

FIX_CONV_OK = """private fun convertProviders(pc: JSONObject, secrets: JSONObject): JSONArray {
    val obj = JSONObject()
    obj.put("providerType", inst.optString("providerType", ""))
    obj.put("label", inst.optString("label", ""))
    if (inst.has("appendV1Suffix")) {
        obj.put("appendV1Suffix", inst.optBoolean("appendV1Suffix"))
    }
    if (inst.has("customBaseURL")) {
        obj.put("customBaseURL", inst.optString("customBaseURL"))
    }
    obj.put("isEnabled", inst.optBoolean("isEnabled", true))
    obj.put("azureMode", inst.optBoolean("azureMode", false))
    obj.put("imageEndpointMode", inst.optString("imageEndpointMode", "auto"))
    if (inst.has("imageEndpointResolved")) {
        obj.put("imageEndpointResolved", inst.optString("imageEndpointResolved"))
    }
    obj.put("pinned", inst.optBoolean("pinned", false))
    val mo = JSONObject()
    mo.put("displayName", model.optString("displayName", ""))
    if (e.has("costTier") && !e.isNull("costTier")) {
        mo.put("costTier", e.optInt("costTier"))
    }
    obj.put("models", modelsArr)
    return out
}
"""

# Delegated-key variant: identical coverage, so the arm is agnostic to HOW the
# converter spells its writes (decision, not spelling).
FIX_CONV_OK_DELEGATED = FIX_CONV_OK.replace(
    '    obj.put("isEnabled", inst.optBoolean("isEnabled", true))\n'
    '    obj.put("azureMode", inst.optBoolean("azureMode", false))\n'
    '    obj.put("imageEndpointMode", inst.optString("imageEndpointMode", "auto"))\n'
    '    if (inst.has("imageEndpointResolved")) {\n'
    '        obj.put("imageEndpointResolved", inst.optString("imageEndpointResolved"))\n'
    '    }\n'
    '    obj.put("pinned", inst.optBoolean("pinned", false))\n',
    '    if (inst.has("isEnabled")) obj.put("isEnabled", inst.optBoolean("isEnabled"))\n'
    '    obj.put("azureMode", inst.optBoolean("azureMode", false))\n'
    '    obj.put("imageEndpointMode", inst.optString("imageEndpointMode", "auto"))\n'
    '    obj.put("imageEndpointResolved", inst.optString("imageEndpointResolved"))\n'
    '    obj.put("pinned", inst.optBoolean("pinned", false))\n')

FIX_CONV_BAD_V1 = """private fun convertProviders(pc: JSONObject, secrets: JSONObject): JSONArray {
    val obj = JSONObject()
    obj.put("providerType", inst.optString("providerType", ""))
    obj.put("label", inst.optString("label", ""))
    if (inst.optBoolean("appendV1Suffix", false)) obj.put("appendV1Suffix", true)
    obj.put("customBaseURL", inst.optString("customBaseURL"))
    val mo = JSONObject()
    mo.put("displayName", model.optString("displayName", ""))
    mo.put("costTier", e.optInt("costTier"))
    obj.put("models", modelsArr)
    return out
}
"""

FIX_CONV_BAD_KEY = """private fun convertProviders(pc: JSONObject, secrets: JSONObject): JSONArray {
    val obj = JSONObject()
    obj.put("providerType", inst.optString("providerType", ""))
    obj.put("label", inst.optString("label", ""))
    if (inst.has("appendV1Suffix")) {
        obj.put("appendV1Suffix", inst.optBoolean("appendV1Suffix"))
    }
    obj.put("customBaseURL", inst.optString("customBaseURL"))
    val mo = JSONObject()
    mo.put("displayName", model.optString("displayName", ""))
    obj.put("models", modelsArr)
    return out
}
"""

# Drops exactly one delegated key — the regression this arm was built for.
FIX_CONV_BAD_RCF_PINNED = FIX_CONV_OK.replace(
    '    obj.put("pinned", inst.optBoolean("pinned", false))\n', "")

FIX_GROUPS_CONV_OK = """private fun convertGroups(pc: JSONObject): JSONArray {
    val groups = pc.optJSONArray("modelGroups") ?: return JSONArray()
    val out = JSONArray()
    for (i in 0 until groups.length()) {
        val g = groups.optJSONObject(i) ?: continue
        val obj = JSONObject()
        obj.put("id", g.optString("id", ""))
        obj.put("name", g.optString("name", ""))
        obj.put("memberEntryIds", g.optJSONArray("memberEntryIds") ?: JSONArray())
        obj.put("strategy", g.optString("strategy", "fallback"))
        obj.put("fallbackStrategy", g.optString("fallbackStrategy", "default"))
        g.optString("defaultThinkingLevel", "").takeIf { it.isNotEmpty() }
            ?.let { obj.put("defaultThinkingLevel", it) }
        if (g.optInt("contextLimitTokens", 0) > 0) {
            obj.put("contextLimitTokens", g.optInt("contextLimitTokens"))
        }
        if (g.optInt("lastContextLimitTokens", 0) > 0) {
            obj.put("lastContextLimitTokens", g.optInt("lastContextLimitTokens"))
        }
        out.put(obj)
    }
    return out
}
"""

FIX_GROUPS_CONV_BAD = FIX_GROUPS_CONV_OK.replace(
    '        obj.put("fallbackStrategy", g.optString("fallbackStrategy", "default"))\n', "")

FIX_CHAT_OK = """private fun convertChats(records: List<JSONObject>): JSONArray {
    for (rec in records) {
        val o = JSONObject()
        o.put("id", s.optString("id", ""))
        o.put("title", s.optString("title", ""))
        val pinnedRaw = if (d.has("pinnedAt")) d.opt("pinnedAt") else s.opt("pinnedAt")
        isoToMillis(pinnedRaw)?.takeIf { it > 0 }?.let { o.put("pinnedAt", it) }
        o.put("editCount", d.optInt("editCount", 0))
        out.put(o)
    }
    return out
}
"""

FIX_CHAT_BAD = """private fun convertChats(records: List<JSONObject>): JSONArray {
    for (rec in records) {
        val o = JSONObject()
        o.put("id", s.optString("id", ""))
        o.put("title", s.optString("title", ""))
        o.put("editCount", d.optInt("editCount", 0))
        out.put(o)
    }
    return out
}
"""


def build_fixture_tree(tmp, conv_src, chat_src, export_src=FIX_EXPORT,
                       sessions_src=FIX_SESSIONS, groups_export_src=FIX_GROUPS,
                       groups_conv_src=FIX_GROUPS_CONV_OK):
    os.makedirs(os.path.join(tmp, APP + "/backup"), exist_ok=True)
    os.makedirs(os.path.join(tmp, APP + "/data/repository"), exist_ok=True)
    with open(os.path.join(tmp, BM_REL), "w", encoding="utf-8") as f:
        f.write(conv_src + "\n" + groups_conv_src + "\n" + chat_src)
    with open(os.path.join(tmp, PR_REL), "w", encoding="utf-8") as f:
        f.write(export_src)
    with open(os.path.join(tmp, CB_REL), "w", encoding="utf-8") as f:
        f.write(sessions_src + "\n" + groups_export_src)
    return tmp

# --------------------------------------------------------------------------
# [deep-audit-1024 B 12-1..12-6] witness fixtures. Every one of them keeps the
# tokens the previous criteria looked for; only the EFFECT moves.
# --------------------------------------------------------------------------

# 12-1: guard, write and polarity all intact — the branch is simply unreachable.
FIX_CONV_V1_DEAD_GUARD = FIX_CONV_OK.replace(
    'if (inst.has("appendV1Suffix")) {',
    'if (false && inst.has("appendV1Suffix")) {')

# 12-2: `!true` folds to a constant; the guard only tests `has(key)`, so the
# constant has nothing to do with the source value.
FIX_CONV_PINNED_CONST = FIX_CONV_OK.replace(
    'obj.put("pinned", inst.optBoolean("pinned", false))',
    'obj.put("pinned", !true)')

# 12-3: the constant and the seam both stay, the write goes dead.
FIX_EXPORT_DEAD_RCF_PINNED = FIX_EXPORT_DELEGATING.replace(
    '    obj.put(RCF_PINNED, instance.pinned)',
    '    if (false) obj.put(RCF_PINNED, instance.pinned)')

# 12-4: the write is present but no real timestamp satisfies the predicate.
FIX_CHAT_INVERTED_PIN = FIX_CHAT_OK.replace(
    'takeIf { it > 0 }', 'takeIf { it < 0 }')

# 12-5: the write lands in a throwaway object.
FIX_GROUPS_TEMP_RECEIVER = FIX_GROUPS_CONV_OK.replace(
    'obj.put("fallbackStrategy", g.optString("fallbackStrategy", "default"))',
    'JSONObject().put("fallbackStrategy", g.optString("fallbackStrategy", "default"))')

# 12-6: block comment (the old `//`-only stripper left it in the code view).
FIX_CONV_BLOCK_COMMENTED = FIX_CONV_OK.replace(
    '    obj.put("label", inst.optString("label", ""))\n',
    '    /* obj.put("label", inst.optString("label", "")) */\n')

def selftest():
    ok = True

    def arm(name, conv_src, chat_src, expect_red, export_src=FIX_EXPORT,
            groups_export_src=FIX_GROUPS, groups_conv_src=FIX_GROUPS_CONV_OK,
            needle=None):
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            build_fixture_tree(tmp, conv_src, chat_src, export_src=export_src,
                               groups_export_src=groups_export_src,
                               groups_conv_src=groups_conv_src)
            problems, checked = run(tmp)
            red = len(problems) > 0
            verdict = "RED" if red else "GREEN"
            hit = ""
            if problems:
                hit = " :: " + problems[0].split(":")[0]
            good = red == expect_red and checked > 0
            if good and needle is not None:
                good = any(needle in p for p in problems)
            print("  [%s] %-40s expect=%s got=%s%s"
                  % ("ok" if good else "XX", name,
                     "RED" if expect_red else "GREEN", verdict, hit))
            if not good:
                ok = False

    # green fixtures — the fixed shape
    arm("provider+chat+group parity OK", FIX_CONV_OK, FIX_CHAT_OK, False)
    arm("delegated run-config keys seen", FIX_CONV_OK_DELEGATED, FIX_CHAT_OK, False,
        export_src=FIX_EXPORT_DELEGATING)
    # red fixtures — one per arm
    arm("appendV1Suffix single-state", FIX_CONV_BAD_V1, FIX_CHAT_OK, True)
    arm("provider key dropped", FIX_CONV_BAD_KEY, FIX_CHAT_OK, True)
    arm("session key dropped", FIX_CONV_OK, FIX_CHAT_BAD, True)
    # ── [wide-1024 12-misc-b] witness arms ────────────────────────────────
    # Every token the old literal-presence test looked for is still present; only
    # the EFFECT is gone, so each arm asserts the write/carry criterion speaks.
    arm("appendV1Suffix write guarded by its value",
        FIX_CONV_OK.replace(
            'if (inst.has("appendV1Suffix")) {',
            'if (inst.has("appendV1Suffix") && inst.optBoolean("appendV1Suffix")) {'),
        FIX_CHAT_OK, True, needle="arm1")
    arm("provider key read but never written",
        FIX_CONV_OK.replace(
            '    obj.put("label", inst.optString("label", ""))\n',
            '    inst.optString("label", "")\n'),
        FIX_CHAT_OK, True, needle="arm2: convertProviders dropped")
    arm("session key parsed but never written",
        FIX_CONV_OK,
        FIX_CHAT_OK.replace(
            '        isoToMillis(pinnedRaw)?.takeIf { it > 0 }?.let { o.put("pinnedAt", it) }\n',
            ''),
        True, needle="arm3: convertChats dropped")

    # [deep-audit-1039 A-07] delegated-key arms. The delegation is what makes the
    # arm see the run-config keys at all; removing it is the pre-fix blind shape
    # (literal extraction finds only the two direct puts and silently drops five).
    arm("delegated RCF key dropped", FIX_CONV_BAD_RCF_PINNED, FIX_CHAT_OK, True,
        export_src=FIX_EXPORT_DELEGATING, needle="arm2: convertProviders dropped")
    arm("delegation call removed (pre-fix blind)", FIX_CONV_OK, FIX_CHAT_OK, True,
        export_src=FIX_EXPORT_NO_DELEGATION,
        needle="no longer calls")
    arm("unmapped RCF identifier fail-closed", FIX_CONV_OK, FIX_CHAT_OK, True,
        export_src=FIX_EXPORT_UNMAPPED_RCF, needle="ROT")
    # [deep-audit-1039 A-07] group parity arms
    arm("convertGroups key dropped", FIX_CONV_OK, FIX_CHAT_OK, True,
        groups_conv_src=FIX_GROUPS_CONV_BAD,
        needle="arm4: convertGroups dropped")
    # A NEW group key in the export with no converter write must red — the
    # direction the arm is for (next dropped field turns red, not silent).
    arm("new group key needs a converter write", FIX_CONV_OK, FIX_CHAT_OK, True,
        groups_export_src=FIX_GROUPS.replace(
            '                put("id", group.id)\n',
            '                put("id", group.id)\n'
            '                put("colorTag", group.colorTag)\n'),
        needle="arm4: convertGroups dropped")

    # rotation guard — a renamed anchor must red, never silently pass
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_CONV_OK, FIX_CHAT_OK)
        p = os.path.join(tmp, BM_REL)
        with open(p, "r", encoding="utf-8") as f:
            src = f.read().replace(CONV_HEADER, "private fun convertProvidersV2(")
        with open(p, "w", encoding="utf-8") as f:
            f.write(src)
        problems, _ = run(tmp)
        red = len(problems) > 0 and any("ROT" in x for x in problems)
        print("  [%s] %-40s expect=RED got=%s"
              % ("ok" if red else "XX", "anchor rotation guard (convertProviders)",
                 "RED" if problems else "GREEN"))
        if not red:
            ok = False

    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_CONV_OK, FIX_CHAT_OK)
        p = os.path.join(tmp, BM_REL)
        with open(p, "r", encoding="utf-8") as f:
            src = f.read().replace(GROUP_HEADER, "private fun convertGroupsV2(")
        with open(p, "w", encoding="utf-8") as f:
            f.write(src)
        problems, _ = run(tmp)
        red = len(problems) > 0 and any("ROT" in x for x in problems)
        print("  [%s] %-40s expect=RED got=%s"
              % ("ok" if red else "XX", "anchor rotation guard (convertGroups)",
                 "RED" if problems else "GREEN"))
        if not red:
            ok = False

    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_CONV_OK, FIX_CHAT_OK)
        p = os.path.join(tmp, BM_REL)
        with open(p, "r", encoding="utf-8") as f:
            src = f.read().replace(CHAT_HEADER, "private fun convertChatsV2(")
        with open(p, "w", encoding="utf-8") as f:
            f.write(src)
        problems, _ = run(tmp)
        red = len(problems) > 0 and any("ROT" in x for x in problems)
        print("  [%s] %-40s expect=RED got=%s"
              % ("ok" if red else "XX", "anchor rotation guard (convertChats)",
                 "RED" if problems else "GREEN"))
        if not red:
            ok = False

    # fail-closed: the exportInstanceJSON anchor itself moved
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_CONV_OK, FIX_CHAT_OK)
        p = os.path.join(tmp, PR_REL)
        with open(p, "r", encoding="utf-8") as f:
            src = f.read().replace(EXPORT_HEADER, "fun exportInstanceJsonV2(")
        with open(p, "w", encoding="utf-8") as f:
            f.write(src)
        problems, _ = run(tmp)
        red = len(problems) > 0 and any("ROT" in x for x in problems)
        print("  [%s] %-40s expect=RED got=%s"
              % ("ok" if red else "XX", "anchor rotation guard (exportInstanceJSON)",
                 "RED" if problems else "GREEN"))
        if not red:
            ok = False

    # fail-closed: the modelGroups export block itself is unreadable
    with tempfile.TemporaryDirectory() as tmp:
        build_fixture_tree(tmp, FIX_CONV_OK, FIX_CHAT_OK)
        p = os.path.join(tmp, CB_REL)
        with open(p, "r", encoding="utf-8") as f:
            src = f.read()
        with open(p, "w", encoding="utf-8") as f:
            f.write(src.replace(GROUPS_ANCHOR, "for (g in providerRepo.modelGroups)"))
        problems, _ = run(tmp)
        red = len(problems) > 0 and any("ROT" in x for x in problems)
        print("  [%s] %-40s expect=RED got=%s"
              % ("ok" if red else "XX", "modelGroups anchor rotation guard",
                 "RED" if problems else "GREEN"))
        if not red:
            ok = False


    # ── [deep-audit-1024 B 12-1..12-6] witness arms ───────────────────────
    # Every token the old criteria looked for is still there; only the EFFECT
    # is gone. Each arm names the criterion that has to speak.
    arm("appendV1Suffix pass-through unreachable",
        FIX_CONV_V1_DEAD_GUARD, FIX_CHAT_OK, True, needle="arm1")
    arm("pinned written as a folded constant (!true)",
        FIX_CONV_PINNED_CONST, FIX_CHAT_OK, True,
        export_src=FIX_EXPORT_DELEGATING, needle="arm2: convertProviders dropped")
    arm("RCF constant written only in a dead branch",
        FIX_CONV_OK, FIX_CHAT_OK, True,
        export_src=FIX_EXPORT_DEAD_RCF_PINNED,
        needle="arm2: run-config key(s) defined but never written")
    arm("pinnedAt write gated by takeIf { it < 0 }",
        FIX_CONV_OK, FIX_CHAT_INVERTED_PIN, True, needle="arm3: convertChats dropped")
    arm("group key written into a throwaway JSONObject()",
        FIX_CONV_OK, FIX_CHAT_OK, True, groups_conv_src=FIX_GROUPS_TEMP_RECEIVER,
        needle="arm4: convertGroups dropped")
    arm("provider key commented out with /* */",
        FIX_CONV_BLOCK_COMMENTED, FIX_CHAT_OK, True,
        needle="arm2: convertProviders dropped")

    # ping-pong: green fixture stays green after the dirty fixture (no state leak)
    arm("re-green after dirty", FIX_CONV_OK, FIX_CHAT_OK, False)

    print("OPENMINIS_COMPAT_FIELD_PARITY_SELFTEST=%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default=None)
    ap.add_argument("--root", dest="rootkw", default=None)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        sys.exit(selftest())
    root = args.rootkw or args.root
    if not root:
        print("usage: openminis_compat_field_parity_check.py <repo_root>")
        sys.exit(2)
    if not os.path.isdir(root):
        print("not a directory: %s" % root)
        sys.exit(2)
    problems, checked = run(root)
    if problems:
        for p in problems:
            print("openminis-compat-parity: %s" % p)
        sys.exit(1)
    print("openminis-compat-parity: OK (%d keys pinned)" % checked)
    sys.exit(0)


if __name__ == "__main__":
    main()
