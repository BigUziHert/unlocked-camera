# Modpack validation — 2026-10-31

The tested camera fixes passed their runtime checks in the copied pack. The
performance samples showed no consistent material FPS loss or substantial
hitches in the measured terrain, stationary sublevel, moving sublevel and
equipped-ability scenes. This report records the executed cases and their
limits; it is not certification of every item, entity, shader state or
multiplayer interaction in the pack.

## Build identity and isolation

The installed file is `unlockedcamera-1.0.0.jar`, SHA-256
`84eea5aee104a5b62fe2568892e9ca279aabc23a2b199746d6653018e09d0a87`.
All **32 production class files** exactly match a rebuild of pre-change `dev`
commit `3884f85` (`Fix the self-review findings on the 2026-09-12 changes`).
The filename alone could not identify the revision because prior development
builds reused version 1.0.0. The current changes increment it to 1.0.1.

Comparison evidence is retained locally in ignored
`build/modpack-test/baseline-research/source-class-comparison.json` and
`staged-manifest.json`. The source archive used is `source-3884f85.zip` in the
same directory.

The original CurseForge instance was read only. Its mods, configuration,
resource packs, shader packs, scripts and options were copied under
`build/modpack-test/game`; the test world is a separate copy. The copied
installed camera JAR was moved outside the test `mods` folder to avoid loading
it alongside development classes. Original-file hashes were recorded in
`build/modpack-test/source-manifest.json`.
The final recheck matched all **1,261 recorded source files** by SHA-256, with
zero changes (`build/modpack-test/source-verification.json`).

The source contains 177 enabled top-level mod JARs and one disabled JAR, with
additional bundled dependencies. The initial full-pack launch reported 209 mod
IDs. **Distant Horizons was disabled in the test copy for the gameplay and
performance runs below**, which reported 208 IDs. Its LOD rendering/streaming
and Ping Wheel distant fallback are therefore not validated by those runs.
The source pack's Distant Horizons JAR/configuration was not changed.

## Test conditions

- Minecraft 1.21.1, NeoForge 21.1.264, copied singleplayer/integrated-server world.
- Windows 11, Java 21.0.6, 10 GB maximum heap, ZGC; the original instance uses
  Java 25, so these are not measurements of its exact Java runtime.
- NVIDIA RTX 4090, AMD Ryzen 7 7800X3D, 64 GB system RAM.
- 1280 × 720 development window; the supplied screenshots are 2560 × 1392.
- Render and simulation distances 8 chunks; VSync disabled; unlimited FPS.
- Iris shaders enabled: Complementary Unbound r5.9.3 with Euphoria Patches
  1.10.5. Shaders were active during all measured runs below.
- Shoulder amount 0.95, shoulder max zoom 5.5, min/max zoom 0.5/32, matching the
  supplied camera configuration. The tested shoulder camera distance was 4.
- Each timing run waits **10 seconds** after changing mode, then records
  **30 seconds** of real `RenderFrameEvent.Post` intervals using
  `System.nanoTime`. Two runs per condition are pooled below. The window was
  focused; world generation, initial shader compilation and warmup are outside
  the measurement interval.

`off` means the mod's master switch was disabled with the camera in vanilla
third-person-back. It does not mean the JAR was removed. `shoulder` means the
new development code's unlocked shoulder camera was active. The bow cases
exercise the continuous ranged-aim hold. The startup state printed on a
`BENCH_START` line can still show the preceding frame's camera; the timer's
10-second delay allows the subsequent tick and camera transitions to settle.

## Measured frame times

This table records the **initial candidate measurement phase**, before the later
static-lens fallback and equipped-ability hold fixes. The final moving/ability
phase below exercises those later changes. The table pools the raw nanosecond
CSVs for the `-a` and `-b` runs of each condition. FPS is total frames divided by
total measured frame time. Percentiles
come from the pooled intervals, rather than averaging the individual p95/p99
values. Lower frame times are better.

| Scene | Camera / item | Frames | FPS | Mean ms | p95 ms | p99 ms | Frames >50 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Terrain | Off / empty | 22,390 | 373.12 | 2.680 | 4.000 | 5.202 | 0 |
| Terrain | Shoulder / empty | 22,228 | 370.44 | 2.700 | 4.037 | 4.767 | 0 |
| Terrain | Off / bow | 21,847 | 364.07 | 2.747 | 3.991 | 4.632 | 0 |
| Terrain | Shoulder / bow | 21,614 | 360.18 | 2.776 | 4.079 | 4.766 | 0 |
| Stationary Sable | Off / empty | 20,782 | 346.33 | 2.887 | 4.154 | 4.849 | 0 |
| Stationary Sable | Shoulder / empty | 20,803 | 346.67 | 2.885 | 4.178 | 4.956 | 0 |
| Stationary Sable | Off / bow | 20,220 | 336.95 | 2.968 | 4.192 | 4.891 | 0 |
| Stationary Sable | Shoulder / bow | 20,584 | 343.02 | 2.915 | 4.111 | 4.819 | 0 |

The terrain shoulder averages are approximately 0.7% lower with empty hands
and 1.1% lower with a bow than the paired off conditions. Stationary Sable
averages are approximately 0.1% and 1.8% higher, respectively. The direction
and size of these small changes vary between runs; this sample establishes no
consistent material slowdown, and it does not establish that the mod improves
FPS. There were **zero intervals above 50 ms across 170,468 measured frames**
(16 × 30-second runs). Longer sessions, larger ships and LOD streaming can
still behave differently.

The stationary Sable scene is the copied world's real sublevel with eight
Create seats, viewed from a fixed ordinary-world platform at approximately
`(-1.648, 135, 5.615)`, yaw 0°, pitch 12°. The crosshair hit was in Sable plot
coordinates. This is a small stationary ship, not a dense moving-ship stress
test.

A separate synchronous call profile at that settled view measured camera pick
mean 5.028 µs / p95 5.200 µs / p99 8.500 µs, and projectile aim mean 5.951 µs /
p95 6.500 µs / p99 9.100 µs. These are timings of repeated individual calls in
one scene; they are not whole-frame timings or a bound on other sublevels.

Raw evidence remains local in `build/modpack-test/game/qa/results.log` and
`*-frames-ns.csv`; derived pooled values are in
`build/modpack-test/pooled-frame-summary.json`. No copied modpack JARs, worlds
or raw private pack data are intended for the repository.

## Functional results recorded so far

| Area | Executed result | Scope |
| --- | --- | --- |
| First-person zoom/freelook sensitivity | 768 cases, zero failures; maximum difference 0.000000716 degrees | Actual transformed mouse handler, including the installed zoom modifier. |
| Just Zoom perspective/lens/scroll | Final expanded suite: 26 cases, zero failures | Static and smooth lens at 0.5/0.2 ratios; third-person detached pose, first-person with/without freelook, and zoom scroll. |
| Shoulder reveal | Passed actual `Camera.setup` transitions | Hidden at 0.19/0.44 offset; visible near 0.95; stays visible through swap center and wall clearance; hides on zoom-out; permanent override and reset pass. |
| Block targeting/collision | Passed stone, top oak slab, glass, oak leaves and bamboo | Actual camera pick; visual collision parity, bamboo transparency, behind-player glass exclusion and eye-based reach rejection. |
| Entity targeting | Passed nearest entity, wall occlusion and behind-player exclusion | Reversible client-only cow fixture; inactive camera delegates to vanilla. |
| Ping Wheel/Create | Stone, glass, oak fence and Create gearbox passed; actual ping server echoes passed | Stationary/moving plot pings returned the expected position; entity ping returned the exact target UUID. Remote-server latency was not tested. |
| Better Combat | Five target-finder cases passed; real attacks damaged cows | Four freelook directions plus inactive baseline; diamond sword and Simply Swords iron rapier gameplay. |
| Projectiles | Vanilla bow/crossbow, Phoenix Bow, Storm Bow, Cursed Bow and Tri-Bow passed | Real server projectile launch direction; central/best-projectile direction dots >0.9996 across reruns. Ballistic drop/long-distance impact are separate. |
| Expanded projectiles | Snowball, trident, Simply Swords iron spear, Create potato cannon, Aether golden dart shooter, Bumblezone stinger spear and Supplementaries slingshot passed | Real server projectiles; best observed direction dots >0.9985. Slingshot's corrected release measured 0.999173. |
| Real Sable sublevel | Plot pick, ping ray, finite aim, seat mount/takeover and dismount passed | One existing stationary sublevel. |
| Simply Swords ability input | Corrected 9 eligibility checks and strict first-press Magispear target check passed | Eligible zombie target UUID matched exactly, with zero horizontal target-center error. Actual ability key packet reached the integrated server. |
| Carry On | Corrected chest pickup/place and cow pickup/place all passed | Server state confirmed pickup/removal before placement; chest reappeared and a new cow was present after release. |

Further installed-JAR inspection, method contracts and scope limitations are in
[the compatibility audit](compatibility-audit-2026-10-31.md).

## Installed-build regression comparison

The matched original `3884f85` production classes were separately loaded with
the QA instrumentation into the same copied pack. They reproduced the reported
mouse/zoom issues and the additional melee mismatch:

| Probe | Original production classes | Candidate result so far |
| --- | --- | --- |
| Mouse sensitivity/freelook | 360 failures / 768 cases; maximum difference 10.369 degrees | 0 / 768; maximum difference 0.000000716 degrees. |
| Third-person zoom | 8 failures / 16 cases | Final expanded suite 0 / 26, including the previously failing 16 cases. |
| Better Combat target finder | 4 failures / 5 cases | 0 / 5; actual sword/rapier attacks also damaged their targets. |

The original zoom failures were perspective, scroll, two static-lens cases and
four detached-camera pose cases. The original melee failures were the four
deflected view directions; inactive delegation passed. These are reproduced
production regressions, distinct from the earlier test-fixture mistakes below.
The original-build frame samples are recorded separately and are **not** pooled
into the candidate timing table.

The original-build stationary Sable shoulder samples pool the two 30-second
runs per item as follows. These were separate process launches from the
candidate, so their run-to-run variation should not be read as a causal
performance improvement or regression.

| Original shoulder condition | Frames | FPS | Mean ms | p95 ms | p99 ms | Frames >50 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Empty | 20,565 | 342.68 | 2.918 | 4.435 | 5.577 | 0 |
| Bow | 20,962 | 349.32 | 2.863 | 4.266 | 5.006 | 0 |

Against the initial candidate's matching stationary shoulder cases, the mean
FPS changes are +1.17% with empty hands and −1.80% with the bow. The mixed
direction and spread between individual runs do not support a consistent
material regression or a claim of improved FPS.

## Moving Sable workload

The revised fixture applies a bounded kinematic transform through Sable's real
physics pipeline from its supported post-physics callback. Normal Sable
tracking sends that pose; no client pose is fabricated. Logged server,
last-networked and client logical/render poses now show the same oscillation,
with the expected interpolation delay, while plot-space picks remain active.
The original ship gained 135 temporary plank blocks (an 11 × 11 floor extent
and a rear wall) and four server-spawned armor stands. The client-tracked/rendered
armor-stand count has not been measured; this is not a dense-entity benchmark.

The observer stays on an ordinary-world platform. The anchor moves ±0.8 blocks
laterally, ±0.15 vertically and ±10° in yaw; its changed mass-center position
is accounted for. The base oscillation period is eight seconds. The workload
exercises moving transformed geometry, not natural powered flight or large
production-ship physics.

An actual Ping Wheel send/receive against the moving plot passed with zero
position error. An actual server bow launch toward the moving plot passed with
direction dot 0.999891 (uncorrected body direction dot 0.954286). The stationary
plot bow check separately measured 0.999987. These establish launch direction
and ping position, not long-range impact or latency compensation for moving
targets. All eight moving frame-time runs completed before the fixture removed
its temporary blocks/entities and restored the original ship pose and zero
linear/angular velocity. That restoration is recorded in `SABLE_MOTION STOP`.

## Final production-code performance phase

These runs include the static-lens compatibility and equipped-ability hold
changes. The final four runs equipped a Simply Swords Magispear, without using
its ability, while viewing the restored stationary Sable ship. They measure
the continuous pre-alignment cost added to fix first-press target selection.
The sampling-start logs confirm the equipped item and final camera mode;
all twelve runs recorded zero unfocused frames.

| Scene | Camera / item | Frames | FPS | Mean ms | p95 ms | p99 ms | Frames >50 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Moving expanded Sable | Off / empty | 20,759 | 346.00 | 2.890 | 4.133 | 4.811 | 0 |
| Moving expanded Sable | Shoulder / empty | 20,751 | 345.86 | 2.891 | 4.174 | 4.917 | 0 |
| Moving expanded Sable | Off / bow | 20,269 | 337.80 | 2.960 | 4.125 | 4.807 | 0 |
| Moving expanded Sable | Shoulder / bow | 20,292 | 338.20 | 2.957 | 4.131 | 4.823 | 0 |
| Stationary Sable | Off / Magispear | 20,408 | 340.15 | 2.940 | 4.090 | 4.751 | 0 |
| Stationary Sable | Shoulder / Magispear | 20,444 | 340.74 | 2.935 | 4.091 | 4.720 | 0 |

Final-code shoulder FPS differed from the paired off runs by −0.04% with empty
hands on the moving ship, +0.12% with the bow, and +0.17% with the equipped
Magispear. These small differences do not show a material FPS loss in the
measured scenes. Moving-ship shoulder p99 was 4.917 ms with empty hands and
4.823 ms with the bow; the corresponding off values were 4.811 and 4.807 ms.

| Measurement phase | Runs × seconds | Measured frames | Longest frame ms | Frames >50 ms |
| --- | ---: | ---: | ---: | ---: |
| Initial candidate, terrain/stationary | 16 × 30 | 170,468 | 14.389 | 0 |
| Matched original production classes | 4 × 30 | 41,527 | 11.667 | 0 |
| Final code, moving expanded Sable | 8 × 30 | 82,071 | 12.060 | 0 |
| Final code, equipped ability weapon | 4 × 30 | 40,852 | 14.874 | 0 |
| Total evidence, phases kept separate above | 32 × 30 | 334,918 | 14.874 | 0 |

Across approximately 16 measured minutes, no recorded frame interval exceeded
50 ms; the longest was 14.874 ms. This supports no observed substantial hitch
or consistent FPS loss in these controlled scenes. It does not establish
performance parity for the original higher resolution/Java runtime, Distant
Horizons streaming, large ships, remote multiplayer or long play sessions.

## Earlier failures and test corrections

The raw log deliberately retains earlier attempts. They must not be counted as
final passes or silently removed:

- The first third-person zoom test exposed a real ineffective compatibility
  injection (`actual=true` for first-person during unlocked zoom). Replacing
  the competing HEAD injection with a whole-method wrapper fixed it; the later
  eight-case rerun passed.
- The initial Simply Swords offhand test assumed a two-handed Magiblade could
  be used in the offhand. Better Combat intentionally hides that equipment.
  The corrected oracle expects rejection and uses Livyatan for the allowed
  offhand case. The corrected nine-case rerun passed.
- The initial Carry On fixtures did not satisfy the copied pack's sneak-input
  requirement and independent 2.5-block pickup limit. The harness was corrected.
  In particular, the earlier `entity place PASS carrying=false cowPresent=true`
  line is invalid evidence because pickup had failed; the corrected harness
  requires successful pickup/removal before counting placement.
- Expanded real projectile/ability tests then found two actual production
  gaps: the slingshot release followed the body direction (best direction dot
  0.9807), and Magispear's first key press selected a server target 1.074 blocks
  horizontally from the camera-ray victim. Source fixes cover custom-pose
  projectile weapons and pre-align equipped ability weapons. The slingshot
  rerun passed at 0.999173. An intermediate Magispear test reported a pass
  under a broad 0.580-block tolerance; that is not counted as exact targeting
  evidence. The final fixture used a confirmed eligible zombie and checked the
  ability context's selected UUID and center: exact UUID match and zero
  horizontal error. The actual entity ping also returned that same UUID;
  its position shifted to Ping Wheel's entity tracking point as expected.
  Idle-ability timings are included in the final-code table above.
- The first expanded Sable motion fixture changed server poses, but client
  render poses remained nearly stationary. No moving-workload FPS samples were
  taken from that attempt. The fixture now applies its kinematic transform in
  Sable's supported post-physics callback, before its normal snapshot tracking,
  and records both last-networked and client render poses. The revised motion
  was confirmed before any moving-workload frame-time samples were started.

## Scope limits

- Distant Horizons-enabled gameplay/LOD streaming, remote multiplayer, all
  modded weapons/blocks/entities, and large production ships remain outside the
  completed scope. The client-only offhand Create super-glue limitation remains.
- Four armor stands were spawned server-side in the expanded sublevel fixture;
  their client-tracked/rendered count was not verified. Dense entity rendering,
  all entity types on sublevels and interacting with every sublevel block were
  not exhaustively tested.
- Projectile checks measured server launch direction. Long-range impacts,
  target leading, every spread projectile and every ability effect are separate
  behaviors. Magispear was cancelled after its server target was inspected.
- The measured graphics/runtime settings and short controlled scenes differ
  from the original instance's resolution, Java runtime and long play sessions.

## Reproduction and release checks

Final `gradlew.bat build` passed, including the executable shoulder-transition
regression suite. The normal release targets the project's NeoForge 21.1.235
dependency; all **39 production class files** in `unlockedcamera-1.0.1.jar` are
byte-for-byte identical to the classes exercised with the pack's 21.1.264 loader.
The release contains **zero QA entries** and reports version 1.0.1. SHA-256:
`cd0dd28e6acc82fdc1e9e02aeb5c1eae7a8b8b38f53a4fd8020ebef7df981fc8`.
Local evidence: `build/modpack-test/release-build.log` and
`build/modpack-test/release-verification.json`.

Final visual checks also confirmed a clearly separated centered crosshair at
distance 4 and a hidden crosshair in the partial shoulder fade at distance 6.3.
Screenshots remain in the copied game's `screenshots/` directory as
`2026-10-31_12.40.30.png` and `2026-10-31_12.40.51.png`.

Use PowerShell 7 and JDK 21. `scripts/prepare-modpack-qa.ps1` requires an
explicit source instance, refuses to overwrite an existing QA copy and copies
the optional test world without its session lock. Add `-DisableDistantHorizons`
only to reproduce the exclusion used above. It hashes the source files and
does not write into the source instance.

Launch the copied pack with
`./gradlew.bat -PcameraQa -Pneo_version=21.1.264 runModpackTest`.
QA probes are opt-in under `src/qa`, and their JAR has the `qa` classifier.
Build the release without `-PcameraQa`, verify the release JAR contains no
`com/caleb/unlockedcamera/qa/` classes, and run the normal build/check tasks
before publishing. Stop motion probes and wait for their restoration log before
closing the test client.
