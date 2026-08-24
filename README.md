# AR Tape Measure — Android demo

Measure real distances through the camera. Kotlin + Jetpack Compose, one Activity.

Replicates the UX of Apple's built-in Measure app: centred reticle, `+` to commit a point,
dashed rubber-band line while aiming, solid line with a distance pill once committed, chained
polyline, undo / clear, and a `Level` tab.

## Read this before trusting a number

**This is a layout tool, not a caliper.**

| Device | Error per point | A 2 m span |
|---|---|---|
| No depth sensor (most Android phones) | several cm | **off by ≥5 cm** |
| ToF / LiDAR-class sensor | → ~1 cm | much better |

The error is **not a stable bias**, so it cannot be calibrated away — it moves with texture,
lighting, how much parallax your movement gave the tracker, and how long the session has run.

Good for: *will this cabinet fit this alcove* (alcove 84, cabinet 78 → yes), stud spacing,
clear height, a rough room outline. Not good for: any fitting dimension, or anywhere being
wrong by 3 cm ruins the job.

The reference video that shaped this UI was shot on an iPhone with LiDAR. The UX is
reproducible on Android; **that accuracy is not.**

## Build & run

Requires a **real device** — the ARCore emulator replays a synthetic scene, so any number it
gives describes the simulation, not a sensor.

```bash
./gradlew :app:assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # 17 pure-maths tests, no device needed
./gradlew :app:installDebug           # to a connected device
```

Toolchain (verified working, not guessed):

| | |
|---|---|
| Gradle | 9.5.0 (wrapper) — AGP 9.3.1 refuses 9.4 |
| AGP | 9.3.1 — **ships built-in Kotlin**, so no `kotlin-android` plugin |
| Kotlin | 2.4.10 |
| Compose BOM | 2026.05.01 — 2026.08.00 needs `compileSdk 37` |
| compileSdk / minSdk | 36 / 24 |
| SceneView | `io.github.sceneview:arsceneview:4.31.0` |

## How it works

```
MainActivity              one Activity; ARCore install gate, camera permission, tab switch
└── MeasureScreen         ARSceneView (camera + tracking + hit tests) under a 2D overlay
    ├── MeasureFrameLoop  per-frame: resolve reticle -> refresh anchors -> build overlay
    ├── MeasureOverlay    Canvas: lines, label pills, endpoint dots, reticle
    ├── MeasureHit        hit resolution, in accuracy order
    ├── PoseProjector     world -> screen, allocation-free
    ├── MeasureState      anchors, units, per-frame overlay snapshot
    └── MeasureMath       pure arithmetic — the only unit-tested part
└── LevelScreen           gravity vector only; no AR, works on every device
```

### Nothing is drawn as 3D geometry

Perspective projection preserves straight lines, so a 3D segment A→B always projects to a
straight screen-space segment. Everything is therefore drawn in one Compose `Canvas`: dashed
strokes and screen-constant label pills — both of which the reference app has — are trivial
there and awkward as Filament geometry.

The trade-off is real and worth stating: 3D nodes (`LineNode` / `TextNode`) would let the
renderer handle camera movement for free, whereas the 2D overlay must re-project every point
each frame. At this point count that is irrelevant; for a scene with hundreds of anchors, 3D
nodes would be the right call.

`OverlayFrame` is read **inside** the Canvas draw lambda, so a new AR frame invalidates only
the draw phase — no recomposition, no relayout.

### Hit resolution is accuracy-ordered

1. **Plane, inside the polygon.** The `isPoseInPolygon` check is load-bearing: ARCore reports
   hits on the *infinite extension* of a plane, so without it, aiming past the edge of a table
   places a point in mid-air and reports a confidently wrong distance — no crash, no warning.
2. **`DepthPoint`** — geometry with depth but no plane grown over it.
3. **The depth image** via `Frame.hitTestDepth`, which is what makes a cluttered space
   measurable rather than only its flat floor.
4. **A raw feature point** — noisiest, and labelled on screen so it is trusted less.

The UI names the source of the last point (`Point 3 on depth map`). A reading you cannot
attribute is a reading you cannot calibrate.

### Anchors, not coordinates

ARCore corrects anchor poses as it refines its map, so poses are re-read every frame. They are
published to Compose only past a **1 mm dead-band** — otherwise the number flickers at frame
rate for jitter nobody can see. 1 mm is far below the accuracy floor, so nothing observable is
discarded.

## Deliberately not implemented

Shutter capture (button is rendered inert rather than lying about being wired), edge snapping
to floor/wall seams, position smoothing on the reticle, closed-loop perimeter, bounding box,
coaching overlay, area measurement.

Each is a separate step, and none of them matter until the accuracy table below has numbers.

## Accuracy protocol — the actual next step

The table is deliberately empty. Filling it needs a real device and a reference object; a
plausible-looking number here would be worse than a blank.

1. Pick something measurable to ±1 mm with a tape (door frame, table edge). Record the truth.
2. Move the phone around it for ~10 s before the first tap, so ARCore has parallax. Wait for a
   stable plane.
3. Take **five** independent readings of the same span — clear between each, re-approach from a
   slightly different angle. One reading says nothing about spread, and **spread is the number
   that matters**: it is what users experience as "this app is inaccurate".
4. Record device, ToF or not, the five readings, lighting/texture, and the hit source shown.

| Surface | Device | ToF? | True | 5 readings | Mean error | Spread | Conditions |
|---|---|---|---|---|---|---|---|
| Balcony concrete | | | | | | | |
| Textured wood | | | | | | | |
| Plain white wall | | | | | | | |
| Glass | | | | | | | |
| Dark / low light | | | | | | | |

## Notes

- APK is ~43 MB debug — Filament plus ARCore. Worth measuring against an ASO size budget.
- `com.google.ar.core` is `optional` in the manifest: the app installs everywhere and gates the
  feature itself. `required` would make Play Store filter the listing off non-AR devices,
  costing impressions and not just installs.
- Only the `CAMERA` permission. No network, no storage, no frames retained.
