#!/usr/bin/env python3
"""[da1016-E] Gate: android-speak language channel + sanitizer wiring.

Mechanism the gate protects (deep-audit-1016c ISSUE-07, P1):

  A. **Language override**: the offload handler used to call
     `tts.setLanguage(tag)` for `--voice` and then `tts.speak(text)` — but
     `TextToSpeechManager.speak` unconditionally ran
     `autoDetectAndSetLanguage(text)` (a zh/en binary guess), so every
     explicit tag was silently overwritten, and the JSON body echoed the
     *requested* tag (`voiceTag ?: "auto"`) as if it had been chosen.
     After the fix the language is resolved in exactly one place:
     `speak()` consults `TtsLanguagePolicy.resolveTargetLocale(explicit,
     text)`; the handler only passes the tag through and reports
     `currentLanguageTag()` (the effective value).

  B. **Sanitizer wiring**: `VoiceTextSanitizer` (spoken-text cleanup:
     markdown out, URL→localized phrase) existed with tests but had ZERO
     production call sites. `speak()` must sanitize BEFORE the engine call
     and treat an empty result as "nothing to speak" (never fall back to
     reading the raw text aloud).

  C. **Streaming preservation**: `speakQueued()` (sentence fragments from
     the live transcription stream) has no explicit language to honor and
     must keep `autoDetectAndSetLanguage` — per-chunk sanitize would break
     cross-chunk link rewrites, so the streaming path stays raw (declared
     residual, not a violation).

A.06 / A.07 (deep-audit-1044) add two tables: the SpeechRecognizer error map
(10/11 must not read as the API-33 language pair, 13 must not fall through to
UNKNOWN) and the per-call TTS defaults (unconditional application, absent flag
→ 1.0).  G10 (deep-audit-1046 S-10) adds the Quick Test clip's cancellation
latch.

**Judgement shape (batch-6 hardening).**  Every criterion in this file used to
be an "is this token present" check, and every witness that broke it moved the
*effect* rather than the token: the sanitize call stayed but its result was
discarded, the policy call stayed but with `null` instead of the tag, the
default stayed but got multiplied by 0, the `when` kept its arms under a
constant subject, the guard stayed but behind `false &&`.  So the criteria are
now derived from the tree: provenance (`value_sources` + one-hop local binding),
control-flow reachability (`path_feasible` / `enclosing_conditions`), brace
depth for guard dominance, and the whole expression for default resolution.
Lexing/masking/brace matching all come from the shared modules
(`_kotlin_lexer`, `_kotlin_ast`, `_kotlin_valueflow`); a lexer failure is a
violation (fail-closed), never a fall back to raw text.

Run from the repo root:
`python3 scripts/scan/tts_voice_and_sanitizer_check.py`
exit 0 = PASS, exit 1 = FAIL (violations printed as path[:line]).

`--selftest` builds violating + compliant fixtures and asserts the gate's
own teeth (a gate that cannot fail is worse than no gate).
"""
import argparse
import os
import re
import shutil
import sys
import tempfile

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from _kotlin_lexer import KotlinLexError                        # noqa: E402
from _kotlin_ast import (                                        # noqa: E402
    const_value,
    enclosing_conditions,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
    when_branches,
)
from _kotlin_valueflow import (                                   # noqa: E402
    brace_depth,
    call_args,
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    literal_view,
    path_feasible,
    value_sources,
)

MAIN_ROOT = os.path.join("src", "android", "app", "src", "main", "java")
MANAGER_REL = os.path.join(
    MAIN_ROOT, "com", "rikkaminis", "app", "speech", "TextToSpeechManager.kt"
)
HANDLER_REL = os.path.join(
    MAIN_ROOT, "com", "rikkaminis", "app", "sandbox", "offload", "SpeakOffloadHandler.kt"
)
# [deep-audit-1044 A-06/A-07] The two tables this gate now also protects: the
# SpeechRecognizer error map and the per-call TTS defaults.
SYSTEM_SPEECH_REL = os.path.join(
    MAIN_ROOT, "com", "rikkaminis", "app", "speech", "SystemSpeechRecognitionEngine.kt"
)

# [dv26/G10] Quick Test clip synthesis (deep-audit-1046 S-10). The TTS card's
# `synthesizeTestClip` is the third engine user in this subsystem; it had the
# cancellation hole the other two don't (a late engine-init callback wrote the
# clip back after the coroutine had already been cancelled and the file deleted).
QUICK_TEST_REL = os.path.join(
    MAIN_ROOT, "com", "rikkaminis", "app", "ui", "components", "QuickTestSheet.kt"
)
CLIP_SIG = "fun synthesizeTestClip("
CLIP_FALLBACK_RE = re.compile(
    r"val\s+(\w+)\s*=\s*(?:java\.io\.)?File\(\s*\w+\.cacheDir"
)
# Statements that touch the engine. WRITE ones put bytes on disk; SETUP ones
# configure the engine and therefore open a window in which a cancellation can
# arrive before the write.
TOUCH_SETUP_RE = re.compile(r"\?\s*\.\s*language\s*=|\?\s*\.\s*setOn\w+\s*\(")
TOUCH_WRITE_RE = re.compile(
    r"\.\s*(?:synthesizeToFile|writeText|writeBytes|appendText|createNewFile|copyTo|renameTo)\s*\("
)
CANCEL_READ_RE = re.compile(r"([A-Za-z_]\w*)\s*(?:\?\.|\.)\s*(?:is[A-Z]\w*|get)\b")

# Explicit exemption marker for rule B (rare callers that must voice raw text).
SANITIZER_EXEMPT_RE = re.compile(r"tts-sanitizer-exempt:\s*\S.*")

# Provenance needles (rule A/B).
SANITIZE_CALL_RE = re.compile(r"\bVoiceTextSanitizer\s*\.\s*sanitize\s*\(")
RESOLVE_CALL_RE = re.compile(r"\bresolveTargetLocale\s*\(")
APPLY_CALL_RE = re.compile(r"\bapplyLanguage\s*\(")
ENGINE_SPEAK_RE = re.compile(r"\btts\s*\??\.\s*speak\s*\(")

# A.07: per-call tuning. Left side covers BOTH spellings the fix allows: the
# field write (`tts.speechRate = …`) and the named argument of the resolver
# (`rate = …`). `tuning.rate` on the right of an `=` is excluded by the
# `(?<![\w.`])` lookbehind.
TUNING_FIELDS = (("speechRate", "rate"), ("speechPitch", "pitch"), ("speechVolume", "volume"))
COERCION_RE = re.compile(r"\.\s*(?:toFloat|toDouble|toInt|toLong)\s*\(\s*\)")


def _site_re(field_name, flag):
    return re.compile(
        r"\btts\s*\??\.\s*" + field_name + r"\s*=(?!=)|(?<![\w.`])" + flag + r"\s*=(?!=)")


def read(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


# ── shared-primitive glue ──────────────────────────────────────────────────
def _views(src):
    """(structure, literal) projections; a lexer failure raises KotlinLexError."""
    return code_view(src), literal_view(src)


def _member(code, name):
    """(body_start, body_end, kind) of `fun <name>(...)`, or None."""
    got = function_body(code, name)
    if not got:
        return None
    start, end, kind = got
    return (start, end, kind) if start >= 0 and end >= start else None


def _params(code, name):
    """Declared parameter names — the provenance walk needs to know what is raw input."""
    m = re.search(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(", code)
    if not m:
        return []
    p_open = m.end() - 1
    close = match_bracket(code, p_open)
    if close < 0:
        return []
    out = []
    for a, b in split_top(code[p_open + 1:close], (",",)):
        piece = code[p_open + 1 + a:p_open + 1 + b]
        pm = re.match(
            r"\s*(?:(?:vararg|noinline|crossinline)\s+)?([A-Za-z_]\w*)\s*:", piece)
        if pm:
            out.append(pm.group(1))
    return out


def _local_rhs(code, name, span):
    """Initializer text of the LAST `val/var <name> = …` inside span, or None."""
    start, end, _kind = span
    best = None
    for m in re.finditer(
            r"\b(?:val|var)\s+" + re.escape(name) + r"\s*(?::[^=\n]+?)?\s*=(?!=)",
            code[start:end]):
        rhs_start = start + m.end()
        best = code[rhs_start:expr_end(code, rhs_start)].strip()
    return best


def _derives(code, span, expr, params, needle_re, hops=3):
    """Does the value of `expr` carry a call matching `needle_re`?

    Follows one identifier per hop through the member's local `val` bindings
    (`speakable` → `sanitize(...)`). A value that was computed and then thrown
    away by a trailing scope block has no path to the needle: `value_sources`
    resolves `sanitize(...).let { text }` to the block's tail (`text` = the raw
    parameter), so the walk stops there — exactly the "call present, effect
    absent" shape the witnesses used.
    """
    todo = [expr]
    seen = 0
    while todo and seen < 12:
        cur = todo.pop(0)
        seen += 1
        srcs = value_sources(cur) or [cur]
        if any(needle_re.search(s) for s in srcs):
            return True
        if hops <= 0:
            continue
        hops -= 1
        for s in srcs:
            for ident in re.findall(r"(?<![\w.`])([A-Za-z_]\w*)(?![\w`])", s):
                if ident in params:
                    continue
                rhs = _local_rhs(code, ident, span)
                if rhs:
                    todo.append(rhs)
    return False


def _call_extent(code, open_paren):
    """(start, end) of the call whose `(` is at open_paren, receiver included."""
    close = match_bracket(code, open_paren)
    if close < 0:
        return None
    i = open_paren
    while i > 0 and (code[i - 1].isalnum() or code[i - 1] in "_."):
        i -= 1
    return (i, close + 1)


def _first_arg(code, open_paren):
    args = call_args(code, open_paren)
    if not args:
        return None
    a, b = args[0]
    return code[a:b].strip()


def _statement_span(code, start, end, pos):
    """Absolute (start, end) of the top-level statement inside [start, end) covering pos."""
    from _kotlin_ast import stmt_spans
    for a, b in stmt_spans(code[start:end]):
        if a <= pos - start < b:
            return (start + a, start + b)
    return (start, end)


def _statement_at(code, start, end, pos):
    s, e = _statement_span(code, start, end, pos)
    return code[s:e]


def is_cancel_read(cond, cancel_idents):
    for m in CANCEL_READ_RE.finditer(cond):
        if m.group(1) in cancel_idents or m.group(1) == "cont":
            return True
    return False


# ── Rule A: the language the engine will actually speak ────────────────────
def _tag_carrying(code, piece):
    """Every `resolveTargetLocale(…)` in `piece` passes a tag derived from the caller.

    `resolveTargetLocale(null, speakable)` and
    `resolveTargetLocale(explicitLanguage.let { null }, …)` both read as "no
    explicit tag"; only a first argument that *is* `explicitLanguage` (or is
    derived from it) counts.
    """
    found = False
    for m in RESOLVE_CALL_RE.finditer(piece):
        open_pos = m.end() - 1
        close = match_bracket(piece, open_pos)
        if close < 0:
            return False
        args = split_top(piece[open_pos + 1:close], (",",))
        if not args:
            return False
        first = piece[open_pos + 1 + args[0][0]:open_pos + 1 + args[0][1]]
        if not contains_ident(first, "explicitLanguage"):
            return False
        found = True
    return found


def _check_language_channel(code, span):
    """Rule A: one resolution point, and it must honour the explicit tag."""
    failures = []
    start, end, _kind = span
    body = code[start:end]
    params = _params(code, "speak")
    if re.search(r"\bautoDetectAndSetLanguage\s*\(", body):
        failures.append(
            "%s: [tts-voice] speak() must not call autoDetectAndSetLanguage "
            "(that unconditional call was the override bug)" % MANAGER_REL
        )
    resolves = [start + m.start() for m in RESOLVE_CALL_RE.finditer(body)]
    if not resolves:
        failures.append(
            "%s: [tts-voice] speak() must resolve the utterance language through "
            "TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, ...) - the "
            "--voice tag must not be overwritten by auto-detection" % MANAGER_REL
        )
        return failures
    # A resolve whose value is never consumed resolved nothing.
    for pos in resolves:
        stmt_s, stmt_e = _statement_span(code, start, end, pos)
        ext = _call_extent(code, code.find("(", pos))
        if ext is not None:
            before = code[stmt_s:ext[0]].strip()
            after = code[ext[1]:stmt_e].strip()
            if not before and not after:
                failures.append(
                    "%s: [tts-voice] the `resolveTargetLocale(...)` result at "
                    "line ~%d is thrown away — a resolution nobody applies leaves "
                    "the engine on whatever language the previous call left"
                    % (MANAGER_REL, code[:pos].count("\n") + 1))
    honoured = False
    for m in APPLY_CALL_RE.finditer(body):
        arg = _first_arg(code, start + m.end() - 1)
        if arg is None:
            continue
        if RESOLVE_CALL_RE.search(arg) or _derives(code, span, arg, params, RESOLVE_CALL_RE):
            if _tag_carrying(code, arg):
                honoured = True
    if not honoured:
        failures.append(
            "%s: [tts-voice] the language handed to the engine never carries the "
            "caller's tag: every applied `resolveTargetLocale(...)` must pass "
            "`explicitLanguage` as its first argument (a `null`/raw guess there is "
            "the same defect as the deleted auto-detect)"
            % MANAGER_REL
        )
    return failures


# ── Rule B: what is voiced must be the sanitizer's output ──────────────────
def _check_sanitizer_flow(code, span):
    failures = []
    start, end, _kind = span
    body = code[start:end]
    params = _params(code, "speak")
    san = [start + m.start() for m in SANITIZE_CALL_RE.finditer(body)]
    eng = [start + m.start() for m in ENGINE_SPEAK_RE.finditer(body)]
    if not san:
        failures.append(
            "%s: [tts-sanitizer] speak() must call VoiceTextSanitizer.sanitize() "
            "BEFORE tts?.speak() (or declare 'tts-sanitizer-exempt: <reason>')"
            % MANAGER_REL
        )
        return failures
    if not eng:
        failures.append(
            "%s: [tts-sanitizer] speak() has no tts?.speak(...) call to judge"
            % MANAGER_REL
        )
        return failures
    e_pos = eng[0]
    open_pos = code.find("(", e_pos)
    spoken = _first_arg(code, open_pos)
    if spoken is None:
        failures.append(
            "%s: [tts-sanitizer] cannot read the tts?.speak(...) arguments"
            % MANAGER_REL
        )
        return failures
    if not _derives(code, span, spoken, params, SANITIZE_CALL_RE):
        failures.append(
            "%s: [tts-sanitizer] the text handed to the engine (%r) does not derive "
            "from VoiceTextSanitizer.sanitize(...) - calling the sanitizer and then "
            "voicing something else is the same defect as not calling it"
            % (MANAGER_REL, spoken.strip()[:60])
        )
    guarded = False
    for m in re.finditer(r"\bif\s*\(", body):
        p_open = start + m.end() - 1
        if p_open >= e_pos:
            break
        p_close = match_bracket(code, p_open)
        if p_close < 0:
            continue
        cond = code[p_open + 1:p_close]
        if "isBlank(" not in cond and "isEmpty(" not in cond:
            continue
        subject = re.sub(r"\.\s*(?:isBlank|isEmpty)\s*\(\s*\)", "", cond).strip()
        if not _derives(code, span, subject, params, SANITIZE_CALL_RE):
            continue
        body_end = _statement_span(code, start, end, p_open)[1]
        if re.search(r"\breturn\b", code[p_close + 1:max(body_end, p_close + 1)]):
            guarded = True
            break
    if not guarded:
        failures.append(
            "%s: [tts-sanitizer] speak() must bail on an empty sanitize result "
            "instead of voicing the raw text (the `isBlank()` guard must test the "
            "value that is actually spoken)" % MANAGER_REL
        )
    return failures


# ── A.06: the SpeechRecognizer code table ──────────────────────────────────
CODE_LABELS = {
    10: ("RECOGNIZER_BUSY", "ERROR_TOO_MANY_REQUESTS, retryable busy"),
    11: ("NETWORK", "ERROR_SERVER_DISCONNECTED"),
    12: ("LANGUAGE_UNSUPPORTED", "ERROR_LANGUAGE_NOT_SUPPORTED"),
    13: ("LANGUAGE_UNSUPPORTED", "ERROR_LANGUAGE_UNAVAILABLE"),
}


def resolves_to(result, simple_name):
    """Every value the result expression can take is `…simple_name`."""
    if not result:
        return False
    srcs = value_sources(result) or [result]
    names = []
    for s in srcs:
        ids = re.findall(r"[A-Za-z_]\w*", s)
        if not ids:
            return False
        names.append(ids[-1])
    return bool(names) and all(n == simple_name for n in names)


def _when_of(code, literals, span):
    """(subject, {label: result_code}, {label: result_literal}, trailing) of the member."""
    start, end, kind = span
    region = code[start:end]
    m = re.search(r"\bwhen\s*\(", region)
    if not m:
        return None
    p_open = start + m.end() - 1
    p_close = match_bracket(code, p_open)
    if p_close < 0:
        return None
    subject = code[p_open + 1:p_close]
    k = p_close + 1
    while k < len(code) and code[k] in " \t\r\n":
        k += 1
    if k >= len(code) or code[k] != "{":
        return None
    b_close = match_bracket(code, k)
    if b_close < 0:
        return None
    results, literals_view = {}, {}
    for _subj, cond, b_start, b_end in when_branches(code):
        if not (k < b_start <= b_close):
            continue
        for part in split_top(cond, (",",)):
            label = cond[part[0]:part[1]].strip()
            if re.fullmatch(r"\d+", label):
                results[int(label)] = code[b_start:b_end].strip()
                literals_view[int(label)] = literals[b_start:b_end].strip()
    trailing = code[b_close + 1:end] if kind == "expr" else ""
    return subject, results, literals_view, trailing


def _check_error_table(src):
    """[deep-audit-1044 A-06] SpeechRecognizer error codes must not be shifted.

    The defect: `mapError`/`errorMessage` labelled 11/12 as the API-33 language
    pair, so 11 (ERROR_SERVER_DISCONNECTED, API 31) surfaced as "language not
    supported" and 13 (ERROR_LANGUAGE_UNAVAILABLE) fell through to UNKNOWN —
    which silently disabled `onError`'s on-device→cloud retry (it gates on
    LANGUAGE_UNSUPPORTED).
    """
    failures = []
    try:
        code, literals = _views(src)
    except KotlinLexError as exc:
        return ["%s: [tts-errorcodes] kotlin parse failure (%s) - refusing to judge "
                "the table (fail-closed)" % (SYSTEM_SPEECH_REL, exc)]

    for member, is_map in (("mapError", True), ("errorMessage", False)):
        span = _member(code, member)
        if span is None:
            failures.append(
                "%s: [tts-errorcodes] %s() not found - the SpeechRecognizer code "
                "table is what keeps the on-device retry alive"
                % (SYSTEM_SPEECH_REL, member))
            continue
        got = _when_of(code, literals, span)
        if got is None:
            failures.append(
                "%s: [tts-errorcodes] %s() has no `when (…) { … }` table"
                % (SYSTEM_SPEECH_REL, member))
            continue
        subject, results, texts, trailing = got
        # 1. the subject must be the code parameter, not a constant. `when (0)`
        #    parses fine and answers "no arm matched" for every real code.
        params = _params(code, member)
        if not params or not contains_ident(subject, params[0]) \
                or const_value(subject) is not None:
            failures.append(
                "%s: [tts-errorcodes] %s() switches on %r, not on the incoming code "
                "%r - a constant subject makes every arm unreachable"
                % (SYSTEM_SPEECH_REL, member, subject.strip()[:30],
                   params[0] if params else "code"))
            continue
        # 2. the table's value must be returned, not eaten by a trailing call.
        if trailing.strip():
            failures.append(
                "%s: [tts-errorcodes] the %s() table is followed by %r - the arm "
                "results never leave the function"
                % (SYSTEM_SPEECH_REL, member, trailing.strip()[:40]))
            continue
        # 3. every arm the retry depends on must classify correctly.
        for label in (10, 11, 12, 13) if is_map else (10, 11, 12, 13):
            result = results.get(label)
            if is_map:
                want, why = CODE_LABELS[label]
                if result is None:
                    failures.append(
                        "%s: [tts-errorcodes] mapError must cover %d (%s)"
                        % (SYSTEM_SPEECH_REL, label, why))
                elif not resolves_to(result, want):
                    failures.append(
                        "%s: [tts-errorcodes] mapError must map %d (%s) to %s "
                        "(got %r)" % (SYSTEM_SPEECH_REL, label, why, want,
                                      result.strip()[:50]))
            else:
                msg = texts.get(label) or ""
                if result is None:
                    failures.append(
                        "%s: [tts-errorcodes] errorMessage must cover %d - otherwise "
                        "the user reads 'Unknown error (%d)' and can do nothing"
                        % (SYSTEM_SPEECH_REL, label, label))
                elif not msg.startswith('"') and not msg.startswith('"""'):
                    failures.append(
                        "%s: [tts-errorcodes] errorMessage(%d) must be a message "
                        "string (got %r)" % (SYSTEM_SPEECH_REL, label, msg[:40]))
                elif "Unknown error" in msg:
                    failures.append(
                        "%s: [tts-errorcodes] errorMessage(%d) still falls back to "
                        "'Unknown error'" % (SYSTEM_SPEECH_REL, label))
        if not is_map:
            m12, m13 = texts.get(12, ""), texts.get(13, "")
            if m12 and m13 and m12 == m13:
                failures.append(
                    "%s: [tts-errorcodes] 12 (language not supported) and 13 (pack "
                    "not downloaded) must read differently - only 13 is fixable by "
                    "the user" % SYSTEM_SPEECH_REL)
    return failures


# ── A.07: per-call tuning defaults ─────────────────────────────────────────
def _const_number(code, token):
    token = token.strip()
    try:
        return float(token.rstrip("fFdD"))
    except ValueError:
        pass
    m = re.search(r"\b(?:const\s+)?val\s+" + re.escape(token)
                  + r"\s*(?::\s*Double\s*)?=\s*([0-9]+(?:\.[0-9]+)?)[fFdD]?", code)
    if m:
        try:
            return float(m.group(1))
        except ValueError:
            return None
    return None


def _default_sites(code, literals, flag, span):
    """[(value_code, value_literal)] of every `flag` binding carrying a `?:` default."""
    start, end, _kind = span
    out = []
    for m in _site_re("speech" + flag.capitalize(), flag).finditer(code[start:end]):
        v_start = start + m.end()
        v_end = expr_end(code, v_start)
        piece = code[v_start:v_end]
        first = split_top(piece, (",",))
        if first:
            a, b = first[0]
            piece = code[v_start + a:v_start + b]
        raw = code[v_start + a:v_start + b] if first else piece
        if "?:" in raw:
            lead = len(raw) - len(raw.lstrip())
            out.append((raw.strip(), literals[v_start + a + lead:v_start + b]))
    return out


def _default_ok(code, flag, value, value_lit):
    """The absent flag must resolve to 1.0, and nothing may mangle it afterwards."""
    norm = COERCION_RE.sub("", value).strip()
    norm = strip_parens(norm).strip()
    m = re.fullmatch(
        r"(?:" + flag + r'|(?:[A-Za-z_][\w.]*\.)?getDouble\s*\(\s*"[^"]*"\s*\))'
        r"\s*\?:\s*([A-Za-z_][\w.]*|\d+(?:\.\d+)?[fFdD]?)", norm)
    if not m:
        return False, ("the fallthrough default is mangled after the `?:` group "
                       "(%r)" % value.strip()[:60])
    if "getDouble" in norm and ('"%s"' % flag) not in value_lit:
        return False, ("the `getDouble(…)` fallthrough does not read --%s" % flag)
    token = m.group(1)
    val = _const_number(code, token)
    if val is None:
        return False, ("the absent-value default %r does not resolve to a number"
                       % token)
    if abs(val - 1.0) > 1e-9:
        return False, ("the absent-value default %r is %s, the CLI contract "
                       "documents 1.0 for --%s" % (token, val, flag))
    return True, ""


def check_per_call_defaults(src):
    """[deep-audit-1044 A-07] `android-speak` must apply its tuning every call.

    `tts` is a process-wide singleton, so `args.getDouble("x")?.let { ... }`
    means "absent flag = keep the previous call's value": one `--volume 0`
    silenced the rest of the process. The decision pinned here is
    *unconditional assignment with the documented default (1.0)*, not the
    spelling of the resolver — inline `?: 1.0` and a `TtsSpeakTuning.from(...)`
    call both pass. Two ways the old token-level check was beaten: the write
    statement moved under a condition the old code did not recognise
    (`Unit; if (…) …`, a raw string hid the rest of the file) and the default
    stayed syntactically but got multiplied by 0 afterwards.
    """
    failures = []
    try:
        code, literals = _views(src)
    except KotlinLexError as exc:
        return ["%s: [tts-defaults] kotlin parse failure (%s) - refusing to judge "
                "the tuning (fail-closed)" % (HANDLER_REL, exc)]
    span = _member(code, "cmdSpeak")
    if span is None:
        return ["%s: [tts-defaults] cmdSpeak() not found" % HANDLER_REL]
    start, end, _kind = span
    body = code[start:end]

    if re.search(r"getDouble\s*\(\s*\)\s*\?\.\s*(?:let|also|run)\b", body):
        failures.append(
            "%s: [tts-defaults] rate/pitch/volume must not be applied "
            "conditionally (`args.getDouble(\"x\")?.let { tts.speechX = ... }`) "
            "- an absent flag then inherits the previous call's value" % HANDLER_REL)

    whole = (0, len(code), "block")
    for field_name, flag in TUNING_FIELDS:
        sites = [start + m.start() for m in
                 _site_re(field_name, flag).finditer(body)]
        writes = [p for p in sites
                  if re.match(r"\btts", code[p:p + 6]) or
                  re.match(r"tts\s*\??\.", code[p:p + 8])]
        if len(writes) != 1:
            failures.append(
                "%s: [tts-defaults] cmdSpeak must assign tts.%s exactly once per "
                "call (found %d) - the manager is a process-wide singleton, so a "
                "skipped assignment leaks the previous call's value"
                % (HANDLER_REL, field_name, len(writes)))
        else:
            pos = writes[0]
            conds = [c for _k, c in enclosing_conditions(code, pos)]
            stmt_s, _stmt_e = _statement_span(code, start, end, pos)
            pre = code[stmt_s:pos]
            if conds:
                failures.append(
                    "%s: [tts-defaults] tts.%s is assigned conditionally (under "
                    "`%s`) - an absent flag then keeps the previous value"
                    % (HANDLER_REL, field_name, conds[0].strip()[:40]))
            elif "?." in pre:
                failures.append(
                    "%s: [tts-defaults] tts.%s is applied through a conditional "
                    "scope call: %r" % (HANDLER_REL, field_name, pre.strip()[:60]))
            elif not path_feasible(code, pos):
                failures.append(
                    "%s: [tts-defaults] the tts.%s assignment sits on an "
                    "unreachable path" % (HANDLER_REL, field_name))

        # the default resolution may live in the resolver function, outside
        # cmdSpeak — the decision is "this file resolves --flag to 1.0", not
        # "it does so inside this member".
        defaults = _default_sites(code, literals, flag, whole)
        if not defaults:
            failures.append(
                "%s: [tts-defaults] the per-call default for --%s is not resolved "
                "anywhere (inline `?: 1.0` or a named constant equal to 1.0) - the "
                "CLI contract documents 1.0 for all three" % (HANDLER_REL, flag))
            continue
        for value, value_lit in defaults:
            ok, why = _default_ok(code, flag, value, value_lit)
            if not ok:
                failures.append("%s: [tts-defaults] --%s: %s" % (HANDLER_REL, flag, why))
    return failures


# ── [dv26/G10] the Quick Test clip's cancellation latch ────────────────────
def _dominating_cancel_guard(block, pos, cancel_idents):
    """Nearest cancellation guard that dominates `pos` inside `block`.

    Dominates = the guard's `if` sits at the SAME brace depth as `pos` (a guard
    parked in a sibling callback or in an already-closed inner block does not
    guard this line), ends before `pos`, is on a feasible path (`if (false &&
    guard)` folds to a constant false), and its body leaves the callback.
    """
    d0 = brace_depth(block, pos)
    best = None
    for cond, t_start, t_end, e_start, e_end in if_blocks(block):
        if t_start >= pos or t_end > pos:
            continue
        if brace_depth(block, t_start) != d0:
            continue
        if not is_cancel_read(cond, cancel_idents):
            continue
        if not guard_feasible(cond):
            continue
        body = block[t_start:t_end]
        if e_start is not None:
            body += " " + block[e_start:e_end]
        if not re.search(r"\breturn\b|\bthrow\b", body):
            continue
        if best is None or t_end > best[1]:
            best = (t_start, t_end)
    return best


def _local_fun_region(block, pos):
    """Innermost local `fun … { … }` body of `block` containing pos, else block."""
    best = None
    for m in re.finditer(r"\bfun\s+[A-Za-z_]\w*\s*\(", block):
        p_close = match_bracket(block, m.end() - 1)
        if p_close < 0:
            continue
        k = p_close + 1
        while k < len(block) and block[k] in " \t\r\n":
            k += 1
        if k >= len(block) or block[k] != "{":
            continue
        b_close = match_bracket(block, k)
        if b_close < 0 or not (k < pos < b_close):
            continue
        if best is None or (b_close - k) < (best[1] - best[0]):
            best = (k, b_close)
    return best or (0, len(block) - 1)


def check_quick_test_clip(src):
    """[dv26/G10] The Quick Test clip must not outlive its caller.

    C1 (*guard*) — inside the `suspendCancellableCoroutine` block, every engine
    SETUP statement (`language =`, `setOn…Listener(`) needs a cancellation guard
    dominating it, and every WRITE (`synthesizeToFile(`, file writes) needs one
    that is *after the last setup statement* — the init callback and the write
    are separated by the setup work, and that window was the defect.
    C2 (*cancel*) — `invokeOnCancellation` must exist inside the block, shut the
    engine down, and re-delete the clip.
    C3 (*cleanup*) — `finally` must still delete the clip, and the block that
    hands the result back (`resumeWith`) must drop the file when it ran after a
    cancellation (the late-write path: no caller will ever read it).
    """
    failures = []
    try:
        code = code_view(src)
    except KotlinLexError as exc:
        return ["%s: [tts-clip-guard] kotlin parse failure (%s) - refusing to judge "
                "the clip (fail-closed)" % (QUICK_TEST_REL, exc)]
    span = _member(code, "synthesizeTestClip")
    if span is None:
        return [
            "%s: [tts-clip-guard] %s not found — re-derive this criterion instead "
            "of deleting it (the clip's cancellation latch is what keeps a late "
            "engine callback from writing an orphan WAV into cacheDir)"
            % (QUICK_TEST_REL, CLIP_SIG.strip())
        ]
    start, end, _kind = span
    body = code[start:end]

    m = CLIP_FALLBACK_RE.search(body)
    clip_var = m.group(1) if m else None

    coro = re.search(r"suspendCancellableCoroutine\s*\{", body)
    if coro is None:
        return [
            "%s: [tts-clip-guard] no `suspendCancellableCoroutine { cont -> … }` "
            "block in synthesizeTestClip — re-derive" % QUICK_TEST_REL
        ]
    o = start + coro.end() - 1
    c = match_bracket(code, o)
    if c < 0:
        return ["%s: [tts-clip-guard] unbalanced suspendCancellableCoroutine block"
                % QUICK_TEST_REL]
    block = code[o:c + 1]

    inv = re.search(r"invokeOnCancellation\s*\{", block)
    cancel_span = None
    if inv is not None:
        io = inv.end() - 1
        ic = match_bracket(block, io)
        if ic > 0:
            cancel_span = (io, ic)
    cancel_body = block[cancel_span[0]:cancel_span[1] + 1] if cancel_span else ""
    cancel_idents = set(re.findall(r"[A-Za-z_]\w*", cancel_body)) if cancel_body else set()

    def fail(tag, msg):
        failures.append("%s: [%s] %s" % (QUICK_TEST_REL, tag, msg))

    # ── C1: a cancellation guard dominates every engine statement ──
    touches = []
    for rex, kind in ((TOUCH_SETUP_RE, "setup"), (TOUCH_WRITE_RE, "write")):
        for t in rex.finditer(block):
            touches.append((t.start(), kind, t.group(0).strip()))
    touches.sort()
    for pos, kind, what in touches:
        g = _dominating_cancel_guard(block, pos, cancel_idents)
        if g is None:
            fail(
                "tts-clip-guard",
                "`%s` runs without a cancellation guard before it — a callback "
                "that lands after the 15 s timeout / sheet dismissal must return "
                "before touching the engine" % what,
            )
            continue
        if kind == "write":
            between = block[g[1]:pos]
            setup = TOUCH_SETUP_RE.search(between)
            if setup is not None:
                fail(
                    "tts-clip-guard",
                    "`%s` is guarded only across the engine setup "
                    "(`%s`) — the window between the init callback and the write "
                    "must be re-checked, else a cancellation landing in it still "
                    "writes the clip" % (what, setup.group(0).strip()),
                )

    # ── C2: the cancel path shuts the engine down AND re-deletes the clip ──
    if cancel_span is None:
        fail(
            "tts-clip-cancel",
            "no `invokeOnCancellation { … }` in the coroutine block — the engine "
            "leaks and the clip survives cancellation",
        )
    else:
        if "shutdown" not in cancel_body:
            fail("tts-clip-cancel", "`invokeOnCancellation` must shut the engine down")
        if clip_var is None or ("%s.delete()" % clip_var) not in cancel_body.replace(" ", ""):
            fail(
                "tts-clip-cancel",
                "`invokeOnCancellation` must re-delete the clip: the engine can "
                "have finished the write between the dispatch and this cancellation",
            )

    # ── C3: exit-path cleanup + late-callback cleanup ──
    fin = re.search(r"\bfinally\s*\{", body)
    fib = None
    if fin is not None:
        fo = start + fin.end() - 1
        fc = match_bracket(code, fo)
        fib = code[fo:fc + 1] if fc > 0 else None
    if fib is None or clip_var is None or ("%s.delete()" % clip_var) not in fib.replace(" ", ""):
        fail(
            "tts-clip-cleanup",
            "the `finally` block must still delete the clip variable — the "
            "success path reads it and nothing else cleans cacheDir",
        )
    resume = block.find("resumeWith(")
    if resume < 0:
        fail("tts-clip-cleanup", "no `resumeWith(` in the coroutine block — re-derive")
    else:
        host = _local_fun_region(block, resume)
        guarded_delete = False
        for cond, t_start, _t_end, e_start, e_end in if_blocks(block):
            if not (host[0] < t_start < host[1]) or t_start >= resume:
                continue
            if not is_cancel_read(cond, cancel_idents) or not guard_feasible(cond):
                continue
            drop = ("%s.delete()" % clip_var).replace(" ", "") if clip_var else None
            if drop is None:
                continue
            branch = (block[t_start:_t_end] + " " +
                      (block[e_start:e_end] if e_start is not None else "")).replace(" ", "")
            if drop in branch:
                guarded_delete = True
                break
        if not guarded_delete:
            fail(
                "tts-clip-cleanup",
                "the block that hands the result back must drop the clip when it "
                "ran after a cancellation — a late onDone/onError leaves the "
                "orphan WAV in cacheDir",
            )

    return failures


def run(root, verbose=True):
    failures = []
    manager = read(root, MANAGER_REL)
    handler = read(root, HANDLER_REL)

    if manager is None:
        failures.append(
            "%s: [tts-files] TextToSpeechManager.kt not found" % MANAGER_REL
        )
    else:
        try:
            manager_code, manager_lit = _views(manager)
        except KotlinLexError as exc:
            manager_code = manager_lit = None
            failures.append(
                "%s: [tts-files] kotlin parse failure (%s) - refusing to judge "
                "speak() (fail-closed)" % (MANAGER_REL, exc))
        if manager_code is not None:
            speak = _member(manager_code, "speak")
            if speak is None:
                failures.append(
                    "%s: [tts-files] TextToSpeechManager.speak() not found" % MANAGER_REL
                )
            else:
                # the marker is a comment: literal_view masks comments, so the
                # raw text is the only view that still carries it (all views
                # are offset-aligned, so the span applies unchanged).
                exempt = bool(SANITIZER_EXEMPT_RE.search(manager[speak[0]:speak[1]]))
                failures.extend(_check_language_channel(manager_code, speak))
                if not exempt:
                    failures.extend(_check_sanitizer_flow(manager_code, speak))
            # ── Rule C: streaming path keeps auto-detection ──
            queued = _member(manager_code, "speakQueued")
            if queued is not None and "autoDetectAndSetLanguage(" not in \
                    manager_code[queued[0]:queued[1]]:
                failures.append(
                    "%s: [tts-stream] speakQueued() must keep autoDetectAndSetLanguage "
                    "(streaming fragments have no explicit language; per-chunk sanitize "
                    "would break cross-chunk link rewrites)" % MANAGER_REL
                )

    if handler is None:
        failures.append(
            "%s: [tts-files] SpeakOffloadHandler.kt not found" % HANDLER_REL
        )
    else:
        try:
            handler_code, handler_lit = _views(handler)
        except KotlinLexError as exc:
            handler_code = handler_lit = None
            failures.append(
                "%s: [tts-files] kotlin parse failure (%s) - refusing to judge the "
                "handler (fail-closed)" % (HANDLER_REL, exc))
        if handler_code is not None:
            # ── Rule A, handler side: pass the tag, never set language itself ──
            if re.search(r"\btts\s*\??\.\s*setLanguage\s*\(", handler_code):
                failures.append(
                    "%s: [tts-voice] SpeakOffloadHandler must not call tts.setLanguage "
                    "- the language is resolved in one place (speak); the old "
                    "set-then-overwrite pair was the bug" % HANDLER_REL
                )
            if not re.search(
                    r"\btts\s*\??\.\s*speak\s*\([^)]*explicitLanguage\s*=", handler_code):
                failures.append(
                    "%s: [tts-voice] the --voice path must pass the tag into speak "
                    '("tts.speak(text, explicitLanguage = voiceTag)"), not a bare '
                    "speak(text) that self-overwrites" % HANDLER_REL
                )
            # ── Rule A, honest result: report the effective language ──
            if 'voiceTag ?: "auto"' in handler_lit:
                failures.append(
                    "%s: [tts-voice] the voice field must not echo the requested "
                    'tag (found `voiceTag ?: "auto"`)' % HANDLER_REL
                )
            if '.put("voice", effectiveVoice)' not in handler_lit:
                failures.append(
                    "%s: [tts-voice] the voice field must report the effective "
                    "language (currentLanguageTag()), not the input parameter"
                    % HANDLER_REL
                )
            failures.extend(check_per_call_defaults(handler))

    system_speech = read(root, SYSTEM_SPEECH_REL)
    if system_speech is None:
        failures.append(
            "%s: [tts-files] SystemSpeechRecognitionEngine.kt not found"
            % SYSTEM_SPEECH_REL
        )
    else:
        failures.extend(_check_error_table(system_speech))

    # ── [dv26/G10] the Quick Test clip's cancellation latch ──
    quick_test = read(root, QUICK_TEST_REL)
    if quick_test is None:
        failures.append(
            "%s: [tts-files] QuickTestSheet.kt not found" % QUICK_TEST_REL
        )
    else:
        failures.extend(check_quick_test_clip(quick_test))

    if verbose:
        for line in failures:
            print(line)
    return failures


# ── [dv26/G10] Quick Test clip fixtures ─────────────────────────────────────
COMPLIANT_QUICK = '''
private suspend fun synthesizeTestClip(context: Context, text: String): ByteArray? {
    val file = java.io.File(context.cacheDir, "quicktest-clip-${System.currentTimeMillis()}.wav")
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    return try {
        kotlinx.coroutines.withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                fun finish(ok: Boolean) {
                    runCatching { tts?.shutdown() }
                    if (SynthClipPolicy.mustDropOnCallback(cancelled.get())) runCatching { file.delete() }
                    if (cont.isActive) cont.resumeWith(Result.success(ok))
                }
                tts = TextToSpeech(context) { status ->
                    if (!SynthClipPolicy.mayTouchEngine(cancelled.get())) {
                        runCatching { tts?.shutdown() }
                        return@TextToSpeech
                    }
                    if (status != TextToSpeech.SUCCESS) {
                        finish(false)
                        return@TextToSpeech
                    }
                    tts?.language = java.util.Locale.US
                    tts?.setOnUtteranceProgressListener(listener)
                    if (!SynthClipPolicy.maySynthesize(cancelled.get())) {
                        runCatching { tts?.shutdown() }
                        return@TextToSpeech
                    }
                    val result = tts?.synthesizeToFile(text, android.os.Bundle(), file, "quicktest-clip")
                    if (result != TextToSpeech.SUCCESS) finish(false)
                }
                cont.invokeOnCancellation {
                    cancelled.set(true)
                    runCatching { tts?.shutdown() }
                    runCatching { file.delete() }
                }
            }
        }?.takeIf { it }?.let { file.readBytes() }
    } catch (e: Exception) {
        null
    } finally {
        runCatching { file.delete() }
    }
}
'''

# The defect: no cancellation latch at all — the late init callback wrote the
# clip after `finally` had already deleted it.
PRE_FIX_QUICK = '''
private suspend fun synthesizeTestClip(context: Context, text: String): ByteArray? {
    val file = java.io.File(context.cacheDir, "quicktest-clip-${System.currentTimeMillis()}.wav")
    return try {
        kotlinx.coroutines.withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                fun finish(ok: Boolean) {
                    runCatching { tts?.shutdown() }
                    if (cont.isActive) cont.resumeWith(Result.success(ok))
                }
                tts = TextToSpeech(context) { status ->
                    if (status != TextToSpeech.SUCCESS) {
                        finish(false)
                        return@TextToSpeech
                    }
                    tts?.language = java.util.Locale.US
                    tts?.setOnUtteranceProgressListener(listener)
                    val result = tts?.synthesizeToFile(text, android.os.Bundle(), file, "quicktest-clip")
                    if (result != TextToSpeech.SUCCESS) finish(false)
                }
                cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
            }
        }?.takeIf { it }?.let { file.readBytes() }
    } catch (e: Exception) {
        null
    } finally {
        runCatching { file.delete() }
    }
}
'''

INIT_GUARD = """                    if (!SynthClipPolicy.mayTouchEngine(cancelled.get())) {
                        runCatching { tts?.shutdown() }
                        return@TextToSpeech
                    }
"""

SYNTH_GUARD = """                    if (!SynthClipPolicy.maySynthesize(cancelled.get())) {
                        runCatching { tts?.shutdown() }
                        return@TextToSpeech
                    }
"""

LATE_DROP = "                    if (SynthClipPolicy.mustDropOnCallback(cancelled.get())) runCatching { file.delete() }\n"

CANCEL_DROP = """                    runCatching { file.delete() }
                }
"""


def _mut(rel, old, new, count=1):
    got = rel.count(old)
    if got != count:
        raise AssertionError("fixture anchor hit %d times, expected %d: %r" % (got, count, old[:60]))
    return rel.replace(old, new, count)


# m1: the init callback lost its entry guard (late callback touches the engine)
MUT_NO_INIT_GUARD_QUICK = _mut(COMPLIANT_QUICK, INIT_GUARD, "")
# m2: only the init guard left — the window between setup and the write is open
MUT_NO_SYNTH_GUARD_QUICK = _mut(COMPLIANT_QUICK, SYNTH_GUARD, "")
# m3: the guard moved AFTER synthesizeToFile (looks guarded, guards nothing)
MUT_GUARD_AFTER_SYNTH_QUICK = _mut(
    _mut(COMPLIANT_QUICK, INIT_GUARD, ""),
    '                    val result = tts?.synthesizeToFile(text, android.os.Bundle(), file, "quicktest-clip")\n',
    '                    val result = tts?.synthesizeToFile(text, android.os.Bundle(), file, "quicktest-clip")\n'
    + INIT_GUARD,
)
# m4: cancel path keeps shutdown but dropped the re-delete
MUT_CANCEL_NO_DROP_QUICK = _mut(COMPLIANT_QUICK, CANCEL_DROP, "                }\n")
# m5: the late callback no longer drops the clip it just wrote
MUT_NO_LATE_DROP_QUICK = _mut(COMPLIANT_QUICK, LATE_DROP, "")
# m6: exit-path cleanup removed (success path leaves the clip behind)
MUT_NO_FINALLY_DROP_QUICK = _mut(
    COMPLIANT_QUICK,
    """    } finally {
        runCatching { file.delete() }
    }
""",
    """    }
""",
)

# ── selftest ────────────────────────────────────────────────────────────────

PRE_FIX_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String) {
        autoDetectAndSetLanguage(text)
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return true
    }

    fun speakQueued(text: String) {
        autoDetectAndSetLanguage(text)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

COMPLIANT_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        val speakable = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext))
        if (speakable.isBlank()) return false
        applyLanguage(TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, speakable))
        tts?.speak(speakable, TextToSpeech.QUEUE_FLUSH, params, drain.utteranceId(sessionEpoch))
        return true
    }

    fun speakQueued(text: String) {
        autoDetectAndSetLanguage(text)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

# Rule-B violation only: no sanitize call, empty result voiced raw.
SANITIZE_REMOVED_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        applyLanguage(TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, text))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return true
    }

    fun speakQueued(text: String) {
        autoDetectAndSetLanguage(text)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

# Rule-A violation only: explicit channel reverted to the unconditional guess.
AUTO_DETECT_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        val speakable = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext))
        if (speakable.isBlank()) return false
        autoDetectAndSetLanguage(speakable)
        tts?.speak(speakable, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return true
    }

    fun speakQueued(text: String) {
        autoDetectAndSetLanguage(text)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

# Rule-C violation: streaming path lost its auto-detection.
STREAM_BROKEN_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        val speakable = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext))
        if (speakable.isBlank()) return false
        applyLanguage(TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, speakable))
        tts?.speak(speakable, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return true
    }

    fun speakQueued(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

# Rule-B exemption arm: raw-text caller declares why.
EXEMPT_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        // tts-sanitizer-exempt: diagnostic readback must voice the raw payload
        applyLanguage(TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, text))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return true
    }

    fun speakQueued(text: String) {
        autoDetectAndSetLanguage(text)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

PRE_FIX_HANDLER = '''
class SpeakOffloadHandler {
    private fun cmdSpeak(args: OffloadArgs): NativeOffloadResult {
        val voiceTag = args.get("voice")
        if (!voiceTag.isNullOrBlank()) {
            tts.setLanguage(Locale.forLanguageTag(voiceTag))
        }
        tts.speak(text)
        return ok().put("voice", voiceTag ?: "auto")
    }
}
'''

COMPLIANT_HANDLER = '''
class SpeakOffloadHandler {
    private fun cmdSpeak(args: OffloadArgs): NativeOffloadResult {
        val voiceTag = args.get("voice")
        val tuning = TtsSpeakTuning.from(
            rate = args.getDouble("rate"),
            pitch = args.getDouble("pitch"),
            volume = args.getDouble("volume"),
        )
        tts.speechRate = tuning.rate
        tts.speechPitch = tuning.pitch
        tts.speechVolume = tuning.volume
        val spoke = tts.speak(text, explicitLanguage = voiceTag)
        if (!spoke) {
            return err().put("error", if (engineDown) "tts_unavailable" else "nothing_to_speak")
        }
        val effectiveVoice = tts.currentLanguageTag() ?: "unknown"
        return ok().put("voice", effectiveVoice)
    }
}

internal data class TtsSpeakTuning(val rate: Float, val pitch: Float, val volume: Float) {
    companion object {
        const val DEFAULT = 1.0

        fun from(rate: Double?, pitch: Double?, volume: Double?): TtsSpeakTuning =
            TtsSpeakTuning(
                rate = (rate ?: DEFAULT).toFloat(),
                pitch = (pitch ?: DEFAULT).toFloat(),
                volume = (volume ?: DEFAULT).toFloat(),
            )
    }
}
'''

# A-07 violation 1: the pre-fix conditional application (an absent flag keeps
# whatever the previous invocation left in the process-wide singleton).
CONDITIONAL_TUNING_HANDLER = '''
class SpeakOffloadHandler {
    private fun cmdSpeak(args: OffloadArgs): NativeOffloadResult {
        val voiceTag = args.get("voice")
        args.getDouble("rate")?.let { tts.speechRate = it.toFloat() }
        args.getDouble("pitch")?.let { tts.speechPitch = it.toFloat() }
        args.getDouble("volume")?.let { tts.speechVolume = it.toFloat() }
        val spoke = tts.speak(text, explicitLanguage = voiceTag)
        if (!spoke) {
            return err().put("error", if (engineDown) "tts_unavailable" else "nothing_to_speak")
        }
        val effectiveVoice = tts.currentLanguageTag() ?: "unknown"
        return ok().put("voice", effectiveVoice)
    }
}
'''

# A-07 violation 2: unconditional, but the absent-flag default is not 1.0 —
# the documented contract for a call without `--rate`.
WRONG_DEFAULT_HANDLER = COMPLIANT_HANDLER.replace("rate = (rate ?: DEFAULT)", "rate = (rate ?: 0.0)")

# ── A-06 fixtures: the SpeechRecognizer error table ─────────────────────────

COMPLIANT_SYSTEM = '''
class SystemSpeechRecognitionEngine(private val appContext: Context) {
    companion object {
        internal fun mapError(code: Int): RecognitionError = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.NO_MATCH
            SpeechRecognizer.ERROR_NETWORK -> RecognitionError.NETWORK
            10 -> RecognitionError.RECOGNIZER_BUSY
            11 -> RecognitionError.NETWORK
            12 -> RecognitionError.LANGUAGE_UNSUPPORTED
            13 -> RecognitionError.LANGUAGE_UNSUPPORTED
            else -> RecognitionError.UNKNOWN
        }

        internal fun errorMessage(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            10 -> "Too many requests - try again in a moment"
            11 -> "Recognition service disconnected"
            12 -> "Language not supported by the recognizer"
            13 -> "Language pack unavailable - download it in the system speech settings"
            else -> "Unknown error ($code)"
        }
    }
}
'''

# The defect itself: 11/12 read as the API-33 language pair, 13 uncovered.
PRE_FIX_SYSTEM = '''
class SystemSpeechRecognitionEngine(private val appContext: Context) {
    companion object {
        private fun mapError(code: Int): RecognitionError = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> RecognitionError.NO_MATCH
            11 /* ERROR_LANGUAGE_NOT_SUPPORTED */,
            12 /* ERROR_LANGUAGE_UNAVAILABLE   */ -> RecognitionError.LANGUAGE_UNSUPPORTED
            else -> RecognitionError.UNKNOWN
        }

        private fun errorMessage(code: Int): String = when (code) {
            11 /* ERROR_LANGUAGE_NOT_SUPPORTED */ -> "Language not supported by the recognizer"
            12 /* ERROR_LANGUAGE_UNAVAILABLE */ -> "Language pack unavailable"
            else -> "Unknown error ($code)"
        }
    }
}
'''

MUT_11_LANGUAGE_SYSTEM = COMPLIANT_SYSTEM.replace(
    "11 -> RecognitionError.NETWORK", "11 -> RecognitionError.LANGUAGE_UNSUPPORTED"
)
MUT_10_UNKNOWN_SYSTEM = COMPLIANT_SYSTEM.replace(
    "10 -> RecognitionError.RECOGNIZER_BUSY", "10 -> RecognitionError.UNKNOWN"
)
MUT_13_UNKNOWN_SYSTEM = COMPLIANT_SYSTEM.replace(
    "13 -> RecognitionError.LANGUAGE_UNSUPPORTED", "13 -> RecognitionError.UNKNOWN"
)
MUT_MSG_13_SYSTEM = COMPLIANT_SYSTEM.replace(
    '            13 -> "Language pack unavailable - download it in the system speech settings"\n',
    "",
)


# ── batch-6 witness fixtures (06-1 … 06-6) ──────────────────────────────────
# Each one is the shape an external witness used to walk through the gate. The
# axis is printed in the label; the assertion is the same for every arm: the
# gate must fire ON THE CRITERION THE DEFECT BREAKS (never "some violation").

# 06-1 (dataflow): the sanitize call is still there, its RESULT is discarded on
# the way to the engine — `.let { text }` hands the raw parameter back.
SANITIZE_DISCARDED_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        val speakable = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext)).let { text }
        if (speakable.isBlank()) return false
        applyLanguage(TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, speakable))
        tts?.speak(speakable, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return true
    }

    fun speakQueued(text: String) {
        autoDetectAndSetLanguage(text)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

# 06-1b (dataflow): sanitize into a variable nobody speaks.
SANITIZE_UNUSED_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        val cleaned = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext))
        val speakable = text
        if (speakable.isBlank()) return false
        applyLanguage(TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, speakable))
        tts?.speak(speakable, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return true
    }

    fun speakQueued(text: String) {
        autoDetectAndSetLanguage(text)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

# 06-2 (dataflow): the policy call runs twice — once with the tag (discarded)
# and once applied with `null`. The old check was a substring of the first.
DISCARDED_RESOLVE_MANAGER = '''
class TextToSpeechManager {
    fun speak(text: String, explicitLanguage: String? = null): Boolean {
        val speakable = VoiceTextSanitizer.sanitize(text, TtsSpokenPhrases.from(appContext))
        if (speakable.isBlank()) return false
        TtsLanguagePolicy.resolveTargetLocale(explicitLanguage, speakable)
        applyLanguage(TtsLanguagePolicy.resolveTargetLocale(null, speakable))
        tts?.speak(speakable, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return true
    }

    fun speakQueued(text: String) {
        autoDetectAndSetLanguage(text)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }
}
'''

# 06-3 (anchor): the documented default is still spelled out, then multiplied
# away — `--rate` without a flag now speaks at rate 0.
MANGLED_DEFAULT_HANDLER = COMPLIANT_HANDLER.replace(
    "rate = (rate ?: DEFAULT).toFloat()",
    "rate = (rate ?: DEFAULT).toFloat() * 0f",
)

# 06-4 (scope): the arms are intact, the SUBJECT is a constant — every arm is
# unreachable and every code falls through.
MUT_WHEN_SUBJECT_SYSTEM = COMPLIANT_SYSTEM.replace(
    "internal fun mapError(code: Int): RecognitionError = when (code) {",
    "internal fun mapError(code: Int): RecognitionError = when (0) {",
)

# 06-4b (scope): the table is right, its VALUE is eaten by a trailing `.let`.
MUT_WHEN_TAIL_SYSTEM = COMPLIANT_SYSTEM.replace(
    "            else -> RecognitionError.UNKNOWN\n        }",
    "            else -> RecognitionError.UNKNOWN\n        }.let { RecognitionError.UNKNOWN }",
)

# 06-6 (parser-death): a raw string whose body contains `//` used to make the
# gate's own comment stripper swallow the rest of the file — the conditional
# tuning below it became invisible.
_QQ = '"' * 3
RAW_STRING_HANDLER = ('''
class SpeakOffloadHandler {
    private fun cmdSpeak(args: OffloadArgs): NativeOffloadResult {
        val banner = @QQ@see // not a comment@QQ@
        val tuning = TtsSpeakTuning.from(
            rate = args.getDouble("rate"),
            pitch = args.getDouble("pitch"),
            volume = args.getDouble("volume"),
        )
        if (args.getDouble("rate") != null) tts.speechRate = tuning.rate
        tts.speechPitch = tuning.pitch
        tts.speechVolume = tuning.volume
        val spoke = tts.speak(text, explicitLanguage = voiceTag)
        if (!spoke) {
            return err().put("error", if (engineDown) "tts_unavailable" else "nothing_to_speak")
        }
        val effectiveVoice = tts.currentLanguageTag() ?: "unknown"
        return ok().put("voice", effectiveVoice)
    }
}

internal data class TtsSpeakTuning(val rate: Float, val pitch: Float, val volume: Float) {
    companion object {
        const val DEFAULT = 1.0

        fun from(rate: Double?, pitch: Double?, volume: Double?): TtsSpeakTuning =
            TtsSpeakTuning(
                rate = (rate ?: DEFAULT).toFloat(),
                pitch = (pitch ?: DEFAULT).toFloat(),
                volume = (volume ?: DEFAULT).toFloat(),
            )
    }
}
''').replace("@QQ@", _QQ)

# Green arm: the same decision spelled inline, without the resolver.
INLINE_DEFAULT_HANDLER = COMPLIANT_HANDLER.replace(
    '''        val tuning = TtsSpeakTuning.from(
            rate = args.getDouble("rate"),
            pitch = args.getDouble("pitch"),
            volume = args.getDouble("volume"),
        )
        tts.speechRate = tuning.rate
        tts.speechPitch = tuning.pitch
        tts.speechVolume = tuning.volume
''',
    '''        tts.speechRate = (args.getDouble("rate") ?: 1.0).toFloat()
        tts.speechPitch = (args.getDouble("pitch") ?: 1.0).toFloat()
        tts.speechVolume = (args.getDouble("volume") ?: 1.0).toFloat()
''',
)

# 06-5 (family-hole): the guard is still written out, behind a constant-false
# first conjunct — `guard_feasible` folds it, a substring check does not.
MUT_GUARD_CONST_FALSE_QUICK = _mut(
    COMPLIANT_QUICK, SYNTH_GUARD,
    "                    if (false && !SynthClipPolicy.maySynthesize(cancelled.get())) {\n"
    "                        runCatching { tts?.shutdown() }\n"
    "                        return@TextToSpeech\n"
    "                    }\n",
)


def write_fixture(root, rel, body):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(body)


def selftest():
    cases = [
        # (label, manager_body, handler_body, system_body, expected_failure_codes)
        (
            "pre-fix shape (override + echo + zero sanitize + conditional tuning)",
            PRE_FIX_MANAGER,
            PRE_FIX_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-voice", "tts-sanitizer", "tts-defaults"},
        ),
        (
            "compliant shape (policy + sanitize + honest voice + full code table)",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            set(),
        ),
        (
            "rule B only: sanitize call removed",
            SANITIZE_REMOVED_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-sanitizer"},
        ),
        (
            "rule A only: explicit channel reverted to auto-detect",
            AUTO_DETECT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-voice"},
        ),
        (
            "rule A handler only: handler still sets language + echoes tag",
            COMPLIANT_MANAGER,
            PRE_FIX_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-voice", "tts-defaults"},
        ),
        (
            "rule C only: streaming path lost auto-detection",
            STREAM_BROKEN_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-stream"},
        ),
        (
            "rule B exemption: declared raw-text caller passes",
            EXEMPT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            set(),
        ),
        # ── A-06: the error table (teeth, one arm per decision point) ──
        (
            "code table reverted to the pre-fix shift (11/12 language, 13 unknown)",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            PRE_FIX_SYSTEM,
            {"tts-errorcodes"},
        ),
        (
            "code table: 11 (server disconnected) reported as a language failure",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            MUT_11_LANGUAGE_SYSTEM,
            {"tts-errorcodes"},
        ),
        (
            "code table: 10 (too many requests) left as UNKNOWN",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            MUT_10_UNKNOWN_SYSTEM,
            {"tts-errorcodes"},
        ),
        (
            "code table: 13 (language pack unavailable) left as UNKNOWN",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            MUT_13_UNKNOWN_SYSTEM,
            {"tts-errorcodes"},
        ),
        (
            "code table: 13 lost its user-visible message",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            MUT_MSG_13_SYSTEM,
            {"tts-errorcodes"},
        ),
        # ── A-07: per-call defaults (teeth) ──
        (
            "per-call defaults: rate/pitch/volume applied conditionally again",
            COMPLIANT_MANAGER,
            CONDITIONAL_TUNING_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-defaults"},
        ),
        (
            "per-call defaults: absent --rate no longer resolves to 1.0",
            COMPLIANT_MANAGER,
            WRONG_DEFAULT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-defaults"},
        ),
        # ── S-10 / G10: the Quick Test clip's cancellation latch (one arm per
        #    decision point, plus the pre-fix shape as the PRE arm) ──
        (
            "clip: pre-fix shape (no latch — late init callback writes an orphan WAV)",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-clip-guard", "tts-clip-cancel", "tts-clip-cleanup"},
            PRE_FIX_QUICK,
        ),
        (
            "clip: compliant latch",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            set(),
            COMPLIANT_QUICK,
        ),
        (
            "clip: init callback lost its entry guard",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-clip-guard"},
            MUT_NO_INIT_GUARD_QUICK,
        ),
        (
            "clip: setup→write window no longer re-checked",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-clip-guard"},
            MUT_NO_SYNTH_GUARD_QUICK,
        ),
        (
            "clip: guard parked after synthesizeToFile",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-clip-guard"},
            MUT_GUARD_AFTER_SYNTH_QUICK,
        ),
        (
            "clip: cancel path dropped the re-delete",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-clip-cancel"},
            MUT_CANCEL_NO_DROP_QUICK,
        ),
        (
            "clip: late callback no longer drops the clip",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-clip-cleanup"},
            MUT_NO_LATE_DROP_QUICK,
        ),
        (
            "clip: exit-path cleanup removed",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-clip-cleanup"},
            MUT_NO_FINALLY_DROP_QUICK,
        ),
        # ── batch-6 witnesses: "the token is still there, the effect is gone" ──
        (
            "06-1 (dataflow) sanitize result discarded via `.let { text }`",
            SANITIZE_DISCARDED_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-sanitizer"},
        ),
        (
            "06-1b (dataflow) sanitize into a variable nobody speaks",
            SANITIZE_UNUSED_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-sanitizer"},
        ),
        (
            "06-2 (dataflow) tag-carrying resolve discarded, `null` one applied",
            DISCARDED_RESOLVE_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-voice"},
        ),
        (
            "06-3 (anchor) documented default multiplied away after the `?:` group",
            COMPLIANT_MANAGER,
            MANGLED_DEFAULT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-defaults"},
        ),
        (
            "06-4 (scope) code table switches on a constant subject (`when (0)`)",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            MUT_WHEN_SUBJECT_SYSTEM,
            {"tts-errorcodes"},
        ),
        (
            "06-4b (scope) code table's value eaten by a trailing `.let`",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            MUT_WHEN_TAIL_SYSTEM,
            {"tts-errorcodes"},
        ),
        (
            "06-5 (family-hole) cancellation guard behind `false &&`",
            COMPLIANT_MANAGER,
            COMPLIANT_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-clip-guard"},
            MUT_GUARD_CONST_FALSE_QUICK,
        ),
        (
            "06-6 (parser-death) raw string `//` hides the conditional tuning",
            COMPLIANT_MANAGER,
            RAW_STRING_HANDLER,
            COMPLIANT_SYSTEM,
            {"tts-defaults"},
        ),
        # ── green arms: equivalent spellings must not be dragged into red ──
        (
            "green: defaults spelled inline (`?: 1.0`) instead of the resolver",
            COMPLIANT_MANAGER,
            INLINE_DEFAULT_HANDLER,
            COMPLIANT_SYSTEM,
            set(),
        ),
    ]
    ok = True
    for case in cases:
        label, manager, handler, system, expected = case[:5]
        quick = case[5] if len(case) > 5 else COMPLIANT_QUICK
        root = tempfile.mkdtemp(prefix="ttsgate-")
        try:
            write_fixture(root, MANAGER_REL, manager)
            write_fixture(root, HANDLER_REL, handler)
            write_fixture(root, SYSTEM_SPEECH_REL, system)
            write_fixture(root, QUICK_TEST_REL, quick)
            failures = run(root, verbose=False)
            got = set()
            for f in failures:
                m = re.search(r"\[(tts-[a-z-]+)\]", f)
                if m:
                    got.add(m.group(1))
            passed = got == expected
            print(
                "[{}] selftest: {} (expected {}, got {})".format(
                    "PASS" if passed else "FAIL",
                    label,
                    sorted(expected) if expected else "green",
                    sorted(got) if got else "green",
                )
            )
            if not passed:
                for f in failures:
                    print("        %s" % f)
            ok = ok and passed
        finally:
            shutil.rmtree(root, ignore_errors=True)

    # A tree the lexer cannot read must be a violation, never a silent pass.
    root = tempfile.mkdtemp(prefix="ttsgate-")
    try:
        write_fixture(root, MANAGER_REL, COMPLIANT_MANAGER + '\nval broken = "unterminated\n')
        write_fixture(root, HANDLER_REL, COMPLIANT_HANDLER)
        write_fixture(root, SYSTEM_SPEECH_REL, COMPLIANT_SYSTEM)
        write_fixture(root, QUICK_TEST_REL, COMPLIANT_QUICK)
        got = {m.group(1) for f in run(root, verbose=False)
               for m in [re.search(r"\[(tts-[a-z-]+)\]", f)] if m}
        passed = got == {"tts-files"}
        print("[{}] selftest: unparseable source is red (fail-closed) (got {})".format(
            "PASS" if passed else "FAIL", sorted(got) if got else "green"))
        ok = ok and passed
    finally:
        shutil.rmtree(root, ignore_errors=True)

    print("tts_voice_and_sanitizer_check selftest: "
          + ("TTS-VOICE GATE ALL ARMS MATCH" if ok else "FAILED"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    failures = run(args.root)
    if failures:
        print("tts_voice_and_sanitizer_check: FAIL (%d violation(s))" % len(failures))
        return 1
    print("tts_voice_and_sanitizer_check: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
