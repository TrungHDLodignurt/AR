# `AR_feature` — integration guide

One Gradle module, `vn.apero.armeasure.*`, offering four measuring tools behind one entry
composable: an ARCore point-to-point ruler (**Distance**), two ARCore shape tools (**Box**,
**Cylinder**, sharing one session with Distance), and a photo-reference tool (**Picture
Measure**) that needs no ARCore at all. There is no photo-only build: the module always ships
the ARCore/SceneView dependency, even for a host that only wants Picture Measure — an earlier
3-way module split let a photo-only host skip ARCore entirely; that option is gone now that
everything lives behind one `include`, and this is the honest headline replacing it.

This file replaces the three per-module READMEs a prior 3-module split shipped (564 lines total,
all now deleted from this repo) — most of that length existed to explain the split itself ("take
this one, skip that one"); collapsing to one module collapses that story too.

Two decisions were **reversed** since that split and are recorded here so nobody "fixes" the code
back toward the old docs:
1. `CustomReferenceStore` is now constructed **inside** the module (`ArPhotoActivity`), not by the
   host — there is no host code to construct it once the module owns and launches its own
   Activity.
2. The module ships its **own theme** (`ArMeasureTheme`) instead of consuming the host's, so the
   full-screen AR camera stays dark and the hub/photo screens keep their design background
   regardless of the host's app theme.

## 1. Copy + include

Copy the `AR_feature/` folder into the host repo root, then in `settings.gradle.kts`:

```kotlin
include(":AR_feature")
```

And in the host module that will render the hub (e.g. `:app`):

```kotlin
dependencies {
    implementation(project(":AR_feature"))
}
```

**Naming deviation, recorded not silently fixed:** the folder is `AR_feature/`, not
kebab-case like the rest of this repo (`ar-feature` would match the convention) — the user who
requested the merge named it explicitly. Do not rename it; the `namespace`
(`vn.apero.armeasure`) and `resourcePrefix` (`armeasure_`) are independent of the folder/module
name, so nothing generated is affected either way.

## 2. Version catalog

Transcribed from this repo's own `gradle/libs.versions.toml` and `AR_feature/build.gradle.kts` —
append to the host's `gradle/libs.versions.toml`:

```toml
[versions]
# Highest AGP that Android Studio AI-253 (2025.3) accepts. Newer AGP fails IDE sync
# even though the CLI build succeeds. Raise this only alongside an IDE upgrade.
agp = "9.2.0-rc01"
kotlin = "2.4.10"
# 2026.08.00 pulls compose-runtime 1.12.x, which demands compileSdk 37.
# Pinned to the last BOM that compiles against 36.
composeBom = "2026.05.01"
coreKtx = "1.17.0"
lifecycle = "2.9.4"
activityCompose = "1.11.0"
arsceneview = "4.31.0"
junit = "4.13.2"
json = "20240303"
# Unbundled: the model arrives through Play Services, so this adds no APK weight. Beta upstream —
# Google warns of backward-incompatible change, which is why the Canny+Hough detector is kept as a
# fallback rather than deleted.
mlkitSubjectSegmentation = "16.0.0-beta1"
# Test-only.
coroutinesTest = "1.7.3"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
sceneview-ar = { group = "io.github.sceneview", name = "arsceneview", version.ref = "arsceneview" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
json = { group = "org.json", name = "json", version.ref = "json" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-savedstate = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-savedstate", version.ref = "lifecycle" }
mlkit-subject-segmentation = { group = "com.google.android.gms", name = "play-services-mlkit-subject-segmentation", version.ref = "mlkitSubjectSegmentation" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`org.json:json` is a **`testImplementation`**-only dependency — added specifically because
Android's own stub `org.json` (present on `android.jar`) throws `"not mocked"` at test time, so
the module's `ReferenceObjectJsonTest` pulls in the real JVM implementation instead of needing
Robolectric. Easy to drop by accident when hand-copying: without it, `AR_feature`'s own
`testDebugUnitTest` fails, not the host's.

**If a key with the same name already exists in the host's catalog** (very likely for `agp`,
`kotlin`, `composeBom`, `coreKtx`, `lifecycle`, `activityCompose`, `junit`, and the
`androidx-*`/`sceneview-ar` aliases above, if the host already ships Compose):
- **Same name, compatible value.** Keep the host's existing entry and skip that line — a newer
  version at or above what this module needs is fine. Do not add a duplicate TOML key (TOML
  rejects duplicates in the same table).
- **Different alias name, same artifact.** `AR_feature/build.gradle.kts` calls aliases by their
  literal dotted name (e.g. `libs.androidx.ui`), so a semantically-equivalent-but-differently-named
  existing alias (e.g. a host's own `androidx-compose-ui` pointing at the same
  `androidx.compose.ui:ui` artifact) does **not** satisfy the reference — Gradle looks up the
  literal name. The module's checked-in `build.gradle.kts` is not meant to be edited, so add the
  new alias name from the block above (pointing at the artifact the host already has under its
  other name) rather than renaming or deduping against the host's existing one.

**Kotlin-version risk**: if the host is pinned to a Kotlin version **older** than `2.4.10`, the
module compiles with that older host compiler (there is no per-module Kotlin version under AGP's
built-in Kotlin support) — verify the host's actual Kotlin toolchain against this module's source
before assuming compatibility, do not just take the version number on faith.

## 3. Build requirements

- `compileSdk` ≥ 36, `minSdk` ≥ 24 (raises the host's floor if it was lower), Java 17
  (`sourceCompatibility`/`targetCompatibility`), Compose enabled.
- **AGP 9 ships built-in Kotlin support. Applying the `kotlin-android` plugin anywhere in this
  repo is a build error** — `AR_feature/build.gradle.kts` applies only `android-library` +
  `kotlin-compose`, never `kotlin-android`. A host module already on `kotlin-android` for its own
  code does not need to change anything to *consume* this module; the conflict is only ever within
  one module's own plugin block.

## 4. Public API — exactly 4 symbols

Everything else in the module is `internal` (verified: `git grep` for top-level
non-`internal` declarations under `AR_feature/src/main` returns only these four, plus the two
manifest-declared Activities below). `ArMeasureHub` lives at
`vn.apero.armeasure.ar.presentation.host.ArMeasureHub` — import that full path, not the package
root (see §5 for the exact `import` line).

```kotlin
@Composable
fun ArMeasureHub(modifier: Modifier = Modifier)

object ArMeasureConfig {
    fun setImageSaver(saver: MeasurementImageSaver)          // one-shot; throws if called twice
    fun setContextWrapper(wrapper: ArMeasureContextWrapper)  // one-shot; throws if called twice
}

fun interface MeasurementImageSaver {
    suspend fun save(bitmap: Bitmap, fileName: String): Uri?
}

fun interface ArMeasureContextWrapper {
    fun wrap(base: Context): Context
}
```

`setContextWrapper` is **required on any host with an in-app language picker** and unnecessary on
every other host — see §10.

Two Activities are declared in the manifest but are **not** API — do not launch them directly,
they are not part of the contract and may be renamed or restructured without notice:
`vn.apero.armeasure.ar.presentation.host.ArCameraActivity`,
`vn.apero.armeasure.photo.presentation.ArPhotoActivity`. `ArMeasureHub` starts both internally.

**Both of these Activities hide the system navigation bar** (transient-by-swipe:
`WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` +
`hide(Type.navigationBars())`, re-applied on `onResume`/`onWindowFocusChanged` since a hidden bar
returns after user interaction), never the status bar. The shared logic lives in one place —
`internal abstract class ArNavBarHidingActivity` in `common/ui`, which both Activities extend —
rather than duplicated per-Activity lifecycle overrides. `ArMeasureHub` itself does none of
this: it is embedded in the *host's own* Activity, so it simply inherits whatever bar behaviour
that host Activity already applies. This means a host that shows system bars everywhere will
still see the bar hide specifically inside these two module-owned, full-screen camera/photo
screens — a deliberate choice (the mock shows no nav bar on any screen, and these are full-screen
capture tasks), not a bug. If a host ever needs this configurable, that is a future
`ArMeasureConfig` field, not something added speculatively now.

## 5. Host wiring

```kotlin
import vn.apero.armeasure.ar.presentation.host.ArMeasureHub
```
`ArMeasureHub` sits deep in `ar.presentation.host`, not at the package root as the name suggests —
add the import above, do not guess a shorter path.

`ArMeasureHub()` is a **composable the host embeds as one of its own tab bodies** — it is a tab
**root**: it draws no bottom navigation bar of its own and has no back button, because the host's
own tab bar already owns that chrome. Concretely, for a host shaped like `AIP936-AIHomeDesign`
(**documentation only, a paper dry-run against that repo's real files — this repo has never
modified that project, and nothing described here has been built or run there**):

That host's `when (tab)` slot architecture makes adding a tab a **four-file** change, not two —
verified against AIP936's actual files, not assumed from a generic bottom-nav shape:

1. `app/.../ui/feature/main/MainContract.kt` — add `MEASURE` to the `Tab` enum:
   ```kotlin
   enum class Tab { HOME, EXPLORE, MEASURE }
   ```
2. `app/.../ui/feature/main/components/main-tab.kt` — this is the file that actually holds the nav
   item list (`val MainTabs: List<MainTabItem>`, plus `typealias MainTab = MainContract.Tab`), not
   `aip-bottom-nav.kt` (that file only renders whatever list it's given). Add a `MainTabItem`:
   ```kotlin
   MainTabItem(tab = MainContract.Tab.MEASURE, labelRes = R.string.nav_measure, icon = R.drawable.ic_tab_measure)
   ```
   **`nav_measure` and `ic_tab_measure` do not exist in AIP936 today** — the host must add both
   (a string resource and a drawable) or this step fails on a missing-resource error.
3. `app/.../ui/feature/main/MainScreen.kt` — its `when(selectedTab)` dispatches through **slot
   lambda parameters** (`homeContent`/`exploreContent`), not a self-contained branch. Add a
   `measureContent: @Composable () -> Unit` parameter and its branch:
   ```kotlin
   MainContract.Tab.MEASURE -> measureContent()
   ```
   `MainScreen`'s bottom nav floats over the body in a `Box(BottomCenter)`; every existing tab page
   reserves `Aip.sizes.bottomNavOverlayInset` (96.dp) at its own bottom so content can't run under
   the capsule. `ArMeasureHub` does not reserve this itself — see the modifier note below.
4. `app/.../ui/feature/main/MainActivity.kt` (its `MainScreen(...)` call site) — pass the new slot:
   ```kotlin
   measureContent = { ArMeasureHub(Modifier.padding(bottom = Aip.sizes.bottomNavOverlayInset)) }
   ```
   The inset is a host-side responsibility, applied through the `modifier` parameter
   `ArMeasureHub` already exposes — the module deliberately does not reserve it internally, since
   that would hardcode "the host's nav bar floats over content," which is false for a host whose
   nav bar takes a real row in a `Column` (this repo's own `:app`/`AppTabBar` is exactly that
   shape).

No DI wiring is needed — the module takes no dependency on the host's DI graph (verified: zero
Koin/Hilt references anywhere in `AR_feature`). The one optional line is in
`Application.onCreate`, only if the host wants to override where saved photos land:
```kotlin
ArMeasureConfig.setImageSaver(MyImageSaver())
```

## 6. Manifest + permissions — nothing for the host to add

Verified against the **merged** manifest (`app/build/intermediates/merged_manifest/*/AndroidManifest.xml`
in this repo), not the module's own source, since that is what a host actually inherits:

- `<uses-permission android:name="android.permission.CAMERA" />` — the module's only declared
  permission. There is **no `WRITE_EXTERNAL_STORAGE`** of any kind (the module never writes below
  API 29 — see §9).
- Two `<uses-feature android:required="false">` entries: `android.hardware.camera` and
  `android.hardware.camera.ar`. Both `required="false"` so the host app still installs on non-AR
  devices; flipping either to `true` would make Play Store filter the listing off non-AR-certified
  devices, a reach cost, not just an install-count one.
- `<meta-data android:name="com.google.ar.core" android:value="optional" />` — ARCore ships as a
  separate Play-Services-for-AR APK; the module gates the feature itself instead of asking Play to
  gate the install.
- `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="subject_segment" />`
  — asks Play Services to fetch the subject-segmentation model at install time rather than on the
  first tap, so Picture Measure's auto-fit is not dead the first time a user reaches it. The code
  treats a missing model as an ordinary failure and falls back, so this is an optimisation, not a
  requirement.
- A module-owned `<provider>`: `vn.apero.armeasure.photo.data.ArMeasurePhotoFileProvider`,
  authority `${applicationId}.armeasure.fileprovider`, `exported="false"`, exposing only its own
  `cache/camera-capture/` subdirectory (`armeasure_file_paths.xml`) — used only to hand the system
  camera app a writable Uri when registering a custom reference object by photo. Namespaced under
  the module's own suffix specifically so it never manifest-merger-conflicts with a host's own
  `FileProvider` at a different authority.
- Two Activities, both `exported="false"`, no `intent-filter` on either — see §4.
- **A photo-only-intent host still gets all of the above** — there is no build variant that drops
  the AR manifest signals; the module always ships the ARCore dependency (see the intro above).
- Not the module's own, but inherited transitively because the module depends on
  `io.github.sceneview:arsceneview`: `INTERNET`, `VIBRATE`, and a `required="true"`
  `glEsVersion="0x00030000"` `uses-feature` (SceneView's own manifest). A host that did not
  already declare these will see them appear once this module is added.

**Host action required: none.** No `<provider>`, no permission, no manifest entry to add.

## 7. AR availability — module-owned, host does nothing

`ArMeasureHub` reads a read-only `rememberArAvailability()` internally. Both cards always show,
including on a device that resolves to `Unsupported` — tapping "AR Measure" there opens
`ArUnsupportedDialog` rather than the camera. The card used to hide itself, which left the tab
looking like the module only ever had one feature: the user never learned the other existed, nor why
they could not have it. "Picture Measure" has no ARCore dependency and works regardless.

The dialog offers two actions. "Use Picture Measure" starts the same Activity the Picture Measure
card does, so the advice it gives is one tap rather than an instruction. "Details" opens Google Play
Services for AR in the Play Store — worth being clear that on a device reporting
`UNSUPPORTED_DEVICE_NOT_CAPABLE` installing it will **not** make AR work, since the gate is Google's
certification list, not a missing APK. It does help the other case that maps to the same
`Unsupported` state, where `UNKNOWN_ERROR`/`UNKNOWN_TIMED_OUT` means the services really are absent
or stale. Splitting those two into distinct states, so the button only appears where it can help,
is an open improvement.

- ARCore's first `checkAvailability` call can return a transient "checking" state with no further
  `onResume` to notice it settle. Both the hub and `ArCameraActivity` re-poll every 200 ms for up
  to 3 s (15 attempts), then fall through to `Unsupported` rather than leaving the entry point
  blank forever (`rePollArAvailability`, shared by both call sites).
- `ArCameraActivity.onResume()` calls `ArMeasureKit.requestInstall`, which can navigate to the Play
  Store and back — the return trip *is* the next `onResume`, which is why this call lives in an
  Activity the module owns rather than in the hub composable.
- `ArAvailability.Unsupported` is expected on a non-trivial fraction of real devices — ARCore
  gates on Google's per-device certification list, independent of raw hardware capability (see
  `plans/reports/report-260824-1520-arcore-hardware-limitation.md`: on-device confirmed on a
  Xiaomi POCO X7 and Samsung Galaxy A07, both otherwise capable, neither ARCore-certified).

**Host work: none.**

## 8. Units

Four units — `Cm` / `M` / `Inch` / `Ft` — a hard user choice per decision, not a suggestion, not
auto-switched by magnitude. Every measuring screen persists the choice via `UnitPreference`
(`SharedPreferences`, module-namespaced, process-wide) and every screen reads it on entry.
`MeasurementResult` always stores metres internally; `unit` is a display concern applied only at
render time by `formatLength`.

Documented exception to "no string literals in Kotlin": `cm`/`m`/`in`/`ft` are hardcoded Kotlin
`String` literals on `LengthUnit` itself, not string resources — `formatLength` runs on a
per-frame AR hot path and is covered by plain JUnit tests with no `Context`/Robolectric available
to resolve a resource. The `UnitMenu`'s own labels ("Centimeters", "Meters", "Inches", "Feet") are
real, localisable resources. One naming inconsistency, left as-is: the compact in-camera `UnitBtn`
abbreviates inches as `in`, while the `UnitMenu` labels it "Inches" — a cosmetic mismatch, not a
bug.

## 9. Saving measurements

`MeasurementImageSaver` (§4) is the one port that crosses an Activity boundary the host does not
launch: where a finished Picture-Measure photo (with its measurement lines drawn in, **no
watermark**) ends up. `ArMeasureConfig.imageSaver` is `null` until `setImageSaver` is called once;
until then, the module's own default (`MediaStoreImageSaver`) writes into
`Pictures/<app label>` via MediaStore's API 29+ pending-write flow.

- **API 29+ only, by design** — the module deliberately does not request
  `WRITE_EXTERNAL_STORAGE` at all, so there is no legacy write path below API 29. Below API 29,
  `PhotoMeasureScreen` hides its own save affordance and explains why instead of attempting a save
  that would silently no-op.
- **Trust boundary**: a host-supplied `MeasurementImageSaver` receives the user's photo bitmap —
  implementing it is a privacy decision for the host, not a mechanical one. Only install an
  implementation the host trusts.
- The module does **not** reuse a host's own file-saving helper (e.g. `FileUtils`-style code some
  hosts ship) — doing so would create exactly the kind of `:core`-module coupling this design
  avoids; the module's default saver is fully self-contained.

## 10. Strings + resources

Every user-facing string is a resource, every name prefixed `armeasure_` (`resourcePrefix` is
enforced at the module level, so a collision with a host's own resource name is impossible).
**108 strings in 11 locales** as of 2026-08-28: `values/` (English) plus `values-ar`, `-de`, `-es`,
`-fr`, `-hi`, `-ja`, `-nl`, `-pt`, `-ru`, `-zh`. A host whose locale list is narrower loses the extras
to its own resource filter, which costs nothing; a host with a locale the module lacks falls back to
English for the module's screens only.

One known exception still ships a literal: `QuadEditorCanvas.kt` draws two Vietnamese edge labels
straight into the `Canvas` (see §17).

### The host's language, which is not the device's language

A host that switches language with the platform's per-app languages
(`AppCompatDelegate.setApplicationLocales`) needs nothing here — that applies process-wide.

A host that instead wraps each Activity's context (`attachBaseContext` +
`createConfigurationContext`, the pattern in `AIP936-AIHomeDesign`'s `BaseComposeActivity`) reaches
only Activities extending *its* base class. `ArCameraActivity` and `ArPhotoActivity` do not, so
without the hook below they render in the **device** locale while the rest of the app renders in the
user's choice — the Measure tab comes up translated and every screen it opens comes up in English.
Install it once, from `Application.onCreate`:

```kotlin
ArMeasureConfig.setContextWrapper { base ->
    localeManager.syncLocale()             // whatever the host's own base Activity already does
    localeManager.updateLocale(base)
}
```

`ArMeasureHub` needs no equivalent: it is composed inside the host's own Activity and so already
reads that Activity's context.

## 11. Cold-start warm-up

The first AR screen mounted in a process (whichever of Distance/Distance chain/Box/Cylinder the user opens
first) waits a fixed **2 s** before ever mounting `ARSceneView`, via a shared, process-global,
one-shot gate (`ArWarmupGate.rememberArWarmedUp()`).

This exists because of a confirmed on-device race: on a cold launch, ARCore's `Session.update()`
can call before `setCameraTextureNames(...)` has actually completed, throwing
`TextureNotSetException` on every frame thereafter with no recovery short of a full remount
(reproduced on a Pixel 6 and a POCO X7; not observed on a Samsung device tested the same way — see
`plans/reports/report-260825-1703-session-handoff-box-cylinder-measure.md` §10 for the full trace).
2 s is a deliberately generous, **unmeasured** margin, not a measured floor — a device with a
faster driver never hits the race regardless, and the true minimum could plausibly be shorter.
The gate is paid once per process regardless of which AR tool triggers it first; the other two
never pay it again in the same process.

## 12. One shared ARCore session

`ArCameraScreen` mounts exactly **one** `rememberEngine()` and exactly one `ARSceneView(...)`
call site for all four AR tools (Distance, Distance chain, Box, Cylinder) — a bottom-sheet tool switch changes
which overlay/state machine is active without unmounting the view, so tracked planes survive a
tool swap. This is the single most important section for anyone touching the camera screen: two
earlier fix attempts during development shipped, then had to be **reverted** after on-device
testing showed they made the exact race they targeted *worse*:

> 1. Recreating the whole Filament `Engine` (not just the ARCore `Session`) on every watchdog
>    remount. Result: **near-100% failure rate** afterward, including on cold starts that had
>    previously worked — `Engine` is a heavyweight, GPU-resource-owning native object, and
>    destroying/recreating one on a ~10 s retry cadence very plausibly left GPU resources
>    mid-teardown for the next `Engine` trying to claim the camera texture. Reverted: `Engine`/
>    `MaterialLoader` are created once, for the screen's whole lifetime.
> 2. Proactively remounting `ARSceneView` on every plain `ON_RESUME`, not just a detected stall.
>    Result: regressed the common case — a short, previously-harmless background/foreground round
>    trip now always tore the camera down and reopened it, and that close-then-reopen race turned
>    out to be *more frequent* than the rare stale-texture case it targeted. Reverted: a resume now
>    only resets the watchdog's stall-detection clock, giving the existing session a fresh timeout
>    window instead of judging it by elapsed background time.
>
> — `plans/reports/report-260825-1703-session-handoff-box-cylinder-measure.md` §11

The structural rules that keep this from recurring, enforced by code comment in
`ArCameraScreen.kt` and worth restating here: the Engine/MaterialLoader constructors run exactly
once, outside any remount block; the `ARSceneView` call sits outside every `when (tool)` branch,
and `tool` never appears inside a `key(...)`; `ArWarmupGate` (§11) is consulted once, before the
view ever mounts, for any of the four tools.

One consequence of "one session for four tools": the session config (`PlaneFindingMode
.HORIZONTAL_AND_VERTICAL`, `DepthMode.AUTOMATIC` where supported) is the heaviest union all three
tools could need, even though Box/Cylinder's height step is an **analytic construction-plane
ray-cast that needs no depth image at all** — `DepthMode.AUTOMATIC` there is pure thermal cost
with no accuracy benefit, and cannot be turned off per-tool under one shared session.

## 13. R8 / ProGuard

Verified clean on the merged module — no `consumer-rules.pro`, by design, not by omission (see
the R8 release-hardening audit report dated 2026-08-26 in this repo's `plans/reports/`):

- SceneView/Filament's own AAR ships a root `proguard.txt` (JNI, kotlin-math, collision, GMS
  FusedLocation keeps) that AGP auto-merges — visible in
  `app/build/outputs/mapping/release/configuration.txt`.
- `com.google.ar:core`'s `Plane::class.java` class-token lookup (`MeasureFrameLoop.kt`,
  `ShapeFrameLoop.kt`) is safe: the `com.google.ar:core` AAR ships a whole-package
  `-keep public class com.google.ar.core.**`; `mapping.txt` shows `Plane` unrenamed.
- `isShrinkResources` does not touch any `armeasure_*` string — all reachable via static
  `R.string.armeasure_*` references, confirmed present in `resources.txt`.
- `ArMeasurePhotoFileProvider` is auto-kept because it is manifest-declared; unrenamed in
  `mapping.txt`.
- `CustomReferenceStore` uses hand-rolled `org.json` (`JSONObject`/`JSONArray`), **not** Gson or
  `kotlinx.serialization` — no speculative keep rule is needed for it, and none should be added
  "just in case."
- `play-services-mlkit-subject-segmentation` needed no keep rule: this module's call sites
  (`SubjectSegmentation.getClient`, `SubjectSegmenterOptions.Builder`, `InputImage.fromBitmap`) are
  reflection-free and the GMS AARs ship their own consumer rules. `assembleRelease` passes.
  `mapping.txt` shows `SubjectSegmenter`, `SubjectSegmenterOptions`, `SubjectSegmenterOptions$Builder`
  and `SubjectSegmentationResult` all present-and-renamed, while the `SubjectSegmentation` holder
  class itself reads `R8$$REMOVED$$CLASS$$` — expected, since its only member is a static factory
  that R8 inlines into the caller. **Still worth one release-build smoke test on a device before
  shipping**: the model is fetched through Play Services' optional-module machinery, which is the
  part of this dependency least likely to be exercised by a compile alone.

**Revisit trigger**: a `com.google.ar:core` or `io.github.sceneview:*` version bump that drops
that AAR's bundled `proguard.txt`. Re-verify by unzipping the new AAR and checking for
`proguard.txt`, then re-running the checks in §14 below.

## 14. Verify the integration

```bash
./gradlew :AR_feature:compileDebugKotlin :AR_feature:testDebugUnitTest   # 185 tests, no device
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease      # mandatory — see below
```

**`assembleRelease` is not optional.** R8 only runs in the release build type, and its failures
are silent at the app level (no crash log pointing at ProGuard) — the sibling `:feature-video`
module shipped exactly this shape of release-only breakage once. A green `assembleDebug` proves
nothing about R8.

After a release build, grep the merged manifest for the permission surface in §6:
```bash
grep -A2 'uses-permission\|armeasure.fileprovider' app/build/intermediates/merged_manifest/release/*/AndroidManifest.xml
```

Then a smoke install (ad-hoc debug-keystore signing purely to make the release APK installable —
**a verification step, never a build configuration**; do not add a signing config to any Gradle
file for this):
```bash
adb -s <device-serial> install -r app/build/outputs/apk/release/app-release.apk
```

**What no script can verify**: placing AR points and drawing a 3-tap Box/Cylinder shape needs a
human aiming a real phone at a real textured surface — there is no way to script ARCore's camera
input. Budget for this explicitly; a green build is not evidence the AR tools work.

## 15. Architecture — MVI, matching the Apero apps

`data/ domain/ presentation/` layering, and the presentation layer is MVI in the same shape as the
Apero/VSL apps' `core` module: per screen a `XContract.kt` (immutable `data class State`, plus
`sealed interface Intent` and `Effect`) and a `XViewModel.kt` extending

    MviViewModel<S : MviState, I : MviIntent, E : MviEffect>

with `state: StateFlow<S>`, `effect`, `processIntent`, `updateState { copy(...) }` and `sendEffect`.
Screens are `PhotoMeasure*`, `ArCamera*`, `Measure*` (Distance and the chained variant) and `Shape*`
(Box and Cylinder).

The base lives here, in `common/presentation/mvi/`, rather than being reused: this module is
standalone and cannot depend on another project's `core`. It is `internal`, so a host that already has
its own `MviState` never sees a second one. Two deliberate differences from the shared version, both
load-bearing:

- **`persist(state)`**, called after every state change. A ViewModel survives a configuration change
  but dies with the process, so without a single hook every screen that hands the foreground to
  another app has to remember which individual fields to save. This module previously accumulated six
  separate `rememberSaveable` patches that way, each shipped as a bug first.
- **`_state` is `by lazy`.** As a plain field initializer it runs during the *base* class's
  construction, before a subclass's constructor properties are assigned — so a `createInitialState()`
  reading a constructor argument silently sees the type default. It compiles and does not warn; the
  screen just starts in the wrong state. **The shared base in `core` still has this trap.**

### Two deliberate exceptions to "everything is State, everything is an Intent"

Both are about event rate, both are documented at the code:

1. **The ARCore frame stream is not `State`.** `onFrame(...)` runs from ARCore's callback at roughly
   30-60 Hz, writing tracking flags and the live measurement. Routed through
   `processIntent -> SharedFlow -> handleIntent -> updateState` that is a coroutine dispatch and a
   whole-state allocation per frame, replacing Compose's per-field invalidation with whole-state
   invalidation. Those values live in `MeasureFrameStream` / `ShapeFrameStream` /
   `ArSessionFrameStream`, owned by the ViewModel and read directly by the renderer. Transient render
   state is not UI state.
2. **Drag is a direct ViewModel call, not an Intent.** `detectDragGestures` fires on every pointer
   move, 60-120 times a second, and the intent channel is a zero-buffer `MutableSharedFlow` whose
   `emit` suspends until the collector catches up. The photo and AR halves reached this independently.

The Joy_4 is why both exist: release cold start there is 648 ms against 2.7 s for a debug build, and
there is no headroom to spend on a coroutine hop per frame. **Neither exception has been profiled** —
they are reasoned from the call sites. `plans/260827-1910-mvi-alignment/regression-test-scenario.md`
item X8 carries the measurement that is still owed.

### Coordinates

`quad`, `segments` and the homography are in the photo's **own pixel grid**, not screen pixels, and
are projected at the draw and gesture edges only (`PhotoCoordinates.kt`). They used to be display-space,
which meant any relayout invalidated all three at once — that shipped as a quad sitting ~180 px off the
object, and corrupted a hand-captured measurement fixture badly enough to waste a session of tuning.
The export path needs no conversion at all now: its draw target *is* the photo's grid, so the same
projection is the identity there.

### What still does not survive process death

The photo bitmap, the quad and the segments. Only the reference choice, the open sheets and the
in-progress camera capture Uri do. A `Bundle` has a hard transaction size limit and throws rather than
truncating, so the bitmap deliberately stays out.

## 16. Maintenance audits

One-line, re-runnable checks for the invariants no compiler enforces (from this module's own
final verification pass):

| # | Check | Command |
|---|---|---|
| 1 | No string literals in Kotlin | `git grep -nE 'Text\(\s*"|text = "|label = "' AR_feature/src/main` → only glyphs |
| 2 | No Vietnamese literals in code | `grep -rnP '"[^"]*[àáảãạăâđèéẻêềếệìíỉòóỏôồốơớùúủưứỳýỵ]' AR_feature/src/main --include=*.kt` → every hit must be inside a comment. The pattern cannot tell a KDoc quotation from a UI string, and several KDocs legitimately quote the Vietnamese design (`"điện thoại"`, `"Chỉnh sửa tỉ lệ"`, `"Lưu"`), so read the hits rather than counting them. A hit in a `stringResource`, `Text(` or any non-comment line is a real failure |
| 3 | Locales complete and consistent | every `values-*/strings.xml` declares the same `<string name=` set as `values/`, in the same count (108 × 11 today). A missing name silently falls back to English for that locale only, which no build or lint step reports |
| 4 | Resource prefix intact | every `<string name=` starts with `armeasure_` |
| 5 | No `ar` ↔ `photo` cross-import | `git grep -n 'import vn.apero.armeasure.ar\.' AR_feature/src/main/java/vn/apero/armeasure/photo` and the mirror → both empty (Kotlin `internal` no longer enforces this boundary post-merge — it is convention only) |
| 6 | Public API is 4 symbols | re-run the grep in §4 |
| 7 | No debug logging of user content | `grep -rn 'Log\.' AR_feature/src/main --include=*.kt` → only the six deliberate lines in `SegmentQuad.kt` and `CameraCapture.kt`, none of which logs user content. They are kept because both are silent-fallback paths: without them an unavailable segmentation model is indistinguishable from one that ran and found nothing, and a lost camera destination Uri looks identical to a cancelled capture. Any other hit, or any of these gaining a filename, Uri or measurement, is a failure |
| 8 | One Engine, one `ARSceneView` | `git grep -c 'rememberEngine\|ARSceneView(' AR_feature/src/main` → 1 each |
| 9 | Reflection sweep | `git grep -n '::class.java\|Class.forName\|reflect\|Gson\|kotlinx.serialization\|getIdentifier' AR_feature/src/main` → only the two documented `Plane::class.java` lookups |
| 10 | R8 clean | re-run §13/§14 after any dependency bump |

## 17. Known limitations and deferred items

Stated plainly, not buried:

- **Cosmetic debt**: bare Unicode glyph icons stand in for real iconography (`▬`, `▨`, `🗑` —
  the trash glyph renders as a full-colour emoji on most keyboards/fonts, not a monochrome icon);
  photo dimension labels repeat the unit on both sides (`"21 cm × 30 cm"` rather than `"21 × 30
  cm"`); `QuadEditorCanvas.kt` still draws two hardcoded Vietnamese labels ("cạnh dài" / "cạnh
  ngắn" — "long edge"/"short edge") directly into the `Canvas`, shown to every user regardless of
  locale — a real gap in decision 14 (resource-driven strings), not yet fixed. It is now the **only**
  such literal: the nine others (built-in reference names, shape and shape-part nouns, hit-source
  names) moved into `strings.xml` on 2026-08-28, after they were found rendering English inside
  translated sentences ("用Payment card拍一张照片").
- **`DragHandle`'s touch target is 28dp**, under this module's own documented ≥48dp convention
  used everywhere else (`ColorDot`, `CmUnitBadge`, etc.). A magnifier loupe may be intended to
  compensate; that tradeoff is not written down anywhere in the code, so treat it as open, not
  intentional.
- **The `camera-capture/` cache directory is swept before each capture and the file is deleted once decoded.** Every "take a photo" round trip
  (custom reference object registration, or the main photo picker) writes a new file there and
  nothing ever deletes it — low risk (cache dir, OS can reclaim under pressure), but unbounded
  growth for the life of the install.
- **AR has no terminal state and produces no saved artifact.** Only the Picture Measure path ends
  in a file (§9); a Distance/Distance chain/Box/Cylinder result exists only on screen as a toast/label and is
  gone once the Activity closes. Frame capture to the gallery and any saved-measurement list are
  both explicitly out of scope.
- **Accuracy**: this is a layout tool, not a caliper. On a phone with no depth sensor (most
  Android devices) expect several centimetres of error per point; that error is not a stable bias
  and cannot be calibrated away. Device certification is itself a gate before accuracy is even a
  question — see `plans/reports/report-260824-1520-arcore-hardware-limitation.md` for the full
  breakdown of why an otherwise-capable device can still be `Unsupported`.
- **Thermal cost**: one ARCore session config serves all four tools, so it is
  necessarily the heaviest union any single tool needs — `DepthMode.AUTOMATIC` (§12) is pure heat
  for Box/Cylinder's height step, which never reads a depth image.
- **Picture Measure needs the reference object visible in the same photo** as whatever is being
  measured — the app cannot infer scale otherwise. Auto-fit is a convenience on top of that, and it
  is allowed to decline: anything it produces that fails `isPlausibleReferenceQuad` is dropped in
  favour of the manual four-corner box, because a wrong quad is worse than none (the user trusts it,
  confirms, and every measurement afterwards is miscalibrated with nothing on screen to say so).
- **Auto-fit's remaining accuracy gap is unquantified, not closed.** The plausibility bounds reject
  only the obviously implausible; a quad of the right size and right proportion locked onto the wrong
  object passes every one of them, and no threshold will catch that — only the user seeing the box
  before confirming. The bounds themselves are reasoned from geometry and synthetic tests, not
  validated against real photos: the one real-photo fixture's ground truth is known to be wrong (see
  `RealPhotoAutoFitTest`) and both its assertions are disabled until a clean capture replaces it.
- **Subject segmentation is unbundled and beta.** `play-services-mlkit-subject-segmentation` adds no
  APK weight (the model arrives through Play Services) but is simply absent on a device without them,
  and Google warns the API may change incompatibly. Both are why the Canny+Hough path is kept rather
  than deleted — it is the only detector on a non-GMS device, and it is more precise than a mask
  boundary wherever the object's edges do have contrast.
- **Several `.kt` files exceed this repo's own ~200-line guideline** — each carries KDoc stating why
  it was kept whole rather than split (e.g. `ArCameraScreen.kt`'s "structural rules" section, §12,
  explains why splitting it would fight the single-mount-point design it documents):
  `HullQuadFit.kt` (205), `QuadFromEdges.kt` (212), `ShapeMath.kt` (218), `MeasureState.kt` (238),
  `ArCameraChrome.kt` (285), `ShapeMeasureState.kt` (286), `ShapeFrameLoop.kt` (302),
  `PhotoMeasureScreen.kt` (410), `PhotoMeasureState.kt` (436), `ArCameraScreen.kt` (328).
  `PhotoMeasureState.kt` and `PhotoMeasureScreen.kt` grew most and are the ones worth splitting next.

### Note for the host team (not this module's work)

If the host ships its own file-saving helper (e.g. an `AI_Photo_*.jpg`-into-`Pictures/AI Profile
Photo`-style utility from an earlier clone), this module deliberately does **not** depend on it —
doing so would be exactly the `:core`-module coupling this design avoids. Worth knowing about
independently of this integration, not something this module's default saver (§9) needs fixed.
