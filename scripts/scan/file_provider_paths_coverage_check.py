#!/usr/bin/env python3
"""FileProvider path coverage gate (deep-audit-1023 ISSUE-01, FIX-A).

事故 (真机可复现，不是 latent):
  `res/xml/file_provider_paths.xml` declares the directories
  `androidx.core.content.FileProvider` is allowed to serve. Three dirs were
  written and handed to `FileProvider.getUriForFile` but never declared —
  `cacheDir/large-messages/` (LargeContentGuard oversized-message export),
  `filesDir/media/` (MediaStore-staged chat attachments) and
  `filesDir/photos-export/` (android-photos offload output). Undeclared root
  ⇒ `getUriForFile` throws `IllegalArgumentException` ⇒ tapping the export
  button always toasted "export failed" while the file stayed behind as a
  permanent orphan (nothing deleted `large-messages/`), and external-open /
  share of a chat attachment always fell through to a `file://` URI that the
  receiving app cannot read.

判据 (all code/XML anchors — never comment text). Every structural question is
answered on the shared lexer projection (`_kotlin_lexer.mask_code`, strictly
length-preserving), and every "which value / which position" question uses the
shared primitives (`_kotlin_ast.match_bracket|function_body`,
`_kotlin_valueflow.path_feasible`): a `}` inside a string literal can no longer
move a block boundary, a `File(` inside prose can no longer stand in for a
root, and a dead `if (false)` call site no longer counts as "the write".

  C1  every writer dir is declared under the matching FileProvider tag
  C2  the writer sites named by the registry still exist (a renamed or
      removed dir means the registry silently stopped covering anything)
  C3  oversized-message export runs off the main thread: `exportLargeContent-
      ToFile` is `suspend`, and the disk calls that matter (`mkdirs`,
      `pruneExpiredLargeMessageFiles`, `writeText`) sit INSIDE a
      `withContext(Dispatchers.IO) { … }` REGION — a call merely spelled next
      to an empty IO block does not count (witness 02-2).
  C4  the export prunes expired orphans before writing, wrapped so a prune
      failure cannot block the export (`runCatching { prune... }`), fed a real
      clock (a constant `0L` makes the cutoff permanently negative and the
      prune a no-op — witness 02-3), and the prune is defined with a literal
      TTL constant
  C5  `getUriForFile` runs AFTER the LIVE `writeText` inside the export (no
      "URI handed out for a file that is not on disk yet" race) — a dead
      `if (false) f.writeText("")` planted before it does not count
      (witness 02-4)
  C7  no full-coverage provider root (`<external-path>`, `<root-path>`,
      `path="."`/`path=""`) — widening the provider to a whole volume is a
      separate, explicitly approved decision, not something a coverage fix
      may slip in.
  C8  [fix/da1024-A] the `cacheDir/share_inbound/` writer prunes its own
      orphans under a literal TTL constant AND the stream copy itself
      (`inputStream` / `outputStream` / `copyTo`) runs inside the IO region —
      the up-to-100 MB copy moving outside it is the defect (witness 02-5);
      nothing used to delete those staged copies (deep-audit-1024 ISSUE-01).
  C9  [fix/da1024-A] the ChatScreen share drain keeps its disk work on IO: the
      `LaunchedEffect(shareBufferVersion)` body switches with
      `withContext(Dispatchers.IO)` and the `cleanSharedFiles(` directory sweep
      sits inside one of those regions.
  C10 every directory root created under filesDir/cacheDir in a guarded writer
      is covered by a declared <files-path>/<cache-path>. Both spellings of a
      root count: `File(context.filesDir, "x")` and
      `context.filesDir.resolve("x")` (witness 02-1 — the extension spelling
      never contains `File(`).
  C11 the dir a registry entry claims must be bound to a LIVE property: a dead
      alias parked on the legal literal while the live one points elsewhere is
      the witness shape.

用法:
  python3 file_provider_paths_coverage_check.py [repo_root]
  python3 file_provider_paths_coverage_check.py --root <dir>
  python3 file_provider_paths_coverage_check.py --selftest

夹具:
  --selftest builds temp dirs — one CLEAN fixture plus a dirty variant per
  criterion — and requires every dirty fixture to turn red.
"""
import re
import sys
import tempfile
from pathlib import Path

from _kotlin_lexer import KotlinLexError
from _kotlin_ast import const_value, function_body, match_bracket
from _kotlin_valueflow import code_view, literal_view, path_feasible

XML_REL = "src/android/app/src/main/res/xml/file_provider_paths.xml"
KT_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/LargeContentGuard.kt"
MEDIASTORE_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/storage/MediaStore.kt"
PHOTOS_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt"
# [fix/da1024-A] deep-audit-1024 ISSUE-01: the share_inbound staging dir lives in
# ChatViewModel.kt and is drained from ChatScreen.kt.
CHATVM_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"
SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"

# (registry id, writer file, regex proving the dir is written, provider tag,
#  declared path). This list IS the coverage contract: a new dir handed to
#  getUriForFile must be added here AND declared in the XML.
WRITER_DIRS = [
    ("large-messages", KT_REL,
     r'File\(\s*context\.cacheDir,\s*"large-messages"\s*\)',
     "cache-path", "large-messages/"),
    ("media", MEDIASTORE_REL,
     r'File\(\s*context\.filesDir,\s*"media"\s*\)',
     "files-path", "media/"),
    ("photos-export", PHOTOS_REL,
     r'File\(\s*context\.filesDir,\s*"photos-export"\s*\)',
     "files-path", "photos-export/"),
    ("share-inbound", CHATVM_REL,
     r'File\(\s*context\.cacheDir,\s*"share_inbound"\s*\)',
     "cache-path", "share_inbound/"),
]

TTL_EXPECTED = "internal const val LARGE_MESSAGE_EXPORT_TTL_MS = 24L * 60L * 60L * 1000L"
SHARE_TTL_EXPECTED = "internal const val SHARE_INBOUND_TTL_MS = 24L * 60L * 60L * 1000L"

IO_OPEN_RE = re.compile(r'withContext\s*\(\s*Dispatchers\.IO\s*\)')

# [fix/meta-audit-m2-binding] C10/C11 — the falsifying witness added a SECOND
# filesDir dir literal (`media-unregistered`) to MediaStore.kt and pointed the
# live property at it, leaving a dead one on the declared `media`. The old C1/C2
# only asked whether SOME site in the file still matched the registry regex, so
# "a dead property wears the legal dir while the live one points elsewhere" read
# as covered. These two criteria derive the dir set from the tree instead of
# trusting the registry, and then bind it to a property that is actually used.
#
# Base dir → the FileProvider tag that must declare it.
FILE_CTOR_RE = re.compile(
    r'(?:java\.io\.)?File\s*\(\s*context\.(?:applicationContext\.)?(filesDir|cacheDir)\s*,')
# [batch-5 C10] the SAME directory root spelled as an extension call:
# `context.filesDir.resolve("media-unregistered")` contains no `File(` at all,
# so the constructor regex alone was blind to a re-pointed live dir (02-1).
DIR_RESOLVE_RE = re.compile(
    r'context\.(?:applicationContext\.)?(filesDir|cacheDir)\s*\.\s*resolve\s*\(')
BASE_TAG = {"filesDir": "files-path", "cacheDir": "cache-path"}
ASSIGN_RE_TAIL = re.compile(r'(?:val|var)\s+([A-Za-z_]\w*)\s*(?::\s*[\w.]+(?:\s*<[^>]*>)?)?\s*=\s*$')
RETURN_RE_TAIL = re.compile(
    r'(?:fun\s+[A-Za-z_]\w*\s*\([^)]*\)\s*(?::\s*[\w.]+(?:\s*<[^>]*>)?)?\s*=|return)\s*$')
MKDIRS_RE_HEAD = re.compile(r'\s*(?:\.(?:apply|also|let|run)\s*\{[^}]*mkdirs\s*\(\s*\)|\.mkdirs\s*\(\s*\))')


def fold_dir_literal(lit: str, pos: int):
    """Fold the directory argument starting at `pos` of the LITERAL view.

    Handles `"a" + "b"` (constant folding — the witness-shaped evasion).
    Returns (literal, resolved) — resolved=False when the argument is not a
    constant we can read (template, identifier, call …).
    """
    i = pos
    n = len(lit)
    parts = []
    while i < n:
        if lit[i] in " \t\r\n":
            i += 1
            continue
        if lit[i] == '"':
            j = i + 1
            buf = []
            while j < n and lit[j] != '"':
                if lit[j] == "\\":
                    buf.append(lit[j:j + 2])
                    j += 2
                    continue
                buf.append(lit[j])
                j += 1
            seg = "".join(buf)
            if "$" in seg:          # template — value unknown at scan time
                return ("", False)
            parts.append(seg)
            i = j + 1
            while i < n and lit[i] in " \t":
                i += 1
            if i < n and lit[i] == "+":
                i += 1
                continue
            break
        return ("", False)
    if not parts:
        return ("", False)
    return ("".join(parts), True)


def _root_matches(code):
    """[(match_start, base, paren_pos, arg_pos)] for both root spellings.

    Matched on the MASKED view (a `File(` inside a string / comment is not a
    site); the literal itself is read from the literal view at the same offset
    (both projections are strictly length-preserving).
    """
    out = []
    for m in FILE_CTOR_RE.finditer(code):
        open_pos = code.rfind("(", m.start(), m.end())
        out.append((m.start(), m.group(1), open_pos, m.end()))
    for m in DIR_RESOLVE_RE.finditer(code):
        out.append((m.start(), m.group(1), m.end() - 1, m.end()))
    out.sort(key=lambda t: t[0])
    return out


def dir_root_sites(code: str, lit: str):
    """Every filesDir/cacheDir DIRECTORY root, in both spellings.

    Returns [(base, tag, literal, resolved, name_or_None)] — a site qualifies as
    a directory root when it is bound to a val/var/property or is immediately
    mkdirs()'d. A bare `File(context.filesDir, "browser_tabs/$sid.json").delete()`
    is a FILE path and is deliberately not a provider root.
    """
    out = []
    for start, base, open_paren, arg_at in _root_matches(code):
        value, resolved = fold_dir_literal(lit, arg_at)
        close = match_bracket(code, open_paren) if open_paren >= 0 else -1
        end = close + 1 if close > 0 else -1
        before = code[max(0, start - 200):start]
        am = ASSIGN_RE_TAIL.search(before)
        name = am.group(1) if am else None
        after = code[end:end + 80] if end > 0 else ""
        rm = None if am else RETURN_RE_TAIL.search(before)
        if not (am or MKDIRS_RE_HEAD.match(after) or rm):
            continue
        if rm and not am:
            # A returned File is only a directory ROOT when the literal looks
            # like a bare dir name (`"photos-scratch"`); `"config.json"` is a
            # file under filesDir and must not be dragged into the declaration
            # contract. Unresolvable literals stay in and fail closed.
            if resolved and ("." in value.split("/")[-1] or value.endswith("/")):
                continue
            if not resolved:
                continue
        out.append((base, BASE_TAG[base], value, resolved, name))
    return out


def declared_covers(decls, tag: str, seg: str) -> bool:
    for t, _n, p in decls:
        if t != tag:
            continue
        q = p.rstrip("/")
        if q == seg or q.startswith(seg + "/"):
            return True
    return False


DECL_RE = re.compile(
    r'<\s*(files-path|cache-path|external-path|external-files-path|external-cache-path|root-path)'
    r'\s+name\s*=\s*"([^"]*)"\s+path\s*=\s*"([^"]*)"\s*/?>',
    re.M,
)


def declared_roots(xml_text: str):
    """Return (list of (tag, name, path), violations)."""
    decls = DECL_RE.findall(xml_text)
    violations = []
    names = [d[1] for d in decls]
    dupes = sorted({x for x in names if names.count(x) > 1})
    if dupes:
        violations.append(f"C6 DUPLICATE provider root name(s): {dupes} — FileProvider throws "
                          "IllegalStateException at first use when two roots share a name")
    for tag, name, path in decls:
        if tag in ("external-path", "root-path") or path in (".", "", "./"):
            violations.append(f"C7 FULL-COVERAGE provider root <{tag} name=\"{name}\" path=\"{path}\"> — "
                              "a whole volume / the whole app dir must not be registered by a "
                              "coverage fix; that widening is an explicitly approved decision "
                              "(deep-audit-1023 FIX-A 报告 §6 超界申请)")
    return decls, violations


# ── span helpers (shared primitives only — no per-character scanners) ───────

def _views(path: Path):
    """(masked_code, literal_view) for one Kotlin file. Raises KotlinLexError."""
    src = path.read_text()
    return code_view(src), literal_view(src)


def _fn_spans(code: str, name: str):
    """(start, end) of the function body, or None. `_kotlin_ast.function_body`
    derives the body from the parameter list, so a default-argument lambda
    cannot steal the body start."""
    fb = function_body(code, name)
    return None if fb is None else (fb[0], fb[1])


def _block_after(code: str, anchor_re: str):
    """(start, end) of the `{ … }` block that follows the anchor match."""
    m = re.search(anchor_re, code)
    if not m:
        return None
    k = m.end()
    while k < len(code) and code[k] in " \t\r\n":
        k += 1
    if k >= len(code) or code[k] != "{":
        return None
    close = match_bracket(code, k)
    return None if close < 0 else (k + 1, close)


def io_regions(code: str):
    """Inner spans of every `withContext(Dispatchers.IO) { … }`.

    The only anchor that proves a disk call is actually inside the IO switch
    rather than merely sitting next to it. Brace matching runs on the masked
    view, so a `"{"` inside a string literal cannot extend the region over the
    rest of the file (witness 02-6).
    """
    out = []
    for m in IO_OPEN_RE.finditer(code):
        k = m.end()
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        if k >= len(code) or code[k] != "{":
            continue
        close = match_bracket(code, k)
        if close < 0:
            continue
        out.append((k + 1, close))
    return out


def _inside(regions, pos: int) -> bool:
    return any(a <= pos < b for a, b in regions)


def _regions_in(regions, span):
    """The IO regions nested inside `span` (the switch must wrap the work, not
    merely be named somewhere in the file)."""
    return [(a, b) for a, b in regions if span[0] <= a and b <= span[1]]


def _live_in(code: str, span, pattern: str):
    """Reachable match positions of `pattern` inside `span` (dead branches skipped).

    `path_feasible` folds the enclosing guards with `guard_feasible`, so
    `if (false) f.writeText("")` and `if (1 == 2) …` are both dead — the whole
    constant-false family, not a spelling whitelist.
    """
    out = []
    seg = code[span[0]:span[1]]
    for m in re.finditer(pattern, seg):
        pos = span[0] + m.start()
        if path_feasible(code, pos):
            out.append(pos)
    return out


def _arg_value(code: str, span):
    """(name_or_None, value_text) — named arguments unwrapped."""
    text = code[span[0]:span[1]]
    m = re.match(r"^\s*([A-Za-z_]\w*)\s*=(?!=)\s*", text)
    if m:
        return m.group(1), text[m.end():].strip()
    return None, text.strip()


def _call_arg_spans(code: str, open_paren: int):
    """Top-level argument spans of the call whose `(` is at `open_paren`."""
    close = match_bracket(code, open_paren)
    if close < 0:
        return []
    inner = code[open_paren + 1:close]
    if not inner.strip():
        return []
    spans, depth, start = [], 0, 0
    for k, ch in enumerate(inner):
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            spans.append((open_paren + 1 + start, open_paren + 1 + k))
            start = k + 1
    spans.append((open_paren + 1 + start, close))
    return spans


def check(root: Path) -> list:
    violations = []

    xml_path = root / XML_REL
    kt_path = root / KT_REL
    if not xml_path.exists():
        return [f"{XML_REL} MISSING"]
    if not kt_path.exists():
        return [f"{KT_REL} MISSING"]
    for rel in (MEDIASTORE_REL, PHOTOS_REL, CHATVM_REL, SCREEN_REL):
        if not (root / rel).exists():
            return [f"{rel} MISSING"]

    xml_text = xml_path.read_text()
    try:
        code, lit = _views(kt_path)
        vm_code, vm_lit = _views(root / CHATVM_REL)
        scr_code, scr_lit = _views(root / SCREEN_REL)
    except KotlinLexError as exc:
        # fail-closed: an unparsable file is a violation, never a silent skip
        return [f"LEXER could not parse the guarded writers ({exc}) — fail closed"]

    decls, xml_violations = declared_roots(xml_text)
    violations += xml_violations
    declared_set = {(tag, path) for tag, _n, path in decls}

    # ── C1 / C2: every writer dir is declared, and the writer still exists ──
    for rid, rel, writer_re, tag, path in WRITER_DIRS:
        wpath = root / rel
        if not wpath.exists():
            violations.append(f"C2 [{rid}] writer file MISSING: {rel}")
            continue
        try:
            wlit = literal_view(wpath.read_text())
        except KotlinLexError as exc:
            violations.append(f"C2 [{rid}] LEXER could not parse {rel} ({exc}) — fail closed")
            continue
        if not re.search(writer_re, wlit):
            violations.append(
                f"C2 [{rid}] writer site no longer matches `{writer_re}` in {rel} — the "
                "coverage registry points at a dir that is not written any more; update "
                "WRITER_DIRS together with the XML")
        if (tag, path) not in declared_set:
            violations.append(
                f"C1 [{rid}] dir `{path}` is written ({rel}) but not declared as "
                f"<{tag} path=\"{path}\"> in {XML_REL} — getUriForFile throws "
                "IllegalArgumentException for every file under it")

    # ── C10 / C11 [fix/meta-audit-m2-binding + batch-5 scope]: derive the dir
    #     set from the tree instead of trusting the registry ────────────────
    guarded = {KT_REL: (code, lit), CHATVM_REL: (vm_code, vm_lit),
               SCREEN_REL: (scr_code, scr_lit)}
    for rel in (MEDIASTORE_REL, PHOTOS_REL):
        try:
            guarded[rel] = _views(root / rel)
        except KotlinLexError as exc:
            violations.append(f"C10 LEXER could not parse {rel} ({exc}) — fail closed")
            guarded[rel] = (None, None)
    dir_sites = []       # (rel, base, tag, literal, name, live)
    for rel in (KT_REL, MEDIASTORE_REL, PHOTOS_REL, CHATVM_REL, SCREEN_REL):
        gcode, glit = guarded[rel]
        if gcode is None:
            continue
        for base, tag, value, resolved, name in dir_root_sites(gcode, glit):
            if not resolved:
                violations.append(
                    f"C10 [{rel}] `context.{base}` directory root built from a "
                    "non-literal expression — the gate cannot tell which provider root it "
                    "lands under, so it must not pass silently")
                continue
            seg = value.split("/")[0]
            if not seg:
                violations.append(f"C10 [{rel}] empty directory literal under context.{base}")
                continue
            if not declared_covers(decls, tag, seg):
                violations.append(
                    f"C10 [{rel}] directory `{base}/{seg}` is created ({name or 'inline'} = "
                    f"context.{base}[, .resolve](…\"{value}\")) but no <{tag}> root declares it in "
                    f"{XML_REL} — getUriForFile throws IllegalArgumentException for everything "
                    "under it, and the registry never noticed because it only checked whether "
                    "its own literal still appears somewhere in the file")
            live = True if name is None else (
                len(re.findall(r'\b%s\b' % re.escape(name), gcode)) >= 2)
            dir_sites.append((rel, base, tag, value, name, live))

    for rid, rel, _writer_re, tag, path in WRITER_DIRS:
        want = path.rstrip("/").split("/")[0]
        bound = [s for s in dir_sites
                 if s[0] == rel and s[2] == tag and s[3].split("/")[0] == want]
        if not bound:
            violations.append(
                f"C11 [{rid}] no directory root bound to `{tag}/{want}` in {rel} — the registry "
                "entry stopped corresponding to a real binding")
            continue
        if not any(s[5] for s in bound):
            names = sorted({s[4] or "<inline>" for s in bound})
            violations.append(
                f"C11 [{rid}] the only bindings of `{tag}/{want}` in {rel} are dead "
                f"({names}: declared but never read) — the live code writes elsewhere, which is "
                "exactly how a re-pointed dir keeps the registry green while the provider has no "
                "root for the real location")

    # ── C3: export must leave the main thread ───────────────────────────────
    if not re.search(r'private\s+suspend\s+fun\s+exportLargeContentToFile\s*\(', code):
        violations.append("C3 exportLargeContentToFile is not `private suspend fun` — the export "
                          "was a blocking disk write on the UI thread (deep-audit-1023 ISSUE-01)")
    body = _fn_spans(code, "exportLargeContentToFile")
    if body is None:
        violations.append("C3 exportLargeContentToFile body not found — cannot verify IO dispatch")
    else:
        regions = _regions_in(io_regions(code), body)
        if not regions:
            violations.append("C3 exportLargeContentToFile body has no "
                              "`withContext(Dispatchers.IO)` — mkdirs / listFiles / writeText "
                              "must run off the main thread (deep-audit-1023 ISSUE-01)")
        # [batch-5 C3] …and the disk calls must be INSIDE a region. An empty
        # `withContext(Dispatchers.IO) {}` followed by the real work in `run {}`
        # keeps the substring and puts a multi-MB writeText back on the UI
        # thread (witness 02-2).
        for token, label in ((r"mkdirs\s*\(", "mkdirs()"),
                             (r"pruneExpiredLargeMessageFiles\s*\(", "the TTL prune"),
                             (r"writeText\s*\(", "writeText()")):
            hits = _live_in(code, body, token)
            if not hits:
                violations.append(f"C3 exportLargeContentToFile no longer calls {label} — the "
                                  "export would leave nothing to share (fail-closed)")
                continue
            outside = [p for p in hits if not _inside(regions, p)]
            if outside:
                violations.append(
                    f"C3 {label} in exportLargeContentToFile runs OUTSIDE "
                    "`withContext(Dispatchers.IO)` — a call merely sitting next to an (empty) "
                    "IO block still blocks the UI thread")

        # ── C4: prune wired before the write, failure non-blocking, real clock ─
        if "runCatching { pruneExpiredLargeMessageFiles(" not in code[body[0]:body[1]]:
            violations.append("C4 exportLargeContentToFile does not wrap the orphan prune in "
                              "`runCatching { pruneExpiredLargeMessageFiles(...) }` — exports must "
                              "stop leaving permanent files behind, and a prune failure must not "
                              "block the export the user asked for")
        prunes = _live_in(code, body, r"pruneExpiredLargeMessageFiles\s*\(")
        writes = _live_in(code, body, r"writeText\s*\(")
        if prunes and writes and min(prunes) > max(writes):
            violations.append("C4 prune runs after writeText — the fresh export would be inside "
                              "the swept set; prune must precede the write")
        for open_pos in [p + len("pruneExpiredLargeMessageFiles") for p in prunes]:
            args = _call_arg_spans(code, open_pos)
            if len(args) < 2:
                continue            # default clock (`System.currentTimeMillis()`)
            _nm, value = _arg_value(code, args[1])
            if const_value(value) is not None:
                # [batch-5 C4] `prune(dir, 0L)` ⇒ cutoff = -TTL ⇒ nothing is ever
                # older than it ⇒ the prune is a silent no-op that still satisfies
                # every "the call is there" rule (witness 02-3).
                violations.append(
                    f"C4 the orphan prune is fed a constant clock `{value}` — the cutoff becomes "
                    "permanently negative, so no file is ever expired and the export leaves a "
                    "permanent orphan again")
        # ── C5: URI handed out only after the bytes are on disk ────────────
        uris = _live_in(code, body, r"getUriForFile\s*\(")
        if writes and uris and max(writes) > min(uris):
            violations.append("C5 getUriForFile precedes the live writeText in the export — the "
                              "chooser may get a URI for a file that is not written yet "
                              "(a dead `if (false) f.writeText(...)` does not count as the write)")

    prune_body = _fn_spans(code, "pruneExpiredLargeMessageFiles")
    if prune_body is None:
        violations.append("C4 pruneExpiredLargeMessageFiles is not defined in LargeContentGuard.kt")
    elif ".lastModified()" not in code[prune_body[0]:prune_body[1]] or \
            ".delete()" not in code[prune_body[0]:prune_body[1]]:
        violations.append("C4 pruneExpiredLargeMessageFiles no longer expires (`.lastModified()`) "
                          "and deletes (`.delete()`) anything")
    if TTL_EXPECTED not in code:
        violations.append(f"C4 TTL literal changed — expected `{TTL_EXPECTED}` (recalibrating it "
                          "is a deliberate act: update this gate in the same commit)")

    # call site: suspend function must be invoked from a coroutine launched on a
    # composition scope, not from a plain onClick on the UI thread
    if "rememberCoroutineScope()" not in code:
        violations.append("C3 LargeContentGuard.kt has no `rememberCoroutineScope()` — the export "
                          "button needs a composition-scoped coroutine to leave the main thread")
    call_at = code.find("exportLargeContentToFile(context")
    if call_at < 0:
        violations.append("C3 export call site not found — LargeContentBadge must invoke "
                          "exportLargeContentToFile")
    else:
        window = code[max(0, call_at - 200):call_at]
        if ".launch" not in window:
            violations.append("C3 export call site is not inside a coroutine `launch` — a suspend "
                              "export called straight from onClick would not compile off-thread, "
                              "and a non-suspend one blocks the UI thread")

    # ── C8 [fix/da1024-A]: the staged-share copy runs on IO and prunes ──────
    #     its own orphans (deep-audit-1024 ISSUE-01 face 2 + 3). The dir is
    #     written, never deleted, and the copy is up to 100 MB per file
    #     (ShareReceiverActivity cap) — all of it used to run on the UI thread.
    if not re.search(r'internal\s+fun\s+pruneExpiredShareInboundFiles\s*\(', vm_code):
        violations.append("C8 pruneExpiredShareInboundFiles is not defined as a top-level "
                          "`internal fun` in ChatViewModel.kt — nothing would ever delete the "
                          "share_inbound staging copies")
    if SHARE_TTL_EXPECTED not in vm_code:
        violations.append(f"C8 TTL literal changed — expected `{SHARE_TTL_EXPECTED}` "
                          "(recalibrating it is a deliberate act: update this gate in the same "
                          "commit)")
    if not re.search(r'suspend\s+fun\s+addAttachmentFromStagedShare\s*\(', vm_code):
        violations.append("C8 addAttachmentFromStagedShare is not `suspend` — a non-suspending "
                          "signature means the 100 MB stream copy cannot leave the UI thread")
    vm_body = _fn_spans(vm_code, "addAttachmentFromStagedShare")
    if vm_body is None:
        violations.append("C8 addAttachmentFromStagedShare body not found — cannot verify the "
                          "share_inbound copy left the main thread")
    else:
        regions = _regions_in(io_regions(vm_code), vm_body)
        if not regions:
            violations.append("C8 addAttachmentFromStagedShare body has no "
                              "`withContext(Dispatchers.IO)` — mkdirs / the TTL prune / the "
                              "stream copy would run on the UI thread again "
                              "(deep-audit-1024 ISSUE-01)")
        else:
            if not any(_inside(regions, p) for p in
                       _live_in(vm_code, vm_body, r"pruneExpiredShareInboundFiles\s*\(")):
                violations.append("C8 the share_inbound prune is not inside "
                                  "`withContext(Dispatchers.IO)` in addAttachmentFromStagedShare — "
                                  "a directory walk on the UI thread right before a 100 MB copy")
            # [batch-5 C8] …and the COPY itself. Prune inside the switch while the
            # up-to-100 MB `inputStream().copyTo(out)` drifts outside it is the
            # defect the criterion exists for (witness 02-5).
            copies = _live_in(vm_code, vm_body, r"(?:\bcopyTo\s*\(|\binputStream\s*\(|\boutputStream\s*\()")
            if not any(re.match(r"copyTo", vm_code[p:p + 6]) for p in copies):
                violations.append("C8 addAttachmentFromStagedShare no longer streams the staged "
                                  "bytes (no live `copyTo(`) — the attachment would never be "
                                  "durable (fail-closed)")
            outside = [p for p in copies if not _inside(regions, p)]
            if outside:
                violations.append(
                    "C8 the share_inbound stream copy (inputStream / outputStream / copyTo) runs "
                    "OUTSIDE `withContext(Dispatchers.IO)` — up to 100 MB on the UI thread right "
                    "after the prune returned")

    # ── C9 [fix/da1024-A]: the ChatScreen share drain keeps its disk work on
    #     IO. Anchor is the `LaunchedEffect(shareBufferVersion)` body itself, so
    #     a `withContext(Dispatchers.IO)` that drifted to an unrelated call site
    #     does not satisfy the criterion.
    scr_body = _block_after(scr_code, r'LaunchedEffect\s*\(\s*shareBufferVersion\s*\)')
    if scr_body is None:
        violations.append("C9 ChatScreen share-drain ingestion point not found — expected a "
                          "`LaunchedEffect(shareBufferVersion) {` block in ChatScreen.kt")
    else:
        if "addAttachmentFromStagedShare(" not in scr_code[scr_body[0]:scr_body[1]]:
            violations.append("C9 ChatScreen share drain no longer calls "
                              "addAttachmentFromStagedShare — a staged share would stop being "
                              "attached to the composer")
        regions = _regions_in(io_regions(scr_code), scr_body)
        if not regions:
            violations.append("C9 the ChatScreen share drain has no "
                              "`withContext(Dispatchers.IO)` — consumeBuffer / the attachment "
                              "copy / the staging-dir sweep all run on the UI thread again "
                              "(deep-audit-1024 ISSUE-01)")
        else:
            sweeps = _live_in(scr_code, scr_body, r"cleanSharedFiles\s*\(")
            if not sweeps:
                violations.append("C9 SharedShareStore.cleanSharedFiles is gone from the share "
                                  "drain — the staging dir would grow without bound (fail-closed)")
            elif any(not _inside(regions, p) for p in sweeps):
                violations.append("C9 SharedShareStore.cleanSharedFiles is called outside "
                                  "`withContext(Dispatchers.IO)` in the ChatScreen share drain — "
                                  "listFiles() + per-file delete() on the UI thread")

    return violations


# ── fixtures ────────────────────────────────────────────────────────────────

CLEAN_XML = """<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path name="shared" path="shared/" />
    <cache-path name="share" path="share/" />
    <cache-path name="large-messages" path="large-messages/" />
    <cache-path name="share-inbound" path="share_inbound/" />
    <files-path name="media" path="media/" />
    <files-path name="photos-export" path="photos-export/" />
    <files-path name="logs" path="logs/" />
</paths>
"""

CLEAN_KT = '''package com.rikkaminis.app.ui.chat

import androidx.core.content.FileProvider
import java.io.File

internal const val LARGE_MESSAGE_EXPORT_TTL_MS = 24L * 60L * 60L * 1000L

internal fun pruneExpiredLargeMessageFiles(
    exportDir: File,
    nowMs: Long,
    maxAgeMs: Long = LARGE_MESSAGE_EXPORT_TTL_MS,
): Int {
    val children = exportDir.listFiles() ?: return 0
    val cutoff = nowMs - maxAgeMs
    var deleted = 0
    for (f in children) {
        if (f.isFile && f.lastModified() < cutoff && runCatching { f.delete() }.getOrDefault(false)) {
            deleted++
        }
    }
    return deleted
}

@Composable
internal fun LargeContentBadge(content: String, stableKey: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = {
            scope.launch {
                val ok = exportLargeContentToFile(context, content, stableKey)
                Toast.makeText(context, if (ok) "ok" else "fail").show()
            }
        },
    ) { }
}

private suspend fun exportLargeContentToFile(
    context: android.content.Context,
    content: String,
    stableKey: String,
): Boolean = try {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "large-messages").apply { mkdirs() }
        runCatching { pruneExpiredLargeMessageFiles(dir, System.currentTimeMillis()) }
            .onFailure { AppLogger.warning("LargeContentGuard", "prune failed") }
        val safeKey = stableKey.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
        val f = File(dir, "msg-$safeKey.txt")
        f.writeText(content)
        f
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    context.startActivity(chooserFor(uri))
    true
} catch (t: Throwable) {
    false
}
'''

CLEAN_MEDIASTORE = '''package com.rikkaminis.app.data.storage

import android.content.Context
import java.io.File
import java.util.UUID

class MediaStore(private val context: Context) {
    val mediaBaseDir: File = File(context.filesDir, "media")

    fun saveMedia(data: ByteArray, relativePath: String): File {
        val file = File(mediaBaseDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        return file
    }

    fun newId(): String = UUID.randomUUID().toString()
}
'''

CLEAN_PHOTOS = '''package com.rikkaminis.app.sandbox.offload

import android.content.Context
import java.io.File

class PhotosOffloadHandler(private val context: Context) {
    fun outDir(): File = File(context.filesDir, "photos-export").also { it.mkdirs() }
}
'''

# [fix/da1024-A] minimal stand-in for ui/chat/ChatViewModel.kt: the
# share_inbound writer, its suspend IO switch and the orphan prune.
CLEAN_CHATVM = '''package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ChatViewModel(private val context: android.content.Context) {

    suspend fun addAttachmentFromStagedShare(file: File): Any? {
        if (!file.exists()) return null
        val durable = withContext(Dispatchers.IO) {
            val durableDir = File(context.cacheDir, "share_inbound").apply { mkdirs() }
            runCatching { pruneExpiredShareInboundFiles(durableDir, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "share_inbound prune failed: ${it.message}") }
            val f = File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
            try {
                file.inputStream().use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
                f
            } catch (e: Exception) {
                Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
                null
            }
        } ?: return null
        addAttachment(durable)
        return durable
    }
}

internal const val SHARE_INBOUND_TTL_MS = 24L * 60L * 60L * 1000L

internal fun pruneExpiredShareInboundFiles(
    inboundDir: File,
    nowMs: Long,
    maxAgeMs: Long = SHARE_INBOUND_TTL_MS,
): Int {
    val children = inboundDir.listFiles() ?: return 0
    val cutoff = nowMs - maxAgeMs
    var deleted = 0
    for (f in children) {
        if (f.isFile && f.lastModified() < cutoff && runCatching { f.delete() }.getOrDefault(false)) {
            deleted++
        }
    }
    return deleted
}
'''

# [fix/da1024-A] minimal stand-in for ui/chat/ChatScreen.kt: the share drain.
CLEAN_SCREEN = '''package com.rikkaminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(sessionId: String, viewModel: ChatViewModel) {
    val shareBufferVersion by ShareCoordinator.bufferVersion.collectAsState()
    LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val pending = withContext(Dispatchers.IO) {
            ShareCoordinator.consumeBuffer(context)
        } ?: return@LaunchedEffect
        val sharedDir = withContext(Dispatchers.IO) {
            SharedShareStore.sharedFileDirectory(context)
        }
        for (item in pending.items) {
            when (item.kind) {
                PendingShare.Item.Kind.INLINE_TEXT -> {
                    viewModel.setInputText(item.value)
                }
                PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(File(sharedDir, item.value))
                }
            }
        }
        viewModel.markShareInjected()
        withContext(Dispatchers.IO) {
            SharedShareStore.cleanSharedFiles(context)
        }
    }
}
'''


def write_fixture(base: Path, xml=None, kt=None, media=None, photos=None, chatvm=None, screen=None):
    def put(rel, text):
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    put(XML_REL, xml if xml is not None else CLEAN_XML)
    put(KT_REL, kt if kt is not None else CLEAN_KT)
    put(MEDIASTORE_REL, media if media is not None else CLEAN_MEDIASTORE)
    put(PHOTOS_REL, photos if photos is not None else CLEAN_PHOTOS)
    put(CHATVM_REL, chatvm if chatvm is not None else CLEAN_CHATVM)
    put(SCREEN_REL, screen if screen is not None else CLEAN_SCREEN)


def selftest() -> int:
    results = []

    def run(name, expect_red, **kwargs):
        with tempfile.TemporaryDirectory(prefix="fpcheck_") as td:
            base = Path(td)
            write_fixture(base, **kwargs)
            v = check(base)
            if expect_red:
                hits = sorted({x.split("]")[0] + "]" for x in v})
                ok = bool(v)
                note = f"red on {hits}" if ok else "NOT RED"
            else:
                ok = not v
                note = "clean" if ok else f"UNEXPECTED: {v}"
            results.append((name, ok, note))

    # The clean fixture must pass: that is the contract for the real tree.
    run("CLEAN", expect_red=False)

    run(
        "XML missing large-messages",
        expect_red=True,
        xml=CLEAN_XML.replace('    <cache-path name="large-messages" path="large-messages/" />\n', ""),
    )
    run(
        "XML missing media",
        expect_red=True,
        xml=CLEAN_XML.replace('    <files-path name="media" path="media/" />\n', ""),
    )
    run(
        "XML missing photos-export",
        expect_red=True,
        xml=CLEAN_XML.replace('    <files-path name="photos-export" path="photos-export/" />\n', ""),
    )
    # A whole-volume root must not sneak in with a coverage fix.
    run(
        "XML wide external-path root",
        expect_red=True,
        xml=CLEAN_XML.replace("</paths>", '    <external-path name="everything" path="." />\n</paths>'),
    )
    run(
        "XML duplicate root name",
        expect_red=True,
        xml=CLEAN_XML.replace('    <files-path name="logs" path="logs/" />',
                              '    <files-path name="media" path="logs/" />'),
    )
    # Writer site renamed ⇒ the registry stopped covering anything real.
    run(
        "writer dir renamed",
        expect_red=True,
        media=CLEAN_MEDIASTORE.replace('File(context.filesDir, "media")',
                                       'File(context.filesDir, "media2")'),
    )
    # Export back on the main thread (non-suspend, no withContext).
    run(
        "export not suspend",
        expect_red=True,
        kt=CLEAN_KT.replace("private suspend fun exportLargeContentToFile(",
                            "private fun exportLargeContentToFile("),
    )
    run(
        "export missing Dispatchers.IO",
        expect_red=True,
        kt=CLEAN_KT.replace("val file = withContext(Dispatchers.IO) {", "val file = run {")
          .replace("    val uri = FileProvider", "    val uri = FileProvider"),
    )
    run(
        "prune call removed",
        expect_red=True,
        kt=CLEAN_KT.replace(
            '        runCatching { pruneExpiredLargeMessageFiles(dir, System.currentTimeMillis()) }\n'
            '            .onFailure { AppLogger.warning("LargeContentGuard", "prune failed") }\n', ""),
    )
    run(
        "prune after write",
        expect_red=True,
        kt=CLEAN_KT.replace(
            '        runCatching { pruneExpiredLargeMessageFiles(dir, System.currentTimeMillis()) }\n'
            '            .onFailure { AppLogger.warning("LargeContentGuard", "prune failed") }\n'
            '        val safeKey',
            '        val safeKey').replace(
            '        f.writeText(content)\n',
            '        f.writeText(content)\n'
            '        runCatching { pruneExpiredLargeMessageFiles(dir, System.currentTimeMillis()) }\n'),
    )
    run(
        "uri before write",
        expect_red=True,
        kt=CLEAN_KT.replace(
            "        val f = File(dir, \"msg-$safeKey.txt\")\n",
            "        val f = File(dir, \"msg-$safeKey.txt\")\n"
            "        val early = FileProvider.getUriForFile(context, \"a\", f)\n"
            "        if (early != null) println(early)\n"),
    )
    run(
        "TTL literal recalibrated silently",
        expect_red=True,
        kt=CLEAN_KT.replace("LARGE_MESSAGE_EXPORT_TTL_MS = 24L * 60L * 60L * 1000L",
                            "LARGE_MESSAGE_EXPORT_TTL_MS = 7L * 24L * 60L * 60L * 1000L"),
    )
    run(
        "call site outside launch",
        expect_red=True,
        kt=CLEAN_KT.replace("            scope.launch {\n", "            run {\n"),
    )

    # ── [fix/da1024-A] deep-audit-1024 ISSUE-01: share_inbound wiring ───────
    run(
        "XML missing share-inbound",
        expect_red=True,
        xml=CLEAN_XML.replace('    <cache-path name="share-inbound" path="share_inbound/" />\n', ""),
    )
    run(
        "share_inbound writer dir renamed",
        expect_red=True,
        chatvm=CLEAN_CHATVM.replace('File(context.cacheDir, "share_inbound")',
                                    'File(context.cacheDir, "share_inbound2")'),
    )
    run(
        "share_inbound prune call removed",
        expect_red=True,
        chatvm=CLEAN_CHATVM.replace(
            '            runCatching { pruneExpiredShareInboundFiles(durableDir, System.currentTimeMillis()) }\n'
            '                .onFailure { Log.w(TAG, "share_inbound prune failed: ${it.message}") }\n', ""),
    )
    run(
        "share_inbound prune outside the IO switch",
        expect_red=True,
        chatvm=(CLEAN_CHATVM
                .replace('            runCatching { pruneExpiredShareInboundFiles(durableDir, System.currentTimeMillis()) }\n'
                         '                .onFailure { Log.w(TAG, "share_inbound prune failed: ${it.message}") }\n', "")
                .replace('        val durable = withContext(Dispatchers.IO) {\n',
                         '        pruneExpiredShareInboundFiles(File(context.cacheDir, "share_inbound"), 0L)\n'
                         '        val durable = withContext(Dispatchers.IO) {\n')),
    )
    run(
        "share_inbound copy not on IO",
        expect_red=True,
        chatvm=CLEAN_CHATVM.replace("val durable = withContext(Dispatchers.IO) {",
                                    "val durable = run {"),
    )
    run(
        "share_inbound copy no longer suspend",
        expect_red=True,
        chatvm=CLEAN_CHATVM.replace("suspend fun addAttachmentFromStagedShare(",
                                    "fun addAttachmentFromStagedShare("),
    )
    run(
        "share_inbound prune not internal top-level",
        expect_red=True,
        chatvm=CLEAN_CHATVM.replace("\ninternal fun pruneExpiredShareInboundFiles(",
                                    "\nprivate fun pruneExpiredShareInboundFiles("),
    )
    run(
        "share_inbound TTL literal recalibrated silently",
        expect_red=True,
        chatvm=CLEAN_CHATVM.replace("SHARE_INBOUND_TTL_MS = 24L * 60L * 60L * 1000L",
                                    "SHARE_INBOUND_TTL_MS = 7L * 24L * 60L * 60L * 1000L"),
    )
    run(
        "ChatScreen sweep outside the IO switch",
        expect_red=True,
        screen=CLEAN_SCREEN.replace(
            '        withContext(Dispatchers.IO) {\n            SharedShareStore.cleanSharedFiles(context)\n        }\n',
            '        SharedShareStore.cleanSharedFiles(context)\n'),
    )
    run(
        "ChatScreen drain has no IO switch at all",
        expect_red=True,
        screen=CLEAN_SCREEN.replace("withContext(Dispatchers.IO) {", "run {"),
    )
    run(
        "ChatScreen drain stops attaching staged files",
        expect_red=True,
        screen=CLEAN_SCREEN.replace("viewModel.addAttachmentFromStagedShare(File(sharedDir, item.value))",
                                    "Unit"),
    )

    # ── [fix/meta-audit-m2-binding] the falsifying witness, verbatim: a dead
    #    alias keeps the registry's literal (`media`) in the file while the
    #    property every write goes through is re-pointed at an undeclared dir.
    #    Old C1/C2 stayed green ("the literal still appears, and media/ is
    #    declared"); C10 catches the undeclared root, C11 catches the dead alias.
    WITNESS_MEDIASTORE = CLEAN_MEDIASTORE.replace(
        '    val mediaBaseDir: File = File(context.filesDir, "media")',
        '    private val _legacyMediaDir: File = File(context.filesDir, "media")\n'
        '    val mediaBaseDir: File = File(context.filesDir, "media-unregistered")')
    run("WITNESS dead alias + re-pointed live dir", expect_red=True, media=WITNESS_MEDIASTORE)

    # Same defect with the XML widened to cover the new dir: C10 goes quiet, so
    # only the binding rule (C11) can still notice that the live write path
    # moved off the registry's dir.
    run(
        "WITNESS variant: new dir declared, only C11 can see it",
        expect_red=True,
        media=WITNESS_MEDIASTORE,
        xml=CLEAN_XML.replace(
            '    <files-path name="photos-export" path="photos-export/" />',
            '    <files-path name="media-unregistered" path="media-unregistered/" />\n'
            '    <files-path name="photos-export" path="photos-export/" />'),
    )

    # Constant folding: the literal is assembled so a naive `File(dir, "x")`
    # scanner reads nothing.
    run(
        "dir literal assembled by concatenation",
        expect_red=True,
        media=CLEAN_MEDIASTORE.replace(
            '    val mediaBaseDir: File = File(context.filesDir, "media")',
            '    val mediaBaseDir: File = File(context.filesDir, "media" + "-unregistered")'),
    )

    # A brand-new dir root that the registry never knew about.
    run(
        "extra undeclared dir root added",
        expect_red=True,
        photos=CLEAN_PHOTOS.replace(
            '    fun outDir(): File = File(context.filesDir, "photos-export").also { it.mkdirs() }',
            '    fun outDir(): File = File(context.filesDir, "photos-export").also { it.mkdirs() }\n'
            '    fun scratch(): File = File(context.filesDir, "photos-scratch")'),
    )

    # Non-literal dir root: fail-closed, not silently skipped.
    run(
        "dir root built from a variable",
        expect_red=True,
        media=CLEAN_MEDIASTORE.replace(
            'File(context.filesDir, "media")', 'File(context.filesDir, dirName)'),
    )


    # ── [batch-5] one arm per falsified witness (each was BLIND before) ─────
    # 02-1: the same dir root spelled as an extension call — no `File(` at all,
    # so the constructor-only derivation never saw the re-pointed live dir.
    run(
        "WITNESS dir root spelled as context.filesDir.resolve(\u2026)",
        expect_red=True,
        media=CLEAN_MEDIASTORE.replace(
            '    val mediaBaseDir: File = File(context.filesDir, "media")',
            '    private val _legacyMediaDir: File = File(context.filesDir, "media")\n'
            '    val mediaBaseDir: File = context.filesDir.resolve("media-unregistered")'),
    )
    # 02-2: an (empty) IO block keeps the substring while the real work runs in
    # `run {}` — on the main thread again.
    run(
        "WITNESS export disk work beside an EMPTY IO block",
        expect_red=True,
        kt=CLEAN_KT.replace(
            "    val file = withContext(Dispatchers.IO) {",
            "    withContext(Dispatchers.IO) { }\n"
            "    val file = run {"),
    )
    # 02-3: the prune is called, deletes, and never expires anything: 0L.
    run(
        "WITNESS prune fed a constant clock (0L)",
        expect_red=True,
        kt=CLEAN_KT.replace(
            "runCatching { pruneExpiredLargeMessageFiles(dir, System.currentTimeMillis()) }",
            "runCatching { pruneExpiredLargeMessageFiles(dir, 0L) }"),
    )
    # 02-4: a dead write planted before the uri, the real write moved after it.
    run(
        "WITNESS dead write before the uri, real write after",
        expect_red=True,
        kt=CLEAN_KT.replace(
            "        f.writeText(content)\n        f\n",
            "        if (false) f.writeText(\"\")\n        f\n")
          .replace(
            "    val uri = FileProvider.getUriForFile(",
            "    file.writeText(content)\n    val uri = FileProvider.getUriForFile("),
    )
    # 02-5: the prune stays inside the IO switch, the up-to-100 MB copy drifts
    # out of it.
    run(
        "WITNESS share_inbound copy drifted out of the IO region",
        expect_red=True,
        chatvm=CLEAN_CHATVM.replace(
            '        val durable = withContext(Dispatchers.IO) {\n'
            '            val durableDir = File(context.cacheDir, "share_inbound").apply { mkdirs() }\n'
            '            runCatching { pruneExpiredShareInboundFiles(durableDir, System.currentTimeMillis()) }\n'
            '                .onFailure { Log.w(TAG, "share_inbound prune failed: ${it.message}") }\n'
            '            val f = File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")\n'
            '            try {\n'
            '                file.inputStream().use { input ->\n'
            '                    f.outputStream().use { output -> input.copyTo(output) }\n'
            '                }\n'
            '                f\n'
            '            } catch (e: Exception) {\n'
            '                Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")\n'
            '                null\n'
            '            }\n'
            '        } ?: return null',
            '        val durableDir = withContext(Dispatchers.IO) {\n'
            '            val d = File(context.cacheDir, "share_inbound").apply { mkdirs() }\n'
            '            runCatching { pruneExpiredShareInboundFiles(d, System.currentTimeMillis()) }\n'
            '                .onFailure { Log.w(TAG, "share_inbound prune failed: ${it.message}") }\n'
            '            d\n'
            '        }\n'
            '        val durable = run {\n'
            '            val f = File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")\n'
            '            file.inputStream().use { input ->\n'
            '                f.outputStream().use { output -> input.copyTo(output) }\n'
            '            }\n'
            '            f\n'
            '        }'),
    )
    # 02-6: a `"{"` inside the first IO block. A bare brace counter never
    # returns to depth 0 there, so the region swallows everything after it.
    run(
        "WITNESS a \"{\" inside the IO block (region swallows the sweep)",
        expect_red=True,
        screen=CLEAN_SCREEN.replace(
            '            ShareCoordinator.consumeBuffer(context)\n',
            '            val tpl = "{"\n'
            '            ShareCoordinator.consumeBuffer(context, tpl)\n')
          .replace(
            '        withContext(Dispatchers.IO) {\n'
            '            SharedShareStore.cleanSharedFiles(context)\n'
            '        }\n',
            '        SharedShareStore.cleanSharedFiles(context)\n'),
    )

    print("=== file_provider_paths_coverage --selftest ===")
    failed = 0
    for name, ok, note in results:
        if not ok:
            failed += 1
        print(f"[{'ok' if ok else 'FAIL'}] {name} -> {note}")
    if failed:
        print(f"SELFTEST: {failed} arm(s) misbehaved")
        return 1
    print(f"FILE_PROVIDER_PATHS_SELFTEST=PASS ({len(results)} arms)")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    if args and args[0] == "--root":
        if len(args) < 2:
            print("usage: file_provider_paths_coverage_check.py --root <dir>")
            return 2
        root = Path(args[1])
    elif args:
        root = Path(args[0])
    else:
        root = Path(__file__).resolve().parents[2]
    violations = check(root)
    for v in violations:
        print("VIOLATION:", v)
    print(f"Summary: {len(violations)} violation(s) found")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
