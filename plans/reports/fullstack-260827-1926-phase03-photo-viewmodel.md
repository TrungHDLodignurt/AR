# Phase 03 — Picture Measure -> PhotoMeasureContract + PhotoMeasureViewModel

Branch `refactor/mvi-alignment`, no commit/checkout/stash. Spec:
`plans/260827-1910-mvi-alignment/phase-03-photo-measure-viewmodel.md` (updated in place, status done).
Ownership respected: only `photo/**` main+test and my own phase file. Nothing under `ar/**`,
`common/**`, README, gradle, other plan files.

## Files

Deleted: `photo/presentation/PhotoMeasureState.kt` (436 lines).

New, all under `photo/presentation/`:

| file | lines | what |
|---|---|---|
| `PhotoMeasureContract.kt` | ~200 | `State` / `Intent` / `Effect`, plus `Segment`, `LiveLine`, `PhotoSnapshot` |
| `PhotoMeasureReducers.kt` | ~215 | every transition, as pure `State -> State` |
| `PhotoMeasureSavedState.kt` | ~55 | the four persisted keys + `saveableFields()` / `restoring()` |
| `PhotoMeasureViewModel.kt` | ~200 | dispatch, the bitmap, the 3 impure collaborators |
| `PhotoCoordinates.kt` | ~27 | `toVec2`/`toOffset`/`toBitmapSpaceIn`/`toDisplayOffsetIn`, lifted out of the deleted file unchanged |

Rewritten: `PhotoMeasureScreen.kt`. Signature-updated: `PhotoQuadCanvas` (`state: State`,
`onIntent`), `LineDrawScreen` (same; `onCommitted` gone — see Effect), `SegmentLabelOverlay`
(`state: State`, `onDeleteSegment`). KDoc-only: `QuadEditorCanvas`, `ColorPickerBar`, `ImageFit`,
`AutoFitQuad`, `ExifBitmapLoader`, `ReferenceObject`, `HullQuadFit`, `MinAreaRect`, `QuadFromEdges`
(all referenced `PhotoMeasureState.*` by name).

Tests: `PhotoMeasureSegmentsTest.kt` ported (12 -> 15), new `PhotoMeasureRestorationTest.kt` (8).

## The Contract as built

```
State(
  chosenReferenceId: String?, customReferences: List<ReferenceObject>, customReferencesLoaded: Boolean,
  quad: List<Vec2>, homography: Homography?, segments: List<Segment>,
  isDrawingSegment: Boolean, draftLine: LiveLine?, draftColor: Color,
  unit: LengthUnit, isDetectingQuad: Boolean, isEditingQuad: Boolean,
  showPickPhotoSheet: Boolean, showReferenceSheet: Boolean, editingReferenceId: String?,
  undoStack/redoStack: List<PhotoSnapshot>, dragStartSnapshot: PhotoSnapshot?,
)
derived: referenceChosen, reference?, editingReference?, isCalibrated, canUndo, canRedo,
         distanceMmFor(segment), draftDistanceMm()
```

Deviations from the sketch, all deliberate:

- **no `photoLoaded`** — see below.
- **`isCalibrated`, `canUndo`, `canRedo` are derived, not fields.** Two booleans that can disagree
  with the thing they describe are two bugs waiting.
- **`reference` and `editingReference` are derived from an id**, not stored. This is the phase's
  central change; see case B2.
- **`homography` IS in `State`** (9 floats). It is not a `data class`, so `State` equality is by
  reference on that one field — harmless, it is only ever replaced wholesale, and undo needs it in
  the same value as the quad it was solved from.
- 27 `Intent`s, named after gestures, 1:1 with the old public methods. One `Effect`:
  `MeasurementCompleted(MeasurementResult.Photo)`, which replaces `LineDrawScreen`'s `onCommitted`
  callback — the screen no longer reads the segment list back to find out what it just committed.

## Where the bitmap went, and why

`PhotoMeasureViewModel.photo: StateFlow<Bitmap?>`, private `MutableStateFlow` behind it, nulled in
`onCleared`.

- Out of `State` because a `data class` compares a `Bitmap` by reference — state equality would be
  meaningless for the single field that costs megabytes — and a `State` retained across a
  configuration change retains those megabytes with it.
- **No mirrored `photoLoaded: Boolean`** (the phase file's suggestion). A `StateFlow` is already
  observable, so the UI recomposes on load exactly as a `State` field would have made it; adding the
  boolean would be a second source of truth for the same fact with no reader the flow cannot serve.
  The brief's alternative wording — "a nullable non-state accessor" — is what this is.
- The decode stays at the UI edge (`loadRotatedBitmap` in the composable's scope, as today) and the
  pixels arrive as `Intent.PhotoPicked(bitmap)`. Moving the decode into the ViewModel would have put
  an Android `Context` in it to save nothing. A `Bitmap` as an intent payload is honest — "the photo
  I just picked" is the action — and it reaches neither `State` nor the handle.
- Accepted, unchanged from today: a config change *during* the decode still loses that decode,
  because the decode is in the composition's scope. Not this phase's job.

## Where undo/redo went, and why

Into `State`: `undoStack`, `redoStack` (`List<PhotoSnapshot>`, `takeLast(20)` — same bound as
`UndoRedoStack`'s default) and `dragStartSnapshot`. This screen no longer uses `UndoRedoStack` at all
(the AR screens still do; `common/**` untouched).

That is the simplification the phase invited. `PhotoSnapshot` already snapshotted whole state, so
with `State` immutable a snapshot is simply a value — and pushing it makes every undo transition a
pure function like every other reducer, which is what let the tests reach them at all. `PhotoSnapshot`
stays *narrower* than `State` on purpose: pushing the whole `State` would make undo rewind the display
unit, an open sheet, or which screen you are on.

**One drag is still one undo step** — `moveQuadCorner` captures the pre-drag snapshot on the first
frame only (`dragStartSnapshot ?: snapshotNow()`), `commitDrag` pushes it. Now asserted:
`a multi-frame corner drag is a single undo entry` drives 20 frames and expects exactly one new entry,
plus `commitDrag without a drag is a no-op`.

## How `persist` covers the six cases

`PhotoMeasureSavedState.kt` is the whole answer: `saveableFields(): Map<String, Any?>` and
`restoring(saved: (String) -> Any?)`, both pure. `persist(state)` writes the map into the
`SavedStateHandle`; `createInitialState()` reads it back. Four keys, namespaced, all `Bundle`-safe.

| case | covered by | proven by |
|---|---|---|
| B1 reference chosen, no photo | `KeyChosenReference` | unit test |
| B2 custom reference must not read as A4 | **derivation, not persistence** | unit test, both orders |
| B3 photo picker sheet open | `KeyPickPhotoSheet` | unit test |
| B4 reference edit sheet open | `KeyReferenceSheet` | unit test |
| B5 editing which custom reference | `KeyEditingReference` + derivation | unit test |
| B6 camera `pendingUri` | left as `rememberSaveable` in `CameraCapture.kt` | **device only** |

B2 is the one worth reading twice. The old shape stored the id AND a resolved `reference` field, kept
in sync by a `LaunchedEffect(chosenReferenceId, customReferences.size)`; the ordering dependency was
inherent, and the async load is exactly what broke it. Now `State.reference` is a getter over
`(builtInReferenceObjects + customReferences).firstOrNull { it.id == chosenReferenceId }`. A
derivation cannot have a load order. And critically **there is no A4 fallback left anywhere**: the
getter returns `null` for "chosen but not resolvable yet", `confirmReference()` no-ops on null rather
than calibrating against the wrong rectangle, and the screen renders the plain background for the
frame or two before the store's list lands. The failure mode is now "one blank frame", never "measured
against the wrong object".

Two consequences of that derivation, both wanted:

- editing the reference you are currently measuring with needs no re-pointing (the old
  `if (state.reference.id == target.id) state.reference = updated` line is gone).
- an id that resolves to nothing *once the list is known* is stale, and
  `customReferencesLoaded(...)` clears it back to the picker. Unreachable today (delete is only
  offered on SCR-15, where nothing is chosen) — it exists so `reference` can stay fallback-free.

B6: `pendingUri` stayed put. It belongs to the `ActivityResult` launcher living in that composition
and is meaningless without it, so hoisting it to the ViewModel would split one mechanism across two
owners for zero gain, and it is already correct.

Explicitly NOT persisted, stated in KDoc in three places (`State`, `persist`,
`PhotoMeasureSavedState.kt`): the photo bitmap, the quad, the segments, the homography, the undo
history. `Bundle` has a hard transaction size limit and throws rather than truncating. There is also a
test asserting the saved key set is exactly those four and that quad/segments/undo come back empty —
so the gap cannot silently change without a test going red.

## Other notes

- **`createInitialState()` reads constructor arguments**, per the coordinator's mid-task correction:
  the base's `_state` is now `by lazy`, so the restore lives there rather than in an `init` block.
  I had independently hit the same trap and was about to work around it; the fix is better.
- DI: `viewModel(factory = remember { viewModelFactory { initializer { ... } } })` with
  `createSavedStateHandle()`. No Koin, matching phase 01's decision. `UnitPreference` stays outside
  the ViewModel — it is passed in as `initialUnit: LengthUnit` plus
  `persistUnit: suspend (LengthUnit) -> Unit`, so the ViewModel needs no `Context`.
- Save is deliberately **not** an `Intent`: it mutates nothing and needs a `TextMeasurer` and a
  `Density`, which only exist inside composition. It stayed in the composable, unchanged.
- `PhotoMeasureScreen`'s unused `unit: LengthUnit = DefaultUnit` parameter was deleted (the state was
  always seeded from `UnitPreference`; no caller passed it).
- Unit persistence is now write-on-change (`Intent.SetUnit`) instead of `LaunchedEffect(state.unit)`,
  which used to write once redundantly on every entry.
- Quad detection now runs in `viewModelScope`, so a few hundred ms of Canny/segmentation survives a
  configuration change instead of being cancelled by it. Small real improvement, free.
- Corner drag goes through `Intent.MoveCorner`, i.e. one Main dispatch + one `State` copy per touch
  event. Kept as an intent rather than a direct VM API (the phase-04 precedent) because this is
  touch-rate on a still photo, not a 30-60 Hz render callback, and it is dwarfed by the bitmap redraw
  it triggers. Flagging it in case the final round sees drag lag on the Joy_4.

## Verify — done

Isolated `git worktree add --detach <tmp> HEAD` + my `photo/**` + the current
`common/presentation/mvi/` (the base changed under me), same recipe phase 02 used; removed afterwards,
`git worktree list` back to just the pre-existing `experiment/hough-pipeline-fixes` one.

- worktree: `:AR_feature:compileDebugKotlin` BUILD SUCCESSFUL, confirmed non-cached
  (`PhotoMeasureViewModel.class`, `PhotoMeasureReducersKt.class` present in the output);
  `:AR_feature:testDebugUnitTest --rerun-tasks` -> **185 tests, 0 failures, 0 errors, 2 skipped**.
- **main tree**: by the time I compiled, the parallel `ar/**` session's work compiles too.
  `:AR_feature:compileDebugKotlin --rerun-tasks` BUILD SUCCESSFUL, zero `e:` lines anywhere;
  `:AR_feature:testDebugUnitTest --rerun-tasks` -> **185 / 0 / 2**; `:app:compileDebugKotlin`
  BUILD SUCCESSFUL.

185 = 174 (phase 02 baseline) + 3 new segment/drag invariants + 8 restoration tests. The 2 skips are
the pre-existing `RealPhotoAutoFitTest` `@Ignore`s.

## Not provable by code inspection — must be in the final round

- **B6 camera `pendingUri`** — needs a real OEM camera app killing the Activity. Unchanged code, but
  unverified.
- **The platform half of B1-B5.** The four values are unit-tested end to end through the same map
  `persist` writes, but that the Activity is actually handed its bundle back after
  `am kill vn.quancua.artapemeasure` is a platform fact no JVM test reaches. Run each B case as
  written in `final-verification-round.md`.
- **B2 on device specifically**: worth checking the one-blank-frame window is not visible as a flash
  when restoring with a custom reference. If it is, the knob is rendering the last-known label rather
  than the background — do not reintroduce a fallback object.
- **Every gesture/draw edge** — same gap phase 02 reported; nothing here changed a conversion.
- **Drag smoothness** on the Joy_4, per the intent-per-touch-event note above.

## Wanted to touch, outside ownership

- `common/domain/UndoRedoStack.kt` KDoc (lines 9 and 23) still names `PhotoMeasureState` as a caller
  and explains that its low-level `pushUndo`/`popUndo` pair exists *for* it. That caller is gone —
  this screen keeps its history in `State` now. The AR screens still use the class, so only the KDoc
  is stale. Frozen file; not touched.
- `AR_feature/README.md` §15 lists the six `rememberSaveable` patches as the design. Phase 05 rewrites
  §15 anyway; flagging so the new answer (`PhotoMeasureSavedState.kt`, one map) lands there.
- Phase 02's own report asked phase 03 to decide on `PhotoMeasureScreen.canvasSize` / `IntSizeSaver`.
  Neither existed any more by the time I read the file — already gone.

## Unresolved

- Is a blank frame acceptable while a restored custom reference resolves? It is strictly better than
  the old wrong-object behaviour, but nobody has seen it.
- Deleting the reference you are currently measuring with now returns to the picker instead of keeping
  a stale object. Unreachable through the current UI; if a future screen makes it reachable, that is
  the behaviour it will get, deliberately.
- `Homography` is not a `data class`, so `State` equality is reference-based on that field. Fine
  today; would matter if anything ever started diffing `State` for equality-based skipping.
