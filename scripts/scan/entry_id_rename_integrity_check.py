#!/usr/bin/env python3
"""
Gate: custom model-id rename integrity (deep-audit-1022 ISSUE-05,
branch fix/da1022-entry-id-integrity).

Renaming a custom model's id changes the composite entry key
("{instanceId}/{modelId}") — the persisted row primary key and the foreign
key every downstream consumer (lastUsed prefs / session bindings /
compact pin / group members) compares by string equality. The report's
original claim ("groups / AgentLoop entries silently drop after restart")
was wrong — toSnapshot's idMap translation + the load-side uuid=row.id
keep config-internal references self-consistent. The real cracks are:

  1. updateEntry did NO id normalization: the renamed entry kept the old
     composite uuid while the persisted row moved to the new key — and
     KEY_LAST_USED_ENTRY (prefs) still held the OLD key, so
     lastUsedVisibleEntry() stopped resolving "the model I was just using".
  2. NO blank-id interception anywhere on the rename path
     (ModelEntryDetailScreen saved `modelId` verbatim; the modelsUpdate RPC
     copied an untrimmed param) — a whitespace id mints the dangling key
     "instanceId/" and orphans every downstream lookup.
  3. A rename colliding with another entry of the same instance would make
     syncAllIncremental's upsertEntries REPLACE the existing row (same
     primary key) — the duplicate entry silently vanishes on next load.

Four arms, each judged on EFFECT (does this reject?) rather than spelling:
  1. updateEntry consumes the decision through a guard that can actually
     reject (`if (!decision.allowed) { … return@synchronized }`);
  2. decideEntryRename's blank guard and its collision predicate/consumer can
     actually reject, and the rejection is RETURNED (a locally constructed
     `EntryRenameDecision(allowed = false)` that nobody returns rejects
     nothing);
  3. the detail screen's save gate cannot be absorbed by a constant
     (`enabled = … || true`);
  4. the modelsUpdate RPC's blank guard can reject AND the copy uses the
     trimmed binding instead of re-reading the raw param.

[batch5 B5-t02] The hand-written comment stripper / brace counter / literal
whitelist (`code_only`, `_balanced`, `literal_decides`, `_if_guards`,
`function_text`) are gone: they only knew `false` as a dead literal, so
`!true && !allowed`, `others.any { false && … }`, a rejection that is built but
never returned, `|| true` after the `enabled` expression, and `if (false &&
mid.isEmpty())` all walked straight through. Structure now comes from
`_kotlin_lexer.mask_code` + `_kotlin_ast.{function_body,if_blocks,const_bool,
match_bracket}` + `_kotlin_valueflow.{guard_feasible,expr_end,brace_depth}`.

Usage: python3 scripts/scan/entry_id_rename_integrity_check.py [root]
       python3 scripts/scan/entry_id_rename_integrity_check.py --root <root>
       python3 scripts/scan/entry_id_rename_integrity_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import argparse
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import expr_end, guard_feasible  # noqa: E402

APP = "src/android/app/src/main/java/com/rikkaminis/app"
PR_REL = APP + "/data/repository/ProviderRepository.kt"
PU_REL = APP + "/data/repository/ProviderRepositoryUtils.kt"
ME_REL = APP + "/ui/settings/ModelEntryDetailScreen.kt"
PM_REL = APP + "/debug/ProviderMutationMethods.kt"

UPDATE_FN = "updateEntry"
DECIDE_FN = "decideEntryRename"
MODELS_UPDATE_FN = "modelsUpdate"

# Arm 1 — updateEntry wiring. Code anchors only (comments are stripped):
# the decideEntryRename call, the fail-closed bail, the two reference-sync
# rewrites and the KEY_LAST_USED_ENTRY rewrite must all be live code.
ARM1_ANCHORS = (
    "decideEntryRename(",
    "return@synchronized",
    "memberEntryIds.replaceAll",
    "agentLoopModelEntryIds.replaceAll",
    "lastUsedEntryId = newUuid",
)

# Arm 2 — decision core must reject blank ids and colliding renames, and
# carry the old→new remap on an allowed rename.
ARM2_ANCHORS = (
    "updatedModelId.isBlank()",
    "others.any",
    "oldKey = existingUuid",
    "newKey = newKey",
)

# Arm 3 — the detail screen must trim before persisting and gate blank ids.
ARM3_ANCHORS = (
    "baseModel.copy(id = modelId.trim())",
    "enabled = !entry.isCustom || modelId.isNotBlank()",
)

# Arm 4 — the modelsUpdate RPC must trim and reject blank modelId params.
ARM4_ANCHORS = (
    'params.optString("modelId", current.baseModel.id).trim()',
    '"modelId must not be blank"',
)

SELFTEST_MARK = "ENTRY_ID_RENAME_INTEGRITY_SELFTEST=PASS"


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def parts(expr, op):
    """顶层按 op 切分的片段（共享 split_top 返回区间）。"""
    return [expr[a:b] for a, b in split_top(expr, (op,))]


def live_guard(cond):
    """守卫是否可能为真。

    两条家族洞都要在这里堵住，且**本门所有臂共用这一个判定**：
      - 常量折叠：`false && g` / `!true && g` / `1 == 2 && g` 恒不可真；
      - 恒真吸收：`... || true` / `... || !false` 让条件永远是 true。
    只认字面 `false` 的白名单漏掉前者，只看 `true` 字面漏掉后者。
    """
    if guard_feasible(cond) is False:
        return False
    return not any(const_bool(p) is True for p in parts(cond, "||"))


def returns_rejection(code, start, end):
    """[start,end) 内是否存在「退出外层函数并返回拒绝决策」的 return。

    `val ignored = EntryRenameDecision(allowed = false, …)` 构造了拒绝决策却
    没有返回 —— 锚「`allowed = false` 出现过」的旧判据看不见这一点，而函数
    紧接着落到 `return EntryRenameDecision(allowed = true)`，冲突 100% 放行。
    """
    for m in re.finditer(r"\breturn\b(@\s*[A-Za-z_]\w*)?", code[start:end]):
        if m.group(1):
            continue  # 标签 return 只退 lambda / synchronized，不把决策交给调用方
        pos = start + m.start()
        # 裸 return（含 if 块内的）就是「把值交出去」；块深度不是区分 lambda 的
        # 可靠信号（if-block 也会加深一层），标签才是。
        stripped = code[pos + len(m.group(0)):expr_end(code, pos + len(m.group(0)))]
        if re.search(r"allowed\s*=\s*false", stripped):
            return True
    return False


def body_of(text, fn):
    """(start, end, body_text) for `fun <fn>(`, or None (gate ROT)."""
    span = function_body(text, fn)
    if span is None:
        return None
    start, end, _kind = span
    return start, end, text[start:end]


def guard_line(src, base, code, cond):
    """函数体内的守卫在**文件**里的行号：按条件文本逐字匹配，不用近似锚。"""
    for m in re.finditer(r"\bif\s*\(", code):
        p_open = m.end() - 1
        close = match_bracket(code, p_open)
        if close < 0:
            continue
        if code[p_open + 1:close] == cond:
            return line_of(src, base + m.start())
    return 0


def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def check_anchors(src, rel, fn, arm, anchors, what):
    """Anchor presence, read on the LITERAL channel (comments stripped, string
    contents kept — two of the arm-4 anchors are string literals)."""
    if fn is None:
        code = src
        base = 0
    else:
        body = body_of(src, fn)
        if body is None:
            return [(rel, 0, arm, what + ": `fun " + fn + "(` not found — gate ROT")]
        base, _end, code = body
    out = []
    for anchor in anchors:
        if anchor not in code:
            out.append((rel, line_of(src, base) if base >= 0 else 0, arm,
                        "%s is missing required code anchor `%s`" % (what, anchor)))
    return out


def check_bail_guard(src, rel, fn):
    """Arm 1 — the fail-closed bail must be a guard that can actually reject.

    Presence anchors alone accept `if (false && !decision.allowed)` and
    `if (!true && !decision.allowed)`: the call, the `return@synchronized` and
    the renaming block are all still spelled out, yet the decision is never
    consumed and the conflicting entry is written.
    """
    out = []
    body = body_of(src, fn)
    if body is None:
        return [(rel, 0, "1", "`fun " + fn + "(` not found — gate ROT")]
    base, _end, code = body
    if "return@synchronized" not in code:
        return [(rel, 0, "1", "the fail-closed `return@synchronized` bail is missing")]
    guards = [g for g in if_blocks(code) if "return@synchronized" in code[g[1]:g[2]]]
    if not guards:
        out.append((rel, 0, "1",
                    "`return@synchronized` is not owned by any `if (...)` — the "
                    "rejection decision is not consumed"))
        return out
    consumers = [g for g in guards if ".allowed" in g[0]]
    if not consumers:
        out.append((rel, 0, "1",
                    "no `if (...decision.allowed...)` guard owns the bail — the "
                    "decideEntryRename result is never consumed"))
    elif not any(live_guard(g[0]) for g in consumers):
        cond = " ".join(consumers[0][0].split())
        out.append((rel, guard_line(src, base, code, consumers[0][0]), "1",
                    "the `%s` guard can never reject — a constant decides it "
                    "(dead guard, `!true` included)" % cond[:70]))
    return out


def check_collision_decision(src, rel, fn):
    """Arm 2 — the computed collision result must be what rejects the rename.

    `if (!true && collides)` (dead guard), `others.any { false && … }` (the
    predicate can never be true) and a rejection that is constructed but never
    returned must all red.
    """
    out = []
    body = body_of(src, fn)
    if body is None:
        return [(rel, 0, "2", "`fun " + fn + "(` not found — gate ROT")]
    base, _end, code = body

    blanks = [g for g in if_blocks(code) if "isBlank()" in g[0]]
    if not blanks:
        out.append((rel, 0, "2",
                    "no `if (...isBlank())` guard — a blank id mints the dangling "
                    "key `instanceId/` and orphans every downstream lookup"))
    elif not any(live_guard(g[0]) and returns_rejection(code, g[1], g[2])
                 for g in blanks):
        out.append((rel, guard_line(src, base, code, blanks[0][0]), "2",
                    "the blank-id guard does not reject (dead condition, or a "
                    "rejection that is never returned)"))

    m = re.search(r"\bval\s+(\w+)\s*=\s*others\s*\.\s*any\s*\{", code)
    if not m:
        out.append((rel, 0, "2",
                    "no `val X = others.any { … }` collision computation — an "
                    "unchecked rename overwrites the row that already owns the key"))
        return out
    name = m.group(1)
    open_pos = m.end() - 1
    close = match_bracket(code, open_pos)
    if close < 0:
        out.append((rel, 0, "2", "`others.any { … }` is not brace-balanced — gate ROT"))
        return out
    pred = code[open_pos + 1:close]
    short = " ".join(pred.split())[:70]
    if "updatedInstanceId" not in pred or "updatedModelId" not in pred:
        out.append((rel, 0, "2",
                    "`others.any { %s }` never compares the proposed pair — the "
                    "collision test is decorative" % short))
    elif not live_guard(pred):
        out.append((rel, line_of(src, base + m.start()), "2",
                    "the collision predicate `%s` can never be true — the collision "
                    "test fires for nothing, so a duplicate id overwrites the row "
                    "that already owns the key" % short))
    consumers = [g for g in if_blocks(code)
                 if re.search(r"\b%s\b" % re.escape(name), g[0])]
    if not consumers:
        out.append((rel, 0, "2",
                    "`%s` (the collision result) is never tested — the decision is "
                    "discarded" % name))
    elif not any(live_guard(g[0]) and returns_rejection(code, g[1], g[2])
                 for g in consumers):
        out.append((rel, guard_line(src, base, code, consumers[0][0]), "2",
                    "`%s` is only tested by a guard that cannot reject, or whose "
                    "rejection is built and never returned" % name))
    return out


def check_save_gate(src, rel):
    """Arm 3 — the save button must not be enabled while the id is blank.

    The anchor only proves the expression is spelled out. `enabled = … ||
    true` / `|| !false` keeps the anchor and disables the gate: the button
    stays hot on an empty model id and the blank id is submitted.
    """
    out = []
    found = False
    for m in re.finditer(r"\benabled\s*=", src):
        # expr_end keeps going through a line that ends with `,` (it reads that
        # as a continuation), so cut at the first TOP-LEVEL comma: the argument
        # expression is over at the comma, not at the newline.
        raw = src[m.end():expr_end(src, m.end())]
        spans = split_top(raw, (",",))
        expr = raw[spans[0][0]:spans[0][1]].strip() if spans else raw.strip()
        if "isNotBlank" not in expr and "isCustom" not in expr:
            continue
        found = True
        if any(const_bool(p) is True for p in parts(expr, "||")) or \
                guard_feasible(expr) is True and const_bool(expr) is True:
            out.append((rel, line_of(src, m.start()), "3",
                        "the save gate is absorbed by a constant-true operand (`%s`) — "
                        "the button stays enabled while the model id is blank"
                        % " ".join(expr.split())[:80]))
        elif guard_feasible(expr) is False:
            out.append((rel, line_of(src, m.start()), "3",
                        "the save gate is constant-false (`%s`) — a custom model can "
                        "never be saved" % " ".join(expr.split())[:80]))
    if not found:
        out.append((rel, 0, "3",
                    "no `enabled = …isNotBlank()…` gate — a blank model id can be "
                    "saved and mints the dangling key `instanceId/`"))
    return out


def check_models_update(src, rel, fn):
    """Arm 4 — the RPC must reject blank ids AND copy the trimmed binding.

    `if (false && mid.isEmpty())` keeps the throw and the anchor while the
    guard never fires; `copy(id = params.optString(…))` keeps the trimmed
    `val mid = …` above and then writes the raw param anyway.
    """
    out = []
    body = body_of(src, fn)
    if body is None:
        return [(rel, 0, "4", "`fun " + fn + "(` not found — gate ROT")]
    base, _end, code = body

    # The binding that matters is the one the applied id actually comes from —
    # resolve it BACKWARDS from `copy(id = …)`, not forwards from the first
    # `val x = params.optString(…)` in the function (that one is `entryId`).
    copies = []
    for mm in re.finditer(r"\bcopy\s*\(", code):
        open_pos = mm.end() - 1
        close = match_bracket(code, open_pos)
        if close < 0:
            continue
        for a, b in split_top(code[open_pos + 1:close], (",",)):
            arg = code[open_pos + 1 + a:open_pos + 1 + b]
            if re.match(r"\s*id\s*=", arg):
                copies.append((strip_parens(arg.split("=", 1)[1].strip()), open_pos))
    if not copies:
        return [(rel, 0, "4",
                 "no `copy(id = …)` on the modelsUpdate path — the new model id is "
                 "never applied")]

    decl_re = re.compile(r"\bval\s+(\w+)\s*=\s*params\s*\.\s*optString\s*\(")
    mid, decl, mid_at = None, "", 0
    for value, copy_at in copies:
        for mm in decl_re.finditer(code):
            ident = mm.group(1)
            if not re.search(r"\b%s\b" % re.escape(ident), value):
                continue
            mid, decl, mid_at = ident, code[mm.start():expr_end(code, mm.start())].strip(), mm.start()
            break
        if mid:
            break
        out.append((rel, line_of(src, base + copy_at), "4",
                    "`copy(id = %s)` bypasses the trimmed binding — the raw param writes "
                    "the dirty id" % " ".join(value.split())[:60]))
    if mid is None:
        return out

    if not decl.endswith(".trim()"):
        out.append((rel, line_of(src, base + mid_at), "4",
                    "the modelId binding is not trimmed (`%s`) — a whitespace id mints "
                    "the dangling key `instanceId/`" % " ".join(decl.split())[:70]))

    guards = [g for g in if_blocks(code)
              if re.search(r"\b%s\b" % re.escape(mid), g[0])
              and re.search(r"isBlank|isEmpty|isNotBlank", g[0])]
    if not guards:
        out.append((rel, 0, "4",
                    "no blank-id guard on the trimmed binding `%s`" % mid))
    elif not any(live_guard(g[0]) and re.search(r"\bthrow\b", code[g[1]:g[2]])
                 for g in guards):
        cond = " ".join(guards[0][0].split())
        out.append((rel, guard_line(src, base, code, guards[0][0]), "4",
                    "the blank-id guard `%s` cannot reject (dead condition, `!true` "
                    "included, or no throw in its body)" % cond[:70]))
    return out


def run_checks(root, rels=None):
    rels = rels or {"pr": PR_REL, "pu": PU_REL, "me": ME_REL, "pm": PM_REL}
    srcs = {k: read(rel, root) for k, rel in rels.items()}
    missing = [(rel, 0, "?", "file not found — gate ROT")
               for k, rel in rels.items() if srcs[k] is None]
    if missing:
        return missing
    code, lit = {}, {}
    for k, text in srcs.items():
        try:
            code[k] = mask_code(text)
            lit[k] = mask_comments(text)
        except KotlinLexError as exc:  # fail-closed: never scan raw text
            return [(rels[k], 0, "?", "kotlin lexer refused the file — gate fails closed (%s)" % exc)]
    checks = []
    checks += check_anchors(lit["pr"], rels["pr"], UPDATE_FN, "1", ARM1_ANCHORS, "updateEntry")
    checks += check_bail_guard(code["pr"], rels["pr"], UPDATE_FN)
    checks += check_anchors(lit["pu"], rels["pu"], DECIDE_FN, "2", ARM2_ANCHORS, "decideEntryRename")
    checks += check_collision_decision(code["pu"], rels["pu"], DECIDE_FN)
    checks += check_anchors(lit["me"], rels["me"], None, "3", ARM3_ANCHORS, "ModelEntryDetailScreen")
    checks += check_save_gate(code["me"], rels["me"])
    checks += check_anchors(lit["pm"], rels["pm"], MODELS_UPDATE_FN, "4", ARM4_ANCHORS,
                            "modelsUpdate RPC")
    checks += check_models_update(code["pm"], rels["pm"], MODELS_UPDATE_FN)
    return checks


# ----------------------------------------------------------------------
# Selftest fixtures — minimal Kotlin-shaped files exercising every arm.
# ----------------------------------------------------------------------
FIX_PR_OK = """fun updateEntry(entry: ModelEntry) = synchronized(configLock) {
    ensureConfigLoaded()
    val config = mutationSnapshot(_config.value)
    val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
    if (idx >= 0) {
        val existing = config.modelEntries[idx]
        val decision = decideEntryRename(
            existingUuid = existing.id,
            updatedInstanceId = entry.providerInstanceId,
            updatedModelId = entry.baseModel.id,
            others = config.modelEntries.map { it.providerInstanceId to it.baseModel.id },
        )
        if (!decision.allowed) {
            return@synchronized
        }
        val renamed = decision.newKey != null
        val newUuid = decision.newKey ?: existing.id
        config.modelEntries[idx] = entry.copy(uuid = newUuid, userModifiedAt = 1L)
        if (renamed) {
            val oldKey = decision.oldKey
            config.modelGroups.forEach { group ->
                group.memberEntryIds.replaceAll { id -> if (id == oldKey) newUuid else id }
            }
            config.agentLoopModelEntryIds.replaceAll { id -> if (id == oldKey) newUuid else id }
            if (lastUsedEntryId == oldKey) lastUsedEntryId = newUuid
        }
        saveConfig(config)
    }
}
"""

FIX_PR_BAD_NOSYNC = """fun updateEntry(entry: ModelEntry) = synchronized(configLock) {
    ensureConfigLoaded()
    val config = mutationSnapshot(_config.value)
    val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
    if (idx >= 0) {
        val decision = decideEntryRename(existing.id, entry.providerInstanceId, entry.baseModel.id, others)
        if (!decision.allowed) {
            return@synchronized
        }
        config.modelEntries[idx] = entry.copy(userModifiedAt = 1L)
        saveConfig(config)
    }
}
"""

FIX_PR_BAD_NOGATE = """fun updateEntry(entry: ModelEntry) = synchronized(configLock) {
    ensureConfigLoaded()
    val config = mutationSnapshot(_config.value)
    val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
    if (idx >= 0) {
        val existing = config.modelEntries[idx]
        val decision = decideEntryRename(
            existingUuid = existing.id,
            updatedInstanceId = entry.providerInstanceId,
            updatedModelId = entry.baseModel.id,
            others = config.modelEntries.map { it.providerInstanceId to it.baseModel.id },
        )
        config.modelEntries[idx] = entry.copy(userModifiedAt = 1L)
        config.modelGroups.forEach { group ->
            group.memberEntryIds.replaceAll { id -> if (id == decision.oldKey) decision.newKey else id }
        }
        config.agentLoopModelEntryIds.replaceAll { id -> if (id == decision.oldKey) decision.newKey else id }
        if (lastUsedEntryId == decision.oldKey) lastUsedEntryId = decision.newKey
        saveConfig(config)
    }
}
"""

FIX_PU_OK = """internal fun decideEntryRename(
    existingUuid: String,
    updatedInstanceId: String,
    updatedModelId: String,
    others: List<Pair<String, String>>,
): EntryRenameDecision {
    if (updatedModelId.isBlank()) {
        return EntryRenameDecision(allowed = false, rejectionReason = "blank model id")
    }
    val newKey = "$updatedInstanceId/$updatedModelId"
    if (newKey == existingUuid) return EntryRenameDecision(allowed = true)
    val collides = others.any { it.first == updatedInstanceId && it.second == updatedModelId }
    if (collides) {
        return EntryRenameDecision(allowed = false, rejectionReason = "collision")
    }
    return EntryRenameDecision(allowed = true, oldKey = existingUuid, newKey = newKey)
}
"""

FIX_PU_BAD_NOBLANK = """internal fun decideEntryRename(
    existingUuid: String,
    updatedInstanceId: String,
    updatedModelId: String,
    others: List<Pair<String, String>>,
): EntryRenameDecision {
    val newKey = "$updatedInstanceId/$updatedModelId"
    if (newKey == existingUuid) return EntryRenameDecision(allowed = true)
    val collides = others.any { it.first == updatedInstanceId && it.second == updatedModelId }
    if (collides) {
        return EntryRenameDecision(allowed = false, rejectionReason = "collision")
    }
    return EntryRenameDecision(allowed = true, oldKey = existingUuid, newKey = newKey)
}
"""

FIX_PU_BAD_NOCOLLIDE = """internal fun decideEntryRename(
    existingUuid: String,
    updatedInstanceId: String,
    updatedModelId: String,
    others: List<Pair<String, String>>,
): EntryRenameDecision {
    if (updatedModelId.isBlank()) {
        return EntryRenameDecision(allowed = false, rejectionReason = "blank model id")
    }
    val newKey = "$updatedInstanceId/$updatedModelId"
    if (newKey == existingUuid) return EntryRenameDecision(allowed = true)
    return EntryRenameDecision(allowed = true, oldKey = existingUuid, newKey = newKey)
}
"""

FIX_ME_OK = """@Composable
fun ModelEntryDetailScreen(instanceId: String, entryId: String, providerRepository: ProviderRepository, onBack: () -> Unit) {
    val updated = if (entry.isCustom) {
        entry.copy(baseModel = baseModel.copy(id = modelId.trim()), overrides = newOverrides)
    } else {
        entry.copy(overrides = newOverrides)
    }
    providerRepository.updateEntry(updated)
    onBack()
}

// actions block
MinisButton(
    onClick = { save() },
    enabled = !entry.isCustom || modelId.isNotBlank(),
) { Text("save") }
"""

FIX_ME_BAD_NOTRIM = """@Composable
fun ModelEntryDetailScreen(instanceId: String, entryId: String, providerRepository: ProviderRepository, onBack: () -> Unit) {
    val updated = if (entry.isCustom) {
        entry.copy(baseModel = baseModel.copy(id = modelId), overrides = newOverrides)
    } else {
        entry.copy(overrides = newOverrides)
    }
    providerRepository.updateEntry(updated)
    onBack()
}

MinisButton(
    onClick = { save() },
    enabled = !entry.isCustom || modelId.isNotBlank(),
) { Text("save") }
"""

FIX_ME_BAD_NOGATE = """@Composable
fun ModelEntryDetailScreen(instanceId: String, entryId: String, providerRepository: ProviderRepository, onBack: () -> Unit) {
    val updated = if (entry.isCustom) {
        entry.copy(baseModel = baseModel.copy(id = modelId.trim()), overrides = newOverrides)
    } else {
        entry.copy(overrides = newOverrides)
    }
    providerRepository.updateEntry(updated)
    onBack()
}

MinisButton(onClick = { save() }) { Text("save") }
"""

FIX_PM_OK = """fun modelsUpdate(context: Context, params: JSONObject): JSONObject {
    val newModel = if (params.has("modelId")) {
        val mid = params.optString("modelId", current.baseModel.id).trim()
        if (mid.isEmpty()) {
            throw RPCException(-32602, "modelId must not be blank")
        }
        current.baseModel.copy(id = mid)
    } else current.baseModel
    return JSONObject()
}
"""

FIX_PM_BAD_NOTRIM = """fun modelsUpdate(context: Context, params: JSONObject): JSONObject {
    val newModel = if (params.has("modelId")) {
        val mid = params.optString("modelId", current.baseModel.id)
        current.baseModel.copy(id = mid)
    } else current.baseModel
    return JSONObject()
}
"""

# (name, {rel-key: fixture}, rel-map, expected-arm)
SELFTEST_CASES = [
    ("pr-ok", {"pr": FIX_PR_OK, "pu": FIX_PU_OK, "me": FIX_ME_OK, "pm": FIX_PM_OK}, None),
    ("pr-nosync", {"pr": FIX_PR_BAD_NOSYNC, "pu": FIX_PU_OK, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "1"),
    ("pr-nogate", {"pr": FIX_PR_BAD_NOGATE, "pu": FIX_PU_OK, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "1"),
    ("pu-noblank", {"pr": FIX_PR_OK, "pu": FIX_PU_BAD_NOBLANK, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "2"),
    ("pu-nocollide", {"pr": FIX_PR_OK, "pu": FIX_PU_BAD_NOCOLLIDE, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "2"),
    ("me-notrim", {"pr": FIX_PR_OK, "pu": FIX_PU_OK, "me": FIX_ME_BAD_NOTRIM, "pm": FIX_PM_OK}, "3"),
    ("me-nogate", {"pr": FIX_PR_OK, "pu": FIX_PU_OK, "me": FIX_ME_BAD_NOGATE, "pm": FIX_PM_OK}, "3"),
    ("pm-notrim", {"pr": FIX_PR_OK, "pu": FIX_PU_OK, "me": FIX_ME_OK, "pm": FIX_PM_BAD_NOTRIM}, "4"),
]

# The `*-witness` cases are the exact minimal patches the wide3 / batch5
# external adversarial audits used to falsify this gate — they keep the new
# effect-based criteria load-bearing:
#   Arm 1 `if (false && !decision.allowed)` — anchors intact, decision discarded
#   Arm 1 `if (!true && !decision.allowed)` — same, with the negated literal
#   Arm 2 `if (false && collides)`          — the collision result never rejects
#   Arm 2 `others.any { false && … }`       — the predicate can never be true
#   Arm 2 rejection built but not returned  — falls through to allowed = true
#   Arm 3 `enabled = … || true`             — the anchor still matches
#   Arm 3 `/* enabled = … */ enabled = true`— only `//` used to be stripped
#   Arm 4 `if (false && mid.isEmpty())`     — the blank guard never fires
#   Arm 4 `copy(id = params.optString(…))`  — the trimmed binding is bypassed
FIX_PR_BAD_DEADGUARD = FIX_PR_OK.replace(
    "if (!decision.allowed) {", "if (false && !decision.allowed) {")
FIX_PR_BAD_NEGLITERAL = FIX_PR_OK.replace(
    "if (!decision.allowed) {", "if (!true && !decision.allowed) {")
FIX_PU_BAD_DEADGUARD = FIX_PU_OK.replace(
    "if (collides) {", "if (false && collides) {")
FIX_PU_BAD_DECORATIVE = FIX_PU_OK.replace(
    "val collides = others.any { it.first == updatedInstanceId && it.second == updatedModelId }",
    "val collides = others.any { false }")
FIX_PU_BAD_PREDICATE = FIX_PU_OK.replace(
    "val collides = others.any { it.first == updatedInstanceId && it.second == updatedModelId }",
    "val collides = others.any { false && it.first == updatedInstanceId && it.second == updatedModelId }")
FIX_PU_BAD_NORETURN = FIX_PU_OK.replace(
    """    if (collides) {
        return EntryRenameDecision(allowed = false, rejectionReason = "collision")
    }""",
    """    if (collides) {
        val ignored = EntryRenameDecision(allowed = false, rejectionReason = "collision")
    }""")
FIX_ME_BAD_COMMENTED = FIX_ME_OK.replace(
    "    enabled = !entry.isCustom || modelId.isNotBlank(),",
    "    /* enabled = !entry.isCustom || modelId.isNotBlank(), */\n    enabled = true,")
FIX_ME_BAD_ORTRUE = FIX_ME_OK.replace(
    "    enabled = !entry.isCustom || modelId.isNotBlank(),",
    "    enabled = !entry.isCustom || modelId.isNotBlank() || true,")
FIX_PM_BAD_DEADGUARD = FIX_PM_OK.replace(
    """        if (mid.isEmpty()) {
            throw RPCException(-32602, "modelId must not be blank")
        }
        current.baseModel.copy(id = mid)""",
    """        if (false && mid.isEmpty()) {
            throw RPCException(-32602, "modelId must not be blank")
        }
        current.baseModel.copy(id = params.optString("modelId", current.baseModel.id))""")

SELFTEST_CASES += [
    ("pr-witness-deadguard",
     {"pr": FIX_PR_BAD_DEADGUARD, "pu": FIX_PU_OK, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "1"),
    ("pr-witness-negliteral",
     {"pr": FIX_PR_BAD_NEGLITERAL, "pu": FIX_PU_OK, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "1"),
    ("pu-witness-deadguard",
     {"pr": FIX_PR_OK, "pu": FIX_PU_BAD_DEADGUARD, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "2"),
    ("pu-witness-decorative",
     {"pr": FIX_PR_OK, "pu": FIX_PU_BAD_DECORATIVE, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "2"),
    ("pu-witness-predicate",
     {"pr": FIX_PR_OK, "pu": FIX_PU_BAD_PREDICATE, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "2"),
    ("pu-witness-noreturn",
     {"pr": FIX_PR_OK, "pu": FIX_PU_BAD_NORETURN, "me": FIX_ME_OK, "pm": FIX_PM_OK}, "2"),
    ("me-witness-blockcomment",
     {"pr": FIX_PR_OK, "pu": FIX_PU_OK, "me": FIX_ME_BAD_COMMENTED, "pm": FIX_PM_OK}, "3"),
    ("me-witness-ortrue",
     {"pr": FIX_PR_OK, "pu": FIX_PU_OK, "me": FIX_ME_BAD_ORTRUE, "pm": FIX_PM_OK}, "3"),
    ("pm-witness-deadguard",
     {"pr": FIX_PR_OK, "pu": FIX_PU_OK, "me": FIX_ME_OK, "pm": FIX_PM_BAD_DEADGUARD}, "4"),
]


def selftest():
    rels = {"pr": "ProviderRepository.kt", "pu": "ProviderRepositoryUtils.kt",
            "me": "ModelEntryDetailScreen.kt", "pm": "ProviderMutationMethods.kt"}
    ok = True
    for name, fixtures, expect_arm in SELFTEST_CASES:
        with tempfile.TemporaryDirectory() as tmp:
            for k, rel in rels.items():
                path = os.path.join(tmp, rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(fixtures[k])
            violations = run_checks(tmp, rels=rels)
            arms = {v[2] for v in violations}
            if expect_arm is None:
                if violations:
                    ok = False
                    print("  selftest %-24s FAIL: expected clean, got arms %s" % (name, sorted(arms)))
                else:
                    print("  selftest %-24s PASS (clean)" % name)
            else:
                if arms == {expect_arm} and violations:
                    print("  selftest %-24s PASS (red on arm %s)" % (name, expect_arm))
                else:
                    ok = False
                    print("  selftest %-24s FAIL: expected red on arm %s, got arms %s"
                          % (name, expect_arm, sorted(arms)))
    if ok:
        print(SELFTEST_MARK + " (%d fixtures)" % len(SELFTEST_CASES))
    else:
        print("ENTRY_ID_RENAME_INTEGRITY_SELFTEST=FAIL")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root_pos", nargs="?", help="repo root (positional)")
    ap.add_argument("--root", dest="root", help="repo root")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    root = args.root or args.root_pos
    if not root:
        print("usage: entry_id_rename_integrity_check.py [root] | --root <root> | --selftest")
        return 2
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        print("not a directory: %s" % root)
        return 2
    violations = run_checks(root)
    if violations:
        for rel, ln, arm, msg in violations:
            print("VIOLATION [%s] %s:%d: %s" % (arm, rel, ln, msg))
        print("Summary: %d violation(s)" % len(violations))
        return 1
    print("entry_id_rename_integrity_check: OK (4 arms, 0 violations)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
