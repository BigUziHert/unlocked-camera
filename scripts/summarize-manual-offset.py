"""Summarize recorded human clicks without turning return values into test verdicts."""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("events", nargs="?", type=Path)
args = parser.parse_args()
events = args.events
if events is None:
    candidates = list((ROOT / "build/modpack-test/game/qa/manual-offset").glob("run-*/events.jsonl"))
    if not candidates:
        raise SystemExit("No manual recording exists yet.")
    events = max(candidates, key=lambda path: path.stat().st_mtime)

counts = Counter()
chains, calls, server_links = {}, {}, {}
partial_lines = 0
for line in events.read_text(encoding="utf-8").splitlines():
    try:
        record = json.loads(line)
    except json.JSONDecodeError:
        partial_lines += 1
        continue
    kind, ident, detail = record["type"], record.get("id", 0), record.get("details", {})
    counts[kind] += 1
    if kind == "INPUT_CHAIN_BEGIN":
        chains[ident] = {"chain": ident, "time": record["time"], "client": detail, "calls": []}
    elif kind == "CLIENT_CALL_BEGIN":
        call = {"call": ident, "chain": detail["chain"], "kind": detail["kind"], "hand": detail["hand"],
                "target": detail["requestedHit"], "result": None, "serverPackets": [], "serverBlockEvents": 0,
                "worldTransitions": 0, "glueJoinEvents": 0}
        calls[ident] = call
        if detail["chain"] in chains:
            chains[detail["chain"]]["calls"].append(call)
    elif kind == "CLIENT_CALL_RETURN" and ident in calls:
        calls[ident]["result"] = detail["result"]
        calls[ident]["exception"] = detail["exception"]
    elif kind == "SERVER_PACKET_BEGIN":
        server_links[ident] = detail["clientCall"]
        if detail["clientCall"] in calls:
            calls[detail["clientCall"]]["serverPackets"].append(detail["sequence"])
    elif kind in ("RIGHT_CLICK_BLOCK_BEGIN", "WORLD_TRANSITION", "GLUE_ENTITY_JOIN_EVENT"):
        linked = calls.get(server_links.get(ident, ident))
        if linked is not None:
            if kind == "RIGHT_CLICK_BLOCK_BEGIN" and detail.get("side") == "server":
                linked["serverBlockEvents"] += 1
            elif kind == "WORLD_TRANSITION":
                linked["worldTransitions"] += 1
            elif kind == "GLUE_ENTITY_JOIN_EVENT":
                linked["glueJoinEvents"] += 1

summary = {"events": str(events), "eventCounts": dict(counts), "partialOrMalformedLines": partial_lines,
           "inputChains": list(chains.values()), "unlinkedCalls": [c for c in calls.values() if not c["chain"]],
           "interpretation": "Actual human input observations only. PASS/SUCCESS are API return values, not test verdicts. Overlapping clicks may share observed world changes. Inspect events.jsonl for precise state/timing."}
output = events.with_name("summary.json")
output.write_text(json.dumps(summary, indent=2), encoding="utf-8")
with events.with_name("actions.tsv").open("w", encoding="utf-8", newline="") as file:
    writer = csv.writer(file, delimiter="\t")
    writer.writerow(["time", "chain", "camera", "unlocked", "main", "off", "target", "handResults", "serverPackets"])
    for chain in chains.values():
        client = chain["client"]
        hit = client.get("hit", {})
        writer.writerow([chain["time"], chain["chain"], client.get("cameraType"), client.get("mode", {}).get("unlockedCameraActive"),
                         client.get("main", {}).get("id"), client.get("off", {}).get("id"),
                         json.dumps(hit), "; ".join(f'{c["hand"]} {c["kind"]}: {c["result"]}' for c in chain["calls"]),
                         sum(len(c["serverPackets"]) for c in chain["calls"])])
print(json.dumps({"summary": str(output), "inputChains": len(chains), "calls": len(calls),
                  "eventCounts": dict(counts), "partialOrMalformedLines": partial_lines}))
