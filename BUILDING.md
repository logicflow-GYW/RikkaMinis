# Building

Most of the build is a plain Gradle run — but the sandbox engine is built from
source, so you need the NDK and the `deps/proot` submodule for a full release
build:

```sh
git clone --recurse-submodules https://github.com/logicflow-GYW/RikkaMinis.git
cd RikkaMinis
./deps/build_proot.sh           # build the PRoot sandbox engine from source
cd src/android
./gradlew assembleRelease
```

The APK lands in `src/android/app/build/outputs/apk/release/app-release.apk`.

If you only want to *install* the app, you do not need any of this — grab the
[latest APK](https://github.com/logicflow-GYW/RikkaMinis/releases/tag/android-latest).

---

## Requirements

| Tool | Version |
|---|---|
| JDK | **17** (Temurin recommended) |
| Android SDK | **compileSdk 36**, targetSdk 35, minSdk 26 |
| Gradle | 8.11.1 — provided by the wrapper, do not install separately |
| NDK | **r28** — required only to build the PRoot sandbox engine via `deps/build_proot.sh` |

Android Studio bundles a suitable JDK and SDK; opening `src/android` as a
project works out of the box. From the command line, `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`) must point at your SDK.

**You do not need CMake.** AGP native compilation is disabled — only PRoot is
built, via its own `deps/build_proot.sh` script (which does need NDK r28). See
below.

---

## Debug vs release

```sh
./gradlew assembleDebug      # ~39 MB, no code shrinking
./gradlew assembleRelease    # ~14 MB, R8-minified — what CI publishes
```

Prefer `assembleRelease` unless you are actively debugging. A debug APK is
roughly three times larger because R8 does not run, and it also embeds a local
JSON-RPC debug server on `127.0.0.1:5321` that release builds compile out.

Both variants are signed with the `debug` signing config — i.e. your
`~/.android/debug.keystore`, generated automatically on first use. This means a
locally built APK will **not** install over one downloaded from Releases: the
signing keys differ. Uninstall first, or install the same keystore CI uses.

---

## Sandbox binaries are built from source

The app runs a Linux sandbox via PRoot. Its engine, `libproot.so`, needs
upstream's Android 10+ W^X bypass patches plus OpenMinis' private extensions
(native offloads, ashmem-backed memory). A plain recompile — e.g. the AGP
CMake path — builds cleanly and then fails the moment the terminal opens:

```
execve("/bin/sh"): Permission denied
```

So this fork builds PRoot from the correct source, using the same makefile
pipeline as upstream's private builds:

```
deps/proot/          OpenMinis' PRoot fork (git submodule, patches included)
deps/talloc/         vendored talloc sources
deps/build_proot.sh  NDK r28 build script (verbatim from upstream's repo)
```

CI runs `./deps/build_proot.sh clean` before Gradle on every build, producing
`libproot.so` plus the independent loaders (`libproot-loader.so` /
`libproot-loader32.so`) into `src/android/app/src/main/jniLibs/arm64-v8a/`.
**No prebuilt sandbox binaries are committed to the repository.**

Three settings in `app/build.gradle.kts` protect the setup:

- `externalNativeBuild` is **removed**. Left enabled, AGP would compile
  `src/main/cpp/` and overwrite the vendored `libpty_bridge.so` with a locally
  built copy. (jieba and crash_handler live under `src/main/cpp/` too, but are
  compiled by dedicated scripts — see below — because their JNI symbol names
  embed the Kotlin package name.)
- `packaging { jniLibs { keepDebugSymbols += "**/*.so" } }` stops AGP running
  the NDK's `strip` over the proot build products.
- `useLegacyPackaging = true` keeps the libraries extracted to
  `nativeLibraryDir` at install time. This is load-bearing: `RootfsManager`
  executes `libproot.so` as a **binary** from that directory, and that is
  precisely the mechanism that sidesteps W^X.

**Consequence:** changes under `src/android/app/src/main/cpp/` are not
compiled. To work on native code, restore the `externalNativeBuild` block
(the removed configuration is documented inline in `build.gradle.kts`) and
install NDK r28+.

---

## Upgrading PRoot

The `deps/proot` submodule pins the exact PRoot revision the app is built
from. To upgrade:

```sh
git -C deps/proot fetch origin
git -C deps/proot checkout <new-tag-or-commit>
git add deps/proot && git commit
```

Pushing that commit triggers a full rebuild — `deps/**` is part of the CI
trigger set, and `build_proot.sh` runs on every build. If upstream's build
script changes, mirror it into `deps/build_proot.sh` as well.

---

## Refreshing the vendored helper libraries

A few native libraries are still committed to the repository, extracted
verbatim from an official upstream release APK: `libpty_bridge.so` (legacy —
no Kotlin caller remains), `libandroidx.graphics.path.so`, `libc++_shared.so`
and `libdatastore_shared_counter.so`. The repository also still tracks
`libjieba_jni.so` and `libminis_crash_handler.so`, but those copies are stale
upstream binaries that CI overwrites from source on every build.

```sh
./scripts/sync_official_binaries.sh              # newest upstream Android release
./scripts/sync_official_binaries.sh 0.22-preview # a specific tag
```

The script downloads the official APK, replaces those `.so` files, prints
sha256s, and aligns `versionName` / `versionCode`. It deliberately does **not**
touch:

- `libproot.so` / `libproot-loader*.so` — built from source by
  `deps/build_proot.sh` on every CI run;
- `libjieba_jni.so` / `libminis_crash_handler.so` — their JNI symbol names
  embed the Kotlin package name, and upstream's binaries still export
  `Java_com_openminis_app_*`; copying them over this fork's renamed build would
  silently reintroduce `UnsatisfiedLinkError`. CI rebuilds both from
  `src/main/cpp/` via `deps/build_jieba.sh` and `deps/build_crash_handler.sh`,
  and a symbol gate fails the build if either binary still exports the old
  prefix.

Because the fork no longer rebases onto upstream, run the script only when a
hand-ported upstream change actually requires a newer helper library. See
[docs/SYNCING_UPSTREAM.md](docs/SYNCING_UPSTREAM.md) for the constraint that
makes the pairing mandatory.

---

## Build-time customization

`app/provider-customization.properties.example` is a template for values that
are not shipped in the public tree. Copy it to
`app/provider-customization.properties` and fill in what you need:

- `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` — only required to sign in with Claude
  **OAuth** credentials. API keys do not need it.

A build with empty values compiles fine and fails at runtime the first time a
missing value is actually required.

---

## Continuous integration

`.github/workflows/build-apk.yml` runs on every push to `main` that touches
`src/android/`, `src/shared/`, `deps/` or the workflow itself, and can be
triggered manually from the Actions tab. It:

1. Restores the signing keystore from the `DEBUG_KEYSTORE_B64` secret
2. Runs the pre-build scan gate (`scripts/scan/scan.sh`) — four-way field-sync
   check, i18n orphan-key check, enum-parse safety, provider process-boundary
   guard
3. Installs NDK r28 and builds PRoot from source via `./deps/build_proot.sh clean`
4. Verifies the build products (`libproot.so`, `libproot-loader.so`) exist
5. Runs the full unit-test suite (`testReleaseUnitTest`) — red means red
6. Compiles the instrumented tests (`compileDebugAndroidTestKotlin`)
7. Runs `assembleRelease`
8. Publishes the APK to the `android-latest` release

### Signing in CI

`DEBUG_KEYSTORE_B64` holds a base64-encoded `debug.keystore`. It is a **repo
secret, not a file in the tree** — keystores are credentials and must not be
committed.

The workflow decodes it to `$RUNNER_TEMP/ci-signing.keystore` and exports
`MINIS_KEYSTORE_PATH`; `build.gradle.kts` reads that variable and points the
`debug` signing config at it.

That indirection is necessary, not decorative. AGP's default is
`$HOME/.android/debug.keystore`, but `actions/checkout` temporarily overrides
`HOME` on the runner — so a keystore written to `~/.android/` is restored to a
path Gradle never reads, and AGP quietly generates a throwaway key instead. The
build succeeds, the APK looks fine, and it then refuses to install over an
existing one with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. This bit us once; the
`Verify APK signature` step exists so it cannot happen silently again. It
compares the certificate embedded in the built APK against the keystore, using
`scripts/apk_cert_sha256.py`.

Losing it means losing upgrade continuity: a new key produces APKs that will not
install over existing ones, and every user has to uninstall and reinstall. Back
it up somewhere durable.

To rotate or recreate it:

```sh
keytool -genkeypair -v -keystore debug.keystore \
  -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 \
  -validity 10950 -dname "CN=Android Debug,O=Android,C=US"

base64 -w0 debug.keystore   # paste into the repo secret
```

The storepass, keypass and alias above are the Android defaults and must stay
as-is — Gradle's `debug` signing config looks for exactly those.

---

## Troubleshooting

**`SDK location not found`** — set `ANDROID_HOME`, or create
`src/android/local.properties` containing `sdk.dir=/path/to/Android/sdk`.

**`Failed to install ... INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — the installed
app was signed with a different key (e.g. the official build, or your local
debug key vs. CI's). Uninstall it first.

**`INSTALL_FAILED_VERSION_DOWNGRADE`** — the APK's `versionCode` is lower than
what is installed. Uninstall, or bump `versionCode` in `build.gradle.kts`.

**Terminal opens then immediately reports `Permission denied`** — the proot
build products are missing or incomplete, usually because the build ran
without `deps/build_proot.sh` (plain AGP CMake compiles PRoot without the
W^X patches and fails at runtime), or the independent loader files
(`libproot-loader.so` / `libproot-loader32.so`) were not produced — without
them PRoot falls back to extracting its embedded loader into the app's
noexec cache directory and dies within ~20 ms. Re-run
`./deps/build_proot.sh clean`; CI's verify step (`test -s libproot-loader.so`)
guards against this on the release path.

**App builds but crashes on launch after a sync** — likely a JNI signature
mismatch between new Kotlin and a native lib. `libpty_bridge.so` is still
vendored; `libjieba_jni.so` and `libminis_crash_handler.so` are rebuilt by CI
from `src/main/cpp/`, so a package rename or JNI signature change must land in
the C++ source as well. `adb logcat` will name the missing method; if the
mismatch is in a vendored lib, rebuild it from its source or wait for an
upstream release that matches.

**Gradle runs out of memory** — raise the heap in `src/android/gradle.properties`:
`org.gradle.jvmargs=-Xmx4096m`.
