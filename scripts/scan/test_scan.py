#!/usr/bin/env python3
"""
Scan self-test — verify the scanners themselves catch what they claim to catch.

Each scanner gets three checks:
  1. CLEAN fixture  → must exit 0
  2. DIRTY fixture  → must exit 1 (the violation class it was built for)
  3. REAL repo tree → must exit 0 (scanners are green in CI today)

Why: a scanner that silently regresses (a regex stops matching after a
refactor, a path moves) fails OPEN — CI stays green while the exact bug class
the scanner was built for (GH#68, P0-pinned-providers, enum crash-on-read…)
walks back in. These fixtures are the ground truth: known-bad trees are
asserted to be caught, so a broken scanner fails LOUD here instead of failing
silent in every future build.

Usage: python3 scripts/scan/test_scan.py [repo_root]
Exit:  0 = all scanners pass all cases, 1 = any failure
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(
    sys.argv[1] if len(sys.argv) > 1 else os.path.join(SCRIPT_DIR, "..", "..")
)

# Relative roots the scanners expect inside a repo tree.
KOTLIN_APP = "src/android/app/src/main/java"
KOTLIN_PKG = os.path.join(KOTLIN_APP, "com/rikkaminis/app")
RES_VALUES = "src/android/app/src/main/res/values"

PASS = 0
FAIL = 0


def run_scanner(script, root):
    """Run one scanner against root; return (exit_code, combined_output)."""
    proc = subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, script), root],
        capture_output=True,
        text=True,
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def make_tree(files):
    """files: {relative_path: content} → tempdir repo root."""
    root = tempfile.mkdtemp(prefix="scan-selftest-")
    for rel, content in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
    return root


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✅ {name}")
    else:
        FAIL += 1
        print(f"  ❌ {name}")
        if detail:
            print(f"     {detail}")


# ─────────────────────────────────────────────────────────────────────────────
# Fixtures
# ─────────────────────────────────────────────────────────────────────────────

FW_MODEL = """package com.rikkaminis.app.data.model

data class ProviderInstance(
    val id: String,
    val label: String,
    val apiKey: String,
)
"""

FW_ENTITY = """package com.rikkaminis.app.data.db

data class ProviderInstanceEntity(
    val id: String,
    val label: String,
    val apiKey: String,
)
"""

FW_MAPPING = """package com.rikkaminis.app.data.db

fun ProviderConfig.toSnapshot(): ProviderConfigSnapshot {
    val instanceRows = instances.mapIndexed { idx, inst ->
        ProviderInstanceEntity(
            id = inst.id,
            label = inst.label,
            apiKey = inst.apiKey,
        )
    }
    return ProviderConfigSnapshot(instanceRows)
}

fun ProviderConfigSnapshot.toProviderConfig(): ProviderConfig {
    val instances = this.instances.map { row ->
        ProviderInstance(
            id = row.id,
            label = row.label,
            apiKey = row.apiKey,
        )
    }.toMutableList()
    return ProviderConfig(instances = instances)
}
"""

# Minimal stubs for the files the scanner reads unconditionally for the
# (inert in this fixture) ModelGroup group.
FW_GROUP_ENTITY_STUB = (
    "package com.rikkaminis.app.data.db\n"
    "// ModelGroup intentionally absent — exercises the parse-error-continue path.\n"
)


def four_way_fixture(model_extra="", entity_extra="", snap_extra="", config_extra=""):
    """Build a four-way-sync fixture with optional per-layer extra fields."""
    model = FW_MODEL.replace("    val apiKey: String,\n", "    val apiKey: String,\n" + model_extra)
    entity = FW_ENTITY.replace(
        "    val apiKey: String,\n", "    val apiKey: String,\n" + entity_extra
    )
    snap_args = "            apiKey = inst.apiKey,\n" + snap_extra
    config_args = "            apiKey = row.apiKey,\n" + config_extra
    mapping = FW_MAPPING.replace("            apiKey = inst.apiKey,\n", snap_args)
    mapping = mapping.replace("            apiKey = row.apiKey,\n", config_args)
    return {
        os.path.join(KOTLIN_PKG, "data/model/ProviderConfig.kt"): model,
        os.path.join(KOTLIN_PKG, "data/db/ProviderInstanceEntity.kt"): entity,
        os.path.join(KOTLIN_PKG, "data/db/ProviderModelGroupEntity.kt"): FW_GROUP_ENTITY_STUB,
        os.path.join(KOTLIN_PKG, "data/db/ProviderConfigMapping.kt"): mapping,
    }


I18N_CLEAN_KT = (
    "package com.rikkaminis.app\n"
    "val title = R.string.app_name\n"
)

I18N_DIRTY_KT = (
    "package com.rikkaminis.app\n"
    "val title = R.string.app_name\n"
    "val orphan = R.string.never_defined_key\n"
)

I18N_STRINGS = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    "<resources>\n"
    '    <string name="app_name">Minis</string>\n'
    "</resources>\n"
)


def enum_fixture(dirty=False):
    guarded = (
        "package com.rikkaminis.app\n"
        "fun a(s: String) {\n"
        "    val x = runCatching { Foo.valueOf(s) }.getOrNull()\n"
        "}\n"
        "fun b(s: String) {\n"
        "    try {\n"
        "        val y = Bar.valueOf(s)\n"
        "    } catch (e: IllegalArgumentException) {\n"
        "        return\n"
        "    }\n"
        "}\n"
    )
    bare = (
        "package com.rikkaminis.app\n"
        "fun c(s: String) {\n"
        "    val z = Baz.valueOf(s)\n"
        "}\n" if dirty else ""
    )
    return {
        os.path.join(KOTLIN_PKG, "EnumUsers.kt"): guarded + bare,
    }


def boundary_fixture(dirty=False):
    worker = (
        "package com.rikkaminis.app.sandbox.offload\n"
        "class ModelExecutionService {\n"
        "    fun run() {\n"
        "        provider.streamMessage(request) {}\n"
        "        provider.sendMessage(request) {}\n"
        "    }\n"
        "}\n"
    )
    caller = (
        "package com.rikkaminis.app.ui.chat\n"
        "class ChatViewModel {\n"
        "    fun send() {\n"
        "        viewModel.sendMessage(msg)\n"
        "        // provider.sendMessage(commented) — comments are skipped\n"
        + ("        provider.sendMessage(userMessage)\n" if dirty else "")
        + "    }\n"
        "}\n"
    )
    return {
        os.path.join(KOTLIN_PKG, "sandbox/offload/ModelExecutionService.kt"): worker,
        os.path.join(KOTLIN_PKG, "ui/chat/ChatViewModel.kt"): caller,
    }


def legacy_fixture(dirty=False, escaped=False):
    """The 2026-09-13 incident class: a live file reaching into the dead
    pre-aggregate pipeline (cold-open prewarm's always-empty source list)."""
    legacy = (
        "package com.rikkaminis.app.ui.chat.legacy\n"
        "internal class StableChatRowLedger {\n"
        "    fun record() {}\n"
        "}\n"
    )
    body = "        val ledger: Any? = null\n"
    if dirty:
        body = "        StableChatRowLedger().record()\n"
    elif escaped:
        body = (
            "        // legacy-ok: single-point source function for both pipelines\n"
            "        StableChatRowLedger().record()\n"
            "\n"
            "        val unrelated = 1\n"
        )
    consumer = (
        "package com.rikkaminis.app.ui.chat\n"
        "class ChatViewModel {\n"
        "    fun send() {\n"
        "        // StableChatRowLedger mentioned in a comment — comments are skipped\n"
        + body
        + "    }\n"
        "}\n"
    )
    return {
        os.path.join(KOTLIN_PKG, "ui/chat/legacy/StableChatRowLedger.kt"): legacy,
        os.path.join(KOTLIN_PKG, "ui/chat/ChatViewModel.kt"): consumer,
    }


# ─────────────────────────────────────────────────────────────────────────────
# Cases
# ─────────────────────────────────────────────────────────────────────────────

def test_four_way():
    print("━━━ four_way_sync_check ━━━")
    # Clean — all four layers agree.
    root = make_tree(four_way_fixture())
    code, out = run_scanner("four_way_sync_check.py", root)
    check("clean fixture exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty A — model field missing in Entity (the GH#68 class: saved nowhere).
    root = make_tree(four_way_fixture(model_extra="    val pinned: Boolean = false,\n"))
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "model-extra-field caught (exit 1, 'Entity missing')",
        code == 1 and "Entity missing" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty B — Entity field not passed in toSnapshot (lost on every save).
    root = make_tree(four_way_fixture(entity_extra="    val pinned: Int = 0,\n"))
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "toSnapshot-gap caught (exit 1, 'toSnapshot missing')",
        code == 1 and "toSnapshot missing" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty C — model field not restored in toProviderConfig (lost on load).
    root = make_tree(
        four_way_fixture(
            model_extra="    val pinned: Boolean = false,\n",
            entity_extra="    val pinned: Int = 0,\n",
            snap_extra="            pinned = if (inst.pinned) 1 else 0,\n",
        )
    )
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "toProviderConfig-gap caught (exit 1, 'toProviderConfig missing')",
        code == 1 and "toProviderConfig missing" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_i18n():
    print("━━━ i18n_check ━━━")
    files = {
        os.path.join(RES_VALUES, "strings.xml"): I18N_STRINGS,
        os.path.join(KOTLIN_PKG, "Ui.kt"): I18N_CLEAN_KT,
    }
    root = make_tree(files)
    code, out = run_scanner("i18n_check.py", root)
    check("clean fixture exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    files[os.path.join(KOTLIN_PKG, "Ui.kt")] = I18N_DIRTY_KT
    root = make_tree(files)
    code, out = run_scanner("i18n_check.py", root)
    check(
        "orphan R.string key caught (exit 1)",
        code == 1 and "never_defined_key" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Missing translation is WARNING-only (legacy locales) — must not fail.
    files[os.path.join(KOTLIN_PKG, "Ui.kt")] = I18N_CLEAN_KT
    files[os.path.join(os.path.dirname(RES_VALUES), "values-de", "strings.xml")] = (
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    )
    root = make_tree(files)
    code, out = run_scanner("i18n_check.py", root)
    check(
        "missing translation stays warning-only (exit 0)",
        code == 0 and ("WARNING" in out or "warn" in out.lower()),
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_enum_parse():
    print("━━━ enum_parse_safety_check ━━━")
    root = make_tree(enum_fixture(dirty=False))
    code, out = run_scanner("enum_parse_safety_check.py", root)
    check(
        "guarded valueOf (runCatching + multi-line try) exits 0",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    root = make_tree(enum_fixture(dirty=True))
    code, out = run_scanner("enum_parse_safety_check.py", root)
    check(
        "bare valueOf caught (exit 1)",
        code == 1 and "Baz.valueOf" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_boundary():
    print("━━━ provider_boundary_guard ━━━")
    root = make_tree(boundary_fixture(dirty=False))
    code, out = run_scanner("provider_boundary_guard.py", root)
    check(
        "worker calls + comment lines are legal (exit 0)",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    root = make_tree(boundary_fixture(dirty=True))
    code, out = run_scanner("provider_boundary_guard.py", root)
    check(
        "app-process provider.sendMessage caught (exit 1)",
        code == 1 and "ChatViewModel.kt" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_legacy():
    print("━━━ legacy_pipeline_guard ━━━")
    # Clean — the dead symbol is only mentioned in a comment.
    root = make_tree(legacy_fixture())
    code, out = run_scanner("legacy_pipeline_guard.py", root)
    check(
        "comment-only mention is legal (exit 0)",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty — a live file consumes the runtime-dead symbol (fails silently in
    # production; must fail loudly here).
    root = make_tree(legacy_fixture(dirty=True))
    code, out = run_scanner("legacy_pipeline_guard.py", root)
    check(
        "live caller of legacy symbol caught (exit 1)",
        code == 1 and "ChatViewModel.kt" in out and "StableChatRowLedger" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Escape hatch — `legacy-ok:` justifies the following block (and the
    # justification must survive comment stripping; a blank line ends it).
    root = make_tree(legacy_fixture(escaped=True))
    code, out = run_scanner("legacy_pipeline_guard.py", root)
    check(
        "legacy-ok escape hatch exempts the block (exit 0)",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_debug_leak():
    print("━━━ debug_leak_guard (source mode) ━━━")
    debug_cls = (
        "package com.rikkaminis.app.debug\n"
        "class DebugServer\n"
    )
    # Clean — the debug class is allow-listed (MinisApp) / comment-only.
    clean = {
        os.path.join(KOTLIN_PKG, "debug/DebugServer.kt"): debug_cls,
        os.path.join(KOTLIN_PKG, "MinisApp.kt"):
            "if (BuildConfig.DEBUG) { com.rikkaminis.app.debug.DebugServer(this).start() }\n",
        os.path.join(KOTLIN_PKG, "Other.kt"):
            "// mentions com.rikkaminis.app.debug.DebugServer in a comment only\n",
    }
    root = make_tree(clean)
    code, out = run_scanner("debug_leak_guard.py", root)
    check("allow-listed call site + comment-only mention is legal (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty — a NEW unaudited file touches debug code (the M1 failure class:
    # ships the token-free loopback debug server into release with no other
    # gate noticing).
    dirty = dict(clean)
    dirty[os.path.join(KOTLIN_PKG, "SomeNewFile.kt")] = (
        "val x = com.rikkaminis.app.debug.DebugServer()\n"
    )
    root = make_tree(dirty)
    code, out = run_scanner("debug_leak_guard.py", root)
    check(
        "unaudited debug-package call site caught (exit 1)",
        code == 1 and "SomeNewFile.kt" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Escape hatch — `debug-ok:` justifies the reference.
    escaped = dict(clean)
    escaped[os.path.join(KOTLIN_PKG, "SomeNewFile.kt")] = (
        "// debug-ok: audited single-point entry, guarded at the caller\n"
        "val x = com.rikkaminis.app.debug.DebugServer()\n"
    )
    root = make_tree(escaped)
    code, out = run_scanner("debug_leak_guard.py", root)
    check("debug-ok escape hatch exempts the reference (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

def test_room_migration():
    print("━━━ room_migration_check ━━━")
    code, out = run_scanner("room_migration_check.py", "--self-test")
    check(
        "self-test fixtures pass (unwired + non-contiguous caught, clean passes)",
        code == 0,
        f"exit={code}\n{out}",
    )

def test_process_logging():
    print("━━━ process_logging_gate ━━━")
    code, out = run_scanner("process_logging_gate.py", "--self-test")
    check(
        "self-test fixtures pass (unwired process caught, wired/escaped pass)",
        code == 0,
        f"exit={code}\n{out}",
    )
    # Real-tree shape: a process declared with the escape comment ABOVE the
    # tag (XML forbids comments inside a start tag) must still pass.
    root = make_tree({
        "src/android/app/src/main/AndroidManifest.xml": (
            "<manifest>\n"
            "  <!-- logging-ok: dormant, no request path -->\n"
            '  <service android:process=":sleepyservice" />\n'
            "</manifest>\n"
        ),
    })
    code, out = run_scanner("process_logging_gate.py", root)
    check("escape comment above the tag passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)


def test_trace_eval():
    print("━━━ trace_eval_check ━━━")
    # The evaluator gates every golden in tests/traces/golden/, including the
    # fault goldens — a silently broken assertion there would let a removed
    # guard ship green. Fixtures below are the ground truth for its checks.

    clean_case = {"tools": ["file_write"], "terminal_state": "Succeeded", "all_tools_succeed": True}
    clean_lines = [
        {"type": "trace_start"},
        {"type": "turn_start", "turn": 0},
        {"type": "tool_call", "turn": 0, "tool": "file_write"},
        {"type": "tool_result", "turn": 0, "tool": "file_write", "success": True, "output": "ok"},
        {"type": "trace_end", "terminal_state": "Succeeded", "terminal_reason": "completed_normally"},
    ]
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps({"trace": "tests/traces/golden/traces/t.jsonl", "expect": clean_case}),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in clean_lines),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check("clean golden exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty A — tool sequence drift (the truncated-call regression shape:
    # the refused call executed after all, so the sequence grew by one).
    lines = clean_lines[:2] + [
        {"type": "tool_call", "turn": 0, "tool": "file_write"},
        {"type": "tool_result", "turn": 0, "tool": "file_write", "success": False, "output": "invalid JSON"},
    ] + clean_lines[2:]
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps({"trace": "tests/traces/golden/traces/t.jsonl", "expect": clean_case}),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in lines),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check(
        "tool-sequence drift caught (exit 1)",
        code == 1 and "tool sequence mismatch" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty B — some_tool_failed not honoured: every result succeeded, i.e. the
    # offload guard was removed and the stub payload got written.
    all_ok = clean_lines
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps(
            {"trace": "tests/traces/golden/traces/t.jsonl", "expect": {"some_tool_failed": True}}
        ),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in all_ok),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check(
        "some_tool_failed with all-succeeded results caught (exit 1)",
        code == 1 and "every tool_result succeeded" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # ...and the positive direction of the same assertion.
    with_failure = clean_lines[:-1] + [
        {"type": "tool_result", "turn": 0, "tool": "file_write", "success": False, "output": "refused"},
    ] + clean_lines[-1:]
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps(
            {"trace": "tests/traces/golden/traces/t.jsonl", "expect": {"some_tool_failed": True}}
        ),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in with_failure),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check("some_tool_failed satisfied by a failed result (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty C — run never finalized.
    no_end = [l for l in clean_lines if l["type"] != "trace_end"]
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps({"trace": "tests/traces/golden/traces/t.jsonl", "expect": clean_case}),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in no_end),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check(
        "missing trace_end caught (exit 1)",
        code == 1 and "no trace_end event" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty D — golden pointing at a trace that does not exist (a renamed or
    # never-committed .jsonl must fail loudly, not silently skip).
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps(
            {"trace": "tests/traces/golden/traces/missing.jsonl", "expect": clean_case}
        )
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check(
        "missing trace file caught (exit 1)",
        code == 1 and "trace file missing" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_extra_days_container():
    print("━━━ extra_days_container_guard ━━━")
    # Ground truth: the guard must accept arrayListOf and reject BOTH wrong
    # containers -- int[] and Kotlin's listOf() (java.util.Arrays$ArrayList).
    # The defect it catches is silent: the framework turns the ClassCastException
    # into a null and the alarm replays as a one-shot.
    good = make_tree({
        "src/MinisApp.kt": (
            "class M {\n"
            "    private fun daysForRepeatMode(r: String): ArrayList<Int>? =\n"
            "        when (r) { \"DAILY\" -> arrayListOf(1, 2, 3, 4, 5, 6, 7); else -> null }\n"
            "    fun f() { daysForRepeatMode(\"DAILY\")?.let {\n"
            "        putExtra(android.provider.AlarmClock.EXTRA_DAYS, it) } }\n"
            "}\n"
        ),
        "src/Handler.kt": (
            "class H {\n"
            "    fun g(m: Int) { when (m) {\n"
            "        1 -> putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2))\n"
            "        else -> putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2))\n"
            "    } }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("extra_days_container_guard.py", good)
    check("ArrayList writers pass (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(good)

    for label, ret, value in (
        ("int[]", "IntArray?", "intArrayOf(1, 2, 3, 4, 5, 6, 7)"),
        ("listOf", "List<Int>?", "listOf(1, 2, 3, 4, 5, 6, 7)"),
    ):
        bad = make_tree({
            "src/MinisApp.kt": (
                "class M {\n"
                f"    private fun daysForRepeatMode(r: String): {ret} =\n"
                f"        when (r) {{ \"DAILY\" -> {value}; else -> null }}\n"
                "    fun f() { daysForRepeatMode(\"DAILY\")?.let {\n"
                "        putExtra(android.provider.AlarmClock.EXTRA_DAYS, it) } }\n"
                "}\n"
            ),
            "src/Handler.kt": (
                "class H {\n"
                "    fun g() { putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2)) }\n"
                "}\n"
            ),
        })
        code, out = run_scanner("extra_days_container_guard.py", bad)
        check(f"{label} writer is caught (exit 1)", code == 1, f"exit={code}\n{out}")
        shutil.rmtree(bad)

def test_stale_closure():
    print("━━━ stale_closure_guard ━━━")
    # Ground truth: the guard must accept rememberUpdatedState and reject a
    # long-lived produceState whose closure reads a plain parameter while that
    # parameter is NOT in the key list. The defect it catches is silent: the
    # producer is never restarted, `snapshotFlow { plainParam }` observes
    # nothing, and the UI freezes at the first composed frame (2026-09-20).
    good = make_tree({
        KOTLIN_PKG + "/ui/Good.kt": (
            "@Composable\n"
            "private fun Good(content: String, isStreaming: Boolean) {\n"
            "    val latestContent by rememberUpdatedState(content)\n"
            "    val d by produceState(initialValue = content, isStreaming) {\n"
            "        snapshotFlow { latestContent }.collect { value = it }\n"
            "    }\n"
            "}\n"
        ),
        # key 里含 content ⇒ 每次变化都重启闭包 ⇒ 安全
        KOTLIN_PKG + "/ui/Keyed.kt": (
            "@Composable\n"
            "private fun Keyed(content: String) {\n"
            "    val d by produceState(initialValue = content, content) {\n"
            "        snapshotFlow { content }.collect { value = it }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stale_closure_guard.py", good)
    check("rememberUpdatedState + keyed param pass (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(good)

    bad = make_tree({
        KOTLIN_PKG + "/ui/Bad.kt": (
            "@Composable\n"
            "private fun Bad(content: String, isStreaming: Boolean) {\n"
            "    val d by produceState(initialValue = content, isStreaming) {\n"
            "        snapshotFlow { content }.collect { value = it }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stale_closure_guard.py", bad)
    check("plain param without rememberUpdatedState is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(bad)

    # 逃生口：带 stale-ok 说明的必须放行
    escaped = make_tree({
        KOTLIN_PKG + "/ui/Esc.kt": (
            "@Composable\n"
            "private fun Esc(content: String) {\n"
            "    // stale-ok: content is immutable for the lifetime of this effect\n"
            "    val d by produceState(initialValue = content) {\n"
            "        snapshotFlow { content }.collect { value = it }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stale_closure_guard.py", escaped)
    check("stale-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(escaped)

def test_ime_inset():
    print("━━━ ime_inset_guard ━━━")
    # Ground truth: a text field hosted by a bare Scaffold with no imePadding is
    # invisible behind the keyboard (edge-to-edge + adjustResize = the window is
    # NOT resized for the IME). The guard must flag that, accept the fixed shape,
    # and honour both escape hatches. Two false negatives found while building
    # this guard are pinned here so they cannot regress:
    #   - a trailing-lambda `Scaffold { }` (no parens) must count as a host;
    #   - a `"*/*"` string literal must not swallow the rest of the file.
    good = make_tree({
        KOTLIN_PKG + "/ui/Good.kt": (
            "@Composable\n"
            "private fun Good() {\n"
            "    Scaffold { padding ->\n"
            "        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {\n"
            "            OutlinedTextField(value = \"\", onValueChange = {})\n"
            "        }\n"
            "    }\n"
            "}\n"
        ),
        # 保护宿主（SettingsScaffold）⇒ 不在判据内
        KOTLIN_PKG + "/ui/Protected.kt": (
            "@Composable\n"
            "private fun Protected() {\n"
            "    SettingsScaffold(title = \"x\") {\n"
            "        OutlinedTextField(value = \"\", onValueChange = {})\n"
            "    }\n"
            "}\n"
        ),
        # 没有文本输入点的裸 Scaffold ⇒ 不该报
        KOTLIN_PKG + "/ui/NoField.kt": (
            "@Composable\n"
            "private fun NoField() {\n"
            "    Scaffold { padding -> Text(\"hi\") }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", good)
    check("fixed + protected + fieldless pass (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(good)

    bad = make_tree({
        KOTLIN_PKG + "/ui/Bad.kt": (
            "@Composable\n"
            "private fun Bad() {\n"
            "    Scaffold { padding ->\n"
            "        Column(Modifier.fillMaxSize().padding(padding)) {\n"
            "            OutlinedTextField(value = \"\", onValueChange = {})\n"
            "        }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", bad)
    check("bare Scaffold + field, no imePadding is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(bad)

    # 假阴性回归 1：`Scaffold {` 尾随 lambda 形态必须被识别为宿主
    trailing = make_tree({
        KOTLIN_PKG + "/ui/Trailing.kt": (
            "@Composable\n"
            "private fun Trailing() {\n"
            "    Scaffold { padding ->\n"
            "        OutlinedTextField(value = \"\", onValueChange = {})\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", trailing)
    check("trailing-lambda `Scaffold {` counts as host (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(trailing)

    # 假阴性回归 2：文件中的 "*/*" 字符串不得吞掉后续代码
    star = make_tree({
        KOTLIN_PKG + "/ui/Star.kt": (
            "@Composable\n"
            "private fun Star() {\n"
            "    val launcher = remember { \"\" }\n"
            "    Button(onClick = { launcher.launch(\"*/*\") }) { Text(\"pick\") }\n"
            "}\n"
            "\n"
            "@Composable\n"
            "private fun Later() {\n"
            "    Scaffold { padding ->\n"
            "        OutlinedTextField(value = \"\", onValueChange = {})\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", star)
    check("\"*/*\" literal does not swallow later functions (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(star)

    # 逃生口 1：ime-ok
    ok_esc = make_tree({
        KOTLIN_PKG + "/ui/OkEsc.kt": (
            "@Composable\n"
            "private fun OkEsc() {\n"
            "    Scaffold { padding ->\n"
            "        AlertDialog(onDismissRequest = {}) {\n"
            "            // ime-ok: independent Window, platform pans to the field\n"
            "            OutlinedTextField(value = \"\", onValueChange = {})\n"
            "        }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", ok_esc)
    check("ime-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(ok_esc)

    # 逃生口 2：ime-unreachable
    un_esc = make_tree({
        KOTLIN_PKG + "/ui/UnEsc.kt": (
            "@Composable\n"
            "private fun UnEsc() {\n"
            "    Scaffold { padding -> Text(\"list\") }\n"
            "    // ime-unreachable: the field lives in the sheet branch below\n"
            "    Sheet { OutlinedTextField(value = \"\", onValueChange = {}) }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", un_esc)
    check("ime-unreachable escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(un_esc)

def test_stream_flow_dispatch():
    print("━━━ stream_flow_dispatch_guard ━━━")
    # Ground truth: a callbackFlow whose body blocks must carry a flowOn, or the
    # producer runs on the COLLECTOR's dispatcher and starves every timer on it
    # — including the flow's own watchdogs. The 2026-09-20 hang: the
    # :modelservice collector is `runBlocking` (no dispatcher), so the TTFB
    # watchdog, the first-data watchdog and the worker's outer timeout all
    # became unreachable at once and a 1.6MB request sat 272s with no byte back.
    good = make_tree({
        KOTLIN_PKG + "/provider/Good.kt": (
            "fun goodStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))\n"
            "    val call = client.newCall(req)\n"
            "    val resp = call.execute()\n"
            "    while (reader.readLine() != null) { }\n"
            "    awaitClose { call.cancel() }\n"
            "}.flowOn(Dispatchers.IO)\n"
        ),
        # 链上还有别的 operator，flowOn 不在第一个位置 —— 也算放行
        KOTLIN_PKG + "/provider/Chained.kt": (
            "fun chainedStream(): Flow<String> = channelFlow<Int> {\n"
            "    Thread.sleep(1)\n"
            "}.flowOn(Dispatchers.IO).map { it.toString() }\n"
        ),
        # 体内没有阻塞调用 ⇒ 不归本门管
        KOTLIN_PKG + "/provider/NonBlocking.kt": (
            "fun pureStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    trySend(1)\n"
            "    awaitClose { }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", good)
    check("dispatched / non-blocking flows pass (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(good)

    # 负例：体内阻塞且链上无 flowOn ⇒ 必须翻红
    bad = make_tree({
        KOTLIN_PKG + "/provider/Bad.kt": (
            "fun badStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    val call = client.newCall(req)\n"
            "    val resp = call.execute()\n"
            "    while (reader.readLine() != null) { }\n"
            "    awaitClose { call.cancel() }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", bad)
    check("blocking cold flow without flowOn is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(bad)

    # flowOn(Dispatchers.Main) 只是把阻塞搬到 UI 线程，同样要翻红
    main_disp = make_tree({
        KOTLIN_PKG + "/provider/MainDisp.kt": (
            "fun mainStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    val resp = call.execute()\n"
            "    awaitClose { }\n"
            "}.flowOn(Dispatchers.Main)\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", main_disp)
    check("flowOn(Dispatchers.Main) is still a violation (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(main_disp)

    # ★ 回归守卫：注释里的花括号/关键字不得干扰作用域配平。
    # 首版判据在这里翻车 —— 我加的解释性注释里写了 `// runBlocking { ... }`，
    # 行注释未掩码 ⇒ 花括号计数失衡 ⇒ match_brace 冲过真正的 } 一路吞掉文件后半段，
    # 反向对照时多报 11 处无关命中（含注释自身）。这个 fixture 把该 bug 钉死。
    comment_braces = make_tree({
        KOTLIN_PKG + "/provider/CommentBrace.kt": (
            "fun commented(): Flow<Int> = callbackFlow<Int> {\n"
            "    // the collector's `runBlocking { ... }` has no dispatcher\n"
            "    // see https://example.com/docs/flow {not a brace}\n"
            "    val resp = call.execute()\n"
            "    awaitClose { }\n"
            "}.flowOn(Dispatchers.IO)\n"
            "\n"
            "fun unrelated(): Int {\n"
            "    val s = \"https://example.com/a//b\"\n"
            "    return 1\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", comment_braces)
    check("comment/string braces do not break scoping (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(comment_braces)

    # 逃生口：带 stream-ok 说明的必须放行
    escaped = make_tree({
        KOTLIN_PKG + "/provider/Esc.kt": (
            "fun escStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    // stream-ok: body runs on a dedicated single-thread executor\n"
            "    val resp = call.execute()\n"
            "    awaitClose { }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", escaped)
    check("stream-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(escaped)


def test_prefs_listener_holder():
    print("━━━ prefs_listener_holder_guard ━━━")
    tmp = tempfile.mkdtemp()
    good = os.path.join(tmp, "good")
    os.makedirs(good)

    # Positive: named field holder (the F-240 shape).
    with open(os.path.join(good, "Good.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
class Good(private val prefs: SharedPreferences) {
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    init { prefs.registerOnSharedPreferenceChangeListener(prefsListener) }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", good)
    check("named field holder passes (exit 0)", code == 0, f"exit={code}\n{out}")

    # Negative A: the exact hazard -- trailing-lambda call (NO parentheses).
    # This is the shape that shipped in ConfigBuiltins.kt:152, and the shape an
    # earlier version of this guard silently skipped.
    bad = os.path.join(tmp, "bad")
    os.makedirs(bad)
    with open(os.path.join(bad, "Bad.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Bad {
    fun reg(prefs: SharedPreferences) {
        prefs.registerOnSharedPreferenceChangeListener { _, _ -> refresh() }
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", bad)
    check("trailing-lambda inline hazard fails (exit != 0)", code != 0, f"exit={code}\n{out}")
    check("hazard is reported as INLINE", "INLINE" in out, out[:400])

    # Negative B: parenthesised anonymous-listener argument, also unheld.
    bad2 = os.path.join(tmp, "bad2")
    os.makedirs(bad2)
    with open(os.path.join(bad2, "Bad2.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Bad2 {
    fun reg(prefs: SharedPreferences) {
        prefs.registerOnSharedPreferenceChangeListener(
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() })
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", bad2)
    check("parenthesised anonymous listener fails (exit != 0)", code != 0, f"exit={code}\n{out}")

    # Negative C: named local that is never stored anywhere -- looks held to a
    # naive reader, but nothing keeps it alive past the frame.
    bad3 = os.path.join(tmp, "bad3")
    os.makedirs(bad3)
    with open(os.path.join(bad3, "Bad3.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Bad3 {
    fun reg(prefs: SharedPreferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", bad3)
    check("unheld named local fails (exit != 0)", code != 0, f"exit={code}\n{out}")

    # Positive: named local that IS stored into a field.
    good2 = os.path.join(tmp, "good2")
    os.makedirs(good2)
    with open(os.path.join(good2, "Good2.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Good2 {
    @Volatile private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    fun reg(prefs: SharedPreferences) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", good2)
    check("local stored into a field passes (exit 0)", code == 0, f"exit={code}\n{out}")

    # Escape hatch.
    esc = os.path.join(tmp, "esc")
    os.makedirs(esc)
    with open(os.path.join(esc, "Esc.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Esc {
    fun reg(prefs: SharedPreferences) {
        // prefs-listener-ok: held by the caller for the screen's lifetime
        prefs.registerOnSharedPreferenceChangeListener { _, _ -> refresh() }
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", esc)
    check("prefs-listener-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")

    # Regression fixtures for the scope-classification bug found 2026-09-21.
    # The guard fused `@Composable` + `fun` across the newline into one token,
    # `\bfun\b` stopped matching, and the function body was classified as a
    # class -- so every local `val listener` inside it looked like a field and
    # reported `held`. Four real sites (ChatScreen, AppearanceScreen,
    # ChatMenuSettingsScreen, MemoryManagementScreen) got that false pass, and
    # deleting their actual holder still passed. These fixtures are the ground
    # truth: both shapes MUST fail when unheld.
    for label, sig in (
        ("annotated multi-line signature",
         "@Composable\nfun Screen(\n    prefs: SharedPreferences,\n    context: Context,\n) {"),
        ("annotated single-line signature",
         "@Composable\nfun Screen(prefs: SharedPreferences, context: Context) {"),
        ("unannotated multi-line signature",
         "fun reg(\n    prefs: SharedPreferences,\n) {"),
    ):
        d = os.path.join(tmp, "shape_" + label.replace(" ", "_"))
        os.makedirs(d)
        with open(os.path.join(d, "Shape.kt"), "w") as f:
            f.write(f"""
import android.content.SharedPreferences
object Shape {{
    {sig}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener {{ _, _ -> refresh() }}
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }}
    private fun refresh() {{}}
}}
""")
        code, out = run_scanner("prefs_listener_holder_guard.py", d)
        check(f"unheld local with {label} fails (exit != 0)", code != 0, f"exit={code}\n{out}")

    # And the DisposableEffect shape these four sites actually use MUST pass --
    # otherwise the fixtures above could be satisfied by a guard that just
    # reports everything as unheld.
    disp = os.path.join(tmp, "dispose_shape")
    os.makedirs(disp)
    with open(os.path.join(disp, "Disp.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
@Composable
fun rememberThing(
    context: Context,
) {
    val prefs = ChatMenuPrefs.prefs(context)
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", disp)
    check("onDispose-held listener in annotated multi-line fn passes (exit 0)",
          code == 0, f"exit={code}\n{out}")

    # Mutation: remove the onDispose holder -> must now FAIL. This is the exact
    # edit that used to stay green.
    mut = os.path.join(tmp, "dispose_mutated")
    os.makedirs(mut)
    with open(os.path.join(mut, "Mut.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
@Composable
fun rememberThing(
    context: Context,
) {
    val prefs = ChatMenuPrefs.prefs(context)
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", mut)
    check("removing the onDispose holder fails (exit != 0)", code != 0, f"exit={code}\n{out}")
    shutil.rmtree(tmp)

def test_real_repo():
    print("━━━ real repo tree (must be clean) ━━━")
    for script in (
        "four_way_sync_check.py",
        "i18n_check.py",
        "enum_parse_safety_check.py",
        "provider_boundary_guard.py",
        "legacy_pipeline_guard.py",
        "trace_eval_check.py",
        "extra_days_container_guard.py",
        "stale_closure_guard.py",
        "ime_inset_guard.py",
        "stream_flow_dispatch_guard.py",
        "prefs_listener_holder_guard.py",
    ):
        code, out = run_scanner(script, REPO_ROOT)
        check(f"{script} on real repo exits 0", code == 0, f"exit={code}\n{out[:2000]}")


def main():
    print("╔══════════════════════════════════════════════════╗")
    print("║  Scan Self-Test (fixture ground truth)          ║")
    print("╚══════════════════════════════════════════════════╝")
    test_four_way()
    test_i18n()
    test_enum_parse()
    test_boundary()
    test_legacy()
    test_debug_leak()
    test_room_migration()
    test_process_logging()
    test_trace_eval()
    test_extra_days_container()
    test_stale_closure()
    test_ime_inset()
    test_stream_flow_dispatch()
    test_prefs_listener_holder()
    test_real_repo()
    print("")
    print(f"{'❌ FAILURES: ' + str(FAIL) if FAIL else '✅ ALL ' + str(PASS) + ' CASES PASS'}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
