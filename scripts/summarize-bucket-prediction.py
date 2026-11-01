"""Summarize new bucket-fix evidence without modifying any probe artifact.

Server confirmation and immediate client prediction are independent checks.
Captured jellyfish rows never clear the separately recorded default-stack NPE.
"""
from pathlib import Path
import argparse
from collections import Counter
import csv
import hashlib
import json

ROOT = Path(__file__).resolve().parent.parent
VIEWS = ('first_person', 'shoulder', 'freelook')
PREFIXES = ('prediction-fix-', 'prediction-hands-', 'prediction-open-water-')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def key(row):
    return row['item'], row['mode']


def fields(value):
    return dict(part.split('=', 1) for part in value.split(';') if '=' in part)


def summarize(directory):
    directory = directory.resolve()
    if not directory.name.startswith(PREFIXES):
        raise ValueError(f'Refusing to label a baseline directory as post-fix evidence: {directory}')
    outcomes = directory / 'outcomes.tsv'
    manifest = directory / 'registered-items.txt'
    items = sorted(set(manifest.read_text(encoding='utf-8').splitlines()))
    hand_cases = directory.name.startswith(('prediction-hands-', 'prediction-open-water-'))
    modes = [f'{view}_{hand}' for hand in ('main_hand', 'off_hand') for view in VIEWS] if hand_cases else list(VIEWS)
    expected = {(item, mode) for item in items for mode in modes}
    with outcomes.open(encoding='utf-8', newline='') as stream:
        attempts = list(csv.DictReader(stream, delimiter='\t'))
    latest = {key(row): row for row in attempts}
    malformed = [i + 2 for i, row in enumerate(attempts) if None in row or any(value is None for value in row.values())]
    rows = list(latest.values())
    confirmed = [row for row in rows if row['status'] == 'SERVER_EFFECT_CONFIRMED']
    unsupported = [row for row in rows if row['item'] == 'sophisticatedcore:xp_bucket'
                   and row['status'] == 'EXPECTED_SERVER_EFFECT_NOT_OBSERVED']
    unexpected_server = [row for row in rows if row not in confirmed and row not in unsupported]
    predictions = [(row, fields(row.get('client_prediction', ''))) for row in confirmed]
    immediate_misses = [row for row, values in predictions if values.get('immediateTargetEffect') == 'false']
    immediate_unmeasured = [row for row, values in predictions if values.get('immediateTargetEffect') not in
                            ('true', 'false', 'NOT_APPLICABLE_ENTITY_SERVER_ONLY')]
    late = {}
    for ticks in (20, 80):
        names = (f'after{ticks}TargetMatchesServer', f'after{ticks}DecoyMatchesServer')
        late[str(ticks)] = {
            'mismatches': [row for row, values in predictions if any(values.get(name) == 'false' for name in names)],
            'unmeasured': [row for row, values in predictions if any(values.get(name) not in ('true', 'false') for name in names)],
        }
    missing, extra = sorted(expected - latest.keys()), sorted(latest.keys() - expected)
    accepted = not (missing or extra or malformed or unexpected_server or immediate_misses or immediate_unmeasured
                    or any(value['mismatches'] or value['unmeasured'] for value in late.values()))
    return {
        'directory': str(directory), 'outcomes_sha256': digest(outcomes), 'manifest_sha256': digest(manifest),
        'scheduled_items': len(items), 'scheduled_cases': len(expected), 'attempt_rows': len(attempts),
        'unique_cases': len(rows), 'missing_cases': missing, 'unexpected_cases': extra, 'malformed_lines': malformed,
        'statuses': dict(Counter(row['status'] for row in rows)),
        'server_effect_confirmed': len(confirmed), 'known_xp_world_placement_unsupported': unsupported,
        'unexpected_server_results': unexpected_server,
        'immediate_prediction_all_observed_cases': dict(Counter(fields(row.get('client_prediction', '')).get('immediateTargetEffect', 'MISSING') for row in rows)),
        'immediate_prediction': dict(Counter(values.get('immediateTargetEffect', 'MISSING') for _, values in predictions)),
        'immediate_prediction_scope': 'server_effect_confirmed_rows_only;all_observed_counts_are_also_reported_separately',
        'immediate_prediction_misses': immediate_misses, 'immediate_prediction_unmeasured': immediate_unmeasured,
        'late_client_server_comparison': late,
        'all_required_outcome_checks_pass': accepted,
        'controller_done_and_restored': 'VERIFY_SEPARATE_RUNNER_LOG;row_coverage_alone_does_not_establish_restoration',
        'limits': [
            'Server-effect confirmation alone does not certify client prediction.',
            'Legal captured jellyfish fixture does not clear the prior default-stack missing-data NPE.',
            'XP fluid has no world-block implementation; its paired unsupported outcomes remain explicit.',
            'Only the recorded item, hand, camera mode and fixture contexts are tested.',
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', nargs='+', type=Path)
    parser.add_argument('--output', type=Path,
                        default=ROOT / 'build/compat-fix-qa-2026-11-01/bucket-prediction-summary.json')
    args = parser.parse_args()
    reports = [summarize(directory) for directory in args.directories]
    destination = args.output.resolve()
    # Do not permit an accidentally selected probe file to become an output.
    for directory in args.directories:
        if destination.is_relative_to(directory.resolve()):
            raise ValueError('Summary output must be outside every source artifact directory')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps({'runs': reports}, indent=2) + '\n', encoding='utf-8')
    for report in reports:
        print(json.dumps({name: report[name] for name in ('directory', 'unique_cases', 'scheduled_cases',
                         'server_effect_confirmed', 'immediate_prediction_all_observed_cases', 'immediate_prediction', 'all_required_outcome_checks_pass')}))
    print(f'Written separate summary: {destination}')


if __name__ == '__main__':
    main()
