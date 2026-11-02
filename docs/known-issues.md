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

**Fixed.** Reproduced on the phone at 0.10.6, fixed and re-checked on the same session.

- Symptom: at the bottom of a transcript with a long reply, sending left the list in place and
  showed "1 new message" instead of following the turn just sent.
- Cause: `atBottom` is `!list.canScrollForward`, a *layout* fact. Appending an item makes
  `canScrollForward` true by itself, so by the time the follow effect ran the list reported "there
  is more below" even though the operator never moved. Whether that landed before or after the new
  item was measured is why a short previous item usually followed and a tall one did not. Opening
  the keyboard produced the same result by shrinking the viewport.
- The sharper form: `following` was set **only** inside that effect, so the app did not know the
  operator had scrolled away until the next item arrived — the count was one item late even where
  it looked like it worked.
- Fix: `following` is now set when a scroll ends (`snapshotFlow { list.isScrollInProgress }`), never
  after an append: a scroll that ends at the end means follow, anywhere else means don't. The
  "back to latest" pill also sets it, so asking for the end is asking to follow again. `atBottom`
  stays where it belongs — deciding whether the pill is visible.

## KI-4 — a finished turn shows "深度求索中~" while the next one starts

- Seen on the phone at 0.10.6 while sending a prompt from the bottom of a transcript: the turn
  before it had already ended, yet it was drawn as running until the new turn landed. It corrected
  itself on its own, so this is a transient mis-attribution, not a stuck state.
- `SessionScreen.kt` decides it like this:

  ```kotlin
  val inFlight = lastTurn != null && (lastTurn.isRunning || lastTurn.isPending) && (sessionRunning || recent)
  val runningTurnId = lastTurn?.takeIf { inFlight || it.isRunning }?.id
  ```

  "This turn is running" is therefore derived from the *session-level* `running` flag and hung on
  whatever the last turn happens to be. In the window between "the session says running" and "the
  new turn appears in `state.turns`", the last turn is still the previous one, so the previous one
  wears the marker.
- Same shape as KI-3: a coarse signal answering a fine-grained question, so the answer lands on
  the wrong item. The difference is that this one is visible for a second and then fixes itself,
  which is why it went unnoticed until a send was watched closely.
- Not fixed. The rule that would fix it is "a turn is running only if the host says *that turn* is
  running, or it is the newest and has no end"; the second half is what `isPending` already means,
  so the work is deciding what to do while the new turn is still only optimistic — which is the
  same question KI-3 answered for scrolling, and it deserves the same treatment rather than a
  quick comparison change.

## KI-5 — the live card stays on "电脑正在处理…" while the app is in the background

Reported on the phone at 0.10.6: a turn finishes while the app is backgrounded, the notification
keeps counting, and only opening the app shows that the work is done.

`LiveTurnCoordinator` has exactly two ways to take the card down:

- `forget(sessionId)` — the host's `turn.settled` reached the app.
- `sync(rows)` — the session list says nothing is running any more. This is the fallback the code
  itself calls "the path that covers a host too old to broadcast `turn.settled`".

Both need something the background app is evidently not doing: `forget` needs the socket frame to
be read and routed while the UI is gone, and `sync` needs the session list to be re-read after the
turn ends — but the sessions poll is the one that only runs while something *is* running, so the
last read is the one before the settle. When the turn ends, the poller stops and nobody takes the
card down.

So the fix is not "hide the card sooner"; it is to make one of those two paths run in the
background: either keep routing `turn.settled` with the UI gone, or re-read the list once when the
poller stops. Which one is right depends on whether the socket is actually read in the background,
which has not been measured yet.

Same session also reported the completion notification arriving late. That may be the same cause:
the completion notification is posted by the settle path, so a settle the app does not process in
the background delays the notification until the app is opened.

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

## Deliberately not done (0.10.4)

- **Hard sign-in gate.** iOS opens on a welcome screen and will not go further
  without an account; Android keeps the account optional and every free feature
  works without one. That is a product decision, not an unfinished port: turning
  the gate on changes who can use the app, so it waits for a decision rather than
  a sprint. If it is taken up, the work is one screen in front of `RootScreen`
  plus the copy that says why, and the same `AccountPhase` the account screen
  already has.
- **In-app purchase.** The paid plan is granted by the service (invite codes,
  admin) and by nothing else on Android, and that is the plan for now. Google Play
  billing is **not** the intended route: the audience this app serves barely has
  Play services, so a Play-only purchase button would be a door most of them
  cannot open. The candidates, in the order they are worth doing:
  1. **Sell codes, redeem in-app.** Nothing to build: `invite/redeem` already
     turns a code into paid days. A shop page or a chat message can hand one out.
  2. **WeChat Pay / Alipay H5.** The relay creates an order, the phone opens the
     payment page, the provider's callback credits the account. Needs a merchant
     account and a filed domain; the client half is one "open this URL, then poll
     `/me`" — no SDK.
  3. **Chinese store billing** (Huawei/Xiaomi/OPPO/vivo): one integration and one
     server verifier per store. Only worth it if the app ships through one.

## Deferred beyond this release

- T6 haptic grammar (needs a real-device round) and T7 answer-fold/peek-stack choreography.
- Content-under-bars and backdrop blur, with their own cold-start and re-anchor regression plan.
- T4 turn-track bracket was deliberately not selected.
