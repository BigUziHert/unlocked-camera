"""Refresh only the disposable pack files from a read-only source instance.

Existing destination files that change are archived. The QA world is retained.
Run only while the disposable Minecraft client is closed.
"""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import shutil

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--instance', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
source = args.instance.resolve()
game = (root / 'build/modpack-test/game').resolve()
assert (source / 'mods').is_dir() and source != game and source not in game.parents
stamp = datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')
archive = root / 'build/exhaustive-qa' / ('pre-refresh-' + stamp)
manifest = []
copied, archived = [], []


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def preserve(path):
    assert game in path.resolve().parents and path.is_file()
    dest = archive / path.relative_to(game)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(path), str(dest))
    archived.append(str(path.relative_to(game)))


source_mods = {p.name for p in (source / 'mods').iterdir() if p.is_file() and not p.name.startswith('unlockedcamera-')}
for existing in (game / 'mods').iterdir():
    if existing.is_file() and existing.name not in source_mods:
        preserve(existing)
for folder in ('mods', 'config', 'defaultconfigs', 'kubejs', 'datapacks', 'resourcepacks', 'shaderpacks'):
    if not (source / folder).is_dir():
        continue
    for path in sorted((source / folder).rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(source)
        sha = digest(path)
        manifest.append({'path': relative.as_posix(), 'size': path.stat().st_size, 'sha256': sha})
        if folder == 'mods' and path.name.startswith('unlockedcamera-'):
            continue  # Development classes supply the camera mod under test.
        dest = game / relative
        if dest.exists() and digest(dest) == sha:
            continue
        if dest.exists():
            preserve(dest)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
        copied.append(relative.as_posix())
options = source / 'options.txt'
if options.is_file():
    manifest.append({'path': 'options.txt', 'size': options.stat().st_size, 'sha256': digest(options)})
    if (game / 'options.txt').exists():
        preserve(game / 'options.txt')
    shutil.copy2(options, game / 'options.txt')
evidence = root / 'build/exhaustive-qa'
evidence.mkdir(parents=True, exist_ok=True)
(evidence / 'current-source-manifest.json').write_text(json.dumps({'source': str(source), 'capturedAt': stamp, 'files': manifest}, indent=2))
report = {'source': str(source), 'game': str(game), 'archive': str(archive), 'sourceFileCount': len(manifest),
          'copied': copied, 'archived': archived, 'cameraOverride': 'development classes; source installed camera JAR excluded'}
(evidence / 'refresh-report.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
