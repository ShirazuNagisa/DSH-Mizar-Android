# Known issues after the 0.10.x UI refactor

These are tracked so the knowledge survives the local `.devstate/` evidence directory. None of
them was introduced by the refactor unless stated; the first three predate it and were only
discovered while verifying the new UI.

## KI-1 — a stopped turn's prompt shows twice in the transcript

- Seen during the flight-overlay verification: after pressing Stop on a long prompt, the client
  showed the prompt again as a second turn ending in an empty "Completed".
- The host log has exactly one aborted turn for that prompt and no re-run; this is a client
  display/projection issue, not a duplicate turn.
- Reproduction is deferred to after the merge: repeat in a fresh session with screenshot and
  logcat, and run the same steps against the pre-refactor APK to confirm it predates the branch.

## KI-2 — a pending question is not rehydrated after reconnect (candidate)

- Seen during the dock capture: a question was still pending on the host, but after the app was
  reinstalled/restarted and reconnected the dock was empty.
- Reproduced on the operator's phone during the 0.10.1 verification: an approval asked at 13:08:06
  while the app's relay link was churning never appeared in the dock, and tapping the session
  refresh button did not bring it back. A fresh request made while the app was foreground arrived
  live, so this is the reconnect/pending path, not the dock UI.
- Expected sibling behaviour: DSH fail-closes a pending approval when the phone answerer goes
  away; that part is by design, not a bug.
- Touches the pending/reconnect path in `AppViewModel`, out of scope for this UI refactor.
- A local harness, `scripts/ki2-host.mjs`, composes the plugin's own listener and broker (fake
  sessions, a localhost control endpoint, hold/release for a second client, and a settle log) so
  an approval or question can be created on cue; use it to reproduce this without the real DSH
  host. It needs the vendored plugin's `node_modules` installed.

## KI-3 — a send at the bottom does not follow when the previous item is taller than the viewport

- At the bottom of a transcript with long replies, sending does not follow the new turn; the list
  stays in place and shows "N new".
- Reproduced identically on the pre-refactor baseline APK, so it predates the branch.
- Likely cause: the follow effect reads `atBottom` after the new items have already been laid
  out. Deferred with KI-1/KI-2.

## Open verification items

- **Interactions after-dock** — verified on the operator's phone in 0.10.1: the Next pill's
  effective target is 80 x 48 dp (0.10.0 measured 66 x 37 dp because the header layout clipped the
  target); taps 20 px outside the target do nothing, taps just inside its overhang cycle the deck,
  and the approval buttons stay 48 dp with the card height unchanged.
- **Background completion notification on a real phone** is not verified. The emulator run covers
  the logic, but the real-device automation kept the app foreground around turn settle (and the
  relay link churned), so no clean background-settle-to-notification run was captured.
- **Path clipping below API 33** is not verified on a device.
- **Real-device pass** is partially done: install/upgrade from a differently signed build,
  re-pairing, relay, cold start, drawer, real screen-corner radius, foreground-suppression and the
  approval/question deck all passed. Still open: background completion notification, FCM push
  where configured, haptics feel, long-running ColorOS foreground-service behaviour.

## Accepted deviations

- The scanner's camera glass uses a quiet, rimless edge instead of the normal glass tokens.
- One English failed-screen capture shows Chinese problem text because the string was written when
  the app was still in Chinese; this is a capture artifact, not a UI bug.
- The English failed screen says "1 sessions"; a pre-existing plural bug, out of scope.
- At 200% font the Pair button sits below the fold; the screen scrolls, so it is reachable.
- `Format.kt` units stay hard-coded because the notification text uses the same formatter.

## Deferred beyond this release

- T6 haptic grammar (needs a real-device round) and T7 answer-fold/peek-stack choreography.
- Content-under-bars and backdrop blur, with their own cold-start and re-anchor regression plan.
- T4 turn-track bracket was deliberately not selected.
