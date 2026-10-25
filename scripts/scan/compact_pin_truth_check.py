#!/usr/bin/env python3
"""
Compact-pin truth gate (deep-audit-1018 ISSUE-07).

Two properties of the compaction domain that failed silently in production
before [fix/da1018-compact-pin]:

  1. THE PIN COLLECTOR DE-DUPLICATES WITHIN ONE MESSAGE.
     Production USER messages are DOUBLE-FILLED: the main send path puts the
     trimmed text in BOTH `content` and the first `AgentContentPart.Text`
     (ChatViewModel 3713-3714), and every engine-side reminder mirrors it
     (AgentLoopEngine, ChatToolExecutors, EmptyTurnRecovery,
     SanitizeAgentHistory, ChatCompactionLogic :497). The pre-fix collector
     read both copies into the pin list, so every user line was pinned twice:
     the PINNED_USER_MESSAGES_TOKEN_LIMIT budget was consumed at 2x the real
     content (the over-cap walk dropped older real messages early) and the
     summary string that rides every request carried each text twice.
     The fix de-duplicates within a message ONLY — it must keep reading BOTH
     sources, because parts carry text `content` does not (per-image captions,
     "[image ...]" placeholders, the attached-files XML). A content-only or
     parts-only shortcut is a regression this gate also catches.

  2. THE "TOOL-RESULT CARRIER" TEST HAS A SINGLE TRUTH SOURCE.
     `LLMMessage.isToolResultOnly()` (emptiness-guarded: non-empty AND all
     ToolResult) is the predicate. Two sites had hand-rolled the same test in
     the opposite polarity and both evaluated the empty-`contentParts` row —
     the most common prompt shape — the WRONG way:
       * resolveCompactAnchorIdx sole-prompt guard
         `contentParts.any { it !is ToolResult }` — vacuously FALSE on empty,
         so a sole plain-text instruction did NOT abort compaction;
       * injectSummaryIntoPostAnchor injection target
         `!contentParts.all { it is ToolResult }` — also FALSE on empty, so
         the plain instruction never received the inlined summary and it
         landed as a standalone head.
     Both are now `!isToolResultOnly()`. This gate forbids ANY bare
     `contentParts.any/all { ... ToolResult ... }` outside the isToolResultOnly
     definition itself, in CODE (comments are stripped first).

Mechanical criteria:
  1. inside `extractPinnedUserMessages` (CompactPin.kt): every text that
     reaches the result list has passed a same-message de-dup carrier
     (`LinkedHashSet` / `HashSet` / `mutableSetOf` / `.toSet()` /
     `.distinct()`), i.e. the de-dup construct must be CONSUMED, not merely
     declared (wide3 `pin-dedup`: a declared-but-unused `seenInMessage` while
     `texts.add(msg.content)` runs direct is the pre-fix regression);
  2. the same body still reads BOTH text sources (`msg.content` AND the
     `AgentContentPart.Text` parts) and the parts branch is LIVE — a
     `if (false && part is AgentContentPart.Text …)` short-circuit does not
     count as reading (wide3 `pin-both-sources`);
  3. the collected result survives to the `return`: no `texts.clear()` /
     `removeAll` / re-assignment between the last collection and the return
     (wide3 `pin-collector-found`: a column-0 `}` used to kill the body
     extractor, hiding exactly this statement);
  4. in ChatCompactionLogic.kt code: no `contentParts.any/all { ... }` lambda
     testing `ToolResult` outside the `isToolResultOnly` definition. The lambda
     is located by its OWN braces, so a multi-line lambda body is judged the
     same as a one-liner (wide3 `single-truth-source`).

Usage: python3 scripts/scan/compact_pin_truth_check.py [repo_root]
       python3 scripts/scan/compact_pin_truth_check.py --selftest
Exit:  0 = guarded, 1 = violation (file:line printed)

Known limits (deliberate): the comment/string masker handles //, /* */,
double-quoted and triple-quoted strings (Kotlin char literals only when they
close on the same line). Function bodies are located by BRACE DEPTH from the
declaration, never by a column-0 `}` or by the first `{` after `fun` (that one
belongs to a lambda in an expression body). The de-dup criterion is structural,
not behavioural: proof that de-dup actually happens lives in CompactPinDedupTest
/ ChatCompactionLogicTruthTableTest (JVM) plus the mutation controls in the fix
report.
"""
import os
import re
import sys

MAIN = "src/android/app/src/main/java/com/rikkaminis/app"
PIN_FILE = MAIN + "/conversation/CompactPin.kt"
LOGIC_FILE = MAIN + "/ui/chat/ChatCompactionLogic.kt"
SELFTEST_MARK = "COMPACT_PIN_TRUTH_SELFTEST=PASS"

SET_CTORS = ("LinkedHashSet", "HashSet", "mutableSetOf", "linkedSetOf", "toMutableSet", "toSet")
LIST_CTORS = ("ArrayList", "mutableListOf", "arrayListOf", "MutableList", "LinkedList")


# ─── structure helpers (offset-preserving) ──────────────────────────────────

def mask_code(src):
    """Return src of identical length with comment text AND string/char literal
    contents blanked to spaces. Newlines are preserved, so every offset (and
    therefore every line number) still matches the original source."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
            continue
        if src[i] == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    break
                if src[j] == "\n":
                    break
                j += 1
            for k in range(i, min(j + 1, n)):
                out[k] = " "
            i = j + 1
            continue
        if src[i] == "'":
            j = src.find("'", i + 1)
            if 0 < j - i <= 4:
                for k in range(i, j + 1):
                    out[k] = " "
                i = j + 1
                continue
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
            continue
        i += 1
    return "".join(out)


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


def match_close(text, start, opener, closer):
    """Index of the bracket that closes the one at `start`, or -1."""
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == opener:
            depth += 1
        elif ch == closer:
            depth -= 1
            if depth == 0:
                return i
    return -1


IF_RE = re.compile(r"\bif\s*\(")


def if_statements(masked):
    """Every `if (...)` with its brace-balanced body span: (p, close, body_start,
    body_end, cond). A single-statement body (no braces) ends at the line."""
    out = []
    for m in IF_RE.finditer(masked):
        p = m.end() - 1
        close = match_close(masked, p, "(", ")")
        if close < 0:
            continue
        j = close + 1
        while j < len(masked) and masked[j] in " \t":
            j += 1
        if j < len(masked) and masked[j] == "{":
            body_end = match_close(masked, j, "{", "}")
            if body_end < 0:
                body_end = len(masked)
        else:
            # Statement body (no braces right after the `)`): it runs to the end
            # of the STATEMENT, which may carry a trailing lambda block
            # (`if (false) withContext(Dispatchers.Main) { host.push(…) }`) — a
            # line-based end would stop before the block and let the dead guard
            # hide the publish from every liveness check.
            k, depth = j, 0
            while k < len(masked):
                ch = masked[k]
                if ch in "([{":
                    depth += 1
                elif ch in ")]}":
                    if depth == 0:
                        break
                    depth -= 1
                elif ch == "\n" and depth == 0:
                    nxt = masked[k + 1:].lstrip(" \t")
                    if nxt[:1] in (".", "?", "&", "|"):
                        k += 1
                        continue
                    break
                k += 1
            body_end = k
        out.append((p, close, j, body_end, masked[p + 1:close]))
    return out


def enclosing(ifs, idx):
    """Conditions of the `if` headers whose body contains `idx`, innermost last."""
    hits = [s for s in ifs if s[2] <= idx <= s[3]]
    hits.sort(key=lambda s: s[0])
    return [s[4] for s in hits]


def split_top(expr, op):
    parts, cur, depth, i = [], [], 0, 0
    while i < len(expr):
        ch = expr[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if depth == 0 and expr.startswith(op, i):
            parts.append("".join(cur))
            cur = []
            i += len(op)
            continue
        cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return parts


def norm_atom(expr):
    s = expr.strip()
    while True:
        if s.startswith("(") and match_close(s, 0, "(", ")") == len(s) - 1:
            s = s[1:-1].strip()
        elif s.startswith("(") or s.endswith(")"):
            s = s.strip("() \t")
        else:
            return s


def is_lit(expr, word):
    return norm_atom(expr) == word


def always_false(cond):
    """True when the condition cannot be true: every `||` disjunct is killed by
    a literal `false` conjunct. This is the `if (false && guard)` shape — the
    guard's spelling survives but its decision is gone."""
    for disj in split_top(cond, "||"):
        if is_lit(disj, "false"):
            continue
        if any(is_lit(c, "false") for c in split_top(disj, "&&")):
            continue
        return False
    return True


def always_true(cond):
    """True when the condition is a tautology: a literal `true` disjunct."""
    for disj in split_top(cond, "||"):
        if is_lit(disj, "true"):
            return True
    return False


def dead_for(cond, needle):
    """True when `needle` sits in the part of `cond` that a literal kills —
    including one level of parenthesised grouping (`|| (false && needle)`),
    which a top-level-only split would miss."""
    for disj in split_top(cond, "||"):
        if needle not in disj:
            continue
        if is_lit(disj, "false"):
            return True
        inner = disj.strip()
        if inner.startswith("(") and match_close(inner, 0, "(", ")") == len(inner) - 1:
            if dead_for(inner[1:-1], needle):
                return True
        for con in split_top(disj, "&&"):
            if needle not in con and is_lit(con, "false"):
                return True
    return False


def fun_body(masked, name, receiver=False):
    """(start, end) span of the function's BODY (inside the braces, or the
    expression after `=`) located by paren + brace matching — never by a
    column-0 `}` and never by the first `{` after `fun` (which can belong to a
    lambda in an expression body). Returns None when the anchor moved."""
    pat = r"\bfun\s+(?:[A-Za-z0-9_.<>?, ]+\.)?" + re.escape(name) + r"\s*\("
    m = re.search(pat, masked)
    if not m:
        return None
    p = m.end() - 1
    close = match_close(masked, p, "(", ")")
    if close < 0:
        return None
    i = close + 1
    while i < len(masked) and masked[i] not in "{=":
        if masked[i] == "\n" and masked[i + 1:i + 2] == "\n":
            return None
        i += 1
    if i >= len(masked):
        return None
    if masked[i] == "{":
        end = match_close(masked, i, "{", "}")
        if end < 0:
            return None
        return (i + 1, end)
    stop = len(masked)
    tail_re = re.compile(r"\n[ \t]*\n|\n[ \t]*\}|\n(?:internal|private|public|fun|object|class|val|@)\b")
    tm = tail_re.search(masked, i)
    if tm:
        stop = tm.start()
    return (i + 1, stop)


def enclosing_fun(masked, idx):
    """Name of the nearest `fun` declared before `idx`."""
    name = ""
    for m in re.finditer(r"\bfun\s+(?:[A-Za-z0-9_.<>?, ]+\.)?([A-Za-z0-9_]+)\s*\(", masked[:idx]):
        name = m.group(1)
    return name


def is_set_ctor(tok):
    return any(t in tok for t in SET_CTORS)


def is_list_ctor(tok):
    return any(t in tok for t in LIST_CTORS)


# ─── criteria ───────────────────────────────────────────────────────────────

def check_pin(text):
    """Criteria 1-3 against CompactPin.kt."""
    problems = []
    masked = mask_code(text)
    span = fun_body(masked, "extractPinnedUserMessages")
    if span is None:
        problems.append(("pin-collector-found",
                         "%s: extractPinnedUserMessages body not found — anchor moved or "
                         "the declaration is unbalanced, update this gate" % PIN_FILE))
        return problems
    bstart, bend = span
    body = masked[bstart:bend]
    if not body.strip():
        problems.append(("pin-collector-found",
                         "%s:%d: extractPinnedUserMessages body is empty"
                         % (PIN_FILE, line_of(masked, bstart))))
        return problems
    ifs = if_statements(masked)

    # ── carriers: which local holds the seen-set, which holds the result ──
    set_names, list_names = set(), set()
    for dm in re.finditer(r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]*)?=\s*([A-Za-z_][\w.]*)", body):
        name, ctor = dm.group(1), dm.group(2)
        if is_set_ctor(ctor):
            set_names.add(name)
        elif is_list_ctor(ctor):
            list_names.add(name)

    # The collection stage is the `for (msg in messages)` loop: adds there
    # introduce text from the source message and must pass the de-dup carrier.
    # Adds AFTER the loop (the over-cap walk `recent.add(0, texts[i])`) only
    # re-order an already de-duplicated list and are out of this criterion.
    scope_start, scope_end = bstart, bend
    for lm in re.finditer(r"\bfor\s*\(\s*\w+\s+in\s+messages\s*\)", body):
        ob = body.find("{", lm.end())
        cb = match_close(body, ob, "{", "}") if ob > 0 else -1
        if cb > 0:
            scope_start, scope_end = bstart + ob, bstart + cb
        break
    scope = masked[scope_start:scope_end]

    adds = []
    for am in re.finditer(r"\b(\w+)\s*\.\s*(add|addAll)\s*\(", scope):
        p = am.end() - 1
        close = match_close(scope, p, "(", ")")
        arg = scope[p + 1:close] if close > 0 else ""
        adds.append((am.group(1), am.group(2), arg, am.start()))

    ret_exprs = [m.group(1).strip() for m in re.finditer(r"\breturn\b([^\n]*)", body)]
    list_dedup = any(re.search(r"\.(distinct|toSet|toMutableSet|toHashSet)\s*\(", e) for e in ret_exprs)

    # ── criterion 1: the de-dup carrier is CONSUMED, not just declared ─────
    if not adds:
        problems.append(("pin-dedup",
                         "%s:%d: extractPinnedUserMessages collects nothing (no add/addAll in "
                         "the body) — the collector was gutted or the body extractor lost it"
                         % (PIN_FILE, line_of(masked, bstart))))
    else:
        bad = []
        for base, kind, arg, at in adds:
            if base in set_names:
                continue
            if kind == "addAll" and any(re.search(r"\b" + re.escape(s) + r"\b", arg) for s in set_names):
                continue
            if list_dedup:
                continue
            bad.append((base, kind, at))
        if bad:
            base, kind, at = bad[0]
            problems.append(("pin-dedup",
                             "%s:%d: extractPinnedUserMessages adds to `%s` without passing a "
                             "same-message de-dup carrier (%s %s) — production messages are "
                             "double-filled (content == first Text part), so every line would pin "
                             "twice and eat the token cap at 2x real content%s"
                             % (PIN_FILE, line_of(masked, scope_start + at), base, base, kind,
                                "" if set_names else "; a declared-but-unused set does not count")))

    # ── criterion 2: both sources, and the parts branch is LIVE ────────────
    reads_content = re.search(r"\.content\b", body) is not None
    parts_loop = None
    for lm in re.finditer(r"\bfor\s*\(\s*(\w+)\s+in\s+[\w.]*\.contentParts\s*\)", body):
        ob = body.find("{", lm.end())
        cb = match_close(body, ob, "{", "}") if ob > 0 else -1
        if cb > 0:
            parts_loop = (ob, cb)
        break
    if not reads_content:
        problems.append(("pin-both-sources",
                         "%s:%d: extractPinnedUserMessages stopped reading `msg.content` — a "
                         "parts-only shortcut loses the plain-text prompt"
                         % (PIN_FILE, line_of(masked, bstart))))
    if parts_loop is None:
        problems.append(("pin-both-sources",
                         "%s:%d: extractPinnedUserMessages has no `for (part in "
                         "<msg>.contentParts)` loop — captions, [image ...] placeholders and the "
                         "attached-files XML would be lost"
                         % (PIN_FILE, line_of(masked, bstart))))
    else:
        ob, cb = parts_loop
        loop_body = body[ob:cb]
        if "AgentContentPart.Text" not in loop_body:
            problems.append(("pin-both-sources",
                             "%s:%d: the contentParts loop no longer tests "
                             "`AgentContentPart.Text`" % (PIN_FILE, line_of(masked, bstart + ob))))
        else:
            live_add = False
            for am in re.finditer(r"\b(\w+)\s*\.\s*(add|addAll)\s*\(", loop_body):
                guards = enclosing(ifs, bstart + ob + am.start())
                if any(always_false(g) or dead_for(g, "AgentContentPart.Text") for g in guards):
                    continue
                live_add = True
            if not live_add:
                problems.append(("pin-both-sources",
                                 "%s:%d: the `AgentContentPart.Text` branch never adds anything "
                                 "LIVE (short-circuited by a literal / dead guard) — text that "
                                 "exists only in contentParts is silently dropped"
                                 % (PIN_FILE, line_of(masked, bstart + ob))))

    # ── criterion 3: the collected result survives to the return ───────────
    if not ret_exprs:
        problems.append(("pin-collector-intact",
                         "%s:%d: extractPinnedUserMessages has no `return` inside its body — the "
                         "body extractor is truncated (the old one stopped at a column-0 `}`)"
                         % (PIN_FILE, line_of(masked, bstart))))
    else:
        carriers = {b for b, _k, _a, _at in adds}
        tail_start = scope_start + max(a[3] for a in adds) if adds else bstart
        tail = masked[tail_start:bend]
        for name in sorted(carriers):
            if re.search(r"\b" + re.escape(name) + r"\s*\.\s*(clear|removeAll|retainAll)\s*\(", tail):
                problems.append(("pin-collector-intact",
                                 "%s:%d: `%s` is emptied after the collection — the pinned user "
                                 "messages never reach the summary (every instruction in the "
                                 "folded range is lost)"
                                 % (PIN_FILE, line_of(masked, tail_start + tail.find(name)), name)))
            elif re.search(r"(?<!val )(?<!var )\b" + re.escape(name) +
                           r"\s*=\s*(?:ArrayList|mutableListOf|arrayListOf|LinkedHashSet|"
                           r"HashSet|mutableSetOf)", tail):
                problems.append(("pin-collector-intact",
                                 "%s:%d: `%s` is re-assigned after the collection — the collected "
                                 "text is discarded"
                                 % (PIN_FILE, line_of(masked, tail_start + tail.find(name)), name)))
    return problems


def check_logic(text):
    """Criterion 4 against ChatCompactionLogic.kt: the isToolResultOnly()
    definition is the only place allowed to test ToolResult polarity against
    contentParts inline."""
    problems = []
    masked = mask_code(text)
    bare = re.compile(r"contentParts\s*\.\s*(any|all)\s*(?:<[^\n{}]*>)?\s*\{")
    for m in bare.finditer(masked):
        ob = m.end() - 1
        cb = match_close(masked, ob, "{", "}")
        lam = masked[ob:cb + 1] if cb > 0 else masked[ob:]
        if "ToolResult" not in lam:
            continue
        cur_fun = enclosing_fun(masked, m.start())
        if cur_fun == "isToolResultOnly":
            continue
        problems.append(("single-truth-source",
                         "%s:%d: bare `contentParts.%s { ... ToolResult ... }` inside `%s` — use "
                         "isToolResultOnly() (the single truth source; its empty-parts polarity is "
                         "the da1018 fix); the lambda is judged by its own braces, so a multi-line "
                         "body counts the same as a one-liner"
                         % (LOGIC_FILE, line_of(masked, m.start()), m.group(1), cur_fun or "?")))
    return problems


def load(root):
    pin_path = os.path.join(root, PIN_FILE)
    logic_path = os.path.join(root, LOGIC_FILE)
    if not os.path.exists(pin_path):
        print("❌ compact-pin truth gate: %s not found" % pin_path)
        return None
    if not os.path.exists(logic_path):
        print("❌ compact-pin truth gate: %s not found" % logic_path)
        return None
    with open(pin_path, encoding="utf-8") as f:
        pin = f.read()
    with open(logic_path, encoding="utf-8") as f:
        logic = f.read()
    return pin, logic


def check(pin, logic):
    return check_pin(pin) + check_logic(logic)


# ─── selftest: each arm must flip exactly its criterion ─────────────────────

PIN_PREFIX = """package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

const val PINNED_USER_MESSAGES_TOKEN_LIMIT: Long = 8000L

fun extractPinnedUserMessages(
    messages: List<LLMMessage>,
    tokenLimit: Long = PINNED_USER_MESSAGES_TOKEN_LIMIT,
): List<String> {
"""

LOGIC_PREFIX = """package com.rikkaminis.app.ui.chat

internal fun LLMMessage.isToolResultOnly(): Boolean =
    contentParts.isNotEmpty() && contentParts.all { it is AgentContentPart.ToolResult }

internal fun someSite(history: List<LLMMessage>): Int {
"""


def selftest(_root):
    arms = []
    # Arm 1 — pre-fix pin collector (criteria 1+2, but the discriminator for
    # criterion 1 is that BOTH direct adds have no dedup).
    pre_fix_pin = PIN_PREFIX + """    val texts = ArrayList<String>()
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        if (msg.content.isNotBlank()) texts.add(msg.content)
        for (part in msg.contentParts) {
            if (part is AgentContentPart.Text && part.text.isNotBlank()) texts.add(part.text)
        }
    }
    return texts
}
"""
    arms.append(("arm1 pre-fix collector flags pin-dedup", pre_fix_pin, LOGIC_PREFIX + "}\n",
                 [("pin-dedup")]))

    # Arm 2 — content-only shortcut WITH a dedup construct: dedup criterion is
    # satisfied but both-sources criterion must fire.
    content_only_pin = PIN_PREFIX + """    val seen = LinkedHashSet<String>()
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        if (msg.content.isNotBlank()) seen.add(msg.content)
    }
    return seen.toList()
}
"""
    arms.append(("arm2 content-only shortcut flags pin-both-sources", content_only_pin,
                 LOGIC_PREFIX + "}\n", [("pin-both-sources")]))

    # Arm 3 — post-fix shape (fixed collector + compliant logic) must be clean.
    fixed_pin = PIN_PREFIX + """    val texts = ArrayList<String>()
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        val seenInMessage = LinkedHashSet<String>()
        if (msg.content.isNotBlank()) seenInMessage.add(msg.content)
        for (part in msg.contentParts) {
            if (part is AgentContentPart.Text && part.text.isNotBlank()) seenInMessage.add(part.text)
        }
        texts.addAll(seenInMessage)
    }
    if (texts.isEmpty()) return emptyList()
    return texts
}
"""
    arms.append(("arm3 fixed shape is clean", fixed_pin, LOGIC_PREFIX + "}\n", []))

    # Arm 4 — bare !all { ToolResult } at the injection site (pre-fix :449).
    arm4_logic = LOGIC_PREFIX + """    val firstTextUserOffset = postAnchor.indexOfFirst {
        it.role == LLMMessage.Role.USER &&
            !it.contentParts.all { p -> p is AgentContentPart.ToolResult }
    }
    return 0
}
"""
    arms.append(("arm4 pre-fix :449 flags single-truth-source", fixed_pin, arm4_logic,
                 [("single-truth-source")]))

    # Arm 5 — bare any { !ToolResult } at the anchor site (pre-fix :124).
    arm5_logic = LOGIC_PREFIX + """    } else if (i == 0 &&
        history[0].role == LLMMessage.Role.USER &&
        history[0].contentParts.any { p -> p !is AgentContentPart.ToolResult } &&
        true
    ) {
        return -1
    }
    return 0
}
"""
    arms.append(("arm5 pre-fix :124 flags single-truth-source", fixed_pin, arm5_logic,
                 [("single-truth-source")]))

    # Arm 6 — comments quoting the old forms must NOT fire (masker works).
    arm6_logic = LOGIC_PREFIX + """    // Was `contentParts.any { it !is ToolResult }` — vacuously false.
    /* and !it.contentParts.all { p -> p is AgentContentPart.ToolResult } */
    val ok = !history[0].isToolResultOnly()
    return if (ok) 0 else 0
}
"""
    arms.append(("arm6 commented-out old forms do not flag", fixed_pin, arm6_logic, []))

    # ── wide3 witnesses ────────────────────────────────────────────────────
    # Arm 7 — `pin-dedup`: the de-dup set is DECLARED but never consumed; the
    # texts go straight into the result list (the exact witness patch).
    dead_set_pin = PIN_PREFIX + """    val texts = ArrayList<String>()
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        val seenInMessage = LinkedHashSet<String>()
        if (msg.content.isNotBlank()) texts.add(msg.content)
        for (part in msg.contentParts) {
            if (part is AgentContentPart.Text && part.text.isNotBlank()) texts.add(part.text)
        }
    }
    return texts
}
"""
    arms.append(("arm7 declared-but-unused dedup set flags pin-dedup", dead_set_pin,
                 LOGIC_PREFIX + "}\n", [("pin-dedup")]))

    # Arm 8 — `pin-both-sources`: the parts branch is short-circuited by `false &&`.
    false_guard_pin = PIN_PREFIX + """    val texts = ArrayList<String>()
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        val seenInMessage = LinkedHashSet<String>()
        if (msg.content.isNotBlank()) seenInMessage.add(msg.content)
        for (part in msg.contentParts) {
            if (false && part is AgentContentPart.Text && part.text.isNotBlank()) seenInMessage.add(part.text)
        }
        texts.addAll(seenInMessage)
    }
    return texts
}
"""
    arms.append(("arm8 short-circuited parts branch flags pin-both-sources", false_guard_pin,
                 LOGIC_PREFIX + "}\n", [("pin-both-sources")]))

    # Arm 9 — `single-truth-source`: the bare lambda body moved onto its own
    # line (the old gate only looked at the line holding `contentParts.any {`).
    arm9_logic = LOGIC_PREFIX + """    } else if (i == 0 &&
        history[0].role == LLMMessage.Role.USER &&
        history[0].contentParts.any {
            it !is AgentContentPart.ToolResult
        } &&
        history.drop(1).none { it.role == LLMMessage.Role.ASSISTANT }
    ) {
        return -1
    }
    return 0
}
"""
    arms.append(("arm9 multi-line lambda flags single-truth-source", fixed_pin, arm9_logic,
                 [("single-truth-source")]))

    # Arm 10 — `pin-collector-found`: a column-0 `}` (the loop's) used to end the
    # extracted body early, hiding a `texts.clear()` that ran before the return.
    cleared_pin = PIN_PREFIX + """    val texts = ArrayList<String>()
    for (msg in messages) {
        if (msg.role != LLMMessage.Role.USER) continue
        val seenInMessage = LinkedHashSet<String>()
        if (msg.content.isNotBlank()) seenInMessage.add(msg.content)
        for (part in msg.contentParts) {
            if (part is AgentContentPart.Text && part.text.isNotBlank()) seenInMessage.add(part.text)
        }
        texts.addAll(seenInMessage)
}
    texts.clear()
    if (texts.isEmpty()) return emptyList()
    return texts
}
"""
    arms.append(("arm10 cleared collector flags pin-collector-intact", cleared_pin,
                 LOGIC_PREFIX + "}\n", [("pin-collector-intact")]))

    failed = []
    for name, pin_src, logic_src, expected in arms:
        got = [c for c, _ in check(pin_src, logic_src)]
        if sorted(got) != sorted(expected):
            failed.append("%s (expected %s, got %s)" % (name, expected, got))

    if failed:
        print("❌ compact-pin truth gate --selftest:")
        for line in failed:
            print("   - %s" % line)
        return 1
    print("✅ compact-pin truth gate --selftest: %d arms flip exactly their criterion" % len(arms))
    print(SELFTEST_MARK)
    return 0


def main():
    if "--selftest" in sys.argv:
        root = next((a for a in sys.argv[1:] if not a.startswith("-")), ".")
        return selftest(root)
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    files = load(root)
    if files is None:
        return 1
    problems = check(*files)
    if problems:
        print("❌ compact-pin truth gate findings:")
        for criterion, msg in problems:
            print("   - [%s] %s" % (criterion, msg))
        return 1
    print("✅ compact-pin truth gate: the pin collector feeds a same-message de-dup carrier "
          "while still reading both text sources, the collected result survives to the return, "
          "and isToolResultOnly() is the only tool-result polarity test in the compaction domain")
    return 0


if __name__ == "__main__":
    sys.exit(main())
