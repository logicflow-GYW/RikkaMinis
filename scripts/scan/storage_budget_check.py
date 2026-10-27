#!/usr/bin/env python3
"""storage_budget_check — the storage-budget loop and the sandbox temp/env
wiring must stay WIRED, not merely spelled.

Why this gate exists
--------------------
`data/storage/StorageBudget.kt`, `notification/StorageBudgetNotifier.kt` and
the `TMPDIR` block of `sandbox/PersistentShell.kt` all landed this week
([feat/storage-budget-loop], [feat/storage-budget-loop L0]) and were referenced
by ZERO scan gates (`grep -l <file> scripts/scan/*.py` = empty). An external
adversarial pass (witness-1027, W-01/W-02/W-03) then flipped three one-token
decisions in them — polarity, argument identity, path domain — and all 112
gates plus all 347 fixtures stayed green. Every one of those three flips is
silent at runtime: the user simply stops getting storage notifications, or the
sandbox quietly refills the rootfs with temp files.

What is judged (decision, never a line's spelling)
--------------------------------------------------
  C1 [W-01] `StorageBudget.runDailyCheck` calls `shouldNotify(…)` with, as its
     3rd argument, the PERSISTED baseline — a `getLong(<KEY>, …)` read whose
     <KEY> is the very key the notify branch writes back as the sampled bytes
     (derived from the file, not hardcoded: baseline keys are the keys of
     `putLong(<KEY>, <sample>)`). The 3rd argument may be that read directly or
     a `val` bound to it, declared before the call, not reassigned. Passing the
     CURRENT sample makes the "+25 % inside the 7-day window" branch compare
     the sample against itself — a permanently dead renotify path.
  C2 [W-02] `StorageBudgetNotifier.ensureChannel` implements the idempotence
     guard in its POSITIVE form: "channel already exists → early return", with
     `createNotificationChannel(` reachable AFTER that guard. The inverted
     guard (`== null → return`) means a fresh install never registers the
     channel, and Android 8+ silently drops the notification while the caller
     stamps `lastNotifiedAt` — locking the 7-day window shut.
  C3 [W-03] `PersistentShell.startProcess` assigns the GUEST temp dir
     (`env["TMPDIR"]` / `env["TMP"]`) a guest absolute-path literal (`"/tmp"`),
     and — symmetrically, so that "unify them on the guest path" cannot break
     the host side — keeps `env["PROOT_TMP_DIR"]` on a HOST path derivation.
     Temp dirs read by tools inside the rootfs must live in the swept guest
     /tmp; a host path there resolves inside `files/alpine-rootfs` and becomes
     permanent, unswept junk (3.26 GiB measured 2026-10-27).

Fail-closed throughout: an unlexable file, a renamed function, an untraceable
argument, or a missing assignment is RED — never a silent skip.

Usage: python3 scripts/scan/storage_budget_check.py [repo_root]
       python3 scripts/scan/storage_budget_check.py --root <repo_root>
       python3 scripts/scan/storage_budget_check.py --selftest
Exit:  0 = wired, 1 = violation (criterion + file:line printed)
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (
    const_value,
    function_body,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
    tail_expr,
)
from _kotlin_valueflow import (
    call_args,
    code_view,
    expr_end,
    literal_view,
    path_feasible,
)

MARKER = "STORAGE_BUDGET_SELFTEST=PASS"

BASE = "src/android/app/src/main/java/com/rikkaminis/app"
TARGET_BUDGET = BASE + "/data/storage/StorageBudget.kt"
TARGET_NOTIFIER = BASE + "/notification/StorageBudgetNotifier.kt"
TARGET_SHELL = BASE + "/sandbox/PersistentShell.kt"

BUDGET_FN = "runDailyCheck"
NOTIFY_CALL = "shouldNotify"
SAMPLE_CALL = "sample"
BASELINE_ARG_INDEX = 2  # shouldNotify(now, lastNotifiedAt, <baseline>, usage)

NOTIFIER_FN = "ensureChannel"
LOOKUP_CALL = "getNotificationChannel"
CREATE_CALL = "createNotificationChannel"

SHELL_FN = "startProcess"
GUEST_TMP_KEYS = ("TMPDIR", "TMP")
HOST_TMP_KEY = "PROOT_TMP_DIR"
# a host path derivation — any of these means "real directory on the host side"
HOST_ANCHORS = (
    "absolutePath",
    "getProotTmpDir",
    "filesDir",
    "cacheDir",
    "context",
    "File(",
    ".path",
)

NAMED_ARG = re.compile(r"^([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$", re.S)
IDENT = re.compile(r"^[A-Za-z_]\w*$")


# ── views ───────────────────────────────────────────────────────────────────
def _view(src):
    """(code, lit, None) or (None, None, error).

    `code` = comments AND string/char interiors blanked (length preserved) —
    the only channel structural judgment may use. `lit` = comments stripped,
    literals kept — used ONLY where a literal must be READ (the `"/tmp"`
    right-hand side of C3). Offsets are identical in both views.
    """
    try:
        return code_view(src), literal_view(src), None
    except KotlinLexError as exc:
        return None, None, "source not lexable (%s) — refusing to fall back to raw scanning" % exc


def _line(code, pos):
    return code.count("\n", 0, pos) + 1


# ── shared call/argument helpers ────────────────────────────────────────────
def _signature_params(code, fn_name):
    """Parameter names of `fun <fn_name>(…)`, in order (derived, not hardcoded)."""
    m = re.search(r"\bfun\s+%s\s*\(" % re.escape(fn_name), code)
    if m is None:
        return []
    op = m.end() - 1
    cp = match_bracket(code, op)
    if cp < 0:
        return []
    names = []
    for a, b in split_top(code[op + 1:cp], (",",)):
        decl = code[op + 1 + a:op + 1 + b]
        decl = re.sub(r"@\w+(?:\([^)]*\))?", " ", decl)
        head = decl.split(":", 1)[0].strip()
        toks = head.split()
        nm = toks[-1] if toks else ""
        names.append(nm if IDENT.match(nm) else "")
    return names


def _call_sites(body, name):
    """Every real call to `name(…)` in `body`: [(pos, [raw argument texts])]."""
    out = []
    for m in re.finditer(r"(?<![\w.])%s\s*\(" % re.escape(name), body):
        if re.search(r"\bfun\s+$", body[:m.start()]):
            continue  # the declaration site, not a call
        spans = call_args(body, m.end() - 1)
        if not spans:
            continue
        out.append((m.start(), [body[a:b].strip() for a, b in spans]))
    return out


def _ordered_args(raw_args, params):
    """Map named arguments onto their parameter slots; keep positional order."""
    if not params:
        return list(raw_args)
    slots = [None] * len(params)
    positional = []
    for txt in raw_args:
        m = NAMED_ARG.match(txt)
        if m and m.group(1) in params:
            slots[params.index(m.group(1))] = m.group(2).strip()
        else:
            positional.append(txt)
    for i in range(len(slots)):
        if slots[i] is None:
            slots[i] = positional.pop(0) if positional else None
    return slots


def _decl_before(body, name, pos):
    """(keyword, initializer) of the last `val|var <name> = …` before `pos`."""
    pat = re.compile(r"(?<![\w.])(val|var)\s+" + re.escape(name) + r"\s*(?::[^=\n]+?)?=(?!=)")
    last = None
    for m in pat.finditer(body):
        if m.start() >= pos:
            break
        end = expr_end(body, m.end())
        last = (m.group(1), body[m.end():end].strip())
    return last


def _get_long_key(expr):
    """`<KEY>` when `expr` IS exactly a `…getLong(<KEY>, …)` read, else None.

    "Exactly" matters: `getLong(K, 0L) + 1` / `getLong(K, 0L).coerceAtLeast(0)`
    hand a DERIVED value to the caller while keeping the read's spelling.
    """
    s = strip_parens(expr.strip())
    m = re.search(r"\bgetLong\s*\(", s)
    if m is None:
        return None
    close = match_bracket(s, m.end() - 1)
    if close != len(s) - 1:
        return None
    spans = split_top(s[m.end():close], (",",))
    if not spans:
        return None
    return s[m.end() + spans[0][0]: m.end() + spans[0][1]].strip()


# ── C1: the baseline handed to shouldNotify ─────────────────────────────────
def _sample_symbol(body):
    """The local holding the CURRENT sample (`val usage = sample(context)`)."""
    m = re.search(r"(?m)^[ \t]*(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*%s\s*\(" % SAMPLE_CALL, body)
    return m.group(1) if m else "usage"


def _is_sample(expr, sample_sym):
    e = strip_parens(expr).strip()
    if not e:
        return False
    if not sample_sym:
        return bool(re.search(r"\busage\b", e))
    return bool(re.search(r"(?<![\w.])%s\b" % re.escape(sample_sym), e))


def _baseline_keys(body, sample_sym):
    """Keys the function persists the SAMPLED bytes under (`putLong(K, <sample>)`).

    Derived, never hardcoded: "the value I remember about this notification is
    the value I notified about". The timestamp write (`putLong(K, now)`) is not
    a sample, so it drops out by construction.
    """
    keys = []
    for m in re.finditer(r"\bputLong\s*\(", body):
        spans = call_args(body, m.end() - 1)
        if len(spans) < 2:
            continue
        key = body[spans[0][0]:spans[0][1]].strip()
        val = body[spans[1][0]:spans[1][1]].strip()
        if _is_sample(val, sample_sym):
            keys.append(key)
    return keys


def _baseline_problem(body, expr, pos, baseline_keys, sample_sym):
    """None when the argument IS the persisted baseline, else a sentence saying why not."""
    e = strip_parens(expr.strip())
    key = _get_long_key(e)
    if key is not None:
        if key in baseline_keys:
            return None
        return ("the 3rd argument reads `%s`, but the notify branch persists the sampled "
                "bytes under %s — the comparison baseline must be the key the notification "
                "writes back, otherwise the remembered value never matches what is read"
                % (key, ", ".join(baseline_keys) or "no key at all"))
    if _is_sample(e, sample_sym):
        return ("the 3rd argument IS the current sample (`%s`) — inside the 7-day window the "
                "`+25%%` branch then compares the sample against itself, which is false for "
                "every positive value, so a doubling disk can never re-alert"
                % one_line(e))
    if IDENT.match(e):
        decl = _decl_before(body, e, pos)
        if decl is None:
            return ("`%s` has no local binding declared before the call — it cannot be proven "
                    "to be the persisted baseline (a field / parameter read is exactly what the "
                    "witness passed in)" % e)
        kw, init = decl
        if kw != "val":
            return "`%s` is a `var` — it can be reassigned between the read and the call" % e
        key = _get_long_key(strip_parens(init))
        if key is not None:
            if key in baseline_keys:
                return None
            return ("`%s` is bound to `getLong(%s, …)`, which is not the baseline the notify "
                    "branch persists (%s)"
                    % (e, key, ", ".join(baseline_keys) or "none"))
        if _is_sample(init, sample_sym):
            return ("`%s` is bound to the CURRENT sample (`%s`) — the `+25%%` renotify branch "
                    "becomes `sample >= sample + sample/4`, permanently false"
                    % (e, one_line(init)))
        return ("`%s` is bound to `%s`, which does not trace to a persisted "
                "`getLong(<baseline key>, …)`" % (e, one_line(init)))
    return ("the 3rd argument `%s` is not a persisted baseline read — it must be "
            "`getLong(<baseline key>, …)` or a `val` bound to it" % one_line(e))


def one_line(expr, cap=90):
    s = " ".join(expr.split())
    return s if len(s) <= cap else s[:cap] + "…"


def check_budget_source(text):
    code, _lit, err = _view(text)
    if err:
        return ["C1: " + err]
    span = function_body(code, BUDGET_FN)
    if span is None or span[2] != "block":
        return ["C1: `%s` not found with a block body in StorageBudget.kt "
                "(renamed or removed?) — fail closed" % BUDGET_FN]
    base, end, _ = span
    body = code[base:end]
    params = _signature_params(code, NOTIFY_CALL)
    sample_sym = _sample_symbol(body)
    baseline_keys = _baseline_keys(body, sample_sym)
    findings = []
    if not baseline_keys:
        findings.append("C1: `%s` never persists the sampled bytes "
                        "(`putLong(<KEY>, <%s>)`) — nothing records what the user was "
                        "notified about, so the renotify comparison has no baseline"
                        % (BUDGET_FN, sample_sym))
    sites = _call_sites(body, NOTIFY_CALL)
    if not sites:
        findings.append("C1: `%s` never calls `%s(` — the daily check no longer decides "
                        "whether to notify" % (BUDGET_FN, NOTIFY_CALL))
        return findings
    for pos, raw in sites:
        args = _ordered_args(raw, params)
        if len(args) <= BASELINE_ARG_INDEX or not (args[BASELINE_ARG_INDEX] or "").strip():
            findings.append("C1: line %d — `%s(` is called with %d argument(s) and the "
                            "baseline sits at position %d; refusing to guess"
                            % (_line(code, base + pos), NOTIFY_CALL, len(args),
                               BASELINE_ARG_INDEX + 1))
            continue
        problem = _baseline_problem(body, args[BASELINE_ARG_INDEX], pos,
                                   baseline_keys, sample_sym)
        if problem:
            findings.append("C1: line %d — %s" % (_line(code, base + pos), problem))
    return findings


# ── C2: the channel idempotence guard's polarity ────────────────────────────
_POS_COND = re.compile(r"!==?\s*null\b")
_NEG_COND = re.compile(r"===?\s*null\b")
_SAFE_NAV = re.compile(r"\?\s*\.\s*(?:let|also|run|apply)\s*\{")


def _exits_at_tail(seg):
    """Does this branch body end in a bare `return` (braced or braceless)?"""
    inner = seg.strip()
    if inner.startswith("{") and inner.endswith("}"):
        inner = inner[1:-1]
    tail = (tail_expr(inner) or "").strip()
    return bool(re.match(r"return\b", tail))


def check_notifier_source(text):
    code, _lit, err = _view(text)
    if err:
        return ["C2: " + err]
    span = function_body(code, NOTIFIER_FN)
    if span is None or span[2] != "block":
        return ["C2: `%s` not found with a block body in StorageBudgetNotifier.kt "
                "(renamed or removed?) — fail closed" % NOTIFIER_FN]
    base, end, _ = span
    body = code[base:end]

    lookups = list(re.finditer(r"\b%s\s*\(" % re.escape(LOOKUP_CALL), body))
    if not lookups:
        return ["C2: `%s` never looks its channel up via `%s(` — the idempotence guard "
                "is gone, so the channel is re-created (or never created) blindly"
                % (NOTIFIER_FN, LOOKUP_CALL)]
    lookup_start = lookups[0].start()
    lookup_close = match_bracket(body, lookups[0].end() - 1)
    if lookup_close < 0:
        return ["C2: the `%s(` call does not balance — fail closed" % LOOKUP_CALL]
    findings = []

    guard = None
    for cond, t0, t1, _e0, _e1 in if_blocks(body):
        if LOOKUP_CALL in cond and t1 > lookup_start:
            if guard is None or t1 < guard[1]:
                guard = (cond, t0, t1)
    creates = [m.start() for m in re.finditer(r"\b%s\s*\(" % re.escape(CREATE_CALL), body)]

    if guard is None:
        # Accepted equivalent: `getNotificationChannel(ID)?.let { return }`
        tail = body[lookup_close + 1:]
        nav = re.match(r"\s*\?\s*\.\s*(?:let|also)\s*\{", tail)
        if nav is not None:
            open_pos = lookup_close + 1 + nav.end() - 1
            close = match_bracket(body, open_pos)
            if close > 0 and re.match(r"\s*return\b", body[open_pos + 1:close].strip()):
                # positive by construction: "exists ⇒ leave without creating"
                if not creates:
                    findings.append("C2: `%s` never calls `%s(` — the channel is never "
                                    "registered" % (NOTIFIER_FN, CREATE_CALL))
                elif not any(p > close + 1 and path_feasible(body, p) for p in creates):
                    findings.append(
                        "C2: `%s(` is not reachable AFTER the existence guard — the creation "
                        "path is dead (constant / unreachable branch)" % CREATE_CALL)
                return findings
        if guard is None:
            # Accepted equivalent: `getNotificationChannel(ID) ?: run { create… }`
            elvis = re.match(r"\s*\?:", tail)
            if elvis is not None:
                stop = expr_end(body, lookup_close + 1)
                rhs = body[lookup_close + 1:stop]
                if re.search(r"\b%s\s*\(" % re.escape(CREATE_CALL), rhs):
                    ok = any(path_feasible(body, p) for p in creates)
                    if not ok:
                        findings.append("C2: the elvis-tail `%s(` is unreachable — the guard "
                                        "decides before the channel can be created" % CREATE_CALL)
                    return findings
            findings.append(
                "C2: `%s` never runs a `%s(...)`-based existence guard — a fresh install "
                "then reaches `%s(` with no channel registered, and Android 8+ drops the "
                "notification silently while the caller stamps lastNotifiedAt"
                % (NOTIFIER_FN, LOOKUP_CALL, CREATE_CALL))
            return findings

    cond, t0, t1 = guard
    c = strip_parens(cond)
    if _NEG_COND.search(c):
        findings.append(
            "C2: line %d — the guard is INVERTED (`%s`): it early-returns when the channel "
            "is MISSING, so `%s(` never runs on a fresh install (Android 8+ then silently "
            "drops the notification and the 7-day window is stamped shut)"
            % (_line(code, base + t0), one_line(c, 70), CREATE_CALL))
    elif not (_POS_COND.search(c) or _SAFE_NAV.search(c)):
        findings.append(
            "C2: line %d — the guard condition `%s` neither tests the channel for EXISTENCE "
            "(`!= null`) nor consumes it via `?.let { return }`; polarity cannot be "
            "established — fail closed" % (_line(code, base + t0), one_line(c, 70)))

    branch = body[t0:t1]
    if re.search(r"\b%s\s*\(" % re.escape(CREATE_CALL), branch):
        findings.append(
            "C2: line %d — `%s(` sits INSIDE the guard branch, so it only runs on the "
            "path the guard is supposed to skip"
            % (_line(code, base + t0), CREATE_CALL))
    elif not _exits_at_tail(branch):
        findings.append(
            "C2: line %d — the guard branch does not end in an early `return`; it falls "
            "through into the creation path either way, so the guard decides nothing"
            % _line(code, base + t0))
    if not creates:
        findings.append("C2: `%s` never calls `%s(` — the channel is never registered"
                        % (NOTIFIER_FN, CREATE_CALL))
    elif not any(p > t1 and path_feasible(body, p) for p in creates):
        findings.append(
            "C2: `%s(` is not reachable AFTER the existence guard — the creation path is "
            "dead (constant / unreachable branch)" % CREATE_CALL)
    return findings


# ── C3: guest /tmp must stay a GUEST path (and the host one a host path) ────
def _env_assignments(text, env, var):
    """[(pos, rhs_text)] of `<env>["<var>"] = …` — searched on the literal view."""
    pat = re.compile(r"\b%s\s*\[\s*\"%s\"\s*\]\s*=(?!=)" % (re.escape(env), re.escape(var)))
    out = []
    for m in pat.finditer(text):
        stop = expr_end(text, m.end())
        out.append((m.start(), text[m.end():stop].strip()))
    return out


def _fixed_string(text, expr):
    """The literal a string-valued expression resolves to (one `val` hop allowed)."""
    e = strip_parens(expr.strip())
    cv = const_value(e)
    if cv is not None and cv[0] == "str":
        return cv[1]
    if IDENT.match(e):
        m = re.search(r"(?<![\w.])(?:val|var)\s+%s\s*(?::[^=\n]+?)?\s*=(?!=)" % re.escape(e), text)
        if m is not None:
            stop = expr_end(text, m.end())
            cv = const_value(strip_parens(text[m.end():stop].strip()))
            if cv is not None and cv[0] == "str":
                return cv[1]
    return None


def _guest_path_problem(text, rhs):
    """None when `rhs` is a guest absolute-path literal, else the reason.

    `text` is the WHOLE file (literal view) so a file-level
    `private const val GUEST_TMP = "/tmp"` resolves; one `val` hop only.
    """
    e = strip_parens(rhs.strip())
    for anchor in HOST_ANCHORS:
        if anchor in e:
            return ("the right-hand side `%s` is a HOST path derivation (`%s`) — tools inside "
                    "the rootfs read this variable, so temp files land under "
                    "`files/alpine-rootfs` as permanent junk instead of the swept guest /tmp"
                    % (one_line(e), anchor))
    value = _fixed_string(text, e)
    if value is None:
        return ("the right-hand side `%s` is not a guest absolute-path literal — the guest "
                "temp dir must be a literal like `\"/tmp\"`" % one_line(e))
    if not value.startswith("/"):
        return ("the right-hand side resolves to `\"%s\"`, which is not an absolute guest "
                "path" % value)
    return None


def check_shell_source(text):
    _code, lit, err = _view(text)
    if err:
        return ["C3: " + err]
    span = function_body(lit, SHELL_FN)
    if span is None or span[2] != "block":
        return ["C3: `%s` not found with a block body in PersistentShell.kt "
                "(renamed or removed?) — fail closed" % SHELL_FN]
    base, end, _ = span
    body = lit[base:end]
    m = re.search(r"\bval\s+([A-Za-z_]\w*)\s*=\s*[A-Za-z_][\w.]*\.environment\(\)", body)
    env = m.group(1) if m else "env"
    findings = []
    for var in GUEST_TMP_KEYS:
        hits = _env_assignments(body, env, var)
        if not hits:
            findings.append(
                "C3: `%s` never assigns `%s[\"%s\"]` — the guest tools then inherit the app "
                "process's host TMPDIR (`/data/user/0/<pkg>/cache`), which inside the rootfs "
                "is an unswept directory tree: every temp write becomes permanent junk"
                % (SHELL_FN, env, var))
            continue
        for pos, rhs in hits:
            problem = _guest_path_problem(lit, rhs)
            if problem:
                findings.append("C3: line %d — `%s[\"%s\"]`: %s"
                                % (_line(lit, base + pos), env, var, problem))
    hosts = _env_assignments(body, env, HOST_TMP_KEY)
    if not hosts:
        findings.append(
            "C3: `%s` never assigns `%s[\"%s\"]` — proot itself runs on the host and needs a "
            "real directory there; its absence means the host side was folded into the guest "
            "path (the mirror of the W-03 defect)" % (SHELL_FN, env, HOST_TMP_KEY))
    else:
        for pos, rhs in hosts:
            e = strip_parens(rhs.strip())
            if not any(anchor in e for anchor in HOST_ANCHORS):
                findings.append(
                    "C3: line %d — `%s[\"%s\"] = %s` is not a host path derivation: proot runs "
                    "on the HOST and needs a real directory, so pointing it at the guest path "
                    "`\"/tmp\"` breaks the host side of the pair"
                    % (_line(lit, base + pos), env, HOST_TMP_KEY, one_line(e)))
    return findings


# ── fixtures ────────────────────────────────────────────────────────────────
BUDGET_OK = """
package p

internal object StorageBudget {
    const val KEY_LAST_SAMPLE_AT = "last_sample_at"
    const val KEY_LAST_NOTIFIED_AT = "last_notified_at"
    const val KEY_LAST_NOTIFIED_BYTES = "last_notified_bytes"

    suspend fun runDailyCheck(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        try {
            val usage = sample(context)
            val lastNotifiedAt = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)
            val lastNotifiedBytes = prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L)
            if (shouldNotify(now, lastNotifiedAt, lastNotifiedBytes, usage)) {
                Notifier.post(context, usage)
                prefs.edit()
                    .putLong(KEY_LAST_NOTIFIED_AT, now)
                    .putLong(KEY_LAST_NOTIFIED_BYTES, usage.appBytes)
                    .apply()
            }
        } catch (t: Throwable) {
            prefs.edit().putLong(KEY_LAST_SAMPLE_AT, now).apply()
        }
    }

    fun shouldNotify(
        now: Long,
        lastNotifiedAt: Long,
        lastNotifiedBytes: Long,
        usage: Usage,
    ): Boolean = usage.overBudget
}
"""

# W-01's witness: the sample handed in where the persisted baseline belongs.
BUDGET_SAMPLE_ARG = BUDGET_OK.replace(
    "shouldNotify(now, lastNotifiedAt, lastNotifiedBytes, usage)",
    "shouldNotify(now, lastNotifiedAt, usage.appBytes, usage)",
)
# the same swap hidden behind the binding
BUDGET_SAMPLE_BINDING = BUDGET_OK.replace(
    "val lastNotifiedBytes = prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L)",
    "val lastNotifiedBytes = usage.appBytes",
)
# a read of the wrong key (the sample timestamp instead of the notified bytes)
BUDGET_WRONG_KEY = BUDGET_OK.replace(
    "val lastNotifiedBytes = prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L)",
    "val lastNotifiedBytes = prefs.getLong(KEY_LAST_SAMPLE_AT, 0L)",
)
# the notify branch stops recording what it notified about
BUDGET_NO_WRITE_BACK = BUDGET_OK.replace(
    "                    .putLong(KEY_LAST_NOTIFIED_BYTES, usage.appBytes)\n", "")
# a `var` baseline that can be reassigned in between
BUDGET_VAR_BASELINE = BUDGET_OK.replace(
    "val lastNotifiedBytes = prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L)",
    "var lastNotifiedBytes = prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L)")
# the baseline comes from a FIELD, not from a persisted read — untraceable
BUDGET_UNTRACEABLE = BUDGET_OK.replace(
    "val lastNotifiedBytes = prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L)",
    "val lastNotifiedBytes = state.lastNotifiedBytes")
# a value derived from the read keeps the read's spelling but not its value
BUDGET_DERIVED_READ = BUDGET_OK.replace(
    "shouldNotify(now, lastNotifiedAt, lastNotifiedBytes, usage)",
    "shouldNotify(now, lastNotifiedAt, prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L) + 1L, usage)")

# clean equivalents
BUDGET_OK_INLINE = BUDGET_OK.replace(
    "shouldNotify(now, lastNotifiedAt, lastNotifiedBytes, usage)",
    "shouldNotify(now, lastNotifiedAt, prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L), usage)")
BUDGET_OK_NAMED = BUDGET_OK.replace(
    "shouldNotify(now, lastNotifiedAt, lastNotifiedBytes, usage)",
    "shouldNotify(\n                now = now,\n                lastNotifiedAt = lastNotifiedAt,\n"
    "                lastNotifiedBytes = lastNotifiedBytes,\n                usage = usage,\n"
    "            )")

NOTIFIER_OK = """
package p

const val CHANNEL_ID = "minis_storage_budget"

object StorageBudgetNotifier {
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }
}
"""

# W-02's witness: polarity flipped — a fresh install never registers the channel.
NOTIFIER_FLIPPED = NOTIFIER_OK.replace(
    "if (manager.getNotificationChannel(CHANNEL_ID) != null) return",
    "if (manager.getNotificationChannel(CHANNEL_ID) == null) return")
# the guard stops short-circuiting (no early return ⇒ it decides nothing)
NOTIFIER_NO_RETURN = NOTIFIER_OK.replace(
    "if (manager.getNotificationChannel(CHANNEL_ID) != null) return",
    "if (manager.getNotificationChannel(CHANNEL_ID) != null) Log.i(TAG, \"have channel\")")
# the creation moved inside the branch the guard skips
NOTIFIER_CREATE_INSIDE = NOTIFIER_OK.replace(
    """        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)""",
    """        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            val channel = NotificationChannel(CHANNEL_ID, "n", 0)
            manager.createNotificationChannel(channel)
            return
        }""")
# the creation path is dead
NOTIFIER_DEAD_CREATE = NOTIFIER_OK.replace(
    "manager.createNotificationChannel(channel)",
    "if (false) manager.createNotificationChannel(channel)")
# no lookup at all — the guard is gone
NOTIFIER_NO_LOOKUP = NOTIFIER_OK.replace(
    "if (manager.getNotificationChannel(CHANNEL_ID) != null) return",
    "if (Build.VERSION.SDK_INT > 0) Log.i(TAG, \"channel\")")

# clean equivalents
NOTIFIER_OK_SAFE_CALL = NOTIFIER_OK.replace(
    "if (manager.getNotificationChannel(CHANNEL_ID) != null) return",
    "manager.getNotificationChannel(CHANNEL_ID)?.let { return }")
NOTIFIER_OK_ELVIS = NOTIFIER_OK.replace(
    """        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)""",
    """        val existing = manager.getNotificationChannel(CHANNEL_ID) ?: run {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "n", 0))
            return
        }
        Log.i(TAG, existing.id)""")

SHELL_OK = """
package p

internal object PersistentShell {
    fun startProcess(context: Context): Process {
        val cmd = ArrayList<String>()
        cmd.add("/bin/sh")
        val processBuilder = ProcessBuilder(cmd)
        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        env["TMPDIR"] = "/tmp"
        env["TMP"] = "/tmp"
        env["TERM"] = "dumb"
        return processBuilder.start()
    }
}
"""

# W-03's witness: the guest temp dir pointed back at the host cache.
SHELL_HOST_TMPDIR = SHELL_OK.replace(
    'env["TMPDIR"] = "/tmp"', 'env["TMPDIR"] = PRootKernel.getProotTmpDir(context).absolutePath')
SHELL_HOST_TMP = SHELL_OK.replace(
    'env["TMP"] = "/tmp"', 'env["TMP"] = File(context.cacheDir, "proot_tmp").absolutePath')
SHELL_MISSING_TMPDIR = SHELL_OK.replace('        env["TMPDIR"] = "/tmp"\n', "")
SHELL_MISSING_TMP = SHELL_OK.replace('        env["TMP"] = "/tmp"\n', "")
# the "unify them" mistake: the host side is dragged onto the guest path
SHELL_HOST_SIDE_GUEST = SHELL_OK.replace(
    'env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath',
    'env["PROOT_TMP_DIR"] = "/tmp"')
SHELL_MISSING_HOST = SHELL_OK.replace(
    '        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath\n', "")

# clean equivalents
SHELL_OK_CONST = SHELL_OK.replace(
    'env["TMPDIR"] = "/tmp"\nenv["TMP"] = "/tmp"', "")
SHELL_OK_CONST = SHELL_OK.replace(
    '        env["TMPDIR"] = "/tmp"\n        env["TMP"] = "/tmp"\n',
    '        env["TMPDIR"] = GUEST_TMP\n        env["TMP"] = GUEST_TMP\n')
SHELL_OK_CONST = 'private const val GUEST_TMP = "/tmp"\n\n' + SHELL_OK_CONST


def _selftest_arms():
    """(name, findings, needles, clean) — a violating arm must be red on ITS criterion."""
    B = lambda src: check_budget_source(src)     # noqa: E731
    N = lambda src: check_notifier_source(src)   # noqa: E731
    S = lambda src: check_shell_source(src)      # noqa: E731
    return [
        ("C1 shipped shape is green", B(BUDGET_OK), [], True),
        ("C1 inline baseline read is green", B(BUDGET_OK_INLINE), [], True),
        ("C1 named arguments are green", B(BUDGET_OK_NAMED), [], True),
        ("W-01 sample as the 3rd argument is red", B(BUDGET_SAMPLE_ARG),
         ["IS the current sample"], False),
        ("W-01b sample behind the binding is red", B(BUDGET_SAMPLE_BINDING),
         ["bound to the CURRENT sample"], False),
        ("W-01c read of the wrong key is red", B(BUDGET_WRONG_KEY),
         ["not the baseline the notify branch persists"], False),
        ("W-01d no write-back of the sampled bytes is red", B(BUDGET_NO_WRITE_BACK),
         ["never persists the sampled bytes"], False),
        ("W-01e a `var` baseline is red", B(BUDGET_VAR_BASELINE),
         ["is a `var`"], False),
        ("W-01f untraceable baseline is red", B(BUDGET_UNTRACEABLE),
         ["does not trace to a persisted"], False),
        ("W-01g a value DERIVED from the read is red", B(BUDGET_DERIVED_READ),
         ["not a persisted baseline read"], False),
        ("C2 shipped shape is green", N(NOTIFIER_OK), [], True),
        ("C2 `?.let { return }` is green", N(NOTIFIER_OK_SAFE_CALL), [], True),
        ("C2 elvis-creates is green", N(NOTIFIER_OK_ELVIS), [], True),
        ("W-02 inverted guard is red", N(NOTIFIER_FLIPPED),
         ["guard is INVERTED"], False),
        ("W-02b guard without an early return is red", N(NOTIFIER_NO_RETURN),
         ["does not end in an early `return`"], False),
        ("W-02c creation inside the guarded branch is red", N(NOTIFIER_CREATE_INSIDE),
         ["sits INSIDE the guard branch"], False),
        ("W-02d dead creation path is red", N(NOTIFIER_DEAD_CREATE),
         ["not reachable AFTER the existence guard"], False),
        ("W-02e no existence lookup at all is red", N(NOTIFIER_NO_LOOKUP),
         ["never looks its channel up"], False),
        ("C3 shipped shape is green", S(SHELL_OK), [], True),
        ("C3 shared guest literal constant is green", S(SHELL_OK_CONST), [], True),
        ("W-03 host path in TMPDIR is red", S(SHELL_HOST_TMPDIR),
         ["is a HOST path derivation"], False),
        ("W-03b host path in TMP is red", S(SHELL_HOST_TMP),
         ["is a HOST path derivation"], False),
        ("W-03c missing TMPDIR is red", S(SHELL_MISSING_TMPDIR),
         ["never assigns"], False),
        ("W-03d missing TMP is red", S(SHELL_MISSING_TMP),
         ["never assigns"], False),
        ("W-03e host side dragged onto the guest path is red", S(SHELL_HOST_SIDE_GUEST),
         ["is not a host path derivation"], False),
        ("W-03f missing host temp dir is red", S(SHELL_MISSING_HOST),
         ["proot itself runs on the host"], False),
    ]


def selftest():
    bad = 0
    arms = _selftest_arms()
    for name, findings, needles, clean in arms:
        text = "\n".join(findings)
        missing = [n for n in needles if n not in text]
        if clean:
            ok = not findings
        else:
            ok = bool(findings) and not missing
        if not ok:
            bad += 1
            print("  [FAIL] %s: %d findings, missing needles %s" % (name, len(findings), missing))
            for f in findings:
                print("         %s" % f)
        else:
            print("  [OK] %s (%d findings)" % (name, len(findings)))
    print("SELFTEST=%s (%d arms)" % ("PASS" if bad == 0 else "FAIL", len(arms)))
    print(MARKER if bad == 0 else "STORAGE_BUDGET_SELFTEST=FAIL (%d arms)" % len(arms))
    return 1 if bad else 0


# ── main ────────────────────────────────────────────────────────────────────
def main():
    args = list(sys.argv[1:])
    if "--selftest" in args:
        return selftest()
    if "--root" in args:
        i = args.index("--root")
        if i + 1 >= len(args):
            print("usage: --root <repo_root>", file=sys.stderr)
            return 2
        root = args[i + 1]
    elif len(args) == 1:
        root = args[0]
    elif not args:
        root = "."
    else:
        print("usage: <repo_root> | --root <repo_root> | --selftest", file=sys.stderr)
        return 2
    findings = []
    for rel, checker in ((TARGET_BUDGET, check_budget_source),
                         (TARGET_NOTIFIER, check_notifier_source),
                         (TARGET_SHELL, check_shell_source)):
        path = os.path.join(root, rel)
        try:
            with open(path, encoding="utf-8") as fh:
                src = fh.read()
        except OSError as exc:
            findings.append("%s: cannot read the file (%s) — fail closed"
                            % (rel.split("/")[-1], exc))
            continue
        findings += checker(src)
    if findings:
        print("❌ storage-budget guard findings:")
        for f in findings:
            print("   - %s" % f)
        return 1
    print("✅ storage-budget guard: runDailyCheck hands shouldNotify the PERSISTED baseline, "
          "ensureChannel guards on channel existence with a reachable creation, and "
          "PersistentShell keeps TMPDIR/TMP on the guest path with PROOT_TMP_DIR on the host")
    return 0


if __name__ == "__main__":
    sys.exit(main())
