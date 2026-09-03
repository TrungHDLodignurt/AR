# CLAUDE.md — ar-tape-measure @ experiment/air-draw

Read automatically at the start of every session in **this worktree**.

This is the air-pen experiment. Everything in the measuring app's own `CLAUDE.md` still applies —
same repo, same module, same invariants. This file covers only what is different here.

> **You are in `ar-tape-measure-air-draw/`, on `experiment/air-draw`.**
> The measuring app lives in `ar-tape-measure/` on `refactor/mvi-alignment`. Both windows open in
> Android Studio titled `ar-tape-measure`; tell them apart by the path in brackets. A file edited
> in the wrong directory lands on the wrong branch.

---

## Mandatory session protocol

1. Read this file (auto-loaded) **and** the parent branch's `CLAUDE.md` for the shared invariants
   — it is not on this branch, so read it from `../ar-tape-measure/CLAUDE.md`.
2. `git log --oneline -3` and `git status --short` before assuming anything about state.
3. Update this file at the end of any work that changes the design, the verdict, or the open
   questions below. Keep it short: this is an experiment, and a long document about an experiment
   that may be deleted is wasted effort.

---

## What this is

Hold either volume key, move the whole phone, and a stroke is laid down behind a nib floating
30 cm ahead. Entertainment, explicitly — **not** a measuring tool. Branched so the measuring app's
knowledge cannot be damaged by it.

Commit `a00ee91`. Reached from the mode sheet as **✎ Air draw**, the fifth `MeasureTool`.

| File | Role |
|---|---|
| `ar/presentation/airdraw/AirDrawFrameStream.kt` | strokes, and the frozen-direction rule |
| `ar/presentation/airdraw/AirDrawFrameLoop.kt` | per-frame append, `Pose.forward()`, width scaling |
| `ar/presentation/airdraw/components/AirDrawOverlay.kt` | ink rendering |
| `ar/presentation/host/ArCameraActivity.kt` | volume keys → `drawHeld` |
| `ar/presentation/camera/ArCameraScreen.kt` | the `MeasureTool.AirDraw` branches |

---

## The one design decision that matters

The nib's direction is **captured at key-down and held in world space** for the whole stroke.
Only the phone's *position* moves it after that. Three options were weighed:

| Nib placement | Rotation moves it | Visible while drawing | Verdict |
|---|---|---|---|
| Fixed in camera space (*Just a Line*) | `2·d·sin(θ/2)` — **5 cm per 10° wobble** at d = 30 cm | yes | tremor scribbles unintended line |
| At the phone itself | exactly zero | **no** — behind the 0.05 m near plane, unprojectable | cannot see the stroke you are making |
| **Frozen direction** | zero once the stroke runs | yes | chosen |

The lever arm is what kills the first option: it converts angular noise into positional noise.
Rotating mid-stroke slides the nib off screen centre but leaves the ink where the phone's
translation put it, which is the honest thing to show.

Supporting choices, each there because its absence is visible immediately:

- A point is recorded only after the nib has travelled 1 cm. A still hand otherwise emits 60
  coincident points a second; the gate doubles as free smoothing.
- Stroke width and nib radius scale with `1/distance` — the only perspective cue a
  2D-Canvas-over-scene overlay must supply by hand.
- White ink over a dark underlay offset 0.6 px, same reason as the measurement endpoints.
- Live stroke in `Signature`, finished strokes in white.

No hit-testing, no depth, no plane: the pen is `frame.camera.pose`, available the moment tracking
starts. No accuracy claims — ARCore drift puts strokes made a minute apart centimetres out, which
disqualifies this for measuring and is invisible for drawing.

---

## Known limitation

The AR Activity **swallows both volume keys**, so volume cannot be changed while it is open.
Acceptable for an experiment. A shipping version would consume them only while the pen tool is
selected, which requires the Activity to know the active tool.

---

## Open questions — the point of the probe

1. **Is it fun to hold?** Nothing else here matters if the answer is no, and no amount of
   discussion answers it — five minutes with the device does.
2. Does rotating mid-stroke leave the ink still? If it drags, the sign in `Pose.forward()` is
   wrong (ARCore cameras look down **−Z**).
3. Is 30 cm the right nib distance?
4. Can depth be read, or do strokes tangle? **A tangle is the signal that the local 3D lattice
   earns its place** — ~150 points at 3 cm spacing, a sphere mask around the nib. Deliberately
   not built yet: adding it before question 1 is answered is building on an unknown.
5. Do strokes look kinked? Only the 1 cm gate smooths them today; Catmull-Rom is the next step.

---

## Deliberately not done

Local 3D lattice · Catmull-Rom smoothing · colour picker · persistence across sessions ·
stroke-length readout. All cheap to add, all pointless before question 1 has an answer.
