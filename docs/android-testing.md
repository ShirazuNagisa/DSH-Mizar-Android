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
