# Phase 07 — Per-module integration-guide READMEs

## Context Links

- Spec: architecture record §1 (a host may take common+photo only), §2 (namespaces), §4 (public
  API), §6 (host-collision fixes), §7 (host calls `ArMeasureKit`), §8 (no DI dependency — AIP936
  uses Koin, wraps a plain constructor in one line)
- Depends on: [phase 06](phase-06-final-verification-and-review.md)
- Downstream consumer (out of scope here): a `skill-creator` skill that automates integrating these
  modules into `AIP936-AIHomeDesign` (`/Users/admin/ahndroidne/StudioProjects/AIP936-AIHomeDesign`,
  Gradle multi-module, Koin, Compose, minSdk 26 / target 36 / compileSdk 36, zero existing
  ARCore/SceneView exposure)

## Overview

- **Priority:** P2
- **Status:** pending
- **Effort:** 1.5h
- Write one `README.md` per module, accurate and complete enough that a later automation step can
  integrate the modules into an unfamiliar app without reading module source.

## Key Insights

- **Audience is an automation step, not a human browsing.** Every instruction must be literal:
  exact file to edit, exact block to insert, exact symbol to call. "Add the Compose dependencies"
  is useless; the actual `libs.versions.toml` lines are not.
- Distribution model is **copy the module folder + add an `include(...)` line** (source modules, no
  Maven publishing). The single biggest automation gotcha follows from that: these
  `build.gradle.kts` files reference this repo's version-catalog aliases
  (`libs.androidx.compose.bom`, `libs.sceneview.ar`, `libs.androidx.activity.compose`, …), which do
  **not** exist in a host's catalog. Each README must therefore ship the exact
  `gradle/libs.versions.toml` block to append, including this repo's pinned versions and the
  comments explaining why they are pinned (AGP pinned to what the IDE accepts, Compose BOM pinned to
  the last one compatible with compileSdk 36).
- A host on a newer Compose BOM / AGP is the likeliest integration failure. Say so explicitly with
  the constraint: `compileSdk` ≥ 36 and a Compose BOM that does not require a higher compileSdk than
  the host's.
- `minSdk 24` on the modules is below AIP936's 26 — that direction is fine (a library may be lower).
  State the rule so the automation does not "fix" it.
- Three host-facing facts that are easy to get wrong and expensive to debug: the
  `com.google.ar.core` = `optional` meta-data arrives from the module's manifest (a host that wants
  `required` must `tools:replace`); the `CAMERA` permission also arrives from the module manifest
  but must be **requested at runtime by the host**; and once `CAMERA` is declared, the photo
  module's take-photo option needs that grant too (`ACTION_IMAGE_CAPTURE` behavior).
- `:ar-measure-photo` alone (with `:ar-measure-common`) is a supported configuration — that is the
  whole reason the split exists, and likely how a host with no AR ambitions starts.

## Requirements

Functional — each README covers:
1. One-paragraph "what this module gives you".
2. Which other modules it requires (`common` always; `ar` and `photo` never each other).
3. `settings.gradle.kts` line(s) + `build.gradle.kts` `implementation(project(...))` line(s).
4. The exact `gradle/libs.versions.toml` block to add.
5. Host build requirements: `compileSdk` ≥ 36, `minSdk` ≥ 24, Java 17, Compose enabled, AGP with
   built-in Kotlin support **or** the `kotlin-android` plugin (state which this repo uses and why
   applying both is an error here).
6. Manifest/permission story: what merges in automatically, what the host must do itself.
7. Every public symbol with its real signature and a copy-pasteable call example.
8. R8/ProGuard: nothing required (no reflection beyond the manifest-declared provider, which R8
   keeps automatically) — stated explicitly so automation does not invent rules.
9. A "verify the integration" checklist (build command + on-device checks).
10. Known limitations pointing at the source of truth, not re-explaining it.

Non-functional:
- No claim in a README that is not true of the code as merged in phase 06 — verify each snippet
  against the actual file before writing it.
- No competitor app named (repo-wide reporting convention, handoff §15.4).

## Architecture

```
ar-measure-common/README.md   ~40 lines  types + why it is always required
ar-measure-ar/README.md       ~120 lines the big one: ARCore/AR-availability/permission story
ar-measure-photo/README.md    ~90 lines  store injection, FileProvider, no-ARCore guarantee
```

`:ar-measure-ar/README.md` must contain, verbatim-correct:

```kotlin
// availability, before showing any AR screen
when (ArMeasureKit.checkAvailability(context)) {
    ArAvailability.Ready -> { /* show a screen below */ }
    ArAvailability.NeedsInstall -> { ArMeasureKit.requestInstall(activity) }  // returns true if redirected
    ArAvailability.Checking -> { /* ARCore's async first call — re-poll shortly */ }
    ArAvailability.Unsupported -> { /* hide the AR entry points */ }
}

ArMeasureRulerScreen(unit = LengthUnit.Metric, onResult = { d: MeasurementResult.Distance -> }, onClose = { navBack() })
ArMeasureBoxScreen(onResult = { b: MeasurementResult.Box -> }, onClose = { navBack() })
ArMeasureCylinderScreen(onResult = { c: MeasurementResult.Cylinder -> }, onClose = { navBack() })
LevelScreen(onClose = { navBack() })
```

`:ar-measure-photo/README.md` must contain:

```kotlin
// the host owns the store — module never constructs one
val store = remember { CustomReferenceStore(context) }          // or: single { CustomReferenceStore(androidContext()) } with Koin
PhotoMeasureScreen(referenceStore = store, onResult = { p: MeasurementResult.Photo -> }, onClose = { navBack() })
```

plus a one-line Koin snippet (AIP936 uses Koin, not Hilt) and the statement that the module takes
**zero** DI-framework dependency.

## Related Code Files

Create:
- `ar-measure-common/README.md`
- `ar-measure-ar/README.md`
- `ar-measure-photo/README.md`

Modify:
- `README.md` (root) — link to the three module READMEs from the module-layout section added in
  phase 06

Delete: none.

## Implementation Steps

1. Re-read the final `build.gradle.kts` of each module and transcribe its dependency list into that
   module's README as the catalog block to add. Do not write the block from this plan — write it
   from the file as it exists.
2. Re-read the final public declarations (the phase 06 grep output) and document each with its real
   signature. Any mismatch between a README and the code is a bug in the README.
3. Write `ar-measure-common/README.md`: `LengthUnit`, `MeasurementResult` (all 4 variants, noting
   all lengths are metres and `unit` is a display preference), `drawLabelPill`; state that it is
   required by both feature modules and has no Android/AR dependency.
4. Write `ar-measure-ar/README.md` with the sections from Requirements, including:
   - the manifest entries that merge in (CAMERA, both `uses-feature required=false`, the
     `com.google.ar.core` = `optional` meta-data) and the `tools:replace` note for a host wanting
     `required`
   - "the host must request `CAMERA` at runtime; the module never prompts"
   - the availability snippet above
   - the note that a first cold mount of any AR screen intentionally waits ~2 s showing a
     "getting the camera ready" hint (it is the fix for a real device-specific ARCore race — point
     at handoff §10, do not re-explain)
   - resources are `armeasure_`-prefixed; nothing in the host can collide
5. Write `ar-measure-photo/README.md` including:
   - "requires `:ar-measure-common` only — works with no ARCore anywhere in the app"
   - the store-injection snippet + Koin one-liner
   - the FileProvider: authority `${applicationId}.armeasure.fileprovider`, declared by the module,
     no host manifest work needed, and it will not conflict with the host's own FileProvider
   - the `ACTION_IMAGE_CAPTURE` caveat when the host declares `CAMERA`
   - prefs file name `vn.apero.armeasure.photo.custom_reference_objects`
   - the gallery path needs no permission (photo picker)
6. Add the "verify the integration" checklist to each README: the host-side build command, and the
   on-device checks that prove wiring (AR: reticle appears; Photo: pick a photo, auto-fit, read a
   number; take-photo option works).
7. Dry-run the AR and photo READMEs against `AIP936-AIHomeDesign` **on paper only** — read its
   `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml` and one module's
   `build.gradle.kts`, and confirm every instruction maps to a real file and no alias/version
   conflicts with what is already there. Record any conflict found (e.g. a different Compose BOM or
   AGP) as an explicit "host compatibility" note in the README. **Do not modify AIP936.**
8. Link the three READMEs from the root `README.md`.
9. Commit: `docs: add per-module integration guides for the ar-measure modules`.

## Todo List

- [ ] Dependency blocks transcribed from the actual `build.gradle.kts` files
- [ ] Public signatures transcribed from the actual sources
- [ ] `ar-measure-common/README.md`
- [ ] `ar-measure-ar/README.md` (manifest + permission + availability + warm-up note)
- [ ] `ar-measure-photo/README.md` (store injection + FileProvider + no-ARCore guarantee)
- [ ] Verify-the-integration checklist in all three
- [ ] Paper dry-run against AIP936; compatibility notes recorded; AIP936 untouched
- [ ] Root `README.md` links the three
- [ ] Commit

## Success Criteria

- A reader (or automation) with only these three READMEs can integrate the modules into a fresh
  Compose app: no module source reading required, no guessing at versions, no missing manifest step.
- Every code snippet compiles against the merged code as-is (signatures checked in step 2).
- The photo-only configuration (`common` + `photo`, no `ar`) is documented as supported.
- Root README links all three.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| README drifts from code and the later skill automates a wrong instruction | Steps 1–2 mandate transcribing from the files, not from this plan; phase 06 runs first so the code is final |
| Version-catalog aliases missing in the host → cryptic Gradle failure | Exact TOML block in each README, with this repo's pinned versions and the reason for each pin |
| Host on a newer Compose BOM / AGP | Explicit "host compatibility" section from the step 7 dry-run |
| Automation flips `uses-feature required` to `true` and silently shrinks Play reach | Called out in the AR README with the `tools:replace` mechanics |
| README turns into a design document | Cap each at the line budget in Architecture; link the architecture record and handoff report instead of restating them |

## Security Considerations

- Document the permission model plainly: the module declares `CAMERA`, the **host** requests it, and
  the host is responsible for its own rationale UI and privacy-policy disclosure.
- Document that the photo module persists only a label plus two lengths in app-private prefs, and
  that nothing (measurements, photos, device info) leaves the device — a host's privacy review will
  ask, and a wrong answer in a README becomes a wrong answer in a store listing.
- Document that the FileProvider exposes only the `camera-capture/` cache subdirectory and is
  `exported="false"`.

## Next Steps

- Out of scope for this plan: the `skill-creator` skill that consumes these READMEs to integrate the
  modules into `AIP936-AIHomeDesign`. These three files are that step's input contract.
</content>
