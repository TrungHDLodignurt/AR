# Review — `common/**`, public surface, build/manifest, README

Branch `refactor/mvi-alignment` @ `d072207`. Read-only; nothing modified.
Scope: `common/**` + tests, `ArMeasureConfig.kt`, build/manifest/catalog/proguard/settings, README accuracy.

Headline: public surface is **correct** (exactly 3 symbols, re-verified past the documented grep's blind
spots). Two real code defects (one perf, one dead-abstraction). Two build-contract defects that break a
host following the README literally. README has 6 confirmed false claims.

---

## CONFIRMED

### C1 — HIGH — `state` getter allocates a new flow per access; every recomposition restarts collection

`AR_feature/src/main/java/vn/apero/armeasure/common/presentation/mvi/MviViewModel.kt:37`

```kotlin
val state: StateFlow<S> get() = _state.asStateFlow()
```

`asStateFlow()` returns a **new** `ReadonlyStateFlow` instance on every call. `collectAsStateWithLifecycle(flow)`
delegates to `produceState(initialValue, flow, lifecycle, minActiveState, context)`, whose keys feed a
`LaunchedEffect` — a new flow identity therefore **cancels and restarts the collection**.

Call sites, all affected:
- `ar/presentation/camera/ArCameraScreen.kt:142-146` — five ViewModels collected in one composable
- `photo/presentation/PhotoMeasureScreen.kt:126`

Consequence: every recomposition of `ArCameraScreen` tears down and relaunches 5 coroutines and 5
`repeatOnLifecycle` registrations. `ArCameraScreen` recomposes on every state change, and the AR path
drives state changes off the frame loop — so this lands on the per-frame path the whole §15 "frame stream
is not State" argument exists to protect. Not a correctness bug (`produceState` keeps its remembered
`MutableState` across restarts, and `StateFlow` replays, so no flicker or lost value) — purely churn.

This is a **regression introduced by the `by lazy` change** (`9c28b9c`), not pre-existing: with a plain
`_state` initializer the idiomatic `val state = _state.asStateFlow()` initializer works; with a lazy
backing field that initializer would force the lazy at construction and defeat the fix, so it was turned
into a `get()`. The fix is to make the public flow lazy too, not to revert:

```kotlin
private val _state: MutableStateFlow<S> by lazy { MutableStateFlow(createInitialState()) }
val state: StateFlow<S> by lazy { _state.asStateFlow() }
```

Evidence that the intended pattern is understood elsewhere: `photo/presentation/PhotoMeasureViewModel.kt:52`
does exactly the stable-identity thing (`val photo: StateFlow<Bitmap?> = _photo.asStateFlow()`), and sits
one line away from an affected `state` collection at `PhotoMeasureScreen.kt:126-127`.

Confidence: high. A specific missing test would have caught it — there is **no test file for
`MviViewModel` at all**; a single `assertSame(vm.state, vm.state)` fails today.

### C2 — HIGH — README §2's version catalog is missing 3 aliases the module's own `build.gradle.kts` calls

`AR_feature/README.md:71-81` (`[libraries]` block).

`AR_feature/build.gradle.kts` references `libs.androidx.lifecycle.viewmodel.compose` (:45),
`libs.androidx.lifecycle.viewmodel.savedstate` (:46) and `libs.mlkit.subject.segmentation` (:51).
None of the three is in README §2's `[libraries]` block. The `mlkitSubjectSegmentation` **version** is
listed (:69) but its library alias is not.

Consequence: a host that follows §1–§2 exactly — which is the entire point of the document, and §2
explicitly says "append to the host's `gradle/libs.versions.toml`" — fails at Gradle *configuration* time
with `Unresolved reference: viewmodel` / `subject`. This is the single most load-bearing block in the
integration guide and it is unbuildable. §2 also still calls itself "Transcribed from this repo's own
`gradle/libs.versions.toml`", which is now false.

Missing lines (verbatim from `gradle/libs.versions.toml:32-34`):
```toml
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-savedstate = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-savedstate", version.ref = "lifecycle" }
mlkit-subject-segmentation = { group = "com.google.android.gms", name = "play-services-mlkit-subject-segmentation", version.ref = "mlkitSubjectSegmentation" }
```

Confidence: certain.

### C3 — MEDIUM — `Theme.ArMeasure` is the module's one unprefixed resource; §10's collision claim is false

`AR_feature/src/main/res/values/themes.xml:8`, referenced by `AndroidManifest.xml:66` and `:73`.

`build.gradle.kts:10` sets `resourcePrefix = "armeasure_"`. Every resource obeys it except this style.
Swept all resource types, not just `<string name=`:

```
grep -rhoE '<(string|style|color|dimen|bool|integer|array|string-array|plurals) name="[^"]*"' \
  AR_feature/src/main/res/values/ | grep -v 'name="armeasure_'
→ <style name="Theme.ArMeasure"
```

Consequence: a host app declaring its own `Theme.ArMeasure` silently wins the resource merge (app
resources override library resources without warning) and both module Activities get the host's theme —
which is precisely the "module ships its own theme" decision recorded in the README preamble (lines 20-22)
being defeated. Also blocks `resourcePrefix` lint on the module.

**This is the exact blind spot my prior memory note flagged**: §16 audit 4 is written as "every `<string
name=` starts with `armeasure_`", so re-running the documented audit passes forever while the real
invariant ("`resourcePrefix` intact", which is the audit's own title) is violated. Suggested rename:
`Theme.ArMeasure` → `armeasure_Theme` (or `Theme.Armeasure_Module`), updating both manifest references.

Confidence: certain for the unprefixed name; high for the override behaviour.

### C4 — MEDIUM — `UndoRedoStack` is now a redo-only stack with a dead undo half, and its KDoc says otherwise

`AR_feature/src/main/java/vn/apero/armeasure/common/domain/UndoRedoStack.kt`

Still used — by `ar/presentation/ruler/MeasureViewModel.kt:72` and
`ar/presentation/shapes/ShapeMeasureViewModel.kt:78`. The photo screen dropped it in the MVI conversion
(`photo/presentation/PhotoMeasureReducers.kt:22` records the replacement: "two lists and one nullable
field replace the mutable `UndoRedoStack` this screen…").

Usage census across `AR_feature/src/main`:

| member | call sites | status |
|---|---|---|
| `pushRedo` / `popRedo` / `dropRedo` / `canRedo` / `clear` / `any` / `drainWithoutEviction` | 6 / 2 / 3 / 7 / 2 / 1 / 1 | live |
| `push` (:45) | 0 | dead |
| `undo` (:51) | 0 | dead |
| `redo` (:58) | 0 | dead |
| `pushUndo` (:65) | 0 | dead |
| `popUndo` (:72) | 0 | dead |
| `canUndo` (:39) | 0 | dead |

(Every `canUndo` hit elsewhere in the module is unrelated derived state — `MeasureContract.kt:40`,
`ShapeContract.kt:95`, `PhotoMeasureContract.kt:146` — not this property.)

Nothing writes `undoDeque` any more, so it is permanently empty in production. That makes `maxDepth`
eviction redo-only, and makes the `undoDeque` branches of `clear`, `any`, `drainWithoutEviction` and
`evictOverflow` unreachable. The class name and its "textbook pair-of-deques" framing now describe
something the module does not do.

Two stale KDoc claims in the same file:
- **:8-10** — "all three measure tools' state ViewModels for the AR ruler, the shapes and the photo screen
  share one tested implementation". The photo screen does not. Two tools, not three.
- **:23-25** — the second justification bullet for the low-level API is entirely about the photo screen's
  whole-state-snapshot undo. That caller is gone. (Bullet 1, the AR ruler/shapes reason, still holds and is
  the only reason the class still exists.)

Consequence beyond docs: `UndoRedoStackTest` spends assertions on the dead half, so part of the 185-test
count covers unreachable code, and a reader is invited to "fix" the AR ViewModels onto the textbook
`push`/`undo`/`redo` API — which bullet 1 explicitly warns would detach an anchor still on screen.
Recommend either deleting the dead six members and renaming to reflect redo-only, or (cheaper) correcting
the KDoc and leaving the members. YAGNI argues for deletion.

Confidence: certain on the census; the deletion-vs-document call is yours.

### C5 — MEDIUM — §13's "R8 verified clean" cites an audit that predates the dependencies it now covers

`AR_feature/README.md:382-383` — "Verified clean on the merged module … see the R8 release-hardening audit
report dated **2026-08-26**".

`gradle/libs.versions.toml` gained `androidx-lifecycle-viewmodel-compose` and
`androidx-lifecycle-viewmodel-savedstate` on **2026-08-27** in `cc1edc4`. §13's own "Revisit trigger" and
§16 audit 10 both say to re-run §13/§14 after any dependency bump; that did not happen.

On the substance I believe the conclusion still holds and **no keep rule is needed**: all four ViewModels
are obtained with explicit factories (`ArCameraViewModel.kt:75`, `ShapeMeasureViewModel.kt:293`,
`MeasureViewModel.kt:247`, `PhotoMeasureViewModel`), so the reflective `NewInstanceFactory` path is never
taken, and `androidx.lifecycle-viewmodel` ships its own consumer rule for that path regardless. But §13
states this as *verified*, and it is not — the mapping.txt it describes was produced before these
artifacts existed. Either re-run `:app:assembleRelease` and re-date the claim, or downgrade the wording to
"expected clean, re-verification owed".

Confidence: certain that the evidence is stale; high that the conclusion is nonetheless correct.

### C6 — LOW/MEDIUM — `formatterCache` is an unsynchronized `HashMap` guarding non-thread-safe `NumberFormat`

`AR_feature/src/main/java/vn/apero/armeasure/common/domain/LengthUnit.kt:34-43`

A file-level `HashMap` mutated by `getOrPut`, caching `NumberFormat` instances that are themselves
documented as not thread-safe (`format()` mutates internal state). The KDoc at :16 advertises the function
as running "from `onSessionUpdated`, a per-frame hot path".

I traced all 17 `formatLength` call sites (frame loops + composition) and they are all main-thread today,
so this is latent rather than live — which is why it is not rated higher. But it is a shared mutable
process-global with no guard, sitting in the module's `common` layer, one off-main caller away from
either a lost cache entry or garbage/`ArrayIndexOutOfBoundsException` out of `format()`. Cheap fix:
`ConcurrentHashMap` plus a per-call `clone()`, or a `ThreadLocal`, or just document the main-thread
requirement in the KDoc so the next caller knows.

Confidence: certain on the code shape; the "all callers are main-thread" trace is high but not exhaustive
(SceneView's dispatch thread for `onSessionUpdated` I took as main by inspection, not by instrumentation).

### C7 — LOW — `updateState` is a non-atomic read-modify-write, and calls an open method during construction

`MviViewModel.kt:62-66`

```kotlin
val next = _state.value.reducer()
_state.value = next
persist(next)
```

Two notes, neither currently reachable as a bug:
1. Not atomic — concurrent callers lose updates. `_state.update { }` is the drop-in fix and costs nothing.
   All current callers are main-thread, so this is prophylactic.
2. `persist(next)` is an **open method invoked from `updateState`**, and `ArCameraViewModel.kt:27-38`
   calls `updateState` from its `init` block — so `persist` runs during subclass construction. Safe today
   because `ArCameraViewModel`'s only persisted dependency (`savedState`) is a constructor *parameter*
   (assigned before init blocks run). It would not be safe for a subclass whose `persist` reads a
   class-body property declared below the init block — e.g. the shape of `ShapeMeasureViewModel.kt:68,78`.
   Worth one KDoc sentence on `persist`.

Confidence: high.

---

## Answers to the specific questions asked

**1. Is the `by lazy` correct?**

- **Thread safety** — yes. Default `LazyThreadSafetyMode.SYNCHRONIZED`; double-checked, correct.
- **Unexpected initialization via accessors** — yes, and this is where the real problem is, though not the
  one anticipated. `state`, `stateValue` and `updateState` all force init, which is fine and intended. The
  damage is C1: forcing the fix through a `get()` on the *public* flow broke identity stability.
- **`processIntent` before first collection** — safe. The collector is launched in the base `init`, so on
  `Dispatchers.Main.immediate` the body runs inline and parks in `collect` before any subclass code
  executes. An `emit` arriving before that would suspend, not drop.
- **Zero-buffer `MutableSharedFlow` dropping or deadlocking** — no drops (replay=0 with a suspending
  `emit` is lossless, not lossy; a `tryEmit` would have been the lossy shape and is not used). No deadlock:
  a `handleIntent` that re-enters `processIntent` defers onto `viewModelScope` rather than re-entering
  `emit`. Ordering across concurrent `processIntent` calls is FIFO in practice via the suspended-emitter
  queue. The one real consequence is that `processIntent` is asynchronous, so `processIntent(x)` followed
  by a `stateValue` read sees pre-`x` state — correct MVI, but worth a KDoc line.
- **Bonus, out of my scope but about the base's contract**: `ar/presentation/camera/ArCameraViewModel.kt:28-32`
  still carries a comment asserting "the base class builds its initial state from a field initializer,
  which runs *before* a subclass's constructor-parameter fields are assigned". That has been false since
  `9c28b9c`, and it directly contradicts `PhotoMeasureViewModel.kt:66-68`, which documents the opposite
  correctly. One of the two will mislead whoever reads it next.

**2. Public surface — CORRECT, verified past the documented grep.**

§4's own command is anchored (`^…`) and would miss a declaration carrying a leading modifier, a top-level
`suspend fun`, an `annotation class`/`value class`/`typealias`, or a decl behind a multi-line annotation.
I re-ran it as a full column-0 sweep of every non-`internal`, non-`private` line, plus a separate pass
resolving what follows every top-level `@Composable`/`@OptIn`. Result is exactly:

```
ArMeasureConfig.kt:15   fun interface MeasurementImageSaver
ArMeasureConfig.kt:30   object ArMeasureConfig
ar/presentation/host/ArMeasureHub.kt:55   fun ArMeasureHub(modifier: Modifier = Modifier)
```

Plus the two manifest Activities, as documented. No leakage from the file moves. `ArMeasureConfig` itself
is sound: `@Volatile` backing field, `internal` read accessor, one-shot `check()` on write — and the
"never a bare public `var`" rationale at :22-28 still matches the code.

**3. Dependencies — versions consistent, nothing unused, one doc gap (C2) and one stale verification (C5).**

Both lifecycle-viewmodel artifacts use `version.ref = "lifecycle"` (`2.9.4`), matching the pre-existing
`lifecycle-runtime-compose`. All three are genuinely used: `viewModel()` ← `-compose`,
`createSavedStateHandle()` ← `-savedstate`, `collectAsStateWithLifecycle` ← `-runtime-compose`. Swept the
rest of `AR_feature/build.gradle.kts` for unused entries — `core-ktx` (`androidx.core.view/content`),
`activity-compose` (`setContent`, `rememberLauncherForActivityResult`), `ui-graphics`, `mlkit`,
`testImplementation(libs.json)` are all reachable. Nothing to remove. `app/proguard-rules.pro` remains
deliberately empty and I agree it should stay that way.

**4. Manifest — accurate.** Every entry in §6's list matches `AndroidManifest.xml` line for line
(permission, both `uses-feature required="false"`, both `meta-data`, the provider with its
`${applicationId}.armeasure.fileprovider` authority and `FILE_PROVIDER_PATHS` meta-data, both
`exported="false"` Activities with no intent-filter). No manifest change is needed for the MVI refactor —
lifecycle-viewmodel contributes no manifest entries. The one thing §6 does **not** mention is that a host
also inherits the unprefixed `Theme.ArMeasure` style (C3), which is the only real host-collision surface
in the module and belongs in that section precisely because §6 is titled "nothing for the host to add".

**5. README — see the dedicated section below.**

**6. `UndoRedoStack` — still used by two of three callers; KDoc is stale. See C4.**

---

## README findings

Each with exact line and what it should say. §14's test count is the one thing I can confirm is *right*.

| # | Line(s) | Claim | Reality | Should say |
|---|---|---|---|---|
| R1 | 71-81 | `[libraries]` block "transcribed from this repo's own `gradle/libs.versions.toml`" | 3 aliases the module calls are absent — see C2 | add the 3 lines quoted in C2 |
| R2 | 322-324 | "every name prefixed `armeasure_` (`resourcePrefix` is enforced at the module level, so a collision with a host's own resource name is impossible)" | `Theme.ArMeasure` (themes.xml:8) is unprefixed; `resourcePrefix` is a lint check, not a build gate | "every **string** name is prefixed `armeasure_`; the one exception is the `Theme.ArMeasure` style, which a host declaring the same name would override" — or fix the resource and keep the claim |
| R3 | 512 | audit 4, "Resource prefix intact — every `<string name=` starts with `armeasure_`" | passes while the invariant in the audit's own title is violated | broaden to all resource types: `grep -rhoE '<(string\|style\|color\|dimen\|bool\|integer\|array\|plurals) name="[^"]*"' AR_feature/src/main/res/values/ \| grep -v 'name="armeasure_'` → empty |
| R4 | 516 | audit 8, "`git grep -c 'rememberEngine\|ARSceneView('` → **1 each**" | `-c` counts matching lines for the combined pattern and can never print "1 each"; actual output is `ArCameraScreen.kt:3` (import + `rememberEngine()` at :150 + `ARSceneView(` at :238) | "→ one file, 3 lines: the import, one `rememberEngine()` (:150), one `ARSceneView(` (:238)". The underlying §12 invariant **is** intact — only the stated expected output is wrong |
| R5 | 517 | audit 9, reflection sweep "→ only the **two** documented `Plane::class.java` lookups" | 6 hits: 2× `Plane::class.java` + 2× `Activity::class.java` in `newIntent` (`ArCameraActivity.kt:104`, `ArPhotoActivity.kt:52`, both `Intent(Context, Class)`, not reflection) + 2 prose hits in KDoc | "→ 6 hits: the two `Plane::class.java` lookups, two `Intent(context, X::class.java)` constructors (not reflection), two KDoc mentions" |
| R6 | 566-572 | ">200-line file list" | 3 of the 10 files **no longer exist** (`MeasureState.kt`, `ShapeMeasureState.kt`, `PhotoMeasureState.kt` — deleted by the MVI conversion); 4 of the remaining counts are wrong (`ArCameraChrome` 285→**300**, `ShapeFrameLoop` 302→**315**, `PhotoMeasureScreen` 410→**374**, `ArCameraScreen` 328→**379**); 6 files >200 lines are missing (`PhotoMeasureChrome` 311, `ShapeMeasureViewModel` 297, `MeasureViewModel` 251, `PhotoMeasureReducers` 243, `PhotoMeasureViewModel` 224, `PhotoMeasureContract` 215); and the closing sentence names a deleted file as "worth splitting next" | current actual list, largest first: `ArCameraScreen.kt` 379, `PhotoMeasureScreen.kt` 374, `ShapeFrameLoop.kt` 315, `PhotoMeasureChrome.kt` 311, `ArCameraChrome.kt` 300, `ShapeMeasureViewModel.kt` 297, `MeasureViewModel.kt` 251, `PhotoMeasureReducers.kt` 243, `PhotoMeasureViewModel.kt` 224, `ShapeMath.kt` 218, `PhotoMeasureContract.kt` 215, `QuadFromEdges.kt` 212, `HullQuadFit.kt` 205. Replace the trailing "worth splitting next" sentence — `ArCameraScreen.kt`/`PhotoMeasureScreen.kt` are now the two largest |
| R7 | 382-383 | §13 "Verified clean … audit dated 2026-08-26" | predates the 2026-08-27 lifecycle-viewmodel additions — see C5 | re-run `:app:assembleRelease` and re-date, or soften to "re-verification owed for the two lifecycle-viewmodel artifacts added 2026-08-27" |

Verified **correct**, for the record:
- §14 line 415 — "185 tests". Exact: `grep -rn "@Test" AR_feature/src/test | wc -l` → 185.
- §16 audit 7 — "six deliberate `Log.` lines in `SegmentQuad.kt` and `CameraCapture.kt`": exactly 4 + 2.
- §16 audit 3 — no `values-xx/`. Only `values/` and `xml/`.
- §16 audit 6 / §4 — public API is 3 symbols (re-verified past the grep's blind spots, see above).
- §6 — every manifest claim about the module's own source.
- §15's `_state`-is-lazy rationale (462-465) — the stated reasoning is sound; it just did not carry through
  to the public accessor (C1).

---

## Positive

- The `by lazy` fix targets a real, genuinely nasty trap and the KDoc explaining it (`MviViewModel.kt:26-35`)
  is the kind of comment that stops someone reverting it. Worth keeping verbatim.
- `persist()` is a good addition and its KDoc earns its length — the "six `rememberSaveable` patches, each
  shipped as a bug first" note is exactly the *why* that makes the hook defensible.
- `ArMeasureConfig`'s one-shot `check()` plus `internal` read accessor is the right shape for a
  process-global, and the trust-boundary note on `MeasurementImageSaver` is correctly placed.
- Public surface survived a whole-module file reorganisation without leaking a single symbol. That is not
  automatic.
- `PhotoMeasureViewModel.kt:42-52` — keeping the `Bitmap` out of `State` with the reference-equality and
  Bundle-size reasoning written down is a good call, well documented.
- The module ships no DI and no reflection, which is what makes the empty `proguard-rules.pro` honest.

---

## Recommended actions, in order

1. Fix C1 — `val state: StateFlow<S> by lazy { _state.asStateFlow() }`. One line; removes per-recomposition
   collection churn on the AR screen.
2. Fix C2/R1 — add the 3 missing catalog aliases to README §2. The integration guide is unbuildable without
   them.
3. Fix C3/R2/R3 — rename `Theme.ArMeasure` to an `armeasure_`-prefixed name (2 manifest refs), and broaden
   audit 4 to all resource types.
4. Fix R6 — regenerate §17's file list; it names three deleted files.
5. Decide C4 — delete `UndoRedoStack`'s six dead members, or correct its KDoc. Either way remove the
   "photo screen" claim at :8-10 and :23-25.
6. Fix R4/R5 — correct the two audits' stated expected output.
7. Re-run `:app:assembleRelease` and re-date §13 (C5/R7).
8. Add a `MviViewModelTest`. Not a generic ask — `assertSame(vm.state, vm.state)` is one line and catches C1
   permanently; a second asserting `createInitialState()` sees a constructor argument locks in `9c28b9c`.
9. Low priority: `_state.update { }` in `updateState`, guard `formatterCache`, fix the contradictory
   construction-order comment at `ArCameraViewModel.kt:28-32`.

---

## Unresolved questions

- Did anyone actually re-run `:app:assembleRelease` after `cc1edc4`? If yes, §13 just needs re-dating and
  C5 collapses to a doc fix.
- Is `onSessionUpdated` guaranteed main-thread under SceneView 4.31? I inferred it from Choreographer-driven
  Filament rendering, not from instrumentation. If it is *not* main, C6 rises from latent to live and C7's
  atomicity note does too.
- `UndoRedoStack`: delete the dead half or document it? The AR ViewModels keep their own `points`/`shapes`
  lists deliberately (KDoc bullet 1), so the undo side may never come back — which argues for deletion.
  Your call on churn vs. YAGNI.
- Not verified by me: that the 185 tests currently **pass** (I only confirmed the count) and §6's claims
  about permissions inherited transitively from `arsceneview` (needs a merged manifest from a real build).
- `common/data/UnitPreference.kt:15` has `DefaultUnit = LengthUnit.Cm` on this branch, while `main` carries
  `61472a2 change: default the measuring unit to meters`. Intentional divergence, or does this branch need
  a rebase before merge?
