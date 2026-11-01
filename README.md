# Unlocked Camera
An unlocked camera for **NeoForge 1.21.1**, inspired by the contraption camera in
Create Aeronautics: a fourth perspective in the vanilla view cycle that detaches into
an orbit camera with scroll-wheel zoom, an over-the-shoulder offset up close, and
hold-to-freelook in first person.

**Client-side only:** install Unlocked Camera on your own client. Servers do not
need this mod installed. Targeting fixes use normal Minecraft interaction and
rotation packets; the server keeps its usual reach and interaction validation.

## Controls
`F5` (or whatever "Toggle Perspective" is bound to) now cycles through four views:
1. First person
2. Third person (behind)
3. **Unlocked camera** — scroll wheel zooms in / out (smoothed)
4. Third person (front-facing)
While the unlocked camera is active, scrolling zooms instead of switching hotbar
slots. When terrain pulls the camera in, it glides back out smoothly once clear.
**Centered, truthful crosshair:** the crosshair stays fixed at screen center.
While the shoulder offset is engaged, targeting raycasts from the camera through
that center crosshair (validated against your real reach), so whatever sits under
the crosshair is what you mine, hit, or interact with — like an over-the-shoulder
shooter. Switching directly from first person into the shoulder camera keeps
the crosshair visible throughout the camera slide. When zooming in from farther
out, it appears at or below the shoulder-offset zoom limit once the slide is
nearly complete. It stays
visible during shoulder swaps and wall clearance; a config option makes it permanent.
**Shoulder offset:** zoomed in to 4 blocks or closer (configurable), the camera
slides over the shoulder; press `X` (rebindable) to swap sides. The offset clips
against walls so it never pokes through blocks.
**Freelook:** in first person, hold `Left Alt` (rebindable) and move the mouse to
look around without turning your body — movement direction stays put, and the
crosshair stays truthful: mining, placing, and projectiles land where the
crosshair points without your body turning, while other players see your head
follow the deflected view. On release the view eases back to your original
facing; enable `freelookKeepDirection` to keep facing where you were looking
instead — with it on, clicking mid-freelook also snaps your body to the
crosshair.
**Better Third Person compatibility:** the mod never touches camera rotation, so
BTP's free orbit is untouched; targeting through the center crosshair works the
same with or without BTP.
**Just Zoom compatibility:** freelook uses the same processed mouse movement as
normal look, including zoom sensitivity and smoothing. Holding Just Zoom keeps
the unlocked camera behind the player; scrolling adjusts lens magnification.
Lens zoom also composes with Simply Swords' FOV effects in first and third person.
**Better Combat and Simply Swords compatibility:** Better Combat's melee hitbox
aims toward the crosshair without turning the player's body. Simply Swords ability
weapons prealign server aim while equipped so rebound abilities can select the
crosshair target on their first press. This uses the existing once-per-tick aim
cache; ordinary melee weapons do not enable that hold.
**Create Aeronautics compatibility:** while seated in an Aeronautics contraption
(a Sable "sublevel"), this mod steps aside completely — the perspective key behaves
as if the mod weren't installed, so Aeronautics' own contraption camera takes over.
Detection uses Sable's own seated-check via reflection, so there is no hard
dependency on it.
## Config
Settings live in `config/unlockedcamera-client.toml` (also editable in-game via
Mods > Unlocked Camera > Config; changes apply live):
- `toggleMod` — master switch that disables the entire mod when off, default on
- `enableUnlockedCamera` — toggle the fourth perspective on/off, default on
- `disableVanillaThirdPerson` — F5 goes straight from first person to the
  unlocked camera, skipping vanilla third person, default off
- `enableFreelook` — toggle freelook on/off, default on
- `freelookKeepDirection` — on release, face where you were looking instead of
  easing back, default off
- `crosshairAlways` — always draw the crosshair in the unlocked camera,
  default off. It is only aim-corrected while the shoulder offset is engaged;
  zoomed out past the shoulder gate it draws at screen center but aim follows
  the body's facing
- `crosshairOnShoulderOffset` — draw it at or below the shoulder-offset zoom
  limit, preserving it on direct first-person entry and waiting for the shoulder
  slide when zooming in from farther out, default on
- `showEnterMessage` — show the action-bar text when entering the unlocked
  camera, default on
- `enableShoulderOffset` — toggle the over-the-shoulder offset, default on
- `shoulderOffsetAmount` — how far the camera slides over the shoulder in
  blocks (0.5 - 1.0), default 0.75
- `shoulderOffsetMaxZoom` — apply the shoulder offset at or closer than this (easing in from one block above)
  distance in blocks, default 4
- `minZoom` — closest zoom, default 1 block
- `maxZoom` — farthest zoom, default 12 blocks
- `freelookYawLimit` — how far left/right freelook can swing in degrees, default 90
## Development
Requires JDK 21. Gradle uses whatever `JAVA_HOME` points at, so set it per
shell if the system default is older:
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat runClient   # launch a dev client with the mod loaded
.\gradlew.bat build       # build the release jar into build/libs/
```
### Testing a copied modpack

The optional QA driver runs against `build/modpack-test/game`. Prepare it with
PowerShell 7; `-Instance` points to the instance directory containing `mods` and
`config`, and `-TestWorld` points to a disposable Minecraft 1.21.1 world:

```powershell
pwsh -File .\scripts\prepare-modpack-qa.ps1 `
  -Instance "C:\path\to\instance" `
  -TestWorld "C:\path\to\disposable-world"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat -PcameraQa -Pneo_version=21.1.264 runModpackTest
```

The script reads the source instance, copies its pack files and options, records
SHA-256 hashes, and moves the copied installed camera JAR out of the copied `mods`
directory so the development classes can replace it. It refuses an existing QA
destination. `-TestWorld` is optional; without it, create a world named `Camera QA`
in the copied instance. `-DisableDistantHorizons` optionally disables that mod in
the copy; record this exclusion when reporting results. Match `-Pneo_version` to
the pack's loader version when testing a different pack.

Once the test world is ready and menus are closed, write one command to the
request file. Results append to `qa/results.log` inside the copied game directory:

```powershell
Set-Content -LiteralPath .\build\modpack-test\game\qa\request.txt -Value 'zoomprobe'
Get-Content -LiteralPath .\build\modpack-test\game\qa\results.log -Tail 20
```

| Command | Check or action |
| --- | --- |
| `zoomprobe` | Actual mouse pipeline: sensitivity, zoom, smoothing, inverted Y, and freelook transitions. |
| `zoomthirdprobe` | Lens FOV, detached camera position, zoom scroll, and first-person/freelook parity. |
| `shoulderprobe` | Crosshair reveal timing, shoulder swaps, and wall clearance. |
| `compatprobe` | Better Combat hitbox targeting and Sable reflection bridge. |
| `swordsprobe` | Simply Swords ability key, release, and offhand eligibility. |
| `rayprobe` | Camera picking and collision against block/entity fixtures. |
| `integrationprobe` | Actual block, Carry On, melee, ability, and projectile interactions. |
| `probe IntegrationRecheckProbe` | Separate standard-scene retry with serialized server setup/acknowledgements, actual held-stack checks and a nearby direct fixture camera; retains invalid historical attempts separately. |
| `probe RegistryCensusProbe` | Inventory every loaded item, block, block state and entity type; enumeration is not a gameplay pass. |
| `probe RegistryItemProbe` | Every default item stack, both hands and five views: camera targeting, transformed packet hooks and applicable Better Combat hitbox geometry. Synthetic packets are not sent. |
| `probe EntityInteractionProbe` | Every available default entity factory: targetability in four camera modes against the ordinary entity-ray baseline. |
| `probe EntityActionProbe` | Real empty-hand requests against default server-created entities, comparing first person, shoulder and actual first-person freelook; unsupported or non-targetable defaults stay explicit. |
| `probe EntityActionReviewProbe` | Retry differing/error IDs and identified placement/readiness fixture limits in both camera modes. Uses null/multipart cleanup repair and front-surface placement for large entities; preserves original tables. |
| `probe EntitySpecializedProbe` | Bounded first-person/offset/freelook board mounting and giant/bone-serpent request-delivery fixtures, with stable target-family and reference geometry checks. |
| `blockprobe` / `blockstates` | Target every default block / every registered block state in isolation, using first person and both shoulders. |
| `probe BlockFreelookGeometryProbe` | Every registered block state with actual held first-person freelook input and both body/view deflection directions; geometry only. |
| `blockinteractions all` | Real empty-main-hand requests for every default block, comparing first-person and shoulder server observations. A namespace regex such as `create\|simulated` can replace `all`. |
| `probe BlockFreelookProbe` | Every default block: real empty-main-hand first-person versus held first-person freelook requests. Uses fresh support, acknowledged teleports, three held-pose preflight ticks and server event receipts. |
| `probe BlockFreelookResumeProbe` | Resume that same controller with pre-use input recovery and screen cleanup. Repeats the real held-input preflight after an interruption, logs the observed key/screen/pose and preserves completed rows/build identity. |
| `probe BlockFreelookRecoveryProbe` | After a timeout, drain the actual pending server mutation and verify the original snapshot, re-restoring if needed. Requires three server ticks of exact state/pose and teleport acknowledgement; does not resume the sweep. |
| `probe BlockFreelookLoadGuardProbe` | While the unchanged broad sweep runs, defer its setup timeout only while phase zero/one has an uncanceled server mutation pending. Leaves action/ACK phases, input poses and result oracles unchanged. |
| `probe BlockFreelookCheckpointStopProbe` | Request an intentional stop at a completed setup boundary before the next use packet; completed rows stay intact. Run recovery verification before restarting or restoring other runtime fixtures. |
| `probe BlockFreelookReviewProbe` | Three separate retries of latest freelook block differences/errors after the broad sweep finishes. Checks both input modes, twenty actual server ticks of fixture settling, three held-pose server ticks, body/pitch aim, event receipts and same-ray reference misses. Records head aim without an extra head-alignment gate; the baseline head fixture remains explicit. Failed requests drain before cleanup. |
| `probe StateRayDiagnostic` | Recheck failed block states against vanilla rays from the same camera origin and player depth, plus direct shape intersections. |
| `probe BlockInteractionRecheck` | Three paired retries of differing block requests with fresh neighbors/supports, settled states, explicit teleport readiness and server event receipt. Reads the secondary instance's original evidence. |
| `probe BlockInteractionReviewProbe` | Reload the updated block recheck controller in an idle QA client; archives prior results and releases installed Simulated hold interactions between cases. |
| `probe BlockSweepResumeProbe` | Resume the existing broad block sweep with hold-release cleanup and a confirmed-teleport recovery gate; preserves its loaded class and recorded rows. |
| `probe BlockReviewIdentity` | Validate a completed review against the live census and release class bytes before adopting a stronger reuse marker; records metadata without rerunning actions. |
| `blockstop` | Stop the current block sweep and restore its temporary fixture/player state. |
| `probe ItemUseProbe` | Real air-use/release of every non-block default item in the main hand, comparing first person and shoulder; holds use for at most 40 ticks. |
| `probe ItemUseReviewProbe` | Recheck only differing/error air-use rows after synchronized inventory/equipment clearing; writes a separate timestamped review directory. |
| `probe ItemUseReviewRetryProbe` | Retry only the latest air-use review's remaining differences/errors; previous review directories remain evidence and the report merges latest outcomes per item. |
| `probe ItemUseFreelookProbe` | Fresh all-item air-use comparison in actual held first-person freelook, with body/view separation and separate resumable evidence. |
| `probe ItemUseFreelookReviewProbe` | Separate retries of only the full freelook sweep's latest errors/differences. Records selected IDs, prior statuses and original table SHA; keeps the full-sweep denominator and original rows. |
| `probe StormsEdgeUseRecheckProbe` | Separate one-item Storms Edge first-person/freelook retry after ability drain and pre-use motion reset. Uses `STORMS_EDGE_RECHECK` and its own `freelook-review-*` directory; preserves earlier preparation failures. |
| `probe SpawnEggUseProbe` | Every default spawn egg: real use on a stone top face in first person, shoulder and actual freelook. Requires expected-type server join plus a live entity; freezes post-join AI/gravity and tests immediate spawning only. |
| `probe SpawnEggUseReviewProbe` | Retest every egg with any original nonpositive mode, using fresh first-person/shoulder/freelook cases. Writes separate `spawn-egg-use/review-*/outcomes.tsv` plus selection/source manifests, and waits observed transient target blockers without forcing a hit. Uses `SPAWN_EGG_RECHECK` markers. |
| `probe SpawnEggUseRemainingProbe` | Retry only the latest remaining nonpositive egg IDs in all three modes. Default random jellyfish eggs use their installed public COMMON-family oracle; no claim that every random variant was sampled. Preserves each prior review directory. |
| `probe EggMultipartFixtureProbe` / `probe WhaleMultipartFixtureProbe` | Diagnose an actual blocking Naturalist multipart entity and use its public unregister lifecycle only when the exact parent is already removed in the copied client. Logs parent identity/state and cleanup; server entities remain unchanged. This fixture cleanup is not a gameplay pass. |
| `probe BucketUseProbe` | Every registered `BucketItem` subclass in all three modes: source pickup, fluid placement or mob release in an overworld basin. Separately records client prediction, actual server effects and observations after twenty server ticks. |
| `probe BucketUseReviewProbe` | Separate corrected entity-oracle and delayed-prediction review in three modes, with immediate, twenty- and eighty-server-tick observations. A captured jellyfish stack uses the installed serializer and remains separate from default-stack exceptions. Uses `BUCKET_RECHECK`, selection/source manifests and `bucket-use/review-*/outcomes.tsv`. |
| `projectileall` / `meleeall` | Registry-driven projectile fixtures in both hands / real melee requests for swords, diggers, tridents and items with default attack-damage modifiers. Context-specific launchers without a fixture are reported separately. |
| `probe OffsetMeleeProbe` / `probe FreelookProjectileProbe` | Separate registry weapon sweeps in shoulder offset / actual held first-person freelook. Offset melee requires exact-victim server attack receipt and attributed player-attack damage; cardboard uses canceled attack plus knockback. Contaminated or unsupported damage oracles remain inconclusive. |
| `probe OffsetMeleeResumeProbe` | Preserve latest validated offset-melee passes and retry missing/nonpassing IDs after interruption. Reports full and pending denominators separately; stale bread has an explicit exact actor/victim/attack-tick custom-damage oracle. |
| `probe MeleePathDiagnosticProbe` | Separate failed-only first-person/offset attack pairs with real input cancellation, held/target readiness and exact server attack/damage traces. Preserves the original sweep outcomes. |
| `probe FreelookAbilityProbe` | Actual Magispear ability key/server target and server-echoed entity ping under held first-person freelook. |
| `probe AbilityPairedRecheckProbe` | Separate real first-person/freelook Magispear key requests, exact selected/expected server UUID, and client/server input/readiness diagnostics. Uses `ABILITY_PAIR_SUITE` / `ABILITY_PAIR_DETAIL`; retains the original ability result and does not retest ping. |
| `projectilebaseline <item-id>` | Repeat one launch fixture in first person with the camera disabled; keep these results separate from the camera-mode sweep. |
| `probe MeleeBaselineProbe` | Recheck only the latest melee sweep's failed items in first person and freelook, waiting for full attack cooldown. |
| `probe CardboardKnockbackProbe` | Measure Create cardboard-sword knockback in first person, shoulder and freelook; health damage is intentionally canceled for the cow fixture. |
| `probe CardboardKnockbackRecheckProbe` | Separate retry after fixture-readiness failure, using the same measured server-event/knockback oracle. |
| `probe CreateInteractionProbe` | Actual Create wrench, super-glue, honey-glue, steering-wheel and throttle requests with server-state checks. |
| `probe CreateFreelookProbe` | The Create action fixtures in actual held first-person freelook; retains separate results from shoulder-offset checks. |
| `probe CreateFreelookRecheckProbe` | Separate retry of Create freelook actions with synchronized fixture setup and fresh QA callbacks; retains earlier aborted attempts. |
| `probe CreateSelectionProbe` | Actual quill free-selection and fresh worldshaper material-selection handlers, with first-person controls; no export or worldshaper edits. Quill input uses a temporary predicate override. |
| `probe PowergridInteractionProbe` | First-endpoint selection with Powergrid wire/cord, including a first-person positive control. |
| `probe PowergridFreelookProbe` | First-endpoint selection with all five Powergrid wire/cord items in actual held first-person freelook; records client acceptance and server endpoint selection separately. |
| `probe PowergridEndpointSession` / `probe PowergridFreelookSessionProbe` | Fresh synchronized endpoint fixtures for offset / freelook, using the heavy connector for iron wire; retain the same explicit client/server result markers. |
| `probe PowergridEndpointReadyScenario` / `probe PowergridFreelookReadyProbe` | Retry the same endpoint actions in a small existing-chunk fixture, waiting for exact readiness and completion of pending server work. |
| `probe PowergridRecoveryProbe` | Drain and restore an interrupted Powergrid fixture before another suite starts; recovery is not gameplay evidence. |
| `probe PhysicsStaffProbe` | Physics-staff selection and server drag-session checks on a Sable sublevel, including bounded kinematic movement; does not measure force-driven displacement. |
| `probe PhysicsStaffFreelookProbe` | Physics-staff selection/session checks in actual held first-person freelook, including stationary and moving sublevel fixtures. |
| `probe PhysicsStaffFreelookSessionProbe` | Fresh synchronized retry of the freelook staff fixtures, preserving earlier attempts in the log. |
| `probe AddonRayProbe` | Aeroworks socket-selection ray helper; does not operate or connect modules. |
| `probe AeroworksModuleProbe` | All 34 default AeroWorks module items on four console bases, attach/remove in first person, offset and actual freelook. Checks exact server socket attachment or removed-item return; independently seeded removal and unsupported geometry remain explicit. |
| `probe AeroworksModuleFreelookReviewProbe` | After the original AeroWorks sweep finishes and restores, retry only freelook nonpasses with positive first-person controls. Preserves three paired repeats per module/console/operation, waits three actual player ticks after body/pitch readiness, and records head/view/socket observations without writing the candidate head yaw. Uses separate `AEROWORKS_MODULE_FREELOOK_RECHECK` markers; original 816-case evidence remains intact. |
| `sableprobe` / `pingprobe` | Real sublevel seat transitions / server-echoed ping targeting. |
| `motionstart` / `motionstop` | Move and restore the test sublevel for motion checks. |
| `probe MinimumLoadSettingsProbe` / `probe MinimumLoadSettingsRestoreProbe` | Temporarily reduce disposable client render and server view/simulation distances; the restore command returns that process's saved values. Alternatively, after fixture cleanup, quit at the reduced radius and restore the copied options backup after verified process exit. Record this as an ended runtime override, separately from exact options bytes and fixture restoration. Does not interrupt queued chunk work or certify gameplay/performance. |
| `camera 4` / `first` / `off` | Set four-block unlocked view / first person / mod-disabled third person. |
| `state` / `unpause` / `quit` | Log camera state / close menus / exit the test client. |
| `benchmark shoulder 30` | Warm up for 10 seconds, then record 30 seconds under the label `shoulder`. |

Run gameplay probes separately and wait for completion and any `RESTORED` line;
they create temporary fixtures in the copied world. Block interaction and item
air-use sweeps resume their recorded rows, with build/mod identity checks. Preserve
or rename their evidence files for a deliberate fresh run. Keep the game focused during benchmarks and avoid
other work that loads the CPU/GPU. Results include mean, p95/p99, frames over
50 ms, unfocused-frame count, and raw frame times in `<label>-frames-ns.csv`.

Registry-wide evidence lives inside the copied game's `qa` directory:

- `census/*.json`: actual registered IDs, mod versions, namespace counts and implementation owners.
- `registry-items/camera-paths.csv` and `registry-entities/targetability.csv`: per-item/per-entity camera checks.
- `block-targeting.tsv`, `block-state-targeting.tsv` and `block-interactions.tsv`: per-block/state ray results and actual empty-hand request comparisons.
- `block-state-freelook-targeting.tsv`: separate all-state targeting with actual first-person freelook input.
- `block-freelook-interactions.tsv`: separate actual first-person freelook block requests, held-input evidence, readiness and server event receipt.
- `block-freelook-recheck.tsv`: failed-only three-pair review with `repeat`, server aim readiness and explicit mode guards; old fixture identities are archived separately.
- `state-ray-diagnostics.tsv` and `block-interaction-recheck.tsv`: separate explanations/retries of original block differences; original rows remain unchanged.
- `entity-actions.tsv` and `entity-freelook-actions.tsv`: separate shoulder and first-person freelook comparisons for default entity empty-hand requests, with explicit setup/targetability limits.
- `entity-actions-recheck.tsv` and `entity-freelook-actions-recheck.tsv`: separate retries of the original differing/error entity IDs.
- `item-air-use/outcomes.tsv`: per-item air-use observations. Named weapon/tool action oracles also append to `results.log`.
- `item-air-use/review-*/outcomes.tsv`: failed-only air-use retries with explicit equipment observations.
- `item-air-use/freelook/outcomes.tsv`: separate all-item first-person freelook air-use observations.
- `item-air-use/freelook-review-*/outcomes.tsv`: separate selected freelook retries, with `selected-items.txt`, `selection.tsv` and `source.txt` documenting their exact origin.
- `spawn-egg-use/outcomes.tsv`: per-egg/mode real block-use results, expected joins/live entities and first-person comparison status.
- `bucket-use/outcomes.tsv`: per-bucket/mode real target-use results, intended fluid/entity, immediate client prediction, authoritative server effect and delayed reconciliation. `registered-items.txt` records the exact `BucketItem` denominator; other drink containers and solid block buckets are excluded.
- `ability-isolation-methods.txt` beside corrected air-use tables: installed public readiness/cancellation APIs used to isolate delayed Simply Swords abilities between cases; actual waits/calls remain in the log.
- `melee-recheck.tsv`: failed-only paired melee checks with their measured attack cooldown and target health.
- `cardboard-knockback.tsv`: server event receipt, canceled damage, displacement, velocity and direction for the cardboard sword.
- `cardboard-knockback-recheck.tsv`: separate fixture retry; an aborted pre-attack setup is not a measured knockback failure.

The initial block request sweep records ordered server observations, but an
acknowledgement alone does not prove that the server accepted the use packet:
Minecraft skips block use while a teleport is unconfirmed. The separate block
recheck verifies that readiness explicitly and rebuilds the fixture before each
mode. Treat initial differences as review cases until their paired evidence is
examined. Local return-value differences can coexist with identical server effects.
Those returns can change vanilla's offhand fallback; the direct empty-main-hand
`useItemOn` fixture does not test that fallback, so matching server effects alone
do not establish complete interaction parity.
Projectile passes measure the best initial direction observed in the request
window (horizontal heading for Crystal Cannon). Logs retain every sampled entity
type and direction; the harness does not certify every emitted projectile, later
impact, or damage. Equipment-getter suppression is an explicit skipped hand,
not a gameplay pass.
Updated block reviews reuse IDs with three complete paired retries under the same
recorded fixture version. Partial IDs are retried; archived results and unresolved
return differences remain in the report. This avoids repeating completed cases
when a broad sweep resumes after a fixture interruption.

Shoulder offset and freelook are separate supported camera modes. Actual freelook
requires first-person view and the held freelook key; the production input handler
clears freelook deflection in third person. The dedicated freelook action probes
use a separated body/view direction, keep-direction disabled, and held input
through the action. They report their evidence separately from shoulder fixtures.
Synthetic hook checks alone do not establish that this real input mode was active.

Before a new reporting session, record the current log length so historical passes
are not counted again. After the probes, generate the static Create dependency
inventory and current-session coverage report:

```powershell
# Run these first three commands before issuing the session's probes.
New-Item -ItemType Directory -Path .\build\exhaustive-qa -Force | Out-Null
$qaResults = '.\build\modpack-test\game\qa\results.log'
Set-Content -LiteralPath .\build\exhaustive-qa\results-start-offset.txt -Value $(if (Test-Path -LiteralPath $qaResults) { (Get-Item -LiteralPath $qaResults).Length } else { 0 })

# Run these after the probes finish.
python .\scripts\audit-create-addons.py
python .\scripts\verify-qa-build.py
python .\scripts\verify-qa-source.py
python .\scripts\summarize-exhaustive-qa.py
```

The report generator writes `build/exhaustive-qa/coverage.json`, the current log
segment, and [the complete-registry report](docs/complete-registry-qa-2026-10-31.md).
It also supports incomplete runs and reports missing suites explicitly. The static
Create family includes optional-dependency integrations, not just dedicated addons.
The report preserves latest per-block outcomes and every prior attempt, keeps
rechecks separate, and includes timestamp-filtered exception/lag records from the
current process logs. Matching smoke observations do not override packet errors.

Independent compatibility suites can use a second disposable instance at
`build/modpack-block-test/game`, prepared with `scripts/prepare-block-qa.py` and
launched with `scripts/launch-copied-qa.ps1 -Instance modpack-block-test`. Each
instance has its own world, request file and results. Run its census before the
block-state and block-interaction sweeps, then merge evidence with:

```powershell
python .\scripts\summarize-exhaustive-qa.py --block-qa .\build\modpack-block-test\game\qa
```

The summarizer refuses different runtime registry IDs/counts or mod versions and
preserves separate raw logs and source attribution. A new secondary log starts at
byte zero; use `--block-offset-file` when reusing one with older results. Parallel
clients are for compatibility testing only; run performance benchmarks alone.
Production-class identity and the current source-file manifest are included when
available; supply `--source-verification` for the final verification JSON. A matching
build identity is not evidence of production fixes.

`verify-qa-build.py` reads the current source manifest, compares every compiled
production class with the installed camera JAR, rejects QA classes in that release,
and writes `build/exhaustive-qa/production-identity.json`. Run it after the final QA
compile. `verify-qa-source.py` hashes the manifest's original source files again and
writes `current-source-verification.json`; it reads the original instance without
changing it. Both helpers require the `current-source-manifest.json` captured when
the disposable copy was refreshed. Keep that manifest with the run's evidence.

These sweeps establish bounded checks, not universal gameplay compatibility.
Camera/packet and entity-targeting checks do not use items or interact with entities.
Isolated block states do not exercise powered machines, recipes, multiblock layouts,
moving sublevels, or custom tools. Matching air-use or block no-ops are not functional
passes. An independently matched occlusion, a non-selectable outline, or a state
requiring a specialized fixture remains distinct from an unexplained difference.
Named real-action passes apply only to their recorded item, hand, scene and oracle;
charged/component variants, additional abilities and multiplayer require their own
fixtures.

Normal builds exclude `src/qa`; opt-in QA JARs have a `-qa` classifier. After QA,
use `.\gradlew.bat build` to produce the release artifact. Normal JAR packaging
also excludes QA classes explicitly. Avoid `clean` until any wanted test evidence
has been copied out of `build/`. The recorded
coverage and remaining limits are in [the compatibility audit](docs/compatibility-audit-2026-10-31.md)
and [the runtime/performance report](docs/modpack-validation-2026-10-31.md).
The later [complete registry report](docs/complete-registry-qa-2026-10-31.md)
preserves the installed-build baseline. The [1.0.3 fixes report](docs/camera-compatibility-fixes-2026-11-01.md)
records the client-only fixes, staged release identities, actual interaction tests,
and remaining limitations separately from that baseline.

Key source files:
- `src/main/java/com/caleb/unlockedcamera/UnlockedCameraMod.java` — mod entry point
- `src/main/java/com/caleb/unlockedcamera/client/UnlockedCameraClient.java` — all camera logic (client only)
- `src/main/java/com/caleb/unlockedcamera/client/` — config, linked config-screen sliders, Sable seat detection
- `src/main/java/com/caleb/unlockedcamera/mixin/` — vanilla hooks (camera, GUI crosshair, mouse, outgoing
  packet aim correction), config-screen slider/color tweaks, and presence-gated Create & Simulated
  crosshair compat (see `UnlockedCameraMixinPlugin`)
