# Contributing

This is an **Android-only fork** of
[OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) that builds a
working APK in CI, publishes it automatically, and carries a small set of
Android-specific product changes on top of upstream (see
[README.md](README.md) — "What this fork changes").

## Where to report what

**Bugs in a feature that exists identically upstream** — a UI problem, a model
provider issue, an agent misbehaving — belong **upstream**, at
[OpenMinis/OpenMinis/issues](https://github.com/OpenMinis/OpenMinis/issues).
If the bug also reproduces in the official upstream build, it is an upstream
issue, not this fork's.

**Issues with this fork's own surface** belong here — the build, the published
APK, or any of the fork-specific product changes listed in the README. For
example:

- The APK from [Releases](https://github.com/logicflow-GYW/RikkaMinis/releases/tag/android-latest)
  fails to install or crashes on launch
- The workflow fails, or a build succeeds but produces a broken APK
- The terminal reports `execve("/bin/sh"): Permission denied` — this points at
  the vendored binaries, which is exactly this fork's territory
- Documentation here is wrong or out of date

Unlike upstream, this repository **does accept pull requests** — for build
tooling, CI, docs and the fork-specific product changes.

## Before opening a build issue

Please include:

- Device model and Android version
- Where the APK came from (Releases, or a local build)
- The failing workflow run URL, if applicable
- `adb logcat` output around the failure, for runtime problems

## Working on the build

Read [BUILDING.md](BUILDING.md) first, particularly the section on why native
code is not compiled — that constraint shapes most of the build configuration
and is easy to break by accident.

Two things to be careful with:

- **Do not commit a keystore.** Signing keys live in the `DEBUG_KEYSTORE_B64`
  repo secret. A committed keystore is a leaked credential.
- **Do not re-enable `externalNativeBuild` casually.** It silently overwrites
  the vendored official binaries with CI-built ones, and the resulting APK fails
  only at runtime, on-device, in a way that looks unrelated.

If you change anything that affects the APK, verify the workflow's
`Verify APK contents` step still passes — it is there to catch precisely this
class of mistake.

## Syncing with upstream

See [docs/SYNCING_UPSTREAM.md](docs/SYNCING_UPSTREAM.md). The short version:
this fork no longer rebases onto upstream — port individual upstream commits by
hand, and refresh the vendored binaries (`./scripts/sync_official_binaries.sh`)
in the same change whenever the port touches a JNI boundary.

## License

Contributions are accepted under the project's
[GPLv3](LICENSE) license.
