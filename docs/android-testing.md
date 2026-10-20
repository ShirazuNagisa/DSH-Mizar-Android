# Instrumentation tests and the phone's pairing

`:app:connectedDebugAndroidTest` installs the debug variant. The debug build signs
with the release key when `keystore.properties` is present, but Android Gradle
Plugin still reinstalls the app around the run; on the paired phone this has
cleared the app data (pairing, cached transcripts).

Run connected tests on an emulator or a second, unpaired device. On the operator's
phone, prefer JVM tests (`:mizarkit:test`) and manual `adb` checks; if an
instrumentation run is unavoidable, expect to scan the pairing QR again afterwards.
