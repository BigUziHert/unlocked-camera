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
shooter. By default the crosshair appears while the offset is engaged; a config
option makes it permanent.

**Shoulder offset:** zoomed in to 4 blocks or closer (configurable), the camera
slides over the shoulder; press `X` (rebindable) to swap sides. The offset clips
against walls so it never pokes through blocks.

**Freelook:** in first person, hold `Left Alt` (rebindable) and move the mouse to
look around without turning your body — movement direction and aim stay put.
Clicking mid-freelook snaps your body to where you're looking, so the interaction
lands on the crosshair. On release the view eases back to your original facing;
enable `freelookKeepDirection` to keep facing where you were looking instead.

**Better Third Person compatibility:** the mod never touches camera rotation, so
BTP's free orbit is untouched; targeting through the center crosshair works the
same with or without BTP.

**Create Aeronautics compatibility:** while seated in an Aeronautics contraption
(a Sable "sublevel"), this mod steps aside completely — the perspective key behaves
as if the mod weren't installed, so Aeronautics' own contraption camera takes over.
Detection uses Sable's own seated-check via reflection, so there is no hard
dependency on it.

## Config

Settings live in `config/unlockedcamera-client.toml` (also editable in-game via
Mods > Unlocked Camera > Config; changes apply live):

- `enableUnlockedCamera` — toggle the fourth perspective on/off, default on
- `enableFreelook` — toggle freelook on/off, default on
- `freelookKeepDirection` — on release, face where you were looking instead of
  easing back, default off
- `crosshairAlways` — always draw the aim-corrected crosshair in the unlocked
  camera, default off
- `crosshairOnShoulderOffset` — draw it while the shoulder offset is engaged,
  default on
- `showEnterMessage` — show the action-bar text when entering the unlocked
  camera, default on
- `enableShoulderOffset` — toggle the over-the-shoulder offset, default on
- `shoulderOffsetMaxZoom` — apply the shoulder offset at or closer than this
  distance in blocks, default 4
- `minZoom` — closest zoom, default 1 block
- `maxZoom` — farthest zoom, default 12 blocks
- `freelookYawLimit` — how far left/right freelook can swing in degrees, default 90
  (max 180 = fully behind you)

## Development

Requires JDK 21 (`gradle.properties` points Gradle at `C:/Program Files/Java/jdk-21`).

```powershell
.\gradlew.bat runClient   # launch a dev client with the mod loaded
.\gradlew.bat build       # build the release jar into build/libs/
```

Key source files:

- `src/main/java/com/caleb/unlockedcamera/UnlockedCameraMod.java` — mod entry point
- `src/main/java/com/caleb/unlockedcamera/client/UnlockedCameraClient.java` — all camera logic (client only)
- `src/main/java/com/caleb/unlockedcamera/mixin/` — Camera, Gui, and MouseHandler hooks
