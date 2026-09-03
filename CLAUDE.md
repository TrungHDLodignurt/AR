# CLAUDE.md — ar-tape-measure

Read automatically at the start of every session. Keep it that way: this file is the entry
point, so anything a new session must not get wrong belongs here or behind a link from here.

---

## Mandatory session protocol

**At the start of a session**

1. Read this file (already done — it is auto-loaded).
2. Read `README.md` for architecture. It is accurate and current; do not re-derive it.
3. Check state before assuming: `git worktree list`, `git status --short`, `git log --oneline -5`.
   This repo runs **multiple worktrees on different branches at once** — see *Worktrees* below.
4. Open the relevant `plans/reports/*.md` only when working in that area. They are long; the
   summaries in this file are enough for orientation.

**At the end of a piece of work**

5. Update this file if any of these changed: an invariant, the verification commands, the state
   table, an open unknown, or the AIP936 sync position. Editing code without editing this file
   when one of those moved is what makes the next session repeat a solved problem.
6. Record only *durable* knowledge. Something true for one afternoon belongs in a report under
   `plans/reports/`, not here.
7. Keep this file under ~200 lines. When it grows past that, move detail into `docs/` or
   `plans/reports/` and leave a one-line pointer.

**What does not belong here:** the user's personal preferences and working style. Those live in
the private per-project memory directory, not in a committed repo file.

---

## Worktrees — check before you edit

Two directories, two branches, **same git repo**. Both open in Android Studio show the window
title `ar-tape-measure`, distinguishable only by the path in brackets.

| Directory | Branch | Contains |
|---|---|---|
| `ar-tape-measure/` | `refactor/mvi-alignment` | the measuring app |
| `ar-tape-measure-air-draw/` | `experiment/air-draw` | + the air-pen experiment |

Consequences that have already caused confusion:

- A file edited in the wrong directory is on the wrong branch. `AirDrawFrameStream.kt` exists
  only in the air-draw worktree; `ArCameraScreen.kt` differs between the two.
- **This file is per-branch.** A `CLAUDE.md` committed on `refactor/mvi-alignment` does not
  appear on `experiment/air-draw` until merged. The air-draw worktree has its own.
- `local.properties` is gitignored, so a new worktree has no SDK path until it is copied in.

---

## Current state

Branch `refactor/mvi-alignment` @ `0115830`. Remote `origin` = `github.com/TrungHDLodignurt/AR`
(private). `main` and four other branches are pushed.

| Feature | Code | Unit tests | On a device |
|---|---|---|---|
| Plane dot field + ellipse reticle | done | — | **never seen** |
| Snap to placed point (28/45 dp) | done | 12 tests | **never seen** |
| Scanning indicator | done | — | seen, **looks weak** (see below) |
| Box base as a 3-tap chain | done | 2 tests | **not verified** |

`:AR_feature:testDebugUnitTest` — 202 tests, 0 failures, 2 pre-existing skips.

**Known weak, not yet fixed:** `ScanningIndicator` draws its grid quad only across y 34%→70% of
its 162 dp box, so the visible graphic is ~58 dp inside a 162 dp footprint, and the dashed
interior at 1 dp / 50% white is nearly invisible over a real camera feed. Confirmed on a Pixel 6.
The lesson generalises: **strokes tuned against a synthetic flat background lose over a live
camera image.** Widen the quad, thicken the strokes, darken the grid.

---

## Invariants — breaking these has already cost time

**ARCore session and rendering**

- `Engine` and `MaterialLoader` are constructed **once**, never inside `key(instanceKey)`.
  Recreating the Engine on the watchdog's ~10 s cadence produced a near-100% failure rate.
- `ARSceneView` sits outside every `when (tool)` branch and `tool` never appears in `key(...)`.
  A tool swap must not remount the view.
- `planeRenderer = false`. The library renderer paints a plane's **whole extent**, so aiming at a
  floor also wireframes every wall. `PlaneRendererV2` looks like the fix and is not:
  `ARSceneScope` exposes no `PlaneRendererBase` instance, so `gridAlpha` / `scanPlaneRadius` are
  unreachable without reflection. Verified against `arsceneview-4.31.0.aar`.
- Turning the plane renderer off also gives up Filament plane occlusion. We were never using it.

**State placement**

- The per-frame streams (`MeasureFrameStream`, `ShapeFrameStream`, `ArSessionFrameStream`,
  `AirDrawFrameStream`) are deliberately **not** MVI `State`. At 30–60 Hz an intent round trip
  costs a coroutine dispatch and a whole-state allocation per frame. Do not "fix" this.
- `MeasureFrameStream`'s KDoc claims a new frame "invalidates only the draw phase". That is true
  for `MeasureOverlay`, and **false for `ArCameraScreen`**: `distanceActions` reads
  `frames.addEnabled` and `distanceHint` reads `frames.live`/`liveStable`, all per-frame Compose
  state read during composition. The chrome already recomposes every frame. Know this before
  blaming the dot field for jank.
- `ar/domain/geometry/` must stay free of Compose and ARCore types — that is what lets it unit
  test on a plain JVM. Screen positions cross that boundary as `Pair<Float, Float>`.

**Behaviour that is load-bearing**

- The reticle's solid-vs-hollow variant encodes on-surface / off-surface. Never trade it for looks.
- Snap uses **nearest-wins** (the reference app's first-match picks the wrong point) and
  **two radii**: enter 28 dp, release 45 dp. One threshold toggles the lock every frame once
  ±1.2 dp hand tremor straddles it.
- `MeasureFrameStream.commitReady` lets a snap bypass the steadiness gate. The gate exists to
  distrust a *live depth estimate*; a snapped position comes from an already-refined anchor.
- The box's three base taps are a **chain**: origin, along one side, then turn at that corner.
  `edgeV` is measured from `origin + edgeU`. Measuring it from the origin reported the span back
  to tap 1 as the second dimension — a 1.55 m side came out as 2.83 m.
- The frame loops are not composables, so dp→px conversion needs `density` threaded in from
  `ArCameraScreen` (`LocalDensity.current.density`).

---

## Verification

```bash
./gradlew :AR_feature:compileDebugKotlin        # the gate for UI-only changes
./gradlew :AR_feature:testDebugUnitTest         # 202 tests
ANDROID_SERIAL=<serial> ./gradlew :app:installDebug
```

Device notes:

- Always pass `adb -s <serial>` and re-read `adb devices` first; serials change between sessions.
  Of the two usually attached, only the Pixel supports AR.
- **ARCore needs parallax motion to find a plane.** A phone held still, however textured the
  scene, stays on "Looking for a surface…" indefinitely. Automated verification of anything
  plane-dependent is therefore impossible without a human moving the device.
- Screenshots are the most expensive thing that enters context. One per question, delete both
  copies immediately after reading.

---

## Syncing AR_feature into AIP936-AIHomeDesign

`AIP936-AIHomeDesign/AR_feature` is a **file copy**, not a Gradle dependency. Same package
(`vn.apero.armeasure`), no rename. Last synced at `0115830` → 936 commit `4734d62`.

The two commits that carried the overlay work touch **no public API**, so `ArMeasureKit` and
`ArMeasureConfig` are untouched and the host needs no rewiring.

**Protocol — verify before overwriting, every time.**

```bash
BASE=<the commit 936 was last synced to>
git archive $BASE AR_feature | tar -x -C /tmp/base           # extract the baseline TREE
git diff --name-only $BASE..HEAD -- AR_feature | sed 's|^AR_feature/||' > /tmp/changed
# then diff /tmp/base/AR_feature/<f> against 936's <f> for each entry:
# OK = safe to overwrite · DRIFT = 936 was edited locally, STOP · NEW = new file
```

Two traps, both already hit:

- **Do not** verify with a per-file `git show` loop and unquoted `$VAR` — zsh does not word-split
  unquoted parameter expansions, so `for f in $FILES` runs **once** with the whole blob and
  reports nonsense. Use `while read -r`, or the `git archive` form above.
- 936 has product flavors. Its app task is **`:app:compileAppDevDebugKotlin`**, not
  `:app:compileDebugKotlin` — the latter fails with "task is ambiguous", which looks like a build
  break but is not.

Verify in 936 after copying: `:AR_feature:compileDebugKotlin`,
`:AR_feature:testDebugUnitTest`, `:app:compileAppDevDebugKotlin`.

936's remote is the shared team repo `AperoVN/AIP936-AIHomeDesign`. **Never push it without
being asked.**

---

## Open unknowns

1. How often `SurfaceSample.planeNormal` is non-null in the field. It gates **both** the dot field
   and the ellipse reticle, so in a depth-heavy environment neither appears. Biggest unknown.
   Related: `hitTestDepth` already returns an estimated `normal` that the code discards
   (`MeasureHit.kt`, the depth-fallback branch sets `trackable = null`) — accepting it would make
   both features work without a plane, at the cost of a normal derived from a smoothed and
   partly-invented depth map.
2. Whether ~200 dots × 2 `drawCircle` per frame holds 60 fps on the low-end target. First lever
   if not: dot count. Second: a non-nullable `projectInto` to stop `Offset?` boxing.
3. Whether 28 / 45 dp feel right with a real hand.
4. Whether the 40 × 40 cm patch and 2.5 cm spacing read well at real measuring distances.

---

## Reference reading

- `README.md` — architecture, hit-resolution order, why nothing is 3D geometry. Accurate.
- `plans/reports/teardown-260828-1507-aruler-scan-coaching-ux.md` — ARuler v3.3.5 teardown:
  its coaching state machine, the 6-tip/5 s carousel, and "Sticking" (45 dp, first-match).
- `plans/reports/brainstormer-260828-1507-ar-scan-planeviz-snap.md` — the four decisions and the
  two dead ends.
- `plans/260828-1620-ar-overlay-scan-dots-snap/` — phase files with the numbers actually used.
