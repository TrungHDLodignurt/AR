# Brainstorm — AR mode 4: "Distance" (independent segments) + 2x2 mode grid

Date: 2026-08-27 16:47 | Branch: `feature/photo-reference-measure` | Module: `:AR_feature`

## Problem statement

AR Measure has 3 tools: chained polyline (each committed point starts the next segment), Box,
Cylinder. Users want a 4th: **independent segments** — tap start, tap end, segment closes; next tap
starts a *new, unconnected* segment. Repeat indefinitely, all segments stay on screen.

Naming: existing chained tool → **Distance chain**; new tool → **Distance**.
Bottom sheet: 3-across row → **2x2 grid**.

## Key finding: this is a pairing rule, not a new tool

`MeasureState` + `buildOverlay` already do everything. The only behavioural delta:

| | Distance chain | Distance (new) |
|---|---|---|
| Segment index pairs | `(0,1) (1,2) (2,3) …` | `(0,1) (2,3) (4,5) …` |
| Rubber band from last point | always | only when point count is **odd** |
| Result emitted on commit | every point from the 2nd | only when a pair closes (count even) |

Everything else — undo/redo with anchor reuse, drag-to-edit, steadiness gate, anchor detach on
dispose, `MeasureOverlay` drawing, label formatting, hit resolution — is pairing-agnostic and
reused verbatim. **`MeasureOverlay.kt` needs zero changes**: it renders whatever `committed` list
it is handed.

## Approaches evaluated

| Approach | Verdict |
|---|---|
| **New `SegmentMeasureState` class** | Rejected. Duplicates undo/redo + drag + steadiness + anchor lifecycle (~200 lines). Two copies of anchor-detach logic is a leak waiting to happen. |
| **`chained: Boolean` on `MeasureState`, 2 instances** | **Chosen.** Zero new classes. Mirrors the existing `ShapeMeasureState(ShapeKind.Box)` / `(ShapeKind.Cylinder)` pattern exactly — same class, different constructor arg, one holder per tool. |
| **1 shared `MeasureState` + a mode flag** | Rejected. A 3-point chain reinterpreted as pairwise becomes "1 segment + 1 orphan point": same data, different meaning, user sees geometry mutate on a mode switch. |
| **Clear points on switch** | Rejected. Contradicts the documented rule in `ArCameraScreen` KDoc ("a swap must not lose tracked points"). |

## Decisions taken (user-confirmed)

| Question | Decision |
|---|---|
| State on mode switch | Separate `MeasureState` per mode, both alive for the screen's lifetime |
| Reticle after a segment closes | No rubber band. Reticle behaves as at start-of-measurement |
| Default tool on entry | **Distance** (independent) — was Distance chain |
| Grid order | `Distance / Distance chain` top row, `Box / Cylinder` bottom row |

## Recommended solution

### 1. Extract the pairing rule as pure maths (do this first)

`buildOverlay` currently has **zero test coverage** (needs `PoseProjector`/ARCore). The pairing rule
is about to be read from three places — segment build, rubber-band gate, result emission — and will
drift if inlined three times.

Add to `ar/domain/geometry/MeasureMath.kt` (the repo's JVM-tested arithmetic home):

    internal fun segmentIndexPairs(pointCount: Int, chained: Boolean): List<Pair<Int, Int>>
    internal fun hasOpenSegment(pointCount: Int, chained: Boolean): Boolean

`hasOpenSegment` = `chained && pointCount > 0` OR `!chained && pointCount % 2 == 1`.
DRY, and covered by `MeasureMathTest` with hand-written expectations for counts 0..5 in both modes.

### 2. `MeasureState(chained: Boolean)`

Constructor arg only; no other change to the class. `ArCameraScreen` holds:

    val distance      = remember { MeasureState(chained = false) }
    val distanceChain = remember { MeasureState(chained = true) }

### 3. `buildOverlay` / `buildLiveSegment` read the pure functions

- committed segments: iterate `segmentIndexPairs(projected.size, state.chained)` instead of
  `0 until size - 1`
- `buildLiveSegment`: early-return `null` unless `hasOpenSegment(...)`

### 4. `commitDistancePoint` — a real bug if skipped

Today it emits `MeasurementResult.Distance` whenever `worldPoints.size >= 2`. In pairwise mode,
placing point 3 (a *new start*) would emit the distance from point 2 to point 3 — a segment that is
never drawn and that the user never asked for. Gate emission on `!hasOpenSegment(size, chained)`,
i.e. a pair actually closed, and take the pair from `segmentIndexPairs().last()`.

### 5. Hints — 2 new strings

`hintForDistance` must tell the user which end the next tap places. Pairwise branch:

- open segment → `armeasure_hint_tap_to_end` ("Tap + to set the end point")
- no open segment → `armeasure_hint_tap_to_start_segment` ("Tap + to start a new measurement")

Chain branch keeps `armeasure_hint_point_on_surface` (`"Point %d on %s"`) unchanged.
`hintFor` gains a 4th `when` arm; both distance tools route to `hintForDistance`, which now
branches on `state.chained`.

### 6. `MeasureModeSheet` → 2x2

Replace the single `Row` with two `Row`s inside the existing `Column` (spacedBy 10.dp).
**Do NOT reach for `LazyVerticalGrid`** — 4 fixed items, lazy layout is pure overhead and breaks
`skipPartiallyExpanded` height assumptions.

Watch: `"Distance chain"` is 14 chars at 12sp SemiBold in a now-half-width card. Set `maxLines = 2`,
`textAlign = Center`, and raise `ModeCard` height 70dp → 78dp so icon + two label lines fit inside
`RoundedCornerShape(percent = 50)`. Verify on the Joy_4 (smaller screen) not just the Pixel.

Glyphs: `↔` stays with Distance (it is literally one segment); Distance chain takes a distinct one
(`⤳` or `⋯`), never the same glyph as Distance — the sheet signals selection by fill+stroke+label
colour precisely because colour alone is a colour-blind failure, and two identical glyphs would
undo that.

### 7. Enum rename — sequence this in two commits

`MeasureTool.Distance` currently *means* chained. Renaming it to `DistanceChain` **and** adding a
new `Distance` in one commit compiles clean while silently rebinding all 12 existing
`MeasureTool.Distance` call sites to the new pairwise mode. The compiler protects nothing.

- **Commit 1:** rename `Distance` → `DistanceChain` only. Compiler forces all 12 sites
  (`ArCameraScreen` ×9, `ArCameraHints` ×2, plus the sheet). No behaviour change. Build + run.
- **Commit 2:** add `Distance`, the second holder, the pure functions, the sheet grid, the strings.

## Files touched

| File | Change |
|---|---|
| `ar/domain/geometry/MeasureMath.kt` | + `segmentIndexPairs`, `hasOpenSegment` |
| `ar/presentation/ruler/MeasureState.kt` | + `chained` ctor arg |
| `ar/presentation/ruler/MeasureFrameLoop.kt` | `buildOverlay` + `buildLiveSegment` use the pure functions |
| `ar/presentation/camera/ArCameraScreen.kt` | enum entry, 2nd holder, default tool, 4 `when` arms (×5 sites) |
| `ar/presentation/camera/ArCameraControls.kt` | `commitDistancePoint` emission gate |
| `ar/presentation/camera/ArCameraHints.kt` | 4th arm + pairwise hint branch |
| `ar/presentation/camera/MeasureModeSheet.kt` | 2x2 grid, 4th card, `modeLabelRes` arm |
| `res/values/strings.xml` | + `armeasure_mode_distance_chain`, 2 hint strings |
| `src/test/.../MeasureMathTest.kt` | pairing cases, counts 0..5 × both modes |
| `AR_feature/README.md`, root `README.md` | tool list says "three tools" / "Distance / Box / Cylinder" in several places |

No test file is deleted; 133 existing tests must stay green.

## Risks

| Risk | Mitigation |
|---|---|
| Silent enum rebind on rename | Two-commit sequence above. Non-negotiable. |
| Pairing logic drifting across its 3 call sites | Single pure function in `MeasureMath.kt`, JVM-tested |
| Docs grep-gates: phase-06 success criteria grep the sheet for non-implemented tool names | Adding `Distance chain` is not one of the forbidden names (Angle/Polyline/Square/Auto-Detection). Confirm the grep before landing. |
| `"Distance chain"` label overflow in a half-width card | `maxLines=2` + 78dp height; verify on Joy_4 `BKB00251473` |
| Drag-to-edit on a point whose pair is closed | Already generic — `buildOverlay` rebuilds pairs from the preview list each frame. No special case, but include in the manual script. |
| Two live `MeasureState`s = 2 anchor sets | `DisposableEffect` must call `releaseAll()` on **both**. One extra line; forgetting it leaks anchors and starves the frame budget. |

## Success criteria

1. `./gradlew :AR_feature:testDebugUnitTest` — 133 existing + new pairing tests, 0 failures.
2. `./gradlew :app:assembleDebug` clean.
3. Device (Pixel `18311FDF60085N`, then Joy_4 `BKB00251473`), Distance mode:
   place 4 points → **2 separate labelled segments**, and visibly **no line between point 2 and 3**.
4. After point 2, no dashed rubber band follows the reticle. After point 3, it does.
5. Undo from 4 points → segment 2 reopens as a dashed band; redo restores it with the same number.
6. Switch Distance → chain → Distance: each mode's geometry comes back untouched.
7. Sheet: 2x2, all 4 labels on ≤2 lines, no clipping, selected card readable in greyscale.

## Next steps

1. Commit 1 (rename) — blocking, mechanical.
2. `segmentIndexPairs` + tests — pure, no device.
3. Commit 2 (mode + sheet + strings).
4. Manual device script (criteria 3–7), both devices.
5. Docs: `AR_feature/README.md` §12 and root README tool lists.

## Unresolved questions

1. Does the deferred phase-06 grep gate (forbidden tool names in `MeasureModeSheet`) also assert an
   exact card *count* of 3? Needs a look at the phase file before commit 2.
2. Glyph for Distance chain — `⤳` renders inconsistently across OEM fonts. Worth a device check, or
   fall back to a vector asset.
3. Should the `+` button's commit toast differ for "segment closed" vs "start placed"? Currently one
   generic `armeasure_toast_point_added`. Left as-is (YAGNI) unless the hint proves insufficient.
