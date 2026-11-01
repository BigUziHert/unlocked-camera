# Manual offset-camera session

This runs the latest `unlockedcamera-1.0.3.jar` production code in the disposable
`build/modpack-test/game` pack. Its recording hooks are QA-only additions. They
do not change targeting, inputs, packets, inventories or blocks while recording,
and are absent from the release jar. No server installation is required for the mod.

## What to try

Start with the **offset camera**, which is the priority. Walk up to each labeled
station and click the center, then an edge, from the left and right shoulders.
Repeat the same action in first person for comparison. Allow about four seconds
between attempts when checking button power or a block that appears then vanishes.

1. Try the linked mangrove button several times. Watch whether it presses
   and releases normally. The earlier difference was inconsistent;
   it has not been established as an offset-camera failure.
2. Try all other linked buttons, the mechanical arm, copycat link casing and
   spark gap. Begin with empty hands, then with an item in the offhand. A shield
   makes offhand use visible; a building block shows unintended placement. Compare
   identical hands and target faces between camera modes.
3. At the glue station, put Create Super Glue in the offhand and a building block
   in the main hand. Place next to the existing blocks near their edges/corners.
   Look for placement reverting or sticking without glue. Repeat in first person.
   Main-hand glue is a useful separate comparison.
4. Repeat suspicious interactions in **first-person freelook** while holding
   Left Alt and looking away from the body's facing direction.

The arena is creative: use the inventory to get any additional item you want to
check. These stations focus on the unresolved cases; setting up the arena does
not mean every interaction in the pack has passed a gameplay test.

## Controls

| Control | Action |
| --- | --- |
| F5 | Cycle camera modes; current config enters unlocked camera directly from first person |
| X | Swap shoulder in the unlocked camera |
| Scroll up/down | Zoom in/out in the unlocked camera |
| Hold Left Alt | First-person freelook; release recenters with the current config |
| F | Swap the selected item with the offhand |
| F2 | Save a screenshot |

The current config has full shoulder offset at five blocks of zoom or closer.
Some addons share X/Left Alt; existing bindings are retained so their interactions
are represented in the test.

## Recording and reporting

The passive recorder writes flushed JSON lines under
`build/modpack-test/game/qa/manual-offset/run-*/events.jsonl`. It captures camera
mode and pose, both hands, the targeted face, actual use-call order and returns,
outgoing use packets, integrated-server handling, and subsequent block/inventory/
glue observations. It records use attempts, including repeated attempts from a
held mouse button. `PASS` and `SUCCESS` in this log are Minecraft return values,
**not test verdicts**.

This is an interaction log, not continuous video. F2 screenshots go to the copied
game's `screenshots` folder. Tell me the station/item and what looked wrong, ideally
immediately after taking F2; I can inspect that image and the corresponding log.
For a clean comparison, describe both the offset result and first-person result.

Run `python scripts/summarize-manual-offset.py` to generate `actions.tsv` and
`summary.json` beside the latest recording. The raw events retain precise timing
and state. The recorder has explicit size/action limits and reports if it stops.

## Session maintenance

The arena helper saves the prior player state and touched blocks before setup.
Use its restore command only when finished, because restoration replaces the
temporary test area. The original CurseForge instance is not the test directory.

If a fixture is accidentally broken, the assistant can run
`probe ManualOffsetArenaRepairProbe` to replace only missing fixture blocks,
including detached buttons/signs. Existing blocks, inventory and camera are
preserved. `probe ManualOffsetArenaRepairProbe$Inspect` checks the 677 expected
fixture positions without changing them. Both produce a diagnostic journal.

The assistant starts/stops the recorder through the existing QA request interface
with `probe ManualOffsetRecorder` and `probe ManualOffsetRecorderStop`. Recording
can be restarted without moving you or changing your inventory or camera.

The reproducible launch tools are `scripts/prepare-manual-qa.py` and
`scripts/launch-manual-qa.ps1 -Session <prepared session directory>`. A session
freezes production classes directly from the release jar and verifies their
hashes before launch. Its manifest and options backup are under
`build/manual-offset-qa/<session>/`.
The launcher uses a detached `javaw.exe` GUI process and records its process ID
and log filenames in `latest-launch.json`. After a game restart, press F5 to
return from first person to the offset camera, and restart the recorder.
