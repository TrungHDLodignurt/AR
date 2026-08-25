# Session Handoff — Box/Cylinder AR Measure Modes

Purpose: let another AI session pick up exactly where this one left off, with no re-derivation
needed. Written 2026-08-25. Branch: `feature/photo-reference-measure`, HEAD `d925a9f`.

## 1. What this session did, end to end

1. Reverse-engineered a competitor app (`com.craftars.measuretools`) to understand its Box/Cylinder
   measuring UX and plane-detection warnings — see §2.
2. Assessed feasibility of porting Box/Cylinder modes onto this app's existing architecture —
   concluded: high feasibility, no new AR capability needed (see §3).
3. Implemented Box + Cylinder AR measuring modes in a git worktree (`feature/box-cylinder-measure`,
   branched off `c00d9ca`), iterating three times based on live on-device testing and direct user
   feedback (see §4 for the final design, §5 for why it changed three times — the history matters,
   don't repeat the two wrong turns).
4. Merged the worktree branch into `feature/photo-reference-measure` (clean auto-merge, no
   conflicts) after confirming a concurrent session had committed its own unrelated work first.
   Deleted the worktree and the now-merged branch — repo is back to a single active branch.

## 2. Competitor teardown — com.craftars.measuretools (condensed)

Full original teardown (`tech-teardown.md`, static analysis + a user-provided screen recording)
lived in a session-scratchpad path that will not persist — this section is the durable summary.

- **Stack**: Unity 6.3 + IL2CPP + AR Foundation/ARCore. Not comparable code-wise to this Kotlin/
  Compose/SceneView app — nothing there is directly portable, only the UX ideas are.
- **Confirmed Box/Cylinder flow** (from an actual screen recording, not guesswork): both are a
  **3-tap** pattern — origin → base → height. Box's tutorial overlay text: *"First tap places the
  origin of the box" / "Second tap forms a base for the box" / "Raise the target and tap a 3rd
  time for the height."* Cylinder is identical except the base is a circle (diameter grows as the
  phone moves away from the center) instead of a rectangle — **not** a "sweep around the object"
  gesture (an earlier name-derived guess from IL2CPP method names, corrected once the recording was
  seen).
- Tool menu had 8 entries: Free Point Measure, Show Angles, Flat Plane Measure, Box Preview,
  Vertical Wall Measure, **Cylinder (NEW badge)**, Chain Measurements, **Floor Plan (SOON badge —
  not shipped)**. Floor Plan being unshipped corrected an earlier assumption from marketing copy.
- Plane-detection "warnings" are just ARCore/AR-Foundation's stock `TrackingFailureReason`
  (`InsufficientLight`/`InsufficientFeatures`/`ExcessiveMotion`) translated into onboarding tip
  copy + a red→green reticle-lock dot — nothing custom. This app already had the same coaching
  pattern pre-session (`MeasureScreen.kt`'s `hintFor`).
- Uses ARCore Depth API (`com.google.ar.core.depth` required) for **occlusion only** (real
  furniture can hide the AR overlay) — not for measurement accuracy. Forcing it `required=true`
  excludes non-Depth devices; not recommended to copy that constraint here.

## 3. Architecture grounding — why Box/Cylinder was easy to add here

This app renders **all** AR graphics as a 2D Compose Canvas overlay, projecting arbitrary 3D world
points to screen space every frame (`PoseProjector.project()`) — not real Filament/SceneView 3D
meshes. So "draw a box" is just "compute more 3D points, project them, draw more line segments" —
no 3D engine work needed. Existing building blocks reused directly:

- `resolveSurface()` (`MeasureHit.kt`) — tap-to-3D-point resolution (plane > depth-point > depth-map
  > feature-point, in accuracy order). Used as-is for origin/base taps.
- `SteadinessGate` (extracted this session from `MeasureState`'s inline logic) — the "hold still
  before it counts" trust gate, now shared across the point ruler, Box, and Cylinder.
- `intersectRayPlane` (`MeasureMath.kt`) — analytic ray/plane intersection, already used for the
  point ruler's steady plane readings; reused for the height step (see §4).

## 4. Final design (after 3 iterations — this is what's actually in the merged code)

### State machine (`ShapeMeasureState.kt`, `ShapePhase` sealed class)

```
AwaitingOrigin
  Box:      -> SizingEdgeU -> SizingEdgeV -> SizingHeight -> (committed shape)
  Cylinder: -> SizingCircle -> SizingHeight -> (committed shape)
```

One `ShapeMeasureState(kind: ShapeKind)` class serves both — cylinder is a strict subset (skips
the box-only edge-drawing steps). Only the origin gets an ARCore anchor per shape; every other
corner is re-derived from it each frame (`ShapeBase.corners()` in `ShapeFrameLoop.kt`), so a shape
never costs more than one entry in ARCore's anchor budget regardless of corner count.

### Box base: two independently drawn edges → a parallelogram, NOT forced to a right angle

This is the single most important, most-iterated-on decision — see §5 for why. Final model:

- `SizingEdgeU`: user drags from the origin; `projectedEdgeVector()` (`ShapeMath.kt`) flattens the
  live point onto the origin's plane and returns the raw in-plane vector — tap fixes `edgeU`.
- `SizingEdgeV`: same, independently — tap fixes `edgeV`. **Not decomposed against `edgeU` or any
  fixed perpendicular axis.**
- Base = `parallelogramCorners(origin, edgeU, edgeV)` = `[origin, origin+edgeU, origin+edgeU+edgeV,
  origin+edgeV]`. If the user's two drags happen to be ~90° apart (typical for a real box), it
  reads as a rectangle. If not, it's a parallelogram — intentional, matches what was actually
  measured rather than silently correcting it.
- `ShapeBase.Rect(edgeU: Vec3, edgeV: Vec3)` stores the two raw vectors directly (no shared basis).

Cylinder's base is unaffected by any of this — a circle is rotationally symmetric, so
`SizingCircle` stays a single tap (center + `circleFromPoints()`), using `planeBasis(normal)`
(an arbitrary, direction-irrelevant in-plane basis) same as before.

### Height: analytic construction-plane ray-cast, not hit-testing

`heightConstructionPlaneNormal()` (`ShapeMath.kt`) builds a **virtual vertical plane** through the
origin, oriented toward the camera, and `resolveHeightSample()` (`ShapeFrameLoop.kt`) intersects
the aim ray against it via `intersectRayPlane`. The resulting `SurfaceSample` is tagged
`HitSource.Plane` on purpose (not a new enum case) so it gets `SteadinessGate`'s "trust instantly"
treatment, same as a real plane hit — raising the phone reports a height immediately, no half-
second hold-still. This replaced an earlier version that reused the real plane/depth/feature-point
hit-test chain for height too, which failed constantly (nothing real to hit when aiming into open
air above the shape) — see §5, iteration 1.

### Hidden-edge dashing

`prismEdgeVisibility()` (`ShapeMath.kt`) determines, per edge, whether either of its two adjacent
faces faces the camera (true for any convex solid — an edge with both neighbours turned away sits
entirely behind the shape's own front side). Works identically for box and cylinder since both are
just an N-gon extruded along the shape's normal; a face's outward direction is taken radially from
its own ring's centroid, so no assumption about winding order is needed. `loopEdges()` tags each
edge; `ShapeOverlayFrame.committedHiddenEdges` (drawn dashed) is separate from `committedEdges`
(solid).

### File map (all under `app/src/main/java/vn/quancua/artapemeasure/measure/`)

| File | Role |
|---|---|
| `ShapeMath.kt` | Pure geometry: `planeBasis`, `projectedEdgeVector`, `parallelogramCorners`, `circleRing`/`circleFromPoints`, `heightAlongAxis`, `heightConstructionPlaneNormal`, `prismEdgeVisibility`, `loopEdges`, dimension-label formatters. Zero ARCore/Compose types — JVM-testable. |
| `ShapeMeasureState.kt` | `ShapeKind`, `ShapePhase`, `ShapeBase`, `MeasuredShape`, the state machine (`commitStep`/`undo`/`clear`). |
| `ShapeFrameLoop.kt` | Per-frame resolve + overlay-build, mirroring `MeasureFrameLoop.kt`'s split from `MeasureState.kt`. |
| `ShapeOverlay.kt` | Canvas draw calls, reusing `MeasureOverlay.kt`'s `drawSegment`/`drawReticle`/`drawLabelPill` (widened to `internal`). |
| `ShapeMeasureScreen.kt` | The composable screen, parameterized by `ShapeKind`; per-phase hint text. |
| `SteadinessGate.kt` | Extracted from `MeasureState`; now shared by ruler + Box + Cylinder. |
| `ShapeMathTest.kt`, `SteadinessGateTest.kt` | 29 JVM unit tests total for this feature (67 pass across the whole `measure` + `photomeasure` packages post-merge). |

`AppTab` (`ui/MeasureControls.kt`) gained `Box`/`Cylinder` entries; `MainActivity.kt` wires them to
`ShapeMeasureScreen(ShapeKind.Box)` / `ShapeMeasureScreen(ShapeKind.Cylinder)`.

## 5. Why it took 3 iterations — don't repeat these

1. **First cut**: height reused the real plane/depth/feature-point hit-test chain (same as origin/
   base). Broke on-device — user reported it "khó, không thân thiện" (hard, unfriendly) compared to
   the reference app, because aiming into open air above the shape usually has nothing real to hit,
   so the reticle dropped out constantly. Fixed by the construction-plane approach in §4.
2. **Second cut**: box base used a *fixed* in-plane basis derived from ARCore's world Z/X axis
   (`planeBasis()`), so the rectangle's orientation never matched the real object regardless of
   drag direction. User: *"góc của cạnh thứ 2 lại bị cố định"* (the edge's angle is still fixed).
   Fixed by deriving the first edge's direction from the user's own drag (`drawnEdgeBasis` —
   since removed).
3. **Third cut**: that fix only freed the *first* edge; the *second* edge (width) was still forced
   perpendicular to the first (a fixed `basis.v`). Screenshot showed the width-drag visually
   disconnected from the reticle. User confirmed directly: *"làm như bên app mẫu ý, dựa vào chiều
   dài của 2 cạnh và góc độ của 2 cạnh... là dựng được 3D"* (base it on both edges' lengths **and**
   the angle between them — i.e. don't force 90°). Landed on the final parallelogram-from-two-
   independent-edges model in §4. `rectangleFromPoints`/`RectBase`/`rectangleCorners`/
   `drawnEdgeBasis` were all dead code by this point and removed.

**Lesson for whoever continues this**: when a UX detail is ambiguous and a build/install cycle is
expensive (this repo needs a physical device — no emulator was used), ask a concrete clarifying
question with 2-3 options *before* the second wrong guess, not after. `AskUserQuestion` was used
successfully for iteration 3 once the pattern of guessing wrong was noticed.

## 6. Known limitations (not fixed, flagged deliberately)

- **Height accuracy on non-depth devices** is a non-issue now (construction-plane math doesn't
  depend on depth sensing at all) — this limitation from the original plan is **resolved**, not
  open.
- **Wall-anchored shapes**: if a shape's origin comes from a vertical-plane (wall) hit, "height"
  extrudes perpendicular to the wall (i.e. depth, not vertical height) — geometrically correct
  given the design (extrusion follows the origin's own plane normal), but the UI hint text always
  says "height" regardless. Cosmetic terminology mismatch, not a bug.
- **Anchor orphan on tab-switch mid-shape**: if a user leaves the Box/Cylinder tab with a shape
  half-drawn (origin already anchored), that anchor is never explicitly detached — relies on
  `ARSceneView`'s session teardown to clean it up. Confirmed pre-existing (the original point ruler
  has the same gap) — not a regression, candidate for a follow-up if it matters.
- **Floor Plan / Angle / Vertical-Wall / Chain modes**: not implemented, not requested this
  session. Floor Plan isn't even shipped in the reference app (see §2). Backlog only.
- Depth-API occlusion (letting the AR overlay duck behind real furniture) was explicitly decided
  **not** worth chasing — see §2, port-notes reasoning from the original teardown.

## 7. Git state

- Single branch now: `feature/photo-reference-measure`, HEAD `d925a9f` (merge commit).
- Worktree `.claude/worktrees/box-cylinder-measure` and branch `feature/box-cylinder-measure` were
  both deleted after a clean merge — nothing left to clean up.
- A concurrent session's own work (`d894a82` "fix: avoid ARCore camera-texture race on cold start
  in Measure tab", `f3ad223` "fix: recover rotated reference-object quads in photo auto-fit") is
  already merged in — unrelated to Box/Cylinder, mentioned here only so it isn't mistaken for part
  of this feature.
- No push to any remote occurred (repo has no `origin` configured).

## 8. Open questions for whoever continues this

- Should Box/Cylinder be gated to devices with AR support the same way the reference app gates to
  Depth-capable devices, or left open as it is now (no gating)? Not decided this session.
- Should the anchor-orphan-on-tab-switch gap (§6) get an explicit `DisposableEffect` cleanup, given
  there are now three AR tabs instead of one? Flagged, not actioned.
- The box/cylinder tool-picker UI is two more entries in the fixed bottom tab bar (5 tabs total:
  Measure/Photo/Level/Box/Cylinder), not the reference app's slide-up sheet — a deliberate
  simplification from the original brief, never revisited after the parallelogram-base fix. Worth
  a product opinion if more tools get added later (a 6th+ tab may need the sheet pattern instead).

---

# Session 2 (concurrent, same day) — AR Measure black-screen root cause + Photo quad-rotation fix

Written by a second AI session running in parallel with the one above, on the same branch. Its
own two commits (`d894a82`, `f3ad223`) are already merged into `feature/photo-reference-measure`
by the time Session 1 wrote §1-§8 — see §7's note. This section is that session's own account,
appended so both sessions' knowledge lives in one file per the user's request. Unrelated to
Box/Cylinder except where §12 flags an actual interaction.

## 9. Photo tab: reference-object quad couldn't fit a rotated object

`QuadFromEdges.kt`'s `quadFromLines` only ever searched for Hough lines near the image's own
horizontal/vertical axes (`angleToleranceDegrees` off 0°/90°). A reference object photographed at
an angle — not aligned with the frame, the common case in practice — had zero candidate lines
within tolerance and silently fell back to the default box, regardless of how clean the edge
detection was. No test caught this because every existing synthetic test used an axis-aligned
rectangle.

**Fix**: try every detected line's own direction as a candidate primary axis, pair it with its
perpendicular as the secondary axis, and pick whichever pair's intersection quad both contains the
tap point and carries the highest combined Hough vote — handles arbitrary rotation, and as a side
effect also discounts a nearby unrelated object's edges (they rarely happen to enclose the tap
point). Added a 35°-rotated synthetic-rectangle regression test; all prior axis-aligned tests still
pass. Commit `f3ad223`.

## 10. Measure tab: cold-start black screen — full root-cause trace

User-reported symptom: after using the AR Measure tab, backgrounding and returning (or killing and
relaunching the app entirely) sometimes shows a permanently black camera feed with a frozen hint
("Move your phone to find a surface"). Previously "fixed" only by clearing the app's storage.

### 10.1 Confirmed root cause (on-device evidence, not inference)

Captured the actual Java exception live, on-device, while the screen was black:

```
com.google.ar.core.exceptions.TextureNotSetException
    at com.google.ar.core.Session.update(Session.java)
    at io.github.sceneview.ar.arcore.ARSession.updateOrNull(ArSession.kt:155)
    at io.github.sceneview.SceneRenderer.renderFrame(SceneRenderer.kt:251)
```

Reading `io.github.sceneview:arsceneview:4.31.0`'s decompiled sources (extracted from the Gradle
cache, `~/.gradle/caches/modules-2/files-2.1/io.github.sceneview/`) confirms why: `SceneRenderer.
renderFrame()` starts calling `session.update()` as soon as a Filament swap chain exists and the
Activity lifecycle is resumed — it never waits for the *separate* `session.setCameraTextureNames(
...)` call (made once, inside `ARSceneView.kt`'s `onSessionCreated`) to have actually completed.
On a device/driver combo where GPU or camera-HAL init is slow relative to the Activity's resume
dispatch, the render loop wins the race and calls `update()` before the texture is registered —
`TextureNotSetException` on every subsequent frame, permanently, because the Session does not
retroactively pick up a texture registered after its first failed frame.

This matches a still-open, multi-year-old Google ARCore SDK issue
(`google-ar/arcore-android-sdk#1170`), not something specific to this app's code — Google's own
guidance there confirms `setCameraTextureNames()` must complete *before* the first `update()`
call, which is exactly the ordering `arsceneview` 4.31.0 does not guarantee.

**Device-dependent, not universal**: reproduced reliably on both a Pixel 6 and a POCO X7 (adb-
connected test devices); a Samsung device tested by the user never showed it. Consistent with a
race whose window width depends on how fast each vendor's GPU/camera driver initializes — this is
why "other apps don't have this bug" isn't evidence of an app-code mistake, and also why the exact
same code can look "fine" on one tester's phone and "broken" on another's.

### 10.2 Fix that shipped (commit `d894a82`)

**Delay mounting `ARSceneView` by 2s (`ArWarmupDelayMs`) on the very first mount per process**,
showing a "Getting the camera ready…" hint meanwhile. Directly informed by a cheap on-device
experiment the user ran: manually switching away from the Measure tab and back (no code change at
all) reliably cleared a live repro of the bug, because that fully unmounts and remounts the whole
screen — Engine included — with a real few-seconds gap in between, which is enough for the slow
driver to catch up. The fix reproduces that same gap automatically on cold start instead of
requiring the user to discover the workaround. Gated by a file-scope `hasAttemptedArWarmup` flag
so only the first mount per process pays for it — a later tab-switch back to Measure in the same
session skips the delay (the driver is already warmed up by then).

Also fixed, same commit: the existing stall-watchdog (recreates the session if no ARCore frame
arrives for `CameraWatchdogTimeoutMs`) was counting time while the app was backgrounded, letting
it fire — and force a pointless remount — while the app wasn't even visible to produce a frame.
Now gated on `Lifecycle.State.RESUMED`; the deadline is pushed out for the whole time the app is
backgrounded and only starts counting real foreground-with-no-frame time.

**Verified**: user confirmed on-device, both the background/resume case and the cold-kill-and-
relaunch case, no longer show the black screen after this fix.

## 11. Wrong turns during this investigation — don't repeat these

Two earlier fix attempts, in order, both shipped-then-reverted after on-device testing showed they
made things *worse*, not better — kept here so nobody re-tries them:

1. **Recreate the whole Filament `Engine` (not just the ARCore `Session`) on every watchdog
   remount** (moved `rememberEngine()`/`rememberMaterialLoader()` inside the `key(instanceKey)`
   block). Reasoning at the time: the stale texture might be Engine-owned, not Session-owned. Result:
   near-100% failure rate afterward, including on a fresh cold start that had previously worked —
   `Engine` is a heavyweight, GPU-resource-owning native object, and destroying/recreating one on a
   tight ~10s retry cadence very plausibly left GPU resources mid-teardown when the next `Engine`
   tried to claim the camera texture, compounding the exact race it was meant to fix. Reverted:
   `Engine`/`MaterialLoader` are created once for the screen's whole lifetime now (matches how the
   library's own samples and presumably other apps use it).
2. **Proactively force a full `ARSceneView` remount on every plain `ON_RESUME`**, not just when the
   watchdog detects a stall. Result: regressed the common case — a short, previously-harmless
   background/foreground round trip now always tore down and reopened the camera, and the close-
   then-immediately-reopen race that introduces turned out to be a *more frequent* failure than the
   rare stale-texture case it was meant to prevent. Reverted: a resume now only resets the
   watchdog's stall-detection clock (`state.lastFrameAtMillis`), giving the *existing* session a
   fresh, fair timeout window instead of judging it by elapsed background time — no remount unless
   the session actually fails to produce a frame after really resuming.

General lesson matching Session 1's §5 lesson: for this repo, an on-device test is the only ground
truth — reasoning about the library's source without a live repro produced two confident, wrong
fixes in a row.

## 12. Known interaction with Box/Cylinder (Session 1's feature) — not yet verified

`ShapeMeasureScreen.kt` (added by Session 1, §4) creates its own `ARSceneView` + `rememberEngine()`
independently of `MeasureScreen.kt` — it does **not** have the `ArWarmupDelayMs` warm-up guard from
§10.2. If a user's first AR screen after a cold launch is Box or Cylinder rather than Measure, it
is likely exposed to the identical `TextureNotSetException` race, unmitigated. This was found by
a code cross-check after both sessions' work was already merged, not by reproducing it on device —
flagged as a probable regression risk for whoever picks this up next, not a confirmed bug. The
straightforward fix, if confirmed, is extracting the warm-up gate (§10.2) into something shared
between `MeasureScreen.kt` and `ShapeMeasureScreen.kt` rather than duplicating the constant and
flag.

## 13. Git state (as of Session 2's commits)

- Same branch as §7: `feature/photo-reference-measure`. Session 2's commits (`d894a82`, `f3ad223`)
  predate Session 1's merge commit chronologically but both are now in the same branch history.
- No push occurred (repo has no `origin` configured, per §7).

## 14. Open questions from Session 2

- §12's Box/Cylinder warm-up gap — confirm on-device, then fix if real.
- Not tested: a background lasting several minutes (only up to ~1 minute was tested) — the 10s
  watchdog and the resume-reset logic should handle it in principle, but it wasn't specifically
  exercised.
- The exact minimum warm-up delay needed is unmeasured — 2s is a deliberately generous guess, not
  a measured floor. Could plausibly be shortened with more on-device timing data if the delay is
  ever felt as sluggish in practice.

---

# Session 3 (concurrent, same day) — ARCore device-gating report for PO/Tech Lead + thermal diagnosis

Written by a third AI session, running in parallel with Sessions 1 and 2 above, on the same
branch. Scope: non-code reporting on the ARCore "device not compatible" issue (not Box/Cylinder,
not the black-screen bug) — appended here per the user's explicit request so all three sessions'
knowledge lives in one file. References Session 2's §9–§14 by number below instead of re-deriving
what it already documented in more depth.

## 15. What this session did

1. Explained the app's AR-measure tech stack and diagnosed why 2 real test devices (Xiaomi POCO
   X7, Samsung Galaxy A07) fail ARCore install with "device not compatible" — confirmed via actual
   on-device logs (`ARCore-InstallService: The device is not supported.` locally on POCO X7;
   `requestInstall=-5` → Play-Store server-side rejection on the A07), not inferred from specs.
   Full findings already live in `plans/reports/report-260824-1520-arcore-hardware-limitation.md`
   and `report-260824-1644-fork-aruler-feasibility.md` (pre-existing, unaffected by anything below
   — no update needed there).
2. Diagnosed why the AR Measure tab runs hot on real devices. Root-caused to three deliberate
   session-config choices in `MeasureScreen.kt`: `DepthMode.AUTOMATIC` (heaviest — per-frame
   depth-from-motion), `PlaneFindingMode.HORIZONTAL_AND_VERTICAL` (~2x plane-finding cost vs one
   orientation), `planeRenderer = true` (GPU mesh draw per tracked plane) — all real feature
   trade-offs, not bugs. **Caveat for whoever cites this next**: these were cited by line number
   at the time (~68–81); Session 2's `d894a82` (§10.2) since rewrote large parts of this same file,
   moving that config block to **lines 189–201**. Re-grep before quoting a line number from either
   this file or the Excel sheet in point 4 below.
3. Confirmed this app's own `AndroidManifest.xml` already declares AR support the permissive way —
   `<uses-feature android:name="android.hardware.camera.ar" android:required="false"/>` +
   `<meta-data android:name="com.google.ar.core" android:value="optional"/>` — meaning Play Store
   is never asked to filter listings by AR support; the AR/no-AR branch is purely a runtime check
   (`ArCoreApk.checkAvailability()`), matching the pattern found in a competitor app's decompiled
   manifest during earlier research this same day. **No manifest change was needed** — it was
   already correct going into this check.
4. Produced two Excel workbooks in `plans/reports/` for non-code audiences: `report-260825-1017-
   arcore-hardware-limitation-baocao.xlsx` (7 sheets — summary, hardware-limit matrix H1–H12,
   manifest declarations, real-device test table, thermal causes, a PO-facing sheet, a tech-lead-
   facing sheet with file/line citations, each table ending in a merged "Kết luận" row) and a
   `-chi-tiet.xlsx` twin holding the pre-shortened/verbose wording, kept by explicit user request
   as a separate permanent file rather than an edit history. Both were built by a throwaway Python/
   openpyxl script that lived only in that session's scratchpad (not checked into this repo) — re-
   editing either `.xlsx` means editing the binary directly or reconstructing the build script.
   **Reporting convention followed, worth keeping if either file is extended**: no competitor app
   is named anywhere in either workbook or in user-facing chat — findings are framed as this team's
   own research, using "Hướng 1" (AR-camera path) / "Hướng 2" (photo + reference-object path)
   rather than naming which app does what.

## 16. Cross-references to Session 2's work (§9–§14) — do not re-derive these

- **§9 (`f3ad223`, rotated-quad fix in `QuadFromEdges.kt`)** is the exact architectural fix that
  `plans/reports/report-260824-1855-canny-hough-tuning-handoff.md` (a still-earlier session's
  handoff doc, `photomeasure/` Canny+Hough tuning) had flagged as identified-but-uncommitted. That
  handoff file has been edited in place this session to mark it committed — see its own updated
  text — rather than left to contradict `git log`.
- **Still genuinely open** (neither this session nor Session 2 touched it): the real-photo failure
  case in that same handoff file — a bright, cluttered desk scene where `autoFitQuad` returned
  `null` (`PhotoMeasure` logcat tag). `f3ad223` may incidentally help (rotation and clutter were
  always plausible overlapping causes, never disentangled for lack of the original photo) but this
  is **unverified**, not assumed-fixed. Whoever picks this up needs the original repro photo/video
  again — see that handoff file's own "Phương pháp tinh chỉnh" section for the no-APK-install JVM
  diagnostic-test method that worked last time.
- **§10 (`d894a82`, cold-start black-screen fix)** and **§12 (its known gap: `ShapeMeasureScreen.kt`
  has no equivalent warm-up guard)** are unrelated to anything this session touched, but are the
  reason point 2 above needs re-verified line numbers — flagging so nobody re-cites the stale ones
  from an Excel sheet built earlier the same day.

## 17. Open items from this session

- Whether to gather real usage data on what % of the actual user base falls into the
  ARCore-uncertified device tier, to size investment in the photo-reference ("Hướng 2") path
  properly — raised as a decision for PO, not resolved this session.
- Whether to act on any of the thermal trade-offs in point 2 (e.g. drop `PlaneFindingMode` to one
  orientation, or make `DepthMode` conditional on the active tool) — flagged as a cost/benefit
  question, not actioned; each one trades away a real capability (wall-measuring, non-planar
  objects) for less heat.
