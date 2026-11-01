# Passive manual interaction recorder

The recorder changes no game inputs, camera/body pose, held stacks, world blocks,
entities, or outgoing packets. It runs only in a loaded integrated world under
`build/modpack-test/game` or `build/modpack-block-test/game`. Arena setup is a
separate, explicitly invoked helper.

Compile the six sources listed in
`build/exhaustive-qa/manual-offset-recorder-javac.args` with Java 21. They live
entirely under `com.caleb.unlockedcamera.qa`, which the release build excludes.
For the disposable launch only, copy
`src/qa/resources/unlockedcamera.manualqa.mixins.json` into the isolated runtime
resources and add a `[[mixins]]` entry naming it to that runtime's mod metadata.
Never add it to the production mixin config or release jar. The server-handler
mixin is passive instrumentation of the integrated test server; it is not a
production server dependency.

Existing QA request commands:

```text
probe ManualOffsetRecorder
probe ManualOffsetRecorderStop
```

Start writes `qa/manual-offset/run-<milliseconds>/events.jsonl` and logs
`MANUAL_OFFSET_RECORDER START file=...`. Stop flushes/closes the file, reports any
unfinished observation windows, and logs `MANUAL_OFFSET_RECORDER STOPPED`.
Starting/stopping does not pause, close a screen, or restore/change player state.

Each `Minecraft.startUseItem` invocation has an input-chain ID; each actual
`useItemOn`/`useItem` call has a call ID. Both hands and all fallthrough calls are
recorded in their real order. A held use key can produce repeated chains; a chain
is not necessarily a distinct physical mouse press. Local return values are
observations, never automatic test verdicts.

`OUTGOING_USE_PACKET` records the actual packet passed to `Connection.send`
after production substitutions, including sequence and hand. This is a send
observation, not an acknowledgement. `SERVER_PACKET_BEGIN/RETURN` correlates
that sequence with the integrated server handler on its server thread.
`RIGHT_CLICK_BLOCK_*` records event dispatch/cancellation separately, because
custom addon paths may bypass that event. `SERVER_PACKET_RETURN` records the
actual immediately observed state; a normal handler return alone proves no
gameplay success. No extra acknowledgement packets or commands are generated.

Snapshots include camera/body pose, unlocked-camera and real held-freelook
predicates, zoom/shoulder side/configuration, both held stacks and inventory,
the clicked block and its six neighbors, menus, and nearby glue entity IDs,
UUIDs and bounds. The recorder reads the existing continuous-aim cache without
recomputing or changing it. Create glue joins and placement events are logged
separately. Per-tick world-state transitions and snapshots at 1, 2, 5, 10, 20,
40 and 80 ticks show button power changes and placement/glue persistence.
Server samples use actual server ticks; client samples use client ticks and are
not simultaneous with server samples. Other manual actions and scheduled block
ticks can overlap these windows, so proximity/timing alone is not causal proof.

Bounds: 64 concurrent windows per side, 80 ticks per window, 32 listed nearby
glue entities (total count retained), 8,000 characters of each stack's component
description, 2,048 packet associations, 5,000 use calls, 100,000 records, and
200 million log characters. Truncation/limits are explicit. Each JSON line is
flushed. Observation/IO failures do not replace the game's result or exception;
the recorder logs an error or stands down. The normal return and exception from
the wrapped game method are preserved unchanged.
