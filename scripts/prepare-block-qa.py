"""Prepare a second disposable client for concurrent block QA. Refuses an existing copy."""
from pathlib import Path
import hashlib
import json
import shutil

root = Path(__file__).resolve().parent.parent
source = root / 'build/modpack-test/game'
world = root / 'run/saves/New World'
target = root / 'build/modpack-block-test/game'
if target.exists():
    raise SystemExit('Second QA directory already exists; preserve it before preparing another copy.')
if not (source / 'mods').is_dir() or not (world / 'level.dat').is_file():
    raise SystemExit('Main copied pack and dormant development test world are required.')
target.mkdir(parents=True)
for name in ('mods', 'config', 'defaultconfigs', 'kubejs', 'datapacks', 'resourcepacks', 'shaderpacks', 'tacz'):
    if (source / name).is_dir():
        shutil.copytree(source / name, target / name)
if (source / 'options.txt').exists():
    shutil.copy2(source / 'options.txt', target / 'options.txt')
shutil.copytree(world, target / 'saves/Camera QA', ignore=shutil.ignore_patterns('session.lock'))
(target / 'qa').mkdir()
(target / 'qa/request.txt').write_text('unpause\nprobe RegistryCensusProbe\n')
manifest = []
for jar in sorted((target / 'mods').iterdir()):
    if jar.is_file():
        manifest.append({'name': jar.name, 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()})
report = {'game': str(target), 'packCopiedFrom': str(source), 'worldCopiedFrom': str(world),
          'sourceWorldLastWrite': (world / 'level.dat').stat().st_mtime,
          'purpose': 'Concurrent compatibility checks only; not performance measurements', 'mods': manifest}
(root / 'build/exhaustive-qa/secondary-copy.json').write_text(json.dumps(report, indent=2))
print('Prepared', target, 'with', len(manifest), 'mod files and a separate copied world.')
