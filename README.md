# Unlocked Camera
An unlocked camera for **NeoForge 1.21.1**, inspired by the contraption camera in
Create Aeronautics: a fourth perspective in the vanilla view cycle that detaches into
an orbit camera with scroll-wheel zoom, an over-the-shoulder offset up close, and
hold-to-freelook in first person.
## Controls
`F5` (or whatever "Toggle Perspective" is bound to) now cycles through four views:
1. First person
2. Third person (behind)
3. **Unlocked camera** — scroll wheel zooms in / out (smoothed)
4. Third person (front-facing)
While the unlocked camera is active, scrolling zooms instead of switching hotbar
slots. When terrain pulls the camera in, it glides back out smoothly once clear.
**Centered, truthful crosshair:** the crosshair stays fixed at screen center.
While the shoulder offset is engaged, targeting raycasts from the camera through
that center crosshair (validated against your real reach), so whatever sits under
the crosshair is what you mine, hit, or interact with — like an over-the-shoulder
shooter. Switching directly from first person into the shoulder camera keeps
the crosshair visible throughout the camera slide. When zooming in from farther
out, it appears at or below the shoulder-offset zoom limit once the slide is
nearly complete. It stays
visible during shoulder swaps and wall clearance; a config option makes it permanent.
**Shoulder offset:** zoomed in to 4 blocks or closer (configurable), the camera
slides over the shoulder; press `X` (rebindable) to swap sides. The offset clips
against walls so it never pokes through blocks.
**Freelook:** in first person, hold `Left Alt` (rebindable) and move the mouse to
look around without turning your body — movement direction stays put, and the
crosshair stays truthful: mining, placing, and projectiles land where the
crosshair points without your body turning, while other players see your head
follow the deflected view. On release the view eases back to your original
facing; enable `freelookKeepDirection` to keep facing where you were looking
instead — with it on, clicking mid-freelook also snaps your body to the
crosshair.
**Better Third Person compatibility:** the mod never touches camera rotation, so
BTP's free orbit is untouched; targeting through the center crosshair works the
same with or without BTP.
**Just Zoom compatibility:** freelook uses the same processed mouse movement as
normal look, including zoom sensitivity and smoothing. Holding Just Zoom keeps
the unlocked camera behind the player; scrolling adjusts lens magnification.
Lens zoom also composes with Simply Swords' FOV effects in first and third person.
**Better Combat and Simply Swords compatibility:** Better Combat's melee hitbox
aims toward the crosshair without turning the player's body. Simply Swords ability
weapons prealign server aim while equipped so rebound abilities can select the
crosshair target on their first press. This uses the existing once-per-tick aim
cache; ordinary melee weapons do not enable that hold.
**Create Aeronautics compatibility:** while seated in an Aeronautics contraption
(a Sable "sublevel"), this mod steps aside completely — the perspective key behaves
as if the mod weren't installed, so Aeronautics' own contraption camera takes over.
Detection uses Sable's own seated-check via reflection, so there is no hard
dependency on it.
## Config
Settings live in `config/unlockedcamera-client.toml` (also editable in-game via
Mods > Unlocked Camera > Config; changes apply live):
- `toggleMod` — master switch that disables the entire mod when off, default on
- `enableUnlockedCamera` — toggle the fourth perspective on/off, default on
- `disableVanillaThirdPerson` — F5 goes straight from first person to the
  unlocked camera, skipping vanilla third person, default off
- `enableFreelook` — toggle freelook on/off, default on
- `freelookKeepDirection` — on release, face where you were looking instead of
  easing back, default off
- `crosshairAlways` — always draw the crosshair in the unlocked camera,
  default off. It is only aim-corrected while the shoulder offset is engaged;
  zoomed out past the shoulder gate it draws at screen center but aim follows
  the body's facing
- `crosshairOnShoulderOffset` — draw it at or below the shoulder-offset zoom
  limit, preserving it on direct first-person entry and waiting for the shoulder
  slide when zooming in from farther out, default on
- `showEnterMessage` — show the action-bar text when entering the unlocked
  camera, default on
- `enableShoulderOffset` — toggle the over-the-shoulder offset, default on
- `shoulderOffsetAmount` — how far the camera slides over the shoulder in
  blocks (0.5 - 1.0), default 0.75
- `shoulderOffsetMaxZoom` — apply the shoulder offset at or closer than this (easing in from one block above)
  distance in blocks, default 4
- `minZoom` — closest zoom, default 1 block
- `maxZoom` — farthest zoom, default 12 blocks
- `freelookYawLimit` — how far left/right freelook can swing in degrees, default 90
## Development
Requires JDK 21. Gradle uses whatever `JAVA_HOME` points at, so set it per
shell if the system default is older:
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat runClient   # launch a dev client with the mod loaded
.\gradlew.bat build       # build the release jar into build/libs/
```
### Testing a copied modpack

The optional QA driver runs against `build/modpack-test/game`. Prepare it with
PowerShell 7; `-Instance` points to the instance directory containing `mods` and
`config`, and `-TestWorld` points to a disposable Minecraft 1.21.1 world:

```powershell
pwsh -File .\scripts\prepare-modpack-qa.ps1 `
  -Instance "C:\path\to\instance" `
  -TestWorld "C:\path\to\disposable-world"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat -PcameraQa -Pneo_version=21.1.264 runModpackTest
```

The script reads the source instance, copies its pack files and options, records
SHA-256 hashes, and moves the copied installed camera JAR out of the copied `mods`
directory so the development classes can replace it. It refuses an existing QA
destination. `-TestWorld` is optional; without it, create a world named `Camera QA`
in the copied instance. `-DisableDistantHorizons` optionally disables that mod in
the copy; record this exclusion when reporting results. Match `-Pneo_version` to
the pack's loader version when testing a different pack.

Once the test world is ready and menus are closed, write one command to the
request file. Results append to `qa/results.log` inside the copied game directory:

```powershell
Set-Content -LiteralPath .\build\modpack-test\game\qa\request.txt -Value 'zoomprobe'
Get-Content -LiteralPath .\build\modpack-test\game\qa\results.log -Tail 20
```

| Command | Check or action |
| --- | --- |
| `zoomprobe` | Actual mouse pipeline: sensitivity, zoom, smoothing, inverted Y, and freelook transitions. |
| `zoomthirdprobe` | Lens FOV, detached camera position, zoom scroll, and first-person/freelook parity. |
| `shoulderprobe` | Crosshair reveal timing, shoulder swaps, and wall clearance. |
| `compatprobe` | Better Combat hitbox targeting and Sable reflection bridge. |
| `swordsprobe` | Simply Swords ability key, release, and offhand eligibility. |
| `rayprobe` | Camera picking and collision against block/entity fixtures. |
| `integrationprobe` | Actual block, Carry On, melee, ability, and projectile interactions. |
| `sableprobe` / `pingprobe` | Real sublevel seat transitions / server-echoed ping targeting. |
| `motionstart` / `motionstop` | Move and restore the test sublevel for motion checks. |
| `camera 4` / `first` / `off` | Set four-block unlocked view / first person / mod-disabled third person. |
| `state` / `unpause` / `quit` | Log camera state / close menus / exit the test client. |
| `benchmark shoulder 30` | Warm up for 10 seconds, then record 30 seconds under the label `shoulder`. |

Run gameplay probes separately and wait for completion; they create temporary
fixtures in the copied world. Keep the game focused during benchmarks and avoid
other work that loads the CPU/GPU. Results include mean, p95/p99, frames over
50 ms, unfocused-frame count, and raw frame times in `<label>-frames-ns.csv`.

Normal builds exclude `src/qa`; opt-in QA JARs have a `-qa` classifier. After QA,
use `.\gradlew.bat build` to produce the release artifact. Normal JAR packaging
also excludes QA classes explicitly. Avoid `clean` until any wanted test evidence
has been copied out of `build/`. The recorded
coverage and remaining limits are in [the compatibility audit](docs/compatibility-audit-2026-10-31.md)
and [the runtime/performance report](docs/modpack-validation-2026-10-31.md).

Key source files:
- `src/main/java/com/caleb/unlockedcamera/UnlockedCameraMod.java` — mod entry point
- `src/main/java/com/caleb/unlockedcamera/client/UnlockedCameraClient.java` — all camera logic (client only)
- `src/main/java/com/caleb/unlockedcamera/client/` — config, linked config-screen sliders, Sable seat detection
- `src/main/java/com/caleb/unlockedcamera/mixin/` — vanilla hooks (camera, GUI crosshair, mouse, outgoing
  packet aim correction), config-screen slider/color tweaks, and presence-gated Create & Simulated
  crosshair compat (see `UnlockedCameraMixinPlugin`)
