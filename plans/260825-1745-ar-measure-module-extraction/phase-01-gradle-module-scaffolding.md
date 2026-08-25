# Phase 01 — Gradle module scaffolding

## Context Links

- Spec: [`report-260825-1745-ar-measure-module-architecture-brainstorm.md`](../reports/report-260825-1745-ar-measure-module-architecture-brainstorm.md) §1 (module split), §2 (namespaces)
- Overview: [`plan.md`](plan.md)
- Files read while planning: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`

## Overview

- **Priority:** P1 (blocking — nothing else compiles without it)
- **Status:** completed
- **Effort:** 1h
- Create three empty Android library modules (`:ar-measure-common`, `:ar-measure-ar`,
  `:ar-measure-photo`) with correct namespace, SDK levels, Compose enabled, dependencies, and
  manifests. No feature code moves in this phase.

## Key Insights

- `gradle/libs.versions.toml` has **no `com.android.library` plugin alias** — only
  `android-application`. It must be added; this is the single reason nothing can be scaffolded
  incrementally.
- This project runs **AGP 9.2.0-rc01, which ships built-in Kotlin support** — applying
  `org.jetbrains.kotlin.android` is an error here (see the comment in `app/build.gradle.kts`).
  Library modules apply `com.android.library` + `kotlin.compose` only.
- Version pins are deliberate and commented in `libs.versions.toml` (AGP pinned to what the IDE
  accepts, Compose BOM pinned to the last one compatible with compileSdk 36). **Do not bump
  anything** in this phase.
- `androidx.lifecycle.compose.LocalLifecycleOwner` is used by `measure/MeasureScreen.kt` but only
  `lifecycle-runtime-ktx` is declared today (it resolves transitively). Declaring
  `lifecycle-runtime-compose` explicitly on `:ar-measure-ar` removes reliance on a transitive.
- Library modules must not declare `applicationId`/`versionCode`/`versionName` (invalid for
  `com.android.library`).

## Requirements

Functional:
- `./gradlew :ar-measure-common:assembleDebug :ar-measure-ar:assembleDebug :ar-measure-photo:assembleDebug` succeeds.
- `:app` still builds and runs unchanged (no `:app` edit in this phase beyond nothing at all).

Non-functional:
- `minSdk 24`, `compileSdk 36`, `targetSdk 36`, Java 17 source/target — identical to `:app`.
- `:ar-measure-photo` must have **zero** ARCore/SceneView on its compile or runtime classpath.
- No module depends on a DI framework.

## Architecture

```
settings.gradle.kts
  include(":app", ":ar-measure-common", ":ar-measure-ar", ":ar-measure-photo")

:ar-measure-common   namespace vn.apero.armeasure.common   deps: compose ui/ui-graphics (api), junit (test)
:ar-measure-ar       namespace vn.apero.armeasure.ar       deps: api(project(":ar-measure-common")),
                                                                 compose ui/material3/foundation (api),
                                                                 sceneview-ar (implementation),
                                                                 lifecycle-runtime-compose (implementation),
                                                                 core-ktx (implementation), junit (test)
:ar-measure-photo    namespace vn.apero.armeasure.photo    deps: api(project(":ar-measure-common")),
                                                                 compose ui/material3/foundation (api),
                                                                 activity-compose (implementation),
                                                                 core-ktx (implementation), junit (test)
:app                 → api-consumes all three (wired in phase 05)
```

`api` for Compose artifacts because the modules' public surface is `@Composable` functions and
`DrawScope`/`Modifier` types — a consumer must see them to call anything. `implementation` for
sceneview so ARCore/Filament never leaks onto a consumer's compile classpath (the public facade
returns the module's own `ArAvailability` enum, never an ARCore type).

## Related Code Files

Create:
- `ar-measure-common/build.gradle.kts`
- `ar-measure-common/src/main/AndroidManifest.xml` (empty `<manifest/>` — see note below)
- `ar-measure-ar/build.gradle.kts`
- `ar-measure-ar/src/main/AndroidManifest.xml`
- `ar-measure-photo/build.gradle.kts`
- `ar-measure-photo/src/main/AndroidManifest.xml`
- `ar-measure-{common,ar,photo}/.gitignore` (`/build`)

Modify:
- `settings.gradle.kts` — 3 `include` lines
- `gradle/libs.versions.toml` — add `android-library` plugin alias + `androidx-lifecycle-runtime-compose` library alias
- `build.gradle.kts` (root) — add `alias(libs.plugins.android.library) apply false`

Delete: none.

## Implementation Steps

1. `gradle/libs.versions.toml`: add under `[plugins]`
   `android-library = { id = "com.android.library", version.ref = "agp" }`; add under
   `[libraries]` `androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }`.
2. Root `build.gradle.kts`: add `alias(libs.plugins.android.library) apply false`.
3. `settings.gradle.kts`: extend to
   `include(":app", ":ar-measure-common", ":ar-measure-ar", ":ar-measure-photo")` (or 3 extra
   `include(...)` lines — match the file's existing single-line style).
4. Create `ar-measure-common/build.gradle.kts`:
   - plugins: `alias(libs.plugins.android.library)`, `alias(libs.plugins.kotlin.compose)`
   - `android { namespace = "vn.apero.armeasure.common"; compileSdk = 36;
     defaultConfig { minSdk = 24 }; compileOptions { source/target = VERSION_17 };
     buildFeatures { compose = true } }`
   - `lint { targetSdk = 36 }` and `testOptions { targetSdk = 36 }` if AGP 9 requires targetSdk on
     libraries via those blocks (AGP 9 removed `defaultConfig.targetSdk` for libraries — if the
     build errors on it, use these blocks; if it warns only, omit).
   - deps: `api(platform(libs.androidx.compose.bom))`, `api(libs.androidx.ui)`,
     `api(libs.androidx.ui.graphics)`, `testImplementation(libs.junit)`
5. Create `ar-measure-ar/build.gradle.kts` — same skeleton, `namespace = "vn.apero.armeasure.ar"`,
   plus `resourcePrefix = "armeasure_"`, plus the deps listed in Architecture above.
6. Create `ar-measure-photo/build.gradle.kts` — same skeleton,
   `namespace = "vn.apero.armeasure.photo"`, plus `resourcePrefix = "armeasure_"`, plus its deps.
7. Create the three `src/main/AndroidManifest.xml` files:
   - common: `<manifest xmlns:android="http://schemas.android.com/apk/res/android" />` (no
     permissions, no features — it is pure Kotlin + Compose atoms).
   - ar: `CAMERA` permission, `android.hardware.camera` `required=false`,
     `android.hardware.camera.ar` `required=false`, and inside `<application>` the
     `com.google.ar.core` = `optional` meta-data — copied verbatim from
     `app/src/main/AndroidManifest.xml` including its explanatory comments. Merging these from the
     library means a host app gets the correct AR declarations for free.
   - photo: empty for now; the FileProvider entry lands in phase 04.
8. Add a `.gitignore` containing `/build` to each new module dir (mirrors whatever `:app` does).
9. **Verification gate (blocking):**
   `./gradlew :ar-measure-common:assembleDebug :ar-measure-ar:assembleDebug :ar-measure-photo:assembleDebug :app:compileDebugKotlin :app:testDebugUnitTest`
   — all green, 67 tests still pass in `:app`.
10. Commit: `chore: scaffold ar-measure-common/-ar/-photo library modules`.

## Todo List

- [x] `libs.versions.toml`: `android-library` plugin alias + `lifecycle-runtime-compose` alias
- [x] Root `build.gradle.kts`: library plugin declared `apply false`
- [x] `settings.gradle.kts`: 3 modules included
- [x] `ar-measure-common/build.gradle.kts` + manifest + `.gitignore`
- [x] `ar-measure-ar/build.gradle.kts` + manifest (AR permissions/features/meta-data) + `.gitignore`
- [x] `ar-measure-photo/build.gradle.kts` + manifest + `.gitignore`
- [x] Verification gate green (3 module assembles + `:app` compile + 67 tests)
- [x] Commit

## Success Criteria

- Three modules assemble from a clean `./gradlew clean`.
- `./gradlew :ar-measure-photo:dependencies --configuration debugCompileClasspath` shows **no**
  `com.google.ar` / `io.github.sceneview` entry.
- `:app` behavior and test count untouched (67).
- Android Studio syncs without error (this is why AGP stays pinned).

## Risk Assessment

| Risk | Mitigation |
|---|---|
| AGP 9 rejects `targetSdk` in a library `defaultConfig` | Use `lint { targetSdk }` / `testOptions { targetSdk }` instead; step 4 already anticipates both outcomes |
| AGP 9's built-in Kotlin conflicts with explicitly applying kotlin-android | Do not apply it — only `com.android.library` + `kotlin.compose`, matching `:app` |
| Compose in a library needs a different BOM wiring than in an app | Same BOM + `kotlin.compose` plugin works for libraries; verified by the assemble gate before any code depends on it |
| IDE sync breaks on the new modules | Run a real Studio sync at the end of the phase, not just CLI |

## Security Considerations

- The AR manifest keeps `required="false"` on both `uses-feature` entries — flipping to `true`
  would silently filter a host app out of Play Store listings on non-AR devices. Documented in the
  manifest comment so a host does not "fix" it.
- No new permission beyond `CAMERA` (already required today). `:ar-measure-photo` declares **no**
  permission — the photo picker path needs none.
- No network, no exported component introduced in this phase.

## Next Steps

- Phase 02 (`:ar-measure-common` extraction) is unblocked immediately after the gate is green.
- Phases 03/04 remain blocked on 02.
</content>
