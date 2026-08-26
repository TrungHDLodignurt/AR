---
title: R8 / release hardening audit — ar-measure modules
date: 2026-08-26
status: verified-clean
commit: b648950
---

# R8 audit: do the ar-measure modules survive minify + resource shrinking?

**Answer: yes, with zero keep rules of our own.** Verified empirically, not reasoned about.

## Why this was audited

`:app` release had `isMinifyEnabled = false`, so R8 had **never** run over this code. First target
host (`AIP936-AIHomeDesign`) ships `isMinifyEnabled = true` + `isShrinkResources = true` → the
integration would have been R8's first contact with these modules, in release only, where failures
are silent.

Not hypothetical: sibling module `:feature-video` (ADA903) shipped a release-only silent breakage of
exactly this shape — R8 class-merging resolved an API-31 symbol on old devices, video export died
with a generic toast and nothing in logcat. Its fix was a module-shipped `consumer-rules.pro`.

## What changed

`app/build.gradle.kts` release block now runs `isMinifyEnabled = true` + `isShrinkResources = true`
with `proguard-android-optimize.txt` + `app/proguard-rules.pro`. So this repo now tests what hosts
actually do. `app/proguard-rules.pro` is a deliberately empty scaffold — a comment there records
that rules belong in the owning module's `consumer-rules.pro`, never in `:app`.

**No module ships a `consumer-rules.pro`** — verified unnecessary, not assumed. An empty one per
module would be needless ceremony.

## Risk surface, each verified

| Risk | Verdict | Evidence |
|---|---|---|
| SceneView/Filament 4.31.0 JNI | covered by the AARs themselves | both `arsceneview`/`sceneview` AARs ship root `proguard.txt` (Filament JNI, kotlin-math, collision, GMS FusedLocation keeps). Their exact rule text found in `app/build/outputs/mapping/release/configuration.txt` → AGP auto-merges them |
| ARCore `Plane::class.java` class-token lookup (`MeasureFrameLoop.kt:51`, `ShapeFrameLoop.kt:60`) | safe | `com.google.ar:core` 1.54.0 AAR ships a whole-package `-keep public class com.google.ar.core.** {*;}`. `mapping.txt` shows `com.google.ar.core.Plane -> com.google.ar.core.Plane:` (unrenamed) |
| `isShrinkResources` vs the 11 `armeasure_*` strings | none shrunk | `resources.txt` lists all 11 + `xml/armeasure_file_paths` as reachable — all referenced via static `R.string.armeasure_*`, which the shrinker's reachability analysis detects |
| `ArMeasurePhotoFileProvider` | kept | manifest-declared → R8 auto-keeps by name. Unrenamed in `mapping.txt`; on-device `dumpsys package providers` shows authority `…armeasure.fileprovider` |
| `CustomReferenceStore` serialization | **no keep rule needed** | hand-rolled `org.json` `JSONObject`/`JSONArray`. No Gson, no kotlinx.serialization, no reflection. R8 renamed the class (`CustomReferenceStore -> ct`) harmlessly since nothing references it by name |

Grepped all 3 modules for `Class.java` / `::class.java` / `reflect` / `Gson` /
`kotlinx.serialization` / `getIdentifier` — no other risk surface. R8 generated **no**
`missing_rules.txt`; that absence is itself a signal.

## Device verification (Pixel 6, Android 16)

Release build, zipaligned + debug-keystore-signed purely to be installable (no signing config added
to any gradle file, no credential committed):

- launches, no crash; `libfilament-jni.so` / `libgltfio-jni.so` / `libfilament-utils-jni.so` all load
- all 5 tabs tapped: no `FATAL EXCEPTION`, no `TextureNotSetException`, process survives
- ARCore native frame loop live and continuous
- Photo reference picker renders its Vietnamese strings + built-in entries (A4 paper 21x30cm,
  Payment card 5x9cm) **under R8** — exercises photo module resources + store
- FileProvider registered at `vn.quancua.artapemeasure.armeasure.fileprovider`

**Not verified (needs a human aiming at a real textured surface):** placing AR points, drawing the
3-tap Box/Cylinder shapes.

Full gate after the change: `clean compileDebugKotlin testDebugUnitTest assembleDebug
assembleRelease` all green, **67 tests** (common 6 + ar 54 + photo 7).

## Consequences for `trung-apply-ar-measure`

1. Say `:app` release now runs R8 + resource shrinking, verified clean on device — a host is no
   longer the first to hit R8 against this code.
2. Say the modules ship **no** `consumer-rules.pro` **by design (verified)**, and why: third-party
   AAR consumer rules + static resource refs + manifest auto-keep already cover everything.
3. Tell hosts to re-verify on version bumps of `com.google.ar:core` / `io.github.sceneview:*` — a
   version dropping its bundled `proguard.txt` is the actual trigger to add module-owned rules.
   Method: unzip AAR → check `proguard.txt`; grep
   `app/build/outputs/mapping/release/{configuration,resources,mapping}.txt`; on-device tab smoke +
   `dumpsys package providers`.
4. State `CustomReferenceStore` uses hand-rolled `org.json`, not Gson — stops a future integrator
   adding a speculative serialization keep rule.
5. Verification step must include `assembleRelease`, not only `assembleDebug` — a compile-only check
   cannot catch R8 breakage (the `feature-video` lesson).

## Unresolved

- Not tested against a host's own possibly-stricter R8 config (AIP936 was explicitly off-limits this
  task). Spot-check there with the same method when integration actually happens.
