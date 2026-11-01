"""Freeze the release plus separate QA instrumentation for a disposable manual run."""
import hashlib
import json
import shutil
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASE = ROOT / "build/libs/unlockedcamera-1.0.3.jar"
GAME = ROOT / "build/modpack-test/game"
CLASSES = ROOT / "build/classes/java/main"
QA_RESOURCE = ROOT / "src/qa/resources/unlockedcamera.manualqa.mixins.json"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    for name in ("ManualOffsetRecorder", "ManualOffsetArenaProbe", "ModpackQa"):
        if not (CLASSES / f"com/caleb/unlockedcamera/qa/{name}.class").is_file():
            raise RuntimeError(f"Compile QA class first: {name}")
    if not QA_RESOURCE.is_file():
        raise RuntimeError("Missing manual recorder mixin configuration")
    if (GAME / "qa/request.txt").exists():
        raise RuntimeError("A QA request is already pending")
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H%M%SZ")
    session = ROOT / "build/manual-offset-qa" / stamp
    runtime = session / "runtime"
    runtime.mkdir(parents=True, exist_ok=False)
    production = {}
    with zipfile.ZipFile(RELEASE) as archive:
        for name in archive.namelist():
            if name.endswith("/"):
                continue
            dest = (runtime / name).resolve()
            if not dest.is_relative_to(runtime.resolve()):
                raise RuntimeError(f"Unsafe archive entry: {name}")
            if "/qa/" in name:
                raise RuntimeError("Release contains QA instrumentation")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(archive.read(name))
            if name.endswith(".class"):
                production[name] = sha(dest)
    shutil.copytree(CLASSES / "com/caleb/unlockedcamera/qa", runtime / "com/caleb/unlockedcamera/qa")
    shutil.copy2(QA_RESOURCE, runtime / QA_RESOURCE.name)
    metadata = runtime / "META-INF/neoforge.mods.toml"
    with metadata.open("a", encoding="utf-8") as out:
        out.write('\n# Disposable manual-session recorder only.\n[[mixins]]\nconfig="unlockedcamera.manualqa.mixins.json"\n')

    replacements = {
        ROOT / "build/classes/java/main": runtime,
        ROOT / "build/resources/main": runtime,
        ROOT / "build/moddev/modpackTestLegacyClasspath.txt": session / "legacy-classpath.txt",
    }
    def rewrite(source, dest):
        content = source.read_text(encoding="utf-8")
        for before, after in replacements.items():
            content = content.replace(str(before).replace("\\", "\\\\"), str(after).replace("\\", "\\\\"))
            content = content.replace(str(before), str(after))
        dest.write_text(content, encoding="utf-8")
    rewrite(ROOT / "build/moddev/modpackTestRunClasspath.txt", session / "classpath.txt")
    rewrite(ROOT / "build/moddev/modpackTestLegacyClasspath.txt", session / "legacy-classpath.txt")
    rewrite(ROOT / "build/moddev/modpackTestRunVmArgs.txt", session / "vm-args.txt")
    shutil.copy2(ROOT / "build/moddev/modpackTestRunProgramArgs.txt", session / "program-args.txt")
    shutil.copy2(RELEASE, session / RELEASE.name)
    shutil.copy2(GAME / "options.txt", session / "original-options.txt")
    config = GAME / "config/unlockedcamera-client.toml"
    if config.exists():
        shutil.copy2(config, session / "original-unlockedcamera-client.toml")
    manifest = {
        "createdUtc": stamp, "game": str(GAME), "runtime": str(runtime),
        "release": str(RELEASE), "releaseSha256": sha(RELEASE),
        "productionClassCount": len(production), "productionClasses": production,
        "qaClassCount": len(list((runtime / "com/caleb/unlockedcamera/qa").rglob("*.class"))),
        "optionsBeforeSha256": sha(GAME / "options.txt"),
        "instrumentation": "QA-only passive recorder; not present in the production release",
    }
    (session / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    (session.parent / "latest-session.txt").write_text(str(session), encoding="utf-8")
    print(json.dumps({"session": str(session), "releaseSha256": manifest["releaseSha256"],
                      "productionClassCount": len(production), "qaClassCount": manifest["qaClassCount"]}))

if __name__ == "__main__":
    main()
