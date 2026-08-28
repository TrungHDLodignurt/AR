# ARuler teardown — scanning UI & hint rules, mapped onto our AR_feature

Reference app: `com.grymala.aruler` (Grymala "AR Ruler App: Tape Measure Cam") **v3.3.5 (2980)**, minSdk 32 / targetSdk 37.
Pulled from device `18311FDF60085N` (Pixel 6): `base.apk` + `split_config.arm64_v8a` + `split_config.xxhdpi`.
Phases run: 0 (acquire), 1 (static recon), 2 (dynamic confirm on device), 3 (targeted decompile).
Scope: **scanning/coaching UI + hint trigger rules only**. Ads/paywall/monetisation deliberately not analysed.

---

## 1. Answer in one paragraph

ARuler does **not** rely on a text toast while ARCore is finding planes. It runs a **two-layer
coaching UI simultaneously**: a 162dp Lottie animation dead-centre (a line-art phone sweeping over a
grid plane) with the label *"Planes detection…"* underneath, **plus** a bottom advice card that
**auto-rotates 6 fixed tips every 5 s** with an Instagram-story style segmented progress bar, a **×**
to dismiss and a persistent **?** button to bring it back. Which of the two advice modes shows is
decided by a 10-state `ControlsStateSwitcher`, not by ARCore's `TrackingFailureReason` — they never
surface ARCore's failure reasons at all.

---

## 2. Stack (evidence)

| Layer | Conclusion | Evidence |
|---|---|---|
| UI toolkit | **Views/XML** (legacy) with Compose only in newer side screens | 160 `res/layout/*.xml`; `androidx.compose.*` versions present but AR screen is `activity_main.xml` |
| AR | ARCore raw, own GL renderer | `libarcore_sdk_c.so`, `libarcore_sdk_jni.so`, `assets/shaders/`, `RecordableGLSurfaceView` |
| Plane maths | **PCL** (Point Cloud Library) native | `libpcl-lib.so` (888 KB), package `com.grymala.pclgrymala` |
| CV / auto-detect | **OpenCV + TFLite + NMS + custom circle detector** | `libopencv_java4.so` (8.9 MB), `libtensorflowlite_jni.so`, `libnms.so`, `libcircle-detector-lib.so` |
| DI | Hilt | `Hilt_ARulerMainUIActivity` |
| Animation | Lottie | `res/raw/ar_search.json`, `res/raw/ar_load.json`, `CustomLottieAnimationView` |
| Coaching media | 7 MP4s bundled | `planes_advanced.mp4`, `instruction.mp4`, `onboarding_tools_advanced.mp4`, … |
| Billing | RevenueCat | `com.revenuecat.purchases.*` |

Note for us: **we already beat them on the plane-maths side** — they carry ~11 MB of native libs
(OpenCV + PCL + TFLite) for what our `AR_feature` does in pure Kotlin + ML Kit.

---

## 3. Mechanism — the scanning UI, decoded

### 3.1 Layer A: centre indicator (`R.id.searchIndicator`)

`res/layout/activity_main.xml`:

```
searchIndicator (LinearLayout, layout_centerInParent, initially gone)
├── CustomLottieAnimationView  162dp × 162dp, lottie_rawRes=@raw/ar_search, autoPlay, loop
└── TextView  "Planes detection…"  white 16sp, centred
```

- `ar_search.json` = `"nm": "Plane_detection_512"`, 512×512, **30 fps, 100 frames (3.33 s loop)**,
  a single PNG image-sequence layer. Confirmed live on device: Lottie node at `[297,835][783,1321]`
  (486 px = 162dp @3x), label at `[334,1333][746,1390]`.
- A second Lottie, `loadIndicator` → `@raw/ar_load`, 140dp centred, is the *"aiming lost"* spinner.
- Hidden in `o1()` ("plane detection end") via `setVisibility(4)`, which also fires the analytics
  events `plane_detection_end` and `planes_detection_time_sec` — **they track time-to-first-plane
  as a product KPI.**

### 3.2 Layer B: bottom advice card (`AdviceLayout` + `SegmentProgressView`)

`plane_detection_advice_layout.xml` + `com.grymala.aruler.ui.AdviceLayout`:

- Two mutually exclusive modes, toggled by `ARulerMainUIActivity.u1(boolean single)`:
  - **`u1(false)` — carousel**: `adviceMultiple` + `adviceClose` (×) + `SegmentProgressView` visible.
  - **`u1(true)` — single**: only `adviceSingle`, no ×, no progress, animator cancelled.
- Carousel content, fixed order, `AdviceLayout.z`:
  1. `advice_1` "Aim the camera on a textured surface (either horizontal or vertical)"
  2. `advice_2` "Select appropriate distance \n (0.5m - 3m)"
  3. `advice_3` "Make sure the lighting is good"
  4. `advice_4` "Move the camera farther / closer to the surface"
  5. `advice_5` "Try to change the viewing angle"
  6. `advice_6` "Try to recognize another surface"
- **Dwell = 5000 ms per tip**, wrapping `i % 6` forever (R8 folded the literal onto
  `HTTPTimeoutManager.SUPPORTED_FALLBACK_TIMEOUT_MS = 5000`).
- `SegmentProgressView`: 6 rounded 3dp strokes, 10dp gaps; consumed segments at alpha 255,
  upcoming at **alpha 60**, current segment fills 0→1 with a `DecelerateInterpolator` — literally an
  IG-story bar. Confirmed on device at `[264,1965][816,1980]`.
- Text swap is height-animated: a hidden `adviceFake` TextView is set to the *next* string first, and
  an `OnGlobalLayoutListener` reads its height so the card grows/shrinks smoothly (400 ms in, 50 ms out).
- Single message = `help_move_closer` "Move closer or point the camera in the other direction".
- Interactions: **tap card → next tip**, **× → dismiss**, **? (`adviceHelp`, 48dp, bottom-end,
  always present) → re-open**.
- Styling: `bg_advise` = solid `#884749a0` (indigo @ 53 %), radius **30dp on three corners, bottom-right
  square** so the card visually points at the `?` button. Horizontal margin 50dp.

### 3.3 The rule table (`ControlsStateSwitcher`, `com/grymala/aruler/ar/b.java`)

10 states: `PLANES_SEARCH, PLANE_NOT_AIMED, PLANE_AIMED, PLANE_POINTER, DRAWING,
DRAWING_CAN_BE_STOPPED, NORMAL, AUTODETECT, VIDEO, ZOOM`. Initial state = `PLANES_SEARCH`.
`kr.a(v,d)` = fade **in**, `kr.b(v,d)` = fade **out**.

| State | Advice mode | `loadIndicator` | Other chrome |
|---|---|---|---|
| `PLANES_SEARCH` | **carousel** (6 tips × 5 s) | hidden | tool + unit chrome hidden |
| `PLANE_NOT_AIMED` | **single** ("move closer / point elsewhere") | **shown** (`ar_load`) | chrome hidden |
| `PLANE_AIMED` | — | — | element icons shown, plane-select UI hidden |
| `PLANE_POINTER` | — | — | plane-pointer mode flag |
| `DRAWING` | — | — | element icons **hidden**, "stop" button shown |
| `DRAWING_CAN_BE_STOPPED` | — | — | element icons shown |
| `NORMAL` | — | — | full bottom toolbar + tool sheet |
| `AUTODETECT` | — | — | `autodetect_layout`, hint "Place the object in the centre of the screen." |

**Not present anywhere:** any mapping from ARCore `TrackingFailureReason` → message. No
"not enough light", no "moving too fast". Their advice is *generic and pre-written*; ours is
*specific and reactive*. That is our advantage, see §5.

### 3.4 Layer C: first-run anchored bubbles (`ArHintManager`, `defpackage/ny.java`)

`view_hint.xml` = `GrymalaTextView` pill, **`accentYellow #ffc700`**, radius 25dp, white 13sp,
`maxWidth 128dp`, elevation 4dp, **plus a 14×9dp triangle pointer** (`ic_ar_hint_pointer_yellow`)
below it — an anchored callout, not a bottom toast. Attached to a real button via `ArHint.c(offset, view)`.

Driven by a **persisted step counter `jx.g` (0..3)**, advanced as the user completes each step
(`jx.f(2)`), suppressible by `jx.w` ("don't show again"):

| `jx.g` | Anchor view | String |
|---|---|---|
| 0 | `create_node_btn` | "Start measuring" |
| 1 | `select_element_btn` (multipoint) / `create_node_btn` | "Stop measuring" |
| 2 | `activity_main_video_recording_view` | "Take a photo" |
| 3 | same | "Hold to record video" |

Shown **1000 ms after** the element icons become visible (`postDelayed(..., 1000L)`), skipped
entirely for auto-detect tools, and it waits for a real layout pass
(`OnGlobalLayoutListener`) if the anchor has not been measured yet.

### 3.5 Other AR-screen chrome they have and we don't

- `button_flash_toggle` — torch toggle, top bar, right (`[870,72][1008,210]` on device).
- `autofocus` — toggle in settings.
- `menu_show_planes` / `select_plane` / `reset_plane` + `instruction_show_planes_title`
  "Measure on planes" / `..._description` "Select a recognized plane and start measuring on it
  (this can be especially useful in hard-to-reach places)" — a **plane-lock mode**.
- `no_surfaces` "At least one surface must be recognized before" — a hard gate on actions that
  need a plane.
- `finish_element` "Finish element before!", `task_not_finished` "Previous task hasn't finished yet",
  `no_horizontal_elements`, `no_elements_to_capture` — **blocking-action error toasts**.
- `recovering` "Recovering…" — a distinct state for ARCore re-localisation.
- `autowall` "Curved wall", `sticking` — snap-to-geometry.

---

## 4. What our `AR_feature` does today

Files: `ar/presentation/camera/components/ArCameraHints.kt` (hint text selection),
`ArCameraChrome.kt::ARToast` (the single presenter), `ArCameraScreen.kt:352` (the one call site).

- **One** presenter: `ARToast` — `ChromeDark #1A1D1F @0.88`, r16, 12dp blur, white 13sp/500,
  bottom-centre, `padding(bottom = 160.dp)`.
- Priority chain at the call site: `commitToast` (1.5 s "Point added") → `trackingFailureHint()`
  → tool hint (`distanceHint` / `shapeHint`).
- `distanceHint` order: dragging → unsteady → `!anyPlaneTracked` → `live == null` →
  `pointCount == 0` → per-segment / per-point.
- We **do** map all 5 `TrackingFailureReason` values to actionable copy (§5) — ARuler does not.
- Warm-up: `ARToast("Getting the camera ready…")` centred, and nothing else on screen.
- Signals already available in `ArSessionFrameStream`: `cameraReady`, `tracking`, `anyPlaneTracked`,
  `depthSupported`, `trackingFailure`, `lastFrameAtMillis`. Plus `SteadinessGate.stable`.

---

## 5. Gap analysis — ARuler vs us

### Where we are already better (do not regress these)
1. **Reactive copy.** Our 5 `TrackingFailureReason` strings ("Not enough light — try a brighter
   area", "Moving too fast — slow down", …) beat their 6 static tips, because they name the actual
   cause. Their app cannot tell you *why*.
2. **Hit-source attribution.** `armeasure_hint_point_on_surface` "Point %1$d on %2$s" tells the user
   whether a reading came from a plane, depth map or feature point. ARuler has no equivalent.
3. **Steadiness feedback.** `armeasure_hint_reading_unsteady` fires from a real 5-frame gate.
4. **11 locales** shipped; their strings are English-first with no per-tip localisation strategy.
5. **No native-lib weight** — no OpenCV/PCL/TFLite in our AR path.

### Where they are better — the actual gaps

| # | Gap | Their solution | Impact |
|---|---|---|---|
| **G1** | **Nothing tells the user the app is working while scanning.** We show a 13sp pill at the bottom; a first-time user reads that as "broken". | 162dp centred Lottie + "Planes detection…" | **High** — this is the first 3–10 s of every session |
| **G2** | **A static hint that never changes reads as a dead end.** If `!anyPlaneTracked` persists 20 s our copy is byte-identical the whole time. | Escalate to a 6-tip carousel, 5 s each, with a progress bar that proves time is passing | **High** |
| **G3** | **No way to get help back.** Nothing on our screen answers "why isn't this working". | Persistent `?` button, always re-opens the tips | **Medium** |
| **G4** | **Hints are never anchored to the control they talk about.** "Tap + to add a point" sits 160dp away from the + button. | Yellow bubble + triangle pointer attached to the real button, first run only, step-persisted | **Medium** |
| **G5** | **No torch.** Our own copy says "Not enough light — try a brighter area" and then offers no way to fix it. | `button_flash_toggle` in the top bar | **Medium-High** — cheap, closes our own loop |
| **G6** | **Blocked actions fail silently.** `addEnabled == false` just greys the capture button; the user is not told what is missing. | `no_surfaces`, `finish_element`, `task_not_finished` toasts | **Medium** |
| **G7** | **No re-localisation state.** After a stall our watchdog silently remounts the session; the user sees a frozen screen for up to 10 s. | `recovering` "Recovering…" + `ar_load` Lottie | **Medium** |
| **G8** | **No plane-lock.** Cannot measure a surface you can no longer aim at. | `select_plane` / `reset_plane` / "Show planes" | **Low-Medium**, bigger feature |
| **G9** | **No time-to-first-plane telemetry.** We cannot tell whether tuning helped. | `plane_detection_end`, `planes_detection_time_sec` | **Low** (but cheap) |

---

## 6. Proposed work, ranked (all inside `AR_feature`, nothing in AIP936)

Sizes are relative. Every item is additive to the existing `ARToast` chain — no rewrite.

### Tier 1 — closes the biggest first-impression gap

**P1. `ScanningIndicator` (G1) — centred scan affordance.** `S`
Shown while `cameraReady && !anyPlaneTracked` (and during `!isWarmedUp`, replacing the current bare
toast). Compose-drawn, no Lottie dependency: a `Canvas` grid quad in perspective + a sweeping
highlight + label. New file `ar/presentation/camera/components/ScanningIndicator.kt`.
Do **not** copy `ar_search.json` — it is their asset (skill ground rule).

**P2. `CoachingCarousel` (G2 + G3) — escalation, not a new toast.** `M`
State machine in a new `ar/presentation/camera/CoachingState.kt`:
`Idle → Hint(steady copy) → [same condition held ≥ 8 s] → Tips(carousel)`, with `Dismissed` and a
`?` re-entry. Tips reuse our existing reactive strings, ordered by what the signals say (not their
fixed list): light → motion → texture → distance → angle. Segmented progress bar as a small
`Canvas`. Dwell 5 s (their number is a reasonable, field-tested default).

**P3. Torch toggle (G5).** `S`
`ArCameraTopBar` third slot. ARCore path: `Config.setFlashMode(Config.FlashMode.TORCH)` on the shared
session, guarded by `session.isFlashModeSupported`. Must be re-applied after the watchdog remount —
hold the flag in `ArCameraViewModel`, not in composition.

### Tier 2 — removes silent dead ends

**P4. Blocked-action feedback (G6).** `S`
Make the capture button tappable-when-disabled and answer with a reason toast
(`armeasure_blocked_no_surface`, `..._finish_shape_first`) instead of ignoring the tap.

**P5. `Recovering` state (G7).** `S`
`ArSessionFrameStream` already has `lastFrameAtMillis`. Add a derived `isStalled` at
`> 2000 ms` (well before the 10 s remount) and show "Reconnecting…" centred, so the watchdog's
10 s window stops looking like a freeze.

**P6. Anchored first-run bubble (G4).** `M`
One reusable `AnchoredHintBubble` (pill + triangle, our `Signature` colour, not their yellow), driven
by a `DataStore`-persisted step counter, `maxWidth ~128dp`, 1 s delay, with a "don't show again".
Steps: place first point → close segment → switch tool.

### Tier 3 — optional

**P7. Time-to-first-plane logging (G9).** `XS` — one log/callback out of `ArMeasureKit`.
**P8. Plane-lock mode (G8).** `L` — real feature, separate plan.

### Suggested order
`P1 → P3 → P2 → P5 → P4 → P6`, then re-assess. P1+P3 are ~a day and cover the two complaints a
first-time user actually has.

---

## 7. Policy / licensing notes

- Nothing in ARuler's scanning path is a policy risk for us (no overlay window, no accessibility
  service, no background location). Their `AndroidManifest` risk surface is ads SDKs only, which is
  out of scope here.
- **Do not copy** `ar_search.json`, `ar_load.json`, `ic_ar_hint_pointer_yellow`, `bg_advise`, the
  Ubuntu font, or any of the 7 MP4s. Their advice *strings* are also theirs — we already have better
  copy of our own.
- The mechanism (state machine → which coach layer, 5 s dwell, segmented progress, anchored bubble
  with a persisted step counter) is a UI pattern, not protected expression. Clean-room reimplementation.

---

## 8. Confirmed vs inferred

**Confirmed** (device dump + decompiled source): the two-layer scanning UI and its exact
sizes/positions; the 6 tips and their order; the 5000 ms dwell; the `u1(true/false)` single-vs-carousel
split; the 10-state enum and cases 1–7; `SegmentProgressView`'s alpha-60 upcoming segments; the
`jx.g` 0..3 anchored-bubble ladder; `bg_advise` `#884749a0`; the flash toggle's existence and bounds;
the absence of any `TrackingFailureReason` mapping.

**Inferred**: exactly which per-frame condition flips `PLANES_SEARCH → PLANE_NOT_AIMED`
(`ARulerActivity.O()` is 1998 instruction units and jadx refuses it; the classifier `N0()/O0()` is
readable but the caller is not). Best reading: `PLANES_SEARCH` until the first tracked plane exists,
then `PLANE_NOT_AIMED` whenever the centre ray misses every plane. Our `anyPlaneTracked` /
`live == null` split already matches that shape.

## 9. Open questions

1. P2's escalation delay — 8 s is a guess. Their carousel starts immediately at `PLANES_SEARCH`; do
   we want immediate-on-scan (matching them) or escalate-after-N-seconds (less noisy)?
2. P6 anchored bubbles: keep the yellow-family accent for "coach mark" semantics, or use our
   `Signature` colour and risk it reading as a normal chip?
3. Does the host (AIP936) want the time-to-first-plane number surfaced through `ArMeasureKit`, or is
   a Logcat line enough for our own tuning?
