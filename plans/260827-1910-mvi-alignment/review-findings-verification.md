# Verifying the review findings left unfixed

For the four findings from `code-reviewer-260828-1009-consolidated.md` that were reported but not
fixed. Each is written so a runner can decide **is this real** before anyone changes code.

Device serials change — run `adb devices` and read them. Always pass `-s`. Never
`./gradlew installDebug`. App id `vn.quancua.artapemeasure`. The Joy_4 (`BKB00251473` when attached)
has **no ARCore**, so every AR item needs the Pixel.

    ./gradlew :app:assembleDebug
    adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

---

## V1 — Watchdog remount closes the ARCore Session under live anchors  **[Pixel]**

Consolidated item 4. Claimed: `key(instanceKey)` dispose calls `Session.close()`, but the release
`DisposableEffect` is keyed `Unit` and the ViewModels keep `points`/`session`, so after a stall
recovery the frame loop reads anchors of a dead session. Reported as **pre-existing**, not caused by
the refactor.

**Why it is unfixed:** it needs a real stall to reproduce, and the fix touches the AR session
lifecycle — the one part of this module with a documented history of expensive mistakes (README §12).
Confirm it happens before changing anything there.

1. AR Measure, let tracking settle, place **two or three points** so anchors exist.
2. Force the watchdog to remount. It fires on a detected stall, so provoke one: cover the camera
   completely for 10-15 s, or background the app and return after ~30 s. Watch logcat for the
   remount:

       adb -s <serial> logcat | grep -iE "instanceKey|remount|Session|anchor|ARCore"

3. After the preview comes back, try to add another point and to undo.

- [ ] **Pass:** points survive the remount, or are cleanly cleared; adding and undoing still work; no
      exception in logcat.
- [ ] **Fail (the finding is real):** the overlay freezes, points stop responding, or logcat shows an
      anchor/session error. Note that the library swallows exceptions from our frame callback, so the
      symptom may be a frozen overlay plus a single line — grep for `ARCore session update failed`.

If it reproduces, also try it **without** placing any points first. If it only fails with live
anchors, that confirms the diagnosis rather than a general remount bug.

---

## V2 — `commitLivePoint` never re-checks `liveStable`  **[Pixel]**

Consolidated item 6. Claimed: `commitStep` re-checks steadiness, `commitLivePoint` does not, and the
MVI intent hop now sits between the tap and the commit — so a sample that was steady at tap time can
be committed after it stopped being steady. One line to fix; not fixed because it is a behaviour
change to measurement, and a wrong "fix" here silently degrades every reading.

1. AR Measure, Distance. Aim at a textured surface until the reticle shows the steady state.
2. **Tap and immediately jerk the phone** — the tap must land while steady, the movement must start
   within a frame or two.
3. Repeat about ten times, alternating with ten controlled taps held steady.

- [ ] **Pass:** jerked taps are rejected the same way an unsteady tap is, or land where aimed.
- [ ] **Fail:** a jerked tap commits a point visibly away from where the reticle was, while the same
      gesture without the jerk lands correctly.

Compare against `commitStep` (the Box/Cylinder height step) doing the same thing — it re-checks, so it
is the control case.

---

## V3 — Two stranding paths in `ArCameraActivity`  **[Pixel]**

Consolidated item 8. Both are cheap to check and neither needs AR tracking.

**V3a, permission never re-read on resume.**
1. `adb -s <serial> shell pm revoke vn.quancua.artapemeasure android.permission.CAMERA`
2. Open AR Measure, deny at the prompt. The "camera permission needed" screen appears.
3. Without leaving the screen, grant it from outside:
   `adb -s <serial> shell pm grant vn.quancua.artapemeasure android.permission.CAMERA`
   (or through Settings, which is what a real user does)
4. Return to the app.

- [ ] **Pass:** the AR screen appears.
- [ ] **Fail:** still the denied screen, with no way forward but killing the app.

**V3b, `NeedsInstall` renders an empty black Box.** Needs a device that is ARCore-capable but has the
services **uninstalled** — the Pixel with `com.google.ar.core` removed, if that is possible on it.
Not reproducible on either device as they stand; record as unverified rather than guessing.

- [ ] Attempted / not attempted, with which device

---

## V4 — `ArCameraScreen` recomposes every frame  **[Pixel, release build]**

Consolidated item 2 of the AR review. Claimed: `distanceActions` and the hints read `frames.live` in
the screen's own restart scope, which makes `ARSceneView` non-skippable and re-runs the screen body at
60 Hz. Reported as **not a regression** — it predates the refactor.

This is the same measurement phase 04 still owes, and it needs a **release** build: debug overhead
dominates (2.7 s versus 648 ms cold start measured on the same device).

    ./gradlew :app:assembleRelease
    BT=~/Library/Android/sdk/build-tools/36.1.0
    $BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/rel.apk
    $BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
        --key-pass pass:android --ks-key-alias androiddebugkey /tmp/rel.apk
    adb -s <serial> install -r /tmp/rel.apk

1. AR Distance, 30 s of continuous tracking on a textured surface, same gestures throughout.
2. `adb -s <serial> logcat -d | grep -cE "Choreographer.*Skipped"` and note any
   `OpenGLRenderer: Davey!` durations.
3. Repeat with the same gestures on a build from `feature/photo-measure-accuracy` (pre-MVI) for a
   baseline — `git worktree add --detach <dir> feature/photo-measure-accuracy`, build and sign there.

- [ ] Skipped-frame counts, before and after
- [ ] **Real if:** the after count is materially worse. **Not the refactor if:** both are similar and
      both are high — that is the pre-existing recomposition cost, and the review's suggested fix
      (pass `addEnabled` as a lambda so the screen body stops reading `frames.live`) is what to try.

**Caveat that applies to the whole item:** the honest version of this measurement needs a low-end
**ARCore-certified** handset. The Joy_4 has no headroom but cannot run AR; the Pixel runs AR with
headroom to spare. Neither settles it.

---

## V5 — Product decisions, not defects

Not bugs. They need an opinion, not a test.

- [ ] **Back arrow discards the whole session** (quad, homography, every segment, both undo stacks)
      from an arrow inches from undo/redo, with no confirmation. Intentional, or should it return to
      the reference picker keeping the session?
- [ ] **Exported image annotation thickness.** The export renders at screen density into a
      full-resolution bitmap, so strokes come out roughly 1.5x thinner relative to the photo than
      they looked on screen. Should the saved image match what the user saw?
- [ ] **`Theme.ArMeasure` is unprefixed** despite `resourcePrefix = "armeasure_"`. A host declaring a
      style of the same name silently overrides both Activities' theme. Renaming is a one-line change
      plus two manifest references — worth doing, but it is a public-surface change for hosts that
      already integrated.

---

## Recording the outcome

Append results to `plans/reports/`, one line per item: id, device, pass/fail, and the evidence
(log line, count, or what was seen). An item that could not be attempted is **not** a pass — say so.
