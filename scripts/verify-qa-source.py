"""Verify the read-only source files against the snapshot used for disposable QA."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
manifest_path = ROOT / 'build/exhaustive-qa/current-source-manifest.json'
manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
source = Path(manifest['source'])
differences = []
for entry in manifest['files']:
    path = source / entry['path']
    if not path.is_file():
        differences.append({'path': entry['path'], 'status': 'missing'})
        continue
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != entry['sha256']:
        differences.append({'path': entry['path'], 'status': 'changed',
                            'expected': entry['sha256'], 'actual': digest.hexdigest()})
result = {'verifiedAt': datetime.now(timezone.utc).isoformat(),
          'source': str(source), 'manifest': str(manifest_path),
          'files': len(manifest['files']), 'status': 'MATCH' if not differences else 'DIFFERENCES',
          'differences': differences,
          'scope': 'Only snapshotted mods/config/defaultconfigs/resourcepacks/shaderpacks/options; no writes to source. Changes by another process are not attributed to QA.'}
(ROOT / 'build/exhaustive-qa/current-source-verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps({'files': result['files'], 'status': result['status'], 'differences': differences}))
if differences:
    raise SystemExit(1)
