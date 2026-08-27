# Final verification round — the only device gate

Context: [plan.md](plan.md). Run once, after phases 01-05 have landed.

Per-phase manual runs were dropped by the user's decision. Everything a human has to do is here.
Organised **by phase**, so a failure still names its likely suspect — that is the only compensation
for not gating each phase separately.

## Before starting

    adb devices                                  # read the serials, do not trust yesterday's
    ./gradlew :AR_feature:testDebugUnitTest      # 172 tests, 0 failures, 2 skipped

The two skips are `RealPhotoAutoFitTest` — pre-existing, its fixture's ground truth is known bad.

Devices: `BKB00251473` is the Joy_4 (low-end, **no ARCore** — the only device that can exercise the
unsupported-AR path). The other is a Pixel (serial changes; read it). Always pass `-s`. Never
`./gradlew installDebug`.

    ./gradlew :app:assembleDebug
    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

---

## A. Phase 02 — coordinates in bitmap space

The failure mode this refactor risks is a quad that **looks** plausible and **measures** wrong. Item
A3 is the one that actually catches it; the rest are cheaper checks that fail sooner.

- [ ] **A1 — quad follows the photo.** Pick a photo, tap the object, then cause a relayout (rotate,
      or background and return). The quad must stay on the object. `onCanvasResized` no longer exists,
      so if it drifts, the conversion is wrong.
- [ ] **A2 — corners drag where the finger goes.** Drag each of the four handles. Any offset between
      finger and handle, or handles that move the wrong amount, is a display/bitmap mix-up at the
      gesture edge.
- [ ] **A3 — the number is still right.** Card as reference (85.60 x 53.98 mm), ruler flat in the same
      frame, same surface. Measure 50 / 100 / 200 mm and record the readings. Compare with the
      pre-refactor behaviour: this phase must not change the numbers at all. A consistent scale error
      means the homography is being solved in the wrong space.
- [ ] **A4 — saved image.** Save an annotated photo and confirm the lines and labels sit where they
      did on screen. The export path used to convert display-to-bitmap itself; a double conversion
      shows up here as annotations in the wrong place.

## B. Phase 03 — Picture Measure ViewModel

Recreation-with-restore is reproduced with `am kill`, **not** `always_finish_activities` — that
setting destroys the Activity instead of saving it, which cost a wasted attempt today:

    HOME  ->  adb -s <serial> shell am kill vn.quancua.artapemeasure  ->  relaunch

The six cases that broke before. For each: reach the state, kill, relaunch, confirm.

- [ ] **B1** Reference chosen, no photo yet -> comes back past the picker, correct label
- [ ] **B2** A **custom** reference (e.g. "phỏn" 150x70) -> label is the custom object, **not "A4
      paper"**. This one regressed once already when the custom list moved to an async load
- [ ] **B3** Photo picker sheet open -> reopens
- [ ] **B4** Reference edit sheet open -> reopens
- [ ] **B5** Editing an existing custom reference -> still editing the same one
- [ ] **B6** Camera capture: take a photo, and the app proceeds **with that photo** rather than
      showing the picker again. Then confirm the temp file was cleaned up:
      `adb -s <serial> shell run-as vn.quancua.artapemeasure ls /data/data/vn.quancua.artapemeasure/cache/camera-capture/`
- [ ] **B7** Camera permission denied -> the permission dialog appears and the app does not crash.
      Reset with `adb -s <serial> shell pm revoke vn.quancua.artapemeasure android.permission.CAMERA`

## C. Phase 04 — AR screens, and the frame measurement

This is the one number the whole phase-04 design rests on, and it has never been profiled — the
reasoning is inferred from the call site. **A release build is required**: debug overhead dominates
and would hide the effect. Measured today on the Joy_4: debug cold start 2.7 s versus release 648 ms.

    ./gradlew :app:assembleRelease
    BT=~/Library/Android/sdk/build-tools/36.1.0
    $BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/rel.apk
    $BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
        --key-pass pass:android --ks-key-alias androiddebugkey /tmp/rel.apk
    adb -s <serial> install -r /tmp/rel.apk

- [ ] **C1 — frame drops.** On a Pixel (the Joy_4 has no ARCore): AR Distance tool, 30 s of continuous
      tracking on a textured surface, same gestures as the baseline. Count with
      `adb -s <serial> logcat -d | grep -cE "Choreographer.*Skipped"` and note any
      `OpenGLRenderer: Davey!` durations. **Worse than baseline means the State/frame-stream split is
      wrong somewhere — find it, do not accept it.**
- [ ] **C2 — one shared session.** Switch Distance -> DistanceChain -> Box -> Cylinder and back. No
      black screen, no camera re-acquire on any switch. This is README section 12; breaking it is the
      most likely way the conversion goes wrong.
- [ ] **C3 — measurements still work.** One distance, one box, one cylinder. Numbers plausible, undo
      and redo behave.
- [ ] **C4 — no-AR device.** On the Joy_4: the AR card is visible, tapping it opens the dialog, and
      both buttons work (Play Store, and Picture Measure).

## D. Whole app

- [ ] **D1** Cold start both devices, no crash. `adb -s <serial> logcat -b crash -d | grep -c "FATAL"`
      must be 0
- [ ] **D2** Unit switch (cm/m/inch/ft) persists across a restart, and reads the same on the AR and
      photo screens
- [ ] **D3** `./gradlew :app:assembleRelease` passes and R8 does not strip the ML Kit segmentation
      path — take one photo on the release build and confirm auto-fit still produces a quad. README
      section 13 records this as owed and it has never been done

## If something fails

Note the item id. A and B failures point at the photo path (phases 02, 03); C at the AR path (04).
Those phases were done by separate sessions with disjoint file ownership, so a failure should not
span them.
