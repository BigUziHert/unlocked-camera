# Contraption Camera

A standalone "contraption camera" for **NeoForge 1.21.1**, inspired by the camera in
Create Aeronautics: a fourth perspective in the vanilla view cycle that detaches into
an orbit camera you can zoom with the scroll wheel — far beyond vanilla's fixed
4-block distance.

## Controls

`F5` (or whatever "Toggle Perspective" is bound to) now cycles through four views:

1. First person
2. Third person (behind)
3. **Contraption camera** — scroll wheel zooms in / out (smoothed)
4. Third person (front-facing)

While the contraption camera is active, scrolling zooms instead of switching hotbar
slots. When terrain pulls the camera in, it glides back out smoothly once clear.

**Freelook:** in first person, hold `Left Alt` (rebindable) and move the mouse to
look around without turning your body — movement direction and aim stay put. On
release the view eases back to center.

**Create Aeronautics compatibility:** while seated in an Aeronautics contraption
(a Sable "sublevel"), this mod steps aside completely — the perspective key behaves
as if the mod weren't installed, so Aeronautics' own contraption camera takes over.
Detection uses Sable's own seated-check via reflection, so there is no hard
dependency on it.

## Config

Zoom limits live in `config/contraptioncamera-client.toml` (also editable in-game via
Mods > Contraption Camera > Config):

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

- `src/main/java/com/caleb/contraptioncamera/ContraptionCameraMod.java` — mod entry point
- `src/main/java/com/caleb/contraptioncamera/client/ContraptionCameraClient.java` — all camera logic (client only)
