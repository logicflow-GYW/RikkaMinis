#!/usr/bin/env python3
"""
Gate: FIX-C (deep-audit-1019 ISSUE-03) — ustar name/prefix ceilings must match
what putText can actually store, and a denied tar path must exit non-zero.

ISSUE-03a (FastioTar.kt)
  `putText` reserves the final byte of every fixed-width string field for the
  NUL terminator (`count = minOf(bytes.size, length - 1)`), so the name field
  stores 99 bytes and the prefix field 154. `splitUstarName` used to ask
  "does it fit" with the RAW field sizes (`<= NAME_LENGTH` / `<=
  PREFIX_LENGTH`), so a 100-byte name / 155-byte prefix / 100-byte rest was
  declared to fit and then silently truncated by one byte — `.py` became `.p`,
  a multi-byte UTF-8 character got cut in half, and nothing on the write path
  reported it. The rule: the fit test uses the STORED sizes
  (`NAME_LENGTH - 1` / `PREFIX_LENGTH - 1`), the same way the linkname guard in
  `writeHeader` always did (`> LINKNAME_LENGTH - 1`). Anything one byte larger
  then takes the GNU 'L' long-name record, which has no such ceiling.

ISSUE-03b (FastioOffloadHandler.kt)
  `finishTar(entry, failed, …)` maps `failed` to the exit code (`if (failed) 1
  else 0`). A denied archive path means NOTHING was written, so the call must
  pass `true` — the old `false` returned 0 with an error object in the body,
  which `set -e` and `&&` chains read as success. The rule: every
  `finishTar(deniedEntry(…), <flag>, …)` passes `true`, same as the
  destination-denied branch of tarExtractFrom.

Batch-7 hardened every criterion from "the spelling exists" to "the decision
holds". The pre-fix gate grepped tokens (`SPLIT_NAME_OK`, a `<=`-shaped
`STALE_CEILING_RE`, `"false &&"` on the LEFT of a guard, the substring
`size - written`, "a sort appears in writeTree") — so each of the six witness
patches below kept every token, kept the position, and reintroduced the bug:

  09-1 the compliant ceiling line stays and `if (utf8Length(name) ==
       NAME_LENGTH) …` is appended        -> the fit test is now parsed: every
       `utf8Length(<half>)` comparison in `splitUstarName` must mean the STORED
       ceiling (`<= FIELD - 1`, or `< FIELD`), judged by the arithmetic.
  09-2 `if (false && Files.isSymbolicLink(archivePath))` -> the refusal is
       judged by folding the condition: a guard that constant-folds refuses
       nothing, and the guarded branch must return/throw.
  09-3 `writeZeros(sink, (size - written) * 0L)` -> the fill AMOUNT must BE
       `size - written`, not contain it.
  09-4 `stream.toList().also { it.sortedBy { … } }` -> a sort inside a
       discarding lambda (`also` / `apply` / `onEach` / `forEach`) hands back
       the unsorted receiver, so it does not order anything.
  09-5 `if (!attrs.isRegularFile && false)` -> the dead literal moved to the
       RIGHT; the condition is folded instead of pattern-matched.
  09-6 (already caught) the same dead literal on the LEFT — kept as an arm.

Everything structural now comes from the shared lexer/AST/value-flow modules
(`code_view`, `function_body`, `match_bracket`, `call_args`, `split_top`,
`const_bool`, `guard_feasible`, `path_feasible`); the hand-written comment
stripper, brace counter and guard scanner are gone. A refactor that keeps the
contract is a deliberate edit to this gate rather than a silent pass.

Usage: python3 scripts/scan/fastio_tar_bounds_check.py [repo_root]
       python3 scripts/scan/fastio_tar_bounds_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_ast import (
    const_bool,
    enclosing_conditions,
    function_body,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import (
    call_args,
    code_view,
    expr_end,
    guard_feasible,
    path_feasible,
    scope_calls,
)

TAR_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioTar.kt"
HANDLER_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioOffloadHandler.kt"

# ── ISSUE-03a: the fit test must use the stored sizes ────────────────────────
# putText writes at most `length - 1` bytes of a fixed-width field (the last
# byte is the NUL terminator), so the ONLY correct fit test is against the
# STORED ceiling. Which field a half of the name is compared to is derived
# from the operand, and the accepted spellings are the ones that mean the
# same ceiling (`<= FIELD - 1`, `< FIELD`) — anything else (`== FIELD`,
# `<= FIELD`, `>=`, `< FIELD - 1` is stricter but fine) is judged by the
# arithmetic, not by a token list: batch-7 witness 09-1 kept the compliant
# line and appended `if (utf8Length(name) == NAME_LENGTH) return "" to name`,
# which a `<=`-only regex never saw.
CEILING_FIELD = {"name": "NAME_LENGTH", "prefix": "PREFIX_LENGTH", "rest": "NAME_LENGTH"}
# The NUL-terminator reserve in putText — the reason the ceilings are -1.
PUTTEXT_RESERVE = "val count = minOf(bytes.size, length - 1)"

# ── ISSUE-03b: a denied path is a failure ───────────────────────────────────
DENIED_CALL_RE = re.compile(
    r"finishTar\(deniedEntry\(.*?\)\s*,\s*(true|false)\s*,",
    re.DOTALL,
)
MIN_DENIED_SITES = 2  # archive path (tarCreate) + dest path (tarExtract)


def _ceiling_sites(body):
    """[(pos, operand, op, rhs)] for every `utf8Length(<operand>) <op> <rhs>`."""
    out = []
    for m in re.finditer(r"utf8Length\s*\(", body):
        spans = call_args(body, m.end() - 1)
        close = match_bracket(body, m.end() - 1)
        if close < 0:
            out.append((m.start(), None, None, None))
            continue
        if len(spans) != 1:
            out.append((m.start(), None, None, None))
            continue
        operand = body[spans[0][0]:spans[0][1]].strip()
        j = close + 1
        while j < len(body) and body[j] in " \t":
            j += 1
        op = None
        for cand in ("<=", ">=", "==", "!=", "<", ">"):
            if body.startswith(cand, j):
                op = cand
                j += len(cand)
                break
        if op is None:
            out.append((m.start(), operand, None, None))
            continue
        # the right operand ends at a top-level `&&` / `||` / `,` / paren
        tail = body[j:]
        line_end = body.find("\n", j)
        seg = body[j:line_end if line_end >= 0 else len(body)]
        parts = split_top(seg, ("&&", "||"))
        raw = seg[parts[0][0]:parts[0][1]] if parts else seg
        # a following `)` closed the enclosing `if (` — it is not part of the rhs
        depth = 0
        cut = len(raw)
        for k, ch in enumerate(raw):
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                if depth == 0:
                    cut = k
                    break
                depth -= 1
        out.append((m.start(), operand, op, raw[:cut].strip()))
    return out


def _ceiling_ok(operand, op, rhs):
    """(ok, message) — does this fit test use the STORED ceiling?"""
    field = CEILING_FIELD.get(operand)
    if field is None:
        return False, (
            "stale raw-field ceiling: `utf8Length(%s)` is not one of the ustar name halves "
            "(%s) — re-point this rule before trusting it"
            % (operand, "/".join(sorted(CEILING_FIELD)))
        )
    if op is None:
        return False, (
            "splitUstarName computes `utf8Length(%s)` without comparing it to the stored "
            "ceiling (`%s - 1`) — the fit test decides nothing" % (operand, field)
        )
    r = re.sub(r"\s+", "", strip_parens(rhs))
    accepted = {field + "-1"}
    if op == "<":
        accepted.add(field)  # `< FIELD` is the same size as `<= FIELD - 1`
    if op in ("<", "<=") and (r in accepted or (op == "<" and r == field + "-1")):
        return True, ""
    return False, (
        "stale raw-field ceiling: `utf8Length(%s) %s %s` is not the STORED size "
        "(`%s - 1`: putText reserves the last byte for the NUL terminator), so a %s-byte "
        "name is declared to fit and then silently truncated by one byte (`.py` -> `.p`, a "
        "multi-byte character cut in half)" % (operand, op, rhs or "<missing>", field, field)
    )


def check_tar(src):
    v = []
    if PUTTEXT_RESERVE not in src:
        v.append("putText must keep the final field byte for the NUL terminator (required "
                 "token missing)")
    code = code_view(src)
    span = function_body(code, "splitUstarName")
    if span is None:
        v.append("splitUstarName must exist in FastioTar.kt (required function missing)")
        return v
    base = span[0]
    body = code[base:span[1]]
    sites = _ceiling_sites(body)
    if not sites:
        v.append("splitUstarName has no `utf8Length(…)` fit test — re-point this rule before "
                 "trusting it (the ustar ceilings are unchecked)")
        return v
    for pos, operand, op, rhs in sites:
        ok, why = _ceiling_ok(operand, op, rhs)
        if not ok:
            v.append("FastioTar.kt:%d %s" % (code.count("\n", 0, base + pos) + 1, why))
    return v


def check_handler(src):
    v = []
    sites = list(DENIED_CALL_RE.finditer(src))
    if len(sites) < MIN_DENIED_SITES:
        v.append(
            "expected >= %d finishTar(deniedEntry(...)) sites (archive + dest), found %d"
            % (MIN_DENIED_SITES, len(sites))
        )
    for m in sites:
        if m.group(1) != "true":
            line = src.count("\n", 0, m.start()) + 1
            v.append(
                "FastioOffloadHandler.kt:%d denied path must finishTar with failed=true (found %s)"
                % (line, m.group(1))
            )
    return v


# ── [dv15-A2] A-04: the archive FILE itself must not be followed ─────────────
#
# GuestPathMapper leaves the LAST segment of a guest path unresolved on purpose
# (that is what keeps `rm <link>` working), so the archive path is a host path
# whose final component may be a symbolic link to ANY host file. Two opens make
# that reachable: Files.newOutputStream(archivePath) in tarCreateInto (write
# through the link — `--force` is what let it through) and the input stream the
# extract side reads with (read through the link). Both cross the guest/host
# boundary this handler exists to enforce.
#
# The decision point is "before the archive file is opened", and TWO shapes
# satisfy it: an explicit Files.isSymbolicLink(...) refusal, or an open that asks
# for LinkOption.NOFOLLOW_LINKS. Both are accepted (the check binds the decision,
# not a spelling) and it is required on BOTH paths — a guard on one half only is
# the pre-fix escape, half-closed. The explicit-refusal shape must also carry a
# structured payload, otherwise the link is noticed and then followed anyway.
#
# member_slice note: tar_fun_body() above cuts at "\nprivate fun ", which a class
# member (indented) never matches, so it returns the rest of the file. Slicing on
# the declaration's own indent keeps the scope tight — and scope is the point
# here, because "a guard exists somewhere in the file" is not the property under
# test.
ARCHIVE_LINK_GUARD = "Files.isSymbolicLink("
NOFOLLOW_OPEN_RE = re.compile(r"new(?:Input|Output)Stream\([^)\n]*NOFOLLOW_LINKS")
ARCHIVE_GUARD_PAYLOAD = "unsafe_archive_path"
LINK_CALL_RE = re.compile(r"Files\.isSymbolicLink\s*\(")


def member_body(code, name):
    """The braced body of `fun <name>(…)`, via the shared brace-balanced slicer."""
    span = function_body(code, name)
    if span is None or span[2] != "block":
        return None
    return code[span[0]:span[1]]


def _if_cond_span(code, pos):
    """(p_open, p_close) of the innermost `if (` whose CONDITION contains pos."""
    for m in reversed(list(re.finditer(r"\bif\s*\(", code[:pos + 1]))):
        p = m.end() - 1
        q = match_bracket(code, p)
        if q < 0:
            continue
        if p < pos <= q:
            return p, q
    return None


def _guarded_statement(code, close_paren):
    """(start, end) of the statement/block guarded by the `if (…)` ending here."""
    i = close_paren + 1
    n = len(code)
    while i < n and code[i] in " \t\r\n":
        i += 1
    if i < n and code[i] == "{":
        j = match_bracket(code, i)
        return (i, j + 1) if j > 0 else (i, n)
    return (i, expr_end(code, i))


def link_decision(code_body):
    """(ok, pos, why) — is there a LIVE symlink refusal in this member?

    Witness 09-2 wrote `if (false && Files.isSymbolicLink(archivePath))`: the
    token, its position and the `refuseLinkedArchive` call all survived, and
    nothing was ever refused. The decision is judged here, not the token: the
    condition must be able to be TRUE, the path must be reachable, and the
    guarded branch must actually stop the flow.
    """
    dead = None
    for m in LINK_CALL_RE.finditer(code_body):
        pos = m.start()
        span = _if_cond_span(code_body, pos)
        if span is None:
            dead = dead or (pos, "the result of Files.isSymbolicLink(…) is not used as an `if` "
                                 "condition — nothing is refused")
            continue
        cond = code_body[span[0] + 1:span[1]]
        if not guard_feasible(cond):
            dead = dead or (pos, "the symlink refusal is short-circuited (`%s` folds to false) — "
                                 "the guard keeps the token and the refuse call and decides "
                                 "nothing, so a link at the archive path is followed anyway"
                            % " ".join(cond.split())[:70])
            continue
        if not path_feasible(code_body, pos):
            dead = dead or (pos, "the symlink refusal sits in a branch that can never run — a "
                                 "link at the archive path is followed anyway")
            continue
        t0, t1 = _guarded_statement(code_body, span[1])
        if not re.search(r"\breturn\b|\bthrow\b", code_body[t0:t1]):
            dead = dead or (pos, "the symlink is noticed and then followed anyway — the guarded "
                                 "branch neither returns nor throws")
            continue
        return True, pos, ""
    return False, None, (dead[1] if dead else None)


def check_archive_open_guard(src):
    v = []
    shapes = {}
    code = code_view(src)
    for name, extra in (("tarCreateInto", None), ("tarExtractFrom", "openArchive")):
        body = member_body(code, name)
        if body is None:
            v.append("%s must exist in FastioOffloadHandler.kt (required function missing)" % name)
            continue
        refusal, _pos, why = link_decision(body)
        if refusal:
            shapes[name] = "refuse"
            continue
        if why is not None:
            shapes[name] = None
            v.append("%s: %s" % (name, why))
            continue
        if NOFOLLOW_OPEN_RE.search(body):
            shapes[name] = "nofollow"
            continue
        if extra:
            opener = member_body(code, extra)
            if opener is not None and NOFOLLOW_OPEN_RE.search(opener):
                shapes[name] = "nofollow"
                continue
        shapes[name] = None
        v.append(
            "%s opens the archive path without a NOFOLLOW-level guard — a symbolic link at the"
            " archive path would be followed (write-through on create, read-through on extract)"
            % name
        )
    if "refuse" in shapes.values() and ARCHIVE_GUARD_PAYLOAD not in src:
        v.append(
            "a symlink at the archive path is detected but not refused — no %s payload"
            % ARCHIVE_GUARD_PAYLOAD
        )
    return v

# ── [dv14-A2] writeRegular: open-before-header + short-payload refill ───────
#
# The header declares `size` bytes of payload. Two consequences:
#   1. If the entry cannot be OPENED, no header may be written — writeLink's
#      order (fail the read, then write the header). Writing the header first
#      declares a payload the stream never receives, and every later member
#      and the end blocks are swallowed as this entry's data.
#   2. If the copy dies MID-WAY, the short payload must be zero-filled back to
#      the declared `size` and the 512-byte boundary restored (GNU tar's
#      "file shrank, padding with zeros" contract), and the failure must still
#      land in out.errors — a truncated archive must not exit 0.
# The refill is guarded (runCatching): a sink that is itself failing (disk
# full) must not mask the read error that is the actual finding.
#
# Scope note: these criteria read ONLY FastioTar.kt. The gate's other view
# reads FastioOffloadHandler.kt, which is another work package's file — do not
# extend these checks onto it. The refill token matches `writeZeros(` or an
# inlined `sink.write(`; a refactor that renames the helper is a deliberate
# edit to this gate, not a silent pass.
def fn_body(code, name):
    """The braced body of `fun <name>(…)` — the shared, brace-balanced slicer.

    The old `tar_fun_body()` cut at the literal `"\nprivate fun "`, which a
    nested lambda or a spliced member silently swallowed; the old
    `handler_member()` did the same ride on indentation.
    """
    return member_body(code, name)


def _refill_amounts(seg):
    """[(pos, amount)] — every printed-byte count handed to a zero-fill."""
    out = []
    for m in re.finditer(r"\bwriteZeros\s*\(", seg):
        spans = call_args(seg, m.end() - 1)
        if len(spans) >= 2:
            out.append((m.start(), seg[spans[1][0]:spans[1][1]].strip()))
    for m in re.finditer(r"\bsink\s*\.\s*write\s*\(", seg):
        spans = call_args(seg, m.end() - 1)
        if len(spans) == 1:
            out.append((m.start(), seg[spans[0][0]:spans[0][1]].strip()))
    return out


def is_undelivered(amount):
    """Is the fill amount EXACTLY the undelivered payload (`size - written`)?"""
    e = strip_parens(amount)
    e = re.sub(r"\.\s*toLong\s*\(\s*\)\s*$", "", e)
    e = re.sub(r"\.\s*toInt\s*\(\s*\)\s*$", "", e)
    return re.sub(r"\s+", "", strip_parens(e)) == "size-written"


def check_write_regular(src):
    v = []
    code = code_view(src)
    body = fn_body(code, "writeRegular")
    if body is None:
        return ["writeRegular must exist in FastioTar.kt (required function missing)"]
    open_at = body.find("Files.newInputStream")
    header_at = body.find("writeHeader(")
    if open_at < 0:
        v.append("writeRegular must open the entry's input stream (Files.newInputStream missing)")
    if header_at < 0:
        v.append("writeRegular must write a ustar header")
    if open_at >= 0 and header_at >= 0 and open_at > header_at:
        v.append(
            "writeRegular writes the header BEFORE opening the input stream - an entry "
            "that cannot be read leaves a declared-but-unwritten payload in the stream"
        )
    copy_at = body.find("copy(")
    if copy_at < 0:
        v.append("writeRegular must stream the payload through copy(")
        return v
    catch_at = body.find("catch (", copy_at)
    if catch_at < 0:
        v.append("writeRegular must guard the payload copy with a catch")
        return v
    brace_at = body.find("{", catch_at)
    close = match_bracket(body, brace_at) if brace_at >= 0 else -1
    if close < 0:
        v.append("writeRegular's copy-failure catch cannot be sliced - re-point this rule "
                 "before trusting it")
        return v
    catch_seg = body[catch_at: close + 1]
    if "out.errors.add" not in catch_seg:
        v.append("the copy-failure catch must still record out.errors (truncation must not exit 0)")
    amounts = _refill_amounts(catch_seg)
    if not amounts:
        v.append("the copy-failure catch must zero-fill the short payload back to the declared size")
    else:
        # [batch-7 09-3] HOW MUCH matters, and it must be the whole amount,
        # not a substring of it: `writeZeros(sink, (size - written) * 0L)` keeps
        # `size - written` visible while writing nothing, so the stream stays
        # short of the size the header already declared.
        if not any(is_undelivered(a) for _pos, a in amounts):
            v.append(
                "the copy-failure refill must restore the UNDELIVERED payload — the fill "
                "amount (%s) is not `size - written`; a constant or a scaled expression "
                "(`writeZeros(sink, (size - written) * 0L)`) restores nothing, so the stream "
                "stays short of the size the header already declared and every later member "
                "is read as payload"
                % (", ".join(repr(a) for _pos, a in amounts))
            )
        if "boundaryPad(" not in catch_seg:
            v.append(
                "the copy-failure refill must also restore the 512-byte boundary "
                "(`writeZeros(sink, boundaryPad(size).toLong())`) - without it the next "
                "member's header starts mid-block and the archive is unreadable"
            )
        if "runCatching" not in catch_seg:
            v.append("the copy-failure refill must be guarded so a failing sink cannot mask the read error")
    return v


# ── [dv16-A FLAKE] the create traversal must emit members in a decided order ─
#
# `FastioTarTest > a relative argument produces relative member names…` went red
# in EIGHT separate CI runs on nothing but member ORDER: `Files.walkFileTree`
# hands the entries over in the directory stream's own order (ext4/f2fs hash
# order), so the same tree produced different archive bytes between runs. The
# decided fix (user, 2026-10-20) is production-side: list each level, sort it,
# then visit in order — one level at a time, so the streaming contract survives
# (header before its members; no whole-tree buffer against
# DEFAULT_MAX_ENTRIES = 2,000,000).
#
# Two decision points are pinned, both bound to the DECISION and not a spelling:
#   (a) the create side does not walk with Files.walkFileTree;
#   (b) the traversal orders the entries of each level before visiting them
#       (`sortedBy` / `sorted()` / `sortedWith` / `sortedByDescending` … all
#       count — what must exist is "a sort applied to the listing").
# Scoped to the create members only (tarCreate + writeTree): FastioTar.kt also
# has an extract side, and extract order is the archive's own, not ours.
CREATE_WALK = "Files.walkFileTree("
LISTING_RE = re.compile(r"newDirectoryStream\(|Files\.list\(")
# Bound to the DECISION (a sort is applied to the listing), not to a spelling:
# `sortedBy { … }` (trailing lambda) and `sorted()` / `sortedWith(cmp)` all count.
SORT_RE = re.compile(r"\.sorted[A-Za-z]*\s*[({]")


DISCARD_SCOPES = ("also", "apply", "onEach", "forEach")


def _listing_init(tree):
    """(pos, initializer) of the local whose initializer lists the directory."""
    for m in re.finditer(r"(?m)^[ \t]*(?:val|var)\s+[A-Za-z_]\w*\s*=", tree):
        init = tree[m.end():expr_end(tree, m.end())]
        if LISTING_RE.search(init):
            return m.start(), init
    return None, None


def sort_result_survives(init):
    """(ok, sorts, dead) — is a sort's VALUE the value of this expression?

    [batch-7 09-4] `.also { it.sortedBy { … } }` runs the sort and hands back
    the receiver, so the old "a sort appears in writeTree" test stayed green
    while the members came out in directory-stream order again. A sort whose
    position falls inside a discarding lambda (`also` / `apply` / `onEach` /
    `forEach`) contributes nothing to the listing.
    """
    sorts = [m.start() for m in SORT_RE.finditer(init)]
    discards = [(o, e) for name, _st, o, e in scope_calls(init) if name in DISCARD_SCOPES]
    dead = [p for p in sorts if any(o < p < e for o, e in discards)]
    return [p for p in sorts if p not in dead], sorts, dead


def check_create_traversal(src):
    v = []
    code = code_view(src)
    scope_parts = []
    for name in ("tarCreate", "writeTree"):
        body = fn_body(code, name)
        if body is None:
            v.append("the create traversal cannot be sliced (%s is gone) — re-point this rule "
                     "before trusting it" % name)
            return v
        scope_parts.append(body)
    if CREATE_WALK in "\n".join(scope_parts):
        v.append("the create traversal walks with Files.walkFileTree — member order is then "
                  "whatever the directory stream hands over, so the same tree archives differently "
                  "between runs (the FastioTarTest order flake)")
    tree = fn_body(code, "writeTree")
    if not LISTING_RE.search(tree):
        v.append("writeTree no longer lists the directory itself — re-read this rule before "
                 "trusting the order criterion")
        return v
    _pos, init = _listing_init(tree)
    if init is None:
        v.append("writeTree lists the directory but never binds the listing to a local — "
                 "re-point this rule before trusting the order criterion")
        return v
    live, sorts, dead = sort_result_survives(init)
    if not sorts:
        v.append("writeTree never orders the entries of a level — the archive member order is "
                 "undecided again")
    elif not live:
        v.append("writeTree sorts the entries and then DISCARDS the sorted list — a sort inside "
                 "`.also { it.sortedBy … }` / `.apply {}` / `.onEach {}` hands back the unsorted "
                 "receiver, so members come out in directory-stream order again")
    return v

# ── [dv22-A2] §1042-A-02: the top-level root loop is a walk like any other ───
#
# `writeTree` has always charged the entry budget, but the loop around it did
# not, in three ways that only bite when a ROOT is not a directory:
#   1. no ceiling — `writeRegular` / `writeLink` on a top-level root bumped
#      `visited` past `maxEntries` and nothing ever set `truncated`;
#   2. `writeTree`'s `false` ("budget exhausted, stop the WHOLE traversal")
#      was discarded, so roots after an exhausted tree were still archived;
#   3. a top-level FIFO/socket went straight into `writeRegular`, whose
#      `Files.newInputStream` blocks forever on a pipe with no writer — the
#      worker thread is dead for the rest of the request.
#
# Three decision points are pinned, each against the DECISION:
#   (a) a budget comparison on `out.visited` exists in the loop and fires
#       BEFORE that iteration's first write call, setting `truncated` and
#       `break`ing;
#   (b) the `writeTree` return value is consumed with its polarity
#       (`if (!writeTree(…)`) — a bare statement is the pre-fix shape;
#   (c) the top-level non-directory write is gated on `Files.isRegularFile`
#       and the non-regular residue is counted (`skippedSpecial++`), matching
#       the `writeTree` child branch and tarCreate's own KDoc.
#
# The gate must NOT charge: `writeRegular` / `writeLink` / `writeTree` each do
# their own `visited++`, so a `charge(` here would count one entry twice and
# stop the walk at half the budget. `charge(` inside tarCreate is therefore a
# violation, not an accepted spelling — that asymmetry (charge here, charge
# there) is exactly what produces an off-by-N budget nobody can reason about.
TOPLEVEL_GATE_BLOCK_RE = re.compile(
    r"if\s*\([^)\n]*\.visited\s*>=\s*maxEntries\s*\)\s*\{(.*?)\}", re.DOTALL
)
TOPLEVEL_WRITE_CALLS = ("writeLink(", "writeRegular(", "writeTree(")
TOPLEVEL_TREE_CONSUMED_RE = re.compile(r"!\s*writeTree\(|writeTree\([^;\n]*?\)\s*==\s*false")
TOPLEVEL_REGULAR_GATE = "Files.isRegularFile("
TOPLEVEL_SKIP_COUNT = "skippedSpecial++"

def check_toplevel_roots(src):
    v = []
    # comments AND string contents blanked by the shared lexer (the pre-fix
    # shape was first reported by a comment that SAID `charge()` while the real
    # call lived in writeTree — 5.4 of the batch-7 family holes)
    body = fn_body(code_view(src), "tarCreate")
    if body is None:
        return ["tarCreate must exist in FastioTar.kt (required function missing)"]

    # (a) the budget gate: exists, stops the walk, and precedes the writes.
    gate = TOPLEVEL_GATE_BLOCK_RE.search(body)
    if gate is None:
        v.append(
            "tarCreate's root loop has no entry-budget gate — a top-level root (file or "
            "symlink) is written past maxEntries and truncated is never set"
        )
    else:
        block = gate.group(1)
        if "truncated = true" not in block:
            v.append("the root-loop budget gate must set out.truncated (a silent over-budget archive)")
        if not re.search(r"\bbreak\b", block):
            v.append(
                "the root-loop budget gate must break the loop — continuing keeps writing the "
                "remaining roots after the budget is gone"
            )
        first_write = min((body.find(w) for w in TOPLEVEL_WRITE_CALLS if body.find(w) >= 0),
                          default=-1)
        if 0 <= first_write < gate.start():
            v.append(
                "the root-loop budget gate sits AFTER the first write call — the root that "
                "exhausts the budget is still archived"
            )
    # The gate compares; the writers charge. Charging both double-counts.
    if re.search(r"\bcharge\(", body):
        v.append(
            "tarCreate charges the budget itself — writeRegular/writeLink/writeTree already "
            "do `visited++`, so the walk stops at half maxEntries (and the gate must only compare)"
        )

    # (b) writeTree's false means "stop the whole traversal".
    tree_calls = [m for m in re.finditer(r"writeTree\(", body)]
    if not tree_calls:
        v.append("tarCreate no longer calls writeTree — re-point this rule before trusting it")
    for m in tree_calls:
        pre = body[max(0, m.start() - 8):m.start()]
        consumed = re.search(r"!\s*$", pre) is not None
        if not consumed:
            consumed = TOPLEVEL_TREE_CONSUMED_RE.search(body, m.start() - 1) is not None
        if not consumed:
            v.append(
                "tarCreate discards writeTree's return value — `false` means the budget ran out "
                "and the remaining roots must NOT be archived"
            )
            break
        # [wide-1024] Consuming the boolean is not obeying it: `if (!writeTree(…))
        # Unit` keeps the `!` and the parens while dropping the only thing the
        # caller could do about it, so the remaining roots are still archived.
        seg_start = body.rfind("\n", 0, m.start()) + 1
        seg_end = body.find("\n", m.start())
        seg = body[seg_start: seg_end if seg_end > 0 else len(body)]
        if seg.rstrip().endswith("{"):
            cb = match_bracket(body, body.find("{", m.start()))
            if cb > 0:
                seg = body[seg_start: cb]
        if not re.search(r"\b(break|return)\b", seg):
            v.append(
                "tarCreate consumes writeTree's return value but does not STOP on it — "
                "`if (!writeTree(…)) Unit` keeps the check and archives the remaining roots "
                "after the budget is gone"
            )
            break

    # (c) a top-level non-directory is only read when it IS a regular file.
    regular_at = body.find(TOPLEVEL_REGULAR_GATE)
    write_regular_at = body.find("writeRegular(")
    if regular_at < 0:
        v.append(
            "tarCreate writes a top-level non-directory without checking Files.isRegularFile — "
            "a FIFO has no writer, so newInputStream blocks the worker thread forever"
        )
    elif write_regular_at >= 0 and regular_at > write_regular_at:
        v.append("the isRegularFile gate sits AFTER writeRegular — it cannot prevent the open")
    if TOPLEVEL_SKIP_COUNT not in body:
        v.append(
            "a top-level special file (FIFO/socket/device) is not counted in skippedSpecial — "
            "the archive silently lacks it, against tarCreate's own KDoc"
        )
    return v

def run(root):
    violations = []
    tar_path = os.path.join(root, TAR_REL)
    handler_path = os.path.join(root, HANDLER_REL)
    if not os.path.isfile(tar_path):
        return 2
    with open(tar_path, encoding="utf-8") as f:
        tar_src = f.read()
    violations += check_tar(tar_src)
    violations += check_write_regular(tar_src)
    violations += check_create_traversal(tar_src)
    violations += check_toplevel_roots(tar_src)
    if not os.path.isfile(handler_path):
        return 2
    with open(handler_path, encoding="utf-8") as f:
        handler_src = f.read()
    violations += check_handler(handler_src)
    violations += check_archive_open_guard(handler_src)
    search_path = os.path.join(root, SEARCH_REL)
    copy_path = os.path.join(root, COPY_REL)
    if not (os.path.isfile(search_path) and os.path.isfile(copy_path)):
        return 2
    with open(search_path, encoding="utf-8") as f:
        search_src = f.read()
    with open(copy_path, encoding="utf-8") as f:
        copy_src = f.read()
    violations += check_special_file_guards(search_src, copy_src)
    if violations:
        for v in violations:
            print("VIOLATION " + v)
        print("GATE=RED fastio_tar_bounds")
        return 1
    print("GATE=GREEN fastio_tar_bounds")
    return 0


# ── [dv23-A6] §1043-A-07: the engines that OPEN an entry must ask first ──────
#
# `FastioTar`'s create walk learned this in §1042-A-02 (a top-level FIFO went
# straight into writeRegular and blocked a worker thread forever). The two
# sibling engines were never gated, and `find` mislabelled the same entries:
#   (a) grepTree.visitFile opens the entry (grepFile → Files.newInputStream)
#       without asking whether it is a regular file;
#   (b) copyTree.visitFile copies it (Files.copy) the same way;
#   (c) findTree.visitFile types every entry that is not a link as `f`, while
#       the help text says `f` means "regular file" — so `find -type f` listed
#       pipes and sockets the guest cannot open.
# Measured on this JVM (probe, 2026-10-22): `Files.newInputStream` and
# `Files.copy` on a WRITER-LESS FIFO never return — open(2) blocks with no
# timeout — while a unix socket raises ENXIO. Two hits exhaust the 2-thread
# offload pool and every other tool call stalls.
#
# Each criterion binds the DECISION ("is this entry a regular file?") and not a
# spelling: `when { attrs.isRegularFile -> 'f' }`, `if (!attrs.isRegularFile)`,
# `if (attrs.isOther)`, `Files.isRegularFile(path, NOFOLLOW_LINKS)` all count.
# The decision must sit BEFORE the open/copy/consider call in the same
# visitFile block — a guard after it cannot prevent the open.
#
# Scope: each engine is sliced on its own. A guard in `grepTree` must not cover
# for a missing one in `copyTree` or `findTree` (that symmetry failure is the
# whole finding). Comments are stripped before slicing for the same reason
# §1042-A-02 strips them: the fix's own KDoc names isRegularFile, and a gate
# that reads prose would stay green after the guard was deleted.
SEARCH_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioSearch.kt"
COPY_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioCopy.kt"

SPECIAL_GUARD_TOKENS = ("isRegularFile", "isOther")
# What the guard has to precede, per engine (the call that touches the entry).
SPECIAL_OPEN_CALLS = {
    "grepTree": ("grepFile(", "Files.newInputStream"),
    "findTree": ("consider(",),
    "copyTree": ("Files.copy(",),
}
# Where the answer has to sit, relative to that call:
#   "before"      - its own statement ahead of an open/copy (grep, cp);
#   "in-argument" - the TYPE handed to `consider(`, because find never opens
#                   anything: its whole defect is the label it passes on.
SPECIAL_GUARD_POSITION = {"grepTree": "before", "findTree": "in-argument", "copyTree": "before"}
# [wide-1024] A literal in front of the decision makes the guard dead code.


def kotlin_top_member(src, name):
    """Slice a COLUMN-0 declaration (`fun name(`) up to the next column-0 line.

    The repo keeps every top-level declaration at indent 0 and every body line
    indented, so indentation is what delimits the member — the same shape the
    handler's `handler_member` relies on, without its indent-vs-keyword dance.
    """
    start = src.find("fun %s(" % name)
    if start < 0:
        return None
    line_start = src.rfind("\n", 0, start) + 1
    lines = src[line_start:].split("\n")
    out = [lines[0]]
    for line in lines[1:]:
        # A multi-line signature closes with `) {` at column 0 — that is still
        # part of the member, not a new declaration.
        if line and not line[0].isspace() and not line.startswith(")"):
            break
        out.append(line)
    return "\n".join(out)


def visit_file_block(src, engine):
    """The `override fun visitFile(` block of [engine], or None if unsliceable."""
    body = kotlin_top_member(src, engine)
    if body is None:
        return None
    start = body.find("override fun visitFile(")
    if start < 0:
        return None
    nxt = body.find("override fun ", start + len("override fun visitFile("))
    return body[start:] if nxt < 0 else body[start:nxt]


WHEN_OPEN_RE = re.compile(r"\bwhen\s*(?:\(|\{)")


def _when_label_at(code, pos):
    """The `when` branch label whose span contains pos (findTree's `-type` map).

    A label is the condition under which its branch body runs, so both
    "pos inside the label" and "pos inside the body" resolve to that label.
    """
    for m in reversed(list(WHEN_OPEN_RE.finditer(code[:pos + 1]))):
        if m.group(0).endswith("("):
            q = match_bracket(code, m.end() - 1)
            if q < 0:
                continue
            b = q + 1
            while b < len(code) and code[b] in " \t\r\n":
                b += 1
        else:
            b = m.end() - 1
        if b >= len(code) or code[b] != "{":
            continue
        close = match_bracket(code, b)
        if close < 0 or not (b < pos < close):
            continue
        seg_start = max(code.rfind("\n", b, pos), code.rfind("{", b, pos),
                        code.rfind(";", b, pos))
        arrow = code.find("->", seg_start, pos)
        if arrow < 0:
            # pos sits inside the LABEL itself — the arrow comes after it
            arrow = code.find("->", pos, close)
            if arrow < 0:
                return None
        label = code[seg_start + 1:arrow].strip()
        return label or None
    return None


def guard_cond_at(code, pos):
    """The condition that decides whether the statement at `pos` runs.

    Covers the three shapes the engines use: the token inside a `when` branch
    LABEL (`attrs.isRegularFile -> 'f'`), the token inside an `if`/`while`
    CONDITION, and the token inside the guarded body.
    """
    conds = [c for _kind, c in enclosing_conditions(code, pos)]
    if conds:
        return conds[-1]
    for m in reversed(list(re.finditer(r"\b(?:if|while)\s*\(", code[:pos + 1]))):
        p = m.end() - 1
        q = match_bracket(code, p)
        if q < 0:
            continue
        if p < pos <= q:
            return code[p + 1:q]
    return _when_label_at(code, pos)


def check_special_file_guards(search_src, copy_src):
    v = []
    engines = (
        ("FastioSearch.kt", search_src, "grepTree",
         "grepTree opens every non-link entry"),
        ("FastioSearch.kt", search_src, "findTree",
         "findTree types every non-link entry as a regular file"),
        ("FastioCopy.kt", copy_src, "copyTree",
         "copyTree copies every non-link entry"),
    )
    for fname, raw, engine, why in engines:
        block = visit_file_block(code_view(raw), engine)
        if block is None:
            v.append("%s: %s's visitFile cannot be sliced - re-point this rule before "
                     "trusting it" % (fname, engine))
            continue
        calls = SPECIAL_OPEN_CALLS[engine]
        open_at = min((block.find(t) for t in calls if block.find(t) >= 0), default=-1)
        if open_at < 0:
            v.append("%s: %s no longer calls %s - re-point this rule before trusting it"
                     % (fname, engine, " / ".join(calls)))
            continue
        mode = SPECIAL_GUARD_POSITION[engine]
        anywhere = min((block.find(t) for t in SPECIAL_GUARD_TOKENS if block.find(t) >= 0),
                       default=-1)
        scope = block[:open_at] if mode == "before" else block[open_at:]
        tok_idx = min((scope.find(t) for t in SPECIAL_GUARD_TOKENS if scope.find(t) >= 0),
                      default=-1)
        guard_at = tok_idx
        if guard_at < 0 and mode == "before" and anywhere >= 0:
            v.append("%s: %s: the regular-file test sits AFTER the call that touches the entry - "
                     "it cannot prevent the open" % (fname, engine))
        elif guard_at < 0:
            tail = (
                "WITHOUT asking whether it is a regular file - and the help text defines `f` as "
                "a regular file, so `-type f` lists pipes and sockets the guest cannot open"
                if mode == "in-argument" else
                "WITHOUT asking whether it is a regular file - a FIFO with no writer blocks "
                "open(2) forever and kills an offload worker thread"
            )
            v.append("%s: %s: %s %s" % (fname, engine, why, tail))
        else:
            # [batch-7 09-5] Position and spelling are not the decision. The old
            # rule only caught a literal on the LEFT (`false && …`); putting the
            # same literal on the right (`… && false`) kept the token in exactly
            # the right place and decided nothing. The condition is folded here:
            # a guard that constant-folds decides nothing, whichever side the
            # literal sits on, and a guard in a dead outer branch never runs.
            cond = guard_cond_at(scope, tok_idx)
            if cond is not None:
                folded = const_bool(cond)
                if folded is not None:
                    v.append(
                        "%s: %s: the regular-file guard is short-circuited (`%s` folds to %s) — "
                        "the condition keeps the token and decides nothing, so a writer-less "
                        "FIFO still reaches open(2) and blocks an offload worker thread"
                        % (fname, engine, " ".join(cond.split())[:60], folded)
                    )
                    continue
                if not guard_feasible(cond):
                    v.append(
                        "%s: %s: the regular-file guard can never be true (`%s`) — every entry "
                        "is skipped, or the branch that opens it runs regardless"
                        % (fname, engine, " ".join(cond.split())[:60])
                    )
                    continue
            if not path_feasible(scope, tok_idx):
                v.append(
                    "%s: %s: the regular-file guard sits in a branch that can never run — the "
                    "open still happens without asking whether the entry is a regular file"
                    % (fname, engine)
                )
    return v


# ── selftest fixtures ────────────────────────────────────────────────────────

GOOD_TAR = """
internal fun splitUstarName(name: String): Pair<String, String>? {
    if (utf8Length(name) <= NAME_LENGTH - 1) return "" to name
    var best = -1
    for (i in name.indices) {
        if (name[i] != '/') continue
        val prefix = name.substring(0, i)
        val rest = name.substring(i + 1)
        if (rest.isEmpty()) continue
        if (utf8Length(prefix) <= PREFIX_LENGTH - 1 && utf8Length(rest) <= NAME_LENGTH - 1) best = i
    }
    return null
}

private fun putText(block: ByteArray, offset: Int, length: Int, value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val count = minOf(bytes.size, length - 1)
    System.arraycopy(bytes, 0, block, offset, count)
}
"""

# pre-fix shape: raw field sizes (100 / 155) — one byte is silently lost.
BAD_TAR_STALE = GOOD_TAR.replace(
    "if (utf8Length(name) <= NAME_LENGTH - 1) return \"\" to name",
    "if (utf8Length(name) <= NAME_LENGTH) return \"\" to name",
)

# half-fix shape: name gate fixed, prefix gate forgotten.
BAD_TAR_HALF_PREFIX = GOOD_TAR.replace(
    "utf8Length(prefix) <= PREFIX_LENGTH - 1 &&", "utf8Length(prefix) <= PREFIX_LENGTH &&"
)
BAD_TAR_HALF_REST = GOOD_TAR.replace(
    "&& utf8Length(rest) <= NAME_LENGTH - 1)", "&& utf8Length(rest) <= NAME_LENGTH)"
)

GOOD_HANDLER = """
private fun handleTar(request: NativeOffloadRequest): NativeOffloadResult {
    val archiveResolution = GuestPathMapper.resolve(archiveRaw, request.cwd, bindings)
    if (archiveResolution is GuestPathMapper.Resolution.Denied) {
        return finishTar(deniedEntry(archiveRaw, archiveResolution), true, startedNs, args)
    }
    return if (create) {
        tarCreateInto(entry, archivePath, rest, bindings, request, gzip, force, startedNs, args)
    } else {
        tarExtractFrom(entry, archivePath, args, bindings, request, gzip, force, startedNs)
    }
}

private fun tarCreateInto(entry: JSONObject, archivePath: Path): NativeOffloadResult {
    if (Files.isSymbolicLink(archivePath)) {
        return refuseLinkedArchive(entry, "write", startedNs, args)
    }
    val raw = Files.newOutputStream(archivePath)
    return finishTar(entry, outcome.errors.isNotEmpty(), startedNs, args)
}

private fun tarExtractFrom(entry: JSONObject, archivePath: Path): NativeOffloadResult {
    val destResolution = GuestPathMapper.resolve(destRaw, request.cwd, bindings)
    if (destResolution is GuestPathMapper.Resolution.Denied) {
        return finishTar(deniedEntry(destRaw, destResolution), true, startedNs, args)
    }
    if (Files.isSymbolicLink(archivePath)) {
        return refuseLinkedArchive(entry, "read", startedNs, args)
    }
    return finishTar(entry, outcome.errors.isNotEmpty(), startedNs, args)
}

private fun refuseLinkedArchive(entry: JSONObject, verb: String, startedNs: Long, args: OffloadArgs): NativeOffloadResult =
    finishTarWith(entry.put("error", "unsafe_archive_path"), 2, startedNs, args)
"""

# [dv15-A2] A-04, the OTHER accepted shape: no explicit isSymbolicLink() check —
# the opens themselves ask for NOFOLLOW_LINKS, and the read side's open lives one
# call deeper (openArchive). If this fixture went red, the criterion would be
# pinning a spelling instead of the decision.
GOOD_HANDLER_NOFOLLOW = """
private fun tarCreateInto(entry: JSONObject, archivePath: Path): NativeOffloadResult {
    val raw = Files.newOutputStream(archivePath, LinkOption.NOFOLLOW_LINKS)
    return finishTar(entry, outcome.errors.isNotEmpty(), startedNs, args)
}

private fun tarExtractFrom(entry: JSONObject, archivePath: Path): NativeOffloadResult {
    return openArchive(archivePath, gzip).use { tarExtract(it, destPath, force, budget, outcome) }
}

private fun openArchive(path: Path, gzip: Boolean): java.io.InputStream {
    val raw = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
    return if (gzip) GZIPInputStream(raw) else raw
}
"""

# pre-fix shape: the archive-denied branch lies with exit 0.
BAD_HANDLER_FALSE = GOOD_HANDLER.replace(
    "return finishTar(deniedEntry(archiveRaw, archiveResolution), true, startedNs, args)",
    "return finishTar(deniedEntry(archiveRaw, archiveResolution), false, startedNs, args)",
)

# ── [dv14-A2] writeRegular fixtures ──────────────────────────────────────────

# compliant shape: open BEFORE the header, and the copy-failure catch both
# records the error and zero-fills the short payload under a guard.
GOOD_REGULAR = """
internal fun writeRegular(
    sink: OutputStream,
    name: String,
    file: Path,
    out: TarCreateOutcome,
    input: InputStream? = null,
) {
    val size = try {
        Files.size(file)
    } catch (e: Exception) {
        out.errors.add("$name: ${e.message}")
        return
    }
    val stream = input ?: try {
        Files.newInputStream(file)
    } catch (e: Exception) {
        out.errors.add("$name: ${e.message}")
        return
    }
    stream.use {
        writeHeader(sink, header(name, '0', size, modeOf(file), mtimeOf(file), ""))
        out.visited++
        var written = 0L
        val counted = object : OutputStream() {
            override fun write(b: Int) { sink.write(b); written += 1 }
            override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len); written += len }
        }
        try {
            copy(stream, counted, size)
        } catch (e: Exception) {
            out.errors.add("$name: ${e.message}")
            runCatching {
                writeZeros(sink, size - written)
                writeZeros(sink, boundaryPad(size).toLong())
            }
            return
        }
        writeZeros(sink, boundaryPad(size).toLong())
        out.files++
        out.bytes += size
    }
}
"""

# pre-fix shape: the header goes out before the stream is opened — an entry
# that cannot be read leaves a declared-but-unwritten payload in the stream.
BAD_REGULAR_OLD_ORDER = """
internal fun writeRegular(sink: OutputStream, name: String, file: Path, out: TarCreateOutcome) {
    val size = try {
        Files.size(file)
    } catch (e: Exception) {
        out.errors.add("$name: ${e.message}")
        return
    }
    writeHeader(sink, header(name, '0', size, modeOf(file), mtimeOf(file), ""))
    out.visited++
    try {
        Files.newInputStream(file).use { s -> copy(s, sink, size) }
    } catch (e: Exception) {
        out.errors.add("$name: ${e.message}")
        return
    }
    val pad = ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE).toInt()
    if (pad > 0) sink.write(ByteArray(pad))
    out.files++
    out.bytes += size
}
"""

# half-fix: open-before-header restored, but the copy-failure catch swallows
# without refilling the declared payload — the archive after this member is
# still garbage to a real reader.
# [wide-1024] refill present, amount constant: the header declared `size`, the
# catch writes a fixed 0 bytes, and every later member is read as payload.
BAD_REGULAR_CONST_REFILL = GOOD_REGULAR.replace(
    "                writeZeros(sink, size - written)\n"
    "                writeZeros(sink, boundaryPad(size).toLong())\n",
    "                writeZeros(sink, 0L)\n",
)
assert BAD_REGULAR_CONST_REFILL != GOOD_REGULAR

BAD_REGULAR_NO_REFILL = GOOD_REGULAR.replace(
    """            runCatching {
                writeZeros(sink, size - written)
                writeZeros(sink, boundaryPad(size).toLong())
            }
            return""",
    "            return",
)

# unguarded refill: the short payload is filled, but a sink that is itself
# failing (disk full) would mask the read error with its own IOException.
BAD_REGULAR_UNGUARDED = GOOD_REGULAR.replace(
    """            runCatching {
                writeZeros(sink, size - written)
                writeZeros(sink, boundaryPad(size).toLong())
            }
            return""",
    """            writeZeros(sink, size - written)
            writeZeros(sink, boundaryPad(size).toLong())
            return""",
)

# silent truncation: the payload is refilled but the read error never lands
# in out.errors, so the handler reports exit 0 for a mangled archive.
BAD_REGULAR_SILENT = GOOD_REGULAR.replace(
    '            out.errors.add("$name: ${e.message}")\n            runCatching {',
    "            runCatching {",
)


# ── [dv16-A FLAKE] create-side ordering fixtures ────────────────────────────
GOOD_TREE = """
internal fun tarCreate(
    sink: OutputStream,
    roots: List<Pair<Path, String>>,
    maxEntries: Long,
    out: TarCreateOutcome,
) {
    for ((path, memberPrefix) in roots) {
        writeTree(sink, path, tarMemberName(memberPrefix), path, maxEntries, out)
    }
}

private fun writeTree(
    sink: OutputStream,
    dir: Path,
    rootName: String,
    root: Path,
    maxEntries: Long,
    out: TarCreateOutcome,
): Boolean {
    val children = Files.newDirectoryStream(dir).use { stream ->
        stream.toList().sortedBy { it.fileName.toString() }
    }
    for (child in children) {
        if (!writeTree(sink, child, rootName, root, maxEntries, out)) return false
    }
    return true
}
"""

# the pre-fix shape: the walker decides the order (directory hash order).
BAD_TREE_WALK = GOOD_TREE.replace(
    "    val children = Files.newDirectoryStream(dir).use { stream ->\n"
    "        stream.toList().sortedBy { it.fileName.toString() }\n"
    "    }",
    "    val children = ArrayList<Path>()\n"
    "    Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {})",
)

# listed, but never ordered: the members come out in stream order again.
BAD_TREE_UNSORTED = GOOD_TREE.replace(
    "        stream.toList().sortedBy { it.fileName.toString() }",
    "        stream.toList()",
)

# marker gone: the rule must fail closed, not silently pass.
BAD_TREE_RENAMED = GOOD_TREE.replace("private fun writeTree(", "private fun walkDir(")

# ── [dv22-A2] §1042-A-02: top-level root loop fixtures ──────────────────────
GATE_BLOCK = """        if (out.visited >= maxEntries) {
            out.truncated = true
            break
        }
"""

GOOD_TOPLEVEL = """
internal fun tarCreate(
    sink: OutputStream,
    roots: List<Pair<Path, String>>,
    maxEntries: Long,
    out: TarCreateOutcome,
) {
    for ((path, memberPrefix) in roots) {
        val name = tarMemberName(memberPrefix)
        if (out.visited >= maxEntries) {
            out.truncated = true
            break
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            out.errors.add("$memberPrefix: not found")
            continue
        }
        if (Files.isSymbolicLink(path)) {
            writeLink(sink, name, path, out)
            continue
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                out.skippedSpecial++
                if (out.skippedNames.size < MAX_REPORTED_SKIPS) out.skippedNames.add(name)
                continue
            }
            writeRegular(sink, name, path, out)
            continue
        }
        try {
            if (!writeTree(sink, path, name, path, maxEntries, out)) break
        } catch (e: Exception) {
            out.errors.add("$memberPrefix: ${e.message}")
        }
    }
    sink.write(ByteArray(BLOCK_SIZE * 2))
    sink.flush()
}

private fun writeTree(sink: OutputStream, dir: Path): Boolean = true
"""

# pre-fix shape (§1042-A-02): no gate at all — roots are written past the budget.
BAD_TOPLEVEL_NO_GATE = GOOD_TOPLEVEL.replace(GATE_BLOCK, "")

# [wide-1024] the return value is consumed with the right polarity and then
# thrown away: `if (!writeTree(…)) Unit` keeps the check, drops the stop.
BAD_TOPLEVEL_UNIT_BRANCH = GOOD_TOPLEVEL.replace(
    "if (!writeTree(sink, path, name, path, maxEntries, out)) break",
    "if (!writeTree(sink, path, name, path, maxEntries, out)) Unit")

# half-fix: the gate exists but reports nothing.
BAD_TOPLEVEL_NO_TRUNCATED = GOOD_TOPLEVEL.replace(
    "            out.truncated = true\n            break", "            break"
)

# half-fix: the gate keeps SCANNING instead of stopping — later roots still write.
BAD_TOPLEVEL_CONTINUE = GOOD_TOPLEVEL.replace(
    "            out.truncated = true\n            break", "            out.truncated = true\n            continue"
)

# half-fix: the gate lives after the first writer, so the budget-exhausting
# root itself is still archived.
BAD_TOPLEVEL_GATE_AFTER_WRITE = GOOD_TOPLEVEL.replace(GATE_BLOCK, "").replace(
    "            writeLink(sink, name, path, out)\n            continue",
    "            writeLink(sink, name, path, out)\n            continue\n"
    "        if (out.visited >= maxEntries) {\n"
    "            out.truncated = true\n"
    "            break\n"
    "        }",
)

# double-charge shape: the gate charges, the writer charges again.
BAD_TOPLEVEL_CHARGES = GOOD_TOPLEVEL.replace(
    GATE_BLOCK, "        if (charge(out, maxEntries)) break\n"
)

# pre-fix shape: writeTree's budget-exhausted `false` is discarded.
BAD_TOPLEVEL_IGNORED_RETURN = GOOD_TOPLEVEL.replace(
    "            if (!writeTree(sink, path, name, path, maxEntries, out)) break",
    "            writeTree(sink, path, name, path, maxEntries, out)",
)

# pre-fix shape: a top-level FIFO goes straight into writeRegular.
BAD_TOPLEVEL_UNCHECKED_SPECIAL = GOOD_TOPLEVEL.replace(
    """            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                out.skippedSpecial++
                if (out.skippedNames.size < MAX_REPORTED_SKIPS) out.skippedNames.add(name)
                continue
            }
""", "")


# ── [dv23-A6] §1043-A-07 fixtures ───────────────────────────────────────────
GOOD_SEARCH_FIX = """
internal fun findTree(
    root: Path,
    out: FindOutcome,
) {
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    consider(dir, 'd', 0L)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    out.files++
                    consider(
                        file,
                        when {
                            attrs.isSymbolicLink -> 'l'
                            attrs.isRegularFile -> 'f'
                            else -> 'o'
                        },
                        attrs.size(),
                    )
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (e: Exception) {
        out.errors.add("$root: ${e.message}")
    }
}

internal fun grepTree(
    root: Path,
    out: GrepOutcome,
) {
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) {
                        out.filesSkippedLink++
                        return FileVisitResult.CONTINUE
                    }
                    if (!attrs.isRegularFile) {
                        out.filesSkippedSpecial++
                        return FileVisitResult.CONTINUE
                    }
                    grepFile(file, guestPathFor(root, "/ws", file), regex, limit, out)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (e: Exception) {
        out.errors.add("$root: ${e.message}")
    }
}
"""

GOOD_COPY_FIX = """
internal fun copyTree(src: Path, dst: Path, force: Boolean, out: CopyOutcome) {
    try {
        Files.walkFileTree(
            src,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val target = targetFor(file)
                    try {
                        if (!attrs.isRegularFile && !attrs.isSymbolicLink) {
                            out.errors.add("$file: special file, not copied")
                            return FileVisitResult.CONTINUE
                        }
                        if (attrs.isSymbolicLink) {
                            Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                        } else {
                            Files.copy(file, target)
                        }
                        out.files++
                    } catch (e: Exception) {
                        out.errors.add("$target: ${e.message}")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    out.errors.add("$file: ${exc.message}")
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (e: Exception) {
        out.errors.add("$src: ${e.message}")
    }
}
"""

GREP_GUARD = """                    if (!attrs.isRegularFile) {
                        out.filesSkippedSpecial++
                        return FileVisitResult.CONTINUE
                    }
"""
COPY_GUARD = """                        if (!attrs.isRegularFile && !attrs.isSymbolicLink) {
                            out.errors.add("$file: special file, not copied")
                            return FileVisitResult.CONTINUE
                        }
"""
FIND_WHEN = """                        when {
                            attrs.isSymbolicLink -> 'l'
                            attrs.isRegularFile -> 'f'
                            else -> 'o'
                        },
"""

# (a) grep: the guard is gone — the engine opens whatever the walk hands it.
BAD_SEARCH_NO_GREP_GUARD = GOOD_SEARCH_FIX.replace(GREP_GUARD, "")
# (b) find: back to `if (isSymbolicLink) 'l' else 'f'`.
BAD_SEARCH_FIND_LABEL = GOOD_SEARCH_FIX.replace(
    FIND_WHEN, "                        if (attrs.isSymbolicLink) 'l' else 'f',\n"
)
# (c) cp: the guard is gone — Files.copy opens the pipe.
BAD_COPY_NO_GUARD = GOOD_COPY_FIX.replace(COPY_GUARD, "")

# [wide-1024] the guard keeps its position and its tokens, and is dead: every
# FIFO falls through to Files.copy, which blocks in open(2) forever.
BAD_COPY_DEAD_GUARD = GOOD_COPY_FIX.replace(
    "if (!attrs.isRegularFile && !attrs.isSymbolicLink) {",
    "if (false && !attrs.isRegularFile && !attrs.isSymbolicLink) {")
assert BAD_COPY_DEAD_GUARD != GOOD_COPY_FIX
# (d) the guard exists but AFTER the open: it cannot prevent anything.
BAD_SEARCH_GUARD_AFTER_OPEN = GOOD_SEARCH_FIX.replace(
    GREP_GUARD + "                    grepFile(",
    "                    grepFile(",
).replace(
    "grepFile(file, guestPathFor(root, \"/ws\", file), regex, limit, out)\n",
    "grepFile(file, guestPathFor(root, \"/ws\", file), regex, limit, out)\n" + GREP_GUARD,
)
# (e) prose only: the guard is replaced by a COMMENT that names it (the reason
#     the criterion strips comments — §1042-A-02's own scar).
BAD_SEARCH_COMMENT_ONLY = GOOD_SEARCH_FIX.replace(
    GREP_GUARD,
    "                    // a special file is not a regular file (attrs.isRegularFile)\n",
)
# (f) marker gone: the rule must fail closed, not silently pass.
BAD_SEARCH_RENAMED = GOOD_SEARCH_FIX.replace("fun grepTree(", "fun walkGrep(")

# ── batch-7 witnesses (09-1 … 09-5) ─────────────────────────────────────────
# 09-1: the compliant ceiling line is KEPT and a second raw-ceiling line is
# appended — a `<=`-shaped token match never saw the `==`, and the hole is a
# 100-byte name that putText then truncates to 99.
B7_TAR_EXTRA_RAW = GOOD_TAR.replace(
    '    if (utf8Length(name) <= NAME_LENGTH - 1) return "" to name\n',
    '    if (utf8Length(name) <= NAME_LENGTH - 1) return "" to name\n'
    '    if (utf8Length(name) == NAME_LENGTH) return "" to name\n')
assert B7_TAR_EXTRA_RAW != GOOD_TAR

# the equivalent spelling of the same stored ceiling must stay clean...
B7_TAR_LT_SPELLING = GOOD_TAR.replace(
    "utf8Length(name) <= NAME_LENGTH - 1", "utf8Length(name) < NAME_LENGTH")
assert B7_TAR_LT_SPELLING != GOOD_TAR

# ...and an INVERTED ceiling is a hole even though the ` - 1` is present.
B7_TAR_INVERTED = GOOD_TAR.replace(
    "utf8Length(prefix) <= PREFIX_LENGTH - 1", "utf8Length(prefix) >= PREFIX_LENGTH - 1")
assert B7_TAR_INVERTED != GOOD_TAR

# 09-2: the refusal keeps the token, the position AND the refuse call, and is
# short-circuited by a literal literal in FRONT of it.
B7_HANDLER_DEAD_LINK = GOOD_HANDLER.replace(
    "if (Files.isSymbolicLink(archivePath)) {",
    "if (false && Files.isSymbolicLink(archivePath)) {", 1)
assert B7_HANDLER_DEAD_LINK != GOOD_HANDLER

# 09-2b: the link is noticed and the flow continues past the refusal.
B7_HANDLER_LINK_KEPT_GOING = GOOD_HANDLER.replace(
    """    if (Files.isSymbolicLink(archivePath)) {
        return refuseLinkedArchive(entry, "write", startedNs, args)
    }""",
    """    if (Files.isSymbolicLink(archivePath)) {
        refuseLinkedArchive(entry, "write", startedNs, args)
    }""")
assert B7_HANDLER_LINK_KEPT_GOING != GOOD_HANDLER

# 09-3: the undelivered payload is multiplied by zero — `size - written` is
# still on the line, and nothing is written back.
B7_REGULAR_ZERO_SCALED = GOOD_REGULAR.replace(
    "writeZeros(sink, size - written)", "writeZeros(sink, (size - written) * 0L)")
assert B7_REGULAR_ZERO_SCALED != GOOD_REGULAR

# 09-4: the sort runs inside a discarding lambda — the listing keeps the
# directory-stream order and the old "a sort appears in writeTree" test is green.
B7_TREE_SORT_DISCARDED = GOOD_TREE.replace(
    "stream.toList().sortedBy { it.fileName.toString() }",
    "stream.toList().also { it.sortedBy { p -> p.fileName.toString() } }")
assert B7_TREE_SORT_DISCARDED != GOOD_TREE

# 09-5: the dead literal moves to the RIGHT of the guard — same dead branch,
# same token, same position.
B7_COPY_DEAD_RIGHT = GOOD_COPY_FIX.replace(
    "if (!attrs.isRegularFile && !attrs.isSymbolicLink) {",
    "if (!attrs.isRegularFile && !attrs.isSymbolicLink && false) {")
assert B7_COPY_DEAD_RIGHT != GOOD_COPY_FIX


def selftest():
    failures = []
    arms = [0]

    def expect_clean(name, v):
        arms[0] += 1
        if v:
            failures.append("%s must be clean, got %s" % (name, v))

    def expect_trip(name, v, needle):
        arms[0] += 1
        if not any(needle in item for item in v):
            failures.append("%s did not trip %r (got %s)" % (name, needle, v))

    good_tar = check_tar(GOOD_TAR)
    expect_clean("compliant tar fixture", good_tar)
    good_handler = check_handler(GOOD_HANDLER)
    expect_clean("compliant handler fixture", good_handler)

    bad_stale = check_tar(BAD_TAR_STALE)
    expect_trip("stale ceiling fixture (ISSUE-03a)", bad_stale, "stale raw-field ceiling")
    expect_trip("stale ceiling fixture (ISSUE-03a)", bad_stale, "NAME_LENGTH - 1")

    bad_half_prefix = check_tar(BAD_TAR_HALF_PREFIX)
    expect_trip("half-fix prefix fixture", bad_half_prefix, "stale raw-field ceiling")
    bad_half_rest = check_tar(BAD_TAR_HALF_REST)
    expect_trip("half-fix rest fixture", bad_half_rest, "stale raw-field ceiling")

    bad_nul = check_tar(GOOD_TAR.replace("length - 1)", "length)"))
    expect_trip("no-NUL-reserve fixture", bad_nul, "putText must keep")

    bad_false = check_handler(BAD_HANDLER_FALSE)
    expect_trip("denied-with-false fixture (ISSUE-03b)", bad_false, "failed=true")
    bad_single = check_handler(
        GOOD_HANDLER.replace(
            "        return finishTar(deniedEntry(destRaw, destResolution), true, startedNs, args)\n", ""
        )
    )
    expect_trip("single-site fixture", bad_single, "expected >= 2 finishTar")

    # ── [dv15-A2] A-04: the archive opens must not follow a link ────────────
    good_guard = check_archive_open_guard(GOOD_HANDLER)
    expect_clean("compliant archive-open guard fixture", good_guard)

    # the OTHER accepted shape must stay clean: no explicit check, the opens ask
    # for NOFOLLOW (and the read side's open sits one call deeper). If this went
    # red the criterion would be pinning a spelling, not the decision.
    expect_clean("NOFOLLOW-open shape fixture (dv15-A2)",
                 check_archive_open_guard(GOOD_HANDLER_NOFOLLOW))

    bad_guard_create = check_archive_open_guard(
        GOOD_HANDLER.replace(
            """    if (Files.isSymbolicLink(archivePath)) {
        return refuseLinkedArchive(entry, "write", startedNs, args)
    }
""", "")
    )
    expect_trip("create-side guard removed (dv15-A2)", bad_guard_create,
                "tarCreateInto opens the archive path")

    bad_guard_extract = check_archive_open_guard(
        GOOD_HANDLER.replace(
            """    if (Files.isSymbolicLink(archivePath)) {
        return refuseLinkedArchive(entry, "read", startedNs, args)
    }
""", "")
    )
    expect_trip("extract-side guard removed (dv15-A2)", bad_guard_extract,
                "tarExtractFrom opens the archive path")

    bad_guard_payload = check_archive_open_guard(
        GOOD_HANDLER.replace('"unsafe_archive_path"', '"unsafe_path"')
    )
    expect_trip("guard-without-payload fixture (dv15-A2)", bad_guard_payload,
                "detected but not refused")

    bad_guard_missing = check_archive_open_guard("object FastioOffloadHandler { }")
    expect_trip("missing archive-open members (dv15-A2)", bad_guard_missing,
                "required function missing")

    # ── [dv14-A2] writeRegular ──────────────────────────────────────────────
    good_regular = check_write_regular(GOOD_REGULAR)
    expect_clean("compliant writeRegular fixture", good_regular)
    bad_regular_order = check_write_regular(BAD_REGULAR_OLD_ORDER)
    expect_trip(
        "header-before-open fixture (dv14-A2)",
        bad_regular_order,
        "BEFORE opening the input stream",
    )
    bad_regular_norefill = check_write_regular(BAD_REGULAR_NO_REFILL)
    expect_trip(
        "copy-failure-without-refill fixture (dv14-A2)",
        bad_regular_norefill,
        "zero-fill the short payload",
    )
    bad_regular_unguarded = check_write_regular(BAD_REGULAR_UNGUARDED)
    expect_trip(
        "unguarded-refill fixture (dv14-A2)",
        bad_regular_unguarded,
        "must be guarded",
    )
    bad_regular_silent = check_write_regular(BAD_REGULAR_SILENT)
    expect_trip(
        "silent-truncation fixture (dv14-A2)",
        bad_regular_silent,
        "must still record out.errors",
    )
    bad_regular_const = check_write_regular(BAD_REGULAR_CONST_REFILL)
    expect_trip(
        "constant-refill fixture (wide-1024)",
        bad_regular_const,
        "UNDELIVERED payload",
    )
    bad_regular_missing = check_write_regular("object FastioTar { }")
    expect_trip(
        "missing-writeRegular fixture (dv14-A2)",
        bad_regular_missing,
        "required function missing",
    )

    # ── [dv16-A FLAKE] the create traversal must decide the member order ─────
    good_tree = check_create_traversal(GOOD_TREE)
    expect_clean("compliant create traversal", good_tree)
    bad_tree_walk = check_create_traversal(BAD_TREE_WALK)
    expect_trip("walker-order fixture (FLAKE)", bad_tree_walk, "walks with Files.walkFileTree")
    bad_tree_unsorted = check_create_traversal(BAD_TREE_UNSORTED)
    expect_trip("unsorted fixture (FLAKE)", bad_tree_unsorted, "never orders the entries")
    bad_tree_renamed = check_create_traversal(BAD_TREE_RENAMED)
    expect_trip("renamed-traversal fixture (FLAKE)", bad_tree_renamed, "cannot be sliced")
    # the order criterion must not fire on a file that merely MENTIONS the walker
    # (FastioTar.kt documents the replacement in a KDoc right above writeTree):
    expect_clean("walker mentioned in a doc comment",
                 check_create_traversal(GOOD_TREE.replace(
                     "private fun writeTree(",
                     "* Replaces `Files.walkFileTree`, which yields the entries in\n"
                     " * whatever order the directory stream hands them over.\n"
                     " */\nprivate fun writeTree(")))

    # ── [dv22-A2] §1042-A-02: the top-level root loop ───────────────────────
    good_top = check_toplevel_roots(GOOD_TOPLEVEL)
    expect_clean("compliant top-level fixture (dv22-A2)", good_top)
    bad_top_no_gate = check_toplevel_roots(BAD_TOPLEVEL_NO_GATE)
    expect_trip("no-gate fixture (dv22-A2)", bad_top_no_gate, "no entry-budget gate")
    bad_top_no_trunc = check_toplevel_roots(BAD_TOPLEVEL_NO_TRUNCATED)
    expect_trip("silent gate fixture (dv22-A2)", bad_top_no_trunc, "must set out.truncated")
    bad_top_continue = check_toplevel_roots(BAD_TOPLEVEL_CONTINUE)
    expect_trip("scan-instead-of-stop fixture (dv22-A2)", bad_top_continue, "must break the loop")
    bad_top_after_write = check_toplevel_roots(BAD_TOPLEVEL_GATE_AFTER_WRITE)
    expect_trip("gate-after-write fixture (dv22-A2)", bad_top_after_write, "AFTER the first write")
    bad_top_charges = check_toplevel_roots(BAD_TOPLEVEL_CHARGES)
    expect_trip("double-charge fixture (dv22-A2)", bad_top_charges, "charges the budget itself")
    bad_top_ignored = check_toplevel_roots(BAD_TOPLEVEL_IGNORED_RETURN)
    expect_trip("discarded-return fixture (dv22-A2)", bad_top_ignored, "discards writeTree's return")
    bad_top_unchecked = check_toplevel_roots(BAD_TOPLEVEL_UNCHECKED_SPECIAL)
    expect_trip("unchecked-special fixture (dv22-A2)", bad_top_unchecked,
                "without checking Files.isRegularFile")
    bad_top_unit = check_toplevel_roots(BAD_TOPLEVEL_UNIT_BRANCH)
    expect_trip("unit-branch fixture (wide-1024)", bad_top_unit, "does not STOP on it")
    bad_top_missing = check_toplevel_roots("object FastioTar { }")
    expect_trip("missing-tarCreate fixture (dv22-A2)", bad_top_missing, "required function missing")

    # ── [dv23-A6] §1043-A-07: the read/open engines ask first ──────────────
    def expect_only(name, v, needle):
        """Exactly one violation, and it is [needle]: the criterion has a tooth
        and the OTHER engines stay clean (a guard in one engine must not cover
        for a missing one in another)."""
        arms[0] += 1
        if len(v) != 1 or needle not in v[0]:
            failures.append("%s must trip exactly %r, got %s" % (name, needle, v))

    def expect_all_clean(name, v):
        arms[0] += 1
        if v:
            failures.append("%s must be clean, got %s" % (name, v))

    good_engines = check_special_file_guards(GOOD_SEARCH_FIX, GOOD_COPY_FIX)
    expect_all_clean("compliant special-file guards (dv23-A6)", good_engines)
    bad_grep = check_special_file_guards(BAD_SEARCH_NO_GREP_GUARD, GOOD_COPY_FIX)
    expect_only("grep guard removed (dv23-A6)", bad_grep, "grepTree opens every non-link entry")
    bad_find = check_special_file_guards(BAD_SEARCH_FIND_LABEL, GOOD_COPY_FIX)
    expect_only("find label regression (dv23-A6)", bad_find,
                "findTree types every non-link entry")
    bad_cp = check_special_file_guards(GOOD_SEARCH_FIX, BAD_COPY_NO_GUARD)
    expect_only("copy guard removed (dv23-A6)", bad_cp, "copyTree copies every non-link entry")
    bad_late = check_special_file_guards(BAD_SEARCH_GUARD_AFTER_OPEN, GOOD_COPY_FIX)
    expect_only("guard-after-open (dv23-A6)", bad_late, "sits AFTER the call")
    bad_cp_dead = check_special_file_guards(GOOD_SEARCH_FIX, BAD_COPY_DEAD_GUARD)
    expect_only("short-circuited copy guard (wide-1024)", bad_cp_dead, "short-circuited")
    bad_prose = check_special_file_guards(BAD_SEARCH_COMMENT_ONLY, GOOD_COPY_FIX)
    expect_only("comment-only guard (dv23-A6)", bad_prose, "grepTree opens every non-link entry")
    bad_renamed = check_special_file_guards(BAD_SEARCH_RENAMED, GOOD_COPY_FIX)
    expect_only("renamed engine (dv23-A6)", bad_renamed, "cannot be sliced")
    # the OTHER accepted shape (`isOther`) must stay clean, or the criterion
    # would be pinning `isRegularFile` the SPELLING rather than the decision.
    expect_all_clean(
        "isOther-shaped guard (dv23-A6)",
        check_special_file_guards(
            GOOD_SEARCH_FIX.replace("if (!attrs.isRegularFile) {", "if (attrs.isOther) {"),
            GOOD_COPY_FIX,
        ),
    )

    # ── batch-7 witnesses: each one used to pass this gate unchanged ────────
    extra_raw = check_tar(B7_TAR_EXTRA_RAW)
    arms[0] += 1
    if len(extra_raw) != 1 or "stale raw-field ceiling" not in extra_raw[0]:
        failures.append("09-1 extra raw ceiling must trip exactly one stale-ceiling finding "
                        "(the compliant line above it keeps counting), got %s" % (extra_raw,))
    expect_clean("09-1 equivalent `< FIELD` spelling stays clean", check_tar(B7_TAR_LT_SPELLING))
    expect_trip("09-1b inverted ceiling is red", check_tar(B7_TAR_INVERTED),
                "stale raw-field ceiling")
    dead_link = check_archive_open_guard(B7_HANDLER_DEAD_LINK)
    arms[0] += 1
    if len(dead_link) != 1 or "short-circuited" not in dead_link[0]:
        failures.append("09-2 short-circuited symlink refusal must trip exactly one "
                        "short-circuit finding, got %s" % (dead_link,))
    expect_trip("09-2b link noticed but not refused is red",
                check_archive_open_guard(B7_HANDLER_LINK_KEPT_GOING), "neither returns nor throws")
    expect_trip("09-3 zero-scaled refill is red", check_write_regular(B7_REGULAR_ZERO_SCALED),
                "UNDELIVERED payload")
    expect_trip("09-4 sort result discarded is red",
                check_create_traversal(B7_TREE_SORT_DISCARDED), "DISCARDS the sorted list")
    expect_only("09-5 dead literal on the RIGHT is red",
                check_special_file_guards(GOOD_SEARCH_FIX, B7_COPY_DEAD_RIGHT), "short-circuited")

    print("selftest compliant tar          -> %d violation(s)" % len(good_tar))
    print("selftest compliant handler      -> %d violation(s)" % len(good_handler))
    print("selftest stale ceiling         -> %d violation(s)" % len(bad_stale))
    print("selftest half-fix prefix/rest   -> %d / %d violation(s)"
          % (len(bad_half_prefix), len(bad_half_rest)))
    print("selftest no NUL reserve        -> %d violation(s)" % len(bad_nul))
    print("selftest denied-with-false      -> %d violation(s)" % len(bad_false))
    print("selftest single-site handler    -> %d violation(s)" % len(bad_single))
    print("selftest compliant writeRegular -> %d violation(s)" % len(good_regular))
    print("selftest writeRegular shapes    -> %d / %d / %d / %d / %d violation(s)"
          % (len(bad_regular_order), len(bad_regular_norefill), len(bad_regular_unguarded),
             len(bad_regular_silent), len(bad_regular_missing)))
    print("selftest compliant top-level    -> %d violation(s)" % len(good_top))
    print("selftest special-file guards    -> %d / %d / %d / %d / %d / %d violation(s)"
          % (len(bad_grep), len(bad_find), len(bad_cp), len(bad_late), len(bad_prose),
             len(bad_renamed)))
    print("selftest top-level shapes       -> %d / %d / %d / %d / %d / %d / %d / %d violation(s)"
          % (len(bad_top_no_gate), len(bad_top_no_trunc), len(bad_top_continue),
             len(bad_top_after_write), len(bad_top_charges), len(bad_top_ignored),
             len(bad_top_unchecked), len(bad_top_missing)))

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        print("FASTIO_TAR_BOUNDS_SELFTEST=FAIL (%d arms)" % arms[0])
        return 1
    print("FASTIO_TAR_SELFTEST_ARMS=%d" % arms[0])
    print("FASTIO_TAR_BOUNDS_SELFTEST=PASS (%d arms)" % arms[0])
    return 0


def main():
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    root = next((a for a in args if not a.startswith("-")), os.getcwd())
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
