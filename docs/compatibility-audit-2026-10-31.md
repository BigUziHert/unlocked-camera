# Modpack compatibility audit — 2026-10-31

This records source and installed-JAR inspection plus the explicitly identified
runtime checks below. It is not a claim that every item, entity, interaction, or
multiplayer server has passed a play test. Raw runtime results are emitted by the
optional `src/qa` driver in the isolated test instance.

## Installed components inspected

The supplied mods directory contains 177 enabled top-level JARs and one disabled
JAR. Bundled dependencies add further mod IDs. Inspection used the original
files read-only; extracted bytecode reports and the nested Simulated JAR were
written under ignored `build/compat-audit/`.

| Component | Installed version | Static result |
| --- | --- | --- |
| Create | 6.0.10 | Existing BigOutlines, RaycastHelper, contraption, Extendo Grip and chain-conveyor hook sites are present. |
| Simulated, bundled by Create Aeronautics | 1.3.2 | Existing steering-wheel, honey-glue and physics-staff hook sites are present. |
| Sable | 2.0.6 | Seat lookup and render-pose push/pop bridge symbols are present. `HitResult.distanceTo` delegates through Sable-aware entity distance. |
| Ping Wheel | 1.12.2 | Directional trace, distant trace, origin and entity-search direction call sites are present. |
| Carry On | 2.2.6.13 | Inspected pickup/placement handlers consume normal interaction event targets and validate server distance; no separate body-ray selection was found in those handlers. |
| Better Third Person | 1.9.0 | Controls player/camera orientation itself. Pack has `aimPlayerOnInteract=true`; correction must continue to avoid rotating the player as an aiming mechanism. |
| Just Zoom | 2.1.0 | Modifies mouse processing and reports third-person camera types as first person while zooming. Its non-smooth FOV callback can be skipped by Simply Swords' earlier return. |
| Better Combat | 2.4.0 | Independent melee hitboxes used body rotation, bypassing corrected vanilla picks. New optional `BetterCombatTargetMixin` directs the hitbox at the current crosshair target. |
| Simply Swords | 1.70.2 | Normal melee routes through Better Combat. Throwables inspected fire in vanilla item use. Unique/rebound abilities need server head prealignment before the initial key press. |
| Supplementaries | 4.0.2 | Slingshot extends `ProjectileWeaponItem` but uses `UseAnim.NONE`; the ranged hold now recognizes that base class. |
| Cataclysm | 3.33 | Cursed Bow and Wither/Void shoulder weapons inspected use `UseAnim.BOW`, so the existing ranged hold recognizes them. |
| TACZ | Absent | Its optional mixins should remain disabled. This pack cannot validate TACZ gameplay. |

A `javap` contract check found all 24 checked optional-mod method/call sites
present, including the new Better Combat constructor hook. This establishes
that the installed bytecode still offers those injection points; only a launched
transformed client can establish successful injection and resulting behavior.

## Zoom and freelook correction

The installed Unlocked Camera 1.0.0 JAR matches commit `3884f85`: independently
recompiling that source with JDK 21 reproduced all 32 production class files
byte-for-byte. Baseline runs load those original classes and resources with the
same copied pack and QA driver. All 37 original JAR entries were rehashed after
staging to confirm that instrumentation did not replace production code.

The old freelook hook canceled `MouseHandler.turnPlayer` before Just Zoom's
sensitivity and smoothing adjustments, then duplicated vanilla's calculation.
The replacement intercepts only the final `LocalPlayer.turn` call. Both normal
look and freelook now receive the same processed deltas, cinematic smoother state,
inverted-Y handling, and NeoForge turn event; freelook changes only the camera
offset instead of the player's body rotation.

`ZoomMouseProbe` compares actual transformed normal mouse processing against
freelook and transitions across sensitivity, zoom factor, normalization,
smoothing, and inverted-Y settings. The original JAR failed 360 of 768 cases,
with maximum angular discrepancy 10.368965 degrees. The corrected pipeline
passed all 768 cases, with maximum discrepancy 0.0000007153 degrees.

Just Zoom's `CameraType.isFirstPerson` injection also collapsed the unlocked
camera onto the player's eye. A wrapper preserves each camera type's real flag
while the unlocked camera owns the rear view. Camera scroll now yields to Just
Zoom while its key is held, letting that mod change lens magnification without
changing camera distance or hotbar selection. All eight initial perspective and
scroll checks passed after these changes.

The expanded probe invokes real `Camera.setup` and `GameRenderer.getFov`, rather
than inferring zoom from key state. It exposed a second conflict: Simply Swords
1.70.2 unconditionally cancels its `getFov` RETURN callback, even when the FOV is
unchanged, skipping Just Zoom's later callback. With smooth zoom disabled, an
85-degree FOV stayed at 85 for both 0.5 and 0.2 factors. Smooth zoom still produced
42.5 and 17 degrees because it changes renderer state earlier through `tickFov`.

The new FOV wrapper applies the missing non-smooth magnification after all earlier
returns, preserving Simply Swords' numeric FOV adjustments. An observer records
Just Zoom's real `getFovModifier` call; when its callback already ran, the wrapper
leaves the result alone, preventing double magnification. Its cache updates and
1–170 degree clamps match Just Zoom. The fallback covers first person, freelook,
and third person while the camera mod is enabled, and adds no raycasts or entity
queries. The master-disabled path delegates to the original method.

The original JAR failed eight of the 16 expanded third-person checks: perspective,
scroll, both non-smooth FOV factors, and all four detached-pose cases. The final
probe has 26 checks, adding first-person/freelook lens parity and actual-callback
double-zoom checks. The final candidate passed all 26 checks in the copied pack,
including smooth and non-smooth lens zoom in first and third person. Its final
mouse regression rerun also passed all 768 cases, with maximum angular error
0.0000007153 degrees.

## Better Combat correction

`TargetFinder.findAttackTargetResult` built an oriented melee hitbox from
`Player.getXRot/getYRot`. Both the cursor entity and nearby entities subsequently
pass its collision/radial filters. A correct vanilla crosshair pick therefore
did not guarantee a correct sword hit during first-person freelook. BTP's
third-person interaction snap does not cover first-person freelook.

The new mixin changes the hitbox constructor's pitch and yaw together, using the
already-computed crosshair hit. Existing plot-space recovery handles Sable hits.
It leaves the melee origin, dimensions, reach, relation checks and obstacle
filtering intact, adds no raycasts/entity queries, and does not turn the player.
The inspected server packet handler consumes the target IDs and validates
relations/range; it does not re-derive attack direction from body rotation.

`CompatibilityProbe` exercises the actual transformed target finder with
synthetic cursor entities: four freelook directions and an inactive baseline.
It checks hitbox orientation, target selection and unchanged body angles.
Synthetic entities are not added to the world or attacked. Terrain obstruction
is temporarily disabled within this synchronous geometry probe, so it is not
an obstacle/through-wall gameplay test. The probe restores its temporary state.

Executed in the copied full-pack client: all five Better Combat cases passed.
The four active hitbox axes matched the expected direction to a dot product of
1.0 (floating-point rounding on one case); each synthetic target was selected,
and the inactive case retained body-aligned behavior. The Sable reflection
bridge resolved to ready state 2 and successfully invoked an action inside its
render-pose push/pop wrapper. The copied server world contained one real Sable
sublevel with eight Create seats.

## Executed interaction checks

The copied full-pack client successfully picked stone, glass, oak fence and a
Create gearbox. The actual transformed Ping Wheel directional ray and Create
trace helper agreed with each selected fixture. These are ray-target checks,
not assertions about a remote player's received ping marker.

A real entity ping subsequently completed the client-to-server-to-client
round trip: the echoed marker identified the exact zombie UUID under the
shoulder crosshair, despite a deliberate 90-degree body deflection during the
ping call. Entity pings anchor to the entity rather than the surface hit point,
so this oracle compares UUIDs instead of requiring identical marker coordinates.

Real Better Combat attack requests in first-person freelook, with body yaw 0
and camera yaw 75 degrees, damaged server-side cows using a diamond sword
(100 to 93 health), Simply Swords iron rapier (100 to 95.5 health) and iron
chakram (100 to 95 health).

Real item-use/release packets spawned server projectiles for the vanilla bow,
vanilla crossbow, Aether Phoenix Bow, Deep Aether Storm Bow, Cataclysm Cursed
Bow and Twilight Forest Tri-Bow. Initial trajectory direction dots exceeded
0.9997 for their central projectiles. The expected aim differed from the body
direction by about 11.7 degrees (dot 0.9792), so body-aligned shots would fail
the probe's 0.995 threshold. Multi-shot weapons also emitted their expected
side projectiles. This checks launch direction, not ballistic drop or long-range
impact accuracy.

The expanded run also passed real snowball, trident, Simply Swords iron spear,
Create potato cannon, Aether golden dart shooter and Bumblezone Stinger Spear
launch checks. Those fixtures supplied their actual required ammunition.

On the actual Sable sublevel, a Create seat was selected in plot coordinates;
Ping Wheel selected the same block and reconstructed aim remained finite with
3.31-block reach. A real use-item-on request mounted the seat, Sable takeover
disabled the unlocked camera, and dismount restored it. Moving-sublevel and
performance results belong to the separate benchmark report.

The first Carry On run exposed two fixture errors: the user's unbound carry key
requires actual sneak input, and the initial chest center was outside Carry
On's independent 2.5-block pickup limit. The corrected probe supplies sneak
input and a closer fixture. Its placement assertions require a successful
pickup/removal first; a cow that was never picked up cannot count as a pass.
The corrected run passed chest pickup, chest placement, cow pickup and cow
placement through actual client requests and server state changes.

## Additional runtime failures and corrections

The real Supplementaries slingshot release initially launched along the body
direction: dot 0.9807 against the required camera aim, failing the 0.995 oracle.
Its custom `UseAnim.NONE` excluded it from the old ranged-item predicate.
Click-time item use briefly corrected server rotation, but that correction was
restored long before the charged release. Recognizing `ProjectileWeaponItem`
keeps the existing once-per-tick aim hold active for custom-pose launchers.
The corrected full-pack rerun passed the slingshot's real server projectile
launch, with direction dot 0.99917 against the expected camera aim.

A real rebound Magispear activation initially used a body-facing fallback ray.
Eligibility-only tests had passed, but `LivingEntity.getViewYRot(1)` reads server
`yHeadRot`, which follows
received movement rotation on a later server tick. Starting the hold on the
first ability key press therefore arrives too late. The correction prealigns
while a Simply Swords ability weapon is equipped, as it already does for bows
and crossbows. Ordinary melee weapons still do not enable this hold. Ability
weapons now intentionally incur the existing cached aim computation once per
client tick; the narrower engaged-key/channel gate remains for vehicle riders.
That mounted gate intentionally does not prealign while idle, to avoid turning
steerable mounts. Instantaneous first-press abilities on ordinary vehicles can
therefore still encounter head-rotation lag and are not covered by the standing
Magispear test. Sable seats hand camera control back to Sable entirely.

The earlier cow result is rejected because the pack ignores passive ability
targets and its loose distance oracle accepted a fallback point.
The strict rerun passed with an eligible zombie: the real server execution
context selected that exact UUID on the first rebound-key press, with shoulder
offset 0.95 and freelook disabled. Its horizontal center error was 0.0 blocks
against the required threshold of 0.001. The fixture confirms the installed
ability filter accepts the zombie and clears blocks placed by the preceding
slingshot test before activation.

Ping Wheel's normal standing trace uses `VISUAL/NONE`; crouching uses
`OUTLINE/ANY`. A real Sable-seat ping initially failed an oracle comparing it to
vanilla's `OUTLINE` hit. Create's seat has distinct outline and collision shapes,
so its ping point correctly lay deeper/lower on the same camera ray. The QA
oracle now independently clips with Ping Wheel's actual shape/fluid policy
before comparing the server-echoed marker. No production change was made for
that fixture discrepancy.

## Weapon inventory scope

A recursive inheritance scan covered 72,912 unique classes, including bundled
JARs. It found no `CrossbowItem` subclass and no separate modded-crossbow item
translation in this installed pack. The vanilla crossbow was exercised with
the pack loaded; YUNG's Cave Biomes supplies a mixin affecting that item.

Additional distinct projectile implementations present include Create's
`potato_cannon`, Supplementaries' `slingshot`, Aether's `golden_dart_shooter`
(plus poison/enchanted variants), Bumblezone's `stinger_spear` and
`crystal_cannon`, and Powergrid's `electrozapper`. Simply Swords' `iron_spear`
uses `SimplySwordsThrowableItem`; its `iron_chakram` is a melee
`SimplySwordsSwordItem`, despite the weapon name. Translation entries for
optional Simply Swords integrations do not by themselves establish registered
items; runtime registry lookup is authoritative.

## Runtime cases required for complete coverage

- **Blocks and entities:** near/far reach, thin shapes, glass, foliage, doors,
  item frames, small entities, wall occlusion, shoulder swapping and camera
  collision, including blocks between camera and player.
- **Sublevels:** repeat on a stationary and moving Sable deck; inspect hull
  outlines, steering wheel versus adjacent lever, throttle, seated camera
  takeover/dismount, physics-staff hover/drag, and honey-glue hover/use.
- **Carry On:** empty-hand pickup of chest and creature, placement, blocked
  placement, range boundary, and Sable deck pickup/placement.
- **Pings:** block and entity targets, behind-player gap exclusion, Sable
  projection and Distant Horizons fallback.
- **Weapons:** vanilla and modded bow/crossbow release, thrown items, Better
  Combat sword attacks during freelook and BTP orbit, special abilities on both
  default and rebound input, moving targets and mounted use.
- **Performance:** warmed identical scene/config runs with camera disabled,
  enabled in first person, shoulder camera, and ranged aim, on both ordinary
  terrain and populated/moving sublevels. Record frame times, p95/p99 and hitch
  counts; separate world generation, shader compilation, LOD streaming, window
  focus throttling and GC from camera costs.

Known design limitation retained: Create super glue in the offhand validates
placement using a server-side eye/body ray. Near-boundary shoulder placements can
revert or fail to glue; this client-only mod does not patch Create's server side.
