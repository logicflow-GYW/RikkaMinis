#!/usr/bin/env python3
"""
Gemini base-version guard — the version segment the request path depends on.

Bug class (deep-audit-1016 ISSUE-01, live ~27 days): `GeminiProvider` assembles
`<base>/models/<id>:generateContent`. `bd68c66c` (2026-09-19, FIX-1) made it strip
`/v1beta` and `/v1` from the configured base — mirrors the models-LIST helper, which
collapses the two forms relays paste — but never restored a segment. The endpoint has
no versionless form, so every Gemini instance (official base included) got 404 on both
chat paths, while the model list kept working: model list 200, chat 404. Measured
ground truth: `/models/<id>:generateContent` -> 404 with an empty body;
`/v1beta/models/<id>:generateContent` -> 400 "API key not valid" (path exists).

Criterion (structural — the behavioural half lives in `GeminiBasePathTest` /
`GeminiProviderTest`, which assert the built URL for every base shape):
  1. Both base normalizers exist and are found **per file**:
       * `GeminiProvider.kt`   -> `normalizedBasePath`
       * `GeminiModelsApi.kt`  -> `normalizeBase`
       * `GeminiBasePath.kt`   -> `normalize` (the extracted shared implementation)
     A missing function is a HARD FAIL, not a skip: a renamed or moved normalizer must
     fail loudly instead of leaving the gate silently green (audit3 FIX-D's lesson —
     a crude mutation dropped a host from the scanned set and the gate passed).
  2. Each normalizer either (a) delegates to `GeminiBasePath.normalize(...)`, or
     (b) itself strips `/v1beta`, strips `/v1`, and restores exactly one `/v1beta`.
     Files are checked individually: a union across files is what hid the split shape
     for a month (the list side restored the segment, the chat side did not).
  3. `GeminiProvider` routes **both** call sites (non-streaming and streaming) through
     the normalizer, and no call site concatenates the raw `basePath` — a single
     bypassing call site is the exact shape this defect can come back in.
  4. [deep-audit-1024 ISSUE-02] The three **other** sites that build a Gemini URL from
     the configured base must delegate too: `GeminiModelsApi.normalizeBase` (checked
     here as "delegates, no shadow copy"), `GeminiVoiceProvider.buildVoiceOutputRequest`
     and the probe-URL builder in `ProviderMutationMethods.kt`. Criteria 4 and 5 need a
     full check-out to say anything: the frozen `test_scan.py` fixtures build a
     gemini-only tree, so when either cross-file target is missing the gate prints
     `[PARTIAL]` and falls back to criteria 1-3 rather than failing on a partial tree.
  5. Any `ProviderType` arm assembling a `/models` probe URL must apply the same
     version-segment rule as its siblings: `anthropic` guards on `endsWith("/v1")`,
     `gemini` goes through [SHARED_DELEGATE]. Before the fix the two arms were the only
     two without the guard, which is what made the 404 reachable only for those types.

Usage: python3 scripts/scan/gemini_base_version_check.py [repo_root]
       python3 scripts/scan/gemini_base_version_check.py --selftest
Exit:  0 = all checks pass, 1 = a missing/incorrect normalizer or wiring
"""
import contextlib
import io
import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse as _kotlin_lex_analyse  # noqa: E402
from _kotlin_ast import const_bool  # noqa: E402

GEMINI_DIR = os.path.join(
    "src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app", "provider", "gemini"
)

APP_DIR = os.path.join("src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app")
VOICE_REL = os.path.join(APP_DIR, "provider", "voice", "VoiceProviderVendors.kt")
MUTATION_REL = os.path.join(APP_DIR, "debug", "ProviderMutationMethods.kt")

# Both must exist before the cross-file criteria (4/5) can say anything: a tree holding
# only the gemini package cannot express a delegation graph across four files.
CROSS_FILE_RELS = (VOICE_REL, MUTATION_REL)
VOICE_CLASS = "GeminiVoiceProvider"
VOICE_FN = "buildVoiceOutputRequest"
PROBE_ARM_MARK = "/models"

# [deep-audit-1039 A-05] The voice family's own base normalizations. Both were
# inline copies with the wrong predicate width:
#   * Azure: the /cognitiveservices strip ran BEFORE any trailing-slash trim, so
#     a custom base pasted as `https://…speech.microsoft.com/` never matched and
#     the endpoint path appended the segment a second time.
#   * ElevenLabs / Deepgram: `if (!base.contains("/v1")) base += "/v1"` asks
#     "does this path mention /v1 anywhere", but concatenation needs the segment
#     as the SUFFIX to end up with exactly one copy.
VOICE_FACTORY_REL = os.path.join(APP_DIR, "provider", "voice", "VoiceProviderFactory.kt")
VOICE_NORMALIZER_REL = os.path.join(APP_DIR, "provider", "voice", "VoiceBaseNormalizer.kt")
VOICE_RULE_FN = "normalizeVersionSegment"
AZURE_RULE_FN = "normalizeAzureBase"
VOICE_NORMALIZER_DELEGATE = "VoiceBaseNormalizer."
BASELINE_VERSION_PREDICATE = 'contains("/v1")'
VOICE_RULE_CALL_SITES = 2  # ElevenLabs + Deepgram's shared dgBase()

# file -> normalizer function name
TARGETS = {
    "GeminiProvider.kt": "normalizedBasePath",
    "GeminiModelsApi.kt": "normalizeBase",
    "GeminiBasePath.kt": "normalize",
}

SHARED_DELEGATE = "GeminiBasePath.normalize"


class LexFailure(Exception):
    """Source the shared Kotlin lexer cannot project. The gate reports it as a
    violation (fail closed) instead of judging a half-masked file."""



def _skip_string(text, i):
    """Index just past the string literal starting at `text[i]` (a quote)."""
    if text.startswith('"""', i):
        j = text.find('"""', i + 3)
        return len(text) if j < 0 else j + 3
    q = text[i]
    i += 1
    while i < len(text) and text[i] != q:
        if text[i] == "\\":
            i += 2
        elif text.startswith("${", i):
            i = _skip_template(text, i + 2)
        else:
            i += 1
    return i + 1


def _skip_template(text, i):
    """Index just past the `}` matching an already-consumed `${`."""
    depth = 1
    while i < len(text):
        c = text[i]
        if c == '"' or c == "'":
            i = _skip_string(text, i)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return len(text)


def strip_comments(text):
    """Comments blanked (same length, newlines kept), STRING LITERALS INTACT.

    Literals must survive: the criteria below look for `"/v1beta"` and for URL
    templates. This used to be a hand-rolled scanner that ended a block comment at
    the FIRST `*/`; Kotlin nests them, so `/* a /* b */ c */` left `c */` (and any
    `return` hidden in `c`) outside the comment — a forged top-level return with the
    real code pushed one brace deeper (wide4 witness `strip_comments /
    _top_level_returns`). The shared lexer handles nested block comments, char
    literals, raw strings and `${...}` templates; a file it cannot lex raises
    `LexFailure` so the gate fails closed instead of judging a broken projection.
    """
    ok, reason, views = _kotlin_lex_analyse(text)
    if not ok:
        raise LexFailure(reason)
    return views["comments"]


def code_view(text):
    """Same-length view with comments AND string contents blanked.

    Structure (braces, `if`/`return` keywords) is judged on this view: a brace or a
    `return` living inside a literal is not code. Positions are identical to
    `strip_comments`'s output, so a span found here can be sliced from the literal-
    preserving view to read the actual value.
    """
    ok, reason, views = _kotlin_lex_analyse(text)
    if not ok:
        raise LexFailure(reason)
    return views["code"]


CONTINUATION_CHARS = "||&&,.+-*/?:=(<["


def expr_span(text, start):
    """Expression text from `start` to the end of the statement.

    A newline only ends the expression when the last non-space character is not
    a continuation operator — a blank line in the middle of a multi-line
    expression must NOT truncate it (the old `find("\\n\\n")` slice let a blank
    line hide the rest of a predicate).
    """
    depth = 0
    i = start
    n = len(text)
    last = ""
    while i < n:
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


def _close_index(text, k, open_ch, close_ch):
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


def function_body(text, name):
    """Return the body of `fun <name>(...)`; None if not found.

    Block bodies are brace-balanced over code only. Expression bodies are
    captured with `expr_span`, which keeps multi-line expressions (including
    blank lines) together — the old single-line slice let a one-line
    `.also { while (false) { ... } }` carry every token while returning the raw
    argument.
    """
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", text)
    if not m:
        return None
    i = text.find("(", m.end() - 1)
    close = _close_index(text, i, "(", ")")
    if close < 0:
        return None
    # expression body (`: Type = expr`) or block body (`{ ... }`) — whichever the
    # signature reaches first. These normalizers are one-liners when they delegate.
    tail = text[close + 1:]
    eq = tail.find("=")
    brace = tail.find("{")
    if eq >= 0 and (brace < 0 or eq < brace):
        return expr_span(text, close + 1 + eq + 1).strip()
    if brace < 0:
        return None
    j = close + 1 + brace  # brace was found in `tail`; the scan below indexes `text`
    end = _close_index(text, j, "{", "}")
    if end < 0:
        return None
    return text[j + 1:end]

def restores_version(body):
    """True when the body puts `/v1beta` back, rather than only stripping it.

    Strip the strip-calls out first; a `/v1beta` that survives is a restore.
    """
    if SHARED_DELEGATE in body:
        return True
    residue = body.replace('.removeSuffix("/v1beta")', "").replace('.removeSuffix("/v1")', "")
    return "/v1beta" in residue


RESTORE_SUFFIX_RE = re.compile(r'/v1beta"\s*$|\+\s*"/v1beta"')

# The value-shaping operators. Every one can keep the restored text in the file while
# handing the caller something else:
#   `return stripped.takeIf { true } ?: "$stripped/v1beta"`  -> Elvis right side is dead,
#   `return if (1 == 1) stripped else "$stripped/v1beta"`    -> same, one spelling over,
#   `GeminiBasePath.normalize(x).let { basePath }`           -> `.let` replaces the value.
# `.also` / `.apply` are deliberately absent: both return their RECEIVER.
_DECIDER_RE = re.compile(
    r"\?:|\bif\b|\bwhen\b|\.let\b|\.run\b|\.with\b|\.takeIf\b|\.takeUnless\b")

# The value shapes that DO carry the version segment — positive assertions, not another
# blacklist of dead spellings (every new `false &&`-style spelling walked through the
# previous blacklist: wide4 family-hole axis).
RESTORE_TEMPLATE_RE = re.compile(r'^"\$\{?[A-Za-z_]\w*\}?/v1beta"$')
RESTORE_CONCAT_RE = re.compile(r'^[A-Za-z_][\w.]*(?:\([^()]*\))?\s*\+\s*"/v1beta"$')
RESTORE_LITERAL_RE = re.compile(r'^"[^"]*/v1beta"$')
_SCOPE_TAIL_RE = re.compile(r"\.(?:also|apply)$")


def _peel_scope(chain):
    """Drop trailing `.also` / `.apply` tokens (`_chain_only` removed their blocks).

    Both are receiver-preserving, so `normalize(x).also { log() }` still hands back the
    normalized base. Everything else in `_DECIDER_RE` is value-shaping: a violation,
    not something to peel.
    """
    while True:
        m = _SCOPE_TAIL_RE.search(chain.rstrip())
        if not m:
            return chain.strip()
        chain = chain[:m.start()]


def _call_tail(chain, head):
    """-> text after `head(...)`'s closing paren, or None when `chain` is not that call.

    `None` = the value is not this call's result; `""` = the call IS the value; a
    non-empty tail = something value-shaping trails it (`.let`, `?:`, …) -> rejected.
    """
    idx = chain.find(head)
    if idx < 0:
        return None
    if chain[:idx].strip():
        return None
    op = chain.find("(", idx + len(head))
    if op < 0:
        return None
    cp = _close_index(chain, op, "(", ")")
    if cp < 0:
        return None
    return chain[cp + 1:].strip()


def _value_is_call(expr_text, expr_code, head):
    """Does `expr` evaluate to exactly `head(...)`?

    Judged on the code view (a `.let` spelled inside a literal is not a decider), with
    the literal-preserving view confirming the call's own spelling.
    """
    chain_code = _peel_scope(_chain_only(expr_code))
    chain_text = _peel_scope(_chain_only(expr_text))
    if _DECIDER_RE.search(chain_code):
        return False, chain_text
    if _call_tail(chain_code, head) != "":
        return False, chain_text
    if _call_tail(chain_text, head) != "":
        return False, chain_text
    return True, chain_text


def _assignments(src):
    """-> {name: [rhs, ...]} for `val|var name = rhs` and plain `name = rhs`."""
    out = {}
    for m in re.finditer(
            r"(?:const\s+)?(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=\s*([^\n]+)", src):
        out.setdefault(m.group(1), []).append(m.group(2))
    for m in re.finditer(r"(?m)^[ \t]+([A-Za-z_]\w*)\s*=\s*([^\n]+)", src):
        out.setdefault(m.group(1), []).append(m.group(2))
    return out


def _const_names(src):
    return set(m.group(1) for m in re.finditer(r"\bconst\s+val\s+([A-Za-z_]\w*)", src))


def _top_level_return_spans(code):
    """[(start, end)] of every depth-0 `return` EXPRESSION in `code`.

    `code` is the string-blanked view: a `return` inside a literal or a brace cannot
    register, and braces balance even when a `${...}` template nests them. A body with
    no top-level `return` is judged WHOLE (expression-body fun) — so a block body that
    lost its `return` falls into the same check instead of being skipped as "nothing
    to look at" (wide4 parser-death witness: a nested block comment forged a return and
    pushed the real one one brace deeper).
    """
    spans = []
    depth = 0
    i = 0
    while i < len(code):
        c = code[i]
        if c == '"' or c == "'":
            i = _skip_string(code, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and re.match(r"return\b", code[i:]):
            raw = expr_span(code, i + 6)
            body = raw.strip()
            start = i + 6 + (len(raw) - len(raw.lstrip()))
            spans.append((start, start + len(body)))
            i += 6 + len(raw)
            continue
        i += 1
    if not spans:
        start = len(code) - len(code.lstrip())
        spans.append((start, start + len(code.strip())))
    return spans


def _chain_only(expr):
    """`expr` with nested `{...}` bodies dropped: `.also { ... }` returns its
    RECEIVER, so whatever happens inside the lambda cannot restore the value."""
    out = []
    depth = 0
    i = 0
    while i < len(expr):
        c = expr[i]
        if c == '"' or c == "'":
            j = _skip_string(expr, i)
            out.append(expr[i:j])
            i = j
            continue
        if c == "{":
            depth += 1
            i += 1
            continue
        if c == "}":
            depth = max(0, depth - 1)
            i += 1
            continue
        if depth == 0:
            out.append(c)
        i += 1
    return "".join(out)


def returned_value_restores(body, body_code, src):
    """Does the VALUE the normalizer returns carry the version segment?

    A restore that lives only in the body (`val unused = "/v1beta"`, or inside a
    `.also { }` whose receiver is the raw argument) hands the caller a
    versionless base: `/models/<id>:generateContent` then 404s for every
    instance. Each top-level `return` chain must POSITIVELY match a carrying shape
    (`"$x/v1beta"`, `x + "/v1beta"`, or a constant that itself ends in `/v1beta`), and
    must not carry a value-shaping operator — `?:` / `if` / `when` / `.let` / `.run` /
    `.takeIf` / `.takeUnless` are exactly "keep the spelling, drop the decision".
    """
    if SHARED_DELEGATE in body_code:
        return _delegation_returns_value(body, body_code)
    table = _assignments(src)
    for start, end in _top_level_return_spans(body_code):
        chain = _peel_scope(_chain_only(body_code[start:end]))
        chain_text = _peel_scope(_chain_only(body[start:end]))
        if not chain:
            return False, "<empty>"
        if _DECIDER_RE.search(chain):
            return False, chain_text
        if RESTORE_TEMPLATE_RE.match(chain_text) or RESTORE_CONCAT_RE.match(chain_text):
            continue
        if re.fullmatch(r"[A-Za-z_]\w*", chain):
            # an early exit to a named default: the NAME only counts when its value
            # carries the segment (`return DEFAULT_BASE` is verified, not assumed)
            if any(RESTORE_LITERAL_RE.match(rhs.strip()) for rhs in table.get(chain, ())):
                continue
        return False, chain_text
    return True, ""


def _delegation_returns_value(body, body_code):
    """Delegation is only a fix when the delegate's RESULT is the returned value:
    `GeminiBasePath.normalize(basePath).let { basePath }` computes the normalized base
    and hands back the raw one (wide4 witness `function_body / SHARED_DELEGATE`)."""
    for start, end in _top_level_return_spans(body_code):
        ok, chain = _value_is_call(body[start:end], body_code[start:end], SHARED_DELEGATE)
        if not ok:
            return False, chain
    return True, ""


def analyse(body, body_code, src=""):
    """Which structural pieces the normalizer body has."""
    restores, chain = returned_value_restores(body, body_code, src)
    return {
        "strips /v1beta": '.removeSuffix("/v1beta")' in body,
        "strips /v1": '.removeSuffix("/v1")' in body,
        "restores /v1beta": restores_version(body),
        "restore /v1beta in the returned value": restores,
    }, chain


def balanced_block(text, open_idx):
    """Index just past the closer matching text[open_idx]; -1 when unbalanced."""
    depth = 0
    i = open_idx
    while i < len(text):
        c = text[i]
        if c == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def when_blocks(text):
    """Body of every `when (...) { ... }` in [text], brace-balanced.

    Block-scoped on purpose: arms are split per block, so a `ProviderType.x ->`
    outside the block under test cannot bleed into an arm's text.
    """
    out = []
    for m in re.finditer(r"\bwhen\s*\(", text):
        brace = text.find("{", m.end())
        if brace < 0 or brace - m.end() > 200:
            continue
        end = balanced_block(text, brace)
        if end < 0:
            continue
        out.append(text[brace + 1 : end - 1])
    return out


def provider_arms(block):
    """(type name, arm text) for every `ProviderType.<x> -> ...` arm in [block]."""
    marks = list(re.finditer(r"ProviderType\.(\w+)\s*->", block))
    for idx, m in enumerate(marks):
        end = marks[idx + 1].start() if idx + 1 < len(marks) else len(block)
        yield m.group(1), block[m.end() : end]


def class_body(text, class_name):
    """Body of `class <class_name>` — brace-balanced, None when absent."""
    m = re.search(r"\bclass\s+" + re.escape(class_name) + r"\s*[(<]", text)
    if not m:
        return None
    brace = text.find("{", m.end())
    if brace < 0:
        return None
    end = balanced_block(text, brace)
    return None if end < 0 else text[brace + 1 : end - 1]


def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return strip_comments(f.read())


DEAD_CODE_RE = re.compile(r"\b(?:if|while)\s*\(\s*false\s*\)")
IF_RE = re.compile(r"\bif\s*\(")
# The anthropic probe arm, in its correct spelling. `cond` == endsWith("/v1") must
# assemble `"$baseURL/models"` on the true branch and `"$baseURL/v1/models"` on the
# false one; a negated cond swaps them. Checking only the CONDITION let a swapped or
# rewritten branch keep the guard token while probing the wrong URL.
PROBE_ARM_MODELS = '"$baseURL/models"'
PROBE_ARM_V1_MODELS = '"$baseURL/v1/models"'


def _squash(text):
    """Whitespace outside string literals removed, so `"$baseURL/models"` compares
    exactly and `"$baseURL" + "/models"` does not."""
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == '"' or c == "'":
            j = _skip_string(text, i)
            out.append(text[i:j])
            i = j
            continue
        if not c.isspace():
            out.append(c)
        i += 1
    return "".join(out)


def _if_parts(arm, arm_code):
    """-> [(cond_text, cond_code, then_text, else_text)] for every `if (...)` in `arm`.

    Structure comes from the code view (braces inside literals cannot shift the
    parens); the value texts come from the literal-preserving view at the same span.
    """
    out = []
    for m in re.finditer(r"\bif\s*\(", arm_code):
        op = m.end() - 1
        cp = _close_index(arm_code, op, "(", ")")
        if cp < 0:
            continue
        tail_code = arm_code[cp + 1:]
        tail_text = arm[cp + 1:]
        then_text, else_text = _split_else(tail_code, tail_text)
        out.append((arm[op + 1:cp], arm_code[op + 1:cp], then_text, else_text))
    return out


def _split_else(tail_code, tail_text):
    """Split a braceless `then <else> else` tail at its top-level `else`."""
    depth = 0
    i = 0
    while i < len(tail_code):
        c = tail_code[i]
        if c == '"' or c == "'":
            i = _skip_string(tail_code, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and re.match(r"else\b", tail_code[i:]):
            return tail_text[:i].strip(), tail_text[i + 4:].strip()
        i += 1
    return tail_text.strip(), ""


def _live_guard_testing(arm, needle):
    """-> (found, live, detail): does a guard test `needle`, and is it a LIVE guard?

    Three ways the guard token survives while the decision dies:
      * the condition folds to a constant — `false &&`, `!true`, `(1 == 2) &&`, `0 == 1`;
      * the branches assemble the wrong URL (swapped, or the /v1 arm lost its segment).
    Both are checked; `detail` says which one, for the finding text.
    """
    arm_code = code_view(arm)
    found = False
    detail = ""
    live = False
    for cond_text, cond_code, then_text, else_text in _if_parts(arm, arm_code):
        if needle not in cond_text:
            continue
        found = True
        if const_bool(cond_code) is not None:
            detail = detail or "constant"
            continue
        negated = _squash(cond_text).lstrip("(").startswith("!")
        expect_then, expect_else = (
            (PROBE_ARM_V1_MODELS, PROBE_ARM_MODELS) if negated
            else (PROBE_ARM_MODELS, PROBE_ARM_V1_MODELS)
        )
        if _squash(then_text) != expect_then or _squash(else_text) != expect_else:
            detail = detail or "branches"
            continue
        live = True
    return found, live, detail

MIN_URL_SITES = 2
URL_MODEL_MARK_RE = re.compile(r":(?:stream)?[Gg]enerateContent")
BASE_INTERP_RE = re.compile(r"(?:\$\{([^}]*)\}|\$([A-Za-z_]\w*))\s*/models/")
FUN_HEAD_RE = re.compile(r"\bfun\s+[A-Za-z_]\w*\s*\(")


def _string_literals(text):
    """-> [(start, literal-text)] for every double-quoted literal."""
    out = []
    i = 0
    while i < len(text):
        if text[i] != '"':
            i += 1
            continue
        j = _skip_string(text, i)
        out.append((i, text[i:j]))
        i = j
    return out


def enclosing_function(src, pos):
    """Text of the innermost `fun` body that contains `pos` (None when outside)."""
    best = None
    for m in FUN_HEAD_RE.finditer(src):
        if m.start() > pos:
            break
        op = src.find("(", m.end() - 1)
        cp = _close_index(src, op, "(", ")")
        if cp < 0 or cp > pos:
            continue
        k = cp + 1
        while k < pos and src[k] not in "{=":
            k += 1
        if k >= pos:
            continue
        if src[k] == "{":
            end = _close_index(src, k, "{", "}")
            if end < 0 or end < pos:
                continue
            best = src[m.start():end + 1]
        else:
            best = src[m.start():]
    return best


def _binding_rhs(fn, name):
    """-> (rhs_text, write_count) for local `val|var name = ...` bindings inside `fn`.

    `rhs_text` is the WHOLE initializer (multi-line aware via `expr_span`), not just
    the text up to the first newline: `val b = if (false) normalizedBasePath() else
    basePath` has to be read as one expression, otherwise a dead-branch pick looks like
    a normalized binding. `write_count` counts every assignment to the name (the
    declaration plus any rebinding), so a good initializer cannot hide a later write.
    """
    rhs = None
    writes = 0
    for m in re.finditer(r"\b(?:val|var)\s+" + re.escape(name) + r"\s*(?::[^=\n]+?)?=(?!=)", fn):
        rhs = expr_span(fn, m.end()).strip()
        writes += 1
    for m in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\s*=(?!=)", fn):
        before = fn[:m.start()].rstrip()
        if before.endswith("val") or before.endswith("var"):
            continue  # the declaration itself, already counted
        writes += 1
    return rhs, writes


def check_url_routing(src):
    """-> (routed_sites, violations) for every models URL built from a base.

    Derived from the literals themselves: the interpolation that sits directly
    before `/models/` is the base. A site is ROUTED when that interpolation is
    the normalizer itself, or a local whose initializer IS the normalizer call;
    every site that reads anything else is a violation. `routed` counts accepted
    sites only, so a third bypassing site cannot inflate the count the way the
    old `normalizedBasePath()` tally did.

    The local-binding arm is judged on the initializer's VALUE, not on the line
    containing the name: a dead-branch pick (`if (false) normalizedBasePath() else
    basePath`) and a `.let` that replaces the result with the raw base both mention
    the normalizer and both hand the request the raw base (wide4 witness
    `check_url_routing`).
    """
    violations = []
    routed = 0
    code = code_view(src)
    for start, literal in _string_literals(src):
        if not URL_MODEL_MARK_RE.search(literal):
            continue
        interps = list(BASE_INTERP_RE.finditer(literal))
        if not interps:
            continue  # a hard-coded base needs no normalization
        fn = enclosing_function(src, start) or ""
        ok = True
        for m in interps:
            expr = (m.group(1) or m.group(2) or "").strip()
            if "normalizedBasePath(" in expr and not _DECIDER_RE.search(expr):
                continue
            if re.fullmatch(r"[A-Za-z_]\w*", expr):
                rhs, writes = _binding_rhs(fn, expr)
                if rhs is not None and writes == 1:
                    good, _ = _value_is_call(rhs, code_view(rhs), "normalizedBasePath")
                    if good:
                        continue
            ok = False
            line = src[:start].count("\n") + 1
            violations.append(
                f"GeminiProvider.kt:{line} the models URL interpolates `{expr}` — a raw basePath "
                "instead of the normalized base — so a base ending in /v1 (appendV1Suffix) or "
                "carrying a duplicate segment 404s on every non-streaming call")
        if ok:
            routed += 1
    return routed, violations


def _gemini_probe_delegates(arm):
    """-> (ok, chain): the gemini probe arm must interpolate the DELEGATE's value.

    `SHARED_DELEGATE in arm` only proved the name appeared; `baseURL`/`basePath` can
    be handed straight back by a `.let { … }` tail on the interpolated call, which is
    the same discarded-result shape as everywhere else in this gate.
    """
    if SHARED_DELEGATE not in arm:
        return False, ""
    for m in re.finditer(r"\$\{([^{}]*)\}", arm):
        expr = m.group(1).strip()
        if SHARED_DELEGATE not in expr:
            continue
        return _value_is_call(expr, code_view(expr), SHARED_DELEGATE)
    return False, "no ${...} interpolation carries the delegate call"


def _voice_delegates(fn):
    """-> (ok, chain): the voice site must bind the delegate's value and use that binding.

    A `.let { effectiveBaseURL() }` tail leaves the delegate call in place while the
    raw base is what the URL is built from.
    """
    if SHARED_DELEGATE not in fn:
        return False, ""
    bound = None
    for m in re.finditer(r"\bval\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=(?!=)", fn):
        rhs = expr_span(fn, m.end()).strip()
        if SHARED_DELEGATE not in rhs:
            continue
        ok, chain = _value_is_call(rhs, code_view(rhs), SHARED_DELEGATE)
        if not ok:
            return False, chain
        if bound is None:
            bound = m.group(1)
    if bound is None:
        return False, "the delegate call's value is never bound to a name"
    if not re.search(r"\$" + re.escape(bound) + r"\b", fn):
        return False, bound
    return True, ""


def check_cross_file_sites(root, gemini_bodies):
    """Criteria 4/5 — the three sibling sites must delegate like the shared rule.

    Only called when both cross-file targets are present: a tree holding just the
    gemini package (the frozen `test_scan.py` fixtures) cannot express this graph.
    """
    failures = []

    sibling = gemini_bodies.get("GeminiModelsApi.kt")
    if sibling is None:
        failures.append("GeminiModelsApi.kt::normalizeBase not found — cannot confirm it delegates")
        print("  [ROT] GeminiModelsApi.kt::normalizeBase not found")
    elif SHARED_DELEGATE in sibling:
        ok, chain = _delegation_returns_value(sibling, code_view(sibling))
        if ok:
            print(f"  [OK] GeminiModelsApi.kt::normalizeBase delegates to {SHARED_DELEGATE}")
        else:
            failures.append(
                "GeminiModelsApi.kt::normalizeBase calls "
                f"{SHARED_DELEGATE}(...) but does not return its value (returns `{chain}`) — "
                "the normalized base is computed and thrown away, so the models list asks a "
                "versionless (or doubly-versioned) URL"
            )
            print("  [FAIL] GeminiModelsApi.kt::normalizeBase discards the delegate's value")
    else:
        failures.append(
            "GeminiModelsApi.kt::normalizeBase carries its own copy of the rule — a second "
            f"implementation is exactly ISSUE-02; it must call {SHARED_DELEGATE}(...)"
        )
        print("  [FAIL] GeminiModelsApi.kt::normalizeBase has a shadow copy")

    voice = read(VOICE_REL, root)
    body = class_body(voice, VOICE_CLASS) if voice else None
    fn = function_body(body, VOICE_FN) if body else None
    if fn is None:
        failures.append(f"{VOICE_REL}: `{VOICE_CLASS}.{VOICE_FN}` not found — keep this gate honest")
        print(f"  [ROT] {VOICE_CLASS}::{VOICE_FN} not found")
    elif SHARED_DELEGATE in fn:
        ok, chain = _voice_delegates(fn)
        if ok:
            print(f"  [OK] {VOICE_CLASS}::{VOICE_FN} delegates to {SHARED_DELEGATE}")
        else:
            failures.append(
                f"{VOICE_CLASS}::{VOICE_FN} calls {SHARED_DELEGATE}(...) but the request URL is "
                f"not built from its value (returns `{chain}`) — the normalized base is computed "
                "and discarded, so a base that already carries /v1beta goes out doubly versioned"
            )
            print(f"  [FAIL] {VOICE_CLASS}::{VOICE_FN} discards the delegate's value")
    else:
        failures.append(
            f"{VOICE_CLASS}::{VOICE_FN} builds the Gemini base itself (contains-check on "
            f"/v1beta) — it must call {SHARED_DELEGATE}(effectiveBaseURL())"
        )
        print(f"  [FAIL] {VOICE_CLASS}::{VOICE_FN} does not delegate")

    mutation = read(MUTATION_REL, root)
    probe_blocks = [
        b for b in when_blocks(mutation or "") if "ProviderType." in b and PROBE_ARM_MARK in b
    ]
    if not probe_blocks:
        failures.append(
            f"{MUTATION_REL}: no `when` block assembles a `{PROBE_ARM_MARK}` probe URL — the "
            "probe URL moved; re-anchor this gate deliberately"
        )
        print(f"  [ROT] {MUTATION_REL}: probe URL block not found")
        return failures

    seen = {"gemini": 0, "anthropic": 0}
    for block in probe_blocks:
        for type_name, arm in provider_arms(block):
            if PROBE_ARM_MARK not in arm:
                continue
            if type_name == "gemini":
                seen["gemini"] += 1
                ok, chain = _gemini_probe_delegates(arm)
                print(f"  [{'OK' if ok else 'FAIL'}] probe arm ProviderType.gemini delegates")
                if not ok:
                    failures.append(
                        "ProviderMutationMethods.kt: the gemini probe arm interpolates "
                        f"`{chain}` instead of the bare {SHARED_DELEGATE}(...) result — the "
                        "delegate is called and its value thrown away, so a custom base that "
                        "already carries /v1beta probes /v1beta/v1beta/models (404)"
                    )
            elif type_name == "anthropic":
                seen["anthropic"] += 1
                guard_found, guard_live, guard_detail = _live_guard_testing(arm, 'endsWith("/v1")')
                ok = guard_found and guard_live
                print(f"  [{'OK' if ok else 'FAIL'}] probe arm ProviderType.anthropic guards on /v1")
                if not ok:
                    if guard_found and guard_detail == "constant":
                        failures.append(
                            "ProviderMutationMethods.kt: the anthropic probe arm keeps the "
                            "endsWith(\"/v1\") comparison but short-circuits it with a constant "
                            "condition (`false && ...`, `(1 == 2) && ...`, `!true && ...`) — the "
                            "raw arm runs, so a base that already ends in /v1 probes "
                            "/v1/v1/models (404)"
                        )
                    elif guard_found:
                        failures.append(
                            "ProviderMutationMethods.kt: the anthropic probe arm tests "
                            "endsWith(\"/v1\") but the branches assemble the wrong URL (`then` must "
                            "be \"$baseURL/models\", `else` \"$baseURL/v1/models\") — the guard "
                            "token survives and the decision does not, so a base ending in /v1 "
                            "probes /v1/v1/models (404)"
                        )
                    else:
                        failures.append(
                            "ProviderMutationMethods.kt: the anthropic probe arm appends /v1 with no "
                            "endsWith(\"/v1\") guard — effectiveBaseURL already ends in /v1 when "
                            "appendV1Suffix applied, so it probes /v1/v1/models (404). The sibling "
                            "openAI/xAI/kimiCode arms all guard; the asymmetry is the defect."
                        )
    for type_name, count in sorted(seen.items()):
        if count == 0:
            failures.append(
                f"{MUTATION_REL}: no ProviderType.{type_name} arm carries a `{PROBE_ARM_MARK}` "
                "probe URL — anchor moved, re-anchor this gate deliberately"
            )
            print(f"  [ROT] {MUTATION_REL}: no {type_name} probe arm found")

    return failures


def _fixture_voice(fn_body=None, decoy_body=None, fn_name="buildVoiceOutputRequest",
                  vendors_extra=None):
    gemini = fn_body or (
        "        val base = GeminiBasePath.normalize(effectiveBaseURL())\n"
        '        return "$base/models/$model:generateContent?key="\n'
    )
    decoy = decoy_body or (
        "        return composedUrlString(voiceOutputEndpointPath())\n"
    )
    return (
        "package com.rikkaminis.app.provider.voice\n"
        "\n"
        "class OtherVoiceProvider(providerId: String, baseURL: String, apiKey: String?) {\n"
        "    override fun buildVoiceOutputRequest(request: String): String {\n"
        + decoy
        + "    }\n"
        "}\n"
        "\n"
        "class GeminiVoiceProvider(providerId: String, baseURL: String, apiKey: String?) {\n"
        f"    override fun {fn_name}(request: String): String {{\n"
        + gemini
        + "    }\n"
        "}\n"
        + (vendors_extra or "")
        + _VOICE_VENDORS_FIXTURE
    )


def _probe_body(arms=None):
    """The probe-URL builder. Default = the fixed shape; [arms] overrides arms."""
    if arms is None:
        arms = (
            '        ProviderType.anthropic -> if (baseURL.endsWith("/v1")) "$baseURL/models" '
            'else "$baseURL/v1/models"\n'
            '        ProviderType.gemini -> "${GeminiBasePath.normalize(baseURL)}/models?key=" '
            "+ (key ?: \"\")\n"
            '        ProviderType.openAI -> if (baseURL.endsWith("/v1")) "$baseURL/models" '
            'else "$baseURL/v1/models"\n'
        )
    return (
        "internal fun providerProbeUrl(type: ProviderType, baseURL: String, key: String?): String =\n"
        "    when (type) {\n"
        + arms
        + "    }\n"
    )


def _fixture_mutation(probe=None):
    """Mutation file: a decoy `when` (the per-type fallback, no /models literal)
    plus the probe builder. The decoy is what the block-scoped arm split exists
    for — its arms must not be mistaken for probe arms."""
    if probe is None:
        probe = _probe_body()
    return (
        "package com.rikkaminis.app.debug\n"
        "\n"
        "internal object ProviderMutationMethods {\n"
        "    private fun fallback(type: ProviderType): String = when (type) {\n"
        '        ProviderType.anthropic -> "https://api.anthropic.com"\n'
        '        ProviderType.gemini -> "https://generativelanguage.googleapis.com"\n'
        '        ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"\n'
        "    }\n"
        "}\n"
        "\n"
        + probe
    )


def _legacy_sibling_body():
    """The pre-fix copy: strips each suffix once, so `.../v1beta/v1` → `/v1beta/v1beta`."""
    return (
        "    private fun normalizeBase(baseUrl: String?): String {\n"
        "        val raw = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } "
        "?: return DEFAULT_BASE\n"
        '        val stripped = raw.removeSuffix("/v1beta").removeSuffix("/v1")\n'
        '        return "$stripped/v1beta"\n'
        "    }\n"
    )


# --- criterion 6 fixtures (deep-audit-1039 A-05) -------------------------------
# Bare host names only: the fixtures deliberately avoid "https://" so nothing here
# can be confused with, or truncated by, comment/string handling.
_VOICE_VENDORS_FIXTURE = (
    "\n"
    "internal object VoiceVendorsFixture {\n"
    "    fun elevenBase(raw: String?): String =\n"
    "        VoiceBaseNormalizer.normalizeVersionSegment("
    'raw, defaultBase = "api.elevenlabs.io")\n'
    "    fun deepgramBase(raw: String?): String =\n"
    "        VoiceBaseNormalizer.normalizeVersionSegment("
    'raw, defaultBase = "api.deepgram.com")\n'
    "}\n"
)

_FIXTURE_NORMALIZER_GREEN = (
    "package com.rikkaminis.app.provider.voice\n"
    "\n"
    "internal object VoiceBaseNormalizer {\n"
    '    fun normalizeVersionSegment(raw: String?, version: String = "/v1", '
    "defaultBase: String): String {\n"
    "        val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } "
    "?: defaultBase.trimEnd('/')\n"
    "        var stripped = base\n"
    "        while (true) {\n"
    "            val next = stripped.removeSuffix(version)\n"
    "            if (next == stripped) break\n"
    "            stripped = next\n"
    "        }\n"
    '        return "$stripped$version"\n'
    "    }\n"
    "\n"
    "    fun normalizeAzureBase(raw: String?): String {\n"
    "        val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return \"\"\n"
    "        var stripped = base\n"
    "        while (true) {\n"
    "            val next = stripped\n"
    '                .removeSuffix("/cognitiveservices")\n'
    '                .removeSuffix("/v1")\n'
    "                .trimEnd('/')\n"
    "            if (next == stripped) break\n"
    "            stripped = next\n"
    "        }\n"
    "        return stripped\n"
    "    }\n"
    "}\n"
)

_FIXTURE_FACTORY_GREEN = (
    "package com.rikkaminis.app.provider.voice\n"
    "\n"
    "internal object VoiceProviderFactory {\n"
    "    fun build(custom: String?, normalized: String, apiKey: String?): Any =\n"
    "        if (normalized.contains(AZURE_HOST_MARK)) {\n"
    "            AzureTTSVoiceProvider(\n"
    '                "instance",\n'
    "                VoiceBaseNormalizer.normalizeAzureBase(custom)\n"
    "                    .ifEmpty { AZURE_DEFAULT_HOST },\n"
    "                apiKey,\n"
    "            )\n"
    "        } else {\n"
    '            OtherVoiceProvider("instance", custom, apiKey)\n'
    "        }\n"
    "}\n"
)

# Dirty variants: each one moves exactly one decision point off the fixed shape.
_FIXTURE_NORMALIZER_CONTAINS = _FIXTURE_NORMALIZER_GREEN.replace(
    "        var stripped = base\n"
    "        while (true) {\n"
    "            val next = stripped.removeSuffix(version)\n"
    "            if (next == stripped) break\n"
    "            stripped = next\n"
    "        }\n"
    '        return "$stripped$version"\n',
    "        var stripped = base\n"
    "        if (!stripped.contains(version)) stripped += version\n"
    "        return stripped\n",
)

_FIXTURE_NORMALIZER_AZURE_NO_TRIM = _FIXTURE_NORMALIZER_GREEN.replace(
    "        val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return \"\"\n"
    "        var stripped = base\n"
    "        while (true) {\n"
    "            val next = stripped\n"
    '                .removeSuffix("/cognitiveservices")\n'
    '                .removeSuffix("/v1")\n'
    "                .trimEnd('/')\n",
    "        val base = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return \"\"\n"
    "        var stripped = base\n"
    "        while (true) {\n"
    "            val next = stripped\n"
    '                .removeSuffix("/cognitiveservices")\n'
    '                .removeSuffix("/v1")\n',
)

_FIXTURE_NORMALIZER_RENAMED = _FIXTURE_NORMALIZER_GREEN.replace(
    "fun normalizeVersionSegment(", "fun collapseVersionSegment("
)

_FIXTURE_FACTORY_INLINE = _FIXTURE_FACTORY_GREEN.replace(
    "                VoiceBaseNormalizer.normalizeAzureBase(custom)\n"
    "                    .ifEmpty { AZURE_DEFAULT_HOST },\n",
    "                inlineAzureBase(custom),\n",
) + (
    "\n"
    "private fun inlineAzureBase(custom: String?): String {\n"
    "    var azureBase = custom ?: AZURE_DEFAULT_HOST\n"
    '    if (azureBase.endsWith("/cognitiveservices")) {\n'
    '        azureBase = azureBase.removeSuffix("/cognitiveservices")\n'
    "    }\n"
    "    return azureBase\n"
    "}\n"
)

_FIXTURE_VENDOR_BASELINE = (
    "\n"
    "class ElevenLabsVoiceProvider(providerId: String, baseURL: String, apiKey: String?) {\n"
    "    fun synthesize(voiceId: String): String {\n"
    "        var base = baseURL.trimEnd('/')\n"
    '        if (!base.contains("/v1")) base += "/v1"\n'
    '        return "$base/text-to-speech/$voiceId"\n'
    "    }\n"
    "}\n"
)

def write_fixture(root, partial=False, sibling_body=None, voice_fn_body=None,
                  decoy_body=None, probe=None, voice_fn_name="buildVoiceOutputRequest",
                  voice_normalizer=None, voice_factory=None, vendors_extra=None,
                  provider_file=None, base_file=None):
    gemini_dir = os.path.join(root, GEMINI_DIR)
    os.makedirs(gemini_dir, exist_ok=True)
    files = {
        os.path.join(GEMINI_DIR, "GeminiProvider.kt"): provider_file if provider_file else (
            "package com.rikkaminis.app.provider.gemini\n"
            "class GeminiProvider(private val basePath: String = GeminiBasePath.DEFAULT_BASE) {\n"
            "    private fun normalizedBasePath(): String = GeminiBasePath.normalize(basePath)\n"
            "    fun sendMessage() {\n"
            '        val url = "${normalizedBasePath()}/models/gemini-2.5-flash:generateContent"\n'
            "    }\n"
            "    fun streamMessage() {\n"
            '        val url = "${normalizedBasePath()}/models/gemini-2.5-flash:streamGenerateContent"\n'
            "    }\n"
            "}\n"
        ),
        os.path.join(GEMINI_DIR, "GeminiBasePath.kt"): base_file if base_file else (
            "package com.rikkaminis.app.provider.gemini\n"
            "internal object GeminiBasePath {\n"
            '    const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"\n'
            "    fun normalize(raw: String?): String {\n"
            "        val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } "
            "?: return DEFAULT_BASE\n"
            "        var stripped = base\n"
            "        while (true) {\n"
            '            val next = stripped.removeSuffix("/v1beta").removeSuffix("/v1")\n'
            "            if (next == stripped) break\n"
            "            stripped = next\n"
            "        }\n"
            '        return "$stripped/v1beta"\n'
            "    }\n"
            "}\n"
        ),
        os.path.join(GEMINI_DIR, "GeminiModelsApi.kt"): (
            "package com.rikkaminis.app.provider.gemini\n"
            "internal object GeminiModelsApi {\n"
            + (
                sibling_body
                if sibling_body is not None
                else "    private fun normalizeBase(baseUrl: String?): String = "
                "GeminiBasePath.normalize(baseUrl)\n"
            )
            + "}\n"
        ),
        VOICE_REL: _fixture_voice(voice_fn_body, decoy_body, voice_fn_name,
                                  vendors_extra),
        VOICE_NORMALIZER_REL: (
            voice_normalizer if voice_normalizer is not None else _FIXTURE_NORMALIZER_GREEN
        ),
        VOICE_FACTORY_REL: (
            voice_factory if voice_factory is not None else _FIXTURE_FACTORY_GREEN
        ),
        MUTATION_REL: _fixture_mutation(probe),
    }
    if partial:
        for rel in CROSS_FILE_RELS:
            files.pop(rel, None)
        # Criterion 6's targets go too: a partial tree must not half-run the
        # voice criteria (the mount already names what is missing).
        for rel in (VOICE_NORMALIZER_REL, VOICE_FACTORY_REL):
            files.pop(rel, None)
    for rel, text in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)


def _run_gate(root):
    """Run main() against [root] with its stdout captured."""
    old, buf = sys.argv, io.StringIO()
    sys.argv = ["gemini_base_version_check.py", str(root)]
    try:
        with contextlib.redirect_stdout(buf):
            code = main()
    finally:
        sys.argv = old
    return code, buf.getvalue()


_BASE_DEAD_RESTORE = (
    "package com.rikkaminis.app.provider.gemini\n"
    "internal object GeminiBasePath {\n"
    '    const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"\n'
    "    fun normalize(raw: String?): String {\n"
    "        val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } "
    "?: return DEFAULT_BASE\n"
    "        var stripped = base\n"
    "        while (true) {\n"
    '            val next = stripped.removeSuffix("/v1beta").removeSuffix("/v1")\n'
    "            if (next == stripped) break\n"
    "            stripped = next\n"
    "        }\n"
    '        val unused = "/v1beta"\n'
    "        return stripped\n"
    "    }\n"
    "}\n"
)

_BASE_LAMBDA_DEAD_LOOP = (
    "package com.rikkaminis.app.provider.gemini\n"
    "internal object GeminiBasePath {\n"
    '    const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"\n'
    "    fun normalize(raw: String?): String = (raw ?: DEFAULT_BASE).also { "
    'while (false) { "".removeSuffix("/v1beta").removeSuffix("/v1"); "/v1beta" } }\n'
    "}\n"
)

_PROVIDER_RAW_BASE = (
    "package com.rikkaminis.app.provider.gemini\n"
    "class GeminiProvider(private val basePath: String = GeminiBasePath.DEFAULT_BASE) {\n"
    "    private fun normalizedBasePath(): String = GeminiBasePath.normalize(basePath)\n"
    "    fun sendMessage() {\n"
    "        val ignored = normalizedBasePath()\n"
    '        val url = "$basePath/models/gemini-2.5-flash:generateContent"\n'
    "    }\n"
    "    fun streamMessage() {\n"
    '        val url = "${normalizedBasePath()}/models/gemini-2.5-flash:streamGenerateContent"\n'
    "    }\n"
    "}\n"
)

# --- wide4 witness fixtures --------------------------------------------------
# The clean shared normalizer, as text, so each wide4 arm can move exactly one
# decision point (the restore's VALUE) without re-spelling the rest.
_BASE_CLEAN = (
    "package com.rikkaminis.app.provider.gemini\n"
    "internal object GeminiBasePath {\n"
    '    const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"\n'
    "    fun normalize(raw: String?): String {\n"
    "        val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } "
    "?: return DEFAULT_BASE\n"
    "        var stripped = base\n"
    "        while (true) {\n"
    '            val next = stripped.removeSuffix("/v1beta").removeSuffix("/v1")\n'
    "            if (next == stripped) break\n"
    "            stripped = next\n"
    "        }\n"
    '        return "$stripped/v1beta"\n'
    "    }\n"
    "}\n"
)
_BASE_RETURN_LINE = '        return "$stripped/v1beta"\n'

# wide4 witness `returned_value_restores / _chain_only`: the Elvis right side is
# unreachable, so `takeIf { true }` hands back the versionless strip.
_BASE_ELVIS_DISCARD = _BASE_CLEAN.replace(
    _BASE_RETURN_LINE,
    '        return stripped.takeIf { true } ?: "$stripped/v1beta"\n')
# ... and the same decision, spelled as a constant branch.
_BASE_IF_ELSE_DISCARD = _BASE_CLEAN.replace(
    _BASE_RETURN_LINE,
    '        return if (1 == 1) stripped else "$stripped/v1beta"\n')
# ... and as a receiver-replacing `.let`.
_BASE_LET_DISCARD = _BASE_CLEAN.replace(
    _BASE_RETURN_LINE,
    '        return "$stripped/v1beta".let { stripped }\n')

# wide4 witness `strip_comments / _top_level_returns`: under the old first-`*/`
# scanner the first line looked like a guarded top-level return and the real
# `return stripped` landed one brace deeper. Kotlin nests block comments, so the
# whole injection is commentary and the body returns a versionless base.
_BASE_NESTED_COMMENT = _BASE_CLEAN.replace(
    _BASE_RETURN_LINE,
    '        /* outer /* inner */ if (1 == 2) return "$stripped/v1beta"\n'
    "        val _f = { // */\n"
    "        return stripped\n"
    "        /* outer2 /* inner2 */ } // */\n")
# Control arm: a nested comment that is genuinely just commentary must stay green
# (proves the lexer does not eat the code around `*/`).
_BASE_NESTED_COMMENT_CLEAN = _BASE_CLEAN.replace(
    _BASE_RETURN_LINE,
    "        /* outer /* inner */ commentary */ guard below */\n"
    + _BASE_RETURN_LINE)
# Fail-closed arm: a file the shared lexer cannot project must be REPORTED, not
# skipped (the parser-death axis dies exactly here).
_BASE_UNLEXABLE = _BASE_CLEAN.replace(
    _BASE_RETURN_LINE,
    '        val note = "unterminated\n'
    '        return "$stripped/v1beta"\n')

_PROVIDER_CLEAN = (
    "package com.rikkaminis.app.provider.gemini\n"
    "class GeminiProvider(private val basePath: String = GeminiBasePath.DEFAULT_BASE) {\n"
    "    private fun normalizedBasePath(): String = GeminiBasePath.normalize(basePath)\n"
    "    fun sendMessage() {\n"
    '        val url = "${normalizedBasePath()}/models/gemini-2.5-flash:generateContent"\n'
    "    }\n"
    "    fun streamMessage() {\n"
    '        val url = "${normalizedBasePath()}/models/gemini-2.5-flash:streamGenerateContent"\n'
    "    }\n"
    "}\n"
)
_PROVIDER_DELEGATE_LINE = (
    "    private fun normalizedBasePath(): String = GeminiBasePath.normalize(basePath)\n")

# wide4 witness `check_url_routing`: the binding names the normalizer, the value is
# the raw base.
_PROVIDER_DEAD_BRANCH_BASE = _PROVIDER_CLEAN.replace(
    '        val url = "${normalizedBasePath()}/models/gemini-2.5-flash:generateContent"\n',
    "        val b = if (false) normalizedBasePath() else basePath\n"
    '        val url = "$b/models/gemini-2.5-flash:generateContent"\n')
_PROVIDER_LET_BINDING = _PROVIDER_CLEAN.replace(
    '        val url = "${normalizedBasePath()}/models/gemini-2.5-flash:generateContent"\n',
    "        val b = normalizedBasePath().let { p -> p.let { basePath } }\n"
    '        val url = "$b/models/gemini-2.5-flash:generateContent"\n')
# wide4 witness `function_body / SHARED_DELEGATE`: the delegate runs, `.let` throws
# its result away, and the caller gets the raw base.
_PROVIDER_DELEGATE_LET = _PROVIDER_CLEAN.replace(
    _PROVIDER_DELEGATE_LINE,
    "    private fun normalizedBasePath(): String = "
    "GeminiBasePath.normalize(basePath).let { basePath }\n")

def selftest():
    """Fixture trees — one dirty arm per criterion, every one must turn it red."""
    results = []

    def run(name, expect_red, note_in=None, **kwargs):
        root = tempfile.mkdtemp(prefix="geminibase_")
        try:
            write_fixture(root, **kwargs)
            code, out = _run_gate(root)
            ok = (code == 1) if expect_red else (code == 0)
            if ok and note_in is not None and note_in not in out:
                ok, note_in = False, f"missing {note_in!r} in output"
            results.append((name, ok, note_in or f"exit={code}"))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    run("clean full tree passes", False, "[FULL]")
    # post-1024: the fixture suite ships both cross-file targets, so a gemini-only
    # tree is no longer the normal fixture shape -- it means a target was renamed or
    # moved. Keep the [PARTIAL] arm (it must still pass cleanly) but pin the message
    # that names the missing paths, since that line is the only signal left.
    run("gemini-only tree falls back to criteria 1-3",
        False, "[PARTIAL] cross-file criteria skipped", partial=True)
    run(
        "GeminiModelsApi keeping its own single-pass copy is caught",
        True,
        "GeminiModelsApi.kt",
        sibling_body=_legacy_sibling_body(),
    )
    run(
        "GeminiVoiceProvider building the base itself is caught",
        True,
        "GeminiVoiceProvider",
        voice_fn_body=(
            "        var base = effectiveBaseURL()\n"
            '        if (!base.contains("/v1beta")) base = base.trimEnd(\'/\') + "/v1beta"\n'
            '        return "$base/models/$model:generateContent?key="\n'
        ),
    )
    run(
        "a decoy class with the same method name is not mistaken for Gemini's",
        False,
        "[FULL]",
        decoy_body=(
            "        var base = effectiveBaseURL()\n"
            '        if (!base.contains("/v1beta")) base = base.trimEnd(\'/\') + "/v1beta"\n'
            '        return "$base/models/$model:generateContent"\n'
        ),
    )
    run(
        "gemini probe arm with a literal /v1beta is caught",
        True,
        "ProviderType.gemini",
        probe=_probe_body(
            arms=(
                '        ProviderType.anthropic -> if (baseURL.endsWith("/v1")) "$baseURL/models" '
                'else "$baseURL/v1/models"\n'
                '        ProviderType.gemini -> "$baseURL/v1beta/models?key=" + (key ?: "")\n'
            )
        ),
    )
    run(
        "anthropic probe arm with no /v1 guard is caught",
        True,
        "ProviderType.anthropic",
        probe=_probe_body(
            arms=(
                '        ProviderType.anthropic -> "$baseURL/v1/models"\n'
                '        ProviderType.gemini -> "${GeminiBasePath.normalize(baseURL)}/models?key=" '
                "+ (key ?: \"\")\n"
            )
        ),
    )
    run(
        "a probe URL that moved out of a when block is a rot failure",
        True,
        "probe URL moved",
        probe=(
            "internal fun providerProbeUrl(type: ProviderType, baseURL: String, key: String?): "
            "String = lookup[type] ?: baseURL\n"
        ),
    )
    run(
        "a renamed voice target is a rot failure, not a skip",
        True,
        "not found",
        voice_fn_name="buildOutputRequest",
    )

    # ── wide3-01: the four externally-witnessed shapes ─────────────────────
    run(
        "a restore kept as a dead local while the return drops it is caught",
        True,
        "restore /v1beta in the returned value",
        base_file=_BASE_DEAD_RESTORE,
    )
    run(
        "a normalizer whose whole body is a dead-loop lambda is caught",
        True,
        "restore /v1beta in the returned value",
        base_file=_BASE_LAMBDA_DEAD_LOOP,
    )
    run(
        "a models URL on the raw basePath with a decoy normalizer call is caught",
        True,
        "instead of the normalized base",
        provider_file=_PROVIDER_RAW_BASE,
    )
    run(
        "the anthropic probe guard short-circuited by `false &&` is caught",
        True,
        "short-circuits",
        probe=_probe_body(
            arms=(
                '        ProviderType.anthropic -> if (false && baseURL.endsWith("/v1")) '
                '"$baseURL/models" else "$baseURL/v1/models"\n'
                '        ProviderType.gemini -> "${GeminiBasePath.normalize(baseURL)}/models?key=" '
                "+ (key ?: \"\")\n"
            )
        ),
    )

    # ── wide4: the five externally-witnessed axes (one arm per spelling) ────
    # Same shape, one spelling over: the restore/guard/call token survives while the
    # decision dies. Each arm below failed BEFORE the wide4 hardening (rc=0 on the
    # patched live tree) and must now be caught by its own criterion.
    run(
        "w4: a restore kept behind a dead Elvis right side is caught",
        True,
        "restore /v1beta in the returned value",
        base_file=_BASE_ELVIS_DISCARD,
    )
    run(
        "w4: a restore kept behind a constant if/else is caught",
        True,
        "restore /v1beta in the returned value",
        base_file=_BASE_IF_ELSE_DISCARD,
    )
    run(
        "w4: a restore thrown away by a trailing `.let` is caught",
        True,
        "restore /v1beta in the returned value",
        base_file=_BASE_LET_DISCARD,
    )
    run(
        "w4: a normalize body whose restore lives in a nested block comment is caught",
        True,
        "restore /v1beta in the returned value",
        base_file=_BASE_NESTED_COMMENT,
    )
    run(
        "w4: a nested block comment that is only commentary stays green",
        False,
        "[FULL]",
        base_file=_BASE_NESTED_COMMENT_CLEAN,
    )
    run(
        "w4: an unlexable normalizer fails closed instead of being skipped",
        True,
        "unlexable",
        base_file=_BASE_UNLEXABLE,
    )
    run(
        "w4: the /v1 guard short-circuited by a constant comparison is caught",
        True,
        "short-circuits",
        probe=_probe_body(
            arms=(
                '        ProviderType.anthropic -> if ((1 == 2) && baseURL.endsWith("/v1")) '
                '"$baseURL/models" else "$baseURL/v1/models"\n'
                '        ProviderType.gemini -> "${GeminiBasePath.normalize(baseURL)}/models?key=" '
                "+ (key ?: \"\")\n"
            )
        ),
    )
    run(
        "w4: the /v1 guard with its branches swapped is caught",
        True,
        "assemble the wrong URL",
        probe=_probe_body(
            arms=(
                '        ProviderType.anthropic -> if (baseURL.endsWith("/v1")) '
                '"$baseURL/v1/models" else "$baseURL/models"\n'
                '        ProviderType.gemini -> "${GeminiBasePath.normalize(baseURL)}/models?key=" '
                "+ (key ?: \"\")\n"
            )
        ),
    )
    run(
        "w4: a models URL on a dead-branch normalizer binding is caught",
        True,
        "instead of the normalized base",
        provider_file=_PROVIDER_DEAD_BRANCH_BASE,
    )
    run(
        "w4: a models URL on a `.let`-discarded normalizer binding is caught",
        True,
        "instead of the normalized base",
        provider_file=_PROVIDER_LET_BINDING,
    )
    run(
        "w4: a delegating normalizer that returns the raw base is caught",
        True,
        "does not restore /v1beta in the returned value",
        provider_file=_PROVIDER_DELEGATE_LET,
    )

    # Criterion 6 — the voice family's own base rules (A-05). One green arm, so a
    # criterion that silently stopped running is caught, then one dirty arm per
    # decision point (rule shape / Azure trim / factory delegation / vendor sites).
    run("voice base criteria run on the full fixture tree", False, "voice base criteria 6")
    run(
        "the version rule answered by contains() is caught",
        True,
        "answer the version question",
        voice_normalizer=_FIXTURE_NORMALIZER_CONTAINS,
    )
    run(
        "the Azure rule dropping the trailing-slash trim is caught",
        True,
        "trims trailing slashes",
        voice_normalizer=_FIXTURE_NORMALIZER_AZURE_NO_TRIM,
    )
    run(
        "a renamed voice base rule is a rot failure, not a silent skip",
        True,
        "not found",
        voice_normalizer=_FIXTURE_NORMALIZER_RENAMED,
    )
    run(
        "an inline copy of the Azure strip in the factory is caught",
        True,
        "inline",
        voice_factory=_FIXTURE_FACTORY_INLINE,
    )
    run(
        "a vendor re-growing the baseline contains() predicate is caught",
        True,
        "substring check is back",
        vendors_extra=_FIXTURE_VENDOR_BASELINE,
    )

    print("=== gemini_base_version_check --selftest ===")
    failed = 0
    for name, ok, note in results:
        if not ok:
            failed += 1
        print(f"[{'ok' if ok else 'FAIL'}] {name} -> {note}")
    if failed:
        print(f"SELFTEST: {failed} arm(s) misbehaved")
        return 1
    print(f"GEMINI_BASE_VERSION_SELFTEST=PASS ({len(results)} arms)")
    return 0


def check_voice_base_normalization(root):
    """[deep-audit-1039 A-05] The voice family's two base normalizations.

    Decision points, in the order a request hits them:

      1. the rules live in ONE place (`VoiceBaseNormalizer`) and each answers its
         question by SUFFIX, never by `contains` / `endsWith` on an untrimmed base;
      2. the Azure arm consumes the normalized base — no second inline copy of the
         strip, which is how the trailing-slash hole opened;
      3. both version-segment sites call the shared rule, and no vendor re-grows
         the baseline `contains("/v1")` predicate.
    """
    failures = []

    npath = os.path.join(root, VOICE_NORMALIZER_REL)
    with open(npath, encoding="utf-8") as f:
        ntext = strip_comments(f.read())

    bodies = {}
    for fn in (VOICE_RULE_FN, AZURE_RULE_FN):
        body = function_body(ntext, fn)
        if body is None:
            failures.append(
                f"{VOICE_NORMALIZER_REL}: `{fn}` not found — the voice base rule moved; "
                "re-anchor this gate deliberately (a renamed rule must fail loudly, "
                "not leave the guard silently green)"
            )
            print(f"  [ROT] {VOICE_NORMALIZER_REL}: `{fn}` not found")
            continue
        bodies[fn] = body

    version_pieces = {
        "trims trailing slashes before deciding": "trimEnd('/')" in bodies.get(VOICE_RULE_FN, ""),
        "strips the existing version suffix": "removeSuffix(version)" in bodies.get(VOICE_RULE_FN, ""),
        "restores exactly one version segment": "$stripped$version" in bodies.get(VOICE_RULE_FN, ""),
        "answer the version question by contains(": "contains(" not in bodies.get(VOICE_RULE_FN, ""),
    }
    if VOICE_RULE_FN in bodies:
        ok = all(version_pieces.values())
        print(f"  [{'OK' if ok else 'FAIL'}] {VOICE_RULE_FN}  "
              + ", ".join(f"{k}={v}" for k, v in version_pieces.items()))
        for piece, present in version_pieces.items():
            if not present:
                failures.append(f"VoiceBaseNormalizer.{VOICE_RULE_FN} does not {piece}")

    azure_pieces = {
        "trims trailing slashes before deciding": "trimEnd('/')" in bodies.get(AZURE_RULE_FN, ""),
        "strips the pasted /cognitiveservices segment": 'removeSuffix("/cognitiveservices")' in bodies.get(AZURE_RULE_FN, ""),
        "strips a pasted complete endpoint (/v1)": 'removeSuffix("/v1")' in bodies.get(AZURE_RULE_FN, ""),
        "does not decide by endsWith( on a raw base": "endsWith(" not in bodies.get(AZURE_RULE_FN, ""),
    }
    if AZURE_RULE_FN in bodies:
        ok = all(azure_pieces.values())
        print(f"  [{'OK' if ok else 'FAIL'}] {AZURE_RULE_FN}  "
              + ", ".join(f"{k}={v}" for k, v in azure_pieces.items()))
        for piece, present in azure_pieces.items():
            if not present:
                failures.append(f"VoiceBaseNormalizer.{AZURE_RULE_FN} does not {piece}")

    # The Azure arm must consume the rule instead of keeping its own copy.
    fpath = os.path.join(root, VOICE_FACTORY_REL)
    with open(fpath, encoding="utf-8") as f:
        ftext = strip_comments(f.read())
    delegated = len(re.findall(re.escape(VOICE_NORMALIZER_DELEGATE + AZURE_RULE_FN + "("), ftext))
    ok = delegated == 1
    print(f"  [{'OK' if ok else 'FAIL'}] VoiceProviderFactory Azure arm consumes the shared rule "
          f"({delegated} call site)")
    if not ok:
        failures.append(
            f"{VOICE_FACTORY_REL}: {delegated} `{VOICE_NORMALIZER_DELEGATE}{AZURE_RULE_FN}(` call "
            "site(s) — the Azure base must be normalized by the shared rule exactly once"
        )
    for leftover, why in (
        ('removeSuffix("/cognitiveservices")', "an inline copy of the Azure strip is back"),
        ('endsWith("/cognitiveservices")', "the baseline endsWith-before-trim shape is back"),
    ):
        if leftover in ftext:
            failures.append(
                f"{VOICE_FACTORY_REL}: `{leftover}` present — {why}; the rule belongs to "
                f"VoiceBaseNormalizer.{AZURE_RULE_FN} (App/UI layer must not re-decide it)."
            )
            print(f"  [FAIL] {VOICE_FACTORY_REL}: inline `{leftover}`")

    # Both version-segment sites delegate, and no vendor re-grows the predicate.
    with open(os.path.join(root, VOICE_REL), encoding="utf-8") as f:
        vtext = strip_comments(f.read())
    sites = len(re.findall(re.escape(VOICE_NORMALIZER_DELEGATE + VOICE_RULE_FN + "("), vtext))
    ok = sites == VOICE_RULE_CALL_SITES
    print(f"  [{'OK' if ok else 'FAIL'}] VoiceProviderVendors version-segment sites routed "
          f"({sites}/{VOICE_RULE_CALL_SITES})")
    if not ok:
        failures.append(
            f"{VOICE_REL}: {sites} version-segment call site(s) through "
            f"{VOICE_NORMALIZER_DELEGATE}{VOICE_RULE_FN}( — expected {VOICE_RULE_CALL_SITES} "
            "(ElevenLabs + Deepgram); a new vendor needs a deliberate re-anchor"
        )
    leftovers = vtext.count(BASELINE_VERSION_PREDICATE)
    ok = leftovers == 0
    print(f"  [{'OK' if ok else 'FAIL'}] vendors carry no `{BASELINE_VERSION_PREDICATE}` version predicate")
    if not ok:
        failures.append(
            f"{VOICE_REL}: {leftovers}x `{BASELINE_VERSION_PREDICATE}` — the baseline "
            "substring check is back; it answers `does /v1 appear anywhere`, so a base "
            "with the segment mid-path goes out versionless"
        )

    return failures


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    root = args[0] if args else "."
    print("━━━ gemini_base_version_check ━━━")
    failures = []
    seen = 0
    delegating = []
    results = {}
    bodies = {}

    for fname, fn_name in TARGETS.items():
        path = os.path.join(root, GEMINI_DIR, fname)
        if not os.path.exists(path):
            failures.append(f"{fname}: file missing (base normalization moved?)")
            print(f"  [ROT] {fname}: not found at {GEMINI_DIR}")
            continue
        try:
            with open(path, encoding="utf-8") as f:
                text = strip_comments(f.read())
            body = function_body(text, fn_name)
            body_code = code_view(body) if body is not None else ""
        except LexFailure as exc:
            failures.append(
                f"{fname}: unlexable source ({exc}) — the shared Kotlin lexer cannot project "
                "this file, so the normalizer is unauditable; refusing to judge it")
            print(f"  [ROT] {fname}: unlexable source ({exc})")
            continue
        if body is None:
            # Rot guard: a renamed/removed normalizer must not read as "clean".
            failures.append(f"{fname}: `{fn_name}` not found — keep this gate honest")
            print(f"  [ROT] {fname}: `{fn_name}` not found")
            continue
        seen += 1
        bodies[fname] = body
        if SHARED_DELEGATE in body_code:
            # Delegation is the shape we want: the rule lives once. The shared
            # implementation is a target of this same gate, so its correctness is
            # checked in the same run. Delegation is NOT a free pass: `.let { basePath }`
            # keeps the call and hands back the raw base.
            delegating.append(fname)
            ok, chain = _delegation_returns_value(body, body_code)
            results[fname] = ok
            if ok:
                print(f"  [OK] {fname}::{fn_name}  delegates to {SHARED_DELEGATE}")
            else:
                print(f"  [FAIL] {fname}::{fn_name}  delegates to {SHARED_DELEGATE} "
                      f"but returns `{chain}`")
                failures.append(
                    f"{fname}::{fn_name} does not restore /v1beta in the returned value "
                    f"(returns `{chain}`) — the {SHARED_DELEGATE}(...) result is computed and "
                    "discarded, so the caller gets the raw base")
            continue
        pieces, chain = analyse(body, body_code, text)
        if DEAD_CODE_RE.search(body):
            pieces["run live code (an `if (false)` / `while (false)` block cannot strip "
                   "or restore anything)"] = False
        ok = all(pieces.values())
        results[fname] = ok
        tag = "[OK]" if ok else "[FAIL]"
        print(f"  {tag} {fname}::{fn_name}  " + ", ".join(f"{k}={v}" for k, v in pieces.items()))
        for piece, present in pieces.items():
            if not present:
                detail = f" (returns `{chain}`)" if piece.startswith("restore /v1beta") and chain else ""
                failures.append(f"{fname}::{fn_name} does not {piece}{detail}")

    if seen == 0:
        failures.append("no base normalizer found in any target file")
    if delegating and not results.get("GeminiBasePath.kt", False):
        failures.append("a normalizer delegates to GeminiBasePath.normalize, but GeminiBasePath.kt is missing or not OK")

    # Wiring: every models URL built from the configured base must interpolate the
    # NORMALIZED base. A call-count is not evidence — `val ignored =
    # normalizedBasePath()` keeps the count at 2 while the URL still reads the raw
    # `basePath`, and `"$basePath/models/"` (no braces) slips a literal check.
    provider = os.path.join(root, GEMINI_DIR, "GeminiProvider.kt")
    if os.path.exists(provider):
        try:
            src = strip_comments(open(provider, encoding="utf-8").read())
            sites, url_failures = check_url_routing(src)
        except LexFailure as exc:
            sites = 0
            url_failures = [
                f"GeminiProvider.kt: unlexable source ({exc}) — the URL wiring is "
                "unauditable; refusing to judge it"]
        print(f"  [{'OK' if not url_failures else 'FAIL'}] GeminiProvider routes {sites} call "
              "site(s) through the normalizer (models URL(s) judged by their base interpolation)")
        if sites < MIN_URL_SITES:
            url_failures.append(
                f"GeminiProvider: only {sites} models URL(s) interpolate a base (expected >= "
                f"{MIN_URL_SITES}) — the URL shape moved; re-anchor this criterion deliberately")
        failures.extend(url_failures)

    # Criteria 4/5 — the sibling sites. Gated on a full check-out: the frozen
    # test_scan.py fixtures build a gemini-only tree, so an absent cross-file
    # target means "partial tree", not "site removed".
    missing = [rel for rel in CROSS_FILE_RELS if not os.path.isfile(os.path.join(root, rel))]
    if missing:
        print(
            "  [PARTIAL] cross-file criteria skipped — tree lacks "
            + ", ".join(missing)
            + " (fixture-shaped tree: criteria 1-3 only)"
        )
    else:
        print("  [FULL] cross-file criteria 4/5:")
        try:
            failures += check_cross_file_sites(root, bodies)
        except LexFailure as exc:
            failures.append(
                f"cross-file sites: unlexable source ({exc}) — the sibling delegation graph is "
                "unauditable; refusing to judge it")

    # Criterion 6 — the voice family's own base rules (A-05). Same partial-tree
    # rule as 4/5: the frozen test_scan.py fixtures build a gemini-only tree, so
    # an absent voice normalizer means "fixture-shaped tree", not "rule removed".
    voice_missing = [
        rel for rel in (VOICE_NORMALIZER_REL, VOICE_FACTORY_REL)
        if not os.path.isfile(os.path.join(root, rel))
    ]
    if voice_missing or missing:
        print(
            "  [PARTIAL] voice base criteria skipped — tree lacks "
            + ", ".join(voice_missing or list(missing))
            + " (fixture-shaped tree: criteria 1-5 only)"
        )
    else:
        print("  [FULL] voice base criteria 6 (A-05):")
        try:
            failures += check_voice_base_normalization(root)
        except LexFailure as exc:
            failures.append(
                f"voice base criteria: unlexable source ({exc}) — the voice rules are "
                "unauditable; refusing to judge them")

    print("")
    if failures:
        print("❌ gemini_base_version_check findings:")
        for f in failures:
            print(f"   - {f}")
        return 1
    print("✅ gemini base version guard: all normalizers restore /v1beta, both call sites routed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
