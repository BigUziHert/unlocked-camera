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
   prefer lang-only changes). The undo journal snapshots RAW config doubles
   (LinkedSliderRange.rawOwn / Linked.raw) and pushes compare in config
   units — a hand-edited off-grid value (maxZoom 12.3 on the half-block
   grid) must come back exactly from Undo, never as its stepped neighbour.
8. Render-level targeting order: vanilla's renderLevel picks BEFORE
   Camera#setup, so a camera-ray pick there reads the previous frame's pose
   and the outline trails the camera at low fps. GameRendererMixin defers
   that one pick to right after setup while the crosshair aim is active —
   never a second pick, never a second setup (smoothing would advance
   twice). Sable wraps the original call to push interpolated sublevel
   poses; the deferred call re-creates that through SableCompat's pose
   bridge, and if that bridge can't resolve the pick keeps vanilla order:
   a one-frame-stale outline beats jittering ship outlines.
9. Teleport echo restore (TeleportRotationMixin) matches incoming absolute
   rotations against the LIVE hold first (current/last-sent aim, no age
   bound: the server never ages a stamp out, and an unmoving player sends
   nothing for minutes) and then a bounded history of aims ACTUALLY
   transmitted (every rewrite and tick send calls recordTransmittedAim;
   128 entries / 5 s) for corrections in flight while turning or standing
   down. Reset per LocalPlayer instance so a respawn rotation always
   applies, and continuousAimAngles never stamps a player the cache wasn't
   computed for. A restore with no hold running re-sends the restored
   rotation once, or client and server disagree until the body next turns.
   Capture and restore happen only on the client thread — handleMovePlayer
   is entered first on netty, where ensureRunningOnSameThread re-schedules
   it, and a netty-side capture raced the main-thread pair.

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
base class, so it serves entity hits too. crosshairAimAngles recovers its
entity sweep the same way (2026-09-12 review): a sublevel entity's location
AND bounding box are plot-space, so the aim nudges along the ray into the
entity instead of toward a centre in another frame.

The 2026-09-12 review (at ec097e8) is absorbed: all 7 findings confirmed
against source/bytecode and fixed (rules 7–9, the TACZ mounted gate, the
honey glue hover range, the plot-space entity aim).

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
- Mounted riders: the passenger gate narrows the hold to the actual draw
  (isUsingItem) so steerable mounts aren't twisted toward the crosshair for
  other players. Guns never start a vanilla item use, so
  TaczCompat.isGunEngaged is the gun's "draw": shoot key down, aiming down
  sights, charging, or shot cooldown running (IClientPlayerGunOperator +
  ShootKey.SHOOT_KEY, reflective). TACZ sends its shoot packet from
  ClientTickEvent.Post — after our Pre-tick hold and the passenger PosRot
  stream — so even the first semi-auto click is stamped before it fires.

## Simulated honey glue

HoneyGlueClientHandler.getHitResult scales the view vector by the reach
attribute (setback added at the attribute: R + s). updateHovered multiplies
the attribute by FIVE first and hands the product to Create's RaycastHelper
as the hover ray length, so the setback goes on at that call (5R + s) — at
the attribute it became 5(R + s) and hover/deletion reached four setbacks
too far.

## Ping Wheel compat

Verified against Ping-Wheel-1.12.2-neoforge-1.21.1: pings raycast from the
camera entity's eye along its view vector (PingController.performPingAction
-> Raycast.traceDirectional, plus the Distant Horizons traceDistantAsync
fallback). PingWheelPingMixin redirects the direction, PingWheelRaycastMixin
the origin (crosshairRayGapFreeOrigin — raw range stays first-person reach,
nothing in the camera-player gap is pingable) and the entity search box's
separate view-vector read. Ping Wheel's own Sable projection runs after the
redirected ray, untouched.

## Process

Measure first: temporary logging pinned every hard bug here in one round after
speculation failed repeatedly. Audit new code by disabling pieces and
retesting. Keep the mod lightweight — Caleb asks for bloat audits regularly.
