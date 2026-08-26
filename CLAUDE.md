# Unlocked Camera — project notes for Claude

Client-side NeoForge 1.21.1 mod (mod id `unlockedcamera`): a fourth F5
perspective with scroll zoom, an over-the-shoulder offset, freelook, and
packet-level projectile aim correction. Private repo:
https://github.com/BigUziHert/unlocked-camera

## Build & run

Requires JDK 21. The system JAVA_HOME points at JRE 8, so every shell needs:

    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"     # PowerShell
    export JAVA_HOME="/c/Program Files/Java/jdk-21"     # bash

    .\gradlew.bat build       # jar into build/libs/
    .\gradlew.bat runClient   # dev client (Create, Aeronautics/Sable, BTP, Sodium)

Run the dev client in the background and let Caleb test in-game; he reports
results. Never commit or push without his explicit go — "it works" is not
approval.

## Architecture rules (learned the hard way — do not undo)

1. NEVER rotate the camera or the player's body to correct aim. Better Third
   Person owns the body and fights back (thrash/twitch). All projectile
   correction lives in outgoing packets (ClientPacketListenerMixin): the
   UseItem rotation rewrite plus the continuous rotation hold while a ranged
   item is held.
2. Server mechanism behind the hold: rotation reaches the server only inside
   movement packets; vanilla sends one only when the body turned; and
   crossbows fire along yHeadRot, which lags yRot by a tick — click-time
   syncing always loses. Hold continuously, computed ONCE per tick and cached
   (per-packet recompute on a Sable sublevel tanks fps). Any tick path that
   skips the recompute must dropHeldAim() or stale aim gets stamped forever.
3. Crosshair stays fixed at screen center; shots converge onto it. The
   near-field parallax trilemma (static crosshair / exact impact / first-person
   parity at edges) is settled — don't reopen it. Rejected: floating reticle,
   FP-parallel firing, leaf special-casing.
4. Sable makes all clips sublevel-aware; sublevel hits come back in far-away
   "plot space". Never walk/re-clip from a plot-space hit (frozen game — see
   gapWalkClip's guard); measure reach with HitResult#distanceTo, which Sable
   overwrites to be sublevel-correct.
5. Other mods raycast from the eye along body look; each such site needs its
   own mixin onto the crosshair ray (see the Create/Simulated mixins). When
   targeting misbehaves, check Create's BigOutlines first — it re-picks after
   vanilla and overwrites hitResult.
6. Gate features on targetDistance (chosen zoom), not smoothedDistance
   (animated), or gates flicker mid-transition.
7. Config screen: option order = definition order in ClientConfig; the slider
   and toggle-color mixins gate on translation keys / config key names — keep
   them in sync if keys ever change (renaming TOML keys resets saved configs;
   prefer lang-only changes).

## Process

Measure first: temporary logging pinned every hard bug here in one round after
speculation failed repeatedly. Audit new code by disabling pieces and
retesting. Keep the mod lightweight — Caleb asks for bloat audits regularly.
