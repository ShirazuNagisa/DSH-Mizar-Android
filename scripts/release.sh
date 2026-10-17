#!/usr/bin/env bash
# Build a signed release APK and stage it as dist/dsh-mizar-<version>.apk.
#
# Signing comes from keystore.properties at the repo root (gitignored), or from the
# MIZAR_* variables in the environment, which is how CI does it. The script refuses to
# produce an unsigned APK: an unsigned release cannot be installed over an earlier
# one, so shipping it silently would be worse than failing here.
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
: "${GRADLE_USER_HOME:?set GRADLE_USER_HOME (keep it away from the plugin repo's cache)}"

./gradlew :app:assembleRelease --no-daemon

apk=app/build/outputs/apk/release/app-release.apk
[ -f "$apk" ] || { echo "no release apk produced" >&2; exit 1; }

if ! apksigner verify --print-certs "$apk" >/dev/null 2>&1; then
  if ! "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools/35.0.0/apksigner" verify "$apk" >/dev/null 2>&1; then
    echo "the release apk is not signed -- set keystore.properties or MIZAR_* env" >&2
    exit 1
  fi
fi

mkdir -p dist
cp "$apk" "dist/dsh-mizar-${version}.apk"
echo "dist/dsh-mizar-${version}.apk"