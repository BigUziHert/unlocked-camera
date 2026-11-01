# Complete-registry camera QA — 2026-10-31

This is a record of executed checks, **not certification that every gameplay interaction works**. Registry enumeration, camera geometry/hooks and real server actions are reported separately.

Only the current task's log bytes 299,061–2,576,117 were read; earlier results were excluded.

The tested **39 production classes match the installed `unlockedcamera-1.0.2.jar` byte-for-byte** (JAR SHA-256 `8632af9398b90947253018a44102d5f73ba8ba331bd74f3b4b4c3d9d81c3a99e`). These are QA additions; this identity comparison does not represent production fixes.

Runtime census: **10148 items, 8089 blocks, 476664 block states, 787 entity types, 208 loaded mod IDs**. Enumeration alone is not a pass.

| Executed check | Coverage | Results |
| --- | --- | --- |
| Held-item camera/packet hooks | 10,148/10148 items; 101,480 cases | PASS_CAMERA_PACKET: 101,045, PASS_CAMERA_PACKET_OFFHAND_SUPPRESSED: 435 |
| Non-block item air-use/release | 2,969/2969 items | ERROR: 44, MATCHED_AIR_USE_SMOKE: 774, MATCHED_NO_OBSERVABLE_EFFECT: 2,099, OBSERVED_DIFFERENCE_REVIEW: 52 |
| Actual FP-freelook item air-use/release | 2,969/2969 items | ERROR: 4, MATCHED_AIR_USE_SMOKE: 824, MATCHED_AUTO_CONSUMED_BEFORE_USE: 14, MATCHED_NO_OBSERVABLE_EFFECT: 2,116, OBSERVED_DIFFERENCE_REVIEW: 11 |
| Spawn-egg block use, three modes | 1,176/1176 cases; 392 eggs | NEEDS_TARGET_FIXTURE: 307, SPAWNED_EXPECTED_ENTITY: 869 |
| Bucket target use, three modes | 351/351 cases; 117 items | ERROR: 3, EXPECTED_SERVER_EFFECT_NOT_OBSERVED: 54, SERVER_EFFECT_CONFIRMED: 294 |
| Default block ray targeting | 8,089/8089 blocks | NEEDS_SPECIALIZED_FIXTURE: 1, NO_OUTLINE: 88, REFERENCE_OCCLUDED_OR_RANGE: 22, TARGETING_PASS: 7,978 |
| All registered block-state targeting | 476,664/476664 states | BASELINE_MISS: 290, NEEDS_SPECIALIZED_FIXTURE: 1, NO_OUTLINE: 2,824, REFERENCE_OCCLUDED_OR_RANGE: 44, TARGETING_FAIL: 33, TARGETING_PASS: 473,472 |
| Actual FP-freelook all-state targeting | 476,664/476664 states | BASELINE_MISS: 290, NEEDS_SPECIALIZED_FIXTURE: 1, NO_OUTLINE: 2,824, TARGETING_PASS: 473,549 |
| Empty-hand block request comparison | 8,089/8089 blocks | CONTEXT_DIFFERENCE: 2, NEEDS_FIXTURE: 134, NEEDS_SPECIALIZED_FIXTURE: 1, NO_OBSERVABLE_EFFECT: 6,760, OBSERVATION_DIFF: 42, OBSERVED_PARITY_PASS: 1,134, TARGETING_FAIL: 16 |
| Actual FP-freelook block requests | 8,089/8089 blocks | MATCHED_NO_OBSERVABLE_EFFECT: 6,810, MATCHED_SERVER_LOCAL_RETURN_DIFF: 12, NEEDS_SPECIALIZED_FIXTURE: 122, OBSERVED_PARITY_PASS: 1,138, SERVER_ACTION_NOT_OBSERVED: 3, SERVER_DIFFERENCE_REVIEW: 2, UNEQUAL_STARTING_FIXTURE: 2 |
| Default entity targetability | 787/787 types | NOT_TARGETABLE_DEFAULT: 271, NO_DEFAULT_FACTORY: 1, TARGETABLE: 515 |
| Empty-hand entity request comparison | 787/787 types | NEEDS_FIXTURE: 175, NOT_TARGETABLE_DEFAULT: 268, NO_OBSERVABLE_EFFECT: 309, OBSERVED_PARITY_PASS: 33, TARGETING_DIFFERENCE: 2 |
| Actual FP-freelook entity requests | 787/787 types | ERROR: 2, NEEDS_FIXTURE: 175, NOT_TARGETABLE_DEFAULT: 268, NO_OBSERVABLE_EFFECT: 307, OBSERVATION_DIFF: 1, OBSERVED_PARITY_PASS: 33, TARGETING_DIFFERENCE: 1 |

Offset and freelook are tested as separate supported modes. Actual freelook requires first-person view, the held freelook key and a body/view deflection; the production input handler clears that deflection in third person. No unsupported combined offset/freelook mode is claimed.

`NO_OBSERVABLE_EFFECT` is a matching no-op, not proof of the block's functionality. `NEEDS_FIXTURE`, `NEEDS_SPECIALIZED_FIXTURE`, `NO_OUTLINE` and `BASELINE_MISS` are setup/targetability limits. `REFERENCE_OCCLUDED_OR_RANGE` matches an independent clip of the same camera ray. `CONTEXT_DIFFERENCE` records different effects with different hit faces. `OBSERVATION_DIFF`, `TARGETING_FAIL` and errors require investigation; they are not automatically confirmed mod defects.

Named fixture checks recorded 1828 distinct latest cases (PASS: 1,481, FAIL: 341, INCONCLUSIVE: 3, PASS_KNOCKBACK: 3), including both client selection and actual server-action oracles. Server-action cases cover 385 registered held-item IDs and prove only the specified action/hand/scene. Generic air-use smoke is separate and can match without activating an item's effect. All attempts and details are retained in the JSON report.

The current source snapshot records 1,263 files. It supersedes the older 1,261-file snapshot; differences already present before this task must not be attributed to QA. End-of-run verification is recorded in coverage.json.

Block-state and block-interaction evidence comes from a second isolated instance (`build/modpack-block-test/game/qa`), log bytes 0–1,183,974. Its runtime registry IDs/counts and mod versions match the primary instance. Raw logs remain separate. Concurrent runs are compatibility checks, not performance measurements.

The executed new geometry probe retained legacy shoulder_left/right column labels. Its implementation and per-row limits use actual held FIRST_PERSON freelook at minus/plus 75 degrees. This explicit semantic mapping does not alter raw headers or outcomes.

During compatibility checks, disposable clients reduced render distance from eight to four chunks and simulation distance from eight to five; fixture extents remain within 21 blocks. This runtime load adjustment and its restoration are recorded per instance. Queued chunk loads may continue, and these concurrent tests are not performance benchmarks.
Original runtime-settings attempts: 1 restored, 0 ended with a confirmed clean process exit, 0 ended after a verified shutdown stop, 1 lost with an interrupted process, 0 still pending. A later process cannot restore a lost live snapshot.

A later disposable-runtime adjustment attempts client render distance two, server view/simulation distance two and unchanged valid client simulation distance five. The log verifies the snapshot and persistence across three actual server ticks; apply errors and pending restoration remain explicit. This does not interrupt already queued chunk deserialization/data-fixer work. No production configuration or mod changes are implied.
Smaller-radius attempts: 4 applied, 1 restored, 1 ended with a confirmed clean process exit, 1 ended after a verified shutdown stop, 1 lost with an interrupted process, 0 still pending. Final clients can quit at the reduced radius to avoid new chunk demand; that ends a runtime override without falsely claiming live restoration. Fixture restoration and exact copied options bytes are verified separately. A quit request alone does not establish exit.

The primary client exited orderly during offset melee; the cause remains unestablished. Its unfinished invocation and lost live snapshots are retained. An explicit resumed melee inventory preserves earlier validated passes and schedules only missing/nonpassing cases.

The secondary client preserved its checkpoint and verified restoration, then stalled while shutting down. Only its verified process was stopped. The [shutdown evidence](../build/exhaustive-qa/secondary-shutdown-analysis.txt) distinguishes the sampled unload-retry stack from the inferred mechanism; the exact holder/root cause and any camera involvement remain unestablished.

After all final fixtures restored, the primary client exited cleanly. The final secondary process (verified PID 3316) again stalled in shutdown and was stopped after the [final thread dump](../build/exhaustive-qa/modpack-block-test-final-shutdown-thread-dump.txt) showed the ChunkMap unload loop inside `stopServer`. This final termination is recorded separately from the earlier restart; it is not labeled a clean secondary exit. Both copied options files were then restored and checked byte-for-byte.

The disposable primary options file matches the preserved original copy byte-for-byte. This is separate from the unchanged original source instance and from runtime restoration.

The disposable secondary options file matches the preserved original copy byte-for-byte. This is separate from the unchanged original source instance and from runtime restoration.

## Findings and paired rechecks

A later generic integration run recorded a target far outside the nearby QA platform (20 affected named results). Those raw failures are retained as invalid-fixture evidence and excluded from action-coverage claims. They do not replace the separate registry projectile/melee sweeps or establish camera defects.

- `create:schematic_and_quill` / shoulder: first-person positive control passed; the camera-mode comparison failed in the tested scene. client targeting/handler.
- `create:schematic_and_quill` / freelook: first-person positive control passed; the camera-mode comparison failed in the tested scene. client targeting/handler.
- `create:handheld_worldshaper` / shoulder: first-person positive control passed; the camera-mode comparison failed in the tested scene. client targeting/handler.
- `create:handheld_worldshaper` / freelook: first-person positive control passed; the camera-mode comparison failed in the tested scene. client targeting/handler.
- `powergrid:wire` / shoulder: first-person positive control passed; the camera-mode comparison failed in the tested scene. server first-endpoint state; full wire connection untested.
- `powergrid:iron_wire` / shoulder: first-person positive control passed; the camera-mode comparison failed in the tested scene. server first-endpoint state; full wire connection untested.
- `powergrid:golden_wire` / shoulder: first-person positive control passed; the camera-mode comparison failed in the tested scene. server first-endpoint state; full wire connection untested.
- `powergrid:insulated_copper_wire` / shoulder: first-person positive control passed; the camera-mode comparison failed in the tested scene. server first-endpoint state; full wire connection untested.
- `powergrid:copper_cord` / shoulder: first-person positive control passed; the camera-mode comparison failed in the tested scene. server first-endpoint state; full wire connection untested.
- `powergrid:wire` / freelook: the first-person control passed; the candidate client rejected selection while the server recorded the requested fresh endpoint. This is a client/server disagreement, not evidence that the server could not select the endpoint. Full connection behavior remains untested.
- `powergrid:iron_wire` / freelook: the first-person control passed; the candidate client rejected selection while the server recorded the requested fresh endpoint. This is a client/server disagreement, not evidence that the server could not select the endpoint. Full connection behavior remains untested.
- `powergrid:golden_wire` / freelook: the first-person control passed; the candidate client rejected selection while the server recorded the requested fresh endpoint. This is a client/server disagreement, not evidence that the server could not select the endpoint. Full connection behavior remains untested.
- `powergrid:insulated_copper_wire` / freelook: the first-person control passed; the candidate client rejected selection while the server recorded the requested fresh endpoint. This is a client/server disagreement, not evidence that the server could not select the endpoint. Full connection behavior remains untested.
- `powergrid:copper_cord` / freelook: the first-person control passed; the candidate client rejected selection while the server recorded the requested fresh endpoint. This is a client/server disagreement, not evidence that the server could not select the endpoint. Full connection behavior remains untested.
- Aeroworks `aeroworks_socket` / shoulder: the loaded ray helper differs from the camera ray while its first-person control passes. This helper check did not operate modules; the separate action suite is reported below.
- Aeroworks `aeroworks_socket` / freelook: the loaded ray helper differs from the camera ray while its first-person control passes. This helper check did not operate modules; the separate action suite is reported below.
- AeroWorks `aeroworks:control_desk` / attach / offset: 34 module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.
- AeroWorks `aeroworks:control_desk` / remove / offset: 34 module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.
- AeroWorks `aeroworks:control_stand` / attach / offset: 34 module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.
- AeroWorks `aeroworks:control_stand` / remove / offset: 34 module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.
- AeroWorks `aeroworks:copycat_control_desk` / attach / offset: 34 module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.
- AeroWorks `aeroworks:copycat_control_desk` / remove / offset: 34 module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.
- AeroWorks `aeroworks:copycat_control_stand` / attach / offset: 34 module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.
- AeroWorks `aeroworks:copycat_control_stand` / remove / offset: 34 module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.
- Separate AeroWorks module-operation suite: completed and restored; 816 scheduled cases, 816 recorded use requests, PASS: 535, FAIL: 281. The older helper-only results remain separate; removal seeds are fixture setup, and other console workflows are untested.
- 9 original AeroWorks freelook comparisons remain recorded as failures requiring timing review. This custom operation uses server head/view aim, while the original packet acknowledgement did not establish a subsequent player tick. Stale head is a hypothesis, not an established cause.
- Separate settled AeroWorks freelook review: completed and restored; 54/54 recorded cases, PASS: 54; 9 original cases did not reproduce in all three settled pairs. Every repeat and its body/head/player-tick evidence remains separate. Original full-sweep rows are unchanged.
- Projectile sweep: 58 scheduled cases; PASS: 55, FAIL: 1; 2 skipped. The 2 first-person baseline cases are counted separately.
- `the_bumblezone:crystal_cannon` / OFF_HAND / projectile: failure also reproduces with the camera disabled; it is not established as a camera regression.
- `the_bumblezone:crystal_cannon` / OFF_HAND / projectile_freelook: failure also reproduces with the camera disabled; it is not established as a camera regression.
- `melee_offset`: completed and restored; 320 scheduled, PASS: 320, 0 skipped. Earlier weapon-mode results are counted separately.
- `projectile_freelook`: completed and restored; 58 scheduled, FAIL: 1, PASS: 55, 2 skipped. Earlier weapon-mode results are counted separately.
- `freelookAbility`: completed and restored; PASS: 1, FAIL: 1. Separate genuine held FIRST_PERSON freelook Magispear ability-key/server-target and server-echoed entity ping fixtures. Does not certify all Simply Swords abilities or ping contexts.
- `abilityPairedRecheck`: completed and restored; PASS: 2. Separate real Magispear ability-key requests in first person and actual freelook, with exact selected/expected server UUID and input/readiness traces. Original ability and ping results remain separate; this bounded pair does not certify other abilities or prove the cause of any earlier failure.
- `entitySpecialized`: completed and restored; PASS: 6, INCONCLUSIVE: 3. Straddleboard: exact board UUID remains mounted immediately and after at least five server ticks. Giant/bone serpent: real server request receipt and target-family/reference geometry only, not arbitrary entity functionality.
- `integrationRecheck`: completed and restored; PASS: 34. Separate standard-scene retry after the invalid distant fixture. Serializes server futures and packet acknowledgements, verifies actual held stacks and nearby target range, and establishes the fixture camera directly each tick. Geometry checks remain distinct from server actions; this does not replace either registry weapon sweep.
- `meleePathDiagnostic`: completed and restored; FAIL: 2, PASS: 22. Separate failed-only first-person/offset diagnostic. Exact client input/cancellation and target/held-state traces accompany server attack/damage attribution. Original 320-item sweep statuses and interrupted attempts remain unchanged. A missing request or invalid baseline is not an automatic camera defect; paired outcomes certify only the named attack fixture.
- The initial Magispear freelook target failure did not reproduce in the separate first-person/freelook pair: both selected the exact expected server UUID with valid key transitions and matching readiness contexts. The earlier failure remains recorded; its cause is not established.
- Failed-only melee rechecks with full attack cooldown: PASS: 22, BASELINE_FAIL: 1. An initial failure is rejected only after its paired first-person/freelook checks pass; original attempts remain in the JSON report.
- Melee health-damage evidence: 319/320 scheduled items across the original sweep and successful rechecks. Create's cardboard sword intentionally cancels damage against the cow; that health oracle is invalid for this item.
- Separate cardboard-sword knockback check: PASS_KNOCKBACK: 3. The original/retry artifacts remain separate. Direction-review results indicate observed knockback with an unresolved direction comparison.
- Physics-staff suite: completed. Selection/session results do not establish force-driven movement.

- `create_freelook`: incomplete or not executed; No results. These are separate actual first-person freelook fixtures with the same bounded action oracles.
- `create_freelook_recheck`: completed; PASS: 25. These are separate actual first-person freelook fixtures with the same bounded action oracles.
- `physics_staff_freelook`: completed; PASS: 18. These are separate actual first-person freelook fixtures with the same bounded action oracles.
- `powergrid_freelook`: completed; PASS: 5, FAIL: 5. These are separate actual first-person freelook fixtures with the same bounded action oracles.

Current-task process logs also record 41 suppressed packet exceptions and 1173 server-lag warnings. Preserved/current process records remain attributed and deduplicated in coverage.json; matching smoke observations do not erase gameplay exceptions. These logs do not by themselves attribute defects to the camera mod or establish benchmark results.

Deep Aether compasses (`deep_aether:bronze_compass`, `deep_aether:gold_compass`, `deep_aether:silver_compass`) produced an installed-mod exception: a structure ResourceKey was supplied as a translatable-text argument. The server suppressed those packet errors. This is a gameplay exception even if the bounded air-use smoke observations match.

The [selected watchdog/server-thread evidence](../build/exhaustive-qa/runtime-lag-evidence.txt) records long ticks in structure/chunk loading, including nearby Naturalist fixture timeouts. Those timeouts are not established camera failures; the captured stack does not identify the original load trigger.

The separate state-ray diagnostic explains 33 initial failing states by independently matching ordinary ray behavior; 0 state differences remain unresolved. Original sweep statuses are preserved above. Matching a vanilla miss is not a functional block pass.

Initial block-request differences: DEFAULT_STATE_SETUP_NOT_SENT: 8, DIFFERENT_HIT_FACE_CONTEXT: 2, ENTITY_OCCLUSION_REQUIRES_FIXTURE: 8, INSTALLED_BLOCK_MIGRATION_REVIEW: 2, MATCHED_SERVER_OBSERVATION_LOCAL_RETURN_DIFF: 17, SERVER_OBSERVATION_DIFFERENCE_REVIEW: 17, UNSTABLE_BLOCK_LIFECYCLE_REVIEW: 6. These categories describe evidence, not confirmed camera defects.

The initial sweep's ordered acknowledgement does not establish server acceptance: Minecraft ignores UseItemOn while a teleport is unconfirmed. It also reset only the target, allowing previous neighbor/support changes. Only the separate settled recheck explicitly gates teleport readiness and resets the whole fixture. Raw observations remain preserved; none is automatically a camera defect.

The freelook block sweep records 37 pre-use input/UI recoveries, including 35 observed cleared freelook keys. Recovery re-establishes real held input, closes any open screen and repeats the preflight before sending a request. A screen-free key interruption does not prove a GUI cause. The earlier aborted attempt remains in the evidence; completed pairs were not relabeled or discarded.

For `create:chain_conveyor`, `simulated:paired_docking_connector`, both initial views recorded no server interaction event, and matching server warnings rejected the outline hit position as too far from its owning block. These are packet-bound fixture limits, not evidence of a camera-only failure. The separate review can aim at an explicitly recorded in-cell surface; it does not alter the rejected original rows.

The Simulated steering wheel uses an installed `QuietUse` interception and custom hold manager. Its matching zero vanilla interaction-event counts are an oracle limit; they do not establish that custom transport failed. The original broad row remains limited, and specialized server-movement checks are reported separately.

Separate three-pair freelook block review: MATCHED_NO_OBSERVABLE_EFFECT: 9, MATCHED_SERVER_LOCAL_RETURN_DIFF: 39, OBSERVED_PARITY_PASS: 3, SERVER_ACTION_NOT_OBSERVED: 6; 17 blocks match all three recorded server comparisons and 0 need specialized fixtures. 2 original server-observation rows and 13 client-return/fallback limitations remain unresolved. Matching no-ops remain no-ops.

Separate block-request rechecks: MATCHED_NO_OBSERVABLE_EFFECT: 41, MATCHED_SERVER_LOCAL_RETURN_DIFF: 47, NEEDS_SPECIALIZED_FIXTURE: 36, OBSERVED_PARITY_PASS: 51, SERVER_DIFFERENCE_REVIEW: 7, UNEQUAL_SETTLED_FIXTURE: 4; 45 blocks matched server observations in all three paired retries, 12 require specialized fixtures, and 3 mix matching observations with unequal starting states or explained interaction regions. These mixed cases are limited comparisons, not full passes. Original records remain unchanged.

Remaining block review cases: 1 unexplained server-observation comparisons and 16 client-return/fallback limitations (categories can overlap). A comparison difference is not automatically a camera defect.

The diode differences for `create:pulse_repeater` select different functional regions of the same face. The recorded first-person hit is inside Create's value-setting slot; the shoulder hit is outside and toggles inversion. Installed bytecode and hit-distance calculations explain this context difference; it is not a camera regression finding or validation of the timing UI.

16 rechecked blocks still returned different client use results despite matching recorded server observations. Those return values can affect vanilla's offhand fallback, which this direct empty-main-hand fixture did not test. These are bounded server-effect comparisons, not full interaction passes.

Failed-only item-use rerun: ERROR: 3, MATCHED_AIR_USE_SMOKE: 63, MATCHED_AUTO_CONSUMED_BEFORE_USE: 14, MATCHED_NO_OBSERVABLE_EFFECT: 18, OBSERVED_DIFFERENCE_REVIEW: 1; 4 initial item-use differences remain unresolved. Matching reruns do not certify all abilities.

14 default package items consumed themselves during the paired inventory-tick setup before any use request could be sent. These are separately observed lifecycle cases, not passed air-use actions or proof of delivery for packages with contents.

The full freelook air-use table contains 2969 item rows, including 2954 items with recorded requests in both views, 14 packages consumed before use, and 1 preparation-error rows without a complete request pair. Recorded requests include packet errors and no-ops; they are not functional-pass counts.

Separate failed-only freelook air-use review: ERROR: 3, MATCHED_AIR_USE_SMOKE: 7, OBSERVED_DIFFERENCE_REVIEW: 5; 8 latest differing/error items remain. Original full-sweep rows and denominator are preserved; bounded matched observations do not certify all abilities.

Those remaining observations are categorized as ENTITY_PRESENCE_OR_LIFETIME_DIFFERENCE: 1, PAIRED_BASELINE_PACKET_EXCEPTION: 3, PASSIVE_OR_EFFECT_SNAPSHOT_DIFFERENCE: 1, SAMPLED_USING_FLAG_ONLY_DIFFERENCE: 3. Matching packet exceptions reproduce in the camera-disabled control; preparation errors have no complete action pair. Snapshot-only differences remain limits, even when their direction reverses between attempts.

Bucket server confirmations remain separate from client behavior: 194 confirmed-server cases lacked an immediate client target effect, and 7 cases had a target/decoy mismatch after twenty server ticks. These are not counted as full client compatibility passes.

Source-backed bucket review explains 51 original generic-oracle misses with the intended entity observed at the item's expected spawn position or with an entity-release item incorrectly treated as pickup. The XP bucket has no ordinary world-fluid block in this installed version; the default jellyfish bucket throws for missing entity data in all three modes. Original statuses remain unchanged, and corrected-oracle/captured-component tests are separate evidence.

Separate bucket review: SERVER_EFFECT_CONFIRMED: 69, including 3 captured-component cases. It records 12 immediate target-prediction differences and 5 twenty-tick target/decoy mismatches. 7/7 original delayed pairs matched both observed blocks by eighty server ticks on retry; 0 review cases retain an eighty-tick mismatch. Default jellyfish exceptions remain recorded separately.

Separate spawn-egg review: SPAWNED_EXPECTED_ENTITY: 318; 0 latest nonpositive item/mode cases remain. All three views are rerun for selected eggs with a fresh baseline, and original full-sweep rows remain intact. Transient blockers are awaited without forcing a hit; only expected-type server joins plus live entities count as positive spawn evidence.

`entityActions` separate cleanup/error recheck: NEEDS_FIXTURE: 146, NO_OBSERVABLE_EFFECT: 7, TARGETING_DIFFERENCE: 1; 1 latest differences remain unresolved. Matching default empty-hand observations do not validate contextual entity abilities.

`entityFreelookActions` separate cleanup/error recheck: NEEDS_FIXTURE: 146, NO_OBSERVABLE_EFFECT: 7, TARGETING_DIFFERENCE: 1; 1 latest differences remain unresolved. Matching default empty-hand observations do not validate contextual entity abilities.

## Create and installed integrations

The family below is the installed dependency closure from Create, Simulated and Sable. It deliberately includes **optional-dependency integrations**, so membership does not imply a dedicated Create addon. Runtime namespaces provide the actual item/block denominator.

| Namespace | Items | Blocks | Held hook cases | Block ray rows | Block requests offset / FP freelook |
| --- | ---: | ---: | ---: | ---: | ---: |
| aeronautics | 46 | 43 | 460 | 43 | 43 / 43 |
| aeronautics_dyeable_components | 80 | 16 | 800 | 16 | 16 / 16 |
| aeroworks | 42 | 8 | 420 | 8 | 8 / 8 |
| alloyed | 85 | 64 | 850 | 64 | 64 / 64 |
| bits_n_bobs | 111 | 148 | 1110 | 148 | 148 / 148 |
| compactflap | 4 | 4 | 40 | 4 | 4 / 4 |
| copycats | 45 | 46 | 450 | 46 | 46 / 46 |
| create | 699 | 643 | 6990 | 643 | 643 / 643 |
| create_connected | 84 | 93 | 840 | 93 | 93 / 93 |
| create_jetpack | 4 | 2 | 40 | 2 | 2 / 2 |
| createbigcannons | 194 | 139 | 1940 | 139 | 139 / 139 |
| createdeco | 402 | 389 | 4020 | 389 | 389 / 389 |
| createdieselgenerators | 60 | 65 | 600 | 65 | 65 / 65 |
| createendertransmission | 4 | 4 | 40 | 4 | 4 / 4 |
| createoplenty | 3 | 0 | 30 | 0 | 0 / 0 |
| createpropulsion | 43 | 33 | 430 | 33 | 33 / 33 |
| dndesires | 111 | 85 | 1110 | 85 | 85 / 85 |
| hotstuff | 5 | 8 | 50 | 8 | 8 / 8 |
| idas | 3 | 0 | 30 | 0 | 0 / 0 |
| integrated_stronghold | 3 | 0 | 30 | 0 | 0 / 0 |
| interiors | 53 | 53 | 530 | 53 | 53 / 53 |
| kineticgrip | 1 | 1 | 10 | 1 | 1 / 1 |
| offroad | 7 | 3 | 70 | 3 | 3 / 3 |
| powergrid | 119 | 77 | 1190 | 77 | 77 / 77 |
| sable_player_ragdoll | 0 | 3 | 0 | 3 | 3 / 3 |
| simulated | 86 | 95 | 860 | 95 | 95 / 95 |
| supplementaries | 374 | 247 | 3740 | 247 | 247 / 247 |
| vsfluidlink | 8 | 12 | 80 | 12 | 12 / 12 |

The separate [Create ecosystem inventory and contextual backlog](../build/exhaustive-qa/create-addon-coverage.md) distinguishes content addons from rendering, world-generation and optional integrations. Its implementation groups are a review queue, not executed gameplay passes.

## Limits and evidence

- No universal proof that every handheld action, component/NBT variant, recipe or block/entity interaction works.
- Held-item hook checks do not exercise item use/release, rendered models, equipment ticks or custom ability packets.
- Default/state geometry does not validate multiblock arrangements, powered Create machinery, contraption assembly or addon operations.
- Generic block actions use an empty main hand; wrench/glue/control fixtures are separately identified.
- Entity target checks are separate from default empty-hand server interactions; neither proves every contextual action or variant.
- Current-task real action results are limited to their recorded hand, mode, world surface and oracle.
- Dedicated-server/multiplayer and moving-sublevel combinations are not inferred from integrated-server tests.

Detailed counts, failure rows, action attempts, missing coverage and source hashes: `build/exhaustive-qa/coverage.json`. Raw current-task lines: `build/exhaustive-qa/current-results.log`. Registry/CSV/TSV evidence remains under `build/modpack-test/game/qa`.
