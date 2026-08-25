---
title: "AR-Measure feature extraction into 3 reusable Gradle library modules"
description: "Move all measure/photo-measure code out of :app into :ar-measure-common/-ar/-photo under vn.apero.armeasure, slim :app to nav only, no behavior change."
status: pending
priority: P2
effort: 11h
branch: feature/photo-reference-measure
tags: [refactor, gradle, multi-module, arcore, compose, reuse]
created: 2026-08-25
---

# AR-Measure module extraction

Turns this single-module app into a multi-module repo: 3 library modules hold all feature code,
`:app` keeps only `MainActivity` + tab nav. **Restructure, not rewrite** — file moves, package
renames, and the specific fixes in the architecture record. No feature/UX change.

Architecture is already decided — primary spec
[`report-260825-1745-…-architecture-brainstorm.md`](../reports/report-260825-1745-ar-measure-module-architecture-brainstorm.md),
background [`report-260825-1703-session-handoff-box-cylinder-measure.md`](../reports/report-260825-1703-session-handoff-box-cylinder-measure.md).
Do not re-derive either.

## Phases

| # | Phase | Effort | Status | Gate |
|---|---|---|---|---|
| 01 | [Gradle module scaffolding](phase-01-gradle-module-scaffolding.md) | 1h | done | 3 empty modules assemble |
| 02 | [Extract `:ar-measure-common`](phase-02-extract-ar-measure-common.md) | 1h | done | module compile + 6 JVM tests |
| 03 | [Extract `:ar-measure-ar`](phase-03-extract-ar-measure-ar.md) | 3h | done | module compile + 54 JVM tests |
| 04 | [Extract `:ar-measure-photo`](phase-04-extract-ar-measure-photo.md) | 2h | done | module compile + 7 JVM tests |
| 05 | [Slim down `:app`](phase-05-slim-down-app-module.md) | 1.5h | done except on-device smoke test | whole project compile + 67 tests + on-device smoke |
| 06 | [Final verification + code review](phase-06-final-verification-and-review.md) | 1h | code-reviewer pass done (see `code-reviewer-260825-1835-*.md`); no `vn.quancua` leftovers confirmed | `code-reviewer` clean, no `vn.quancua` leftovers |
| 07 | [Per-module integration-guide READMEs](phase-07-module-integration-guide-readmes.md) | 1.5h | pending | 3 READMEs sufficient to automate an integration |

Phases 03 and 04 are independent of each other (only both depend on 02) — safe to run in
parallel or swap order. Everything else is strictly sequential.

## Key dependencies / ordering facts

- Nothing compiles before phase 01 (no `com.android.library` plugin alias exists yet).
- Both feature modules need `LengthUnit` + the length formatters from `:ar-measure-common`, so 02 is
  a hard prerequisite for both.
- **`:app` is knowingly non-compiling between phases 02 and 05.** Sources are `git mv`'d out before
  its wiring is rewritten, so per-phase gates use *module-scoped* Gradle tasks
  (`:ar-measure-ar:compileDebugKotlin`, `:ar-measure-ar:testDebugUnitTest`), never bare
  project-wide `compileDebugKotlin`. Whole-project green returns in phase 05.
- Test totals per module sum to today's 67 `@Test` functions exactly: common 6 (formatting) + ar 54
  (`MeasureMathTest` 22 + `ShapeMathTest` 25 + `SteadinessGateTest` 7) + photo 7 (`HomographyTest` 3
  + `QuadFromEdgesTest` 4). No test added or removed.
- On-device verification is only possible from phase 05 (no emulator path for the ARCore runtime);
  JVM tests need no device at any point.

## Decisions locked before starting (do not re-open)

1. Facade name is `ArMeasureKit`.
2. `MeasurementResult.Photo` carries `distanceMeters` + `unit` only.
3. `CustomReferenceStore` is always host-constructed and passed into `PhotoMeasureScreen`; the
   module never constructs or holds its own instance.

## Deviations from the architecture record (found while surveying the actual code)

Each is a mechanical extension of a fix the record already made, not a new design choice:

1. **`formatLength`/`formatMeters`/`formatImperial` move to `:ar-measure-common` with `LengthUnit`** —
   the record listed only the enum, but `photo/PhotoQuadCanvas.kt:33` imports `measure.formatLength`,
   so leaving the formatters in `:ar-measure-ar` keeps the exact photo→ar dependency the split
   exists to remove. Phase 02.
2. **`onClose` is `(() -> Unit)? = null`**, not a required `() -> Unit`: today's screens have no close
   affordance, so a required callback is either dead API or a back-handler that changes `:app`'s
   back-button behavior. Null = today's chrome exactly; non-null = a `✕` pill. Phase 03.
3. **`armeasure_` resource prefix + module-owned `FileProvider` subclass** — same host-collision
   reasoning the record applied to the `CustomReferenceStore` prefs key. Phases 03/04.
4. `CustomReferenceStore.loadAll`/`add` become `internal` (only the module's own picker calls them),
   keeping `ReferenceObject` internal so the public surface matches §4 literally: the constructor
   and nothing else. Phase 04.
5. Kotlin sources keep PascalCase filenames; the kebab-case rule applies to markdown/plan files.

## Out of scope

Writing the `skill-creator` skill that auto-integrates these modules into `AIP936-AIHomeDesign`.
Phase 07's READMEs are that step's input; the skill itself is a separate, later task.
