# Third-Party Licenses

RikkaMinis (this Android-only fork) bundles, links, or depends on the following
third-party components. Versions reflect the current source tree; license types
were verified against each project's repository (GitHub license metadata /
LICENSE files).

> This fork deleted the `src/ios/` tree, so the upstream iOS components (iSH,
> FFmpeg, LAME, the Swift Package Manager dependencies) are **not** part of this
> repository and are deliberately omitted below.

## Native C/C++ dependencies (`deps/`)

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [proot](https://github.com/OpenMinis/proot) (fork) | git submodule `deps/proot` | **GPL-2.0** | Linux sandbox on Android (`libproot.so`, built from source in CI) |
| [talloc](https://talloc.samba.org) (Samba) | vendored at `deps/talloc` | **LGPL-3.0-or-later** | Memory allocator required by proot |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | Android `jieba_jni` (`libjieba_jni.so`, vendored) | **MIT** | Chinese word segmentation (header-only + dictionaries) |
| Alpine Linux minirootfs | committed at `src/android/app/src/main/assets/alpine-minirootfs.tar` | Aggregate of package licenses (musl **MIT**, BusyBox **GPL-2.0**, etc.) | Bundled into the app as the default rootfs |

## Android — Gradle dependencies

| Library | Version | License |
|---|---|---|
| AndroidX / Jetpack (Compose BOM 2025.09.00, core-ktx, lifecycle, activity, navigation, Room, DataStore, security-crypto, browser, webkit, exifinterface) | see `app/build.gradle.kts` | **Apache-2.0** (Google / AOSP) |
| OkHttp | 4.12.0 | **Apache-2.0** |
| kotlinx-serialization-json | 1.7.3 | **Apache-2.0** |
| kotlinx-coroutines-android | 1.9.0 | **Apache-2.0** |
| Coil (coil-compose) | 2.7.0 | **Apache-2.0** |
| Reorderable (sh.calvin.reorderable) | 2.4.0 | **Apache-2.0** |
| ACRA (acra-core) | 5.12.0 | **Apache-2.0** |
| Shizuku API + provider (dev.rikka.shizuku) | 13.1.5 | **MIT** |
| Termux terminal-view (com.termux.termux-app:terminal-view, via JitPack) | 0.118.0 | **Apache-2.0** (the `terminal-view` library; the Termux app project itself is GPLv3) |

Test-only dependencies: JUnit 4.13.2 (**EPL-1.0**), MockWebServer 4.12.0 (**Apache-2.0**), kotlinx-coroutines-test 1.9.0 (**Apache-2.0**), org.json 20231013 (**Public Domain / JSON License**).

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android `app/src/main/assets/katex/` | **MIT** |
| jieba dictionaries | Android `assets/jieba/` | **MIT** (cppjieba distribution) |

## Removed / historical

- **`src/ios/`** — the upstream iOS tree (iSH, FFmpeg, LAME, the Swift packages,
  `swift-markdown-ui`) is deleted from this fork and therefore not distributed
  with RikkaMinis.
