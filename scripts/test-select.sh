#!/usr/bin/env bash
#
# Run only the tests a diff can actually affect.
#
# The iOS client got this first (`scripts/test-select.sh`), and for the same
# reason: the full suite is a minute of unit tests plus half an hour of
# instrumentation, so a one-line change to a screen gets checked by the screen's
# own tests and nothing else. The map below is deliberately coarse -- a file is
# assigned to the cheapest layer that can catch its mistakes -- because a selector
# that is wrong in the expensive direction gets ignored.
#
# Usage:
#   scripts/test-select.sh                 # diff against origin/main, print the plan
#   scripts/test-select.sh HEAD~3          # diff against a revision
#   scripts/test-select.sh --run origin/main
#
# Exits 0 when there is nothing to run (a docs-only diff), 1 when the selected
# task fails.

set -euo pipefail

run=0
if [[ "${1:-}" == "--run" ]]; then
    run=1
    shift
fi
base="${1:-origin/main}"

if ! git rev-parse --verify --quiet "$base" >/dev/null; then
    echo "test-select: '$base' is not a revision; pass one, e.g. HEAD~1" >&2
    exit 2
fi

# Changed files: committed against the base, plus anything staged or not.
changed=$( {
    git diff --name-only "$base"...HEAD
    git diff --name-only
    git diff --cached --name-only
} 2>/dev/null | sort -u )

if [[ -z "$changed" ]]; then
    echo "test-select: no changes against $base — nothing to run"
    exit 0
fi

tasks=()
add() {
    local task=$1
    for existing in "${tasks[@]:-}"; do
        [[ "$existing" == "$task" ]] && return
    done
    tasks+=("$task")
}

while IFS= read -r file; do
    case "$file" in
        # The protocol module and the shared golden frames: the fast layer, and the
        # one place a frame change is actually pinned.
        mizarkit/*|protocol/*)
            add ":mizarkit:test" ;;
        # Gradle wiring can change every module's output; run everything cheap.
        *.gradle.kts|gradle/*|gradle.properties|settings.gradle.kts)
            add ":mizarkit:test"
            add ":app:testDebugUnitTest"
            add ":app:assembleDebug" ;;
        # Resources and the manifest fail the build, not a test.
        app/src/main/res/*|app/src/main/AndroidManifest.xml)
            add ":app:assembleDebug" ;;
        # UI code: unit tests for the pure parts, instrumentation for the rest.
        app/src/main/kotlin/*/ui/*|app/src/androidTest/*)
            add ":app:testDebugUnitTest"
            add ":app:connectedDebugAndroidTest" ;;
        app/src/main/*)
            add ":app:testDebugUnitTest"
            add ":app:assembleDebug" ;;
        # Docs, scripts and screenshots cannot break the app.
        *) ;;
    esac
done <<< "$changed"

if [[ ${#tasks[@]} -eq 0 ]]; then
    echo "test-select: docs-only diff — nothing to run"
    exit 0
fi

command=(./gradlew)
for task in "${tasks[@]}"; do command+=("$task"); done

echo "test-select: ${#tasks[@]} task(s) for $(wc -l <<< "$changed" | tr -d ' ') changed file(s)"
printf '  %s\n' "${tasks[@]}"

if [[ $run -eq 1 ]]; then
    echo "+ ${command[*]}"
    GRADLE_USER_HOME="${GRADLE_USER_HOME:-E:/gradle-home2}" "${command[@]}"
fi
