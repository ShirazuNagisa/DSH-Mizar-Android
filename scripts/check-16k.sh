#!/usr/bin/env bash
#
# Is this APK allowed on a 16 KB page-size device?
#
# Android 15+ devices can boot with 16 KB memory pages; a native library built for
# 4 KB pages cannot be mapped there. The two things an APK controls are both
# checkable without a device:
#
#   1. every 64-bit .so has LOAD segments aligned to 0x4000 (16 KB);
#   2. the archive is zipaligned for 16 KB (`zipalign -P 16`).
#
# The debug APK is the one CI builds, so it is the one checked. A failure here is a
# dependency or Gradle/NDK regression, not something the UI can work around.
#
# Usage: scripts/check-16k.sh [apk] [zipalign]
set -euo pipefail

apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
zipalign=${2:-${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/36.0.0/zipalign}

if [[ ! -f "$apk" ]]; then
  echo "no APK at $apk -- build :app:assembleDebug first" >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Extract everything: a pattern limited to lib/ behaves differently between the
# Info-ZIP on a Linux runner and the one bundled with Git for Windows.
unzip -q "$apk" -d "$work"

fail=0
for abi in arm64-v8a x86_64; do
  dir="$work/lib/$abi"
  [[ -d "$dir" ]] || continue
  for so in "$dir"/*.so; do
    [[ -e "$so" ]] || continue
    # readelf is binutils; the NDK ships one under llvm-readelf if that is all there is.
    readelf_bin=$(command -v readelf || true)
    if [[ -z "$readelf_bin" ]]; then
      ndk=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1 || true)
      readelf_bin=${ndk:-}
    fi
    if [[ -z "$readelf_bin" ]]; then
      echo "skip: no readelf, cannot verify $so" >&2
      continue
    fi
    # Field 2 of a -lW LOAD line is the offset; the alignment is the last column.
    while read -r align; do
      if [[ "$align" != "0x4000" ]]; then
        echo "FAIL: $(basename "$so") ($abi) has a LOAD segment aligned $align, want 0x4000" >&2
        fail=1
      fi
    done < <("$readelf_bin" -lW "$so" | awk '$1 == "LOAD" { print $NF }')
  done
done

if [[ $fail -ne 0 ]]; then
  echo "16 KB check failed: a 64-bit native library is not 16 KB aligned" >&2
  exit 1
fi

if [[ -x "$zipalign" ]]; then
  if ! "$zipalign" -c -P 16 -v 4 "$apk" >/dev/null 2>&1; then
    echo "16 KB check failed: zipalign -P 16 rejected $apk" >&2
    exit 1
  fi
  echo "16 KB check passed: libs are 0x4000-aligned and the archive is zipaligned"
else
  echo "16 KB check partially passed: libraries are 0x4000-aligned (zipalign not found at $zipalign)"
fi
