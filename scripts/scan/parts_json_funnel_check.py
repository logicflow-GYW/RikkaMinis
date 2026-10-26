#!/usr/bin/env python3
"""parts_json_funnel_check.py — [fix/da1019-partsjson-funnel] scan gate.

Issue: three paths bypassed the existing parts_json / reasoning_content
ceilings, so an oversized row could re-enter the database and abort the next
read with SQLiteBlobTooBigException (Issue #17, 2 MB CursorWindow):

  1. READ  - loadMessagePage's unbounded branch called the DAO directly,
             bypassing the tolerant pager's row-by-row degradation.
  2. WRITE - updateMessageParts wrote the payload verbatim (no capPartsJsonForRow).
  3. IMPORT- ConfigBackup.importChatSections constructed MessageEntity straight
             from the payload; neither column was capped.

Judgements (each must hold in the live tree):

  [R1] In every production source, `dao.loadMessagesPage(` and
       `dao.loadMessagesPageInRange(` appear ONLY inside ChatRepository.kt's
       single `loadPageSlice` funnel (1 occurrence each, inside its body).
  [R2] ChatRepository.kt has exactly 4 `loadPageTolerant(` occurrences
       (1 definition + 3 call sites) and all three paged entries route
       through it (loadMessagesInner / loadMessagePage / loadMessagePageRaw).
  [R3] loadPageRowByRow also routes through loadPageSlice (no direct DAO page).
  [R4] updateMessageParts writes through the ceiling:
       `dao.updateMessageParts(id, capPartsJsonForRow(partsJson))`.
  [R5] The reasoning ceiling is a single companion funnel
       (`capReasoningContentForRow`) used by appendMessage (no inline
       re-derivation of the byte comparison outside the companion).
  [R6] ConfigBackup.importChatSections caps both columns through the
       companion funnels before dao.insertMessage.

[da1032-B] The same file lives on the READ side of the protocol too — the
debug RPC view that mirrors `parts_json` back out to callers. Three judgments
pin that surface (each judged on the DECISION POINT, not on a call shape):

  [R7] ChatDebugMethods.messageToJson classifies every real ContentPart
       serial name — `toolUse` / `toolResult` / `mediaRef` (the @SerialName
       set in data/model/ContentPart.kt). Any one missing means those parts
       are silently dropped from `chat.messages.list` (toolCalls /
       attachments permanently empty). Comments are stripped first, so a KDoc
       that merely mentions the name does not satisfy the judgment.
  [R8] `chat.sessions.list` and `chat.sessions.get` publish ONE session
       shape: both route through the single `sessionCoreJson` mapping point,
       which carries `pinnedAt` + `lastMessagePreview`. Re-inlining either
       field in a caller is a FAIL — that is exactly how the two views drifted
       apart the first time.
  [R9] Model-reference resolution exists ONCE: the composite predicate
       (`it.baseModel.id == modelId || it.id == modelId`) lives in
       ChatDebugMethods.resolveModelDisplay, is reached from >= 2 call sites
       (incl. the mutation side), and no other copy of the bare predicate is
       re-derived in the debug package.

  [R11 / C-EXP] Every reader that DISPATCHES ON THE PART NAME of a persisted
       parts_json row must handle `"mediaRef"` — the @SerialName a real row
       carries for media today. `ChatExporter.countAttachments` only knew the
       pre-mediaRef names (`"image"` / `"video"`), which every row written
       since the migration stopped using, so those counters were structural
       zeros: summed per batch, published in Summary, written into
       session.json, and never incremented once. The READER SET IS DERIVED (a
       function body that both parses a persisted parts payload and switches
       on `type`), so a new face added tomorrow is judged the same day — a
       hand-listed file list would go blind.

  --selftest  proves the gate would fail on the pre-fix shapes (negative
  fixtures inline), so an emptied or stubbed tree cannot masquerade as green.
"""
import sys
import os
import re

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    FUN_RE,
    const_bool,
    split_top,
    const_value,
    function_body,
    if_blocks,
    match_bracket,
    stmt_spans,
    strip_parens,
    tail_expr,
    when_branches,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    contains_ident,
    expr_end,
    guard_feasible,
    path_feasible,
    value_sources,
)

OK, FAIL = "PASS", "FAIL"

PARTS_RE = re.compile(r"dao\.loadMessagesPage\(")
RANGE_RE = re.compile(r"dao\.loadMessagesPageInRange\(")

# [da1032-B] read-side surface (debug RPC view of the same parts_json rows).
DEBUG_REL = "src/android/app/src/main/java/com/rikkaminis/app/debug/ChatDebugMethods.kt"
MUTATION_DEBUG_REL = "src/android/app/src/main/java/com/rikkaminis/app/debug/ChatMutationMethods.kt"
DAO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/db/ChatDao.kt"
CHATREPO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/ChatRepository.kt"

# The @SerialName set of data/model/ContentPart.kt — the only three keys that
# a real persisted parts_json row can carry besides "text".
PART_SERIAL_NAMES = ('"toolUse"', '"toolResult"', '"mediaRef"')
SESSION_PARITY_FIELDS = ('put("pinnedAt"', 'put("lastMessagePreview"')
SHARED_SESSION_MAPPER = "sessionCoreJson("
COMPOSITE_MODEL_PREDICATE = "it.id == modelId"
BARE_MODEL_PREDICATE_RE = re.compile(r"modelEntries\.firstOrNull\s*\{[^}]*baseModel\.id\s*==")


def strip_kotlin_comments(text):
    """Literal view of [text] — comments blanked, string CONTENT preserved.

    [B7-t03] This used to be a hand-written lexer (string / raw string / `${…}`
    template machine). Every failure mode the external witnesses exploited on
    other gates began there: a `}` inside a literal closing a function body, a
    `"video/*"` opening a block comment, a KDoc brace balancing a real one. The
    shared `_kotlin_lexer` is the single implementation (length strictly
    preserved, so a position found in this view is a position in the source),
    and a lex failure is propagated to the caller as fail-closed.

    Positions taken here are reusable on `mask_code` output and vice versa —
    both views are length-identical to the source.
    """
    return mask_comments(text)


def code_of(text):
    """Structural view: comments AND string interiors blanked, length preserved."""
    return mask_code(text)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def extract_function(text, sig):
    """Body of the function named in [sig], via the shared `function_body`.

    [B7-t03] The old version located a `{` after the signature and brace-matched
    it. Two holes the witnesses drove through: an EXPRESSION-bodied funnel
    (`fun capReasoningContentForRow(rc: String?) = rc`) has no body brace at
    all, so the matcher ran on and returned the NEXT function's body — a
    non-None answer for a body that no longer exists; and a KDoc example with a
    balanced `{"type": …}` could be mistaken for the body. `function_body`
    distinguishes `{ … }` from `= …` and knows where the expression ends.
    """
    name = None
    m = re.search(r"fun\s+(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(", sig)
    if m:
        name = m.group(1)
    if name is None:
        # fall back to the first declaration mentioned in the signature string
        m = re.search(r"([A-Za-z_]\w*)\s*\(", sig)
        if m:
            name = m.group(1)
    if name is None:
        return None
    # Prefer the declaration this signature actually names, in source order.
    want = sig.strip()
    best = None
    for decl in FUN_RE.finditer(text):
        if decl.group(1) != name:
            continue
        tail = text[decl.start():decl.start() + 200].replace("\n", " ")
        if want and want.split("(")[0].split()[-1] not in tail and "(" in want:
            # only accept a declaration whose signature prefix matches
            if not tail.startswith(want.split("(")[0].strip()):
                continue
        best = decl
        break
    if best is None:
        return None
    body = function_body(text[best.start():], name)
    if body is None:
        return None
    b_start, b_end, _kind = body
    return text[best.start():best.start() + b_end + 1]


def region_between(text, start_sig, end_sig_regex):
    """Text from start_sig to the next top-level-ish declaration after it.
    Line-order regions (not brace matching): expression bodies like
    `= if (...) { … } else { … }` close their first brace mid-body, so a
    brace-matched region would silently stop inside the body."""
    start = text.find(start_sig)
    if start < 0:
        return None
    m = re.search(end_sig_regex, text[start + 10:])
    if m:
        return text[start:start + 10 + m.start()]
    return text[start:]



# ── [B7-t03] value-flow helpers shared by [R5] / [R6] ─────────────────────
FUNNEL_SPECS = (
    ("capPartsJsonForRow", "buildTruncatedPartsJson", "partsJson"),
    ("capReasoningContentForRow", "buildTruncatedText", "reasoningContent"),
)
NULLISH = ("null", "JSONObject.NULL", "Unit", "")


def ceiling_funnel_violations(code, name, truncator):
    """[R5] A ceiling funnel must DO the ceiling, not just exist.

    The old judgment was `extract_function(...) is not None` — a funnel kept
    with its full signature and every call site wired, whose body became
    `= reasoningContent`, passed while every multi-MB reasoning blob reached
    Room verbatim. Judged now on three facts about the body:
      (a) the byte ceiling is COMPARED (the constant appears in a condition);
      (b) the truncator is CALLED on a reachable path;
      (c) the value the function RETURNS can come from that truncator.
    """
    b = function_body(code, name)
    if b is None:
        return ["missing"]
    start, end, kind = b
    body = code[start:end]
    out = []
    if not any("MAX_MESSAGE_PARTS_JSON_BYTES" in cond
               for cond, _ts, _te, _es, _ee in if_blocks(body)):
        out.append("does not compare against MAX_MESSAGE_PARTS_JSON_BYTES")
    sites = [m.start() for m in re.finditer(r"\b" + truncator + r"\s*\(", body)]
    if not sites:
        out.append("never calls %s()" % truncator)
    elif not any(path_feasible(body, x) for x in sites):
        out.append("the %s() call is unreachable (dead guard)" % truncator)
    # (c) the value the function RETURNS can come from that truncator. Every
    # `return` point counts — a block funnel early-returns the truncated value
    # and falls through to the original, which is the correct shape.
    if kind == "block":
        sources = []
        for m in re.finditer(r"\breturn\b", body):
            e = expr_end(body, m.end())
            sources.extend(value_sources(body[m.end():e]))
        if not sources:
            tail = tail_expr(body)
            sources = value_sources(tail) if tail else []
    else:
        sources = value_sources(body)
    if not any(truncator in src for src in sources):
        out.append("the returned value never comes from %s()" % truncator)
    return out


def local_decl_rhs(region, name):
    """The right-hand side of the local declaration of [name], or None."""
    m = re.search(r"\b(?:val|var)\s+" + re.escape(name) + r"\s*(?::[^=\n]+)?=(?!=)", region)
    if m is None:
        return None
    e = expr_end(region, m.end())
    return region[m.end():e].strip().rstrip(",").strip()


def local_sources(region, rhs, depth=3):
    """[rhs] resolved through local `val x = …` bindings (bounded hops)."""
    out = []
    for src in value_sources(rhs):
        s = strip_parens(src.strip().rstrip(",").strip())
        m = re.fullmatch(r"([A-Za-z_]\w*)", s)
        if m and depth > 0:
            init = local_decl_rhs(region, m.group(1))
            if init is not None and init != s:
                out.extend(local_sources(region, init, depth - 1))
                continue
        out.append(s)
    return out


def funnel_bypasses(code_region, literal_region, ident, funnel):
    """[R5]/[R6] Every path by which [ident]'s value escapes [funnel].

    Two shapes, both witnessed on this gate:

      * a bare USE — `reasoningContent?.let { … }`, or an import that reads the
        payload straight into the entity (`partsJson = m.optString("partsJson")`);
      * a WRITE whose value does not come from the funnel — appending
        `.let { m.optString("partsJson") }` to a correctly capped expression
        keeps the whole capped call as a substring while storing the raw one.

    Positions are located on the CODE view (string interiors blanked, so the
    key name `"partsJson"` inside `m.optString("partsJson")` is not mistaken for
    a variable), while the right-hand sides are sliced from the LITERAL view
    (so the call that carries the key name survives).
    """
    out = []
    spans = []
    for m in re.finditer(r"\b" + re.escape(funnel) + r"\s*\(", code_region):
        spans.extend(call_args(code_region, m.end() - 1))

    writes = []
    for m in re.finditer(r"(?<![\w`])" + re.escape(ident) + r"\s*=(?!=)", code_region):
        e = expr_end(code_region, m.end())
        writes.append((m.start(), m.end(), e, code_region[:m.start()].count("\n") + 1))

    for m in re.finditer(r"(?<![\w`])" + re.escape(ident) + r"(?![\w`])", code_region):
        if any(a <= m.start() < b for a, b in spans):
            continue
        if any(w[0] == m.start() for w in writes):
            continue
        out.append((code_region[:m.start()].count("\n") + 1,
                    "raw use of %s outside %s(...)" % (ident, funnel)))

    for _pos, rhs_start, rhs_end, line in writes:
        # A named-argument assignment ends at its OWN top-level comma —
        # `expr_end` would carry it past `,` into the next parameter.
        seg = code_region[rhs_start:rhs_end]
        parts = split_top(seg, (",",))
        if parts and parts[0][1] < (rhs_end - rhs_start):
            rhs_end = rhs_start + parts[0][1]
        rhs = literal_region[rhs_start:rhs_end].strip().rstrip(",").strip()
        if not rhs:
            continue
        for src in local_sources(literal_region, rhs):
            if src in NULLISH or funnel in src:
                continue
            out.append((line, "`%s = %s` stores a value that never passed %s"
                        % (ident, src[:60], funnel)))
            break
    return out


DEBUG_BLOB_PARTY_RELS = (
    DEBUG_REL,
    MUTATION_DEBUG_REL,
    "src/android/app/src/main/java/com/rikkaminis/app/debug/HeadlessChatRunner.kt",
)


class ColumnScanner:
    """Resolves what a DAO @Query's `SELECT *` actually expands to.

    Star projections are not self-describing: `SELECT * FROM messages` reads
    parts_json while `SELECT * FROM sessions` does not, and the difference is
    the row type each query is bound to. A capture anchored on the literal
    `parts_json` token therefore sees only some of the real read surface — so
    [R10] asks this scanner instead.

    """

    # Row types whose projection is exposed to the parts_json read risk.
    ROWS = {
        "MessageEntity": True,
        "ChatSessionEntity": False,
        "SessionTailRow": True,
    }
    TABLE_ROW_TYPES = {
        "messages": "MessageEntity",
        "compact_markers": "CompactMarkerEntity",
        "sessions": "ChatSessionEntity",
    }

    _STAR_FROM_RE = re.compile(r"(?is)\bSELECT\s+\*\s+.*?\bFROM\s+(?P<table>\w+)")
    _DIRECT_HIT_RE = re.compile(r"(?i)\bparts_json\b")

    @classmethod
    def for_dao_tables(cls):
        return dict(cls.TABLE_ROW_TYPES)

    @classmethod
    def may_read(cls, table_map, body):
        """True when this query can materialise parts_json into Kotlin memory.

        Two shapes qualify, and nothing else:
          * the column is named in the projection (`SELECT parts_json …`, or a
            multi-column list that includes `m.parts_json`);
          * the projection is a STAR over a table whose row type carries the
            column (`SELECT * FROM messages`).

        A COUNT/aggregate over the same table is deliberately NOT a reader: it
        never materialises the blob, which is precisely why COUNT is the shape
        [fix/dv23-a5] moved the emptiness probe onto.
        """
        if cls._DIRECT_HIT_RE.search(body):
            return True
        star = cls._STAR_FROM_RE.search(body)
        if star is None:
            return False
        row_type = table_map.get(star.group("table"))
        return cls.ROWS.get(row_type, False)


def derive_parts_readers(dao_source):
    """Every ChatDao @Query that READS parts_json, by function name.

    Derived from the real DAO rather than hand-listed, so a blob-reading query
    added tomorrow falls under [R10] the same day — the gate cannot go blind by
    omission.

    The @Query argument is extracted by paren balancing, not by a single regex:
    several declarations build their SQL with `"…" + "…"` concatenation across
    lines, and a lazy `.*?` capture happily runs past the closing paren into the
    NEXT declaration (that misfire attributed `loadUserMessagesSince`'s SQL to
    `nextSortOrder` while developing this arm).
    """
    code = strip_kotlin_comments(dao_source)
    readers = {}
    for m in re.finditer(r"@Query\s*\(", code):
        body = _balanced_arg(code, m.end() - 1)
        if body is None:
            continue
        # `body` ends just before its closing paren; step past it and expect
        # the annotated member to be the very next declaration.
        after = code[m.end() + len(body) + 1:]
        fn = re.match(r"\s*\n\s*(?:suspend\s+)?fun\s+(\w+)\s*\(", after)
        if fn is None:
            continue
        if not re.match(r"(?is)\s*['\"]{0,3}\s*(SELECT|WITH)\b", body):
            continue
        if ColumnScanner.may_read(ColumnScanner.for_dao_tables(), body):
            readers[fn.group(1)] = body
    return readers


def _balanced_arg(text, open_idx):
    """Text between the paren at `open_idx` and its match ("" if unbalanced)."""
    close = match_bracket(text, open_idx)
    if close < 0:
        return None
    return text[open_idx + 1:close]


# ── [B7-t03] arm-level helpers shared by [R7] / [R10] / [R11] ─────────────
def when_arm_spans(text, lo, hi):
    """[(subject, cond, body_start, body_end)] of `when` arms opening in [lo,hi)."""
    out = []
    for subject, cond, b_start, b_end in when_branches(text):
        if lo <= b_start < hi:
            out.append((subject.strip(), cond, b_start, b_end))
    return out


def arm_extent(text, start, end):
    """Tighten an arm body: a `{ … }` arm ends at its own closing brace."""
    i = start
    while i < end and text[i] in " \t\r\n":
        i += 1
    if i < end and text[i] == "{":
        close = match_bracket(text, i)
        if close > 0:
            return i + 1, close
    return start, end


def arm_hands_on(text, start, end, var):
    """The arm PASSES the loop value on: a live call receives [var].

    `"mediaRef" -> Unit` keeps the serial name in the dispatch and drops the
    part; `"mediaRef" -> if (false) attachments.put(p)` keeps both and drops it
    anyway. Both are silent data loss at chat.messages.list, so the arm must
    hand the value to a call that can actually run.
    """
    if not var:
        return False
    for m in re.finditer(r"\b([A-Za-z_]\w*)\s*\(", text[start:end]):
        open_pos = start + m.end() - 1
        for a, b in call_args(text, open_pos):
            if contains_ident(text[a:b], var) and path_feasible(text, a):
                return True
    return False


def dao_bound_names(code):
    """Names bound to the DAO (`val rawDao = repo.dao`), 3 hops."""
    names = {"dao"}
    for _ in range(3):
        grew = False
        for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*=(?!=)", code):
            rhs_end = expr_end(code, m.end())
            rhs = code[m.end():rhs_end]
            if re.search(r"(?<![\w])dao\b", rhs) and m.group(1) not in names:
                names.add(m.group(1))
                grew = True
        if not grew:
            break
    return names


def call_receiver(code, pos):
    """The receiver identifier immediately before a call at [pos], or None."""
    j = pos - 1
    while j >= 0 and code[j] in " \t":
        j -= 1
    if j >= 0 and code[j] == "?":
        j -= 1
    if j < 0 or code[j] != ".":
        return None
    j -= 1
    while j >= 0 and code[j] in " \t":
        j -= 1
    end = j + 1
    while j >= 0 and (code[j].isalnum() or code[j] == "_"):
        j -= 1
    return code[j + 1:end]


def check_debug_surface(root):
    """[R7]-[R9] — the read-side debug RPC view of the same persisted rows.

    Returns its own findings list so `--selftest` can grade these arms on
    isolated fixtures without the [R1]-[R6] noise.
    """
    findings = []
    dbg_path = os.path.join(root, DEBUG_REL)
    mut_path = os.path.join(root, MUTATION_DEBUG_REL)
    for p in (dbg_path, mut_path):
        if not os.path.exists(p):
            return [f"[R7] FAIL missing file: {os.path.relpath(p, root)}"]
    dbg = strip_kotlin_comments(read(dbg_path))
    mut = strip_kotlin_comments(read(mut_path))

    # [R7] messageToJson must classify every real ContentPart serial name —
    # and, per [B7-t03], the arm must actually HAND THE PART ON. The old
    # judgment was `serial name appears in the body`, so `"mediaRef" -> Unit`
    # (a matched-but-discarded branch) kept the gate green while every image and
    # file part vanished from chat.messages.list.
    mb = function_body(dbg, "messageToJson")
    if mb is None:
        findings.append(
            "[R7] FAIL messageToJson not found in ChatDebugMethods (fail-closed: this arm "
            "pins the parts_json read-side classification)")
    else:
        lo, hi, _k = mb
        arms = when_arm_spans(dbg, lo, hi)
        part_var = None
        for subject, _cond, _bs, _be in arms:
            vm = re.search(r"([A-Za-z_]\w*)\s*\.\s*optString\s*\(", subject)
            if vm:
                part_var = vm.group(1)
                break
        missing, silent = [], []
        for name in PART_SERIAL_NAMES:
            key = name.strip('"')
            hits = [a for a in arms if contains_ident(a[1], key)]
            if not hits:
                missing.append(name)
                continue
            handed = False
            for _subject, _cond, bs, be in hits:
                a_start, a_end = arm_extent(dbg, bs, be)
                if arm_hands_on(dbg, a_start, a_end, part_var):
                    handed = True
                    break
            if not handed:
                silent.append(name)
        if part_var is None:
            findings.append(
                "[R7] FAIL cannot derive the part variable from the messageToJson ")
            findings[-1] += "dispatch subject (fail-closed: a classification arm that "
            findings[-1] += "cannot be traced cannot be judged)"
        elif missing:
            findings.append(
                f"[R7] FAIL messageToJson cannot classify {', '.join(missing)} — those parts "
                "are dropped from chat.messages.list (toolCalls / attachments stay empty)")
        elif silent:
            findings.append(
                f"[R7] FAIL messageToJson matches {', '.join(silent)} but never hands the "
                "part on (arm body is Unit / dead / does not pass the part variable) — "
                "the part is classified on paper and dropped in fact")
        else:
            findings.append(
                "[R7] PASS messageToJson classifies toolUse / toolResult / mediaRef "
                "and hands each part on")

    # [R8] one session shape, reached from both RPCs through one mapper.
    list_body = extract_function(dbg, "fun sessionsList(")
    get_body = extract_function(dbg, "fun sessionsGet(")
    mapper = extract_function(dbg, "fun sessionCoreJson(") if SHARED_SESSION_MAPPER in dbg else None
    if list_body is None or get_body is None:
        findings.append("[R8] FAIL sessionsList / sessionsGet not found in ChatDebugMethods")
    elif mapper is None:
        findings.append(
            "[R8] FAIL no shared session mapper: chat.sessions.list and chat.sessions.get "
            "build their shapes independently and will drift again")
    else:
        routed = SHARED_SESSION_MAPPER in list_body and SHARED_SESSION_MAPPER in get_body
        carried = [f for f in SESSION_PARITY_FIELDS if f not in mapper]
        re_inlined = [f for f in SESSION_PARITY_FIELDS if f in get_body or f in list_body]
        if not routed:
            which = "sessionsList" if SHARED_SESSION_MAPPER not in list_body else "sessionsGet"
            findings.append(f"[R8] FAIL {which} does not route through {SHARED_SESSION_MAPPER[:-1]}")
        elif carried:
            findings.append(
                f"[R8] FAIL shared mapper is missing {', '.join(carried)} — the field parity "
                "this arm pins is gone")
        elif re_inlined:
            findings.append(
                f"[R8] FAIL {', '.join(re_inlined)} re-inlined in a caller alongside the shared "
                "mapper — two copies of the session shape, free to drift apart")
        else:
            findings.append(
                "[R8] PASS sessionsList + sessionsGet share one session shape "
                "(pinnedAt / lastMessagePreview single-sourced)")

    # [R9] model-reference resolution exists once.
    definition = extract_function(dbg, "fun resolveModelDisplay(")
    if definition is None:
        findings.append("[R9] FAIL resolveModelDisplay missing from ChatDebugMethods")
    elif COMPOSITE_MODEL_PREDICATE not in definition:
        findings.append(
            "[R9] FAIL resolveModelDisplay lost the composite predicate "
            "(it.id == modelId) — composite entry ids stop resolving")
    else:
        dbg_calls = len(re.findall(r"resolveModelDisplay\(", dbg)) - 1   # minus the definition
        mut_calls = len(re.findall(r"resolveModelDisplay\(", mut))
        local_copy = BARE_MODEL_PREDICATE_RE.search(mut)
        if local_copy:
            findings.append(
                "[R9] FAIL ChatMutationMethods re-derives its own modelEntries predicate "
                "— the same decision now lives in two places")
        elif mut_calls < 1 or dbg_calls + mut_calls < 2:
            findings.append(
                f"[R9] FAIL shared resolver call sites: dbg={dbg_calls} mut={mut_calls} "
                "(want >= 2 in total, incl. the mutation side)")
        else:
            findings.append(
                f"[R9] PASS single resolver reached from {dbg_calls + mut_calls} call sites")
    # [R10] [fix/dv23-a5] The debug RPCs must answer "is this session empty?"
    # WITHOUT reading parts_json. Every chat DAO query that pulls the column is
    # derived from ChatDao.kt's own @Query bodies (not hand-listed, so a new
    # blob-reading query is covered the day it lands). Anything in debug/
    # calling one directly walks straight past the 2 MB CursorWindow ceiling
    # that ChatRepository.loadPageTolerant exists to hold: one >2 MB
    # historical row aborts the whole RPC with SQLiteBlobTooBigException.
    dao_path = os.path.join(root, DAO_REL)
    parts_readers = {}
    if not os.path.exists(dao_path):
        findings.append("[R10] FAIL missing ChatDao.kt - cannot derive the parts_json read surface")
    else:
        parts_readers = derive_parts_readers(read(dao_path))
        if not parts_readers:
            findings.append(
                "[R10] FAIL no parts_json-reading query derived from ChatDao.kt "
                "(fail-closed: the read surface could not be established)")
    if parts_readers:
        hits = []
        # [B7-t03] The judged fact is "the DAO object is reached", not "the
        # literal `.dao.` appears". Two spellings carried the same defect past
        # the old regex: the DAO bound to a local (`val rawDao = repo.dao;
        # rawDao.lastMessageParts(…)`) and a reader with no tolerant wrapper on
        # ChatRepository at all (there is no `repo.<name>` funnel to hide behind).
        repo_path = os.path.join(root, CHATREPO_REL)
        repo_funs = set()
        if os.path.exists(repo_path):
            try:
                repo_funs = {m.group(1) for m in FUN_RE.finditer(code_of(read(repo_path)))}
            except KotlinLexError:
                findings.append(
                    "[R10] FAIL cannot parse ChatRepository.kt (fail-closed): the tolerant "
                    "wrapper surface cannot be established")
        else:
            findings.append("[R10] FAIL missing ChatRepository.kt — cannot tell a wrapped "
                            "reader from a raw one (fail-closed)")
        for path in DEBUG_BLOB_PARTY_RELS:
            p10 = os.path.join(root, path)
            if not os.path.exists(p10):
                continue
            code = strip_kotlin_comments(read(p10))
            bound = dao_bound_names(code)
            for name in parts_readers:
                wrapped = name in repo_funs
                for m in re.finditer(r"(?<![\w`])" + re.escape(name) + r"\s*\(", code):
                    recv = call_receiver(code, m.start())
                    line = code[:m.start()].count(chr(10)) + 1
                    if recv is None:
                        if not wrapped:
                            hits.append(
                                "%s:%d %s( (no tolerant wrapper exists on ChatRepository)"
                                % (path.rsplit('/', 1)[-1], line, name))
                        continue
                    if recv in bound:
                        hits.append(
                            "%s:%d %s( via '%s'" % (path.rsplit('/', 1)[-1], line, name, recv))
        if hits:
            findings.append(
                "[R10] FAIL debug/ reads parts_json outside its tolerant funnel: "
                + ", ".join(sorted(set(hits)))
                + " - one >2 MB historical row aborts the whole RPC with SQLiteBlobTooBigException")
        else:
            findings.append(
                f"[R10] PASS debug/ never calls {len(parts_readers)} parts_json DAO reads directly "
                f"({', '.join(sorted(parts_readers))})")
    return findings



# ── [B7-t03] declaration enumeration through the shared primitives ────────
def iter_functions(text):
    """[(name, decl_start, body_start, body_end)] for every `fun` in [text].

    The old enumeration split the file into line blocks by a hand-written
    declaration regex — so a one-line `fun f() = x` swallowed the next N
    declarations, and a signature wrapped over two lines was invisible. This
    walks `FUN_RE` (shared) and lets `function_body` (shared) decide where each
    body ends; overloads are sliced per declaration, because `function_body`
    always answers for the FIRST match of a name.
    """
    decls = list(FUN_RE.finditer(text))
    out = []
    for i, m in enumerate(decls):
        nxt = decls[i + 1].start() if i + 1 < len(decls) else len(text)
        seg = text[m.start():nxt]
        b = function_body(seg, m.group(1))
        if b is None:
            continue
        out.append((m.group(1), m.start(), m.start() + b[0], m.start() + b[1]))
    return out


def derive_all_functions(root):
    """{name: [(rel, line, body)]} for every production function declaration.

    Needed because a face can delegate its dispatch: after the A-04 fix,
    `countAttachments` only PARSES the row and hands the array to a pure
    counter whose body carries every recognized name. A criterion scoped to a
    single function body would go blind on exactly that refactor, so the
    judged surface is each face plus what it delegates to.
    """
    table = {}
    for path in find_production_files(root):
        code = strip_kotlin_comments(read(path))
        for name, decl, b_start, b_end in iter_functions(code):
            table.setdefault(name, []).append(
                (os.path.relpath(path, root), code[:decl].count("\n") + 1,
                 code[decl:b_end]))
    return table


CALLEE_RE = re.compile(r"(?<![\w.])([A-Za-z_]\w*)\s*\(")


def functions_in_source(code):
    """{name: body} for the function declarations inside one file's source."""
    out = {}
    for name, decl, b_start, b_end in iter_functions(code):
        out.setdefault(name, code[decl:b_end])
    return out


def expand_same_file(body, local):
    """`body` plus (one hop) the bodies of functions it calls IN THE SAME FILE.

    One hop, same file, because that is where a delegated discriminator
    actually lives: a face that spells out `"image"`/`"video"` arms and hands
    the modern names to a sibling helper in the same file is not the defect.
    Cross-file delegation needs no expansion — the discriminating body itself
    is judged where it is declared (after the A-04 fix the names live in the
    pure counter, and THAT body is the one carrying mediaRef).
    """
    out = [body]
    for name in set(CALLEE_RE.findall(body)):
        callee = local.get(name)
        if callee is not None and callee not in out:
            out.append(callee)
    return "\n".join(out)


def count_type_dispatchers(root):
    """How many production functions dispatch a payload's `type` at all.

    Used to tell "the derivation went blind" (RED) apart from "the tree has
    no offending face" (GREEN).
    """
    n = 0
    for path in find_production_files(root):
        code = strip_kotlin_comments(read(path))
        for _name, decl, b_start, b_end in iter_functions(code):
            body = code[decl:b_end]
            if any(r.search(body) for r in TYPE_DISPATCH_RES):
                n += 1
    return n


def iter_image_video_faces(root):
    """[(where, body, local)] for every production function that discriminates
    IMAGE from VIDEO by legacy part name.

    That discrimination is the invariant, not "touches partsJson": a body
    that answers "is this an image or a video?" is making a claim about
    persisted rows, and every row written since the mediaRef migration carries
    the current serial name instead of the pre-migration one. Seeding on
    partsJson would also have missed the fixed shape (the entry point now only
    parses, the names live in the pure counter) and would have to depend on
    call-graph expansion to see the delegated body.

    A face that treats image and video IDENTICALLY (`sanitizeChatParts` drops
    both, `extractText` only reads `"text"`) is out of family on purpose: it
    asserts nothing per-kind, so demanding mediaRef there would be noise.
    """
    for path in find_production_files(root):
        code = strip_kotlin_comments(read(path))
        # Comments are stripped first, so an explanatory KDoc that merely
        # NAMES serial names cannot satisfy the judgment.
        local = functions_in_source(code)
        for name, decl, b_start, b_end in iter_functions(code):
            body = code[decl:b_end]
            # Must actually dispatch a payload's `type` — otherwise this is a
            # modality badge/prompt helper that merely mentions `"image"` and
            # `"video"` as capability strings (17 such faces measured).
            if not any(r.search(body) for r in TYPE_DISPATCH_RES):
                continue
            body_lines = body.split("\n")
            img = {i for i, l in enumerate(body_lines) if any(s in l for s in LEGACY_IMAGE_NAMES)}
            vid = {i for i, l in enumerate(body_lines) if any(s in l for s in LEGACY_VIDEO_NAMES)}
            if not (img and vid and img != vid):
                continue
            yield f"{os.path.relpath(path, root)} {name}()", body, local


def find_production_files(root):
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        if "/.git" in dirpath or "/test/" in dirpath or "/androidTest/" in dirpath:
            continue
        if "/src/main/" not in dirpath.replace(os.sep, "/"):
            continue
        for fn in filenames:
            if fn.endswith(".kt"):
                out.append(os.path.join(dirpath, fn))
    return out


# ── [R11 / C-EXP] every parts_json name-dispatch must handle "mediaRef" ────
# The persisted vocabulary is the @SerialName set of ContentPart.kt; a reader
# that dispatches on the part name and omits "mediaRef" silently counts /
# renders / exports nothing for every row written today.
MEDIA_REF_NAME = '"mediaRef"'
# The legacy pre-migration names a row may still carry; a face that keeps them
# alongside the current serial name is correct, one that knows ONLY them
# quantifies rows that no longer exist.
LEGACY_IMAGE_NAMES = ('"image"', '"image_url"')
LEGACY_VIDEO_NAMES = ('"video"', '"video_url"')
# Marks that a payload really is a persisted parts row rather than some other
# JSON array (an alarm list, a GitHub tree, an OpenAI content block...). The
# COLUMN itself, plus how every real reader reaches it.
PARTS_PAYLOAD_MARKS = ("partsJson", "parts_json", "m.partsJson", "row.partsJson")
TYPE_DISPATCH_RES = (
    re.compile(r'\boptString\(\s*"type"\s*\)'),
    re.compile(r'\bgetString\(\s*"type"\s*\)'),
    re.compile(r'\bopt\(\s*"type"\s*\)'),
)


def derive_parts_name_readers(source):
    """[(line_no, name, body)] for every function in `source` that touches a
    persisted parts payload and dispatches on its `type`.

    Derived bottom-up rather than hand-listed: after the mediaRef migration
    most faces handled the new name and one did not, so a list written against
    today's tree would simply omit tomorrow's face.
    """
    out = []
    for name, decl, b_start, b_end in iter_functions(source):
        body = source[decl:b_end]
        payload = any(m in body for m in PARTS_PAYLOAD_MARKS)
        dispatch = any(r.search(body) for r in TYPE_DISPATCH_RES)
        if not (payload and dispatch):
            continue
        out.append((source[:decl].count("\n") + 1, name, body))
    return out


# A face "recognizes" the modern serial name only if the name appears on a
# line that DISPATCHES on it. Merely mentioning it anywhere in the body is
# not enough: a `toolResult` arm that reads `value.optJSONObject("mediaRef")`
# mentions the name without ever classifying a mediaRef ROW — so a face whose
# only mention is such a lookup would satisfy a naive substring check while
# still counting every mediaRef row as nothing (the very defect R11 grades).
#
# [meta-audit-1024 / M5] DATAFLOW axis. Naming the arm is not counting the row;
# `"mediaRef" -> Unit` and a discarded nested read throw the value away.
# [batch7 B7-t03] FAMILY-HOLE axis. The arm MOVES a counter but by ZERO —
# `vid += 0 else img += 0` — and the move sits behind a constant guard. Both
# keep every spelling this gate once checked and quantify nothing.
# So the judgment is the FLOW plus the VALUE plus the PATH: the arm must move a
# counter by a possibly-non-zero amount, on a reachable line.
ACCUM_INC_RE = re.compile(r"\b\w+\s*\+=(?!=)")
ACCUM_SUM_RE = re.compile(r"\b(\w+)\s*=\s*\1\s*\+")
ACCUM_PP_RE = re.compile(r"\b\w+\s*\+\+|\+\+\s*\w+")
NESTED_READ_RE = re.compile(r"(?:opt|get)(?:JSONObject)?\(\s*\"mediaRef\"\s*\)")


def dispatch_arm_span(surface, part_name):
    """(start, end) of the arm dispatching on [part_name], or None.

    Two dispatch shapes exist in this codebase, both located structurally now:
    a `when` arm (`"mediaRef" -> …`, brace-balanced through `match_bracket`)
    and an equality test (`type == "mediaRef"`, its own line).
    """
    key = part_name.strip('"')
    for _subject, cond, b_start, b_end in when_branches(surface):
        if contains_ident(cond, key):
            return arm_extent(surface, b_start, b_end)
    for m in re.finditer(r"==\s*" + re.escape(part_name), surface):
        line_start = surface.rfind("\n", 0, m.start()) + 1
        nl = surface.find("\n", m.end())
        return line_start, (len(surface) if nl < 0 else nl)
    return None


def dispatch_arm(body, part_name):
    """Text of the arm that dispatches on [part_name], or None."""
    span = dispatch_arm_span(body, part_name)
    if span is None:
        return None
    return body[span[0]:span[1]]


def _nonzero_delta(text, expr_start):
    """Is the `+= <expr>` / `= x + <expr>` delta possibly non-zero?

    `vid += 0` is a counter move that cannot move the counter — the whole
    structural zero this gate exists to remove, wearing the spelling of a fix.
    A non-constant operand stays "possibly non-zero" (fail-open on the value,
    because an unknown expression may well be 1).
    """
    e = expr_end(text, expr_start)
    seg = text[expr_start:e]
    # `vid += 0 else img += 0` — the delta ends at the arm's `else`, not at the
    # end of the line: without this cut the whole `0 else img += 0` becomes one
    # (unknown, hence "possibly non-zero") expression.
    cut = re.search(r"\belse\b", seg)
    if cut:
        seg = seg[:cut.start()]
    raw = strip_parens(seg.strip().rstrip(",").strip())
    cv = const_value(raw)
    if cv is None:
        return True
    try:
        return not (cv[0] == "num" and float(cv[1]) == 0.0)
    except (TypeError, ValueError, IndexError):
        return True


def arm_accumulates_at(text, start, end, local=None, depth=1):
    """Does the arm at [start,end) MOVE a counter, by a non-zero amount, live?

    Directly or through a same-file helper (one hop), mirroring
    [expand_same_file].
    """
    for m in ACCUM_INC_RE.finditer(text, start, end):
        if path_feasible(text, m.end()) and _nonzero_delta(text, m.end()):
            return True
    for m in ACCUM_SUM_RE.finditer(text, start, end):
        if path_feasible(text, m.end()) and _nonzero_delta(text, m.end()):
            return True
    for m in ACCUM_PP_RE.finditer(text, start, end):
        if path_feasible(text, m.start()):
            return True
    if local and depth > 0:
        body = text[start:end]
        for name in set(CALLEE_RE.findall(body)):
            callee = local.get(name)
            if callee and callee != body:
                if arm_accumulates_at(callee, 0, len(callee), None, depth - 1):
                    return True
    return False


def arm_accumulates(arm, local=None):
    """Does [arm] MOVE a counter — directly, or through a same-file helper?"""
    if not arm:
        return False
    return arm_accumulates_at(arm, 0, len(arm), local)


def nested_read_consumed(arm):
    """Is the nested mediaRef read BOUND or PASSED ON, rather than discarded?

    Three consuming shapes, all of them live in this codebase:
      - bound and used:  `val media = value?.optJSONObject("mediaRef")` …
                         later `isVideoMediaRef(media)`
      - passed inline:   `isVideoMediaRef(value?.optJSONObject("mediaRef"))`
      - chained:         `… .optJSONObject("mediaRef")?.let { … }`
    A bare statement (`value?.optJSONObject("mediaRef")`) is none of them: the
    read happens, the value is thrown away, nothing is counted.
    """
    for m in NESTED_READ_RE.finditer(arm):
        if not path_feasible(arm, m.start()):
            continue
        line_start = arm.rfind("\n", 0, m.start()) + 1
        nl = arm.find("\n", m.end())
        line_end = len(arm) if nl < 0 else nl
        before = arm[line_start:m.start()]
        after = arm[m.end():line_end]
        bm = re.search(r"\b(?:val|var)\s+(\w+)\s*=\s*[^=\n]*$", before)
        if bm:
            if contains_ident(arm[m.end():], bm.group(1)):
                return True
            continue
        if before.count("(") > before.count(")"):
            return True          # the read sits inside another call's argument list
        if re.match(r"\s*\?\.\s*(?:let|also|run|takeIf)\b", after):
            return True
    return False


def tool_result_arm(body):
    """Arm text for the `"toolResult"` dispatch, or None."""
    return dispatch_arm(body, '"toolResult"')

def check_tool_result_media_ref(root):
    """[R12 / C-EXP-NESTED] a face that quantifies media must also count the
    media a TOOL produced.

    A tool result is persisted as a `"toolResult"` part whose `value` carries
    an optional `mediaRef` (see `ToolResult.mediaRef`). A media-counting face
    that dispatches on `"toolResult"` but never looks inside it for a
    `mediaRef` counts every tool-produced image/video as nothing — the same
    structural zero R11 grades, reached through a different door. R11 only
    sees the TOP-LEVEL dispatch arm, so without this criterion a face can
    satisfy R11 and still drop every screenshot a tool returned.

    The judgment is derived, not hand-listed: a face is in the family when
    it dispatches on the `"toolResult"` part name AND attaches quantification
    (splits image from video by part name). The nested lookup must be inside
    the toolResult arm — a `mediaRef` lookup in a different arm does not
    classify a tool result's media.
    """
    tool_rows, missing_rows, silent_rows = [], [], []
    dispatchers = count_type_dispatchers(root)
    if dispatchers == 0:
        return ["[R12] FAIL no `type` dispatcher derived from the tree "
                "(fail-closed: the reader set could not be established)"]
    for where, surface, local in iter_image_video_faces(root):
        if '"toolResult"' not in surface:
            continue
        tool_rows.append(where)
        if not tool_result_arm_reads_media_ref(surface):
            missing_rows.append(where)
        elif not tool_result_arm_counts(surface, local):
            # reads the nested mediaRef but throws the value away
            silent_rows.append(where)
    if not tool_rows:
        return [
            f"[R12] PASS no media-quantifying parts_json face dispatches on "
            f"{chr(34)}toolResult{chr(34)} ({dispatchers} type dispatchers derived) "
            "— nothing to count there"
        ]
    findings = []
    if missing_rows:
        findings.append(
            f"[R12] FAIL {len(missing_rows)}/{len(tool_rows)} media-quantifying "
            f"parts_json faces dispatch on {chr(34)}toolResult{chr(34)} without "
            f"reading its nested mediaRef — {', '.join(missing_rows)}. A tool that "
            "returns media (a screenshot, a clip) persists it as "
            "toolResult.value.mediaRef, so such a face counts every tool-produced "
            "image/video as nothing — the same structural zero R11 grades, "
            "reached through a different door."
        )
    if silent_rows:
        findings.append(
            f"[R12] FAIL {len(silent_rows)} media-quantifying parts_json faces read "
            f"the nested mediaRef of their toolResult arm and DISCARD it — "
            f"{', '.join(silent_rows)}. The lookup runs, its result reaches no "
            "counter (a bare `value?.optJSONObject(\"mediaRef\")` statement, or a "
            "binding nothing uses), so tool-produced images/videos still count as "
            "zero while the arm looks correct."
        )
    if findings:
        return findings
    return [
        f"[R12] PASS all {len(tool_rows)} media-quantifying parts_json faces bind "
        f"and count the nested mediaRef of their toolResult arm "
        f"({', '.join(tool_rows)})"
    ]

def tool_result_arm_reads_media_ref(body):
    """Does the toolResult arm of [body] read a nested mediaRef?

    Arm-scoped (brace-balanced): a `mediaRef` lookup inside the toolResult arm
    counts; one in a sibling arm does not.
    """
    arm = tool_result_arm(body)
    return bool(arm) and MEDIA_REF_NAME in arm


def tool_result_arm_counts(body, local=None):
    """Does the toolResult arm CONSUME the nested read and MOVE a counter?

    [meta-audit-1024 / M5] The spelling criterion above is satisfied by a bare
    `value?.optJSONObject("mediaRef")` statement — the read happens, the value
    is discarded, nothing is counted, and every tool-produced image/video is
    still structurally zero. Consuming the read (bound/used, passed inline, or
    chained) AND moving a counter are the two halves of "this face actually
    quantifies tool media"; either one missing means the value never reaches a
    counter.
    """
    arm = tool_result_arm(body)
    if not arm:
        return False
    return nested_read_consumed(arm) and arm_accumulates(arm, local)

def check_media_ref_dispatch(root):
    """[R11 / C-EXP] every parts_json face that ATTACHES quantization has to
    recognize the mediaRef names.

    Derivation: a counter/reporter that distinguishes image from video by
    part name is asserting a claim about persisted rows, and only the
    mediaRef names describe the rows being written today (the legacy names
    describe pre-migration rows). A face that splits image/video and doesn't
    recognize mediaRef reports structural zeros. The Family Is Identified By
    The DISCRIMINATION, not by the function name — `extractText` only appends
    `type == "text"`, `isInterruptedTail` only maps kinds, `sanitizeChatParts`
    drops both image and video identically. Those three don't quantify media,
    so demanding mediaRef handling there would only add noise; the property
    that matters is "image vs video, distinguished by part name".
    """
    legacy_rows, both_rows, absent_rows, silent_rows = [], [], [], []
    dispatchers = 0
    for where, surface, local in iter_image_video_faces(root):
        body_lines = surface.split("\n")
        img = {i for i, l in enumerate(body_lines) if any(s in l for s in LEGACY_IMAGE_NAMES)}
        vid = {i for i, l in enumerate(body_lines) if any(s in l for s in LEGACY_VIDEO_NAMES)}
        if not (img and vid and img != vid):
            continue  # does not discriminate image vs video by part name
        arm = dispatch_arm(surface, MEDIA_REF_NAME)
        if arm is None:
            legacy_rows.append(where)
        elif not arm_accumulates(arm, local):
            # dispatches on the modern name but the arm moves no counter:
            # `"mediaRef" -> Unit` and friends — the row is still counted as 0
            silent_rows.append(where)
        else:
            both_rows.append(where)
        absent_rows.append(where)
    dispatchers = count_type_dispatchers(root)
    if dispatchers == 0:
        # Fail-closed means "the DERIVATION went blind", not "the tree happens
        # to have no offending face". A tree with no `type` dispatcher at all
        # cannot be judged by this criterion, so it is RED. A tree that HAS
        # dispatchers but none in the family is GREEN (nothing violates) —
        # conflating the two would make the precision arm ungradeable.
        return ["[R11] FAIL no `type` dispatcher derived from the tree "
                "(fail-closed: the reader set could not be established)"]
    if not absent_rows:
        return [
            f"[R11] PASS no image/video-discriminating parts_json face in the tree "
            f"({dispatchers} type dispatchers derived, none discriminates image vs "
            "video by part name) — nothing to classify"]

    findings = []
    if legacy_rows:
        findings.append(
            f"[R11] FAIL {len(legacy_rows)}/{len(absent_rows)} image/video parts_json "
            f"faces split on the LEGACY part names only — {', '.join(legacy_rows)}. "
            f"Rows persisted today carry {MEDIA_REF_NAME} "
            f"(`{(MEDIA_REF_NAME,)}` plus nested toolResult variants), so this face "
            "quantifies nothing that exists: the export counters were summed per "
            "batch, written into session.json, and never incremented once."
        )
    if silent_rows:
        findings.append(
            f"[R11] FAIL {len(silent_rows)} image/video parts_json faces dispatch on "
            f"{MEDIA_REF_NAME} and MOVE NO COUNTER — {', '.join(silent_rows)}. The "
            "arm names the modern part (so a name-matching check passes) but its "
            "body never increments an accumulator: `\"mediaRef\" -> Unit`, or a "
            "branch that only reads. Every mediaRef row is still counted as zero — "
            "the structural zero this criterion grades, reached without dropping "
            "the name."
        )
    if findings:
        return findings
    return [
        f"[R11] PASS all {len(both_rows)} image/video parts_json faces classify "
        f"{MEDIA_REF_NAME} alongside the legacy names and move their counters "
        f"({', '.join(both_rows)})"
    ]


def check(root):
    findings = []
    src = "src/android/app/src/main/java/com/rikkaminis/app"
    chatrepo_path = os.path.join(root, src, "data/repository/ChatRepository.kt")
    backup_path = os.path.join(root, src, "backup/ConfigBackup.kt")
    for p in (chatrepo_path, backup_path):
        if not os.path.exists(p):
            return FAIL, [f"missing file: {p}"]
    chatrepo = read(chatrepo_path)
    backup = read(backup_path)
    try:
        chatrepo_code = code_of(chatrepo)
        backup_code = code_of(backup)
    except KotlinLexError as exc:
        return FAIL, ["[R1] FAIL cannot parse ChatRepository/ConfigBackup (fail-closed): %s" % exc]

    # [R1] the paged DAO shapes have exactly one call site each, inside loadPageSlice
    other_hits = []
    for path in find_production_files(root):
        t = read(path)
        if path == chatrepo_path:
            continue
        for regex in (PARTS_RE, RANGE_RE):
            for m in regex.finditer(t):
                other_hits.append(f"{os.path.relpath(path, root)}:{t[:m.start()].count(chr(10)) + 1}")
    if other_hits:
        findings.append(f"[R1] FAIL paged DAO call outside ChatRepository: {other_hits}")
    else:
        findings.append("[R1] PASS no paged DAO call outside ChatRepository")

    n_page = len(PARTS_RE.findall(chatrepo))
    n_range = len(RANGE_RE.findall(chatrepo))
    slice_region = region_between(
        chatrepo, "private suspend fun loadPageSlice(", r"\n    (private |internal )?(suspend )?fun ")
    rbr_region = region_between(
        chatrepo, "private suspend fun loadPageRowByRow(", r"\n    (private |internal )?(suspend )?fun ")
    if slice_region is None or rbr_region is None:
        findings.append("[R1] FAIL loadPageSlice/loadPageRowByRow not found in ChatRepository")
    elif n_page == 1 and n_range == 1 and PARTS_RE.search(slice_region) and RANGE_RE.search(slice_region):
        findings.append("[R1] PASS loadPageSlice is the single funnel (1 + 1 DAO calls)")
    else:
        findings.append(
            f"[R1] FAIL paged DAO calls: page={n_page} range={n_range}, "
            f"page-in-slice={bool(slice_region and PARTS_RE.search(slice_region))}, "
            f"range-in-slice={bool(slice_region and RANGE_RE.search(slice_region))}"
        )

    # [R2] tolerant pager: 1 definition + 3 call sites, all entries routed
    tolerant_count = len(re.findall(r"loadPageTolerant\(", chatrepo))
    if tolerant_count == 4:
        findings.append("[R2] PASS loadPageTolerant = 1 definition + 3 call sites")
    else:
        findings.append(f"[R2] FAIL loadPageTolerant occurrences = {tolerant_count}, expected 4")
    for site in ("loadPageTolerant(sessionId, offset, limit)", "loadPageTolerant(sessionId, offset, limit, startMs, endMs)"):
        if site in chatrepo:
            findings.append(f"[R2] PASS routed: `{site}`")
        else:
            findings.append(f"[R2] FAIL missing routing: `{site}`")

    # [R3] row-by-row fallback also funnels
    if rbr_region is None:
        findings.append("[R3] FAIL loadPageRowByRow not found")
    elif PARTS_RE.search(rbr_region) or RANGE_RE.search(rbr_region):
        findings.append("[R3] FAIL loadPageRowByRow calls the DAO directly")
    else:
        findings.append("[R3] PASS loadPageRowByRow routes through the funnel")

    # [R4] updateMessageParts writes through the ceiling
    upd_region = region_between(
        chatrepo, "suspend fun updateMessageParts(", r"\n    (private |internal )?(suspend )?fun ")
    if upd_region is None:
        findings.append("[R4] FAIL updateMessageParts not found")
    elif "dao.updateMessageParts(id, capPartsJsonForRow(partsJson))" in upd_region:
        findings.append("[R4] PASS updateMessageParts caps what reaches the DAO")
    else:
        findings.append("[R4] FAIL updateMessageParts writes the payload verbatim")

    # [R5] reasoning ceiling is a single companion funnel that DOES the ceiling
    # [B7-t03] anchored on the body's three facts, not on the signature.
    bad_funnels = []
    for fn, truncator, _ident in FUNNEL_SPECS:
        for why in ceiling_funnel_violations(chatrepo_code, fn, truncator):
            bad_funnels.append("%s: %s" % (fn, why))
    if bad_funnels:
        findings.append(
            "[R5] FAIL the companion ceiling funnel no longer applies its ceiling — "
            + "; ".join(bad_funnels)
            + ". A funnel whose body returns the input verbatim keeps every call "
            "site wired and every oversized row uncapped (Issue #17 / "
            "SQLiteBlobTooBigException on the next read).")
    else:
        findings.append(
            "[R5] PASS capPartsJsonForRow / capReasoningContentForRow compare the "
            "byte ceiling, truncate on a live path, and return the truncated value")
    append = function_body(chatrepo_code, "appendMessage")
    if append is None:
        findings.append("[R5] FAIL appendMessage not found")
    else:
        a_start, a_end, _k = append
        append_body = chatrepo_code[a_start:a_end]
        append_lit = strip_kotlin_comments(chatrepo)[a_start:a_end]
        inline = len(re.findall(r"toByteArray\s*\(\s*Charsets\.UTF_8\s*\)", append_body))
        wiring = funnel_bypasses(
            append_body, append_lit, "reasoningContent", "capReasoningContentForRow")
        if inline:
            findings.append(
                "[R5] FAIL appendMessage re-derives the reasoning byte ceiling inline "
                "(the comparison must live only in the companion funnel)")
        elif wiring:
            findings.append(
                "[R5] FAIL appendMessage binds reasoningContent without the funnel — "
                + "; ".join("line %d: %s" % w for w in wiring[:3]))
        else:
            findings.append(
                "[R5] PASS appendMessage routes reasoning through the funnel")

    # [R6] import caps both columns
    # [R6] import caps both columns — judged on the VALUE that reaches the row,
    # not on the presence of the cap call somewhere in the function. [B7-t03]
    ics = function_body(backup_code, "importChatSections")
    if ics is None:
        findings.append("[R6] FAIL importChatSections not found in ConfigBackup")
    else:
        i_start, i_end, _k = ics
        ics_body = backup_code[i_start:i_end]
        bad_cols = []
        ics_lit = strip_kotlin_comments(backup)[i_start:i_end]
        for fn, _trunc, ident in FUNNEL_SPECS:
            found = funnel_bypasses(ics_body, ics_lit, ident, fn)
            if not found:
                continue
            for line, src in found:
                bad_cols.append("%s line %d: %s" % (ident, line, src))
        if bad_cols:
            findings.append(
                "[R6] FAIL backup import stores an uncapped payload — "
                + "; ".join(bad_cols[:4])
                + ". The cap call is present but its result is discarded, so a "
                "restored multi-MB row crashes the next read with "
                "SQLiteBlobTooBigException.")
        else:
            findings.append("[R6] PASS import caps both columns before insertMessage")

    # [da1032-B] read-side debug RPC view of the very same rows
    findings.extend(check_debug_surface(root))

    # [da1047-A4 / C-EXP] every parts_json name dispatcher handles "mediaRef"
    findings.extend(check_media_ref_dispatch(root))
    findings.extend(check_tool_result_media_ref(root))

    if any(f.startswith(("FAIL", "[R")) and " FAIL " in f for f in findings):
        return FAIL, findings
    return OK, findings


def build_fixture(root, chatrepo_body, configbackup_body):
    """Lay out a minimal fake tree shaped like the production sources."""
    src = os.path.join(root, "src/android/app/src/main/java/com/rikkaminis/app")
    os.makedirs(os.path.join(src, "data/repository"), exist_ok=True)
    os.makedirs(os.path.join(src, "backup"), exist_ok=True)
    with open(os.path.join(src, "data/repository/ChatRepository.kt"), "w", encoding="utf-8") as f:
        f.write(chatrepo_body)
    with open(os.path.join(src, "backup/ConfigBackup.kt"), "w", encoding="utf-8") as f:
        f.write(configbackup_body)
    return root


PREFIX_REPO = """class Repo {
    suspend fun loadMessagePage(sessionId: String, offset: Int, limit: Int,
                                maxChars: Int, startMs: Long?, endMs: Long?): List<MessageEntity> {
        val rows = if (startMs == null && endMs == null) {
            dao.loadMessagesPage(sessionId, offset, limit)
        } else {
            dao.loadMessagesPageInRange(sessionId, offset, limit, startMs, endMs)
        }
        return rows.mapNotNull { e -> extract(e) }
    }

    suspend fun updateMessageParts(id: String, partsJson: String) =
        dao.updateMessageParts(id, partsJson)

    suspend fun appendMessage(sessionId: String, partsJson: String, reasoningContent: String?): Unit {
        val capped = partsJson
        val cappedReasoning = reasoningContent?.let { rc ->
            if (rc.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_PARTS_JSON_BYTES) {
                buildTruncatedText(rc)
            } else {
                rc
            }
        }
    }
}
"""

PREFIX_IMPORT = """internal suspend fun importChatSections(
    chatRepo: ChatRepository,
    chatMessagesArr: JSONArray?,
    skipped: MutableList<String>,
): Pair<Int, Int> {
    val message = MessageEntity(
        partsJson = m.optString("partsJson"),
    )
    return 0 to 0
}
"""


def build_debug_fixture(root, debug_body, mutation_body, dao_body=None):
    """Lay out the two debug RPC files [R7]-[R9] read, plus the DAO [R10] derives from."""
    debug_dir = os.path.join(root, "src/android/app/src/main/java/com/rikkaminis/app/debug")
    os.makedirs(debug_dir, exist_ok=True)
    with open(os.path.join(debug_dir, "ChatDebugMethods.kt"), "w", encoding="utf-8") as f:
        f.write(debug_body)
    with open(os.path.join(debug_dir, "ChatMutationMethods.kt"), "w", encoding="utf-8") as f:
        f.write(mutation_body)
    dao_path = os.path.join(root, DAO_REL)
    os.makedirs(os.path.dirname(dao_path), exist_ok=True)
    with open(dao_path, "w", encoding="utf-8") as f:
        f.write(DAO_GREEN if dao_body is None else dao_body)
    # [B7-t03] [R10] now distinguishes a tolerant wrapper from a raw read by
    # asking ChatRepository what it wraps, so every fixture needs its surface.
    repo_path = os.path.join(root, CHATREPO_REL)
    os.makedirs(os.path.dirname(repo_path), exist_ok=True)
    with open(repo_path, "w", encoding="utf-8") as f:
        f.write(REPO_GREEN)
    return root


# [R10] the tolerant wrappers ChatRepository exposes. A reader named here may be
# reached as `repo.<name>(…)`; a reader NOT named here has no funnel to hide
# behind and any call site is a raw DAO read.
REPO_GREEN = """package com.rikkaminis.app.data.repository

class ChatRepository(private val dao: ChatDao) {
    suspend fun messageCount(sessionId: String): Int = 0
    suspend fun loadMessages(sessionId: String): List<MessageEntity> = emptyList()
    suspend fun getMessage(id: String): MessageEntity? = null
    suspend fun lastMessageParts(sessionId: String): String? = null
    suspend fun loadMessagesPage(sessionId: String, limit: Int): List<MessageEntity> = emptyList()
    suspend fun loadMessagesPageInRange(a: Int, b: Int): List<MessageEntity> = emptyList()
    suspend fun loadUserMessagesSince(sessionId: String, since: Long): List<MessageEntity> = emptyList()
}
"""


# --- [R7]-[R9] fixtures ------------------------------------------------------
# All three judgments read the same two shipped files; the fixtures below are
# minimally-shaped stand-ins whose only job is to make one arm flip.

# [R10] the DAO the derivation walks. Shaped like ChatDao: a blob-naming read,
# a star-over-messages read, a star-over-sessions read (NOT a blob read — this
# is the control that keeps the star rule from swallowing every query), and an
# aggregate that never materialises the column.
DAO_GREEN = """package com.rikkaminis.app.data.db

interface ChatDao {
    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    suspend fun listSessions(): List<ChatSessionEntity>

    @Query(\"\"\"
        SELECT parts_json FROM messages
        WHERE session_id = :sessionId
        ORDER BY sort_order DESC LIMIT 1
    \"\"\")
    suspend fun lastMessageParts(sessionId: String): String?

    @Query("SELECT * FROM messages WHERE session_id = :sessionId")
    suspend fun loadMessages(sessionId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun messageCountForSession(sessionId: String): Int
}
"""

# [R10] a DAO that gained a brand-new blob read the gate has never been told
# about — the derivation must pick it up on its own (no hand-list to update).
DAO_NEW_BLOB_READ = DAO_GREEN.replace(
    '    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")',
    '    @Query("SELECT * FROM messages WHERE session_id = :sessionId AND role = :role")\n'
    '    suspend fun messagesByRole(sessionId: String, role: String): List<MessageEntity>\n'
    '\n'
    '    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")',
)

DEBUG_GREEN = """package com.rikkaminis.app.debug

internal object ChatDebugMethods {
    private fun provider(context: Context): ProviderRepository = TODO()

    /**
     * KDoc that names "mediaRef" and "toolUse" without dispatching on them —
     * the [R7] judgment must ignore it (comments are stripped).
     */
    suspend fun sessionsList(context: Context, params: JSONObject): JSONObject {
        val includeEmpty = params.optBoolean("includeEmpty", false)
        val provRepo = provider(context)
        for (session in all) {
            // [fix/dv23-a5] the emptiness probe is a COUNT, never a blob read
            if (!includeEmpty && repo.messageCount(session.id) == 0) continue
            arr.put(sessionCoreJson(session, provRepo))
        }
        return JSONObject().apply { put("count", emitted) }
    }

    suspend fun sessionsGet(context: Context, params: JSONObject): JSONObject {
        val provRepo = provider(context)
        val messageCount = repo.messageCount(sessionId)
        return sessionCoreJson(session, provRepo).apply {
            put("messageCount", messageCount)
        }
    }

    private fun sessionCoreJson(session: ChatSessionEntity, provRepo: ProviderRepository): JSONObject =
        JSONObject().apply {
            put("id", session.id)
            put("title", session.title ?: JSONObject.NULL)
            put("modelId", session.modelId)
            put("modelName", resolveModelDisplay(provRepo, session.modelId))
            put("category", session.category ?: JSONObject.NULL)
            put("pinnedAt", session.pinnedAt ?: JSONObject.NULL)
            put("lastMessagePreview", session.lastMessage ?: JSONObject.NULL)
            put("createdAt", session.createdAt)
        }

    internal fun messageToJson(m: MessageEntity, includeTools: Boolean, includeReasoning: Boolean): JSONObject {
        val obj = JSONObject()
        val parts = org.json.JSONArray(m.partsJson)
        if (parts != null) {
            obj.put("parts", parts)
            val sb = StringBuilder()
            val tools = JSONArray()
            val attachments = JSONArray()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                when (p.optString("type")) {
                    "text" -> sb.append(p.optString("value", ""))
                    "toolUse", "tool_call", "tool_use" -> if (includeTools) tools.put(p)
                    "toolResult", "tool_result" -> if (includeTools) tools.put(p)
                    "mediaRef", "image", "file", "attachment" -> attachments.put(p)
                }
            }
            if (sb.isNotEmpty()) obj.put("content", sb.toString())
            if (tools.length() > 0) obj.put("toolCalls", tools)
            if (attachments.length() > 0) obj.put("attachments", attachments)
        }
        return obj
    }

    fun modelsList(context: Context, params: JSONObject): JSONObject {
        val inputs = effective.inputModalities ?: emptyList()
        return JSONObject().apply {
            put("supportsImages", "image" in inputs)
            put("kind", "text")
        }
    }

    internal fun resolveModelDisplay(repo: ProviderRepository, modelId: String): String {
        val cfg = repo.config.value
        val entry = cfg.modelEntries.firstOrNull { it.baseModel.id == modelId || it.id == modelId }
            ?: return modelId
        return entry.model.displayName
    }
}
"""

MUTATION_GREEN = """package com.rikkaminis.app.debug

internal object ChatMutationMethods {
    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp ?: throw RPCException(-32000, "no app")

    private fun resolveDisplay(context: Context, modelId: String): String {
        return ChatDebugMethods.resolveModelDisplay(app(context).providerRepository, modelId)
    }
}
"""

MUTATION_LOCAL_PREDICATE = """package com.rikkaminis.app.debug

internal object ChatMutationMethods {
    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp ?: throw RPCException(-32000, "no app")

    private fun resolveDisplay(context: Context, modelId: String): String {
        val cfg = app(context).providerRepository.config.value
        val entry = cfg.modelEntries.firstOrNull { it.baseModel.id == modelId } ?: return modelId
        return entry.model.displayName
    }
}
"""

# [R7] line-comment-only mention of a serial name the dispatch does not handle
DEBUG_LINE_COMMENT_ONLY = DEBUG_GREEN.replace(
    '                val p = parts.optJSONObject(i) ?: continue\n',
    '                // handles "mediaRef" via the attachment branch below\n'
    '                val p = parts.optJSONObject(i) ?: continue\n',
).replace('"mediaRef", "image", "file", "attachment" ->', '"image", "file", "attachment" ->')

DEBUG_BLOCK_COMMENT_ONLY = DEBUG_GREEN.replace(
    '                val p = parts.optJSONObject(i) ?: continue\n',
    '                /* "toolUse" and "toolResult" land in tools */\n'
    '                val p = parts.optJSONObject(i) ?: continue\n',
).replace('"toolUse", "tool_call", "tool_use" ->', '"tool_call", "tool_use" ->') \
    .replace('"toolResult", "tool_result" ->', '"tool_result" ->')

DEBUG_MISSING_TOOLUSE = DEBUG_GREEN.replace(
    '"toolUse", "tool_call", "tool_use" ->', '"tool_call", "tool_use" ->')

# [R8] the pre-fix shape: sessionsGet builds its own object, no shared mapper
DEBUG_SESSIONS_INLINE = """package com.rikkaminis.app.debug

internal object ChatDebugMethods {
    suspend fun sessionsList(context: Context, params: JSONObject): JSONObject {
        return JSONObject().apply { put("count", emitted) }
    }

    suspend fun sessionsGet(context: Context, params: JSONObject): JSONObject {
        return JSONObject().apply {
            put("id", session.id)
            put("title", session.title ?: JSONObject.NULL)
            put("messageCount", messageCount)
        }
    }
}
"""

# [R8] mapper exists but a caller re-inlines one of the shared fields anyway
DEBUG_REINLINED_FIELD = DEBUG_GREEN.replace(
    """        return sessionCoreJson(session, provRepo).apply {
            put("messageCount", messageCount)
        }""",
    """        return sessionCoreJson(session, provRepo).apply {
            put("pinnedAt", session.pinnedAt ?: JSONObject.NULL)
            put("messageCount", messageCount)
        }""")

# [R8] mapper exists but lost the field parity this arm pins
DEBUG_MAPPER_DROPS_FIELD = DEBUG_GREEN.replace(
    '            put("lastMessagePreview", session.lastMessage ?: JSONObject.NULL)\n', '')

# [R9] the composite arm dropped -> composite entry ids stop resolving
DEBUG_BARE_PREDICATE = DEBUG_GREEN.replace(
    'it.baseModel.id == modelId || it.id == modelId', 'it.baseModel.id == modelId')

# [R10] the pre-fix shape: the emptiness probe is a raw blob read per session.
DEBUG_PRE_FIX_PROBE = DEBUG_GREEN.replace(
    """            // [fix/dv23-a5] the emptiness probe is a COUNT, never a blob read
            if (!includeEmpty && repo.messageCount(session.id) == 0) continue""",
    """            val lastParts = repo.dao.lastMessageParts(session.id)
            if (!includeEmpty && lastParts == null) continue""",
)

# [R10] the same bypass through a different DAO door: load the whole message
# list (a `SELECT *` over messages) to answer a yes/no question.
DEBUG_RAW_LOADMESSAGES = DEBUG_GREEN.replace(
    """            // [fix/dv23-a5] the emptiness probe is a COUNT, never a blob read
            if (!includeEmpty && repo.messageCount(session.id) == 0) continue""",
    """            if (!includeEmpty && repo.dao.loadMessages(session.id).isEmpty()) continue""",
)

# [R10] a raw read of a DAO query that only exists in DAO_NEW_BLOB_READ.
DEBUG_NEW_BLOB_READ_CALL = DEBUG_GREEN.replace(
    """            // [fix/dv23-a5] the emptiness probe is a COUNT, never a blob read
            if (!includeEmpty && repo.messageCount(session.id) == 0) continue""",
    """            if (!includeEmpty && repo.dao.messagesByRole(session.id, "user").isEmpty()) continue""",
)

# [R10] negative control for over-capture: neither of these can read the blob,
# so a green verdict here proves the gate is not just banning `.dao.` wholesale.
DEBUG_NON_BLOB_CALLS = DEBUG_GREEN.replace(
    """            // [fix/dv23-a5] the emptiness probe is a COUNT, never a blob read
            if (!includeEmpty && repo.messageCount(session.id) == 0) continue""",
    """            val n = repo.dao.messageCountForSession(session.id)
            if (!includeEmpty && n == 0) continue
            val sessions = repo.dao.listSessions()""",
)



# --- [R11 / C-EXP] mediaRef fixtures ---------------------------------------
# The judged surface is DERIVED: a production function that dispatches a
# payload's `type` AND discriminates image from video by part name. The
# fixtures below are minimally-shaped stand-ins whose only job is to make one
# arm flip.

MEDIA_FACE_REL = "src/android/app/src/main/java/com/rikkaminis/app/share/ChatPartsAttachmentCount.kt"

MEDIA_GREEN = """package com.rikkaminis.app.share

import org.json.JSONArray

internal fun countPartsAttachments(arr: JSONArray): Pair<Int, Int> {
    var img = 0
    var vid = 0
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val value = obj.optJSONObject("value")
        when (obj.optString("type")) {
            "mediaRef" -> {
                if (isVideoMediaRef(value)) vid += 1 else img += 1
            }
            "toolResult" -> {
                val media = value?.optJSONObject("mediaRef")
                if (media != null) {
                    if (isVideoMediaRef(media)) vid += 1 else img += 1
                }
            }
            "image", "image_url" -> img += 1
            "video", "video_url" -> vid += 1
        }
    }
    return img to vid
}

internal fun isVideoMediaRef(media: JSONObject?): Boolean {
    if (media == null) return false
    val mime = media.optString("mimeType")
    return mime.startsWith("video/", ignoreCase = true)
}
"""

# The pre-fix shape: the modern serial name is not recognized at all, so every
# row written today quantifies as nothing.
MEDIA_PREFIX = """package com.rikkaminis.app.share

import org.json.JSONArray

internal fun countPartsAttachments(arr: JSONArray): Pair<Int, Int> {
    var img = 0
    var vid = 0
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        when (obj.optString("type")) {
            "image", "image_url" -> img += 1
            "video", "video_url" -> vid += 1
        }
    }
    return img to vid
}
"""

# A second, independent face that ALSO drops the modern name — proves the
# derivation covers more than the one function it was written against.
MEDIA_SECOND_FACE = """
internal fun countVideoParts(arr: JSONArray): Pair<Int, Int> {
    var img = 0
    var vid = 0
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        when (obj.optString("type")) {
            "image", "image_url" -> img += 1
            "video", "video_url" -> vid += 1
        }
    }
    return img to vid
}
"""

# Precision: a JSON reader that dispatches on `type` but treats image and
# video IDENTICALLY asserts nothing per-kind, and a non-parts JSON reader
# that merely mentions "image"/"video" is not a parts face at all. Neither may
# be flagged.
MEDIA_PRECISION = """package com.rikkaminis.app.share

internal fun dropMedia(arr: JSONArray): Int {
    var dropped = 0
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        when (obj.optString("type")) {
            "mediaRef", "image", "image_url", "video", "video_url" -> dropped += 1
        }
    }
    return dropped
}

internal fun modalityBadge(kind: String): String {
    return if (kind == "image") "vision" else if (kind == "video") "motion" else "text"
}
"""


# --- [R12 / C-EXP-NESTED] toolResult nested-media fixtures -------------------
# The toolResult arm must READ the nested mediaRef; a face that dispatches on
# "toolResult" but never looks inside drops every tool-produced image/video.

MEDIA_TOOLRESULT_DROPPED = """package com.rikkaminis.app.share

import org.json.JSONArray

internal fun countPartsAttachments(arr: JSONArray): Pair<Int, Int> {
    var img = 0
    var vid = 0
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val value = obj.optJSONObject("value")
        when (obj.optString("type")) {
            "mediaRef" -> {
                if (isVideoMediaRef(value)) vid += 1 else img += 1
            }
            "toolResult" -> {
                Unit
            }
            "image", "image_url" -> img += 1
            "video", "video_url" -> vid += 1
        }
    }
    return img to vid
}
"""

# A face that reads the nested mediaRef — but OUTSIDE the toolResult arm
# (in the mediaRef arm instead). That must NOT satisfy R12: a tool result's
# media would still be uncounted.
MEDIA_TOOLRESULT_OUTSIDE_ARM = """package com.rikkaminis.app.share

import org.json.JSONArray

internal fun countPartsAttachments(arr: JSONArray): Pair<Int, Int> {
    var img = 0
    var vid = 0
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val value = obj.optJSONObject("value")
        val media = value?.optJSONObject("mediaRef")
        when (obj.optString("type")) {
            "mediaRef" -> {
                if (media != null) {
                    if (isVideoMediaRef(media)) vid += 1 else img += 1
                }
            }
            "toolResult" -> {
                Unit
            }
            "image", "image_url" -> img += 1
            "video", "video_url" -> vid += 1
        }
    }
    return img to vid
}
"""

def build_media_fixture(root, body, extra=None):
    """Lay out the media-counting face(s) [R11] derives from."""
    path = os.path.join(root, MEDIA_FACE_REL)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(body if extra is None else body + extra)
    return root


# --- [meta-audit-1024 / M5] DATAFLOW witnesses ------------------------------
# Adversarial shapes supplied by an external verifier. Both arms still NAME
# the modern part and the toolResult arm still performs the nested lookup, so
# every spelling criterion is satisfied — and nothing is counted.
MEDIA_WITNESS_R11_ARM = (
    '            "mediaRef" -> {\n'
    '                if (isVideoMediaRef(value)) vid += 1 else img += 1\n'
    '            }',
    '            "mediaRef" -> Unit',
)
MEDIA_WITNESS_R12_ARM = (
    '            "toolResult" -> {\n'
    '                val media = value?.optJSONObject("mediaRef")\n'
    '                if (media != null) {\n'
    '                    if (isVideoMediaRef(media)) vid += 1 else img += 1\n'
    '                }\n'
    '            }',
    '            "toolResult" -> {\n'
    '                value?.optJSONObject("mediaRef")\n'
    '            }',
)


def witness_shape(green):
    """[green] with both counting arms replaced by their silent twins."""
    out = green
    for old, new in (MEDIA_WITNESS_R11_ARM, MEDIA_WITNESS_R12_ARM):
        out = out.replace(old, new, 1)
    return out


MEDIA_WITNESS = witness_shape(MEDIA_GREEN)

# Precision: counting DELEGATED to a same-file helper. The arm itself carries
# no `+=`; the helper it calls does. A criterion that only accepts inline
# increments would accuse a legitimate refactor — the same-file hop exists so
# that moving the counters into a helper is not punished.
MEDIA_DELEGATED = """package com.rikkaminis.app.share

import org.json.JSONArray

internal class Counters(var img: Int = 0, var vid: Int = 0)

internal fun countPartsAttachments(arr: JSONArray): Pair<Int, Int> {
    val c = Counters()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        when (obj.optString("type")) {
            "mediaRef" -> countInto(obj.optJSONObject("value"), c)
            "toolResult" -> countInto(obj.optJSONObject("value")?.optJSONObject("mediaRef"), c)
            "image", "image_url" -> c.img += 1
            "video", "video_url" -> c.vid += 1
        }
    }
    return c.img to c.vid
}

internal fun countInto(media: JSONObject?, c: Counters) {
    if (media == null) return
    if (isVideoMediaRef(media)) c.vid += 1 else c.img += 1
}

internal fun isVideoMediaRef(media: JSONObject?): Boolean {
    if (media == null) return false
    val mime = media.optString("mimeType")
    return mime.startsWith("video/", ignoreCase = true)
}
"""



# --- batch7 B7-t03 fixtures: the spliced decision points ---------------------
# 10-1: the funnel keeps its full signature and every call site, and stops
# capping — the body is the identity function.
FUNNEL_REPO_GOOD = """class Repo {
    suspend fun appendMessage(sessionId: String, partsJson: String, reasoningContent: String?): Unit {
        val capped = capPartsJsonForRow(partsJson)
        val cappedReasoning = capReasoningContentForRow(reasoningContent)
        val row = MessageEntity(
            partsJson = capped,
            reasoningContent = cappedReasoning,
        )
    }

    internal fun capPartsJsonForRow(partsJson: String): String {
        if (partsJson.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_PARTS_JSON_BYTES) {
            return buildTruncatedPartsJson(partsJson)
        }
        return partsJson
    }

    internal fun capReasoningContentForRow(reasoningContent: String?): String? =
        reasoningContent?.let { rc ->
            if (rc.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_PARTS_JSON_BYTES) {
                buildTruncatedText(rc)
            } else {
                rc
            }
        }
}
"""

FUNNEL_REPO_IDENTITY = FUNNEL_REPO_GOOD.replace(
    """    internal fun capReasoningContentForRow(reasoningContent: String?): String? =
        reasoningContent?.let { rc ->
            if (rc.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_PARTS_JSON_BYTES) {
                buildTruncatedText(rc)
            } else {
                rc
            }
        }""",
    """    internal fun capReasoningContentForRow(reasoningContent: String?): String? = reasoningContent""",
)

IMPORT_GOOD = """internal suspend fun importChatSections(
    chatRepo: ChatRepository,
    chatMessagesArr: JSONArray?,
    skipped: MutableList<String>,
): Pair<Int, Int> {
    val message = MessageEntity(
        partsJson = ChatRepository.capPartsJsonForRow(m.optString("partsJson")),
        reasoningContent = ChatRepository.capReasoningContentForRow(m.optString("reasoningContent")),
    )
    return 0 to 0
}
"""

# 10-2: the cap call is still there, verbatim; the stored value is the raw one.
IMPORT_LET = IMPORT_GOOD.replace(
    'partsJson = ChatRepository.capPartsJsonForRow(m.optString("partsJson")),',
    'partsJson = ChatRepository.capPartsJsonForRow(m.optString("partsJson"))'
    '.let { m.optString("partsJson") },',
)

# 10-3: the serial name and its dispatch arm survive; the arm drops the part.
DEBUG_MEDIA_REF_UNIT = DEBUG_GREEN.replace(
    '"mediaRef", "image", "file", "attachment" -> attachments.put(p)',
    '"mediaRef", "image", "file", "attachment" -> Unit',
)

# 10-4: the DAO is reached through a local binding instead of `.dao`.
DEBUG_DAO_ALIAS = DEBUG_GREEN.replace(
    "if (!includeEmpty && repo.messageCount(session.id) == 0) continue",
    "val rawDao = repo.dao\n"
    "            if (!includeEmpty && rawDao.lastMessageParts(session.id) == null) continue",
)

# 10-5: the counter move survives, its amount is zero.
MEDIA_ZERO_DELTA = MEDIA_GREEN.replace(
    "if (isVideoMediaRef(value)) vid += 1 else img += 1",
    "if (isVideoMediaRef(value)) vid += 0 else img += 0",
).replace(
    "if (isVideoMediaRef(media)) vid += 1 else img += 1",
    "if (isVideoMediaRef(media)) vid += 0 else img += 0",
)

def selftest():
    import tempfile
    import shutil
    bad = 0

    def expect(root, name, wanted, verdict_wanted):
        nonlocal bad
        verdict, findings = check(root)
        hits = [f for f in findings if any(w in f for w in wanted)]
        if verdict_wanted == "FAIL" and (verdict != FAIL or not hits):
            print(f"  SELFTEST {name}: FAIL (gate did not catch the pre-fix shape)")
            bad += 1
        elif verdict_wanted == "FAIL":
            print(f"  SELFTEST {name}: OK (gate RED: {', '.join(h.split(']')[0] + ']' for h in hits)})")
        elif verdict != verdict_wanted:
            print(f"  SELFTEST {name}: FAIL (gate verdict {verdict}, wanted {verdict_wanted})")
            bad += 1
        else:
            print(f"  SELFTEST {name}: OK (gate {verdict})")

    # negative fixture: the pre-fix tree must be RED on R1/R2/R4/R6
    tmp = tempfile.mkdtemp(prefix="funnel-neg-")
    try:
        root = build_fixture(tmp, PREFIX_REPO, PREFIX_IMPORT)
        expect(root, "pre-fix-shapes", ["[R1] FAIL", "[R2] FAIL", "[R4] FAIL", "[R6] FAIL"], "FAIL")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    # --- [R7]-[R9]: the debug RPC read surface -------------------------------
    def debug_arm(name, debug_body, mutation_body, tag, want, dao_body=None):
        """Grade ONE read-side arm on an isolated fixture tree."""
        nonlocal bad
        tmp = tempfile.mkdtemp(prefix="debug-arm-")
        try:
            build_debug_fixture(tmp, debug_body, mutation_body, dao_body)
            hits = [f for f in check_debug_surface(tmp) if f.startswith(tag)]
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if not hits:
            print(f"  SELFTEST {name}: FAIL ({tag} produced no finding — arm is empty)")
            bad += 1
            return
        wrong = [h for h in hits if want not in h]
        if wrong:
            print(f"  SELFTEST {name}: FAIL (wanted {want}, got {wrong[0]})")
            bad += 1
        else:
            print(f"  SELFTEST {name}: OK ({hits[0].split(']')[1].strip()[:60]})")

    # [R7] the shipped classification is green; a KDoc mention alone is not
    debug_arm("r7-real-serial-names", DEBUG_GREEN, MUTATION_GREEN, "[R7]", "PASS")
    debug_arm("r7-missing-toolUse", DEBUG_MISSING_TOOLUSE, MUTATION_GREEN, "[R7]", "FAIL")
    debug_arm("r7-name-only-in-line-comment", DEBUG_LINE_COMMENT_ONLY, MUTATION_GREEN, "[R7]", "FAIL")
    debug_arm("r7-name-only-in-block-comment", DEBUG_BLOCK_COMMENT_ONLY, MUTATION_GREEN, "[R7]", "FAIL")

    # [R8] one shape, one mapper, no re-inlined field
    debug_arm("r8-shared-mapper", DEBUG_GREEN, MUTATION_GREEN, "[R8]", "PASS")
    debug_arm("r8-sessionsGet-inline-copy", DEBUG_SESSIONS_INLINE, MUTATION_GREEN, "[R8]", "FAIL")
    debug_arm("r8-caller-re-inlines-pinnedAt", DEBUG_REINLINED_FIELD, MUTATION_GREEN, "[R8]", "FAIL")
    debug_arm("r8-mapper-drops-field", DEBUG_MAPPER_DROPS_FIELD, MUTATION_GREEN, "[R8]", "FAIL")

    # [R9] one resolver, composite predicate intact, reached from both sides
    debug_arm("r9-single-resolver", DEBUG_GREEN, MUTATION_GREEN, "[R9]", "PASS")
    debug_arm("r9-mutation-local-predicate", DEBUG_GREEN, MUTATION_LOCAL_PREDICATE, "[R9]", "FAIL")
    debug_arm("r9-composite-arm-dropped", DEBUG_BARE_PREDICATE, MUTATION_GREEN, "[R9]", "FAIL")

    # [R10] [fix/dv23-a5] the debug RPCs must never read parts_json through a
    # raw DAO path — the COUNT probe is the shape, the blob read is the bug.
    debug_arm("r10-count-probe", DEBUG_GREEN, MUTATION_GREEN, "[R10]", "PASS")
    debug_arm("r10-raw-lastMessageParts", DEBUG_PRE_FIX_PROBE, MUTATION_GREEN, "[R10]", "FAIL")
    debug_arm("r10-raw-loadMessages", DEBUG_RAW_LOADMESSAGES, MUTATION_GREEN, "[R10]", "FAIL")
    # teeth are not hardcoded to the two known names: a DAO that grows a new
    # blob read is covered the day it lands.
    debug_arm("r10-new-dao-blob-read-used", DEBUG_NEW_BLOB_READ_CALL,
              MUTATION_GREEN, "[R10]", "FAIL", dao_body=DAO_NEW_BLOB_READ)
    # ... and the derivation must not over-reach: a star over sessions and a
    # COUNT over messages are NOT blob reads, so calling them stays green.
    debug_arm("r10-non-blob-queries-allowed", DEBUG_NON_BLOB_CALLS,
              MUTATION_GREEN, "[R10]", "PASS", dao_body=DAO_GREEN)
    # fail-closed: no DAO to derive from is RED, not silently green.
    _tmp = tempfile.mkdtemp(prefix="r10-nodao-")
    try:
        build_debug_fixture(_tmp, DEBUG_GREEN, MUTATION_GREEN)
        os.remove(os.path.join(_tmp, DAO_REL))
        _hits = [f for f in check_debug_surface(_tmp) if f.startswith("[R10]")]
        if not _hits or "FAIL" not in _hits[0]:
            print("  SELFTEST r10-missing-dao: FAIL (gate went blind without ChatDao.kt)")
            bad += 1
        else:
            print("  SELFTEST r10-missing-dao: OK (fails closed)")
    finally:
        shutil.rmtree(_tmp, ignore_errors=True)

    # the whole gate must still flip RED on the pre-fix read surface
    tmp = tempfile.mkdtemp(prefix="funnel-read-neg-")
    try:
        build_fixture(tmp, PREFIX_REPO, PREFIX_IMPORT)
        build_debug_fixture(tmp, DEBUG_GREEN.replace(
            '"toolUse", "tool_call", "tool_use" ->', '"tool_call", "tool_use" ->'),
            MUTATION_LOCAL_PREDICATE)
        verdict, findings = check(tmp)
        hit = [f for f in findings if " FAIL " in f]
        if verdict == FAIL and any(f.startswith("[R7]") for f in hit) and \
                any(f.startswith("[R9]") for f in hit):
            print("  SELFTEST read-surface-prefix-tree: OK (gate RED on R7 + R9)")
        else:
            print("  SELFTEST read-surface-prefix-tree: FAIL (gate did not catch the read side)")
            bad += 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    # --- [R11 / C-EXP]: the mediaRef dispatch judgment ------------------------
    def media_arm(name, body, extra, want, tag="[R11]"):
        """Grade ONE [R11] arm on an isolated fixture tree."""
        nonlocal bad
        tmp = tempfile.mkdtemp(prefix="media-arm-")
        try:
            build_media_fixture(tmp, body, extra)
            hits = [f for f in check_media_ref_dispatch(tmp) if f.startswith(tag)]
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if not hits:
            print(f"  SELFTEST {name}: FAIL ({tag} produced no finding — arm is empty)")
            bad += 1
            return
        if want == "PASS":
            if "PASS" not in hits[0]:
                print(f"  SELFTEST {name}: FAIL (expected PASS, got {hits[0][:120]})")
                bad += 1
            else:
                print(f"  SELFTEST {name}: OK ({hits[0].split(']')[1].strip()[:60]})")
            return
        if "FAIL" not in hits[0]:
            print(f"  SELFTEST {name}: FAIL (expected FAIL, got {hits[0][:120]})")
            bad += 1
            return
        # the failing face must be NAMED — a criterion that cannot say WHICH
        # function is wrong is not actionable
        # The FAIL message names the OFFENDING faces only (the ones that
        # split on the legacy names). The compliant face must NOT be named —
        # naming it would be a false accusation.
        if want == "FAIL-NAMED":
            if "countVideoParts" not in hits[0]:
                print(f"  SELFTEST {name}: FAIL (offending face not named: {hits[0][:200]})")
                bad += 1
            elif "countPartsAttachments" in hits[0]:
                print(f"  SELFTEST {name}: FAIL (compliant face wrongly accused)")
                bad += 1
            else:
                print(f"  SELFTEST {name}: OK (offending face named, compliant face spared)")
            return
        if want == "FAIL" and ("countPartsAttachments" in hits[0] or "countVideoParts" in hits[0]):
            print(f"  SELFTEST {name}: OK (gate RED: {hits[0].split(']')[0] + ']'})")
            return
        print(f"  SELFTEST {name}: FAIL (expected {want}, got {hits[0][:160]})")
        bad += 1

    # --- batch7 B7-t03: one arm per witnessed shape (10-1 .. 10-5) ----------
    def repo_arm(name, repo_body, import_body, wanted, tag="[R5]"):
        nonlocal bad
        tmp = tempfile.mkdtemp(prefix="b7-repo-")
        try:
            root = build_fixture(tmp, repo_body, import_body)
            hits = [f for f in check(root)[1] if f.startswith(tag)]
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if not hits:
            print(f"  SELFTEST {name}: FAIL ({tag} produced no finding — arm is empty)")
            bad += 1
            return
        if (wanted == "FAIL") != ("FAIL" in hits[0]):
            print(f"  SELFTEST {name}: FAIL (wanted {wanted}, got {hits[0][:130]})")
            bad += 1
            return
        print(f"  SELFTEST {name}: OK ({hits[0].split(']')[0] + ']'} as expected)")

    repo_arm("r5-wired-funnels", FUNNEL_REPO_GOOD, IMPORT_GOOD, "PASS")
    repo_arm("r5-funnel-body-identity", FUNNEL_REPO_IDENTITY, IMPORT_GOOD, "FAIL")
    repo_arm("r6-import-caps-both", FUNNEL_REPO_GOOD, IMPORT_GOOD, "PASS", "[R6]")
    repo_arm("r6-import-let-discards-cap", FUNNEL_REPO_GOOD, IMPORT_LET, "FAIL", "[R6]")
    debug_arm("r7-arm-drops-part", DEBUG_MEDIA_REF_UNIT, MUTATION_GREEN, "[R7]", "FAIL")
    debug_arm("r10-dao-alias-read", DEBUG_DAO_ALIAS, MUTATION_GREEN, "[R10]", "FAIL")
    media_arm("r11-counter-delta-zero", MEDIA_ZERO_DELTA, None, "FAIL")

    media_arm("r11-modern-name-handled", MEDIA_GREEN, None, "PASS")
    # --- [R12] the nested toolResult media arm ---
    def r12_arm(name, body, want):
        nonlocal bad
        tmp = tempfile.mkdtemp(prefix="r12-arm-")
        try:
            build_media_fixture(tmp, body)
            hits = [f for f in check_tool_result_media_ref(tmp) if f.startswith("[R12]")]
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if not hits:
            print(f"  SELFTEST {name}: FAIL ([R12] produced no finding — arm is empty)")
            bad += 1
            return
        if want == "PASS" and "PASS" in hits[0]:
            print(f"  SELFTEST {name}: OK (nested toolResult media counted)")
            return
        if want == "FAIL" and "FAIL" in hits[0]:
            print(f"  SELFTEST {name}: OK (gate RED: {hits[0].split(']')[0] + ']'})")
            return
        print(f"  SELFTEST {name}: FAIL (expected {want}, got {hits[0][:160]})")
        bad += 1

    r12_arm("r12-compliant-nested-media", MEDIA_GREEN, "PASS")
    r12_arm("r12-toolresult-arm-drops-media", MEDIA_TOOLRESULT_DROPPED, "FAIL")
    r12_arm("r12-media-ref-read-outside-arm", MEDIA_TOOLRESULT_OUTSIDE_ARM, "FAIL")
    media_arm("r11-pre-fix-shape", MEDIA_PREFIX, None, "FAIL")
    # teeth are not hardcoded to one function: a SECOND face that drops the
    # modern name is caught and NAMED.
    media_arm("r11-second-face-dropped", MEDIA_GREEN, MEDIA_SECOND_FACE, "FAIL-NAMED")
    # precision: image and video treated identically asserts nothing per-kind;
    # a modality-badge helper mentions the words but is not a parts face.
    media_arm("r11-precision-no-false-positive", MEDIA_PRECISION, None, "PASS")

    # --- [meta-audit-1024 / M5] DATAFLOW arms -------------------------------
    # The witness fixture must really be the mutated shape; if the anchor drifts
    # the arms below would silently grade MEDIA_GREEN and "pass" while blind.
    if MEDIA_WITNESS == MEDIA_GREEN:
        print("  SELFTEST media-witness-binding: FAIL (arm replacement missed — "
              "the witness fixture is the compliant one)")
        bad += 1
    else:
        print("  SELFTEST media-witness-binding: OK (both counting arms replaced)")
    # name matches, counter does not move: `"mediaRef" -> Unit`
    media_arm("r11-witness-arm-without-count", MEDIA_WITNESS, None, "FAIL")
    # read happens, result discarded: `value?.optJSONObject("mediaRef")`
    r12_arm("r12-witness-arm-discards-read", MEDIA_WITNESS, "FAIL")
    # delegated counting stays green (no false accusation on refactors)
    media_arm("r11-delegated-count-stays-green", MEDIA_DELEGATED, None, "PASS")
    r12_arm("r12-delegated-count-stays-green", MEDIA_DELEGATED, "PASS")
    # fail-closed: a tree with no derivable face is RED, not silently green.
    _tmp = tempfile.mkdtemp(prefix="r11-noface-")
    try:
        os.makedirs(os.path.join(_tmp, "src/android/app/src/main/java/com/rikkaminis/app"), exist_ok=True)
        _hits = [f for f in check_media_ref_dispatch(_tmp) if f.startswith("[R11]")]
        if not _hits or "FAIL" not in _hits[0]:
            print("  SELFTEST r11-no-face: FAIL (gate went blind with no derivable face)")
            bad += 1
        else:
            print("  SELFTEST r11-no-face: OK (fails closed)")
    finally:
        shutil.rmtree(_tmp, ignore_errors=True)

    # the gate must be able to fail: an empty tree has no ChatRepository at all
    verdict, _ = check("/nonexistent-root-xyz")
    if verdict == OK:
        print("  SELFTEST empty-tree: FAIL (gate passed a nonexistent root)")
        bad += 1
    else:
        print("  SELFTEST empty-tree: OK (fails closed)")
    return 1 if bad else 0


def main():
    argv = sys.argv[1:]
    if "--selftest" in argv:
        n = selftest()
        print("PARTSJSON_FUNNEL_SELFTEST=" + ("PASS" if n == 0 else "FAIL"))
        sys.exit(n)
    root = argv[0] if argv else "."
    verdict, findings = check(root)
    for f in findings:
        print("  " + f)
    print(f"parts_json_funnel_check: {verdict}")
    sys.exit(0 if verdict == OK else 1)


if __name__ == "__main__":
    main()
