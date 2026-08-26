---
title: "AR_feature — single-module AR/Photo measure feature with the AIP936 design UI"
description: "Merge the 3 ar-measure modules into one AR_feature module, delete Level, add a 4-unit picker, undo/redo, one shared ARCore session for Distance/Box/Cylinder, and build the hub + photo flow to the AIP936 wireframes."
status: pending
priority: P2
effort: 27h
branch: feature/photo-reference-measure
tags: [refactor, gradle, single-module, arcore, compose, ui, design-implementation, reuse]
created: 2026-08-26
---

# AR_feature — one module, one shared AR session, the design UI

`:ar-measure-common` + `:ar-measure-ar` + `:ar-measure-photo` collapse into one `AR_feature/`, Level
is deleted, and the UI is built from the AIP936 wireframes on top. The measuring maths, hit-test
chain, warm-up gate and photo CV pipeline are **behaviour-preserving** — a module merge plus additive
UI, not a rewrite.

Settled inputs, do not re-derive: [the 3-module split](../260825-1745-ar-measure-module-extraction/plan.md)
+ [its record](../reports/report-260825-1745-ar-measure-module-architecture-brainstorm.md),
[the house module pattern](../reports/researcher-260826-0900-feature-video-module-wrapping-pattern.md),
[the AR-session hazard record](../reports/report-260825-1703-session-handoff-box-cylinder-measure.md) §10–§12,
[the R8 audit](../reports/report-260826-0930-r8-release-hardening-ar-measure-modules.md).

## Phases

| # | Phase | Effort | Gate |
|---|---|---|---|
| 01 | [Merge into one `AR_feature`, delete Level](phase-01-merge-into-single-ar-feature-module.md) | 2.5h | project compile + 67 tests |
| 02 | [`LengthUnit` → cm/m/inch/ft + `UnitMenu`](phase-02-four-unit-length-and-unit-menu.md) | 2h | 75 tests (6 rewritten) |
| 03 | [Undo **and redo** in all 3 state holders](phase-03-undo-redo-stacks.md) | 2.5h | 86 tests + on-device |
| 04 | [Entry layer: hub, Activities, availability gate](phase-04-entry-layer-hub-and-activities.md) | 3h | on-device nav walk |
| 05 | [ONE shared ARCore session](phase-05-one-shared-arcore-session.md) | 3.5h | 92 tests + **human on-device** |
| 06 | [AR camera UI to the design](phase-06-ar-camera-ui-to-design.md) | 3.5h | human on-device measure |
| 07 | [Reference objects: grid + add/edit/delete](phase-07-reference-object-crud.md) | 2h | 98 tests + on-device CRUD |
| 08 | [Photo measure path + save to gallery](phase-08-photo-measure-path-and-save.md) | 4h | 102 tests + saved image |
| 09 | [Final verification: 16 audits + code review](phase-09-final-verification-and-audits.md) | 2h | audits clean |
| 10 | [One `AR_feature/README.md`](phase-10-single-readme-integration-guide.md) | 1.5h | README alone suffices |
| 11 | [Update the integration skill](phase-11-update-integration-skill.md) | 1.5h | skill matches shipped code |

Strictly sequential except **03 ∥ 02** and **07 ∥ 05/06** (disjoint files).

## Ordering facts

- **`:app` is knowingly non-compiling in phase 01 only, and only mid-phase** — the three module dirs
  are `git mv`'d before `:app` and `settings.gradle.kts` are rewired, so use
  `:AR_feature:compileDebugKotlin` until step 9. Every phase *boundary*, 01's included, is green.
- 02 blocks 06 and 08: today's 2-value enum and `formatMeters()`'s hardcoded `" m"` cannot render
  `15 cm` at all.
- 04 before 05–08: it builds the navigable shell, so later phases verify through the real entry path
  on the attached Pixel 6 instead of a scratch harness.
- Tests: **67** → 67 (01) → 75 (02) → 86 (03) → 86 (04) → 92 (05) → 92 (06) → 98 (07) → **102** (08).
  Phases 04, 06, 10, 11 add none and say so rather than padding. No test is deleted; the 6 formatter
  tests are rewritten in place. Level ships no tests, so deleting it removes none.
- Every gate runs `compileDebugKotlin` + `testDebugUnitTest` + `assembleDebug` + **`assembleRelease`**
  — R8 only runs in release and its failures are silent.
- Placing AR points and drawing 3-tap shapes **cannot be scripted**: a human must aim the phone at a
  real textured surface. Phases 03 and 05–09 say so in their gates.

## Accepted costs of the one-module decision

1. **The ARCore-free photo-only consumption path is forfeited** — a deliberate reversal of §1 of the
   architecture record, taken because the user wants one `include` line.
2. **`internal` no longer separates AR from Photo.** Mitigated by keeping the `ar/` and `photo/`
   package roots plus a cross-import grep in phase 09 — a convention, not a compiler guarantee.
3. **One session config serves all three AR tools**, so it is the heaviest union.
   `DepthMode.AUTOMATIC` is pure heat cost for Box/Cylinder height (analytic ray-cast, no depth) and
   cannot be made per-tool under one session.

**Naming deviation, recorded not silently fixed:** `AR_feature/` + `include(":AR_feature")` breaks
this repo's kebab-case habit. The user named it explicitly. Namespace stays `vn.apero.armeasure` and
`resourcePrefix = "armeasure_"` is unchanged, so nothing generated is affected.

## Unresolved questions

1. Unit **persistence** (02) exceeds the letter of decision 8, added because a unit that resets per
   screen is not a "hard user choice". Confirm.
2. `WRITE_EXTERNAL_STORAGE maxSdkVersion="28"` (08) is a second permission, needed only for the API
   24–28 gallery save. Accept, or drop saving below API 29?
3. `ArMeasureConfig` is a process-global mutable `var` (04) — the only no-DI way across an Activity
   boundary. Alternative: host `Application` implements an interface.
4. `UnitBtn` shows `in` while the menu says `inch` (design `XCFlV` vs decision 8). Fine?
5. Two design screens implemented as one, twice: SCR-16/17's sheets (edit sheet `fgWMc` is an
   unparented root orphan) and SCR-23/24. Confirm.
6. AR has no terminal state in the design and frame capture is out of scope, so only the photo branch
   produces a saved artifact. Acceptable asymmetry?
7. If phase 05's thermal check throttles, the only lever is one-orientation plane finding, which
   costs wall measuring. Decide then.
8. The `.pen` on disk is stale; only the pencil MCP reads the live document. Designer should save.

## Out of scope

Modifying `AIP936-AIHomeDesign` — its wiring snippet is *documented* in phase 10, nothing more. The 6
other mock sheet entries (Angle, Polyline, Polyline smooth, Square, Poly smooth, Auto-Detection) are
not implemented and must not appear as disabled placeholders. AR frame capture to the gallery, and
any saved-measurement list.
