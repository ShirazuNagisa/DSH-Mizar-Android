# Known issues after the 0.10.0 UI refactor

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
- Expected sibling behaviour: DSH fail-closes a pending approval when the phone answerer goes
  away; that part is by design, not a bug.
- Touches the pending/reconnect path in `AppViewModel`, out of scope for this UI refactor.

## KI-3 — a send at the bottom does not follow when the previous item is taller than the viewport

- At the bottom of a transcript with long replies, sending does not follow the new turn; the list
  stays in place and shows "N new".
- Reproduced identically on the pre-refactor baseline APK, so it predates the branch.
- Likely cause: the follow effect reads `atBottom` after the new items have already been laid
  out. Deferred with KI-1/KI-2.

## Open verification items

- **Interactions after-dock**: the Interactions pass changed the deck's Next pill and card
  height; the after frame and the >=48 dp touch-target measurement are still to be captured the
  next time a real pending approval/question appears (the pre-change measurements are recorded in
  `.devstate/`).
- **Path clipping below API 33** is not verified on a device.
- **Real-device pass** (ColorOS foreground-service behaviour, FCM push where configured, real
  screen-corner radius, haptics) is deferred to a separate session on the operator's phone.

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
