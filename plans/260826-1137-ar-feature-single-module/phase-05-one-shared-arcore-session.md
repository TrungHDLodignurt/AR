# Phase 05 — ONE shared ARCore session for Distance / Box / Cylinder

## Context Links

- [Plan overview](plan.md) · depends on [phase 04](phase-04-entry-layer-hub-and-activities.md)
- **Read before starting:** [`report-260825-1703-session-handoff-box-cylinder-measure.md`](../reports/report-260825-1703-session-handoff-box-cylinder-measure.md)
  **§10, §11, §12** — two confident fixes were shipped and reverted here; §11.1 measured a
  **near-100% failure rate** from recreating the Filament `Engine` on a retry cadence.
- Anchor lifetime: same report §4, §6. Session-config heat: §15 point 2, §17.

## Overview

- **Priority:** P1 — the technically riskiest phase in the plan.
- **Status:** pending
- **Effort:** 3.5h

Today `MeasureScreen.kt` and `ShapeMeasureScreen.kt` each create their own `ARSceneView` **and their
own `rememberEngine()`**. They become one `ArCameraScreen` with one view, one Engine, one session,
and a swappable state machine.

## Key Insights

1. **The Engine must be created exactly once and never recreated.** §11.1: moving `rememberEngine()`
   inside the `key(instanceKey)` block — so a watchdog remount rebuilt the Engine — produced a
   near-100% failure rate, *including on cold starts that previously worked*, because a heavyweight
   GPU-resource-owning native object torn down on a ~10s cadence leaves resources mid-teardown when
   the next Engine claims the camera texture. **Therefore `tool` must never appear in a `key(...)`
   and the `ARSceneView` must never sit inside a `when (tool)` branch.** A tool swap that remounted
   the view would reintroduce that failure on *every* swap.
2. **Do not tear down or remount on a tool swap, and do not `session.configure()` on a swap.**
   §11.2: proactively remounting on every `ON_RESUME` regressed the common case, because
   close-then-immediately-reopen of the camera is a *more frequent* failure than the rare
   stale-texture bug it was meant to fix. A swap is not a reason to touch the session.
3. **The warm-up gate becomes correct by construction.** `ArWarmupGate` is already a process-global
   one-shot; with one shared mount point the §12 bug class ("a cold launch straight into Box or
   Cylinder skipped the guard") disappears structurally. Keep the flag process-global, not per-tool.
4. **The watchdog must stay RESUMED-gated and owned by the shared host.** A background tool swap must
   not be able to fire a pointless remount. The stall decision is currently ~10 lines inline in a
   `LaunchedEffect` with **no test at all**, despite being the exact logic two reverted fixes got
   wrong. Extract it as a pure function and test it — that is the one genuinely testable piece here.
5. **Six fields on the tool state holders are actually session facts, and after the merge they have
   exactly one writer.** `depthSupported` is written from `sessionConfiguration`; `trackingFailure`
   from `onTrackingFailureChanged`; `cameraReady`, `lastFrameAtMillis`, `tracking` and
   `anyPlaneTracked` from the frame callback. With one shared view there is one of each, so they
   must move to a shared `ArSessionState`. This is behaviour-preserving (same values, same writers,
   new owner) and deletes six duplicated fields across the two holders.
6. **`SteadinessGate` goes stale on an inactive tool.** Each holder owns its own gate. Swap to a tool
   whose gate still holds samples from before the swap and `liveStable` can read `true` on a stale
   reading for one frame — long enough to enable `+` and commit a false point. **Every holder needs
   an `onActivated()` that resets its gate and clears `live`.** This bug does not exist today
   because each tool got a fresh holder per mount; the merge introduces it.
7. **`unit` must be hoisted out of the holders.** Three holders each with their own `var unit` means
   switching tools shows a different unit. Hoist one `unit` into the screen, seeded from phase 02's
   `UnitPreference`, and pass it to the frame loops.
8. **The half-drawn-shape anchor is safe** because phase 03 added `releaseAll()` behind a
   `DisposableEffect`. Keep a half-drawn shape across a swap (losing it on a tool peek would be
   worse UX than the anchor cost) and let dispose clean up. Do **not** detach on swap.
9. **`PoseProjector` is safe to share.** Its only state is scratch `FloatArray` buffers, and
   `update(frame)` is called once per frame before any projection. Only one frame loop runs per
   frame, so there is no interleaving. Sharing one instance also removes a per-mount allocation.
10. **One session config serves all three tools**, so it is the union — and the two existing config
    blocks are already byte-identical, so there is nothing to reconcile. `DepthMode.AUTOMATIC` is
    pure heat cost for Box/Cylinder (whose height uses an analytic construction-plane ray-cast and
    no depth at all) but cannot be made per-tool under one session. Recorded, not fixed.

## Requirements

**Functional**
- One `ArCameraScreen` hosting one `ARSceneView`, one `Engine`, one `MaterialLoader`, one `Session`.
- `MeasureTool { Distance, Box, Cylinder }`; swapping swaps only the active state machine + overlay.
- A swap must **not**: lose tracked planes, re-run the warm-up delay, reset the camera, discard
  committed measurements, or discard a half-drawn shape.
- A swap **must**: reset the newly-active tool's steadiness gate, and keep the displayed unit.
- The watchdog remount path still works, still RESUMED-gated, still keyed on `instanceKey` only.

**Non-functional**
- No new allocation on the frame path; the `when (tool)` dispatch is a branch, not a lookup.
- Only the active tool's overlay is composed and only its frame loop runs — an inactive tool costs
  nothing per frame.
- `ArCameraScreen.kt` stays under ~200 lines by keeping the session plumbing in `ArSessionState.kt`
  and the hint text in its own file.

## Architecture

```kotlin
// ar/presentation/camera/ArSessionState.kt   (internal)
internal class ArSessionState {
    var lastFrameAtMillis by mutableStateOf(System.currentTimeMillis())
    var cameraReady      by mutableStateOf(false)
    var tracking         by mutableStateOf(false)
    var anyPlaneTracked  by mutableStateOf(false)
    var depthSupported   by mutableStateOf(false)
    var trackingFailure  by mutableStateOf<TrackingFailureReason?>(null)
    fun noteFrame() { cameraReady = true; lastFrameAtMillis = System.currentTimeMillis() }
}

/** Pure. The logic two reverted fixes got wrong — see report §11. */
internal fun shouldForceRemount(
    lastFrameAtMillis: Long, nowMillis: Long, isResumed: Boolean, timeoutMs: Long,
): Boolean = isResumed && (nowMillis - lastFrameAtMillis) > timeoutMs
```

```kotlin
// ar/presentation/camera/ArCameraScreen.kt   (internal)
internal enum class MeasureTool { Distance, Box, Cylinder }

@Composable
internal fun ArCameraScreen(modifier: Modifier = Modifier) {
    val engine = rememberEngine()                       // ONCE. Never inside key(). Never per tool.
    val materialLoader = rememberMaterialLoader(engine)
    val sessionState = remember { ArSessionState() }
    val projector = remember { PoseProjector() }        // shared: scratch buffers only

    var tool by remember { mutableStateOf(MeasureTool.Distance) }
    var unit by remember { mutableStateOf(unitPreference.unit) }

    // All three live for the screen's whole lifetime -> a swap loses nothing.
    val distance = remember { MeasureState() }
    val box      = remember { ShapeMeasureState(ShapeKind.Box) }
    val cylinder = remember { ShapeMeasureState(ShapeKind.Cylinder) }

    var session by remember { mutableStateOf<Session?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var instanceKey by remember { mutableIntStateOf(0) }   // watchdog remount ONLY
    val isWarmedUp = ArWarmupGate.rememberArWarmedUp()
    ...
}
```

Structural rules, non-negotiable:

| Rule | Why |
|---|---|
| `ARSceneView` sits outside every `when (tool)` | a remount on swap reintroduces §11.2's close-then-reopen race |
| `key(instanceKey)` wraps only the `ARSceneView`; `tool` is not in the key | §11.1 |
| `rememberEngine()` / `rememberMaterialLoader()` are outside `key(...)` | §11.1, near-100% failure |
| no `session.configure()` outside `sessionConfiguration` | reconfiguring mid-session resets tracking |
| `ArWarmupGate` called once, before the view mounts | §10.2 / §12 |
| `DisposableEffect` releases all three holders' anchors | §6, the orphan the shared session makes mandatory |

Tool swap:

```kotlin
fun selectTool(next: MeasureTool) {
    if (next == tool) return
    activeState(next).onActivated()   // resets SteadinessGate, clears live
    tool = next
}
```

## Related Code Files

**Create**
- `AR_feature/.../ar/presentation/camera/ArCameraScreen.kt`
- `AR_feature/.../ar/presentation/camera/ArSessionState.kt` (+ `shouldForceRemount`)
- `AR_feature/.../ar/presentation/camera/ArCameraHints.kt` (the merged `hintFor` for all 3 tools)
- `AR_feature/src/test/java/vn/apero/armeasure/ar/presentation/camera/ArSessionStateTest.kt`

**Modify**
- `.../ar/presentation/ruler/MeasureState.kt` — drop `lastFrameAtMillis`, `cameraReady`, `tracking`,
  `anyPlaneTracked`, `depthSupported`, `trackingFailure`, `unit`; add `onActivated()`
- `.../ar/presentation/shapes/ShapeMeasureState.kt` — same six fields + `unit` dropped; add
  `onActivated()`
- `.../ar/presentation/ruler/MeasureFrameLoop.kt` — `onFrame(...)` takes `ArSessionState` and `unit`
- `.../ar/presentation/shapes/ShapeFrameLoop.kt` — `onShapeFrame(...)` same
- `.../ar/presentation/host/ArCameraActivity.kt` — body becomes `ArCameraScreen()`
- `.../ar/domain/steadiness/SteadinessGate.kt` — add `reset()` if it has none

**Delete**
- `.../ar/presentation/ruler/MeasureScreen.kt` — its watchdog, lifecycle observer, session
  plumbing and chrome move into `ArCameraScreen`; `HintBanner` moves to `ArCameraHints.kt`
- `.../ar/presentation/shapes/ShapeMeasureScreen.kt` — including `ArMeasureBoxScreen` /
  `ArMeasureCylinderScreen`, whose `MeasurementResult` mapping becomes the shared screen's
  `onShapeCommitted` branch

Keep `MeasureOverlay.kt`, `ShapeOverlay.kt`, `MeasureMath.kt`, `ShapeMath.kt`, `MeasureHit.kt`,
`ArWarmupGate.kt` **untouched** — the maths and hit-test chain are behaviour-preserving.

## Implementation Steps

1. Write `ArSessionState` + `shouldForceRemount` and its 6 tests. Green before touching the screens.
2. Add `SteadinessGate.reset()` and `onActivated()` to both AR holders; move the six session fields
   off them; drop their `unit`.
3. Update the two frame loops' signatures to take `ArSessionState` and `unit`. Compile-driven — the
   compiler names every site.
4. Write `ArCameraScreen`, assembling: warm-up gate → `key(instanceKey) { ARSceneView }` → a
   `when (tool)` overlay → the merged chrome (top bar with undo/redo/clear, `UnitBtn`, bottom bar).
   Copy the watchdog `LaunchedEffect` and the `ON_RESUME` `DisposableEffect` across verbatim except
   that the stall test now calls `shouldForceRemount`.
5. Merge the two `hintFor` functions into `ArCameraHints.kt`. **The 5 shape-phase hints are currently
   hardcoded English string literals** (`ShapeMeasureScreen.kt:232-236`) — move them into
   `strings.xml` as `armeasure_hint_shape_*` while moving the code. Same for the `"Point N on …"`
   line at `MeasureScreen.kt:359`.
6. Point `ArCameraActivity` at `ArCameraScreen`. Delete the two old screen files.
7. Wire `selectTool` to a temporary debug affordance (the design's `ModeBtn` + sheet lands in phase
   06) so the swap is testable on-device *in this phase* rather than at the end of the next one.
8. Gate — including the human on-device checks, which are the real gate here.

## Todo List

- [ ] `ArSessionState` + pure `shouldForceRemount` + 6 tests
- [ ] `SteadinessGate.reset()`; `onActivated()` on both AR holders
- [ ] Move the 6 session fields off the holders; drop their `unit`
- [ ] Frame-loop signatures take `ArSessionState` + `unit`
- [ ] `ArCameraScreen`: one Engine, one view, `key(instanceKey)` on the view only
- [ ] `when (tool)` selects overlay + frame loop; view is outside it
- [ ] `DisposableEffect` releases all three holders
- [ ] Merge `hintFor`; move 6 hardcoded English literals into `strings.xml`
- [ ] Delete `MeasureScreen.kt` + `ShapeMeasureScreen.kt`
- [ ] `ArCameraActivity` → `ArCameraScreen`
- [ ] Temporary tool-swap affordance for on-device testing
- [ ] Gate: `compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease`
- [ ] **On-device, human, aiming at a real textured surface:**
      - [ ] cold launch (force-stop first) → Distance → the 2s warm-up hint shows **once**
      - [ ] place 2 points, swap to Box, swap back → **both points still there**, plane grid still
            rendered, warm-up hint does **not** show again
      - [ ] draw a Box origin + first edge, swap to Cylinder, swap back → the half-drawn box is
            intact at the same phase
      - [ ] swap tools 20× in a row → no black screen, no `TextureNotSetException` in logcat,
            no camera reopen in `CameraService` logs
      - [ ] force-stop, cold launch **straight into Box** → no black screen (the §12 case that was
            never verified)
      - [ ] force-stop, cold launch **straight into Cylinder** → same
      - [ ] background for 2 minutes, resume → frames return without a remount
      - [ ] immediately after a swap, confirm `+` is **disabled** until the new tool's reading
            settles (the stale-`SteadinessGate` check)

## Success Criteria

**92 tests pass** (86 + 6). The 6 `ArSessionStateTest` tests:

1. not resumed → never remounts, however long the stall
2. resumed and stalled past the timeout → remounts
3. resumed and stalled exactly at the timeout → does **not** remount (strict `>`)
4. resumed with a fresh frame → does not remount
5. a clock that went backwards (negative delta) → does not remount
6. `noteFrame()` sets `cameraReady` and advances `lastFrameAtMillis`

Plus, and these matter more than the unit tests:
- `git grep -c 'rememberEngine' AR_feature` returns **1**.
- `git grep -c 'ARSceneView(' AR_feature` returns **1**.
- `git grep -n 'key(' AR_feature/.../ArCameraScreen.kt` shows `key(instanceKey)` and nothing else.
- Every on-device box above ticked by a human. A green build is **not** sufficient evidence in this
  phase — §11 records two green-building fixes that made the app worse.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| Someone puts `tool` in the `key(...)` or the view inside `when (tool)` | medium | the grep assertions above are gate items; the rules table is in this file and must be repeated in the code's KDoc with the §11 evidence |
| Stale `SteadinessGate` lets a false point commit right after a swap | **high** if unaddressed | `onActivated()` + the named on-device check |
| Frame-loop signature change silently drops a field write (e.g. `anyPlaneTracked` never set → the hint says "move to find a surface" forever) | high | on-device hint progression check; the hint text is the visible symptom |
| Anchor orphan accumulates across many swaps | medium | phase 03's `releaseAll()` on dispose; watch for ARCore anchor warnings during the 20-swap check |
| `DepthMode.AUTOMATIC` heat with longer sessions (one screen now holds all three tools, so users stay longer) | medium | measure device temperature during the 20-swap check; if it throttles, that is a finding for a follow-up, not a reason to make depth per-tool (impossible under one session) |
| 2s warm-up may now be measurable and shortenable | low | out of scope; §14 records it as an unmeasured guess. Do not tune it in this phase |
| Deleting the two screen files loses the reverted-fix commentary | **high** | those KDoc blocks are the institutional memory of §11. **Copy them into `ArCameraScreen.kt` verbatim**, do not summarise |

## Security Considerations

- No new permissions, dependencies, storage or network. CAMERA is already requested by
  `ArCameraActivity` (phase 04).
- A never-torn-down session holds the camera open longer than before. It must still stop on
  `ON_PAUSE` — that is `ARSceneView`'s own lifecycle observer; do not disable it, and confirm on
  device that backgrounding releases the camera (another app can open it).
- Anchor leaks are a resource-exhaustion path, not a security one, but the `releaseAll()` hook is
  the same mitigation.
- Do not add logging of frame data or poses; `PhotoMeasure`'s existing `Log.d` of tap coordinates
  (`PhotoMeasureState.kt:107`) is already more than needed and should be removed in phase 08.

## Next Steps

- Phase 06 replaces the temporary swap affordance with the design's `ModeBtn` + `MeasureModeSheet`
  and dresses the chrome.
- The unverified §12 cold-launch-into-Box/Cylinder case is closed out **here**, not later.
