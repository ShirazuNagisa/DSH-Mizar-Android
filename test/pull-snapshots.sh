#!/usr/bin/env bash
#
# Pull the PNGs a `-e snapshot record` run wrote on the device into the test assets.
#
# Run it as `bash test/pull-snapshots.sh [serial]`; it lives in `test/` because it is
# suite tooling, not an app script.
#
# Record, then pull, then look:
#
#   ./gradlew :app:installDebug :app:installDebugAndroidTest
#   adb -s emulator-5558 shell am instrument -w \
#     -e class me.shirazu.dshmizar.snapshot.VisualSnapshotTest \
#     -e snapshot record \
#     me.shirazu.dshmizar.test/androidx.test.runner.AndroidJUnitRunner
#   test/pull-snapshots.sh emulator-5558
#   git status app/src/androidTest/assets/snapshots   # review every changed PNG
#
# `am instrument` and not `:app:connectedDebugAndroidTest`: the Gradle task
# uninstalls the app when it finishes, which deletes the external files directory
# the recordings are written to.
#
# The goldens are the assertion, so a reference recorded from a regression will
# pass forever. Open the PNGs in the diff before committing them; `git diff
# --stat` says which ones moved, and an image viewer says whether the move was the
# point of the change.
#
# The recordings are written to the app's *external* files directory on purpose:
# `adb pull` can read `/sdcard/Android/data/<package>/files/...` without root, and
# the operator's phone is not rooted. A failed comparison writes the actual image
# and a `<name>.diff.png` next to it; those failure artefacts are dropped here so a
# bad run cannot quietly become a baseline.
#
# The device is chosen by the first argument, else ANDROID_SERIAL, else adb's only
# attached device. `ADB=/path/to/adb` overrides discovery (ANDROID_SDK_ROOT and
# ANDROID_HOME are tried, then PATH).

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
serial=${1:-${ANDROID_SERIAL:-}}
remote=/sdcard/Android/data/me.shirazu.dshmizar/files/snapshots
target="$repo_root/app/src/androidTest/assets/snapshots"

find_adb() {
    if [[ -n "${ADB:-}" ]]; then
        printf '%s\n' "$ADB"
        return
    fi
    local root
    for root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
        if [[ -n "$root" && -x "$root/platform-tools/adb" ]]; then
            printf '%s\n' "$root/platform-tools/adb"
            return
        fi
        if [[ -n "$root" && -x "$root/platform-tools/adb.exe" ]]; then
            printf '%s\n' "$root/platform-tools/adb.exe"
            return
        fi
    done
    if command -v adb >/dev/null 2>&1; then
        printf 'adb\n'
        return
    fi
    echo "pull-snapshots: adb not found; set ADB, ANDROID_SDK_ROOT or ANDROID_HOME" >&2
    exit 2
}

adb=$(find_adb)
device=()
if [[ -n "$serial" ]]; then
    device=(-s "$serial")
fi

if ! "$adb" "${device[@]}" get-state >/dev/null 2>&1; then
    echo "pull-snapshots: no usable device${serial:+ ($serial)}; start an emulator or pass a serial" >&2
    exit 2
fi

# The directory may not exist when nothing was recorded; `ls` on a missing path is
# reported by adb as an error, so count first and fail with the record command.
count=$("$adb" "${device[@]}" shell "ls $remote/*.png 2>/dev/null | wc -l" | tr -d '\r')
if [[ "${count:-0}" -eq 0 ]]; then
    echo "pull-snapshots: no recordings at $remote" >&2
    echo "  record first: ./gradlew :app:installDebug :app:installDebugAndroidTest" >&2
    echo "    adb shell am instrument -w -e class me.shirazu.dshmizar.snapshot.VisualSnapshotTest \\" >&2
    echo "      -e snapshot record me.shirazu.dshmizar.test/androidx.test.runner.AndroidJUnitRunner" >&2
    exit 1
fi

mkdir -p "$target"
"$adb" "${device[@]}" pull "$remote/." "$target/" > /dev/null
# Failure artefacts are not baselines: a `.diff.png` is a report, and the actual
# image of a failed comparison is replaced by the next good recording. Drop them so
# a review never has to decide whether one is meant to be committed.
shopt -s nullglob
for artifact in "$target"/*.diff.png; do
    rm -f "$artifact"
done
shopt -u nullglob

echo "pull-snapshots: pulled ${count} recorded image(s) into app/src/androidTest/assets/snapshots"
echo "  review them before committing:"
git -C "$repo_root" status --short -- app/src/androidTest/assets/snapshots | sed 's/^/    /'
echo "  then commit the goldens with the change that moved them."
