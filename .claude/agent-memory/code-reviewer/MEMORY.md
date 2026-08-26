# Code Reviewer Memory — ar-tape-measure

- [Project conventions: measure module](measure-module-conventions.md) — now AR_feature/vn.apero.armeasure; pure math files, anchor-detach pattern, shared gates/helpers
- [Testing gap pattern to watch for](testing-gap-pattern.md) — new basis/gate helpers ship with happy-path tests only, branch/gate itself untested (planeBasis 0.9 gap since fixed)
- [Grep-audit blind spots](grep-audit-blind-spots.md) — AR_feature's own invariant greps (no hardcoded strings, insets, etc.) can miss real violations; check the concept, not just the documented command
