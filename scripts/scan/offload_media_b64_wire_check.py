#!/usr/bin/env python3
"""
offload_media_b64_wire_check — deep-audit-1022 FIX-A gate
                              + deep-audit-1041 A-06 extension.

Cross-process base64 wire contract (worker -> result.json -> main process):
  - Producer side (ModelExecutionService.kt, media_files encode sites) must use
    android.util.Base64.NO_WRAP, not Base64.DEFAULT (DEFAULT wraps at 76 chars
    + trailing newline).
  - Consumer side (ProviderExecutionGateway.kt) must use the MIME decoder
    (java.util.Base64.getMimeDecoder()), NOT the strict RFC 4648 decoder
    (java.util.Base64.getDecoder()), which throws IllegalArgumentException on
    any wrapped payload.

[dv21-A06] The same contract, one layer out. The original gate only knew about
the gateway and the worker's encode sites, so the OTHER consumers of the same
wire were free to (and did) hand-roll a different decoder:
  - ModelUseOffloadHandler.kt  : bare `Base64.decode(b64, Base64.DEFAULT)`,
                                 unguarded inside `mapNotNull` -> one malformed
                                 attachment threw away the whole run's result;
  - ModelExecutionService.kt   : the streaming entry + parseContentPartsJson
                                 used the STRICT decoder (wrapped payload threw
                                 out of the run, or silently dropped the image);
  - ChatStreamJsonl.kt         : strict decoder, unguarded, out of the chunk
                                 reader.
The rule added here, stated as a decision rather than a spelling:

  C3  no consumer of the offload wire may use the strict RFC 4648 decoder;
  C4  every decode site in those files is either routed through the shared
      `OffloadWireBase64.decode(...)` entry OR lexically inside an ABSORBING
      `try { }` / `runCatching { }` guard — a guard whose failure stays a value
      (`catch (e: T) { throw e }` and `runCatching { … }.getOrThrow()` are
      decorative: the same payload escapes);
  C5  the shared entry itself exists, is tolerant (MIME), cannot throw, and the
      decode inside it is really inside that guard (not in a sibling branch).

C1 (gateway) is unchanged in intent and deliberately keeps its own literal
`java.util.Base64.getMimeDecoder()` requirement, so the gateway is out of the
C3/C4 scope — it is already pinned by C1. What C1 now ALSO requires is that the
bytes it produces are the bytes that leave: `.decode(<constant>)` produces an
empty payload, and `…getOrNull()?.let { ByteArray(0) }` throws them away one
step later.

All checks run on the shared lexer's offset-preserving views
(`_kotlin_lexer.mask_code` for structure, `mask_comments` when an anchor carries
a string literal). Base64 flags, decode arguments, guard spans and `try`/`catch`
absorption are read structurally (`_kotlin_ast`), not by substring.

Usage: python3 offload_media_b64_wire_check.py <repo_root>
       python3 offload_media_b64_wire_check.py --selftest
Exit: 0 = pass, 1 = violation, 2 = usage error.
"""
import os
import re
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_value,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    expr_end,
    path_feasible,
)

OFFLOAD = os.path.join(
    "src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app",
    "sandbox", "offload")
GATEWAY_REL = os.path.join(OFFLOAD, "ProviderExecutionGateway.kt")
SERVICE_REL = os.path.join(OFFLOAD, "ModelExecutionService.kt")
HANDLER_REL = os.path.join(OFFLOAD, "ModelUseOffloadHandler.kt")
JSONL_REL = os.path.join(OFFLOAD, "ChatStreamJsonl.kt")
HELPER_REL = os.path.join(OFFLOAD, "OffloadWireBase64.kt")

SELFTEST_MARK = "OFFLOAD_MEDIA_B64_SELFTEST=PASS"

# Code anchors (not comment anchors): these appear in executable lines only.
MEDIA_ENCODE_ANCHOR = 'put("data", android.util.Base64.encodeToString('
STRICT_DECODER = "Base64.getDecoder()"
MIME_DECODER = "java.util.Base64.getMimeDecoder()"

# [dv21-A06]
HELPER_CALL = "OffloadWireBase64.decode("
HELPER_OBJECT = "object OffloadWireBase64"
HELPER_SIGNATURE = "fun decode(b64: String): ByteArray?"
HELPER_GUARD = "runCatching"
# Every consumer file must still decode something — a file that stopped
# decoding entirely means the anchor moved, not that the contract holds.
CONSUMERS = (HANDLER_REL, SERVICE_REL, JSONL_REL)

# A decode site: the strict/basic/MIME decoder paths, the android one-shot
# decoder, or the shared entry itself. `encodeToString` is not a decode site.
DECODE_SITE_RE = re.compile(
    r"\b(?:android\.util\.|java\.util\.)?Base64\s*\.\s*"
    r"(?:getDecoder|getMimeDecoder|getUrlDecoder)\s*\(\s*\)\s*\.\s*decode\s*\("
    r"|\bBase64\s*\.\s*decode\s*\("
    r"|\bOffloadWireBase64\s*\.\s*decode\s*\("
)

# Abort-shaped fallbacks: a decode miss must fall back, never end the stream.
ABORT_RE = re.compile(
    r"\bthrow\b|\!\!|\berror\s*\(|\brequire(?:NotNull)?\s*\(|\bcheck(?:NotNull)?\s*\("
    r"|\.\s*(?:getOrThrow|throwOnFailure)\s*\(|\bTODO\s*\(")

# Zeroing an already-decoded payload (or deriving a zero-length one).
ZERO_BYTES_RE = re.compile(r"\bByteArray\s*\(\s*0\s*\)")


def read(rel):
    with open(rel, "r", encoding="utf-8") as f:
        return f.read()


def views(src):
    """(code, literals) — both offset-preserving. Raises KotlinLexError."""
    return mask_code(src), mask_comments(src)


def find_close(text, open_idx, opener, closer):
    """Index of the [closer] matching text[open_idx], or -1."""
    if opener == "(" and closer == ")":
        return match_bracket(text, open_idx)
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == opener:
            depth += 1
        elif text[i] == closer:
            depth -= 1
            if depth == 0:
                return i
    return -1


def statement_tail(text, start, limit=600):
    """Text from [start] to the end of the statement carrying it — a top-level
    `,` / `;` / newline, or a closing bracket that ends the enclosing expression."""
    depth = 0
    for i in range(start, min(len(text), start + limit)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return text[start:i]
            depth -= 1
        elif c in ",;\n" and depth == 0:
            return text[start:i]
    return text[start:start + limit]


# ── guards ────────────────────────────────────────────────────────────────

def _catch_var_and_body(code, try_close):
    """(var_name, body) of the `catch (v: T) { … }` that follows a try body, or
    (None, None) when there is no catch at all."""
    m = re.match(r"\s*catch\s*\(\s*([A-Za-z_]\w*)\s*:[^)]*\)\s*\{", code[try_close + 1:])
    if not m:
        return None, None
    brace = try_close + m.end()
    close = match_bracket(code, brace - 1)
    body = code[brace:close] if close > 0 else code[brace:]
    return m.group(1), body


def _rethrows_same(body, var):
    """True when the catch body rethrows the exception it just caught — the
    guard is decorative (`catch (e: Throwable) { throw e }`).

    Throwing a DIFFERENT, typed error (`throw AudioInputError(...)`) is the
    documented "refuse instead of guess" shape and stays legal.
    """
    if not var:
        return False
    for m in re.finditer(r"(?<![\w`])throw\b", body):
        tail = statement_tail(body, m.end()).strip()
        if re.match(re.escape(var) + r"\b", tail):
            return True
    return False


def guard_regions(code):
    """[(start, end, absorbing)] for every `try { … }` / `runCatching { … }`
    body. `absorbing` = the failure stays a value: a `try` whose catch does not
    rethrow the same exception, or a `runCatching` not closed with
    `.getOrThrow()` / `.throwOnFailure()`."""
    out = []
    for m in re.finditer(r"\b(try|runCatching)\s*\{", code):
        open_idx = m.end() - 1
        close = match_bracket(code, open_idx)
        if close < 0:
            continue
        if m.group(1) == "runCatching":
            suffix = code[close + 1: close + 48]
            absorbing = not re.search(
                r"\.\s*(?:getOrThrow|throwOnFailure)\s*\(", suffix)
        else:
            var, body = _catch_var_and_body(code, close)
            absorbing = body is not None and not _rethrows_same(body, var)
        out.append((open_idx, close + 1, absorbing))
    return out


def innermost_region(regions, pos):
    inside = [r for r in regions if r[0] <= pos < r[1]]
    if not inside:
        return None
    return min(inside, key=lambda r: r[1] - r[0])


_EMPTY_ARG_CALLS = re.compile(
    r"\.\s*(?:take|takeLast|substring|subSequence|slice|copyOfRange)\s*\(")


def decode_arg_reason(arg):
    """Why the value handed to `.decode(…)` cannot be the wire payload."""
    e = strip_parens(arg.strip())
    if not e:
        return ("the decode argument is a constant / literal (it is masked away) — "
                "every payload decodes to zero bytes")
    const = const_value(e)
    if const is not None and (const[0] == "null" or
                              (const[0] == "str" and const[1].strip() == "")):
        return ("the decode argument is a constant (`%s`) — every payload decodes "
                "to zero bytes" % " ".join(e.split())[:48])
    for m in _EMPTY_ARG_CALLS.finditer(e):
        args = call_args(e, m.end() - 1)
        if not args:
            continue
        first = const_value(strip_parens(e[args[0][0]:args[0][1]]))
        if first is not None and first[0] == "num" and first[1] == 0:
            return ("the decode argument is a zero-length derivation (`%s`) — it "
                    "reads the wire field and then discards every byte"
                    % " ".join(e.split())[:48])
    if not re.search(r"[A-Za-z_]\w*", e):
        return ("the decode argument carries no value at all (`%s`)"
                % " ".join(e.split())[:48])
    return None


def _skip_catch_clauses(code, pos):
    """From `pos` (just past a `try` body's `}`) skip its `catch (…) { … }` /
    `finally { … }` clauses and return the offset after the last one. A `try`
    guard's value legitimately comes from its catch (`catch { ByteArray(0) }`
    is the documented degrade path), so the post-guard window starts after it."""
    while True:
        m = re.match(r"\s*(?:catch\s*\([^)]*\)|finally)\s*\{", code[pos:])
        if not m:
            return pos
        brace = pos + m.end() - 1
        close = match_bracket(code, brace)
        if close < 0:
            return pos
        pos = close + 1


def post_guard_zeroing(code, guard_start, guard_close, limit=200):
    """The value produced by the guard is replaced by an empty array one step
    later (`…getOrNull()?.let { ByteArray(0) }`)."""
    if code[:guard_start].rstrip().endswith("try"):
        guard_close = _skip_catch_clauses(code, guard_close)
    # stop at the enclosing statement boundary
    stop = statement_tail(code, guard_close, limit)
    seg = code[guard_close:guard_close + len(stop)]
    if ZERO_BYTES_RE.search(seg):
        return "ByteArray(0)"
    for m in re.finditer(r"\.\s*(?:let|run|also|apply)\s*\{", seg):
        close = match_bracket(seg, m.end() - 1)
        if close < 0:
            continue
        body = seg[m.end():close]
        spans = stmt_spans(body)
        tail = body[spans[-1][0]:spans[-1][1]].strip() if spans else body.strip()
        if ZERO_BYTES_RE.search(tail) or re.match(
                r"^\.?\s*(?:fill\s*\(\s*0|copyOf\s*\(\s*0|take\s*\(\s*0)\b", tail):
            return tail
    return None


# ── C2: producer flags ────────────────────────────────────────────────────

_FLAG_TOKEN_RE = re.compile(r"[A-Za-z_][\w.]*\s*\.\s*([A-Z_][A-Z0-9_]*)\b")


def flag_tokens(arg):
    """Every base64 modifier token of the flag expression, in ANY spelling.

    `android.util.Base64.NO_WRAP`                     -> ['NO_WRAP']
    `Base64.NO_WRAP or Base64.URL_SAFE`               -> ['NO_WRAP', 'URL_SAFE']
    `Base64.URL_SAFE + android.util.Base64.NO_WRAP`   -> ['URL_SAFE', 'NO_WRAP']
    Returns [] when the argument is not a pure flag expression (a variable, a
    call …) so the caller fail-closes instead of trusting a spelling.

    The pre-fix tokenizer split on `or` and then took `.split('.')[-1]`, so a
    `+`-joined pair collapsed to its RIGHT operand: `URL_SAFE + NO_WRAP` read as
    `['NO_WRAP']` while rewriting the alphabet to `-`/`_` (silent bit shift in
    every generated image).
    """
    rest = _FLAG_TOKEN_RE.sub(" ", arg)
    if re.sub(r"[\s()+|]|\bor\b", "", rest):
        return []
    return [m.group(1) for m in _FLAG_TOKEN_RE.finditer(arg)]


# ── C3/C4: consumers ──────────────────────────────────────────────────────

def check_consumers(root, failures):
    """C3/C4 over the offload-wire consumers."""
    for rel in CONSUMERS:
        label = os.path.basename(rel)
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            failures.append("%s: file missing at %s" % (label, path))
            return
        try:
            code, lit = views(read(path))
        except KotlinLexError as exc:
            failures.append("%s: does not lex (%s) — fail-closed: the decode sites "
                            "cannot be judged on an unmasked view" % (label, exc))
            continue

        # C3 — the strict RFC 4648 decoder has no business on this wire.
        for hit in re.finditer(re.escape(STRICT_DECODER), code):
            line = code[: hit.start()].count("\n") + 1
            failures.append(
                "%s:%d uses the strict %s (throws on a wrapped RFC 2045 "
                "payload); decode through %s or java.util.Base64.getMimeDecoder()"
                % (label, line, STRICT_DECODER, HELPER_CALL.rstrip("(")))

        # C4 — every decode site is helper-routed or lexically inside an
        # ABSORBING guard.
        sites = list(DECODE_SITE_RE.finditer(code))
        if not sites:
            failures.append(
                "%s: no base64 decode site found in %d bytes — gate anchor is "
                "stale (update it) or the file stopped decoding the wire"
                % (label, len(code)))
            continue
        regions = guard_regions(code)
        for s in sites:
            if "OffloadWireBase64" in s.group(0):
                continue
            line = code[: s.start()].count("\n") + 1
            region = innermost_region(regions, s.start())
            if region is None:
                failures.append(
                    "%s:%d decodes base64 outside any guard — an unparseable "
                    "payload throws out of the run (route it through %s or wrap it "
                    "in try/runCatching)" % (label, line, HELPER_CALL.rstrip("(")))
                continue
            if not region[2]:
                failures.append(
                    "%s:%d sits in a guard that RETHROWS the same exception "
                    "(`catch (e: T) { throw e }` / `.getOrThrow()`) — the guard is "
                    "decorative: one malformed payload still escapes and discards "
                    "the whole run's result" % (label, line))
            # the decoded bytes must be what leaves the guard
            zero = post_guard_zeroing(code, region[0], region[1])
            if zero:
                failures.append(
                    "%s:%d replaces the decoded bytes right after the guard (`%s`) — "
                    "every generated image/audio lands empty"
                    % (label, line, " ".join(zero.split())[:48]))

        # C4b [wide-1024] — the chunk reader's miss path is a FALLBACK, never an
        # abort. `?: throw …` reads the same two substrings the old rule wanted
        # while turning one empty/malformed b64 block into a dead stream;
        # `?: error("…")` is the same abort spelled through kotlin.error.
        if rel == JSONL_REL:
            for s in re.finditer(re.escape(HELPER_CALL), code):
                close = find_close(code, s.end() - 1, "(", ")")
                tail = statement_tail(code, close + 1 if close > 0 else s.end())
                if ABORT_RE.search(tail):
                    line = code[: s.start()].count("\n") + 1
                    failures.append(
                        "ChatStreamJsonl.kt:%d aborts on a decode miss (`%s`) — "
                        "`OffloadWireBase64.decode(b64)` answers null for an empty or "
                        "truncated payload, and the chunk reader must fall back "
                        "(?: ByteArray(0)) instead of ending the stream"
                        % (line, " ".join(tail.split())[:48]))


# ── C5: the shared entry ──────────────────────────────────────────────────

def check_helper(root, failures):
    """C5 — the shared decode entry exists, cannot throw, and the decode inside
    it really sits inside that guard."""
    path = os.path.join(root, HELPER_REL)
    if not os.path.exists(path):
        failures.append(
            "OffloadWireBase64.kt: file missing at %s (the single decode entry "
            "the consumers are required to route through)" % path)
        return
    try:
        code, _lit = views(read(path))
    except KotlinLexError as exc:
        failures.append("OffloadWireBase64.kt: does not lex (%s) — fail-closed" % exc)
        return
    for anchor in (HELPER_OBJECT, HELPER_SIGNATURE, MIME_DECODER, HELPER_GUARD):
        if anchor not in code:
            failures.append(
                "OffloadWireBase64.kt: missing `%s` — the shared entry must stay "
                "tolerant (MIME) and non-throwing" % anchor)
    if STRICT_DECODER in code:
        failures.append("OffloadWireBase64.kt: uses the strict %s" % STRICT_DECODER)
    # C5 dataflow [wide-1024] — `runCatching` only helps if the failure stays a
    # value. `.getOrThrow()` keeps both substrings the old rule looked for
    # (`getMimeDecoder().decode(` and `runCatching`) while re-raising exactly the
    # IllegalArgumentException this entry exists to absorb.
    for bad_re, what in ((r"\bgetOrThrow\s*\(", "`getOrThrow()`"),
                         (r"!!", "`!!`"),
                         (r"\.\s*get\s*\(\s*\)", "`get()`")):
        if re.search(bad_re, code):
            failures.append(
                "OffloadWireBase64.kt: the guard ends in %s — the shared decode entry "
                "must never throw; a truncated / mis-padded payload has to come back "
                "as null" % what)
    if re.search(r"getOrNull\s*\(", code) is None:
        failures.append(
            "OffloadWireBase64.kt: the decode guard is not consumed through "
            "`getOrNull()` (fail-closed) — the shared entry's contract is `null` on a "
            "bad payload, and only a null-returning accessor implements it")

    # C5 placement [batch6 t12] — the file may CONTAIN `runCatching` and a MIME
    # decode while the decode that actually serves non-empty payloads sits in a
    # bare sibling branch:
    #   if (b64.isNotEmpty()) getMimeDecoder().decode(b64)
    #   else runCatching { getMimeDecoder().decode(b64) }.getOrNull()
    sites = list(DECODE_SITE_RE.finditer(code))
    regions = guard_regions(code)
    if not sites:
        failures.append("OffloadWireBase64.kt: no decode site (fail-closed) — the "
                        "shared entry's body is unreadable")
    for s in sites:
        line = code[: s.start()].count("\n") + 1
        if not path_feasible(code, s.start()):
            failures.append("OffloadWireBase64.kt:%d decodes on an unreachable path "
                            "(fail-closed: the guard is dead code)" % line)
            continue
        region = innermost_region(regions, s.start())
        if region is None or not region[2]:
            failures.append(
                "OffloadWireBase64.kt:%d the decode call in the shared entry is %s — "
                "`runCatching { … }` has to wrap the decode that serves every payload, "
                "otherwise one truncated block throws out of all its callers"
                % (line, "outside any guard" if region is None
                   else "inside a guard that rethrows"))
            continue
        args = call_args(code, s.end() - 1)
        if not args:
            failures.append("OffloadWireBase64.kt:%d decode(…) has no readable "
                            "argument (fail-closed)" % line)
            continue
        reason = decode_arg_reason(code[args[0][0]:args[0][1]])
        if reason:
            failures.append("OffloadWireBase64.kt:%d %s" % (line, reason))


def check_tree(root):
    """Return (ok, [failure messages])."""
    failures = []
    gw_path = os.path.join(root, GATEWAY_REL)
    svc_path = os.path.join(root, SERVICE_REL)
    for p, label in ((gw_path, "ProviderExecutionGateway.kt"),
                     (svc_path, "ModelExecutionService.kt")):
        if not os.path.exists(p):
            failures.append("%s: file missing at %s" % (label, p))
            return False, failures

    # 1) Consumer side: gateway must not use the strict RFC 4648 decoder.
    gw_code, gw_lit = views(read(gw_path))
    if STRICT_DECODER in gw_lit:
        failures.append("gateway uses strict %s (throws on wrapped payload); "
                        "use java.util.Base64.getMimeDecoder()" % STRICT_DECODER)
    # 1b) [wide-1024 C1] …and it must actually DECODE with it, on the payload
    #     it was handed. `runCatching { getMimeDecoder(); ByteArray(0) }`
    #     satisfies every substring the old rule wanted while carrying zero
    #     decoded bytes; `.decode("")` / `.decode(b64.take(0))` decode a
    #     constant. Dataflow, not spelling.
    gw_regions = guard_regions(gw_code)
    mime_sites = list(re.finditer(re.escape(MIME_DECODER), gw_code))
    if not mime_sites:
        failures.append("gateway does not use java.util.Base64.getMimeDecoder() "
                        "(MIME decoder required for RFC 2045 wrapped input)")
    for m in mime_sites:
        line = gw_code[: m.start()].count("\n") + 1
        chain = re.match(r"\s*\.\s*decode\s*\(", gw_code[m.end():])
        if chain is None:
            failures.append(
                "ProviderExecutionGateway.kt:%d constructs the MIME decoder but never "
                "decodes with it — the media payload must be produced by "
                "`getMimeDecoder().decode(b64)`, not by a literal next to it" % line)
            continue
        open_paren = m.end() + chain.end() - 1
        args = call_args(gw_code, open_paren)
        if not args:
            failures.append("ProviderExecutionGateway.kt:%d: `.decode(` arguments do "
                            "not balance (fail-closed)" % line)
            continue
        reason = decode_arg_reason(gw_code[args[0][0]:args[0][1]])
        if reason:
            failures.append("ProviderExecutionGateway.kt:%d %s" % (line, reason))
            continue
        close = find_close(gw_code, open_paren, "(", ")")
        if close < 0:
            failures.append("ProviderExecutionGateway.kt:%d: unbalanced "
                            ".decode( … ) on the media wire (fail-closed)" % line)
            continue
        guard = innermost_region(gw_regions, m.start())
        if guard:
            if not guard[2]:
                failures.append(
                    "ProviderExecutionGateway.kt:%d wraps the media decode in a guard "
                    "that rethrows (`.getOrThrow()` / `catch { throw e }`) — a "
                    "malformed attachment still kills the whole run" % line)
            if gw_code[close + 1:guard[1] - 1].strip():
                failures.append(
                    "ProviderExecutionGateway.kt:%d decodes the payload then drops it "
                    "before the guard ends (the assignment gets something else) — every "
                    "generated image/audio would land as a zero-byte file" % line)
            zero = post_guard_zeroing(gw_code, guard[0], guard[1])
            if zero:
                failures.append(
                    "ProviderExecutionGateway.kt:%d replaces the decoded bytes after "
                    "the guard (`%s`) — every generated image/audio lands empty"
                    % (line, " ".join(zero.split())[:48]))

    # 2) Producer side: the media_files encode sites must use NO_WRAP — and the
    #    flag expression must be exactly that, in any spelling. [wide-1024 C2]
    #    `NO_WRAP or URL_SAFE` used to pass both substring tests while rewriting
    #    the alphabet to `-`/`_`, which the consumer's MIME decoder silently
    #    DISCARDS (not an error: a quiet bit shift in every generated image).
    svc_code, svc_lit = views(read(svc_path))
    sites = [ln for ln in svc_lit.splitlines() if MEDIA_ENCODE_ANCHOR in ln]
    if len(sites) == 0:
        failures.append("no media encode sites found (anchor %r missing) — "
                        "gate is stale, update the anchor" % MEDIA_ENCODE_ANCHOR)
    encode_sites = list(re.finditer(r"\bBase64\s*\.\s*encodeToString\s*\(", svc_code))
    if not encode_sites:
        failures.append("ModelExecutionService.kt: no Base64.encodeToString( call site "
                        "found (fail-closed) — the media wire producer is unreadable")
    for m in encode_sites:
        lineno = svc_code[: m.start()].count("\n") + 1
        args = call_args(svc_code, m.end() - 1)
        if not args or len(args) < 2:
            failures.append("ModelExecutionService.kt:%d: encodeToString has no flag "
                            "argument (fail-closed) — NO_WRAP is a wire contract" % lineno)
            continue
        flags = flag_tokens(svc_code[args[-1][0]:args[-1][1]])
        bad = [t for t in flags if t != "NO_WRAP"]
        if not flags or bad:
            detail = ("Base64.DEFAULT wraps at 76 chars + a newline" if "DEFAULT" in bad
                      else "a non-NO_WRAP modifier rewrites the base64 alphabet, and the "
                           "consumer's MIME decoder silently drops those characters")
            failures.append(
                "ModelExecutionService.kt:%d media encode flags are %r — the flag "
                "expression must be exactly NO_WRAP (%s)"
                % (lineno, " or ".join(flags) or "(unreadable)", detail))

    # 3-5) [dv21-A06] the other consumers of the same wire.
    check_consumers(root, failures)
    check_helper(root, failures)

    return len(failures) == 0, failures


HELPER_COMPLIANT = (
    "package p\n\n"
    "object OffloadWireBase64 {\n"
    "    fun decode(b64: String): ByteArray? =\n"
    "        if (b64.isEmpty()) null\n"
    "        else runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()\n"
    "}\n"
)

FIXTURE_COMPLIANT = {
    GATEWAY_REL: (
        "class G {\n"
        "    val data = runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()\n"
        "        ?: return@mapNotNull null\n"
        "}\n"
    ),
    SERVICE_REL: (
        "class S {\n"
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
        "    val a = OffloadWireBase64.decode(x) ?: ByteArray(0)\n"
        "    val b = OffloadWireBase64.decode(y) ?: ByteArray(0)\n"
        "}\n"
    ),
    HANDLER_REL: (
        "class H {\n"
        "    // Base64.decode(b64, Base64.DEFAULT) in prose must not be a decode site\n"
        "    val data = OffloadWireBase64.decode(b64) ?: return@mapNotNull null\n"
        "    val kept = try { Base64.decode(other, Base64.DEFAULT) } catch (e: Throwable) { null }\n"
        "}\n"
    ),
    JSONL_REL: (
        "class J {\n"
        "    val data = OffloadWireBase64.decode(b64) ?: ByteArray(0)\n"
        "}\n"
    ),
    HELPER_REL: HELPER_COMPLIANT,
}

FIXTURE_VIOLATION = {
    GATEWAY_REL: (
        "class G {\n"
        "    // comment mentioning java.util.Base64.getMimeDecoder() must not fool the gate\n"
        "    val data = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()\n"
        "}\n"
    ),
    SERVICE_REL: (
        "class S {\n"
        '    // put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.DEFAULT))\n'
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
        "}\n"
    ),
    HANDLER_REL: FIXTURE_COMPLIANT[HANDLER_REL],
    JSONL_REL: FIXTURE_COMPLIANT[JSONL_REL],
    HELPER_REL: HELPER_COMPLIANT,
}


def fixture(**overrides):
    """FIXTURE_COMPLIANT with the named files replaced. Keyword names are the
    bare file constants (GATEWAY / SERVICE / HANDLER / JSONL / HELPER), mapped
    onto their repo-relative paths here so a caller cannot silently add a sixth
    bogus file (which is exactly what a name-vs-path mixup looked like)."""
    rel_by_name = {
        "GATEWAY": GATEWAY_REL,
        "SERVICE": SERVICE_REL,
        "HANDLER": HANDLER_REL,
        "JSONL": JSONL_REL,
        "HELPER": HELPER_REL,
    }
    files = dict(FIXTURE_COMPLIANT)
    for name, content in overrides.items():
        if name not in rel_by_name:
            raise AssertionError("unknown fixture target %r" % name)
        files[rel_by_name[name]] = content
    return files


def run_fixture(files, expect_ok, label, needle=None):
    import shutil
    import tempfile
    tmp = tempfile.mkdtemp(prefix="b64wire-gate-")
    try:
        for rel, content in files.items():
            path = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
        ok, failures = check_tree(tmp)
        if expect_ok and not ok:
            print("FAIL  %s: compliant fixture rejected: %s" % (label, failures))
            return False
        if not expect_ok and ok:
            print("FAIL  %s: violating fixture accepted" % label)
            return False
        if needle and not any(needle in f for f in failures):
            print("FAIL  %s: red on the WRONG criterion: %s" % (label, failures[:2]))
            return False
        print("PASS  %s" % label)
        return True
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def selftest():
    ok = True
    ok &= run_fixture(FIXTURE_COMPLIANT, True, "selftest: compliant fixture accepted")
    ok &= run_fixture(FIXTURE_VIOLATION, False, "selftest: violation fixture rejected")
    # Comment-anchored NO_WRAP in a commented-out line must NOT satisfy the producer check
    ok &= run_fixture({
        GATEWAY_REL: FIXTURE_COMPLIANT[GATEWAY_REL],
        SERVICE_REL: (
            "class S {\n"
            '    // put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
            "}\n"
        ),
        HANDLER_REL: FIXTURE_COMPLIANT[HANDLER_REL],
        JSONL_REL: FIXTURE_COMPLIANT[JSONL_REL],
        HELPER_REL: HELPER_COMPLIANT,
    }, False, "selftest: commented-out NO_WRAP does not satisfy producer check")
    # Comment-anchored getMimeDecoder must NOT satisfy the consumer check
    ok &= run_fixture({
        GATEWAY_REL: (
            "class G {\n"
            "    // java.util.Base64.getMimeDecoder()\n"
            "    val data = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()\n"
            "}\n"
        ),
        SERVICE_REL: FIXTURE_COMPLIANT[SERVICE_REL],
        HANDLER_REL: FIXTURE_COMPLIANT[HANDLER_REL],
        JSONL_REL: FIXTURE_COMPLIANT[JSONL_REL],
        HELPER_REL: HELPER_COMPLIANT,
    }, False, "selftest: commented-out getMimeDecoder does not satisfy consumer check")

    # ── [dv21-A06] one arm per new criterion ────────────────────────────────
    ok &= run_fixture(fixture(JSONL=(
        "class J {\n"
        "    val data = OffloadWireBase64.decode(b64) ?: ByteArray(0)\n"
        "    val old = java.util.Base64.getDecoder().decode(legacy)\n"
        "}\n"
    )), False, "selftest: C3 strict decoder on ChatStreamJsonl rejected")
    ok &= run_fixture(fixture(HANDLER=(
        "class H {\n"
        "    val data = Base64.decode(b64, Base64.DEFAULT)\n"
        "}\n"
    )), False, "selftest: C4 unguarded decode in ModelUseOffloadHandler rejected")
    ok &= run_fixture(fixture(SERVICE=(
        "class S {\n"
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
        "    val a = OffloadWireBase64.decode(x) ?: ByteArray(0)\n"
        "    val b = android.util.Base64.decode(y, android.util.Base64.DEFAULT)\n"
        "}\n"
    )), False, "selftest: C4 unguarded decode in ModelExecutionService rejected")
    ok &= run_fixture(fixture(HANDLER=(
        "class H {\n"
        "    val data = try { Base64.decode(b64, Base64.DEFAULT) }"
        " catch (e: IllegalArgumentException) { ByteArray(0) }\n"
        "}\n"
    )), True, "selftest: guarded decode accepted (criterion is not spelling-bound)")
    ok &= run_fixture(fixture(HANDLER=(
        "class H {\n"
        '    val msg = "Base64.decode(b64, Base64.DEFAULT)"\n'
        "    val data = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()\n"
        "}\n"
    )), True, "selftest: runCatching guard accepted, literal text is masked")
    ok &= run_fixture(fixture(JSONL=(
        "class J {\n"
        "    val data = ByteArray(0)\n"
        "}\n"
    )), False, "selftest: C4 stale anchor (no decode site) rejected")
    files = fixture()
    del files[HELPER_REL]
    ok &= run_fixture(files, False, "selftest: C5 missing helper rejected")
    ok &= run_fixture(fixture(HELPER=(
        "object OffloadWireBase64 {\n"
        "    fun decode(b64: String): ByteArray? = java.util.Base64.getDecoder().decode(b64)\n"
        "}\n"
    )), False, "selftest: C5 helper without MIME/runCatching rejected")

    # ── [wide-1024] 门瞎实锤 → 固化臂（每条 = 一份外部模型的见证，原样） ──────
    ok &= run_fixture(fixture(GATEWAY=(
        "class G {\n"
        "    val data = runCatching { java.util.Base64.getMimeDecoder(); ByteArray(0) }.getOrNull()\n"
        "        ?: return@mapNotNull null\n"
        "}\n"
    )), False, "selftest: C1 MIME decoder constructed but never consumed (wide-1024)")
    ok &= run_fixture(fixture(GATEWAY=(
        "class G {\n"
        "    val data = runCatching { java.util.Base64.getMimeDecoder().decode(b64); ByteArray(0) }\n"
        "        .getOrNull() ?: return@mapNotNull null\n"
        "}\n"
    )), False, "selftest: C1 decoded bytes discarded inside the guard (wide-1024)")
    ok &= run_fixture(fixture(SERVICE=(
        "class S {\n"
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE))\n'
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
        "    val a = OffloadWireBase64.decode(x) ?: ByteArray(0)\n"
        "    val b = OffloadWireBase64.decode(y) ?: ByteArray(0)\n"
        "}\n"
    )), False, "selftest: C2 NO_WRAP or URL_SAFE rejected (alphabet rewrite, wide-1024)")
    ok &= run_fixture(fixture(HELPER=(
        "object OffloadWireBase64 {\n"
        "    fun decode(b64: String): ByteArray? =\n"
        "        if (b64.isEmpty()) null\n"
        "        else runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrThrow()\n"
        "}\n"
    )), False, "selftest: C5 shared entry re-raises (getOrThrow, wide-1024)")
    ok &= run_fixture(fixture(JSONL=(
        "class J {\n"
        '    val data = (OffloadWireBase64.decode(b64) ?: throw IllegalArgumentException("bad b64")),\n'
        "}\n"
    )), False, "selftest: C4 ChatStreamJsonl throws on a decode miss (wide-1024)")

    # ── [batch6 t12] the six witnesses this wave added ──────────────────────
    # 11-1a: the decoder runs with a constant payload.
    ok &= run_fixture(fixture(GATEWAY=(
        "class G {\n"
        '    val data = runCatching { java.util.Base64.getMimeDecoder().decode("") }.getOrNull()\n'
        "        ?: return@mapNotNull null\n"
        "}\n"
    )), False, "selftest: C1 decode of a constant payload rejected",
        needle="the decode argument is a constant")
    # 11-1b: …or with a zero-length derivation of the real payload.
    ok &= run_fixture(fixture(GATEWAY=(
        "class G {\n"
        "    val data = runCatching { java.util.Base64.getMimeDecoder().decode(b64.take(0)) }.getOrNull()\n"
        "        ?: return@mapNotNull null\n"
        "}\n"
    )), False, "selftest: C1 decode of b64.take(0) rejected",
        needle="zero-length derivation")
    # 11-2: the bytes are replaced one step AFTER the guard closes.
    ok &= run_fixture(fixture(GATEWAY=(
        "class G {\n"
        "    val data = runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()"
        "?.let { ByteArray(0) }\n"
        "        ?: return@mapNotNull null\n"
        "}\n"
    )), False, "selftest: C1 bytes zeroed after the guard rejected",
        needle="replaces the decoded bytes")
    # 11-3: `+`-joined flags — the old tokenizer kept only the right operand.
    ok &= run_fixture(fixture(SERVICE=(
        "class S {\n"
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.URL_SAFE + android.util.Base64.NO_WRAP))\n'
        '    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.NO_WRAP))\n'
        "    val a = OffloadWireBase64.decode(x) ?: ByteArray(0)\n"
        "    val b = OffloadWireBase64.decode(y) ?: ByteArray(0)\n"
        "}\n"
    )), False, "selftest: C2 `URL_SAFE + NO_WRAP` rejected",
        needle="media encode flags are")
    # 11-4: kotlin.error() is a throw spelled as a call.
    ok &= run_fixture(fixture(JSONL=(
        "class J {\n"
        '    val data = OffloadWireBase64.decode(b64) ?: error("bad b64"),\n'
        "}\n"
    )), False, "selftest: C4b `?: error(…)` abort rejected",
        needle="aborts on a decode miss")
    # 11-5: the guard only covers the branch that never runs.
    ok &= run_fixture(fixture(HELPER=(
        "object OffloadWireBase64 {\n"
        "    fun decode(b64: String): ByteArray? =\n"
        "        if (b64.isNotEmpty()) java.util.Base64.getMimeDecoder().decode(b64)\n"
        "        else runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()\n"
        "}\n"
    )), False, "selftest: C5 guard on the dead branch only rejected",
        needle="outside any guard")
    # 11-6: a try/catch that rethrows the exception it caught is decorative.
    ok &= run_fixture(fixture(HANDLER=(
        "class H {\n"
        "    val data = try { Base64.decode(b64, Base64.DEFAULT) }"
        " catch (e: Throwable) { throw e }\n"
        "}\n"
    )), False, "selftest: C4 rethrowing guard rejected",
        needle="guard that RETHROWS the same exception")
    # negative control for the same criterion: a typed refusal stays legal.
    ok &= run_fixture(fixture(HANDLER=(
        "class H {\n"
        "    val data = try { Base64.decode(b64, Base64.DEFAULT) }"
        " catch (e: IllegalArgumentException) { throw AudioInputError(\"bad base64\") }\n"
        "}\n"
    )), True, "selftest: typed refusal in catch stays legal")
    if ok:
        print(SELFTEST_MARK)
    return 0 if ok else 1


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        sys.exit(selftest())
    root = None
    if "--root" in args:
        i = args.index("--root")
        if i + 1 < len(args):
            root = args[i + 1]
    elif args and not args[0].startswith("-"):
        root = args[0]
    if not root:
        print("usage: offload_media_b64_wire_check.py <repo_root> | --selftest",
              file=sys.stderr)
        sys.exit(2)
    ok, failures = check_tree(root)
    for f in failures:
        print("VIOLATION: %s" % f)
    if ok:
        _code, lit = views(read(os.path.join(root, SERVICE_REL)))
        n = len([l for l in lit.splitlines() if MEDIA_ENCODE_ANCHOR in l])
        print("OFFLOAD_MEDIA_B64_WIRE: OK (%d media encode sites NO_WRAP, consumer "
              "MIME decoder)" % n)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
