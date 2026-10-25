#!/usr/bin/env python3
"""
Read-only-mount write guard — every host-file write whose target comes from a
caller-supplied path parameter must consult `isLinuxPathUnderReadOnlyMount`.

Bug class ([deep-audit-1016] FIX-C 2b, live since T219): the guard exists and
`tools/FileWriteTool` / `tools/FileEditTool` call it, but `minis-model-use
--output` wrote through `shapeRawPassthrough` / `writeRunOutput` with no
read-only check at all — so a `--output` pointing into a user's read-only
mounted folder wrote the file anyway, while the same path refused via
`file_write`. Two write sites, one contract, and nothing mechanical looked at
both. (The audit report also claimed `FastioOffloadHandler` already checked;
it does not — it refuses *any* external-mount write, a superset of the
read-only rule, which is why it is exempt below WITH evidence rather than
silently passed.)

Criterion 1 (read-only mounts): for every function that (a) declares a path
parameter from PATH_PARAMS and (b) writes a host file, the function body must
call `isLinuxPathUnderReadOnlyMount(` — unless the file is in EXEMPT with a
reason and an evidence pattern that must still be present (an exemption that
rots is a failure, not a pass).

Criterion 2 (blocking I/O off the caller's thread, [deep-audit-1025 ISSUE-08]):
same "one contract, two write sites, nothing mechanical looked at both" shape,
one layer down. `MountedFoldersStore.probeWritable()` does a synchronous
create+delete probe write and was called straight from the suspend `add()` /
`refreshWritability()` bodies — both reached from Main (`rememberCoroutineScope()`
in MountedFoldersScreen, ON_RESUME, the storage-permission result) — while
`AppLogger.clearLogs()` (writer close + directory scan + unlink of every log
file) was called straight from the delete-all button's onClick. Every call site
of either must now sit inside a `withContext(Dispatchers.IO) { … }` block; see
BLOCKING_IO_CALLS for the table and `check_blocking_io` for what "inside" means.
[deep-audit-1027 ISSUE-05] added the third subject: `RootfsManager.verifyIntegrity()`
(manifest read + seven stats) was called twice from
`RootfsManagementViewModel.repairRootfs`, reached straight from the screen's
`onClick` — once inline, once inside a `viewModelScope.launch` whose
`Main.immediate` body ran it on the click thread anyway.

Criterion 10 (deep-audit-1041 B-02): the three rootfs lifecycle entry points
(`install` / `resetRootfs` / `repairRootfs`) must each settle their failure arms
through the shared terminal that re-reads the manager (`refresh`) — `reset()`
deletes the rootfs directory BEFORE re-installing and `autoRepair()`'s last
stage is that same reset, so a throw mid-extract left `isInstalled` at its
pre-operation `true` and the screen kept offering a rootfs that no longer
existed. `refresh` itself must zero the size of a rootfs that is gone, and
`repairRootfs` must subscribe to install progress like its two siblings (it
published `installProgress = 0f` and then left the bar frozen for a full
Stage-3 re-extract).

Usage: python3 scripts/scan/readonly_mount_write_guard.py [repo_root]
       python3 scripts/scan/readonly_mount_write_guard.py --selftest
Exit:  0 = every site guarded (or exempt with evidence), 1 = unguarded site
"""
import contextlib
import io
import os
import re
import shutil
import sys
import tempfile

GUARD = "isLinuxPathUnderReadOnlyMount("

# Filled by `functions()` on every call: declarations the parser could not follow
# to the end. `scan()` turns them into violations (fail-closed) instead of
# pretending the function had no writes.
PARSE_FAILURES = []

# [deep-audit-1033 I01] A SECOND, independent refusal is required at the
# browser's delete site. `deleteItem` already refuses read-only *mounts*
# (da1031-C), but the same browser also opens the two folders the settings
# screen labels Read-Only — Skills and Memory — because their
# `SharedFolderEntry.writable = false` reached the AccessBadge and nothing else:
# the trash icon really deleted SOUL.md / GLOBAL.md / SKILL.md from disk.
#
# Deliberately NOT folded into `GUARD`: the two live in different semantic
# domains (a mount lock is the user's runtime choice; the shared-folder rule is
# the registry's static declaration) and the second one has to stay derived from
# `SharedFolderRegistry.entries` rather than becoming a second path list here.
# `SandboxPathGuardWiringTest` pins that the two predicates do not overlap.
SHARED_DIR_GUARD = "isLinuxPathUnderReadOnlySharedDir("

# Sites that must carry an ADDITIONAL guard on top of `GUARD`, keyed by
# (file, function): (needle, what it refuses).
EXTRA_GUARDS = {
    ("ui/sandbox/FileBrowserViewModel.kt", "deleteItem"): (
        SHARED_DIR_GUARD,
        "refusing the read-only shared folders (skills / memory)",
    ),
}

PATH_PARAMS = re.compile(
    r"^(outputPath|linuxPath|guestPath|destPath|dstPath|dest|dst|hostPath|targetPath|archivePath|outPath)$"
)

# A call that puts bytes on the host filesystem.
WRITE_CALL = re.compile(
    r"\.(writeBytes|writeText|appendText|write)\(|newOutputStream\(|createNewFile\("
    r"|FileOutputStream\(|Files\.(copy|write|createDirectories)\("
)

FUN_DECL = re.compile(
    r"^\s*(?:(?:internal|private|public|protected|override|suspend|inline|operator|"
    r"tailrec|open|abstract|final|external)\s+)*fun\s+(\w+)\s*[(<]"
)

# rel path -> (reason, [(evidence file rel or None for "this file", regex, min hits)])
#
# Each entry must earn its keep TWICE: the reason is the argument, and the
# evidence pattern must still be present in the named file — an exemption whose
# justification rotted (the refusal it leans on was deleted) is reported as a
# violation, not silently tolerated. `None` means "this file".
EXEMPT = {
    "sandbox/PRootKernel.kt": (
        "defines the guard itself (`isLinuxPathUnderReadOnlyMount`)",
        [(None, r"fun isLinuxPathUnderReadOnlyMount\(", 1)],
    ),
    "sandbox/offload/FastioOffloadHandler.kt": (
        "refuses any external-mount write (`isExternalMount`) — a superset of the "
        "read-only rule, so the read-only guard is unreachable by construction",
        [(None, r"isExternalMount", 3)],
    ),
    "sandbox/offload/FastioCopy.kt": (
        "tree-copy engine: every caller reaches it through FastioOffloadHandler, which "
        "refuses any external-mount destination before the copy starts",
        [("sandbox/offload/FastioOffloadHandler.kt", r"isExternalMount", 3)],
    ),
    "sandbox/offload/FastioTar.kt": (
        "tar engine: the extract entry point runs after FastioOffloadHandler's "
        "external-mount refusal on the destination",
        [("sandbox/offload/FastioOffloadHandler.kt", r"isExternalMount", 3)],
    ),
    "data/MountedFoldersStore.kt": (
        "`probeWritable` discovers OS-level writability by *attempting* a write; "
        "refusing it would make every mount report non-writable",
        [(None, r"fun probeWritable\(", 1)],
    ),
    "sandbox/offload/PhotosOffloadHandler.kt": (
        "its destinations are app-private (`filesDir/photos-export`), never a "
        "caller-supplied guest path",
        [(None, r"photos-export", 1)],
    ),
}


APP_REL = os.path.join("src", "android", "app", "src", "main", "java", "com", "rikkaminis", "app")

# ── Criterion 2: blocking host I/O that must not run on the caller's thread ──
#
# (anchor file rel, call regex, definition regex, label)
#
# The anchor file is where the blocking work lives / is reached from: if it is
# gone while its package directory survives, the criterion has lost its subject
# and says so instead of quietly checking nothing. The *call* regexes are then
# applied to every Kotlin file under the app package — the blocking call is what
# matters, not which file happens to hold it today.
BLOCKING_IO_CALLS = (
    (
        "data/MountedFoldersStore.kt",
        r"probeWritable\s*\(",
        r"\bfun\s+probeWritable\s*\(",
        "probeWritability (creates + deletes a probe file on the mount)",
    ),
    (
        "ui/settings/LogManagementScreen.kt",
        r"AppLogger\.clearLogs\s*\(",
        r"\bfun\s+clearLogs\s*\(",
        "log purge (closes the writers + unlinks every log file)",
    ),
    (
        "share/ShareReceiverActivity.kt",
        r"importProviderJson\s*\(",
        r"\bfun\s+importProviderJson\s*\(",
        "provider import (importInstanceJSON: ensureConfigLoaded runBlocking + "
        "EncryptedSharedPreferences commit + Room write)",
    ),
    (
        "share/ShareReceiverActivity.kt",
        r"discardStagedAttachments\s*\(",
        r"\bfun\s+discardStagedAttachments\s*\(",
        "staging discard (length() + delete() per staged attachment)",
    ),
    (
        "ui/sandbox/RootfsManagementViewModel.kt",
        r"manager\.verifyIntegrity\s*\(",
        r"\bfun\s+verifyIntegrity\s*\(",
        "rootfs integrity verification (reads .integrity_manifest + stats 7 critical files)",
    ),
    # [deep-audit-1033 I05 / I08] Save paths that wrote on the click's thread.
    # The call regexes are receiver-qualified on purpose: the blocking work is
    # reached through the repository/store handle the UI holds, while the same
    # operation called from a repository-internal or debug-RPC caller (which
    # already runs off Main) is not what this criterion is about. Unqualified
    # names would flag those callers too and force churn far outside the
    # screens that actually had the defect.
    (
        "ui/onboarding/OnboardingScreen.kt",
        r"providerRepository\.addInstance\s*\(",
        r"\bfun\s+addInstance\s*\(",
        "provider instance insert (SQLite write from the onboarding save button)",
    ),
    (
        "ui/onboarding/OnboardingScreen.kt",
        r"providerRepository\.saveApiKey\s*\(",
        r"\bfun\s+saveApiKey\s*\(",
        "API key persist (EncryptedSharedPreferences commit from the UI)",
    ),
    (
        "ui/settings/AddCustomModelScreen.kt",
        r"providerRepository\.addEntry\s*\(",
        r"\bfun\s+addEntry\s*\(",
        "custom model insert (SQLite write from the save button)",
    ),
    (
        "ui/settings/BackupSettingsScreen.kt",
        r"webDavStore\.load\s*\(",
        r"\bfun\s+load\s*\(",
        "WebDAV config read (EncryptedSharedPreferences + Keystore unwrap)",
    ),
    (
        "ui/settings/BackupSettingsScreen.kt",
        r"webDavStore\.save\s*\(",
        r"\bfun\s+save\s*\(",
        "WebDAV config persist (EncryptedSharedPreferences commit)",
    ),
)

# Guarded = the call sits inside a brace-balanced `withContext(Dispatchers.IO) {`
# block that encloses it. The non-blocking `withContext(IO) expr` form is
# deliberately NOT recognised: a wrong-shaped wrap must go red rather than pass
# on a technicality (fail-closed). An unbalanced block is a ROT failure, never a
# silent skip — an unparseable file must not read as clean.
IO_BLOCK_RE = re.compile(r"withContext\s*\(\s*Dispatchers\.IO\s*\)\s*\{")


def strip_comments(text, collapse_literals=True):
    """Drop comments, keep every newline (so a finding can cite the real line).

    A character scanner, not a regex. The regex version this replaces ate `//`
    inside string literals (`"https://…"`), which left the quote unterminated and
    then corrupted every brace/quote scan downstream — measured on
    `sandbox/RootfsManager.kt`, whose stripped text lost quote parity and made a
    perfectly balanced `withContext(Dispatchers.IO) { … }` look unbalanced.

    [collapse_literals] replaces a string/char literal with `""` / `''` (and a raw
    string with a newline-preserving placeholder) so nothing inside one counts as
    structure: a brace in a message must not unbalance a block, and a guard call
    quoted in prose must not satisfy criterion 1. `False` keeps literal bodies
    verbatim — that is the view the EXEMPT evidence patterns are matched against,
    where `"photos-export"` must still be found.
    Kotlin nests block comments, so `/*` counts depth.
    """
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            if text.startswith('"""', i):
                end = text.find('"""', i + 3)
                end = n if end < 0 else end + 3
                out.append('""' + "\n" * text.count("\n", i, end)
                           if collapse_literals else text[i:end])
                i = end
                continue
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "\n" or text[j] == '"':
                    break
                j += 1
            closed = j < n and text[j] == '"'
            if collapse_literals:
                out.append('""' if closed else " ")
            else:
                out.append(text[i:j + 1] if closed else text[i:])
            i = j + 1 if j < n else n
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "\n" or text[j] == "'":
                    break
                j += 1
            closed = j < n and text[j] == "'"
            if collapse_literals:
                out.append("''" if closed else " ")
            else:
                out.append(text[i:j + 1] if closed else text[i:])
            i = j + 1 if j < n else n
            continue
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
            continue
        if text.startswith("/*", i):
            depth = 0
            while i < n:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                    continue
                if text.startswith("*/", i):
                    depth -= 1
                    i += 2
                    if depth == 0:
                        break
                    continue
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_strings(line):
    out, i, quote = [], 0, None
    while i < len(line):
        c = line[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
            continue
        if c in "\"'":
            quote = c
        out.append(c)
        i += 1
    return "".join(out)


def param_close(sig_text):
    """Index just past the `)` that closes the first `(`; -1 when unbalanced.

    Needed instead of a non-greedy regex because a parameter list can nest
    parentheses (`(onDone: () -> Unit)`) and span lines.
    """
    start = sig_text.find("(")
    if start < 0:
        return -1
    depth, i = 0, start
    while i < len(sig_text):
        c = sig_text[i]
        if c in "\"'":
            quote = c
            i += 1
            while i < len(sig_text) and sig_text[i] != quote:
                i += 2 if sig_text[i] == "\\" else 1
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def depth_of(text):
    """Net ( and { opened by a chunk of stripped source."""
    return text.count("(") - text.count(")") + text.count("{") - text.count("}")


def match_paren(text, open_idx):
    """Index of the `)` closing text[open_idx]; -1 when unbalanced."""
    depth, i, n = 0, open_idx, len(text)
    while i < n:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def if_branches(text):
    """[(cond_start, cond_end, body_start, body_end)] for every `if (...)`.

    Brace bodies and the single-statement body that runs to the end of the line
    are both covered; `body_end` is exclusive.
    """
    out = []
    for m in re.finditer(r"\bif\s*\(", text):
        op = m.end() - 1
        cl = match_paren(text, op)
        if cl < 0:
            continue
        b = cl + 1
        while b < len(text) and text[b] in " \t\r\n":
            b += 1
        if b < len(text) and text[b] == "{":
            end = balanced_block(text, b)
            be = end if end > 0 else len(text)
        else:
            eol = text.find("\n", b)
            be = len(text) if eol < 0 else eol
        out.append((op + 1, cl, b, be))
    return out


def split_top(s, tokens):
    """Split on the given operators at parenthesis/brace depth 0."""
    parts, depth, cur = [], 0, []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
        hit = None
        if depth == 0:
            for t in tokens:
                if s.startswith(t, i):
                    hit = t
                    break
        if hit:
            parts.append("".join(cur).strip())
            cur = []
            i += len(hit)
            continue
        cur.append(c)
        i += 1
    parts.append("".join(cur).strip())
    return parts


def constant_truth(cond):
    """True/False when the condition's value is decidable without running it.

    `false && guard(x)` / `x || true` keep every identifier a substring-based
    criterion looks for while pinning the decision — the single most common
    way a witness deleted a guard's effect without deleting the call.
    """
    s = cond.strip()
    while s.startswith("(") and s.endswith(")"):
        depth, ok, close_at = 0, True, -1
        for i, ch in enumerate(s):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    close_at = i
                    break
        if close_at != len(s) - 1:
            ok = False
        if not ok:
            break
        s = s[1:-1].strip()
    ors = split_top(s, ("||",))
    if len(ors) > 1:
        vals = [constant_truth(p) for p in ors]
        if any(v is True for v in vals):
            return True
        if all(v is False for v in vals):
            return False
        return None
    ands = split_top(s, ("&&",))
    if len(ands) > 1:
        vals = [constant_truth(p) for p in ands]
        if any(v is False for v in vals):
            return False
        if all(v is True for v in vals):
            return True
        return None
    if s == "true":
        return True
    if s == "false":
        return False
    if s.startswith("!"):
        inner = constant_truth(s[1:])
        return None if inner is None else (not inner)
    return None


def alias_names(text, seed, hops=2):
    """Identifiers assigned from `seed` (transitively, a couple of hops).

    A guard's value is routinely carried one line: `val replay =
    isStaleInstallReplay(…)`, `val ro = isLinuxPathUnderReadOnlyMount(p)`. The
    decision that matters is the one taken on the CARRIER, so the criterion has
    to follow one hop or it would only accept the inlined spelling.
    """
    names = {seed}
    for _ in range(hops):
        before = len(names)
        for m in re.finditer(r"\bval\s+(\w+)\s*=\s*([^\n]*)", text):
            rhs = m.group(2)
            if any(re.search(r"\b%s\b" % re.escape(n), rhs) for n in names):
                names.add(m.group(1))
        if len(names) == before:
            break
    names.discard(seed)
    return names


def decision_branches(text, names, terminators):
    """[(cond, body_start, body_end, body)] for branches keyed on [names].

    Returns only the branches where the decision is CONSUMED by a terminating
    statement (`return` / `throw` / `continue` / `return@label`) — the criterion
    the wide3 witnesses defeat by keeping the call and neutralising the branch.
    """
    out = []
    for cs, ce, bs, be in if_branches(text):
        cond = text[cs:ce]
        if not any(re.search(r"\b%s\b" % re.escape(n), cond) for n in names):
            continue
        body = text[bs:be]
        if not any(t in body for t in terminators):
            continue
        out.append((cond, bs, be, body))
    return out


def functions(text):
    """[(name, params, body)] for every `fun` declared in `text`.

    Clears PARSE_FAILURES at entry, so the caller reads exactly the failures of
    THIS call.
    """
    del PARSE_FAILURES[:]
    lines = text.split("\n")
    out = []
    i = 0
    while i < len(lines):
        m = FUN_DECL.match(lines[i])
        if not m:
            i += 1
            continue
        name = m.group(1)
        # Signature runs until the parameter list closes.
        paren, j = 0, i
        sig = []
        seen_open = False
        while j < len(lines):
            s = strip_strings(lines[j])
            paren += s.count("(") - s.count(")")
            if "(" in s:
                seen_open = True
            sig.append(lines[j])
            if seen_open and paren <= 0:
                break
            j += 1
        sig_text = "\n".join(sig)
        close = param_close(sig_text)
        params = sig_text[sig_text.find("(") + 1:close - 1] if close > 0 else ""
        # Expression body (`: T = expr`, no `{` before the `=`): the signature
        # scan already followed the expression's parentheses, so the body is the
        # tail of the signature rather than the next `{` block. Without this the
        # scan runs on into the *following* declaration — crediting this function
        # with a write that is not its own and skipping that declaration
        # entirely (measured on `PRootKernel.resolveHostPath`, a pure resolver
        # that was credited with `installHandlerStubs`' `stub.writeText(…)`).
        rest = strip_strings(sig_text[close:]) if close > 0 else ""
        eq, brace = rest.find("="), rest.find("{")
        if eq >= 0 and (brace < 0 or eq < brace):
            # wide3 parser-death: the expression may START ON THE NEXT LINE
            # (`): NativeOffloadResult =` newline `run {`). Seeding the depth from
            # the tail of the signature line alone gave 0 for that shape, so the
            # `while depth > 0` loop never ran, the body was the header fragment,
            # and the whole function — its writes and the guard that refuses them
            # — fell out of the scan while the remaining sites kept the
            # `checked >= 3` floor satisfied. Consume lines until the expression
            # has actually started AND balanced.
            body = [sig_text[close:]]
            tail = rest[eq + 1:]
            depth = depth_of(tail)
            started = bool(tail.strip())
            k = j + 1
            while k < len(lines):
                if started and depth <= 0:
                    break
                s = strip_strings(lines[k])
                body.append(lines[k])
                if s.strip():
                    started = True
                    depth += depth_of(s)
                k += 1
            if not started or depth != 0:
                PARSE_FAILURES.append(
                    f"{name}: expression body could not be followed to its end "
                    f"(depth {depth}) — the criterion cannot see this function"
                )
            out.append((name, params, "\n".join(body)))
            i = k
            continue
        # Body: brace-balanced from the signature's last line.
        bal, k, seen_brace, body = 0, j, False, []
        while k < len(lines):
            s = strip_strings(lines[k])
            bal += s.count("{") - s.count("}")
            if "{" in s:
                seen_brace = True
            body.append(lines[k])
            if seen_brace and bal <= 0:
                break
            k += 1
        out.append((name, params, "\n".join(body)))
        i = max(k, j) + 1
    return out


def read(root, rel):
    full = os.path.join(root, "src/android/app/src/main/java/com/rikkaminis/app", rel)
    try:
        return open(full, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def path_param_names(params):
    names = []
    for part in params.split(","):
        nm = part.strip().split(":")[0].strip()
        if PATH_PARAMS.match(nm):
            names.append(nm)
    return names


def scan(root):
    """Returns (violations, sites_checked, files_scanned, exempt_used)."""
    app = os.path.join(root, "src/android/app/src/main/java/com/rikkaminis/app")
    violations, checked, files = [], 0, 0
    exempt_used = {}
    evidence_cache = {}

    def evidence_text(rel_key):
        """Comment-free text with literal bodies kept — the EXEMPT view.

        Evidence patterns name things that live inside string literals
        (`"photos-export"`), so they must be matched against a view that did not
        collapse literals away. Structure, by contrast, is scanned on the
        collapsed view (`GUARD in body`), where a guard call quoted in a message
        cannot satisfy the criterion.
        """
        if rel_key not in evidence_cache:
            evidence_cache[rel_key] = strip_comments(read(root, rel_key), collapse_literals=False)
        return evidence_cache[rel_key]

    for dirpath, _dirs, filenames in os.walk(app):
        for fn in sorted(filenames):
            if not fn.endswith(".kt"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, app).replace(os.sep, "/")
            raw = open(full, encoding="utf-8", errors="replace").read()
            text = strip_comments(raw)
            files += 1
            parsed = functions(text)
            # fail-closed: a `fun` the parser could not follow is a HOLE in the
            # criterion, not a function without a write. Silently dropping it is
            # exactly how the wide3 witness made a guarded write disappear.
            for why in PARSE_FAILURES:
                violations.append(f"{rel}: {why}")
            for name, params, body in parsed:
                if not path_param_names(params):
                    continue
                if not WRITE_CALL.search(body):
                    continue
                checked += 1
                if GUARD in body:
                    continue
                if rel in EXEMPT:
                    reason, evidence = EXEMPT[rel]
                    rotted = []
                    for ev_rel, pattern, need in evidence:
                        target = evidence_text(ev_rel or rel)
                        hits = len(re.findall(pattern, target))
                        if hits < need:
                            rotted.append(f"{ev_rel or rel}: /{pattern}/ {hits} < {need}")
                    if rotted:
                        violations.append(
                            f"{rel}:{name} — exempt ('{reason}') but its evidence rotted: "
                            + "; ".join(rotted)
                        )
                    else:
                        exempt_used[rel] = reason
                    continue
                violations.append(
                    f"{rel}:{name}({params.strip()[:60]}) — writes a host file from a path "
                    f"parameter without calling {GUARD}"
                )
    return violations, checked, files, exempt_used


def balanced_block(text, open_idx):
    """Index just past the closer matching text[open_idx]; -1 when unbalanced.

    String literals are skipped so a quoted brace (or a `${…}` interpolation
    inside one) can never be mistaken for structure.
    """
    depth, i = 0, open_idx
    while i < len(text):
        c = text[i]
        if c in "\"'":
            quote = c
            i += 1
            while i < len(text) and text[i] != quote:
                i += 2 if text[i] == "\\" else 1
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def io_block_regions(text):
    """[(start, end)] of every `withContext(Dispatchers.IO) { … }` block; None = unbalanced."""
    regions = []
    for m in IO_BLOCK_RE.finditer(text):
        open_idx = text.find("{", m.start(), m.end())
        if open_idx < 0:
            return None
        end = balanced_block(text, open_idx)
        if end < 0:
            return None
        regions.append((open_idx, end))
    return regions


def call_offsets(text, call_re, decl_re):
    """Offsets of every call, skipping the declaration it is named after.

    A definition (`fun probeWritable(`) is not a call site: counting it would
    report the declaration line as an unguarded caller — the false positive this
    criterion would otherwise open with.
    """
    decl_spans = [m.span() for m in decl_re.finditer(text)] if decl_re else []
    out = []
    for m in call_re.finditer(text):
        if any(s <= m.start() < e for s, e in decl_spans):
            continue
        out.append(m.start())
    return out


def app_kt_files(root):
    app = os.path.join(root, APP_REL)
    found = []
    for dirpath, _dirs, filenames in os.walk(app):
        for fn in sorted(filenames):
            if fn.endswith(".kt"):
                found.append(os.path.join(dirpath, fn))
    return found


def check_blocking_io(root):
    """Blocking host I/O reached off a `Dispatchers.IO` block.

    Returns (violations, rot, sites, notes). A target whose anchor file is gone
    while its package directory survives is ROT (the tree moved, not the bug);
    a tree that simply does not ship that package (the frozen `test_scan.py`
    fixtures build a `sandbox/`-only tree) is noted as `[PARTIAL]` and skipped.
    """
    app = os.path.join(root, APP_REL)
    violations, rot, notes = [], [], []
    sites = 0

    active = []
    for rel, call_pat, decl_pat, label in BLOCKING_IO_CALLS:
        path = os.path.join(app, *rel.split("/"))
        if os.path.isfile(path):
            active.append((rel, call_pat, decl_pat, label))
            continue
        pkg = os.path.dirname(path)
        if os.path.isdir(pkg):
            rot.append(
                f"{rel}: missing while `{os.path.basename(pkg)}/` still exists — "
                f"the {label} criterion lost its anchor"
            )
        else:
            notes.append(f"[PARTIAL] {label} criterion skipped ({rel} absent)")

    if not active:
        return violations, rot, sites, notes

    # One pass over the app package, holding on to nothing that is not a hit.
    # (An earlier revision stripped all ~600 sources into a dict first; that is
    # ~20 MB of live Python strings and got the whole sandbox SIGKILLed mid-run
    # at gate 26 of scan.sh. Keep it streaming.)
    rules = [(rel, re.compile(call_pat), re.compile(decl_pat), label)
             for rel, call_pat, decl_pat, label in active]
    decl_hits = {label: 0 for _rel, _cp, _dp, label in rules}
    sites_here = {rel: 0 for rel, _cp, _dp, _label in rules}
    kept, hits, total = {}, [], {label: 0 for _rel, _cp, _dp, label in rules}

    for full in app_kt_files(root):
        fps = os.path.relpath(full, app).replace(os.sep, "/")
        try:
            raw = open(full, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        text = strip_comments(raw)
        regions = None
        for rel, call_re, decl_re, label in rules:
            decl_hits[label] += len(decl_re.findall(text))
            offsets = call_offsets(text, call_re, decl_re)
            if not offsets:
                continue
            if regions is None:
                regions = io_block_regions(text)
                if regions is None:
                    rot.append(
                        f"{fps}: unbalanced braces around a withContext(Dispatchers.IO) block"
                    )
                    regions = []
            kept[fps] = (text, regions)
            total[label] += len(offsets)
            if fps == rel:
                sites_here[rel] += len(offsets)
            for off in offsets:
                hits.append((fps, off, label))
        del text

    for rel, _call_re, _decl_re, label in rules:
        if decl_hits[label] == 0:
            rot.append(
                f"{label}: no definition matching its declaration pattern anywhere — renamed "
                f"or removed, so the criterion would pass by looking at nothing"
            )
            continue
        if total[label] == 0:
            rot.append(
                f"{rel}: nothing calls this any more — the {label} criterion would be vacuous"
            )
            continue
        if sites_here[rel] == 0:
            # The call moved out of its home file. Still enforced (the scan above
            # is app-wide), but the anchor is worth naming: it is the place a
            # reader looks first when this criterion changes.
            notes.append(f"[PARTIAL] {label}: no call site left in {rel} (enforced app-wide)")

    if rot:
        # Nothing below can be trusted to have parsed: report and let main decide.
        return violations, rot, sites, notes

    for fps, off, label in hits:
        sites += 1
        text, regions = kept[fps]
        if not any(s <= off < e for s, e in regions):
            line = text.count("\n", 0, off) + 1
            violations.append(
                f"{fps}:{line} — {label} runs on the caller's thread; wrap it in "
                f"withContext(Dispatchers.IO) {{ … }}"
            )
    return violations, rot, sites, notes


# ── Criterion 3: the share-import UI hand-off must stay on a Looper thread ──
#
# The ISSUE-07 fix moved the provider import onto Dispatchers.IO, which made the
# `toast()` helper functionally dependent on hopping back to Main: `Toast.show()`
# on a worker thread throws "Can't toast on a thread that has not called
# Looper.prepare()". No criterion-2 rule can see that dependency (showing a
# Toast is not a blocking-I/O call), so a future cleanup that removes the hop
# would read as a pure simplification and pass every gate — then crash at
# runtime on the very first shared-provider import. This criterion pins the hop
# in the one file that owns it, and reports ROT when its premise disappears.
SHARE_TOAST_HOP_RE = re.compile(r"runOnUiThread\s*[({]|Handler\s*\(")

def check_share_toast_hop(root):
    """The share `toast()` must hop to a Looper thread.

    Returns (violations, rot, sites, notes) — the same contract as
    check_blocking_io, wired into main() the same way. Skipped ([PARTIAL]) on
    trees that do not ship the share package, e.g. the frozen `test_scan.py`
    fixtures, which build a `sandbox/`-only tree.
    """
    rel = "share/ShareReceiverActivity.kt"
    path = os.path.join(root, APP_REL, *rel.split("/"))
    violations, rot, notes = [], [], []
    sites = 0
    if not os.path.isfile(path):
        if os.path.isdir(os.path.dirname(path)):
            rot.append(
                f"{rel}: missing while `share/` still exists — the toast-hop "
                f"criterion lost its anchor"
            )
        else:
            notes.append("[PARTIAL] share toast hop criterion skipped (share/ absent)")
        return violations, rot, sites, notes

    text = strip_comments(open(path, encoding="utf-8", errors="replace").read())
    bodies = [body for name, _params, body in functions(text) if name == "toast"]
    if not bodies:
        rot.append(
            f"{rel}: no `fun toast(` left to check — the criterion would pass "
            f"by looking at nothing"
        )
        return violations, rot, sites, notes

    # Premise: the import really does run on a worker thread in this file. If a
    # future refactor pulls it back onto Main, the hop stops being required —
    # say so and let a human re-justify or retire the criterion instead of
    # enforcing it forever for no reason (the same rot-not-skip idiom as
    # criterion 2).
    regions = io_block_regions(text)
    if regions is None:
        rot.append(f"{rel}: unbalanced braces around a withContext(Dispatchers.IO) block")
        return violations, rot, sites, notes
    import_offsets = call_offsets(
        text, re.compile(r"importProviderJson\s*\("), re.compile(r"\bfun\s+importProviderJson\s*\(")
    )
    if not any(s <= o < e for s, e in regions for o in import_offsets):
        rot.append(
            f"{rel}: the toast-hop criterion's premise is gone — no "
            f"importProviderJson call inside a withContext(Dispatchers.IO) block"
        )
        return violations, rot, sites, notes

    head = text.find("fun toast(")
    for body in bodies:
        sites += 1
        if not SHARE_TOAST_HOP_RE.search(body):
            line = text.count("\n", 0, head) + 1 if head >= 0 else 0
            violations.append(
                f"{rel}:{line} — toast() shows the Toast on its calling thread; the provider "
                f"import now runs on Dispatchers.IO, where Toast.show() throws without a "
                f"Looper — hop back with runOnUiThread {{ … }}"
            )
    return violations, rot, sites, notes


# ── criterion 5: guard-call SHAPE at the model-carried write sites ──────────
#
# [da1031-C] ISSUE-04. The whitelist above is keyed on the PARAMETER NAME, so it
# cannot see a write that arrives through a model — `deleteItem(item: FileItem)`
# and `handleWriteFile(params: JSONObject)`. Widening that whitelist was
# rejected on evidence: measured on this tree it newly flags three legitimate
# sites (`parseAttachments` / `handleModelUseExec` / `postJsonRpc`), and one of
# them shares its FILE with a site this criterion exists to protect — a
# file-level exemption would therefore silently disable the check it was added
# for. Anchor on the FUNCTION NAME plus the write call instead, and require the
# guard to PRECEDE the write: a `return@launch` after the delete is not a guard,
# it is a post-mortem.
GUARD_SITES = (
    (
        "ui/sandbox/FileBrowserViewModel.kt",
        "deleteItem",
        re.compile(r"\.deleteRecursively\s*\(|\.delete\s*\("),
        "deleting a listed item's host file",
    ),
    (
        "debug/DebugRPCHandler.kt",
        "handleWriteFile",
        re.compile(r"\.writeBytes\s*\("),
        "overwriting a host file behind resolveHostPath",
    ),
)

# [da1031-C] The route that opens a mounted folder must hand the browser the
# guest-side prefix. Without it the browser works on a host path, so the guard
# above never sees a mount path at all — it is unreachable from that entry
# (and "Copy Absolute Path" copies the host path).
MOUNT_ROUTE = (
    "ui/navigation/AppNavigation.kt",
    "Routes.fileBrowser(",
    'linuxRoot = "/var/minis/mounts/',
)


def guard_decides(body, needle):
    """(live, dead_condition) — does `needle`'s value actually decide a refusal?

    wide3: the CALL being in front of the write is not the refusal. The witness
    kept the call, its position and the early-return body and only wrote
    `if (false && isLinuxPathUnderReadOnlyMount(path))`, so every substring said
    guarded while every mount path was written. The predicate must feed a branch
    that is reachable (`constant_truth(cond) is not False`) and that TERMINATES
    (`return`/`throw`). `return@launch` counts — the label form is the real
    shape inside a coroutine body.
    """
    name = needle[:-1] if needle.endswith("(") else needle
    live, dead = False, None
    for m in re.finditer(re.escape(needle), body):
        idx = m.start()
        found = []
        for cs, ce, _bs, _be in if_branches(body):
            if cs <= idx < ce:
                found.append(body[cs:ce])
        carriers = {name} | alias_names(body, name)
        found += [cond for cond, _bs, _be, _body in decision_branches(
            body, carriers, ("return", "throw"))]
        for cond in found:
            if constant_truth(cond) is False:
                if dead is None:
                    dead = cond.strip()
                continue
            live = True
    return live, dead


def guard_message(where, needle, dead, label):
    if dead is not None:
        return (
            f"{where} — the refusal guard sits behind `if ({dead})`, a condition "
            f"that is constantly false: {needle}) is in front of the write and "
            f"never decides it, so the write happens anyway ({label})"
        )
    return (
        f"{where} — {needle})'s result is not consumed by a branch that "
        f"returns/throws: the predicate runs and the write goes ahead regardless "
        f"({label})"
    )


def check_guard_call_shape(root):
    """Returns (violations, rot, sites, notes).

    [PARTIAL] convention, same as [check_blocking_io]: a tree that carries none
    of these anchors is a synthetic fixture tree (the frozen `test_scan.py`
    shape), not a regression. As soon as one anchor is present, a missing one is
    ROT — the criterion lost its subject and says so instead of checking nothing.

    A site can owe more than the mount guard: [EXTRA_GUARDS] lists the sites
    that were later shown to need a second, independent refusal (the read-only
    shared folders the browser also opens). Extra guards are judged the same way
    — present, and in front of the write — because a refusal placed after the
    delete is a post-mortem, not a guard.
    """
    anchors = [(rel, fn, rx, label) for rel, fn, rx, label in GUARD_SITES]
    route_rel, route_anchor, route_needle = MOUNT_ROUTE
    present = [a for a in anchors if read(root, a[0])]
    if read(root, route_rel):
        present.append(MOUNT_ROUTE)
    if not present:
        return [], [], 0, [
            "[PARTIAL] the read-only-mount guard anchors (FileBrowserViewModel / "
            "DebugRPCHandler / AppNavigation) are absent from this tree — skipped"
        ]

    violations, rot, sites = [], [], 0
    for rel, fn, rx, label in anchors:
        src = read(root, rel)
        if not src:
            rot.append(f"{rel}: lost its anchor for {fn}() — criterion 5 cannot run")
            continue
        bodies = [body for name, _params, body in functions(strip_comments(src)) if name == fn]
        if not bodies:
            rot.append(f"{rel}: no definition of {fn}() — renamed? criterion 5 lost its subject")
            continue
        sites += 1
        body = bodies[0]
        guard_at = body.find(GUARD)
        write = rx.search(body)
        if write is None:
            rot.append(f"{rel}:{fn}() — the write call is gone; criterion 5 lost its subject")
            continue
        if guard_at < 0:
            violations.append(f"{rel}:{fn}() — {label} without calling {GUARD})")
        elif guard_at > write.start():
            violations.append(
                f"{rel}:{fn}() — {GUARD}) runs AFTER the write it is meant to refuse; "
                f"move it in front of it"
            )
        else:
            live, dead = guard_decides(body, GUARD)
            if not live:
                violations.append(
                    guard_message(f"{rel}:{fn}()", GUARD, dead, label))
        extra = EXTRA_GUARDS.get((rel, fn))
        if extra is not None:
            needle, what = extra
            extra_at = body.find(needle)
            if extra_at < 0:
                violations.append(
                    f"{rel}:{fn}() — {label} without {what} ({needle})"
                )
            elif extra_at > write.start():
                violations.append(
                    f"{rel}:{fn}() — {needle}) runs AFTER the write it is meant to "
                    f"refuse; move it in front of it"
                )
            else:
                live, dead = guard_decides(body, needle)
                if not live:
                    violations.append(
                        guard_message(f"{rel}:{fn}()", needle, dead, label))

    route_src = read(root, route_rel)
    if not route_src:
        rot.append(f"{route_rel}: lost its anchor — criterion 5 cannot run")
        return violations, rot, sites, []
    # Literal-preserving view: the needle lives inside a string literal, which
    # the default (collapsing) view replaces with `""`.
    route_text = strip_comments(route_src, collapse_literals=False)
    if route_anchor not in route_text:
        rot.append(f"{route_rel}: no {route_anchor} call — criterion 5 lost its subject")
        return violations, rot, sites, []
    sites += 1
    if route_needle not in route_text:
        violations.append(
            f"{route_rel} — the mounted-folder browse route opens the file browser "
            f"without a guest-side prefix ({route_needle!r} missing): the read-only "
            f"guard and Copy Absolute Path never see /var/minis/mounts/<name>/…"
        )
    return violations, rot, sites, []


# ── selftest ────────────────────────────────────────────────────────────────
# ── criterion-10 fixture (deep-audit-1041 B-02) ──────────────────────────

def _rootfs_settlement(arms="settled", size="zeroed", repair_subscribes=True,
                       replay_gate=True, collect="present", repair_verdict=True,
                       repair_prior=True, settlement_stale_guard=True,
                       replay_dead=False):
    """The B-02 subject: three lifecycle entry points, one failure terminal.

    [arms] settled — every `catch` delegates to the terminal (the shape the fix
    ships); direct — each `catch` re-inlines the state copy and stops there,
    which is the pre-fix shape the criterion exists to catch.
    [size] zeroed — `refresh` decides the size on the manager's verdict;
    missing — no decision at all; inside_branch — the zeroing is placed in the
    installed-only branch (the "must not introduce" shape).
    [repair_subscribes] False drops `repairRootfs`'s subscription, the third leg
    of the same defect (the bar froze at 0% for a full Stage-3 re-extract).
    [replay_gate] False is the B-05 pre-fix collector: every frame the StateFlow
    hands over is applied, including the terminal it replays on subscribe.
    [collect] "missing" drops the subscription body entirely (the ROT shape).
    [repair_verdict] False is the B-05 pre-fix settlement: `repairRootfs`
    branches on `autoRepair()`'s boolean and never reads the published terminal.
    [repair_prior] False is the B-01 pre-fix shape: the repair reads the flow
    without first snapshotting the terminal it inherited.
    [settlement_stale_guard] False keeps the snapshot and the hand-off but leaves
    the `Failed` arm unconditional — the second half of B-01.
    """
    if size == "zeroed":
        decision = (
            "            rootfsSize = if (manager.isInstalled) _uiState.value.rootfsSize "
            "else 0L,\n"
        )
        # The installed arm is the async size read that used to be the only way
        # the field ever changed.
        inner = (
            "            viewModelScope.launch {\n"
            "                _uiState.value = _uiState.value.copy("
            "rootfsSize = manager.getRootfsSize())\n"
            "            }\n"
        )
    elif size == "inside_branch":
        decision = ""
        inner = "            _uiState.value = _uiState.value.copy(rootfsSize = 0L)\n"
    else:  # missing
        decision = ""
        inner = ""

    def entry(fn, tag, result, message_ints):
        subscribes = not (fn == "repairRootfs" and not repair_subscribes)
        head = ""
        if fn == "repairRootfs" and repair_prior and repair_verdict:
            # [deep-audit-1043 B-01] the shipped shape snapshots the terminal the
            # flow already holds, BEFORE anything the operation publishes.
            head += "        val priorTerminal = manager.installState.value\n"
        if subscribes:
            head += "        observeInstallProgress(manager, ctx)\n"
        if arms == "settled":
            fail = '            settleRootfsFailure(ctx, "%s", %d, e)\n' % (tag, message_ints)
        else:
            fail = (
                '            logging.log("%s failed")\n'
                "            _uiState.value = _uiState.value.copy(\n"
                "                isProcessing = false,\n"
                "                lastOperationSuccess = false,\n"
                '                resultMessage = "failed",\n'
                "                installProgress = null,\n"
                "            )\n"
            ) % tag
        if fn != "repairRootfs":
            call = "            manager.%s()\n" % tag
            after = "            refresh(ctx)\n"
        elif repair_verdict:
            # [deep-audit-1042 B-05] the shipped settlement: the manager's
            # published terminal decides, `autoRepair()`'s boolean is only one
            # input.
            call = "            val repaired = manager.autoRepair()\n"
            after = (
                "            _uiState.value = rootfsRepairState(\n"
                "                _uiState.value,\n"
                "                manager.installState.value,\n"
                "                repaired,\n"
                '                "repaired",\n'
                '                "missing",\n'
                + ("                priorTerminal,\n" if repair_prior else "") +
                "            )\n"
                "            refresh(ctx)\n"
            )
        else:
            # pre-fix shape: `repaired == false` is announced as corruption even
            # when the manager published `Failed(rootfs_insufficient_space)`.
            call = "            val repaired = manager.autoRepair()\n"
            after = (
                "            _uiState.value = _uiState.value.copy(\n"
                "                isProcessing = false,\n"
                "                lastOperationSuccess = repaired,\n"
                '                resultMessage = if (repaired) "repaired" else "missing",\n'
                "                installProgress = null,\n"
                "            )\n"
                "            refresh(ctx)\n"
            )
        return (
            "    fun %s(ctx: Ctx) {\n" % fn
            + head
            + "        try {\n"
            + call
            + after
            + "        } catch (e: Exception) {\n"
            + fail
            + "        }\n"
            + "    }\n"
            + "\n"
        )

    if collect == "present" and replay_gate:
        # [deep-audit-1042 B-05] the shipped collector: the terminal the flow
        # replays on subscribe is recognised and skipped.
        collector = (
            "    private fun observeInstallProgress(manager: RootfsManager, ctx: Ctx) {\n"
            "        progressJob?.cancel()\n"
            "        progressJob = viewModelScope.launch {\n"
            "            val priorTerminal = manager.installState.value\n"
            "            var firstFrame = true\n"
            "            manager.installState.collect { state ->\n"
            "                val replay = isStaleInstallReplay(firstFrame, state, priorTerminal)\n"
            "                firstFrame = false\n"
            + ("                if (false && replay) return@collect\n" if replay_dead
               else "                if (replay) return@collect\n")
            + "                when (state) {\n"
            "                    is RootfsInstallState.Failed ->\n"
            "                        _uiState.value = _uiState.value.copy("
            "isProcessing = false)\n"
            "                    else -> Unit\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "\n"
        )
    elif collect == "present":
        # pre-fix shape: the replayed terminal is applied as a live event
        collector = (
            "    private fun observeInstallProgress(manager: RootfsManager, ctx: Ctx) {\n"
            "        progressJob?.cancel()\n"
            "        progressJob = viewModelScope.launch {\n"
            "            manager.installState.collect { state ->\n"
            "                when (state) {\n"
            "                    is RootfsInstallState.Installed ->\n"
            "                        _uiState.value = _uiState.value.copy("
            "installProgress = null)\n"
            "                    is RootfsInstallState.Failed ->\n"
            "                        _uiState.value = _uiState.value.copy("
            "isProcessing = false)\n"
            "                    else -> Unit\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "\n"
        )
    else:  # missing — the subscription body is gone (ROT)
        collector = (
            "    private fun observeInstallProgress(manager: RootfsManager, ctx: Ctx) {\n"
            "        progressJob?.cancel()\n"
            "    }\n"
            "\n"
        )

    return (
        "    fun refresh(ctx: Ctx) {\n"
        "        _uiState.value = _uiState.value.copy(\n"
        "            isInstalled = manager.isInstalled,\n"
        + decision +
        "        )\n"
        "        if (manager.isInstalled) {\n"
        + inner +
        "        }\n"
        "    }\n"
        "\n"
        + collector
        + "    private fun settleRootfsFailure(ctx: Ctx, tag: String, res: Int, e: Exception) {\n"
        "        logging.log(tag)\n"
        "        _uiState.value = rootfsFailureState(_uiState.value, ctx.getString(res))\n"
        "        refresh(ctx)\n"
        "    }\n"
        "\n"
        "    private fun isStaleInstallReplay(\n"
        "        firstFrame: Boolean,\n"
        "        state: RootfsInstallState,\n"
        "        priorTerminal: RootfsInstallState,\n"
        "    ): Boolean = false\n"
        "\n"
        "    private fun rootfsRepairState(\n"
        "        current: Ctx,\n"
        "        terminal: RootfsInstallState,\n"
        "        repaired: Boolean,\n"
        "        ok: String,\n"
        "        missing: String,\n"
        + (
            "        priorTerminal: RootfsInstallState? = null,\n"
            if settlement_stale_guard else ""
        )
        + "    ): Ctx = when {\n"
        + (
            "        terminal is RootfsInstallState.Failed &&\n"
            "            !isStaleInstallReplay(true, terminal, priorTerminal) -> current\n"
            if settlement_stale_guard else
            "        terminal is RootfsInstallState.Failed -> current\n"
        )
        + "        repaired -> current\n"
        + "        else -> current\n"
        + "    }\n"
        + "\n"
        + entry("install", "install", 1, 1)
        + entry("resetRootfs", "reset", 2, 2)
        + entry("repairRootfs", "autoRepair", 3, 3)
    )



FILLER_GUARD = "isLinuxPathUnderReadOnlyMount("


def _filler(tag):
    """A guarded path-parameter write site — filler for the criterion-1 rot guards."""
    return (
        "package com.rikkaminis.app.filler\n"
        "\n"
        f"class Filler{tag} {{\n"
        f"    fun write{tag}(outputPath: String, data: String) {{\n"
        f"        if ({FILLER_GUARD}outputPath)) return\n"
        "        java.io.File(outputPath).writeText(data)\n"
        "    }\n"
        "}\n"
    )


MOUNT_READERS = {
    # the shape criterion 2 exists to keep: the probe hops to IO
    "io": (
        "    suspend fun refreshWritability() {\n"
        "        val probed = entry.resolvedHostPath?.let { p ->\n"
        "            withContext(Dispatchers.IO) { @@FN@@(p) }\n"
        "        } ?: false\n"
        "    }\n"
    ),
    # the pre-fix shape (live until deep-audit-1025)
    "direct": (
        "    suspend fun refreshWritability() {\n"
        "        val probed = entry.resolvedHostPath?.let { p -> @@FN@@(p) } ?: false\n"
        "    }\n"
    ),
    # `withContext(IO) expr` — correct at runtime, but not a block: fail-closed
    "inline_io": (
        "    suspend fun refreshWritability() {\n"
        "        val probed = entry.resolvedHostPath?.let { p ->\n"
        "            withContext(Dispatchers.IO) @@FN@@(p)\n"
        "        } ?: false\n"
        "    }\n"
    ),
}


def _mount_store(shape="io", fn_name="probeWritable", volume_shape="single"):
    reader = MOUNT_READERS[shape].replace("@@FN@@", fn_name)
    text = (
        "package com.rikkaminis.app.data\n"
        "\n"
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.withContext\n"
        "\n"
        "class MountedFoldersStore {\n"
        "    private val entry = Entry()\n"
        "\n"
        f"    fun {fn_name}(hostPath: String): Boolean {{\n"
        '        val probe = java.io.File(hostPath, ".minis-probe")\n'
        "        return runCatching { probe.outputStream().use { it.write(0) }; true }\n"
        "            .getOrDefault(false)\n"
        "            .also { runCatching { probe.delete() } }\n"
        "    }\n"
        "\n"
        + reader +
        "}\n"
    )
    # [dv20-A4-A06] The store owns the single definition of the volume-root
    # resolver; the kernel used to carry a drifted second copy. `VOLUME_ROOT_BODIES`
    # exists so an arm can drop the definition while keeping the file present.
    return text + VOLUME_ROOT_BODIES[volume_shape]

VOLUME_ROOT_BODIES = {
    "single": (
        "\n"
        "internal fun resolveVolumeRoot(context: Context, volumeId: String): String? {\n"
        "    return resolveVolumeRootDecision(volumeId, emptyList())\n"
        "}\n"
    ),
    "none": "",
}

# The drifted twin the kernel used to carry, standing in a file of its own so
# criterion 5's anchors stay untouched.
VOLUME_ROOT_TWIN = (
    "package com.rikkaminis.app.sandbox\n"
    "\n"
    "internal fun resolveVolumeRoot(context: Context, volumeId: String): String? {\n"
    "    return null\n"
    "}\n"
)

LOG_BODIES = {
    "io": (
        "            MinisTextButton(onClick = {\n"
        "                scope.launch {\n"
        "                    withContext(Dispatchers.IO) { AppLogger.clearLogs() }\n"
        "                    refresh()\n"
        "                }\n"
        "            })\n"
    ),
    # the pre-fix shape: purge on whatever thread the click arrived on
    "direct": (
        "            MinisTextButton(onClick = {\n"
        "                AppLogger.clearLogs()\n"
        "                refresh()\n"
        "            })\n"
    ),
    # IO used for a *different* call — the wrap must actually cover the purge
    "wrong_call": (
        "            MinisTextButton(onClick = {\n"
        "                withContext(Dispatchers.IO) { somethingElse() }\n"
        "                AppLogger.clearLogs()\n"
        "            })\n"
    ),
}


def _log_screen(shape="io"):
    return (
        "package com.rikkaminis.app.ui.settings\n"
        "\n"
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.withContext\n"
        "\n"
        "@Composable\n"
        "fun LogManagementScreen() {\n"
        + LOG_BODIES[shape] +
        "}\n"
    )


def _app_logger():
    return (
        "package com.rikkaminis.app.logging\n"
        "\n"
        "object AppLogger {\n"
        "    fun clearLogs() {\n"
        "    }\n"
        "}\n"
    )


# ── criterion-2 subject added by [deep-audit-1027 ISSUE-05] ──────────────────
# `RootfsManager.verifyIntegrity()` reads `.integrity_manifest` and stats seven
# critical files; the pre-fix ViewModel called it twice from a method reached
# straight from the screen's onClick.
ROOTFS_BODIES = {
    # the shape FIX-D ships: both reads inside `withContext(Dispatchers.IO) { … }`
    "io": (
        "    fun verifyRootfsOnCall(ctx: Ctx) {\n"
        "        val manager = RootfsManager.getInstance(ctx)\n"
        "        viewModelScope.launch {\n"
        "            val initial = withContext(Dispatchers.IO) {\n"
        "                manager.verifyIntegrity()\n"
        "            }\n"
        "            if (initial.healthy) return@launch\n"
        "            manager.autoRepair()\n"
        "            val after = withContext(Dispatchers.IO) { manager.verifyIntegrity() }\n"
        "        }\n"
        "    }\n"
    ),
    # pre-fix shape (live until deep-audit-1027): the first read on the click thread
    "direct": (
        "    fun verifyRootfsOnCall(ctx: Ctx) {\n"
        "        val manager = RootfsManager.getInstance(ctx)\n"
        "        val initial = manager.verifyIntegrity()\n"
        "        if (initial.healthy) return\n"
        "        viewModelScope.launch {\n"
        "            val after = withContext(Dispatchers.IO) { manager.verifyIntegrity() }\n"
        "        }\n"
        "    }\n"
    ),
    # the second read left inside `viewModelScope.launch`: Main.immediate is not an
    # IO hop, so the read still runs on the click thread
    "launch_only": (
        "    fun verifyRootfsOnCall(ctx: Ctx) {\n"
        "        val manager = RootfsManager.getInstance(ctx)\n"
        "        withContext(Dispatchers.IO) { manager.verifyIntegrity() }\n"
        "        viewModelScope.launch {\n"
        "            val after = manager.verifyIntegrity()\n"
        "        }\n"
        "    }\n"
    ),
    # correct at runtime, wrong shape (`withContext(IO) expr`) — fail-closed
    "inline_io": (
        "    fun verifyRootfsOnCall(ctx: Ctx) {\n"
        "        val manager = RootfsManager.getInstance(ctx)\n"
        "        val initial = withContext(Dispatchers.IO) manager.verifyIntegrity()\n"
        "    }\n"
    ),
}


def _rootfs_vm(shape="io", verdict_shape="derived", settlement_shape="settled"):
    return (
        "package com.rikkaminis.app.ui.sandbox\n"
        "\n"
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.launch\n"
        "import kotlinx.coroutines.withContext\n"
        "\n"
        "class RootfsManagementViewModel {\n"
        + ROOTFS_BODIES[shape] + VERDICT_BODIES[verdict_shape]
        + ROOTFS_SETTLEMENT_BODIES[settlement_shape] +
        "}\n"
    )

# [deep-audit-1041 B-02] Third axis on the same file. `_rootfs_settlement`
# declares the three lifecycle entry points, so criterion 2's subject had to
# stop calling itself `repairRootfs` (one definition per name in a fixture that
# is read as source).
ROOTFS_SETTLEMENT_BODIES = {
    "settled": _rootfs_settlement(),
    "catch_without_refresh": _rootfs_settlement(arms="direct"),
    "size_not_zeroed": _rootfs_settlement(size="missing"),
    "size_zeroed_inside_branch": _rootfs_settlement(size="inside_branch"),
    "no_repair_subscription": _rootfs_settlement(repair_subscribes=False),
    # [deep-audit-1042 B-05] the two halves of criterion 11, each dirty shape
    # breaking exactly one decision.
    "replay_applied": _rootfs_settlement(replay_gate=False),
    # wide3: the snapshot, the derived `replay` and the `return@collect` all stay;
    # only the branch's condition is pinned false.
    "replay_dead": _rootfs_settlement(replay_dead=True),
    "collector_gone": _rootfs_settlement(collect="missing"),
    "repair_settles_on_boolean": _rootfs_settlement(repair_verdict=False),
    # [deep-audit-1043 B-01] the two halves of the inherited-terminal decision.
    "repair_inherits_stale_terminal": _rootfs_settlement(repair_prior=False),
    "repair_settlement_ignores_prior": _rootfs_settlement(settlement_stale_guard=False),
}

# [dv20-A4-B03] `ROOTFS_BODIES` varies the blocking-IO decision; the *verdict*
# decision is a second, orthogonal axis. Every shape carries the derived verdict
# by default so the existing arms keep testing what they were written for, and
# `assumed` reverts to "the suspend call returned, therefore success".
VERDICT_BODIES = {
    "derived": (
        "    private fun settleRootfsOperation(manager: RootfsManager, ok: String) {\n"
        "        when (val terminal = manager.installState.value) {\n"
        "            is RootfsInstallState.Installed ->\n"
        "                _uiState.value = _uiState.value.copy(lastOperationSuccess = true)\n"
        "            is RootfsInstallState.Failed ->\n"
        "                _uiState.value = _uiState.value.copy(lastOperationSuccess = false)\n"
        "            else ->\n"
        "                _uiState.value = _uiState.value.copy(lastOperationSuccess = false)\n"
        "        }\n"
        "    }\n"
        "\n"
        "    fun verifyRootfsVerdict(manager: RootfsManager) {\n"
        "        val initial = manager.integritySnapshot()\n"
        "        if (initial.healthy) {\n"
        "            _uiState.value = _uiState.value.copy(lastOperationSuccess = true)\n"
        "        }\n"
        "    }\n"
        "\n"
    ),
    "assumed": (
        "    private fun settleRootfsOperation(manager: RootfsManager, ok: String) {\n"
        "        manager.driveInstall()\n"
        "        _uiState.value = _uiState.value.copy(lastOperationSuccess = true)\n"
        "    }\n"
        "\n"
    ),
}


def _rootfs_manager():
    """The declaration the criterion's fail-closed anchor points at."""
    return (
        "package com.rikkaminis.app.sandbox\n"
        "\n"
        "class RootfsManager {\n"
        "    fun verifyIntegrity(): Boolean = true\n"
        "\n"
        "    suspend fun autoRepair(): Boolean = true\n"
        "}\n"
    )


SHARE_BODIES = {
    # the shape the fix ships (deep-audit-1026 ISSUE-07): both disk-bound calls
    # inside the withContext(Dispatchers.IO) block
    "io": (
        "    fun onPositive(json: String, items: List<String>) {\n"
        "        lifecycleScope.launch {\n"
        "            withContext(Dispatchers.IO) {\n"
        "                if (importProviderJson(json)) discardStagedAttachments(items)\n"
        "            }\n"
        "        }\n"
        "    }\n"
    ),
    # pre-fix shape for the import: the staged-file delete is wrapped, the
    # import (ensureConfigLoaded + prefs commit + Room write) is not
    "direct_import": (
        "    fun onPositive(json: String, items: List<String>) {\n"
        "        if (importProviderJson(json)) {\n"
        "            withContext(Dispatchers.IO) { discardStagedAttachments(items) }\n"
        "        }\n"
        "    }\n"
    ),
    # the delete left on the dialog callback's thread
    "direct_discard": (
        "    fun onPositive(json: String, items: List<String>) {\n"
        "        withContext(Dispatchers.IO) { importProviderJson(json) }\n"
        "        discardStagedAttachments(items)\n"
        "    }\n"
    ),
    # `lifecycleScope.launch` alone is NOT an IO wrap — the body still runs on
    # Main, so both calls must stay red (fail-closed on a plausible mistake)
    "launch_only": (
        "    fun onPositive(json: String, items: List<String>) {\n"
        "        lifecycleScope.launch {\n"
        "            if (importProviderJson(json)) discardStagedAttachments(items)\n"
        "        }\n"
        "    }\n"
    ),
}

# Criterion-3 fixtures: what the `toast()` helper looks like. `hop` is the shape
# ISSUE-07 ships; `direct` is the crash-on-first-import shape; `missing` is the
# rot shape (the helper was deleted outright, so the criterion must not read
# the tree as clean by checking nothing).
SHARE_TOAST_BODIES = {
    "hop": (
        "    private fun toast(msg: String) {\n"
        "        runOnUiThread {\n"
        "            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()\n"
        "        }\n"
        "    }\n"
    ),
    "direct": (
        "    private fun toast(msg: String) {\n"
        "        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()\n"
        "    }\n"
    ),
    "missing": "",
}


def _share_receiver(shape="io", toast_hop="hop"):
    return (
        "package com.rikkaminis.app.share\n"
        "\n"
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.launch\n"
        "import kotlinx.coroutines.withContext\n"
        "\n"
        "class ShareReceiverActivity {\n"
        "    fun importProviderJson(json: String): Boolean = json.isNotEmpty()\n"
        "\n"
        "    fun discardStagedAttachments(items: List<String>) {\n"
        "        items.forEach { java.io.File(it).length() }\n"
        "    }\n"
        "\n"
        + SHARE_BODIES[shape] +
        "\n"
        + SHARE_TOAST_BODIES[toast_hop] +
        "}\n"
    )


BROWSER_MOUNT_GUARD = (
    "        linuxPathOf(item)?.let { lp ->\n"
    "            if (PRootKernel.isLinuxPathUnderReadOnlyMount(lp)) {\n"
    "                return@launch\n"
    "            }\n"
    "        }\n"
)

# [deep-audit-1033 I01] The second, independent refusal the delete path owes:
# the browser also opens the folders the settings screen labels Read-Only.
BROWSER_SHARED_GUARD = (
    "        linuxPathOf(item)?.let { lp ->\n"
    "            if (SharedFolderRegistry.isLinuxPathUnderReadOnlySharedDir(lp)) {\n"
    "                return@launch\n"
    "            }\n"
    "        }\n"
)

BROWSER_GUARD_BODY = BROWSER_MOUNT_GUARD + BROWSER_SHARED_GUARD


def _file_browser_vm(shape="guard"):
    """Criterion-5 anchor: the delete path of a listed item.

    [shape] guard (default — both refusals, in front of the delete) /
    mount_only (the da1033 defect shape: read-only mounts refused, the read-only
    shared folders not) / shared_first (both, written in the other order —
    precision arm: order must not matter) / shared_late (mount refusal in front,
    shared refusal after the delete) / direct (no guard) / late (both guards
    AFTER the delete — the post-mortem shape) / renamed (function renamed away →
    ROT).
    """
    if shape == "guard":
        head, tail = BROWSER_GUARD_BODY, ""
    elif shape == "shared_first":
        head, tail = BROWSER_SHARED_GUARD + BROWSER_MOUNT_GUARD, ""
    elif shape == "mount_only":
        head, tail = BROWSER_MOUNT_GUARD, ""
    elif shape == "shared_late":
        head, tail = BROWSER_MOUNT_GUARD, BROWSER_SHARED_GUARD
    elif shape == "dead_guard":
        # wide3: the mount refusal keeps its call, its position and its
        # `return@launch`; only the condition is pinned false.
        head, tail = (BROWSER_MOUNT_GUARD.replace(
            "if (PRootKernel.isLinuxPathUnderReadOnlyMount(lp))",
            "if (false && PRootKernel.isLinuxPathUnderReadOnlyMount(lp))")
            + BROWSER_SHARED_GUARD, "")
    elif shape == "late":
        head, tail = "", BROWSER_GUARD_BODY
    else:  # direct / renamed
        head, tail = "", ""
    fn = "deleteEntry" if shape == "renamed" else "deleteItem"
    return (
        "package com.rikkaminis.app.ui.sandbox\n"
        "\n"
        "class FileBrowserViewModel {\n"
        "    private fun linuxPathOf(item: FileItem): String? = null\n"
        "\n"
        f"    fun {fn}(item: FileItem) {{\n"
        "        viewModelScope.launch(Dispatchers.IO) {\n"
        + head +
        "            try {\n"
        "                val deleted = if (item.isDirectory) "
        "item.file.deleteRecursively() else item.file.delete()\n"
        "            } catch (e: Exception) {\n"
        "            }\n"
        + tail +
        "        }\n"
        "    }\n"
        "}\n"
    )


RPC_GUARD_BODY = (
    "        if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {\n"
    "            throw RPCException(-32000, \"Read-only mount: '$path' is not writable\")\n"
    "        }\n"
)


def _debug_rpc(shape="guard"):
    """Criterion-5 anchor: the RPC write path (`params` is not a whitelisted
    parameter name, which is exactly why this criterion is keyed on the
    function name)."""
    if shape == "direct":
        guard = ""
    elif shape == "dead_guard":
        # wide3: same call, same position, same throwing body — pinned false.
        guard = RPC_GUARD_BODY.replace(
            "if (PRootKernel.isLinuxPathUnderReadOnlyMount(path))",
            "if (false && PRootKernel.isLinuxPathUnderReadOnlyMount(path))")
    else:
        guard = RPC_GUARD_BODY
    fn = "handleWrite" if shape == "renamed" else "handleWriteFile"
    return (
        "package com.rikkaminis.app.debug\n"
        "\n"
        "class DebugRPCHandler {\n"
        f"    private fun {fn}(params: JSONObject) {{\n"
        + guard +
        "        val hostFile = PRootKernel.resolveHostPath(path) ?: File(path)\n"
        "        hostFile.writeBytes(content.toByteArray())\n"
        "    }\n"
        "}\n"
    )


def _app_nav(shape="guard"):
    """Criterion-5 anchor: the mounted-folder browse route's guest prefix.

    [shape] guard (route carries the prefix) / no_prefix (host path only — the
    guard above then never sees a mount path) / no_route (no fileBrowser call at
    all → ROT).
    """
    if shape == "no_route":
        call = ""
    elif shape == "no_prefix":
        call = (
            "        navController.safeNavigate(\n"
            "            Routes.fileBrowser(\n"
            "                rootPath = hostPath,\n"
            "                rootLabel = entry.name,\n"
            "            ),\n"
            "        )\n"
        )
    else:
        call = (
            "        navController.safeNavigate(\n"
            "            Routes.fileBrowser(\n"
            "                rootPath = hostPath,\n"
            "                rootLabel = entry.name,\n"
            '                linuxRoot = "/var/minis/mounts/${entry.name}",\n'
            '                displayPrefix = "/var/minis/mounts/${entry.name}",\n'
            "            ),\n"
            "        )\n"
        )
    return (
        "package com.rikkaminis.app.ui.navigation\n"
        "\n"
        "class AppNavigation {\n"
        "    private fun mountedFolderDetail(hostPath: String?) {\n"
        + call +
        "    }\n"
        "}\n"
    )


# ── criterion-2 subjects added by [deep-audit-1033 I05 / I08] ────────────────
# Three save paths that used to run on the click's thread. They are why the C2
# table grew receiver-qualified rows: the blocking work is reached through the
# repository/store handle the screen holds.

def _save_targets():
    """Declarations the C2 rows look for (a call without a declaration would be
    reported as vacuous instead of passing, so the fixture ships both)."""
    return (
        "package com.rikkaminis.app.ui.settings\n"
        "\n"
        "class ProviderRepository {\n"
        "    fun addInstance(instance: Any, apiKey: String) {}\n"
        "    fun saveApiKey(id: String, key: String) {}\n"
        "    fun addEntry(entry: Any) {}\n"
        "}\n"
        "\n"
        "class WebDavConfigStore {\n"
        "    fun load(): Any? = null\n"
        "    fun save(cfg: Any) {}\n"
        "}\n"
    )


def _onboarding_screen(shape="io"):
    """Onboarding save button: SQLite insert + Keystore commit, in that order."""
    calls = (
        "                providerRepository.addInstance(instance, apiKey)\n"
        "                providerRepository.saveApiKey(instance.id, apiKey.trim())\n"
    )
    if shape == "io":
        body = (
            "        scope.launch {\n"
            "            withContext(Dispatchers.IO) {\n"
            + calls +
            "            }\n"
            "        }\n"
        )
    elif shape == "io_nested":
        body = (
            "        scope.launch {\n"
            "            runCatching {\n"
            "                withContext(Dispatchers.IO) {\n"
            + calls +
            "                }\n"
            "            }\n"
            "        }\n"
        )
    else:  # direct
        body = (
            "        providerRepository.addInstance(instance, apiKey)\n"
            "        providerRepository.saveApiKey(instance.id, apiKey.trim())\n"
        )
    return (
        "package com.rikkaminis.app.ui.onboarding\n"
        "\n"
        "class OnboardingScreen {\n"
        "    fun save(\n"
        "        providerRepository: ProviderRepository,\n"
        "        instance: Any,\n"
        "        apiKey: String,\n"
        "    ) {\n"
        + body +
        "    }\n"
        "}\n"
    )


def _add_model_screen(shape="io"):
    """Custom-model save button: one SQLite insert, then navigate back."""
    if shape == "io":
        body = (
            "        scope.launch {\n"
            "            withContext(Dispatchers.IO) {\n"
            "                providerRepository.addEntry(entry)\n"
            "            }\n"
            "            onBack()\n"
            "        }\n"
        )
    elif shape == "none":
        body = "        onBack()\n"
    else:  # direct
        body = (
            "        providerRepository.addEntry(entry)\n"
            "        onBack()\n"
        )
    return (
        "package com.rikkaminis.app.ui.settings\n"
        "\n"
        "class AddCustomModelScreen {\n"
        "    fun save(providerRepository: ProviderRepository) {\n"
        + body +
        "    }\n"
        "}\n"
    )


def _backup_screen(shape="io"):
    """Backup screen: WebDAV config read at first composition + persist on save."""
    if shape == "io":
        body = (
            "        LaunchedEffect(Unit) {\n"
            "            withContext(Dispatchers.IO) {\n"
            "                webDavStore.load()\n"
            "            }\n"
            "        }\n"
            "        scope.launch {\n"
            "            withContext(Dispatchers.IO) {\n"
            "                webDavStore.save(cfg)\n"
            "            }\n"
            "        }\n"
        )
    else:  # direct
        body = (
            "        webDavStore.load()\n"
            "        webDavStore.save(cfg)\n"
        )
    return (
        "package com.rikkaminis.app.ui.settings\n"
        "\n"
        "class BackupSettingsScreen {\n"
        "    fun applyWebDavConfig(\n"
        "        webDavStore: WebDavConfigStore,\n"
        "        cfg: Any,\n"
        "    ) {\n"
        + body +
        "    }\n"
        "}\n"
    )


# ── Criterion 6 (deep-audit-1034 A2 / ISSUE-02): the SAF provider's own root ──
# `documentId` arrives in the caller's spelling ("/", " ", "shared"), so two
# decisions must be taken on the NORMALISED id rather than the raw string:
#   D1 the root test — a client passing "/" must not receive the top-level
#      listing (the TOP_LEVEL filter was skipped, so every internal filesDir
#      entry leaked into the Files app)
#   D2 the top-level test — the whitelist directories themselves must not be
#      removed (`deleteDocument("shared")` used to `deleteRecursively()` the
#      whole shared tree).
DOCUMENTS_PROVIDER_REL = "providers/MinisDocumentsProvider.kt"
DOC_RAW_ROOT_TEST = re.compile(r"\bparentDocumentId\s*\.\s*isEmpty\(\)")
DOC_NORMALISED_ARG = re.compile(r"[A-Za-z_]\w*\(\s*parentDocumentId\s*[,)]")
TOPLEVEL_DECISION = re.compile(
    # Both spellings of the separator are live Kotlin: `contains("/")` and
    # `contains('/')` — production uses the char form, the fixtures the string
    # form, and a criterion that only knew one of them reported the real tree
    # red on its first run.
    r"""(?:contains|startsWith|endsWith|indexOf|lastIndexOf)\(\s*(?:'/'|"/")\s*\)"""
    r"""|split\(\s*(?:'/'|"/")\s*[,)]"""
    r"""|trim\(\s*(?:'/'|"/")\s*\)"""
)

_DOCUMENTS_PROVIDER = '''package com.rikkaminis.app.providers

class MinisDocumentsProvider : DocumentsProvider() {
    private fun normalizeDocId(id: String): String = id.trim().trim('/')

    internal fun isTopLevelDocId(id: String): Boolean = !normalizeDocId(id).contains("/")

    private fun readOnlyTopOf(clean: String): String? = clean.split("/").firstOrNull()

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
    ): Cursor {
        val cleanParent = @ROOT@
        val isRoot = cleanParent.isEmpty()
        for (child in parent.listFiles()?.toList() ?: emptyList()) {
            if (isRoot && child.name !in TOP_LEVEL) continue
        }
        return cursor
    }

    override fun deleteDocument(documentId: String) {
        val file = resolveDoc(documentId)
        val clean = normalizeDocId(documentId)
        if (clean.isEmpty()) throw UnsupportedOperationException("Provider root cannot be deleted")
        val top = readOnlyTopOf(clean)
        if (top != null) throw UnsupportedOperationException("$top is read-only")
        @TOPLEVEL@
        if (!file.deleteRecursively()) throw FileNotFoundException("Failed to delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val clean = normalizeDocId(documentId)
        if (clean.isEmpty()) throw UnsupportedOperationException("Provider root cannot be renamed")
        val top = readOnlyTopOf(clean)
        if (top != null) throw UnsupportedOperationException("$top is read-only")
        @TOPLEVEL@
        if (!file.renameTo(target)) throw FileNotFoundException("Failed to rename $documentId")
        return documentIdFor(target)
    }

@FUNNEL_FUN@}
'''

_DOC_TOPLEVEL_GUARD = """if (isTopLevelDocId(documentId)) {
            throw UnsupportedOperationException("Top-level '$clean' cannot be removed")
        }"""


_DOC_FUNNEL_FUN = """    private fun resolveDoc(documentId: String): File {
        val root = providerRoot()
        val clean = normalizeDocId(documentId)
        if (clean.isEmpty()) return root
        if (clean.contains("..")) throw FileNotFoundException("Invalid path: $documentId")
@FUNNEL@
        val target = File(root, clean)
        return target
    }
"""

# [dv20-A4-A07] The whitelist that guarded the listing only, now guarding the one
# funnel every entry point resolves an id through.
_DOC_FUNNEL_GUARD = """        val top = clean.substringBefore('/')
        if (top !in TOP_LEVEL) {
            throw FileNotFoundException("Not a top-level entry: $documentId")
        }"""

def _documents_provider(shape="clean"):
    root_expr = "parentDocumentId" if shape == "raw_root" else "normalizeDocId(parentDocumentId)"
    guard = "" if shape == "no_toplevel_guard" else _DOC_TOPLEVEL_GUARD
    funnel = "" if shape == "funnel_blind" else _DOC_FUNNEL_GUARD
    return (_DOCUMENTS_PROVIDER
            .replace("@ROOT@", root_expr)
            .replace("@TOPLEVEL@", guard)
            .replace("@FUNNEL_FUN@", _DOC_FUNNEL_FUN)
            .replace("@FUNNEL@", funnel))


def check_documents_provider(root):
    """(violations, rot, sites, notes) — criterion 6's two decisions."""
    violations, rot, notes = [], [], []
    path = os.path.join(root, APP_REL, DOCUMENTS_PROVIDER_REL)
    pkg = os.path.dirname(path)
    if not os.path.isfile(path):
        if os.path.isdir(pkg) and os.listdir(pkg):
            rot.append(
                "%s lost its anchor (the providers package is there, the file is not)"
                % DOCUMENTS_PROVIDER_REL
            )
        else:
            notes.append(
                "[PARTIAL] the read-only-mount provider anchors — this tree never shipped "
                "%s" % DOCUMENTS_PROVIDER_REL
            )
        return violations, rot, 0, notes

    with open(path, encoding="utf-8", errors="replace") as fh:
        raw = fh.read()
    # Two views: the collapsed one for the call/argument shapes (a guard call
    # quoted in prose must not count), the literal-preserving one for the
    # '/'-ness decision — the separator *is* a literal, so collapsing it would
    # hide the very thing D2 looks for.
    text = strip_comments(raw)
    literals = strip_comments(raw, collapse_literals=False)
    fns = {name: body for name, _params, body in functions(text)}
    sites = 0

    # D1 — the root test consumes a normalised id, never the raw parameter.
    if "queryChildDocuments" not in fns:
        rot.append("%s: `queryChildDocuments` is gone — the root decision moved"
                   % DOCUMENTS_PROVIDER_REL)
    else:
        body = fns["queryChildDocuments"]
        sites += 1
        if DOC_RAW_ROOT_TEST.search(body):
            violations.append(
                "%s:queryChildDocuments — the root test runs on the RAW documentId "
                "(`parentDocumentId.isEmpty()`), so \"/\" skips the top-level filter"
                % DOCUMENTS_PROVIDER_REL
            )
        elif not DOC_NORMALISED_ARG.search(body):
            violations.append(
                "%s:queryChildDocuments — the document id is never normalised before the "
                "root test" % DOCUMENTS_PROVIDER_REL
            )

    # D2 — delete/rename refuse a top-level entry through that same decision. The
    # decision is *discovered* (any Boolean-returning function whose own body
    # expresses the '/'-ness test), never hard-coded by name, so renaming the
    # helper cannot silently free the top level again; requiring the Boolean
    # return type keeps an unrelated string helper (`…TopOf`) from standing in
    # for the decision.
    top_names = set()
    for m in re.finditer(r"\bfun\s+(\w+)\s*\([^)]*\)\s*:\s*Boolean\b", literals):
        window = literals[m.end():m.end() + 200]
        window = re.split(r"\n\s*\n|\n\s*(?:internal |private |public |override )?fun ", window)[0]
        if TOPLEVEL_DECISION.search(window):
            top_names.add(m.group(1))
    for fn in ("deleteDocument", "renameDocument"):
        if fn not in fns:
            rot.append("%s: `%s` is gone — the top-level decision moved"
                       % (DOCUMENTS_PROVIDER_REL, fn))
            continue
        body = fns[fn]
        sites += 1
        test_used = any(re.search(r"\b%s\s*\(" % re.escape(n), body) for n in top_names)
        refuses = "UnsupportedOperationException(" in body or "errEnvelope(" in body
        if not test_used:
            violations.append(
                "%s:%s — no top-level refusal: a whitelist directory itself can still be "
                "removed, taking the whole tree with it" % (DOCUMENTS_PROVIDER_REL, fn)
            )
        elif not refuses:
            violations.append(
                "%s:%s — the top-level test is evaluated but nothing refuses on it"
                % (DOCUMENTS_PROVIDER_REL, fn)
            )
    return violations, rot, sites, notes


# --- criterion 7/8/9: the three decisions deep-audit-1040 A4 touched ---------

FUN_START = re.compile(
    r"(?m)^[ \t]*(?:private |internal |public |override |suspend |open )*fun\s+(\w+)"
)
VOLUME_ROOT_STORE_REL = "data/MountedFoldersStore.kt"
ROOTFS_VM_REL = "ui/sandbox/RootfsManagementViewModel.kt"
VOLUME_ROOT_DEF = re.compile(r"\bfun\s+resolveVolumeRoot\s*\(")
SUCCESS_CLAIM = re.compile(r"lastOperationSuccess\s*=\s*true\b")
VERDICT_SOURCE = re.compile(r"\binstallState\b|\bRootfsInstallState\.\w+|\.healthy\b")
# A funnel is the function that turns a *document id* into a `File` — the one
# every entry point resolves through. Anchoring on the id parameter rather than
# on a name (`resolveDoc`) is what makes a rename a rot failure instead of a
# silent pass.
FUNNEL_DECL = re.compile(r"\bfun\s+(\w+)\s*\(([^)]*)\)\s*:\s*File\??")
FUNNEL_ID_PARAM = re.compile(r"\bdocumentId\b")
FUNNEL_WHITELIST = re.compile(r"!?\s*\bin\s+([A-Z_][A-Z0-9_]*)\b")


def _a4_brace_block(text, open_idx):
    depth = 0
    k = open_idx
    while k < len(text):
        c = text[k]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[open_idx:k + 1]
        k += 1
    return text[open_idx:]


def check_documents_provider_funnel(root):
    """(violations, rot, sites, notes) — criterion 9 (A-07).

    The whitelist used to guard the LISTING only: `queryChildDocuments` skipped
    anything outside `TOP_LEVEL` when the parent was root, but every other entry
    point resolved an arbitrary document id straight to a File — `mcp-servers/…`,
    `logs/…` and any other filesDir entry were addressable even though they never
    appear in the Files app. The subject is the funnel, not a call site: guarding
    it guards every caller at once.
    """
    violations, rot, notes = [], [], []
    path = os.path.join(root, APP_REL, *DOCUMENTS_PROVIDER_REL.split("/"))
    pkg = os.path.dirname(path)
    if not os.path.isfile(path):
        if os.path.isdir(pkg) and os.listdir(pkg):
            rot.append(
                "%s lost its anchor (the providers package is there, the file is not)"
                % DOCUMENTS_PROVIDER_REL
            )
        else:
            notes.append(
                "[PARTIAL] the provider id->File funnel — this tree never shipped "
                "%s" % DOCUMENTS_PROVIDER_REL
            )
        return violations, rot, 0, notes

    with open(path, encoding="utf-8", errors="replace") as fh:
        text = strip_comments(fh.read())
    starts = [m.start() for m in FUN_START.finditer(text)]
    sites = 0
    for i, off in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(text)
        head = text[off:end]
        brace = head.find("{")
        decl = FUNNEL_DECL.search(head if brace < 0 else head[:brace])
        if not decl or not FUNNEL_ID_PARAM.search(decl.group(2)):
            continue
        if brace < 0:
            continue
        sites += 1
        body = _a4_brace_block(text, off + brace)
        if not FUNNEL_WHITELIST.search(body):
            violations.append(
                "%s: `%s` turns a document id into a File without judging the "
                "top-level whitelist — ids outside what the provider exposes are "
                "reachable" % (DOCUMENTS_PROVIDER_REL, decl.group(1))
            )
    if sites == 0:
        rot.append(
            "%s: no id->File funnel found — the detector stopped matching "
            "(the id-taking File returner was renamed or retyped?)"
            % DOCUMENTS_PROVIDER_REL
        )
    return violations, rot, sites, notes


def check_volume_root_single_source(root):
    """(violations, rot, sites, notes) — criterion 7 (A-06).

    The volume-root resolver existed twice — once in the store, once as a
    drifted copy inside the kernel — so the two readers could disagree about
    which directory backs a volume. The rule is "exactly one definition, and it
    lives in the store": a rename that moves the single source *out* of the
    store is still one definition, and is reported as such rather than silently
    accepted.
    """
    violations, rot, notes = [], [], []
    anchor = os.path.join(root, APP_REL, *VOLUME_ROOT_STORE_REL.split("/"))
    pkg = os.path.dirname(anchor)
    if not os.path.isfile(anchor):
        if os.path.isdir(pkg) and os.listdir(pkg):
            rot.append(
                "%s lost its anchor (the data package is there, the file is not)"
                % VOLUME_ROOT_STORE_REL
            )
        else:
            notes.append(
                "[PARTIAL] the single-source volume-root anchor — this tree never "
                "shipped %s" % VOLUME_ROOT_STORE_REL
            )
        return violations, rot, 0, notes

    defs = []
    for full in app_kt_files(root):
        with open(full, encoding="utf-8", errors="replace") as fh:
            text = strip_comments(fh.read())
        n = len(VOLUME_ROOT_DEF.findall(text))
        if n:
            rel = os.path.relpath(full, os.path.join(root, APP_REL)).replace(os.sep, "/")
            defs.append((rel, n))
    sites = sum(n for _rel, n in defs)
    if sites == 0:
        rot.append(
            "no definition matching `fun resolveVolumeRoot(` anywhere under "
            "com/rikkaminis/app — the single source vanished or was renamed"
        )
        return violations, rot, 0, notes
    if sites > 1:
        where = ", ".join("%s (%d)" % (rel, n) for rel, n in sorted(defs))
        violations.append(
            "the volume-root resolver has %d definitions — %s; the two readers "
            "can disagree about which directory backs a volume" % (sites, where)
        )
    elif defs[0][0] != VOLUME_ROOT_STORE_REL:
        violations.append(
            "the single volume-root resolver lives in %s, not in %s — the store "
            "is no longer its source" % (defs[0][0], VOLUME_ROOT_STORE_REL)
        )
    return violations, rot, sites, notes


def check_rootfs_verdict(root):
    """(violations, rot, sites, notes) — criterion 8 (B-03).

    `RootfsManager.installIfNeeded()` reports "not enough disk space" by
    publishing `RootfsInstallState.Failed(...)` and returning normally; it never
    throws. Awaiting it therefore proves nothing, and the old unconditional
    `lastOperationSuccess = true` turned "refused, nothing extracted" into a
    green "installed successfully". The criterion is per function: any function
    that claims success must also consult an independent verdict source — the
    published terminal state, or the integrity check's `.healthy`.
    """
    violations, rot, notes = [], [], []
    path = os.path.join(root, APP_REL, *ROOTFS_VM_REL.split("/"))
    pkg = os.path.dirname(path)
    if not os.path.isfile(path):
        if os.path.isdir(pkg) and os.listdir(pkg):
            rot.append(
                "%s lost its anchor (the ui/sandbox package is there, the file is not)"
                % ROOTFS_VM_REL
            )
        else:
            notes.append(
                "[PARTIAL] the rootfs verdict anchor — this tree never shipped %s"
                % ROOTFS_VM_REL
            )
        return violations, rot, 0, notes

    with open(path, encoding="utf-8", errors="replace") as fh:
        text = strip_comments(fh.read())
    starts = [m.start() for m in FUN_START.finditer(text)]
    sites = 0
    named = FUN_START.findall(text)
    for i, off in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(text)
        region = text[off:end]
        if not SUCCESS_CLAIM.search(region):
            continue
        sites += 1
        if not VERDICT_SOURCE.search(region):
            violations.append(
                "%s: `%s` claims success (lastOperationSuccess = true) without "
                "consulting the manager's terminal state — a refused install "
                "still reports success"
                % (ROOTFS_VM_REL, named[i] if i < len(named) else "<unknown>")
            )
    if SUCCESS_CLAIM.findall(text) and sites == 0:
        rot.append(
            "%s: success claims exist but none sat inside a function body — the "
            "verdict detector stopped matching" % ROOTFS_VM_REL
        )
        return violations, rot, 0, notes
    if sites == 0:
        rot.append(
            "%s: no success claim found at all — the verdict detector stopped "
            "matching (the field was renamed?)" % ROOTFS_VM_REL
        )
    return violations, rot, sites, notes

# ── criterion 10 (deep-audit-1041 B-02): the rootfs failure terminal ────────
#
# `install` / `resetRootfs` / `repairRootfs` all end their SUCCESS path with
# `refresh(ctx)`; the `catch` arms used to stop at the state copy. That is only
# enough when the failed operation left the rootfs alone — and none of them is:
# `RootfsManager.reset()` deletes the directory BEFORE re-installing and
# `autoRepair()`'s Stage 3 is that same reset, so a throw mid-extract left the
# screen rendering a rootfs that no longer existed (green "Installed", the
# "Install" button hidden behind `if (!isInstalled)`).
#
# The subject is the DECISION ("what must the failure arm re-read"), not the
# spelling: a `catch` passes when it reaches `refresh()`, directly or through a
# helper declared in the same file that reaches it — so a delegation is fine
# and a rename is not a silent pass. Anchoring instead on "refresh() appears
# somewhere in the function" would be satisfied by the success path, which is
# exactly the shape that shipped the defect.
ROOTFS_LIFECYCLE = ("install", "resetRootfs", "repairRootfs")
REFRESH_DEF = re.compile(r"\bfun\s+refresh\s*\(")
OBSERVE_PROGRESS_DEF = re.compile(r"\bfun\s+observeInstallProgress\s*\(")
CATCH_HEAD = re.compile(r"\bcatch\s*\([^)]*\)\s*\{")
# refresh decides the size from the manager's own verdict: either the decision
# is taken inline (`if (…isInstalled…) old else 0L`) or the not-installed arm is
# handled outright (`rootfsSize = 0L`).
SIZE_ZEROING = re.compile(
    r"rootfsSize\s*=\s*if\s*\([^)]*isInstalled[^)]*\)[^,\n]*\belse\s+0L?\b"
    r"|rootfsSize\s*=\s*0L?\b"
)
INSTALLED_BRANCH = re.compile(r"if\s*\([^)]*isInstalled[^)]*\)\s*\{")

# ── Criterion 11 (deep-audit-1042 B-05): the replayed terminal ──────────────
#
# `RootfsManager.installState` is a StateFlow whose value never leaves a
# terminal (eight write sites, not one of them resets to `Idle`), so the value
# a subscriber receives as its FIRST frame is the previous operation's verdict,
# not an event. Applying that frame is two defects in one: the stale `Installed`
# arm wipes the `installProgress` the caller just published, and the stale
# `Failed` arm clears `isProcessing` — the flag the screen disables Repair/Reset
# on — so the in-flight guard inverts.
#
# Anchored on the DECISION (recognise the frame as a replay), not on a name:
# either a value captured before `.collect` that the collector consults, or a
# `dropWhile` in front of it. A collector that subscribes with neither has, by
# construction, no way to tell a replay from an event.
INSTALL_STATE_COLLECT = re.compile(r"installState\s*\.\s*collect\s*[\{\(]")
DROPWHILE_ON_FLOW = re.compile(r"installState\s*\.\s*(?:dropWhile|filterNot)\s*\(")
# the value the flow is holding before the subscription starts
PRIOR_CAPTURE = re.compile(r"\bval\s+(\w+)\s*=\s*[\w.]*installState\s*\.\s*value\b")
# ── the repair settlement must consult the same verdict ─────────────────────
#
# `autoRepair()`'s Stage 3 is a full `reset()`, and `installIfNeeded()` reports
# its disk-space gate by publishing `Failed(rootfs_insufficient_space)` and
# RETURNING (it never throws). Settling on the boolean alone therefore announces
# a storage refusal as rootfs corruption. The repair path must read the manager's
# published terminal, directly or through a helper.
VERDICT_READ = re.compile(r"\binstallState\s*\.\s*value\b|\bRootfsInstallState\s*\.\s*\w+")
# ── the settlement must tell an inherited terminal from its own ──────────────
#
# [deep-audit-1043 B-01] `installState` never returns to `Idle` and `autoRepair()`
# publishes nothing in Stages 1/2/2.5/2.6, so the terminal sitting on the flow
# when a repair starts is the PREVIOUS operation's verdict. A settlement that
# reads it without comparing it to what it inherited reports yesterday's error
# over a repair that just restored every critical file. Anchored on the DECISION
# (the `Failed` arm's condition consults the prior terminal) and on
# instance-identity spelling only as one of two accepted forms.
REPAIR_STALE_GUARD = re.compile(r"isStaleInstallReplay\s*\(|!==")


def call_args(text, call):
    """The parenthesised argument list of the first `<call>` in [text], or None.

    [text] is expected to come from `strip_comments` (string literals collapsed),
    so parenthesis counting needs no quote handling.
    """
    at = text.find(call)
    if at < 0:
        return None
    open_idx = text.find("(", at)
    if open_idx < 0:
        return None
    depth, i = 0, open_idx
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i]
        i += 1
    return None


def check_rootfs_progress_replay(root):
    """(violations, rot, sites, notes) — criterion 11 (B-05)."""
    violations, rot, notes = [], [], []
    path = os.path.join(root, APP_REL, *ROOTFS_VM_REL.split("/"))
    pkg = os.path.dirname(path)
    if not os.path.isfile(path):
        if os.path.isdir(pkg) and os.listdir(pkg):
            rot.append(
                "%s lost its anchor (the ui/sandbox package is there, the file is not)"
                % ROOTFS_VM_REL
            )
        else:
            notes.append(
                "[PARTIAL] the rootfs progress-replay anchor — this tree never shipped %s"
                % ROOTFS_VM_REL
            )
        return violations, rot, 0, notes

    with open(path, encoding="utf-8", errors="replace") as fh:
        text = strip_comments(fh.read())

    if not OBSERVE_PROGRESS_DEF.search(text):
        rot.append(
            "%s: no `fun observeInstallProgress(` left — the replay gate has nothing "
            "to guard" % ROOTFS_VM_REL
        )
        return violations, rot, 0, notes

    bodies = {}
    for name, _params, body in functions(text):
        bodies.setdefault(name, body)

    # Names whose body reads the manager's published terminal, closed
    # transitively: the repair settlement may delegate, but the read has to
    # land somewhere. The progress collector is excluded from the seed: its own
    # read of `installState.value` is the replay capture, not a settlement
    # verdict, and `repairRootfs` subscribes to it — counting that would make
    # every repair path look like it consulted the verdict.
    reads_verdict = {
        name for name, body in bodies.items()
        if name != "observeInstallProgress" and VERDICT_READ.search(body)
    }
    growing = True
    while growing:
        growing = False
        for name, body in bodies.items():
            if name in reads_verdict:
                continue
            if any(re.search(r"\b%s\s*\(" % re.escape(n), body) for n in reads_verdict):
                reads_verdict.add(name)
                growing = True

    sites = 0
    collector = bodies.get("observeInstallProgress", "")
    collects = list(INSTALL_STATE_COLLECT.finditer(collector))
    # The gate must be consulted *inside* the collector, not merely declared in
    # some other function of the same file.
    if not collects:
        rot.append(
            "%s: `observeInstallProgress` no longer collects `installState` — the "
            "replay gate lost its subject (was it moved out of this file?)"
            % ROOTFS_VM_REL
        )
    else:
        sites += 1
        gated = bool(DROPWHILE_ON_FLOW.search(collector))
        dead_gate = None
        if not gated:
            after_collect = collector[collects[0].end():]
            for m in PRIOR_CAPTURE.finditer(collector):
                if m.start() >= collects[0].start():
                    continue
                name = m.group(1)
                # The captured value must reach a branch that TERMINATES the
                # frame. Merely naming it inside the collector was satisfied by
                # the wide3 witness `if (false && replay) return@collect`: the
                # snapshot, the derived `replay` and the `return@collect` all
                # stayed, the skip did not.
                carriers = {name} | alias_names(after_collect, name)
                branches = decision_branches(
                    after_collect, carriers, ("return", "continue"))
                live = []
                for cond, _bs, _be, _body in branches:
                    if constant_truth(cond) is False:
                        if dead_gate is None:
                            dead_gate = cond.strip()
                        continue
                    live.append(cond)
                if live:
                    gated = True
                    break
        if not gated:
            if dead_gate is not None:
                violations.append(
                    "%s:observeInstallProgress — the replayed terminal is skipped "
                    "only behind `if (%s)`, whose condition is constantly false: "
                    "the snapshot, the `isStaleInstallReplay` call and the "
                    "`return@collect` are all still there, and the collector still "
                    "applies every frame `installState` replays — including the "
                    "previous operation's failed terminal, which wipes the "
                    "`installProgress` the caller just published and clears "
                    "`isProcessing`" % (ROOTFS_VM_REL, dead_gate)
                )
            else:
                violations.append(
                    "%s:observeInstallProgress — the collector applies every frame "
                    "`installState` hands it, including the terminal a StateFlow "
                    "REPLAYS on subscribe (the flow never returns to Idle). That "
                    "wipes the `installProgress` the caller just published and clears "
                    "`isProcessing` — the flag the screen disables Repair/Reset on, "
                    "so a second operation can be started over the first. Capture the "
                    "flow's current value before `.collect` and skip that frame (or "
                    "`dropWhile` it away) before touching the UI state" % ROOTFS_VM_REL
                )

    repair = bodies.get("repairRootfs")
    if repair is None:
        rot.append(
            "%s: no `fun repairRootfs(` left — the settlement criterion lost its "
            "subject" % ROOTFS_VM_REL
        )
    else:
        sites += 1
        if "repairRootfs" not in reads_verdict:
            violations.append(
                "%s:repairRootfs — settles on `autoRepair()`'s boolean alone. "
                "Stage 3 of that call is a full reset(), and installIfNeeded() "
                "reports its disk-space gate by PUBLISHING "
                "`Failed(rootfs_insufficient_space)` and returning normally — so "
                "a refusal for lack of storage is announced as rootfs corruption "
                "(the same verdict `install`/`resetRootfs` already read through "
                "the manager's terminal)" % ROOTFS_VM_REL
            )

        # [deep-audit-1043 B-01] …and the terminal it reads must be compared to
        # the one it inherited. Two halves: the capture (taken before the
        # operation can publish anything) and the hand-off to the settlement.
        repair_at = repair.find("autoRepair(")
        if repair_at < 0:
            rot.append(
                "%s:repairRootfs — no `autoRepair()` call left, so the settlement has "
                "no operation to adjudicate (criterion 11 lost this subject)"
                % ROOTFS_VM_REL
            )
        else:
            captured = [
                m.group(1) for m in PRIOR_CAPTURE.finditer(repair)
                if m.start() < repair_at
            ]
            args = call_args(repair, "rootfsRepairState(")
            if args is None:
                violations.append(
                    "%s:repairRootfs — the settlement no longer routes through "
                    "`rootfsRepairState(`, so the inherited-terminal decision has "
                    "nowhere to live" % ROOTFS_VM_REL
                )
            elif not captured:
                violations.append(
                    "%s:repairRootfs — never snapshots the terminal `installState` "
                    "was already holding when the repair started (before "
                    "`autoRepair()`). The flow never returns to `Idle` and Stages "
                    "1/2/2.5/2.6 publish nothing, so a `Failed` left by a PREVIOUS "
                    "operation is read as this repair's verdict: an in-place repair "
                    "that restored every critical file is reported as failed, with "
                    "the old error text" % ROOTFS_VM_REL
                )
            elif not any(
                re.search(r"\b%s\b" % re.escape(name), args) for name in captured
            ):
                violations.append(
                    "%s:repairRootfs — snapshots the pre-operation terminal (%s) but "
                    "never hands it to the settlement, so the helper cannot compare "
                    "what it read against what it inherited" % (
                        ROOTFS_VM_REL, ", ".join(captured),
                    )
                )

    helper = bodies.get("rootfsRepairState")
    if helper is None:
        rot.append(
            "%s: no `fun rootfsRepairState(` left — the repair settlement criterion "
            "lost its subject" % ROOTFS_VM_REL
        )
    else:
        sites += 1
        arm = re.search(r"is\s+RootfsInstallState\s*\.\s*Failed", helper)
        if arm is None:
            rot.append(
                "%s:rootfsRepairState — no `is RootfsInstallState.Failed` arm left, so "
                "the inherited-terminal decision lost its subject" % ROOTFS_VM_REL
            )
        else:
            arrow = helper.find("->", arm.start())
            condition = helper[
                arm.start(): arrow if arrow > 0 else arm.start() + 400
            ]
            if not REPAIR_STALE_GUARD.search(condition):
                violations.append(
                    "%s:rootfsRepairState — the `Failed` arm fires unconditionally. An "
                    "inherited terminal (the PREVIOUS operation's `Failed`, still on "
                    "the flow because it never returns to `Idle` and `autoRepair()` "
                    "publishes nothing before Stage 3) then outranks `repaired`: a "
                    "successful in-place repair is settled as a failure carrying the "
                    "old error. Gate the arm on instance identity with the "
                    "pre-operation terminal (`isStaleInstallReplay` / `!==`), never on "
                    "the error text" % ROOTFS_VM_REL
                )

    return violations, rot, sites, notes


def check_rootfs_failure_settlement(root):
    """(violations, rot, sites, notes) — criterion 10 (B-02)."""
    violations, rot, notes = [], [], []
    path = os.path.join(root, APP_REL, *ROOTFS_VM_REL.split("/"))
    pkg = os.path.dirname(path)
    if not os.path.isfile(path):
        if os.path.isdir(pkg) and os.listdir(pkg):
            rot.append(
                "%s lost its anchor (the ui/sandbox package is there, the file is not)"
                % ROOTFS_VM_REL
            )
        else:
            notes.append(
                "[PARTIAL] the rootfs failure-terminal anchor — this tree never shipped %s"
                % ROOTFS_VM_REL
            )
        return violations, rot, 0, notes

    with open(path, encoding="utf-8", errors="replace") as fh:
        text = strip_comments(fh.read())

    if not REFRESH_DEF.search(text):
        rot.append(
            "%s: no `fun refresh(` left — the failure terminal has nothing to converge on"
            % ROOTFS_VM_REL
        )
        return violations, rot, 0, notes
    if not OBSERVE_PROGRESS_DEF.search(text):
        rot.append(
            "%s: no `fun observeInstallProgress(` left — the progress-subscription "
            "contract lost its subject" % ROOTFS_VM_REL
        )
        return violations, rot, 0, notes

    bodies = {}
    for name, _params, body in functions(text):
        bodies.setdefault(name, body)

    # Names whose body reaches refresh(), closed transitively: an arm may
    # delegate, but the delegation has to land somewhere.
    reaches = {name for name, body in bodies.items() if "refresh(" in body}
    growing = True
    while growing:
        growing = False
        for name, body in bodies.items():
            if name in reaches:
                continue
            if any(re.search(r"\b%s\s*\(" % re.escape(n), body) for n in reaches):
                reaches.add(name)
                growing = True

    sites = 0
    for fn in ROOTFS_LIFECYCLE:
        body = bodies.get(fn)
        if body is None:
            rot.append(
                "%s: no `fun %s(` — the lifecycle entry points moved" % (ROOTFS_VM_REL, fn)
            )
            continue
        arms = []
        broken = False
        for m in CATCH_HEAD.finditer(body):
            open_idx = body.find("{", m.start(), m.end())
            end = balanced_block(body, open_idx) if open_idx >= 0 else -1
            if end < 0:
                rot.append("%s:%s — unbalanced failure arm" % (ROOTFS_VM_REL, fn))
                broken = True
                break
            arms.append(body[open_idx:end])
        if broken:
            continue
        if not arms:
            rot.append(
                "%s:%s — no failure arm to check (the criterion would pass by looking "
                "at nothing)" % (ROOTFS_VM_REL, fn)
            )
            continue
        for arm in arms:
            sites += 1
            if "refresh(" in arm:
                continue
            if any(re.search(r"\b%s\s*\(" % re.escape(n), arm) for n in reaches):
                continue
            violations.append(
                "%s:%s — the failure arm updates the UI state but never re-reads the "
                "manager (no reachable refresh()): after a reset/repair that already "
                "deleted the rootfs the screen keeps rendering 'Installed' with the "
                "Install button hidden" % (ROOTFS_VM_REL, fn)
            )

    refresh_body = bodies.get("refresh", "")
    sites += 1
    zeroings = list(SIZE_ZEROING.finditer(refresh_body))
    if not zeroings:
        violations.append(
            "%s:refresh — the size of a rootfs that is gone is never zeroed (the read "
            "below it is guarded by isInstalled), so the previous figure outlives a "
            "wipe and renders beside 'Not installed'" % ROOTFS_VM_REL
        )
    else:
        guarded = []
        for m in INSTALLED_BRANCH.finditer(refresh_body):
            open_idx = refresh_body.find("{", m.start(), m.end())
            end = balanced_block(refresh_body, open_idx) if open_idx >= 0 else -1
            if end > 0:
                guarded.append((open_idx, end))
        for m in zeroings:
            if any(s <= m.start() < e for s, e in guarded):
                violations.append(
                    "%s:refresh — the size is zeroed INSIDE the installed-only branch; an "
                    "installed rootfs then flashes 0 before the async read refills it"
                    % ROOTFS_VM_REL
                )
                break

    repair = bodies.get("repairRootfs")
    if repair is not None and "observeInstallProgress(" not in repair:
        sites += 1
        violations.append(
            "%s:repairRootfs — sets installProgress = 0f without subscribing to "
            "manager.installState, so the determinate bar freezes at 0%% for the whole "
            "Stage-3 re-extract" % ROOTFS_VM_REL
        )
    return violations, rot, sites, notes



def write_io_fixture(root, store_shape="io", log_shape="io", store_fn="probeWritable",
                     drop_store=False, drop_log=False, data_pkg=True, ui_pkg=True,
                     keep_pkgs_when_dropped=True, share_shape="io", drop_share=False,
                     toast_hop="hop", rootfs_shape="io", drop_rootfs=False,
                     browser_shape="guard", rpc_shape="guard", nav_shape="guard",
                     drop_guard_anchors=False,
                     onboarding_shape="io", add_model_shape="io", backup_shape="io",
                     provider_shape="clean", volume_shape="single",
                     volume_mirror=False, verdict_shape="derived",
                     settlement_shape="settled"):
    """A `com/rikkaminis/app` tree carrying both criterion-2 targets.

    [keep_pkgs_when_dropped] leaves an unrelated file in the package whose anchor
    was dropped, which is what a *moved* file looks like (ROT). With it off, the
    package is gone too, which is what a synthetic fixture tree looks like
    ([PARTIAL] — the frozen `test_scan.py` shape). [drop_guard_anchors] removes
    criterion 5's three anchors outright, which is the same [PARTIAL] shape for
    that criterion.
    """
    app = os.path.join(root, APP_REL)
    files = {}
    for i in range(22):
        files[f"filler/F{i}.kt"] = _filler(i)
    files["logging/AppLogger.kt"] = _app_logger()
    if not drop_store:
        files["data/MountedFoldersStore.kt"] = _mount_store(store_shape, store_fn, volume_shape)
        if volume_mirror:
            files["sandbox/VolumeRootMirror.kt"] = VOLUME_ROOT_TWIN
    elif keep_pkgs_when_dropped:
        files["data/MountRegistry.kt"] = "package com.rikkaminis.app.data\n\nclass MountRegistry\n"
    if not drop_log:
        files["ui/settings/LogManagementScreen.kt"] = _log_screen(log_shape)
    elif keep_pkgs_when_dropped:
        files["ui/settings/OtherScreen.kt"] = (
            "package com.rikkaminis.app.ui.settings\n\nclass OtherScreen\n"
        )
    if not drop_share:
        files["share/ShareReceiverActivity.kt"] = _share_receiver(share_shape, toast_hop)
    elif keep_pkgs_when_dropped:
        files["share/ShareHost.kt"] = "package com.rikkaminis.app.share\n\nclass ShareHost\n"
    if not drop_rootfs:
        files["sandbox/RootfsManager.kt"] = _rootfs_manager()
        files["ui/sandbox/RootfsManagementViewModel.kt"] = _rootfs_vm(rootfs_shape, verdict_shape, settlement_shape)
    elif keep_pkgs_when_dropped:
        files["ui/sandbox/OtherScreen.kt"] = (
            "package com.rikkaminis.app.ui.sandbox\n\nclass OtherScreen\n"
        )
    if not data_pkg:
        files.pop("data/MountRegistry.kt", None)
    if not ui_pkg:
        files.pop("ui/settings/OtherScreen.kt", None)
    # Criterion 5's anchors belong to the same "did this tree ever ship the
    # package?" question: in the synthetic shape (keep_pkgs_when_dropped=False)
    # writing FileBrowserViewModel.kt would make `ui/sandbox/` exist again and
    # trip criterion 4's moved-file rot guard.
    if not drop_guard_anchors and keep_pkgs_when_dropped:
        files["ui/sandbox/FileBrowserViewModel.kt"] = _file_browser_vm(browser_shape)
        files["debug/DebugRPCHandler.kt"] = _debug_rpc(rpc_shape)
        files["ui/navigation/AppNavigation.kt"] = _app_nav(nav_shape)
    # [deep-audit-1033 I05 / I08] criterion-2's new subjects belong to the same
    # "did this tree ever ship the package?" question. The onboarding pair is
    # unconditional (no arm drops `ui/onboarding/`), and its declaration stubs
    # travel with it so the anchor is never reported as vacuous. The two
    # `ui/settings/` anchors follow that package's existence.
    files["ui/onboarding/OnboardingScreen.kt"] = _onboarding_screen(onboarding_shape)
    files["ui/onboarding/SaveTargets.kt"] = _save_targets()
    if not drop_log or keep_pkgs_when_dropped:
        files["ui/settings/AddCustomModelScreen.kt"] = _add_model_screen(add_model_shape)
        files["ui/settings/BackupSettingsScreen.kt"] = _backup_screen(backup_shape)
    # [deep-audit-1034 A2 / ISSUE-02] criterion 6's subject. `absent` keeps the
    # package populated (a *moved* file — ROT); `never_shipped` drops the package
    # too, which is the synthetic shape ([PARTIAL], same as the frozen fixture
    # `test_scan.py` builds).
    if provider_shape != "never_shipped":
        if provider_shape != "absent":
            files["providers/MinisDocumentsProvider.kt"] = _documents_provider(provider_shape)
        elif keep_pkgs_when_dropped:
            files["providers/OtherProvider.kt"] = (
                "package com.rikkaminis.app.providers\n\nclass OtherProvider\n"
            )
    for rel, text in files.items():
        path = os.path.join(app, *rel.split("/"))
        if not os.path.isdir(os.path.dirname(path)):
            os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)


def _run_gate(root):
    old = sys.argv
    sys.argv = ["readonly_mount_write_guard.py", str(root)]
    try:
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = main()
        return code, buf.getvalue()
    finally:
        sys.argv = old


def selftest():
    """Fixture trees — one dirty arm per criterion, every one must turn it red."""
    results = []

    def run(name, expect_red, note_in=None, extra=None, **kwargs):
        root = tempfile.mkdtemp(prefix="romount_")
        try:
            write_io_fixture(root, **kwargs)
            for rel, text in (extra or {}).items():
                path = os.path.join(root, APP_REL, *rel.split("/"))
                if not os.path.isdir(os.path.dirname(path)):
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(text)
            code, out = _run_gate(root)
            ok = (code == 1) if expect_red else (code == 0)
            if ok and note_in is not None and note_in not in out:
                ok, note_in = False, f"missing {note_in!r} in output"
            results.append((name, ok, note_in or f"exit={code}"))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    run("both call sites wrapped in Dispatchers.IO pass", False,
        "blocking-IO call site(s) inside Dispatchers.IO")
    run("the probe called straight from the suspend reader is caught", True,
        "data/MountedFoldersStore.kt", store_shape="direct")
    run("the log purge on the click's thread is caught", True,
        "ui/settings/LogManagementScreen.kt", log_shape="direct")
    run("a wrap around a different call does not cover the purge", True,
        "ui/settings/LogManagementScreen.kt", log_shape="wrong_call")
    run("the non-blocking `withContext(IO) expr` form is not accepted (fail-closed)", True,
        "data/MountedFoldersStore.kt", store_shape="inline_io")
    run("a renamed probe is a rot failure, not a silent skip", True,
        "no definition matching", store_fn="probe")
    run("a package that lost its anchor file is a rot failure", True,
        "lost its anchor", drop_store=True, drop_log=True)
    run("a tree that never shipped the package is skipped, not failed", False,
        "[PARTIAL]", drop_store=True, drop_log=True, keep_pkgs_when_dropped=False)
    # [dv20-A4 A-06 / A-07 / B-03] the three decisions this batch touched.
    run("a single volume-root definition in the store passes", False,
        "volume-root definition(s)")
    run("a second volume-root definition elsewhere is caught", True,
        "volume-root resolver has 2 definitions", volume_mirror=True)
    run("a store that lost the resolver is a rot failure, not a silent skip", True,
        "no definition matching", volume_shape="none")
    run("the id->File funnel judging the exposed top level passes", False,
        "id->File funnel(s)")
    run("a funnel that resolves any id straight to a File is caught", True,
        "without judging the top-level whitelist", provider_shape="funnel_blind")
    run("a success claim backed by the manager's verdict passes", False,
        "success claim(s) backed")
    run("a success claim made without consulting the verdict is caught", True,
        "without consulting the manager's terminal state", verdict_shape="assumed")
    run("the provider import wrapped in Dispatchers.IO passes", False)
    run("the provider import outside the IO block is caught", True,
        "share/ShareReceiverActivity.kt", share_shape="direct_import")
    run("the staged-file discard outside the IO block is caught", True,
        "share/ShareReceiverActivity.kt", share_shape="direct_discard")
    run("`lifecycleScope.launch` alone does not wrap the calls in IO", True,
        "share/ShareReceiverActivity.kt", share_shape="launch_only")
    run("a share anchor that lost its file is a rot failure", True,
        "lost its anchor", drop_share=True)
    run("the share toast hop after the import moved to IO passes", False,
        "share UI hand-off verified on a Looper thread")
    run("a Toast shown straight from the dialog thread is caught", True,
        "share/ShareReceiverActivity.kt", toast_hop="direct")
    run("a share tree whose toast() was removed is a rot failure, not a silent skip", True,
        "no `fun toast(", toast_hop="missing")
    run("the toast-hop criterion reports its premise gone when the import is not on IO", True,
        "premise is gone", share_shape="launch_only")
    run("both rootfs integrity reads inside Dispatchers.IO pass", False)
    run("the rootfs integrity read on the click thread is caught", True,
        "rootfs integrity verification (reads .integrity_manifest", rootfs_shape="direct")
    run("`viewModelScope.launch` alone does not wrap the rootfs read in IO", True,
        "ui/sandbox/RootfsManagementViewModel.kt", rootfs_shape="launch_only")
    run("the non-blocking `withContext(IO) expr` rootfs wrap is not accepted", True,
        "ui/sandbox/RootfsManagementViewModel.kt", rootfs_shape="inline_io")
    run("a rootfs ViewModel anchor that lost its file is a rot failure", True,
        "lost its anchor", drop_rootfs=True)
    run("a tree that never shipped the rootfs ViewModel package is skipped, not failed", False,
        "[PARTIAL]", drop_rootfs=True, keep_pkgs_when_dropped=False)

    # ── criterion 5 (da1031-C) ──────────────────────────────────────────────
    run("the mounted-folder browser keeps the read-only guard in front of the delete", False,
        "model-carried write/route site(s) carry the guard")
    run("a delete that skips the read-only guard is caught", True,
        "FileBrowserViewModel.kt:deleteItem(", browser_shape="direct")
    run("a guard placed AFTER the delete is caught (post-mortem is not a guard)", True,
        "runs AFTER the write", browser_shape="late")
    run("a renamed deleteItem is a rot failure, not a silent skip", True,
        "no definition of deleteItem(", browser_shape="renamed")
    run("an RPC write that skips the read-only guard is caught", True,
        "DebugRPCHandler.kt:handleWriteFile(", rpc_shape="direct")
    run("a renamed handleWriteFile is a rot failure, not a silent skip", True,
        "no definition of handleWriteFile(", rpc_shape="renamed")
    run("a mounted-folder route that drops the guest prefix is caught", True,
        "without a guest-side prefix", nav_shape="no_prefix")
    run("a route whose fileBrowser call was removed is a rot failure", True,
        "no Routes.fileBrowser( call", nav_shape="no_route")
    run("a tree that never shipped the criterion-5 anchors is skipped, not failed", False,
        "[PARTIAL] the read-only-mount guard anchors", drop_guard_anchors=True)

    # ── criterion 2 (da1033 I05 / I08): the save paths off the click's thread ─
    run("the three save paths wrapped in Dispatchers.IO pass", False,
        "blocking-IO call site(s) inside Dispatchers.IO")
    run("the onboarding insert on the click's thread is caught", True,
        "ui/onboarding/OnboardingScreen.kt", onboarding_shape="direct")
    run("a model insert left on the click's thread is caught", True,
        "ui/settings/AddCustomModelScreen.kt", add_model_shape="direct")
    run("the WebDAV read + persist on the composable's thread is caught", True,
        "ui/settings/BackupSettingsScreen.kt", backup_shape="direct")
    run("an IO wrap nested inside launch + runCatching still counts", False,
        onboarding_shape="io_nested")
    run("a save path whose call was removed is a rot failure, not a silent skip", True,
        "nothing calls this any more", add_model_shape="none")

    # ── criterion 5 (da1033 I01): the second, independent refusal ───────────
    run("a browser that refuses read-only mounts but not the read-only shared folders is caught",
        True, "refusing the read-only shared folders", browser_shape="mount_only")
    run("a shared-folder refusal placed AFTER the delete is caught", True,
        "runs AFTER the write", browser_shape="shared_late")
    run("the two refusals may be written in either order", False,
        browser_shape="shared_first")

    # ── criterion 6 (da1034 A2 / ISSUE-02): the SAF provider's own root ─────
    run("a provider that judges the raw documentId as the root is caught", True,
        "queryChildDocuments", provider_shape="raw_root")
    run("a provider whose delete/rename never refuses a top-level entry is caught", True,
        "deleteDocument", provider_shape="no_toplevel_guard")
    run("a provider file that moved out of a populated package is a rot failure", True,
        "lost its anchor", provider_shape="absent")
    run("a tree that never shipped the provider is a skip, not a failure", False,
        "[PARTIAL]", provider_shape="never_shipped")
    run("the clean provider tree passes", False)

    # ── criterion 10 (deep-audit-1041 B-02): the rootfs failure terminal ──
    run("the three rootfs failure arms settling through refresh pass", False,
        "rootfs failure arm(s) re-reading the manager on exit")
    run("a failure arm that stops at the state copy is caught", True,
        "never re-reads the manager", settlement_shape="catch_without_refresh")
    run("refresh leaving the size of a wiped rootfs behind is caught", True,
        "never zeroed", settlement_shape="size_not_zeroed")
    run("zeroing the size inside the installed-only branch is caught", True,
        "INSIDE the installed-only branch", settlement_shape="size_zeroed_inside_branch")
    run("repairRootfs without the progress subscription is caught", True,
        "without subscribing to manager.installState", settlement_shape="no_repair_subscription")

    # ── criterion 11 (deep-audit-1042 B-05): the replayed terminal ────────
    run("a collector that skips the replayed terminal and a repair that reads the "
        "published verdict pass", False,
        "rootfs progress/settlement site(s) reading the published verdict")
    run("a collector that applies the replayed terminal is caught", True,
        "REPLAYS on subscribe", settlement_shape="replay_applied")
    run("a collector that no longer subscribes to installState is a rot failure", True,
        "no longer collects `installState`", settlement_shape="collector_gone")
    run("a repair that settles on autoRepair's boolean is caught", True,
        "settles on `autoRepair()`'s boolean alone", settlement_shape="repair_settles_on_boolean")

    # ── criterion 11 (deep-audit-1043 B-01): the inherited terminal ────────
    run("a repair that snapshots the pre-operation terminal and gates the "
        "Failed arm on it passes", False,
        "rootfs progress/settlement site(s) reading the published verdict")
    run("a repair that never snapshots the terminal it inherited is caught", True,
        "never snapshots the terminal", settlement_shape="repair_inherits_stale_terminal")
    run("a `Failed` arm left unconditional despite the snapshot is caught", True,
        "fires unconditionally", settlement_shape="repair_settlement_ignores_prior")

    # ── wide3 witnesses: one arm per falsified axis ─────────────────────────
    # C1 (parser-death): the write function's body starts on the NEXT line, so a
    # parser that seeds the depth from the signature line sees a header fragment
    # and drops the whole function out of the criterion.
    EXPR_BODY_WRITE = (
        "package com.rikkaminis.app.sandbox.offload\n"
        "\n"
        "object ExprBodyWrite {\n"
        "    private fun writeRunOutput(\n"
        "        request: String,\n"
        "        outputPath: String?,\n"
        "        appliedExtras: String,\n"
        "    ): String =\n"
        "        run {\n"
        "            val sessionId = request\n"
        "            if (outputPath != null) {\n"
        "                java.io.File(outputPath).writeBytes("
        "appliedExtras.toByteArray())\n"
        "            }\n"
        "            sessionId\n"
        "        }\n"
        "}\n"
    )
    run("witness C1: an expression body on the next line is still scanned", True,
        "writes a host file from a path parameter",
        extra={"sandbox/offload/ExprBodyWrite.kt": EXPR_BODY_WRITE})
    # C5 (dataflow): the guard call stays in front of the write, its condition is
    # pinned false — every substring said guarded while the write went ahead.
    run("witness C5: a read-only guard behind `false &&` is caught", True,
        "constantly false", rpc_shape="dead_guard")
    run("witness C5: the browser's mount refusal behind `false &&` is caught", True,
        "constantly false", browser_shape="dead_guard")
    # C11 (dataflow): the replay snapshot + `return@collect` stay, the branch is
    # pinned false, so every replayed terminal is applied as a live event again.
    run("witness C11: a replay skip behind `false &&` is caught", True,
        "constantly false", settlement_shape="replay_dead")

    print("=== readonly_mount_write_guard --selftest ===")
    failed = 0
    for name, ok, note in results:
        if not ok:
            failed += 1
        print(f"[{'ok' if ok else 'FAIL'}] {name} -> {note}")
    if failed:
        print(f"SELFTEST: {failed} arm(s) misbehaved")
        return 1
    print(f"MOUNT_PROBE_IO_SELFTEST=PASS ({len(results)} arms)")
    return 0


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    root = os.path.abspath(args[0] if args else os.path.join(os.path.dirname(__file__), "..", ".."))
    violations, checked, files, exempt_used = scan(root)
    io_violations, io_rot, io_sites, io_notes = check_blocking_io(root)
    hop_violations, hop_rot, hop_sites, hop_notes = check_share_toast_hop(root)
    shape_violations, shape_rot, shape_sites, shape_notes = check_guard_call_shape(root)
    doc_violations, doc_rot, doc_sites, doc_notes = check_documents_provider(root)
    funnel_violations, funnel_rot, funnel_sites, funnel_notes = check_documents_provider_funnel(root)
    vol_violations, vol_rot, vol_sites, vol_notes = check_volume_root_single_source(root)
    verdict_violations, verdict_rot, verdict_sites, verdict_notes = check_rootfs_verdict(root)
    set_violations, set_rot, set_sites, set_notes = check_rootfs_failure_settlement(root)
    replay_violations, replay_rot, replay_sites, replay_notes = check_rootfs_progress_replay(root)

    rot = []
    if files < 20:
        rot.append(f"only {files} Kotlin files under com/rikkaminis/app — the source tree moved?")
    if checked < 3:
        rot.append(f"only {checked} path-parameter write sites found — the detector stopped matching?")
    rot += io_rot
    rot += hop_rot
    rot += shape_rot
    rot += doc_rot
    rot += funnel_rot
    rot += vol_rot
    rot += verdict_rot
    rot += set_rot
    rot += replay_rot
    if rot:
        print("ROT — the guard can no longer see what it is supposed to check:")
        for r in rot:
            print(f"  ✗ {r}")
        return 1
    for n in (io_notes + hop_notes + shape_notes + doc_notes
              + funnel_notes + vol_notes + verdict_notes + set_notes + replay_notes):
        print(f"  {n}")
    if violations or io_violations:
        print("Unguarded host-file writes:")
        for v in violations + io_violations:
            print(f"  ✗ {v}")
    if hop_violations:
        print("UI calls left on the wrong thread:")
        for v in hop_violations:
            print(f"  ✗ {v}")
    if shape_violations:
        print("Read-only-mount guard not wired at the model-carried write site:")
        for v in shape_violations:
            print(f"  ✗ {v}")
    if doc_violations:
        print("SAF provider root decisions taken on the raw document id:")
        for v in doc_violations:
            print(f"  ✗ {v}")
    if funnel_violations:
        print("Document ids resolved to a File without judging what the provider exposes:")
        for v in funnel_violations:
            print(f"  ✗ {v}")
    if vol_violations:
        print("Volume-root resolution defined more than once:")
        for v in vol_violations:
            print(f"  ✗ {v}")
    if verdict_violations:
        print("Rootfs outcomes claimed without consulting the manager's verdict:")
        for v in verdict_violations:
            print(f"  ✗ {v}")
    if set_violations:
        print("Rootfs failure arms that stop short of re-reading the manager:")
        for v in set_violations:
            print(f"  ✗ {v}")
    if replay_violations:
        print("Rootfs progress frames applied without recognising the StateFlow replay:")
        for v in replay_violations:
            print(f"  ✗ {v}")
    if (violations or io_violations or hop_violations or shape_violations or doc_violations
            or funnel_violations or vol_violations or verdict_violations or set_violations
            or replay_violations):
        return 1
    print(f"OK: {checked} path-parameter write site(s) guarded ({files} files scanned)")
    print(f"    {io_sites} blocking-IO call site(s) inside Dispatchers.IO")
    print(f"    {vol_sites} volume-root definition(s), single source in {VOLUME_ROOT_STORE_REL}")
    print(f"    {verdict_sites} success claim(s) backed by the manager's verdict")
    print(f"    {set_sites} rootfs failure arm(s) re-reading the manager on exit")
    print(f"    {replay_sites} rootfs progress/settlement site(s) reading the published verdict")
    print(f"    {funnel_sites} id->File funnel(s) judging the exposed top level")
    if hop_sites:
        print(f"    {hop_sites} share UI hand-off verified on a Looper thread")
    if shape_sites:
        print(
            f"    {shape_sites} model-carried write/route site(s) carry the guard "
            f"in front of the write"
        )
    if doc_sites:
        print(
            f"    {doc_sites} SAF provider decision(s) taken on the normalised document id"
        )
    for rel, reason in sorted(exempt_used.items()):
        print(f"  · exempt {rel}: {reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
