# `:ar-measure-photo`

Measures a distance from a single still photo by calibrating against a reference object of known
size (a card, an A4 sheet, or a user-registered custom object) already visible in the frame.
Requires `:ar-measure-common` only — **works with zero ARCore/SceneView anywhere in the host
app**. Never depends on `:ar-measure-ar` and is never required by it. This is the module to take
if you want measuring without pulling ARCore onto the host's classpath at all.

## When you need this module

Any host that wants measuring without an AR/camera-live-feed requirement, or a host that also
wants live AR measuring alongside it (take `:ar-measure-ar` too in that case — the two compose
independently; see its README).

## 1. Copy + include

Copy `ar-measure-common/` and `ar-measure-photo/` into your repo root, then in
`settings.gradle.kts`:

```kotlin
include(":ar-measure-common")
include(":ar-measure-photo")
```

`build.gradle.kts` of your `:app` (or whichever module hosts the screen):

```kotlin
dependencies {
    implementation(project(":ar-measure-common"))
    implementation(project(":ar-measure-photo"))
}
```

## 2. Version catalog

Apply `../ar-measure-common/README.md`'s base block first, then append these **additional** keys
(this module's own `build.gradle.kts`, transcribed as-is):

```toml
[versions]
activityCompose = "1.11.0"
coreKtx = "1.17.0"

[libraries]
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
```

Same duplicate-key rule as the common README (including the "differently-named alias for the same
artifact" pitfall documented in its Host compatibility notes — most likely to bite on
`androidx-material3` here): `activityCompose`/`coreKtx` and the
`androidx-activity-compose`/`androidx-core-ktx` aliases already exist in almost any Android app —
if the exact same alias name is already present pointing at the same artifact, keep the host's
version (a newer one is fine) and skip that line instead of creating a duplicate TOML key.

## 3. Build requirements

Same as `:ar-measure-common` (`compileSdk` ≥ 36, `minSdk` ≥ 24, Java 17, Compose enabled, AGP
built-in Kotlin / no `kotlin-android` in this module's own build script) plus `resourcePrefix =
"armeasure_"` already set inside this module — nothing the host needs to configure.

## 4. Manifest / FileProvider — no host work needed

This module declares its own `FileProvider` subclass in its manifest:

```xml
<provider
    android:name="vn.apero.armeasure.photo.data.ArMeasurePhotoFileProvider"
    android:authorities="${applicationId}.armeasure.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true" />
```

- Used only to hand the system camera app a writable `content://` Uri when the user takes a new
  photo of a custom reference object (`ACTION_IMAGE_CAPTURE`-style flow via
  `ActivityResultContracts.TakePicture()`). It exposes only its own `camera-capture/` cache
  subdirectory (declared in this module's own `res/xml/armeasure_file_paths.xml`) and is
  `exported="false"`.
- **The host does not need to add a `<provider>` entry for this.** The authority is
  `${applicationId}.armeasure.fileprovider` — namespaced under the module's own suffix
  specifically so a host app's own (very likely already-declared) `FileProvider` at a different
  authority never manifest-merger-conflicts with this one.
- The "take a photo" path for registering a custom reference object needs the `CAMERA` permission
  to actually work — if the host has not taken `:ar-measure-ar` (which is the module that declares
  `CAMERA` in its manifest) and has not declared+requested `CAMERA` itself, that one option in the
  reference-object flow will fail; picking an existing photo (see below) is unaffected either way.
- Picking an **existing** photo (the primary flow, and the reference photo itself) uses the
  system photo picker (`ActivityResultContracts.PickVisualMedia`) — **no runtime permission is
  needed for that path on any supported API level.**

## 5. `CustomReferenceStore` — host-constructed, always

The module never constructs or holds its own `CustomReferenceStore` — the host owns it and passes
it in. This is a locked decision, not an oversight:

```kotlin
// Compose, no DI framework
val store = remember { CustomReferenceStore(context) }
PhotoMeasureScreen(referenceStore = store, onResult = { p: MeasurementResult.Photo -> }, onClose = { navBack() })
```

```kotlin
// Koin — single line, this module takes zero DI-framework dependency of its own
single { CustomReferenceStore(androidContext()) }
```

`CustomReferenceStore`'s constructor is the module's entire public surface for that class — the
internal `ReferenceObject` type and the store's `loadAll`/`add` members are not accessible outside
the module; the host only ever needs to construct one and hand it to `PhotoMeasureScreen`.

Storage: an app-private `SharedPreferences` file named
`vn.apero.armeasure.photo.custom_reference_objects` (namespaced so it cannot collide with a host's
own prefs file of a more generic name). It persists only a label plus two side lengths in
millimetres per custom reference object — no photos, no measurements, no device info; nothing
here or elsewhere in this module leaves the device or touches the network.

## 6. Public API — screen

```kotlin
@Composable
fun PhotoMeasureScreen(
    referenceStore: CustomReferenceStore,
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Metric,
    onResult: (MeasurementResult.Photo) -> Unit = {},
    onClose: (() -> Unit)? = null,
)
```

- `unit` is only the *initial* display unit; the in-screen m/ft toggle overrides it at runtime.
- `onResult` fires once per completed measurement gesture (drag-end or first placement), never
  per drag frame. `MeasurementResult.Photo` carries `distanceMeters` + the `unit` active when it
  fired — `distanceMeters` is always in metres regardless of `unit`.
- `onClose` is nullable, defaulting to `null` (no close affordance, this repo's own chrome today);
  a non-null lambda renders a "✕" the host can wire to its own navigation.
- Flow order: pick or create a reference object **first**, then take/pick the one photo that
  shows both the reference object and whatever is being measured, then drag two points to read a
  distance. This order (reference before photo) is deliberate — the app cannot tell the user what
  to include in the photo without already knowing what the reference object is.

## 7. R8 / ProGuard

Nothing required. The one manifest-declared class (`ArMeasurePhotoFileProvider`) is kept
automatically by R8's manifest-component handling; no other reflection in this module.

## 8. Verify the integration

```bash
./gradlew :ar-measure-photo:compileDebugKotlin :ar-measure-photo:testDebugUnitTest
./gradlew assembleDebug   # whole host app, once wired into a screen/route
```

On a real device or emulator (no ARCore dependency, so an emulator is fine here, unlike
`:ar-measure-ar`):
- Opening the screen with no reference object registered yet prompts to pick/create one.
- Picking an existing photo via the system picker needs no permission prompt.
- After marking the reference object's two corners on the photo, the screen auto-fits a
  quadrilateral around it and becomes calibrated.
- Dragging two points elsewhere on the photo shows a distance pill that updates live and fires
  `onResult` on release.
- The "take a photo" option for a *new* custom reference object only completes if `CAMERA` is
  granted (see §4) — expected to fail gracefully (system camera app's own permission prompt/denial
  path), not crash this module.

## Known limitations

Point at the source, do not re-derive:
- **Cluttered-scene auto-fit**: the quadrilateral auto-fit (`autoFitQuad`) has one confirmed,
  still-open real-photo failure — a bright, cluttered desk scene where it returned `null` — that
  requires the user to place the four corners by hand as a fallback. Genuinely unresolved, not
  just unverified-safe: see
  `report-260825-1703-session-handoff-box-cylinder-measure.md` §16 in this repo's `plans/reports/`.
- Accuracy is bounded by lens distortion and how precisely the reference object's corners are
  marked — same caveat as any single-photo perspective measurement, not specific to a device
  tier the way `:ar-measure-ar`'s accuracy is.
