# Phase 05 — Slim down `:app` to nav + host wiring

## Context Links

- Spec: architecture record §4 (`AppTab`/`AppTabBar` dropped from the module surface — they are
  this repo's own nav chrome), §7 (host calls `ArMeasureKit`, not `ArCoreApk`)
- Feature background: handoff report §4 (tab list is Measure/Photo/Box/Cylinder/Level)
- Depends on: [phase 03](phase-03-extract-ar-measure-ar.md) **and** [phase 04](phase-04-extract-ar-measure-photo.md)
- Blocks: [phase 06](phase-06-final-verification-and-review.md)

## Overview

- **Priority:** P1 (this is the phase that makes the repo compile again end to end)
- **Status:** done except the on-device smoke test (verified by code-reviewer against commit b1dd385)
- **Effort:** 1.5h
- `:app` ends up as `MainActivity.kt` + `ui/AppTabBar.kt` + resources, consuming the three modules
  through their public API only. First on-device verification of the whole extraction happens here.

## Key Insights

- `:app` has been knowingly non-compiling since phase 02/03 moved its sources out. This phase is
  where the whole-project gate returns; treat any leftover unresolved reference as a missed move,
  not as something to re-implement in `:app`.
- `MainActivity.kt` today carries ~15 lines of `ArCoreApk` state machine (`onResume`,
  `userRequestedInstall`, its own private `ArAvailability` enum). All of that is now
  `ArMeasureKit`'s job — delete it, do not keep a parallel copy.
- `ArMeasureKit.checkAvailability` can return `Checking` on its first call (ARCore's own async
  behavior). `MainActivity` previously never saw that state because it read availability off
  `requestInstall`'s result. Add a bounded re-poll so the AR tabs cannot sit blank forever if no
  further `onResume` arrives.
- `AppTab` + `AppTabBar` were stashed out of `MeasureControls.kt` in phase 03 — restore them into
  `:app` as `ui/AppTabBar.kt`, together with the 3 colour literals they need
  (`Color(0xF21C1C1E)`, `Color.White`, `Color(0x4DFFFFFF)`). Do not export chrome colours from a
  module to avoid this.
- `:app` no longer needs `libs.sceneview.ar`: after the move, no ARCore/SceneView type appears in
  any `:app` source (`ArAvailability` is the module's own enum). Removing it from `:app`'s
  dependencies is the practical proof that `implementation(libs.sceneview.ar)` in
  `:ar-measure-ar` was scoped correctly.
- `MainActivity` keeps owning the camera runtime permission request and the `ArUnsupported` /
  `CameraDenied` / `CenteredMessage` screens plus their `ar_unsupported_*` strings — that is host
  policy, deliberately not module API.

## Requirements

Functional:
- All 5 tabs work exactly as before the extraction: Measure, Photo, Box, Cylinder, Level.
- The Photo tab receives a `:app`-constructed `CustomReferenceStore`.
- Non-AR devices still get the unsupported screen on the 3 AR tabs and a working Photo/Level tab.
- Camera-permission-denied path unchanged.

Non-functional:
- `./gradlew compileDebugKotlin testDebugUnitTest` green project-wide; 67 tests total across the
  three modules.
- `:app` contains no `com.google.ar` / `io.github.sceneview` import.
- `:app/src/main/java` contains exactly 2 Kotlin files.

## Architecture

```
app/src/main/java/vn/quancua/artapemeasure/
  MainActivity.kt        camera permission, ArMeasureKit availability, tab host
  ui/AppTabBar.kt        enum AppTab + AppTabBar (restored from MeasureControls.kt)
app/src/main/res/values/ strings.xml (app_name + ar_unsupported_*), themes.xml
app/src/main/AndroidManifest.xml   activity + theme only (AR features/permission now merge in
                                   from :ar-measure-ar, FileProvider from :ar-measure-photo)
```

Tab → composable mapping:

| Tab | Call |
|---|---|
| Measure | `ArMeasureRulerScreen()` (AR-gated) |
| Photo | `PhotoMeasureScreen(referenceStore = store)` (never AR-gated — works on any device) |
| Box | `ArMeasureBoxScreen()` (AR-gated) |
| Cylinder | `ArMeasureCylinderScreen()` (AR-gated) |
| Level | `LevelScreen()` |

`:app` passes no `onClose` (tab-root screens — nothing to close to) and no `onResult` yet; the
existing on-screen labels remain the UI of record. `unit` is left at its default, matching today's
behavior where each screen starts metric with its own toggle.

## Related Code Files

Create:
- `app/src/main/java/vn/quancua/artapemeasure/ui/AppTabBar.kt` (restore `AppTab` + `AppTabBar`)

Modify:
- `app/src/main/java/vn/quancua/artapemeasure/MainActivity.kt`
- `app/build.gradle.kts` — add the 3 module deps, remove `libs.sceneview.ar`, keep the Compose deps
  it still uses directly
- `app/src/main/AndroidManifest.xml` — remove the AR `uses-feature`/`meta-data`/`CAMERA` entries now
  supplied by `:ar-measure-ar` (keep the `uses-permission` only if the merger does not surface it;
  verify with the merged-manifest report rather than guessing)
- `app/src/main/res/values/strings.xml` — final state: `app_name`, `ar_unsupported_title`,
  `ar_unsupported_body`

Delete:
- Any now-empty `app/src/main/java/vn/quancua/artapemeasure/{measure,level,photomeasure}/`
  directories and `app/src/main/java/vn/quancua/artapemeasure/ui/MeasureControls.kt` remnants
- `app/src/test/java/vn/quancua/artapemeasure/` (all 5 test files moved out; the directory tree
  should be gone)

## Implementation Steps

1. `app/build.gradle.kts`: add
   `implementation(project(":ar-measure-common"))`, `implementation(project(":ar-measure-ar"))`,
   `implementation(project(":ar-measure-photo"))`; delete `implementation(libs.sceneview.ar)` and
   `testImplementation(libs.junit)` (no `:app` tests remain).
2. Create `ui/AppTabBar.kt` with the stashed `AppTab` enum (Measure/Photo/Box/Cylinder/Level, same
   labels and glyphs) and `AppTabBar`, plus its own colour constants. Keep the existing comment
   about drawing the bar behind the nav bar while padding the labels.
3. Rewrite `MainActivity.kt`:
   - delete the private `ArAvailability` enum, `userRequestedInstall`, and the `ArCoreApk`
     `onResume` block; import `vn.apero.armeasure.ar.ArAvailability` /
     `vn.apero.armeasure.ar.ArMeasureKit`
   - `onResume`: `if (!ArMeasureKit.requestInstall(this)) arAvailability = ArMeasureKit.checkAvailability(this)`
   - in `AppRoot`, add a bounded re-poll: while `arAvailability == Checking`, a `LaunchedEffect`
     re-calls `checkAvailability` every 200 ms for at most ~3 s, then falls through to
     `Unsupported`. Comment why (ARCore's async first call).
   - treat `NeedsInstall` the same as today's redirect path: show the blank/`Checking` placeholder;
     `requestInstall` on the next resume drives it forward.
   - keep `ArUnsupported`, `CameraDenied`, `CenteredMessage` and the camera permission launcher
     as they are
   - hoist `val store = remember { CustomReferenceStore(context) }` into `AppRoot` and pass it to
     `PhotoMeasureScreen` (locked decision #3: the host owns the instance)
   - map the 5 tabs per the table above, keeping the existing AR gating `when` blocks and the
     comment explaining why the Photo tab is not gated
4. Prune `app/src/main/res/values/strings.xml` to the 3 remaining strings.
5. Delete the emptied source/test directories; confirm `find app/src -name "*.kt"` lists exactly
   `MainActivity.kt` and `ui/AppTabBar.kt`.
6. Reconcile the manifest: build once, then read
   `app/build/outputs/logs/manifest-merger-debug-report.txt` (or the merged manifest) and confirm
   `CAMERA`, both `uses-feature` entries, the `com.google.ar.core` meta-data and the photo
   FileProvider all arrive from the library modules. Remove only the duplicates from `:app`'s
   manifest — never both copies.
7. **Verification gate (blocking):** `./gradlew clean compileDebugKotlin testDebugUnitTest` →
   green, **67 tests** (6 + 54 + 7). Then `./gradlew installDebug` on a physical device.
8. On-device smoke test (physical device required — no emulator path for ARCore):
   - cold-kill, launch straight into **Box** → no black screen, hint shows "Getting the camera
     ready…" then the reticle appears (handoff §12's still-unverified case — this is the cheapest
     opportunity to finally close it)
   - Measure: place 2 points, see a distance label; undo; clear
   - Box and Cylinder: complete one 3-tap shape each
   - Photo: pick a photo from the gallery, auto-fit the reference quad, drag the line, read a number
   - Photo: **take a photo** with the camera option (exercises the new FileProvider authority)
   - Photo: register a custom reference object, then reopen the picker (exercises the renamed prefs)
   - Level: tilt the phone
   - background/foreground round trip on Measure → camera comes back
9. Commit: `refactor: slim :app down to nav and wire the ar-measure modules`.

## Todo List

- [x] `:app` deps: 3 modules in, sceneview + junit out
- [x] `ui/AppTabBar.kt` restored with its own colours
- [x] `MainActivity` on `ArMeasureKit`; own ARCore state machine deleted
- [x] `Checking` re-poll added and commented
- [x] `CustomReferenceStore` constructed in `:app`, passed to `PhotoMeasureScreen`
- [x] strings.xml pruned to 3; manifest duplicates reconciled from the merger report
- [x] Dead directories and `app/src/test` removed; exactly 2 Kotlin files in `:app`
- [x] Verification gate: clean project-wide compile + 67 tests
- [ ] On-device smoke test, all 8 items incl. cold-launch-into-Box and take-photo — **NOT RUN, no
      device/emulator attached this session**
- [x] Commit

## Success Criteria

- Project-wide `compileDebugKotlin` + `testDebugUnitTest` green from a `clean`, 67 tests.
- `grep -rn "com.google.ar\|sceneview" app/src` → empty.
- Every smoke-test item behaves as it did before the extraction; no new hint text, no new button.
- Merged manifest contains exactly one `CAMERA` permission, one of each `uses-feature`, one AR
  meta-data, one FileProvider.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| An unresolved reference tempts a re-implementation inside `:app` | Rule: unresolved reference = a file that failed to move in 03/04. Fix it there, then re-run this gate |
| Manifest-merger duplicate/conflict on AR entries or the provider | Step 6 reads the actual merger report rather than reasoning about it |
| `Checking` never resolving → permanently blank AR tab | Bounded re-poll in step 3, falling through to `Unsupported` |
| Regressing the cold-start black-screen fix (device-dependent, invisible in JVM tests) | Smoke-test item 1 is exactly that repro, from the Box tab (handoff §12) |
| FileProvider authority mismatch only visible at capture time | Smoke-test item "take a photo" |
| Renamed prefs key silently breaking the custom-reference flow | Smoke-test item "register a custom reference object" |
| Only one device tested (the race is device-specific per handoff §10.1) | Run the cold-launch item on both available devices (Pixel 6 and POCO X7) if both are to hand |

## Security Considerations

- `:app` remains the only component that requests the camera permission at runtime — unchanged
  behavior, and the pattern phase 07's README tells host apps to follow.
- After the merge, verify `android:exported` values in the merged manifest: only `MainActivity`
  should be exported; the module provider must stay `exported="false"`.
- No new logging of measurements or file paths introduced.

## Next Steps

- Phase 06: leftover sweep + `code-reviewer`.
- Phase 07: per-module integration READMEs.
</content>
