# `:ar-measure-common`

Shared value types + drawing helper used by both feature modules
(`:ar-measure-ar`, `:ar-measure-photo`). No Android framework surface beyond Jetpack Compose UI
types — no ARCore, no camera, no permissions, no manifest entries. Always required: both
feature modules declare `api(project(":ar-measure-common"))`.

## When you need this module

Always — it is the transitive dependency of both feature modules and cannot be skipped. You
never add it directly unless you are building your own screen against these types without using
either feature module.

## 1. Copy + include

Copy the whole `ar-measure-common/` folder into your repo root, then in `settings.gradle.kts`:

```kotlin
include(":ar-measure-common")
```

## 2. Version catalog — base block

Append to `gradle/libs.versions.toml`. This is the **base** block shared by all three modules;
`:ar-measure-ar` and `:ar-measure-photo`'s READMEs each list only their *additional* keys on top
of this one — apply this block once regardless of which feature module(s) you also take.

```toml
[versions]
# Highest AGP that Android Studio AI-253 (2025.3) accepts. Newer AGP fails IDE sync
# even though the CLI build succeeds. Raise this only alongside an IDE upgrade.
agp = "9.2.0-rc01"
kotlin = "2.4.10"
# 2026.08.00 pulls compose-runtime 1.12.x, which demands compileSdk 37.
# Pinned to the last BOM that compiles against 36.
composeBom = "2026.05.01"
junit = "4.13.2"

[libraries]
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

**If a key with the same name already exists in your catalog** (very likely for `agp`, `kotlin`,
`composeBom`, `junit`, `androidx-compose-bom`, `androidx-ui`, `androidx-ui-graphics` in any host
that already ships Compose): do not add a second key of that name — TOML rejects duplicate keys
in the same table, and Gradle version catalogs are generated from that TOML. Instead:
- If the existing entry points at the **same** group/artifact (it will, for all the library/plugin
  aliases above — there is only one real artifact behind each), keep the host's existing entry
  and skip that line. A newer host version is fine; these modules build against nothing exotic.
- If a key of the same *name* somehow points at something else, rename only the new key (e.g.
  `armeasureComposeBom`) and update the one line in this module's own `build.gradle.kts` that
  references it after copying — but this module's checked-in `build.gradle.kts` itself is
  otherwise not meant to be edited.

## 3. Build requirements

- `compileSdk` ≥ 36, `minSdk` ≥ 24 (lower than a host's own floor is fine — a library may sit
  below the app's `minSdk`), Java 17 (`sourceCompatibility`/`targetCompatibility`), Compose
  enabled (`buildFeatures.compose = true`).
- AGP 9's built-in Kotlin support is what compiles this module — no `kotlin-android` plugin is
  applied anywhere in this module's own `build.gradle.kts`. Applying `kotlin-android` *and*
  relying on AGP's built-in Kotlin in the same module is a build error; a host module already on
  `kotlin-android` does not need to change anything to consume this module, the conflict is only
  ever within one module's own plugin block.

## 4. Public API

All under `vn.apero.armeasure.common.domain` / `vn.apero.armeasure.common.ui`.

```kotlin
enum class LengthUnit { Metric, Imperial }

sealed interface MeasurementResult {
    data class Distance(val meters: Float, val unit: LengthUnit) : MeasurementResult
    data class Box(val lengthU: Float, val lengthV: Float, val height: Float, val unit: LengthUnit) : MeasurementResult
    data class Cylinder(val radius: Float, val height: Float, val unit: LengthUnit) : MeasurementResult
    data class Photo(val distanceMeters: Float, val unit: LengthUnit) : MeasurementResult
}

fun formatMeters(meters: Float, locale: Locale = Locale.getDefault()): String   // "1,6 m"
fun formatImperial(meters: Float): String                                       // "5' 3\""
fun formatLength(meters: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String

fun DrawScope.drawLabelPill(
    textMeasurer: TextMeasurer,
    label: String,
    center: Offset,
    style: TextStyle,
    backgroundColor: Color = Color.White,
)
```

**Every length field on every `MeasurementResult` variant is in metres, always** — `unit` is a
display preference for how the host should render the number, never a conversion already applied
to the field. Convert with `formatLength`/`formatMeters`/`formatImperial` at render time.

`drawLabelPill` is a screen-space rounded label used inside a Compose `Canvas`; useful if you
build your own overlay on top of either feature module's result, not required to consume the
screens themselves.

## 5. R8 / ProGuard

Nothing required. No reflection.

## 6. Verify the integration

```bash
./gradlew :ar-measure-common:compileDebugKotlin
```

No on-device check — this module has no UI screen of its own to smoke-test; it is validated
indirectly through whichever feature module you add next.

## Host compatibility notes (paper dry-run)

Dry-run against a real, unrelated multi-module Compose app (Gradle `:app` + `:core`, Koin DI,
minSdk 26 / compileSdk 36, no prior ARCore/SceneView exposure) surfaced the two failure shapes to
actually watch for — recorded here so automation checks for these instead of assuming a clean
append:

1. **Same key name, different value.** That host already defines its own `agp` (a newer patch,
   pinned for an unrelated composite-build reason), `kotlin`, `composeBom` (an older-but-still-
   `compileSdk 36`-compatible BOM), `coreKtx` (happened to be byte-identical, `1.17.0`), `junit`,
   and an `androidx-activity-compose` alias — all under those same key names. In every case the
   existing value was compatible (same artifact, a version at or above what these modules need);
   the correct move was to keep the host's value and add nothing for those specific keys, per the
   rule above. Never assume a host has none of these — check first.
2. **Different key name, same artifact — the dangerous one.** That host's Compose UI aliases were
   named `androidx-compose-ui` / `androidx-compose-ui-graphics` / `androidx-compose-material3`,
   not this repo's `androidx-ui` / `androidx-ui-graphics` / `androidx-material3`. Because these
   modules' own `build.gradle.kts` files call the aliases by their exact dotted name (e.g.
   `libs.androidx.ui`), a semantically-equivalent-but-differently-named existing alias does
   **not** satisfy the reference — Gradle looks up the literal name. These modules' checked-in
   `build.gradle.kts` are not meant to be edited, so the fix is always to add the new alias name
   from this README's block (pointing at the same underlying artifact the host already has under
   its other name), never to rename or dedupe against the host's existing one.

## Known limitations

None specific to this module. See `../ar-measure-ar/README.md` and `../ar-measure-photo/README.md`
for the feature modules' caveats.
