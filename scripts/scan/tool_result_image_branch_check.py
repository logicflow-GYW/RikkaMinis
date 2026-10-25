#!/usr/bin/env python3
"""
Tool-result image consumer guard — every provider serializer that handles a
tool-result part must consume its image bytes.

Bug class (deep-audit-1017 ISSUE-01, P0): `read_image` / `browser_use`
screenshots reach the request as `AgentContentPart.ToolResult.imageData`, not as
an `ImageData` part. Anthropic (`AnthropicProvider.kt` -> image block inside
`tool_result.content`) and Gemini (`GeminiProvider.kt` -> sibling `inlineData`)
were fixed by "[FIX-1 / F-192]"; `OpenAIProvider.kt` never was — `grep -n
imageData` over that file returned **0 hits** while both siblings had the branch,
so every image a model read back through a tool call was invisible on OpenAI
Chat-Completions *and* Responses APIs. Classic "fixed 2 of 3" family recurrence:
the defect is invisible in review because the file looks complete, and it is
invisible at runtime because the request still succeeds (the model simply never
sees the bytes).

[deep-audit-1045 A-03] Sibling criterion, same family ("bytes that exist but
never reach the wire"): the three provider serializers each drop messages the
orphan-tool-pairing sanitizer emptied, using
`contentParts.isNotEmpty() || content.isNotEmpty()` — i.e. "is there TEXT?".
A `minis-model-use` turn carrying only an image or only an audio block has no
text, so the filter deleted the whole user turn and the payload never left the
device (the request stayed valid and semantically empty). The predicate now
lives once, in `MessagePayloadFilter.kt:hasWirePayload(...)`, and every
empty-message filter site must route through it. Criteria 5/6 below pin the
decision (does a media-only turn survive?) and the single source.

Criterion (structural — behaviour is pinned by `OpenAIToolResultImagesTest` and
`MessagePayloadFilterTest`):
  1. Every `*.kt` under the provider package that handles tool-result parts —
     either a `when` branch (`is AgentContentPart.ToolResult ->`) or a filter
     (`filterIsInstance<AgentContentPart.ToolResult>()`) — must reference
     `imageData` somewhere in the file. A provider that serializes tool results
     without ever looking at the bytes is the defect.
  2. Each individual `is AgentContentPart.ToolResult ->` branch body must itself
     reference `imageData`: a provider that handles one tool-result site and not
     the other (the shape OpenAI would have had if only its Chat path had been
     patched) fails here.
  3. Deliberate non-serializers live in EXEMPTIONS with (a) a written reason and
     (b) a `symbol` that must still exist in the file. A stale exemption — the
     file no longer handles tool results, or the cited symbol was renamed —
     is itself a failure, so an exemption cannot outlive the shape it excused.
  4. Scanning zero files is a hard failure (a path typo must not read as green).

Usage: python3 scripts/scan/tool_result_image_branch_check.py [repo_root]
       python3 scripts/scan/tool_result_image_branch_check.py --selftest
Exit:  0 = pass, 1 = violation (file:line printed)
"""
import os
import re
import shutil
import sys
import tempfile

PROVIDER_REL = os.path.join(
    "src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app", "provider"
)

WHEN_BRANCH = "is AgentContentPart.ToolResult ->"
FILTER_CALL = "filterIsInstance<AgentContentPart.ToolResult>()"
IMAGE_FIELD = "imageData"

# [deep-audit-1045 A-03] The empty-message filter (derived from the tree: any
# `.filter {` / `.filterIndexed {` whose body asks `contentParts.isNotEmpty()`)
# must route through the shared payload predicate, and that predicate must weigh
# every media payload the providers can serialize.
# A site is either the delegated form (predicate call) or the legacy inline form
# (the marker the pre-fix tree used) — both are derived, so a reverted site is
# caught by the same criterion that a new site is covered by.
EMPTY_FILTER_SITE_MARKS = ("hasWirePayload(", "contentParts.isNotEmpty()")
PAYLOAD_PREDICATE_FILE = "MessagePayloadFilter.kt"
PAYLOAD_PREDICATE_DECL = "fun LLMMessage.hasWirePayload("
PAYLOAD_PREDICATE_CALL = "hasWirePayload("
PAYLOAD_MEDIA_ARMS = ("audioParts.isNotEmpty()", "imageParts.isNotEmpty()")
PAYLOAD_TURN_IMAGE_ARM = "turnImageCount > 0"
MIN_EMPTY_FILTER_SITES = 3

# file (provider-relative) -> {"reason": ..., "symbol": ...}
EXEMPTIONS = {
    "RequestSanitizers.kt": {
        "reason": (
            "sanitizeToolPairing only decides whether to KEEP or DROP whole parts "
            "(orphan tool_use / tool_result pairing) and never serializes bytes to "
            "any wire format, so there is nothing for it to consume"
        ),
        "symbol": "fun sanitizeToolPairing(",
    },
}


def strip_comments_and_strings(text):
    """Blank out comments and string contents so prose cannot fake a match and
    braces inside literals cannot unbalance a count."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if text.startswith('"""', i):
            i += 3
            while i < n and not text.startswith('"""', i):
                i += 1
            i += 3
            continue
        if c == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        out.append((i, c))
        i += 1
    return out


BOOL_OP_LITERAL = re.compile(
    r"(?:&&|\|\|)\s*(?:true|false)(?![\w])"
    r"|(?<![\w.\"'])(?:true|false)\s*(?:&&|\|\|)")
IF_RE = re.compile(r"\bif\s*\(")
CONTINUATION_CHARS = "||&&,.+-*/?:=(<["


def _skip_string(text, i):
    if text.startswith('"""', i):
        j = text.find('"""', i + 3)
        return len(text) if j < 0 else j + 3
    q = text[i]
    i += 1
    while i < len(text) and text[i] != q:
        if text[i] == "\\":
            i += 2
        elif text.startswith("${", i):
            depth = 1
            i += 2
            while i < len(text) and depth:
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                i += 1
        else:
            i += 1
    return i + 1


def _close_of(text, k, open_ch, close_ch):
    depth = 0
    i = k
    while i < len(text):
        c = text[i]
        if c == '"' or c == "'":
            i = _skip_string(text, i)
            continue
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _expr_span(text, start):
    """Expression text up to the statement end. A newline only ends it when the
    last non-space character is not a continuation operator, so a BLANK LINE in
    the middle of an expression does not truncate it."""
    depth = 0
    i = start
    last = ""
    while i < len(text):
        c = text[i]
        if c == '"' or c == "'":
            j = _skip_string(text, i)
            last = text[j - 1]
            i = j
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return text[start:i]
            depth -= 1
        if c == "\n" and depth == 0 and last not in CONTINUATION_CHARS:
            return text[start:i]
        if not c.isspace():
            last = c
        i += 1
    return text[start:]


def _if_conditions(text):
    """-> [(cond_start, cond_text)] for every brace- or bracket-bodied `if (`."""
    out = []
    for m in IF_RE.finditer(text):
        op = text.find("(", m.end() - 1)
        cp = _close_of(text, op, "(", ")")
        if cp < 0:
            continue
        out.append((op, text[op:cp + 1]))
    return out


def dead_guard_in(text, needle):
    """-> offending condition text when `needle` is only guarded by a condition a
    boolean literal short-circuits (`if (false && part.imageData != null)`).

    Presence of the token is not a decision: the branch stays textually intact
    and never runs, so the payload is silently dropped.
    """
    for _pos, cond in _if_conditions(text):
        if needle not in cond:
            continue
        inner = cond[1:-1] if cond.startswith("(") and cond.endswith(")") else cond
        if BOOL_OP_LITERAL.search(inner) or re.match(r"\s*(?:true|false)\b", inner):
            return re.sub(r"\s+", " ", inner).strip()[:80]
    return None


def payload_predicate_body(text):
    """Body of the shared payload predicate, bounded by the EXPRESSION itself.

    The old slice ended at the first blank line (`find("\n\n")`): a carrier arm
    written after a blank line — or after a blank line inside the multi-line
    `||` chain — vanished from C-IMG-1, so the declaration could promise a
    payload no serializer consumes.
    """
    blanked = blank_comments(text)
    idx = blanked.find(PAYLOAD_PREDICATE_DECL)
    if idx < 0:
        return None
    op = blanked.find("(", idx)
    cp = _close_of(blanked, op, "(", ")")
    if cp < 0:
        return None
    k = cp + 1
    while k < len(blanked) and blanked[k] not in "={":
        k += 1
    if k >= len(blanked):
        return None
    if blanked[k] == "{":
        end = _close_of(blanked, k, "{", "}")
        return blanked[k + 1:end] if end > 0 else blanked[k + 1:]
    return _expr_span(blanked, k + 1)


def branch_block(text, start):
    """Return the text of the block opened at/after `start` (first `{` reached),
    brace-balanced over code only."""
    code = strip_comments_and_strings(text)
    depth = 0
    opened = False
    end = None
    for idx, ch in code:
        if idx < start:
            continue
        if ch == "{":
            depth += 1
            opened = True
        elif ch == "}":
            depth -= 1
            if opened and depth == 0:
                end = idx + 1
                break
    if end is None:
        return None
    return text[start:end]


def enclosing_block(text, target):
    """Return (start, block) of the innermost brace block containing [target],
    or (None, None). Braces inside comments/strings are ignored."""
    code = strip_comments_and_strings(text)
    stack = []
    for idx, ch in code:
        if idx >= target:
            break
        if ch == "{":
            stack.append(idx)
        elif ch == "}" and stack:
            stack.pop()
    if not stack:
        return (None, None)
    start = stack[-1]
    depth = 0
    opened = False
    for idx, ch in code:
        if idx < start:
            continue
        if ch == "{":
            depth += 1
            opened = True
        elif ch == "}":
            depth -= 1
            if opened and depth == 0:
                return (start, text[start:idx + 1])
    return (None, None)


def payload_filter_sites(text):
    """Derive the empty-message filter sites in one file: every brace block that
    opens a `.filter` / `.filterIndexed` lambda and either delegates to the
    shared predicate or still carries the legacy inline marker. No line numbers
    and no file list, so a new provider call site is covered the day it lands,
    and a site reverted to the inline predicate is caught by the same pass."""
    sites = []
    seen = set()
    for mark in EMPTY_FILTER_SITE_MARKS:
        for m in re.finditer(re.escape(mark), text):
            start, block = enclosing_block(text, m.start())
            if start is None or start in seen:
                continue
            head = text[max(0, start - 40):start]
            if not re.search(r"\.filter(Indexed)?\s*$", head):
                continue
            seen.add(start)
            sites.append((text[:start].count("\n") + 1, block))
    return sites


def check_payload_filters(root, provider_dir, texts):
    """[deep-audit-1045 A-03] Empty-message filters must keep media-only turns,
    through the one shared predicate."""
    violations = []
    total = 0
    for rel, text in texts.items():
        for line, block in payload_filter_sites(text):
            total += 1
            if PAYLOAD_PREDICATE_CALL in block:
                continue
            missing = [arm for arm in PAYLOAD_MEDIA_ARMS if arm not in block]
            violations.append(
                "%s:%d empty-message filter drops media-only turns (no %s, no shared "
                "%s) — a minis-model-use turn carrying only an image or an audio block "
                "is deleted and the bytes never reach the model"
                % (rel, line, " / ".join(missing) or "<media arm>", PAYLOAD_PREDICATE_CALL)
            )
    if total < MIN_EMPTY_FILTER_SITES:
        violations.append(
            "only %d empty-message filter site(s) found under the provider package "
            "(expected >= %d) — the filter was removed or renamed, which either loses the "
            "sanitizer defense or hides the media arm from this gate"
            % (total, MIN_EMPTY_FILTER_SITES)
        )
    predicate = texts.get(PAYLOAD_PREDICATE_FILE)
    if predicate is None:
        violations.append(
            "%s: the shared payload predicate file is gone — the three serializers are "
            "free to drift apart again" % PAYLOAD_PREDICATE_FILE
        )
        return violations, total
    if PAYLOAD_PREDICATE_DECL not in predicate:
        violations.append(
            "%s: '%s' declaration not found (the predicate was inlined or renamed)"
            % (PAYLOAD_PREDICATE_FILE, PAYLOAD_PREDICATE_DECL)
        )
        return violations, total
    body = payload_predicate_body(predicate)
    if body is None:
        violations.append(
            "%s: the payload predicate's body could not be derived — refusing to report "
            "green (the declaration shape moved)" % PAYLOAD_PREDICATE_FILE)
        return violations, total
    dead = BOOL_OP_LITERAL.search(body)
    if dead is not None:
        window = body[max(0, dead.start() - 60):dead.end() + 60]
        violations.append(
            "%s: the payload predicate's decision is short-circuited by a boolean literal "
            "(`%s`) — every media arm is still present as text and none of them is weighed, "
            "so a media-only turn is deleted and its bytes never reach the model"
            % (PAYLOAD_PREDICATE_FILE, re.sub(r"\s+", " ", window).strip()))
    alive = [arm for arm in PAYLOAD_MEDIA_ARMS + (PAYLOAD_TURN_IMAGE_ARM,) if arm not in body]
    if alive:
        violations.append(
            "%s: the payload predicate no longer weighs %s — that payload can only come "
            "from a media-only turn, so dropping the arm re-drops the user's turn"
            % (PAYLOAD_PREDICATE_FILE, ", ".join(alive))
        )
    return violations, total


# ── [deep-audit-1047 A-03] Declared carriers must have a wire consumer ──────
# `hasWirePayload` is the ONE place that decides "this turn still has payload".
# Every arm it weighs is therefore a promise to some serializer: the turn is
# kept, so the serializer had better know what to do with those bytes. Until
# 1047 nothing on the wire side read `msg.imageParts` at all — the promise was
# unbacked, and the empty filter happily let a text-less image turn through to
# an empty `content: ""`.
#
# C-IMG-1 (declaration implies consumption): derive the carrier list from the
#     predicate BODY (`<carrier>.isNotEmpty()` arms) and require `.<carrier>`
#     reads in the files that actually write media wire parts. Byte-estimate
#     readers (`LLMProvider.approxInputBytes`) do NOT count — that is exactly
#     the read that made the old gate blind.
# C-IMG-2 (images and audio must not be mutually exclusive): inside a file that
#     writes both, the `input_audio` write may not sit in a sibling branch of
#     the chain that serializes `imageParts` — a turn carrying both would lose
#     one of them silently.
#
# Both derive from the tree: no carrier list, no file list, no line numbers.

CARRIER_ARM_RE = re.compile(r"([A-Za-z_]\w*)\.isNotEmpty\(\)")
MEDIA_WIRE_MARKS = (
    re.compile(r'put\(\s*"type"\s*,\s*"image_url"'),
    re.compile(r'put\(\s*"type"\s*,\s*"input_image"'),
    re.compile(r'put\(\s*"type"\s*,\s*"image"'),
    re.compile(r'put\(\s*"inlineData"'),
    # The shared image encoders (OpenAIToolResultImages.chat/responsesUserContent)
    # build the parts themselves, so a serialization site may hand them the bytes
    # without ever spelling an image marker inline.
    re.compile(r"\b[A-Za-z_]*UserContent\s*\("),
)
AUDIO_WRITE_RE = re.compile(r'put\(\s*"type"\s*,\s*"input_audio"')
MIN_MEDIA_SERIALIZER_FILES = 2
IF_KW = re.compile(r"\bif\s*\(")


def blank_comments(text):
    """Same length as `text`: comment bodies become spaces (newlines kept),
    literals INTACT — the media markers are string literals."""
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"' or c == "'":
            q = text[i:i + 3] if text.startswith('"""', i) else c
            i += len(q)
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text.startswith(q, i) if len(q) == 3 else text[i] == q:
                    i += len(q)
                    break
                i += 1
            continue
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if text.startswith("/*", i):
            while i < n and not text.startswith("*/", i):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                out[i] = " "
                out[i + 1] = " "
                i += 2
            continue
        i += 1
    return "".join(out)


def blank_comments_and_strings(text):
    """Same length as `text`: comments AND literal contents become spaces,
    newlines kept — so brace/paren matching can never be fooled by a literal,
    and every index still lines up with the original."""
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            for k in range(i, min(i + 3, n)):
                out[k] = " "
            i += 3
            while i < n and not text.startswith('"""', i):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            for k in range(i, min(i + 3, n)):
                out[k] = " "
            i += 3
            continue
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if text.startswith("/*", i):
            while i < n and not text.startswith("*/", i):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                out[i] = " "
                out[i + 1] = " "
                i += 2
            continue
        if c == '"' or c == "'":
            q = c
            out[i] = " "
            i += 1
            while i < n:
                if text[i] == "\\":
                    out[i] = " "
                    if i + 1 < n and text[i + 1] != "\n":
                        out[i + 1] = " "
                    i += 2
                    continue
                if text[i] == q:
                    out[i] = " "
                    i += 1
                    break
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        i += 1
    return "".join(out)


def matching(text, i):
    """Index of the delimiter closing the one at `i` (`(`/`{`), or -1."""
    opener = text[i]
    closer = ")" if opener == "(" else "}"
    depth = 0
    k = i
    while k < len(text):
        if text[k] == opener:
            depth += 1
        elif text[k] == closer:
            depth -= 1
            if depth == 0:
                return k
        k += 1
    return -1


def if_blocks(structure):
    """-> [(if_start, cond, body_start, body_end)] for every brace-bodied `if`
    in a comment/string-blanked text."""
    out = []
    for m in IF_KW.finditer(structure):
        paren_at = structure.find("(", m.start())
        if paren_at < 0:
            continue
        paren_end = matching(structure, paren_at)
        if paren_end < 0:
            continue
        brace_at = structure.find("{", paren_end)
        if brace_at < 0:
            continue
        brace_end = matching(structure, brace_at)
        if brace_end < 0:
            continue
        out.append((m.start(), structure[paren_at + 1:paren_end], brace_at, brace_end))
    return out


def innermost_if(blocks, index):
    """The smallest `if` body containing `index`, or None."""
    best = None
    for blk in blocks:
        if blk[2] <= index < blk[3]:
            if best is None or (blk[3] - blk[2]) < (best[3] - best[2]):
                best = blk
    return best


def is_else_chained(structure, if_start):
    """True when the `if` at `if_start` opens an `else` / `else if` branch."""
    i = if_start - 1
    while i >= 0 and structure[i] in " \t\r\n":
        i -= 1
    return structure[max(0, i - 3):i + 1].endswith("else")


def preceding_sibling(structure, if_start):
    """(start, end) of the sibling block that the `else` at `if_start` continues,
    or None."""
    i = if_start - 1
    while i >= 0 and structure[i] in " \t\r\n":
        i -= 1
    if not structure[max(0, i - 3):i + 1].endswith("else"):
        return None
    k = i - 4
    while k >= 0 and structure[k] in " \t\r\n":
        k -= 1
    if k < 0 or structure[k] != "}":
        return None
    depth = 0
    while k >= 0:
        if structure[k] == "}":
            depth += 1
        elif structure[k] == "{":
            depth -= 1
            if depth == 0:
                return (k, i)
        k -= 1
    return None


def predicate_carrier_arms(predicate_text):
    """-> set of carrier names the shared payload predicate weighs, or None."""
    span = payload_predicate_body(predicate_text)
    if span is None:
        return None
    return {m.group(1) for m in CARRIER_ARM_RE.finditer(span)}


def check_carrier_consumption(texts):
    """C-IMG-1."""
    violations = []
    predicate = texts.get(PAYLOAD_PREDICATE_FILE)
    arms = predicate_carrier_arms(predicate) if predicate is not None else None
    if not arms:
        violations.append(
            "%s: no `<carrier>.isNotEmpty()` arm could be derived from the payload "
            "predicate — refusing to report green" % PAYLOAD_PREDICATE_FILE)
        return violations
    serializers = {}
    for rel, text in texts.items():
        flat = blank_comments(text)
        if any(mark.search(flat) for mark in MEDIA_WIRE_MARKS):
            serializers[rel] = flat
    if len(serializers) < MIN_MEDIA_SERIALIZER_FILES:
        violations.append(
            "only %d file(s) under the provider package write a media wire part "
            "(expected >= %d) — the serializers moved or the markers changed, so "
            "carrier consumption cannot be judged"
            % (len(serializers), MIN_MEDIA_SERIALIZER_FILES))
        return violations
    for carrier in sorted(arms):
        read = re.compile(r"\.%s\b" % re.escape(carrier))
        if not any(read.search(body) for body in serializers.values()):
            violations.append(
                "%s: the payload predicate keeps a turn alive for `%s.isNotEmpty()`, but "
                "no media serializer under the provider package ever reads `.%s` — those "
                "bytes are dropped and a text-less turn goes out as `content: \"\"`"
                % (PAYLOAD_PREDICATE_FILE, carrier, carrier))
    return violations


def has_media_image_mark(text):
    return any(mark.search(text) for mark in MEDIA_WIRE_MARKS)


def check_image_audio_not_exclusive(texts):
    """C-IMG-2.

    Anchored on the two WRITES, not on a carrier name: whatever feeds the image
    parts, an `input_audio` write that lives in an `else`/`else if` branch of the
    same chain as an image write is unreachable whenever images are attached —
    and vice versa. The merged shape (both writes reachable in one block) is the
    only compliant one."""
    violations = []
    audio_sites = 0
    for rel in sorted(texts):
        text = texts[rel]
        structure = blank_comments_and_strings(text)
        flat = blank_comments(text)
        audio_positions = [m.start() for m in AUDIO_WRITE_RE.finditer(flat)]
        if not audio_positions:
            continue
        blocks = if_blocks(structure)
        image_blocks = [blk for blk in blocks if has_media_image_mark(flat[blk[2]:blk[3]])]
        for apos in audio_positions:
            audio_sites += 1
            line = flat[:apos].count("\n") + 1
            ablk = innermost_if(blocks, apos)
            if ablk is None:
                continue
            if has_media_image_mark(flat[ablk[2]:ablk[3]]):
                continue  # both carriers reachable from one block — the fix shape
            sibling = preceding_sibling(structure, ablk[0]) if is_else_chained(structure, ablk[0]) else None
            if sibling is not None and has_media_image_mark(flat[sibling[0]:sibling[1]]):
                violations.append(
                    "%s:%d writes `input_audio` in an `else` branch whose sibling writes "
                    "an image part — a turn carrying both loses its audio silently (the "
                    "two carriers must share one branch)" % (rel, line))
                continue
            flagged = False
            for iblk in image_blocks:
                if not is_else_chained(structure, iblk[0]):
                    continue
                sib = preceding_sibling(structure, iblk[0])
                if sib is None:
                    continue
                if any(sib[0] <= p < sib[1] for p in audio_positions):
                    violations.append(
                        "%s:%d writes an image part in an `else` branch whose sibling "
                        "writes `input_audio` — same exclusive chain, the audio is dropped "
                        "whenever images are attached" % (rel, line))
                    flagged = True
                    break
            if flagged:
                continue
    if audio_sites == 0:
        violations.append(
            "no `input_audio` write site found under the provider package — refusing to "
            "report green (the audio serialization moved)")
    return violations


def scan_root(root, exemptions=None):
    """Return (violations, scanned_files, per_file_report)."""
    exemptions = EXEMPTIONS if exemptions is None else exemptions
    provider_dir = os.path.join(root, PROVIDER_REL)
    violations = []
    report = []
    scanned = 0
    texts = {}
    if not os.path.isdir(provider_dir):
        return (["provider package not found at %s" % provider_dir], 0, [])

    for dirpath, _dirs, files in os.walk(provider_dir):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, provider_dir)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            texts[rel] = text
            when_hits = [m.start() for m in re.finditer(re.escape(WHEN_BRANCH), text)]
            has_filter = FILTER_CALL in text
            if not when_hits and not has_filter:
                continue
            scanned += 1

            exempt = exemptions.get(rel)
            if exempt is not None:
                symbol = exempt["symbol"]
                if symbol not in text:
                    violations.append(
                        "%s: stale exemption — cited symbol %r no longer exists; "
                        "re-check whether it really never serializes bytes" % (rel, symbol)
                    )
                    report.append((rel, "STALE-EXEMPT"))
                else:
                    report.append((rel, "exempt"))
                continue

            if IMAGE_FIELD not in text:
                line = text[: when_hits[0]].count("\n") + 1 if when_hits else 1
                violations.append(
                    "%s:%d handles tool-result parts but never references %s "
                    "(tool-result images would be dropped)" % (rel, line, IMAGE_FIELD)
                )
                report.append((rel, "missing-imageData"))
                continue

            bad_branch = False
            for start in when_hits:
                block = branch_block(text, start)
                line = text[:start].count("\n") + 1
                if block is None:
                    violations.append("%s:%d unbalanced ToolResult branch" % (rel, line))
                    bad_branch = True
                    continue
                if IMAGE_FIELD not in block:
                    violations.append(
                        "%s:%d ToolResult branch does not consume %s"
                        % (rel, line, IMAGE_FIELD)
                    )
                    bad_branch = True
                else:
                    dead = dead_guard_in(block, IMAGE_FIELD)
                    if dead is not None:
                        violations.append(
                            "%s:%d the ToolResult branch reads %s only inside a condition a "
                            "boolean literal short-circuits (`%s`) — the image path is dead "
                            "code, so every tool-result image is silently dropped"
                            % (rel, line, IMAGE_FIELD, dead)
                        )
                        bad_branch = True
            report.append((rel, "branch-without-imageData" if bad_branch else "ok"))

    # An exemption that no longer matches any scanned file is dead weight and
    # would silently excuse a future regression in that file.
    for rel, exempt in exemptions.items():
        if not any(r[0] == rel for r in report):
            violations.append(
                "%s: exemption is stale — file no longer handles tool-result parts "
                "(%s)" % (rel, exempt["reason"][:60])
            )

    payload_violations, _sites = check_payload_filters(root, provider_dir, texts)
    violations.extend(payload_violations)
    # [deep-audit-1047 A-03] The two criteria that make "declared" mean
    # "consumed", and that keep the image / audio carriers out of mutually
    # exclusive branches.
    violations.extend(check_carrier_consumption(texts))
    violations.extend(check_image_audio_not_exclusive(texts))
    return (violations, scanned, report)


def selftest():
    """Fixtures proving the gate has teeth: a violating sample must be red, a
    compliant one green, and exemption rot must be caught."""
    tmp = tempfile.mkdtemp(prefix="trimg-gate-")
    try:
        pkg = os.path.join(tmp, PROVIDER_REL)
        os.makedirs(os.path.join(pkg, "anthropic"), exist_ok=True)

        def write(rel, body):
            path = os.path.join(pkg, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(body)

        # (a) violating: filter form, no imageData anywhere.
        write(
            "openai/ViolatingProvider.kt",
            "class V {\n"
            "    fun f(parts: List<Any>) {\n"
            "        for (tr in parts.filterIsInstance<AgentContentPart.ToolResult>()) {\n"
            "            put(\"content\", tr.content)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        # (b) compliant: same shape, consumes the bytes.
        write(
            "gemini/CompliantProvider.kt",
            "class C {\n"
            "    fun f(parts: List<Any>) {\n"
            "        for (tr in parts.filterIsInstance<AgentContentPart.ToolResult>()) {\n"
            "            if (tr.imageData != null) put(\"blob\", tr.imageData)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        # (c) when-branch form with the image handled in the branch body.
        write(
            "anthropic/WhenProvider.kt",
            "class W {\n"
            "    fun f(part: Any) = when (part) {\n"
            "        is AgentContentPart.ToolResult -> {\n"
            "            if (part.imageData != null) put(\"image\", part.imageData)\n"
            "        }\n"
            "        else -> Unit\n"
            "    }\n"
            "}\n",
        )
        # (d) file-level imageData present but the branch body itself ignores it.
        write(
            "anthropic/SplitProvider.kt",
            "class S {\n"
            "    fun other(p: Any) = p.imageData\n"
            "    fun f(part: Any) = when (part) {\n"
            "        is AgentContentPart.ToolResult -> {\n"
            "            put(\"content\", part.content)\n"
            "        }\n"
            "        else -> Unit\n"
            "    }\n"
            "}\n",
        )

        violations, scanned, report = scan_root(tmp)
        ok = True

        def check(label, condition, detail=""):
            nonlocal ok
            mark = "OK" if condition else "FAIL"
            print("  [%s] %s%s" % (mark, label, (" — " + detail) if detail and not condition else ""))
            if not condition:
                ok = False

        check("scanned 4 fixture files", scanned == 4, "scanned=%d" % scanned)
        check(
            "filter-form violation is red",
            any("ViolatingProvider.kt" in v for v in violations),
        )
        check(
            "compliant filter-form is green",
            not any("CompliantProvider.kt" in v for v in violations),
        )
        check("compliant when-branch is green", not any("WhenProvider.kt" in v for v in violations))
        check(
            "when-branch that ignores imageData inside its own body is red",
            any("SplitProvider.kt" in v and "ToolResult branch does not consume" in v for v in violations),
        )

        # (e) wide3-01: the branch keeps `imageData` but the condition that
        #     gates the encode is short-circuited by a literal — the path is dead.
        d_root = tempfile.mkdtemp(prefix="trimg-deadguard-")
        try:
            d_pkg = os.path.join(d_root, PROVIDER_REL)
            write_fixture(os.path.join(d_pkg, "anthropic/DeadGuardProvider.kt"),
                          "class D {\n"
                          "    fun f(part: Any) = when (part) {\n"
                          "        is AgentContentPart.ToolResult -> {\n"
                          "            if (false && part.imageData != null "
                          "&& part.imageMimeType != null) {\n"
                          '                put("image", part.imageData)\n'
                          "            }\n"
                          "        }\n"
                          "        else -> Unit\n"
                          "    }\n"
                          "}\n")
            v_dead_guard, _, _ = scan_root(d_root)
            check("a ToolResult image guard short-circuited by a literal is red",
                  any("DeadGuardProvider.kt" in v and "dead code" in v for v in v_dead_guard),
                  str(v_dead_guard[:2]))
        finally:
            shutil.rmtree(d_root, ignore_errors=True)

        # Exemption with a live symbol is honoured; a dead one is rot.
        write(
            "played/ExemptProvider.kt",
            "fun sanitizeToolPairing() {}\n"
            "class E { fun f(p: Any) = when (p) {\n"
            "    is AgentContentPart.ToolResult -> put(\"x\")\n"
            "    else -> Unit\n"
            "} }\n",
        )
        live = {
            "played/ExemptProvider.kt": {
                "reason": "fixture",
                "symbol": "fun sanitizeToolPairing(",
            }
        }
        v_live, _, _ = scan_root(tmp, live)
        check("exemption with a live symbol is honoured", not any("ExemptProvider" in v for v in v_live))

        dead = {
            "played/ExemptProvider.kt": {
                "reason": "fixture",
                "symbol": "fun noLongerHere(",
            }
        }
        v_dead, _, _ = scan_root(tmp, dead)
        check(
            "exemption with a renamed symbol is reported as rot",
            any("stale exemption" in v for v in v_dead),
        )

        # [deep-audit-1045 A-03] media-only turns survive the empty-message filter
        def payload_tree():
            t = tempfile.mkdtemp(prefix="trimg-payload-")
            pkg = os.path.join(t, PROVIDER_REL)
            os.makedirs(pkg, exist_ok=True)

            def w(rel, body):
                path = os.path.join(pkg, rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(body)

            w(PAYLOAD_PREDICATE_FILE,
              "internal fun LLMMessage.hasWirePayload(\n"
              "    isLastUserTurn: Boolean,\n"
              "    turnImageCount: Int,\n"
              "): Boolean =\n"
              "    contentParts.isNotEmpty() ||\n"
              "        content.isNotEmpty() ||\n"
              "        audioParts.isNotEmpty() ||\n"
              "        imageParts.isNotEmpty() ||\n"
              "        (isLastUserTurn && turnImageCount > 0)\n")
            for rel, var in (("openai/A.kt", "a"), ("openai/B.kt", "b"),
                             ("anthropic/C.kt", "c")):
                w(rel,
                  "class %s {\n"
                  "    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
                  "        val sites = raw\n"
                  "            .filterIndexed { index, m ->\n"
                  "                m.hasWirePayload(\n"
                  "                    isLastUserTurn = index == 0,\n"
                  "                    turnImageCount = images.size,\n"
                  "                )\n"
                  "            }\n"
                  "    }\n"
                  "}\n" % ("P" + var.upper()))
            return t, pkg

        p_root, p_pkg = payload_tree()
        try:
            v_clean, _sc, _rp = scan_root(p_root)
            check("media-aware filter sites are green",
                  not any("empty-message filter" in v or "payload predicate" in v
                          for v in v_clean),
                  str(v_clean[:2]))

            def mutate(rel, old, new_text):
                path = os.path.join(p_pkg, rel)
                with open(path, encoding="utf-8") as fh:
                    body = fh.read()
                assert old in body, "fixture mutation anchor missing in %s" % rel
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(body.replace(old, new_text, 1))

            # (1) predicate loses the audio arm
            mutate(PAYLOAD_PREDICATE_FILE, "        audioParts.isNotEmpty() ||\n", "")
            v_audio, _, _ = scan_root(p_root)
            check("dropping the audio arm is red",
                  any(PAYLOAD_PREDICATE_FILE in v and "audioParts" in v for v in v_audio),
                  str(v_audio[:2]))
            mutate(PAYLOAD_PREDICATE_FILE, "        content.isNotEmpty() ||\n",
                   "        content.isNotEmpty() ||\n        audioParts.isNotEmpty() ||\n")

            # (2) predicate loses the request-level turn-image arm
            mutate(PAYLOAD_PREDICATE_FILE,
                   "        imageParts.isNotEmpty() ||\n"
                   "        (isLastUserTurn && turnImageCount > 0)\n",
                   "        imageParts.isNotEmpty()\n")
            v_turn, _, _ = scan_root(p_root)
            check("dropping the turn-image arm is red",
                  any(PAYLOAD_PREDICATE_FILE in v and "turnImageCount" in v for v in v_turn),
                  str(v_turn[:2]))
            mutate(PAYLOAD_PREDICATE_FILE,
                   "        imageParts.isNotEmpty()\n",
                   "        imageParts.isNotEmpty() ||\n"
                   "        (isLastUserTurn && turnImageCount > 0)\n")

            # (3) one site reverts to the legacy inline predicate
            mutate("openai/B.kt",
                   "                m.hasWirePayload(\n"
                   "                    isLastUserTurn = index == 0,\n"
                   "                    turnImageCount = images.size,\n"
                   "                )\n",
                   "                m.contentParts.isNotEmpty() || m.content.isNotEmpty()\n")
            v_site, _, _ = scan_root(p_root)
            check("a site reverted to the inline text-only predicate is red",
                  any("openai/B.kt" in v and "empty-message filter" in v for v in v_site),
                  str(v_site[:2]))
            mutate("openai/B.kt",
                   "                m.contentParts.isNotEmpty() || m.content.isNotEmpty()\n",
                   "                m.hasWirePayload(\n"
                   "                    isLastUserTurn = index == 0,\n"
                   "                    turnImageCount = images.size,\n"
                   "                )\n")

            # (5) wide3-01: the media arms stay verbatim but a literal
            #     short-circuits the whole disjunction.
            write_fixture(os.path.join(p_pkg, PAYLOAD_PREDICATE_FILE),
                          "internal fun LLMMessage.hasWirePayload(\n"
                          "    isLastUserTurn: Boolean,\n"
                          "    turnImageCount: Int,\n"
                          "): Boolean =\n"
                          "    contentParts.isNotEmpty() ||\n"
                          "        content.isNotEmpty() ||\n"
                          "        (false && (audioParts.isNotEmpty() || "
                          "imageParts.isNotEmpty() || "
                          "(isLastUserTurn && turnImageCount > 0)))\n")
            v_dead, _sc, _rp = scan_root(p_root)
            check("a payload predicate short-circuited by `false &&` is red",
                  any("short-circuited by a boolean literal" in v for v in v_dead),
                  str(v_dead[:2]))

            # (4) the shared predicate file disappears
            os.remove(os.path.join(p_pkg, PAYLOAD_PREDICATE_FILE))
            v_gone, _, _ = scan_root(p_root)
            check("a missing payload predicate file is red",
                  any("payload predicate file is gone" in v for v in v_gone),
                  str(v_gone[:2]))
        finally:
            shutil.rmtree(p_root, ignore_errors=True)

        # [deep-audit-1047 A-03] Carrier arms must have a wire consumer, and the
        # image / audio carriers must not sit in mutually exclusive branches.
        # Fixtures live at module level (MEDIA_*) so they can be inspected and
        # mutated without the payload-filter scaffolding around them.
        m_root, m_pkg, m_snapshot = build_media_tree(MEDIA_MERGED)
        try:
            v_ok, _sc, _rp = scan_root(m_root)
            check("merged image/audio branch and full carrier reads are green",
                  not media_crit_codes(v_ok), str(v_ok[:2]))

            # (1) no serializer reads a carrier the predicate weighs -> C-IMG-1 only
            for rel in m_snapshot:
                write_fixture(os.path.join(m_pkg, rel), m_snapshot[rel]
                              .replace("msg.imageParts", "msg.contentParts"))
            v_unread, _, _ = scan_root(m_root)
            check("a carrier arm with no serializer reader is C-IMG-1 red",
                  media_crit_codes(v_unread) == {"C-IMG-1"},
                  str(sorted(media_crit_codes(v_unread))))
            for rel in m_snapshot:
                write_fixture(os.path.join(m_pkg, rel), m_snapshot[rel])

            # (2) split the merged branch back into exclusive `else if` arms
            write_fixture(os.path.join(m_pkg, "openai/A.kt"),
                          m_snapshot["openai/A.kt"].replace(MEDIA_MERGED, MEDIA_EXCLUSIVE))
            v_split, _, _ = scan_root(m_root)
            check("image and audio in exclusive else-if branches is C-IMG-2 red",
                  media_crit_codes(v_split) == {"C-IMG-2"},
                  str(sorted(media_crit_codes(v_split))))
            write_fixture(os.path.join(m_pkg, "openai/A.kt"), m_snapshot["openai/A.kt"])
            v_back, _, _ = scan_root(m_root)
            check("restoring the merged branch clears the C-IMG-2 arm",
                  not media_crit_codes(v_back), str(sorted(media_crit_codes(v_back))))
            # (3) wide3-01: a carrier arm added after a BLANK LINE must still be
            #     derived — the old slice ended at the first `\n\n`.
            write_fixture(os.path.join(m_pkg, PAYLOAD_PREDICATE_FILE),
                          PREDICATE_BODY.replace(
                              "        imageParts.isNotEmpty() ||\n",
                              "        imageParts.isNotEmpty() ||\n"
                              "\n"
                              "        unconsumedCarrierParts.isNotEmpty() ||\n"))
            v_blank, _, _ = scan_root(m_root)
            check("a carrier arm after a blank line is still derived (C-IMG-1 red)",
                  media_crit_codes(v_blank) == {"C-IMG-1"},
                  str(sorted(media_crit_codes(v_blank))))
            write_fixture(os.path.join(m_pkg, PAYLOAD_PREDICATE_FILE), PREDICATE_BODY)
            if "openai/A.kt" not in m_snapshot or MEDIA_MERGED not in m_snapshot["openai/A.kt"]:
                check("media fixture carries the merged branch it mutates", False)
        finally:
            shutil.rmtree(m_root, ignore_errors=True)

        missing_dir, scanned_missing, _ = scan_root(os.path.join(tmp, "does-not-exist"))
        check(
            "missing provider package is a hard failure, not a green skip",
            bool(missing_dir) and scanned_missing == 0,
        )
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)



# ── [deep-audit-1047 A-03] selftest fixtures for C-IMG-1 / C-IMG-2 ──────────
PREDICATE_BODY = (
    "internal fun LLMMessage.hasWirePayload(\n"
    "    isLastUserTurn: Boolean,\n"
    "    turnImageCount: Int,\n"
    "): Boolean =\n"
    "    contentParts.isNotEmpty() ||\n"
    "        content.isNotEmpty() ||\n"
    "        audioParts.isNotEmpty() ||\n"
    "        imageParts.isNotEmpty() ||\n"
    "        (isLastUserTurn && turnImageCount > 0)\n"
)
FILTER_SITE = (
    "        val sites = raw\n"
    "            .filterIndexed { index, m ->\n"
    "                m.hasWirePayload(\n"
    "                    isLastUserTurn = index == 0,\n"
    "                    turnImageCount = images.size,\n"
    "                )\n"
    "            }\n"
)
# Compliant shape: images (request-level + per-message) and audio share ONE branch,
# text last.
MEDIA_MERGED = (
    "        for ((index, msg) in sites.withIndex()) {\n"
    "            val legacy = (if (index == 0) images else emptyList()) + msg.imageParts\n"
    "            if (legacy.isNotEmpty() || msg.audioParts.isNotEmpty()) {\n"
    "                val contentArray = JSONArray()\n"
    "                for (part in legacy) {\n"
    '                    contentArray.put(JSONObject().put("type", "image_url"))\n'
    "                }\n"
    "                for (audio in msg.audioParts) {\n"
    '                    contentArray.put(JSONObject().put("type", "input_audio"))\n'
    "                }\n"
    "                if (msg.content.isNotEmpty()) {\n"
    '                    contentArray.put(JSONObject().put("type", "text"))\n'
    "                }\n"
    "            }\n"
    "        }\n"
)
# Defect shape (88ef9b27): images and audio are exclusive `else if` siblings.
MEDIA_EXCLUSIVE = (
    "        for ((index, msg) in sites.withIndex()) {\n"
    "            if (index == 0 && images.isNotEmpty()) {\n"
    "                val contentArray = JSONArray()\n"
    "                for (part in images) {\n"
    '                    contentArray.put(JSONObject().put("type", "image_url"))\n'
    "                }\n"
    "                val alt = msg.imageParts\n"
    "            } else if (msg.audioParts.isNotEmpty()) {\n"
    "                val contentArray = JSONArray()\n"
    "                for (audio in msg.audioParts) {\n"
    '                    contentArray.put(JSONObject().put("type", "input_audio"))\n'
    "                }\n"
    "            }\n"
    "        }\n"
)


def write_fixture(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(body)


def build_media_tree(media_body):
    """-> (root, provider_dir, {rel: text}) for the C-IMG fixtures.

    A carries both carriers; B and C serialize images only (Anthropic and Gemini
    have no audio-input shape, so the carrier criterion is tree-wide by design).
    """
    root = tempfile.mkdtemp(prefix="trimg-media-")
    pkg = os.path.join(root, PROVIDER_REL)
    os.makedirs(pkg, exist_ok=True)
    files = {
        PAYLOAD_PREDICATE_FILE: PREDICATE_BODY,
        # RequestSanitizers is exempt in the real tree; a fixture tree without it
        # would report that exemption as stale and mask the arms under test.
        "RequestSanitizers.kt": "fun sanitizeToolPairing() {}\n",
        "openai/A.kt": (
            "class A {\n"
            "    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
            + FILTER_SITE
            + "        val parts = sites.map { it.contentParts.size + it.content.length }\n"
            + media_body
            + "    }\n"
            "}\n"),
        "anthropic/B.kt": (
            "class B {\n"
            "    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
            + FILTER_SITE
            + '        put("type", "image")\n'
            "        val n = sites.map { it.contentParts.size + it.content.length }\n"
            "    }\n"
            "    fun g(msg: LLMMessage) = msg.imageParts.size\n"
            "}\n"),
        "gemini/C.kt": (
            "class C {\n"
            "    fun f(raw: List<LLMMessage>, images: List<Any>) {\n"
            + FILTER_SITE
            + '        put("inlineData", images)\n'
            "        val n = sites.map { it.contentParts.size + it.content.length }\n"
            "    }\n"
            "    fun g(msg: LLMMessage) = msg.imageParts.size\n"
            "}\n"),
    }
    for rel, body in files.items():
        write_fixture(os.path.join(pkg, rel), body)
    return root, pkg, dict(files)


def media_crit_codes(violations):
    """-> the C-IMG criteria a violation list actually fails."""
    codes = set()
    for v in violations:
        if "no media serializer under the provider package ever reads" in v:
            codes.add("C-IMG-1")
        if "input_audio" in v:
            codes.add("C-IMG-2")
    return codes


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        print("tool_result_image_branch_check self-test:")
        return selftest()
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    violations, scanned, report = scan_root(root)
    for rel, status in report:
        print("  %-8s %s" % ("[" + status + "]", rel))
    if scanned == 0:
        violations.append(
            "no provider file handling tool-result parts was found — refusing to report green"
        )
    print("  scanned %d provider file(s)" % scanned)
    if violations:
        print("")
        print("❌ tool_result_image_branch_check findings:")
        for v in violations:
            print("   - %s" % v)
        return 1
    print("")
    print("✅ tool-result image guard: every provider serializer consumes ToolResult.imageData")
    return 0


if __name__ == "__main__":
    sys.exit(main())
