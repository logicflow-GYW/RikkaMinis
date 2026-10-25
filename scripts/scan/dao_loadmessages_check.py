#!/usr/bin/env python3
"""
Gate: production code must not read the chat transcript through the raw DAO
(`<dao>.loadMessages(...)`) — every production read funnels through
ChatRepository.loadMessages / loadMessagePageRaw so the big-session
(>2MB CursorWindow / SQLiteBlobTooBigException) tolerance path is
unavoidable.

ISSUE-08 (deep-audit-1018, P1, fixed 2026-10-16, branch
fix/da1018-dao-loadmessages): twelve call sites bypassed the repository —
CompactOrchestration (compact raw-id verify), ChatDebugMethods,
ChatMutationMethods, HeadlessChatRunner (debug RPC + scheduled-task +
headless CLI channels). A scheduled task or CLI read on a big session
threw SQLiteBlobTooBigException and the channel died. All twelve now route
through ChatRepository (count-only sites use messageCount); both production
paged entries (loadMessagesInner, loadMessagePageRaw) funnel through the
single tolerant loadPageTolerant.

Scope:
  * production sources only: any .kt under */src/main/* (plus src/shared/,
    currently zero .kt) — instrumented/unit tests legitimately drive the
    raw DAO against fresh small databases and are out of scope by design;
  * NO file-level whitelist. The repository and the DAO interface are in
    scope like everything else: the DAO only *declares* `fun loadMessages`
    (a declaration has no receiver, so it cannot match), and the repository
    legitimately uses the paged calls (`dao.loadMessagePage*`) which this
    gate does not flag. A whole-file exemption made ChatRepository.kt a
    lawless zone where a new helper could re-introduce the bypass this gate
    exists to stop (wide3 witness `WHITELIST_BASENAMES`).

Criterion (what counts as a bypass): a live `loadMessages(...)` CALL whose
receiver resolves to the DAO — directly (`dao.` / `x.dao.` /
`app.chatRepository.dao.`), through a local alias (`val rawDao = repo.dao`
… `rawDao.loadMessages(...)`), across a wrapped line (`repo.dao` then
`.loadMessages(...)` — ktlint wraps chains at the dot), or bare inside a
DAO scope function (`with(repo.dao) { loadMessages(...) }`). Receivers that
resolve to neither the DAO nor a repository-shaped name are reported too:
a static gate that silently accepts an unknown receiver goes blind again
the moment a new alias spelling appears.

Usage: python3 scripts/scan/dao_loadmessages_check.py [repo_root]
       python3 scripts/scan/dao_loadmessages_check.py --selftest
Exit:  0 = clean (or selftest pass), 1 = violation(s) / selftest fail,
       2 = usage error
"""
import re
import sys
import tempfile
from pathlib import Path

# `<receiver>.loadMessages(` / `<receiver>?.loadMessages(`
CALL_RE = re.compile(r"(?<![\w.])([A-Za-z_][\w.]*)\s*(?:\?\.|\.)\s*loadMessages\s*\(")
# A call with no receiver at all — legal only when it is a declaration
# (`fun loadMessages(`) or the enclosing scope IS a dao (`with(…dao) { … }`).
BARE_RE = re.compile(r"(?<![\w.])loadMessages\s*\(")
DECL_BEFORE_RE = re.compile(r"\bfun\s+(?:suspend\s+)?$")
DAO_SCOPE_RE = re.compile(
    r"(?:\bwith\s*\(|\.\s*(?:let|run|also|apply)\s*\{)[^;{}\n]*dao", re.I)
ASSIGN_RE = re.compile(r"\b(?:val|var)\s+(\w+)\s*=\s*")

DAO_SUFFIX = re.compile(r"dao$", re.I)
REPO_HINT = re.compile(r"repo", re.I)


def is_production(path: Path, root: Path) -> bool:
    """Production = .kt under */src/main/*, or under src/shared/."""
    rel = path.relative_to(root).as_posix()
    if not rel.endswith(".kt"):
        return False
    if "/src/main/" in rel:
        return True
    return rel.startswith("src/shared/")


def mask_comments(src: str) -> str:
    """Blank `//` and `/* */` comments with spaces (newlines kept), leaving
    string literals intact and every index aligned with `src`.

    Without this the KDoc that *documents* the banned shape ("the legacy
    `dao.loadMessages` path …") would be read as the banned shape itself —
    which is exactly what the old whole-file whitelist papered over.
    """
    return _mask(src, blank_strings=False)


def mask_strings(src: str) -> str:
    """Blank string-literal contents with spaces (quotes kept), same length.

    Used only for brace-depth bookkeeping: a `"{"` inside a literal must not
    change how statements are glued together.
    """
    return _mask(src, blank_strings=True)


def _mask(src: str, blank_strings: bool) -> str:
    def _blank(ch: str) -> str:
        return "\n" if ch == "\n" else " "

    out = []
    i = 0
    n = len(src)
    state = None  # None | '"' | "'" | '"""'
    while i < n:
        if state is not None:
            if state == '"""':
                if src.startswith('"""', i):
                    out.append('"""')
                    i += 3
                    state = None
                    continue
                out.append(_blank(src[i]) if blank_strings else src[i])
                i += 1
                continue
            c = src[i]
            if c == "\\" and i + 1 < n:
                out.append(_blank(c) if blank_strings else c)
                out.append(_blank(src[i + 1]) if blank_strings else src[i + 1])
                i += 2
                continue
            out.append(_blank(c) if (blank_strings and c != state) else c)
            if c == state:
                state = None
            i += 1
            continue
        if src.startswith('"""', i):
            out.append('"""')
            i += 3
            state = '"""'
            continue
        c = src[i]
        if c == '"' or c == "'":
            state = c
            out.append(c)
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            if j < 0:
                j = n
            out.append(" " * (j - i))
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n - 2 if j < 0 else j
            out.append("".join("\n" if ch == "\n" else " " for ch in src[i:j + 2]))
            i = j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def logical_statements(text: str, structural: str):
    """[(first_line_no, joined_text)] with wrapped lines glued back together.

    A Kotlin chain wraps at the dot (ktlint puts the `.` at the start of the
    continuation line), so `repo.dao` on one line and `.loadMessages(s)` on the
    next is ONE expression; scanning line by line sees neither as the banned
    shape — that is witness `is_production`. `structural` is the same text with
    strings blanked, used only to count brackets.
    """
    lines = text.split("\n")
    struc = structural.split("\n")
    out = []
    buf = []
    start = 1
    depth = 0
    for i, line in enumerate(lines):
        if not buf:
            start = i + 1
        buf.append(line)
        for ch in struc[i]:
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
        nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
        open_next = depth > 0 or nxt[:1] in (".", "?", ":") or nxt[:1] == "|"
        cont = open_next or line.rstrip().endswith(("=", ",", "(", "&&", "||", "+"))
        if cont:
            continue
        out.append((start, "\n".join(buf)))
        buf = []
    if buf:
        out.append((start, "\n".join(buf)))
    return out


def collect_dao_names(stmt: str) -> set:
    """Identifiers that are bound to the DAO inside one statement.

    `val rawDao = repo.dao` (and a wrapped variant) makes every later
    `rawDao.loadMessages(...)` the same bypass spelled differently — that is
    witness `PATTERN`.
    """
    names = set()
    for m in ASSIGN_RE.finditer(stmt):
        rhs = stmt[m.end():].strip()
        if re.search(r"(?:^|\.)\s*dao$", rhs) or rhs == "dao":
            names.add(m.group(1))
    for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*:\s*\w*Dao\b", stmt):
        names.add(m.group(1))
    return names


def _receiver_kind(receiver: str, dao_names: set) -> str:
    """'dao' | 'repo' | 'unknown' for a call receiver expression."""
    last = re.split(r"[\s.]", receiver.strip())[-1] or receiver.strip()
    if last in dao_names or DAO_SUFFIX.search(last):
        return "dao"
    if REPO_HINT.search(receiver):
        return "repo"
    return "unknown"


def scan(root: Path) -> list[str]:
    """Return violation descriptions (file:line + snippet), empty if clean."""
    violations: list[str] = []
    scanned = 0
    for path in sorted(root.rglob("*.kt")):
        if not is_production(path, root):
            continue
        try:
            raw = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:  # unreadable = report it
            violations.append(f"{path}: unreadable ({exc})")
            continue
        scanned += 1
        text = mask_comments(raw)
        structural = mask_strings(text)
        dao_names = set()
        for first_lineno, stmt in logical_statements(text, structural):
            dao_names |= collect_dao_names(stmt)
            for m in CALL_RE.finditer(stmt):
                receiver = m.group(1)
                kind = _receiver_kind(receiver, dao_names)
                if kind == "repo":
                    continue
                line = first_lineno + stmt.count("\n", 0, m.start())
                why = ("direct DAO read" if kind == "dao"
                       else "receiver not provably the repository")
                snippet = stmt[max(0, m.start() - 40):m.end() + 20].replace("\n", " ")
                violations.append(
                    f"{path}:{line}: {why} — `{receiver}.loadMessages(` bypasses "
                    f"ChatRepository.loadPageTolerant: {snippet.strip()[:120]}")
            for m in BARE_RE.finditer(stmt):
                if DECL_BEFORE_RE.search(stmt[:m.start()]):
                    continue  # declaration, not a call
                prefix = stmt[:m.start()]
                line = first_lineno + stmt.count("\n", 0, m.start())
                snippet = stmt[max(0, m.start() - 50):m.end() + 20].replace("\n", " ")
                if DAO_SCOPE_RE.search(prefix):
                    violations.append(
                        f"{path}:{line}: DAO scope-function read — `loadMessages(` runs "
                        f"on a dao receiver: {snippet.strip()[:120]}")
                else:
                    violations.append(
                        f"{path}:{line}: unqualified loadMessages( call — cannot prove the "
                        f"receiver is the repository (fail-closed): {snippet.strip()[:120]}")
    if scanned == 0:
        violations.append("no production .kt sources found — scanner is blind on this tree")
    return violations


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def selftest() -> int:
    """Arms: every banned spelling must red, every legal one must stay green.
    Each arm names its own expectation so a broken criterion cannot pass as
    noise from a neighbouring arm."""
    ok = True

    def arm(label: str, files: dict, expect: int, needle: str = "") -> None:
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, content in files.items():
                _write(root / rel, content)
            hits = scan(root)
            good = len(hits) == expect and (not needle or any(needle in h for h in hits))
            if good:
                print(f"[selftest] {label}: OK ({len(hits)} violation(s))")
            else:
                print(f"[selftest] {label}: FAIL expected {expect} hit(s) "
                      f"containing {needle!r}, got {hits!r}")
                ok = False

    P = "src/android/app/src/main/java/com/example/"
    T = "src/android/app/src/test/java/com/example/"

    # ---- banned spellings -------------------------------------------------
    arm("A direct .dao receiver reds",
        {P + "Foo.kt": "class Foo { fun bad(repo: ChatRepository) {"
                       " val rows = repo.dao.loadMessages(\"s1\") } }\n"},
        1, "direct DAO read")

    arm("B local DAO alias reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        val rawDao = repo.dao\n"
                       "        val rows = rawDao.loadMessages(\"s1\")\n"
                       "    }\n}\n"},
        1, "direct DAO read")

    arm("C wrapped chain (dot on next line) reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        val rows = repo.dao\n"
                       "            .loadMessages(\"s1\")\n"
                       "    }\n}\n"},
        1, "direct DAO read")

    arm("D DAO inside a with() scope reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        with(repo.dao) { loadMessages(\"s1\") }\n"
                       "    }\n}\n"},
        1, "DAO scope-function read")

    arm("E repository file is NOT a lawless zone (no whitelist)",
        {P + "ChatRepository.kt": "class ChatRepository {\n"
                                  "    suspend fun sessionTokenUsages(s: String) =\n"
                                  "        this.dao.loadMessages(s).mapNotNull { it.tokenUsage }\n"
                                  "}\n"},
        1, "direct DAO read")

    arm("F unreadable receiver is fail-closed",
        {P + "Foo.kt": "class Foo { fun f(x: Any) = x.loadMessages(\"s1\") }\n"},
        1, "not provably the repository")

    # ---- legal spellings --------------------------------------------------
    arm("G repository receiver green",
        {P + "Clean.kt": "class Clean {\n"
                         "    suspend fun ok(repo: ChatRepository) = repo.loadMessages(\"s1\")\n"
                         "    suspend fun ok2(app: MinisApp) =\n"
                         "        app.chatRepository.loadMessages(\"s1\")\n"
                         "}\n"},
        0)

    arm("H the DAO interface declaration green",
        {P + "ChatDao.kt": "interface ChatDao {\n"
                           "    suspend fun loadMessages(sessionId: String): List<MessageEntity>\n"
                           "    suspend fun loadMessagesPage(s: String, o: Int, l: Int):"
                           " List<MessageEntity>\n"
                           "}\n"},
        0)

    arm("I doc comment mentioning dao.loadMessages green",
        {P + "ChatRepository.kt": "class ChatRepository {\n"
                                  "    // the legacy `dao.loadMessages` path issued one SELECT\n"
                                  "    suspend fun loadMessages(s: String) = loadMessagesInner(s)\n"
                                  "}\n"},
        0)

    arm("J test source sets are out of scope",
        {P + "Clean.kt": "class Clean { fun ok(repo: ChatRepository) ="
                         " repo.loadMessages(\"s1\") }\n",
         T + "BadTest.kt": "class BadTest { fun t(repo: ChatRepository) ="
                           " repo.dao.loadMessages(\"s1\") }\n",
         "src/android/app/src/androidTest/java/com/example/BadIt.kt":
             "class BadIt { fun t(repo: ChatRepository) = repo.dao.loadMessages(\"s1\") }\n"},
        0)

    if ok:
        print("DAO_LOADMESSAGES_SELFTEST=PASS")
        return 0
    print("DAO_LOADMESSAGES_SELFTEST=FAIL")
    return 1


def main(argv: list[str]) -> int:
    root: Path | None = None
    for i, arg in enumerate(argv):
        if arg == "--selftest":
            return selftest()
        if arg == "--root":
            if i + 1 >= len(argv):
                print("usage: --root needs a value", file=sys.stderr)
                return 2
            root = Path(argv[i + 1])
        elif not arg.startswith("--"):
            root = Path(arg)
    if root is None:
        root = Path.cwd()
    if not root.is_dir():
        print(f"repo root not found: {root}", file=sys.stderr)
        return 2

    violations = scan(root)
    if violations:
        print("[dao-loadmessages] DIRECT dao.loadMessages BYPASSES FOUND:")
        for v in violations:
            print(f"  FAIL: {v}")
        print(
            "Fix: route production reads through ChatRepository.loadMessages "
            "/ loadMessagePageRaw (count-only sites: messageCount)."
        )
        return 1
    print("[dao-loadmessages] clean: 0 direct dao.loadMessages( in production")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
