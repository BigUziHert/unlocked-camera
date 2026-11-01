"""Read installed JARs; enumerate Create-family dependencies and ray-related classes.

This is static inventory, never a gameplay pass. Runtime registry census is authoritative
for item/block IDs because assets and translations can describe unregistered entries.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile
from datetime import datetime, timezone

try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib


def references(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        return []
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    at, i = 10, 1
    while i < count:
        tag = data[at]
        at += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, at)[0]
            at += 2
            pool[i] = data[at:at + n].decode("utf8", "replace")
            at += n
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, struct.unpack_from(">H", data, at)[0])
            at += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag, *struct.unpack_from(">HH", data, at))
            at += 4
        elif tag in (3, 4):
            at += 4
        elif tag in (5, 6):
            at += 8
            i += 1
        elif tag == 15:
            at += 3
        else:
            raise ValueError(f"Unknown constant pool tag {tag}")
        i += 1
    found = []
    for entry in pool:
        if not isinstance(entry, tuple) or entry[0] not in (10, 11):
            continue
        _, owner_id, name_type_id = entry
        _, name_id, descriptor_id = pool[name_type_id]
        name = pool[name_id]
        if name not in {"getEyePosition", "getViewVector", "getLookAngle", "getViewYRot",
                        "getViewXRot", "pick", "clip", "rayTrace", "getTraceTarget",
                        "traceToOutline", "getEntityHitResult"}:
            continue
        owner = pool[pool[owner_id][1]]
        found.append(f"{owner}#{name}{pool[descriptor_id]}")
    return sorted(set(found))


def read_jar(blob, name, output):
    with zipfile.ZipFile(io.BytesIO(blob)) as jar:
        entry = {"jar": name, "sha256": hashlib.sha256(blob).hexdigest(), "mods": [],
                 "dependencies": [], "rayReferenceClasses": []}
        for path in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if path not in jar.namelist():
                continue
            metadata = tomllib.loads(jar.read(path).decode("utf8"))
            entry["mods"] += [{"id": m.get("modId"), "version": m.get("version"),
                               "name": m.get("displayName"), "description": m.get("description", "").strip()}
                              for m in metadata.get("mods", [])]
            for mod, dependencies in metadata.get("dependencies", {}).items():
                for dependency in dependencies:
                    entry["dependencies"].append({"mod": mod, **dependency})
            # NeoForge's descriptor takes precedence when a universal JAR also
            # carries an older Forge descriptor; do not count both as loaded.
            break
        output.append(entry)
        for path in jar.namelist():
            if path.endswith(".jar"):
                read_jar(jar.read(path), f"{name}!/{path}", output)
        # Only scan classes after dependency graph classification, below.


def blob_at(top, path):
    parts = path.split("!/")
    blob = (top / parts[0]).read_bytes()
    for part in parts[1:]:
        with zipfile.ZipFile(io.BytesIO(blob)) as jar:
            blob = jar.read(part)
    return blob


def dependency_kind(dependency):
    if "type" in dependency:
        return dependency["type"]
    if "mandatory" in dependency:
        return "required" if dependency["mandatory"] else "optional"
    return "unspecified"


def dependency_paths(jars, seeds, allowed):
    """Shortest dependency paths, scoped to the descriptor's owning mod ID.

    A required chain is not necessarily a content addon: it can be a renderer,
    world generator or compatibility bridge. Keep that distinction explicit.
    """
    paths = {identifier: [identifier] for identifier in seeds}
    changed = True
    while changed:
        changed = False
        for jar in jars:
            for dependency in jar["dependencies"]:
                owner, target = dependency["mod"], dependency.get("modId")
                if dependency_kind(dependency) not in allowed or target not in paths:
                    continue
                candidate = [owner] + paths[target]
                if owner not in paths or len(candidate) < len(paths[owner]):
                    paths[owner] = candidate
                    changed = True
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mods", type=Path, default=Path("build/modpack-test/game/mods"))
    parser.add_argument("--output", type=Path, default=Path("build/compat-audit/create-addons.json"))
    args = parser.parse_args()
    jars = []
    for path in sorted(args.mods.glob("*.jar")):
        read_jar(path.read_bytes(), path.name, jars)
    seeds = {"create", "aeronautics", "simulated", "sable"}
    all_paths = dependency_paths(jars, seeds, {"required", "optional"})
    required_paths = dependency_paths(jars, seeds, {"required"})
    family = set(all_paths)
    selected = [j for j in jars if any(m["id"] in family for m in j["mods"])]
    relationships = []
    for entry in selected:
        for mod in entry["mods"]:
            identifier = mod["id"]
            if identifier not in family:
                continue
            dependencies = [d for d in entry["dependencies"] if d["mod"] == identifier and d.get("modId") in family]
            relationships.append({"id": identifier, "version": mod["version"], "name": mod["name"],
                                  "jar": entry["jar"],
                                  "classification": "core" if identifier in seeds else "required_dependency_chain" if identifier in required_paths else "optional_integration_closure",
                                  "shortestDependencyPathToCore": all_paths[identifier],
                                  "requiredDependencyPathToCore": required_paths.get(identifier),
                                  "directCoreDependencies": [{"id": d["modId"], "kind": dependency_kind(d)} for d in dependencies if d["modId"] in seeds],
                                  "directFamilyDependencies": [{"id": d["modId"], "kind": dependency_kind(d)} for d in dependencies]})
        with zipfile.ZipFile(io.BytesIO(blob_at(args.mods, entry["jar"]))) as jar:
            for path in jar.namelist():
                if path.endswith(".class"):
                    refs = references(jar.read(path))
                    if refs:
                        entry["rayReferenceClasses"].append({"class": path[:-6].replace("/", "."), "references": refs})
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Static metadata dependency closure from Create/Aeronautics/Simulated/Sable. Required dependency chains can include world generators, rendering and compatibility bridges; optional closure is not a list of dedicated content addons. Ray references are review candidates, not confirmed bugs or play tests.",
              "classificationBasis": "NeoForge/Forge per-mod dependency descriptors only, not gameplay purpose. Runtime registry census is authoritative for loaded IDs/versions.",
              "coreModIds": sorted(seeds),
              "topLevelEnabledJars": len(list(args.mods.glob("*.jar"))),
              "disabledJars": [p.name for p in sorted(args.mods.glob("*.jar.disabled"))],
              "familyModIds": sorted(family), "modRelationships": sorted(relationships, key=lambda value: value["id"]), "jars": selected}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf8")
    print(json.dumps({"output": str(args.output), "familyModIds": sorted(family),
                      "selectedJars": len(selected), "rayReferenceClasses": sum(len(j["rayReferenceClasses"]) for j in selected)}))


if __name__ == "__main__":
    main()
