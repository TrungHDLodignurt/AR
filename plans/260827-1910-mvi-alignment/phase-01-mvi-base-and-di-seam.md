# Phase 01 — local MVI base + the DI seam

Context: [plan.md](plan.md) · [risk assessment](../reports/report-260827-1910-mvi-conversion-risk.md)
Priority: first. Status: **done** 2026-08-27.

## Goal

A local `internal` MVI base identical in shape to `ADA903-AI-ProfilePhoto/core/.../ui/common/base/`,
plus `SavedStateHandle`, plus a decision on how ViewModels are obtained.

## Reference to copy from, verbatim

`core/src/main/java/.../ui/common/base/MviContract.kt` and `MviViewModel.kt`:

    interface MviState ; interface MviIntent ; interface MviEffect

    abstract class MviViewModel<S: MviState, I: MviIntent, E: MviEffect> : ViewModel() {
        private val _state  = MutableStateFlow(createInitialState())
        val state: StateFlow<S> ; val stateValue: S
        private val _effect = Channel<E>(BUFFERED) ; val effect = _effect.receiveAsFlow()
        private val _intent = MutableSharedFlow<I>()   // collected in init -> handleIntent
        protected abstract fun createInitialState(): S
        protected abstract fun handleIntent(intent: I)
        fun processIntent(intent: I)
        protected fun updateState(reducer: S.() -> S)
        protected fun sendEffect(effect: E)
    }

Keep the names, generics, and member order the same so a reader moving between projects sees one
pattern. Diverge only where noted below, and say why in KDoc.

## Files to create

- `common/presentation/mvi/MviContract.kt` — the three marker interfaces, `internal`
- `common/presentation/mvi/MviViewModel.kt` — the base, `internal`, plus the `SavedStateHandle` seam

## The SavedStateHandle seam — as built

The house base has none, which is why converting would not by itself fix any of today's restoration
bugs. Implemented as a single hook rather than a per-field helper:

    protected open fun persist(state: S) = Unit    // called after every updateState

Subclasses take `SavedStateHandle` in their own constructor, restore inside `createInitialState()`,
and override `persist` to write back. Rationale in the KDoc: it makes "what survives" one reviewable
decision per screen instead of remembering to guard each new field — which is exactly how this module
accumulated six separate `rememberSaveable` patches, each one shipped as a bug first.

KDoc also states the limit: only small `Parcelable`-safe values, never a `Bitmap` or a large list,
because the handle goes through a `Bundle` with a hard transaction size cap.

## The DI decision — resolve before phase 03

Koin 4.0.4 is in both `ADA903` and `AIP936` catalogs, so `koinViewModel()` matches the house style.
But this module currently ships zero DI and README §13's R8 story rests on a small, reflection-free
dependency list.

Two options, pick one and record it here:

1. **`androidx.lifecycle.viewmodel.compose.viewModel()` with an explicit factory.** No new
   dependency, no keep rules, works in a host with or without Koin. Call style differs from the house.
2. **Koin.** Matches the house exactly. Adds a dependency to a module advertised as DI-free, and new
   R8 keep surface.

**DECIDED: option 1**, `viewModel()` with an explicit factory. The module's portability claim is
load-bearing for the apply skill, and the call-site difference is one line per screen. Catalog gains
`androidx-lifecycle-viewmodel-compose` and `androidx-lifecycle-viewmodel-savedstate` at the existing
`lifecycle` version — no new version, no DI, no R8 keep surface.

## Todo

- [x] Read both house base files in full; note any member this summary missed
- [x] Create `MviContract.kt`, `MviViewModel.kt`, `internal`
- [x] Add the `persist` hook + KDoc on what may go in the handle
- [x] Decide the DI option, write the decision into this file
- [x] `:AR_feature:compileDebugKotlin` + `testDebugUnitTest` (172, 2 skipped)

## Success criteria

Base class compiles, is unused so far, and diffs against the house version show only the documented
additions. No behaviour change, so no device check needed in this phase.
