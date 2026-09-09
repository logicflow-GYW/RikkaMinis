# Syncing with upstream

> **Status: no longer routine.** This fork stopped rebasing onto upstream after
> the package rename (`com.openminis.app` → `com.rikkaminis.app`, 741 files):
> the two trees are now too far apart for a whole-tree replay. Upstream changes
> are absorbed **on demand**, one commit at a time. What follows is the
> historical rebase procedure, kept because the hard constraints it documents
> (vendored binaries must stay paired with the Kotlin source,
> `build.gradle.kts` is the conflict hotspot, proot is built from source) still
> apply whenever upstream code is ported by hand.

This fork tracks [`OpenMinis/OpenMinis`](https://github.com/OpenMinis/OpenMinis)
but deliberately diverges in a few well-defined places. This document is the
procedure for pulling in upstream changes without breaking the build.

## What you need to know first

Upstream is a **one-way mirror**, not a normal development repo — its own README
states it does not accept pull requests. It is a periodic public snapshot of a
private repository. Practical consequences:

- History is not guaranteed to be a clean incremental series. A single update
  may arrive as one large squashed commit, and force-pushes are possible.
- Because of that, **do not `git merge` upstream.** A merge would create a
  tangled history that gets harder to reconcile every time. Rebase instead.

## What this fork changes

The divergence started small but is no longer so (the package rename alone
touched 741 files), which is why whole-tree syncing was retired. The table
below lists the areas that matter when porting an individual upstream commit:

| Area | Change | Conflicts with upstream? |
|---|---|---|
| `src/android/app/build.gradle.kts` | CMake/`externalNativeBuild` disabled, packaging options, version fields | **Likely** — the one file to watch |
| `.github/workflows/build-apk.yml` | Added by this fork | No — upstream has no such file |
| `src/android/app/src/main/jniLibs/arm64-v8a/*.so` | Vendored upstream binaries (pty_bridge, c++_shared, datastore, androidx.graphics.path); the jieba/crash_handler copies are rebuilt from source in CI | No — upstream does not commit these |
| `src/android/app/src/main/assets/alpine-minirootfs.tar` | Vendored official asset | No — same reason |
| `.gitignore` | Un-ignores the vendored binaries | Minor, easy to resolve |
| `scripts/sync_official_binaries.sh` | Added by this fork | No |
| iOS sources | Deleted | Deletions may reappear; re-delete |

`deps/` (the `deps/proot` submodule, vendored `deps/talloc` and
`deps/build_proot.sh`) matches upstream and should be kept as-is. proot itself
is **built from source** in CI, so it is not part of the sync procedure below.

So in practice, when porting an individual upstream commit, **`build.gradle.kts`
is still the file to watch** — but note that a whole-tree replay is off the
table: the package rename alone touched 741 files.

## Why the other binaries must be refreshed every time

This fork does not compile native code through AGP: `externalNativeBuild` is
disabled in `build.gradle.kts`. Three libraries are built from source in CI:
proot (`deps/build_proot.sh`, with upstream's Android 10+ W^X bypass patches in
the `deps/proot` submodule), jieba (`deps/build_jieba.sh`) and crash_handler
(`deps/build_crash_handler.sh`) — the last two because their JNI symbol names
embed the Kotlin package name, which this fork renamed to `com.rikkaminis.app`.
The remaining vendored libraries (pty_bridge, c++_shared, datastore,
androidx.graphics.path) are the official `.so` files, committed as-is.

Vendored binaries and the Kotlin source **must be kept as a matched pair**. If
upstream changes a JNI method signature — say a parameter is added to
`PtyBridge.forkExec` — the old `.so` no longer matches the new Kotlin
declaration, and the app crashes at runtime. So when porting an upstream commit
that touches a JNI boundary, refresh the vendored libraries in the same change.
proot / jieba / crash_handler need no refresh: they rebuild from source; bump
the `deps/proot` submodule only when upstream's build inputs change.

## Procedure

```bash
# One-time setup
git remote add upstream https://github.com/OpenMinis/OpenMinis.git

# 1. Fetch and inspect what changed
git fetch upstream
git log --oneline HEAD..upstream/main

# 2. Replay this fork's commits on top of upstream
git rebase upstream/main
#    Resolve conflicts (expect build.gradle.kts). Keep, on our side:
#      - no externalNativeBuild / cmake block
#      - the packaging { jniLibs { ... } } block
#      - our versionCode / versionName
#    Then: git add <file> && git rebase --continue

# 3. Refresh the vendored libraries to match the new source.
#    proot / jieba / crash_handler need no refresh here: they are built
#    from source in CI (deps/build_proot.sh, build_jieba.sh,
#    build_crash_handler.sh). Only bump deps/proot when upstream changes
#    proot's build inputs. The script skips jieba/crash_handler on purpose
#    (their symbols embed the renamed Kotlin package).
./scripts/sync_official_binaries.sh

# 4. Review, commit, push
git diff --stat
git add -A
git commit -m "chore: sync with upstream + refresh vendored binaries"
git push --force-with-lease
```

`--force-with-lease` is required because rebasing rewrites commits; it refuses
the push if someone else changed the branch meanwhile, unlike a bare `--force`.

Pushing to `main` triggers `.github/workflows/build-apk.yml`, which compiles
proot from source (NDK r28 + `deps/build_proot.sh`), runs the backup tests,
builds a release APK and publishes it to the `android-latest` release.

## After syncing: verify

The workflow checks that every vendored `.so` is a valid ELF and that the
APK's `libproot.so` matches the freshly compiled one. It cannot catch a JNI
signature mismatch — that only shows up at runtime. So after a sync that pulled
in Kotlin changes, install the APK and confirm:

1. The app starts (rules out a bad `libminis_crash_handler.so` / early JNI load).
2. The terminal opens and runs a command — this is the source-built
   `libproot.so` + `libpty_bridge.so` smoke test, and the thing most likely to break.
3. Chinese text input still segments correctly (exercises `libjieba_jni.so`).

If the terminal reports `Permission denied`, the proot build is broken: check
that `deps/build_proot.sh` ran in CI and that `deps/proot` is at the right
commit — do **not** reach for an extracted binary, that reintroduces the
unpatched build. For the other libs, re-run `sync_official_binaries.sh`, or pin
an older upstream tag: `./scripts/sync_official_binaries.sh 0.22-preview`.

## If a sync goes badly wrong

The rebase is recoverable as long as you have not garbage-collected:

```bash
git rebase --abort          # during a rebase
git reflog                  # find the pre-rebase commit
git reset --hard <sha>      # go back to it
```
