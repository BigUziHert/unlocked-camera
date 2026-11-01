"""Compare tested production class bytes with the user's installed release JAR."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
source_manifest = json.loads((ROOT / 'build/exhaustive-qa/current-source-manifest.json').read_text(encoding='utf-8-sig'))
source = Path(source_manifest['source'])
jars = sorted((source / 'mods').glob('unlockedcamera-*.jar'))
if len(jars) != 1:
    raise RuntimeError(f'Expected exactly one installed camera JAR, found {len(jars)}')
installed = jars[0]
classes = ROOT / 'build/classes/java/main'
tested = {path.relative_to(classes).as_posix(): path.read_bytes()
          for path in classes.rglob('*.class') if '/qa/' not in path.relative_to(classes).as_posix()}
with zipfile.ZipFile(installed) as jar:
    release = {name: jar.read(name) for name in jar.namelist() if name.endswith('.class')}
    qa_in_release = [name for name in release if '/qa/' in name]
differences = sorted(name for name in set(tested) | set(release) if tested.get(name) != release.get(name))
result = {'installedJar': str(installed), 'sha256': hashlib.sha256(installed.read_bytes()).hexdigest(),
          'installedClasses': len(release), 'testedClasses': len(tested), 'differences': differences,
          'qaClassesInInstalledRelease': qa_in_release}
(ROOT / 'build/exhaustive-qa/production-identity.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result))
if differences or qa_in_release:
    raise SystemExit(1)
