#!/usr/bin/env python3
"""Thinking-level wiring guard — RikkaMinis CI scan gate.

## Why this gate exists

2026-09-22, for real, on branch `fix/thinking-level-ui-truth` (ae5517cc). That
branch fixed four thinking-level defects by extracting ONE rule
(`effectiveThinkingLevel`) and deriving every consumer from it — a genuinely
good shape. Then it split the composer picker into two parameters:

    current   -> drives the capsule highlight
    requested -> drives the orange up-arrow ("your setting is capped here")

...and wired `requested` to `viewModel.thinkingLevel`, which the SAME branch had
just redefined to BE the effective level. So `requested == current`, the
`isCappedBy(requested, …)` test was trivially false, and the up-arrow — the one
affordance that explains "your Max is capped at High by this model" — became
unreachable. Reachable combinations went 15 -> 0 with no exception, no log line,
and no failing test.

The branch's own KDoc warned about this exact mistake ("It cannot be derived
from [current] … the cue would never appear"). Documentation is not a gate.

## Why the unit tests cannot catch it

`ThinkingLevelPicker` is `@Composable internal`. The Compose call site is not
compilable in the sandbox JVM harness, and the branch's 13 tests all exercise
the pure rule, not the wiring. A pure function can be perfectly correct while
its only caller feeds it the wrong argument. That gap is what this gate closes:
it reads the call site as TEXT and checks the argument's origin.

## What it checks

1. Call-site provenance — in every `ThinkingLevelPicker(` call, the `requested`
   argument must NOT be an effective-level expression (`viewModel.thinkingLevel`,
   `.effectiveThinkingLevel`, or `effectiveThinkingLevel`). It must be a raw
   source (`requestedThinkingLevel`, `_thinkingLevel`, …).
2. The `current` argument must be the effective one, so the two cannot be
   silently collapsed back into a single value.
3. Inside the picker body, the clamp predicate must take `requested`, and the
   tap rule must key off `requested` (not the highlight `isHighlighted`).
4. Measured-gateway effort truth (2026-10-22, deep-audit-1041 A-04) — see below.

Escape hatch: `thinking-wiring-ok: <reason>` (picker family) /
`thinking-effort-ok: <reason>` (effort family) on the line, the line above, or
anywhere inside the annotated span/file.

## Second family: the measured-gateway effort truth table

`GatewayEffortTruth` exists because the `reasoning_effort` enum is per MODEL and
per GATEWAY, and models.dev under-declares it. It was written, measured, tabled
and unit-tested — and then the one place that builds the request still listed the
gateway hosts BY HAND:

    // OpenAIProvider.injectThinkingParams, before the fix
    when (host) {
        ...
        "token.sensenova.cn", "api.sensenova.cn" -> { ... resolveTier(...) ... }
    }

The second measured gateway (`api.senseaudio.cn`, whose enum is coarser but has
higher tiers and which 400s `medium`) therefore never reached the table: it fell
through to the rule engine, `medium` went out unclamped, and the tiers the
gateway does serve were clamped away by whatever stale set the catalog declared.
Every unit test passed — they exercise the table, not the dispatch. A table one
consumer consults and another re-implements by hand is not a single source of
truth; the tests cannot tell the difference, so this gate does.

Criteria (all derived from the table, never from a written-down host list):

4. No `*_HOSTS` host literal may appear in the provider's code: the measured-host
   set is owned by `GatewayEffortTruth`, and the provider must dispatch on
   `isMeasuredHost(host)`. This rejects both the original defect and the tempting
   one-line "fix" that adds a third literal to the same `when`.
5. The provider must consult `GatewayEffortTruth.` at all (a table nobody reads
   cannot be enforced on the wire).
6. `resolveTier`'s LAST-RESORT set must be chosen by `host`: one global fallback
   now serves two gateways that disagree about `medium` on the same model id.
7. Every `*_HOSTS` family must have its own `*_FALLBACK_TIERS` symbol, so adding
   a measured gateway to the table cannot silently inherit the wrong last resort.

Fail-closed: if the host set or `resolveTier` cannot be read, or one of the two
files exists while the other does not, the family reports a violation instead of
checking nothing. Minimal trees that contain neither file (see test_scan fixtures)
skip it by design.

Usage: python3 scripts/scan/thinking_wiring_guard.py <repo_root>
       python3 scripts/scan/thinking_wiring_guard.py --selftest [repo_root]
Exit:  0 = clean, 1 = violation(s) found
"""
import os
import re
import shutil
import sys
import tempfile

LINE_COMMENT_RE = re.compile(r"//.*$")
ESCAPE_RE = re.compile(r"thinking-wiring-ok\s*:")
EFFORT_ESCAPE_RE = re.compile(r"thinking-effort-ok\s*:")

# ── measured-gateway effort truth table (deep-audit-1041 A-04) ───────────────
TRUTH_REL = "src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/GatewayEffortTruth.kt"
PROVIDER_REL = "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt"
TRUTH_OBJECT = "GatewayEffortTruth."
HOSTS_DECL_RE = re.compile(
    r"\b(?:private\s+)?val\s+(\w+_HOSTS)\b(?::[^=\n]+)?\s*=\s*setOf\(([^)]*)\)"
)
FALLBACK_DECL_RE = re.compile(r"\b(?:private\s+)?val\s+(\w+_FALLBACK_TIERS)\b")
HOSTS_SUFFIX = "_HOSTS"
FALLBACK_SUFFIX = "_FALLBACK_TIERS"
STR_LITERAL_RE = re.compile(r'"([^"\n]+)"')
RESOLVE_SIG = "fun resolveTier("
# How far past the final `?:` the host test must appear: the operand itself, not
# an unrelated later statement.
FALLBACK_WINDOW = 240

# Expressions that denote the EFFECTIVE level (capability + ceiling folded in).
EFFECTIVE_EXPR_RE = re.compile(
    r"viewModel\.thinkingLevel\b|\.effectiveThinkingLevel\b|\beffectiveThinkingLevel\s*\("
)

# Local bindings, resolved by provenance rather than by name shape. A gate that
# only pattern-matches the argument's spelling is defeated by a rename — which
# is exactly how the first version of THIS gate went green on the real bug
# (the caller's local was named `effectiveThinkingLevelState`, so no pattern
# fired). Track what each `val` was bound FROM.
# `var` is included on purpose: an effective value laundered through a MUTABLE
# local is the same laundering. Witness C1 wrote
#     var _thinkingLevel = viewModel.thinkingLevel.value   // EFFECTIVE level
#     …  requested = _thinkingLevel
# and went green — the binding table only read `val`, so `_thinkingLevel` was
# taken at face value and its name matched the raw-source shape.
BINDING_RE = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::\s*[\w<>?., ]+)?\s*(?:=|by)\s*"
)

# How far an initializer is followed: enough for a wrapped expression, short
# enough that a runaway capture cannot swallow the rest of the file.
EXPR_MAX = 400
# A depth-0 newline ends a Kotlin expression unless the next line CONTINUES it.
CONTINUATION_CHARS = "?:.+-*/%&|=<>!,"

STR_LIT_RE = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
CONCAT_GAP_RE = re.compile(r"\s*\+\s*")
# `host` parked in a local that the operand's value never reads.
DEAD_HOST_BIND_RE = re.compile(
    r"\b(?:val|var)\s+[A-Za-z_]\w*\s*(?::[^=\n]+)?=\s*host\b"
)
EFFECTIVE_SOURCE_RE = re.compile(
    r"viewModel\.thinkingLevel\b|\beffectiveThinkingLevel\b"
)
RAW_SOURCE_RE = re.compile(
    r"viewModel\.requestedThinkingLevel\b|\b_?thinkingLevel\b"
)

PICKER_SIG = "internal fun ThinkingLevelPicker("


def strip_comments(text: str) -> str:
    """Drop // line comments so a commented-out line never satisfies a check."""
    out = []
    for line in text.splitlines():
        out.append(LINE_COMMENT_RE.sub("", line))
    return "\n".join(out)


def initializer_text(src: str, start: int, limit: int = EXPR_MAX) -> str:
    """The initializer expression at `start` — bracket- and continuation-aware.

    A single-line capture is what made the provenance table a spelling check:
    `val req = run {\\n viewModel.thinkingLevel.value\\n }` read as `run {` and
    resolved to nothing (witness C1's second shape), while the argument name
    still looked legitimate. Depth tracking keeps a brace/paren body whole, and a
    depth-0 newline ends the expression unless the next line continues it.
    """
    depth = 0
    i = start
    out = []
    while i < len(src) and len(out) < limit:
        ch = src[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        if ch == "\n" and depth == 0:
            j = i + 1
            while j < len(src) and src[j] in " \t":
                j += 1
            nxt = src[j] if j < len(src) else ""
            if nxt not in CONTINUATION_CHARS:
                break
            out.append(" ")
            i += 1
            continue
        if ch == ";" and depth == 0:
            break
        out.append(ch)
        i += 1
    return "".join(out).strip()


def binding_map(src: str):
    """name -> the expression it was bound from (last assignment wins)."""
    m = {}
    for match in BINDING_RE.finditer(src):
        m[match.group(1)] = initializer_text(src, match.end())
    return m


def folded_string_literals(text: str) -> set:
    """Every string literal, with `+`-joined neighbours folded into one value.

    `host == "api.senseaudio" + ".cn"` is the same host test with the literal
    typed in two pieces, and `f'"{host}"' in src` reads the host as absent — the
    hole witness C4 used. Folding moves the comparison from how the literal was
    typed onto the value the code compares against.
    """
    toks = [(m.start(), m.end(), m.group(1)) for m in STR_LIT_RE.finditer(text)]
    out = set()
    cur = None
    for start, end, value in toks:
        if cur is not None and CONCAT_GAP_RE.fullmatch(text[cur[1]:start]):
            cur = (cur[0], end, cur[2] + value)
        else:
            if cur is not None:
                out.add(cur[2])
            cur = (start, end, value)
    if cur is not None:
        out.add(cur[2])
    return out


def host_is_consumed(operand: str) -> bool:
    """True when `host` is READ where it decides the operand's value.

    `run { val ignored = host; SENSENOVA_FALLBACK_TIERS }` mentions the host and
    keeps the one global set: the value does not depend on it, so the two
    gateways still share a last resort (witness C6). An argument
    (`fallbackTiersFor(host)`), a condition (`if (host in …)`, `host == "…"`) or a
    receiver (`host.startsWith(…)`) is a read; a local assigned from `host` and
    never consulted is not.
    """
    dead = [(m.start(), m.end()) for m in DEAD_HOST_BIND_RE.finditer(operand)]
    for m in re.finditer(r"\bhost\b", operand):
        if not any(s <= m.start() < e for s, e in dead):
            return True
    return False


def resolve_provenance(expr: str, bindings, depth: int = 0) -> str:
    """Follow a local `val` chain down to its ultimate source expression.

    Without this the gate is a spelling check: rename the caller's local to
    `effectiveThinkingLevelState` and the argument looks innocent while still
    carrying the effective level (verified — the first version went green on
    the exact bug it exists to catch).
    """
    if depth > 8:
        return expr
    expr = expr.strip()
    if not re.fullmatch(r"[A-Za-z_]\w*", expr):
        return expr
    src = bindings.get(expr)
    if src is None:
        return expr
    return resolve_provenance(src, bindings, depth + 1)


def split_top_level_args(arglist: str):
    """Split a Kotlin argument list on top-level commas (depth-aware)."""
    args, depth, cur = [], 0, ""
    for ch in arglist:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            args.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        args.append(cur)
    return args


def find_call_args(src: str, call: str):
    """Yield the argument-list text of every `call(` in src, depth-aware."""
    idx = 0
    while True:
        i = src.find(call, idx)
        if i < 0:
            return
        j = i + len(call)
        depth = 1
        while j < len(src) and depth:
            if src[j] == "(":
                depth += 1
            elif src[j] == ")":
                depth -= 1
            j += 1
        yield i, src[i + len(call):j - 1]
        idx = j


def raw_call_span(raw: str, anchor: int, call: str) -> str:
    """The call's source span taken from the ORIGINAL text.

    `find_call_args` works on comment-stripped source, so its arglist length is
    shorter than the real text — using it to slice `raw` for an inline escape
    comment falls short of the marker. Walk the parens on `raw` instead.
    """
    i = raw.find(call, anchor)
    if i < 0:
        return ""
    j = i + len(call)
    depth = 1
    while j < len(raw) and depth:
        if raw[j] == "(":
            depth += 1
        elif raw[j] == ")":
            depth -= 1
        j += 1
    return raw[i:j]


def find_function_body(src: str, signature: str):
    """Return the body text of the function starting at `signature`."""
    i = src.find(signature)
    if i < 0:
        return None, None
    depth = 0
    started = False
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
            started = True
        elif src[j] == "}":
            depth -= 1
            if started and depth == 0:
                return src[i:j + 1], i
    return None, None


def check_measured_gateway_effort(root: str, violations: list) -> int:
    """Criteria 4-7 — the measured-gateway effort truth table. Returns family count.

    Everything is DERIVED: the host set comes from the table's own `*_HOSTS`
    declarations, so a third measured gateway extends the prohibition with no
    edit here (and a table that cannot be read is a violation, not a silent pass).
    """
    truth_path = os.path.join(root, TRUTH_REL)
    prov_path = os.path.join(root, PROVIDER_REL)
    has_truth = os.path.isfile(truth_path)
    has_prov = os.path.isfile(prov_path)
    if not has_truth and not has_prov:
        return 0   # minimal fixture tree: the family is not installed here
    if not has_truth or not has_prov:
        missing = TRUTH_REL if not has_truth else PROVIDER_REL
        violations.append(
            f"{missing}: missing while its counterpart exists — the measured-gateway "
            "effort checks cannot run, so a now-broken dispatch would read as clean"
        )
        return 0

    with open(truth_path, encoding="utf-8") as f:
        truth_raw = f.read()
    with open(prov_path, encoding="utf-8") as f:
        prov_raw = f.read()
    truth = strip_comments(truth_raw)
    prov = strip_comments(prov_raw)

    families = HOSTS_DECL_RE.findall(truth)          # [(symbol, setOf body)]
    hosts = []
    for name, body in families:
        family_hosts = STR_LITERAL_RE.findall(body)
        if not family_hosts:
            violations.append(
                f"{TRUTH_REL}: {name} declares no host literal — a measured gateway "
                "that silently stops being measured is a regression the wire absorbs "
                "without a trace (its enum then goes unenforced)"
            )
        hosts.extend(family_hosts)
    if not families or not hosts:
        violations.append(
            f"{TRUTH_REL}: no `*_HOSTS = setOf(\"...\")` declaration could be read — "
            "the gate would be comparing the provider against an empty host set, which "
            "passes for every shape (fail-closed)"
        )
        return 0

    # ── 4 & 5: the provider delegates, it does not restate ───────────────
    if not EFFORT_ESCAPE_RE.search(prov):
        # Compare the VALUES the provider compares against, not the literal's
        # spelling: `"api.senseaudio" + ".cn"` folds back to the host.
        folded = folded_string_literals(prov)
        found = sorted({h for h in hosts if h in folded})
        for h in found:
            violations.append(
                f"{PROVIDER_REL}: host literal {h!r} is written into the provider — the "
                "measured-host set is owned by GatewayEffortTruth. Dispatch on "
                "GatewayEffortTruth.isMeasuredHost(host); a second copy of the list is "
                "how api.senseaudio.cn stayed unenforced on the wire. "
                "(thinking-effort-ok: <reason> to override.)"
            )
        if TRUTH_OBJECT not in prov:
            violations.append(
                f"{PROVIDER_REL}: never consults {TRUTH_OBJECT} — a measured gateway's "
                "reasoning_effort enum is then unenforced where the request is built"
            )

    # ── 6: the last resort is chosen by host ─────────────────────────────
    body, _ = find_function_body(truth, RESOLVE_SIG)
    if body is None:
        violations.append(
            f"{TRUTH_REL}: `{RESOLVE_SIG}` not found — the last-resort check cannot run "
            "(fail-closed)"
        )
    elif not EFFORT_ESCAPE_RE.search(truth):
        cut = body.rfind("?:")
        if cut < 0:
            violations.append(
                f"{TRUTH_REL}: resolveTier has no `?:` fallback chain to inspect "
                "(fail-closed)"
            )
        else:
            # The LAST-RESORT OPERAND, not a character window: a window is
            # satisfied by any later statement that happens to mention `host`
            # (witness C6 laundered it through `val ignored = host`), and it can
            # also cut the operand in half.
            op = initializer_text(body, cut + 2, limit=FALLBACK_WINDOW * 2)
            if not op:
                violations.append(
                    f"{TRUTH_REL}: resolveTier's last resort could not be read "
                    "(fail-closed — an unreadable fallback would pass every shape)"
                )
            elif not host_is_consumed(op):
                violations.append(
                    f"{TRUTH_REL}: resolveTier's last resort does not depend on `host` "
                    f"— one shared fallback then serves gateways whose enums disagree "
                    f"(seen: {op.splitlines()[0][:70]!r}). Choose it per "
                    "gateway family. (thinking-effort-ok: <reason> to override.)"
                )

    # ── 7: every host family owns a fallback ─────────────────────────────
    declared = set(FALLBACK_DECL_RE.findall(truth))
    for name, _ in families:
        want = name[: -len(HOSTS_SUFFIX)] + FALLBACK_SUFFIX
        if want not in declared:
            violations.append(
                f"{TRUTH_REL}: {name} has no `{want}` — a measured gateway added "
                "without its own last resort silently inherits the previous "
                "gateway's enum (the two disagree on `medium`)."
            )
    return len(families)


def _selftest_wiring_fixture():
    """Minimal picker-call tree, mirroring the shape test_scan.py builds."""
    pkg = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/"
    caller = (
        "package com.rikkaminis.app.ui.chat\n"
        "class ChatInputArea {\n"
        "    fun row(viewModel: ChatViewModel) {\n"
        "        val requestedThinkingLevelState by viewModel.requestedThinkingLevel.collectAsState()\n"
        "        ThinkingLevelPicker(\n"
        "            current = viewModel.effectiveThinkingLevel,\n"
        "            requested = requestedThinkingLevelState,\n"
        "            availableLevels = viewModel.availableThinkingLevels,\n"
        "            onSelect = { },\n"
        "        )\n"
        "    }\n"
        "}\n"
    )
    body = (
        "package com.rikkaminis.app.ui.chat\n"
        "internal fun ThinkingLevelPicker(\n"
        "    current: ThinkingLevel,\n"
        "    requested: ThinkingLevel,\n"
        "    availableLevels: List<ThinkingLevel>,\n"
        "    onSelect: (ThinkingLevel) -> Unit,\n"
        ") {\n"
        "    val maxAvailable = availableLevels.lastOrNull { it != ThinkingLevel.OFF }\n"
        "    val isClamped = maxAvailable != null && isCappedBy(requested, maxAvailable)\n"
        "    availableLevels.forEach { level ->\n"
        "        onSelect(thinkingTapTarget(level, requested))\n"
        "    }\n"
        "}\n"
    )
    return {pkg + "ChatInputArea.kt": caller, pkg + "ChatComposerWidgets.kt": body}


def _wiring_tree(caller: str = None):
    """The picker fixture, optionally with a rewritten caller.

    wide3 witness arms live here: the picker family's provenance table is what
    witnesses C1 (var-bound / multi-line-bound effective level) attacked.
    """
    tree = dict(_selftest_wiring_fixture())
    if caller is not None:
        pkg = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/"
        tree[pkg + "ChatInputArea.kt"] = caller
    return tree


EFFORT_TRUTH_CLEAN = """package com.rikkaminis.app.provider.thinking

object GatewayEffortTruth {
    private val SENSENOVA_HOSTS = setOf("token.sensenova.cn", "api.sensenova.cn")
    private val SENSEAUDIO_HOSTS = setOf("api.senseaudio.cn")
    val SENSENOVA_FALLBACK_TIERS = listOf("none", "low", "medium", "high", "xhigh")
    val SENSEAUDIO_FALLBACK_TIERS = listOf("none", "low", "high", "xhigh", "max")

    fun isMeasuredHost(host: String): Boolean = true

    private fun fallbackTiersFor(host: String): List<String> =
        if (host in SENSEAUDIO_HOSTS) SENSEAUDIO_FALLBACK_TIERS else SENSENOVA_FALLBACK_TIERS

    fun resolveTier(host: String, modelId: String, declaredTiers: List<String>?): String {
        val tiers = tiersFor(host, modelId)
            ?: declaredTiers?.takeIf { it.isNotEmpty() }
            ?: fallbackTiersFor(host)
        return ThinkingEffortLadder.clampEffort(tiers.first())
    }
}
"""

EFFORT_PROVIDER_CLEAN = """package com.rikkaminis.app.provider.openai

class OpenAIProvider {
    fun inject(host: String, body: MutableMap<String, String>) {
        if (GatewayEffortTruth.isMeasuredHost(host)) {
            body["reasoning_effort"] = GatewayEffortTruth.resolveTier(host, "m", null)
        }
    }
}
"""


def _effort_tree(provider=EFFORT_PROVIDER_CLEAN, truth=EFFORT_TRUTH_CLEAN, drop=()):
    tree = dict(_selftest_wiring_fixture())
    if "provider" not in drop:
        tree[PROVIDER_REL] = provider
    if "truth" not in drop:
        tree[TRUTH_REL] = truth
    return tree


WIRING_CALLER_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt"
)
WIRING_REQUESTED_DECL = (
    "        val requestedThinkingLevelState by "
    "viewModel.requestedThinkingLevel.collectAsState()\n"
)


def _caller_with(binding: str, arg: str) -> str:
    """The clean caller with a different `requested` binding/argument."""
    base = _selftest_wiring_fixture()[WIRING_CALLER_REL]
    base = base.replace(WIRING_REQUESTED_DECL, binding)
    return base.replace(
        "            requested = requestedThinkingLevelState,\n",
        "            requested = %s,\n" % arg,
    )


def selftest(root: str | None = None) -> int:
    """Negative arms; each must red on ITS criterion only."""
    cases = [
        ("clean text-book dispatch",
         _effort_tree(), 0, None),
        ("provider hardcodes the first gateway's hosts (the original defect)",
         _effort_tree(provider=EFFORT_PROVIDER_CLEAN.replace(
             "if (GatewayEffortTruth.isMeasuredHost(host)) {",
             'if (host == "token.sensenova.cn") {')),
         1, "host literal 'token.sensenova.cn'"),
        ("provider gains a third literal arm (the tempting one-line fix)",
         _effort_tree(provider=EFFORT_PROVIDER_CLEAN.replace(
             "if (GatewayEffortTruth.isMeasuredHost(host)) {",
             'if (host == "api.senseaudio.cn") {')),
         1, "host literal 'api.senseaudio.cn'"),
        ("provider stops consulting the table at all",
         _effort_tree(provider=EFFORT_PROVIDER_CLEAN
                      .replace("GatewayEffortTruth.isMeasuredHost(host)", "true")
                      .replace("GatewayEffortTruth.resolveTier(host, \"m\", null)", '"low"')),
         1, "never consults GatewayEffortTruth."),
        ("resolveTier falls back to one global set",
         _effort_tree(truth=EFFORT_TRUTH_CLEAN.replace(
             "?: fallbackTiersFor(host)", "?: SENSENOVA_FALLBACK_TIERS")),
         1, "last resort does not depend on `host`"),
        ("a third measured gateway arrives without its own fallback",
         _effort_tree(truth=EFFORT_TRUTH_CLEAN.replace(
             'private val SENSEAUDIO_HOSTS = setOf("api.senseaudio.cn")',
             'private val SENSEAUDIO_HOSTS = setOf("api.senseaudio.cn")\n'
             '    private val THIRDGATEWAY_HOSTS = setOf("api.third.example")')),
         1, "has no `THIRDGATEWAY_FALLBACK_TIERS`"),
        ("host set cannot be derived (fail-closed)",
         _effort_tree(truth=EFFORT_TRUTH_CLEAN
                      .replace('private val SENSENOVA_HOSTS = setOf("token.sensenova.cn", "api.sensenova.cn")',
                               "private val SENSENOVA_HOSTS = HOSTS_FROM_CONFIG")
                      .replace('private val SENSEAUDIO_HOSTS = setOf("api.senseaudio.cn")',
                               "private val SENSEAUDIO_HOSTS = HOSTS_FROM_CONFIG")),
         1, "no `*_HOSTS = setOf"),
        ("host set declared but empty (fail-closed)",
         _effort_tree(truth=EFFORT_TRUTH_CLEAN.replace(
             'setOf("api.senseaudio.cn")', "setOf()")),
         1, "declares no host literal"),
        ("provider present, table deleted (fail-closed)",
         _effort_tree(drop=("truth",)), 1, "missing while its counterpart exists"),
        ("table present, provider deleted (fail-closed)",
         _effort_tree(drop=("provider",)), 1, "missing while its counterpart exists"),
        ("picker-only minimal tree skips the family",
         dict(_selftest_wiring_fixture()), 0, None),
        # ── wide3 witness arms (external adversarial audit, 2026-10-25) ──
        ("effective level laundered through a `var` local (witness C1)",
         _wiring_tree(_caller_with(
             "        var _thinkingLevel = viewModel.thinkingLevel.value\n",
             "_thinkingLevel")),
         1, "resolves to the EFFECTIVE level"),
        ("effective level bound across lines inside `run { }` (witness C1)",
         _wiring_tree(_caller_with(
             "        val req = run {\n"
             "            viewModel.thinkingLevel.value\n"
             "        }\n",
             "req")),
         1, "resolves to the EFFECTIVE level"),
        ("provider splits a measured host literal with `+` (witness C4)",
         _effort_tree(provider=EFFORT_PROVIDER_CLEAN.replace(
             "if (GatewayEffortTruth.isMeasuredHost(host)) {",
             'if (host == "api.senseaudio" + ".cn" '
             "|| GatewayEffortTruth.isMeasuredHost(host)) {")),
         1, "host literal 'api.senseaudio.cn'"),
        ("resolveTier mentions host but keeps one global fallback (witness C6)",
         _effort_tree(truth=EFFORT_TRUTH_CLEAN.replace(
             "?: fallbackTiersFor(host)",
             "?: run { val ignored = host; SENSENOVA_FALLBACK_TIERS }")),
         1, "last resort does not depend on `host`"),
    ]
    failures = []
    for label, tree, want_rc, want_text in cases:
        d = tempfile.mkdtemp(prefix="twg-selftest-")
        try:
            for rel, text in tree.items():
                p = os.path.join(d, rel)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as f:
                    f.write(text)
            rc, out = _run_scan(d)
            ok = rc == want_rc and (want_text is None or want_text in out)
            if not ok:
                failures.append(f"{label}: rc={rc} want={want_rc}\n{out}")
        finally:
            shutil.rmtree(d, ignore_errors=True)

    if root:
        rc, out = _run_scan(root)
        if rc != 0:
            failures.append(f"real tree ({root}): rc={rc}\n{out}")

    if failures:
        print("❌ thinking_wiring_guard selftest:")
        for f in failures:
            print(f"   • {f}")
        return 1
    print(f"THINKING_EFFORT_SELFTEST=PASS ({len(cases)} fixtures"
          f"{' + real tree' if root else ''})")
    return 0


def _run_scan(root: str):
    """(exit_code, output) for `root` — used by the selftest arms."""
    try:
        return scan(root)
    except Exception as e:      # a crash must read as red, never as an empty pass
        return 1, f"❌ thinking_wiring_guard raised: {type(e).__name__}: {e}"


def scan(root: str):
    """Run every criterion over `root`. Returns (exit_code, output_text)."""
    kt_root = os.path.join(root, "src/android/app/src/main/java")
    violations = []
    checked_calls = 0

    for dirpath, _, filenames in os.walk(kt_root):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8") as f:
                raw = f.read()
            src = strip_comments(raw)
            bindings = binding_map(src)
            lines = raw.splitlines()

            def escaped(offset: int, span_text: str | None = None) -> bool:
                """`thinking-wiring-ok:` on this line, the one above, or anywhere
                inside the annotated call/function span."""
                if span_text and ESCAPE_RE.search(span_text):
                    return True
                ln = src[:offset].count("\n")
                for k in (ln, ln - 1):
                    if 0 <= k < len(lines) and ESCAPE_RE.search(lines[k]):
                        return True
                return False

            # ── 1 & 2: call-site provenance ──────────────────────────────
            for offset, arglist in find_call_args(src, "ThinkingLevelPicker("):
                # Skip the declaration itself (its line reads `fun ThinkingLevelPicker(`).
                line_start = src.rfind("\n", 0, offset) + 1
                if "fun ThinkingLevelPicker" in src[line_start:offset + 30]:
                    continue
                # Count BEFORE the escape check: the rot guard below asks "is the
                # picker still wired anywhere?", which an escape hatch must not
                # answer. (Counting after it made an escaped call look like a
                # deleted picker.)
                checked_calls += 1
                # The escape hatch may sit on the offending argument's own line,
                # which is well below the call's opening paren — so scan the whole
                # call span, taken from the ORIGINAL text (see raw_call_span).
                call_span = raw_call_span(raw, offset, "ThinkingLevelPicker(")
                if escaped(offset, call_span):
                    continue
                named = {}
                for a in split_top_level_args(arglist):
                    if "=" in a:
                        k, v = a.split("=", 1)
                        named[k.strip()] = v.strip()

                req = named.get("requested")
                cur = named.get("current")
                ln = src[:offset].count("\n") + 1
                # Resolve through local `val` bindings — see resolve_provenance.
                req_src = resolve_provenance(req, bindings) if req else ""
                cur_src = resolve_provenance(cur, bindings) if cur else ""
                if req is None:
                    violations.append(
                        f"{rel}:{ln}: ThinkingLevelPicker call has no `requested` argument"
                    )
                elif EFFECTIVE_SOURCE_RE.search(req_src):
                    violations.append(
                        f"{rel}:{ln}: `requested` resolves to the EFFECTIVE level "
                        f"({req!r} <- {req_src!r}). isCappedBy() then compares the "
                        "level against itself, so the orange up-arrow is unreachable "
                        "(15 -> 0 of 64 combos, silently). Feed the RAW choice "
                        "(viewModel.requestedThinkingLevel)."
                    )
                elif not RAW_SOURCE_RE.search(req_src):
                    violations.append(
                        f"{rel}:{ln}: `requested` ({req!r} <- {req_src!r}) does not "
                        "resolve to a raw stored thinking level; the up-arrow has no "
                        "honest source."
                    )
                if cur is not None and not EFFECTIVE_SOURCE_RE.search(cur_src):
                    violations.append(
                        f"{rel}:{ln}: `current` ({cur!r} <- {cur_src!r}) does not "
                        "resolve to the effective level; the highlight must track "
                        "what this turn actually sends."
                    )

            # ── 3: picker body predicates ────────────────────────────────
            if fn == "ChatComposerWidgets.kt":
                body, at = find_function_body(src, PICKER_SIG)
                if body is not None and not escaped(at):
                    m = re.search(r"isCappedBy\(\s*([A-Za-z_][\w]*)\s*,", body)
                    if not m:
                        violations.append(
                            f"{rel}: ThinkingLevelPicker no longer calls "
                            "isCappedBy(<requested>, …) — the clamp cue was removed "
                            "or rewritten; re-justify it or add thinking-wiring-ok."
                        )
                    elif m.group(1) != "requested":
                        violations.append(
                            f"{rel}: ThinkingLevelPicker passes `{m.group(1)}` to "
                            "isCappedBy, not `requested` — the cue goes dead when "
                            "that value is the effective level."
                        )
                    t = re.search(r"thinkingTapTarget\(\s*[A-Za-z_][\w]*\s*,\s*([A-Za-z_][\w]*)", body)
                    if not t:
                        violations.append(
                            f"{rel}: ThinkingLevelPicker no longer routes taps through "
                            "thinkingTapTarget(level, <requested>)."
                        )
                    elif t.group(1) != "requested":
                        violations.append(
                            f"{rel}: thinkingTapTarget is keyed off `{t.group(1)}`, not "
                            "`requested` — a tap on the capped ceiling capsule then "
                            "turns thinking OFF instead of selecting it."
                        )

    # ── 4..7: measured-gateway effort truth table (see module docstring) ──
    families = check_measured_gateway_effort(root, violations)

    if checked_calls == 0:
        lines = [
            "⚠️  no ThinkingLevelPicker call sites found — is the picker still there?",
            "    (gate would silently pass; failing instead so it can't rot)",
        ]
        return 1, "\n".join(lines)

    if violations:
        lines = [f"❌ thinking-level wiring: {len(violations)} violation(s)"]
        lines += [f"   • {v}" for v in violations]
        return 1, "\n".join(lines)

    return 0, (
        f"✅ thinking-level wiring OK ({checked_calls} call site(s) checked; "
        f"{families} measured-gateway effort family/families pinned)"
    )


def main():
    if len(sys.argv) < 2:
        print("usage: thinking_wiring_guard.py <repo_root>")
        print("       thinking_wiring_guard.py --selftest [repo_root]")
        return 2
    if sys.argv[1] == "--selftest":
        return selftest(sys.argv[2] if len(sys.argv) > 2 else None)
    rc, out = _run_scan(sys.argv[1])
    print(out)
    return rc


if __name__ == "__main__":
    sys.exit(main())
