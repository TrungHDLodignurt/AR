# Converting AR_feature to the house MVI convention — risk assessment

Date: 2026-08-27 | Module: AR_feature | Reference: `ADA903-AI-ProfilePhoto`

## The house convention, as it actually is

Read from `ADA903-AI-ProfilePhoto/core/.../ui/common/base/`:

    abstract class MviViewModel<S: MviState, I: MviIntent, E: MviEffect> : ViewModel() {
        private val _state = MutableStateFlow(createInitialState())   // StateFlow<S>
        private val _effect = Channel<E>(BUFFERED)                    // receiveAsFlow()
        private val _intent = MutableSharedFlow<I>()                  // collected in init
        protected fun updateState(reducer: S.() -> S)                 // _state.value = value.reducer()
        fun processIntent(intent: I)
    }

Per screen: a `XContract.kt` (immutable `data class State` + `sealed interface Intent` +
`sealed interface Effect`) and a `XViewModel.kt`. `feature-video` has 6 such pairs. ViewModels are
obtained with `koinViewModel()`.

**Two properties of that base class matter more than the pattern name:**

1. `S` is a single immutable object behind one `StateFlow`. Any field change emits the whole state.
2. **There is no `SavedStateHandle`.** Nothing in the base class survives process death.

## What AR_feature is today

- 4 plain state-holder classes in `remember {}`: `PhotoMeasureState`, `MeasureState`,
  `ShapeMeasureState`, `ArSessionState`. 31 `by mutableStateOf` properties.
- 0 `StateFlow`, 0 `sealed Intent/Effect`, 0 reducer, 0 Koin/Hilt.
- ~22 direct state mutations from composables.
- Undo/redo is already whole-state snapshots (`PhotoSnapshot`).

## Risks, highest first

### 1. Per-frame state churn in the AR tools — the blocking risk

`ArCameraScreen.kt:218` calls `onFrame(...)` from ARCore's frame callback, i.e. **every frame**
(~30-60 Hz). It writes `sessionState.tracking`, `sessionState.anyPlaneTracked`, `state.live`, and
drag samples.

Today: a direct `mutableStateOf` write. Compose's snapshot system invalidates only the composables
and draw scopes that read the specific field.

Under this MVI base class, per frame: `processIntent` -> `viewModelScope.launch` -> `SharedFlow.emit`
-> collector -> `handleIntent` -> `updateState { copy(...) }` -> new state object -> `StateFlow`
emission -> every collector recomposes. That is one coroutine dispatch plus one full state allocation
per frame, and coarse invalidation instead of fine.

We spent today removing dropped frames on the Joy_4 — where a release cold start is 648 ms and a
debug one 2.7 s, i.e. the device has no headroom to spare. Putting a coroutine hop and an allocation
on the AR frame path is the one change most likely to undo that work.

**Mitigation:** do not convert the AR tools. Convert Picture Measure only, and leave
`MeasureState`/`ShapeMeasureState`/`ArSessionState` as state holders. Cost: the module then contains
two patterns, which is worse for "one architecture everywhere" but better for the product.

### 2. MVI alone does not fix the bug class that motivated the question

Every restoration bug fixed today — `chosenReferenceId`, `pendingUri`, `canvasSize`, the A4 fallback
— was Activity recreation losing state. The house base class has **no `SavedStateHandle`**, so a
ViewModel built on it dies with the Activity's ViewModelStore on process death exactly as `remember`
does. It survives *configuration change*, which `remember` does not — a real but partial win.

To actually fix those, the conversion must also add `SavedStateHandle` support, which means either
extending `MviViewModel` in `core` (a change to a shared base other projects use) or deviating from
the house pattern in this module. **That decision is the substance of the work; the pattern rename is
not.**

### 3. Koin becomes a dependency of a module whose selling point is having none

`koinViewModel()` implies Koin. `AR_feature`'s README leads with a 3-symbol public API and "zero
Koin/Hilt references, verified"; §13's R8 story rests on the dependency list being small and
reflection-free. Adding Koin means:
- a host without Koin cannot use the module, or must adopt it;
- new keep-rule surface for R8 (Koin uses reflection for definitions);
- the `MeasurementImageSaver`-as-plain-interface seam becomes redundant or inconsistent.

**Mitigation:** use `androidx.lifecycle.viewmodel.compose.viewModel()` with an explicit factory
instead of `koinViewModel()`. That keeps ViewModels without a DI dependency, at the cost of not
matching the house call style exactly.

### 4. Bitmap inside an immutable state object

`PhotoMeasureState.photo` is a `Bitmap` (up to ~2048px long side). In an MVI `data class State` it
becomes a field copied by reference on every `copy()` — functionally fine, but:
- a ViewModel outliving the Activity now retains a multi-MB bitmap across config change (usually
  good: no re-decode; occasionally bad: retention on a low-heap device);
- `data class` equality on a `Bitmap` is reference equality, so a state whose only change is the
  bitmap's *contents* would not emit. Not a problem today because the bitmap is immutable once
  loaded, but it is a trap for anyone who later edits pixels in place.

### 5. Display-space coordinates are unaffected by the conversion

`quad`/`segments`/`homography` in screen pixels is an orthogonal defect: MVI does not fix it, and
`onCanvasResized` would still be needed. Worth doing **before** any conversion, since moving to
bitmap-space coordinates changes the shape of the state that MVI would then freeze into a contract.

### 6. Effort and test exposure

4 state holders, 31 properties, ~22 mutation sites, ~10 composables. Picture Measure alone is the
bulk of it. The 172 JVM tests are almost entirely pure maths (imaging, geometry, IoU) and would be
untouched — which is reassuring for the imaging code and useless as a safety net for the conversion
itself: **the presentation layer has essentially no test coverage**, so the conversion would be
verified by hand on device, exactly like today's bugs were found.

## Recommendation

Not a single "convert to MVI" task. Three separable pieces, in this order:

1. **Move quad/segments/homography to bitmap space.** Removes `onCanvasResized`, kills a whole defect
   class, and settles the state shape before it is frozen into a contract. No architectural change.
2. **`PhotoMeasureState` -> `MviViewModel` + `SavedStateHandle`.** Fixes the restoration bugs
   structurally and matches the house pattern where it earns its keep. Decide up front whether
   `SavedStateHandle` goes into `core`'s base class (helps every project) or stays local.
3. **Leave the AR tools as state holders**, with the per-frame reasoning written down so it is not
   re-litigated. Revisit only if profiling on a low-end device shows the frame path has headroom.

If the goal is strictly "one architecture across projects", then 3 conflicts with it, and the honest
trade is: uniformity against frame-rate on the cheapest device the app targets. Recommend keeping the
exception and documenting it — which §15 of the module README now does.

## Unresolved questions

1. Does `core`'s `MviViewModel` get `SavedStateHandle`, affecting other projects, or does AR_feature
   deviate? This is the decision the rest depends on.
2. Is a Koin dependency acceptable in a module currently advertised as DI-free, or should it use
   `viewModel()` with an explicit factory?
3. Are the AR tools' per-frame writes actually hot enough to matter? Asserted from the call site and
   today's frame-drop measurements, never profiled directly.
4. The presentation layer has no automated tests. Nothing here changes that, and any conversion
   inherits it.
