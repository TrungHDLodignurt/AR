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

**Why:** worth flagging because this is exactly the "wrong basis or dropped sign renders a
plausible-looking result that only a test catches" risk the test files' own docstrings call out.

**How to apply:** when reviewing new geometry/gating code here, explicitly compute which
branch each test actually hits (don't just read test names) and call out any conditional
branch or extracted shared class left completely untested.
