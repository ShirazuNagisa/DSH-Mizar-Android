# Instrumentation tests and the phone's pairing

`:app:connectedDebugAndroidTest` installs the debug variant. The debug build signs
with the release key when `keystore.properties` is present, but Android Gradle
Plugin still reinstalls the app around the run; on the paired phone this has
cleared the app data (pairing, cached transcripts).

Run connected tests on an emulator or a second, unpaired device. On the operator's
phone, prefer JVM tests (`:mizarkit:test`) and manual `adb` checks; if an
instrumentation run is unavoidable, expect to scan the pairing QR again afterwards.

`AccountUiTest` follows the same rule: it builds `UiState`/`AccountUiState` by hand
and never signs in against a real service, so it needs no pairing and no network.

## The four layers

| Layer | Command | What it is for | Cost |
|---|---|---|---|
| Protocol | `:mizarkit:test` | frames, pairing, projection, the log buffer | ~10 s |
| App logic | `:app:testDebugUnitTest` | pure app-side code (the Markdown reveal) | ~20 s |
| UI | `:app:connectedDebugAndroidTest` | Compose behaviour that only exists on a device | ~1 min |
| Live host | `:mizarkit:hostCheck` | the client against a real plugin listener | manual |

`scripts/test-select.sh` picks the layers a diff can reach: `mizarkit/**` runs the
protocol suite, `app/src/main/kotlin/**/ui/**` adds instrumentation, a resource
change runs `:app:assembleDebug` (resources fail the build, not a test), and a
docs-only diff runs nothing. `--run` executes the selection.

## Recorded HTTP, for the pairing paths

`mizarkit/.../replay/HttpReplay.kt` replays a JSON recording of real HTTP
exchanges, keyed on method and path (never the host — a recording made against
`127.0.0.1:19390` has to replay against `10.0.2.2:19390`). A request with no
recorded answer is an `AssertionError` naming what was asked for: a replay that
invents a 500 would hide the drift it exists to catch.

Fixtures live in `mizarkit/src/test/resources/replay/`. Re-record one against a
real host:

```sh
MIZAR_RECORD=mizarkit/src/test/resources/replay \
  ./gradlew :mizarkit:hostCheck -Pbase=http://127.0.0.1:19390 -Pcode=ABCD-EFGH
```

`HttpReplayTest` is where the two pairing cases that used to need a phone now run
in milliseconds: a relay pairing whose direct address is dead, and an expired code
that must not be retried through the relay.

## Snapshots

`ConversationSnapshotTest` captures the answer card and compares it with a golden
PNG in `app/src/androidTest/assets/snapshots/`. The comparison tolerates
anti-aliasing (per-channel 16, at most 0.4% of pixels) and reports how far off it
was plus where the new image was written.

The content is laid out under a **fixed density** (`Density(2f)`), so the golden
does not depend on which device runs the suite. A deliberate visual change fails
once, is looked at, and is re-recorded:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.snapshot=record
adb pull /data/data/me.shirazu.dshmizar/files/snapshots/answer-card.png \
  app/src/androidTest/assets/snapshots/answer-card.png
```

## Simulating a wide screen on the operator's phone

`wm size` / `wm density` are device-wide overrides, not app-scoped. Always record the
previous values first and restore those exact values; never `wm size reset` /
`wm density reset` blindly. On a ColorOS phone the density override (480) is a user
setting, and resetting it changes the launcher grid and icon layout for every app.

```powershell
adb shell wm size      # record Physical size / Override size
adb shell wm density   # record Physical density / Override density
adb shell wm size 2400x2560
# ... screenshot ...
# Restore BOTH recorded overrides, never `reset`:
adb shell wm size 1080x2376     # the recorded Override size (may differ on other phones)
adb shell wm density 480        # the recorded Override density
```

## Instrumentation tests also disable system animations

`connectedAndroidTest` leaves the device-wide animation scales at zero:

- `window_animation_scale`
- `transition_animation_scale`
- `animator_duration_scale`

After a run on the operator's phone, restore them explicitly (default is 1.0):

```powershell
adb shell settings put global window_animation_scale 1.0
adb shell settings put global transition_animation_scale 1.0
adb shell settings put global animator_duration_scale 1.0
```
