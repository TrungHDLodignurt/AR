# Code Review — `AR_feature` module, phases 01–09 (`plans/260826-1137-ar-feature-single-module/`)

## Scope
- Whole `AR_feature/` module (namespace `vn.apero.armeasure`), 72 Kotlin files, ~7,260 LOC main + 10 test files.
- `app/` reviewed only for integration issues, not changed.
- This review effectively executes phase-09's "Implementation Steps §6" (`code-reviewer` pass) and re-runs its audits 1–11 and 15 verbatim; audits 12 (on-device 48dp dump), 13 (contrast KDoc spot-check only), 14 (R8/`assembleRelease`) need a build/device and were not re-run here.

## Overall Assessment
High quality, unusually well-documented codebase — anchor lifecycle, undo/redo, and the shared-ARCore-session structural rules are all correct and carefully reasoned. Two real, user-facing bugs found (one leftover Vietnamese string, one missing inset). Everything else is minor/nit or stale-plan-doc bookkeeping.

## Blocking
None.

## Major

1. **Leftover hardcoded Vietnamese strings, drawn straight into the Canvas — `QuadEditorCanvas.kt:88,94`**
   ```kotlin
   drawLabelPill(textMeasurer, "cạnh dài", bottomMid, labelStyle, backgroundColor = LongEdgeColor)
   ...
   drawLabelPill(textMeasurer, "cạnh ngắn", rightMid, labelStyle, backgroundColor = ShortEdgeColor)
   ```
   "long edge"/"short edge" labels on the SCR-22 quad-calibration screen, shown to every user regardless of locale — violates decision 14 (English-default, resource-driven strings) far more directly than the two already-documented `ReferenceObject` label exceptions (which at least have "no `Context` available" as a reason; `QuadEditorCanvas` is a `@Composable` with full `stringResource` access one call site up).
   **Why phase-09's own audit #1/#2 didn't catch it:** both audit greps target `Text(`, `text = "`, `label = "` — a positional string argument to `drawLabelPill(textMeasurer, "…", …)` matches neither pattern. Worth adding `git grep -n 'drawLabelPill(textMeasurer, "'` (or similar) to the audit list so this class of miss doesn't recur.
   **Fix now:** add `armeasure_quad_edge_long` / `armeasure_quad_edge_short` to `strings.xml`, resolve via `stringResource` in `QuadEditorCanvas` (already `@Composable`), pass the resolved `String`s into the plain `DrawScope.drawQuadWithEdgeLabels`.

2. **`ReferencePickerScreen`'s grid has no bottom navigation-bar/gesture-inset padding** — the fourth inset gap the task asked to hunt for.
   `AR_feature/src/main/java/vn/apero/armeasure/photo/presentation/ReferencePickerScreen.kt` — the `LazyVerticalGrid`'s `contentPadding` is `PaddingValues(horizontal = 20.dp)` only; the wrapping `Column` has no `.navigationBarsPadding()` either. This is the module's *only* `LazyColumn`/`LazyVerticalGrid` (confirmed via grep), rendered full-screen under `ArPhotoActivity`'s `enableEdgeToEdge()`. Every other bottom-anchored control in the module already carries `.navigationBarsPadding()` (`ColorPickerBar`, `ArCameraBottomBar`, `MeasureModeSheet`, `ReferenceEditSheet`, `PhotoMeasureScreen`'s checkmark button — the three already fixed this session plus one pre-existing) — this screen was missed. `ColorPickerBar`'s own code comment documents exactly this failure mode on-device ("taps at the very bottom … were silently swallowed by the nav-gesture area"); the last grid row (including the "Add reference" card) is exposed to the same thing.
   **Fix now:** add `.navigationBarsPadding()` to the outer `Column` in `ReferencePickerScreen`, or add bottom inset to the grid's `contentPadding`.

## High Priority
None beyond the two Major items above — no other performance, type-safety, or unguarded-error-handling issues found. ARCore anchor lifecycle (`MeasureState.kt`, `ShapeMeasureState.kt`), the shared-session threading model (`ArSessionState`, `ArCameraScreen`'s watchdog), and Bitmap handling (`ExifBitmapLoader.kt`, `PhotoAnnotations.kt`'s `performSave`) are all correct — see "Positive Observations".

## Medium Priority

3. **`PhotoMeasureScreen`'s `@param referenceStore` KDoc is stale.** It reads "the host constructs and owns this — the module never creates or holds its own instance", which was true before phase-04's ownership reversal but is no longer accurate: `ArPhotoActivity` (inside this module) now constructs `CustomReferenceStore` itself, as its own KDoc correctly documents. Fix now — small doc edit, currently actively misleading.

4. **`DragHandle` (`DraggableHandlesOverlay.kt`) touch/drag target is 28dp** (`radiusDp * 2`, `radiusDp = 14.dp`), with no separate larger hit box — every other interactive element in this module (`ColorDot`, `CmUnitBadge`, `ChromeLightButton`, etc.) wraps a smaller visual inside a documented ≥48dp tap target. `MagnifierLoupe` may be intended to compensate for the small handle, but that tradeoff isn't written down the way similar tradeoffs are elsewhere in the module. Record the decision (or widen the hit box, keeping the 28dp visual) as part of phase-09's still-open on-device audit #12.

5. **Stale plan doc vs. shipped permission set.** `MediaStoreImageSaver`'s KDoc documents a deliberate decision to skip `WRITE_EXTERNAL_STORAGE` entirely (API 29+ only) — matches the actual manifest (`CAMERA` only, confirmed by reading `AndroidManifest.xml`). But `phase-09-final-verification-and-audits.md`'s own "Security Considerations" section and audit #14 still say the release build should ship "`CAMERA` and `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28`" — a plan artifact from before that simplification. Not a code bug (the simpler, shipped behavior is *better*), but the plan should be updated to match so a future session doesn't "fix" the manifest back to the stale spec.

6. **File-size audit (#16) not yet executed/recorded.** 8 files exceed the repo's own <200-line guideline: `ShapeMath.kt` (218), `MeasureState.kt` (238), `ArCameraChrome.kt` (285), `ShapeMeasureState.kt` (286), `ShapeFrameLoop.kt` (302), `PhotoMeasureState.kt` (305), `PhotoMeasureScreen.kt` (321), `ArCameraScreen.kt` (328). Each carries substantial contextual KDoc justifying its architecture (`ArCameraScreen.kt`'s "structural rules — do not violate" section is the clearest), but none states an explicit "kept over 200 lines because…" exception the way the dev-rules ask for. Splitting `ArCameraScreen.kt` in particular would fight the single-mount-point rule it documents — recommend recording the exception rather than splitting.

7. **Camera-capture temp files never cleaned up.** `CameraCapture.kt`'s `createCameraCaptureUri` writes `cacheDir/camera-capture/ref-<timestamp>.jpg` on every "take photo" round trip (custom-reference-object registration and the main photo picker) and nothing ever deletes them. Low risk (cache dir; OS can reclaim under pressure), but unbounded growth for the life of the install.

## Low Priority / Nits

8. `renderAnnotatedBitmap` (`PhotoAnnotations.kt`) uses the Composable-scoped `TextMeasurer` from a `Dispatchers.Default` coroutine (`performSave`) while the same instance may concurrently be read/written by on-screen recomposition on the main thread. `TextMeasurer`'s internal cache is an LRU cache (generally synchronized in AndroidX), so this is unlikely to corrupt state, but Compose doesn't officially document `TextMeasurer` as safe for concurrent cross-thread use. Worth a footnote, not blocking.
9. `PhotoMeasureState.loadPhoto`/`discardPhoto` never explicitly `.recycle()` the previous `photo` Bitmap when replacing/discarding it — inconsistent with how scrupulously every other Bitmap path in this module recycles intermediates (`ExifBitmapLoader`, `performSave`). Not a real leak (GC reclaims native Bitmap memory since API 26), just an inconsistency.
10. `ArMeasureKit.userRequestedInstall` is a plain, unsynchronized top-level `var` on an `internal object` — safe in practice (mutated only from Activity lifecycle callbacks on the main thread), but inconsistent with `ArSessionState`'s own documented rationale for backing similar cross-callback shared fields with Compose `mutableStateOf` rather than a plain `var`.

## Verified Clean (explicit asks)
- **Logging:** zero `Log.` calls anywhere in `AR_feature/src/main` (audit 11 passes).
- **`ar`↔`photo` cross-imports:** none beyond the one documented `ArMeasureHub` → `ArPhotoActivity` exception (audit 5 passes).
- **Hardcoded strings:** clean except finding #1 above; the two documented `ReferenceObject` labels ("A4 paper"/"Payment card") are the only other known exception, confirmed no others exist via `Text(`/`text =`/`label =` grep.
- **`vn.quancua` leftovers:** none in `AR_feature/`.
- **Reflection:** limited to the two documented `Plane::class.java` lookups (`MeasureFrameLoop.kt`, `ShapeFrameLoop.kt`) plus two ordinary `Intent(context, X::class.java)` class-literal constructors (not a risk, not "reflection" in the sense being audited).
- **Public API surface:** exactly 3 symbols (`ArMeasureHub`, `ArMeasureConfig`, `MeasurementImageSaver`) confirmed via `git grep`, plus the 2 manifest-declared `internal` Activities.
- **Shared ARCore session:** exactly one `rememberEngine()`, one `ARSceneView(` call site in the whole module (audit 10 passes).
- **Locales:** no `values-*` directories; English-only as intended.
- **`in`/`inch` label inconsistency:** confirmed present and confined to the documented compact-unit-button vs. menu spot, no further instances found.
- **`planeBasis()`'s previously-flagged untested fallback branch** (dot ≥ 0.9, see prior review-memory note) **is now covered** — `ShapeMathTest.kt` lines 264–273 explicitly exercise it. Good regression coverage added since the earlier finding.

## Positive Observations
- `MeasureState`/`ShapeMeasureState` anchor lifecycle is exemplary: every undo/redo/clear/dispose path detaches exactly the anchors it should, with a correct "still referenced elsewhere" orphan check (`isAnchorOrphaned`) in the shape tool so a reused origin anchor across phase transitions is never double-detached or leaked.
- `UndoRedoStack<T>`'s `onEvict` contract (called exactly once per released entry, never for one still handed back to the caller) is clean, generic, and shared by three very different callers without leaking abstraction.
- `ArCameraScreen.kt`'s KDoc "structural rules" section is a good model of writing down *why* a risky-looking single-mount-point design is correct, with a pointer to the incident report that proves the alternative failed.
- `ExifBitmapLoader.kt` and `PhotoAnnotations.kt`'s `performSave` are careful and correct about recycling every intermediate `Bitmap` on every path, including failure paths.
- `Homography.kt`/`QuadFromEdges.kt` degenerate-input handling (returning `null` rather than NaN/Infinity) is consistently applied and well tested, including the previously-flagged rotated-rectangle regression case.
- `FileProvider` scope is tight: `armeasure_file_paths.xml` exposes only `cache/camera-capture/`, authority is host-namespaced via `${applicationId}`, both Activities are `exported=false` with no intent-filter.

## Recommended Actions
1. Fix finding #1 (hardcoded Vietnamese Canvas labels) — add string resources, thread through `stringResource`.
2. Fix finding #2 (`ReferencePickerScreen` grid inset) — add `.navigationBarsPadding()`.
3. Fix finding #3 (stale `PhotoMeasureScreen` KDoc) while touching the file for #1/#2 review.
4. Record decisions for #4 (drag-handle touch target) and #6 (file-size exceptions) in phase-09/10 docs rather than leaving them silently open.
5. Update phase-09's plan doc to match the shipped, simpler permission set (#5).
6. Optional cleanup: delete stale `camera-capture/` cache files on next launch (#7).

## Unresolved Questions
- Was the `MagnifierLoupe` explicitly relied on as the accessibility mitigation for the 28dp drag handles, or is the small size simply unreviewed? Worth confirming with whoever ran the UI/UX review (`ui-ux-designer-260826-1100-ar-measure-wireframe-review.md`) before deciding to widen it.
- Should audit #1/#2's grep patterns in phase-09 be widened to also catch `drawLabelPill(...)`/other Canvas-text call sites, given finding #1 slipped past them?
