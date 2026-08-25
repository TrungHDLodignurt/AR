# `:ar-measure-ar`

ARCore/SceneView-backed measuring: a point-to-point ruler, two shape tools (Box, Cylinder), and a
gravity-only Level tool. Requires `:ar-measure-common`. Never depends on `:ar-measure-photo` and
is never required by it — **skip this module entirely** if you only want photo-reference
measuring (see `../ar-measure-photo/README.md`); that is the whole reason the two feature modules
are split apart.

Pulls in ARCore + Filament (via SceneView) on the host's classpath. That is a real cost (see
"Known limitations" below) — take this module only if you need live-camera AR measuring.

## 1. Copy + include

Copy `ar-measure-common/` (its README) and `ar-measure-ar/` into your repo root, then in
`settings.gradle.kts`:

```kotlin
include(":ar-measure-common")
include(":ar-measure-ar")
```

`build.gradle.kts` of your `:app` (or whichever module hosts the screens):

```kotlin
dependencies {
    implementation(project(":ar-measure-common"))
    implementation(project(":ar-measure-ar"))
}
```

## 2. Version catalog

Apply `../ar-measure-common/README.md`'s base block first, then append these **additional** keys
(this module's own `build.gradle.kts`, transcribed as-is):

```toml
[versions]
arsceneview = "4.31.0"
coreKtx = "1.17.0"
lifecycle = "2.9.4"

[libraries]
sceneview-ar = { group = "io.github.sceneview", name = "arsceneview", version.ref = "arsceneview" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
```

Same duplicate-key rule as the common README (including the "differently-named alias for the same
artifact" pitfall documented in its Host compatibility notes — most likely to bite on
`androidx-material3` here): if your catalog already defines `coreKtx` (it will, in almost any
Android app) or an alias literally named `androidx-core-ktx` pointing at `androidx.core:core-ktx`,
keep the host's version and skip that line — a newer `core-ktx` is fine. `arsceneview` and
`sceneview-ar` are very unlikely to already exist in a host with no prior SceneView usage; add
them as-is. `com.google.ar:core` itself is **not** declared directly —
`io.github.sceneview:arsceneview` pulls the matching ARCore client transitively.

`io.github.sceneview:arsceneview` resolves from `mavenCentral()` (already in this repo's
`dependencyResolutionManagement` and in most hosts' default repositories) — no extra Maven
repository needs to be added for it.

## 3. Build requirements

Same as `:ar-measure-common` (`compileSdk` ≥ 36, `minSdk` ≥ 24, Java 17, Compose enabled, AGP
built-in Kotlin / no `kotlin-android` in this module's own build script) plus `resourcePrefix =
"armeasure_"` already set inside this module — nothing the host needs to configure.

## 4. Manifest / permissions

This module's manifest merges the following into the host app automatically — **the host does
not need to declare any of it**:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.ar" android:required="false" />
<application>
    <meta-data android:name="com.google.ar.core" android:value="optional" />
</application>
```

- `required="false"` on both `uses-feature` entries and `optional` on the `com.google.ar.core`
  meta-data are deliberate: the host app installs on every device, AR-capable or not, and this
  module's own `ArMeasureKit.checkAvailability` gates the feature at runtime instead. Flipping
  `android.hardware.camera.ar` to `required="true"` (or the meta-data to `"required"`) makes the
  Play Store filter the listing out of search results on non-AR-certified devices — a reach cost,
  not just an install-count cost. If a host genuinely wants that trade-off, override it in the
  host's own manifest with `tools:replace="android:required"` / `tools:replace="android:value"`
  (needs `xmlns:tools="http://schemas.android.com/tools"` on the host manifest's root element).
- **`CAMERA` is declared, never requested, by this module.** The host must request it at runtime
  (e.g. `ActivityResultContracts.RequestPermission()`) before mounting any AR screen below —
  mounting one without the grant does not crash, but ARCore's session will not produce frames.
- Resources are `armeasure_`-prefixed (`resourcePrefix` enforced at the module level) —
  nothing in this module's `res/` can collide with a host's own resource names.

## 5. AR-availability gate

Call this before showing **any** screen below. Mirrors what this repo's own `MainActivity.kt`
does:

```kotlin
// on a real device+install, this typically resolves to Ready or Checking synchronously,
// call again from onResume too — see the NeedsInstall / Checking notes below
when (ArMeasureKit.checkAvailability(context)) {
    ArAvailability.Ready -> { /* show a screen below */ }
    ArAvailability.NeedsInstall -> { ArMeasureKit.requestInstall(activity) }  // returns true if redirected
    ArAvailability.Checking -> { /* ARCore's async first call — re-poll shortly */ }
    ArAvailability.Unsupported -> { /* hide the AR entry points */ }
}
```

- `ArMeasureKit.requestInstall(activity)` can navigate away to the Play Store and back; call
  `checkAvailability` again from the hosting `Activity.onResume()`, not just once at first
  composition — the return trip from the Play Store *is* the next `onResume`.
- `ArAvailability.Checking` is ARCore's own async first-call state, not a final answer. This
  repo's `MainActivity.kt` bounds the re-poll with a `LaunchedEffect` that retries every 200 ms for
  up to ~3 s, then falls through to `Unsupported` rather than leaving the AR entry points blank
  forever:

  ```kotlin
  LaunchedEffect(arAvailability) {
      if (arAvailability != ArAvailability.Checking) return@LaunchedEffect
      var elapsedMs = 0
      while (elapsedMs < 3000) {
          delay(200)
          elapsedMs += 200
          val next = ArMeasureKit.checkAvailability(context)
          if (next != ArAvailability.Checking) { onAvailabilityChange(next); return@LaunchedEffect }
      }
      onAvailabilityChange(ArAvailability.Unsupported)
  }
  ```
- `ArAvailability.Unsupported` should hide the AR tabs/entry points, not show an error — many
  real, otherwise-capable devices are simply not on Google's ARCore-certified list, which is
  normal and expected, not a bug to chase (see "Known limitations").

## 6. Public API — screens

```kotlin
@Composable
fun ArMeasureRulerScreen(
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Metric,
    onResult: (MeasurementResult.Distance) -> Unit = {},
    onClose: (() -> Unit)? = null,
)

@Composable
fun ArMeasureBoxScreen(
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Metric,
    onResult: (MeasurementResult.Box) -> Unit = {},
    onClose: (() -> Unit)? = null,
)

@Composable
fun ArMeasureCylinderScreen(
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Metric,
    onResult: (MeasurementResult.Cylinder) -> Unit = {},
    onClose: (() -> Unit)? = null,
)

@Composable
fun LevelScreen(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null)
```

Call example, once `ArAvailability.Ready` and `CAMERA` is granted:

```kotlin
ArMeasureRulerScreen(unit = LengthUnit.Metric, onResult = { d: MeasurementResult.Distance -> }, onClose = { navBack() })
ArMeasureBoxScreen(onResult = { b: MeasurementResult.Box -> }, onClose = { navBack() })
ArMeasureCylinderScreen(onResult = { c: MeasurementResult.Cylinder -> }, onClose = { navBack() })
LevelScreen(onClose = { navBack() })
```

- `unit` is only the *initial* display unit — every screen has its own in-screen m/ft toggle that
  overrides it at runtime; the value returned in `onResult` always carries whichever unit was
  active when the result fired.
- `onResult` fires once per committed measurement (a finished segment / box / cylinder), never
  per frame.
- `onClose` is nullable and defaults to `null` — `null` renders no close affordance (this repo's
  own chrome today); pass a non-null lambda to render a "✕" pill in the top bar that invokes it.
  `LevelScreen` needs no AR-availability gate at all — it only reads the device's gravity sensor.

## 7. Cold-start warm-up (real device-specific ARCore race)

The first AR screen mounted in a process intentionally shows a "Getting the camera ready…" hint
and waits ~2 s before mounting `ARSceneView` for the first time. This is a fix for a confirmed
on-device race (ARCore's `Session.update()` racing the GPU/camera driver's own init on cold
start, throwing `TextureNotSetException` on every frame with no recovery short of a remount) —
see `report-260825-1703-session-handoff-box-cylinder-measure.md` §10 in this repo's
`plans/reports/` for the full root-cause trace. Nothing for a host to configure; it is shared
process-global state (`ArWarmupGate`, internal) so the delay is paid once per process regardless
of which of Ruler/Box/Cylinder is opened first, and never repeated for the other two.

## 8. R8 / ProGuard

Nothing required beyond what R8 already keeps automatically for manifest-declared components
(this module declares none beyond the AR meta-data, which is not a class reference). No
reflection.

## 9. Verify the integration

```bash
./gradlew :ar-measure-ar:compileDebugKotlin :ar-measure-ar:testDebugUnitTest
./gradlew assembleDebug   # whole host app, once wired into a screen/route
```

On a real device (**the ARCore emulator replays a synthetic scene — any number it gives describes
the simulation, not a sensor; do not use it to validate accuracy**):
- `CAMERA` permission prompt appears once, on first launch after wiring the runtime request.
- Opening the Ruler screen on an ARCore-supported device shows a live camera feed with a centred
  reticle; aiming at a textured surface locks a plane within a few seconds.
- Tapping commits a point; a second point draws a line with a distance pill.
- Box/Cylinder: origin tap → freehand base drag → height drag → committed shape fires `onResult`.
- Level: tilting the device slides the bubble.
- On a non-AR-certified or ARCore-unsupported device, `ArAvailability.Unsupported` is reached
  (allow up to ~3 s for the bounded re-poll) and the AR entry points should be hidden by the
  host's own UI, not crash or hang on a blank camera view.

## Known limitations

Point at the source, do not re-derive:
- **Device coverage is the real ceiling, not this code.** ARCore gates on Google's per-device
  certification list, independent of raw hardware specs — a powerful, recent device can still be
  `Unsupported` if Google never certified that exact model. See
  `report-260824-1520-arcore-hardware-limitation.md` in this repo's `plans/reports/` for the full
  breakdown; a host integrating this module should expect a non-trivial fraction of Android
  devices to land on `ArAvailability.Unsupported` and design the UI around that rather than
  treating it as an integration bug.
- **Thermal cost**: a live ARCore + Filament session is a sustained camera + GPU workload;
  expect the device to warm up and expect battery drain during any AR screen, same as any other
  live-camera AR app. Not specific to this module.
- **Deferred, not fixed** (see `report-260825-1703-session-handoff-box-cylinder-measure.md` §6):
  anchors from a half-drawn Box/Cylinder shape are not explicitly detached if the user navigates
  away mid-shape (relies on `ARSceneView` session teardown); a wall-anchored (vertical-plane)
  shape's "height" extrudes along the wall's own normal, i.e. depth, not vertical height, while
  the on-screen hint text always says "height" — geometrically correct, cosmetically misleading.
  Floor Plan / Angle / Vertical-Wall / Chain modes are not implemented at all.
