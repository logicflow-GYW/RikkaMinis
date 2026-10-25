#!/usr/bin/env python3
"""
Gate: FIX-I (deep-audit-1024 ISSUE-09) + FIX-C (deep-audit-1033 ISSUE-03) —
every path-taking offload handler must route a relative path through
[resolveMaybeRelative] (the single join implementation defined in
`ConfigOffloadHandler.kt`) instead of keeping private, drifting copies and
cwd-less call chains.

The 1024 bug (one function, contradicting itself):
  - `cmdRun`'s `--output` still refused any relative path with
    `invalid_output_path`, while the SAME `cmdRun` forwarded `request.cwd` for
    `--input` / `--system-file`;
  - `readLinuxPath` kept a hand-written inline join (`$cwd/$linuxPath`), which
    `BrowserUseOffloadHandler.kt`'s KDoc forbids ("single join implementation");
  - the `image_url` chain (`parseMessages` -> `parseMessageArray` ->
    `resolveImageUrl`) had no `cwd` parameter, so a relative image path inside
    `prompt.json` (`{"type":"image_url","image_url":{"url":"chart.png"}}`)
    threw `ImageInputError`.

The 1033 bug (the last handler that never adopted the contract):
  - `SpeechOffloadHandler.cmdTranscribe` was dispatched as
    `cmdTranscribe(args, request.sessionId)` — `request.cwd` dropped — and
    `resolveSourcePath` opened with
    `if (trimmed.isEmpty() || !trimmed.startsWith("/")) return null`, so
    `android-speech transcribe --source note.wav` from the shell's cwd always
    came back `file_exists:false` / `resolved_host_path:""`.

Checks (every one is anchored on CODE, never on comment text; the structural
work runs on the shared lexer projection `_kotlin_lexer.mask_code` and the
value/flow questions are answered with `_kotlin_valueflow`, so a string literal
cannot move a boundary and a criterion can no longer be satisfied by merely
naming the right helper):
  ModelUseOffloadHandler.kt
    C1  `readLinuxPath(...)`    calls `resolveMaybeRelative(` FED the cwd, and
                                the joined value (not the raw argument) is the
                                one handed to `sessionAwareHostFile(`.
    C2  `cmdRun(...)`           the value bound to `outputPath` IS the join's
                                result — `raw.also { resolveMaybeRelative(…) }`
                                keeps the name and returns the raw spelling.
    C3  `resolveImageUrl(...)`  calls `resolveMaybeRelative(` FED the cwd.
    C4  the file keeps NO inline cwd join (`"$cwd...` string template) — the
        single implementation may only live in `ConfigOffloadHandler.kt`.
  SpeechOffloadHandler.kt
    S1  `resolveSourcePath(...)`  calls `resolveMaybeRelative(` FED the cwd and
                                  maps the joined value into the host tree.
    S2  every `cmdTranscribe(...)` / `resolveSourcePath(...)` occurrence in the
        file carries a cwd VALUE in its argument list — the parameter NAME is
        not a value (`cmdTranscribe(args, sid, cwd = "")` passes nothing), and
        `request.cwd` / a local derived from it both count.
    S3  `resolveSourcePath` no longer decides relative-vs-absolute itself
        (no `startsWith("/")` in its body) and the file carries no hand-written
        `"$cwd` join.
    S4  privacy edge: inside `cmdTranscribe`, the `--source` classifier runs
        BEFORE the microphone permission gate, and the explicit non-mic branch
        returns a NON-zero result carrying an error payload — on a path that
        actually executes (`if (1 == 2) return …` is dead code, whatever the
        constant looks like: the guard is folded with `guard_feasible`).
  ShizukuOffloadHandler.kt
    Z1  `shizukuHostPath(...)`  joins through `resolveMaybeRelative(` FED the
                                cwd, and the joined guest path is what gets
                                mapped (not a re-typed `raw.trim()`).
    Z2  the cwd and the SESSION both reach the mapping, and the returned value
        comes from `PRootKernel.resolvePathForSession` — `resolvePathForSession(
        null, raw.trim(), …)` keeps all three call literals and loses both the
        session scoping and the join.
    Z3  `shizukuHostPath` resolves through `PRootKernel.resolvePathForSession`
        — a guest path cannot reach the Shizuku service's host filesystem.
    Z4  relative-vs-absolute is the join's decision, and the join is single
        source.

Boundary (documented, not a bug): S4 pins the current literal shape. A refactor
that moves the error payload into a helper (e.g. `return notSupported(...)`)
reds the gate; that is fail-closed on purpose — the helper must then be named
in the criterion.

Usage: python3 scripts/scan/offload_relative_path_unify_check.py [repo_root]
       python3 scripts/scan/offload_relative_path_unify_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage
"""
import os
import re
import shutil
import sys
import tempfile

from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (
    function_body,
    last_write,
    match_bracket,
    split_top,
)
from _kotlin_valueflow import (
    code_view,
    expr_end,
    literal_view,
    path_feasible,
    value_sources,
)

SRC = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload"
TARGET_REL = SRC + "/ModelUseOffloadHandler.kt"
SPEECH_REL = SRC + "/SpeechOffloadHandler.kt"
# deep-audit-1034 A2 / ISSUE-03: the same single-source contract, third target —
# the Shizuku handler resolves the paths it hands to the privileged service.
SHIZUKU_REL = SRC + "/ShizukuOffloadHandler.kt"
# A Kotlin string template that joins a cwd itself — the shape that must not
# reappear in either target file (the join belongs to resolveMaybeRelative).
INLINE_JOIN = '"$cwd'
# `name = value` (but not `==`): a named argument, whose NAME is not its value.
NAMED_ARG = re.compile(r"^\s*([A-Za-z_]\w*)\s*=(?!=)\s*")


# ── views / spans (all structural work on the shared projection) ─────────────

def _fn(code, name):
    """(start, end) of the function body between its braces, or None."""
    fb = function_body(code, name)
    if fb is None:
        return None
    return fb[0], fb[1]


def _calls(code, name, scope=None):
    """Every CALL of `name` inside `scope` (a function's declaration is not a call).

    Returns [(open_paren_pos, [(arg_start, arg_end), …])] in absolute offsets.
    """
    lo, hi = scope if scope else (0, len(code))
    seg = code[lo:hi]
    out = []
    pattern = re.compile(r"(?<![\w`])" + re.escape(name) + r"\s*\(")
    for m in pattern.finditer(seg):
        before = seg[max(0, m.start() - 12):m.start()]
        if re.search(r"\bfun\s+$", before):
            continue                      # declaration, not a call site
        open_pos = lo + seg.index("(", m.start() + len(name))
        close = match_bracket(code, open_pos)
        if close < 0:
            continue
        inner = code[open_pos + 1:close]
        if not inner.strip():
            out.append((open_pos, []))
            continue
        out.append((open_pos, [(open_pos + 1 + a, open_pos + 1 + b)
                               for a, b in split_top(inner, (",",))]))
    return out


def _arg_text(code, span):
    return code[span[0]:span[1]].strip()


def _arg_value(code, span):
    """(name_or_None, value_text) — named arguments unwrapped."""
    text = code[span[0]:span[1]]
    m = NAMED_ARG.match(text)
    if m:
        return m.group(1), text[m.end():].strip()
    return None, text.strip()


def _reads_ident(code, scope, span, ident):
    """Does this argument's VALUE carry `ident`?

    The parameter NAME does not: `cmdTranscribe(args, sid, cwd = "")` passes an
    empty string while satisfying any `"cwd" in args` substring test.
    """
    _name, value = _arg_value(code, span)
    if not value:
        return False
    if re.search(r"(?<![\w`])" + re.escape(ident) + r"(?![\w`])", value):
        return True
    # one hop: `val dir = request.cwd` … `f(..., dir)`
    if scope is not None and re.fullmatch(r"[A-Za-z_]\w*", value):
        w = last_write(code[scope[0]:scope[1]], value)
        if w and re.search(r"(?<![\w`])" + re.escape(ident) + r"(?![\w`])", w[1]):
            return True
    return False


def _bound_to_code_result(code, scope, call_name):
    """Identifier bound to a call result (`val X = call(...)`), or None."""
    body = code[scope[0]:scope[1]]
    m = re.search(r"\b(?:val|var)\s+([A-Za-z_]\w*)[^=\n]*=\s*" + re.escape(call_name), body)
    return m.group(1) if m else None


def _return_exprs(code, scope):
    """Top-level `return <expr>` expressions of the function body (shared expr_end)."""
    body = code[scope[0]:scope[1]]
    out = []
    for m in re.finditer(r"\breturn\b(?!@)", body):
        start = scope[0] + m.end()
        end = expr_end(code, start)
        expr = code[start:end].strip()
        if expr:
            out.append(expr)
    return out


# ── ModelUseOffloadHandler.kt ───────────────────────────────────────────────

def check_source(raw):
    """Return a list of (criterion_id, message) for the given file text."""
    code = code_view(raw)
    lit = literal_view(raw)
    problems = []

    read = _fn(code, "readLinuxPath")
    if read is None:
        problems.append(("C1", "declaration 'fun readLinuxPath(' not found in the target file"))
    else:
        joins = _calls(code, "resolveMaybeRelative", read)
        if not joins:
            problems.append(("C1", "readLinuxPath() does not call resolveMaybeRelative() "
                                   "— it must delegate to the single join implementation"))
        else:
            # [batch-5 C1] …and the join has to be FED the cwd.
            # `resolveMaybeRelative(linuxPath, null)` keeps the call, the argument
            # list and the downstream mapping intact while there is nothing to
            # join onto: every relative --input / --system-file is resolved
            # against nothing and looked up against `/` — the 1024 defect back.
            args = joins[0][1]
            if len(args) < 2 or not _reads_ident(code, read, args[1], "cwd"):
                problems.append((
                    "C1",
                    "readLinuxPath() feeds the shared join `%s` instead of the cwd "
                    "parameter — a relative --input is resolved against nothing"
                    % (_arg_text(code, args[1]) if len(args) > 1 else "<missing>")))
            # …and the joined value must be the one that gets mapped.
            host = _calls(code, "sessionAwareHostFile", read)
            if host and host[0][1]:
                joined = _bound_to_code_result(code, read, "resolveMaybeRelative(")
                first = _arg_text(code, host[0][1][0])
                carries = (contains_word(first, joined) if joined
                           else "resolveMaybeRelative" in first)
                if not carries:
                    problems.append((
                        "C1",
                        "readLinuxPath() computes the joined path and then hands "
                        "`%s` to sessionAwareHostFile(...) instead of the resolved value "
                        "— a relative path is resolved, discarded, and looked up against `/`"
                        % (first or "?")))

    run = _fn(code, "cmdRun")
    if run is None:
        problems.append(("C2", "declaration 'fun cmdRun(' not found in the target file"))
    else:
        # [batch-5 C2] the value bound to `outputPath` must BE the join's result.
        # `raw.also { resolveMaybeRelative(raw, request.cwd) }` keeps the helper
        # name inside the body and returns the raw relative spelling, so the
        # `invalid_output_path` refusal the 1024 fix removed comes straight back.
        m = re.search(r"\b(?:val|var)\s+outputPath\s*(?::[^=\n]+)?=\s*", code[run[0]:run[1]])
        if not m:
            problems.append(("C2", "cmdRun() no longer binds `outputPath` — the --output "
                                   "normalisation cannot be verified (fail-closed)"))
        else:
            start = run[0] + m.end()
            expr = code[start:expr_end(code, start)]
            sources = value_sources(expr)
            if not any("resolveMaybeRelative" in s for s in sources):
                head = expr.strip().splitlines()[0][:70] if expr.strip() else ""
                problems.append((
                    "C2",
                    "cmdRun() normalises --output through something that is not "
                    "resolveMaybeRelative() (`%s`) — the call may still be spelled in the "
                    "body while a relative --output dies with invalid_output_path" % head))

    img = _fn(code, "resolveImageUrl")
    if img is None:
        problems.append(("C3", "declaration 'fun resolveImageUrl(' not found in the target file"))
    else:
        joins = _calls(code, "resolveMaybeRelative", img)
        if not joins:
            problems.append(("C3", "resolveImageUrl() does not call resolveMaybeRelative() "
                                   "— a relative image_url has no cwd to resolve against"))
        else:
            args = joins[0][1]
            if len(args) < 2 or not _reads_ident(code, img, args[1], "cwd"):
                # `resolveMaybeRelative(url, null)` — same hole as C1, and the
                # `takeIf { it.startsWith("/") }` tail then throws ImageInputError.
                problems.append((
                    "C3",
                    "resolveImageUrl() feeds the shared join `%s` instead of the cwd "
                    "parameter — a relative image_url is resolved against nothing"
                    % (_arg_text(code, args[1]) if len(args) > 1 else "<missing>")))

    if INLINE_JOIN in lit:
        problems.append(("C4", "a hand-written inline cwd join ('\"$cwd...') is back — the "
                               "join must stay single-source in resolveMaybeRelative()"))
    return problems


def contains_word(text, word):
    if not word:
        return False
    return re.search(r"(?<![\w`])" + re.escape(word) + r"(?![\w`])", text) is not None


# ── SpeechOffloadHandler.kt ─────────────────────────────────────────────────

def check_speech_source(raw):
    """SpeechOffloadHandler.kt half of the contract (deep-audit-1033 FIX-C)."""
    code = code_view(raw)
    lit = literal_view(raw)
    problems = []

    resolver = _fn(code, "resolveSourcePath")
    cmd = _fn(code, "cmdTranscribe")

    # S1 — the resolver delegates the join, fed with the cwd, and maps its result.
    if resolver is None:
        problems.append(("S1", "declaration 'fun resolveSourcePath(' not found in the target file"))
    else:
        joins = _calls(code, "resolveMaybeRelative", resolver)
        if not joins:
            problems.append(("S1", "resolveSourcePath() does not call resolveMaybeRelative() — a "
                                   "relative --source is still refused instead of joined onto cwd"))
        else:
            args = joins[0][1]
            if len(args) < 2 or not _reads_ident(code, resolver, args[1], "cwd"):
                problems.append((
                    "S1",
                    "resolveSourcePath() feeds the shared join `%s` instead of the cwd "
                    "parameter — a relative --source is resolved against nothing"
                    % (_arg_text(code, args[1]) if len(args) > 1 else "<missing>")))
                problems.append((
                    "S2",
                    "the cwd parameter no longer reaches the join — cmdTranscribe(...) "
                    "forwards request.cwd into resolveSourcePath(), which drops it"))
            joined = _bound_to_code_result(code, resolver, "resolveMaybeRelative(")
            if joined:
                tr = _calls(code, "resolvePathForSession", resolver)
                if tr and not any(contains_word(_arg_text(code, a), joined) for a in tr[0][1]):
                    problems.append((
                        "S1",
                        "resolveSourcePath() joins the path into `%s` and then maps "
                        "something else into the host tree — the join result is discarded"
                        % joined))

    # S2 — the cwd really enters the call chain: judged on the VALUE, not on the
    # parameter name (`cwd = ""` carries no cwd at all).
    for name in ("cmdTranscribe", "resolveSourcePath"):
        calls = _calls(code, name)
        if not calls:
            problems.append(("S2", "no %s(...) occurrence found — declaration vanished" % name))
            continue
        for open_pos, args in calls:
            if not any(_reads_ident(code, None, a, "cwd") for a in args):
                spelled = ", ".join(_arg_text(code, a) for a in args)
                problems.append(("S2", "%s(...) drops cwd from its arguments: %s(%s)"
                                 % (name, name, spelled)))

    # S3 — relative-vs-absolute is the join's decision, and the join is single-source.
    if resolver is not None and 'startsWith("/")' in lit[resolver[0]:resolver[1]]:
        problems.append(("S3", "resolveSourcePath() re-implements the relative-vs-absolute "
                               "decision (startsWith(\"/\") in its body) — it belongs to "
                               "resolveMaybeRelative()"))
    if INLINE_JOIN in lit:
        problems.append(("S3", "a hand-written inline cwd join ('\"$cwd...') is back — the "
                               "join must stay single-source in resolveMaybeRelative()"))

    # S4 — privacy edge: classify --source before asking for the microphone, and
    # an explicit non-mic source must return an error, never a success, on a
    # path that is actually reachable.
    if cmd is None:
        problems.append(("S4", "declaration 'fun cmdTranscribe(' not found in the target file"))
    else:
        body_start = cmd[0]
        body = code[cmd[0]:cmd[1]]
        classified = body.find("sourceIsMic(")
        mic_gate = body.find("ensureMicPermission(")
        if classified < 0:
            problems.append(("S4", "cmdTranscribe() never classifies --source (sourceIsMic)"))
        if mic_gate < 0:
            problems.append(("S4", "cmdTranscribe() never reaches the microphone permission "
                                   "gate (ensureMicPermission)"))
        if classified >= 0 and mic_gate >= 0:
            if mic_gate < classified:
                problems.append(("S4", "the microphone permission gate runs BEFORE the --source "
                                       "classifier — an explicit --source can fall through to a "
                                       "live mic"))
            else:
                b_start, b_end = body_start + classified, body_start + mic_gate
                if '"error"' not in lit[b_start:b_end]:
                    problems.append(("S4", "the explicit non-mic source branch carries no error "
                                           "payload before the mic gate"))
                rets = [(m.start(), m.group(1)) for m in
                        re.finditer(r"return\s+NativeOffloadResult\(\s*(\d+)", code[b_start:b_end])]
                # [batch-5 S4] …and the refusal has to EXECUTE. `if (false) return …`
                # and `if (1 == 2) return …` keep every token the rules above look
                # for while never returning: control falls out of the branch into the
                # live-microphone path. The guard is folded, not pattern-matched, so
                # the whole family of constant-false spellings is dead.
                live = [(p, c) for (p, c) in rets if path_feasible(code, b_start + p)]
                if not rets:
                    problems.append(("S4", "the explicit non-mic source branch never returns a "
                                           "result before the mic gate"))
                elif not live:
                    problems.append((
                        "S4", "the explicit non-mic source branch's refusal return is dead code "
                              "(a constant-false guard) — a missing or unsupported --source falls "
                              "straight through to the microphone"))
                elif live[-1][1] == "0":
                    problems.append(("S4", "the explicit non-mic source branch returns success "
                                           "(exit 0) — a missing file must not read as a "
                                           "transcription"))
    return problems


# ── ShizukuOffloadHandler.kt ────────────────────────────────────────────────

def check_shizuku_source(raw):
    """ShizukuOffloadHandler.kt half of the contract (deep-audit-1034 A2 / ISSUE-03).

    The Shizuku service runs OUTSIDE the sandbox: the paths this handler hands to
    `ls`/`rm`/`cp`/`pm install` must be guest→host translated first. The join is
    single-source ([resolveMaybeRelative]), the session is what makes the mapping
    possible at all, and a path that does not resolve must refuse.
    """
    code = code_view(raw)
    lit = literal_view(raw)
    problems = []
    resolver = _fn(code, "shizukuHostPath")

    if resolver is None:
        problems.append(("Z1", "declaration 'private fun shizukuHostPath(' not found in the target file"))
        return problems

    # Z1 — the guest→host translation delegates the join …
    joins = _calls(code, "resolveMaybeRelative", resolver)
    if not joins:
        problems.append(("Z1", "shizukuHostPath() does not call resolveMaybeRelative() — a relative "
                               "path handed to the privileged service cannot be joined onto cwd"))
        problems.append(("Z2", "shizukuHostPath() never passes request.cwd — a relative path has "
                               "nothing to resolve against"))
    else:
        # Z2 — … fed with the cwd, not with `null` / an empty string.
        args = joins[0][1]
        if len(args) < 2 or not _reads_ident(code, resolver, args[1], "cwd"):
            problems.append(("Z2", "shizukuHostPath() feeds the shared join `%s` instead of "
                                   "request.cwd — a relative path has nothing to resolve against"
                                   % (_arg_text(code, args[1]) if len(args) > 1 else "<missing>")))

    # Z3 — a guest path cannot be used by an out-of-sandbox service: map it into
    #      this session's host tree, or the operand is a silent no-op.
    tr = _calls(code, "resolvePathForSession", resolver)
    if not tr:
        problems.append(("Z3", "shizukuHostPath() does not resolve through "
                               "PRootKernel.resolvePathForSession — a guest path cannot reach the "
                               "Shizuku service's host filesystem"))
    else:
        targs = tr[0][1]
        # Z2 [batch-5] — the SESSION has to reach the mapping. `resolvePathForSession(
        # null, raw.trim(), context)` keeps the call, the join line and the return
        # head, while the mapping loses the session (falls back to a global scan)
        # and the joined guest path (a relative operand no longer resolves).
        if not targs or not _reads_ident(code, resolver, targs[0], "sessionId"):
            problems.append((
                "Z2",
                "shizukuHostPath() hands `%s` as the session to resolvePathForSession — an "
                "absolute path then escapes the session scoping"
                % (_arg_text(code, targs[0]) if targs else "<missing>")))
        # Z1 [batch-5] — …and the joined guest path, not a re-typed spelling.
        joined = _bound_to_code_result(code, resolver, "resolveMaybeRelative(")
        if joined and not any(contains_word(_arg_text(code, a), joined) for a in targs):
            problems.append((
                "Z1",
                "shizukuHostPath() joins the path into `%s` and hands something else to "
                "resolvePathForSession (%s) — the join is discarded at the mapping site"
                % (joined, ", ".join(_arg_text(code, a) for a in targs))))
        # Z2 — …and the value the function RETURNS must be the mapping's result.
        # `raw.trim().also { resolvePathForSession(…) }` keeps both call literals
        # and still hands the privileged service the guest spelling.
        rets = _return_exprs(code, resolver)
        if rets and not any("resolvePathForSession" in s
                            for e in rets for s in value_sources(e)):
            problems.append((
                "Z2", "shizukuHostPath() returns a path that does not come from "
                      "PRootKernel.resolvePathForSession (%s) — the guest spelling is passed "
                      "through to the host-side service" % rets[0].splitlines()[0][:70]))

    # Z4 — relative-vs-absolute is the join's decision, and the join is single-source.
    if 'startsWith("/")' in lit[resolver[0]:resolver[1]]:
        problems.append(("Z4", "shizukuHostPath() re-implements the relative-vs-absolute decision "
                               "(startsWith(\"/\") in its body) — it belongs to resolveMaybeRelative()"))
    if INLINE_JOIN in lit:
        problems.append(("Z4", "a hand-written inline cwd join ('\"$cwd...') is back — the join must "
                               "stay single-source in resolveMaybeRelative()"))
    return problems


def check_tree(root):
    problems = []
    targets = ((TARGET_REL, "C0", check_source, "ModelUseOffloadHandler.kt"),
               (SPEECH_REL, "S0", check_speech_source, "SpeechOffloadHandler.kt"),
               (SHIZUKU_REL, "Z0", check_shizuku_source, "ShizukuOffloadHandler.kt"))
    for rel, missing_id, fn, _label in targets:
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            problems.append((missing_id, "target file missing: %s" % rel))
            continue
        with open(path, "r", encoding="utf-8") as fh:
            src = fh.read()
        try:
            problems.extend(fn(src))
        except KotlinLexError as exc:
            # fail-closed: an unparsable file is a violation, never a silent skip
            problems.append((missing_id, "lexer could not parse %s (%s) — fail closed" % (rel, exc)))
    return os.path.join(root, TARGET_REL), problems


# ── selftest fixtures ────────────────────────────────────────────────────────

_CLEAN = '''package com.rikkaminis.app.sandbox.offload

class ModelUseOffloadHandler {
    private fun cmdRun(request: NativeOffloadRequest) {
        val outputPath = args.get("output")?.let { raw ->
            if (raw.startsWith("file://")) raw else resolveMaybeRelative(raw, request.cwd)
        }
        if (outputPath != null && !outputPath.startsWith("/")) {
            return NativeOffloadResult(2, "nope")
        }
    }

    private fun readLinuxPath(linuxPath: String, cwd: String?): String? {
        val resolved = resolveMaybeRelative(linuxPath, cwd)
        return resolved
    }

    private fun resolveImageUrl(url: String, cwd: String?): LLMMessage.ImagePart {
        val linuxPath = when {
            url.startsWith("file://") -> url.removePrefix("file://")
            url.startsWith("/") -> url
            else -> resolveMaybeRelative(url, cwd).takeIf { it.startsWith("/") }
                ?: throw ImageInputError("bad")
        }
        return linuxPath
    }
}
'''

# Mirrors the post-fix SpeechOffloadHandler shape (cwd in the chain, join in the
# resolver, classifier ahead of the mic gate, error payload in between).
_SPEECH_CLEAN = '''package com.rikkaminis.app.sandbox.offload

class SpeechOffloadHandler(private val context: Context) : NativeOffloadHandler {
    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        return when (args.positional.firstOrNull()) {
            "transcribe", "listen" -> cmdTranscribe(args, request.sessionId, request.cwd)
            else -> NativeOffloadResult(2, "usage")
        }
    }

    private fun cmdTranscribe(args: OffloadArgs, sessionId: String?, cwd: String?): NativeOffloadResult {
        val source = args.get("source")
        if (source != null && !sourceIsMic(source)) {
            val resolved = resolveSourcePath(source, sessionId, cwd)
            val body = JSONObject().put("error", "not_supported")
                .put("requested_path", source)
                .toString()
            return NativeOffloadResult(2, OffloadOutput.formatBody(body, args))
        }
        ensureMicPermission(args)?.let { return it }
        return NativeOffloadResult(0, "ok")
    }

    private fun resolveSourcePath(source: String, sessionId: String?, cwd: String?): File? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        val guestPath = resolveMaybeRelative(trimmed, cwd)
        return try {
            PRootKernel.resolvePathForSession(sessionId, guestPath, context)
        } catch (e: Throwable) {
            null
        }
    }
}
'''

_DIRTY_READLINUX = _CLEAN.replace(
    "        val resolved = resolveMaybeRelative(linuxPath, cwd)\n        return resolved",
    '        val resolved = if (!linuxPath.startsWith("/") && !cwd.isNullOrEmpty()) {\n'
    '            if (cwd.endsWith("/")) "$cwd$linuxPath" else "$cwd/$linuxPath"\n'
    "        } else linuxPath\n        return resolved",
)

_DIRTY_OUTPUT = _CLEAN.replace(
    '        val outputPath = args.get("output")?.let { raw ->\n'
    '            if (raw.startsWith("file://")) raw else resolveMaybeRelative(raw, request.cwd)\n'
    "        }",
    '        val outputPath = args.get("output")',
)

_DIRTY_IMAGE = _CLEAN.replace(
    "            else -> resolveMaybeRelative(url, cwd).takeIf { it.startsWith(\"/\") }\n"
    "                ?: throw ImageInputError(\"bad\")",
    "            else -> throw ImageInputError(\"bad\")",
)

# All three call sites clean, but a 4th helper keeps an inline join → only C4.
_DIRTY_INLINE_ONLY = _CLEAN.replace(    "    private fun readLinuxPath(linuxPath: String, cwd: String?): String? {",
    '    private fun otherJoin(linuxPath: String, cwd: String?): String {\n'
    '        return if (cwd.endsWith("/")) "$cwd$linuxPath" else "$cwd/$linuxPath"\n'
    "    }\n\n"
    "    private fun readLinuxPath(linuxPath: String, cwd: String?): String? {",
)

# The Before shape of deep-audit-1033 ISSUE-03, verbatim: no join, hard reject.
_SPEECH_BEFORE = _SPEECH_CLEAN.replace(
    "        val guestPath = resolveMaybeRelative(trimmed, cwd)\n"
    "        return try {\n"
    "            PRootKernel.resolvePathForSession(sessionId, guestPath, context)",
    "        if (!trimmed.startsWith(\"/\")) return null\n"
    "        return try {\n"
    "            PRootKernel.resolvePathForSession(sessionId, trimmed, context)",
)

# cwd dropped from the dispatch (the one-line defect) — the join stays.
_SPEECH_NO_CWD = _SPEECH_CLEAN.replace(
    "cmdTranscribe(args, request.sessionId, request.cwd)",
    "cmdTranscribe(args, request.sessionId)",
)

# The microphone gate hoisted above the classifier: the privacy edge reopens.
_SPEECH_MIC_FIRST = _SPEECH_CLEAN.replace(
    "        if (source != null && !sourceIsMic(source)) {",
    "        ensureMicPermission(args)?.let { return it }\n"
    "        if (source != null && !sourceIsMic(source)) {",
).replace("\n        ensureMicPermission(args)?.let { return it }\n        return NativeOffloadResult(0, \"ok\")",
          "\n        return NativeOffloadResult(0, \"ok\")")

# Join present, but a stray hand-written join elsewhere → S3 only.
_SPEECH_STRAY_JOIN = _SPEECH_CLEAN.replace(
    "        ensureMicPermission(args)?.let { return it }",
    '        ensureMicPermission(args)?.let { return it }\n'
    '        val fallbackJoin = "$cwd/legacy.wav"',
)


_SHIZUKU_CLEAN = '''package com.rikkaminis.app.sandbox.offload

class ShizukuOffloadHandler(private val context: Context) : NativeOffloadHandler {
    private fun shizukuHostPath(raw: String, request: NativeOffloadRequest): String? {
        val guestPath = resolveMaybeRelative(raw.trim(), request.cwd)
        return PRootKernel.resolvePathForSession(request.sessionId, guestPath, context)?.absolutePath
    }

    private fun fileLs(path: String?, request: NativeOffloadRequest): NativeOffloadResult {
        val host = shizukuHostPath(path, request)
            ?: return errEnvelope("PATH_NOT_RESOLVED", "file ls: '$path' does not resolve", args)
        return ok(ShizukuManager.runProcess(arrayOf("ls", host)))
    }
}
'''
# The base shape: no shared join at all (a relative path dies) — and with the
# join gone the cwd is gone too, so both Z1 and Z2 fire (same shape as S1S3).
_SHIZUKU_NO_JOIN = _SHIZUKU_CLEAN.replace(
    "resolveMaybeRelative(raw.trim(), request.cwd)",
    "raw.trim()",
)
# The join is there but the cwd is not passed: a relative path has nothing to
# resolve against.
_SHIZUKU_NO_CWD = _SHIZUKU_CLEAN.replace(
    "resolveMaybeRelative(raw.trim(), request.cwd)",
    'resolveMaybeRelative(raw.trim(), "")',
)
# Resolved into the guest spelling: the out-of-sandbox service would get a path
# that does not exist on the host.
_SHIZUKU_GUEST_ONLY = _SHIZUKU_CLEAN.replace(
    "PRootKernel.resolvePathForSession(",
    "PRootKernel.resolveHostPath(",
)
# A second relative-vs-absolute decision grown inside the resolver.
_SHIZUKU_INLINE_DECISION = _SHIZUKU_CLEAN.replace(
    "val guestPath = resolveMaybeRelative(raw.trim(), request.cwd)",
    'val guestPath = if (raw.startsWith("/")) raw '
    "else resolveMaybeRelative(raw.trim(), request.cwd)",
)


# ── [wide-1024] 门瞎实锤固化成臂 ────────────────────────────────────────────
# C1: the resolved path is computed and then DISCARDED at the mapping site.
_MODEL_SESSION_MAP = _CLEAN.replace(
    "    private fun readLinuxPath(linuxPath: String, cwd: String?): String? {\n"
    "        val resolved = resolveMaybeRelative(linuxPath, cwd)\n"
    "        return resolved\n"
    "    }",
    "    private fun readLinuxPath(linuxPath: String, sessionId: String?, cwd: String?): String? {\n"
    "        val resolved = resolveMaybeRelative(linuxPath, cwd)\n"
    "        val hostFile: File = sessionAwareHostFile(resolved, sessionId) ?: return null\n"
    "        if (!hostFile.exists() || !hostFile.isFile) return null\n"
    "        return hostFile.readText()\n"
    "    }",
)
_MODEL_RAW_ARG = _MODEL_SESSION_MAP.replace(
    "sessionAwareHostFile(resolved, sessionId)",
    "sessionAwareHostFile(linuxPath, sessionId)",
)
# S1/S2: the join is still called, but with `null` instead of the cwd parameter.
_SPEECH_NULL_CWD = _SPEECH_CLEAN.replace(
    "        val guestPath = resolveMaybeRelative(trimmed, cwd)",
    "        val guestPath = resolveMaybeRelative(trimmed, null)",
)
# S4: the refusal keeps every token the rule reads and is dead code.
_SPEECH_DEAD_REFUSAL = _SPEECH_CLEAN.replace(
    "            return NativeOffloadResult(2, OffloadOutput.formatBody(body, args))",
    "            if (false) return NativeOffloadResult(2, OffloadOutput.formatBody(body, args))",
)
# Z2: both call literals stay, the translation's value is thrown away at the exit.
_SHIZUKU_PASSTHROUGH = _SHIZUKU_CLEAN.replace(
    "        return PRootKernel.resolvePathForSession(request.sessionId, guestPath, context)?.absolutePath",
    "        return raw.trim().also { "
    "PRootKernel.resolvePathForSession(request.sessionId, guestPath, context) }",
)


# ── [batch-5] 门瞎实锤固化成臂 ──────────────────────────────────────────────
# C1: the join is spelled, the cwd argument is `null` — nothing to join onto.
_MODEL_NULL_CWD = _CLEAN.replace(
    "resolveMaybeRelative(linuxPath, cwd)",
    "resolveMaybeRelative(linuxPath, null)")
# C2: the helper name survives inside cmdRun, the raw relative spelling is what
# the `--output` path keeps (`.also {}` discards the join).
_MODEL_DISCARDED_OUTPUT = _CLEAN.replace(
    '        val outputPath = args.get("output")?.let { raw ->\n'
    '            if (raw.startsWith("file://")) raw else resolveMaybeRelative(raw, request.cwd)\n'
    '        }',
    '        val outputPath = args.get("output")?.let { raw ->\n'
    '            raw.also { resolveMaybeRelative(raw, request.cwd) }\n'
    '        }')
# C3: the same `null` cwd hole on the image_url path.
_MODEL_NULL_CWD_IMAGE = _CLEAN.replace(
    "else -> resolveMaybeRelative(url, cwd)",
    "else -> resolveMaybeRelative(url, null)")
# S2: the parameter NAME `cwd` is there, its value is an empty string.
_SPEECH_NAMED_EMPTY_CWD = _SPEECH_CLEAN.replace(
    "cmdTranscribe(args, request.sessionId, request.cwd)",
    'cmdTranscribe(args, request.sessionId, cwd = "")')
# S4: a constant-false guard that is not spelled `false` / `!true`.
_SPEECH_DEAD_COMPARISON = _SPEECH_CLEAN.replace(
    "            return NativeOffloadResult(2, OffloadOutput.formatBody(body, args))",
    "            if (1 == 2) return NativeOffloadResult(2, OffloadOutput.formatBody(body, args))")
# Z1/Z2: the mapping keeps its call head and loses the session + the joined path.
_SHIZUKU_DROPPED_ARGS = _SHIZUKU_CLEAN.replace(
    "PRootKernel.resolvePathForSession(request.sessionId, guestPath, context)",
    "PRootKernel.resolvePathForSession(null, raw.trim(), context)")

def _fixture_root(model_text, speech_text=_SPEECH_CLEAN, shizuku_text=_SHIZUKU_CLEAN):
    root = tempfile.mkdtemp(prefix="offload-relpath-gate-selftest-")
    for rel, text in ((TARGET_REL, model_text), (SPEECH_REL, speech_text),
                      (SHIZUKU_REL, shizuku_text)):
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
    return root


def selftest():
    cases = [
        ("clean: all three ModelUse sites delegate to resolveMaybeRelative", _CLEAN, set()),
        ("C1C4: readLinuxPath keeps an inline join", _DIRTY_READLINUX, {"C1", "C4"}),
        ("C2: --output not normalised", _DIRTY_OUTPUT, {"C2"}),
        ("C3: resolveImageUrl has no cwd join", _DIRTY_IMAGE, {"C3"}),
        ("C4 only: all sites clean but a stray inline join remains", _DIRTY_INLINE_ONLY, {"C4"}),
        ("S1S3: the Speech Before shape (hard relative reject, no join)",
         _CLEAN, {"S1", "S3"}, _SPEECH_BEFORE),
        ("S2: the Speech dispatch drops request.cwd", _CLEAN, {"S2"}, _SPEECH_NO_CWD),
        ("S4: the mic gate runs before the --source classifier",
         _CLEAN, {"S4"}, _SPEECH_MIC_FIRST),
        ("S3 only: the Speech join is present but a stray inline join is back",
         _CLEAN, {"S3"}, _SPEECH_STRAY_JOIN),
        # ── [da1034-A2 ISSUE-03] the Shizuku half ──────────────────────────
        ("Z1Z2: the Shizuku resolver drops the shared join (and the cwd with it)",
         _CLEAN, {"Z1", "Z2"}, _SPEECH_CLEAN, _SHIZUKU_NO_JOIN),
        ("Z2: the Shizuku resolver never passes request.cwd",
         _CLEAN, {"Z2"}, _SPEECH_CLEAN, _SHIZUKU_NO_CWD),
        ("Z3: the Shizuku resolver keeps the guest spelling",
         _CLEAN, {"Z3"}, _SPEECH_CLEAN, _SHIZUKU_GUEST_ONLY),
        ("Z4: a second relative-vs-absolute decision inside the resolver",
         _CLEAN, {"Z4"}, _SPEECH_CLEAN, _SHIZUKU_INLINE_DECISION),
        # ── [wide-1024] dataflow arms: the right API is named, the wrong value moves ──
        ("clean: readLinuxPath maps the RESOLVED path (session-aware shape)",
         _MODEL_SESSION_MAP, set()),
        ("C1: readLinuxPath resolves the path and then maps the raw argument",
         _MODEL_RAW_ARG, {"C1"}),
        ("S1S2: the Speech join is fed `null` instead of the cwd parameter",
         _CLEAN, {"S1", "S2"}, _SPEECH_NULL_CWD),
        ("S4: the refusal return is wrapped in `if (false)` (dead code)",
         _CLEAN, {"S4"}, _SPEECH_DEAD_REFUSAL),
        ("Z2: shizukuHostPath returns the guest spelling, not the mapped host path",
         _CLEAN, {"Z2"}, _SPEECH_CLEAN, _SHIZUKU_PASSTHROUGH),
        # ── [batch-5] one arm per falsified witness (each was BLIND before) ──
        ("C1: the join is fed `null` instead of the cwd parameter",
         _MODEL_NULL_CWD, {"C1"}),
        ("C2: --output keeps the helper name and returns the raw spelling",
         _MODEL_DISCARDED_OUTPUT, {"C2"}),
        ("C3: the image join is fed `null` instead of the cwd parameter",
         _MODEL_NULL_CWD_IMAGE, {"C3"}),
        ('S2: the named argument `cwd = ""` passes no cwd at all',
         _CLEAN, {"S2"}, _SPEECH_NAMED_EMPTY_CWD),
        ("S4: the refusal sits behind `if (1 == 2)` (constant-false comparison)",
         _CLEAN, {"S4"}, _SPEECH_DEAD_COMPARISON),
        ("Z1Z2: the mapping is handed `null` session + the raw spelling",
         _CLEAN, {"Z1", "Z2"}, _SPEECH_CLEAN, _SHIZUKU_DROPPED_ARGS),
    ]
    failures = []
    for case in cases:
        label, model_text, expected = case[0], case[1], case[2]
        speech_text = case[3] if len(case) > 3 else _SPEECH_CLEAN
        shizuku_text = case[4] if len(case) > 4 else _SHIZUKU_CLEAN
        root = _fixture_root(model_text, speech_text, shizuku_text)
        try:
            _, problems = check_tree(root)
        finally:
            shutil.rmtree(root, ignore_errors=True)
        got = {cid for cid, _ in problems}
        ok = got == expected
        print("  [%s] %s%s" % ("PASS" if ok else "FAIL", label,
                               "" if ok else "  → got=%s expected=%s" % (sorted(got), sorted(expected))))
        if not ok:
            failures.append(label)
    if failures:
        print("❌ selftest failed: %s" % ", ".join(failures))
        return 1
    print("OFFLOAD_RELATIVE_PATH_UNIFY_SELFTEST=PASS (%d fixtures: %d violation "
          "shapes caught, %d clean shapes green)"
          % (len(cases), len(cases) - 2, 2))
    return 0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    root = os.path.abspath(args[0]) if args else os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", ".."))
    if "--selftest" in flags:
        return selftest()
    unknown = [f for f in flags if f != "--selftest"]
    if unknown:
        print("unknown option(s): %s" % " ".join(unknown))
        return 2
    path, problems = check_tree(root)
    if path is None or problems:
        print("offload_relative_path_unify_check: %d violation(s) in %s + %s" %
              (len(problems), TARGET_REL, os.path.basename(SPEECH_REL)))
        for cid, msg in problems:
            print("  [%s] %s" % (cid, msg))
        return 1
    print("offload_relative_path_unify_check: %s + %s + %s — "
          "OFFLOAD-RELATIVE-PATH-UNIFY GATE OK"
          % (TARGET_REL, os.path.basename(SPEECH_REL), os.path.basename(SHIZUKU_REL)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
