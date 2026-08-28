# Code review — `ar/**` after the MVI conversion + component move

Date 2026-08-28 · branch `refactor/mvi-alignment` @ `d072207` · scope
`AR_feature/src/main/java/vn/apero/armeasure/ar/**` + `src/test/.../ar/**`. Read-only; nothing
modified. `photo/**`, `common/**`, build files: other reviewers, only read for context.

Verification actually run here: `./gradlew :AR_feature:compileDebugKotlin --offline` → exit 0.
No device. Library evidence below comes from reading the unpacked
`io.github.sceneview:arsceneview:4.31.0` sources.

---

## §12 — one shared session: holds, and now confirmed harder than by reading our code alone

- `rememberEngine()`/`rememberMaterialLoader()` at `ArCameraScreen.kt:150-151`, top level, outside
  every block. Not inside `key`.
- Exactly one `key(...)` in the file, `ArCameraScreen.kt:237`, wrapping only `ARSceneView`. Keyed on
  `instanceKey`, bumped only at `:301` by the watchdog.
- `tool` appears in `when (tool)` at `:266`, `:307`, `:321` — frame loop, overlay, chrome bindings —
  and nowhere near a `key`. `ARSceneView` sits outside every branch.
- `ArWarmupGate` consulted at `:164`, gate at `:229`, before the view can mount, for all four tools.

New evidence the phase-04 report could not give: `ARSceneView.kt:805/833` holds
`onSessionUpdated`/`onSessionCreated`/`sessionConfiguration` in `AtomicReference`s **re-`set` from a
`SideEffect` on every recomposition**, and `:1688` invokes the current one. So a tool swap changes
which loop runs on the next frame without any remount, by construction, not by luck. The remaining
device question is only cosmetic (a black frame during the swap), not behavioural.

Also established by reading the library: `ARSceneView.kt:1455-1505` runs the AR frame inside
`LaunchedEffect { withFrameNanos { ... } }` — **our `onFrame` runs on the main thread, in the Compose
frame callback**, not on a render thread. Two consequences used throughout below.

---

## Confirmed by reading

### C1 (high) A watchdog remount leaves every tool holding anchors from a closed session

`ArCameraScreen.kt:237` `key(instanceKey)` — bumping `instanceKey` disposes the `ARSceneView`, and
`ARSceneView.kt:1105-1107` → `ARCore.kt:163-171` calls `Session.close()` on that dispose. Nothing
releases the tools' anchors at that moment:

- the release `DisposableEffect` is keyed `Unit` (`ArCameraScreen.kt:181`), so it does not fire on a
  remount, only on leaving the screen;
- the ViewModels are not cleared (no config change happened);
- `MeasureViewModel.session` (`:81`) and `ShapeMeasureViewModel.session` (`:83`) keep pointing at the
  closed `Session` until the new `onSessionCreated` arrives.

So after the remount: `MeasureFrameLoop.kt:80` calls `it.anchor.pose` every frame on anchors issued
by a closed session, and `MeasureViewModel.clear()/releaseAll()` would later `detach()` them.

User-visible: the watchdog exists for a stall with measurements already on screen — exactly the case
where this bites. Best case the ARCore call throws, and because `ARSceneView.kt:1491-1499` wraps our
whole callback in `catch (e: Exception) { Log.e("SceneView", "ARCore session update failed") }`, the
overlay silently freezes and never comes back, with one logcat line as the only symptom. Worst case
it is a native use-after-free.

Pre-existing — `git show 3f7e5fb:.../ArCameraScreen.kt` has the same `remember`ed holders outside the
`key` — so the MVI conversion did not introduce it. It did make it cheap to fix: release from
`onSessionChanged` (and push `null` when the view is disposed) instead of only from the composition's
`DisposableEffect`.
Confidence: the code path, high. ARCore's exact behaviour on a closed session's anchor, medium —
needs a device (see Q1).

### C2 (high) The whole screen, and all of `ARSceneView`, recomposes at frame rate

`ArCameraScreen.kt:321-327` calls `distanceActions`/`shapeActions`, which read
`viewModel.frames.addEnabled` (`ArCameraControls.kt:115/134`) and the hint, which reads `frames.live`
/ `frames.liveStable` / `sessionFrames.anyPlaneTracked` (`ArCameraHints.kt:51-57, 85-89`). Those are
non-restartable `@Composable` functions, so their snapshot reads land in **`ArCameraScreen`'s own**
restart scope. `frames.live` gets a new `SurfaceSample` instance every frame (`MeasureHit.kt:33`, a
plain class → identity equality → always a real state change), so `ArCameraScreen` recomposes 30-60
times a second.

Knock-on: the `ARSceneView` call at `:238` takes four lambdas capturing `tool`/`unit`/`viewSize`/the
ViewModels. None of those captures is stable, so the lambdas are new objects each recomposition,
`ARSceneView` is not skippable, and a ~2000-line composable body re-executes every frame (including
its `SideEffect` re-`set`ting ~20 `AtomicReference`s). It does not *remount* — the `remember`s hold —
so §12 is not violated, but this is the largest per-frame cost in the screen.

The phase-04 report named this as suspect (a) and assumed "a wash vs pre-MVI". Reading the old file,
that is right — `3f7e5fb` read the same two fields in the same scope. So: not a regression, but the
fix it proposes (pass `addEnabled` as `() -> Boolean` read inside the button, and the hint likewise)
is worth more than the report credits, because it also takes `ARSceneView` out of the per-frame
recomposition set. Also note `stringResource(res, it.label)` at `ArCameraHints.kt:69/74` does a
resource lookup + `String.format` per frame.
Confidence: high by reading. The size of the win is unmeasured — this is the X8 gate.

### C3 (medium-high) `CameraDenied` is a dead end

`ArCameraActivity.kt:59-61` reads the CAMERA permission once in `onCreate`. `onResume` (`:93-101`)
re-resolves availability but never re-reads the permission, and `CameraDenied()`
(`GateMessages.kt:33-39`) is text only — no "open settings", no retry.

User-visible: deny once, grant it in system Settings, come back → still the denied screen, forever,
until the Activity is destroyed and recreated. Fix is one line in `onResume`.

### C4 (medium-high) `NeedsInstall` can strand the user on a black screen

`ArCameraActivity.kt:79`: everything that is not `Ready`/`Unsupported` renders
`Box(Modifier.fillMaxSize())` — nothing at all. `Checking` self-resolves (the bounded re-poll at
`:69-71` falls through to `Unsupported` after 3 s, giving `ArUnsupported()`), so `Checking` is safe.
`NeedsInstall` is not: `rePollArAvailability` returns immediately when `current != Checking`
(`ArAvailabilityGate.kt:77`), and `ArMeasureKit.requestInstall` returns `false` when the user declines
(`ArMeasureKit.kt:73-77`, `UnavailableException` swallowed). The hub reaches this state deliberately —
`ArMeasureHub.kt:95-100` starts the Activity for anything that is not `Unsupported`.

User-visible: on a device needing Google Play Services for AR, declining the install leaves a pure
black screen with no text and no affordance; only system back gets out. `ArUnsupported()` (or the
dialog) would be the honest thing to show.

### C5 (medium) `commitLivePoint` does not re-check `liveStable`; `commitStep` does

`ShapeMeasureViewModel.kt:136` has `if (!frames.liveStable) return`.
`MeasureViewModel.kt:121-130` has no equivalent — it trusts the button's `addEnabled` gate.

That gate was reliable pre-MVI, when "+" called the holder directly. It is now a
`processIntent → viewModelScope.launch → zero-buffer SharedFlow.emit → collect → handleIntent` round
trip (`MviViewModel.kt:43/57`), i.e. at least one main-loop reschedule after the tap. `frames.live` is
overwritten by every intervening frame, so the point that gets committed is a *later* sample than the
one the user saw go solid — and, unlike the shape tool, it is committed even if the gate has since
dropped. On a depth reading that is precisely the 0.46 m ↔ 3.73 m swing `MeasureFrameStream.kt:64-75`
documents: a confidently wrong length, anchored, with nothing on screen saying so.
One added line (`if (!frames.liveStable) return`) restores parity.
Confidence: the asymmetry, certain. How often the sample actually flips inside that window: needs a
device.

### C6 (medium) Per-frame allocation that should not be there

Main-thread, per frame, both loops:

- `MeasureFrameLoop.kt:56` / `ShapeFrameLoop.kt:66`: `session.getAllTrackables(Plane::class.java)` —
  a JNI thunk plus a fresh collection every frame, feeding one boolean that only picks a hint string.
  The library removed exactly this class of per-frame JNI allocation in its own code
  (`ARSceneView.kt:1653-1660`, "#2270"); our loop puts one back. Poll it every N frames.
- `MeasureFrameLoop.kt:80`: `points.map { it.anchor.pose.toVec3() }` allocates a list + a `Vec3` per
  point every frame, and `refreshWorldPoints` then throws it away under the 1 mm dead-band.
- `buildOverlay` (`:130-169`): `toMutableList` + `map` + `buildList` + a `Segment2D` and a
  `formatLength(...)` **String** per segment per frame — the label is rebuilt at 60 Hz even when the
  number has not changed — plus `filterNotNull`. `Offset` is a value class over `Long`, so every
  `List<Offset>`/`List<Offset?>` here boxes each element.

Rough order: ~30 objects/frame for a four-point chain, ~1800/s. Not fatal; it is the honest answer to
"is there allocation left on the callback that should not be".

### C7 (medium) `releaseAll()` leaves the last overlay frame behind

`MeasureViewModel.kt:222-232` and `ShapeMeasureViewModel.kt:266-270` clear the points/shapes and
reset the state, but do not reset `frames.overlay` — while their own `clear()` does
(`MeasureViewModel.kt:174`, `ShapeMeasureViewModel.kt:253`). A retained ViewModel re-entered after an
Activity recreation therefore draws the previous session's segments/wireframe from the moment the
overlay composes until the first ARCore frame overwrites it, with `pointCount == 0` underneath —
lines on screen that undo cannot remove. Short-lived, but it is the exact class of lie
`clearForUntrackedFrame`'s KDoc argues against. One line each.

### C8 (medium) The initial-state trap comments are now wrong, and the workaround with them

`MviViewModel._state` became `by lazy` in `9c28b9c`, which landed **before** the AR conversion
(`c859be4`). Three places still document the old, fixed hazard as current:

- `MeasureContract.kt:27-31` — "it *cannot* be here — the base class calls `createInitialState()`
  from its own field initializer".
- `ShapeContract.kt:84-87` — same claim.
- `ArCameraViewModel.kt:27-38` — an `init { updateState { ... } }` block whose stated reason is
  "reading unitPreference/savedState there would read null". With `by lazy` that is no longer true;
  `createInitialState()` may read constructor arguments directly.

No functional bug today (the init block runs after the ctor properties are assigned, and no collector
exists yet, so nothing observes the default). But it is the misleading residue asked about: a reader
told the trap is live will keep routing seeds through `init`, and the *real* remaining reasons for
`chained`/`kind` living outside `State` (they never change; they are configuration) are buried under a
reason that is now false. Rewrite the three comments; the `init` block can stay or go, but not for
the reason it gives.

### C9 (low-medium) Two threading claims in KDoc are wrong

`ArSessionFrameStream.kt:27-29`: "the AR library's frame callback may not run on the same thread as
the watchdog's polling coroutine". It does — `withFrameNanos` inside a composition `LaunchedEffect`,
main thread, same as the watchdog's `LaunchedEffect`. Compose state is still the right backing (the
chrome reads these in composition, which is the second half of that sentence and is correct), but the
wrong half licenses someone to later write these fields from a genuinely background thread "because
it's already safe". `MeasureFrameStream.kt:55-57` has a milder version of the same.

Related and worth writing down somewhere: every exception our per-frame code can throw is swallowed by
`ARSceneView.kt:1491-1499`. A bug in the frame path never crashes — it freezes the overlay. The device
round should grep for `SceneView.*ARCore session update failed`, or a real defect will read as "the AR
tool just stopped drawing".

### C10 (low) Two unguarded indices in code that guards the same thing elsewhere

- `MeasureFrameLoop.kt:132` `world[draggingIndex] = it` — direct index; `:168` guards the identical
  index with `projected.getOrNull(it)`, eight lines apart.
- `MeasureViewModel.kt:199-200` `points[index]` in `onDragEnd`.

Today both are safe: every path that shrinks `points` calls `frames.endDrag()` first
(`MeasureViewModel.kt:150, 167, 223`) and everything runs on the main thread. Both crash the moment
that ordering discipline slips. `getOrNull` costs nothing.

### C11 (low) Duplicate imports left by the file move (`7a8dec0`)

- `ArCameraScreen.kt:46` and `:59` — `...shapes.components.ShapeOverlay`
- `ArCameraControls.kt:21` and `:24` — `...ruler.components.MeasureOverlay`
- `ShapeOverlay.kt` — `...ruler.components.drawReticle`, `...drawSegment`

Legal Kotlin; I compiled to be sure (exit 0). Cosmetic, but they are the tell that the move commit
added imports without removing the old ones — worth a sweep for anything else it left half-done.

### C12 (low) Stale KDoc after the fourth tool and the mode sheet landed

- `ArWarmupGate.kt:12` "Distance/Box/Cylinder alike" — four tools now.
- `ArCameraActivity.kt:37-38` same list, plus "the design's real tool-picker sheet replacing today's
  debug switcher is phase 06" — `MeasureModeSheet` shipped; the sentence describes a state that no
  longer exists.
- `ArMeasureKit.kt:22-24` "all three AR tools (Distance, Box, Cylinder)".
- Several "phase 03/04/05" cross-references (`ArCameraScreen.kt:101,179`, `ArSessionFrameStream.kt:12`,
  `ArCameraViewModel.kt:72`, `MeasureViewModel.kt:243`) — plan-relative labels that will mean nothing
  to a reader six months out. The README §-numbers are the durable reference.

### C13 (low) `pointerInput(Unit)` captures `viewSize` by value

`ArCameraControls.kt:47-57`: the block is keyed `Unit`, so Compose keeps the first lambda instance and
its captured `viewSize` forever; `onDragStart` projects world points with it. It works today only
because `ArWarmupGate` delays `DistanceOverlay`'s first composition past the parent's first layout, so
`viewSize` is already real when the lambda is created. Remove or shorten the warm-up delay and
drag-to-edit stops finding points, with no other symptom. Pre-existing (identical in `3f7e5fb`).
`PointerInputScope.size`, or keying on `viewSize`, removes the coupling.

### C14 (low) `SteadinessGate.stable` is not snapshot state

`SteadinessGate.kt:24` is a plain `var`, read through `MeasureFrameStream.liveStable` /
`ShapeFrameStream.liveStable` from composition (`addEnabled`, both hint functions). Compose records no
read of it. It is correct today only because every `stable` change is accompanied by a `live` write
that does invalidate. That is a coincidence of the two always moving together, not a guarantee — the
same shape as the `planeBasis` gate issue this module has hit before.

---

## Suspected — needs a device

- **S1** ARCore's actual behaviour when `Anchor.getPose()`/`detach()` is called after
  `Session.close()` (C1). Throw-and-swallow vs native crash decides whether C1 is "the overlay dies"
  or "the app dies". Reproduce by forcing `instanceKey++` with points placed.
- **S2** `createSavedStateHandle()` resolving from `ArCameraActivity`'s creation extras
  (`ArCameraViewModel.kt:76`). Standard AndroidX, but it throws rather than degrades. Still the first
  thing to watch on first entry; carried over unresolved from the phase-04 report.
- **S3** Whether C2's per-frame recomposition actually costs measurable frames on the Joy_4. This is
  the X8 gate; the procedure in the phase-04 report is still the right one.
- **S4** Black frame on tool swap. The *logic* is now proven remount-free (§12 above), so this is a
  visual check only.

## Positive

- The state/frame split is real and holds: `updateState` is called only from
  commit/undo/redo/clear/drag-end/`releaseAll` — all tap-rate. Nothing per-frame reaches `State` or an
  intent. Grepped and read every call site.
- `onFrame` reads `stateValue.phase` (`ShapeMeasureViewModel.kt:110`) rather than mirroring the phase
  into the frame stream — one source of truth, no tearing, and since everything is main-thread the
  read is a consistent snapshot.
- Overlays read the frame through a lambda **inside** the draw scope (`MeasureOverlay.kt:49-50`,
  `ShapeOverlay.kt:65-66`), so a new frame invalidates draw only. That is the part of the design that
  is doing the real work.
- Double release is genuinely safe: `DisposableEffect` then `onCleared` — the second pass finds empty
  lists and a drained history, so no anchor is detached twice
  (`MeasureViewModel.kt:222-237`, `ShapeMeasureViewModel.kt:266-289`).
- `ShapeMeasureViewModel.isAnchorOrphaned` + `detachAllHeldAnchors` is careful, correct work on the
  one genuinely hard lifetime problem here (an anchor shared between a phase, a shape and history).
- `checkAvailability` is off the main thread on every path (`ArAvailabilityGate.kt:61-62`; both call
  sites go through it), and `requestInstall` deliberately is not. That split is right and documented.

## Recommended order

1. C1 — release anchors and drop the session reference on session change, not only on dispose.
2. C3, C4 — two small stranding fixes, both in `ArCameraActivity`.
3. C5 — one line in `commitLivePoint`.
4. C2 + C6 — do these together, then run the X8 measurement once, on the Joy_4.
5. C7, C8, C9, C12 — comment/consistency sweep, cheap.
6. C10, C11, C13, C14 — when nearby.

## Missing tests worth having (each tied to a bug above, not general coverage)

- `ArCameraViewModel` restore-from-`SavedStateHandle`: `SavedStateHandle(mapOf("ar_camera_tool" to
  "Box"))` → initial state's `tool == Box`. Pure JVM, no Robolectric. It pins the ordering that C8's
  comments are confused about, and would catch a regression if someone "simplifies" the `init` block
  into `createInitialState()` incorrectly.
- A `SteadinessGate` sequence test asserting `stable` goes false on the first out-of-band sample after
  a steady run — the state C5 lets slip through unchecked.

## Unresolved

1. S1 above — the answer changes C1's severity from high to critical.
2. `ArSessionFrameStream` staying in `remember` rather than a ViewModel: right today (it describes the
   composition's own session), wrong the day the session is hoisted. Same question the phase-04 report
   left open; still open, still correct today.
3. Is the watchdog remount path exercised by anyone's manual pass? It is the only route to C1 and
   nothing in the regression scenario forces a 10 s frameless stall.
4. `points` is unbounded — nothing caps how many anchors a session can accumulate before ARCore's own
   budget complains. Out of scope for this branch; noting it because C6's per-frame cost scales with it.
