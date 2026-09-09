#!/usr/bin/env bash
#
# Refresh the vendored prebuilt native libraries from an official upstream
# release APK.
#
# WHY THIS EXISTS
# ---------------
# This fork does not compile native code through AGP's CMake block (see
# src/android/app/build.gradle.kts — externalNativeBuild is disabled). It ships
# the official arm64-v8a helper libraries (pty_bridge, crash_handler, jieba, the
# c++/androidx/datastore support libs) committed under
# src/android/app/src/main/jniLibs/ instead, and this script refreshes them from
# an upstream release APK.
#
# NOTE: proot is NOT handled here any more. The sandbox engine (libproot.so, its
# loaders and assets/proot-aarch64) is built from source in CI via
# deps/build_proot.sh (deps/proot submodule + vendored deps/talloc), so it must
# never be overwritten with an extracted binary — doing so would defeat the
# reproducible build. Refresh proot by bumping the deps/proot submodule instead.
#
# The consequence: after every `git rebase upstream/main` you MUST re-run this
# script. Upstream's Kotlin may have changed a JNI method signature, and the
# old .so files would then crash at runtime. Kotlin source and these binaries
# have to move together.
#
# USAGE
#   ./scripts/sync_official_binaries.sh              # latest upstream release
#   ./scripts/sync_official_binaries.sh 0.23-preview # a specific tag
#
# Requires: curl, unzip, python3. GITHUB_TOKEN is optional (raises API rate
# limits); the release assets themselves are public.

set -euo pipefail

UPSTREAM_REPO="OpenMinis/OpenMinis"
REQUESTED_TAG="${1:-}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN_DIR="$REPO_ROOT/src/android/app/src/main"
JNI_DIR="$MAIN_DIR/jniLibs/arm64-v8a"
ASSETS_DIR="$MAIN_DIR/assets"

# The exact set this fork vendors. Keep in sync with build.gradle.kts.
# proot (libproot.so, libproot-loader*.so, assets/proot-aarch64) is NOT here:
# it is built from source via deps/build_proot.sh, not extracted.
#
# [pkg-rename] libjieba_jni.so and libminis_crash_handler.so are ALSO excluded:
# their JNI symbol names embed the Kotlin package, and upstream's binaries still
# export Java_com_openminis_app_*. Copying them over our renamed build would
# silently reintroduce UnsatisfiedLinkError. They are rebuilt from source in CI
# by deps/build_jieba.sh / deps/build_crash_handler.sh instead.
JNI_LIBS="
libandroidx.graphics.path.so
libc++_shared.so
libdatastore_shared_counter.so
libpty_bridge.so
"
ASSET_FILES="
alpine-minirootfs.tar
"

auth_header() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    printf 'Authorization: Bearer %s' "$GITHUB_TOKEN"
  else
    printf 'X-No-Auth: 1'
  fi
}

# Explicit template: BusyBox mktemp (Alpine) needs one, unlike GNU coreutils.
# Also don't trust TMPDIR — some sandboxes export a path that doesn't exist.
TMP_BASE="${TMPDIR:-/tmp}"
[ -d "$TMP_BASE" ] && [ -w "$TMP_BASE" ] || TMP_BASE=/tmp
WORK_DIR="$(mktemp -d "$TMP_BASE/minis-sync.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

# --- 1. Resolve the release and its APK asset -------------------------------
# Not /releases/latest: upstream publishes iOS and Android releases to the same
# repo, so "latest" is often an iOS tag with no APK attached. Scan the release
# list instead and take the newest one that actually ships an arm64 .apk.
echo "==> Querying upstream release (${REQUESTED_TAG:-latest Android})"
if [ -n "$REQUESTED_TAG" ]; then
  curl -fsSL -H "$(auth_header)" \
    "https://api.github.com/repos/$UPSTREAM_REPO/releases/tags/$REQUESTED_TAG" \
    -o "$WORK_DIR/release.json"
else
  curl -fsSL -H "$(auth_header)" \
    "https://api.github.com/repos/$UPSTREAM_REPO/releases?per_page=50" \
    -o "$WORK_DIR/releases.json"
  python3 - "$WORK_DIR/releases.json" "$WORK_DIR/release.json" <<'PY'
import json, sys
releases = json.load(open(sys.argv[1]))
for rel in releases:  # API returns newest first
    if any(a["name"].endswith(".apk") and "arm64" in a["name"]
           for a in rel.get("assets", [])):
        json.dump(rel, open(sys.argv[2], "w"))
        break
else:
    sys.exit("No release with an arm64 .apk asset found.")
PY
fi

eval "$(python3 - "$WORK_DIR/release.json" <<'PY'
import json, sys, shlex
rel = json.load(open(sys.argv[1]))
apk = next(
    (a for a in rel.get("assets", [])
     if a["name"].endswith(".apk") and "arm64" in a["name"]),
    None,
)
if apk is None:
    sys.exit("No arm64 .apk asset found in that release.")
print("TAG=" + shlex.quote(rel["tag_name"]))
print("APK_NAME=" + shlex.quote(apk["name"]))
print("APK_URL=" + shlex.quote(apk["browser_download_url"]))
PY
)"

echo "    release : $TAG"
echo "    asset   : $APK_NAME"

# --- 2. Download and unpack -------------------------------------------------
echo "==> Downloading APK"
curl -fsSL -o "$WORK_DIR/official.apk" "$APK_URL"

echo "==> Extracting binaries"
mkdir -p "$WORK_DIR/x"
unzip -o -q "$WORK_DIR/official.apk" 'lib/arm64-v8a/*' 'assets/*' -d "$WORK_DIR/x"

# Verify everything we expect is present BEFORE touching the working tree, so a
# malformed release can't leave the repo half-updated.
missing=0
for f in $JNI_LIBS; do
  [ -f "$WORK_DIR/x/lib/arm64-v8a/$f" ] || { echo "    MISSING lib: $f"; missing=1; }
done
for f in $ASSET_FILES; do
  [ -f "$WORK_DIR/x/assets/$f" ] || { echo "    MISSING asset: $f"; missing=1; }
done
if [ "$missing" -ne 0 ]; then
  echo "ERROR: upstream release layout changed — refusing to update." >&2
  echo "Inspect $APK_NAME by hand and adjust this script." >&2
  exit 1
fi

# --- 3. Copy into the working tree ------------------------------------------
echo "==> Updating vendored files"
mkdir -p "$JNI_DIR" "$ASSETS_DIR"
for f in $JNI_LIBS; do
  cp -f "$WORK_DIR/x/lib/arm64-v8a/$f" "$JNI_DIR/$f"
  printf '    %s  %s\n' "$(sha256sum "$JNI_DIR/$f" | cut -c1-16)" "jniLibs/$f"
done
for f in $ASSET_FILES; do
  cp -f "$WORK_DIR/x/assets/$f" "$ASSETS_DIR/$f"
  printf '    %s  %s\n' "$(sha256sum "$ASSETS_DIR/$f" | cut -c1-16)" "assets/$f"
done

# --- 4. Version alignment (DISABLED) ---------------------------------------
# Historically this section rewrote versionName/versionCode in
# build.gradle.kts to mirror the upstream tag (0.22-preview -> versionCode 22).
# That alignment is obsolete: RikkaMinis owns its own version line now
# (1.0.0 base; CI injects a monotonically increasing versionCode). Rewriting
# the gradle file here would clobber that, so version fields are left alone.
# "Which upstream release are these binaries from" stays answerable through
# the commit message produced by the Report step below.
VERSION_NAME="${TAG}"

echo "==> Version fields left untouched (RikkaMinis owns its version line)."
echo "==> Binaries are from upstream $VERSION_NAME — record that in the commit message."

# --- 5. Report --------------------------------------------------------------
cat <<EOF

Done. Vendored binaries now come from upstream $TAG.

Next steps:
  git diff --stat
  git add -A && git commit -m "chore: sync prebuilt binaries from upstream $TAG"
  git push

Pushing to main triggers the build workflow, which publishes the APK to the
'android-latest' release.

Reminder: if upstream also changed Kotlin code, rebase onto it FIRST, then run
this script, so source and binaries ship as a matched pair.
EOF
