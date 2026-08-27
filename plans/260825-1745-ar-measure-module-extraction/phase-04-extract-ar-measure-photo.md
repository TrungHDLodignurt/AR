# Phase 04 — Extract `:ar-measure-photo`

## Context Links

- Spec: architecture record §1 (photo module must stay ARCore-free — the load-bearing reason for
  the split), §3 (`:ar-measure-photo` layout), §4 (public API), §5 (`AutoFitQuad` relocation, the
  `LengthUnit` cross-import), §6 (`CustomReferenceStore` prefs-key prefix)
- Feature background: handoff report §9 (rotated-quad fix — the current `QuadFromEdges` behavior is
  deliberate and test-covered; do not touch the algorithm), §16 (the still-open cluttered-scene
  auto-fit failure is *not* in scope here)
- Depends on: [phase 02](phase-02-extract-ar-measure-common.md)
- Blocks: [phase 05](phase-05-slim-down-app-module.md)
- Independent of: [phase 03](phase-03-extract-ar-measure-ar.md)

## Overview

- **Priority:** P1
- **Status:** done (verified by code-reviewer against commit a94af63)
- **Effort:** 2h
- Move all 20 `photomeasure/` files into `:ar-measure-photo` under domain/data/presentation, fix
  the SharedPreferences key prefix and the FileProvider authority, take `CustomReferenceStore` as a
  parameter, and reduce everything else to `internal`.

## Key Insights

- This module's whole point is that it has **no ARCore/SceneView dependency**. Its only external
  needs are Compose, `androidx.activity` (photo picker + take-picture contracts),
  `androidx.core` (FileProvider, `SharedPreferences.edit`), plus platform `org.json` and
  `android.media.ExifInterface`. Verified by an import sweep — keep it that way.
- The pure imaging files (`CannyEdgeDetector`, `HoughTransform`, `GrayscaleImage`, `QuadFromEdges`,
  `Homography`, `ImageFit`, `ReferenceObject`) are already free of Android/Compose imports. Straight
  moves, zero logic change.
- `AutoFitQuad.kt` is the record's single purity finding: `autoFitQuad()` +
  `extractGrayscaleWindow()` take `android.graphics.Bitmap`, so they belong in `photo.data` as a
  Bitmap→`GrayscaleImage` adapter, while the `cannyEdges`/`houghLines`/`quadFromLines` they call
  live in `photo.domain.imaging`. **File relocation only — do not split or rewrite the functions.**
- `PhotoMeasureState.kt:10` imports `vn.quancua.artapemeasure.measure.LengthUnit` and
  `PhotoQuadCanvas.kt:33` imports `measure.formatLength` — both now come from
  `:ar-measure-common` (phase 02), which is what kills the photo→ar dependency.
- `PhotoMeasureScreen.kt:49` currently does `remember { CustomReferenceStore(context) }`. Locked
  decision #3: that line is **deleted** and the store arrives as a parameter. The module must never
  construct one.
- `CustomReferenceStore`'s prefs file is the generic `"custom_reference_objects"` — namespace it.
  Note this changes the storage location, so existing local test data on a dev device disappears
  (acceptable: no production users, no migration warranted).
- `CameraCapture.kt` builds a FileProvider authority as `"${context.packageName}.fileprovider"`, and
  `:app`'s manifest declares `androidx.core.content.FileProvider` under that authority. A host app
  very likely already declares its own `androidx.core.content.FileProvider` — two `<provider>`
  entries with the same `android:name` is a manifest-merger conflict. Fix: a module-owned
  `FileProvider` subclass with its own authority.
- Public surface is deliberately tiny: `PhotoMeasureScreen` + `CustomReferenceStore`'s constructor.
  `loadAll`/`add` become `internal` (only the module's own picker uses them), which keeps
  `ReferenceObject` internal too and matches the record's §4 listing exactly.

## Requirements

Functional:
- `PhotoMeasureScreen(referenceStore, modifier, unit, onResult, onClose)` public; renders and
  behaves exactly as today when given a host-constructed store.
- `CustomReferenceStore(context)` public constructor; persisted under
  `"vn.apero.armeasure.photo.custom_reference_objects"`.
- Take-a-photo flow works through the module's own FileProvider authority.
- `onResult` emits `MeasurementResult.Photo(distanceMeters, unit)` once per completed measurement
  gesture (not per drag frame).
- 7 moved JVM tests pass (`HomographyTest` 3, `QuadFromEdgesTest` 4).

Non-functional:
- Zero ARCore/SceneView on the module's compile **and** runtime classpath.
- `photo.domain.imaging` keeps zero `android.*`/Compose imports.
- No new permission declared by this module.

## Architecture

```
ar-measure-photo/src/main/java/vn/apero/armeasure/photo/
  domain/imaging/  CannyEdgeDetector.kt HoughTransform.kt GrayscaleImage.kt QuadFromEdges.kt
                   Homography.kt ImageFit.kt ReferenceObject.kt
  data/            CustomReferenceStore.kt ExifBitmapLoader.kt CameraCapture.kt AutoFitQuad.kt
                   ArMeasurePhotoFileProvider.kt (NEW)
  presentation/    PhotoMeasureScreen.kt PhotoMeasureState.kt ReferencePickerScreen.kt
                   NameReferenceDialog.kt PickPhotoSheet.kt QuadEditorCanvas.kt
                   DraggableHandlesOverlay.kt MagnifierLoupe.kt PhotoQuadCanvas.kt
ar-measure-photo/src/main/res/xml/armeasure_file_paths.xml   (moved from app res/xml/file_paths.xml)
ar-measure-photo/src/main/AndroidManifest.xml                 provider entry
ar-measure-photo/src/test/java/vn/apero/armeasure/photo/domain/imaging/
                   HomographyTest.kt QuadFromEdgesTest.kt
```

FileProvider:

```kotlin
// data/ArMeasurePhotoFileProvider.kt
internal class ArMeasurePhotoFileProvider : FileProvider(R.xml.armeasure_file_paths)
```

```xml
<provider
    android:name="vn.apero.armeasure.photo.data.ArMeasurePhotoFileProvider"
    android:authorities="${applicationId}.armeasure.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true" />
```

Kotlin `internal` compiles to a public JVM class, so the platform can still instantiate it by name
while Kotlin consumers cannot reference it. The `<meta-data>`/`file_paths` resource pointer is
replaced by the resource-id constructor (`androidx.core` ≥ 1.7), which keeps the paths XML tied to
the class instead of the merged manifest.

## Related Code Files

Move (`git mv` + package/import edits) — all 20 from
`app/src/main/java/vn/quancua/artapemeasure/photomeasure/` into the packages shown above; plus:
- `app/src/main/res/xml/file_paths.xml` → `ar-measure-photo/src/main/res/xml/armeasure_file_paths.xml`
- `app/src/test/.../photomeasure/HomographyTest.kt` → `ar-measure-photo/src/test/.../photo/domain/imaging/HomographyTest.kt`
- `app/src/test/.../photomeasure/QuadFromEdgesTest.kt` → same package

Create:
- `ar-measure-photo/src/main/java/vn/apero/armeasure/photo/data/ArMeasurePhotoFileProvider.kt`
- provider entry in `ar-measure-photo/src/main/AndroidManifest.xml`

Modify:
- `app/src/main/AndroidManifest.xml` — delete the `<provider>` block and its comment (the module
  owns it now).

## Implementation Steps

1. `git mv` the 20 sources into `domain/imaging` / `data` / `presentation` exactly per the
   Architecture block; update every `package` line and the two `:common` imports
   (`LengthUnit`, `formatLength`, `drawLabelPill`).
2. `git mv` the two test files; update packages to
   `vn.apero.armeasure.photo.domain.imaging`.
3. `CustomReferenceStore.kt`: change `PrefsName` to
   `"vn.apero.armeasure.photo.custom_reference_objects"`, adding a one-line comment saying the
   prefix exists to avoid colliding with a host app's own prefs file. Mark `loadAll`/`add`
   `internal`; keep the class and its constructor `public`. Keep the existing KDoc.
4. Create `ArMeasurePhotoFileProvider.kt`; move `file_paths.xml` → `armeasure_file_paths.xml`
   (contents unchanged); add the `<provider>` entry to the module manifest; delete the provider
   block from `:app`'s manifest.
5. `CameraCapture.kt`: change the authority string to
   `"${context.packageName}.armeasure.fileprovider"` so it matches the merged
   `${applicationId}.armeasure.fileprovider`. Keep the KDoc explaining why a `content://` Uri is
   required.
6. `PhotoMeasureScreen.kt`: delete the `remember { CustomReferenceStore(context) }` line; new
   signature
   `fun PhotoMeasureScreen(referenceStore: CustomReferenceStore, modifier: Modifier = Modifier, unit: LengthUnit = LengthUnit.Metric, onResult: (MeasurementResult.Photo) -> Unit = {}, onClose: (() -> Unit)? = null)`.
   Thread `referenceStore` to wherever `store` was used (the reference picker path).
7. `unit` plumbing: `PhotoMeasureState(initialUnit: LengthUnit = LengthUnit.Metric)`,
   `var unit by mutableStateOf(initialUnit)`; pass the parameter at the `remember { … }` site.
   `toggleUnit()` untouched.
8. `onResult` plumbing: add `onPointDragEnd: (Int) -> Unit = {}` to `DraggableHandlesOverlay`
   (it already has an internal `onDragEnd` at line 47 — hook it there, no new gesture code), and in
   `PhotoMeasureScreen` emit `MeasurementResult.Photo(currentDistanceMm / 1000f, state.unit)` on
   that callback and once when the line is first placed. Skip emission while
   `currentDistanceMm == null`. Do **not** emit per drag frame.
9. `onClose`: if non-null, render a `✕` in the existing top chrome of `PhotoMeasureScreen`;
   otherwise no visual change.
10. Visibility sweep: `internal` for every top-level declaration except `PhotoMeasureScreen` and
    `CustomReferenceStore`. `AutoFitQuad`, `ReferenceObject`, `builtInReferenceObjects`,
    `Homography`, `PhotoMeasureState`, every canvas/overlay/dialog composable → `internal`.
11. **Verification gate (blocking):**
    `./gradlew :ar-measure-photo:compileDebugKotlin :ar-measure-photo:testDebugUnitTest` — compile
    green, **7 tests pass**. `:app` stays red until phase 05.
12. Grep gates: `grep -rn "com.google.ar\|sceneview" ar-measure-photo/src` → empty;
    `./gradlew :ar-measure-photo:dependencies --configuration debugRuntimeClasspath | grep -i "ar-core\|sceneview"` → empty;
    `grep -rn "android\.\|androidx.compose" ar-measure-photo/src/main/java/vn/apero/armeasure/photo/domain` → empty.
13. Commit: `refactor: move photo-reference measuring into :ar-measure-photo`.

## Todo List

- [x] 20 sources + 2 tests + `file_paths.xml` moved, packages/imports updated
- [x] `AutoFitQuad.kt` landed in `data/` unchanged (adapter stays whole)
- [x] Prefs name namespaced
- [x] `ArMeasurePhotoFileProvider` + module manifest provider; `:app` provider removed
- [x] `CameraCapture` authority updated to match
- [x] `PhotoMeasureScreen` takes `referenceStore`; internal construction deleted
- [x] `initialUnit` on `PhotoMeasureState`
- [x] `onResult` on drag-end / first placement only
- [x] Optional `✕` close affordance
- [x] `internal` sweep; only 2 public declarations remain
- [x] Verification gate green (module compile + 7 tests)
- [x] Commit

## Success Criteria

- `:ar-measure-photo:testDebugUnitTest` → 7 tests, 0 failures (including the 35°-rotated-rectangle
  regression test from handoff §9 — proof the algorithm survived the move).
- Exactly 2 public top-level declarations: `PhotoMeasureScreen`, `CustomReferenceStore`.
- Runtime classpath free of ARCore/SceneView.
- No `vn.quancua` reference remains in the module.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Manifest-merger conflict on `androidx.core.content.FileProvider` in a host app | Module-owned subclass + `${applicationId}.armeasure.fileprovider` authority |
| Authority string and manifest drift apart → `IllegalArgumentException` at capture time | Steps 4 and 5 are adjacent and both listed in the todo; on-device smoke test in phase 05 covers the take-photo path |
| Losing saved custom reference objects on a dev device | Expected side effect of the prefs rename; no production users, no migration written (YAGNI) |
| Touching the Canny/Hough algorithm during the move | `git mv` + review `git diff -M`; the 7 tests (incl. rotated-rect) are the gate |
| ARCore creeping in via a transitive dependency later | Step 12's `dependencies` grep is cheap — repeat it in phase 06 |
| `internal` on a `FileProvider` subclass breaking instantiation | Kotlin `internal` classes are `public` in bytecode; verified by the on-device take-photo smoke test in phase 05 |

## Security Considerations

- `android:exported="false"` + `grantUriPermissions="true"` preserved; `armeasure_file_paths.xml`
  still exposes only the `camera-capture/` cache subdirectory, nothing else in app storage.
- No permission declared by this module. **Caveat for the README:** if the host app declares
  `CAMERA` (it will, if `:ar-measure-ar` is also included), Android requires that permission to be
  *granted* before `ACTION_IMAGE_CAPTURE` will run — so the take-photo option depends on the host's
  runtime grant. The pick-from-gallery path uses the photo picker and needs nothing.
- `CustomReferenceStore` writes only a name and two lengths, in the host app's private prefs. No
  photo, no image data, nothing sensitive persisted.
- Bitmaps stay in memory / app-private cache; no external-storage or network path exists.

## Next Steps

- Phase 05 wires `:app` to both feature modules (needs phase 03 done too).
</content>
