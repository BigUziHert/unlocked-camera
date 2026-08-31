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

1. NEVER rotate the camera, and never rotate the player's body AS the aim
   correction. Better Third Person owns the body and fights back
   (thrash/twitch). The SHOT is corrected only in outgoing packets
   (ClientPacketListenerMixin): the UseItem rotation rewrite plus the
   continuous rotation hold while a ranged item is held. The body may turn
   only as the damped cosmetic follow while drawing (turnPlayerWhileAiming,
   bounded by MIN_AIM_TARGET_DISTANCE / MAX_AIM_STEP / MAX_AIM_PITCH) and in
   the freelook snap — never to make a projectile land right.
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

## Settled by design — do not "fix"

- Entering the camera starts at vanilla's 4-block distance and glides to the
  configured zoom (even when min zoom > 4): Caleb prefers the seamless F5
  transition over an instant jump-cut. (Caleb's explicit call, 2026-08-26.)
- Keybind defaults stay LEFT_ALT (freelook) and X (swap shoulder) despite the
  Create/vanilla overlaps: Create's toolbox radial opens on raw LEFT_ALT only
  with a toolbox in range (server config toolboxRange, default 10) and its
  screen stands freelook down automatically — deliberate coexistence. (Caleb,
  2026-08-30.)
- Known limitation, document-only: with super glue in the OFFHAND, Create
  validates block placement server-side along the eye/body ray
  (SuperGlueHandler.glueInOffHandAppliesOnBlockPlace), and ServerboundUseItemOn
  carries no rotation — shoulder-camera placements near block boundaries can
  ghost/revert or place without glue. Main-hand glue placement is covered.
  (Caleb chose not to mixin Create's server side, 2026-08-30.)
- Entity-scale convention is SCALE-RELATIVE (vanilla's): configured distances
  mean blocks at normal size and scale with generic.scale; the collision-cap
  seeds and the shoulder move/clearance multiply by cameraEntityScale to cross
  into world-space geometry. (Caleb, 2026-08-30.)

All findings from the 2026-08-26 external code review are resolved: the aim
ray sweeps entities; reach is eye-based for world hits (Sable's sublevel-aware
HitResult#distanceTo only for plot-space hits); and config-slider gestures
journal as one composite undo step covering every linked setting they pushed.

The 2026-08-30 review (at fbf1507) is fully absorbed too: all 18 confirmed
findings fixed or deliberately documented above, SPEC-1 verified harmless
against BTP 1.9.0, SPEC-2 hardened with a look-vector identity guard. Compat
rays for Create start at the player's depth on the crosshair ray
(crosshairRayGapFreeOrigin) with raw reach; incoming absolute-rotation
teleports that echo the held aim are restored (TeleportRotationMixin).

Plot-space frames (crosshair anywhere on a ship) must RECOVER, never stand
down: the review's "leave un-modded Create+Sable behavior" residual broke
steering-wheel/throttle targeting on decks — the modpack's core interaction.
Hit-derived Create hooks stay engaged via plotSpaceHitOnCameraRay +
plotAwareHitDistSqr (crosshairHitUsable gates them together), and the
entity-vs-block tie-break in cameraRayPick recovers plot-space ENTITY hits
(item frames on ship walls) the same way — Sable resolves them inside
getEntityHitResult, and its sublevel-aware HitResult#distanceTo lives on the
base class, so it serves entity hits too.

## TACZ (Timeless and Classics Zero) compat

Two mechanisms, both verified against the installed jar
(tacz-neoforge-1.21.1-1.1.8-hotfix-r6):

- Guns need the aim hold for the same reason a bow release does:
  ClientMessagePlayerShoot carries NO rotation, and the server handler fires
  along serverPlayer::getXRot/::getYRot. Guns are UseAnim.NONE and not
  ProjectileItem, so holdingRangedItem recognizes them via TaczCompat's
  reflective IGun check (AbstractGunItem implements IGun).
- With a gun in the main hand TACZ cancels the vanilla crosshair layer and
  draws its own reticle, which bails in third person unless
  ShoulderSurfingCompat.showCrosshair() vouches for it. TaczCrosshairMixin
  answers through that seam (their own third-person-camera hook) so the gun's
  real reticle draws whenever our crosshair would — do not paste a vanilla
  crosshair over it instead.

## Process

Measure first: temporary logging pinned every hard bug here in one round after
speculation failed repeatedly. Audit new code by disabling pieces and
retesting. Keep the mod lightweight — Caleb asks for bloat audits regularly.
