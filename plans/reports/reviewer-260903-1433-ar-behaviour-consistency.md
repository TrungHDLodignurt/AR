# Reviewer — AR behaviour & state consistency

Scope: `git diff 541e3d1..HEAD -- AR_feature/src` (b53ffa2, e26f5d6, 0115830). Read-only review; no
files changed. The already-fixed phantom-lock bug is not reported.

Hunting for: **the UI asserting a state the controls will not honour**, and transitions that leave
stale visuals.

11 findings — 9 CONFIRMED by code reading, 2 PLAUSIBLE. Ranked by how likely a real user is to hit
it, most likely first.

---

## CONFIRMED

### F1 — The dot field paints the plane's infinite extension; the reticle refuses it (HIGHEST likelihood)

`buildPlaneDots` lays a ±20 cm lattice on the plane through the hit
(`PlaneDotField.kt`, the `for (i in -LatticeHalfSpan..LatticeHalfSpan)` loop): the only masks
are the circular falloff and the viewport cull. It never asks whether a lattice point is inside
the plane's polygon. `resolveSurface` does — `trackable.isPoseInPolygon(hit.hitPose)`
(`MeasureHit.kt:179`), which README calls out as load-bearing precisely because ARCore reports hits
on the *infinite extension* of a plane.

Repro (any of the four tools): aim at a desk/table ~15 cm from its edge, on a device where the
desk is a tracked plane. Dots are painted across the edge and out over thin air (visually over the
floor two metres below). Track the reticle onto that painted area: the dots keep saying "surface
here", the reticle goes hollow, `+` drops to 0.38 alpha, and the hint switches to "Aim at a
surface".

Why it matters most: the whole job of a surface affordance is to say where a tap will work, and it
is wrong along every plane boundary — i.e. along every edge users actually measure. It is also the
one contradiction that persists indefinitely instead of self-repairing on the next frame.

Direction (not applied): clip each lattice point with the same `isPoseInPolygon` the resolver uses,
or drop dots whose distance to the polygon boundary is negative. The trackable is already reachable
— `SurfaceSample` holds it — but is currently `private` and only exposed as `planeNormal`.

### F2 — "Looking for a surface…" centre-stage while the reticle is solid and `+` is live

`distanceHint` (`ArCameraHints.kt:48-61`) tests `!sessionFrames.anyPlaneTracked` **before**
`frames.live == null`, and the new `ScanningIndicator` is gated on the same flag alone
(`ArCameraScreen.kt:358`). Neither consults the live reading. But a `DepthPoint`, the depth image
and a raw feature point all resolve with **no plane tracked anywhere**, and `SteadinessGate` promotes
them after 5 frames (~166 ms).

So `live != null && liveStable && !anyPlaneTracked` is a reachable, stable state in which the screen
simultaneously shows:

- centre: the 162 dp scanning animation + "Looking for a surface…"
- bottom: "Move your phone to find a surface"
- reticle: **solid** (`reticleOnSurface = live != null && commitReady`)
- `+`: **enabled and fully opaque** — and tapping it commits a real point

Repro (all four tools; `shapeHint` has the identical ordering at `ArCameraHints.kt:92-93`): enter
the camera, aim at something textured but non-planar — a bookshelf, a cluttered desk, a chair — and
hold still for ~0.2 s before ARCore has grown any plane. This is the first few seconds of most
sessions, so most users will see it.

This is the exact inverse of the fixed snap bug: there the UI promised what the controls refused;
here the controls permit what the UI says is impossible. The centre indicator is new in b53ffa2,
which is what turns a quiet bottom-line inconsistency into a loud one.

### F3 — "Point added" is the terminal confirmation for a finished box or cylinder

`ArCameraScreen.kt:208-218` merges all four tools' `Measured` effects into one collector and always
sets `commitToast = armeasure_toast_point_added` ("Point added"). A box's 4th tap finishes a whole
shape, emits `MeasurementResult.Box(...)` to the host, resets `phase` to `AwaitingOrigin` — and the
screen says "Point added" for 1.5 s.

Two consequences beyond the wrong noun: for those 1.5 s the toast **replaces** `actions.hint`
(`ArCameraScreen.kt:368`), so the "Tap + to place the box's corner" that would tell the user the
shape is done and a new one has started is suppressed; and a `trackingFailureHint` that arrives
inside that window is suppressed too, even though the whole point of that hint's priority is that a
specific actionable failure outranks everything.

Likelihood: every single box/cylinder measurement. Severity low, but it is a wrong assertion about
state at the one moment the user is checking whether their measurement landed.

### F4 — Nothing clears a tool's live reading when frames stop arriving

`clearForUntrackedFrame()` only runs *from inside* a frame callback. If `onSessionUpdated` stops
being called at all — the exact failure the 10 s watchdog exists for — every per-frame value freezes
at its last value and keeps being displayed as current:

| element | during a stall |
|---|---|
| camera preview | frozen image |
| `frames.overlay` | segments + labels at 10 s-old screen coords |
| reticle | still **solid** |
| `+` | still **enabled** |
| hint | still "Point 2 on depth map" |
| `ScanningIndicator` | still hidden (`anyPlaneTracked` frozen `true`) |
| tracking-failure hint | none — sceneview only pushes a reason from a frame that arrives (`ARSceneView.kt:1679-1686`), and it maps `NONE` to `null` |

Repro: whatever produced the stalls the watchdog was written for (`CameraWatchdogTimeoutMs`,
`ArCameraScreen.kt:73`) — per CLAUDE.md's history, close-then-immediately-reopen the camera is the
known trigger. The screen asserts a live measurable surface for ≥10 s while nothing is live.

Tapping `+` in that window goes to `commitLivePoint`, which checks only `session != null` and
`frames.live != null` — it will happily anchor a 10 s-old sample.

The watchdog's clock is session-level and already available; the tool streams have no equivalent
staleness notion.

### F5 — A watchdog remount leaves every committed point drawn from a dead session's anchors

`instanceKey++` re-keys `ARSceneView`, which disposes the old view and its `Session` and creates a
new one; `onSessionCreated` then pushes the new handle into all four ViewModels
(`ArCameraScreen.kt:264-272`). **Nothing else happens.** `MeasureViewModel.points` and
`ShapeMeasureViewModel.shapes` still hold `Anchor`s issued by the closed session, and the frame loop
keeps calling `it.anchor.pose` on them every frame (`MeasureFrameLoop.kt:124`,
`ShapeFrameLoop.kt:169`).

A new ARCore session starts a **new world origin**. So after a remount the committed geometry is
re-projected from poses that either (a) are stale values in a coordinate frame that no longer
exists, so the whole measurement is drawn in the wrong place with a length measured in a dead frame,
or (b) throw from the released native handle. Meanwhile `pointCount` / `shapeCount`, `canUndo`,
`canRedo` and the hint all keep asserting a valid measurement.

Same likelihood as F4 (a fired watchdog), higher severity: a *wrong number*, confidently drawn,
rather than a stale one.

### F6 — `clearForUntrackedFrame()` does not clear `snappedIndex` (or the drag)

`MeasureFrameStream.clearForUntrackedFrame()` clears `live` and `overlay` and nothing else
(`MeasureFrameStream.kt:165-168`). `snappedIndex` survives, and `distanceHint` reads it above every
other case.

Repro (Distance / Distance chain): place two points, hover the reticle over point 2 so it locks,
then lose tracking in a way that reports `TrackingFailureReason.NONE` — session re-initialisation
or a brief relocalisation, which sceneview turns into `trackingFailure = null`, so the failure hint
does not take over. Result for as long as the frames stay untracked: bottom line says **"Snapped to
point 2"**, no lock ring is drawn (the overlay was reset), no dots, hollow reticle, and `+` is dead
(`addEnabled` needs `live != null`). This is the fixed bug's shape one layer down — the hint asserts
a lock the controls will not honour, with nothing on screen to explain it.

`draggingIndex` / `dragSample` / `dragTouchPosition` also survive an untracked frame, which is
correct-by-design for the sticky drag, but see F9.

### F7 — The box's live preview vanishes wholesale when the origin corner goes behind the camera

`buildEdgeUSegment` and `buildEdgeVEdges` bail out of the **entire** preview with `?: return` the
moment `project(origin)` or `project(turn)` is null (`ShapeFrameLoop.kt:242-244` and `281-288`), and
`project` returns null exactly when a point is behind the camera (`PoseProjector.kt:52`).

Repro (Box): measure something wide from close range — a sofa, a fridge, a bed. Tap the origin
corner, walk/turn along the first side. Once the origin passes behind the camera plane the whole
preview disappears — including the second edge, which is fully on screen and is the thing the user
is currently aiming. The hint still says "Move to draw the second edge, tap + to fix it", `+` is
still enabled, and the tap still commits an `edgeV` the user was given no preview of.

`buildSizingHeightEdges` gets this right (`continue` per corner, `ShapeFrameLoop.kt:335-339`), so
the inconsistency is internal to the same file.

Same shape, pre-existing rather than from this diff: `buildLiveSegment`
(`MeasureFrameLoop.kt:296`) drops the rubber band **and its live length label** when the previous
point is behind the camera, while `+` stays enabled and the hint keeps saying "tap + to set the
end". Measuring a 3 m wall from 1 m away hits this every time; the user gets a correct measurement
with nothing drawn.

### F8 — A shape origin with no plane silently invents one

When the origin tap resolves without a plane, `commitStep` falls back to world-up:
`(sample.planeNormal ?: Vec3(0f, 1f, 0f)).normalized()` (`ShapeMeasureViewModel.kt:148`). That is a
defensible default and it is documented. The problem is that it is the *only* thing on screen that
knows: `planeNormal == null` is also exactly the condition that empties the dot field and the
ellipse ring (`ShapeFrameLoop.kt:200-228`), so the screen says "no plane here" and the tool then
proceeds as if there were a horizontal one.

Repro (Box, depth-capable device): aim at a non-planar object standing against a **wall** — a
picture frame, a wall-mounted box — with no plane grown over it. No dots, flat circular reticle,
`+` enabled. Tap. The base is now built on an assumed horizontal plane, so `projectedEdgeVector`
flattens the first edge into the horizontal, and the preview edge slides sideways as the user aims
up the wall. Nothing said the orientation was guessed.

This is the honest answer to "is the screen honest when `planeNormal == null`?" — for the two
distance tools, yes (dots and ellipse absent, hit source named in the hint, nothing else claims a
plane). For the two shape tools, no: the commit assumes a plane the screen just said was absent.

### F9 — `onActivated()` leaves the outgoing tool's overlay and the drag state behind

What survives a tool swap, per stream:

| value | survives? |
|---|---|
| steadiness gate | reset ✔ |
| `live` | cleared ✔ |
| `snappedIndex` | cleared ✔ |
| `overlay` (dot field, reticle flags, segments) | **survives** |
| `worldPoints` | survives (correct — real anchors) |
| `draggingIndex` / `dragSample` / `dragTouchPosition` | **survives** |

Two consequences:

1. Cosmetic: switching to a tool used earlier draws that tool's last overlay frame — including
   `reticleOnSurface = true` and its dot patch — at screen coordinates from wherever the phone was
   then, until its next frame loop runs. One frame (16-33 ms) normally, so invisible; unbounded if
   the swap lands inside an F4 stall.
2. Real: a `draggingIndex` that survives a swap is never cleared by `onActivated()`. Coming back to
   that tool leaves `addEnabled` permanently false (it requires `draggingIndex == null`), the hint
   permanently on "Move over a surface, then release to reposition", the rubber band suppressed and
   snapping disabled — with no finger on screen. Only Undo or Clear (which call `frames.endDrag()`)
   escape it. See P1 for whether the drag can actually survive.

---

## PLAUSIBLE

### P1 — Can a drag survive to trigger F9.2?

It needs a pointer-down on a placed point that ends without `onDragEnd` **or** `onDragCancel`:
a second finger opening the mode sheet mid-drag. `ModalBottomSheet` renders in its own window, and
Android delivers `ACTION_CANCEL` to the window losing touch, which Compose turns into a pointer
cancel — so `onDragCancel` almost certainly fires and closes the hole. Not verified on a device.
Worth noting regardless: the safety net is missing, and `onActivated()` is the natural place for it.

### P2 — `commitLivePoint` does not re-check the commit gate

`ShapeMeasureViewModel.commitStep` guards itself with `if (!frames.liveStable) return`
(`ShapeMeasureViewModel.kt:137`). `MeasureViewModel.commitLivePoint` checks only `session != null`
and `frames.live != null` (`MeasureViewModel.kt:123-128`) — the gate is enforced solely by
`addEnabled` on the button. A tap dispatched one frame before the gate drops still commits. It
cannot simply mirror the shape check, because a snap deliberately bypasses `liveStable`;
`frames.commitReady` is the field that expresses the right condition and is not consulted. Low
impact (one frame of race), but it is the difference between "the button was enabled" and "the
reading was trustworthy", and F4 widens that window to 10 s.

---

## Clean — checked, nothing found

- **`reticleOnSurface` vs `addEnabled`.** Both overlays derive the reticle from the same terms as
  the button (`MeasureFrameLoop.kt:274` vs `MeasureFrameStream.kt:146`; `ShapeFrameLoop.kt:222` vs
  `ShapeFrameStream.kt:37`). They cannot disagree. The disagreements found above are all
  hint-vs-control or affordance-vs-control, never reticle-vs-`+`.
- **Snap index bounds after an undo.** `snapTarget` reads the held index through
  `positions.getOrNull(currentlySnapped)` and only ever returns an in-bounds index, so
  `points[snapped]` in `MeasureFrameLoop.kt:102-108` cannot go out of range when the point list
  shrinks under a live lock. The stale hint text lasts one frame.
- **Box phase transitions vs hint text (item 5), apart from F7.** origin → "place the box's
  corner", `SizingEdgeU` → "first edge", `SizingEdgeV` → "second edge", `SizingHeight` → "finish
  the box"; each matches what the next tap does. The chain preview (A→B, B→C) and the committed
  parallelogram `[A, B, C, A+C−B]` describe the same figure, and `undo` from `SizingHeight`
  restores `edgeU` verbatim rather than recomputing it.
- **Undo / redo / Clear anchor identity.** Deferred detach through `UndoRedoStack.onEvict` plus
  `isAnchorOrphaned` is consistent; `drainWithoutEviction` empties both deques and drops both flags,
  so `canRedo = false` after a clear is never a lie. `ShapeUiState.canUndo` matches exactly the
  cases `undo()` can act on.
- **Clear/Undo leaving stale frame-stream values.** `clear()` resets `overlay` and `worldPoints` but
  not `live` / `snappedIndex` / the gate, so for one frame after Clear the hint can read "Snapped to
  point 2" with an empty point list and `+` enabled via the stale snap in `commitReady`. The next
  frame repairs all of it, and no tap can physically land inside one frame. Reported here rather
  than as a finding — except that F4 makes "the next frame" arbitrarily far away.
- **Rotation-driven stale visuals.** Both module activities are `screenOrientation="portrait"` with
  `configChanges="orientation|screenSize|…"`, so a retained ViewModel is never re-attached to a
  fresh composition and `releaseAll()`'s failure to clear `frames.overlay` is unreachable.
- **The shared `ArSessionFrameStream` and `PoseProjector`.** Every field on the session stream is
  genuinely session-level, and only the active tool's loop writes it, so there is no cross-tool
  leak. The projector is written once per frame before any read. One nit: `projector.update` sits
  *after* the untracked-frame early return, so `DistanceOverlay`'s `onDragStart` hit-test uses the
  last tracked frame's matrices if a drag begins during a hiccup.
- **The premise's `drawHeld` external input.** No such symbol on this branch — `grep -r drawHeld
  AR_feature app` is empty. The only external-input stream sharing the pattern here is the drag on
  `MeasureFrameStream`, covered in F9.

---

## Recommended order

1. **F1** — wrong wherever a plane ends, never self-repairs, and it is the new feature's core
   affordance.
2. **F2** — reachable in the first seconds of most sessions, and the new centre indicator makes the
   contradiction loud.
3. **F4 + F5** — one root cause (nothing invalidates tool state when the session's frames stop or
   the session is replaced) and the only findings that can produce a confidently wrong *number*.
4. **F3** — trivial fix, hit by every shape measurement.
5. **F7, F8, F6** — real, narrower.
6. **F9 / P1 / P2** — hardening.

## Unresolved questions

- F5: does `Anchor.getPose()` on an anchor from a closed `Session` throw or return a stale value?
  Determines whether a fired watchdog crashes or silently draws garbage. Not answerable from source
  here; needs a device or ARCore's native behaviour confirmed.
- F2: how often is `anyPlaneTracked` false while a depth/feature reading is stable? Same open
  unknown as CLAUDE.md's #1, from the other direction — the answer sets F2's real-world frequency.
- P1: whether `ModalBottomSheet` opening mid-drag delivers a pointer cancel. One device check.
- F4: is there a stall mode in which frames stop but the camera preview keeps updating? If so the
  frozen-image tell disappears and F4 becomes much harder for a user to notice.
