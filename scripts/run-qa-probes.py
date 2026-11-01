"""Run the opt-in disposable client's probes serially and retain this run's log.

The client must already be launched with -PcameraQa in build/modpack-test/game.
This runner never turns a census, smoke check, or incomplete probe into a pass.
"""
from pathlib import Path
import argparse
import hashlib
import json
import time

ROOT = Path(__file__).resolve().parent.parent
QA = ROOT / 'build/modpack-test/game/qa'
EVIDENCE = ROOT / 'build/exhaustive-qa'
PROBES = {
    'buckethold': ('probe BucketHoldRegressionProbe', 'BUCKET_HOLD RESTORED', 600),
    'items': ('probe RegistryItemProbe', 'REGISTRY_ITEMS DONE', 600),
    'entities': ('probe EntityInteractionProbe', 'REGISTRY_ENTITIES DONE', 600),
    'addons': ('probe AddonRayProbe', 'ADDON_RAY DONE', 60),
    'blocks': ('blockprobe', 'BLOCK_TARGETING DONE', 900),
    'states': ('blockstates', 'BLOCK_STATE_TARGETING DONE', 1800),
    'create': ('probe CreateInteractionProbe', 'CREATE_INTERACTIONS RESTORED', 600),
    'projectiles': ('projectileall', 'INTEGRATION DONE', 1200),
    'projectileoffset': ('probe OffsetProjectileRegressionProbe', 'PROJECTILE_OFFSET_SUITE RESTORED', 1800),
    'projectileoffsetrecheck': ('probe OffsetProjectileRecheckProbe', 'PROJECTILE_OFFSET_RECHECK_SUITE RESTORED', 1800),
    'melee': ('meleeall', 'INTEGRATION DONE', 3600),
    'interactions': ('blockinteractions all', 'BLOCK_INTERACTIONS DONE', 10800),
    'uses': ('probe ItemUseProbe', 'ITEM_AIR_USE DONE', 10800),
    'powergrid': ('probe PowergridInteractionProbe', 'POWERGRID RESTORED', 600),
    'physics': ('probe PhysicsStaffProbe', 'PHYSICS_STAFF RESTORED', 600),
    'crystalbaseline': ('projectilebaseline the_bumblezone:crystal_cannon', 'INTEGRATION DONE', 180),
    'meleerecheck': ('probe MeleeBaselineProbe', 'MELEE_RECHECK DONE', 3600),
    'meleetail': ('probe MeleeRemainingProbe', 'MELEE_RECHECK DONE', 600),
    'physicsretry': ('probe PhysicsStaffScenario', 'PHYSICS_STAFF RESTORED', 600),
    'physicsrecheck': ('probe PhysicsStaffRecheckProbe', 'PHYSICS_STAFF_RECHECK RESTORED', 1200),
    'physicsfreelookrecheck': ('probe PhysicsStaffFreelookRecheckProbe', 'PHYSICS_STAFF_FREELOOK_RECHECK RESTORED', 1200),
    'statediagnostic': ('probe StateRayDiagnostic', 'STATE_RAY_DIAGNOSTIC DONE', 300),
    'entityactions': ('probe EntityActionProbe', 'ENTITY_ACTIONS RESTORED', 5400),
    'cardboard': ('probe CardboardKnockbackProbe', 'CARDBOARD_KNOCKBACK RESTORED', 180),
    'cardboardrecheck': ('probe CardboardKnockbackRecheckProbe', 'CARDBOARD_KNOCKBACK_RECHECK RESTORED', 600),
    'usereview': ('probe ItemUseReviewProbe', 'ITEM_AIR_USE RESTORED', 3600),
    'usereviewretry': ('probe ItemUseReviewRetryProbe', 'ITEM_AIR_USE RESTORED', 3600),
    'blockreview': ('probe BlockInteractionReviewProbe', 'BLOCK_RECHECK RESTORED', 1800),
    'blockresume': ('probe BlockSweepResumeProbe', 'BLOCK_INTERACTIONS DONE', 10800),
    'createselection': ('probe CreateSelectionProbe', 'CREATE_SELECTION RESTORED', 300),
    'createselectionfix': ('probe CreateSelectionFixProbe', 'CREATE_SELECTION_FIX RESTORED', 300),
    'integration': ('integrationprobe', 'INTEGRATION DONE', 900),
    'integrationrecheck': ('probe IntegrationRecheckProbe', 'INTEGRATION_RECHECK_SUITE RESTORED', 1200),
    'spawneggs': ('probe SpawnEggUseProbe', 'SPAWN_EGG_USE RESTORED', 10800),
    'spawneggreview': ('probe SpawnEggUseReviewProbe', 'SPAWN_EGG_RECHECK RESTORED', 3600),
    'spawneggremaining': ('probe SpawnEggUseRemainingProbe', 'SPAWN_EGG_RECHECK RESTORED', 3600),
    'stormsedgeuse': ('probe StormsEdgeUseRecheckProbe', 'STORMS_EDGE_RECHECK RESTORED', 900),
    'buckets': ('probe BucketUseProbe', 'BUCKET_USE RESTORED', 3600),
    'bucketreview': ('probe BucketUseReviewProbe', 'BUCKET_RECHECK RESTORED', 1800),
    'bucketpolicy': ('probe BucketRayPolicyProbe', 'BUCKET_RAY_POLICY RESTORED', 120),
    'bucketpredictionfix': ('probe BucketPredictionFixProbe', 'BUCKET_PREDICTION_FIX RESTORED', 5400),
    'buckethands': ('probe BucketHandSmokeProbe', 'BUCKET_HAND_SMOKE RESTORED', 600),
    'bucketopenwater': ('probe BucketOpenWaterProbe', 'BUCKET_OPEN_WATER RESTORED', 300),
    'bucketpolicyrecheck': ('probe BucketRayPolicyRecheckProbe', 'BUCKET_RAY_POLICY_RECHECK RESTORED', 120),
    'serverisolation': ('probe ServerIsolationProbe', 'SERVER_ISOLATION RESTORED', 180),
    'sableraymetric': ('probe SableRayMetricProbe', 'SABLE_RAY_METRIC RESTORED', 120),
    'sableaddons': ('probe SableAddonInteractionProbe', 'SABLE_ADDON_ACTION RESTORED', 1200),
    'sableaddonrecheck': ('probe SableAddonRecheckProbe', 'SABLE_ADDON_RECHECK RESTORED', 1200),
    'playerrecovery': ('probe QaPlayerRecoveryProbe', 'QA_PLAYER_RECOVERY DONE', 300),
    'powergridregression': ('probe PowergridRegressionProbe', 'POWERGRID_REGRESSION RESTORED', 1200),
    'powergridfreelookregression': ('probe PowergridFreelookRegressionProbe', 'POWERGRID_FREELOOK_REGRESSION RESTORED', 1200),
    'aeroworksregression': ('probe AeroworksModuleRegressionProbe', 'AEROWORKS_MODULE_REGRESSION RESTORED', 5400),
    'aeroworksremaining': ('probe AeroworksModuleRemainingProbe', 'AEROWORKS_MODULE_REMAINING RESTORED', 5400),
    'aeroworkssmoke': ('probe AeroworksModuleSmokeProbe', 'AEROWORKS_MODULE_SMOKE RESTORED', 600),
    'freelookstates': ('probe BlockFreelookGeometryProbe', 'BLOCK_STATE_FREELOOK_TARGETING RESTORED', 1800),
    'freelookblocks': ('probe BlockFreelookProbe', 'BLOCK_FREELOOK RESTORED', 10800),
    'freelookblockresume': ('probe BlockFreelookResumeProbe', 'BLOCK_FREELOOK RESTORED', 10800),
    'freelookblockguardedresume': ('probe BlockFreelookResumeProbe\nprobe BlockFreelookLoadGuardProbe', 'BLOCK_FREELOOK RESTORED', 10800),
    'freelookblockreview': ('probe BlockFreelookReviewProbe', 'BLOCK_FREELOOK_RECHECK RESTORED', 3600),
    'freelookuses': ('probe ItemUseFreelookProbe', 'ITEM_AIR_USE RESTORED', 14400),
    'freelookusereview': ('probe ItemUseFreelookReviewProbe', 'ITEM_FREELOOK_REVIEW RESTORED', 3600),
    'freelookcreate': ('probe CreateFreelookProbe', 'CREATE_FREELOOK RESTORED', 600),
    'freelookcreaterecheck': ('probe CreateFreelookRecheckProbe', 'CREATE_FREELOOK_RECHECK RESTORED', 1200),
    'freelookphysics': ('probe PhysicsStaffFreelookProbe', 'PHYSICS_STAFF_FREELOOK RESTORED', 600),
    'freelookpowergrid': ('probe PowergridFreelookProbe', 'POWERGRID_FREELOOK RESTORED', 600),
    'powergridcorrected': ('probe PowergridEndpointScenario', 'POWERGRID RESTORED', 600),
    'powergridsession': ('probe PowergridEndpointSession', 'POWERGRID RESTORED', 1200),
    'powergridfreelooksession': ('probe PowergridFreelookSessionProbe', 'POWERGRID_FREELOOK RESTORED', 1200),
    'powergridready': ('probe PowergridEndpointReadyScenario', 'POWERGRID RESTORED', 1200),
    'powergridfreelookready': ('probe PowergridFreelookReadyProbe', 'POWERGRID_FREELOOK RESTORED', 1200),
    'physicsfreelooksession': ('probe PhysicsStaffFreelookSessionProbe', 'PHYSICS_STAFF_FREELOOK RESTORED', 1200),
    'meleeoffset': ('probe OffsetMeleeProbe', 'MELEE_OFFSET_SUITE RESTORED', 3600),
    'meleeoffsetrecheck': ('probe OffsetMeleeRecheckProbe', 'MELEE_OFFSET_RECHECK_SUITE RESTORED', 3600),
    'meleepath': ('probe MeleePathDiagnosticProbe', 'MELEE_PATH_SUITE RESTORED', 1200),
    'meleeresume': ('probe OffsetMeleeResumeProbe', 'MELEE_OFFSET_SUITE RESTORED', 3600),
    'projectilefreelook': ('probe FreelookProjectileProbe', 'PROJECTILE_FREELOOK_SUITE RESTORED', 1800),
    'abilityfreelook': ('probe FreelookAbilityProbe', 'ABILITY_FREELOOK_SUITE RESTORED', 300),
    'abilitypaired': ('probe AbilityPairedRecheckProbe', 'ABILITY_PAIR_SUITE RESTORED', 600),
    'entityreview': ('probe EntityActionReviewProbe', 'ENTITY_ACTIONS_RECHECK RESTORED', 1800),
    'entityspecialized': ('probe EntitySpecializedProbe', 'ENTITY_SPECIALIZED RESTORED', 1800),
    'aeroworksmodules': ('probe AeroworksModuleProbe', 'AEROWORKS_MODULE RESTORED', 3600),
    'aeroworksfreelookreview': ('probe AeroworksModuleFreelookReviewProbe', 'AEROWORKS_MODULE_FREELOOK_RECHECK RESTORED', 3600),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('probes', nargs='+', choices=PROBES)
    parser.add_argument('--qa', type=Path, default=QA, help='Disposable client qa directory.')
    parser.add_argument('--evidence', type=Path, default=EVIDENCE, help='Per-client runner evidence directory.')
    parser.add_argument('--wait-after', help='Wait after the LAST occurrence of this existing log marker before sending anything.')
    parser.add_argument('--wait-until', help='Completion marker following --wait-after; keeps an already running probe undisturbed.')
    parser.add_argument('--wait-for-start', action='store_true', help='Allow --wait-after to name a queued future probe; wait for its start first.')
    args = parser.parse_args()
    qa, evidence = args.qa.resolve(), args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    run_name = '-'.join(args.probes)
    if len(run_name) > 100:
        run_name = run_name[:48] + '-' + hashlib.sha256(run_name.encode()).hexdigest()[:12]
    runner_report = evidence / ('runner-' + run_name + '.json')
    results = []
    request = qa / 'request.txt'
    log = qa / 'results.log'
    if args.wait_after:
        if not args.wait_until:
            parser.error('--wait-after requires --wait-until')
        text = log.read_text(encoding='utf-8')
        after = text.rfind(args.wait_after)
        if after < 0 and args.wait_for_start:
            print('WAIT future probe: ' + args.wait_after, flush=True)
            start_deadline = time.monotonic() + 10800
            while after < 0:
                if time.monotonic() > start_deadline:
                    raise RuntimeError('Queued probe did not start; no request sent')
                time.sleep(.5)
                text = log.read_text(encoding='utf-8')
                after = text.rfind(args.wait_after)
        if after < 0:
            raise RuntimeError('Existing probe start marker absent: ' + args.wait_after)
        print('WAIT existing probe: ' + args.wait_after, flush=True)
        began = last_progress = time.monotonic()
        while args.wait_until not in text[after:]:
            if ' ABORT' in text[after:] or time.monotonic() - began > 10800:
                raise RuntimeError('Existing probe aborted or timed out; no further request sent')
            if time.monotonic() - last_progress >= 30:
                print('WAIT ' + text.splitlines()[-1][:300], flush=True)
                last_progress = time.monotonic()
            time.sleep(.5)
            text = log.read_text(encoding='utf-8')
        if ' ABORT' in text[after:]:
            raise RuntimeError('Existing probe aborted; inspect its restoration before continuing')
        time.sleep(2)
    for name in args.probes:
        command, marker, timeout = PROBES[name]
        idle_deadline = time.monotonic() + 300
        while request.exists():
            if time.monotonic() > idle_deadline:
                raise RuntimeError('Previous QA request not consumed; client may not be ready')
            time.sleep(.5)
        start = log.stat().st_size if log.exists() else 0
        request.write_text(command + '\n', encoding='utf-8')
        print(f'START {name}: {command}', flush=True)
        began = last_progress = time.monotonic()
        status = 'TIMEOUT'
        body = ''
        while time.monotonic() - began < timeout:
            with log.open('rb') as file:
                file.seek(start)
                body = file.read().decode('utf-8', 'replace')
            if any(line.startswith('ERROR ') or ' ABORT' in line for line in body.splitlines()):
                status = 'ABORTED'
                break
            if marker in body:
                status = 'COMPLETED'
                break
            if time.monotonic() - last_progress >= 30:
                tail = body.splitlines()[-1] if body.splitlines() else 'waiting for client'
                print(f'PROGRESS {name} seconds={int(time.monotonic()-began)} {tail[:350]}', flush=True)
                last_progress = time.monotonic()
            time.sleep(.5)
        (evidence / f'{name}-results.log').write_text(body, encoding='utf-8')
        record = {'probe': name, 'command': command, 'execution': status,
                  'elapsedSeconds': round(time.monotonic()-began, 1),
                  'note': 'COMPLETED only means the probe terminated; inspect per-case outcomes.'}
        results.append(record)
        runner_report.write_text(json.dumps(results, indent=2))
        print(f'{status} {name} seconds={record["elapsedSeconds"]}\n' + '\n'.join(body.splitlines()[-3:]), flush=True)
        if status != 'COMPLETED':
            raise SystemExit(1)
        time.sleep(2)  # Allow asynchronous fixture restoration before next suite.


if __name__ == '__main__':
    main()
