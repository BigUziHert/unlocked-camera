"""Describe installed Create ecosystems and contextual coverage without claiming all gameplay passed.

Metadata relationships, reviewed mod purpose, registry method ownership, and actual
fixture evidence are separate. Generic held-item/air-use/block-ray sweeps are not
treated as proof of a machine workflow, component variant, or full connection.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re


ROLES = {
    "create": "base_content", "aeronautics": "bundled_flight_content", "simulated": "bundled_physics_content",
    "sable": "physics_framework", "offroad": "bundled_vehicle_content",
    "alloyed": "standalone_content_with_optional_integration", "supplementaries": "standalone_content_with_optional_integration",
    "colorwheel": "rendering_integration", "emf_compat_create": "animation_integration",
    "emf_compat_supplementaries": "animation_integration", "sablexaeromaps": "map_integration",
    "aeronautics_curios_compat": "equipment_and_remote_control_integration", "createoplenty": "content_and_recipe_integration",
    "idas": "world_generation_integration", "integrated_stronghold": "world_generation_integration",
    "integrated_villages": "world_generation_integration", "sable_player_ragdoll": "ragdoll_framework",
    "ragdoll_reactions": "ragdoll_gameplay", "ragrevival": "ragdoll_gameplay",
}

# A finite list of concrete fixture families still needing context-specific
# validation, not a claim to exhaust arbitrary NBT, recipes, worlds or sequences.
BACKLOG = {
    "create": [
        "Other wrenchable blocks, directional states and assembled-contraption interactions beyond the three dedicated wrench fixtures.",
        "Schematic-and-quill area confirmation/export beyond the bounded client free-position voxel check; schematic placement/transforms, deploy/print and schematicannon workflow.",
        "Chain conveyor connection/removal and riding; belts, train tracks, schedules and mounted contraption controls.",
        "Worldshaper actual edits beyond client material selection, and symmetry tools; toolbox, filter, controller, logistics/package/address and stock-link interfaces.",
        "Sandpaper with an offhand ingredient; blueprint and recipe transfer; multi-step machine configuration.",
        "Offhand super-glue placement near shoulder boundaries is a documented server/body-ray limitation; mainhand fixture passing does not clear it.",
    ],
    "simulated": [
        "Steering wheel/throttle interaction on moving sublevels; existing dedicated control fixtures used ordinary terrain.",
        "Rope/spring coupling and removal, diagram capture/assembly, winch, docking, navigation, linked typewriter and sensors.",
        "Physics staff rotation/scroll/lock modes, offhand use and unconstrained force displacement; current fixture proves selection/server drag targets under controlled motion.",
    ],
    "aeronautics": ["Mounted potato cannon aiming/firing, burner/propeller setup and piloted flight behavior, goggles interaction."],
    "offroad": ["Tire fitting/removal and actual vehicle steering/driving across wheel sizes."],
    "aeroworks": ["Module channel/control configuration, composed sub-socket heads, restyling and moving-console behavior beyond the registered root-module attachment/removal fixture; private eyeRay checks alone are not module operations."],
    "powergrid": ["Wire first-endpoint cases are separate from two-endpoint connection, removal, cord attachment and live power transfer.",
                  "String lights, schematic/punch card, multimeter, battery, saw, electrozapper and generator-winding workflows."],
    "bits_n_bobs": ["Cogwheel-chain placement/removal/carriages, flywheel bearing, throttle controls, configurable lights/displays and chair mounting."],
    "createbigcannons": ["Cannon assembly, ramrod/worm/manual loading, welding, breeches, fuzes, mounted aim/fire and ammunition variants."],
    "createdieselgenerators": ["Sprayer filling and spraying, lighter, oil scanner, hammer/wire cutters, track-layer bag, entity filter and oil-processing machinery."],
    "createpropulsion": ["Assembler stick, auto glue, contraption mover/cloner/remover; thruster/vector/tilt controls and fuel-network operation."],
    "vsfluidlink": ["Fluid/item/electrical/chain connector pairing, magnetic attachment, transfer and disconnection across moving sublevels."],
    "copycats": ["Material insertion/removal, multi-material part selection, wrench orientation and behavior on contraptions."],
    "create_connected": ["Configuration and use of extension blocks, kinetic/redstone networks and connected contraption behavior."],
    "create_jetpack": ["Equipping, charging and actual jetpack flight/controls for both tiers."],
    "createdeco": ["Coin-stack placement and contextual use of decorative blocks, doors, trapdoors and fixtures."],
    "createendertransmission": ["Configuring and using inter-dimensional transfer/network endpoints."],
    "createoplenty": ["Colored sandpaper polishing with an ingredient; recipe integration is separate from camera targeting."],
    "dndesires": ["Gatling breaker/handheld saw use and sustained mining, burst packages and configured machinery."],
    "compactflap": ["Flap bearing configuration, assembly and redstone-directed movement."],
    "hotstuff": ["Analog torsion spring/link relay configuration and copycat sail/levitite use on sublevels."],
    "interiors": ["Mounting/dismounting all chair forms and table use, including on moving sublevels."],
    "kineticgrip": ["Grip-handle activation, release and mounted/moving-sublevel behavior."],
    "aeronautics_dyeable_components": ["Colored tire fitting and other color-dependent attachments; color enumeration is not an attachment test."],
    "aeronautics_curios_compat": ["Curios goggles equip/render and remote linked-typewriter use."],
    "alloyed": ["Steel tool contextual uses, fishing/shearing, armor equipment and material-processing workflows."],
    "supplementaries": ["Contextual block tools, flute/bubble blower, key/lock, bombs/cannon boats, lunch basket and remaining functional furniture; separate from Create addons."],
    "ragdoll_reactions": ["Actual ragdoll pickup/response or interaction paths; not an item-registry content addon."],
    "ragrevival": ["Downed-player feeding, cooperative revival and dragging."],
}


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def metadata(path):
    return {"path": str(path).replace("\\", "/"), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", type=Path, default=Path("build/compat-audit/create-addons.json"))
    parser.add_argument("--census", type=Path, default=Path("build/modpack-test/game/qa/census"))
    parser.add_argument("--results", type=Path, default=Path("build/modpack-test/game/qa/results.log"))
    parser.add_argument("--secondary-results", type=Path, default=Path("build/modpack-block-test/game/qa/results.log"),
                        help="Fresh disposable secondary instance, used for the AeroWorks actual-operation suite")
    parser.add_argument("--offset-file", type=Path, default=Path("build/exhaustive-qa/results-start-offset.txt"))
    parser.add_argument("--output", type=Path, default=Path("build/exhaustive-qa/create-addon-coverage.json"))
    args = parser.parse_args()
    audit, summary = load(args.audit), load(args.census / "summary.json")
    mods = {row["id"]: row for row in load(args.census / "mods.json")}
    family = set(audit["familyModIds"])
    descriptions = {mod["id"]: mod.get("description", "") for jar in audit["jars"] for mod in jar["mods"]}
    item_groups, block_groups = defaultdict(list), defaultdict(list)
    for kind, groups in (("items", item_groups), ("blocks", block_groups)):
        for row in load(args.census / f"{kind}.json"):
            namespace = row["id"].split(":")[0]
            if namespace not in family:
                continue
            owners = sorted({method.split("[")[0] for method in row.get("actionMethods", [])
                             if re.search(r"#(?:useOn|use|onItemUseFirst|interactLivingEntity|useWithoutItem|useItemOn)\[", method)
                             and not method.startswith(("net.minecraft.world.item.Item#", "net.minecraft.world.level.block.Block#", "net.neoforged.neoforge.common.extensions.IItemExtension#"))})
            if owners:
                groups[(namespace, row["class"], tuple(owners))].append(row["id"])
    relationships = []
    for row in audit["modRelationships"]:
        identifier = row["id"]
        relationships.append({**row, "loadedVersion": mods.get(identifier, {}).get("version"),
                              "reviewedPurpose": ROLES.get(identifier, "dedicated_content_addon"),
                              "purposeEvidence": descriptions.get(identifier, "") or "Reviewed registered content and installed classes; descriptor has no description.",
                              "registryCounts": summary.get("namespaceCounts", {}).get(identifier, {}),
                              "contextualGameplayBacklog": BACKLOG.get(identifier, []),
                              "backlogMeaning": "No dedicated success-effect fixture recorded here for these contexts; generic registry/air-use/shape coverage is separate."})
    start_offset = int(args.offset_file.read_text(encoding="utf-8-sig").strip())
    result_bytes = args.results.read_bytes()
    if start_offset > len(result_bytes):
        raise ValueError("Task offset exceeds results log; refusing a rotated or different file")
    log = result_bytes[start_offset:].decode("utf-8-sig", "replace")
    evidence = []
    secondary_metadata = []
    secondary_log = ""
    if args.secondary_results.exists():
        secondary_bytes = args.secondary_results.read_bytes()
        secondary_log = secondary_bytes.decode("utf-8-sig", "replace")
        secondary_metadata.append({**metadata(args.secondary_results), "startByteOffset": 0, "endByteOffset": len(secondary_bytes)})
    for prefix in ("CREATE_INTERACTIONS", "CREATE_FREELOOK", "CREATE_FREELOOK_RECHECK", "PHYSICS_STAFF", "PHYSICS_STAFF_FREELOOK",
                   "POWERGRID", "POWERGRID_FREELOOK", "CREATE_SELECTION", "ADDON_RAY", "AEROWORKS_MODULE", "AEROWORKS_MODULE_FREELOOK_RECHECK"):
        # Latest complete or currently-running invocation only. Do not blend the
        # obsolete physics fixture or stale-endpoint Powergrid run into counts.
        use_secondary = prefix.startswith("AEROWORKS_MODULE") and prefix + " START" in secondary_log
        suite_log = secondary_log if use_secondary else log
        suite_source = args.secondary_results if use_secondary else args.results
        starts = list(re.finditer(rf"^{prefix} START\b", suite_log, re.MULTILINE))
        segment = suite_log[starts[-1].start():] if starts else ""
        lines = [line for line in segment.splitlines() if line.startswith(prefix + " ")]
        if prefix == "ADDON_RAY":
            lines = [line for line in log.splitlines() if line.startswith(prefix + " ")]
        restored = any(line.startswith(prefix + " RESTORED") for line in lines)
        aborted = any(line.startswith(prefix + " ABORT") for line in lines)
        completed = any(line.startswith(prefix + " DONE") for line in lines)
        attempts = []
        for index, match in enumerate(starts):
            end = starts[index + 1].start() if index + 1 < len(starts) else len(suite_log)
            attempt_lines = [line for line in suite_log[match.start():end].splitlines() if line.startswith(prefix + " ")]
            attempts.append({"ordinal": index + 1,
                             "passRows": sum(line.startswith(prefix + " PASS ") for line in attempt_lines),
                             "failRows": sum(line.startswith(prefix + " FAIL ") for line in attempt_lines),
                             "aborted": any(line.startswith(prefix + " ABORT") for line in attempt_lines),
                             "completed": any(line.startswith(prefix + " DONE") for line in attempt_lines),
                             "restored": any(line.startswith(prefix + " RESTORED") for line in attempt_lines)})
        evidence.append({"suite": prefix, "source": str(suite_source).replace("\\", "/"), "lines": lines,
                         "restored": restored, "attempts": attempts,
                         "status": "aborted_restored" if aborted and restored else "aborted" if aborted
                         else "completed_restored" if completed and restored else "completed" if completed
                         else "incomplete_or_not_run"})
        if prefix.startswith("AEROWORKS_MODULE"):
            outcomes = Counter((words[5], words[1]) for line in lines
                               if len(words := line.split()) >= 6 and words[1] in ("PASS", "FAIL", "INCONCLUSIVE"))
            evidence[-1]["modeOutcomes"] = [{"mode": mode, "status": status, "rows": count}
                                           for (mode, status), count in sorted(outcomes.items())]
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(),
              "scope": "Dependency and purpose classification plus finite contextual gameplay backlog. Does not certify every addon, interaction, state/component variant, or workflow.",
              "classificationNotes": ["A required dependency does not imply a dedicated content addon: world generation, rendering and compatibility bridges are separate purposes.",
                                      "Optional integration closure includes Alloyed, Supplementaries, Colorwheel and an EMF Supplementaries bridge; those are not all Create content addons.",
                                      "Registry method groups miss event-driven interactions and component-conditioned behavior; they are a review queue, not a complete interaction count.",
                                      "First-endpoint wiring, server drag targets and private ray helpers each have narrower scope than a complete gameplay workflow.",
                                      "Powergrid rows preserve clientAccepted separately from serverSelected: a client rejection with a correct server endpoint is a feedback mismatch, not proof that endpoint selection failed.",
                                      "AeroWorks module rows cover root attachment/removal with default components on stationary consoles; an independent seeded removal fixture does not count as a successful attachment.",
                                      "The original AeroWorks full sweep and its failed-only settled freelook review remain separate evidence. The review repeats each eligible first-person/freelook pair three times after three actual player ticks; it does not replace original offset or freelook outcomes. Original rows did not capture head yaw, so the exact original timing mechanism is inferred rather than directly observed."],
              "sources": [metadata(args.audit), metadata(args.census / "summary.json"), metadata(args.census / "mods.json"),
                          {"path": str(args.results).replace("\\", "/"), "startByteOffset": start_offset,
                           "endByteOffset": len(result_bytes), "sha256": hashlib.sha256(result_bytes).hexdigest()}] + secondary_metadata,
              "runtimePackagingIds": [mods[identifier] for identifier in ("aeronautics_bundled",) if identifier in mods],
              "metadataRelationshipCounts": dict(Counter(row["classification"] for row in relationships)),
              "reviewedPurposeCounts": dict(Counter(row["reviewedPurpose"] for row in relationships)),
              "mods": relationships, "dedicatedFixtureEvidence": evidence,
              "itemActionImplementationGroups": [{"namespace": k[0], "class": k[1], "actionOwners": k[2], "ids": v} for k, v in sorted(item_groups.items())],
              "blockActionImplementationGroups": [{"namespace": k[0], "class": k[1], "actionOwners": k[2], "ids": v} for k, v in sorted(block_groups.items())]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    md = ["# Create ecosystem coverage inventory", "", report["scope"], "",
          "Versions and registry counts come from the loaded runtime census. Dependency classifications come from installed descriptors; purpose is a separate review.", "",
          "| Mod | Loaded version | Purpose | Metadata relationship | Items / blocks / entities |", "|---|---|---|---|---|"]
    for row in relationships:
        counts = row["registryCounts"]
        md.append(f"| {row['id']} | {row['loadedVersion']} | {row['reviewedPurpose']} | {row['classification']} | {counts.get('items',0)} / {counts.get('blocks',0)} / {counts.get('entityTypes',0)} |")
    md += ["", "## AeroWorks original operations and settled review", "",
           "These are separate runs. The settled review covers only original freelook failures with successful first-person controls, repeating each pair three times after three actual player ticks. It does not replace the original full-sweep observations or the offset failures.", "",
           "| Suite | Mode | Pass | Fail | Inconclusive | Completion |", "|---|---|---:|---:|---:|---|"]
    for suite in evidence:
        if not suite["suite"].startswith("AEROWORKS_MODULE"):
            continue
        outcomes = {(row["mode"], row["status"]): row["rows"] for row in suite["modeOutcomes"]}
        for mode in ("first_person", "offset", "freelook"):
            if any(key[0] == mode for key in outcomes):
                md.append(f"| {suite['suite']} | {mode} | {outcomes.get((mode, 'PASS'), 0)} | {outcomes.get((mode, 'FAIL'), 0)} | {outcomes.get((mode, 'INCONCLUSIVE'), 0)} | {suite['status']} |")
    md += ["", "## Contextual gameplay still requiring dedicated fixtures", "",
           "This backlog concerns specific success effects. Generic item handling, air use, target parity and block-state ray sweeps do not certify these workflows. A row is not a demonstrated compatibility failure.", ""]
    for row in relationships:
        if row["contextualGameplayBacklog"]:
            md += [f"### {row['id']}", ""] + [f"- {line}" for line in row["contextualGameplayBacklog"]] + [""]
    md += ["## Evidence boundaries", ""] + [f"- {line}" for line in report["classificationNotes"]]
    md += ["", f"Machine-readable JSON includes {len(item_groups)} item and {len(block_groups)} block action-implementation groups, exact registered IDs and latest dedicated fixture log lines.", ""]
    args.output.with_suffix(".md").write_text("\n".join(md), encoding="utf-8")
    print(json.dumps({"output": str(args.output), "mods": len(relationships), "metadataRelationshipCounts": report["metadataRelationshipCounts"],
                      "itemActionImplementationGroups": len(item_groups), "blockActionImplementationGroups": len(block_groups)}))


if __name__ == "__main__":
    main()
