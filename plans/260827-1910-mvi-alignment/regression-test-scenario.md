# AR_feature — post-refactor regression test scenario

Supersedes `final-verification-round.md` (deleted). Context: [plan.md](plan.md).
Written 2026-08-27 for the state of `feature/photo-reference-measure` at `7a8dec0`.

**What this document is.** The module was just converted from plain Compose state holders to MVI
(`ar/**` and `photo/**`), its photo coordinates moved from display space to bitmap space, and its
per-screen composables moved into `components/` packages. Three sessions with disjoint file ownership
did that work, and the presentation layer has **no automated tests at all** — so everything below is
the whole safety net.

**Its primary purpose is not "does the app work".** It is: *did any of the bugs this module already
shipped and fixed come back?* Those are section 1, first, each one naming the original symptom so a
runner knows what a failure looks like without having to guess. Section 2 is the risk the refactor
itself introduces. Section 3 is broad smoke. Section 4 is what can only be checked by reading code —
listed separately and honestly, because a grep is not a device proof.

---

## 0. Before you start — read this part, it has cost time before

### 0.1 Hardware available *today*

    adb devices          # re-read it. Serials have changed twice; never hardcode one.

At the time of writing exactly **one** device is attached:

| Device | Serial (verify!) | ARCore | Can exercise |
|---|---|---|---|
| Pixel | `99261FFAZ0077C` | **yes** | everything except the unsupported-AR path |
| Joy_4 (low-end) | `BKB00251473` | **no** | **NOT ATTACHED.** The only device for the unsupported-AR path and the only honest device for the frame budget |

Every item is tagged:

- **[Pixel]** — runnable now, on the attached Pixel.
- **[needs Joy_4]** — cannot be run today. Do not substitute a faster phone and call it done; for the
  performance items the whole point is the device with no headroom.
- **[either]** — runnable now; ideally repeated on the Joy_4 when it is back.

### 0.2 Activity recreation — the trap that wasted an attempt

Recreation **with state restored** is reproduced with `am kill`:

    adb -s <serial> shell input keyevent KEYCODE_HOME
    adb -s <serial> shell am kill vn.quancua.artapemeasure
    # relaunch from the launcher (or: adb -s <serial> shell monkey -p vn.quancua.artapemeasure -c android.intent.category.LAUNCHER 1)

**`always_finish_activities` does NOT work for this.** It *destroys* the Activity rather than saving
it, so it tests the wrong path — a previous session burned a run on it. If you set it for any reason,
reset it: `adb -s <serial> shell settings put global always_finish_activities 0`.

Also: both Activities declare `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`
and `screenOrientation="portrait"`, so **rotating the device reproduces nothing**. Backgrounding is
the trigger. (This also means "rotate to force a relayout" from older checklists is dead — use the
relayout triggers named in R1/X6 instead.)

### 0.3 Installing

**Never `./gradlew installDebug`** — it picks the wrong device. Always:

    ./gradlew :app:assembleDebug
    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

App id `vn.quancua.artapemeasure`. Activities (both `exported=false`, launch via the app UI):
`vn.apero.armeasure.ar.presentation.host.ArCameraActivity`,
`vn.apero.armeasure.photo.presentation.ArPhotoActivity`.

### 0.4 Release build — required for anything performance-related

Debug overhead dominates and hides the effect: **2.7 s debug vs 648 ms release cold start on the same
device**. Carried over verbatim from the old checklist section C, and known-good (an APK was signed
this way already):

    ./gradlew :app:assembleRelease
    BT=~/Library/Android/sdk/build-tools/36.1.0
    $BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/ar-rel.apk
    $BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
        --key-pass pass:android --ks-key-alias androiddebugkey /tmp/ar-rel.apk
    $BT/apksigner verify /tmp/ar-rel.apk        # silence = pass
    adb -s <serial> install -r /tmp/ar-rel.apk

### 0.5 Fixtures to have on the desk

- A **payment card**, 85.60 x 53.98 mm (ISO/IEC 7810 ID-1) — precise and always to hand.
- An **A4 sheet**, 210 x 297 mm.
- A **custom reference** named `phỏn`, 150 x 70 mm — the one used in past testing; several items
  depend on a *custom* object specifically, because built-ins never showed the A4-fallback bug.
- A **ruler** that can lie flat in the same frame as the reference, on the same surface.
- A **textured surface** for AR (rug, wood table — not a blank wall).

### 0.6 Logging + one-time setup per device

    adb -s <serial> shell setprop log.tag.PhotoAutoFit DEBUG
    adb -s <serial> logcat -c

`PhotoAutoFit` (in `photo/data/SegmentQuad.kt`) is the detection diagnostic. Before trusting any
auto-fit case, check its line reports the reference you actually picked — `targetRatio` must equal
`longSideMm / shortSideMm`. A wrong reference makes the case worthless.

### 0.7 What the runner can automate from a terminal

These need no eyes on the screen and should be scripted first; they are the cheap half.
Everything else in this document needs a human looking at the display or driving a gesture.

| id | check |
|---|---|
| T1 | `./gradlew :AR_feature:testDebugUnitTest` — expect **185 tests, 0 failures, 2 skipped** |
| T2 | `./gradlew :app:assembleRelease` — must pass with R8 + resource shrinking |
| T3 | `adb -s <serial> logcat -b crash -d \| grep -c FATAL` → 0 |
| T4 | `adb -s <serial> shell pm list packages \| grep quancua` → present |
| T5 | camera cache listing (R7) |
| T6 | `Choreographer: Skipped` / `Davey!` grep counts (R11, X8) |
| T7 | the README §16 maintenance greps (section 4) |
| T8 | CAMERA permission revoke/grant (R6) |

---

## 1. Known regressions — run these first

Each item: the original symptom, then the steps, then pass/fail. If any of these fails, the refactor
put a shipped bug back and that is more important than anything in section 2.

### R1 — detected quad drawn off the object after a canvas reflow  **[either]**

*Original symptom.* `quad`, `segments` and `homography` were stored in display-space pixels. The
quad-editing screen grew a "↩ Detect again" button as soon as a quad existed, the `Column` reflowed,
the photo re-aspect-fitted into a shorter box — and all three stayed at their old coordinates,
**~180 px out on a 2048 px-tall photo**. "The box doesn't sit on the object" for three sessions.

*What changed.* Coordinates are now in the photo's own pixel grid, `onCanvasResized`/`remapToCanvas`
are deleted, **and the "Detect again" button no longer exists at all** (grep confirms: no
`Detect again`, no `TEMPORARY` marker in `src/main`). So the original trigger is gone; the remaining
relayout triggers are the instruction-text swap and the reserved check-button slot.

- [ ] **R1.1** Pick a *long-named custom* reference (`phỏn`) so the instruction text is at its
      longest. Pick a photo, tap the object. Watch the instruction box at the moment the quad
      appears: its height must not change (`InstructionBox` is passed `sizingText` = the unused
      wording, so the box measures to `max(place, adjust)`).
      **Pass:** instruction box height constant, quad lands and stays on the object.
      **Fail (original):** the box grows a line, the photo box shrinks, the quad sits above the object.
- [ ] **R1.2** With the quad placed but not confirmed, background the app and return. The quad must
      still be on the same photo feature.
      **Fail (original):** quad shifted vertically by roughly the height the layout changed by.

### R2 — the same reflow corrupting a hand-captured measurement fixture  **[terminal + code read]**

*Original symptom.* The ground-truth quad was hand-dragged against the *post*-reflow layout but
converted to bitmap space using the *pre*-reflow canvas. Every IoU measured against it was
meaningless, including a "this photo is unwinnable" conclusion that was later retracted. It cost a
session of tuning.

- [ ] **R2.1** `grep -n "@Ignore" AR_feature/src/test/**/RealPhotoAutoFitTest.kt` — both assertions
      must still be disabled, and `BaselineIoU = 0.26` must still be documented as meaningless.
      **Fail:** somebody re-enabled a test against the bad fixture.
- [ ] **R2.2** If anyone captures a *new* fixture: it is only trustworthy after measuring per-edge
      luminance contrast (the check that caught the corruption — bad quad read 60.7 / 4.2 / 0.8 / 1.8,
      ML Kit's read 133.7 / 30.6 / 30.4 / 31.6). Do not accept a capture without that number.
      This item is a *rule*, not a run; tick it only if a capture happened.

### R3 — returning from the OEM camera/gallery resets the flow to "choose reference object"  **[either]**

*Original symptom.* All five screen-level states were plain `remember`. Handing the foreground to the
OEM camera/gallery recreated the Activity and reset everything, snapping the flow back to
"Chọn đối tượng tham chiếu".

- [ ] **R3.1** Pick a reference → the pick-photo sheet opens → tap "Chọn từ thư viện" → return by
      picking an image.
      **Pass:** still past the picker, correct reference label, the picked photo in use.
      **Fail (original):** back on the reference picker with the photo lost.
- [ ] **R3.2** Same, but return by pressing **back** from the gallery without picking.
      **Pass:** still past the picker, the sheet reopens (the sheet owns the launchers, so it being
      restored open is load-bearing, not incidental).

### R4 — a **custom** reference silently falling back to "A4 paper" after recreation  **[either]**

*Original symptom.* The re-resolve effect keyed only on the id while reading the custom list; moving
that list to an async load left it empty on the first pass, so a user-created object silently fell
back to built-in A4 and **the screen calibrated against the wrong size**. Built-ins never showed it.
This bug regressed once already. It is the highest-value item in section 1: it is silent.

*What changed.* `State.reference` is now *derived* from `chosenReferenceId` over
`builtInReferenceObjects + customReferences`, and **there is no A4 fallback anywhere**; a chosen-but-
unresolved id renders as nothing for a frame or two.

- [ ] **R4.1** Create the custom `phỏn` 150x70 mm, select it, then `HOME` → `am kill` → relaunch
      (0.2). **Pass:** the label reads `phỏn`, never "A4 paper".
      **Fail (original):** label says A4, or measurements come out scaled by 210/150.
- [ ] **R4.2** Same, then confirm a quad and measure a known 100 mm. The number must match the
      pre-kill calibration. A reading off by a factor near `150/210 = 0.71` or `210/150 = 1.4` is the
      fallback back.
- [ ] **R4.3** Watch the restore closely for a **blank flash** where the reference label/box should
      be. One blank frame is the accepted design; a visible flash is a finish problem to report — the
      fix is rendering the last-known label, **not** reintroducing a fallback object.

### R5 — photo taken with the camera, but the app re-showed the picker  **[either]**

*Original symptom.* The destination `Uri` was a plain `remember`. When the OEM camera app took the
foreground the Uri was lost, so the captured photo became unreachable: the JPEG was on disk, the
picker simply reappeared.

*Status.* `pendingUri` deliberately stayed as `rememberSaveable` in `CameraCapture.kt` — unchanged by
the refactor, and **unit tests cannot reach it**. This is device-only.

- [ ] **R5.1** Pick a reference → "Chụp ảnh" → take a photo in the OEM camera → accept it.
      **Pass:** the app proceeds *with that photo*.
      **Fail (original):** the pick-photo sheet reappears and the shot is nowhere.
- [ ] **R5.2** Take a photo and then **cancel** in the camera app on a device whose camera writes the
      file anyway. **Pass:** the written capture is accepted (several OEM camera apps report cancelled
      but do write). Not reproducible on every camera app — note "n/a" if this camera does not do it.

### R6 — crash on "take photo" with CAMERA permission revoked  **[either, terminal-assisted]**

*Original symptom.* The module declares CAMERA for ARCore, so the platform requires the app to
*hold* it before `ACTION_IMAGE_CAPTURE` may start. Without the grant the app **died with a
SecurityException** on the "take a photo" tap.

    adb -s <serial> shell pm revoke vn.quancua.artapemeasure android.permission.CAMERA
    adb -s <serial> logcat -c

- [ ] **R6.1** With the permission revoked, tap "Chụp ảnh".
      **Pass:** the runtime permission dialog appears; granting proceeds to the camera; denying
      returns to the sheet with no crash.
      **Fail (original):** app dies. Confirm with
      `adb -s <serial> logcat -b crash -d | grep -c SecurityException` → must be 0.
- [ ] **R6.2** Re-grant afterwards:
      `adb -s <serial> shell pm grant vn.quancua.artapemeasure android.permission.CAMERA`

### R7 — camera temp JPEGs (~6 MB each) never deleted from the cache  **[either, fully terminal]**

*Original symptom.* Nothing ever removed the files in `cacheDir/camera-capture/`; every "take a
photo" round trip left about 6 MB there for the life of the install.

*What changed.* The temp JPEG is deleted once decoded and the directory is swept before each new
capture; the delete is authority-checked so a *gallery* Uri arriving through the same callback is
never touched. Note **README §17 still claims the directory is never cleaned** — that paragraph is
stale, not evidence.

- [ ] **R7.1** After two camera captures:
      `adb -s <serial> shell run-as vn.quancua.artapemeasure ls -l /data/data/vn.quancua.artapemeasure/cache/camera-capture/`
      **Pass:** empty, or at most one in-flight file.
      **Fail (original):** one ~6 MB JPEG per capture, accumulating.
- [ ] **R7.2** Register a *custom reference* using its own camera capture (a second capture path) and
      re-list. Same expectation.
- [ ] **R7.3** Pick a photo from the **gallery** and confirm nothing in the gallery was deleted (the
      authority check). Look at the gallery app afterwards.

### R8 — AR bottom bar sitting inside the gesture-nav swipe area  **[Pixel]**

*Original symptom.* `navigationBarsPadding()` alone left the capture and clear buttons close enough
to the swipe-up area that **a tap near the capture button's lower edge was read as a system gesture**
and the app went to the launcher.

*Constants to know:* `ArCameraChrome.kt` `BottomBarLift = 50.dp`, plus the hint toast offset that has
to stay in step with it (`ArCameraScreen.kt` ~line 351 comments the pairing). The refactor moved this
file into `camera/components/`, which is exactly how such a pair drifts apart.

- [ ] **R8.1** Gesture navigation enabled. In the AR Distance tool, tap the **lower edge** of the
      capture button ten times.
      **Pass:** ten point placements, zero exits to the launcher.
      **Fail (original):** at least one tap swipes the app away.
- [ ] **R8.2** Same for the Clear button.
- [ ] **R8.3** The hint toast must not land on top of the capture button. Trigger a hint (aim at a
      blank wall until the "move the phone" hint shows) and look.

### R9 — the unsupported-AR device hiding the AR card entirely  **[needs Joy_4]**

*Original symptom.* On a device ARCore cannot run on, the AR Measure card simply disappeared. The user
never learned the feature existed, nor why they could not have it.

**Not runnable today** — the Pixel has ARCore and cannot reach the `Unsupported` branch. Do not fake
it by editing code; that would test a different program.

- [ ] **R9.1** On the Joy_4: the AR card **is visible**.
      **Fail (original):** no card at all.
- [ ] **R9.2** Tapping it opens the explanation dialog, buttons stacked full width, each ≥48dp.
- [ ] **R9.3** "Use Picture Measure" starts the Picture Measure Activity; "Details" opens Google Play
      Services for AR in the Play Store.
- [ ] **R9.4** On the Pixel (i.e. the *supported* side of the same branch): tapping the card opens the
      AR Activity directly and never the dialog. This half **is** runnable today.

### R10 — the photo visibly shrinking/jumping right after detection  **[either]**

*Original symptom.* Two independent causes: the "Detect again" button appearing as a `Column` child
(photo box lost ~45 dp) and the instruction text swapping to a longer wording. The visible result was
the photo shrinking the instant the quad landed.

- [ ] **R10.1** Watch the photo box, not the quad, at the moment of detection.
      **Pass:** the photo does not move or resize at all.
      **Fail (original):** a visible shrink/jump.
- [ ] **R10.2** Repeat with the longest custom reference name you can create (the `place` wording is
      7 chars longer than `adjust`, which is the second, independent trigger).
- [ ] **R10.3** Compare SCR-21 (before detection) and SCR-22 (after) — the photo box must be
      byte-identical in size. Related: commit `0fcba80` reserved a check-button slot so the photo is
      *vertically centred* on both. Confirm the photo is centred, not top-heavy.

### R11 — ANR-class main-thread work  **[either; [needs Joy_4] for the honest numbers]**

*Original symptoms, four separate ones:*
- full-size bitmap decode on the UI thread — a 3072x4080 photo cost 50 MB ARGB_8888 and 100 MB at the
  rotation peak; an OOM risk, not just a stall;
- segmentation post-processing left on `Main` (bilinear rescale, 3 MB FloatArray copy, flood fill over
  ~790k pixels, hull fit) — which is also **why the "detecting" spinner never appeared**: the thread
  that would animate it was the one working;
- `CustomReferenceStore` reading prefs + parsing JSON + possibly writing a migration, all inside
  composition; `UnitPreference` likewise;
- `ArCoreApk.checkAvailability` from a `remember {}` block — not the cheap accessor the name suggests
  (reflection over `ActivityInfo`, a thrown-and-caught `NoSuchFieldException`, JNI) — **47 dropped
  frames on every cold entry to the tab**.

Procedure, per sub-item: `adb -s <serial> logcat -c`, do the action, then

    adb -s <serial> logcat -d | grep -oE "Skipped [0-9]+ frames|Davey! duration=[0-9]+ms"

- [ ] **R11.1** Cold-enter the Measure tab (the `ArCoreApk` path). **Pass:** no `Skipped` burst
      anywhere near 47 frames. **Fail (original):** ~47 dropped frames every time.
- [ ] **R11.2** Pick the largest photo you have. **Pass:** UI stays responsive, no OOM, no long skip.
- [ ] **R11.3** Tap the object to run detection. **Pass:** the "detecting" spinner is **visible and
      animating for the whole wait**, and the UI never blocks.
      **Fail (original):** no spinner at all — the tell that the work is on Main.
- [ ] **R11.4** Enter the photo flow with several custom references stored (the prefs/JSON path). No
      stall on entry.
- [ ] **R11.5** Detection latency from the `PhotoAutoFit` log line: Pixel well under 1 s. On the
      Joy_4 this is **unmeasured**; if it exceeds ~1.5 s the 900 px downscale needs revisiting.
      **[needs Joy_4]**

### R12 — keyboard popping back up after picking a unit in the reference sheet  **[either]**

*Not in the original list; found in commit `dc693f3`.* The Length/Width field kept Compose focus while
the unit menu's focusable `Popup` stole and returned window focus; on return the platform restarted
the field's input connection and the keyboard reappeared.

- [ ] **R12.1** Add-custom-reference sheet → focus Length → open the unit menu → pick a unit.
      **Pass:** keyboard stays hidden, single transition, no flicker.
      **Fail (original):** keyboard pops back up after the pick.
- [ ] **R12.2** Same but **dismiss** the menu without picking. Same expectation.

### R13 — default unit, and unit persistence  **[either]**

*From `61472a2` (default changed to meters) and `36c1842`.* Also: unit persistence moved from a
`LaunchedEffect(state.unit)` to write-on-change (`Intent.SetUnit`) during the refactor — a fresh
chance to lose the write.

- [ ] **R13.1** Fresh install (`pm clear` is acceptable here): the default unit is **meters**.
- [ ] **R13.2** Switch to cm on the photo screen, kill the app, relaunch: still cm, and the **AR**
      screens read cm too (one process-wide `UnitPreference`).
- [ ] **R13.3** Switch on an **AR** screen, then open the photo screen: same unit. Then `am kill` and
      confirm it survived.
      **Fail:** the write-on-change path lost an update, or the two halves disagree.

### R14 — AR camera warm-up on **every** entry, not once per process  **[Pixel]**

*From `a417ddf`.* The warm-up gate used to be a process-global one-shot. ARCore's `Session.update()`
can beat `setCameraTextureNames(...)` and throw `TextureNotSetException` on every frame thereafter,
with no recovery short of a remount — so a *second* entry to the AR tab in the same process was
unprotected. `ArWarmupGate.ArWarmupDelayMs` is currently **200 ms** and runs on every entry.
(README §11 still says 2 s and process-global — stale text, not evidence.)

- [ ] **R14.1** Enter the AR tool, leave, enter again, three times in one process.
      **Pass:** camera preview every time.
      **Fail (original):** black screen / frozen preview on the second or third entry. Confirm with
      `adb -s <serial> logcat -d | grep -c TextureNotSetException` → 0.

### R15 — system-bar insets on the AR and photo chrome  **[either]**

*From `6f95cbd` and `1a61eb8`.* Four AR_feature screens had chrome under the status bar / nav bar.
The refactor moved most of this chrome into new `components/` packages.

- [ ] **R15.1** Each of: reference picker, photo measure, line draw, AR camera — nothing sits under
      the status bar and nothing is clipped by the nav bar.

### R16 — AR mode and reference sheets are Material3 `ModalBottomSheet`  **[either]**

*From `0e2eff5`.* Both sheets used to be hand-rolled.

- [ ] **R16.1** The AR mode sheet and the reference sheet each open as a modal bottom sheet, dismiss
      on scrim tap and on back, and do not leave the scrim behind.

### R17 — auto-fit must decline rather than lie  **[either]**

*Original reasoning (`3e186b3` + §17).* A wrong quad is worse than no quad: the user trusts it,
confirms, and every later measurement is miscalibrated with nothing on screen to say so.
`isPlausibleReferenceQuad` is deliberately generous (a payment card at normal distance covers ~1.4% of
the frame), so it only rejects the obviously implausible.

Run the fallback matrix — reuse of the earlier scenario's Part B, which is still the right shape:

- [ ] **R17.1** Card on a white desk, another card 2 cm away parallel: snaps to **the one tapped**.
- [ ] **R17.2** A4 with a strong side light and a visible shadow: snaps to the **paper**, not
      paper+shadow.
- [ ] **R17.3** Black phone on a dark desk, shadow touching it: manual box, or a quad **obviously**
      wrong on screen.
- [ ] **R17.4** Object partly out of frame: manual box.
- [ ] **R17.5** Tap on carpet (no rectangle at all): manual box.
- [ ] **R17.6** A bent-open book cover (non-planar): manual box.
- [ ] For every one of R17.3–R17.6 answer one question: **would a non-technical user notice the box is
      wrong before tapping confirm?** If no, that is a bug whatever the algorithm did.
- [ ] **R17.7** Card on a device **without** Play Services would exercise the Canny+Hough-only path.
      **[needs Joy_4]** — and even there, only if it lacks the segmentation module.

---

## 2. Risks the refactor itself introduces

### X1 — the number is still right: letterbox offset vs double conversion  **[either]** — the single most important item in this document

Coordinates moved from display space to bitmap space. The failure mode is a quad that **looks
plausible and measures wrong**, and it is invisible until you read a number. The highest-risk single
line is the detector-output assignment: `segmentQuad`/`autoFitQuad` already return bitmap-space
corners, so the old bitmap→display mapping was *deleted* rather than replaced. Getting that backwards
renders somewhere plausible on a roughly-square photo.

Setup: card as reference (85.60 x 53.98 mm), ruler flat in the same frame on the same surface. Confirm
the quad on the card's real edges, then measure segments of **50, 100 and 200 mm** and write the three
readings down.

**How to read the result — this is the diagnostic, do not skip it:**

| observation | diagnosis |
|---|---|
| all three within a few mm | pass |
| **constant** error in mm across 50/100/200 (e.g. every reading +12 mm) | a **letterbox offset lost** in one direction. Its magnitude ≈ the letterbox bar size on that photo |
| **proportional** error — every reading off by the same *ratio* (e.g. all ~0.6x or ~1.7x) | a **double or missing display↔bitmap conversion**. The ratio will sit close to `fit.width / canvasWidth` or its inverse |
| error grows with distance from the reference object | **not a bug** — inherent to a single-homography calibration. Record its size |

- [ ] **X1.1** Three readings recorded, and the error classified against the table above.
- [ ] **X1.2** Repeat with the segment on the **far side of the frame** from the card. Growing error
      here is expected; a *constant* or *proportional* jump is not.
- [ ] **X1.3** The numbers must not be **worse than the pre-refactor reading**. If no pre-refactor
      number exists, build the baseline from a throwaway worktree (see X8) — after-only proves nothing.

### X2 — sign/direction check, 60 seconds, catches the failure mode early  **[either]**

- [ ] **X2.1** Pick a photo whose subject is clearly **off-centre and near one edge** — a landscape
      photo, so the letterbox bars are large. **A near-square photo hides this bug; do not use one.**
      Tap the object.
      **Pass:** the quad appears under your finger, on the object.
      **Fail:** it appears shifted toward the centre of the screen, or scaled toward the top-left —
      the tap conversion (`toBitmapSpaceIn` in `PhotoQuadCanvas`) or the detector-output assignment is
      inverted. **The offset magnitude ≈ the letterbox bar size — that is the tell.**

### X3 — corner drag tracks the finger  **[either]**

- [ ] **X3.1** Drag each of the four handles.
      **Pass:** the handle stays exactly under the finger, no drift, no acceleration.
      **Fail — constant offset:** a missing letterbox offset.
      **Fail — drift growing with distance from the photo's centre:** a scale applied in the wrong
      direction.
- [ ] **X3.2** The magnifier loupe shows the pixels actually under the handle (`DraggableHandlesOverlay`
      and `MagnifierLoupe` stayed display-space on purpose; their two callers convert).

### X4 — draft and committed numbers agree across the two screens  **[either]**

The quad editor and the line-draw screen have **different-sized photo boxes** and no longer remap
between them.

- [ ] **X4.1** On the draw screen note the live length before pressing ✓. After committing, the label
      on the previous screen must show **the same number**.
      **Fail:** a conversion crept back into `commitDrawnSegment` (JVM tests cover the arithmetic;
      this is the device echo).

### X5 — the saved PNG  **[either]**

`renderAnnotatedBitmap` draws into a canvas that *is* the photo's resolution, so `toDisplaySpace` is
the identity there and `toBitmapSpaceSegment` was deleted as a double conversion.

- [ ] **X5.1** Save an annotated photo, open the PNG at full size.
      **Pass:** every line sits on the same photo feature it did on screen; every label on its own line.
      **Fail:** annotations shifted or shrunk — `displayOf` is not the identity at photo resolution.

### X6 — relayout with no remap code left  **[either]**

There is no `onCanvasResized` any more, so a failure here means **the conversion is wrong, not the
remap**. Remember rotation does nothing (0.2) — use backgrounding and the confirm-button appearance.

- [ ] **X6.1** Quad placed, not confirmed → force a relayout (background/return; place the quad so
      the confirm button appears). The quad stays on the object.
- [ ] **X6.2** Confirm *after* the relayout, then re-measure the 100 mm from X1. **The number must
      match.** Calibration no longer depends on canvas size, so a difference means the homography is
      being solved from something screen-shaped.

### X7 — MVI drag: lag and dropped drags  **[either; [needs Joy_4] to be conclusive]**

Both halves reached the same conclusion independently but implemented it **differently**, and that
asymmetry is the risk:
- **AR**: drag is a *direct ViewModel API* (`onDragStart/onDragMove/onDragEnd/onDragCancel`) —
  `onDragStart` must be visible to the very next `onDrag`, which a `SharedFlow` hop cannot promise.
- **Photo**: corner drag goes through `Intent.MoveCorner`, i.e. one Main dispatch + one whole-`State`
  copy **per touch event**. Flagged by its own author as "watch for drag lag".

The intent channel is a zero-buffer `MutableSharedFlow` whose `emit` suspends until the collector
catches up — a dropped or stuttering drag is exactly what that looks like.

- [ ] **X7.1** Photo: drag a corner fast, in a large circle, for ~5 s without lifting.
      **Pass:** the handle keeps up, no stutter, and it ends where the finger ended.
      **Fail:** visible lag behind the finger, or the handle stops mid-drag and jumps.
- [ ] **X7.2** Photo: drag a corner and lift **immediately** (a flick). The corner must land at the
      lift point, not one event behind it.
- [ ] **X7.3** AR: drag a placed point across the screen and release. Same expectations. This is the
      path that changed most in phase 04.
- [ ] **X7.4** Multi-finger noise: start a corner drag, put a second finger down, lift it, continue.
      No jump to the second finger, no lost drag.
- [ ] **X7.5** **One drag = one undo step.** Drag a corner across the screen (many pointer events),
      then undo once. **Pass:** the corner returns to its pre-drag position in a single undo.
      **Fail:** one undo per pointer event, i.e. `dragStartSnapshot` is being re-captured per frame.

### X8 — the ARCore frame-rate measurement (carried over from `final-verification-round.md` §C)  **[needs Joy_4 to be honest; runnable on Pixel as a weaker signal]**

This is the one number the whole phase-04 design rests on, and **it has never been profiled** — the
reasoning is inferred from the call site. Both MVI exceptions (frame stream outside `State`, drag as a
direct call) are justified by it. A release build is required (0.4).

*Baseline half.* `git stash` is forbidden in this repo's workflow, so build the before-number from a
throwaway worktree at the pre-conversion commit:

    git worktree add /tmp/ar-before 3f7e5fb
    # build + sign a release APK there with the 0.4 recipe, measure, then:
    git worktree remove /tmp/ar-before

Measuring after-only proves nothing.

*Run — identical gestures before and after:*

1. `adb -s <serial> logcat -c`, launch, open the Measure tab → AR card, let the warm-up clear.
2. Aim at a **textured** surface until the reticle goes solid.
3. Place 4 points with `+`, ~3 s apart, panning slowly between them.
4. Drag one placed point across the screen and release.
5. Undo twice, redo once, Clear.
6. Switch tool to Box via the sheet and back to Distance (this is also X9 — any black frame or
   re-warm-up here is a regression regardless of the frame numbers).
7. Keep the phone moving until 30 s have elapsed, then close.

*Capture — filter tightly, never dump raw logcat:*

    adb -s <serial> logcat -d | grep -E "Choreographer: Skipped|OpenGLRenderer: Davey" \
      | grep -oE "Skipped [0-9]+ frames|Davey! duration=[0-9]+ms"

- [ ] **X8.1** Sum the skipped-frame counts and count `Davey!` events over the 30 s window, for
      before and after.
      **Regression:** total skipped frames worse by more than ~10%, or any new `Davey! duration>700ms`
      the baseline did not have.
      **If the after is worse, the split is wrong somewhere — find it, do not accept it.** First
      suspects, in order: (a) `distanceActions`/`shapeActions` read `frames.addEnabled` and the hint
      in composition, so the chrome recomposes on every live-reading change — *unchanged* from the
      pre-MVI code, so it should be a wash, but it is the largest remaining per-frame recomposition and
      the obvious fix is passing `addEnabled` as a `() -> Boolean` read inside the button;
      (b) four `collectAsStateWithLifecycle` calls collect inactive tools' states too (tap-rate, should
      be free).
- [ ] **X8.2** Release cold start, for the record: `648–686 ms` was the Joy_4's pre-refactor release
      number with zero dropped frames. Compare.

### X9 — one shared ARCore session across all four tools  **[Pixel]**

README §12 is the most important section for anyone touching the camera screen; two earlier "fixes"
here shipped and had to be reverted after making the race *worse*. So far the refactor's compliance
has been verified **only by reading code** (see C1) — a tool swap producing a black screen would only
be caught here.

- [ ] **X9.1** Switch Distance → Distance chain → Box → Cylinder and back, twice.
      **Pass:** no black screen, no camera re-acquire, no warm-up delay on any switch; tracked planes
      survive the swap.
      **Fail:** a black frame, a re-warm-up, or planes lost — the `ARSceneView` is being remounted per
      tool.
- [ ] **X9.2** Place a point in Distance, switch to Box, switch back. The scene must still be tracking
      the same planes.
- [ ] **X9.3** Background and foreground the AR screen briefly. **Pass:** the existing session keeps
      going (a resume only resets the watchdog's stall clock). **Fail:** a full camera teardown/reopen
      on every resume — that is reverted behaviour #2 returning.

### X10 — `SavedStateHandle` restoration: the platform half  **[either]**

The four persisted keys are unit-tested end to end through the same map `persist` writes, but **that
the Activity is handed its bundle back is a platform fact no JVM test reaches.** Use `am kill` (0.2).

For each: reach the state, `HOME`, `am kill`, relaunch, confirm.

- [ ] **X10.1** Reference chosen, no photo yet → comes back past the picker with the correct label.
- [ ] **X10.2** A **custom** reference (`phỏn`) → label is the custom object, never "A4 paper". (Same
      as R4.1; tick both.)
- [ ] **X10.3** Photo picker sheet open → reopens.
- [ ] **X10.4** Reference edit sheet open → reopens.
- [ ] **X10.5** Editing an existing custom reference → still editing the same one.
- [ ] **X10.6** In-progress camera capture Uri → survives (same as R5.1).
- [ ] **X10.7** **Not persisted, by design:** the photo bitmap, the quad, the segments, the homography
      and the undo history (a `Bundle` has a hard transaction size limit and *throws* rather than
      truncating). Confirm the app comes back cleanly to the picker/reference state rather than
      crashing or showing a half-restored screen. **A crash here with a
      `TransactionTooLargeException` means something started persisting the big values.**
- [ ] **X10.8** MVI base `by lazy` fix (`9c28b9c`): a `createInitialState()` reading a constructor
      argument used to silently see the type default — it compiles, does not warn, and the screen just
      starts in the wrong state. X10.1–X10.5 coming back correct **is** the test for this; if they
      restore as defaults, suspect the base's `_state` initialisation before anything else.

### X11 — `createSavedStateHandle()` in `ArCameraActivity`  **[Pixel]**

Runtime-only and inferred: `createSavedStateHandle()` resolving from `ArCameraActivity`'s creation
extras is the standard AndroidX pattern, but it **throws rather than degrades** if it ever does not.
"First thing to watch in the device round."

- [ ] **X11.1** Open each of the four AR tools once. **Pass:** no crash at first composition.
      **Fail:** an exception naming `SavedStateHandle`/creation extras on the very first frame.
- [ ] **X11.2** Select Box, `HOME`, `am kill`, relaunch, re-enter AR. **Pass:** the active tool is
      restored (the only genuine `persist` in phase 04). Anchors are expected to be gone — an
      `Anchor` is meaningless once its session is.

### X12 — anchor release now also runs from `onCleared()`  **[Pixel]**

New hazard the conversion introduces: a ViewModel outlives the composition, so without this an
Activity recreation would retain anchors from a torn-down session. `releaseAll()` also resets the
state, so a re-entered retained VM cannot report points that no longer exist.

- [ ] **X12.1** Place 3 points in Distance, background, `am kill`, relaunch, re-enter AR.
      **Pass:** point count is 0 and undo is disabled — no ghost points, no stale overlay.
      **Fail:** the chrome claims 3 points with nothing on screen, or a crash touching a released
      anchor.
- [ ] **X12.2** Place points, then leave the AR Activity by back. Re-enter. Same expectation, and no
      `logcat` complaint about anchors from a dead session.
- [ ] **X12.3** Place points in Box, switch to Cylinder, back to Box: the finished shapes belonging to
      each tool are the ones that reappear (anchors and finished shapes live in private plain lists on
      the ViewModels and are republished through the frame stream each frame).

### X13 — R8 release build + the ML Kit segmentation path  **[either; the segmentation half needs Play Services]**

README §13 records this as owed and **never done**. `assembleRelease` passes, and `mapping.txt` shows
the `SubjectSegmentation` holder class as `R8$$REMOVED$$CLASS$$` — expected, since its only member is
a static factory R8 inlines — but the model arrives through Play Services' *optional-module*
machinery, which is the part of this dependency least likely to be exercised by a compile.

- [ ] **X13.1** `./gradlew :app:assembleRelease` passes with R8 + resource shrinking (terminal).
- [ ] **X13.2** On the **release** APK: take one photo, tap the object, and confirm auto-fit still
      produces a quad, i.e. the segmentation path survives shrinking.
      **Fail:** no quad ever, or a `ClassNotFoundException`/`NoSuchMethodError` in logcat naming a
      `SubjectSegmenter*` class.
- [ ] **X13.3** First use on a fresh install may log "Waiting for the subject segmentation optional
      module to be downloaded" and fall back to Hough **once**. That is expected, not a failure — but
      it must recover on the second tap.
- [ ] **X13.4** On the release build also confirm no `armeasure_*` string went missing to
      `isShrinkResources` (any blank label on any screen).

### X14 — undo/redo across the new snapshot type  **[either]**

The photo screen no longer uses `UndoRedoStack` at all — `undoStack`/`redoStack` are `List<PhotoSnapshot>`
in `State`, bounded at 20. `PhotoSnapshot` stays deliberately *narrower* than `State`, so undo must
**not** rewind the display unit, an open sheet, or which screen you are on.

- [ ] **X14.1** Corner drag → undo → redo. Correct in both directions.
- [ ] **X14.2** Draw 3 segments, delete one, undo twice, redo once. Correct.
- [ ] **X14.3** Change the unit, then undo. **Pass:** the unit does **not** change back.
- [ ] **X14.4** Open a sheet, close it, undo. **Pass:** the sheet does not reopen.
- [ ] **X14.5** More than 20 operations: the oldest undo entries drop, nothing crashes.
- [ ] **X14.6** AR side (still uses `UndoRedoStack`): undo/redo in Distance, Box and Cylinder.

### X15 — the initial box / initial draft-line size changed  **[either]**

Not a coordinate bug — a deliberate re-interpretation. `placeDraftInitial` and the `revealQuadAt`
fallback box were fractions of the **canvas** (0.2 / 0.22 / 0.14) and are now the same fractions of
the **photo**, which is ≤ the canvas. Nobody has looked at it on a device.

- [ ] **X15.1** Manual fallback box on a **very wide** photo: is the initial box a usable size, or
      absurdly large? If it reads too large, the fractions are the knob. Report a judgement, not a
      pass/fail.
- [ ] **X15.2** Same for the initial draft line on the draw screen.

### X16 — composables moved into `components/` packages  **[either]**

`7a8dec0` moved per-screen UI into `components/`. Nothing behavioural should have changed, but paired
constants that must stay in step now live further apart (R8's `BottomBarLift` is the known example).

- [ ] **X16.1** Walk every screen once — reference picker, photo measure, line draw, colour picker,
      segment labels, AR chrome, AR bottom bar, hints, mode sheet — and confirm nothing is missing,
      duplicated or mispositioned.

---

## 3. Broad smoke

- [ ] **S1** `./gradlew :AR_feature:testDebugUnitTest` → **185 tests, 0 failures, 2 skipped**
      (`RealPhotoAutoFitTest`'s two documented `@Ignore`s). A different count means a test was added
      or lost — find out which. **[terminal]**
- [ ] **S2** Cold start, no crash: `adb -s <serial> logcat -b crash -d | grep -c FATAL` → 0.
      **[either, terminal]**
- [ ] **S3** Full photo happy path end to end: reference → photo → tap → adjust → confirm → draw 2
      segments → colour change → delete one → save → open the saved file from the gallery. **[either]**
- [ ] **S4** Custom reference CRUD: add, edit, delete, and confirm deleting the one currently chosen
      returns to the picker rather than keeping a stale object (deliberate; see C5). **[either]**
- [ ] **S5** AR happy path: one distance, one distance chain, one box, one cylinder. Numbers
      plausible; undo/redo behave; the steadiness gate suppresses a commit while the phone is moving.
      **[Pixel]**
- [ ] **S6** AR on a device ARCore cannot run on. **[needs Joy_4]**
- [ ] **S7** Low-memory device pass of the whole photo flow with a 3072x4080 photo. **[needs Joy_4]**
- [ ] **S8** Rapid back-and-forth between the photo tool and the AR tool, 5 times, no crash and no
      camera left held. **[Pixel]**

---

## 4. Only checkable by reading code — say so, do not imply a device proved it

These are greps and reads. They are **not** device evidence. Where a device check exists for the same
invariant, it is named.

- [ ] **C1** §12 structural rules. `git grep -c 'rememberEngine\|ARSceneView(' AR_feature/src/main`
      → 1 each; `grep -n "key(" ArCameraScreen.kt` → exactly one call site (`key(instanceKey)`, wrapping
      only `ARSceneView`), bumped by the watchdog alone; `tool` appears only in `when (tool)` and never
      inside a `key(...)`. **This was checked by reading before and after the conversion and it holds.
      The device proof is X9 and it has not been run.**
- [ ] **C2** No `ar` ↔ `photo` cross-import (both greps empty). Kotlin `internal` no longer enforces
      this boundary post-merge — it is convention only.
- [ ] **C3** Public API is exactly 3 symbols (README §4 grep).
- [ ] **C4** Reflection sweep: only the two documented `Plane::class.java` lookups.
- [ ] **C5** `customReferencesLoaded(...)` clearing a stale reference id back to the picker is
      **unreachable through the current UI** (delete is only offered on SCR-15, where nothing is
      chosen). It exists so `reference` can stay fallback-free. Code read only; do not claim it tested.
- [ ] **C6** `Homography` is not a `data class`, so `State` equality is reference-based on that one
      field. Harmless today (it is only ever replaced wholesale); it would matter if anything started
      diffing `State` for equality-based skipping. Code read only.
- [ ] **C7** README §16 audits 1–4 (no string literals, no Vietnamese literals except the documented
      `QuadEditorCanvas` exception, no `values-*` locale dirs, `armeasure_` prefix intact).
- [ ] **C8 — stale docs found while writing this, worth fixing but not device work:**
      - README §17 says `camera-capture/` is never cleaned — false since `aefe6af` (see R7).
      - README §11 says the warm-up is **2 s and process-global** — it is now 200 ms on every entry
        (`a417ddf`, see R14).
      - README §16 audit 7 says `git grep -n 'Log\.' AR_feature/src/main` → empty; `SegmentQuad.kt`
        has a `PhotoAutoFit` diagnostic tag. Either the audit or the tag needs a decision.
      - README §17's over-200-line file list names `MeasureState.kt`, `ShapeMeasureState.kt` and
        `PhotoMeasureState.kt`, all deleted by the refactor.
      - Commit `aefe6af` hoisted three `stringResource` reads out of callbacks because a
        `getString()` from a callback can report a **stale locale**. With English-only resources this
        is **not observable on a device at all** — code read is the only check there is.

---

## 5. What cannot be verified with today's hardware — state it, do not paper over it

1. **The unsupported-AR path** (R9.1–R9.3, S6). Needs the Joy_4. The Pixel has ARCore; there is no
   honest way to reach the `Unsupported` branch on it.
2. **The frame budget** (X8, R11.5, S7). Runnable on the Pixel, but the Pixel has headroom the Joy_4
   does not — which is the entire premise of both MVI exceptions. A Pixel pass does **not** discharge
   the phase-04 gate.
3. **The Canny+Hough-only detector path** (R17.7). Needs a device without Play Services / without the
   segmentation module.
4. **The stale-locale `stringResource` fix** (C8). No second locale exists.
5. **`customReferencesLoaded` clearing a stale id** (C5). Unreachable through the current UI.
6. **A trustworthy real-photo auto-fit fixture** (R2). The existing one's ground truth is known bad and
   a replacement needs the per-edge contrast sanity check before anyone believes it.
7. **R5.2** (camera app reports cancelled but writes the file) depends on the OEM camera behaving that
   way; the Pixel's camera may simply not.

## If something fails

Note the item id and the device tag. R1/R2/R10 and all of X1–X6 point at the bitmap-space coordinate
work; R3–R7, R12, X10, X14 at the photo ViewModel; R8, R14, X8, X9, X11, X12 at the AR conversion.
Those three pieces were built by separate sessions with disjoint file ownership, so a single failure
should not span them — if it does, suspect the shared MVI base in `common/presentation/mvi/`.
