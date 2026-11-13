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

## The five layers

| Layer | Command | What it is for | Cost |
|---|---|---|---|
| Protocol | `:mizarkit:test` | frames, pairing, projection, the log buffer | ~10 s |
| App logic | `:app:testDebugUnitTest` | pure app-side code (the Markdown reveal) | ~20 s |
| UI | `:app:connectedDebugAndroidTest` | Compose behaviour and the visual goldens, only on a device | ~30-80 s for the eleven goldens, 170-340 s for the whole suite (API 36, 2026-11-13; the wide end is a just-rebooted emulator), plus the emulator boot |
| Mock host | `test/mock-host/mock-host.mjs` | a drivable Mizar v1 host for the streaming/lightbox surfaces | manual, seconds |
| Live host | `:mizarkit:hostCheck` | the client against a real plugin listener | manual |

CI splits the UI layer in two: a pull request runs the **subset** that pins the
screens (`VisualSnapshotTest`, `MockHostFixturesTest`, `ConversationUiTest`,
`LiveTurnTest`), and a manual dispatch can ask for `full`. A PR labeled
`instrumentation-full` gets the whole suite; the label exists because the subset is
a default, not a ceiling. See `.github/workflows/android-pr.yml`.

`scripts/test-select.sh` picks the layers a diff can reach: `mizarkit/**` runs the
protocol suite, `app/src/main/kotlin/**/ui/**` adds instrumentation, a resource
change runs `:app:assembleDebug` (resources fail the build, not a test), and a
docs-only diff runs nothing. Anything under `app/src/androidTest/**` -- the
behavioural tests, the goldens in `assets/snapshots/`, the mock-host fixtures in
`assets/mock-host/` -- maps to the device suite, because nothing cheaper can verify
a PNG or a rendered page. `test/mock-host/**` is tooling rather than app code, so it
maps to nothing and `--run` does not pick it up; run its `--selftest` by hand.
`--run` executes the selection.

## Time in tests is virtual

`kotlinx-coroutines-test` is on the app test classpath. Anything that polls or waits
takes a `CoroutineDispatcher` for exactly this reason: hand it a
`StandardTestDispatcher(testScheduler)` and `advanceTimeBy` drives the clock, so
"sixteen polls at a 5 ms interval" is arithmetic instead of a bet on how busy the
machine is. `ConnectionMonitorTest` is the worked example; its ten cases went from
~0.6 s of sleeping to 0.189 s in total.

A real `delay(80)` in a test is a race, and it is the race that made 0.10.6 fail on
CI while passing four times locally. Two rules that go with it:

- **Stop the loop before the test returns.** `runTest` drains the scheduler when the
  body ends, and an endless 5 ms poll loop is drained forever.
- **Assert the number, not the bound.** `polls <= 3` passed with the threshold moved
  from 2 to 3, which was the one thing that test was named after.

## Compiling against android.jar, not the JDK

```sh
./gradlew :mizarkit-android-api:androidApiCheck
```

`:mizarkit` is a plain Kotlin/JVM module compiled by the JDK 17 toolchain, and
**Android lint never looks at a JVM module**. `URLDecoder.decode(String, Charset)`
is a Java 10 addition that Android only gained in API 33, so the compiler was
happy, all 360 kit tests were happy (360 at the 0.10.5 point in time; 363 after 0.10.6 and 457 after
the 2026-11 parity work), and every phone below Android 13 threw
`NoSuchMethodError` the moment a pairing link was decoded. The API 26 smoke test
found it; nothing in the build could have.

`:mizarkit-android-api` compiles the same sources a second time with `-no-jdk`
against `platforms/android-26/android.jar` — the jar for `minSdk`. A method
Android does not have is then a compile error. It ships nothing, has no tests,
and nothing depends on it; the compile is the whole check.

Two things worth knowing if you touch it:

- **`-Xno-jdk` is silently ignored** by the Kotlin 2.x compiler. It prints
  `Flag is not supported by this version of the compiler` as a warning and then
  compiles against the JDK anyway, so the module still reports `BUILD SUCCESSFUL`
  and checks nothing. The flag is `-no-jdk`.
- The check was proved by mutation, not by a green run: adding
  `URLDecoder.decode(s, Charsets.UTF_8)` and `List.of(...)` to a scratch file
  fails it with `Argument type mismatch: actual type is 'Charset', but
  'String!' was expected` and `Unresolved reference 'of'`. Both were removed
  afterwards.

The jar comes from `sdk.dir` in `local.properties`, else `ANDROID_HOME`, at
`platforms/android-26/android.jar`; install it with
`sdkmanager "platforms;android-26"`. `-PandroidApiJar=<path>` overrides the path
and `-PandroidApiLevel=<n>` the level.

## A green build is not a green test run

Gradle prints `BUILD SUCCESSFUL` for a test task that was **up to date**, and an up-to-date
task has not executed a single test. 0.10.3 shipped that way: `ProtocolLogTest` landed
after 0.10.2 was cut, every local `:mizarkit:test` after it was up to date, and the first
machine to actually run those tests was CI — which failed the release on two of them.

Before cutting a release, force the run:

```sh
./gradlew :mizarkit:test :app:testDebugUnitTest --rerun-tasks
```

The same failure is worth remembering as a shape, because it will come back in another
guise. **A test double that answers inside `send`** hands the reply to the reader thread
before the caller's next line runs, so anything the caller records *after* the send lands
after the answer. The protocol log did exactly that and read `IN error:UNAUTHORIZED`
before `OUT sent` — an answer to a request that had not been sent yet. Recording the
attempt before the frame reaches the transport fixed it by construction: the log follows
causality, not thread scheduling.

So when a test fails only sometimes, or only on CI, look first for an assertion about
**order** that a synchronous fake can reorder.

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

## Visual regression: the snapshot suite

`VisualSnapshotTest` renders every page the app owns and compares it, pixel by
pixel, with a committed golden PNG in `app/src/androidTest/assets/snapshots/`. It
replaces "take a screenshot and look at it", which catches a spacing change only
when somebody remembers to look. The harness is `snapshot/Snapshot.kt` (compare,
record, diff) and `snapshot/SnapshotScene.kt` (the pinned rendering environment);
the fixtures are `snapshot/SnapshotFixtures.kt`.

One class, on purpose: a class is the unit of work a device shard takes, and the
tests share no mutable state, so the class runs beside the behavioural tests in any
order. Adding a page is one `@Test` (or one more class using the same helpers), one
recording run, one PNG review, one commit.

### The pinned environment

Every property that a device, a locale or an accessibility setting could vary is
pinned once, in `SnapshotScene`, not by each test:

| What | Pinned to | Why |
|---|---|---|
| density / font scale | `Density(2f, fontScale = 1f)` | dp becomes the same number of pixels on every device |
| locale / resources | `en-US` (`values/`, the default catalogue) | a Chinese emulator and an English CI runner must record the same words |
| theme | `MizarTheme(LIGHT)` | the app's appearance, not the emulator's night setting |
| motion | Reduce Motion on | ambient loops (search pulse, twin star, refresh spinner) rest at one frame instead of racing the capture; one-shot animations are settled by `waitForIdle` |
| frame | 390x844 dp, window insets consumed | the crop is the app's content, not the device's status bar height |
| configuration | `screenWidthDp`/`screenHeightDp`/`densityDpi` pinned | popovers and the approval dock size themselves from these |
| process formatting | `Locale.US` / `UTC` for the run, restored afterwards | `DateUtils` ("0 min. ago") and `DateFormat` read the process defaults |

The system UI is deliberately absent: a golden is the app's content. The status
bar, the keyboard and the notification shade are checked by hand with
`adb screencap`.

### What is covered

| Golden | Surface |
|---|---|
| `answer-card.png` | one assistant answer (the original baseline, kept) |
| `home.png` | home: mark, host name, workspace and model chips, composer |
| `session.png` | session page: app bar, a finished turn (folded tool step, delivered file, branch action), composer, context figures |
| `approval-dock.png` | the approval deck: card behind the front one, position marker, Next pill |
| `sidebar.png` | sidebar: header, search, new-session key, both workspaces expanded, connection footer |
| `settings.png` | the settings directory, at the popover width (272 dp) |
| `context-sheet.png` | the composer's context sheet: the figures, the em dashes for what the host did not send, and the "Compact the context" row (a popover is its own window, so its content is recorded on its own) |
| `account-signed-out.png` | the account form, signed out |
| `account-signed-in.png` | the account profile, devices and invite section, signed in |
| `account-computers.png` | the account's computers: one online with its Open action, one offline with Open disabled |
| `unpaired.png` | the first-run page: the account entry first, the scan code path below it |

The home page's greeting is picked from the current hour ("Good morning" ..
"Good night"), so the band that line sits in is **masked**: the test finds the
greeting's semantics bounds and excludes the full-width row it occupies, because
the four greetings are different widths and a node-width mask would leave the tails
comparable. A mask is not a wider tolerance -- everything else on the page is still
compared pixel by pixel, and a regression that removed the greeting entirely would
change the page around the mask.

Two surfaces are on purpose **not** in the suite yet:

- **Live Updates.** It is a system notification (`LiveTurn`), drawn by the system,
  not by the app; a golden of the shade would depend on the emulator's status bar,
  clock and font. Its contract is asserted by `LiveTurnTest` (progress style,
  ongoing, chronometer, stop action) and its look is checked with `adb screencap`
  and `-e livehold 1`.
- **Motion.** A finished state is snapshot-able; the travel to it is not. Motion
  has its own budgets in `ui/design/Motion.kt` and is judged on a phone.

The process fold and the image lightbox are the two surfaces still to be added:
they arrive with the conversation integration, and the mock host above exists so
their states (mid-stream, fold closed/open, image locked/unlocked) can be pushed
on demand instead of waited for. A new baseline for them is the same three steps as
any other page.

### Running it

The whole device suite (goldens compare, never write):

```sh
./gradlew :app:connectedDebugAndroidTest
```

One device out of two, which is how the two AVDs are used (`mizar35` on 5556,
`mizar36` on 5558);

```sh
ANDROID_SERIAL=emulator-5558 ./gradlew :app:connectedDebugAndroidTest
```

Only the visual class, while iterating on a page:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.shirazu.dshmizar.snapshot.VisualSnapshotTest
```

Measured on `mizar36` (API 36), 2026-11-13: the eleven goldens take **~30-80 s** (warm emulator vs just rebooted), the
whole 55-case suite **165 s** of test execution (3 min wall including an incremental
build). `MockHostFixturesTest` is 0.2 s of that: it decodes fixtures, it does not
draw.

There is one thing the Gradle task is wrong for, and it is recording: it
**uninstalls the app** when it finishes, and that removes the app's external files
directory -- exactly where the goldens and the failure diffs were written. `record`
mode and the CI job therefore install and drive the runner themselves:

```sh
./gradlew :app:installDebug :app:installDebugAndroidTest
adb -s emulator-5558 shell am instrument -w \
  -e class me.shirazu.dshmizar.snapshot.VisualSnapshotTest \
  me.shirazu.dshmizar.test/androidx.test.runner.AndroidJUnitRunner
```

The goldens were recorded on **API 36** (`mizar36`, the visual baseline machine)
and are compared there; the same goldens also pass on `mizar35` today, which is
what makes them worth keeping. A rasteriser change between platforms would show up
as a failure on one of the two, and the fix is to record on the machine the team
agreed on (API 36) and note the difference in the commit.

### Recording (or re-recording) a golden

Recording is explicit and per-run. **A mismatch never rewrites a golden, and a
missing golden is a failure**: a test that passes because it compared nothing
proves nothing.

```sh
# 1. install both APKs; the app must stay installed or the recordings vanish
./gradlew :app:installDebug :app:installDebugAndroidTest

# 2. record on the visual baseline device; nothing is committed by the test
adb -s emulator-5558 shell am instrument -w \
  -e class me.shirazu.dshmizar.snapshot.VisualSnapshotTest \
  -e snapshot record \
  me.shirazu.dshmizar.test/androidx.test.runner.AndroidJUnitRunner

# 3. pull every recording into the test assets (no root needed)
bash test/pull-snapshots.sh emulator-5558

# 4. review what moved, then commit the PNGs with the change that moved them
git status app/src/androidTest/assets/snapshots
git diff --stat app/src/androidTest/assets/snapshots
```

`-e class` accepts a comma-separated list; each class is a shard the runner can
take alone, which is what makes recording one page cheap. A record run takes about
8-80 s for all eleven, depending on the emulator's warmth and whether the APK has to be rebuilt first.

`-e snapshot record` is the only accepted value that changes behaviour: with any
other value (or no argument, which is what CI and a normal run have) the suite
compares. `Snapshot.isRecording` reads the instrumentation argument, not an
environment variable, so it cannot be inherited accidentally from a shell.

Recordings are written to the app's **external** files directory
(`/sdcard/Android/data/me.shirazu.dshmizar/files/snapshots/`) rather than
`filesDir`: `adb pull` can read that without root, and the operator's phone is not
rooted. `test/pull-snapshots.sh` discovers `adb` from `ADB`, `ANDROID_SDK_ROOT`,
`ANDROID_HOME` or `PATH`, pulls only baselines and drops `*.diff.png` artefacts. On
Windows run it from **Git Bash** (`C:\Program Files\Git\bin\bash.exe`): the `bash`
on `PATH` is WSL's, which does not inherit `ADB`/`ANDROID_HOME` from PowerShell.

### When a comparison fails

```text
snapshot: session differs in 1.42% of its pixels (limit 0.40%); 18731 of 1316640
compared, worst channel delta 255 at (312, 470). Actual: /sdcard/.../session.png
Diff: /sdcard/.../session.diff.png. If the change was intended, re-record with
`-e snapshot record`.
```

The comparison tolerates anti-aliasing, not layout: per-channel tolerance 16 and
at most 0.4% of the unmasked pixels may differ. A size change fails before a
pixel is compared, because a frame or layout change is the cheapest signal. The
failure writes the actual image and a **magenta diff** (`<name>.diff.png`, masked
regions grey) next to it; CI uploads them as an artifact, so a red emulator run
comes with the pictures. The other render classes in `androidTest` write their
evidence into their own external directories (`screenshots/`, `features-shell/`);
the CI job pulls those too, so one red run returns every picture it drew.

### Adding a baseline for a new page

The integration flow, for a page Lead has just wired:

1. **Add the test.** In `VisualSnapshotTest`, or a new class in
   `me.shirazu.dshmizar.snapshot`, render the page inside `SnapshotPage` (a full
   screen) or `SnapshotCrop` (content height) and call
   `rule.captureSnapshot("my-page")`. Fixtures go in `SnapshotFixtures.kt`; keep
   timestamps absolute or `@now`-relative (the fixture comment has the rule).
2. **Record and pull.** The two commands above. The first record run also verifies
   the page renders at all -- a crash or an empty frame is visible in the PNG.
3. **Review and commit.** Open the new PNG. It *is* the assertion: a baseline
   recorded from a regression passes forever. Commit it with the page's change.

A page whose layout is still moving under an in-flight integration should be
recorded once that work lands; recording it twice is two reviews of the same
surface, and the second one is the one that matters. The pages already in the
table above will need one re-record when Lead's shell/conversation integration
lands -- that is the normal workflow, not a broken baseline.

### The mock host (drivable fixtures for the new surfaces)

`test/mock-host/mock-host.mjs` is a Mizar v1 host with no dependencies and no
model behind it. The real host cannot be asked to "stream exactly this half
sentence, then show an approval"; this one can, so the process fold, streamed
text, the image lightbox and the Live Updates end-to-end check have a
reproducible screen to look at.

```sh
# from the Android/ repo root: serve on 19470, play the fixture timeline on connect
node test/mock-host/mock-host.mjs --autoplay --fixtures app/src/androidTest/assets/mock-host/fixtures.json

# a phone or emulator pairs through the normal deep link; 10.0.2.2 is the host
# machine's loopback as the emulator sees it
adb -s emulator-5558 shell am start -a android.intent.action.VIEW \
  -d 'mizar://pair?h=10.0.2.2:19470&c=MOCK-1234&n=Mock%20Studio&v=1'

curl http://127.0.0.1:19470/health
```

What it speaks:

| Route | Answer |
|---|---|
| `GET /mizar/v1/hello` | host identity, `pairingOpen: true` |
| `POST /mizar/v1/pair` | `fixtures.pairing.code` (`MOCK-1234`) for a device token |
| `WS /mizar/v1/ws` | welcome on connect, then protocol v1 methods |
| `GET /health` | clients, script state, pairing code |
| `POST /ctl/push` | push one timeline step now |
| `POST /ctl/play` | play a list of steps now |
| `POST /ctl/pending` | replace pending approvals/questions |
| `POST /ctl/event` | push one event (`session.changed`, `turn.settled`, ...) |
| `POST /ctl/reset` | forget pushed state |

Methods answered: `session.list`, `workspace.list`, `session.messages`,
`session.refresh`, `session.stats`, `session.sync`, `session.stream`,
`session.prompt`/`session.create`/`session.cancel`, `interactions.pending`,
`approval.answer`, `question.answer`, `host.info`, `model.catalog`,
`permissions.catalog`, `usage.overview`, `file.stat`, `file.read`. Anything else
answers `NOT_IMPLEMENTED`, which is what the client's additive-method fallback is
for.

**State frames.** A timeline step of type `state` is encoded as the binary
envelope (`u8` session-id length, the id, then the 13-byte `0x53` frame) and pushed
to every connected phone; `flags` selects `snapshot` / `append` / `heartbeat` /
`final`, `interval` is the test's, and `"textSeq": "auto"` makes the host compute
the append offset an interval's text actually ends at. A frame that carries body
text is marked `snapshot` **and** `append` (or `append` alone for a continuation);
`snapshot` without `append` is rejected by the decoder as a contradiction, which is
the kind of thing a hand-written producer gets wrong and `MockHostFixturesTest`
catches before a device does. The fixtures' timeline (`fixtures.json`) is a worked
example: thinking, a heartbeat, narration, the answer, `final`, `COMPLETED`,
`turn.settled`, then a pending approval.

`file.read` serves a generated gradient PNG for the lightbox and a text file for
the preview, so neither needs a binary committed.

**Selftest.** `node test/mock-host/mock-host.mjs --selftest` starts the host on an
ephemeral port and speaks to it as the app does -- hello, pair, WS handshake, a
method call, a pushed state frame -- and exits non-zero on the first disagreement.
Run it after touching the host; it catches the class of bug that otherwise shows
up only on a booted device.

**The instrumentation contract.** `MockHostFixturesTest` reads the same fixtures
from `androidTest/assets/mock-host/` and decodes them with the production kit
code: the record fixture must project into a finished turn with its prompt,
answer, folded tool call and delivered file; the pending/stats/catalog fixtures
must decode; every emitted state frame must decode through `StateFrameEnvelope`
and land in a `StateChannel` with the state, interval, flags and text the timeline
declared. `frames.json` is generated by the host (`--emit-frames`) and committed;
regenerate it whenever the timeline changes:

```sh
node test/mock-host/mock-host.mjs \
  --emit-frames app/src/androidTest/assets/mock-host/frames.json
```

That test is what keeps a hand-written JS encoder and a Kotlin decoder honest
without a phone: edit the emitter and the next instrumentation run fails on the
byte that moved.

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

CI's emulator step deliberately leaves animations **on** (`disable-animations:
false`): the typewriter tests depend on the reveal not jumping to its last frame.
The snapshot harness does not rely on the device-wide setting for its own
determinism -- `SnapshotScene` forces Reduce Motion inside the composition it
renders.


