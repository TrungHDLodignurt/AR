# AR_feature -> house MVI convention

Branch: `refactor/mvi-alignment` (from `feature/photo-measure-accuracy`)
Decided 2026-08-27. Risk assessment: `plans/reports/report-260827-1910-mvi-conversion-risk.md`

## Why

Every other Apero/VSL app is MVI (`MviViewModel<S, I, E>` + `XContract.kt` + `XViewModel.kt`, Koin).
This module is plain Compose state holders. That is defensible in isolation but this module exists to
be copied into those apps, so a one-off architecture is a tax every future maintainer pays.

Doing it now, not later: the presentation layer has **no automated tests**, so the conversion is
verified by hand — and right now the flows are fresh, both devices are attached, and every lifecycle
hole found today is still known.

## Decisions

| Question | Decision |
|---|---|
| Scope | Every screen gets Contract + ViewModel. No exceptions visible to a reader |
| Per-frame AR values | **Outside** the MVI `State` object |
| `SavedStateHandle` | Local to AR_feature. `core` untouched |
| DI | Koin is already in ADA903 and AIP936 (4.0.4), so it is acceptable — but see phase 01 |

### Per-frame values stay out of State

`ArCameraScreen.kt:218` calls `onFrame(...)` from ARCore's frame callback, ~30-60 Hz, writing
tracking flags and the live measurement. Routing that through
`processIntent -> SharedFlow -> handleIntent -> updateState { copy() } -> StateFlow` means a coroutine
dispatch and a full state allocation per frame, with coarse invalidation replacing Compose's
fine-grained field invalidation. On the Joy_4 — release cold start 648 ms, debug 2.7 s — there is no
headroom for that.

Transient render state is not UI state. The ViewModel owns what the user drives (tool, unit,
committed segments, undo); the frame stream stays in a plain holder the renderer reads directly.

### Local base class is not a deviation

AR_feature cannot depend on ADA903's `core` — different project. It must define its own
`MviState`/`MviIntent`/`MviEffect`/`MviViewModel` regardless, mirroring the house names and shape.
Adding `SavedStateHandle` there is therefore free and touches nothing shared. All of it is
`internal`, so a host never sees a second base class.

## Phases

| # | Phase | Est | Gate |
|---|---|---|---|
| 01 | [Local MVI base + decide the DI seam](phase-01-mvi-base-and-di-seam.md) | 1h | compiles, 172 tests green |
| 02 | [Coordinates to bitmap space](phase-02-bitmap-space-coordinates.md) | 3h | `onCanvasResized` deleted; measure a photo on device and compare to a ruler |
| 03 | [Picture Measure -> ViewModel](phase-03-photo-measure-viewmodel.md) | 4h | every lifecycle case from today re-tested by hand |
| 04 | [AR screens -> ViewModel, frame stream outside State](phase-04-ar-screens-viewmodel.md) | 4h | frame-drop count before/after on Joy_4 |
| 05 | [Docs + apply skill](phase-05-docs-and-apply-skill.md) | 1h | README §15 rewritten; skill matches |

Phase 02 comes before any conversion on purpose: it changes the shape of the state, and freezing the
wrong shape into a `data class State` costs twice.

## Not in scope

- Touching `core` in any other project.
- Adding tests to the presentation layer. It has none; that does not change here, and it is the main
  reason every phase gate is a manual device check.
- Persisting the photo bitmap across process death.

## Risks

| Risk | Handling |
|---|---|
| No test net on presentation | Every phase gate is an explicit on-device check, listed in the phase file |
| Phase 04 regresses AR frame rate | Measured, not assumed: `Choreographer` skip count before and after, release build, Joy_4 |
| Phase 03 reintroduces a lifecycle bug | The six known cases are enumerated in the phase file as a checklist |
| Conversion drifts from the house shape | Phase 01 pins the base class against ADA903's verbatim |
