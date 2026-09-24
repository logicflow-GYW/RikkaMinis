#!/usr/bin/env python3
"""Debug-code release boundary guard — RikkaMinis CI scan gate.

## Why this gate exists

`debug/` lives in `src/main` (NOT a `src/debug` source set), so the release
APK's hygiene depends on two links that are easy to silently break:

  1. every debug-package entry point must sit behind an `if (BuildConfig.DEBUG)`
     guard in the CALLING file, and
  2. R8 dead-code elimination must actually remove the classes
     (requires `isMinifyEnabled = true` and no `-keep` rule matching them).

Neither link is covered by any other gate. A future `-keep` rule, a minify
disable, or a NEW debug-package call site outside a guard would ship the
debug JSON-RPC server (127.0.0.1:5321, token-free loopback) into the release
APK with no error, warning or log line. This gate makes both loud.

Two modes:

  source <repo_root>   — every reference to `com.rikkaminis.app.debug.` outside
                         `debug/` must be in the allow-list below, or carry an
                         inline `debug-ok: <reason>` comment (same shape as
                         legacy_pipeline_guard.py). NOT a ban: the allow-list
                         files are the audited call sites. A NEW file touching
                         debug code is a build failure until it is audited.
  apk <path-to-apk>    — the release artifact must NOT contain any debug-only
                         marker string. This is the EXACT property that matters:
                         unzip the dex, grep, fail if present.

  --self-test          — fixture ground truth: the scanner must CATCH a dirty
                         fixture AND pass a clean one (a silently-regressed
                         scanner fails open; this makes it fail loud).

Usage:
  python3 scripts/scan/debug_leak_guard.py <repo_root>     # source mode
  python3 scripts/scan/debug_leak_guard.py apk <path-to-apk>
  python3 scripts/scan/debug_leak_guard.py --self-test

Exit: 0 = clean, 1 = violation(s) found.
"""

import os
import re
import struct
import sys
import zipfile

# Debug-package target: fully-qualified references AND imports.
TARGET_RE = re.compile(r"com\.rikkaminis\.app\.debug\.")

# Files allowed to touch debug code (rel to src/android/app/src/main/java,
# posix). Each entry is an AUDITED call site; the list is the review surface.
ALLOWED_FILES = {
    "com/rikkaminis/app/MainActivity.kt",        # DebugRPCHandler.currentActivity
    "com/rikkaminis/app/MinisApp.kt",            # DebugServer start + minis-debug handler (BuildConfig.DEBUG guarded)
    "com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt",   # LLMRequestLog
    "com/rikkaminis/app/provider/openai/OpenAIProvider.kt",         # LLMRequestLog
    "com/rikkaminis/app/sandbox/offload/SessionsOffloadHandler.kt", # ChatMutationMethods
}

# [audit-0916d] A `comment-only` entry used to sit here for ChatViewModel.kt.
# It was redundant: comment text is stripped by BLOCK_COMMENT_RE before the
# scan, so a mention inside `/* … */` never reaches TARGET_RE (verified by
# removing the entry — the source scan still exits 0). Kepping it weakens the
# gate instead: the whole 5k-line file became an unaudited surface. If a REAL
# ChatViewModel call site ever appears, the scan must fail and the site be
# audited — that is the point of the allow-list.

ESCAPE_RE = re.compile(r"debug-ok\s*:")
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)

# Marker strings that must be ABSENT from a release dex. Each is distinctive
# enough that a false positive is implausible: class names + the debug server
# port + a handler name only registered in debug builds.
APK_MARKERS = [
    "DebugServer",
    "DebugMethodRegistry",
    "DebugOffloadHandler",
    "DebugRPCHandler",
    "minis-debug",
    "5321",
]


def strip_comments(text):
    """Remove block comments and //-comments; keep line structure."""
    text = BLOCK_COMMENT_RE.sub("", text)
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith("//"):
            out.append(line if ESCAPE_RE.search(line) else "//")
        else:
            out.append(re.sub(r"//.*$", "", line))
    return out


def scan_source(root):
    src = os.path.join(root, "src/android/app/src")
    violations = []
    for base, _dirs, files in os.walk(src):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(base, fn)
            rel = os.path.relpath(path, src).replace(os.sep, "/")
            posix_rel = os.path.relpath(path, os.path.join(src, "main/java")).replace(os.sep, "/")
            if "/test/" in rel or "/androidTest/" in rel or "/debug/" in posix_rel:
                continue  # the debug package itself + tests pin the behaviour
            if posix_rel in ALLOWED_FILES:
                continue
            lines = strip_comments(open(path, encoding="utf-8", errors="replace").read())
            for i, line in enumerate(lines):
                prev = lines[i - 1] if i > 0 else ""
                if ESCAPE_RE.search(line) or ESCAPE_RE.search(prev):
                    continue
                if TARGET_RE.search(line):
                    violations.append(f"{posix_rel}:{i + 1}  {line.strip()[:100]}")
    return violations


def dex_entries(names):
    """Entry names the guard reads as the artifact's dex code.

    Pure (takes a name list) so the selector AND the fail-closed verdict below
    are testable without building a zip. Matched by BASENAME so a nested
    `dex/classes.dex` still counts; a renamed or absent dex yields [] — and []
    is a VIOLATION, not a pass (see [apk_verdict]).
    """
    out = []
    for n in names:
        base = os.path.basename(n)
        if base == "classes.dex" or (base.startswith("classes") and base.endswith(".dex")):
            out.append(n)
    return out


def apk_verdict(present, dex_count):
    """Verdict for the apk mode: 0 = clean, 1 = violation.

    [audit-0916d] Fail CLOSED on zero dex entries. Without this, a layout
    change (dex renamed, or the artifact truncated) made the gate print
    "markers absent" exit 0 without having scanned a single byte — a gate that
    cannot fail is not a gate. Verified: a zip carrying no `classes*.dex` used
    to pass.
    """
    if dex_count == 0:
        return 1
    if present:
        return 1
    return 0


def _read_uleb128(data, off):
    """Read a uleb128 from a dex. Raises ValueError past the buffer end."""
    result = 0
    shift = 0
    while True:
        if off >= len(data):
            raise ValueError("uleb128 runs past end of buffer")
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7
        if shift > 28:
            raise ValueError("uleb128 too long")

def dex_strings(data):
    """Return the string contents of a dex's string_data section.

    Pure (takes bytes) so the string-table-only scan is testable without a
    real build.

    [2026-09-24] The guard used to scan the RAW dex bytes. A short marker can
    coincide with dalvik bytecode operands across instruction boundaries:
    the 2026-09-24 release CI false-positived on "5321" — two iput-object
    instructions whose field-index bytes spelled 5321 (offset 3161468, code
    section, verified NOT inside any string_data item; each branch built
    clean, the merged input deterministically produced the byte sequence).
    A real debug leak always surfaces as strings in string_data (class names
    survive only via keep rules, whose names live in the same table), so
    scanning ONLY string contents removes the raw-byte false-positive class
    entirely while still catching every real leak.
    """
    if len(data) < 64:
        raise ValueError("dex too small to parse")
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    if string_ids_off + 4 * string_ids_size > len(data):
        raise ValueError("string_ids table runs past end of buffer")
    out = []
    for i in range(string_ids_size):
        (item_off,) = struct.unpack_from("<I", data, string_ids_off + 4 * i)
        _utf16_len, pos = _read_uleb128(data, item_off)
        end = data.index(b"\x00", pos)  # MUTF-8 NUL is 0xC0 0x80, so 0x00 is safe
        out.append(data[pos:end].decode("utf-8", errors="replace"))
    return out

def scan_apk(apk_path):
    """Return (debug markers present, dex entries scanned) for the APK."""
    present = []
    dex_count = 0
    with zipfile.ZipFile(apk_path) as zf:
        for name in dex_entries(zf.namelist()):
            dex_count += 1
            data = zf.read(name)
            try:
                strings = dex_strings(data)
            except ValueError as e:
                # Fail CLOSED: an unparseable dex must not read as clean.
                present.append(f"{name}: UNPARSEABLE DEX ({e})")
                continue
            for s in strings:
                for marker in APK_MARKERS:
                    if marker in s:
                        present.append(f"{name}: {marker} (string: {s[:60]!r})")
    return present, dex_count


def self_test():
    import tempfile
    ok = True
    # --- source mode: dirty fixture must FAIL, clean must PASS ---
    base = os.path.join("src/android/app/src/main/java/com/rikkaminis/app")
    dirty = {
        f"{base}/SomeNewFile.kt": "val x = com.rikkaminis.app.debug.DebugServer()\n",
        f"{base}/debug/DebugServer.kt": "package com.rikkaminis.app.debug\nclass DebugServer\n",
    }
    root = tempfile.mkdtemp(prefix="debugleak-dirty-")
    for rel, content in dirty.items():
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w").write(content)
    v = scan_source(root)
    ok &= bool(v) and check(f"source mode CATCHES dirty fixture ({len(v)} violation)", len(v) > 0)

    root2 = tempfile.mkdtemp(prefix="debugleak-clean-")
    clean = {
        f"{base}/MinisApp.kt": "if (BuildConfig.DEBUG) { com.rikkaminis.app.debug.DebugServer(this).start() }\n",
        f"{base}/SomeOther.kt": "// mentions debug.DebugServer in a comment only\n",
        f"{base}/debug/DebugServer.kt": "package com.rikkaminis.app.debug\nclass DebugServer\n",
    }
    for rel, content in clean.items():
        p = os.path.join(root2, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w").write(content)
    v2 = scan_source(root2)
    ok &= check("source mode passes clean fixture (allow-listed + comment-only)", len(v2) == 0, str(v2))

    # reverse control: the marker list itself must be non-trivial
    ok &= check("apk marker list non-empty", len(APK_MARKERS) >= 3)

    # --- apk mode: dex selector + fail-closed verdict (reverse control) ---
    # [audit-0916d] A zip with no `classes*.dex` used to print 'markers absent'
    # and exit 0 — the gate could not fail. These pin both halves.
    real_layout = ["classes.dex", "resours/foo.bin", "lib/aarch64/libx.so"]
    ok &= check(
        "apk selector finds the real layout's dex",
        dex_entries(real_layout) == ["classes.dex"],
        str(dex_entries(real_layout)),
    )
    ok &= check(
        "apk selector finds a nested dex",
        dex_entries(["dex/classes2.dex"]) == ["dex/classes2.dex"],
    )
    ok &= check(
        "apk selector ignores an artifact with no dex",
        dex_entries(["resours/foo.bin"]) == [],
    )
    ok &= check("apk verdict: ZERO dex entries is a VIOLATION (fail closed)", apk_verdict([], 0) == 1)
    ok &= check("apk verdict: markers present is a violation", apk_verdict(["classes.dex: DebugServer"], 1) == 1)
    ok &= check("apk verdict: clean dex passes", apk_verdict([], 1) == 0)

    # --- apk scan: string-table-only (2026-09-24 raw-byte false-positive regression) ---
    # The raw-byte scan flagged "5321" that was actually dalvik bytecode
    # operands (two iput-object field indices). These fixtures pin: bytes in
    # bytecode/metadata territory never count; a marker in string_data does.
    def _mk_dex(strings, junk=b""):
        """Minimal dex-shaped fixture: 64-byte header + string table + data."""
        n = len(strings)
        buf = bytearray(64 + 4 * n)
        struct.pack_into("<II", buf, 56, n, 64)
        data_off = 64 + 4 * n
        blob = b""
        for i, s in enumerate(strings):
            raw = s.encode("utf-8") + b"\x00"
            ln = len(s)
            uleb = bytes([ln]) if ln < 0x80 else bytes([0x80 | (ln & 0x7F), ln >> 7])
            struct.pack_into("<I", buf, 64 + 4 * i, data_off + len(blob))
            blob += uleb + raw
        return bytes(buf) + blob + junk

    import zipfile as _zf
    dex_clean = _mk_dex(
        ["Lcom/example/Foo;", "hello world"],
        junk=b"\x55\x53\x35\x33\x32\x31\xab\xcd" * 4,  # the 09-24 bytecode class
    )
    dex_dirty = _mk_dex(["Lcom/example/DebugServer;", "minis-debug"], junk=b"")
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tf:
        with _zf.ZipFile(tf, "w") as z:
            z.writestr("classes.dex", dex_clean)
            z.writestr("resours/foo.bin", b"junk")
        apk_clean_path = tf.name
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tf2:
        with _zf.ZipFile(tf2, "w") as z:
            z.writestr("classes.dex", dex_dirty)
        apk_dirty_path = tf2.name
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tf3:
        with _zf.ZipFile(tf3, "w") as z:
            z.writestr("classes.dex", b"\x00" * 10)
        apk_broken_path = tf3.name
    present_c, cnt_c = scan_apk(apk_clean_path)
    ok &= check(
        "apk scan: marker bytes in bytecode (not string_data) are NOT flagged",
        present_c == [] and cnt_c == 1,
        str(present_c),
    )
    present_d, cnt_d = scan_apk(apk_dirty_path)
    ok &= check(
        "apk scan: a debug marker in string_data IS flagged",
        cnt_d == 1 and any("DebugServer" in p for p in present_d),
        str(present_d),
    )
    present_b, cnt_b = scan_apk(apk_broken_path)
    ok &= check(
        "apk scan: an unparseable dex fails CLOSED",
        cnt_b == 1 and any("UNPARSEABLE" in p for p in present_b),
        str(present_b),
    )
    for p in (apk_clean_path, apk_dirty_path, apk_broken_path):
        os.unlink(p)
    return 0 if ok else 1


def _is_apk_arg(x):
    return x.lower().endswith(".apk") or os.path.isfile(x)


PASS = 0
FAIL = 0


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✅ {name}")
        return True
    FAIL += 1
    print(f"  ❌ {name}" + (f"\n     {detail}" if detail else ""))
    return False


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "--self-test":
        return self_test()
    if args[0] == "apk" or (len(args) == 1 and _is_apk_arg(args[0])):
        apk = args[1] if len(args) >= 2 else args[0]
        present, dex_count = scan_apk(apk)
        if apk_verdict(present, dex_count) == 0:
            print(f"✅ Debug-leak guard (apk): {len(APK_MARKERS)} debug markers absent from {dex_count} dex entries")
            return 0
        if dex_count == 0:
            print("❌ Debug-leak guard (apk): NO dex entries found in the artifact — nothing was scanned!")
            print("   The gate fails CLOSED: a renamed / nested dex, or a truncated artifact,")
            print("   must not read as 'markers absent'. Check the build output layout, then")
            print("   extend dex_entries() if the change is intentional.")
            return 1
        print("❌ Debug-leak guard (apk): DEBUG-ONLY code found in the release APK!")
        print("   A -keep rule, minify disable, or new BuildConfig.DEBUG-less entry")
        print("   shipped debug code. The debug JSON-RPC server (127.0.0.1:5321) is")
        print("   token-free — this must not reach users.")
        for x in present:
            print("     " + x)
        return 1
    root = os.path.abspath(args[0])
    v = scan_source(root)
    if v:
        print(f"❌ Debug-leak guard (source): {len(v)} unaudited debug-package reference(s)")
        print("   debug/ lives in src/main; release hygiene depends on R8 DCE +")
        print("   BuildConfig.DEBUG guards at every call site. New references must")
        print("   be audited and added to ALLOWED_FILES — or justified inline with")
        print("   `debug-ok: <reason>`.")
        for x in v:
            print("     " + x)
        return 1
    print("✅ Debug-leak guard (source): no unaudited debug-package references")
    return 0
if __name__ == "__main__":
    sys.exit(main())
