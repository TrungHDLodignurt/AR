# Claims audit — written assertions vs. code

Date: 2026-09-03 · Branch `refactor/mvi-alignment` @ `25075d3`
Scope: `CLAUDE.md`, `README.md`, KDoc on files in `git diff 541e3d1..HEAD -- AR_feature/src`,
commit messages of `b53ffa2` / `e26f5d6` / `0115830`.
Fact-check only — nothing was fixed.

**~110 checkable claims examined. 9 FALSE · 8 MISLEADING · 9 UNVERIFIABLE.**
Everything not listed below checked out TRUE.

Evidence gathered by reading source, by arithmetic, by `javap` over
`arsceneview-4.31.0.aar`, and by running both verification commands.

---

## Verification commands — both exist and pass

```
./gradlew :AR_feature:compileDebugKotlin   → BUILD SUCCESSFUL
./gradlew :AR_feature:testDebugUnitTest --rerun-tasks → BUILD SUCCESSFUL
```

Test-results XML aggregate: **202 tests, 0 failures, 2 skipped.** `:app` has no `src/test`,
so `:AR_feature`'s count is the whole repo's count. This number contradicts `README.md` — see
FALSE-1.

---

## FALSE

### F1 — `README.md:38, 54, 55` — the test count

> `| :AR_feature | … | 185 |`
> `./gradlew testDebugUnitTest   # all 185 pure-maths tests, no device needed`
> `./gradlew :AR_feature:testDebugUnitTest  # same 185 tests, feature module only`

**Actual: 202** (0 failures, 2 skipped), from a forced `--rerun-tasks` run this session.
`b53ffa2`/`0115830` added `SnapTargetTest` (12 tests) and 2 tests to `ShapeMathTest`; the
README was not updated. Stale by 17.

Aside: "pure-maths" is also loose — `PhotoMeasureRestorationTest` and `LabelContrastTest` are
in the same task and are not maths.

### F2 — `README.md:38` — size of the public API

> "Public API is exactly 3 symbols: `ArMeasureHub` (the entry composable), `ArMeasureConfig`,
> `MeasurementImageSaver` — everything else is `internal`"

There are **4**. The fourth is `ArMeasureContextWrapper`:

```
AR_feature/src/main/java/vn/apero/armeasure/ArMeasureConfig.kt:36: fun interface ArMeasureContextWrapper {
```

A grep for every top-level declaration in `AR_feature/src/main/java` carrying no
`internal`/`private` modifier returns exactly four: `ArMeasureHub`, `ArMeasureConfig`,
`MeasurementImageSaver`, `ArMeasureContextWrapper`.

The module's own README already says so — `AR_feature/README.md:129` is headed
**"## 4. Public API — exactly 4 symbols"** and documents `setContextWrapper` at line 143. The
root README contradicts the document it links to.

### F3 — `README.md:78, 81` — two files in the architecture diagram do not exist

> `├── MeasureFrameLoop / MeasureOverlay / MeasureHit / MeasureState / MeasureMath`
> `└── ShapeFrameLoop / ShapeOverlay / ShapeMeasureState / ShapeMath   (Box, Cylinder)`

`MeasureState.kt` and `ShapeMeasureState.kt` do not exist, and neither does a `MeasureState`
or `ShapeMeasureState` symbol. The MVI conversion renamed them:

- `MeasureUiState` — `ar/presentation/ruler/` (imported at `ArCameraScreen.kt:41`)
- `ShapeUiState` — `ar/presentation/shapes/` (imported at `ArCameraScreen.kt:48`)

The diagram also omits `MeasureFrameStream` / `ShapeFrameStream`, which is where the per-frame
state actually lives and is the single most load-bearing structural fact on that path.

### F4 — `CLAUDE.md:153` — `ArMeasureKit` does not exist

> "The overlay work so far touches **no public API** (`ArMeasureKit`, `ArMeasureConfig`), so the
> host needs no rewiring."

`ArMeasureKit` appears **nowhere** in `AR_feature/src` — only in `plans/260826-1137-…` and
`plans/260825-1745-…`, which describe making it `internal`. It was folded away. The public
surface is the four symbols in F2.

The load-bearing half of the sentence is **TRUE**: `git diff 541e3d1..HEAD` touches neither
`ArMeasureConfig.kt` nor `ArMeasureHub.kt`. Only the parenthetical is wrong — but it is wrong
in the way that matters, because it is the list a future session would diff against.

### F5 — `ShapeFrameLoop.kt:249-251` — `buildEdgeVEdges` KDoc contradicts its own body

> "Box preview while the **third** tap is being aimed: the fixed `phase.edgeU` and the live
> second edge, **both drawn from the shared origin corner** — and nothing else."

They are not both drawn from the origin. The body (`ShapeFrameLoop.kt:277-289`):

```kotlin
val turn = origin + phase.edgeU
val edgeV = projectedEdgeVector(turn, sample.position, phase.normal)
val a = project(origin) ?: return
val b = project(turn) ?: return
out += Segment2D(a, b, …)          // origin -> turn
val c = project(turn + edgeV) ?: return
out += Segment2D(b, c, …)          // turn   -> turn + edgeV
```

The two edges share `turn` (= tap 2), not `origin` (= tap 1). The **same KDoc**, four
paragraphs later, says the opposite and is the correct version: *"the second edge grows from
the end of the first, not from the origin — a second edge sprouting back at tap 1 while the
user is standing at tap 2 is not how anyone traces a box."*

This is a leftover sentence from the pre-`0115830` version that the rewrite missed. It is the
one place in the repo that still describes the bug that commit fixed as if it were the design.

### F6 — `MeasureOverlay.kt:180-183` — claim about what `drawEndpointDot` does

> "Strokes the reticle's projected ring as a closed path — dark underlay first, then white,
> **the same two-pass trick `drawEndpointDot` uses** so the stroke survives a bright real-world
> surface."

`drawReticleRing` does use a dark underlay (`Color(0x57000000)`), but `drawEndpointDot` does
not — its underlay is translucent **white** under an opaque white dot:

```
MeasureOverlay.kt:23   private val LineColor = Color.White
MeasureOverlay.kt:27   private val EndpointHaloColor = Color(0x59FFFFFF)
MeasureOverlay.kt:205  drawCircle(color = EndpointHaloColor, radius = 7.dp.toPx(), center = point)
MeasureOverlay.kt:206  drawCircle(color = LineColor,        radius = 5.dp.toPx(), center = point)
```

White-under-white is a two-pass halo but not the same trick, and it cannot deliver the stated
benefit ("survives a bright real-world surface") — that needs the dark pass `drawReticleRing`
actually has. `drawEndpointDot`'s own KDoc (`MeasureOverlay.kt:202-203`, "a solid white dot
plus a 2dp halo … so it reads over a bright real-world surface as well as a dark one") carries
the same problem; the 2 dp figure itself is right (7 dp − 5 dp).

Compare `PlaneDotField.kt:99` (`DotHalo = Color(0x52000000)`), which does it correctly and says
so: *"Offset a hair down-right of each dot so a white dot survives a white surface too."*

### F7 — `CLAUDE.md:34, 37-40` — the worktree count

> "**Two directories, two branches, same git repo.**"

`git worktree list` right now:

```
…/ar-tape-measure                                    25075d3 [refactor/mvi-alignment]
…/ar-tape-measure-air-draw                           299870a [experiment/air-draw]
…/ar-tape-measure/.claude/worktrees/agent-a54b1c75c280c87a3   9b88231 [experiment/hough-pipeline-fixes]
```

Three, on three branches. The third is an agent-created worktree, so the table is not *wrong
about the two it lists* — but a hard count is exactly what `CLAUDE.md:24-25` forbids writing
down ("a command answers each of those, and a written copy only survives until it starts
lying"). It has started lying.

Everything else in that section is TRUE: `AirDrawFrameStream.kt` exists only on
`experiment/air-draw` (`git ls-tree -r experiment/air-draw` finds it; it is absent from HEAD),
and that branch does carry its own `CLAUDE.md` (`# CLAUDE.md — ar-tape-measure @
experiment/air-draw`).

### F8 — `MeasureFrameStream.kt:24-25` and `README.md:102-103` — the invalidation claim

> KDoc on `OverlayFrame`: "Recomputed each ARCore frame and read inside the Canvas draw lambda,
> so a new frame invalidates only the draw phase — no recomposition, no relayout."
> README: "`OverlayFrame` is read **inside** the Canvas draw lambda, so a new AR frame
> invalidates only the draw phase — no recomposition, no relayout."

A new frame writes far more than `overlay`. `noteLiveSample` (`MeasureFrameStream.kt:154-157`)
writes `live` and the steadiness gate, and `noteSnap` writes `snappedIndex` — all three read
during composition:

```
ArCameraHints.kt:54-61   frames.snappedIndex, frames.live, frames.liveStable
ArCameraControls.kt:52   val snappedIndex = frames.snappedIndex
ArCameraControls.kt:127  addEnabled = viewModel.frames.addEnabled   // = draggingIndex == null && live != null && commitReady
```

`ArCameraScreen` binds `distanceActions(...)`/`distanceHint(...)` on every composition, so the
chrome recomposes every frame. Narrowed to `overlay` alone the sentence would be true; as
written ("a new frame") it is false.

**Already known and already written down** at `CLAUDE.md:95-99`, which states the correction
precisely. The KDoc and the README were never corrected to match, so two of the three
documents still assert the false version. Since `CLAUDE.md:13` sends every new session to the
README first, the false version is the one a session reads first.

### F9 — `CLAUDE.md:13` and `CLAUDE.md:200` — "README is accurate"

> "Read `README.md` for architecture. **It is accurate and current; do not re-derive it.**"
> "`README.md` — architecture, hit-resolution order, why nothing is 3D geometry. **Accurate.**"

Contradicted by F1 (test count off by 17), F2 (API size wrong, contradicting the module README),
F3 (two nonexistent files in the architecture diagram) and F8 (the invalidation claim CLAUDE.md
itself refutes 87 lines later). The instruction "do not re-derive it" makes this the costliest
false claim in the audit: it tells the reader to trust the numbers instead of checking them.

---

## MISLEADING

### M1 — `MeasureFrameLoop.kt:48` — "28 dp is ~4.6 mm of glass"

dp is defined against 160 dpi, so 28 dp = 28/160 in = **4.45 mm**, not 4.6. (Only some real
densities round up to ~4.5: a Pixel 6 at 420 dpi nominal / ~411 physical gives 4.54 mm.) The
argument the number supports is unaffected.

### M2 — `MeasureFrameLoop.kt:132` — `resolveSnap` names the wrong tool

> "**The unchained tool** excludes the open segment's own start."

```
MeasureFrameLoop.kt:154   val excluded = if (hasOpenSegment(points.size, chained)) setOf(points.lastIndex) else emptySet()
MeasureMath.kt:167-168    internal fun hasOpenSegment(pointCount: Int, chained: Boolean): Boolean =
                              if (chained) pointCount > 0 else pointCount % 2 == 1
```

The **chained** tool excludes on *every* non-empty point list; the unchained tool excludes only
on odd counts. Singling out the unchained tool reads as "the chained one does not", which is
backwards — it does so strictly more often.

### M3 — `CLAUDE.md:191` and `PlaneDotField.kt:117, 94` — "~200 dots"

> CLAUDE.md open unknown 2: "Whether **~200 dots** × 2 `drawCircle` per frame holds 60 fps"
> KDoc: "at **~200 dots** a frame the list would allocate two hundred boxed values every frame"

Computed from the actual constants (`LatticeHalfSpan = 8`, `FalloffGain = 1.5f`, the
`alpha <= 0.01f` cut at `PlaneDotField.kt:154`):

| stage | dots |
|---|---|
| 17 × 17 lattice | 289 (= `MaxDots`, the buffer size — correct) |
| after the circular mask (`ring > 8`) | 197 |
| after the alpha cut, `fade = 1f` | **177** |
| after the alpha cut, `fade = MeasuringDotFade (0.55f)` | **161** |

So the worst case is 177 and the steady state while measuring is 161. "~200" overstates the
per-frame cost by 13-24%, which matters because that figure is the whole basis of open
unknown 2 and of the "first lever if not: dot count" mitigation.

### M4 — `PlaneDotField.kt:94` — "two hundred sub-pixel marks"

Dot radius is `BaseDotRadiusDp = 1.5f` coerced into `0.6f..2.2f` dp
(`PlaneDotField.kt:71-73, 168-169`). At a 2.75 density that is a 1.65-6.05 px radius, i.e. a
3-12 px diameter. Not sub-pixel. (Count also per M3.)

### M5 — `README.md:114-116` — hit-source attribution after the snap feature

> "The UI names the source of the last point (`Point 3 on depth map`). A reading you cannot
> attribute is a reading you cannot calibrate."

`b53ffa2` broke the attribution for snapped points. `SurfaceSample.snappedTo`
(`MeasureHit.kt:81-87`) replaces `position` and `pose` but keeps the **raw aim ray's** source:

```kotlin
fun snappedTo(target: Vec3): SurfaceSample = SurfaceSample(
    position = target,
    source = source,          // ← the discarded ray hit's source, not the snapped point's
    hitResult = null,
    …
)
```

So a point committed on a plane hit that snapped onto a depth-map-derived point is labelled
"on plane". The position comes from one reading and the label from another. (The
`atAnchor` path at `MeasureHit.kt:108-114` gets this right — it carries
`points[snapped].source`.) The README section is unchanged and still promises the attribution
holds. This is a real defect, not only a doc drift.

The four-step order in that section (`README.md:107-113`) is otherwise **TRUE** and matches
`resolveSurface` (`MeasureHit.kt:174-210`) exactly, including the `isPoseInPolygon` check.
Worth noting the README describes no snap path at all, so a reader cannot tell that a commit
can now bypass hit resolution entirely (`MeasureFrameLoop.kt:99-109`).

### M6 — `README.md:137-139` — "coaching overlay" listed as not implemented

> "Deliberately not implemented: Shutter capture …, edge snapping to floor/wall seams, position
> smoothing on the reticle, closed-loop perimeter, **coaching overlay**, area measurement, …"

`b53ffa2` added `ScanningIndicator` — a 162 dp centred animated graphic of a phone sweeping
over a perspective grid, shown both while no plane is tracked and during warm-up
(`ArCameraScreen.kt:233-237`), whose own KDoc says *"The reference app puts a 162 dp animation
dead centre for exactly this reason"*. If "coaching overlay" meant ARuler's 6-tip/5 s tip
carousel then the line still stands; if it meant the standard ARCore-style scan-coaching
graphic, it is now implemented. The line is at best ambiguous after `b53ffa2`.

Cross-checked and still TRUE in that list: "edge snapping to floor/wall seams" (`b53ffa2`
snaps to *placed points*, not seams) and "position smoothing on the reticle" (no smoothing
added).

### M7 — `README.md:167` — APK size

> "APK is **~43 MB** debug — Filament plus ARCore."

`app/build/outputs/apk/debug/app-debug.apk`, built 2026-09-03 09:27 (i.e. after these three
commits): **46,916,741 bytes = 44.7 MiB = 46.9 MB.** Low by 1.7 MiB / 3.9 MB depending on which
unit was meant. The bullet's point ("worth measuring against an ASO size budget") is if anything
strengthened.

### M8 — `SnapTargetTest.kt:60-70` — dp/px conflation in the test comment

> "Straddling 28 px by **±1.2 dp** — the case that makes a single threshold unusable."

The test's `enter = 28f` is already a pixel value, and the sampled distances
(26.8, 29.2, 27.4, 28.6) straddle it by ±1.2 **px**. In production `enterPx = SnapEnterDp *
density` = 77 px at density 2.75, where ±1.2 dp would be ±3.3 px. The test is internally
consistent and passes; only the comment names the wrong unit — which matters because that
comment is the only place the test ties itself to the ±1.2 dp premise it claims to guard.

---

## UNVERIFIABLE

None of these can be checked from this repo. Listed so they are not mistaken for verified.

1. **`CLAUDE.md:107-108` / `SnapTarget.kt:15-16` — "±1.2 dp hand tremor at 60 Hz."** No source
   or measurement in the repo, and `b53ffa2`'s body gives an *incompatible* figure for the same
   phenomenon: *"hand tremor of a degree or two straddles it."* At a typical ~60° horizontal FOV
   on a 1080 px-wide screen at density 2.75, one degree is ~18 px ≈ 6.5 dp of reticle travel, so
   "a degree or two" is 6-13 dp — five to ten times the ±1.2 dp figure. One of the two is wrong;
   nothing in the repo says which. The 28/45 dp design does not depend on the exact value.
2. **`CLAUDE.md:113` / `0115830` / `ShapeMathTest.kt:107` — "a 1.55 m side came out as 2.83 m."**
   A device observation. Arithmetically consistent: with a right angle,
   |B−A| = √(2.83² − 1.55²) = 2.37 m, a plausible box side.
3. **`CLAUDE.md:139-179` — the whole AIP936 section.** No 936 checkout on this machine, so
   `:app:compileAppDevDebugKotlin`, the product flavors, the "task is ambiguous" failure mode
   and the remote `AperoVN/AIP936-AIHomeDesign` are all untestable here. The zsh word-splitting
   trap at `CLAUDE.md:168-170` is correct as a shell fact.
4. **`CLAUDE.md:57-63` — the "ever seen running on a device?" table.** Nothing in the repo can
   confirm or refute a device run.
5. **`CLAUDE.md:68-70` — "Confirmed on a Pixel 6."** The *arithmetic* in that entry is TRUE:
   `QuadTopY = 0.34f`, `QuadBottomY = 0.70f`, `IndicatorSize = 162.dp` →
   (0.70 − 0.34) × 162 = **58.3 dp**, and the interior is drawn at
   `Stroke(width = 1.dp)` in `GridColor = Color(0x80FFFFFF)` = 50.2% white
   (`ScanningIndicator.kt:69, 71, 62, 83, 176`). Only the device confirmation is unverifiable.
6. **`CLAUDE.md:81` — "near-100% failure rate"** from moving the Engine inside `key(instanceKey)`.
7. **`MeasureFrameStream.kt:58` — "release cold start 648 ms, debug 2.7 s";
   `MeasureFrameStream.kt:80-82` — depth "swinging between 0.46 m and 3.73 m";
   `MeasureFrameLoop.kt:40-41` — off-ray depth hits "tens to nearly two hundred pixels out".**
   All field measurements with no artefact in the repo.
8. **Every ARuler claim** — `ARulerActivity.I0()`, `f08.z0 = 45 × density`, first-match ordering,
   `ar_search.json` at 827 KB / "a hundred PNG frames", "tracks time-to-first-plane as a product
   metric". No APK or decompiled tree in the repo; the teardown report is the only source and is
   itself the document under audit.
9. **`CLAUDE.md:129-130` — "Of the two usually attached, only the Pixel supports AR."** Device
   state, and serials change between sessions by that same note.

---

## Verified TRUE — the notable ones

Stated explicitly because these are the claims most worth doubting, and they hold.

**`CLAUDE.md` invariants — all five structural ones hold.**

- Engine/MaterialLoader constructed once, outside `key`: `ArCameraScreen.kt:154-155`, with
  `key(instanceKey)` opening only at line 241.
- `ARSceneView` outside every `when (tool)` branch, `tool` absent from `key(...)`:
  `ArCameraScreen.kt:241-289`; the `when (tool)` blocks are at 277 (inside `onSessionUpdated`,
  a callback, not composition), 318 and 332.
- `planeRenderer = false`: `ArCameraScreen.kt:253`.
- **`ARSceneScope` exposes no `PlaneRendererBase` — confirmed against the AAR.**
  `javap -p io/github/sceneview/ar/ARSceneScope.class` lists only `$stable` as a field and no
  member returning `PlaneRendererBase`. The instance is created inside `ARSceneView` and passed
  only to internal lambdas (`ARSceneKt$ARSceneView$35$1.$arPlaneRenderer`). `PlaneRendererV2`
  does carry `MATERIAL_GRID_ALPHA` / `MATERIAL_SCAN_PLANE_RADIUS`, exactly as
  `PlaneDotField.kt:30-34` says — reachable only with an instance nobody hands out.
- `ar/domain/geometry/` free of Compose and ARCore types: no `androidx.*`,
  `com.google.ar.*` or `io.github.sceneview.*` import anywhere in the package, and no
  fully-qualified use either. Screen positions cross as `Pair<Float, Float>`
  (`SnapTarget.kt:37`, `MeasureMath.kt:94`).

**The snap numbers.** `SnapEnterDp = 28f` / `SnapReleaseDp = 45f`
(`MeasureFrameLoop.kt:52, 55`), applied as `enterPx = SnapEnterDp * density`,
`releasePx = SnapReleaseDp * density` (`MeasureFrameLoop.kt:160-161`). Nearest-wins via
`nearestIndexWithin` (`SnapTarget.kt:54, 58`; `MeasureMath.kt:93-113`, strict `<` on the running
best so ties keep the earlier index, as documented). The held-snap branch does gate a rival on
`enterPx`, matching the `@param enterPx` text.

**The parallelogram claim** — `CLAUDE.md:111-113`, `0115830`, `ShapeMeasureViewModel.kt:168-170`:
"`parallelogramCorners(A, B-A, C-B)` is `[A, B, C, A+C-B]`". Arithmetic, from
`ShapeMath.kt:66-67` (`listOf(origin, origin + edgeU, origin + edgeU + edgeV, origin + edgeV)`):

```
c0 = A
c1 = A + (B−A)         = B
c2 = A + (B−A) + (C−B) = C
c3 = A + (C−B)         = A + C − B
```

And `edgeV` is measured from `origin + edgeU` in both the commit path
(`ShapeMeasureViewModel.kt:171`) and the preview (`ShapeFrameLoop.kt:278-279`).

**AIP936 "touches no public API"** — TRUE. `git diff --stat 541e3d1..HEAD -- AR_feature/src`
touches neither `ArMeasureConfig.kt` nor `ArMeasureHub.kt`. (The symbol *names* in that
sentence are wrong — F4.)

**`CLAUDE.md` open unknown 1 — the discarded depth normal.** TRUE, and verified against the AAR:
`hitTestDepth` returns `DepthHitResult(position: Float3, normal: Float3, distance: Float)`
(`javap -p io/github/sceneview/ar/arcore/DepthHitResult.class`), and `MeasureHit.kt:198-204`
consumes only `depthHit.position`, leaving `trackable` at its `null` default so `planeNormal`
is null. (Pedantically the branch does not *set* `trackable = null` as the text says — it
inherits the default; same effect.) Open unknown 4's "40 × 40 cm patch and 2.5 cm spacing" is
also TRUE: `LatticeStepMeters = 0.025f`, span −8..8 → 16 × 0.025 = 0.40 m.

**`README.md` toolchain table** — every row correct: Gradle 9.4.1
(`gradle-wrapper.properties:3`), AGP 9.2.0-rc01, Kotlin 2.4.10, Compose BOM 2026.05.01,
arsceneview 4.31.0 (`libs.versions.toml:4, 5, 8, 12`), compileSdk/minSdk 36/24
(`AR_feature/build.gradle.kts:9, 13`), namespaces `vn.apero.armeasure` / `vn.quancua.artapemeasure`,
two modules only (`settings.gradle.kts:17-18`).

**`README.md` "Nothing is drawn as 3D geometry"** — still TRUE after `b53ffa2`. The dot field
and the reticle ring are `drawCircle` / `drawPath` in the same Compose `Canvas`
(`PlaneDotField.kt:194-208`, `MeasureOverlay.kt:184-193`); no `LineNode`/`TextNode` anywhere.

**`README.md:169-171` permissions** — TRUE. Module manifest declares only
`android.permission.CAMERA`; `app/src/main/AndroidManifest.xml` declares none;
`com.google.ar.core` meta-data is `optional` and both camera `uses-feature` entries are
`required="false"`.

**Commit `b53ffa2`** — every checkable claim holds. 3 cm ring
(`ReticleRingRadiusMeters = 0.03f`); patch sized in metres; solid/hollow distinction untouched;
prior reticle was an 8 dp dot (`dotRadius = 4.dp` before the diff); 162 dp indicator, also used
for warm-up; dots and ring shared by all four tools (built in both
`MeasureFrameLoop.kt:248-263` and `ShapeFrameLoop.kt:197-227`); one haptic tick per lock via
`LaunchedEffect(snappedIndex)` (`ArCameraControls.kt:47-56`) — keyed on the index, so
point-to-point moves re-tick as claimed. `ArMeasureTokens.Signature = Color(0xFF8A9A5B)`
(`ArMeasureTokens.kt:54`) is the olive the KDoc says it is. Only F6 and M4 sit in this commit's
KDoc.

**Commit `e26f5d6`** — both claims hold. The snapped-no-hit commit path exists
(`MeasureFrameLoop.kt:103-108` → `SurfaceSample.atAnchor`), and the index hazard really was
removed: `resolveSnap` now projects `points[i].anchor.pose` directly
(`MeasureFrameLoop.kt:150-153`) instead of indexing `frames.worldPoints`, which is refreshed
later in the frame (line 124) behind the 1 mm dead-band (`refreshWorldPoints`). Nothing
undisclosed in the diff.

**Commit `0115830`** — the diff matches the message, including "two tests" (both present in
`ShapeMathTest.kt`, both passing) and the subtle point that the obvious guard is wrong. Its only
overstatement is that it left F5 behind in the KDoc it rewrote.

---

## Unresolved questions

1. **M5 is a code defect, not a doc drift.** `snappedTo` keeping the aim ray's `HitSource` while
   replacing the position means the on-screen attribution is wrong for every snapped point.
   Which source *should* a snapped point report — the snapped-to point's (as `atAnchor` does),
   or a new `HitSource.Snapped`? Not fixed here; flagged for a decision.
2. Does "coaching overlay" in `README.md:138` mean ARuler's tip carousel or the scan graphic
   (M6)? Only the author can settle whether that line is now false.
3. Which tremor figure is real — ±1.2 dp or "a degree or two" (UNVERIFIABLE-1)? They differ by
   5-10×. Open unknown 3 ("whether 28 / 45 dp feel right with a real hand") is the experiment
   that would settle it.
4. F7: is the `.claude/worktrees/` agent worktree permanent enough to belong in the table, or
   should the count simply be dropped in favour of `git worktree list` per the file's own rule?
