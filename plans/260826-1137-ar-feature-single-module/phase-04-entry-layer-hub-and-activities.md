# Phase 04 — Entry layer: hub composable, module-owned Activities, availability gate

## Context Links

- [Plan overview](plan.md) · depends on [phase 01](phase-01-merge-into-single-ar-feature-module.md)
- House pattern: [`researcher-260826-0900-feature-video-module-wrapping-pattern.md`](../reports/researcher-260826-0900-feature-video-module-wrapping-pattern.md)
  §1 (Activity + `start()`), §2 (nested Activities + Intent extras), §7 (manifest-declared, host
  declares nothing), §9 "Do NOT copy" #2 (no `:core` dependency)
- Design: `suhME` SCR-14 hub — 2 cards + a BottomNav instance `yCnt6` that is a **ref** to the
  host's own `dNXIJ` component

## Overview

- **Priority:** P1 — builds the navigable shell every later phase is verified through.
- **Status:** pending
- **Effort:** 3h

The module gets its real entry surface: one hub composable the host embeds in its own tab, and
module-owned full-screen Activities for the deep screens. Public API shrinks to 3 symbols.

## Key Insights

1. **The design itself confirms decision 4.** `suhME`'s bottom nav is `yCnt6`, a *ref* to `dNXIJ` —
   a component that belongs to the host's design system, showing HOME / EXPLORE / **MEASURE**
   selected. The hub is a tab root, so it draws **no** bottom bar and needs **no** back button. If
   the module drew the bar it would have to know the host's tabs, icons and selected state — the
   exact coupling that forces `trung-apply-feature-video` to ship a whole `core-kernel-contract.md`.
2. **`requestInstall` cannot live in the hub.** It navigates to the Play Store and the *return trip
   is another `onResume`* — of the **host's** Activity, which we do not own. So split it: the hub
   only calls the read-only `checkAvailability` to decide whether to show the AR card; the
   module-owned `ArCameraActivity` owns `requestInstall`, the `onResume` re-check, the bounded
   re-poll, the CAMERA permission request, and the Unsupported / permission-denied screens.
3. **Entering the Photo path tears the AR session down for free.** Because the AR camera is a
   separate Activity, backing out of it finishes it, which destroys the composition, which destroys
   `rememberEngine()`'s Engine and the ARCore Session with it. Decision 7's "Photo should tear AR
   down" therefore needs **no teardown code at all** — it is a consequence of the Activity boundary.
   The one requirement is that `ArCameraActivity` must not be `singleTask`/retained.
4. **Nothing is returned to the host** (decision 5), so no `ActivityResultContract`, no `setResult`,
   no `@Parcelize` on `MeasurementResult`. `MeasurementResult` becomes `internal`.
5. **The only port that has to cross an Activity boundary is the image saver**, and there is no DI
   framework in this repo (verified: zero Koin/Hilt references). A composable parameter cannot reach
   an Activity launched later, so the port needs a process-scoped holder. One `object` with one
   nullable `var`, defaulting to the module's own implementation when unset.
6. **Kotlin `internal` classes are public at the bytecode level**, so a manifest-declared
   `internal class ArCameraActivity` still resolves. That keeps them out of the documented API while
   the manifest works. Verify at `assembleDebug` — if AGP rejects it, fall back to `public` with a
   "do not launch directly" KDoc.
7. **`:app` becomes the demo host, not the product.** It should mimic AIP936's shape — a
   `MainContract.Tab`-style enum with a MEASURE tab and one `when` branch calling `ArMeasureHub()` —
   so the integration contract is proven in-repo before anyone touches AIP936.
8. The design's hub cards declare `height: fill_container` inside a `fill_container` column, so each
   stretches to ≈237.5px around ~88px of content. That is a resize artefact. **Implement hug
   height.** Same for the two dead spacer frames `WoBUn` (32) and `Ppyrg` (24).

## Requirements

**Functional**
- `ArMeasureHub()` — a tab-root composable: header, 2 cards, no bottom bar, no back affordance.
- AR Measure card hidden when `ArAvailability.Unsupported`; Picture Measure card always shown
  (decision 6).
- `ArCameraActivity` and `ArPhotoActivity` launch full-screen from the hub, declared in the module's
  own manifest, portrait, `start(context)` companions.
- `ArCameraActivity` owns: `requestInstall`, the `onResume` availability re-check, the bounded
  200ms×15 re-poll, the CAMERA permission request, and the Unsupported / denied screens.
- `:app` renders its own 2-tab nav with a MEASURE tab whose branch is `ArMeasureHub()`.
- Public API is exactly: `ArMeasureHub`, `ArMeasureConfig`, `MeasurementImageSaver`.

**Non-functional**
- All hub strings from `strings.xml`, `armeasure_` prefix, English default (decision 14).
- Cards ≥48dp tall (they are ~88); chevron and card tap share one 48dp+ target — the chevron is
  decoration, the whole card is the target.
- Hub contrast: card title `#1A1D1F` on `#FFFFFF` and desc `#5C6166` on `#FFFFFF` both already pass
  (5.7:1). Do not put text in `#8A9A5B` (3.05:1) — the badge icon may stay olive.

## Architecture

Public surface, all in `vn.apero.armeasure`:

```kotlin
@Composable
fun ArMeasureHub(modifier: Modifier = Modifier)

fun interface MeasurementImageSaver {
    suspend fun save(bitmap: Bitmap, fileName: String): Uri?
}

object ArMeasureConfig {
    /** null = the module saves to Pictures/<app label> itself. Set from Application.onCreate. */
    var imageSaver: MeasurementImageSaver? = null
}
```

Everything else becomes `internal`: `ArMeasureKit`, `ArAvailability`, `MeasurementResult`,
`LengthUnit`, `CustomReferenceStore`, `LabelPill`/`drawLabelPill`, and all three
`ArMeasure*Screen` composables.

Internal entry classes:

```kotlin
internal class ArCameraActivity : ComponentActivity() {
    companion object {
        fun newIntent(context: Context): Intent
        fun start(context: Context)
    }
}
internal class ArPhotoActivity : ComponentActivity() {
    companion object { fun newIntent(context: Context): Intent; fun start(context: Context) }
}
```

Navigation model, copied from `:feature-video` §2 — **nested Activities, no NavHost**. The Android
back stack is the nav model, so the module owns no back stack and the host's back button is correct
at every depth. Photo sub-screens (reference grid → editor) are added in phases 07/08 either as
in-Activity state or as further Activities; the grid is the `ArPhotoActivity` root.

Availability gate, `internal`:

```kotlin
// ar/presentation/host/ArAvailabilityGate.kt
@Composable internal fun rememberArAvailability(): ArAvailability   // bounded 200ms x 15 re-poll
```

Used read-only by the hub. `ArCameraActivity.onResume` keeps the existing
`if (!ArMeasureKit.requestInstall(this)) arAvailability = ArMeasureKit.checkAvailability(this)`
pattern verbatim from today's `MainActivity`.

Hub layout (design values; sizes are dp, colours are the design tokens):

| Element | Spec |
|---|---|
| Screen | `bgPrimary #F4F4F2`, vertical |
| Header | pad `[24,20]`, gap 4 — title `armeasure_hub_title` Inter 22/600 ls −0.2 `#1A1D1F`; subtitle 14/400 `#5C6166` |
| Cards column | pad `[0,20]`, gap 18, **hug** card heights |
| Card | `#FFFFFF`, r14, stroke `#1A1D1F24` 1px, pad 16, gap 14, `alignItems center` |
| Badge | 56×56, r28, `#8A9A5B1F`, icon 26 `#8A9A5B` |
| Card text | title 16/600 `#1A1D1F`; desc 13/400 `#5C6166`, gap 2 |
| Chevron | 20, `#9BA1A6`, decorative only |

## Related Code Files

**Create**
- `AR_feature/.../ar/presentation/host/ArMeasureHub.kt` (public composable + the 2 cards)
- `AR_feature/.../ar/presentation/host/ArCameraActivity.kt`
- `AR_feature/.../photo/presentation/ArPhotoActivity.kt`
- `AR_feature/.../ar/presentation/host/ArAvailabilityGate.kt` (internal, the re-poll)
- `AR_feature/.../ar/presentation/host/GateMessages.kt` (internal `ArUnsupported`,
  `CameraDenied`, `CenteredMessage` — moved out of `:app`)
- `AR_feature/.../ArMeasureConfig.kt` (public: `ArMeasureConfig` + `MeasurementImageSaver`)
- `AR_feature/.../common/ui/ArMeasureTheme.kt` (internal; dark scheme for the camera Activities,
  light for the hub/photo screens — the module must not depend on the host's theme)

**Modify**
- `AR_feature/src/main/AndroidManifest.xml` — declare both Activities, `screenOrientation="portrait"`,
  `configChanges` matching today's `:app` entry
- `AR_feature/src/main/res/values/strings.xml` — hub title/subtitle, 2 card titles, 2 card
  descriptions, AR-unsupported title/body, camera-denied title/body (10 strings)
- `ArMeasureKit.kt`, `MeasurementResult.kt`, `LengthUnit.kt`, `LabelPill.kt`,
  `CustomReferenceStore.kt`, the three `ArMeasure*Screen` entries — `public` → `internal`
- `app/src/main/java/vn/quancua/artapemeasure/MainActivity.kt` — gutted to a theme + a 2-tab nav +
  `ArMeasureHub()`; the permission launcher, the availability poll, the `onResume` dance and the 3
  gate composables all leave
- `app/src/main/java/vn/quancua/artapemeasure/ui/AppTabBar.kt` — becomes a 2-tab demo nav
  (`HOME`, `MEASURE`) mirroring AIP936's `aip-bottom-nav.kt` shape
- `app/src/main/res/values/strings.xml` — delete `ar_unsupported_title` / `ar_unsupported_body`
  (they move into the module)

## Implementation Steps

1. Add `ArMeasureConfig` + `MeasurementImageSaver` (the port is declared here now; its default
   implementation lands in phase 08 — do **not** stub it, just leave `imageSaver` nullable and
   unreferenced until then).
2. Add `ArMeasureTheme` — a thin `MaterialTheme` wrapper, dark for camera screens, light
   (`bgPrimary` background) for hub/photo. Do not create a `:core`; do not depend on the host theme.
3. Move `ArUnsupported` / `CameraDenied` / `CenteredMessage` from `MainActivity.kt` into
   `GateMessages.kt` as `internal`, and their strings into the module's `strings.xml`.
4. Add `rememberArAvailability()` with the bounded re-poll, lifted verbatim from
   `MainActivity.AppRoot`'s `LaunchedEffect` (200ms, 3000ms budget, falls through to `Unsupported`).
5. Write `ArCameraActivity`: `enableEdgeToEdge()`, the CAMERA permission launcher, the `onResume`
   `requestInstall`/`checkAvailability` pair, and a body that renders — for now — today's
   `ArMeasureRulerScreen`. The tool sheet and the shared session arrive in phases 05/06.
6. Write `ArPhotoActivity`: constructs `CustomReferenceStore(this)` itself and renders
   `PhotoMeasureScreen`. Note this reverses the old locked decision "the host always constructs the
   store" — with the module owning its own Activity there is no host to construct it, and the store
   was already `internal`. Record that in phase 10's README.
7. Write `ArMeasureHub`: header + 2 cards to the spec table, tapping them calls
   `ArCameraActivity.start(context)` / `ArPhotoActivity.start(context)`. Hide the AR card when
   `rememberArAvailability() == Unsupported`.
8. Declare both Activities in the module manifest.
9. Narrow the public API: add `internal` to the symbols listed above; the compiler will find every
   `:app` reference that breaks, which is the point.
10. Rewrite `:app`: `MainActivity` = `ArMeasureTheme`-less (`:app` keeps its own
    `MaterialTheme(darkColorScheme())` only if it wants) + a 2-tab nav + `ArMeasureHub()`. Delete the
    3 gate composables and the 2 strings.
11. Gate, including the on-device navigation walk.

## Todo List

- [ ] `ArMeasureConfig` + `MeasurementImageSaver` (port declared, no default yet)
- [ ] `ArMeasureTheme` (internal, no `:core`)
- [ ] `GateMessages.kt` + 4 gate strings moved out of `:app`
- [ ] `rememberArAvailability()` with the bounded 200ms×15 re-poll
- [ ] `ArCameraActivity` (permission + `requestInstall` + `onResume` re-check)
- [ ] `ArPhotoActivity` (constructs its own `CustomReferenceStore`)
- [ ] `ArMeasureHub` — 2 cards, hug height, no bottom bar, no back button
- [ ] AR card hidden on `Unsupported`; Picture Measure always shown
- [ ] Declare both Activities in the module manifest (portrait)
- [ ] Narrow public API to `ArMeasureHub` / `ArMeasureConfig` / `MeasurementImageSaver`
- [ ] `:app` → 2-tab demo host calling `ArMeasureHub()`
- [ ] Delete `ar_unsupported_*` from `:app` strings
- [ ] 10 new `armeasure_*` strings, English
- [ ] Gate: `compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease`
- [ ] On-device: hub → AR Measure → back → hub → Picture Measure → back → hub
- [ ] On-device: revoke CAMERA (`adb shell pm revoke`), enter AR Measure, confirm the denied screen
      and that the hub and Picture Measure still work
- [ ] On-device: confirm via `logcat`/`dumpsys` that leaving the AR Activity destroys the session
      (no ARCore frame callbacks continue)

## Success Criteria

- 86 tests still pass (this phase adds no pure logic, so it adds no tests — say so rather than
  padding the count).
- `assembleRelease` green; both Activities present in
  `app/build/intermediates/merged_manifest/**/AndroidManifest.xml` (`grep -c ArCameraActivity` = 1).
- `git grep -n '^\(public \)\?\(@Composable\s*\)\?fun \|^object \|^enum class \|^sealed interface ' AR_feature/src/main`
  cross-checked by hand: exactly 3 public symbols outside the two manifest-declared Activities.
- `:app`'s `MainActivity.kt` is under ~50 lines and imports nothing from
  `vn.apero.armeasure.ar.presentation.*` or `…photo.presentation.*` — only `ArMeasureHub`.
- On-device: the full navigation walk above, plus the CAMERA-revoked walk.
- On-device: the AR session is gone after backing out (no continuing frame callbacks).

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| AGP/manifest rejects an `internal` Activity class name | medium | verified by `assembleDebug`; fall back to `public` + a "do not launch directly" KDoc and say so in the README |
| `ArMeasureConfig`'s mutable global is a smell and a test-isolation hazard | certain | it is the only mechanism that crosses an Activity boundary without DI; documented, single field, nullable, module-default when unset. Alternative (host `Application` implements an interface) noted in Unresolved Questions |
| Hub needs `requestInstall` after all (device with `NeedsInstall`) | medium | hub shows the AR card for `NeedsInstall` too; `ArCameraActivity` runs the install flow on entry. Verify on a device without ARCore installed |
| `ArPhotoActivity` constructing its own store contradicts a previously locked decision | certain | deliberate; there is no host to construct it once the module owns the Activity. Record the reversal in phase 10 |
| The module now ships a theme, so a host's theme is ignored inside module screens | medium | intended (the camera screens must be dark regardless); documented in the README as a difference from `:feature-video`'s `:core`-injected theme |
| `enableEdgeToEdge` + `windowInsetsPadding` regressions in the new Activities | medium | the existing `MeasureTopBar`/`AppTabBar` inset handling is copied, not reinvented; check on-device that chrome clears the status/nav bars |

## Security Considerations

- CAMERA is requested by the module, in `ArCameraActivity`, at the moment it is needed — never at
  app launch and never from the hub. The manifest declaration already lives in the module.
- **Verify on-device:** because the module *declares* `android.permission.CAMERA`, the photo path's
  "take a photo" option (`ActivityResultContracts.TakePicture`) may require that permission to be
  *granted*, not merely declared. If so, `ArPhotoActivity` must request it before launching the
  capture intent, or the option must be hidden when it is denied. Do not assume either way.
- `ArMeasureConfig.imageSaver` is a process-global mutable hook. A host must set it only from
  `Application.onCreate`. Document that a malicious/incorrect implementation receives the user's
  photo bitmap — the port is a trust boundary and the README must say so.
- No new permissions beyond what the merged manifest already declares. No network, no export.
- The two Activities are `exported="false"` by default (no `intent-filter`) — do **not** add one.

## Next Steps

- Phase 05 replaces `ArCameraActivity`'s body with the single shared ARCore session.
- Phases 07/08 build out `ArPhotoActivity`'s screens.
- Phase 10 documents the 3-symbol API and the AIP936 wiring snippet.
