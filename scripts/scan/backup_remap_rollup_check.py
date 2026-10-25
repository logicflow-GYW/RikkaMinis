#!/usr/bin/env python3
"""
Gate: a restore must land every id reference it re-minted, and a backup must
carry the distilled memory layer (deep-audit-1018, ISSUE-09, branch
fix/da1018-backup-remap).

Three silent-in-production defect classes, all "the payload looks complete but
one field/artifact points at something that no longer exists on the target":

  A. PINNED COMPACTION MODEL (remapDefaultsIds)
     Import mints fresh entry uuids (Stage 1) and rewrites the `defaults.*`
     id-bearing fields from the source install's uuids. `runtime.compactModelEntryId`
     was not among them. Its consumer treats an unresolvable pin as
     "not reachable — follow the session chain" with ONE info line
     (CompactOrchestration), so a restore onto another install silently
     un-pinned the user's compaction model with no user-visible signal.

  B. SESSION MODEL BINDING (importChatSections)
     Session rows persist `{"type":"group","groupId":…,"lastEntryId":…}` /
     `{"type":"entry","entryId":…}` built from the SOURCE install's uuids.
     Writing `s.optString("modelBinding")` through verbatim made every restored
     session fail ChatViewModel.restoreFromBinding → silent degradation to the
     stale modelId (group lost, so no fallback chain either).

  C. DISTILLED MEMORY (buildSections + deleteFile)
     `MemoryRepository.listAllFiles()` excludes MEMORY-ROLLUP.md on purpose
     (settings list / memory_get must not surface it). Packing `memoryFiles`
     from that listing alone therefore never carried the distilled rules — the
     one artifact a device transfer cannot regenerate. Symmetrically,
     deleteFile guarded GLOBAL.md / SOUL.md but not the rollup.

  D. VOICE GROUP BINDINGS (ConfigBuiltins.registerDefaults + remapDefaultsIds)
     [da1031-A] `defaults.voiceInputGroup` / `defaults.voiceOutputGroup` are
     group-id references exactly like `defaults.primaryGroup`, but they were
     missing from BOTH the registry (so exportJSON() never carried them in the
     backup's `fields` section) and `remapDefaultsIds` (so a restore that did
     carry them left them pointing at the source install's group id). Either
     way NULL on the target — the voice panel silently falls back to the
     System engine. Both halves are needed: registering without remapping
     heals nothing, remapping without registering never sees a value.

  E. THINKING-RULE RE-ATTACH KEY (ConfigBackup.import Stage 1 vs Stage 1.5)
     [da1031-A] rules ride a (providerType, providerLabel) key. Stage 1 used
     to register with the label importInstanceJSON RESOLVED (auto-renamed to
     "$label ($suffix)" on a collision) while Stage 1.5 looks up the backup's
     ORIGINAL providerLabel — on any install that already had the provider,
     the whole thinking-rules group was silently dropped. Both sides must
     spell the backup's own label.

   F. ROLLUP CANDIDATE口径 SINGLE SOURCE (MemoryRepository.dailyLogSizes vs
      MemoryRollupRunner.pickLargestEligibleDate)
      [dv15-A4] The prompt-side size hint filtered with three literal file
      names only (GLOBAL.md / MEMORY-ROLLUP.md / SOUL.md) while the executor
      additionally required a past date shape and no existing rollup section.
      An already-distilled log therefore stayed "the largest daily log"
      forever: the system prompt re-suggested memory_rollup on every turn and
      the agent burned a call to be told SKIPPED_ALREADY. Both sides now judge
      candidacy with one predicate; this criterion asserts the routing plus the
      three decisions that predicate must keep.

Registered gates assert the ROUTING/PRESENCE of the decision, not the absence
of a spelling, so a refactor that keeps the contract is a deliberate edit here.

Usage: python3 scripts/scan/backup_remap_rollup_check.py [root]
       python3 scripts/scan/backup_remap_rollup_check.py --root <root>
       python3 scripts/scan/backup_remap_rollup_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import argparse
import os
import re
import sys

CFG_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"
MEM_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/MemoryRepository.kt"
BUILTINS_REL = "src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBuiltins.kt"

COMPACT_PATH = '"runtime.compactModelEntryId"'
ROLLUP_ARG = "com.rikkaminis.app.workspace.MemoryRollupEngine.ROLLUP_FILE"
REMAP_SESSION_CALL = "remapSessionBindingJson("
MEM_PACK_CALL = "memoryBackupEntries("
ROLLUP_GUARD = "if (name == ROLLUP_FILE) return false"
SAVE_BY_NAME = "memoryRepo.saveFile(name, content)"

# ── [da1031-A] ISSUE-03: the two voice-group bindings ────────────────────
# D. VOICE GROUP BINDINGS (registry + remapDefaultsIds + thinking-rule key)
#    `defaults.voiceInputGroup` / `defaults.voiceOutputGroup` are group-id
#    references exactly like `defaults.primaryGroup`, but they were missing
#    from BOTH the registry (so ConfigRegistry.exportJSON() never carried them
#    in the backup's `fields` section) and `remapDefaultsIds` (so a restore
#    that did carry them left them pointing at the source install's group id).
#    NULL on the target either way — the voice panel silently falls back to the
#    System engine. Both halves are needed: registering without remapping heals
#    nothing, remapping without registering never sees a value.
VOICE_GROUP_PATHS = ("defaults.voiceInputGroup", "defaults.voiceOutputGroup")
VOICE_GROUP_READERS = ("repo.voiceInputGroupId ?: \"\"", "repo.voiceOutputGroupId ?: \"\"")

# E. THINKING-RULE RE-ATTACH KEY (Stage 1 registration vs Stage 1.5 lookup)
#    Rules ride a (providerType, providerLabel) key. Stage 1 used to register
#    the key with the label importInstanceJSON RESOLVED (auto-renamed to
#    "$label ($suffix)" on a collision), while Stage 1.5 looks up the backup's
#    original providerLabel — so on any install that already had the provider,
#    the whole thinking-rules group was dropped. Both sides must spell the
#    backup's own label.
STAGE1_KEY_CALL = "providerKeyToId["
STAGE1_KEY_END = "] = instId"
STAGE15_LOOKUP = 'providerKeyToId["$type\\u0000$label"]'

# F. ROLLUP CANDIDATE口径 SINGLE SOURCE
#    The hint (MemoryRepository.dailyLogSizes) and the executor
#    (MemoryRollupRunner.pickLargestEligibleDate) must judge candidacy through
#    the SAME predicate. The criterion is anchored on the decision — the hint's
#    filter chain routes through the shared predicate, the predicate expresses
#    date shape + today boundary + already-distilled, and the executor neither
#    re-spells the date pattern nor bypasses the predicate.
ENGINE_REL = "src/android/app/src/main/java/com/rikkaminis/app/workspace/MemoryRollupEngine.kt"
RUNNER_REL = "src/android/app/src/main/java/com/rikkaminis/app/workspace/MemoryRollupRunner.kt"
HINT_CALL = "com.rikkaminis.app.workspace.MemoryRollupEngine.isRollupCandidate("
EXEC_CALL = "MemoryRollupEngine.isRollupCandidate("
CANDIDATE_DEF = "fun isRollupCandidate("
PAST_LOG_DEF = "fun isPastDailyLog("
PAST_LOG_CALL = "isPastDailyLog("
DATE_PATTERN = 'Regex("\\\\d{4}-\\\\d{2}-\\\\d{2}")'
HINT_DEF = "private fun dailyLogSizes("
PICK_DEF = "private fun pickLargestEligibleDate("

# ── [dv28-A3] ROLLUP CANDIDACY: the CONTENT dimension (dv28-A3) ──────────
# The shared predicate promised to be the one口径 for "is this log worth
# rolling up", but the CONTENT dimension ("does it still hold anything
# distillable") lived only in the executor's picker. A log made entirely of
# in-flight notes (>= 50 KB) was therefore named as the pending one by the
# system prompt on every turn, and every memory_rollup answered
# NOTHING_TO_DISTILL. The criterion below asserts that the transient filter
# lives in exactly ONE place (inside the engine, reachable from the shared
# predicate) and that BOTH sides reach it.
#
# Anchored on the DECISION, not on a spelling: the filter site is derived as
# "a function that COMPARES against TRANSIENT" (not merely mentions it), the
# reachability is derived from the call graph of the engine, and the hint's
# reachability is derived from the call graph of the hint. Renaming the
# classifier, the enum member or the helper does not blind it.
TRANSIENT_TOKEN = "TRANSIENT"
# A *filter* compares against the bucket; the classifier itself merely
# RETURNS it (`return RollupClass.TRANSIENT`) — that is not a filter site.
FILTER_EXPR = re.compile(
    r"(?:==|!=)\s*[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*\."
    + TRANSIENT_TOKEN + r"\b"
)

def strip_kotlin_comments(text):
    """Text with line AND block comments blanked out (newlines preserved).

    KDoc mentioning `RollupClass.TRANSIENT` must not satisfy a criterion that
    asks for a *filter*; blanking comments keeps presence checks about CODE.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(''.join(ch if ch == '\n' else ' ' for ch in text[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out)

def kotlin_fun_map(src):
    """Map function name -> body text (comments blanked) for every `fun` decl."""
    clean = strip_kotlin_comments(src)
    out = {}
    for m in re.finditer(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", clean):
        name = m.group(1)
        brace = clean.find("{", m.end())
        if brace < 0:
            continue
        end = _balanced(clean, brace, "{", "}")
        if end < 0:
            continue
        out.setdefault(name, clean[brace:end])
    return out

def fun_closure(fns, roots):
    """Names reachable from [roots] through calls between [fns] members."""
    seen = set()
    frontier = [r for r in roots if r in fns]
    while frontier:
        name = frontier.pop()
        if name in seen:
            continue
        seen.add(name)
        body = fns[name]
        for m in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", body):
            callee = m.group(1)
            if callee in fns and callee not in seen:
                frontier.append(callee)
    return seen

def filter_sites(fns):
    """Functions whose body COMPARES against the transient bucket."""
    return sorted(name for name, body in fns.items() if FILTER_EXPR.search(body))

def engine_roots_called(body, eng_fns):
    """Engine functions invoked from [body] (qualified or plain calls)."""
    roots = set()
    for m in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", body):
        name = m.group(1)
        if name in eng_fns:
            roots.add(name)
    return roots

# ── [fix/dv18-4 A-07] GROUP RUN-CONFIG RESTORE (Stage 2 same-name merge) ──
# A backup's groups[] entry carries five run-config fields (strategy /
# fallbackStrategy / defaultThinkingLevel / contextLimitTokens /
# lastContextLimitTokens — buildSections writes all of them). The same-name
# merge branch used to decode NONE of them, so a restore onto an install that
# already had the group silently reset the user's routing / thinking-level /
# context-limit choices (Stage 1 restores the instance run-config on a
# same-label merge; Stage 2 did not — the asymmetry was the defect). Two
# routing decisions, anchored on the seam names:
#   G1: the merge branch routes the payload through the shared decode +
#       apply seam, and BOTH restore paths use the shared decode.
#   G2: the decode lives in ONE place (readGroupRunConfig) — no restore path
#       re-decodes the five keys inline — and the seam still decodes all five.
GROUP_SEAM_READ = "readGroupRunConfig("
GROUP_SEAM_APPLY = "applyGroupRunConfig("
GROUP_RUN_KEYS = ("strategy", "fallbackStrategy", "defaultThinkingLevel",
                  "contextLimitTokens", "lastContextLimitTokens")
GROUP_INLINE_READS = tuple('"%s"' % k for k in GROUP_RUN_KEYS)


def code_only(text):
    """Text with line AND block comments blanked — presence checks below must
    not be satisfiable from prose in a comment.

    [wide3-08 C1-C3, parser-death] this used to split on `//` only, so
    `/* if (name == ROLLUP_FILE) return false */` still satisfied
    `ROLLUP_GUARD in code_only(body)` and the guard could be commented away
    while the gate stayed green. `strip_kotlin_comments` (same file, already
    used by the F/C-R rules) blanks both comment kinds and keeps string
    literals intact, so literal-shaped criteria below are unaffected.
    """
    return strip_kotlin_comments(text)


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def _mask(text):
    """Length-preserving blank of string/char CONTENTS plus both comment kinds.

    Structure scans run on masked text, so a `}` inside a literal or a
    `/* } */` comment cannot move a block boundary (wide3-08 parser-death).
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("x" * (j - i))
            i = j
            continue
        if c in ('"', "'"):
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] in (c, "\n"):
                    j += 1
                    break
                j += 1
            out.append("x" * (j - i))
            i = j
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)

def _balanced(src, open_idx, opener="(", closer=")"):
    """Index just past the closer matching the opener at [open_idx] (-1 if none).

    Runs on masked text: a brace/paren inside a string literal or a comment is
    not structure.
    """
    masked = _mask(src)
    depth = 0
    for i in range(open_idx, len(masked)):
        if masked[i] == opener:
            depth += 1
        elif masked[i] == closer:
            depth -= 1
            if depth == 0:
                return i + 1
    return -1

def _match_paren(text, open_idx):
    """Index of the `)` matching the `(` at open_idx (-1 if none)."""
    end = _balanced(text, open_idx, "(", ")")
    return -1 if end < 0 else end - 1

def _split_top(s, op):
    """Split at depth-0 occurrences of `op` (literals/comments masked)."""
    masked = _mask(s)
    parts, depth, start, i = [], 0, 0, 0
    while i < len(masked):
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and masked.startswith(op, i):
            parts.append(s[start:i])
            i += len(op)
            start = i
            continue
        i += 1
    parts.append(s[start:])
    return parts

def _unparen(p):
    p = p.strip()
    while p.startswith("(") and p.endswith(")") and _match_paren(p, 0) == len(p) - 1:
        p = p[1:-1].strip()
    return p

def _lit_value(part):
    """True/False when `part` is a bare boolean literal (optionally negated)."""
    p = _unparen(part)
    neg = False
    while p.startswith("!"):
        neg = not neg
        p = _unparen(p[1:])
    if p == "true":
        v = True
    elif p == "false":
        v = False
    else:
        return None
    return (not v) if neg else v

def _truth(cond):
    """True/False when a condition is DECIDED by a literal, else None.

    `if (false) entryIdMap else emptyMap()` is decided: the map survives only
    in the branch that never runs (wide3-08 R1).
    """
    c = _unparen(cond)
    if _lit_value(c) is not None:
        return _lit_value(c)
    for part in _split_top(c, "||"):
        if _lit_value(part) is True:
            return True
    for part in _split_top(c, "&&"):
        if _lit_value(part) is False:
            return False
    return None

def _split_else(rest):
    """`then` / `else` halves of an `if (...) A else B` expression."""
    masked = _mask(rest)
    depth = 0
    i = 0
    while i < len(masked):
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and re.match(r"\belse\b", masked[i:]):
            return rest[:i], rest[i + 4:]
        i += 1
    return rest, ""

def _split_statements(body):
    """Top-level statements of a block body (`;` or depth-0 newline ends one)."""
    masked = _mask(body)
    parts, depth, start = [], 0, 0
    for i, c in enumerate(masked):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and (c == ";" or c == "\n"):
            parts.append(body[start:i])
            start = i + 1
    parts.append(body[start:])
    return parts

def _arm_end(text, start):
    """Index where the `when`-arm value beginning at `start` ends."""
    masked = _mask(text)
    depth = 0
    i = start
    while i < len(masked):
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif c == "\n" and depth == 0:
            head = text[start:i].strip()
            j = i + 1
            while j < len(masked) and masked[j] in " \t\n":
                j += 1
            nxt = masked[j:j + 4].lstrip()
            if nxt.startswith(".") or nxt.startswith("?:") or nxt.startswith("?") \
                    or (head.startswith("if ") and nxt.startswith("else")):
                i += 1
                continue
            return i
        i += 1
    return len(masked)

def _live_value(expr, depth=0):
    """The value expressions an arm can actually take at runtime.

    Folds `if (<literal-decided>) A else B` onto the taken branch; when the
    condition is a real runtime test both branches stay live. A literal value
    folds to "" (nothing derived). This is the "does the decision still reach
    the map?" judgement for the compact arm (wide3-08 R1).
    """
    if depth > 4:
        return expr
    e = expr.strip().rstrip(",").strip()
    while e.startswith("{") and e.endswith("}") and _balanced(e, 0, "{", "}") == len(e):
        stmts = [s for s in _split_statements(e[1:-1]) if s.strip()]
        if not stmts:
            return ""
        e = stmts[-1].strip()
    m = re.match(r"if\s*\(", e)
    if m:
        mp = _match_paren(e, m.end() - 1)
        if mp < 0:
            return e
        cond = e[m.end():mp]
        then_part, else_part = _split_else(e[mp + 1:])
        tv = _truth(cond)
        if tv is True:
            return _live_value(then_part, depth + 1)
        if tv is False:
            return _live_value(else_part, depth + 1)
        return (_live_value(then_part, depth + 1) + " "
                + _live_value(else_part, depth + 1))
    if _lit_value(e) is not None:
        return ""
    return e

def _arg_value(text, start):
    """Text of the argument value beginning at `start` (ends at a depth-0 `,`/`)`)."""
    masked = _mask(text)
    depth = 0
    i = start
    while i < len(masked):
        c = masked[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return text[start:i]
            depth -= 1
        elif c == "," and depth == 0:
            return text[start:i]
        i += 1
    return None

CHAIN_LINK = re.compile(r"\.\s*(let|run|also|apply|takeIf|takeUnless)\s*\{")

def _drops_remap(rhs):
    """True when a chain link after the remap call throws its result away.

    `remapSessionBindingJson(...).let { null }` keeps the call in the spelling
    and discards the remapped JSON; `.let { s.optString("modelBinding") }` puts
    the un-remapped source uuid back (wide3-08 B). A link that reads `it` (or
    `this` for run/apply) passes the value along and stays legal.
    """
    idx = rhs.find(REMAP_SESSION_CALL)
    if idx < 0:
        return False
    op = rhs.find("(", idx)
    cp = _match_paren(rhs, op) if op >= 0 else -1
    if cp < 0:
        return True
    tail = rhs[cp + 1:]
    m = CHAIN_LINK.search(tail)
    if not m:
        return False
    link = m.group(1)
    brace = tail.find("{", m.end() - 1)
    end = _balanced(tail, brace, "{", "}") if brace >= 0 else -1
    if end < 0:
        return True
    body = _mask(tail[brace + 1:end - 1])
    if link in ("run", "apply"):
        return not re.search(r"\bthis\b", body)
    pm = re.match(r"\s*([A-Za-z_]\w*)\s*->", body)
    if pm:
        return not re.search(r"\b%s\b" % re.escape(pm.group(1)), body[pm.end():])
    return not re.search(r"\bit\b", body)


def function_text(src, header):
    """Text of the declaration starting at [header], up to its closing brace."""
    i = src.find(header)
    if i < 0:
        return None
    brace = src.find("{", i)
    if brace < 0:
        return None
    end = _balanced(src, brace, "{", "}")
    return None if end < 0 else src[i:end]


def check_remap_defaults(src):
    """A: remapDefaultsIds' `when (path)` must route runtime.compactModelEntryId."""
    body = function_text(src, "fun remapDefaultsIds(")
    if body is None:
        return [(CFG_REL, 0, "remapDefaultsIds( not found — gate ROT")]
    when = body.find("when (path)")
    if when < 0:
        return [(CFG_REL, 0, "remapDefaultsIds no longer branches on `path` — gate ROT")]
    end = _balanced(body, body.find("{", when), "{", "}")
    branch = body[when:end - 1 if end > 0 else len(body)]
    routed = False
    seen_value = None
    for m in re.finditer(re.escape(COMPACT_PATH), branch):
        arrow = branch.find("->", m.end())
        if arrow < 0 or "\n" in branch[m.end():arrow]:
            # not a pattern position (a mention inside some other arm's value)
            continue
        value = branch[arrow + 2:_arm_end(branch, arrow + 2)]
        seen_value = value
        if "entryIdMap" in _live_value(value):
            routed = True
            break
    if not routed:
        if seen_value is None:
            msg = ("remapDefaultsIds does not route " + COMPACT_PATH + " to entryIdMap"
                   " — a restored install silently loses its pinned compaction model")
        else:
            msg = ("remapDefaultsIds routes " + COMPACT_PATH + " through `"
                   + " ".join(seen_value.split())[:70] + "`, whose LIVE value is not "
                   "entryIdMap — the map survives in the spelling only (a dead branch "
                   "or a constant fold never hands it over)")
        return [(CFG_REL, line_of(src, src.find("fun remapDefaultsIds(")), msg)]
    return []


def check_import_chat_sections(src):
    """B: the session binding write must route through remapSessionBindingJson."""
    body = function_text(src, "internal suspend fun importChatSections(")
    if body is None:
        return [(CFG_REL, 0, "importChatSections( not found — gate ROT")]
    code = code_only(body)
    if REMAP_SESSION_CALL not in code:
        return [(
            CFG_REL,
            line_of(src, src.find("internal suspend fun importChatSections(")),
            "importChatSections writes modelBinding without " + REMAP_SESSION_CALL +
            " — restored sessions keep the source install's uuids",
        )]
    if re.search(r"modelBinding\s*=\s*s\.optString\(", code):
        return [(
            CFG_REL,
            line_of(src, src.find("internal suspend fun importChatSections(")),
            "modelBinding still written straight from the payload (s.optString) — "
            "the remap call is bypassed",
        )]
    # The call site must actually hand over the maps it was given.
    call = code[code.find(REMAP_SESSION_CALL):]
    call = call[: _balanced(call, call.find("(")) if "(" in call else len(call)]
    for arg in ("groupIdMap", "entryIdMap"):
        if arg not in call:
            return [(
                CFG_REL,
                line_of(src, src.find("internal suspend fun importChatSections(")),
                "importChatSections calls " + REMAP_SESSION_CALL + " without " + arg,
            )]
    # [wide3-08 B] ... and the value handed to modelBinding must BE the result.
    for m in re.finditer(r"modelBinding\s*=", code):
        rhs = _arg_value(code, m.end())
        if rhs is None:
            return [(
                CFG_REL,
                line_of(src, src.find("internal suspend fun importChatSections(")),
                "modelBinding assignment cannot be parsed — fail-closed",
            )]
        if _drops_remap(rhs):
            return [(
                CFG_REL,
                line_of(src, src.find("internal suspend fun importChatSections(")),
                "importChatSections keeps the " + REMAP_SESSION_CALL + " call but a "
                "chain link after it does not use the result (`.let { null }` / "
                "`.let { s.optString(…) }`) — the restored session keeps the source "
                "install's uuids",
            )]
    return []


def check_memory_pack(src):
    """C1: the backup's memory section must pack the rollup as well."""
    body = function_text(src, "fun buildSections(")
    if body is None:
        return [(CFG_REL, 0, "buildSections( not found — gate ROT")]
    if MEM_PACK_CALL not in code_only(body):
        return [(
            CFG_REL,
            line_of(src, src.find("fun buildSections(")),
            "buildSections does not use " + MEM_PACK_CALL +
            " — memoryFiles falls back to listAllFiles() and drops the rollup",
        )]
    fn = function_text(src, "fun memoryBackupEntries(")
    if fn is None:
        return [(CFG_REL, 0, "memoryBackupEntries( not found — gate ROT")]
    if "ROLLUP_BACKUP_FILE" not in code_only(fn):
        return [(
            CFG_REL,
            line_of(src, src.find("fun memoryBackupEntries(")),
            "memoryBackupEntries does not carry ROLLUP_BACKUP_FILE",
        )]
    const = re.search(r"const val ROLLUP_BACKUP_FILE: String =\s*\n?\s*([^\n]+)", src)
    if const is None or ROLLUP_ARG not in const.group(1):
        return [(
            CFG_REL,
            line_of(src, src.find("const val ROLLUP_BACKUP_FILE")) if "const val ROLLUP_BACKUP_FILE" in src else 0,
            "ROLLUP_BACKUP_FILE is not sourced from " + ROLLUP_ARG +
            " (single source of truth for the on-disk name)",
        )]
    return []


def check_restore_writeback(src):
    """C2: Stage 6 must write payload entries by NAME (a whitelist drops rollup)."""
    body = function_text(src, "suspend fun import(")
    if body is None:
        return [(CFG_REL, 0, "import( not found — gate ROT")]
    if SAVE_BY_NAME not in code_only(body):
        return [(
            CFG_REL,
            line_of(src, src.find("suspend fun import(")),
            "memory restore no longer writes payload entries by name (" + SAVE_BY_NAME +
            ") — a rollup entry in the payload would be dropped",
        )]
    return []


def check_delete_guard(src):
    """C3: deleteFile must refuse the rollup product file."""
    body = function_text(src, "fun deleteFile(name: String)")
    if body is None:
        return [(MEM_REL, 0, "deleteFile( not found — gate ROT")]
    if ROLLUP_GUARD not in code_only(body):
        return [(
            MEM_REL,
            line_of(src, src.find("fun deleteFile(name: String)")),
            "deleteFile has no " + ROLLUP_GUARD + " guard — the distilled memory "
            "layer can be deleted (and is lost on the next backup/restore)",
        )]
    return []


def check_voice_group_remap(src):
    """D2: remapDefaultsIds must route both voice group paths to groupIdMap."""
    body = function_text(src, "fun remapDefaultsIds(")
    if body is None:
        return [(CFG_REL, 0, "remapDefaultsIds( not found — gate ROT")]
    when = body.find("when (path)")
    if when < 0:
        return [(CFG_REL, 0, "remapDefaultsIds no longer branches on `path` — gate ROT")]
    end = _balanced(body, body.find("{", when), "{", "}")
    branch = body[when:end if end > 0 else len(body)]
    out = []
    for path in VOICE_GROUP_PATHS:
        routed = False
        for line in branch.split("\n"):
            code = line.split("//")[0]
            if ('"%s"' % path) in code and "->" in code:
                if "groupIdMap" in code.split("->", 1)[1]:
                    routed = True
        if not routed:
            out.append((
                CFG_REL,
                line_of(src, src.find("fun remapDefaultsIds(")),
                "remapDefaultsIds does not route \"%s\" to groupIdMap — a restored "
                "install keeps the source install's group id and the voice binding dangles" % path,
            ))
    return out


def check_voice_defaults_registered(src_builtins):
    """D1: registerDefaults must register both voice group bindings, reading
    and writing the repository properties (a registered field that does not
    touch the repo would export "" forever)."""
    body = function_text(src_builtins, "fun registerDefaults(")
    if body is None:
        return [(BUILTINS_REL, 0, "registerDefaults( not found — gate ROT")]
    code = code_only(body)
    base = line_of(src_builtins, src_builtins.find("fun registerDefaults("))
    out = []
    for path, reader in zip(VOICE_GROUP_PATHS, VOICE_GROUP_READERS):
        if ('path = "%s"' % path) not in code:
            out.append((
                BUILTINS_REL,
                base,
                "\"%s\" is not registered in ConfigBuiltins.registerDefaults — "
                "the backup `fields` section cannot carry it and a restore drops "
                "the voice binding" % path,
            ))
        elif reader not in code:
            out.append((
                BUILTINS_REL,
                base,
                "\"%s\" is registered but its reader does not read %s — the field "
                "exports a constant instead of the live binding" % (path, reader.split(" ?:")[0].strip()),
            ))
    return out


def _provider_key_assignment(src):
    """The `providerKeyToId[…] = instId` Stage-1 assignment text (code only)."""
    body = function_text(src, "    suspend fun import(")
    if body is None:
        return None
    i = body.find(STAGE1_KEY_CALL)
    if i < 0:
        return None
    j = body.find(STAGE1_KEY_END, i)
    if j < 0:
        return None
    return code_only(body[i:j])


def check_thinking_rule_key(src):
    """E: Stage 1 must key by the BACKUP's original label (Stage 1.5 looks the
    (providerType, providerLabel) pair up — never the rename result), and the
    Stage 1.5 lookup must keep the same key shape."""
    body = function_text(src, "    suspend fun import(")
    if body is None:
        return [(CFG_REL, 0, "import( not found — gate ROT")]
    base = line_of(src, src.find("    suspend fun import("))
    key = _provider_key_assignment(src)
    if key is None:
        return [(CFG_REL, base,
                 "Stage 1 no longer registers " + STAGE1_KEY_CALL + "…" + STAGE1_KEY_END +
                 " — thinking rules can never re-attach to a restored provider")]
    if "resolvedLabel" in key:
        return [(CFG_REL, base,
                 "Stage 1 keys the thinking-rule lookup by the auto-renamed label "
                 "(resolvedLabel) — Stage 1.5 looks up the backup's original "
                 "(providerType, providerLabel) pair, so a label-conflict import "
                 "silently loses its whole thinking-rules group")]
    if "+ label" not in key:
        return [(CFG_REL, base,
                 "Stage 1 provider key no longer uses the backup's original label")]
    if STAGE15_LOOKUP not in code_only(body):
        return [(CFG_REL, base,
                 "Stage 1.5 no longer looks up " + STAGE15_LOOKUP +
                 " — the two sides of the (providerType, providerLabel) contract diverged")]
    return []


def check_rollup_candidate_single_source(mem, eng, runner):
    """F: the rollup candidacy口径 must have ONE definition.

    [dv15-A4] The prompt-side size hint and the executor used to spell the
    date shape and the "already distilled" cutoff themselves; the hint's copy
    had lost both, so an already-distilled log stayed "the largest daily log"
    and the agent was told to roll it up every turn. Anchored on the decision,
    not on a spelling: the hint must route through the shared predicate, the
    predicate must still decide date shape + today boundary + already
    distilled, and the executor must not carry its own copy of the pattern.
    """
    out = []

    # 1. the shared predicate exists and keeps all three decisions.
    pred = function_text(eng, CANDIDATE_DEF)
    past = function_text(eng, PAST_LOG_DEF)
    if pred is None or past is None:
        out.append((ENGINE_REL, 0,
                    "isRollupCandidate / isPastDailyLog not found - gate ROT"))
    else:
        if CANDIDATE_DEF not in eng:
            out.append((ENGINE_REL, 0, CANDIDATE_DEF + " not found - gate ROT"))
        body = code_only(pred)
        if PAST_LOG_CALL not in body or "hasRollupForDate(" not in body:
            out.append((ENGINE_REL, line_of(eng, eng.find(CANDIDATE_DEF)),
                        "the shared candidate predicate no longer decides the date "
                        "shape and the already-distilled cutoff together - the hint "
                        "and the executor can diverge again"))
        shape = code_only(past)
        lost = []
        if "DATE_FILE_PATTERN" not in shape:
            lost.append("date shape")
        if not re.search(r">=\s*todayStr", shape):
            lost.append("today/future boundary")
        if DATE_PATTERN not in eng:
            lost.append("single date-pattern definition")
        if lost:
            out.append((ENGINE_REL, line_of(eng, eng.find(PAST_LOG_DEF)),
                        "the past-log shape test lost: " + ", ".join(lost)))

    # 2. the prompt-side hint routes through the shared predicate.
    hint = function_text(mem, HINT_DEF)
    if hint is None:
        out.append((MEM_REL, 0, "dailyLogSizes( not found - gate ROT"))
    elif HINT_CALL not in code_only(hint):
        out.append((MEM_REL, line_of(mem, mem.find(HINT_DEF)),
                    "dailyLogSizes no longer judges candidacy with " + HINT_CALL +
                    " - the system prompt can name a log the executor answers with "
                    "SKIPPED_ALREADY"))

    # 3. the executor shares it and does not re-spell the date pattern.
    pick = function_text(runner, PICK_DEF)
    if pick is None:
        out.append((RUNNER_REL, 0, "pickLargestEligibleDate( not found - gate ROT"))
    elif EXEC_CALL not in code_only(pick):
        out.append((RUNNER_REL, line_of(runner, runner.find(PICK_DEF)),
                    "pickLargestEligibleDate no longer uses " + EXEC_CALL +
                    " - the executor's candidacy口径 is independent of the hint again"))
    if DATE_PATTERN in code_only(runner):
        out.append((RUNNER_REL, line_of(runner, runner.find(DATE_PATTERN)),
                    "the executor re-spells the date pattern (" + DATE_PATTERN +
                    ") instead of sourcing it from MemoryRollupEngine - the two "
                    "copies can drift apart again"))
    return out


def filter_occurrences(src):
    """(line_number, that_line) for each transient-bucket COMPARISON in code.

    [strip_kotlin_comments] blanks comments with spaces of equal length, so an
    index found in the cleaned text addresses the same character in [src] —
    which is what lets us report a real line number and read a marker on it.
    """
    clean = strip_kotlin_comments(src)
    lines = src.split("\n")
    out = []
    for m in FILTER_EXPR.finditer(clean):
        ln = line_of(src, m.start())
        out.append((ln, lines[ln - 1] if ln - 1 < len(lines) else ""))
    return out


def check_rollup_content_dimension(mem, eng, runner):
    """[dv28-A3] The transient ("nothing distillable") dimension must live in
    the SINGLE shared decision, and BOTH sides must reach it.

    Pre-fix, `isRollupCandidate` decided only date shape + not-yet-distilled
    while the executor's picker additionally required a non-transient entry. A
    pure-transient daily log (>= 50 KB) was then named by the system prompt on
    every turn, and every memory_rollup answered NOTHING_TO_DISTILL.

    Derived, not spelled: a *filter site* is a function that COMPARES against
    TRANSIENT (the classifier merely RETURNS it, so it is not a filter site);
    reachability comes from the engine's call graph; the hint's reachability
    comes from the engine functions its body calls.
    """
    out = []
    eng_fns = kotlin_fun_map(eng)
    mem_fns = kotlin_fun_map(mem)
    runner_fns = kotlin_fun_map(runner)

    # C-R1 (ONE violation, so the PRE arm points at exactly one place): the
    # content dimension must live in the shared predicate's closure and neither
    # side may carry its own copy of it. Two reasons can feed it — a side-local
    # copy (most precise location) and a predicate that cannot reach any filter.
    # The escape hatch for a deliberate unreachable defence is a
    # `// unreachable-defense:` marker on the offending line.
    pred_closure = fun_closure(eng_fns, ["isRollupCandidate"])
    eng_sites = filter_sites(eng_fns)
    pred_reaches = [s for s in eng_sites if s in pred_closure]
    c1_rel, c1_line, c1_reasons = None, 0, []
    for src, rel in ((mem, MEM_REL), (runner, RUNNER_REL)):
        stray = [(ln, ln_text) for ln, ln_text in filter_occurrences(src)
                 if "unreachable-defense:" not in ln_text]
        if stray:
            if c1_rel is None:
                c1_rel, c1_line = rel, stray[0][0]
            c1_reasons.append(
                rel + " re-implements the transient filter (%d site(s), first at "
                "line %d)" % (len(stray), stray[0][0]))
    if not pred_reaches:
        if c1_rel is None:
            c1_rel = ENGINE_REL
            c1_line = (line_of(eng, eng.find(CANDIDATE_DEF))
                       if CANDIDATE_DEF in eng else 0)
        c1_reasons.append(
            "the shared candidate predicate cannot reach any transient filter"
            + (" (engine filter sites: " + ", ".join(eng_sites) + ")" if eng_sites else ""))
    if c1_reasons:
        out.append((c1_rel, c1_line,
                    "; ".join(c1_reasons) +
                    " — 'is there anything distillable' is not part of the ONE "
                    "candidacy decision, so the prompt side and the executor can "
                    "judge a log differently again"))

    # C-R2: the prompt-side hint must reach the content dimension.
    hint = function_text(mem, HINT_DEF)
    if hint is None:
        out.append((MEM_REL, 0, "dailyLogSizes( not found - gate ROT"))
        return out
    hint_roots = set()
    for m in re.finditer(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(", strip_kotlin_comments(hint)):
        if m.group(1) in eng_fns:
            hint_roots.add(m.group(1))
    reached = fun_closure(eng_fns, hint_roots) if hint_roots else set()
    if not [s for s in eng_sites if s in reached]:
        out.append((MEM_REL, line_of(mem, mem.find(HINT_DEF)),
                    "dailyLogSizes cannot reach the transient filter - the system "
                    "prompt can still name a log the executor would answer with "
                    "NOTHING_TO_DISTILL"))
    return out


def _import_body(src):
    body = function_text(src, "    suspend fun import(")
    if body is None:
        return None, line_of(src, src.find("    suspend fun import("))
    return body, line_of(src, src.find("    suspend fun import("))


def _merge_branch(src_import_body):
    """The same-name merge branch (`if (existingGroup != null) { … }`)."""
    i = src_import_body.find("if (existingGroup != null)")
    if i < 0:
        return None
    brace = src_import_body.find("{", i)
    if brace < 0:
        return None
    end = _balanced(src_import_body, brace, "{", "}")
    return None if end < 0 else src_import_body[i:end]


def check_group_run_config_merge(src):
    """G1: the same-name merge branch must restore the payload's run-config
    through the shared seam, and BOTH restore paths must use the shared
    decode (create + merge)."""
    body, base = _import_body(src)
    if body is None:
        return [(CFG_REL, base, "import( not found — gate ROT")]
    merge = _merge_branch(body)
    if merge is None:
        return [(CFG_REL, base, "same-name merge branch (if (existingGroup != null)) not found — gate ROT")]
    out = []
    code = code_only(merge)
    if GROUP_SEAM_READ not in code:
        out.append((
            CFG_REL, base,
            "the same-name merge branch does not decode the payload's run-config (" +
            GROUP_SEAM_READ + ") — a restore onto an existing group silently drops "
            "strategy / fallbackStrategy / defaultThinkingLevel / contextLimitTokens",
        ))
    if GROUP_SEAM_APPLY not in code:
        out.append((
            CFG_REL, base,
            "the same-name merge branch does not apply the decoded run-config (" +
            GROUP_SEAM_APPLY + ") — the merge keeps only the member union and resets "
            "the group's run-config to whatever the local group had",
        ))
    if "updateGroup(" not in code:
        out.append((CFG_REL, base, "merge branch no longer writes via updateGroup( — gate ROT"))
    if code_only(body).count(GROUP_SEAM_READ) < 2:
        out.append((
            CFG_REL, base,
            "the create path stopped using the shared group run-config decode (" +
            GROUP_SEAM_READ + ") — a second inline decode can drift from the merge "
            "path again",
        ))
    return out


def check_group_run_config_single_decode(src):
    """G2: the five run-config keys are decoded in exactly one place — the
    readGroupRunConfig seam — and the seam still reads all five keys."""
    body, base = _import_body(src)
    if body is None:
        return [(CFG_REL, base, "import( not found — gate ROT")]
    out = []
    code = code_only(body)
    for token in GROUP_INLINE_READS:
        for fn in ("optString", "optInt", "has", "put"):
            needle = fn + "(" + token
            if needle in code:
                out.append((
                    CFG_REL,
                    line_of(src, src.find("    suspend fun import(")) if needle in src else base,
                    "the import path re-decodes a group run-config key inline (" + needle +
                    ") instead of going through " + GROUP_SEAM_READ +
                    " — two decodes for the same payload can drift apart",
                ))
    seam = function_text(src, "internal fun readGroupRunConfig(")
    if seam is None:
        out.append((CFG_REL, 0, "readGroupRunConfig( not found — gate ROT"))
    else:
        seam_code = code_only(seam)
        for token in GROUP_INLINE_READS:
            if token not in seam_code:
                out.append((
                    CFG_REL,
                    line_of(src, src.find("internal fun readGroupRunConfig(")),
                    "the shared group run-config decode no longer reads " + token +
                    " — the field would be silently dropped on every restore",
                ))
    return out


def check(root, raw_cfg=None, raw_mem=None, raw_builtins=None, raw_engine=None,
          raw_runner=None):
    if raw_cfg is None:
        with open(os.path.join(root, CFG_REL), encoding="utf-8") as f:
            raw_cfg = f.read()
    if raw_mem is None:
        with open(os.path.join(root, MEM_REL), encoding="utf-8") as f:
            raw_mem = f.read()
    if raw_builtins is None:
        with open(os.path.join(root, BUILTINS_REL), encoding="utf-8") as f:
            raw_builtins = f.read()
    if raw_engine is None:
        with open(os.path.join(root, ENGINE_REL), encoding="utf-8") as f:
            raw_engine = f.read()
    if raw_runner is None:
        with open(os.path.join(root, RUNNER_REL), encoding="utf-8") as f:
            raw_runner = f.read()
    out = []
    out += check_remap_defaults(raw_cfg)
    out += check_voice_group_remap(raw_cfg)
    out += check_voice_defaults_registered(raw_builtins)
    out += check_thinking_rule_key(raw_cfg)
    out += check_import_chat_sections(raw_cfg)
    out += check_memory_pack(raw_cfg)
    out += check_restore_writeback(raw_cfg)
    out += check_delete_guard(raw_mem)
    out += check_rollup_candidate_single_source(raw_mem, raw_engine, raw_runner)
    out += check_rollup_content_dimension(raw_mem, raw_engine, raw_runner)
    out += check_group_run_config_merge(raw_cfg)
    out += check_group_run_config_single_decode(raw_cfg)
    return out


def selftest(root):
    """Each criterion must go RED on its own targeted mutation of the real tree.

    Fixtures are derived from the repository sources (never hand-written), so
    the self-test cannot pass on a tree whose shape the gate no longer matches.
    """
    with open(os.path.join(root, CFG_REL), encoding="utf-8") as f:
        cfg = f.read()
    with open(os.path.join(root, MEM_REL), encoding="utf-8") as f:
        mem = f.read()
    with open(os.path.join(root, BUILTINS_REL), encoding="utf-8") as f:
        bui = f.read()
    with open(os.path.join(root, ENGINE_REL), encoding="utf-8") as f:
        eng = f.read()
    with open(os.path.join(root, RUNNER_REL), encoding="utf-8") as f:
        runner = f.read()

    def on_cfg(fn):
        return lambda c, m, b, e, r, _fn=fn: _fn(c)

    def on_mem(fn):
        return lambda c, m, b, e, r, _fn=fn: _fn(m)

    def on_bui(fn):
        return lambda c, m, b, e, r, _fn=fn: _fn(b)

    def on_engine(fn):
        return lambda c, m, b, e, r, _fn=fn: _fn(e)

    def on_runner(fn):
        return lambda c, m, b, e, r, _fn=fn: _fn(r)

    def on_rollup(fn):
        return lambda c, m, b, e, r, _fn=fn: _fn(m, e, r)

    def rollup_all(m, e, r):
        """Both rollup criteria — an arm on one of them must not report the
        other's violations as collateral."""
        return (check_rollup_candidate_single_source(m, e, r)
                + check_rollup_content_dimension(m, e, r))

    arms = []

    def arm(name, crit, cfg=cfg, mem=mem, bui=bui, eng=eng, runner=runner):
        arms.append((name, crit, cfg, mem, bui, eng, runner))

    def sub_once(text, old, new):
        assert text.count(old) >= 1, "fixture anchor missing: " + old
        return text.replace(old, new, 1)

    # 0. pristine must be green.
    arm("pristine", None)

    # 1. drop the compact path branch.
    arm(
        "M1 compact branch removed",
        on_cfg(check_remap_defaults),
        cfg=sub_once(cfg, '"defaults.agentLoopEntries", "runtime.compactModelEntryId" -> entryIdMap',
                     '"defaults.agentLoopEntries" -> entryIdMap'),
    )
    # 2. compact path present but only in a comment.
    arm(
        "M2 compact path demoted to comment",
        on_cfg(check_remap_defaults),
        cfg=sub_once(cfg, '"defaults.agentLoopEntries", "runtime.compactModelEntryId" -> entryIdMap',
                     '"defaults.agentLoopEntries" -> entryIdMap // runtime.compactModelEntryId'),
    )
    # 2b. compact path routed to the WRONG map.
    arm(
        "M2b compact routed to groupIdMap",
        on_cfg(check_remap_defaults),
        cfg=sub_once(cfg, '"defaults.agentLoopEntries", "runtime.compactModelEntryId" -> entryIdMap',
                     '"defaults.agentLoopEntries" -> entryIdMap\n            "runtime.compactModelEntryId" -> groupIdMap'),
    )
    # 3. binding written verbatim again.
    arm(
        "M3 binding not remapped",
        on_cfg(check_import_chat_sections),
        cfg=sub_once(
            cfg,
            """modelBinding = remapSessionBindingJson(
                        s.optString("modelBinding").ifEmpty { null },
                        groupIdMap = groupIdMap,
                        entryIdMap = entryIdMap,
                    ),""",
            'modelBinding = s.optString("modelBinding").ifEmpty { null },',
        ),
    )
    # 4. maps not handed over at the call site.
    arm(
        "M4 maps dropped from call site",
        on_cfg(check_import_chat_sections),
        cfg=sub_once(cfg, """                        groupIdMap = groupIdMap,
                        entryIdMap = entryIdMap,
""", ""),
    )
    # 5. memory packing falls back to the plain listing.
    arm(
        "M5 memory pack loses rollup",
        on_cfg(check_memory_pack),
        cfg=sub_once(cfg, "val entries = memoryBackupEntries(", "val entries = listAllFilesOnly("),
    )
    # 6. rollup name re-spelled instead of sourced from the engine const.
    arm(
        "M6 rollup name re-spelled",
        on_cfg(check_memory_pack),
        cfg=sub_once(cfg, ROLLUP_ARG, '"MEMORY-ROLLUP.md"'),
    )
    # 7. restore write-back replaced by a name whitelist.
    arm(
        "M7 restore whitelist drops rollup",
        on_cfg(check_restore_writeback),
        cfg=sub_once(cfg, SAVE_BY_NAME, 'memoryRepo.saveFile("GLOBAL.md", content)'),
    )
    # 8. rollup delete guard removed.
    arm(
        "M8 delete guard removed",
        on_mem(check_delete_guard),
        mem=sub_once(mem, "        " + ROLLUP_GUARD + "\n", ""),
    )

    # ── [da1031-A] arms 9-13 ─────────────────────────────────────────────
    # 9. voice group binding dropped from remapDefaultsIds' group branch.
    arm(
        "M9 voiceInputGroup not routed",
        on_cfg(check_voice_group_remap),
        cfg=sub_once(cfg, '"defaults.voiceInputGroup", ', ""),
    )
    # 9b. routed to the WRONG map.
    arm(
        "M9b voiceOutputGroup routed to entryIdMap",
        on_cfg(check_voice_group_remap),
        cfg=sub_once(cfg,
                     '"defaults.voiceOutputGroup", "defaults.agentLoopGroups" -> groupIdMap',
                     '"defaults.agentLoopGroups" -> groupIdMap\n        "defaults.voiceOutputGroup" -> entryIdMap'),
    )
    # 10. registration dropped (path re-spelled → resolveField/exportJSON miss).
    arm(
        "M10 voiceOutputGroup unregistered",
        on_bui(check_voice_defaults_registered),
        bui=sub_once(bui, 'path = "defaults.voiceOutputGroup",', 'path = "defaults.voiceOutputGroupUnused",'),
    )
    # 10b. registered but reading a constant instead of the repo property.
    arm(
        "M10b voiceInputGroup reader detached",
        on_bui(check_voice_defaults_registered),
        bui=sub_once(bui, 'reader = { ConfigValue.Str(repo.voiceInputGroupId ?: "") },',
                     'reader = { ConfigValue.Str("") },'),
    )
    # 11. Stage 1 keyed by the auto-renamed label again (the ISSUE-03-B defect).
    arm(
        "M11 stage1 keyed by resolvedLabel",
        on_cfg(check_thinking_rule_key),
        cfg=sub_once(cfg, 'obj.optString("providerType", "") + "\\u0000" + label',
                     'obj.optString("providerType", "") + "\\u0000" + resolvedLabel'),
    )
    # 11b. Stage 1.5 lookup diverged from the Stage 1 key.
    arm(
        "M11b stage15 lookup diverged",
        on_cfg(check_thinking_rule_key),
        cfg=sub_once(cfg, 'providerKeyToId["$type\\u0000$label"]',
                     'providerKeyToId["$type\\u0000" + resolvedLabelFor(label)]'),
    )
    # 11c. Stage 1 key registration removed entirely.
    arm(
        "M11c stage1 registration removed",
        on_cfg(check_thinking_rule_key),
        cfg=sub_once(cfg, STAGE1_KEY_CALL, "providerKeyMap["),
    )

    # ── [dv15-A4] arms 12-16: rollup candidacy single source ─────────────
    # 12. the prompt-side hint goes back to filtering literal file names
    #     (the pre-fix shape: GLOBAL.md / MEMORY-ROLLUP.md / SOUL.md only).
    arm(
        "M12 hint drops the shared candidate predicate",
        on_rollup(rollup_all),
        mem=sub_once(mem, HINT_CALL, "isDailyLogForRollupLegacy("),
    )
    # 13. the shared predicate loses the already-distilled cutoff.
    arm(
        "M13 candidate predicate ignores the rollup file",
        on_rollup(rollup_all),
        eng=sub_once(eng, "hasRollupForDate(rollupContent,", "rollupIgnored("),
    )
    # 14. the past-log shape test loses the today/future boundary.
    arm(
        "M14 today boundary dropped",
        on_rollup(rollup_all),
        eng=sub_once(eng, "if (datePart >= todayStr) return false",
                     'if (datePart >= "9999-99-99") return false'),
    )
    # 15. the executor re-spells the date pattern instead of sourcing it.
    arm(
        "M15 executor re-spells the date pattern",
        on_rollup(rollup_all),
        runner=sub_once(
            runner,
            'private const val TAG = "MemoryRollupRunner"',
            'private const val TAG = "MemoryRollupRunner"\n'
            '        private val DATE_FILE_PATTERN = Regex("\\\\d{4}-\\\\d{2}-\\\\d{2}")',
        ),
    )
    # 16. the executor's picker bypasses the shared predicate.
    arm(
        "M16 executor picker bypasses the shared predicate",
        on_rollup(rollup_all),
        runner=sub_once(
            runner,
            "MemoryRollupEngine.isRollupCandidate(",
            "isDailyLogLegacy(",
        ),
    )

    # ── [fix/dv18-4 A-07] arms 17-20: group run-config restore ──────────
    # 17. the merge branch stops applying the decoded run-config (the A-07
    #     defect shape: members-only merge). The decode call is still there,
    #     so exactly the apply criterion + the two-callsite count fire.
    arm(
        "M17 merge stops applying run-config",
        on_cfg(check_group_run_config_merge),
        cfg=sub_once(
            cfg,
            """                    val merged = applyGroupRunConfig(
                        existingGroup,
                        readGroupRunConfig(g, warnGroupRunConfig),
                    ).copy(memberEntryIds = mergedMembers)""",
            "                    val merged = existingGroup.copy(memberEntryIds = mergedMembers)",
        ),
    )
    # 18. the create path stops using the shared decode (a second, private
    #     decode can drift from the merge path again).
    arm(
        "M18 create path stops using the shared decode",
        on_cfg(check_group_run_config_merge),
        cfg=sub_once(
            cfg,
            "val runConfig = readGroupRunConfig(g, warnGroupRunConfig)",
            "val runConfig = readGroupRunConfigLegacy(g, warnGroupRunConfig)",
        ),
    )
    # 19. a run-config key is re-decoded inline in the import path (the two
    #     decodes can drift apart again).
    arm(
        "M19 strategy re-decoded inline",
        on_cfg(check_group_run_config_single_decode),
        cfg=sub_once(
            cfg,
            "strategy = runConfig.strategy.value,",
            'strategy = enumOrDefault(g.optString("strategy"), RoutingStrategy.fallback),',
        ),
    )
    # 20. the shared decode loses a key (ROT).
    arm(
        "M20 seam stops reading fallbackStrategy",
        on_cfg(check_group_run_config_single_decode),
        cfg=sub_once(
            cfg,
            """        fallbackStrategy = BackupField(
            present = g.has("fallbackStrategy"),
            value = enumOrDefault(g.optString("fallbackStrategy"), FallbackStrategy.default),
        ),""",
            "        fallbackStrategy = BackupField(present = false, value = FallbackStrategy.default),",
        ),
    )

    # ── [dv28-A3] arms 21-23: the transient ("nothing distillable") dimension
    #    must live in the ONE candidacy decision, reached by BOTH sides.
    # 21. the shape the defect had: the shared predicate judges only date
    #     shape + not-yet-distilled, and the executor carries its own filter.
    #     ⇒ C-R1 (two reasons: side copy + predicate cannot reach) and C-R2.
    arm(
        "M21 content dimension removed from the shared predicate",
        on_rollup(rollup_all),
        eng=sub_once(
            eng,
            "        entries.filter { classify(it) != RollupClass.TRANSIENT }",
            "        entries",
        ),
        runner=sub_once(
            runner,
            "                f to f.name.removeSuffix(\".md\")",
            "                val legacy = MemoryRollupEngine.extractEntries(\"\").none {\n"
            "                    MemoryRollupEngine.classify(it) !=\n"
            "                        MemoryRollupEngine.RollupClass.TRANSIENT\n"
            "                }\n"
            "                f to f.name.removeSuffix(\".md\")",
        ),
    )
    # 22. the filter is pushed BACK into the executor's body while the engine
    #     keeps its own copy ⇒ C-R1 only (a second label of the same decision;
    #     the hint still reaches the engine's copy, so C-R2 stays green).
    arm(
        "M22 transient filter duplicated in the executor",
        on_rollup(rollup_all),
        runner=sub_once(
            runner,
            "                f to f.name.removeSuffix(\".md\")",
            "                val legacy = MemoryRollupEngine.extractEntries(\"\").none {\n"
            "                    MemoryRollupEngine.classify(it) !=\n"
            "                        MemoryRollupEngine.RollupClass.TRANSIENT\n"
            "                }\n"
            "                f to f.name.removeSuffix(\".md\")",
        ),
    )
    # 23. the hint grows its own transient filter next to the shared predicate
    #     ⇒ C-R1 (a side-local copy of the decision; the reachability itself is
    #     untouched, so C-R2 stays green — the two criteria bite separately).
    arm(
        "M23 hint grows its own transient filter",
        on_rollup(rollup_all),
        mem=sub_once(
            mem,
            "        return memoryDir.listFiles()",
            "        // gate fixture: a second copy of the transient decision\n"
            "        val _legacy = com.rikkaminis.app.workspace.MemoryRollupEngine\n"
            "            .extractEntries(\"\").none { e2 ->\n"
            "                com.rikkaminis.app.workspace.MemoryRollupEngine.classify(e2) !=\n"
            "                    com.rikkaminis.app.workspace.MemoryRollupEngine.RollupClass.TRANSIENT\n"
            "            }\n"
            "        return memoryDir.listFiles()",
        ),
    )

    # ── [wide3-08] witnesses re-attacked from the external audit ─────────────
    # 24. the compact arm still spells `entryIdMap` but folds it into a dead
    #     branch: the value handed over is the empty map (axis=dataflow).
    arm(
        "M24 compact arm routed through a dead branch",
        on_cfg(check_remap_defaults),
        cfg=sub_once(
            cfg,
            '"defaults.agentLoopEntries", "runtime.compactModelEntryId" -> entryIdMap',
            '"defaults.agentLoopEntries", "runtime.compactModelEntryId" -> '
            'if (false) entryIdMap else emptyMap()',
        ),
    )
    # 25. the remap call survives, its result is thrown away: `.let { null }`
    #     after the call (axis=dataflow).
    arm(
        "M25 modelBinding drops the remap result",
        on_cfg(check_import_chat_sections),
        cfg=sub_once(
            cfg,
            """                        entryIdMap = entryIdMap,
                    ),
                    source = s.optString("source").ifEmpty { null },""",
            """                        entryIdMap = entryIdMap,
                    ).let { null },
                    source = s.optString("source").ifEmpty { null },""",
        ),
    )
    # 26. the rollup delete guard survives only inside a BLOCK comment: the
    #     presence check read comments as code (axis=parser-death).
    arm(
        "M26 rollup guard hidden in a block comment",
        on_mem(check_delete_guard),
        mem=sub_once(
            mem,
            "        " + ROLLUP_GUARD + "\n",
            "        /* " + ROLLUP_GUARD + " */\n",
        ),
    )

    failures = []
    for name, crit, c, m, b, e, r in arms:
        violations = check(root, raw_cfg=c, raw_mem=m, raw_builtins=b, raw_engine=e,
                           raw_runner=r)
        if crit is None:
            if violations:
                failures.append("%s: expected GREEN, got %r" % (name, violations))
            continue
        expected = crit(c, m, b, e, r)
        if not expected:
            failures.append("%s: criterion did not fire (dead tooth)" % name)
            continue
        missing = [v for v in expected if v not in violations]
        if missing:
            failures.append("%s: expected violation missing: %r" % (name, missing))
        collateral = [v for v in violations if v not in expected]
        if collateral:
            failures.append("%s: collateral violation %r" % (name, collateral))

    if failures:
        for f in failures:
            print("SELFTEST FAIL: " + f)
        return 1
    print("selftest: %d arms matched (pristine green + %d targeted mutations)"
          % (len(arms), len(arms) - 1))
    print("BACKUP_REMAP_ROLLUP_SELFTEST=PASS")
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--root", dest="root_opt")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    root = args.root_opt or args.root
    if not os.path.isdir(os.path.join(root, "src/android/app/src/main/java/com/rikkaminis/app")):
        print("ROT: %s does not look like the RikkaMinis root" % root)
        return 2
    if args.selftest:
        return selftest(root)
    violations = check(root)
    if violations:
        for rel, line, msg in violations:
            print("%s:%s: %s" % (rel, line, msg))
        print("backup_remap_rollup_check: %d violation(s)" % len(violations))
        return 1
    print("backup_remap_rollup_check: OK (compact pin remapped, binding remapped, "
          "rollup packed + guarded, group run-config restored on same-name merge)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
