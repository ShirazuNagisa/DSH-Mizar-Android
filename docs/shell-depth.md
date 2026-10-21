# Shell depth: the lifted card and the recessed drawers

Android side of the "more layering, more depth" pass on the shell. The reference
direction matches iOS' recent shell work (`7563001`, *Cast the card's shadow from
a shape…*): the card is the lit object, the drawers are set into the frame, and
the shadow is a cheap shape-derived draw rather than an effect re-derived from
animating content.

## The four cues

1. **Lift, don't shrink.** The card used to scale to `0.94` as a drawer opened,
   which reads as pushed away. It now stays at `0.994`; the shadow and the
   drawer's recess do the depth work.
2. **Contact shadow.** `drawerDepth` darkens a 40 dp band at the drawer's inner
   edge, where the card covers it. This is the cue that makes the two surfaces
   read as one above the other.
3. **Recess.** The drawer gets a theme-dependent scrim, a top-down shade and one
   specular edge; in the dark theme the drawer is a full tone below the card.
4. **Glass.** `DrawerGlass` / `CardGlass` add a soft, blurred strip of card-
   coloured light on the edge shared by the two surfaces. It is a real blur of a
   static strip (API 31+); older devices keep the gradient and contact shadow.

## Files

- `ui/shell/ShellDepth.kt` — tokens, `cardScale`, `cardDepth`, `cardRim`,
  `cardSheen`, `DrawerGlass`, `CardGlass`, `drawerDepth`, `columnDepth`.
- `ui/shell/MizarShell.kt` — wires the modifiers on both phone drawers, the
  card, and the standing right column of the wide layout.
- `src/debug/.../DepthPreviewActivity.kt` — debug-only design bench: renders the
  real shell against a fixed session list, no pairing or host needed.

## Reviewing it

```powershell
adb shell am start -n me.shirazu.dshmizar/.DepthPreviewActivity
adb shell am start -n me.shirazu.dshmizar/.DepthPreviewActivity --ez dark true
```

Open the left drawer with the menu button, the right drawer by tapping the
conversation title. For the wide layout, set a wide `wm size` on an emulator
(never on the operator's phone) and reset the recorded size afterwards.

## Notes

- `DrawerGlass`/`CardGlass` use `Modifier.blur` only on Android 12+; below that
  they are no-ops, so the depth degrades to shadow + scrim rather than failing.
- The pre-existing account/subscription work in the working tree is unrelated to
  this pass; this commit touches only the shell files and the debug bench.
