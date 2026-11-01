"""Summarize executed camera QA without converting a registry census into gameplay passes.

Only the results.log bytes at/after --offset-file belong to this task. Artifacts
also need a current-task start/completion marker and must not predate that file.
The script can run during a sweep; incomplete suites stay explicitly incomplete.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from datetime import datetime, timezone
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re


def namespace(identifier):
    match = re.search(r"(?:Block\{)?([a-z0-9_.-]+):", identifier or "")
    return match.group(1) if match else "unknown"


def read_json(path, fallback=None):
    if not path.exists():
        return fallback
    return json.loads(path.read_text(encoding="utf-8-sig"))


def table(path, delimiter=","):
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as stream:
        # Live buffered writers can expose half a final row. Only complete
        # lines are evidence; the next report will pick up the finished row.
        def complete_lines():
            for line in stream:
                if not line.endswith("\n"):
                    break
                yield line
        records = list(csv.DictReader(complete_lines(), delimiter=delimiter))
        if any(None in row or any(value is None for value in row.values()) for row in records):
            raise ValueError("Malformed complete artifact row: " + str(path))
        return records


def counter(rows, field):
    return dict(sorted(Counter(row.get(field, "MISSING") for row in rows).items()))


def block_difference_category(row):
    """Describe the recorded evidence without turning fixture differences into bugs."""
    if any("DEFAULT_STATE_UNSTABLE:" in row.get(key, "") for key in ("baseline_pick", "shoulder_pick")):
        return "DEFAULT_STATE_SETUP_NOT_SENT"
    if row.get("status") == "ERROR":
        return "EXECUTION_ERROR"
    if row.get("status") == "CONTEXT_DIFFERENCE":
        return "DIFFERENT_HIT_FACE_CONTEXT"
    if row.get("baseline_observation") == row.get("shoulder_observation") and row.get("baseline_use") != row.get("shoulder_use"):
        if row.get("baseline_pick") == row.get("shoulder_pick") == "TARGET":
            return "MATCHED_SERVER_OBSERVATION_LOCAL_RETURN_DIFF"
    if any(row.get(key, "").startswith("ENTITY:") for key in ("baseline_pick", "shoulder_pick")):
        return "ENTITY_OCCLUSION_REQUIRES_FIXTURE"
    if row.get("status") == "TARGETING_FAIL":
        return "TARGETING_DIFFERENCE_REVIEW"
    if any(row.get(key, "").startswith("Block{minecraft:air}|") for key in ("baseline_observation", "shoulder_observation")):
        return "UNSTABLE_BLOCK_LIFECYCLE_REVIEW"
    if row.get("block", "").startswith("create_connected:copycat_") and "Block{copycats:" in row.get("baseline_observation", "") + row.get("shoulder_observation", ""):
        return "INSTALLED_BLOCK_MIGRATION_REVIEW"
    return "SERVER_OBSERVATION_DIFFERENCE_REVIEW"


def diode_region_evidence(row):
    """Apply the installed Create slot's audited predicate to recorded hit points."""
    if row.get("block") not in {"create:pulse_extender", "create:pulse_repeater", "create:pulse_timer"}:
        return None
    if row.get("status") != "SERVER_DIFFERENCE_REVIEW" or row.get("first_before") != row.get("shoulder_before"):
        return None
    if row.get("first_after") != row.get("first_before") or row.get("shoulder_after") != row.get("shoulder_before", "").replace("inverted=false", "inverted=true"):
        return None
    audits = [Path("build/compat-audit/diode-value-hit-region.txt"), Path("build/compat-audit/diode-value-transform.txt")]
    if not all(path.exists() for path in audits):
        return None
    distances = []
    for key in ("first_hit", "shoulder_hit"):
        match = re.fullmatch(r"up:\(([-\d.eE+]+), ([-\d.eE+]+), ([-\d.eE+]+)\)", row.get(key, ""))
        if not match:
            return None
        point = tuple(float(x) - origin for x, origin in zip(match.groups(), (1742, 150, 193)))
        distances.append(math.dist(point, (.5, 2.5999999046325684 / 16, .5)))
    if not distances[0] < .25 <= distances[1]:
        return None
    return {"interpretation": "DIFFERENT_VALUE_SETTING_HIT_REGIONS", "firstDistance": distances[0],
            "shoulderDistance": distances[1], "slotRadius": .25, "firstInsideSlot": True, "shoulderInsideSlot": False,
            "audits": [file_meta(path) for path in audits],
            "reason": "Installed ValueSettingsInputHandler cancels block use inside the value-settings slot; the outside hit reaches BrassDiodeBlock.toggle. Both views hit UP but select different functional regions. This explains the raw difference, not operation of the timing UI."}


def completed(log, start, done):
    return log.rfind(done) > log.rfind(start) >= 0


def load_setting_history(log, prefix, source):
    lines = log.splitlines()
    start = prefix + (" APPLIED" if prefix == "QA_LOAD_SETTINGS" else " START")
    starts = [i for i, line in enumerate(lines) if line.startswith(start)]
    attempts = []
    for number, index in enumerate(starts):
        end = starts[number + 1] if number + 1 < len(starts) else len(lines)
        # A request to quit is not proof of exit. The options-restored marker is
        # emitted by the external runner only after exit and byte verification.
        process_end = next((i for i in range(index + 1, end) if lines[i].startswith((
            "QA_PROCESS ABORT", "QA_PROCESS CLEAN_EXIT_CONFIRMED", "QA_COPY_OPTIONS_RESTORED"))), None)
        segment = lines[index:process_end + 1 if process_end is not None else end]
        restored = any(line.startswith(prefix + " RESTORED") for line in segment)
        ended = process_end is not None and not restored
        clean_end = ended and not lines[process_end].startswith("QA_PROCESS ABORT")
        verified_stop = ended and "verified_shutdown_unload_loop_force_stopped" in lines[process_end] and "fixtures_previously_restored=true" in lines[process_end]
        attempts.append({"startLine": index + 1, "applied": any(line.startswith(prefix + " APPLIED") for line in segment),
                         "restored": restored, "processEndedBeforeRestore": ended,
                         "overrideEndedWithCleanProcessExit": clean_end,
                         "overrideEndedWithVerifiedShutdownStop": verified_stop,
                         "snapshotLostWithInterruptedProcess": ended and not clean_end and not verified_stop,
                         "cleanExitRequested": any(line.startswith("QA_PROCESS CLEAN_EXIT_REQUEST") for line in segment),
                         "restorationPending": not restored and not ended,
                         "events": [line for line in segment if line.startswith((prefix + " ", "QA_PROCESS ", "QA_COPY_OPTIONS_RESTORED"))]})
    return {"source": source, "events": [line for line in lines if line.startswith(prefix + " ")], "attempts": attempts,
            "restored": bool(attempts) and all(r["restored"] for r in attempts),
            "applied": bool(attempts) and attempts[-1]["applied"],
            "restorationPending": any(r["restorationPending"] for r in attempts),
            "snapshotsLostWithProcess": sum(r["snapshotLostWithInterruptedProcess"] for r in attempts),
            "overridesEndedWithCleanProcessExit": sum(r["overrideEndedWithCleanProcessExit"] for r in attempts),
            "overridesEndedWithVerifiedShutdownStop": sum(r["overrideEndedWithVerifiedShutdownStop"] for r in attempts)}


def suite_segments(lines, prefix):
    starts = [i for i, line in enumerate(lines) if line.startswith(prefix + " START")]
    return [(lines[start], "\n".join(lines[start:end])) for start, end in zip(starts, starts[1:] + [len(lines)])]


def file_meta(path):
    return {"path": str(path).replace("\\", "/"), "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def read_log(path, offset=0):
    data = path.read_bytes() if path.exists() else b""
    if offset > len(data):
        raise ValueError(f"Saved offset {offset} exceeds {path} length {len(data)}; refusing a rotated log")
    return data[offset:].decode("utf-8-sig", "replace"), {
        "path": str(path).replace("\\", "/"), "startByteOffset": offset, "endByteOffset": len(data)}


def current_console(path, started, source, process="current"):
    """Keep dated current-task exception/lag records from one original process log."""
    if not path.exists():
        return {"source": source, "available": False, "path": str(path), "records": []}
    text = gzip.open(path, "rt", encoding="utf-8-sig", errors="replace").read() if path.suffix == ".gz" else path.read_text(encoding="utf-8-sig", errors="replace")
    header = re.compile(r"^\[(\d{2}[A-Za-z]{3}\d{4} \d{2}:\d{2}:\d{2}\.\d{3})\]", re.M)
    starts = list(header.finditer(text))
    records, launches, rollbacks = [], [], []
    previous_local, clock_segment, first_stamp, last_stamp, task_records = None, 0, None, None, 0
    for index, match in enumerate(starts):
        local = datetime.strptime(match.group(1), "%d%b%Y %H:%M:%S.%f")
        if previous_local is not None and 1800 < (previous_local - local).total_seconds() < 7200:
            clock_segment += 1
            rollbacks.append({"before": previous_local.isoformat(), "after": local.isoformat(), "segment": clock_segment})
        previous_local = local
        # The task crossed the local DST fallback. Preserve the second 01:xx
        # hour as fold=1 rather than assigning both occurrences the first offset.
        stamp = local.replace(fold=min(clock_segment, 1)).astimezone()
        first_stamp = first_stamp or stamp
        last_stamp = stamp
        end = starts[index + 1].start() if index + 1 < len(starts) else len(text)
        record = text[match.start():end].rstrip()
        if "ModLauncher running:" in record:
            launches.append(stamp.isoformat())
        if stamp.timestamp() < started - 2:
            continue
        task_records += 1
        if not re.search(r"\b(?:ERROR|FATAL)\b|(?:Exception|Error):|suppressing error|Can't keep up!|Rejecting UseItemOnPacket", record):
            continue
        record_lines = record.splitlines()
        records.append({"time": stamp.isoformat(), "localTime": local.isoformat(), "clockRollbackSegment": clock_segment,
                        "source": source, "process": process, "artifact": str(path).replace("\\", "/"), "text": "\n".join(record_lines[:120]),
                        "fullRecordSha256": hashlib.sha256(record.encode("utf-8")).hexdigest(),
                        "truncatedLines": max(0, len(record_lines) - 120)})
    return {"source": source, "process": process, "available": True, **file_meta(path), "records": records,
            "firstLogTime": first_stamp.isoformat() if first_stamp else None, "lastLogTime": last_stamp.isoformat() if last_stamp else None,
            "launcherStartTimes": launches, "clockRollbacks": rollbacks, "currentTaskDatedRecords": task_records,
            "scope": "Original dated Minecraft records at/after task start, including daily rotations. Observed DST rollback distinguishes repeated local times. Exceptions and lag are evidence for review, not automatic camera defects."}


def console_history(folder, started, source, preserved=()):
    """Include task-overlapping normal logs; exclude debug duplicates and old runs."""
    paths = [(path, role) for path, role in preserved if path.exists()]
    paths += [(path, "rotated") for path in folder.iterdir()
              if path.is_file() and re.fullmatch(r"\d{4}-\d{2}-\d{2}-\d+\.log\.gz", path.name)] if folder.exists() else []
    if (folder / "latest.log").exists(): paths.append((folder / "latest.log", "latest"))
    candidates = [current_console(path, started, source, role) for path, role in paths]
    included = [entry for entry in candidates if entry.get("currentTaskDatedRecords", 0)]
    excluded = [{key: value for key, value in entry.items() if key != "records"} | {"reason": "no dated records at/after this task's start"}
                for entry in candidates if not entry.get("currentTaskDatedRecords", 0)]
    launches = sorted({stamp for entry in included for stamp in entry["launcherStartTimes"]}, key=lambda value: datetime.fromisoformat(value).timestamp())
    def process_for(stamp):
        before = [value for value in launches if datetime.fromisoformat(value).timestamp() <= datetime.fromisoformat(stamp).timestamp()]
        return source + "@" + before[-1] if before else source + "@unobserved-launch"
    for entry in included:
        entry["processIdentity"] = process_for(entry["firstLogTime"])
        for record in entry["records"]:
            record["processIdentity"] = process_for(record["time"])
    # Preserved copies are first so duplicate records keep their original
    # pre-resume attribution, while an additional archive remains auditable.
    return included, excluded


def verify_secondary(primary, secondary, secondary_log, started):
    """Refuse merging unlike packs; compare IDs as well as registry counts."""
    summary_path = secondary / "census/summary.json"
    if "CENSUS COMPLETE" not in secondary_log or not summary_path.exists() or summary_path.stat().st_mtime < started - 2:
        raise ValueError("Secondary block instance requires a completed current-task registry census before merging")
    first = read_json(primary / "census/summary.json", {})
    second = read_json(summary_path, {})
    keys = ("items", "blocks", "blockStates", "entityTypes", "mods", "namespaceCounts")
    different = [key for key in keys if first.get(key) != second.get(key)]
    mods_a = {row["id"]: row["version"] for row in read_json(primary / "census/mods.json", [])}
    mods_b = {row["id"]: row["version"] for row in read_json(secondary / "census/mods.json", [])}
    if not mods_a or mods_a != mods_b:
        different.append("mod IDs/versions")
    for filename in ("items", "blocks", "entities"):
        a = {(r["id"], r.get("stateCount")) for r in read_json(primary / f"census/{filename}.json", [])}
        b = {(r["id"], r.get("stateCount")) for r in read_json(secondary / f"census/{filename}.json", [])}
        if not a or a != b:
            different.append(f"{filename} registry IDs/state counts")
    if different:
        raise ValueError("Refusing secondary merge: mismatched " + ", ".join(different))
    return {"matched": True, "checks": list(keys) + ["mod IDs/versions", "registry IDs/state counts"],
            "primaryCensus": file_meta(primary / "census/summary.json"), "secondaryCensus": file_meta(summary_path),
            "scope": "Identical loaded registry IDs/counts and mod versions; separate disposable worlds, concurrent compatibility checks, not a performance benchmark"}


def artifact(path, log, marker, started, delimiter=","):
    reason = None
    if not path.exists():
        reason = "missing"
    elif marker not in log:
        reason = "no current-task execution marker"
    elif path.stat().st_mtime < started - 2:
        reason = "artifact predates task"
    if reason:
        return [], {"available": False, "reason": reason, "path": str(path).replace("\\", "/")}
    return table(path, delimiter), {"available": True, **file_meta(path)}


def latest_action_results(lines):
    """Keep attempts, and use the last observed result for each explicit oracle."""
    attempts = []
    latest = {}
    powergrid_fixtures = {}
    projectile_samples = {}
    melee_requests = {}
    melee_resume_candidates = {}
    aero_preflights = {}
    integration_suite, integration_fixture = None, None
    for number, line in enumerate(lines, 1):
        integration_start = re.match(r"^INTEGRATION START(?: suite=(\S+))?", line)
        if integration_start:
            integration_suite = integration_start.group(1) or "standard"
            integration_fixture = None
            melee_requests.clear()
        fixture = re.match(r"^INTEGRATION FIXTURE player=\(([^)]+)\) camera=\(([^)]+)\) target=BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}", line)
        if fixture:
            player = tuple(float(v.strip()) for v in fixture.group(1).split(","))
            camera = tuple(float(v.strip()) for v in fixture.group(2).split(","))
            target = tuple(float(v) + .5 for v in fixture.groups()[2:])
            distance = math.dist(player, target)
            integration_fixture = {"line": number, "player": player, "camera": camera, "target": target,
                                   "targetDistanceFromPlayer": distance, "cameraDistanceFromPlayer": math.dist(player, camera),
                                   "grossDistanceMismatch": distance > 16,
                                   "scope": "The standard QA platform expects a nearby target; a target more than sixteen blocks from the player is a confirmed fixture mismatch, not a camera comparison oracle."}
        for prefix, kind in (("PHYSICS_STAFF", "physics_staff"), ("CREATE_SELECTION", "create_selection"),
                             ("CARDBOARD_KNOCKBACK", "cardboard_knockback"), ("PHYSICS_STAFF_FREELOOK", "physics_staff_freelook"),
                             ("CARDBOARD_KNOCKBACK_RECHECK", "cardboard_knockback_recheck"),
                             ("CREATE_INTERACTIONS", "create"), ("POWERGRID", "powergrid"),
                             ("MELEE_OFFSET_SUITE", "melee_offset"), ("PROJECTILE_FREELOOK_SUITE", "projectile_freelook"),
                             ("MELEE_PATH_SUITE", "melee_path"),
                             ("ABILITY_FREELOOK_SUITE", "ability_freelook"), ("ABILITY_FREELOOK_SUITE", "ping_freelook"),
                             ("ABILITY_PAIR_SUITE", "ability_pair"),
                             ("ENTITY_SPECIALIZED", "entity_specialized"),
                             ("AEROWORKS_MODULE", "aeroworks_module"),
                             ("AEROWORKS_MODULE_FREELOOK_RECHECK", "aeroworks_module_freelook_recheck"),
                             ("INTEGRATION_RECHECK_SUITE", "integration_recheck"),
                             ("CREATE_FREELOOK", "create_freelook"), ("CREATE_FREELOOK_RECHECK", "create_freelook_recheck"),
                             ("POWERGRID_FREELOOK", "powergrid_freelook")):
            if line.startswith(prefix + " START"):
                # A retry may rename cases. Do not inherit labels from an aborted run.
                if kind == "melee_offset":
                    melee_resume_candidates = {key: value for key, value in latest.items() if value["kind"] == kind and value["status"] == "PASS"}
                latest = {key: value for key, value in latest.items() if value["kind"] != kind}
                if kind.startswith("projectile"):
                    projectile_samples.clear()
                if kind.startswith("melee"):
                    melee_requests.clear()
                if kind.startswith("aeroworks_module"):
                    aero_preflights = {key: value for key, value in aero_preflights.items() if key[0] != prefix}
        if line.startswith("MELEE_OFFSET INVENTORY ") and "resume=true" in line:
            # This explicit controller selects only missing/nonpassing IDs.
            # Keep earlier successful cases; raw interrupted attempts remain.
            latest.update(melee_resume_candidates)
        melee_request = re.match(r"^(MELEE|MELEE_OFFSET|MELEE_PATH) REQUEST item=(\S+) (.*)$", line)
        if melee_request:
            prefix, identifier, detail = melee_request.groups()
            mode_match = re.search(r"\bmode=(\S+)", detail)
            request_mode = mode_match.group(1) if prefix == "MELEE_PATH" and mode_match else ""
            melee_requests[prefix, identifier, request_mode] = {"detail": detail, "line": number}
        projectile_use = re.match(r"^(PROJECTILE|PROJECTILE_FREELOOK) USE item=(\S+) hand=(\S+)", line)
        if projectile_use:
            projectile_samples[projectile_use.groups()] = []
        projectile_sample = re.match(r"^(PROJECTILE|PROJECTILE_FREELOOK) SAMPLE item=(\S+) hand=(\S+) entity=(\S+) (.*)$", line)
        if projectile_sample:
            prefix, identifier, hand, entity, detail = projectile_sample.groups()
            projectile_samples.setdefault((prefix, identifier, hand), []).append({"entity": entity, "detail": detail, "line": number})
        integration_retry = re.match(r"^INTEGRATION_RECHECK_DETAIL (.+?) (PASS|FAIL) (.*)$", line)
        if integration_retry:
            label, status, detail = integration_retry.groups()
            identifier = re.search(r"[\w.\-]+:[\w./\-]+", label)
            identifier = identifier.group(0) if identifier else "simplyswords:magispear" if label.startswith("SIMPLY_SWORDS_ABILITY") else ""
            scope = ("server projectile launch" if label.startswith("PROJECTILE ") else
                     "server knockback without health damage" if label.startswith("MELEE ") and any(marker in detail for marker in ("oracle=knockback_without_damage", "oracle=canceled_server_attack_with_knockback")) else
                     "server target health" if label.startswith("MELEE ") else
                     "server ability target" if label.startswith("SIMPLY_SWORDS_ABILITY") else
                     "server pickup/place state" if label.startswith("CARRYON ") else
                     "server-echoed entity ping" if label == "PING_ACTION" else "client targeting/handler")
            entry = {"kind": "integration_recheck", "case": label, "id": identifier, "status": status,
                     "detail": detail, "line": number, "mode": "mixed_standard_actions", "scope": scope}
            attempts.append(entry); latest[("integration_recheck", label)] = entry
            continue
        aero_preflight = re.match(r"^(AEROWORKS_MODULE(?:_FREELOOK_RECHECK)?) (BEFORE|POSE_READY) (\S+) (\S+) (attach|remove) (\S+) (.*)$", line)
        if aero_preflight:
            prefix, stage, identifier, console, operation, mode, detail = aero_preflight.groups()
            repeat_match = re.search(r"\brepeat=(\d+)", detail)
            repeat = int(repeat_match.group(1)) if repeat_match else None
            aero_preflights.setdefault((prefix, identifier, console, operation, mode, repeat), []).append({"stage": stage, "detail": detail, "line": number})
        aero_module = re.match(r"^(AEROWORKS_MODULE(?:_FREELOOK_RECHECK)?) (PASS|FAIL|INCONCLUSIVE) (\S+) (\S+) (attach|remove) (\S+) (.*)$", line)
        if aero_module:
            prefix, status, identifier, console, operation, mode, detail = aero_module.groups()
            kind = "aeroworks_module_freelook_recheck" if prefix.endswith("_RECHECK") else "aeroworks_module"
            repeat_match = re.search(r"\brepeat=(\d+)", detail)
            repeat = int(repeat_match.group(1)) if repeat_match else None
            result = re.search(r"\bclientResult=(\S+)", detail)
            preflights = aero_preflights.get((prefix, identifier, console, operation, mode, repeat), [])
            settled = [int(match.group(1)) for row in preflights if (match := re.search(r"\bsettledPlayerTicks=(-?\d+)", row["detail"]))]
            entry = {"kind": kind, "id": identifier, "console": console, "case": operation, "mode": mode, "repeat": repeat,
                     "heldItemId": "create:wrench" if operation == "remove" else identifier,
                     "status": status, "detail": detail, "line": number,
                     "preflightEvidence": preflights, "settledPlayerTicks": settled[-1] if settled else None,
                     "clientResult": result.group(1) if result else None,
                     "recordedUseRequest": bool(result and result.group(1) in {"PASS", "FAIL", "SUCCESS", "SUCCESS_NO_ITEM_USED", "CONSUME", "CONSUME_PARTIAL"}),
                     "scope": "server exact attached module/socket" if operation == "attach" else "server empty console and exact removed item return; independent removal seed is fixture-only"}
            for flag in ("positiveControl", "serverEffect", "cameraReferenceValid"):
                flag_match = re.search(r"\b" + flag + r"=(true|false)", detail)
                if flag_match: entry[flag] = flag_match.group(1) == "true"
            attempts.append(entry); latest[(kind, identifier, console, operation, mode, repeat)] = entry
            continue
        ability_free = re.match(r"^ABILITY_FREELOOK_DETAIL (SIMPLY_SWORDS_ABILITY server target|PING_ACTION) (PASS|FAIL) (.*)$", line)
        if ability_free:
            case, status, detail = ability_free.groups()
            kind = "ability_freelook" if case.startswith("SIMPLY") else "ping_freelook"
            entry = {"kind": kind, "id": "simplyswords:magispear" if kind == "ability_freelook" else "",
                     "case": case, "mode": "actual_first_person_freelook", "status": status, "detail": detail,
                     "line": number, "scope": "server ability target" if kind == "ability_freelook" else "server-echoed entity ping"}
            attempts.append(entry); latest[(kind, case)] = entry
            continue
        ability_pair = re.match(r"^ABILITY_PAIR_DETAIL (SIMPLY_SWORDS_ABILITY server target) (PASS|FAIL) (.*)$", line)
        if ability_pair:
            case, status, detail = ability_pair.groups()
            mode = re.search(r"\bmode=(first_person|freelook)\b", detail)
            entry = {"kind": "ability_pair", "id": "simplyswords:magispear", "case": case,
                     "mode": mode.group(1) if mode else "unknown", "status": status, "detail": detail,
                     "line": number, "scope": "separate real ability-key request and exact server target UUID"}
            for flag in ("exactTarget", "active"):
                value = re.search(r"\b" + flag + r"=(true|false)", detail)
                if value: entry[flag] = value.group(1) == "true"
            attempts.append(entry); latest[("ability_pair", case, entry["mode"])] = entry
            continue
        entity_special = re.match(r"^ENTITY_SPECIALIZED (PASS|FAIL|INCONCLUSIVE) (\S+) (\S+) (.*)$", line)
        if entity_special:
            status, identifier, mode, detail = entity_special.groups()
            scope = re.search(r"\bscope=(\S+)", detail)
            entry = {"kind": "entity_specialized", "id": identifier, "subject": "entity", "mode": mode,
                     "status": status, "detail": detail, "line": number,
                     "scope": scope.group(1) if scope else "limited specialized entity fixture"}
            attempts.append(entry); latest[("entity_specialized", identifier, mode)] = entry
            continue
        selection = re.match(r"^CREATE_SELECTION (PASS|FAIL) (\S+) (\S+) (.*)$", line)
        if selection:
            status, case, mode, detail = selection.groups()
            identifier = {"quill_free_selection": "create:schematic_and_quill",
                          "worldshaper_material_selection": "create:handheld_worldshaper"}.get(case, "")
            entry = {"kind": "create_selection", "id": identifier, "case": case, "mode": mode,
                     "status": status, "detail": detail, "line": number, "scope": "client targeting/handler"}
            attempts.append(entry); latest[("create_selection", case, mode)] = entry
            continue
        cardboard = re.match(r"^(CARDBOARD_KNOCKBACK(?:_RECHECK)?) mode=(\S+) (PASS_KNOCKBACK|PASS_KNOCKBACK_DIRECTION_REVIEW|FAIL_KNOCKBACK) (.*)$", line)
        if cardboard:
            prefix, mode, status, detail = cardboard.groups()
            kind = "cardboard_knockback_recheck" if prefix.endswith("_RECHECK") else "cardboard_knockback"
            entry = {"kind": kind, "id": "create:cardboard_sword", "mode": mode, "status": status,
                     "detail": detail, "line": number, "scope": "server attack event, canceled health damage, actual displacement and impact velocity"}
            attempts.append(entry); latest[(kind, mode)] = entry
            continue
        physics = re.match(r"^(PHYSICS_STAFF(?:_FREELOOK)?) (PASS|FAIL) (.*)$", line)
        if physics:
            prefix, status, rest = physics.groups()
            parts = re.split(r" (?=(?:hit|bodyHit|ship|player|seatDisplacement)=)", rest, maxsplit=1)
            label = parts[0]
            kind = "physics_staff_freelook" if prefix.endswith("_FREELOOK") else "physics_staff"
            entry = {"kind": kind, "id": "simulated:creative_physics_staff", "case": label,
                     "status": status, "detail": parts[1] if len(parts) > 1 else "", "line": number,
                     "scope": "server drag session; force displacement untested" if "server" in label else "client targeting/handler"}
            attempts.append(entry); latest[(kind, label)] = entry
            continue
        powergrid_fixture = re.match(r"^(POWERGRID(?:_FREELOOK)?) FIXTURE (\S+) (\S+) state=Block\{([^}]+)\}", line)
        if powergrid_fixture:
            prefix, identifier, mode, fixture = powergrid_fixture.groups()
            kind = "powergrid_freelook" if prefix.endswith("_FREELOOK") else "powergrid"
            powergrid_fixtures[(kind, identifier, mode)] = {"block": fixture, "line": number}
        powergrid = re.match(r"^(POWERGRID(?:_FREELOOK)?) (PASS|FAIL) ([\w.\-]+:[\w./\-]+) (\S+) (.*)$", line)
        if powergrid:
            prefix, status, identifier, mode, detail = powergrid.groups()
            kind = "powergrid_freelook" if prefix.endswith("_FREELOOK") else "powergrid"
            entry = {"kind": kind, "id": identifier, "mode": mode, "status": status,
                     "detail": detail, "line": number, "scope": "server first-endpoint state; full wire connection untested"}
            if (kind, identifier, mode) in powergrid_fixtures:
                entry["fixture"] = powergrid_fixtures[(kind, identifier, mode)]
            for flag in ("clientAccepted", "serverSelected", "serverAimSynced"):
                match_flag = re.search(r"\b" + flag + r"=(true|false)", detail)
                if match_flag:
                    entry[flag] = match_flag.group(1) == "true"
            attempts.append(entry)
            latest[(kind, identifier, mode)] = entry
            continue
        match = re.match(r"^(PROJECTILE|MELEE|MELEE_OFFSET|PROJECTILE_FREELOOK|MELEE_PATH) ([\w.\-]+:[\w./\-]+)(?: (first_person|offset))?(?: hand=(\w+))? (PASS|FAIL|INCONCLUSIVE) (.*)$", line)
        if match:
            kind, identifier, camera_mode, hand, status, detail = match.groups()
            result_kind = kind.lower()
            if kind in {"MELEE", "PROJECTILE"} and integration_suite not in {None, "melee", "projectiles"}:
                result_kind += "_integration"
            entry = {"kind": result_kind, "id": identifier, "hand": hand or "MAIN_HAND",
                     "status": status, "detail": detail, "line": number, "scope": "server projectile launch" if kind.startswith("PROJECTILE") else "server target health"}
            if kind in {"MELEE", "PROJECTILE"}:
                entry["integrationSuite"] = integration_suite
                if integration_fixture: entry["integrationFixture"] = integration_fixture
            if kind.startswith("MELEE"):
                oracle = re.search(r"\boracle=(\S+)", detail)
                if oracle: entry["oracle"] = oracle.group(1)
                if entry.get("oracle") in {"knockback_without_damage", "canceled_server_attack_with_knockback"}:
                    entry["scope"] = "server knockback without health damage"
                elif entry.get("oracle") == "server_attack_and_direct_player_attack_damage":
                    entry["scope"] = "server attack receipt and directly attributed player-attack damage"
                elif entry.get("oracle") == "server_attack_and_exact_stale_sandwich_source":
                    entry["scope"] = "server attack receipt and exact attributed stale-sandwich damage"
                request = melee_requests.get((kind, identifier, camera_mode if kind == "MELEE_PATH" else ""))
                if request:
                    entry["requestEvidence"] = request
                    actual_held = re.search(r"\bactualHeld=(\S+)", request["detail"])
                    if actual_held: entry["actualHeldItemId"] = actual_held.group(1)
                attribution = {}
                for field in ("preAttackHealth", "healthAtFirstAttack", "serverAttackEvents", "canceledAttackEvents", "clientAttackEvents", "clickEvents", "canceledClickEvents", "attributedDamageEvents", "attributedPlayerAttackDamage", "attributedCustomSourceEvents", "attributedCustomSourceDamage", "unattributedDamage"):
                    value = re.search(r"\b" + field + r"=(-?[\d.]+(?:[Ee][+-]?\d+)?)", detail)
                    if value: attribution[field] = float(value.group(1))
                damage_sources = re.search(r"\bdamageSources=\[([^]]*)\]", detail)
                if damage_sources: attribution["damageSources"] = [s.strip() for s in damage_sources.group(1).split(",") if s.strip()]
                allowed_source = re.search(r"\ballowedCustomSource=(\S+)", detail)
                if allowed_source: attribution["allowedCustomSource"] = allowed_source.group(1)
                if attribution: entry["damageAttribution"] = attribution
            if kind in {"MELEE_OFFSET", "PROJECTILE_FREELOOK"}:
                entry["mode"] = "offset" if kind == "MELEE_OFFSET" else "actual_first_person_freelook"
            if kind == "MELEE_PATH": entry["mode"] = camera_mode
            if kind.startswith("PROJECTILE"):
                entry["samples"] = list(projectile_samples.get((kind, identifier, hand or "MAIN_HAND"), []))
                entry["sampleTypes"] = sorted({sample["entity"] for sample in entry["samples"]})
                entry["oracleLimit"] = "Best initial direction among player-owned projectiles observed during the request window; Crystal Cannon uses horizontal heading. The harness does not assert every launch, impact, or expected projectile type. Recorded types are available for independent factory review."
            if status == "INCONCLUSIVE":
                entry["recordedAction"] = bool(entry.get("requestEvidence") or entry.get("damageAttribution", {}).get("serverAttackEvents", 0) > 0)
            key = (result_kind, identifier, camera_mode if kind == "MELEE_PATH" else hand or "MAIN_HAND")
        else:
            match = re.match(r"^(CREATE_INTERACTIONS|CREATE_FREELOOK|CREATE_FREELOOK_RECHECK) (PASS|FAIL) (.*)$", line)
            if match:
                prefix, status, rest = match.groups()
                pieces = re.split(r" (?=(?:before|after|cameraHit|entities|hovered|remaining|hit|interactionPos|value)=)", rest, maxsplit=1)
                label = pieces[0]
                match_id = re.search(r"[\w.\-]+:[\w./\-]+", label)
                identifier = match_id.group(0) if match_id else ("simulated:honey_glue" if label.startswith("HONEY_GLUE") else "create:super_glue" if label.startswith("SUPER_GLUE") else "")
                server = bool(re.search(r"server-(?:rotation|removal|selection|movement)", label))
                kind = "create_freelook_recheck" if prefix == "CREATE_FREELOOK_RECHECK" else "create_freelook" if prefix == "CREATE_FREELOOK" else "create"
                entry = {"kind": kind, "id": identifier, "case": label, "status": status,
                         "detail": pieces[1] if len(pieces) > 1 else "", "line": number,
                         "scope": "server state" if server else "client targeting/handler"}
                entry["heldItemId"] = "create:wrench" if label.startswith("WRENCH") else identifier if label.startswith(("HONEY_GLUE", "SUPER_GLUE")) else ""
                key = (kind, label)
            else:
                match = re.match(r"^(SIMPLY_SWORDS_ABILITY server target|CARRYON (?:chest|entity) (?:pickup|place)) (PASS|FAIL) (.*)$", line)
                if not match:
                    continue
                label, status, detail = match.groups()
                entry = {"kind": "ability" if label.startswith("SIMPLY") else "carry",
                         "id": "simplyswords:magispear" if label.startswith("SIMPLY") else "",
                         "case": label, "status": status, "detail": detail, "line": number, "scope": "server state"}
                entry["integrationSuite"] = integration_suite
                if integration_suite in {"standard", "all"}: entry["kind"] += "_integration"
                if integration_fixture: entry["integrationFixture"] = integration_fixture
                key = (entry["kind"], label)
        attempts.append(entry)
        latest[key] = entry
    return attempts, list(latest.values())


def comparison_results(lines):
    projectile, melee, recheck, inventories = {}, {}, {}, []
    suite = None
    for number, line in enumerate(lines, 1):
        start = re.match(r"(?:INTEGRATION|MELEE_OFFSET_SUITE|PROJECTILE_FREELOOK_SUITE|MELEE_PATH_SUITE) START suite=(\S+)", line)
        if start:
            suite = start.group(1)
        inventory = re.match(r"(PROJECTILE|MELEE|MELEE_OFFSET|PROJECTILE_FREELOOK|MELEE_PATH) INVENTORY scheduledCases=(\d+)", line)
        if inventory:
            total = re.search(r"\bscheduledTotal=(\d+)", line)
            preserved = re.search(r"\bpreservedPass=(\d+)", line)
            inventories.append({"suite": suite, "kind": inventory.group(1).lower(),
                                "scheduled": int(total.group(1)) if total else int(inventory.group(2)),
                                "scheduledThisInvocation": int(inventory.group(2)),
                                "preservedPass": int(preserved.group(1)) if preserved else 0,
                                "resume": "resume=true" in line, "line": number})
        match = re.match(r"PROJECTILE_BASELINE (\S+) hand=(\S+) (PASS|FAIL) (.*)", line)
        if match:
            item, hand, status, detail = match.groups()
            projectile[item, hand] = {"id": item, "hand": hand, "status": status, "detail": detail, "line": number}
        match = re.match(r"MELEE_BASELINE item=(\S+) mode=(\S+) (PASS_DAMAGE|NO_DAMAGE) (.*)", line)
        if match:
            item, mode, status, detail = match.groups()
            melee[item, mode] = {"id": item, "mode": mode, "status": status, "detail": detail, "line": number}
        match = re.match(r"MELEE_RECHECK ([\w.-]+:[\w./-]+) (PASS|CAMERA_FAIL_REVIEW|BASELINE_FAIL|BASELINE_ONLY_FAILURE_REVIEW) (.*)", line)
        if match:
            item, status, detail = match.groups()
            recheck[item] = {"id": item, "status": status, "detail": detail, "line": number}
    return {"projectileBaselines": list(projectile.values()), "meleeModeChecks": list(melee.values()),
            "meleeRechecks": list(recheck.values()), "scheduledSuites": inventories}


def summarize(args):
    offset = int(args.offset_file.read_text(encoding="utf-8-sig").strip())
    started = args.offset_file.stat().st_mtime
    source = args.qa / "results.log"
    log, primary_log_meta = read_log(source, offset)
    lines = log.splitlines()
    logs = {"primary": log}
    log_sources = {"primary": primary_log_meta}
    secondary_check = None
    block_log = log
    if args.block_qa:
        block_offset = int(args.block_offset_file.read_text(encoding="utf-8-sig").strip()) if args.block_offset_file else 0
        block_log, block_log_meta = read_log(args.block_qa / "results.log", block_offset)
        secondary_check = verify_secondary(args.qa, args.block_qa, block_log, started)
        logs["block"] = block_log
        log_sources["block"] = block_log_meta
    warnings = []
    census_path = args.qa / "census/summary.json"
    census_valid = census_path.exists() and "CENSUS COMPLETE" in log and census_path.stat().st_mtime >= started - 2
    census = read_json(census_path, {}) if census_valid else {}
    census_items = read_json(args.qa / "census/items.json", []) if census_valid else []
    census_blocks = read_json(args.qa / "census/blocks.json", []) if census_valid else []
    census_entities = read_json(args.qa / "census/entities.json", []) if census_valid else []
    if not census_valid:
        warnings.append("No completed current-task census. Registry denominators are unavailable.")

    family_audit = read_json(args.create_audit, {})
    family = set(family_audit.get("familyModIds", []))
    contextual_create_files = [p for p in (Path("build/exhaustive-qa/create-addon-coverage.json"), Path("build/exhaustive-qa/create-addon-coverage.md"))
                               if p.exists() and p.stat().st_mtime >= started - 2]
    inventory = {"status": "ENUMERATED_NOT_PLAY_TESTED" if census_valid else "NOT_EXECUTED", **census}

    specs = {
        "items": ("registry-items/camera-paths.csv", "REGISTRY_ITEMS START", ","),
        "blocks": ("block-targeting.tsv", "BLOCK_TARGETING START", "\t"),
        "blockStates": ("block-state-targeting.tsv", "BLOCK_STATE_TARGETING START", "\t"),
        "blockFreelookStates": ("block-state-freelook-targeting.tsv", "BLOCK_STATE_FREELOOK_TARGETING START", "\t"),
        "blockInteractions": ("block-interactions.tsv", "BLOCK_INTERACTIONS START", "\t"),
        "entities": ("registry-entities/targetability.csv", "REGISTRY_ENTITIES START", ","),
        "entityActions": ("entity-actions.tsv", "ENTITY_ACTIONS START", "\t"),
        "entityFreelookActions": ("entity-freelook-actions.tsv", "ENTITY_ACTIONS START", "\t"),
        "entityActionRechecks": ("entity-actions-recheck.tsv", "ENTITY_ACTIONS_RECHECK START", "\t"),
        "entityFreelookRechecks": ("entity-freelook-actions-recheck.tsv", "ENTITY_ACTIONS_RECHECK START", "\t"),
        "blockFreelookActions": ("block-freelook-interactions.tsv", "BLOCK_FREELOOK START", "\t"),
        "itemAirUse": ("item-air-use/outcomes.tsv", "ITEM_AIR_USE START", "\t"),
        "itemAirUseFreelook": ("item-air-use/freelook/outcomes.tsv", "profile=freelook", "\t"),
        "spawnEggUse": ("spawn-egg-use/outcomes.tsv", "SPAWN_EGG_USE START", "\t"),
        "bucketUse": ("bucket-use/outcomes.tsv", "BUCKET_USE START", "\t"),
    }
    evidence, rows, artifact_logs = {}, {}, {}
    for key, (relative, marker, delimiter) in specs.items():
        secondary = args.block_qa is not None and key in {"blockStates", "blockFreelookStates", "blockInteractions", "blockFreelookActions"}
        folder = args.block_qa if secondary else args.qa
        artifact_log = block_log if secondary else log
        artifact_logs[key] = artifact_log
        rows[key], evidence[key] = artifact(folder / relative, artifact_log, marker, started, delimiter)
        evidence[key]["source"] = "block" if secondary else "primary"
        if not evidence[key]["available"]:
            warnings.append(f"{key}: {evidence[key]['reason']}.")
    block_interaction_attempts = rows["blockInteractions"]
    rows["blockInteractions"] = list({r["block"]: r for r in block_interaction_attempts if r.get("block")}.values())
    item_rows = rows["items"]
    item_ids = {r["item"] for r in item_rows if r.get("item")}
    item_failures = [r for r in item_rows if r.get("camera_packet_status", "").startswith("FAIL")]
    item_status = counter(item_rows, "camera_packet_status")
    items = {
        "completed": completed(log, "REGISTRY_ITEMS START", "REGISTRY_ITEMS DONE"),
        "registered": census.get("items"), "distinctItems": len(item_ids), "cases": len(item_rows),
        "statuses": item_status,
        "betterCombatAttackGeometryChecks": sum(int(r.get("bettercombat_attacks") or 0) for r in item_rows),
        "failures": item_failures,
        "missingItemIds": sorted({r["id"] for r in census_items} - item_ids),
        "scope": "Default stacks in both hands and five views; actual transformed camera and synthetic packet hooks. No network send, render, inventory tick, use, attack, ability or server-effect certification.",
    }
    block_sections = {}
    for key, done in [("blocks", "BLOCK_TARGETING DONE"), ("blockStates", "BLOCK_STATE_TARGETING DONE"),
                      ("blockFreelookStates", "BLOCK_STATE_FREELOOK_TARGETING DONE"), ("blockInteractions", "BLOCK_INTERACTIONS DONE")]:
        records = rows[key]
        statuses = counter(records, "status")
        issues = [r for r in records if r.get("status") in {"TARGETING_FAIL", "ERROR", "OBSERVATION_DIFF"}]
        fixture = [r for r in records if r.get("status") in {"NO_OUTLINE", "BASELINE_MISS", "NEEDS_FIXTURE", "NEEDS_SPECIALIZED_FIXTURE", "REFERENCE_OCCLUDED_OR_RANGE", "CONTEXT_DIFFERENCE"}]
        block_sections[key] = {
            "completed": completed(artifact_logs[key], done.replace(" DONE", " START"), done),
            "source": evidence[key]["source"],
            "rows": len(records), "statuses": statuses,
            "differencesOrErrors": issues,
            "fixtureOrNonTargetableCount": len(fixture),
            "fixtureOrNonTargetableRows": fixture[:250] if key in {"blockStates", "blockFreelookStates"} else fixture,
            "fixtureExamplesTruncated": key in {"blockStates", "blockFreelookStates"} and len(fixture) > 250,
        }
    block_sections["blocks"]["scope"] = "Every default block state in isolation; direct camera pose; first-person baseline and both shoulders. Geometry only."
    block_sections["blockStates"]["scope"] = "Every registered state in isolation; three ray modes. No neighbor arrangements, machinery operation, UI, recipes or actions."
    block_sections["blockFreelookStates"]["scope"] = "Separate all-state geometry with actual FIRST_PERSON freelook input consumed, held key, active=false and body/view deflections of minus/plus 75 degrees. Direct camera pose and geometry only; no use request, rendering or machinery-function certification."
    legacy_free_headers = bool(rows["blockFreelookStates"] and "shoulder_left" in rows["blockFreelookStates"][0])
    block_sections["blockFreelookStates"]["rawHeaderMapping"] = {
        "shoulder_left": "freelook_left", "shoulder_right": "freelook_right"} if legacy_free_headers else {}
    block_sections["blockFreelookStates"]["schemaNote"] = (
        "The executed new geometry probe retained legacy shoulder_left/right column labels. Its implementation and per-row limits use actual held FIRST_PERSON freelook at minus/plus 75 degrees. This explicit semantic mapping does not alter raw headers or outcomes."
        if legacy_free_headers else "Candidate columns explicitly name the freelook modes.")
    block_sections["blockInteractions"]["scope"] = "Real empty-main-hand requests in first person and one shoulder; ordered server acknowledgement. Compared block state, menu, mounted entity type and block-entity class. Matching no-ops are not functional passes."
    block_sections["blocks"]["registered"] = census.get("blocks")
    block_sections["blockStates"]["registered"] = census.get("blockStates")
    block_sections["blockFreelookStates"]["registered"] = census.get("blockStates")
    block_sections["blockInteractions"]["registered"] = census.get("blocks")
    registered_block_ids = {r["id"] for r in census_blocks}
    for key in ("blocks", "blockInteractions"):
        observed_ids = {r["block"] for r in rows[key]}
        block_sections[key]["distinctBlockIds"] = len(observed_ids)
        block_sections[key]["missingBlockIds"] = sorted(registered_block_ids - observed_ids)
    for key in ("blockStates", "blockFreelookStates"):
        state_ids = {r["block"] for r in rows[key]}
        state_counts = Counter(state.partition("{")[2].partition("}")[0] for state in state_ids)
        block_sections[key]["distinctStateDescriptions"] = len(state_ids)
        block_sections[key]["blockStateCountMismatches"] = [
            {"block": r["id"], "expected": r["stateCount"], "observed": state_counts[r["id"]]}
            for r in census_blocks if state_counts[r["id"]] != r["stateCount"]]
    block_sections["blockInteractions"]["attemptRows"] = len(block_interaction_attempts)
    block_sections["blockInteractions"]["attempts"] = block_interaction_attempts
    block_sections["blockInteractions"]["resumeCleanup"] = {
        "events": [{"source": name, "text": line} for name, source_log in logs.items() for line in source_log.splitlines()
                   if line.startswith("BLOCK_SWEEP_RESUME ")],
        "scope": "Optional QA wrapper around the unchanged broad class: releases installed hold interactions between captured cases and gates recovery on confirmed teleports. It preserves the broad resume identity and prior records; it is not a production fix.",
    }
    diagnostic_rows = []
    diagnostic_source = None
    diagnostic_candidates = [("primary", args.qa, log)]
    if args.block_qa:
        diagnostic_candidates.append(("block", args.block_qa, block_log))
    for name, folder, source_log in diagnostic_candidates:
        path = folder / "state-ray-diagnostics.tsv"
        if path.exists() and "STATE_RAY_DIAGNOSTIC START" in source_log:
            candidate_rows, candidate_meta = artifact(path, source_log, "STATE_RAY_DIAGNOSTIC START", started, "\t")
            if candidate_meta["available"]:
                diagnostic_rows, diagnostic_source = candidate_rows, name
                evidence["stateRayDiagnostics"] = {**candidate_meta, "source": name}
    diagnostic_by_state = defaultdict(list)
    for row in diagnostic_rows:
        diagnostic_by_state[row["state"]].append(row)
    explained_states = set()
    for state, checks in diagnostic_by_state.items():
        first = next((r for r in checks if r.get("mode") == "first_person"), None)
        sides = [r for r in checks if r.get("mode") in {"shoulder_left", "shoulder_right"}]
        if first and first.get("status") == "PASS_TARGET" and len(sides) == 2 and all(
                r.get("first_person_positive") == "true" and r.get("status") in {
                    "PASS_TARGET", "MATCHING_VANILLA_CAMERA_RAY_MISS", "MATCHING_VANILLA_OCCLUSION"} for r in sides):
            explained_states.add(state)
    block_sections["blockStates"]["stateRayDiagnostics"] = {
        "source": diagnostic_source, "rows": diagnostic_rows, "statuses": counter(diagnostic_rows, "status"),
        "explainedInitialFailureStates": sorted(explained_states),
        "scope": "Separate narrow recheck; first-person actual pick plus same-origin vanilla camera/depth rays and direct shape intersection. Matching vanilla misses explain fixture/engine ray geometry, not machinery functionality; original sweep rows remain unchanged.",
    }
    block_sections["blockStates"]["unresolvedDifferenceRows"] = [r for r in block_sections["blockStates"]["differencesOrErrors"]
                                                               if r.get("status") != "TARGETING_FAIL" or r.get("block") not in explained_states]
    initial_block_differences = [dict(r, interpretation=block_difference_category(r)) for r in rows["blockInteractions"]
                                 if r.get("status") in {"TARGETING_FAIL", "ERROR", "OBSERVATION_DIFF", "CONTEXT_DIFFERENCE"}]
    block_sections["blockInteractions"]["differenceCategories"] = counter(initial_block_differences, "interpretation")
    block_sections["blockInteractions"]["classifiedDifferences"] = initial_block_differences
    recheck_rows, recheck_source, recheck_complete, recheck_artifacts = [], None, False, []
    for name, folder, source_log in diagnostic_candidates:
        paths = sorted(folder.glob("block-interaction-recheck-*.tsv"), key=lambda p: p.stat().st_mtime)
        paths.append(folder / "block-interaction-recheck.tsv")
        for path in paths:
            candidate_rows, candidate_meta = artifact(path, source_log, "BLOCK_RECHECK START", started, "\t")
            if candidate_meta["available"]:
                profile = folder / "block-interaction-recheck-fixture-version.txt" if path.name == "block-interaction-recheck.tsv" else Path(str(path) + ".fixture-version.txt")
                candidate_meta["fixtureVersion"] = profile.read_text(encoding="utf-8-sig").strip() if profile.exists() else "LEGACY_NOT_RECORDED"
                recheck_rows.extend(candidate_rows)
                recheck_source = name
                recheck_artifacts.append({**candidate_meta, "source": name})
                recheck_complete = completed(source_log, "BLOCK_RECHECK START", "BLOCK_RECHECK DONE")
    if recheck_artifacts:
        evidence["blockInteractionRecheck"] = recheck_artifacts
    latest_recheck_rows = list({(r["block"], r["repeat"]): r for r in recheck_rows}.values())
    for row in latest_recheck_rows:
        region = diode_region_evidence(row)
        if region:
            row["regionEvidence"] = region
    recheck_attempts = []
    for name, text in logs.items():
        for line in text.splitlines():
            match = re.match(r"BLOCK_RECHECK ([\w.-]+:[\w./-]+) repeat=(\d+) (\S+) serverEvents=(\d+)/(\d+)", line)
            if match:
                identifier, repeat, status, first_events, side_events = match.groups()
                recheck_attempts.append({"source": name, "block": identifier, "repeat": int(repeat), "status": status,
                                         "first_server_events": int(first_events), "shoulder_server_events": int(side_events)})
    rechecked_blocks = defaultdict(list)
    for row in latest_recheck_rows:
        rechecked_blocks[row["block"]].append(row)
    matching_rechecks = set()
    fixture_rechecks = set()
    region_rechecks = set()
    mixed_limit_rechecks = set()
    for identifier, checks in rechecked_blocks.items():
        # Three independent paired retries, with a receipt event in both views.
        unique = {r["repeat"]: r for r in checks}
        if len(unique) == 3 and all(r.get("status") in {"OBSERVED_PARITY_PASS", "MATCHED_NO_OBSERVABLE_EFFECT", "MATCHED_SERVER_LOCAL_RETURN_DIFF"}
                                  and int(r.get("first_server_events") or 0) > 0 and int(r.get("shoulder_server_events") or 0) > 0 for r in unique.values()):
            matching_rechecks.add(identifier)
        if len(unique) == 3 and all(r.get("status") in {"UNEQUAL_SETTLED_FIXTURE", "NEEDS_SPECIALIZED_FIXTURE"} for r in unique.values()):
            fixture_rechecks.add(identifier)
        if len(unique) == 3 and all(r.get("regionEvidence") for r in unique.values()):
            region_rechecks.add(identifier)
        bounded = {"OBSERVED_PARITY_PASS", "MATCHED_NO_OBSERVABLE_EFFECT", "MATCHED_SERVER_LOCAL_RETURN_DIFF", "UNEQUAL_SETTLED_FIXTURE", "NEEDS_SPECIALIZED_FIXTURE"}
        if len(unique) == 3 and identifier not in matching_rechecks | fixture_rechecks | region_rechecks and all(
                r.get("status") in bounded or r.get("regionEvidence") for r in unique.values()):
            mixed_limit_rechecks.add(identifier)
    block_sections["blockInteractions"]["recheck"] = {
        "source": recheck_source, "completed": recheck_complete, "rows": latest_recheck_rows,
        "attempts": recheck_attempts, "attemptRows": len(recheck_attempts),
        "runs": [{"source": name, "start": start_line, "completed": completed(segment, "BLOCK_RECHECK START", "BLOCK_RECHECK DONE"),
                  "aborted": "BLOCK_RECHECK ABORT" in segment}
                 for name, source_log in logs.items() for start_line, segment in suite_segments(source_log.splitlines(), "BLOCK_RECHECK")],
        "statuses": counter(latest_recheck_rows, "status"), "matchingServerObservationBlocks": sorted(matching_rechecks),
        "specializedFixtureBlocks": sorted(fixture_rechecks),
        "differentInteractionRegionBlocks": sorted(region_rechecks),
        "mixedFixtureOrRegionBlocks": sorted(mixed_limit_rechecks),
        "localReturnDifferenceBlocks": sorted({r["block"] for r in latest_recheck_rows if r.get("status") == "MATCHED_SERVER_LOCAL_RETURN_DIFF"}),
        "unresolvedDifferenceRows": [r for r in latest_recheck_rows if r.get("status") in {"SERVER_DIFFERENCE_REVIEW", "SERVER_ACTION_NOT_OBSERVED", "ERROR"} and not r.get("regionEvidence")],
        "scope": "Separate failed-only recheck: three paired modes with fresh supports/neighbors, settled pre-state, explicit server teleport readiness and RightClickBlock event receipt. An event proves request handling, not success of a machine function. Matching server observations with different local return values remain limited: vanilla startUseItem offhand fallback was not exercised by this direct empty-main-hand useItemOn fixture.",
    }
    for name, folder, source_log in diagnostic_candidates:
        adoption_path = folder / "block-interaction-recheck-identity-adoption.json"
        if adoption_path.exists() and adoption_path.stat().st_mtime >= started - 2 and "BLOCK_RECHECK PROFILE_ADOPT verified=true" in source_log:
            adoption = read_json(adoption_path, {})
            block_sections["blockInteractions"]["recheck"]["identityAdoption"] = {
                "source": name, "artifact": file_meta(adoption_path), "verification": adoption,
                "reviewEvidenceHashMatched": any(a.get("sha256") == adoption.get("reviewSha256") for a in recheck_artifacts),
                "scope": "Metadata adoption after explicit runtime/census/release-byte and completed-pair checks. Does not count additional test executions or erase unresolved outcomes.",
            }
    recheck_difference_blocks = {r["block"] for r in block_sections["blockInteractions"]["recheck"]["unresolvedDifferenceRows"]}
    server_review_rows = [r for r in initial_block_differences
        if r["block"] not in matching_rechecks | fixture_rechecks | region_rechecks | mixed_limit_rechecks
        and (r["block"] in recheck_difference_blocks or r["interpretation"] not in {"DEFAULT_STATE_SETUP_NOT_SENT", "DIFFERENT_HIT_FACE_CONTEXT", "MATCHED_SERVER_OBSERVATION_LOCAL_RETURN_DIFF"})]
    server_review_rows = [dict(r, originalInterpretation=r["interpretation"], interpretation="SERVER_DIFFERENCE_OBSERVED_IN_RECHECK",
                              recheckRows=[check for check in block_sections["blockInteractions"]["recheck"]["unresolvedDifferenceRows"] if check["block"] == r["block"]])
                          if r["block"] in recheck_difference_blocks else r for r in server_review_rows]
    matched_client_returns = {identifier for identifier, checks in rechecked_blocks.items()
        if len({r["repeat"] for r in checks}) == 3 and all(r.get("status") in {"OBSERVED_PARITY_PASS", "MATCHED_NO_OBSERVABLE_EFFECT"} for r in checks)}
    remaining_client_ids = ({r["block"] for r in initial_block_differences if r["interpretation"] == "MATCHED_SERVER_OBSERVATION_LOCAL_RETURN_DIFF"}
                            | {r["block"] for r in latest_recheck_rows if r.get("status") == "MATCHED_SERVER_LOCAL_RETURN_DIFF"}) - matched_client_returns
    client_review_rows = [{"block": identifier, "status": "CLIENT_RETURN_DIFFERENCE_REVIEW",
                           "interpretation": "SERVER_OBSERVATIONS_MAY_MATCH_OFFHAND_FALLBACK_UNTESTED",
                           "recheckRows": rechecked_blocks.get(identifier, [])} for identifier in sorted(remaining_client_ids)]
    block_sections["blockInteractions"]["unresolvedServerObservationRows"] = server_review_rows
    block_sections["blockInteractions"]["unresolvedClientReturnRows"] = client_review_rows
    block_sections["blockInteractions"]["unresolvedDifferenceRows"] = list({r["block"]: r for r in client_review_rows + server_review_rows}.values())
    block_sections["blockInteractions"]["fixtureCaveat"] = "The initial sweep's ordered acknowledgement does not establish server acceptance: Minecraft ignores UseItemOn while a teleport is unconfirmed. It also reset only the target, allowing previous neighbor/support changes. Only the separate settled recheck explicitly gates teleport readiness and resets the whole fixture. Raw observations remain preserved; none is automatically a camera defect."
    initial_resume = re.search(r"BLOCK_INTERACTIONS START .*?alreadyRecorded=(\d+)", artifact_logs["blockInteractions"])
    if initial_resume and int(initial_resume.group(1)) > 0:
        warnings.append("Block interaction file resumes pre-existing matching-build records. Their prior-session rows cannot be separated from this log offset; treat the artifact as cumulative, not all newly executed.")
        block_sections["blockInteractions"]["preExistingRecords"] = int(initial_resume.group(1))
    entity_rows = rows["entities"]
    entity_failures = [r for r in entity_rows if r.get("baseline") == "ERROR" or any(r.get(k, "").startswith("FAIL") for k in ("shoulder_left", "shoulder_right", "freelook_left", "freelook_right"))]
    entity_checks = Counter(r.get(k, "MISSING") for r in entity_rows for k in ("shoulder_left", "shoulder_right", "freelook_left", "freelook_right"))
    entities = {
        "completed": completed(log, "REGISTRY_ENTITIES START", "REGISTRY_ENTITIES DONE"),
        "registered": census.get("entityTypes"), "rows": len(entity_rows),
        "baselines": counter(entity_rows, "baseline"), "modeStatuses": dict(sorted(entity_checks.items())),
        "differencesOrErrors": entity_failures,
        "scope": "Default client-side factory and targetability only. Synthetic entities; no AI, variants, multipart assembly, damage, trade, tame, mount, server spawn or interaction actions.",
    }
    entity_action_rows = list({r["entity"]: r for r in rows["entityActions"] if r.get("entity")}.values())
    entity_actions = {
        "completed": completed(log, "ENTITY_ACTIONS START", "ENTITY_ACTIONS DONE"),
        "registered": census.get("entityTypes"), "rows": len(entity_action_rows), "attemptRows": len(rows["entityActions"]),
        "statuses": counter(entity_action_rows, "status"),
        "differencesOrErrors": [r for r in entity_action_rows if r.get("status") in {"OBSERVATION_DIFF", "TARGETING_DIFFERENCE", "ERROR"}],
        "fixtureOrIneligibleRows": [r for r in entity_action_rows if r.get("status") in {"NOT_TARGETABLE_DEFAULT", "NEEDS_FIXTURE"}],
        "missingEntityIds": sorted({r["id"] for r in census_entities} - {r["entity"] for r in entity_action_rows}),
        "scope": "Real empty-hand interaction requests against default server-created entities, first person vs shoulder. Unsupported/ineligible types and matching no-ops are explicit; no proof of all trades, mounts, tame/feeding items, variants or multipart gameplay.",
    }
    free_entity_rows = list({r["entity"]: r for r in rows["entityFreelookActions"] if r.get("entity")}.values())
    entity_freelook = {
        "completed": evidence["entityFreelookActions"]["available"] and completed(log, "ENTITY_ACTIONS START", "ENTITY_ACTIONS DONE"),
        "registered": census.get("entityTypes"), "rows": len(free_entity_rows), "statuses": counter(free_entity_rows, "status"),
        "differencesOrErrors": [r for r in free_entity_rows if r.get("status") in {"OBSERVATION_DIFF", "TARGETING_DIFFERENCE", "ERROR"}],
        "missingEntityIds": sorted({r["id"] for r in census_entities} - {r["entity"] for r in free_entity_rows}),
        "scope": "Separate default-entity empty-main-hand comparison: actual FIRST_PERSON freelook with body/view separation. Offset evidence is recorded separately; no unsupported combined offset/freelook mode is claimed.",
    }
    for section, original, key in ((entity_actions, entity_action_rows, "entityActionRechecks"),
                                    (entity_freelook, free_entity_rows, "entityFreelookRechecks")):
        review = list({r["entity"]: r for r in rows[key] if r.get("entity")}.values())
        combined = {r["entity"]: r for r in original + review}
        section["recheck"] = {"completed": evidence[key]["available"] and completed(log, "ENTITY_ACTIONS_RECHECK START", "ENTITY_ACTIONS_RECHECK DONE"),
            "rows": review, "statuses": counter(review, "status"),
            "fixtureEvents": [line for line in lines if line.startswith("ENTITY_ACTIONS_RECHECK FIXTURE ")],
            "scope": "Separate retry of original offset/freelook errors and differences, plus NEEDS_FIXTURE cases with phase-1 timeouts or entity picks. Repairs snapshot cleanup of null/multipart entries and places the nearest front surface 2.1 blocks away instead of putting a large entity's center 2.1 blocks away. Original outcomes remain unchanged; matching default requests do not establish full entity-function coverage."}
        section["latestReviewedStatuses"] = counter(list(combined.values()), "status")
        section["unresolvedDifferenceRows"] = [r for r in combined.values() if r.get("status") in {"ERROR", "OBSERVATION_DIFF", "TARGETING_DIFFERENCE"}]
        section["latestReviewedFixtureRows"] = [r for r in combined.values() if r.get("status") in {"NEEDS_FIXTURE", "NOT_TARGETABLE_DEFAULT"}]
    free_block_rows = list({r["block"]: r for r in rows["blockFreelookActions"] if r.get("block")}.values())
    block_freelook = {
        "completed": completed(artifact_logs["blockFreelookActions"], "BLOCK_FREELOOK START", "BLOCK_FREELOOK DONE"),
        "registered": census.get("blocks"), "rows": len(free_block_rows), "attemptRows": len(rows["blockFreelookActions"]),
        "attempts": rows["blockFreelookActions"], "statuses": counter(free_block_rows, "status"),
        "missingBlockIds": sorted(registered_block_ids - {r["block"] for r in free_block_rows}),
        "actualFreelookGateRows": sum("actualFreelookGate=true" in r.get("freelook_mode", "") for r in free_block_rows),
        "pairedServerReceiptRows": sum(int(r.get("first_server_events") or 0) > 0 and int(r.get("freelook_server_events") or 0) > 0 for r in free_block_rows),
        "differencesOrErrors": [r for r in free_block_rows if r.get("status") in {"ERROR", "TARGETING_DIFFERENCE_REVIEW", "SERVER_ACTION_NOT_OBSERVED", "SERVER_DIFFERENCE_REVIEW", "MATCHED_SERVER_LOCAL_RETURN_DIFF"}],
        "fixtureOrContextRows": [r for r in free_block_rows if r.get("status") in {"NEEDS_SPECIALIZED_FIXTURE", "UNEQUAL_STARTING_FIXTURE", "FIXTURE_CHANGED_BEFORE_USE"}],
        "uiPreflightRecovery": {"events": [line for line in artifact_logs["blockFreelookActions"].splitlines() if line.startswith("BLOCK_FREELOOK_UI_FIX ")],
            "scope": "Optional guard around the unchanged broad controller. It detects cleared keys, changed poses or open screens, closes any screen and restarts the actual input pose, three-tick preflight and ordered readiness ACK before any request. Screen-free key interruptions do not prove a GUI cause. Completed rows retain their identity; original errors and all attempts remain recorded."},
        "mutationRecovery": {"events": [line for line in artifact_logs["blockFreelookActions"].splitlines() if line.startswith("BLOCK_FREELOOK_RECOVERY ")],
            "verifiedRestored": completed(artifact_logs["blockFreelookActions"], "BLOCK_FREELOOK_RECOVERY START", "BLOCK_FREELOOK_RECOVERY RESTORED verified=true"),
            "scope": "Separate recovery after a broad timeout: await the actual uncanceled mutation future, exclude earlier broad mutations from the server stack, compare the original snapshot and re-restore only after draining if needed. Exact block/block-entity/inventory/mode/pose and teleport readiness must persist for three server ticks. It does not resume gameplay or alter completed evidence; flying and entity NBT were not captured by the original snapshot."},
        "pendingSetupLoadGuard": {"events": [line for line in artifact_logs["blockFreelookActions"].splitlines() if line.startswith("BLOCK_FREELOOK_LOAD_GUARD ")],
            "scope": "Optional guard for the unchanged loaded controller: suppress the client-age timeout only in setup phases zero/one while the actual uncanceled server mutation future is pending. Normal completion/exception handling resumes afterward. No pose, action result, use-packet acknowledgement or gameplay oracle changes; this prevents premature overlapping cleanup during known chunk-load stalls."},
        "intentionalCheckpointStops": {"events": [line for line in artifact_logs["blockFreelookActions"].splitlines() if line.startswith("BLOCK_FREELOOK_CHECKPOINT_STOP ")],
            "scope": "An intentional process-restart checkpoint waits for setup phase one and its completed uncanceled mutation future before invoking the original cleanup. Completed rows remain; the current incomplete pair will be retried. An ABORT paired with this marker is an intentional checkpoint, not a new gameplay failure; separate recovery verification is required."},
        "scope": "Separate real FIRST_PERSON freelook vs baseline, active=false, body 90 degrees from camera, held key and consumed production mouse-turn hook. Fresh supports, conditional acknowledged teleports, three held-pose ticks before a fresh ordered readiness ACK, server observations and event-time state guard. Unstable states are explicit limits. Matching local returns/effects do not certify offhand fallback or contextual tools.",
    }
    free_review_attempts, free_review_current, free_review_artifacts = [], [], []
    free_review_complete = False
    for name, folder, source_log in diagnostic_candidates:
        current_file = folder / "block-freelook-recheck.tsv"
        profile_file = Path(str(current_file) + ".identity")
        profile = profile_file.read_text(encoding="utf-8-sig").strip() if profile_file.exists() else None
        candidates = sorted(folder.glob("block-freelook-recheck-previous-*.tsv"), key=lambda p: p.stat().st_mtime)
        candidates.append(current_file)
        for path in candidates:
            candidate_rows, candidate_meta = artifact(path, source_log, "BLOCK_FREELOOK_RECHECK START", started, "\t")
            if not candidate_meta["available"]:
                continue
            candidate_profile_file = Path(str(path) + ".identity")
            candidate_profile = candidate_profile_file.read_text(encoding="utf-8-sig").strip() if candidate_profile_file.exists() else None
            candidate_meta.update(source=name, fixtureIdentity=candidate_profile, matchesCurrentIdentity=bool(profile and candidate_profile == profile))
            free_review_artifacts.append(candidate_meta)
            free_review_attempts.extend(candidate_rows)
            if profile and candidate_profile == profile:
                free_review_current.extend(candidate_rows)
            if path == current_file:
                free_review_complete = completed(source_log, "BLOCK_FREELOOK_RECHECK START", "BLOCK_FREELOOK_RECHECK DONE")
    free_review_latest = list({(r["block"], r.get("repeat")): r for r in free_review_current if r.get("block")}.values())
    free_review_groups = defaultdict(list)
    for row in free_review_latest:
        region = diode_region_evidence({**row, "shoulder_before": row.get("freelook_before"),
                                        "shoulder_after": row.get("freelook_after"), "shoulder_hit": row.get("freelook_hit")})
        if region: row["interactionRegionEvidence"] = region
        free_review_groups[row["block"]].append(row)
    free_matching_blocks, free_special_blocks, free_region_blocks = set(), set(), set()
    free_local_return_blocks = {r["block"] for r in free_review_latest if r.get("status") == "MATCHED_SERVER_LOCAL_RETURN_DIFF"}
    for identifier, checks in free_review_groups.items():
        if {r.get("repeat") for r in checks} != {"0", "1", "2"}:
            continue
        matching = {"OBSERVED_PARITY_PASS", "MATCHED_NO_OBSERVABLE_EFFECT", "MATCHED_SERVER_LOCAL_RETURN_DIFF"}
        if all(r.get("status") in matching and r.get("first_after") == r.get("freelook_after")
               and int(r.get("first_server_events") or 0) > 0 and int(r.get("freelook_server_events") or 0) > 0
               and "keyHeld=false" in r.get("first_mode", "") and "serverAimReady=true" in r.get("first_mode", "")
               and "actualFreelookGate=true" in r.get("freelook_mode", "") and "serverAimReady=true" in r.get("freelook_mode", "") for r in checks):
            free_matching_blocks.add(identifier)
        if all(r.get("status") == "NEEDS_SPECIALIZED_FIXTURE" for r in checks): free_special_blocks.add(identifier)
        if all(r.get("interactionRegionEvidence") for r in checks): free_region_blocks.add(identifier)
    free_explained = free_matching_blocks | free_special_blocks | free_region_blocks
    block_freelook["recheck"] = {
        "completed": free_review_complete, "artifacts": free_review_artifacts, "attempts": free_review_attempts,
        "latestRows": free_review_latest, "statuses": counter(free_review_latest, "status"),
        "matchingServerObservationBlocks": sorted(free_matching_blocks), "specializedFixtureBlocks": sorted(free_special_blocks),
        "differentInteractionRegionBlocks": sorted(free_region_blocks), "localReturnDifferenceBlocks": sorted(free_local_return_blocks),
        "readinessEvents": [line for _, _, source_log in diagnostic_candidates for line in source_log.splitlines()
                            if line.startswith("BLOCK_FREELOOK_RECHECK ") and any(word in line for word in (" WAIT ", " WAIT_MUTATION_DRAIN ", " MUTATIONS_DRAINED ", " ERROR ", " ABORT "))],
        "scope": "Three separately recorded pairs for latest broad differences/errors. Fresh supports, genuine mode input guards on both sides, server body/pitch aim readiness and event receipts. The newer fixture waits twenty actual server/player settle ticks and three held-pose server ticks; head aim is observed without an extra gate, while the baseline head fixture remains explicit. The earlier head-gated client-tick timeout is preserved in history. Current fixture identity is required for reuse. Matching no-ops are not functional passes; local return/fallback limitations remain explicit."}
    head_tick_evidence = Path("build/compat-audit/block-freelook-head-tick.txt")
    if head_tick_evidence.exists():
        block_freelook["recheck"]["headTickReadinessEvidence"] = {
            "artifact": file_meta(head_tick_evidence),
            "scope": "Mapped vanilla code distinguishes accepted body rotation/ordered packet ACK from a later actual player tick updating head yaw. The old pre-use head-only timeout did not send the failed pair's use request and does not establish a camera failure. The audit explains the stricter-than-needed generic block-use readiness condition; it does not independently measure how many server ticks elapsed in that aborted attempt."}
    block_freelook["unresolvedServerObservationRows"] = [r for r in block_freelook["differencesOrErrors"]
        if r["block"] not in free_explained and r.get("status") != "MATCHED_SERVER_LOCAL_RETURN_DIFF"]
    block_freelook["unresolvedClientReturnRows"] = [r for r in free_block_rows
        if r["block"] in free_local_return_blocks or r.get("status") == "MATCHED_SERVER_LOCAL_RETURN_DIFF"
        and (r["block"] not in free_matching_blocks or r["block"] in free_local_return_blocks)]
    air_rows = list({row["item"]: row for row in rows["itemAirUse"] if row.get("item")}.values())
    air_ids = {row["item"] for row in air_rows}
    air_segments = suite_segments(lines, "ITEM_AIR_USE")
    original_air_segments = [segment for start, segment in air_segments if "review=true" not in start and "profile=freelook" not in start]
    air = {
        "completed": bool(original_air_segments) and completed(original_air_segments[-1], "ITEM_AIR_USE START", "ITEM_AIR_USE DONE"),
        "registeredNonBlockItems": sum(not row.get("blockItem", False) for row in census_items),
        "attemptRows": len(rows["itemAirUse"]), "distinctItems": len(air_ids),
        "latestStatuses": counter(air_rows, "status"),
        "differencesOrErrors": [row for row in air_rows if row.get("status") not in {"MATCHED_AIR_USE_SMOKE", "MATCHED_NO_OBSERVABLE_EFFECT"}],
        "missingNonBlockItemIds": sorted({row["id"] for row in census_items if not row.get("blockItem", False)} - air_ids),
        "scope": "Real default-stack air-use/release requests, main hand, first person vs shoulder, at most 40 use ticks. Matching results can be no-ops. No special ammo, charge, target, custom ability or full effect certification.",
    }
    air_review_rows, air_review_runs = [], []
    for start_line, segment in air_segments:
        if "review=true" not in start_line or "profile=freelook" in start_line:
            continue
        match = re.search(r" file=(.+)$", start_line)
        if not match:
            continue
        relative = Path(match.group(1).replace("\\", "/"))
        path = relative if relative.is_absolute() else args.qa.parent / relative
        if not path.resolve().is_relative_to((args.qa / "item-air-use").resolve()):
            raise ValueError("Air-use review artifact outside expected QA directory: " + str(path))
        candidate_rows, candidate_meta = artifact(path, segment, "ITEM_AIR_USE START", started, "\t")
        air_review_rows.extend(candidate_rows)
        packet_log = path.parent / "runtime-packet-errors.log"
        isolation_methods = path.parent / "ability-isolation-methods.txt"
        air_review_runs.append({"artifact": candidate_meta, "completed": completed(segment, "ITEM_AIR_USE START", "ITEM_AIR_USE DONE"),
                                "packetErrorArtifact": file_meta(packet_log) if packet_log.exists() and packet_log.stat().st_mtime >= started - 2 else None,
                                "abilityIsolationMethods": file_meta(isolation_methods) if isolation_methods.exists() and isolation_methods.stat().st_mtime >= started - 2 else None,
                                "abilityIsolationEvents": [line for line in segment.splitlines() if "ABILITY_ISOLATION" in line or "WAIT_ACTIVE_ABILITIES" in line]})
    latest_air_reviews = list({r["item"]: r for r in air_review_rows if r.get("item")}.values())
    resolved_air = {r["item"] for r in latest_air_reviews if r.get("status") in {"MATCHED_AIR_USE_SMOKE", "MATCHED_NO_OBSERVABLE_EFFECT"}}
    auto_consumed_air = {r["item"] for r in latest_air_reviews if r.get("status") == "MATCHED_AUTO_CONSUMED_BEFORE_USE"
                         and r.get("first_use") == r.get("shoulder_use") == "NOT_SENT_AUTO_CONSUMED_BEFORE_USE"}
    requested_air_ids = {r["item"] for r in air_rows + latest_air_reviews if any(r.get(key) in {"SUCCESS", "SUCCESS_NO_ITEM_USED", "CONSUME", "CONSUME_PARTIAL", "PASS", "FAIL"}
                        for key in ("first_use", "shoulder_use"))}
    air["itemsWithRecordedUseRequest"] = sorted(requested_air_ids)
    air["review"] = {"runs": air_review_runs, "attempts": air_review_rows, "latestRows": latest_air_reviews,
                     "statuses": counter(latest_air_reviews, "status"), "matchingItems": sorted(resolved_air),
                     "autoConsumedBeforeUseItems": sorted(auto_consumed_air),
                     "scope": "Failed-only rerun with a synchronized empty-inventory/equipment gate and explicit equipment observations. Original records remain unchanged; matching smoke does not prove every contextual ability."}
    reviewed_air = {r["item"]: r for r in air_rows + latest_air_reviews}
    air["latestReviewedStatuses"] = counter(list(reviewed_air.values()), "status")
    air["unresolvedDifferenceRows"] = [r for r in reviewed_air.values() if r.get("status") not in {
        "MATCHED_AIR_USE_SMOKE", "MATCHED_NO_OBSERVABLE_EFFECT", "MATCHED_AUTO_CONSUMED_BEFORE_USE"}]
    free_air_rows = list({r["item"]: r for r in rows["itemAirUseFreelook"] if r.get("item")}.values())
    free_air_segments = [segment for start, segment in air_segments if "profile=freelook" in start and "review=true" not in start]
    free_requested_ids = {r["item"] for r in free_air_rows if any(r.get(key) in {"SUCCESS", "SUCCESS_NO_ITEM_USED", "CONSUME", "CONSUME_PARTIAL", "PASS", "FAIL"}
                          for key in ("first_use", "freelook_use"))}
    air_freelook = {
        "completed": bool(free_air_segments) and completed(free_air_segments[-1], "ITEM_AIR_USE START", "ITEM_AIR_USE DONE"),
        "registeredNonBlockItems": air["registeredNonBlockItems"], "distinctItems": len(free_air_rows), "attemptRows": len(rows["itemAirUseFreelook"]),
        "latestStatuses": counter(free_air_rows, "status"), "itemsWithRecordedUseRequest": sorted(free_requested_ids),
        "differencesOrErrors": [r for r in free_air_rows if r.get("status") not in {"MATCHED_AIR_USE_SMOKE", "MATCHED_NO_OBSERVABLE_EFFECT", "MATCHED_AUTO_CONSUMED_BEFORE_USE"}],
        "autoConsumedBeforeUseItems": sorted(r["item"] for r in free_air_rows if r.get("status") == "MATCHED_AUTO_CONSUMED_BEFORE_USE"),
        "missingNonBlockItemIds": sorted({r["id"] for r in census_items if not r.get("blockItem", False)} - {r["item"] for r in free_air_rows}),
        "scope": "Separate fresh all-item air-use comparison against actual FIRST_PERSON freelook (held key, active=false, body/view separated). Default stacks, main hand, at most 40 use ticks. Auto-consumed packages are inventory lifecycle cases with no use sent; all effects remain bounded smoke observations.",
    }
    use_returns = {"SUCCESS", "SUCCESS_NO_ITEM_USED", "CONSUME", "CONSUME_PARTIAL", "PASS", "FAIL"}
    air_freelook["fullSweepRecordedRequestsByMode"] = {
        mode: sum(r.get(column) in use_returns for r in free_air_rows)
        for mode, column in (("first_person", "first_use"), ("freelook", "freelook_use"))}
    air_freelook["fullSweepPairedRequestItems"] = sorted(r["item"] for r in free_air_rows
        if all(r.get(column) in use_returns for column in ("first_use", "freelook_use")))
    air_freelook["fullSweepPreparationErrorRows"] = [r for r in free_air_rows
        if r.get("status") == "ERROR" and not all(r.get(column) in use_returns for column in ("first_use", "freelook_use"))]
    free_done = re.findall(r"^ITEM_AIR_USE DONE completed=(\d+) scheduled=(\d+) differences=(\d+) errors=(\d+)", free_air_segments[-1], re.M) if free_air_segments else []
    air_freelook["reportedDoneCounts"] = dict(zip(("completed", "scheduled", "differences", "errors"), map(int, free_done[-1]))) if free_done else None
    free_isolation = args.qa / "item-air-use/freelook/ability-isolation-methods.txt"
    air_freelook["abilityIsolationMethods"] = file_meta(free_isolation) if free_isolation.exists() and free_isolation.stat().st_mtime >= started - 2 else None
    air_freelook["abilityIsolationEvents"] = [line for segment in free_air_segments for line in segment.splitlines() if "ABILITY_ISOLATION" in line or "WAIT_ACTIVE_ABILITIES" in line]
    free_air_review_rows, free_air_valid_reviews, free_air_review_runs = [], [], []
    free_review_segments = [(prefix, start_line, segment) for prefix in ("ITEM_FREELOOK_REVIEW", "STORMS_EDGE_RECHECK")
                            for start_line, segment in suite_segments(lines, prefix)]
    free_review_segments.sort(key=lambda entry: log.find(entry[1]))
    for review_prefix, start_line, segment in free_review_segments:
        match = re.search(r" file=(.+)$", start_line)
        if not match: continue
        relative = Path(match.group(1).replace("\\", "/"))
        path = relative if relative.is_absolute() else args.qa.parent / relative
        if not path.resolve().is_relative_to((args.qa / "item-air-use").resolve()) or not path.parent.name.startswith("freelook-review-"):
            raise ValueError("Freelook review outside its expected evidence directory: " + str(path))
        candidate_rows, candidate_meta = artifact(path, segment, review_prefix + " START", started, "\t")
        selection_path, manifest_path, source_path = (path.parent / name for name in ("selection.tsv", "selected-items.txt", "source.txt"))
        selection = table(selection_path, "\t") if selection_path.exists() else []
        selected = {r["item"] for r in selection if r.get("prior_status") in {"ERROR", "OBSERVED_DIFFERENCE_REVIEW"}}
        manifest = set(manifest_path.read_text(encoding="utf-8-sig").splitlines()) if manifest_path.exists() else set()
        eligible = selected & manifest
        valid = [r for r in candidate_rows if r.get("item") in eligible]
        free_air_review_rows.extend(candidate_rows); free_air_valid_reviews.extend(valid)
        packet_log, isolation = path.parent / "runtime-packet-errors.log", path.parent / "ability-isolation-methods.txt"
        free_air_review_runs.append({"artifact": candidate_meta, "prefix": review_prefix,
            "completed": completed(segment, review_prefix + " START", review_prefix + " DONE"),
            "restored": completed(segment, review_prefix + " START", review_prefix + " RESTORED"),
            "selectionArtifact": file_meta(selection_path) if selection_path.exists() else None,
            "selectedItemsArtifact": file_meta(manifest_path) if manifest_path.exists() else None,
            "sourceIdentityArtifact": file_meta(source_path) if source_path.exists() else None,
            "selectedItems": sorted(eligible), "selectionManifestMatches": selected == manifest and bool(manifest_path.exists()),
            "unvalidatedSelectionRows": [r for r in candidate_rows if r.get("item") not in eligible],
            "packetErrorArtifact": file_meta(packet_log) if packet_log.exists() else None,
            "abilityIsolationMethods": file_meta(isolation) if isolation.exists() else None,
            "isolationAndDrainEvents": [line for line in segment.splitlines() if any(marker in line for marker in ("ABILITY_ISOLATION", "WAIT_ACTIVE_ABILITIES", "WAIT_MUTATION_DRAIN", "MUTATIONS_DRAINED"))]})
    latest_free_reviews = list({r["item"]: r for r in free_air_valid_reviews if r.get("item")}.values())
    reviewed_free_air = {r["item"]: r for r in free_air_rows + latest_free_reviews}
    free_requested_ids |= {r["item"] for r in latest_free_reviews if any(r.get(key) in {"SUCCESS", "SUCCESS_NO_ITEM_USED", "CONSUME", "CONSUME_PARTIAL", "PASS", "FAIL"} for key in ("first_use", "freelook_use"))}
    air_freelook["itemsWithRecordedUseRequest"] = sorted(free_requested_ids)
    air_freelook["review"] = {"runs": free_air_review_runs, "attempts": free_air_review_rows, "latestRows": latest_free_reviews,
                              "statuses": counter(latest_free_reviews, "status"),
                              "scope": "Separate failed/different-only review. Selection.tsv and selected-items.txt must agree that each merged row was selected from ERROR/OBSERVED_DIFFERENCE_REVIEW. Original full-sweep rows and denominator remain; matching no-ops are not newly selected or promoted to functional passes."}
    air_freelook["latestReviewedStatuses"] = counter(list(reviewed_free_air.values()), "status")
    air_freelook["unresolvedDifferenceRows"] = [r for r in reviewed_free_air.values() if r.get("status") not in {"MATCHED_AIR_USE_SMOKE", "MATCHED_NO_OBSERVABLE_EFFECT", "MATCHED_AUTO_CONSUMED_BEFORE_USE"}]
    original_free_by_id = {r["item"]: r for r in free_air_rows}
    free_air_difference_evidence = []
    for row in air_freelook["unresolvedDifferenceRows"]:
        first, candidate = row.get("first_server", ""), row.get("freelook_server", "")
        explanation = {"item": row["item"], "rawStatus": row["status"], "row": row}
        if row.get("status") == "ERROR" and "runtime_packet_exceptions=[" in first and "runtime_packet_exceptions=[" in candidate:
            same_error = first.split("runtime_packet_exceptions=[", 1)[1] == candidate.split("runtime_packet_exceptions=[", 1)[1]
            explanation.update(category="PAIRED_BASELINE_PACKET_EXCEPTION" if same_error else "DIFFERING_PACKET_EXCEPTIONS_REVIEW",
                scope="Both real request modes recorded runtime packet exceptions. A matching camera-disabled exception is not an established camera-only failure; the gameplay error remains an error.")
        elif row.get("status") == "ERROR" and "Timeout phase=2" in first and not row.get("freelook_use"):
            explanation.update(category="PRE_USE_FIXTURE_NOT_READY", scope="The freelook setup timed out before its use request. Runtime actor position/readiness diagnostics remain separate; this row cannot compare the item's action between modes.")
        else:
            left, right = first.split("|"), candidate.split("|")
            changes = [i for i, (a, b) in enumerate(zip(left, right)) if a != b] if len(left) == len(right) else []
            field = changes[0] if len(changes) == 1 else None
            category = ({1: "SAMPLED_USING_FLAG_ONLY_DIFFERENCE", 3: "PASSIVE_OR_EFFECT_SNAPSHOT_DIFFERENCE", 6: "ENTITY_PRESENCE_OR_LIFETIME_DIFFERENCE"}.get(field)
                if row.get("first_use") == row.get("freelook_use") and row.get("first_screen") == row.get("freelook_screen") else None)
            explanation.update(category=category or "UNCLASSIFIED_OBSERVATION_DIFFERENCE", changedSignatureFields=changes,
                scope="Bounded snapshots differ; this is not automatically a missing use action or camera regression. Timing, persistent effects and entity lifetime remain unverified contextual behavior.")
            original = original_free_by_id.get(row["item"], {})
            old_left, old_right = original.get("first_server", "").split("|"), original.get("freelook_server", "").split("|")
            if field is not None and len(old_left) > field and len(old_right) > field:
                observed = [left[field].rsplit(" -> ", 1)[-1], right[field].rsplit(" -> ", 1)[-1]]
                prior = [old_left[field].rsplit(" -> ", 1)[-1], old_right[field].rsplit(" -> ", 1)[-1]]
                explanation.update(initialObservedValues=prior, latestObservedValues=observed,
                    differenceDirectionReversed=prior[0] != prior[1] and prior == observed[::-1])
        free_air_difference_evidence.append(explanation)
    air_freelook["unresolvedObservationCategories"] = counter(free_air_difference_evidence, "category")
    air_freelook["unresolvedObservationEvidence"] = free_air_difference_evidence
    air_freelook["preparationReadinessDiagnostics"] = [line for line in lines if line.startswith(("ITEM_AIR_USE TIMEOUT_", "ITEM_FREELOOK_REVIEW TIMEOUT_", "STORMS_EDGE_RECHECK TIMEOUT_"))]
    egg_rows = list({(r["item"], r["mode"]): r for r in rows["spawnEggUse"] if r.get("item") and r.get("mode")}.values())
    egg_inventory = args.qa / "spawn-egg-use/registered-items.txt"
    egg_manifest = egg_inventory.exists() and egg_inventory.stat().st_mtime >= started - 2 and "SPAWN_EGG_USE START" in log
    egg_ids = {line.strip() for line in egg_inventory.read_text(encoding="utf-8-sig").splitlines() if re.fullmatch(r"[\w.\-]+:[\w./\-]+", line.strip())} if egg_manifest else set()
    egg_starts = re.findall(r"SPAWN_EGG_USE START eggs=(\d+) scheduledViews=(\d+)", log)
    egg_requested_ids = {r["item"] for r in egg_rows if r.get("use_result") in {"PASS", "FAIL", "SUCCESS", "SUCCESS_NO_ITEM_USED", "CONSUME", "CONSUME_PARTIAL"}}
    egg_positive = [r for r in egg_rows if r.get("status") == "SPAWNED_EXPECTED_ENTITY" and int(r.get("expected_joined") or 0) > 0 and int(r.get("expected_live") or 0) > 0]
    egg_observed_pairs = {(r["item"], r["mode"]) for r in egg_rows}
    egg_packet_errors = args.qa / "spawn-egg-use/runtime-packet-errors.log"
    spawn_eggs = {
        "completed": completed(log, "SPAWN_EGG_USE START", "SPAWN_EGG_USE DONE"),
        "restored": completed(log, "SPAWN_EGG_USE START", "SPAWN_EGG_USE RESTORED"),
        "registeredEggs": int(egg_starts[-1][0]) if egg_starts else None,
        "scheduledViews": int(egg_starts[-1][1]) if egg_starts else None,
        "registeredItemsArtifact": file_meta(egg_inventory) if egg_manifest else None,
        "distinctItems": len({r["item"] for r in egg_rows}), "cases": len(egg_rows), "attemptRows": len(rows["spawnEggUse"]),
        "attempts": rows["spawnEggUse"], "latestRows": egg_rows, "statuses": counter(egg_rows, "status"),
        "comparisons": counter(egg_rows, "comparison"), "itemsWithRecordedUseRequest": sorted(egg_requested_ids),
        "observedExpectedSpawnCases": len(egg_positive),
        "inconsistentPositiveRows": [r for r in egg_rows if r.get("status") == "SPAWNED_EXPECTED_ENTITY" and r not in egg_positive],
        "differencesWithPositiveBaseline": [r for r in egg_rows if r.get("comparison") == "EXPECTED_SPAWN_NOT_REPRODUCED"],
        "errors": [r for r in egg_rows if r.get("status") == "ERROR"],
        "missingItemModes": [{"item": identifier, "mode": mode} for identifier in sorted(egg_ids) for mode in ("first_person", "shoulder", "freelook") if (identifier, mode) not in egg_observed_pairs],
        "packetErrorArtifact": file_meta(egg_packet_errors) if egg_packet_errors.exists() and egg_packet_errors.stat().st_mtime >= started - 2 else None,
        "scope": "Actual default spawn-egg main-hand use on a stone top face in first person, offset and genuine first-person freelook. Positive evidence requires the expected type's captured server join and a live server entity afterward. AI/gravity are frozen after joining; variants, natural spawn rules and long-term behavior remain untested. Setup-only rows and nonpositive first-person controls are not camera-failure proofs."}
    egg_review_runs, egg_review_attempts, egg_valid_reviews = [], [], []
    for start_line, segment in suite_segments(lines, "SPAWN_EGG_RECHECK"):
        match = re.search(r" file=(.+)$", start_line)
        if not match: continue
        relative = Path(match.group(1).replace("\\", "/"))
        path = relative if relative.is_absolute() else args.qa.parent / relative
        if not path.resolve().is_relative_to((args.qa / "spawn-egg-use").resolve()) or not path.parent.name.startswith("review-"):
            raise ValueError("Spawn-egg review outside expected evidence directory: " + str(path))
        review_rows, review_meta = artifact(path, segment, "SPAWN_EGG_RECHECK START", started, "\t")
        selection_path, manifest_path, source_path = (path.parent / name for name in ("selection.tsv", "registered-items.txt", "source.txt"))
        selection = table(selection_path, "\t") if selection_path.exists() else []
        selected = {r["item"] for r in selection if r.get("prior_status") and r["prior_status"] != "SPAWNED_EXPECTED_ENTITY"}
        manifest = set(manifest_path.read_text(encoding="utf-8-sig").splitlines()) if manifest_path.exists() else set()
        eligible = selected & manifest
        validated = [r for r in review_rows if r.get("item") in eligible and r.get("mode") in {"first_person", "shoulder", "freelook"}]
        egg_review_attempts.extend(review_rows); egg_valid_reviews.extend(validated)
        egg_review_runs.append({"artifact": review_meta,
            "completed": completed(segment, "SPAWN_EGG_RECHECK START", "SPAWN_EGG_RECHECK DONE"),
            "restored": completed(segment, "SPAWN_EGG_RECHECK START", "SPAWN_EGG_RECHECK RESTORED"),
            "selectionArtifact": file_meta(selection_path) if selection_path.exists() else None,
            "registeredItemsArtifact": file_meta(manifest_path) if manifest_path.exists() else None,
            "sourceIdentityArtifact": file_meta(source_path) if source_path.exists() else None,
            "selectedItems": sorted(eligible), "selectionManifestMatches": selected == manifest and manifest_path.exists(),
            "unvalidatedRows": [r for r in review_rows if r not in validated],
            "readinessEvents": [line for line in segment.splitlines() if line.startswith("SPAWN_EGG_RECHECK ")]})
    egg_latest_reviews = list({(r["item"], r["mode"]): r for r in egg_valid_reviews}.values())
    reviewed_eggs = {(r["item"], r["mode"]): r for r in egg_rows + egg_latest_reviews}
    spawn_eggs["review"] = {"runs": egg_review_runs, "attempts": egg_review_attempts, "latestRows": egg_latest_reviews,
        "statuses": counter(egg_latest_reviews, "status"),
        "inconsistentPositiveRows": [r for r in egg_latest_reviews if r.get("status") == "SPAWNED_EXPECTED_ENTITY"
            and (int(r.get("expected_joined") or 0) <= 0 or int(r.get("expected_live") or 0) <= 0)],
        "scope": "Separate review of every item with any original nonpositive view, rerunning all three modes with a fresh baseline. Selection and manifest validate eligible IDs; transient multipart blockers are observed and awaited, never bypassed with a forced hit. Original full-sweep rows and denominator remain unchanged."}
    spawn_eggs["latestReviewedStatuses"] = counter(list(reviewed_eggs.values()), "status")
    egg_requested_ids |= {r["item"] for r in egg_latest_reviews if r.get("use_result") in use_returns}
    spawn_eggs["itemsWithRecordedUseRequest"] = sorted(egg_requested_ids)
    spawn_eggs["latestReviewedExpectedSpawnCases"] = sum(r.get("status") == "SPAWNED_EXPECTED_ENTITY"
        and int(r.get("expected_joined") or 0) > 0 and int(r.get("expected_live") or 0) > 0 for r in reviewed_eggs.values())
    spawn_eggs["unresolvedNonpositiveRows"] = [r for r in reviewed_eggs.values() if r.get("status") != "SPAWNED_EXPECTED_ENTITY"
        or int(r.get("expected_joined") or 0) <= 0 or int(r.get("expected_live") or 0) <= 0]
    egg_fixture_evidence = Path("build/exhaustive-qa/spawn-egg-multipart-fixture-evidence.txt")
    if egg_fixture_evidence.exists():
        spawn_eggs["review"]["fixtureEvidence"] = {"artifact": file_meta(egg_fixture_evidence),
            "scope": "Installed multipart/entity behavior motivates observed-blocker readiness. Exact identities of blockers in the original setup-only attempts were not logged or proven; the evidence does not retroactively establish their cause."}
    spawn_eggs["fixtureCleanupEvents"] = [line for line in lines if line.startswith(("EGG_MULTIPART_FIXTURE ", "EGG_WHALE_FIXTURE "))]
    spawn_eggs["review"]["commonRandomFamilyRows"] = [r for r in egg_latest_reviews if r.get("expected_entity", "").startswith("COMMON_RANDOM_FAMILY:")]
    egg_family_evidence = Path("build/compat-audit/jellyfish-spawn-egg-oracle.txt")
    if egg_family_evidence.exists():
        spawn_eggs["review"]["randomFamilyOracleEvidence"] = {"artifact": file_meta(egg_family_evidence),
            "scope": "The default jellyfish egg samples its installed COMMON family on each getType call. An earlier setup sample does not predict the independent use choice. The bounded retry verifies the legitimate family plus actual server join/live observations; it does not claim every random variant was sampled."}
    bucket_rows = list({(r["item"], r["mode"]): r for r in rows["bucketUse"] if r.get("item") and r.get("mode")}.values())
    bucket_inventory = args.qa / "bucket-use/registered-items.txt"
    bucket_manifest = bucket_inventory.exists() and bucket_inventory.stat().st_mtime >= started - 2 and "BUCKET_USE START" in log
    bucket_ids = {line.strip() for line in bucket_inventory.read_text(encoding="utf-8-sig").splitlines() if re.fullmatch(r"[\w.\-]+:[\w./\-]+", line.strip())} if bucket_manifest else set()
    bucket_starts = re.findall(r"BUCKET_USE START items=(\d+) scheduledViews=(\d+)", log)
    bucket_requested_ids = {r["item"] for r in bucket_rows if r.get("client_use") in {"PASS", "FAIL", "SUCCESS", "SUCCESS_NO_ITEM_USED", "CONSUME", "CONSUME_PARTIAL"}}
    bucket_pairs = {(r["item"], r["mode"]) for r in bucket_rows}
    bucket_scope = args.qa / "bucket-use/scope.txt"
    bucket_packet_errors = args.qa / "bucket-use/runtime-packet-errors.log"
    buckets = {
        "completed": completed(log, "BUCKET_USE START", "BUCKET_USE DONE"),
        "restored": completed(log, "BUCKET_USE START", "BUCKET_USE RESTORED"),
        "registeredBucketItems": int(bucket_starts[-1][0]) if bucket_starts else None,
        "scheduledViews": int(bucket_starts[-1][1]) if bucket_starts else None,
        "registeredItemsArtifact": file_meta(bucket_inventory) if bucket_manifest else None,
        "scopeArtifact": file_meta(bucket_scope) if bucket_manifest and bucket_scope.exists() else None,
        "distinctItems": len({r["item"] for r in bucket_rows}), "cases": len(bucket_rows), "attemptRows": len(rows["bucketUse"]),
        "attempts": rows["bucketUse"], "latestRows": bucket_rows, "statuses": counter(bucket_rows, "status"),
        "actions": counter(bucket_rows, "expected_action"), "comparisons": counter(bucket_rows, "comparison"),
        "clientPredictionSummaries": counter(bucket_rows, "client_prediction"),
        "serverEffectWithoutImmediateClientTargetEffect": [r for r in bucket_rows if r.get("status") == "SERVER_EFFECT_CONFIRMED" and "immediateTargetEffect=false" in r.get("client_prediction", "")],
        "delayedClientServerBlockDifferences": [r for r in bucket_rows if "after20TargetMatchesServer=false" in r.get("client_prediction", "") or "after20DecoyMatchesServer=false" in r.get("client_prediction", "")],
        "itemsWithRecordedUseRequest": sorted(bucket_requested_ids),
        "observedServerEffectCases": sum(r.get("status") == "SERVER_EFFECT_CONFIRMED" for r in bucket_rows),
        "differencesWithPositiveBaseline": [r for r in bucket_rows if r.get("comparison") == "BASELINE_EFFECT_NOT_REPRODUCED"],
        "errors": [r for r in bucket_rows if r.get("status") == "ERROR"],
        "missingItemModes": [{"item": identifier, "mode": mode} for identifier in sorted(bucket_ids) for mode in ("first_person", "shoulder", "freelook") if (identifier, mode) not in bucket_pairs],
        "packetErrorArtifact": file_meta(bucket_packet_errors) if bucket_packet_errors.exists() and bucket_packet_errors.stat().st_mtime >= started - 2 else None,
        "scope": "All registered BucketItem subclasses, default stack and main hand, in a stationary overworld basin. Real air-use tests source pickup, fluid placement or expected mob release at the intended target; immediate client prediction, body POV, ordered server effect and observations after twenty server ticks remain separate. A confirmed server effect does not certify client prediction/fallback parity. SolidBucketItem/BlockItem containers and non-BucketItem drinks are outside this denominator; special fluids, dimensions and component variants are untested."}
    bucket_entity_oracle_evidence = []
    for row in bucket_rows:
        if row.get("status") != "EXPECTED_SERVER_EFFECT_NOT_OBSERVED" or not row.get("expected_entity"):
            continue
        entity_pattern = re.escape(row["expected_entity"]) + r":(\d+)=\(([-+\d.eE]+), ([-+\d.eE]+), ([-+\d.eE]+)\)"
        immediate = re.findall(entity_pattern, row.get("server_immediate", ""))
        later = re.findall(entity_pattern, row.get("server_after_20_ticks", ""))
        clicked = re.search(r"BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}", row.get("camera_hit", ""))
        if not immediate or not later or not clicked: continue
        cell = tuple(int(v) for v in clicked.groups())
        wall_top = (cell[0] + .5, cell[1] + 1., cell[2] + .5)
        paired_positions = [(a[0], tuple(float(v) for v in a[1:]), tuple(float(v) for v in b[1:]))
            for a in immediate for b in later if a[0] == b[0]]
        matching_top = [entry for entry in paired_positions if math.dist(entry[1], wall_top) < .001 and math.dist(entry[2], wall_top) < .001]
        if matching_top:
            bucket_entity_oracle_evidence.append({"item": row["item"], "mode": row["mode"], "rawStatus": row["status"],
                "expectedEntity": row["expected_entity"], "clickedBlock": cell, "observedTopPosition": wall_top,
                "sameEntityIdAtBothObservations": [entry[0] for entry in matching_top],
                "interpretation": "EXPECTED_ENTITY_OBSERVED_AT_CLICKED_BLOCK_TOP_OUTSIDE_GENERIC_ADJACENT_TARGET",
                "scope": "The requested entity type exists at both ordered server observations atop the clicked block. The original generic adjacent-cell oracle missed it; this separate observation is not a new execution or full bucket/client-compatibility pass."})
    buckets["expectedEntityOutsideGenericTargetEvidence"] = bucket_entity_oracle_evidence
    bucket_by_item = defaultdict(list)
    for row in bucket_rows: bucket_by_item[row["item"]].append(row)
    buckets["nonpositiveInAllThreeModes"] = [identifier for identifier, checks in bucket_by_item.items()
        if {r["mode"] for r in checks} == {"first_person", "shoulder", "freelook"}
        and all(r.get("status") != "SERVER_EFFECT_CONFIRMED" for r in checks)]
    buckets["defaultStackPacketExceptionRows"] = [r for r in bucket_rows if r.get("status") == "ERROR" and "runtime_packet_exceptions=[" in r.get("limits", "")]
    buckets["sourceOracleArtifacts"] = []
    for path in (Path("build/compat-audit/cataclysm-special-bucket-oracle.txt"), Path("build/compat-audit/bucket-57-nonpass-audit.txt")):
        if path.exists(): buckets["sourceOracleArtifacts"].append(file_meta(path))
    buckets["sourceOracleInterpretations"] = []
    bucket_audit = Path("build/compat-audit/bucket-57-nonpass-audit.txt")
    if bucket_audit.exists():
        top_items = {"cataclysm:modern_remnant_bucket", "cataclysm:netherite_ministrosity_bucket"} | {
            "naturalist:" + name for name in ("ant", "bee", "butterfly", "caterpillar", "crab", "dragonfly", "duck_bucket", "endermite", "firefly", "hedgehog", "rat", "scorpion", "silverfish", "snail")}
        for evidence_row in bucket_entity_oracle_evidence:
            if evidence_row["item"] in top_items:
                buckets["sourceOracleInterpretations"].append({"item": evidence_row["item"], "mode": evidence_row["mode"],
                    "category": "SOURCE_EXPECTED_ENTITY_POSITION_OBSERVED_GENERIC_ORACLE_MISS",
                    "observedEvidence": evidence_row, "sourceArtifact": file_meta(bucket_audit)})
        for row in bucket_rows:
            category = None
            if row["item"] == "minecraft:sulfur_cube_bucket" and row.get("expected_action") == "SOURCE_PICKUP":
                sulfur_at_target = r"minecraft:sulfur_cube:\d+=\(1708\.5, 171(?:\.0)?, 192\.5\)"
                if all(re.search(sulfur_at_target, row.get(column, "")) for column in ("server_immediate", "server_after_20_ticks")):
                    category = "SOURCE_EXPECTED_ENTITY_RELEASE_OBSERVED_GENERIC_PICKUP_ORACLE_MISS"
            elif row["item"] == "sophisticatedcore:xp_bucket" and row.get("client_use") == "FAIL" and row.get("status") == "EXPECTED_SERVER_EFFECT_NOT_OBSERVED":
                category = "SOURCE_HAS_NO_ORDINARY_WORLD_FLUID_BLOCK"
            elif row["item"] == "upgrade_aquatic:jellyfish_bucket" and row.get("status") == "ERROR" and "CustomData.copyTag()" in row.get("limits", ""):
                category = "DEFAULT_STACK_MISSING_REQUIRED_BUCKET_ENTITY_DATA"
            if category:
                buckets["sourceOracleInterpretations"].append({"item": row["item"], "mode": row["mode"], "category": category,
                    "rawStatus": row["status"], "sourceArtifact": file_meta(bucket_audit),
                    "scope": "Source-backed interpretation of the original default-stack observation. Original status is preserved; any corrected-oracle or captured-component execution must be reported separately."})
    buckets["sourceOracleCategories"] = counter(buckets["sourceOracleInterpretations"], "category")
    bucket_review_runs, bucket_review_attempts, bucket_valid_reviews = [], [], []
    for start_line, segment in suite_segments(lines, "BUCKET_RECHECK"):
        match = re.search(r" file=(.+)$", start_line)
        if not match: continue
        relative = Path(match.group(1).replace("\\", "/"))
        path = relative if relative.is_absolute() else args.qa.parent / relative
        if not path.resolve().is_relative_to((args.qa / "bucket-use").resolve()) or not path.parent.name.startswith("review-"):
            raise ValueError("Bucket review outside expected evidence directory: " + str(path))
        review_rows, review_meta = artifact(path, segment, "BUCKET_RECHECK START", started, "\t")
        selection_path, manifest_path, source_path = (path.parent / name for name in ("selection.tsv", "registered-items.txt", "source.txt"))
        selection = table(selection_path, "\t") if selection_path.exists() else []
        selected = {r["item"] for r in selection if r.get("item") != "sophisticatedcore:xp_bucket" and r.get("prior_status")
            and (r["prior_status"] != "SERVER_EFFECT_CONFIRMED" or "after20TargetMatchesServer=false" in r.get("prior_prediction", "")
                 or "after20DecoyMatchesServer=false" in r.get("prior_prediction", ""))}
        manifest = set(manifest_path.read_text(encoding="utf-8-sig").splitlines()) if manifest_path.exists() else set()
        eligible = selected & manifest
        valid = [r for r in review_rows if r.get("item") in eligible and r.get("mode") in {"first_person", "shoulder", "freelook"}]
        bucket_review_attempts.extend(review_rows); bucket_valid_reviews.extend(valid)
        packet_path = path.parent / "runtime-packet-errors.log"
        bucket_review_runs.append({"artifact": review_meta,
            "completed": completed(segment, "BUCKET_RECHECK START", "BUCKET_RECHECK DONE"),
            "restored": completed(segment, "BUCKET_RECHECK START", "BUCKET_RECHECK RESTORED"),
            "selectionArtifact": file_meta(selection_path) if selection_path.exists() else None,
            "registeredItemsArtifact": file_meta(manifest_path) if manifest_path.exists() else None,
            "sourceIdentityArtifact": file_meta(source_path) if source_path.exists() else None,
            "selectedItems": sorted(eligible), "selectionManifestMatches": selected == manifest and manifest_path.exists(),
            "unvalidatedRows": [r for r in review_rows if r not in valid],
            "packetErrorArtifact": file_meta(packet_path) if packet_path.exists() else None,
            "events": [line for line in segment.splitlines() if line.startswith("BUCKET_RECHECK ")]})
    bucket_latest_reviews = list({(r["item"], r["mode"], "captured" if "captured_stack_serialized_by_Bucketable" in r.get("limits", "") else "default"):
                                  r for r in bucket_valid_reviews}.values())
    bucket_captured = [r for r in bucket_latest_reviews if "captured_stack_serialized_by_Bucketable" in r.get("limits", "")]
    buckets["review"] = {"runs": bucket_review_runs, "attempts": bucket_review_attempts, "latestRows": bucket_latest_reviews,
        "statuses": counter(bucket_latest_reviews, "status"), "capturedComponentRows": bucket_captured,
        "defaultStackRows": [r for r in bucket_latest_reviews if r not in bucket_captured],
        "serverEffectWithoutImmediateClientTargetEffect": [r for r in bucket_latest_reviews if r.get("status") == "SERVER_EFFECT_CONFIRMED" and "immediateTargetEffect=false" in r.get("client_prediction", "")],
        "clientServerDifferencesAfter20Ticks": [r for r in bucket_latest_reviews if "after20TargetMatchesServer=false" in r.get("client_prediction", "") or "after20DecoyMatchesServer=false" in r.get("client_prediction", "")],
        "clientServerDifferencesAfter80Ticks": [r for r in bucket_latest_reviews if "after80TargetMatchesServer=false" in r.get("client_prediction", "") or "after80DecoyMatchesServer=false" in r.get("client_prediction", "")],
        "scope": "Separate three-view review of corrected entity position/action oracles and original delayed client mismatches. Captured jellyfish stacks come from the installed Bucketable serializer and remain a distinct component fixture; they never replace default-stack exceptions. Records immediate/20-tick predictions and a further 60 server ticks (80 total). Original full-sweep statuses remain unchanged."}
    bucket_review_by_pair = {(r["item"], r["mode"]): r for r in bucket_latest_reviews if r not in bucket_captured}
    buckets["review"]["originalDelayedMismatchFollowups"] = []
    for original in buckets["delayedClientServerBlockDifferences"]:
        followup = bucket_review_by_pair.get((original["item"], original["mode"]))
        prediction = followup.get("client_prediction", "") if followup else ""
        matches80 = all(flag in prediction for flag in ("after80TargetMatchesServer=true", "after80DecoyMatchesServer=true"))
        buckets["review"]["originalDelayedMismatchFollowups"].append({"item": original["item"], "mode": original["mode"],
            "original": original, "review": followup, "comparison": "CLIENT_BLOCKS_MATCHED_BY_80_TICKS_ON_RETRY" if matches80
                else "CLIENT_BLOCK_DIFFERENCE_PERSISTS_AT_80_TICKS" if "after80" in prediction else "NO_COMPLETE_EXTENDED_OBSERVATION"})
    attempts, latest = latest_action_results(lines)
    for result in attempts: result["source"] = "primary"
    if args.block_qa:
        secondary_attempts, secondary_latest = latest_action_results(block_log.splitlines())
        for result in secondary_attempts: result["source"] = "block"
        attempts.extend(secondary_attempts); latest.extend(secondary_latest)
    comparisons = comparison_results(lines)
    comparisons["source"] = "primary"
    baseline_projectiles = {(row["id"], row["hand"]): row for row in comparisons["projectileBaselines"]}
    melee_rechecks = {row["id"]: row for row in comparisons["meleeRechecks"]}
    for result in latest:
        result["interpretation"] = "OBSERVED_PASS" if result["status"] == "PASS" else "FIXTURE_OR_ORACLE_LIMIT" if result["status"] == "INCONCLUSIVE" else "UNRESOLVED_FAILURE"
        if result["kind"] == "entity_specialized":
            result["interpretation"] = {"PASS": "OBSERVED_SPECIALIZED_ENTITY_ORACLE_PASS", "FAIL": "ENTITY_COMPARISON_FAILURE_REVIEW", "INCONCLUSIVE": "SPECIALIZED_ENTITY_FIXTURE_LIMIT"}[result["status"]]
        if result["kind"] in {"aeroworks_module", "aeroworks_module_freelook_recheck"}:
            result["interpretation"] = {"PASS": "OBSERVED_MODULE_ACTION_ORACLE_PASS", "FAIL": "MODULE_ACTION_FAILURE_REVIEW", "INCONCLUSIVE": "MODULE_FIXTURE_LIMIT"}[result["status"]]
            baseline = next((r for r in latest if r["kind"] == result["kind"] and r["id"] == result["id"]
                             and r["console"] == result["console"] and r["case"] == result["case"]
                             and r.get("repeat") == result.get("repeat")
                             and r["mode"] == "first_person" and r["status"] == "PASS" and r["source"] == result["source"]), None)
            if result["status"] == "FAIL" and baseline and result.get("positiveControl"):
                result["interpretation"] = ("AEROWORKS_FREELOOK_TIMING_REVIEW" if result["kind"] == "aeroworks_module" and result["mode"] == "freelook"
                    else "AEROWORKS_SETTLED_PAIR_FAILURE_REVIEW" if result["kind"] == "aeroworks_module_freelook_recheck"
                    else "CONFIRMED_AEROWORKS_ACTION_COMPARISON_FAILURE")
                result["baselineEvidence"] = {key: baseline[key] for key in ("id", "console", "case", "mode", "status", "line", "source")}
            elif result["status"] == "PASS" and baseline and result["mode"] != "first_person" and result.get("clientResult") != baseline.get("clientResult"):
                result["interpretation"] = "SERVER_MODULE_EFFECT_PASS_LOCAL_RETURN_DIFFERENCE"
        if result["kind"] in {"cardboard_knockback", "cardboard_knockback_recheck"}:
            result["interpretation"] = {"PASS_KNOCKBACK": "OBSERVED_KNOCKBACK_PASS",
                                        "PASS_KNOCKBACK_DIRECTION_REVIEW": "KNOCKBACK_OBSERVED_DIRECTION_REVIEW",
                                        "FAIL_KNOCKBACK": "UNRESOLVED_KNOCKBACK_FAILURE"}[result["status"]]
        if result["kind"] in {"projectile", "projectile_freelook"} and result["status"] == "FAIL":
            baseline = baseline_projectiles.get((result["id"], result["hand"]))
            if baseline and baseline["status"] == "FAIL":
                result["interpretation"] = "REPRODUCES_WITH_CAMERA_DISABLED"
                result["baselineEvidence"] = baseline
        if result["kind"] == "melee" and result["status"] == "FAIL" and result["id"] in melee_rechecks:
            recheck = melee_rechecks[result["id"]]
            result["interpretation"] = {
                "PASS": "INITIAL_FAILURE_NOT_REPRODUCED_WITH_FULL_COOLDOWN",
                "BASELINE_FAIL": "REPRODUCES_WITH_CAMERA_DISABLED",
                "CAMERA_FAIL_REVIEW": "CAMERA_COMPARISON_FAILURE_REVIEW",
                "BASELINE_ONLY_FAILURE_REVIEW": "BASELINE_ONLY_FAILURE_REVIEW",
            }[recheck["status"]]
            result["recheckEvidence"] = recheck
            if result["id"] == "create:cardboard_sword" and recheck["status"] == "BASELINE_FAIL" and Path("build/compat-audit/create-cardboard-sword.txt").exists():
                result["interpretation"] = "EXPECTED_NON_DAMAGE_ITEM_INVALID_HEALTH_ORACLE"
                result["oracleLimitation"] = "Installed Create intentionally cancels damage against the non-arthropod cow fixture; matching zero health damage does not test its knockback. Source/bytecode audit: build/compat-audit/create-cardboard-sword.txt."
        if result["kind"] == "melee_offset" and result["id"] == "create:cardboard_sword" and result["status"] == "FAIL" and result["scope"] != "server knockback without health damage" and Path("build/compat-audit/create-cardboard-sword.txt").exists():
            result["interpretation"] = "EXPECTED_NON_DAMAGE_ITEM_INVALID_HEALTH_ORACLE"
            result["oracleLimitation"] = "The cow fixture is non-arthropod; installed Create intentionally cancels cardboard sword health damage. Separate knockback evidence is required."
        stale_bread_audit = Path("build/exhaustive-qa/stale-bread-custom-damage-evidence.txt")
        if result["kind"] == "melee_offset" and result["id"] == "twilightforest:stale_bread" and result["status"] == "INCONCLUSIVE" and stale_bread_audit.exists():
            result["interpretation"] = "EXPECTED_CUSTOM_DAMAGE_SOURCE_ORACLE_LIMIT"
            result["oracleLimitation"] = "Installed Twilight Forest intentionally substitutes its stale_sandwich damage source. The request and custom damage remain observed, but the strict direct minecraft:player_attack oracle does not certify this case; it is not a camera failure or an automatic pass."
            result["sourceAudit"] = file_meta(stale_bread_audit)
        if result["scope"] == "server knockback without health damage":
            result["interpretation"] = "OBSERVED_KNOCKBACK_PASS" if result["status"] == "PASS" else "KNOCKBACK_FIXTURE_OR_ORACLE_LIMIT" if result["status"] == "INCONCLUSIVE" else "UNRESOLVED_KNOCKBACK_FAILURE"
        cardboard_noai_audit = Path("build/exhaustive-qa/cardboard-noai-fixture-evidence.txt")
        if (result["kind"] == "melee_path" and result["id"] == "create:cardboard_sword" and result["status"] == "FAIL"
                and result.get("damageAttribution", {}).get("serverAttackEvents", 0) > 0
                and result.get("damageAttribution", {}).get("canceledAttackEvents", 0) > 0 and cardboard_noai_audit.exists()):
            result["interpretation"] = "INVALID_NOAI_KNOCKBACK_FIXTURE"
            result["oracleLimitation"] = "The paired NoAI cow receives the intentionally canceled attack but its travel path is disabled. Zero physical displacement cannot certify cardboard knockback failure; a separate corrected movement-enabled fixture is required."
            result["sourceAudit"] = file_meta(cardboard_noai_audit)
        if result["kind"] in {"powergrid", "powergrid_freelook"} and result["status"] == "FAIL":
            baseline = next((r for r in latest if r["kind"] == result["kind"] and r["id"] == result["id"]
                             and r.get("mode") == "first-person" and r["status"] == "PASS" and r["source"] == result["source"]), None)
            if baseline and "positiveControl=true" in result["detail"]:
                result["interpretation"] = "CONFIRMED_CLIENT_REJECTION_WITH_SERVER_ENDPOINT" if result.get("serverSelected") and result.get("clientAccepted") is False else "CONFIRMED_CAMERA_COMPARISON_FAILURE_IN_TESTED_SCENE"
            elif result.get("mode") == "first-person":
                result["interpretation"] = "BASELINE_CONTROL_FAILURE"
            elif any(r["kind"] == result["kind"] and r["id"] == result["id"] and r.get("mode") == "first-person" and r["status"] == "FAIL" for r in latest):
                result["interpretation"] = "INCONCLUSIVE_FAILED_FIRST_PERSON_CONTROL"
            iron_audits = [Path("build/compat-audit/powergrid-light-wires.json"), Path("build/compat-audit/powergrid-connector.txt")]
            if (result["id"] == "powergrid:iron_wire" and result.get("fixture", {}).get("block") == "powergrid:wire_connector"
                    and all(p.exists() for p in iron_audits)
                    and "powergrid:iron_wire" not in read_json(iron_audits[0], {}).get("values", [])):
                result["interpretation"] = "EXPECTED_WRONG_TERMINAL_FIXTURE"
                result["fixtureEvidence"] = {"audits": [file_meta(p) for p in iron_audits],
                    "reason": "The fixture's ordinary connector accepts LIGHT_WIRES; the installed tag excludes iron_wire. This rejection does not compare camera compatibility. A heavy connector is needed for the follow-up."}
        if result["kind"] == "create_selection" and result["status"] == "FAIL":
            baseline = next((r for r in latest if r["kind"] == "create_selection" and r["case"] == result["case"]
                             and r["mode"] == "first_person" and r["status"] == "PASS" and r["source"] == result["source"]), None)
            if baseline:
                result["interpretation"] = "CONFIRMED_CLIENT_SELECTION_COMPARISON_FAILURE"
                result["baselineEvidence"] = baseline
        if result.get("integrationFixture", {}).get("grossDistanceMismatch"):
            result["interpretation"] = "INVALID_QA_FIXTURE_DISTANCE"
    melee_path_pairs = []
    for identifier in sorted({r["id"] for r in latest if r["kind"] == "melee_path"}):
        cases = {r.get("mode"): r for r in latest if r["kind"] == "melee_path" and r["id"] == identifier}
        first, offset_case = cases.get("first_person"), cases.get("offset")
        if not first or not offset_case:
            comparison = "PAIR_INCOMPLETE"
        elif first["status"] == offset_case["status"] == "PASS":
            comparison = "BOTH_MODES_OBSERVED_PASS"
        elif first["status"] == "PASS" and offset_case["status"] == "FAIL":
            comparison = "OFFSET_FAILURE_WITH_POSITIVE_FIRST_PERSON_CONTROL"
        elif first["status"] == offset_case["status"] == "FAIL":
            comparison = "INVALID_NOAI_KNOCKBACK_FIXTURE" if first.get("interpretation") == offset_case.get("interpretation") == "INVALID_NOAI_KNOCKBACK_FIXTURE" else "BOTH_MODES_FAILED"
        elif first["status"] == "FAIL" and offset_case["status"] == "PASS":
            comparison = "BASELINE_ONLY_FAILURE_REVIEW"
        else:
            comparison = "PAIRED_ORACLE_OR_FIXTURE_LIMIT"
        pair = {"id": identifier, "comparison": comparison, "firstPerson": first, "offset": offset_case}
        melee_path_pairs.append(pair)
        for original in latest:
            if original["kind"] != "melee_offset" or original["id"] != identifier or original["status"] != "FAIL": continue
            if comparison == "BOTH_MODES_OBSERVED_PASS":
                original["interpretation"] = "INITIAL_OFFSET_FAILURE_NOT_REPRODUCED_IN_PAIRED_DIAGNOSTIC"
            elif comparison == "OFFSET_FAILURE_WITH_POSITIVE_FIRST_PERSON_CONTROL":
                original["interpretation"] = "PAIRED_OFFSET_COMPARISON_FAILURE_REVIEW"
            elif comparison == "BOTH_MODES_FAILED":
                original["interpretation"] = "PAIRED_FIRST_PERSON_CONTROL_ALSO_FAILED"
            elif comparison == "INVALID_NOAI_KNOCKBACK_FIXTURE":
                original["interpretation"] = "PAIRED_KNOCKBACK_FIXTURE_INVALID_FUNCTION_UNCERTIFIED"
            original["pairedDiagnostic"] = {"comparison": comparison,
                                             "firstPersonLine": first["line"] if first else None,
                                             "offsetLine": offset_case["line"] if offset_case else None}
    actual_items = {r.get("heldItemId", r.get("id", "")) for r in latest if r["scope"] != "client targeting/handler" and r.get("subject") != "entity"
                    and r.get("recordedAction") is not False
                    and r.get("interpretation") != "INVALID_QA_FIXTURE_DISTANCE"
                    and (not r["kind"].startswith("aeroworks_module") or r.get("recordedUseRequest"))} - {""}
    action_summary = {
        "attempts": attempts, "latestResults": latest,
        "latestStatuses": dict(Counter(r["status"] for r in latest)),
        "interpretations": dict(Counter(r["interpretation"] for r in latest)),
        "invalidFixtureResults": [r for r in latest if r.get("interpretation") == "INVALID_QA_FIXTURE_DISTANCE"],
        "comparisons": comparisons,
        "specializedActionItemIds": sorted(actual_items & {r["id"] for r in census_items}),
        "actualActionItemIds": sorted((actual_items | requested_air_ids | free_requested_ids | egg_requested_ids | bucket_requested_ids) & {r["id"] for r in census_items}),
        "itemsWithoutRecordedAction": sorted({r["id"] for r in census_items} - actual_items - requested_air_ids - free_requested_ids - egg_requested_ids - bucket_requested_ids),
        "needsFixture": [line for line in lines if "PROJECTILE NEEDS_FIXTURE" in line or "PROJECTILE SKIP" in line],
        "scope": "A passed check proves only its named oracle, item, hand and scene. Raw initial failures remain recorded; successful paired rechecks are separately interpreted. Latest result per explicit case and source instance is used; all attempts retained with separate source attribution. No inference to other abilities or contexts.",
    }
    projectile_latest = [row for row in latest if row["kind"] == "projectile"]
    projectile_skips = {}
    for line in lines:
        match = re.match(r"PROJECTILE SKIP .*?item=(\S+) hand=(\S+)", line)
        if match:
            projectile_skips[match.groups()] = line
    for row in projectile_latest:
        projectile_skips.pop((row["id"], row["hand"]), None)
    scheduled = [r["scheduled"] for r in comparisons["scheduledSuites"] if r["suite"] == "projectiles" and r["kind"] == "projectile"]
    action_summary["projectileSweep"] = {
        "scheduled": scheduled[-1] if scheduled else None,
        "observedResults": len(projectile_latest), "statuses": dict(Counter(r["status"] for r in projectile_latest)),
        "skippedCases": list(projectile_skips.values()),
        "baselineCasesSeparate": len(comparisons["projectileBaselines"]),
    }
    action_summary["physicsStaff"] = {
        "completed": completed(log, "PHYSICS_STAFF START", "PHYSICS_STAFF DONE"),
        "checks": [row for row in latest if row["kind"] == "physics_staff"],
        "scope": "Selection and server drag-session oracles only; bounded kinematic movement does not prove force-driven displacement.",
    }
    action_summary["freelookSuites"] = {
        kind: {"completed": completed(log, prefix + " START", prefix + " DONE"),
               "checks": [row for row in latest if row["kind"] == kind],
               "scope": "Actual FIRST_PERSON freelook candidate, distinct from offset suites; each named client/server oracle retains its own limits."}
        for prefix, kind in (("CREATE_FREELOOK", "create_freelook"), ("CREATE_FREELOOK_RECHECK", "create_freelook_recheck"),
                             ("PHYSICS_STAFF_FREELOOK", "physics_staff_freelook"), ("POWERGRID_FREELOOK", "powergrid_freelook"))
    }
    melee_latest = [r for r in latest if r["kind"] == "melee"]
    action_summary["meleeSweep"] = {
        "scheduled": next((r["scheduled"] for r in reversed(comparisons["scheduledSuites"]) if r["suite"] == "melee" and r["kind"] == "melee"), None),
        "originalLatestStatuses": dict(Counter(r["status"] for r in melee_latest)),
        "itemsWithDamageEvidence": sorted({r["id"] for r in melee_latest if r["status"] == "PASS" and r["scope"] == "server target health"}
                                        | {r["id"] for r in comparisons["meleeRechecks"] if r["status"] == "PASS"}),
        "scope": "Original attack sweep plus successful failed-only paired rechecks. Health damage is not an appropriate oracle for the Create cardboard sword; its separate knockback evidence is required.",
    }
    action_summary["weaponModeSweeps"] = {}
    for prefix, kind in (("MELEE_OFFSET", "melee_offset"), ("PROJECTILE_FREELOOK", "projectile_freelook")):
        checks = [r for r in latest if r["kind"] == kind]
        skipped = {}
        for line in lines:
            match = re.match(prefix + r" SKIP .*?item=(\S+) hand=(\S+)", line)
            if match: skipped[match.groups()] = line
        for row in checks: skipped.pop((row["id"], row["hand"]), None)
        action_summary["weaponModeSweeps"][kind] = {
            "completed": completed(log, prefix + "_SUITE START", prefix + "_SUITE DONE"),
            "restored": completed(log, prefix + "_SUITE START", prefix + "_SUITE RESTORED"),
            "scheduled": next((r["scheduled"] for r in reversed(comparisons["scheduledSuites"]) if r["kind"] == kind), None),
            "checks": checks, "statuses": counter(checks, "status"), "skippedCases": list(skipped.values()),
            "oracleCounts": counter(checks, "oracle") if kind == "melee_offset" else {},
            "inconclusiveCases": [r for r in checks if r["status"] == "INCONCLUSIVE"],
            "inconsistentAttributedPasses": [r for r in checks if r["status"] == "PASS" and r.get("oracle") == "server_attack_and_direct_player_attack_damage"
                                              and not (r.get("damageAttribution", {}).get("serverAttackEvents", 0) > 0
                                                       and r.get("damageAttribution", {}).get("attributedDamageEvents", 0) > 0
                                                       and r.get("damageAttribution", {}).get("attributedPlayerAttackDamage", 0) > 0)],
            "factoryAuditArtifact": file_meta(Path("build/compat-audit/projectile-factory-audit.txt")) if kind == "projectile_freelook" and Path("build/compat-audit/projectile-factory-audit.txt").exists() else None,
            "inconsistentCustomSourcePasses": [r for r in checks if r["status"] == "PASS" and r.get("oracle") == "server_attack_and_exact_stale_sandwich_source"
                                               and not (r["id"] == "twilightforest:stale_bread"
                                                        and r.get("damageAttribution", {}).get("serverAttackEvents", 0) > 0
                                                        and r.get("damageAttribution", {}).get("attributedCustomSourceEvents", 0) > 0
                                                        and r.get("damageAttribution", {}).get("attributedCustomSourceDamage", 0) > 0
                                                        and r.get("damageAttribution", {}).get("allowedCustomSource") == "twilightforest:stale_sandwich")],
            "invocations": [r for r in comparisons["scheduledSuites"] if r["kind"] == kind],
            "defaultStackMigrations": [{"registeredItem": r["id"], "actualHeldItem": r["actualHeldItemId"], "status": r["status"], "line": r["line"]}
                                       for r in checks if r.get("actualHeldItemId") and r["actualHeldItemId"] != r["id"]],
            "scope": "Separate real action sweep in the named camera mode. Offset melee requires exact-victim server attack receipt plus directly attributed minecraft:player_attack damage in that server tick; the explicit stale-bread exception instead attributes the exact actor/direct-actor/victim/attack-tick stale_sandwich source, and cardboard uses canceled attack plus physical knockback at unchanged health. Other contamination/custom-source limits remain inconclusive. An explicit resumed inventory preserves earlier PASS IDs and reports its pending count separately from the full denominator. Original interrupted attempts remain. Projectile scope is best initial direction, not every launch or later impact."}
    path_segment = log[log.rfind("MELEE_PATH_SUITE START"):] if "MELEE_PATH_SUITE START" in log else ""
    path_audit = Path("build/exhaustive-qa/non-bettercombat-melee-path-evidence.txt")
    action_summary["meleePathDiagnostic"] = {
        "completed": completed(log, "MELEE_PATH_SUITE START", "MELEE_PATH_SUITE DONE"),
        "restored": completed(log, "MELEE_PATH_SUITE START", "MELEE_PATH_SUITE RESTORED"),
        "scheduled": next((r["scheduled"] for r in reversed(comparisons["scheduledSuites"]) if r["kind"] == "melee_path"), None),
        "checks": [r for r in latest if r["kind"] == "melee_path"], "pairs": melee_path_pairs,
        "statuses": counter([r for r in latest if r["kind"] == "melee_path"], "status"),
        "comparisons": counter(melee_path_pairs, "comparison"),
        "traces": [line for line in path_segment.splitlines() if line.startswith(("MELEE_PATH ", "MELEE_PATH_SUITE "))],
        "selectionEvents": [line for line in lines if line.startswith("MELEE_PATH DIAGNOSTIC_SELECTION ")],
        "sourceAudit": file_meta(path_audit) if path_audit.exists() else None,
        "scope": "Separate failed-only first-person/offset diagnostic. Exact client input/cancellation and target/held-state traces accompany server attack/damage attribution. Original 320-item sweep statuses and interrupted attempts remain unchanged. A missing request or invalid baseline is not an automatic camera defect; paired outcomes certify only the named attack fixture."}
    action_summary["freelookAbility"] = {
        "completed": completed(log, "ABILITY_FREELOOK_SUITE START", "ABILITY_FREELOOK_SUITE DONE"),
        "restored": completed(log, "ABILITY_FREELOOK_SUITE START", "ABILITY_FREELOOK_SUITE RESTORED"),
        "checks": [r for r in latest if r["kind"] in {"ability_freelook", "ping_freelook"}],
        "scope": "Separate genuine held FIRST_PERSON freelook Magispear ability-key/server-target and server-echoed entity ping fixtures. Does not certify all Simply Swords abilities or ping contexts."}
    ability_pairs = [r for r in latest if r["kind"] == "ability_pair"]
    ability_modes = {r["mode"]: r for r in ability_pairs}
    ability_pair_segment = log[log.rfind("ABILITY_PAIR_SUITE START"):] if "ABILITY_PAIR_SUITE START" in log else ""
    ability_preflights = {}
    for line in ability_pair_segment.splitlines():
        match = re.match(r"^ABILITY_PAIR_DETAIL SIMPLY_SWORDS_ABILITY (PREFLIGHT_CLIENT|PREFLIGHT_SERVER|INPUT_OBSERVED) mode=(first_person|freelook) (.*)$", line)
        if not match: continue
        stage, mode, detail = match.groups()
        values = dict(re.findall(r"(?:^|\s)([A-Za-z][A-Za-z0-9]*)=([^ ]+)", detail))
        effects = re.search(r"\beffects=(.*?) (?=(?:clientTick|active)=)", detail)
        if effects: values["effects"] = effects.group(1)
        ability_preflights.setdefault(mode, {})[stage] = {"values": values, "line": line}
    ability_ready = {}
    for mode in ("first_person", "freelook"):
        observed = ability_preflights.get(mode, {})
        client = observed.get("PREFLIGHT_CLIENT", {}).get("values", {})
        server = observed.get("PREFLIGHT_SERVER", {}).get("values", {})
        key = observed.get("INPUT_OBSERVED", {}).get("values", {})
        ability_ready[mode] = (all(client.get(k) == v for k, v in {"keyDown": "false", "handlerPressed": "false", "awakened": "true", "keyUpServerTicks": "25", "clientCooldown": "false", "held": "simplyswords:magispear"}.items())
            and all(server.get(k) == v for k, v in {"held": "simplyswords:magispear", "vanillaCooldown": "false", "abilityCooldown": "false", "incapacitated": "false", "active": "false"}.items())
            and key.get("keyDown") == "true" and key.get("handlerPressed") == "true")
    ability_contexts = [{k: ability_preflights.get(mode, {}).get("PREFLIGHT_SERVER", {}).get("values", {}).get(k)
        for k in ("held", "damage", "vanillaCooldown", "abilityCooldown", "incapacitated", "effects", "active")}
        for mode in ("first_person", "freelook")]
    ability_contexts_match = all(ability_ready.values()) and ability_contexts[0] == ability_contexts[1]
    ability_pair_interpretation = "INCOMPLETE_OR_LIMITED_PAIR"
    if set(ability_modes) == {"first_person", "freelook"} and ability_contexts_match:
        if all(r["status"] == "PASS" and r.get("exactTarget") for r in ability_pairs):
            ability_pair_interpretation = "INITIAL_FREELOOK_FAILURE_NOT_REPRODUCED_IN_PAIRED_REQUESTS"
        elif ability_modes["first_person"]["status"] == "FAIL":
            ability_pair_interpretation = "BASELINE_FAILURE_REQUIRES_REVIEW"
        elif ability_modes["first_person"].get("exactTarget") and ability_modes["freelook"]["status"] == "FAIL":
            ability_pair_interpretation = "FREELOOK_FAILURE_WITH_POSITIVE_PAIRED_CONTROL"
    action_summary["abilityPairedRecheck"] = {
        "completed": completed(log, "ABILITY_PAIR_SUITE START", "ABILITY_PAIR_SUITE DONE"),
        "restored": completed(log, "ABILITY_PAIR_SUITE START", "ABILITY_PAIR_SUITE RESTORED"),
        "checks": ability_pairs, "comparison": ability_pair_interpretation,
        "preflightEvidence": ability_preflights, "readinessByMode": ability_ready, "matchedPreflightContexts": ability_contexts_match,
        "traces": [line for line in ability_pair_segment.splitlines() if line.startswith("ABILITY_PAIR")],
        "scope": "Separate real Magispear ability-key requests in first person and actual freelook, with exact selected/expected server UUID and input/readiness traces. Original ability and ping results remain separate; this bounded pair does not certify other abilities or prove the cause of any earlier failure."}
    ability_key_evidence = Path("build/exhaustive-qa/magispear-ability-key-evidence.txt")
    if ability_key_evidence.exists():
        action_summary["abilityPairedRecheck"]["inputPathEvidence"] = {"artifact": file_meta(ability_key_evidence),
            "scope": "Installed key-handler and ability-start code grounds the paired fixture's readiness checks; dynamic results and valid matched preflights are still required for interpretation."}
    action_summary["integrationRecheck"] = {
        "completed": completed(log, "INTEGRATION_RECHECK_SUITE START", "INTEGRATION_RECHECK_SUITE DONE"),
        "restored": completed(log, "INTEGRATION_RECHECK_SUITE START", "INTEGRATION_RECHECK_SUITE RESTORED"),
        "checks": [r for r in latest if r["kind"] == "integration_recheck"],
        "scope": "Separate standard-scene retry after the invalid distant fixture. Serializes server futures and packet acknowledgements, verifies actual held stacks and nearby target range, and establishes the fixture camera directly each tick. Geometry checks remain distinct from server actions; this does not replace either registry weapon sweep."}
    action_summary["entitySpecialized"] = {
        "completed": completed(log, "ENTITY_SPECIALIZED START", "ENTITY_SPECIALIZED DONE"),
        "restored": completed(log, "ENTITY_SPECIALIZED START", "ENTITY_SPECIALIZED RESTORED"),
        "checks": [r for r in latest if r["kind"] == "entity_specialized"],
        "scope": "Straddleboard: exact board UUID remains mounted immediately and after at least five server ticks. Giant/bone serpent: real server request receipt and target-family/reference geometry only, not arbitrary entity functionality."}
    aero_log = block_log if "AEROWORKS_MODULE START" in block_log else log
    aero_start = re.findall(r"AEROWORKS_MODULE START modules=(\d+) consoles=(\d+) cases=(\d+)", aero_log)
    aero_latest_attempt = aero_log[aero_log.rfind("AEROWORKS_MODULE START"):] if "AEROWORKS_MODULE START" in aero_log else ""
    action_summary["aeroworksModules"] = {
        "completed": completed(aero_log, "AEROWORKS_MODULE START", "AEROWORKS_MODULE DONE"),
        "restored": completed(aero_log, "AEROWORKS_MODULE START", "AEROWORKS_MODULE RESTORED"),
        "scheduled": int(aero_start[-1][2]) if aero_start else None,
        "modules": int(aero_start[-1][0]) if aero_start else None, "consoles": int(aero_start[-1][1]) if aero_start else None,
        "checks": [r for r in latest if r["kind"] == "aeroworks_module"],
        "recordedUseRequests": sum(bool(r.get("recordedUseRequest")) for r in latest if r["kind"] == "aeroworks_module"),
        "restorationEvidence": [line for line in aero_latest_attempt.splitlines() if line.startswith(("AEROWORKS_MODULE RESTORED", "AEROWORKS_MODULE NOT_RESTORED"))],
        "scope": "Separate default-module attach and Create-wrench removal fixtures on four stationary console bases in first person, offset and actual first-person freelook. Sent requests are counted separately from scheduled/setup-only cases. Exact server module/socket or empty-console/item-return oracle. Removal is independently seeded; offhand fallback, steering, channels, moving consoles and component variants remain untested. The older ray-helper result is separate evidence."}
    aero_review_prefix = "AEROWORKS_MODULE_FREELOOK_RECHECK"
    aero_review_log = block_log if aero_review_prefix + " START" in block_log else log
    aero_review_starts = re.findall(aero_review_prefix + r" START modules=(\d+) consoles=(\d+) cases=(\d+)", aero_review_log)
    aero_review_last = aero_review_log[aero_review_log.rfind(aero_review_prefix + " START"):] if aero_review_starts else ""
    aero_review_checks = [r for r in latest if r["kind"] == "aeroworks_module_freelook_recheck"]
    aero_review_groups = defaultdict(list)
    for row in aero_review_checks:
        aero_review_groups[row["id"], row["console"], row["case"]].append(row)
    aero_review_comparisons = []
    for (identifier, console, operation), checks in aero_review_groups.items():
        by_pair = {(r.get("repeat"), r["mode"]): r for r in checks}
        all_pairs = {(repeat, mode) for repeat in (1, 2, 3) for mode in ("first_person", "freelook")}
        complete_pairs = set(by_pair) == all_pairs
        valid = complete_pairs and all(r.get("recordedUseRequest") and (r.get("settledPlayerTicks") or 0) >= 3 for r in checks)
        positive_controls = valid and all(by_pair[repeat, "first_person"]["status"] == "PASS"
            and by_pair[repeat, "first_person"].get("serverEffect") for repeat in (1, 2, 3))
        free_success = positive_controls and all(by_pair[repeat, "freelook"]["status"] == "PASS"
            and by_pair[repeat, "freelook"].get("serverEffect") for repeat in (1, 2, 3))
        interpretation = ("INITIAL_FREELOOK_FAILURE_NOT_REPRODUCED_IN_THREE_SETTLED_PAIRS" if free_success else
            "SETTLED_FREELOOK_FAILURE_REMAINS_FOR_REVIEW" if positive_controls else
            "INCOMPLETE_OR_LIMITED_SETTLED_REVIEW")
        aero_review_comparisons.append({"id": identifier, "console": console, "operation": operation,
            "completeThreePairs": complete_pairs, "recordedSettledRequests": valid,
            "positiveControls": bool(positive_controls), "interpretation": interpretation,
            "checks": checks})
        for original in action_summary["aeroworksModules"]["checks"]:
            if (original["id"], original["console"], original["case"], original["mode"]) == (identifier, console, operation, "freelook"):
                original["settledReviewInterpretation"] = interpretation
    action_summary["aeroworksModules"]["freelookRecheck"] = {
        "completed": completed(aero_review_log, aero_review_prefix + " START", aero_review_prefix + " DONE"),
        "restored": completed(aero_review_log, aero_review_prefix + " START", aero_review_prefix + " RESTORED"),
        "scheduled": int(aero_review_starts[-1][2]) if aero_review_starts else None,
        "checks": aero_review_checks, "attempts": [r for r in attempts if r["kind"] == "aeroworks_module_freelook_recheck"],
        "statuses": counter(aero_review_checks, "status"), "pairedComparisons": aero_review_comparisons,
        "restorationEvidence": [line for line in aero_review_last.splitlines() if line.startswith((aero_review_prefix + " RESTORED", aero_review_prefix + " NOT_RESTORED"))],
        "scope": "Failed-only review of completed original freelook nonpasses with matching positive first-person controls. Three separately preserved first-person/freelook pairs per module/console/operation. Requires body/pitch readiness then three actual player ticks, observes server head/view/socket without writing candidate head yaw. Original full-sweep statuses and denominator remain unchanged; a successful settled retry does not prove the cause of an earlier failure or certify untested workflows."}
    aero_head_evidence = Path("build/compat-audit/aeroworks-head-ray-timing.txt")
    if aero_head_evidence.exists():
        action_summary["aeroworksModules"]["headRayTimingEvidence"] = {"artifact": file_meta(aero_head_evidence),
            "scope": "Installed AeroWorks uses a server view vector that consumes head yaw; packet acknowledgement can precede the player tick updating head yaw. Original rows did not measure that interval, so stale head is a hypothesis until paired actual-tick retries. Generic hit-bearing block use has a different readiness requirement."}
    cardboard_rows, cardboard_meta = artifact(args.qa / "cardboard-knockback.tsv", log, "CARDBOARD_KNOCKBACK START", started, "\t")
    if cardboard_meta["available"]:
        evidence["cardboardKnockback"] = {**cardboard_meta, "source": "primary"}
    action_summary["cardboardKnockback"] = {
        "completed": completed(log, "CARDBOARD_KNOCKBACK START", "CARDBOARD_KNOCKBACK DONE"),
        "rows": cardboard_rows, "statuses": counter(cardboard_rows, "status"),
        "scope": "Create intentionally cancels health damage to the cow. Pass requires server attack receipt, canceled damage, unchanged health and measured displacement/impact velocity. Direction-review status still requires investigation of knockback direction.",
    }
    cardboard_recheck, cardboard_recheck_meta = artifact(args.qa / "cardboard-knockback-recheck.tsv", log, "CARDBOARD_KNOCKBACK_RECHECK START", started, "\t")
    if cardboard_recheck_meta["available"]:
        evidence["cardboardKnockbackRecheck"] = {**cardboard_recheck_meta, "source": "primary"}
    action_summary["cardboardKnockback"]["recheck"] = {
        "completed": completed(log, "CARDBOARD_KNOCKBACK_RECHECK START", "CARDBOARD_KNOCKBACK_RECHECK DONE"),
        "rows": cardboard_recheck, "statuses": counter(cardboard_recheck, "status"),
        "scope": "Separate fixture retry. The original aborted before an attack request and remains recorded as an incomplete setup, not a zero-knockback result."}
    latest_cardboard = list({r["mode"]: r for r in cardboard_rows + cardboard_recheck if r.get("mode")}.values())
    action_summary["cardboardKnockback"]["latestObservedRows"] = latest_cardboard
    action_summary["cardboardKnockback"]["latestStatuses"] = counter(latest_cardboard, "status")
    action_summary["createSelection"] = {
        "completed": completed(log, "CREATE_SELECTION START", "CREATE_SELECTION DONE"),
        "checks": [r for r in latest if r["kind"] == "create_selection"],
        "scope": "Actual Create client handlers: quill tick with temporary input-predicate routing (not native GLFW input), and fresh worldshaper material component selection. No schematic export or worldshaper edits sent.",
    }
    family_counts = {}
    for key, id_field, status_field in (("items", "item", "camera_packet_status"), ("blocks", "block", "status"),
                                         ("blockStates", "block", "status"), ("blockInteractions", "block", "status"),
                                         ("blockFreelookStates", "block", "status"), ("blockFreelookActions", "block", "status"),
                                         ("itemAirUseFreelook", "item", "status")):
        grouped = defaultdict(Counter)
        for row in rows[key]:
            grouped[namespace(row.get(id_field))][row.get(status_field, "MISSING")] += 1
        family_counts[key] = grouped
    family_counts["reviewedItemAirUse"] = defaultdict(Counter)
    for row in reviewed_air.values():
        family_counts["reviewedItemAirUse"][namespace(row.get("item"))][row.get("status", "MISSING")] += 1
    family_rows = []
    namespace_counts = census.get("namespaceCounts", {})
    for name in sorted(family & set(namespace_counts)):
        counts = namespace_counts[name]
        paths, geometry, interactions, states = (family_counts[key][name] for key in ("items", "blocks", "blockInteractions", "blockStates"))
        family_rows.append({"namespace": name, **counts,
                            "itemHookCases": paths.total(), "itemHookStatuses": dict(sorted(paths.items())),
                            "blockTargetingRows": geometry.total(), "blockTargetingStatuses": dict(sorted(geometry.items())),
                            "blockStateRows": states.total(), "blockStateStatuses": dict(sorted(states.items())),
                            "blockInteractionRows": interactions.total(), "blockInteractionStatuses": dict(sorted(interactions.items())),
                            "blockFreelookStateRows": family_counts["blockFreelookStates"][name].total(),
                            "blockFreelookStateStatuses": dict(family_counts["blockFreelookStates"][name]),
                            "blockFreelookInteractionRows": family_counts["blockFreelookActions"][name].total(),
                            "blockFreelookInteractionStatuses": dict(family_counts["blockFreelookActions"][name]),
                            "itemAirUseRows": family_counts["reviewedItemAirUse"][name].total(),
                            "itemAirUseStatuses": dict(family_counts["reviewedItemAirUse"][name]),
                            "itemAirUseFreelookRows": family_counts["itemAirUseFreelook"][name].total(),
                            "itemAirUseFreelookStatuses": dict(family_counts["itemAirUseFreelook"][name])})
    addon_rays = []
    for line in lines:
        match = re.match(r"ADDON_RAY (\S+) (\S+) (PASS|FAIL) (.*)", line)
        if match:
            case, mode, status, detail = match.groups()
            addon_rays.append({"case": case, "mode": mode, "status": status, "detail": detail,
                               "scope": "loaded ray helper; no module operation"})
    latest_rays = {(r["case"], r["mode"]): r for r in addon_rays}
    for key, ray in latest_rays.items():
        baseline = latest_rays.get((ray["case"], "first_person"))
        ray["interpretation"] = "CONFIRMED_RAY_HELPER_MISMATCH" if ray["status"] == "FAIL" and baseline and baseline["status"] == "PASS" else "OBSERVED_" + ray["status"]
    errors = [{"source": name, "line": number, "text": line}
              for name, text in logs.items() for number, line in enumerate(text.splitlines(), 1)
              if re.search(r"\b(?:ERROR|ABORT|RESTORE_ERROR|SERVER_ERROR|SERVER_PACKET_ERROR|NEEDS_CLEAR_SPACE)\b", line)]
    prior_primary = Path("build/exhaustive-qa/primary-pre-resume-latest.log.gz")
    console_sources, excluded_console_sources = console_history(args.qa.parent / "logs", started, "primary",
        [(prior_primary, "pre-resume")] if "QA_PROCESS ABORT primary_client_orderly_exit" in log else [])
    if args.block_qa:
        prior_secondary = Path("build/exhaustive-qa/secondary-pre-restart-latest.log")
        secondary_sources, secondary_excluded = console_history(args.block_qa.parent / "logs", started, "block",
            [(prior_secondary, "pre-restart")] if "QA_PROCESS CONTROLLED_RESTART" in block_log else [])
        console_sources.extend(secondary_sources); excluded_console_sources.extend(secondary_excluded)
    all_console_records = [record for source in console_sources for record in source["records"]]
    unique_console_records = {}
    for record in all_console_records:
        # A current log may retain older lines across a restart. Preserve the
        # original archive's process attribution when the exact record repeats.
        unique_console_records.setdefault((record["source"], record["processIdentity"], record["time"], record["fullRecordSha256"]), record)
    console_records = list(unique_console_records.values())
    runtime_console = {"sources": [{k: v for k, v in source.items() if k != "records"} for source in console_sources],
                       "excludedEarlierArchives": excluded_console_sources,
                       "records": console_records,
                       "duplicateRecordsExcluded": len(all_console_records) - len(console_records),
                       "suppressedPacketExceptions": [r for r in console_records if "Failed to handle packet" in r["text"] and "suppressing error" in r["text"]],
                       "rejectedUseItemPackets": [r for r in console_records if "Rejecting UseItemOnPacket" in r["text"]],
                       "serverLagWarnings": [r for r in console_records if "Can't keep up!" in r["text"]],
                       "scope": "Task-overlapping latest, dated normal rotation archives and preserved copies; earlier task-external archives and debug duplicates excluded. Each record retains source process identity; exact full-record hashes deduplicate copies and DST fold distinguishes the repeated hour. Separate from probe status: matched smoke can coexist with exceptions. Concurrent instances are not performance measurements."}
    block_freelook["customInteractionPathEvidence"] = []
    quiet_use_audit = Path("build/compat-audit/simulated-quiet-use-mixin.txt")
    wheel_audit = Path("build/compat-audit/SimulatedSteeringWheelMixin.javap.txt")
    wheel_row = next((r for r in free_block_rows if r.get("block") == "simulated:steering_wheel"), None)
    if (wheel_row and quiet_use_audit.exists() and wheel_audit.exists()
            and wheel_row.get("status") == "SERVER_ACTION_NOT_OBSERVED"
            and all(wheel_row.get(k) == "SUCCESS" for k in ("first_use", "freelook_use"))
            and all(int(wheel_row.get(k) or 0) == 0 for k in ("first_server_events", "freelook_server_events"))):
        block_freelook["customInteractionPathEvidence"].append({
            "block": wheel_row["block"], "artifacts": [file_meta(quiet_use_audit), file_meta(wheel_audit)],
            "interpretation": "VANILLA_EVENT_ORACLE_DOES_NOT_COVER_QUIET_USE",
            "scope": "Installed Simulated code cancellably intercepts useItemOn and returns a non-null QuietUse result. The wheel starts STEERING_WHEEL_MANAGER and returns SUCCESS through that custom path. Matching zero vanilla RightClickBlock events therefore does not establish missing custom transport. The original row remains limited; specialized wheel movement evidence is separate and this audit is not a new dynamic pass."})
    block_freelook["packetRejectionEvidence"] = []
    for row in free_block_rows:
        if row.get("status") != "SERVER_ACTION_NOT_OBSERVED" or any(int(row.get(key) or 0) != 0 for key in ("first_server_events", "freelook_server_events")):
            continue
        points = [re.search(r":\(([-+\d.eE]+), ([-+\d.eE]+), ([-+\d.eE]+)\)", row.get(key, "")) for key in ("first_hit", "freelook_hit")]
        if not all(points): continue
        points = [tuple(float(x) for x in match.groups()) for match in points]
        matched = []
        for record in runtime_console["rejectedUseItemPackets"]:
            if record["source"] != evidence["blockFreelookActions"]["source"]: continue
            location = re.search(r"Location \(([-+\d.eE]+), ([-+\d.eE]+), ([-+\d.eE]+)\) too far away from hit block BlockPos\{x=1742, y=150, z=193\}", record["text"])
            if location and all(math.dist(point, tuple(float(x) for x in location.groups())) < .0001 for point in points):
                matched.append(record)
        if len(matched) >= 2:
            block_freelook["packetRejectionEvidence"].append({"block": row["block"], "records": matched,
                "interpretation": "MATCHING_VANILLA_PACKET_BOUND_REJECTIONS",
                "scope": "Both recorded views have zero server event receipts and nearly identical hit positions, matching at least two server warnings rejecting those positions. This explains transport rejection, not block functionality. A separate in-cell-surface review preserves the original rejected points."})
    lag_evidence = Path("build/exhaustive-qa/runtime-lag-evidence.txt")
    if lag_evidence.exists() and lag_evidence.stat().st_mtime >= started - 2:
        runtime_console["serverStallEvidence"] = {"artifact": file_meta(lag_evidence), "text": lag_evidence.read_text(encoding="utf-8-sig"),
            "scope": "Selected watchdog/server-thread evidence and nearby fixture timeouts. It describes the observed structure/chunk-load stack without establishing the original trigger or attributing it to the camera mod."}
    shutdown_evidence = Path("build/exhaustive-qa/secondary-shutdown-analysis.txt")
    shutdown_dump = Path("build/exhaustive-qa/secondary-shutdown-thread-dump.txt")
    if shutdown_evidence.exists():
        runtime_console["secondaryShutdownEvidence"] = {"artifact": file_meta(shutdown_evidence),
            "threadDump": file_meta(shutdown_dump) if shutdown_dump.exists() else None,
            "text": shutdown_evidence.read_text(encoding="utf-8-sig"),
            "scope": "Observed prolonged shutdown and sampled ChunkMap unload-rescheduling stack; the specific holder/root cause remains unknown. Only the independently identified secondary PID was stopped after verified checkpoint/restoration. The inferred unload-retry mechanism does not establish a camera defect."}
    final_shutdown_dump = Path("build/exhaustive-qa/modpack-block-test-final-shutdown-thread-dump.txt")
    final_shutdown_events = [line for line in block_log.splitlines() if line.startswith("QA_PROCESS ABORT ")
        and "verified_shutdown_unload_loop_force_stopped" in line and "fixtures_previously_restored=true" in line]
    if final_shutdown_dump.exists() and final_shutdown_events:
        runtime_console["finalSecondaryShutdownEvidence"] = {"threadDump": file_meta(final_shutdown_dump),
            "events": final_shutdown_events,
            "scope": "Final secondary PID 3316 was independently verified and stopped after all its test fixtures had restored, following another shutdown stall. The sampled server stack includes ChunkMap.scheduleUnload/processUnloads inside stopServer. This is a verified shutdown termination, not a clean exit or a new gameplay result; the underlying holder/cause remains unestablished. Copied options bytes are verified separately."}
    air["packetExceptionEvidence"] = []
    compass_audit = Path("build/compat-audit/deep-aether-dungeon-compass.txt")
    for variant in ("bronze", "gold", "silver"):
        identifier = "deep_aether:" + variant + "_compass"
        failures = [r for r in runtime_console["suppressedPacketExceptions"] if "deep_aether.structure.locating" in r["text"]
                    and "aether:" + variant + "_dungeon" in r["text"]]
        if failures:
            original = next((r for r in air_rows if r.get("item") == identifier), None)
            air["packetExceptionEvidence"].append({"item": identifier, "interpretation": "OBSERVED_SUPPRESSED_ITEM_PACKET_EXCEPTION",
                "originalSmokeStatus": original.get("status") if original else None, "records": failures,
                "audit": file_meta(compass_audit) if compass_audit.exists() else None,
                "reason": "Installed Deep Aether DungeonCompass passes a raw structure ResourceKey to Component.translatable; Minecraft rejects it before locating the structure. Matching bounded observations do not prove that the compass function ran."})
    identity = read_json(args.production_identity, {})
    current_manifest = read_json(args.source_manifest, {})
    manifest_mod_files = {r["path"][5:] for r in current_manifest.get("files", []) if r.get("path", "").startswith("mods/")}
    expected_mod_files = {p for p in manifest_mod_files if not Path(p).name.startswith("unlockedcamera-")}
    qa_mod_folder = args.qa.parent / "mods"
    copied_mod_files = {p.relative_to(qa_mod_folder).as_posix() for p in qa_mod_folder.rglob("*") if p.is_file()} if qa_mod_folder.exists() else set()
    loaded_mod_ids = {r["id"] for r in read_json(args.qa / "census/mods.json", [])} if census_valid else set()
    source_verification = read_json(args.source_verification, {}) if args.source_verification else {}
    options_backup = Path("build/exhaustive-qa/primary-interrupted-options.txt")
    copied_options = args.qa.parent / "options.txt"
    secondary_options_backup = Path("build/exhaustive-qa/secondary-interrupted-options.txt")
    secondary_copied_options = args.block_qa.parent / "options.txt" if args.block_qa else None
    interruption_evidence = Path("build/exhaustive-qa/primary-interruption-20261031-204556.txt")
    provenance_metadata = {
        "productionIdentity": identity,
        "productionClassesMatchInstalled": isinstance(identity.get("testedClasses"), int)
        and identity["testedClasses"] > 0 and not identity.get("differences")
        and identity.get("installedClasses") == identity["testedClasses"],
        "currentSourceManifest": {"path": str(args.source_manifest), "source": current_manifest.get("source"),
                                  "capturedAt": current_manifest.get("capturedAt"), "files": len(current_manifest.get("files", [])),
                                  **({"sha256": file_meta(args.source_manifest)["sha256"]} if current_manifest else {})},
        "sourceVerification": source_verification or {"status": "NOT_YET_RECORDED"},
        "copiedModFileMembership": {"sourceManifestFiles": len(manifest_mod_files), "copiedFiles": len(copied_mod_files),
            "matchesManifestExceptCameraOverride": bool(manifest_mod_files) and expected_mod_files == copied_mod_files,
            "missingCopiedFiles": sorted(expected_mod_files - copied_mod_files), "extraCopiedFiles": sorted(copied_mod_files - expected_mod_files),
            "cameraOverride": "Installed camera JAR replaced by development classes; production identity is compared separately.",
            "distantHorizonsLoaded": "distanthorizons" in loaded_mod_ids,
            "cullLeavesJarInCurrentManifest": any("cullleaves" in p.lower() and p.lower().endswith(".jar") for p in manifest_mod_files),
            "note": "File-name membership comparison, not a fresh hash verification. A leftover CullLeaves config is not an installed JAR or evidence of a deliberate runtime exclusion."},
        "runtimeLoadSettings": [load_setting_history(source_log, "QA_LOAD_SETTINGS", name)
                                for name, source_log in logs.items() if "QA_LOAD_SETTINGS APPLIED" in source_log],
        "minimumRuntimeLoadSettings": [load_setting_history(source_log, "MINIMUM_LOAD_SETTINGS", name)
                                       for name, source_log in logs.items() if "MINIMUM_LOAD_SETTINGS START" in source_log],
        "processLifecycle": {"events": [{"source": name, "line": line} for name, source_log in logs.items() for line in source_log.splitlines() if line.startswith(("QA_PROCESS ", "QA_COPY_OPTIONS_RESTORED"))],
                             "interruptionEvidence": file_meta(interruption_evidence) if interruption_evidence.exists() else None,
                             "scope": "The primary process exited orderly during offset melee; its initiating cause is not established, and its earlier live snapshots were lost. The secondary controlled restart follows an intentional drained checkpoint and separate restoration verification. Neither restart is a gameplay pass or proof of a performance improvement. Interrupted attempts remain evidence; explicit resume controllers preserve validated completed cases."},
        "copiedOptionsRestoration": {"backup": file_meta(options_backup),
                                     "current": file_meta(copied_options) if copied_options.exists() else None,
                                     "matchesOriginalCopiedBytes": copied_options.exists() and options_backup.read_bytes() == copied_options.read_bytes(),
                                     "scope": "Only the disposable primary options file was changed for restart. This byte comparison is separate from runtime snapshot restoration and from verification of the original source instance."} if options_backup.exists() else None,
        "secondaryCopiedOptionsRestoration": {"backup": file_meta(secondary_options_backup),
                                              "current": file_meta(secondary_copied_options) if secondary_copied_options.exists() else None,
                                              "matchesOriginalCopiedBytes": secondary_copied_options.exists() and secondary_options_backup.read_bytes() == secondary_copied_options.read_bytes(),
                                              "scope": "Disposable secondary startup options only. Exact saved bytes must be restored after final quit; this is separate from live snapshot restoration and source-instance verification."}
                                             if secondary_copied_options is not None and secondary_options_backup.exists() else None,
        "note": "Production class equality establishes tested build identity, not production fixes. The current source manifest supersedes the earlier 1,261-file snapshot.",
    }
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "certification": "NOT_EXHAUSTIVE_GAMEPLAY_CERTIFICATION",
        "provenance": {"log": str(source).replace("\\", "/"), "startByteOffset": primary_log_meta["startByteOffset"], "endByteOffset": primary_log_meta["endByteOffset"],
                       "currentTaskLines": sum(len(text.splitlines()) for text in logs.values()), "primaryTaskLines": len(lines), "historicLogExcluded": True,
                       "sessionStartApproximate": datetime.fromtimestamp(started, timezone.utc).isoformat(),
                       "artifacts": evidence, "sources": log_sources, "secondaryInstanceVerification": secondary_check,
                       **provenance_metadata},
        "inventory": inventory, "items": items, **block_sections, "entities": entities, "entityActions": entity_actions, "itemAirUse": air,
        "blockFreelookActions": block_freelook, "entityFreelookActions": entity_freelook, "itemAirUseFreelook": air_freelook,
        "spawnEggUse": spawn_eggs,
        "bucketUse": buckets,
        "actualActions": action_summary,
        "createFamily": {"scope": family_audit.get("scope", "Missing static dependency inventory"),
                         "includesOptionalDependencyIntegrations": True, "modIds": sorted(family),
                         "contextualInventoryArtifacts": [file_meta(path) for path in contextual_create_files],
                         "namespaces": family_rows, "addonRayAttempts": addon_rays,
                         "addonRayLatest": list(latest_rays.values())},
        "runtimeErrors": errors, "runtimeConsole": runtime_console, "warnings": warnings,
        "remainingLimits": [
            "No universal proof that every handheld action, component/NBT variant, recipe or block/entity interaction works.",
            "Held-item hook checks do not exercise item use/release, rendered models, equipment ticks or custom ability packets.",
            "Default/state geometry does not validate multiblock arrangements, powered Create machinery, contraption assembly or addon operations.",
            "Generic block actions use an empty main hand; wrench/glue/control fixtures are separately identified.",
            "Entity target checks are separate from default empty-hand server interactions; neither proves every contextual action or variant.",
            "Current-task real action results are limited to their recorded hand, mode, world surface and oracle.",
            "Dedicated-server/multiplayer and moving-sublevel combinations are not inferred from integrated-server tests.",
        ],
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "coverage.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    (args.output / "current-results.log").write_text(log, encoding="utf-8")
    if args.block_qa:
        (args.output / "block-current-results.log").write_text(block_log, encoding="utf-8")
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.write_text(markdown(report, args), encoding="utf-8")
    return report


def markdown(report, args):
    inv = report["inventory"]
    def status_text(statuses):
        return ", ".join(f"{name}: {count:,}" for name, count in statuses.items()) or "No results"
    def coverage(section):
        obj = report[section]
        return f"{obj['rows']:,}/{obj.get('registered') or '?'}"
    out = ["# Complete-registry camera QA — 2026-10-31", "",
           "This is a record of executed checks, **not certification that every gameplay interaction works**. Registry enumeration, camera geometry/hooks and real server actions are reported separately.", "",
           f"Only the current task's log bytes {report['provenance']['startByteOffset']:,}–{report['provenance']['endByteOffset']:,} were read; earlier results were excluded.", "",
           f"Runtime census: **{inv.get('items', '?')} items, {inv.get('blocks', '?')} blocks, {inv.get('blockStates', '?')} block states, {inv.get('entityTypes', '?')} entity types, {inv.get('mods', '?')} loaded mod IDs**. Enumeration alone is not a pass.", "",
           "| Executed check | Coverage | Results |", "| --- | --- | --- |",
           f"| Held-item camera/packet hooks | {report['items']['distinctItems']:,}/{inv.get('items', '?')} items; {report['items']['cases']:,} cases | {status_text(report['items']['statuses'])} |",
           f"| Non-block item air-use/release | {report['itemAirUse']['distinctItems']:,}/{report['itemAirUse']['registeredNonBlockItems']} items | {status_text(report['itemAirUse']['latestStatuses'])} |",
           f"| Actual FP-freelook item air-use/release | {report['itemAirUseFreelook']['distinctItems']:,}/{report['itemAirUseFreelook']['registeredNonBlockItems']} items | {status_text(report['itemAirUseFreelook']['latestStatuses'])} |",
           f"| Spawn-egg block use, three modes | {report['spawnEggUse']['cases']:,}/{report['spawnEggUse']['scheduledViews'] or '?'} cases; {report['spawnEggUse']['distinctItems']} eggs | {status_text(report['spawnEggUse']['statuses'])} |",
           f"| Bucket target use, three modes | {report['bucketUse']['cases']:,}/{report['bucketUse']['scheduledViews'] or '?'} cases; {report['bucketUse']['distinctItems']} items | {status_text(report['bucketUse']['statuses'])} |",
           f"| Default block ray targeting | {coverage('blocks')} blocks | {status_text(report['blocks']['statuses'])} |",
           f"| All registered block-state targeting | {coverage('blockStates')} states | {status_text(report['blockStates']['statuses'])} |",
           f"| Actual FP-freelook all-state targeting | {coverage('blockFreelookStates')} states | {status_text(report['blockFreelookStates']['statuses'])} |",
           f"| Empty-hand block request comparison | {coverage('blockInteractions')} blocks | {status_text(report['blockInteractions']['statuses'])} |",
           f"| Actual FP-freelook block requests | {coverage('blockFreelookActions')} blocks | {status_text(report['blockFreelookActions']['statuses'])} |",
           f"| Default entity targetability | {report['entities']['rows']:,}/{inv.get('entityTypes', '?')} types | {status_text(report['entities']['baselines'])} |",
           f"| Empty-hand entity request comparison | {report['entityActions']['rows']:,}/{inv.get('entityTypes', '?')} types | {status_text(report['entityActions']['statuses'])} |",
           f"| Actual FP-freelook entity requests | {report['entityFreelookActions']['rows']:,}/{inv.get('entityTypes', '?')} types | {status_text(report['entityFreelookActions']['statuses'])} |", "",
           "Offset and freelook are tested as separate supported modes. Actual freelook requires first-person view, the held freelook key and a body/view deflection; the production input handler clears that deflection in third person. No unsupported combined offset/freelook mode is claimed.", "",
           "`NO_OBSERVABLE_EFFECT` is a matching no-op, not proof of the block's functionality. `NEEDS_FIXTURE`, `NEEDS_SPECIALIZED_FIXTURE`, `NO_OUTLINE` and `BASELINE_MISS` are setup/targetability limits. `REFERENCE_OCCLUDED_OR_RANGE` matches an independent clip of the same camera ray. `CONTEXT_DIFFERENCE` records different effects with different hit faces. `OBSERVATION_DIFF`, `TARGETING_FAIL` and errors require investigation; they are not automatically confirmed mod defects.", "",
           f"Named fixture checks recorded {len(report['actualActions']['latestResults'])} distinct latest cases ({status_text(report['actualActions']['latestStatuses'])}), including both client selection and actual server-action oracles. Server-action cases cover {len(report['actualActions']['specializedActionItemIds'])} registered held-item IDs and prove only the specified action/hand/scene. Generic air-use smoke is separate and can match without activating an item's effect. All attempts and details are retained in the JSON report.", ""]
    identity = report["provenance"]["productionIdentity"]
    if report["provenance"]["productionClassesMatchInstalled"]:
        out[6:6] = [f"The tested **{identity['testedClasses']} production classes match the installed `{Path(identity['installedJar']).name}` byte-for-byte** (JAR SHA-256 `{identity['sha256']}`). These are QA additions; this identity comparison does not represent production fixes.", ""]
    manifest = report["provenance"]["currentSourceManifest"]
    out += [f"The current source snapshot records {manifest['files']:,} files. It supersedes the older 1,261-file snapshot; differences already present before this task must not be attributed to QA. End-of-run verification is {'recorded in coverage.json' if report['provenance']['sourceVerification'].get('status') != 'NOT_YET_RECORDED' else 'not yet recorded'}.", ""]
    if report["provenance"]["secondaryInstanceVerification"]:
        secondary = report["provenance"]["sources"]["block"]
        out += [f"Block-state and block-interaction evidence comes from a second isolated instance (`{args.block_qa.as_posix()}`), log bytes {secondary['startByteOffset']:,}–{secondary['endByteOffset']:,}. Its runtime registry IDs/counts and mod versions match the primary instance. Raw logs remain separate. Concurrent runs are compatibility checks, not performance measurements.", ""]
    if report["blockFreelookStates"]["rawHeaderMapping"]:
        out += [report["blockFreelookStates"]["schemaNote"], ""]
    if report["provenance"]["runtimeLoadSettings"]:
        settings = report["provenance"]["runtimeLoadSettings"]
        attempts = [attempt for item in settings for attempt in item["attempts"]]
        out += ["During compatibility checks, disposable clients reduced render distance from eight to four chunks and simulation distance from eight to five; fixture extents remain within 21 blocks. This runtime load adjustment and its restoration are recorded per instance. Queued chunk loads may continue, and these concurrent tests are not performance benchmarks.",
                f"Original runtime-settings attempts: {sum(r['restored'] for r in attempts)} restored, {sum(r['overrideEndedWithCleanProcessExit'] for r in attempts)} ended with a confirmed clean process exit, {sum(r['overrideEndedWithVerifiedShutdownStop'] for r in attempts)} ended after a verified shutdown stop, {sum(r['snapshotLostWithInterruptedProcess'] for r in attempts)} lost with an interrupted process, {sum(r['restorationPending'] for r in attempts)} still pending. A later process cannot restore a lost live snapshot.", ""]
    if report["provenance"]["minimumRuntimeLoadSettings"]:
        settings = report["provenance"]["minimumRuntimeLoadSettings"]
        attempts = [attempt for item in settings for attempt in item["attempts"]]
        out += ["A later disposable-runtime adjustment attempts client render distance two, server view/simulation distance two and unchanged valid client simulation distance five. The log verifies the snapshot and persistence across three actual server ticks; apply errors and pending restoration remain explicit. This does not interrupt already queued chunk deserialization/data-fixer work. No production configuration or mod changes are implied.",
                f"Smaller-radius attempts: {sum(r['applied'] for r in attempts)} applied, {sum(r['restored'] for r in attempts)} restored, {sum(r['overrideEndedWithCleanProcessExit'] for r in attempts)} ended with a confirmed clean process exit, {sum(r['overrideEndedWithVerifiedShutdownStop'] for r in attempts)} ended after a verified shutdown stop, {sum(r['snapshotLostWithInterruptedProcess'] for r in attempts)} lost with an interrupted process, {sum(r['restorationPending'] for r in attempts)} still pending. Final clients can quit at the reduced radius to avoid new chunk demand; that ends a runtime override without falsely claiming live restoration. Fixture restoration and exact copied options bytes are verified separately. A quit request alone does not establish exit.", ""]
    if report["provenance"]["processLifecycle"]["events"]:
        out += ["The primary client exited orderly during offset melee; the cause remains unestablished. Its unfinished invocation and lost live snapshots are retained. An explicit resumed melee inventory preserves earlier validated passes and schedules only missing/nonpassing cases.", ""]
    shutdown = report["runtimeConsole"].get("secondaryShutdownEvidence")
    if shutdown:
        relative = Path(os.path.relpath(shutdown["artifact"]["path"], args.markdown.parent)).as_posix()
        out += [f"The secondary client preserved its checkpoint and verified restoration, then stalled while shutting down. Only its verified process was stopped. The [shutdown evidence]({relative}) distinguishes the sampled unload-retry stack from the inferred mechanism; the exact holder/root cause and any camera involvement remain unestablished.", ""]
    final_shutdown = report["runtimeConsole"].get("finalSecondaryShutdownEvidence")
    if final_shutdown:
        relative = Path(os.path.relpath(final_shutdown["threadDump"]["path"], args.markdown.parent)).as_posix()
        out += [f"After all final fixtures restored, the primary client exited cleanly. The final secondary process (verified PID 3316) again stalled in shutdown and was stopped after the [final thread dump]({relative}) showed the ChunkMap unload loop inside `stopServer`. This final termination is recorded separately from the earlier restart; it is not labeled a clean secondary exit. Both copied options files were then restored and checked byte-for-byte.", ""]
    for instance, key in (("primary", "copiedOptionsRestoration"), ("secondary", "secondaryCopiedOptionsRestoration")):
        options_restore = report["provenance"][key]
        if options_restore:
            out += [f"The disposable {instance} options file {'matches the preserved original copy byte-for-byte' if options_restore['matchesOriginalCopiedBytes'] else 'does not yet match the preserved original copy; restoration is pending'}. This is separate from the unchanged original source instance and from runtime restoration.", ""]
    out += ["## Findings and paired rechecks", ""]
    if report["actualActions"]["invalidFixtureResults"]:
        out += [f"A later generic integration run recorded a target far outside the nearby QA platform ({len(report['actualActions']['invalidFixtureResults'])} affected named results). Those raw failures are retained as invalid-fixture evidence and excluded from action-coverage claims. They do not replace the separate registry projectile/melee sweeps or establish camera defects.", ""]
    findings = [r for r in report["actualActions"]["latestResults"] if r.get("interpretation", "").startswith("CONFIRMED_") and r["kind"] != "aeroworks_module"]
    rays = [r for r in report["createFamily"]["addonRayLatest"] if r.get("interpretation") == "CONFIRMED_RAY_HELPER_MISMATCH"]
    for finding in findings:
        if finding["interpretation"] == "CONFIRMED_CLIENT_REJECTION_WITH_SERVER_ENDPOINT":
            out.append(f"- `{finding['id']}` / {finding.get('mode', '')}: the first-person control passed; the candidate client rejected selection while the server recorded the requested fresh endpoint. This is a client/server disagreement, not evidence that the server could not select the endpoint. Full connection behavior remains untested.")
        else:
            out.append(f"- `{finding['id']}` / {finding.get('mode', finding.get('case', ''))}: first-person positive control passed; the camera-mode comparison failed in the tested scene. {finding['scope']}.")
    for ray in rays:
        out.append(f"- Aeroworks `{ray['case']}` / {ray['mode']}: the loaded ray helper differs from the camera ray while its first-person control passes. This helper check did not operate modules; the separate action suite is reported below.")
    aero = report["actualActions"]["aeroworksModules"]
    aero_failures = Counter((r["console"], r["case"], r["mode"]) for r in aero["checks"] if r.get("interpretation") == "CONFIRMED_AEROWORKS_ACTION_COMPARISON_FAILURE")
    for (console, operation, mode), count in sorted(aero_failures.items()):
        out.append(f"- AeroWorks `{console}` / {operation} / {mode}: {count} module cases failed the paired expected-server-effect comparison after their first-person controls passed. Per-module request/target details remain in coverage.json.")
    out.append(f"- Separate AeroWorks module-operation suite: {'completed and restored' if aero['completed'] and aero['restored'] else 'incomplete or not executed'}; {aero['scheduled'] or '?'} scheduled cases, {aero['recordedUseRequests']} recorded use requests, {status_text(dict(Counter(r['status'] for r in aero['checks'])))}. The older helper-only results remain separate; removal seeds are fixture setup, and other console workflows are untested.")
    aero_free_timing = [r for r in aero["checks"] if r.get("interpretation") == "AEROWORKS_FREELOOK_TIMING_REVIEW"]
    if aero_free_timing:
        out.append(f"- {len(aero_free_timing)} original AeroWorks freelook comparisons remain recorded as failures requiring timing review. This custom operation uses server head/view aim, while the original packet acknowledgement did not establish a subsequent player tick. Stale head is a hypothesis, not an established cause.")
    aero_review = aero["freelookRecheck"]
    if aero_review["checks"] or aero_review["scheduled"] is not None:
        resolved = sum(r["interpretation"] == "INITIAL_FREELOOK_FAILURE_NOT_REPRODUCED_IN_THREE_SETTLED_PAIRS" for r in aero_review["pairedComparisons"])
        out.append(f"- Separate settled AeroWorks freelook review: {'completed and restored' if aero_review['completed'] and aero_review['restored'] else 'incomplete'}; {len(aero_review['checks'])}/{aero_review['scheduled'] or '?'} recorded cases, {status_text(aero_review['statuses'])}; {resolved} original cases did not reproduce in all three settled pairs. Every repeat and its body/head/player-tick evidence remains separate. Original full-sweep rows are unchanged.")
    wrong_terminals = sorted({r["id"] for r in report["actualActions"]["latestResults"] if r.get("interpretation") == "EXPECTED_WRONG_TERMINAL_FIXTURE"})
    if wrong_terminals:
        out.append(f"- {', '.join('`' + identifier + '`' for identifier in wrong_terminals)}: the ordinary Powergrid connector rejects this wire under the installed item tag, including in first person. Those raw failures are fixture limits; a compatible heavy-connector follow-up is required.")
    projectile = report["actualActions"]["projectileSweep"]
    out += [f"- Projectile sweep: {projectile['scheduled'] or '?'} scheduled cases; {status_text(projectile['statuses'])}; {len(projectile['skippedCases'])} skipped. The {projectile['baselineCasesSeparate']} first-person baseline cases are counted separately."]
    for finding in report["actualActions"]["latestResults"]:
        if finding["kind"] in {"projectile", "projectile_freelook"} and finding.get("interpretation") == "REPRODUCES_WITH_CAMERA_DISABLED":
            out.append(f"- `{finding['id']}` / {finding['hand']} / {finding['kind']}: failure also reproduces with the camera disabled; it is not established as a camera regression.")
    for name, suite in report["actualActions"]["weaponModeSweeps"].items():
        out.append(f"- `{name}`: {'completed and restored' if suite['completed'] and suite['restored'] else 'incomplete or not executed'}; {suite['scheduled'] or '?'} scheduled, {status_text(suite['statuses'])}, {len(suite['skippedCases'])} skipped. Earlier weapon-mode results are counted separately.")
    for name in ("freelookAbility", "abilityPairedRecheck", "entitySpecialized", "integrationRecheck", "meleePathDiagnostic"):
        suite = report["actualActions"][name]
        out.append(f"- `{name}`: {'completed and restored' if suite['completed'] and suite['restored'] else 'incomplete or not executed'}; {status_text(dict(Counter(r['status'] for r in suite['checks'])))}. {suite['scope']}")
    ability_pair = report["actualActions"]["abilityPairedRecheck"]
    if ability_pair["comparison"] == "INITIAL_FREELOOK_FAILURE_NOT_REPRODUCED_IN_PAIRED_REQUESTS":
        out.append("- The initial Magispear freelook target failure did not reproduce in the separate first-person/freelook pair: both selected the exact expected server UUID with valid key transitions and matching readiness contexts. The earlier failure remains recorded; its cause is not established.")
    rechecks = report["actualActions"]["comparisons"]["meleeRechecks"]
    out += [f"- Failed-only melee rechecks with full attack cooldown: {status_text(dict(Counter(r['status'] for r in rechecks)))}. An initial failure is rejected only after its paired first-person/freelook checks pass; original attempts remain in the JSON report.",
            f"- Melee health-damage evidence: {len(report['actualActions']['meleeSweep']['itemsWithDamageEvidence'])}/{report['actualActions']['meleeSweep']['scheduled'] or '?'} scheduled items across the original sweep and successful rechecks. Create's cardboard sword intentionally cancels damage against the cow; that health oracle is invalid for this item.",
            f"- Separate cardboard-sword knockback check: {status_text(report['actualActions']['cardboardKnockback']['latestStatuses'])}. The original/retry artifacts remain separate. Direction-review results indicate observed knockback with an unresolved direction comparison.",
            f"- Physics-staff suite: {'completed' if report['actualActions']['physicsStaff']['completed'] else 'incomplete or not executed'}. Selection/session results do not establish force-driven movement.", ""]
    for name, suite in report["actualActions"]["freelookSuites"].items():
        out += [f"- `{name}`: {'completed' if suite['completed'] else 'incomplete or not executed'}; {status_text(dict(Counter(r['status'] for r in suite['checks'])))}. These are separate actual first-person freelook fixtures with the same bounded action oracles."]
    out.append("")
    console = report["runtimeConsole"]
    if console["records"]:
        out += [f"Current-task process logs also record {len(console['suppressedPacketExceptions'])} suppressed packet exceptions and {len(console['serverLagWarnings'])} server-lag warnings. Preserved/current process records remain attributed and deduplicated in coverage.json; matching smoke observations do not erase gameplay exceptions. These logs do not by themselves attribute defects to the camera mod or establish benchmark results.", ""]
        if report["itemAirUse"]["packetExceptionEvidence"]:
            out += [f"Deep Aether compasses ({', '.join('`' + r['item'] + '`' for r in report['itemAirUse']['packetExceptionEvidence'])}) produced an installed-mod exception: a structure ResourceKey was supplied as a translatable-text argument. The server suppressed those packet errors. This is a gameplay exception even if the bounded air-use smoke observations match.", ""]
        if console.get("serverStallEvidence"):
            relative = Path(os.path.relpath(console["serverStallEvidence"]["artifact"]["path"], args.markdown.parent)).as_posix()
            out += [f"The [selected watchdog/server-thread evidence]({relative}) records long ticks in structure/chunk loading, including nearby Naturalist fixture timeouts. Those timeouts are not established camera failures; the captured stack does not identify the original load trigger.", ""]
    diagnostics = report["blockStates"]["stateRayDiagnostics"]
    if diagnostics["rows"]:
        out += [f"The separate state-ray diagnostic explains {len(diagnostics['explainedInitialFailureStates'])} initial failing states by independently matching ordinary ray behavior; {len(report['blockStates']['unresolvedDifferenceRows'])} state differences remain unresolved. Original sweep statuses are preserved above. Matching a vanilla miss is not a functional block pass.", ""]
    block_requests = report["blockInteractions"]
    out += [f"Initial block-request differences: {status_text(block_requests['differenceCategories'])}. These categories describe evidence, not confirmed camera defects.", "",
            block_requests["fixtureCaveat"], ""]
    free_recoveries = report["blockFreelookActions"]["uiPreflightRecovery"]["events"]
    if free_recoveries:
        cleared_keys = sum(" RECOVER " in line and "mode=1" in line and "key=false" in line for line in free_recoveries)
        out += [f"The freelook block sweep records {sum(' RECOVER ' in line for line in free_recoveries)} pre-use input/UI recoveries, including {cleared_keys} observed cleared freelook keys. Recovery re-establishes real held input, closes any open screen and repeats the preflight before sending a request. A screen-free key interruption does not prove a GUI cause. The earlier aborted attempt remains in the evidence; completed pairs were not relabeled or discarded.", ""]
    if report["blockFreelookActions"]["packetRejectionEvidence"]:
        names = ", ".join("`" + r["block"] + "`" for r in report["blockFreelookActions"]["packetRejectionEvidence"])
        out += [f"For {names}, both initial views recorded no server interaction event, and matching server warnings rejected the outline hit position as too far from its owning block. These are packet-bound fixture limits, not evidence of a camera-only failure. The separate review can aim at an explicitly recorded in-cell surface; it does not alter the rejected original rows.", ""]
    if report["blockFreelookActions"]["customInteractionPathEvidence"]:
        out += ["The Simulated steering wheel uses an installed `QuietUse` interception and custom hold manager. Its matching zero vanilla interaction-event counts are an oracle limit; they do not establish that custom transport failed. The original broad row remains limited, and specialized server-movement checks are reported separately.", ""]
    free_review = report["blockFreelookActions"]["recheck"]
    if free_review["latestRows"]:
        out += [f"Separate three-pair freelook block review: {status_text(free_review['statuses'])}; {len(free_review['matchingServerObservationBlocks'])} blocks match all three recorded server comparisons and {len(free_review['specializedFixtureBlocks'])} need specialized fixtures. {len(report['blockFreelookActions']['unresolvedServerObservationRows'])} original server-observation rows and {len(report['blockFreelookActions']['unresolvedClientReturnRows'])} client-return/fallback limitations remain unresolved. Matching no-ops remain no-ops.", ""]
    if block_requests["recheck"]["rows"]:
        out += [f"Separate block-request rechecks: {status_text(block_requests['recheck']['statuses'])}; {len(block_requests['recheck']['matchingServerObservationBlocks'])} blocks matched server observations in all three paired retries, {len(block_requests['recheck']['specializedFixtureBlocks'])} require specialized fixtures, and {len(block_requests['recheck']['mixedFixtureOrRegionBlocks'])} mix matching observations with unequal starting states or explained interaction regions. These mixed cases are limited comparisons, not full passes. Original records remain unchanged.", ""]
        out += [f"Remaining block review cases: {len(block_requests['unresolvedServerObservationRows'])} unexplained server-observation comparisons and {len(block_requests['unresolvedClientReturnRows'])} client-return/fallback limitations (categories can overlap). A comparison difference is not automatically a camera defect.", ""]
        if block_requests["recheck"]["differentInteractionRegionBlocks"]:
            out += [f"The diode differences for {', '.join('`' + identifier + '`' for identifier in block_requests['recheck']['differentInteractionRegionBlocks'])} select different functional regions of the same face. The recorded first-person hit is inside Create's value-setting slot; the shoulder hit is outside and toggles inversion. Installed bytecode and hit-distance calculations explain this context difference; it is not a camera regression finding or validation of the timing UI.", ""]
        if block_requests["recheck"]["localReturnDifferenceBlocks"]:
            out += [f"{len(block_requests['recheck']['localReturnDifferenceBlocks'])} rechecked blocks still returned different client use results despite matching recorded server observations. Those return values can affect vanilla's offhand fallback, which this direct empty-main-hand fixture did not test. These are bounded server-effect comparisons, not full interaction passes.", ""]
    if report["itemAirUse"]["review"]["latestRows"]:
        out += [f"Failed-only item-use rerun: {status_text(report['itemAirUse']['review']['statuses'])}; {len(report['itemAirUse']['unresolvedDifferenceRows'])} initial item-use differences remain unresolved. Matching reruns do not certify all abilities.", ""]
        if report["itemAirUse"]["review"]["autoConsumedBeforeUseItems"]:
            out += [f"{len(report['itemAirUse']['review']['autoConsumedBeforeUseItems'])} default package items consumed themselves during the paired inventory-tick setup before any use request could be sent. These are separately observed lifecycle cases, not passed air-use actions or proof of delivery for packages with contents.", ""]
    free_air = report["itemAirUseFreelook"]
    if free_air["distinctItems"]:
        out += [f"The full freelook air-use table contains {free_air['distinctItems']} item rows, including {len(free_air['fullSweepPairedRequestItems'])} items with recorded requests in both views, {len(free_air['autoConsumedBeforeUseItems'])} packages consumed before use, and {len(free_air['fullSweepPreparationErrorRows'])} preparation-error rows without a complete request pair. Recorded requests include packet errors and no-ops; they are not functional-pass counts.", ""]
    if free_air["review"]["latestRows"]:
        out += [f"Separate failed-only freelook air-use review: {status_text(free_air['review']['statuses'])}; {len(free_air['unresolvedDifferenceRows'])} latest differing/error items remain. Original full-sweep rows and denominator are preserved; bounded matched observations do not certify all abilities.", ""]
        out += [f"Those remaining observations are categorized as {status_text(free_air['unresolvedObservationCategories'])}. Matching packet exceptions reproduce in the camera-disabled control; preparation errors have no complete action pair. Snapshot-only differences remain limits, even when their direction reverses between attempts.", ""]
    bucket = report["bucketUse"]
    if bucket["cases"]:
        out += [f"Bucket server confirmations remain separate from client behavior: {len(bucket['serverEffectWithoutImmediateClientTargetEffect'])} confirmed-server cases lacked an immediate client target effect, and {len(bucket['delayedClientServerBlockDifferences'])} cases had a target/decoy mismatch after twenty server ticks. These are not counted as full client compatibility passes.", ""]
    if bucket["sourceOracleInterpretations"]:
        entity_oracle_misses = sum(r["category"].startswith("SOURCE_EXPECTED_ENTITY_") for r in bucket["sourceOracleInterpretations"])
        out += [f"Source-backed bucket review explains {entity_oracle_misses} original generic-oracle misses with the intended entity observed at the item's expected spawn position or with an entity-release item incorrectly treated as pickup. The XP bucket has no ordinary world-fluid block in this installed version; the default jellyfish bucket throws for missing entity data in all three modes. Original statuses remain unchanged, and corrected-oracle/captured-component tests are separate evidence.", ""]
    if bucket["review"]["latestRows"]:
        followups = bucket["review"]["originalDelayedMismatchFollowups"]
        settled = sum(r["comparison"] == "CLIENT_BLOCKS_MATCHED_BY_80_TICKS_ON_RETRY" for r in followups)
        out += [f"Separate bucket review: {status_text(bucket['review']['statuses'])}, including {len(bucket['review']['capturedComponentRows'])} captured-component cases. It records {len(bucket['review']['serverEffectWithoutImmediateClientTargetEffect'])} immediate target-prediction differences and {len(bucket['review']['clientServerDifferencesAfter20Ticks'])} twenty-tick target/decoy mismatches. {settled}/{len(followups)} original delayed pairs matched both observed blocks by eighty server ticks on retry; {len(bucket['review']['clientServerDifferencesAfter80Ticks'])} review cases retain an eighty-tick mismatch. Default jellyfish exceptions remain recorded separately.", ""]
    egg_review = report["spawnEggUse"]["review"]
    if egg_review["latestRows"]:
        out += [f"Separate spawn-egg review: {status_text(egg_review['statuses'])}; {len(report['spawnEggUse']['unresolvedNonpositiveRows'])} latest nonpositive item/mode cases remain. All three views are rerun for selected eggs with a fresh baseline, and original full-sweep rows remain intact. Transient blockers are awaited without forcing a hit; only expected-type server joins plus live entities count as positive spawn evidence.", ""]
    for name in ("entityActions", "entityFreelookActions"):
        entity = report[name]
        if entity["recheck"]["rows"]:
            out += [f"`{name}` separate cleanup/error recheck: {status_text(entity['recheck']['statuses'])}; {len(entity['unresolvedDifferenceRows'])} latest differences remain unresolved. Matching default empty-hand observations do not validate contextual entity abilities.", ""]
    unfinished = [name for name in ("items", "itemAirUse", "itemAirUseFreelook", "spawnEggUse", "bucketUse", "blocks", "blockStates", "blockFreelookStates", "blockInteractions", "blockFreelookActions", "entities", "entityActions", "entityFreelookActions") if not report[name]["completed"]]
    if unfinished:
        out += [f"**Incomplete or not executed suites:** {', '.join(unfinished)}.", ""]
    out += ["## Create and installed integrations", "",
            "The family below is the installed dependency closure from Create, Simulated and Sable. It deliberately includes **optional-dependency integrations**, so membership does not imply a dedicated Create addon. Runtime namespaces provide the actual item/block denominator.", "",
            "| Namespace | Items | Blocks | Held hook cases | Block ray rows | Block requests offset / FP freelook |",
            "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for row in report["createFamily"]["namespaces"]:
        out.append(f"| {row['namespace']} | {row.get('items', 0)} | {row.get('blocks', 0)} | {row['itemHookCases']} | {row['blockTargetingRows']} | {row['blockInteractionRows']} / {row['blockFreelookInteractionRows']} |")
    contextual = next((r for r in report["createFamily"]["contextualInventoryArtifacts"] if r["path"].endswith(".md")), None)
    if contextual:
        relative = Path(os.path.relpath(contextual["path"], args.markdown.parent)).as_posix()
        out += ["", f"The separate [Create ecosystem inventory and contextual backlog]({relative}) distinguishes content addons from rendering, world-generation and optional integrations. Its implementation groups are a review queue, not executed gameplay passes."]
    out += ["", "## Limits and evidence", ""]
    out += [f"- {line}" for line in report["remainingLimits"]]
    if report["warnings"]:
        out += ["", "Current report warnings:", ""] + [f"- {line}" for line in report["warnings"]]
    out += ["", f"Detailed counts, failure rows, action attempts, missing coverage and source hashes: `{args.output.as_posix()}/coverage.json`. Raw current-task lines: `{args.output.as_posix()}/current-results.log`. Registry/CSV/TSV evidence remains under `{args.qa.as_posix()}`.", ""]
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--qa", type=Path, default=Path("build/modpack-test/game/qa"))
    parser.add_argument("--block-qa", type=Path, help="Optional second instance QA folder; requires matching current census/mod versions")
    parser.add_argument("--block-offset-file", type=Path, help="Secondary log byte offset; defaults to zero for a fresh isolated instance")
    parser.add_argument("--offset-file", type=Path, default=Path("build/exhaustive-qa/results-start-offset.txt"))
    parser.add_argument("--create-audit", type=Path, default=Path("build/compat-audit/create-addons.json"))
    parser.add_argument("--output", type=Path, default=Path("build/exhaustive-qa"))
    parser.add_argument("--markdown", type=Path, default=Path("docs/complete-registry-qa-2026-10-31.md"))
    parser.add_argument("--production-identity", type=Path, default=Path("build/exhaustive-qa/production-identity.json"))
    parser.add_argument("--source-manifest", type=Path, default=Path("build/exhaustive-qa/current-source-manifest.json"))
    parser.add_argument("--source-verification", type=Path, default=Path("build/exhaustive-qa/current-source-verification.json"))
    args = parser.parse_args()
    report = summarize(args)
    print(json.dumps({"report": str(args.output / "coverage.json"), "markdown": str(args.markdown),
                      "currentTaskLines": report["provenance"]["currentTaskLines"],
                      "items": report["items"]["cases"], "blocks": report["blocks"]["rows"],
                      "blockStates": report["blockStates"]["rows"], "blockInteractions": report["blockInteractions"]["rows"],
                      "entities": report["entities"]["rows"], "realActionCases": len(report["actualActions"]["latestResults"])}))


if __name__ == "__main__":
    main()
