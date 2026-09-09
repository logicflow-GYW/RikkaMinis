#!/bin/bash
set -e

# ============================================================================
# Jieba JNI Native Build
# ============================================================================
# Cross-compiles jieba_jni.cpp for Android aarch64 using the Android NDK.
# Same rationale as deps/build_crash_handler.sh: externalNativeBuild (CMake) is
# disabled in build.gradle.kts, so this library is NOT compiled by Gradle and
# ships as a vendored prebuilt under
#   src/android/app/src/main/jniLibs/arm64-v8a/libjieba_jni.so
#
# WHY THIS SCRIPT EXISTS (added with the com.openminis.app -> com.rikkaminis.app
# package rename): JNI symbol names are derived from the Kotlin host class's
# fully-qualified name. Renaming the package without rebuilding this .so would
# leave `Java_com_openminis_app_shared_JiebaEngine_*` in the binary while the
# Kotlin side looks for `Java_com_rikkaminis_app_shared_JiebaEngine_*` ->
# UnsatisfiedLinkError at the first Chinese-segmentation call. The binary and
# the Kotlin source MUST move together.
#
# cppjieba is header-only (limonp vendored inline), so a single include root
# (cpp/cppjieba/include) is all that is needed — mirrors the CMakeLists.txt
# target_include_directories(jieba_jni PRIVATE ...) entry.
#
# Output:
#   src/android/app/src/main/jniLibs/arm64-v8a/libjieba_jni.so
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CPP_DIR="$PROJECT_ROOT/src/android/app/src/main/cpp"
CPP="$CPP_DIR/jieba_jni.cpp"
INCLUDE_DIR="$CPP_DIR/cppjieba/include"
JNILIBS_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"
OUT="$JNILIBS_DIR/libjieba_jni.so"

[ -f "$CPP" ] || { echo "ERROR: source not found: $CPP" >&2; exit 1; }
[ -d "$INCLUDE_DIR" ] || { echo "ERROR: include dir not found: $INCLUDE_DIR" >&2; exit 1; }

# Android target — keep in sync with minSdk in app/build.gradle.kts and
# ANDROID_API used by build_proot.sh / build_crash_handler.sh.
ANDROID_API=26
NDK_TRIPLE="aarch64-linux-android"

# ---- Locate NDK (same resolution as build_crash_handler.sh) ----------------
if [ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK_HOME="$ANDROID_NDK_HOME"
elif [ -n "$ANDROID_NDK_ROOT" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    NDK_HOME="$ANDROID_NDK_ROOT"
else
    candidates=(
        "$ANDROID_HOME/ndk/28.0.12433566"
        "$HOME/Android/Sdk/ndk/28.0.12433566"
        "$HOME/Library/Android/sdk/ndk/28.0.12433566"
    )
    NDK_HOME=""
    for c in "${candidates[@]}"; do
        if [ -d "$c" ]; then NDK_HOME="$c"; break; fi
    done
    [ -n "$NDK_HOME" ] || { echo "ERROR: NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
fi

host_tag="$(uname -s)-$(uname -m)"
case "$host_tag" in
    Linux-*) host_bin="linux-x86_64" ;;
    Darwin-*) host_bin="darwin-x86_64" ;;
    *) host_bin="unknown" ;;
esac
TOOLCHAIN_BIN="$NDK_HOME/toolchains/llvm/prebuilt/$host_bin/bin"
CXX="$TOOLCHAIN_BIN/${NDK_TRIPLE}${ANDROID_API}-clang++"
[ -x "$CXX" ] || { echo "ERROR: clang++ missing: $CXX" >&2; exit 1; }
echo "Using clang++: $CXX"

mkdir -p "$JNILIBS_DIR"

# -fPIC -shared == shared library. C++17 for cppjieba. libc++ is dynamic
# (-lc++_shared ships in jniLibs already), matching the previous vendored .so.
$CXX -fPIC -shared -O2 -std=c++17 -stdlib=libc++ \
    -Wl,-soname,libjieba_jni.so \
    -I"$INCLUDE_DIR" \
    "$CPP" -llog -o "$OUT"

echo "Wrote: $OUT"
ls -la "$OUT"

# ---- Self-check: symbols must carry the CURRENT package name ---------------
PKG_EXPECT="${MINIS_EXPECTED_JNI_PKG:-com_rikkaminis_app}"
if ! strings "$OUT" | grep -q "Java_${PKG_EXPECT}_shared_JiebaEngine_nativeInit"; then
    echo "ERROR: libjieba_jni.so lacks Java_${PKG_EXPECT}_shared_JiebaEngine_nativeInit" >&2
    echo "       JNI symbols do not match the Kotlin package — the app would throw UnsatisfiedLinkError." >&2
    exit 1
fi
echo "Symbol check OK: Java_${PKG_EXPECT}_shared_JiebaEngine_*"
