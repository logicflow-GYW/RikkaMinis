# FIX-MIRROR-BASE-1114 — the mirror wrote one directory too deep

**Branch** `fix/mirror-public-base-1114` (based on `c68227e5`, the build shipped as `1.0.0+3099`)
**Found by** the sandbox-side verification pass on the 11-12→11-14 changes (user asked "what can you verify here"), then confirmed on the device.

## 先证（device, 2026-11-14 23:0x）

The mirror was **live and healthy** — it kept producing fresh `previous/` stamps
(`previous/20261114-230344`) — while writing into the wrong directory:

```
/sdcard/Download/RikkaMinis/backup/Download/RikkaMinis/backup/mirror/current/...
                                              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^ ROOT_REL applied twice
```

- 41 MB / 2070 entries at that nested path.
- The parked v1 tree (`backup/legacy-mirror-v1`, 50 MB) sat at the *un-nested* level,
  which is what made the double prefix easy to spot: two "mirror" trees, one level apart.

Root cause: `openTargetWithKind` built the writer as

```kotlin
MirrorPlan.TargetKind.PUBLIC_DOWNLOADS -> FileTarget(publicBase())
```

`publicBase()` already appends `ROOT_REL` (`Download/RikkaMinis/backup`), while every path
`runLocked` hands to a `MirrorTarget` starts with that same prefix
(`currentPath(rel)` = `CURRENT_REL` + name). `FileTarget` joins `base + relPath` verbatim
⇒ the prefix landed twice.

## 证据

JVM probe against the shipped tree (`verify-1114/MirrorBaseDuplicationProbe.kt`):

```
today -> Download/RikkaMinis/backup/Download/RikkaMinis/backup/mirror/current/memory/x.md
fixed -> Download/RikkaMinis/backup/mirror/current/memory/x.md
```

Matches the device listing byte for byte.

## 修法（1 行 + 说明）

- `publicStorageRoot()` = `Environment.getExternalStorageDirectory()` — the base the
  storage-relative paths expect.
- `publicBase()` stays for the two sites that address the mirror root itself (the v1 tree
  and its park destination); pointing those at the volume root would look for `/sdcard/mirror`.
- `appExternalBase()` deliberately unchanged (26–28 fallback): its existing files live at the
  nested path, so "tidying" it would strand them. Documented with a ponytail tag.

## 测试

New `MirrorPublicBaseWiringTest` (3 cases):
1. the `PUBLIC_DOWNLOADS` arm must pass the volume root, and must **not** pass `publicBase()`;
2. the migration must keep `val base = publicBase()`;
3. a storage-relative path written through `FileTarget(volumeRoot)` lands at the documented
   path exactly once (no second `ROOT_REL`).

`SilentMirrorWiringTest` had pinned the defect — it asserted
`store.contains("FileTarget(publicBase())")`, i.e. the wiring test enshrined the bug. Updated to
`publicStorageRoot()`; the mutation run (revert the one line) turns both tests red.

## Local evidence

- JVM harness on the branch tree: **OK (115 tests)** (mirror target/plan/scope/wiring + fold +
  paste-fold wiring + the 3 new cases).
- Mutation arm: reverting the line ⇒ **2 failures** (SilentMirrorWiringTest +
  MirrorPublicBaseWiringTest) ⇒ the assertions have teeth.
- Neighbour gates: `mirror_category_route_check` PASS, `mirror_vm_thread_check` PASS,
  `backup_remap_rollup_check` OK, `backup_streaming_export_check` OK (arm1..arm11).

## 未做（登记）

- 设备上那 41 MB 的 `backup/Download/…` 是本地数据的副本，修复后不会再更新；删除与否由用户决定。
- 修复后首次同步会在正确路径重建 `current/`，`previous/` 从空开始（镜像不是唯一副本，无数据风险）。
