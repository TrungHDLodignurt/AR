---
name: testing-gap-pattern
description: Recurring test-coverage gap in this repo — new geometry/gating helpers get happy-path tests but not their edge-case branches
metadata:
  type: feedback
---

When this project adds a new pure-math helper with an internal branch (e.g. a basis function
that picks a fallback reference axis when the primary one is near-parallel), the accompanying
test file tends to cover orthonormality/sign/wraparound well but not the actual edge-case
branch that motivated the fallback code in the first place.

Concrete instance: `planeBasis()` in ShapeMath.kt picks `Vec3(0,0,1)` as reference unless the
normal's dot with Z is `>= 0.9`, in which case it falls back to `Vec3(1,0,0)`. All 19 tests in
ShapeMathTest.kt (floor/wall/diagonal normals) had `|n·Z| < 0.71`, so the `>= 0.9` fallback
branch was never exercised by any test, despite being exactly the divide-by-zero-adjacent case
the code comments call out as the risky one.

Also seen: `SteadinessGate` was extracted as shared logic (used by both the tape-measure and
box/cylinder tools) but has zero dedicated unit tests of its own — neither before nor after
the extraction — despite gating every commit action across 3 screens.

Also seen (ar-measure module extraction, commit 61160dc): `ArMeasureBoxScreen`/
`ArMeasureCylinderScreen`'s `onResult` lambda captures the composable's own `unit: LengthUnit`
parameter instead of the live `state.unit` — so if the user toggles the in-screen m/ft control
before finishing a shape, the callback reports the *original* unit while the on-screen label
already shows the toggled one. `ArMeasureRulerScreen` and `PhotoMeasureScreen` got this right
(`state.unit`). No JVM test catches it because the whole `onResult` plumbing is composable-only
(Compose UI, not exercised by JVM unit tests) — the same class of gap as the untested
`SteadinessGate` above: a plausible-looking wiring change that only a state-vs-parameter read
catches, not a test-name skim.

**Why:** worth flagging because this is exactly the "wrong basis or dropped sign renders a
plausible-looking result that only a test catches" risk the test files' own docstrings call out.

**How to apply:** when reviewing new geometry/gating code here, explicitly compute which
branch each test actually hits (don't just read test names) and call out any conditional
branch or extracted shared class left completely untested.

**Update (2026-08-26, AR_feature single-module review):** the `planeBasis()` dot≥0.9 fallback
gap above is now fixed — `ShapeMathTest.kt` (in `AR_feature/src/test/.../ar/domain/geometry/`)
explicitly exercises it with `Vec3(0.1f, 0.1f, 0.99f)`. Don't re-flag it; do keep checking new
basis/gate helpers for the same class of gap going forward.

**New instance of the broader pattern, but for string-literal/grep audits, not tests:** this
repo's own grep-based invariant audits (see [[grep-audit-blind-spots]]) can have the identical
blind spot as a test suite — a check that reads as "covers X" but its actual pattern doesn't
reach every code shape X can take.
