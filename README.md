# AR Tape Measure — Android demo

Measure real distances through the camera. Kotlin + Jetpack Compose, one Activity.

Replicates the UX of Apple's built-in Measure app: centred reticle, `+` to commit a point,
dashed rubber-band line while aiming, solid line with a distance pill once committed, chained
polyline, undo / clear. Four tabs: **Measure** (the point-to-point ruler above), **Photo**
(calibrate against a reference object in a photo — a card or A4 sheet, no AR needed), and **Box** /
**Cylinder** (3-tap origin → freehand base → height AR shapes).

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

## Module layout

The repo is two modules: one library module holds all feature code, `:app` is nav only.

| Module | Package | Contents | JVM tests |
|---|---|---|---|
| [`:AR_feature`](AR_feature/README.md) | `vn.apero.armeasure.*` | `common` (LengthUnit, formatters, LabelPill) + `ar` (Ruler/Box/Cylinder + ARCore infra) + `photo` (Canny/Hough/Homography photo-reference measuring). Public API: `ArAvailability`, `ArMeasureKit`, `ArMeasureRulerScreen`, `ArMeasureBoxScreen`, `ArMeasureCylinderScreen`, `PhotoMeasureScreen`, `CustomReferenceStore` (constructor only) | 67 |
| `:app` | `vn.quancua.artapemeasure` | `MainActivity` + tab nav only, wires `:AR_feature` | 0 |

`AR_feature/README.md` (linked above) is a self-contained integration guide for pulling the
module into another app: exact `settings.gradle.kts`/`build.gradle.kts` lines, the
`gradle/libs.versions.toml` block to append, the manifest/permission story, and every public
signature with a copy-pasteable call example.

## Build & run

Requires a **real device** — the ARCore emulator replays a synthetic scene, so any number it
gives describes the simulation, not a sensor.

```bash
./gradlew assembleDebug                          # whole app APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest                      # all 67 pure-maths tests, no device needed
./gradlew :app:installDebug                      # to a connected device
./gradlew :AR_feature:testDebugUnitTest          # same 67 tests, feature module only
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
└── ShapeMeasureScreen    Box/Cylinder — same ARSceneView + 2D-overlay shape, parameterized by ShapeKind
    ├── ShapeFrameLoop    per-frame resolve (real hit-test for origin/base, analytic
    │                     construction-plane ray-cast for height) -> build wireframe overlay
    ├── ShapeOverlay      Canvas: wireframe edges (dashed where occluded), dimension pill, reticle
    ├── ShapeMeasureState state machine: origin -> freehand edge(s) -> height -> committed shape
    ├── SteadinessGate    "hold still before it counts" trust gate, shared with MeasureState
    └── ShapeMath         pure geometry — parallelogram/circle bases, hidden-edge visibility
└── PhotoMeasureScreen    calibrate against a photographed reference object; no AR/camera-feed dependency
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

### Editing a placed point

Press and drag any committed dot to move it: it re-resolves against the surface under the
finger every frame (the same accuracy-ordered hit test the reticle uses), the touched
segment's label updates live, and the anchor is only replaced on release — cancel the drag
and the point never moved. The reference app hides the exact same information behind its
own fingertip with a live magnified crop of the camera feed rendered by a GPU shader; this
app lifts a second copy of the dot above the touch point instead, which answers the same
question ("where will this land") without a per-frame texture read-back.

## Deliberately not implemented

Shutter capture (button is rendered inert rather than lying about being wired), edge snapping
to floor/wall seams, position smoothing on the reticle, closed-loop perimeter, coaching overlay,
area measurement, Floor Plan / Angle / Vertical-Wall / Chain modes (Box and Cylinder are done —
see the diagram above).

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
