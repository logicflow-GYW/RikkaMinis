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
KOTLIN_PKG = os.path.join(KOTLIN_APP, "com/openminis/app")
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

FW_MODEL = """package com.openminis.app.data.model

data class ProviderInstance(
    val id: String,
    val label: String,
    val apiKey: String,
)
"""

FW_ENTITY = """package com.openminis.app.data.db

data class ProviderInstanceEntity(
    val id: String,
    val label: String,
    val apiKey: String,
)
"""

FW_MAPPING = """package com.openminis.app.data.db

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
    "package com.openminis.app.data.db\n"
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
    "package com.openminis.app\n"
    "val title = R.string.app_name\n"
)

I18N_DIRTY_KT = (
    "package com.openminis.app\n"
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
        "package com.openminis.app\n"
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
        "package com.openminis.app\n"
        "fun c(s: String) {\n"
        "    val z = Baz.valueOf(s)\n"
        "}\n" if dirty else ""
    )
    return {
        os.path.join(KOTLIN_PKG, "EnumUsers.kt"): guarded + bare,
    }


def boundary_fixture(dirty=False):
    worker = (
        "package com.openminis.app.sandbox.offload\n"
        "class ModelExecutionService {\n"
        "    fun run() {\n"
        "        provider.streamMessage(request) {}\n"
        "        provider.sendMessage(request) {}\n"
        "    }\n"
        "}\n"
    )
    caller = (
        "package com.openminis.app.ui.chat\n"
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


def test_real_repo():
    print("━━━ real repo tree (must be clean) ━━━")
    for script in (
        "four_way_sync_check.py",
        "i18n_check.py",
        "enum_parse_safety_check.py",
        "provider_boundary_guard.py",
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
    test_real_repo()
    print("")
    print(f"{'❌ FAILURES: ' + str(FAIL) if FAIL else '✅ ALL ' + str(PASS) + ' CASES PASS'}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
