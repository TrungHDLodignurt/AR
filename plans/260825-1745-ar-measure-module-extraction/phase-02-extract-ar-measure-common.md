# Phase 02 — Extract `:ar-measure-common`

## Context Links

- Spec: architecture record §3 (`:ar-measure-common` layout), §4 (public API), §5 (the
  `LengthUnit` cross-feature import that blocks the split), §9 (testability)
- Depends on: [phase 01](phase-01-gradle-module-scaffolding.md)
- Blocks: [phase 03](phase-03-extract-ar-measure-ar.md), [phase 04](phase-04-extract-ar-measure-photo.md)

## Overview

- **Priority:** P1 (both feature modules depend on it)
- **Status:** done
- **Effort:** 1h
- Move the genuinely shared pieces — `LengthUnit` **and the length formatters**, `LabelPill` — into
  `:ar-measure-common`, and add the new `MeasurementResult` sealed interface.

## Key Insights

- `LengthUnit` does not live in a file of its own today: it sits at
  `measure/MeasureMath.kt:111`, wedged between `formatImperial` (105) and `formatLength` (113).
  Extraction is a **partial file split**, not a whole-file move.
- **The formatters must come too.** `photomeasure/PhotoQuadCanvas.kt:33` does
  `import vn.quancua.artapemeasure.measure.formatLength`. Moving only the enum leaves
  `:ar-measure-photo` → `:ar-measure-ar` alive, which is the exact dependency this split exists to
  break. So `formatMeters`, `formatImperial`, `formatLength` move with it. Everything else in
  `MeasureMath.kt` (`Vec3`, `measureDistanceMeters`, `intersectRayPlane`, `nearestIndexWithin`,
  `measurePointsMoved`, …) is AR-only and stays behind for phase 03.
- `MeasurementResult` is **new code** — the only genuinely new type in the whole extraction. It
  exists so a host gets a result without importing AR internals. Per the locked decisions,
  `Photo` carries `distanceMeters` + `unit` only.
- `ui/LabelPill.kt` is already a clean, dependency-light `DrawScope` extension used by both
  overlay families (`MeasureOverlay.kt:16`, `ShapeOverlay.kt:12`, `PhotoQuadCanvas.kt:34`,
  `QuadEditorCanvas.kt:27`) — a straight file move, `public` stays `public`.
- 6 of `MeasureMathTest`'s 28 tests are pure formatting tests (the `metric format …` /
  `imperial format …` block, currently lines 64–98). They move with their subject.

## Requirements

Functional:
- `vn.apero.armeasure.common.domain.LengthUnit` + `formatLength`/`formatMeters`/`formatImperial`
  available to both feature modules.
- `vn.apero.armeasure.common.domain.MeasurementResult` exists with all 4 variants exactly as
  specified.
- `vn.apero.armeasure.common.ui.drawLabelPill` available to both feature modules.
- 6 formatting tests pass in `:ar-measure-common:testDebugUnitTest`.

Non-functional:
- Zero `android.*`, `com.google.ar.*` imports in `common.domain`. `common.ui` may use Compose only.
- Every symbol in this module is intentionally `public` — it is 100% public API by design.

## Architecture

```
ar-measure-common/src/main/java/vn/apero/armeasure/common/
  domain/LengthUnit.kt          enum LengthUnit + formatMeters/formatImperial/formatLength (moved)
  domain/MeasurementResult.kt   NEW sealed interface: Distance | Box | Cylinder | Photo
  ui/LabelPill.kt               DrawScope.drawLabelPill (moved verbatim, package line changed)
ar-measure-common/src/test/java/vn/apero/armeasure/common/
  domain/LengthUnitTest.kt      the 6 formatting tests, assertions copied verbatim
```

`MeasurementResult.kt` content (exact — do not add fields):

```kotlin
sealed interface MeasurementResult {
    data class Distance(val meters: Float, val unit: LengthUnit) : MeasurementResult
    data class Box(val lengthU: Float, val lengthV: Float, val height: Float, val unit: LengthUnit) : MeasurementResult
    data class Cylinder(val radius: Float, val height: Float, val unit: LengthUnit) : MeasurementResult
    data class Photo(val distanceMeters: Float, val unit: LengthUnit) : MeasurementResult
}
```

## Related Code Files

Create:
- `ar-measure-common/src/main/java/vn/apero/armeasure/common/domain/LengthUnit.kt`
- `ar-measure-common/src/main/java/vn/apero/armeasure/common/domain/MeasurementResult.kt`
- `ar-measure-common/src/test/java/vn/apero/armeasure/common/domain/LengthUnitTest.kt`

Move (`git mv`, then edit the `package` line):
- `app/src/main/java/vn/quancua/artapemeasure/ui/LabelPill.kt`
  → `ar-measure-common/src/main/java/vn/apero/armeasure/common/ui/LabelPill.kt`

Modify:
- `app/src/main/java/vn/quancua/artapemeasure/measure/MeasureMath.kt` — delete the 4 moved
  declarations (lines ~93–118: `formatMeters`, `formatImperial`, `LengthUnit`, `formatLength`) and
  the now-unused `java.text.NumberFormat`/`java.util.Locale` imports if nothing else uses them.
- `app/src/test/java/vn/quancua/artapemeasure/measure/MeasureMathTest.kt` — delete the 6 moved
  formatting tests (and the `java.util.Locale` import if unused afterwards).

Delete: none (`:app` breakage from these edits is expected and resolved in phase 03/05 — see
"Risk Assessment").

## Implementation Steps

1. `git mv app/src/main/java/vn/quancua/artapemeasure/ui/LabelPill.kt ar-measure-common/src/main/java/vn/apero/armeasure/common/ui/LabelPill.kt`;
   change its `package` to `vn.apero.armeasure.common.ui`. No other edit — keep the KDoc verbatim.
2. Create `domain/LengthUnit.kt` with `package vn.apero.armeasure.common.domain`; **cut** (not copy)
   `formatMeters`, `formatImperial`, `enum class LengthUnit`, `formatLength` out of
   `measure/MeasureMath.kt` including their KDoc comments verbatim, plus the `NumberFormat`/`Locale`
   imports they need.
3. Create `domain/MeasurementResult.kt` with the exact block above. KDoc each variant with one line
   naming the tool that produces it (Distance ← ruler, Box/Cylinder ← shape screens, Photo ←
   photo-reference screen) and that all lengths are metres regardless of `unit` (`unit` is a display
   preference, not a conversion that has already been applied).
4. Create `domain/LengthUnitTest.kt` (`package vn.apero.armeasure.common.domain`) and move the 6
   formatting tests into it, assertions and test names byte-identical. Keep the imports they use
   (`assertEquals`, `Test`, `Locale`).
5. Remove those 6 tests from `MeasureMathTest.kt`; leave the remaining 22 untouched.
6. **Verification gate (blocking):**
   `./gradlew :ar-measure-common:compileDebugKotlin :ar-measure-common:testDebugUnitTest`
   — compile green, **6 tests pass**. Do not run project-wide `compileDebugKotlin`: `:app` is
   expected to be red from step 2 onward until phase 05.
7. Sanity grep: `grep -rn "android\.\|com\.google\.ar" ar-measure-common/src/main/java/vn/apero/armeasure/common/domain` returns nothing.
8. Commit: `refactor: extract LengthUnit, length formatting and LabelPill into :ar-measure-common`.

## Todo List

- [x] `LabelPill.kt` moved with `git mv`, package line updated
- [x] `LengthUnit.kt` created; the 4 declarations cut out of `MeasureMath.kt`
- [x] `MeasurementResult.kt` created with exactly 4 variants, `Photo` minimal
- [x] `LengthUnitTest.kt` created with the 6 formatting tests verbatim
- [x] Those 6 tests removed from `MeasureMathTest.kt` (22 remain)
- [x] Verification gate green (module compile + 6 tests)
- [x] `domain` package proven free of `android.*` / `com.google.ar.*`
- [x] Commit

## Success Criteria

- `:ar-measure-common:testDebugUnitTest` reports 6 tests, 0 failures.
- `MeasureMathTest.kt` now has 22 `@Test` functions; 22 + 25 + 7 + 6 + 3 + 4 = 67 preserved.
- No formatter or `LengthUnit` declaration remains anywhere under `app/`.
- `MeasurementResult` compiles with no dependency beyond `LengthUnit`.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| `:app` stops compiling mid-phase (it still imports the moved symbols) | Expected and planned: gates are module-scoped until phase 05. Do not "fix" `:app` here — phase 03 moves its AR sources out wholesale anyway |
| Accidentally copying instead of cutting → two `LengthUnit` enums | Step 7's grep + the phase-06 sweep for `vn.quancua` leftovers catch it; also a duplicate would break `:app` compile in phase 05 loudly |
| Losing the formatter KDoc (explains the mm-precision and locale rules) | Explicit "verbatim" instruction in steps 2 and 4 |
| Someone extends `MeasurementResult.Photo` with quad corners | Locked decision #2 — `Photo` stays minimal; call it out in review |

## Security Considerations

- No permission, storage, or network surface in this module. `MeasurementResult` carries plain
  floats — no user content, nothing to leak into logs.
- Module is `api`-exposed to consumers by design; no `internal` boundary to protect here.

## Next Steps

- Phases 03 and 04 both unblock. They are independent — run in either order or in parallel.
</content>
