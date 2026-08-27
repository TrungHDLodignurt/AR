# Phase 06 — Final verification + code review

## Context Links

- Spec: architecture record §4 (public surface is enforced by `internal`, not naming), §10 (known
  gaps carried over, deliberately not fixed here)
- Depends on: [phase 05](phase-05-slim-down-app-module.md)
- Blocks: [phase 07](phase-07-module-integration-guide-readmes.md)

## Overview

- **Priority:** P2
- **Status:** completed
- **Effort:** 1h
- Prove the extraction is complete and leak-free, then hand the diff to the `code-reviewer` agent.
  No feature work.

## Key Insights

- The three real failure modes of a move-only refactor are: (a) a file that was copied rather than
  moved, leaving two live definitions; (b) a symbol that stayed `public` and quietly became part of
  the API a host can depend on; (c) a dependency that leaked across a module boundary. All three are
  cheap to detect by grep — do it once, deliberately, rather than trusting per-phase gates.
- `git diff -M --stat main...HEAD` is the honest check that this was a restructure: renames should
  dominate, with real content diffs confined to the files this plan explicitly lists as changed.
- Review is delegated to the `code-reviewer` agent per this repo's workflow — **do not perform the
  review inline**.

## Requirements

Functional:
- Zero references to `vn.quancua.artapemeasure` outside `:app`'s own 2 files and their package
  declarations.
- Public surface is exactly 6 (`:ar-measure-ar`) + 2 (`:ar-measure-photo`) + all of
  `:ar-measure-common` (public by design).
- 67 tests green from a clean build.

Non-functional:
- `:ar-measure-photo` runtime classpath free of ARCore/SceneView.
- `ar.domain.*` and `photo.domain.imaging` free of Android/ARCore/Compose imports.

## Architecture

Verification matrix (all commands run from repo root):

| Check | Command | Expected |
|---|---|---|
| Clean build + tests | `./gradlew clean compileDebugKotlin testDebugUnitTest` | green, 67 tests |
| Release compile | `./gradlew assembleRelease` | green (catches library-consumer wiring the debug path hides) |
| No old package leftovers | `grep -rn "vn.quancua" ar-measure-common ar-measure-ar ar-measure-photo --include=*.kt --include=*.xml` | empty |
| No duplicate definitions | `grep -rn "enum class LengthUnit\|fun formatLength\|hasAttemptedArWarmup\|fun drawLabelPill" --include=*.kt .` | one hit each |
| Public surface (`ar`) | list top-level non-`internal` declarations under `ar-measure-ar/src/main` | exactly 6 |
| Public surface (`photo`) | same for `ar-measure-photo/src/main` | exactly 2 |
| Domain purity | `grep -rn "android\.\|com.google.ar\|androidx.compose" ar-measure-ar/src/main/java/vn/apero/armeasure/ar/domain ar-measure-photo/src/main/java/vn/apero/armeasure/photo/domain` | empty |
| Photo classpath | `./gradlew :ar-measure-photo:dependencies --configuration debugRuntimeClasspath` | no `com.google.ar`, no `sceneview` |
| Rename-dominated diff | `git diff -M --stat` across the phase commits | renames dominate |
| Merged manifest | read `app/build/outputs/logs/manifest-merger-debug-report.txt` | one CAMERA, 2 uses-feature, 1 AR meta-data, 1 provider, only `MainActivity` exported |

## Related Code Files

Modify (only if a check fails): whichever module the failure points at.

Create: none. Delete: none.

Docs to update in this phase (they describe the structure that just changed):
- `README.md` — module layout + the new build/test commands (per-module Gradle tasks)
- `docs/system-architecture.md` and `docs/project-changelog.md` if they exist; delegate to
  `docs-manager` if the edit is more than a section

## Implementation Steps

1. Run every row of the verification matrix; record actual output for each. Fix failures in the
   owning phase's module, then re-run the full matrix (not just the failing row).
2. Confirm `git status` is clean and each phase landed as its own conventional commit.
3. Update `README.md`: the 3-module layout, what `:app` is now, and the per-module compile/test
   commands. Keep it factual — the consumer-facing instructions belong in phase 07's READMEs, not
   here.
4. Delegate to the **`code-reviewer` agent** with: this plan folder, the architecture record, and
   the commit range for phases 01–05. Ask it specifically to check (a) no accidental behavior change
   in the AR session config / watchdog / warm-up / steadiness code, (b) visibility correctness
   against the record's §4 public list, (c) module-boundary leaks, (d) that no mock/placeholder code
   was introduced. **Do not review inline.**
5. Triage the review: fix anything correctness- or API-surface-related in this phase; anything that
   is a pre-existing gap (see Next Steps) goes to backlog, not into this refactor.
6. Commit any fixes plus the README update: `docs: document the multi-module layout after extraction`.

## Todo List

- [x] Full verification matrix run, all rows pass
- [x] Clean git state, one conventional commit per phase
- [x] `README.md` updated for the module layout
- [x] `code-reviewer` agent invoked with the phase 01–05 commit range
- [x] Review findings triaged: fix vs. backlog decided per item
- [x] Fixes + docs committed

## Success Criteria

- Every matrix row passes on a clean checkout of the branch.
- `code-reviewer` reports no correctness or API-surface issue outstanding.
- The diff reads as moves + the specific changes this plan lists — nothing else.

## Risk Assessment

| Risk | Mitigation |
|---|---|
| Review surfaces a real behavior change deep in the AR code | It is cheaper to catch here than on-device; fix and re-run the phase 05 smoke test for the affected tab |
| Scope creep from review suggestions ("while we're here…") | Rule for this phase: only correctness, visibility and boundary findings are actionable; everything else is backlog |
| `assembleRelease` failing on something debug hid (e.g. resource shrinking, unused-resource lint) | Included as an explicit matrix row rather than discovered later |
| A leftover copy passing all gates because both copies compile | The duplicate-definition grep row targets exactly the 4 symbols most likely to be copied |

## Security Considerations

- Re-read the merged manifest as a security check, not just a wiring check: no unintended exported
  component, provider still `exported="false"`, no permission added beyond `CAMERA`.
- Confirm no debug logging of measurements, file paths or Uris was introduced during the move
  (`grep -rn "Log\." ar-measure-*/src/main`).
- Confirm nothing confidential entered the repo with the new Gradle files (`local.properties` stays
  untracked).

## Next Steps

- Phase 07: integration-guide READMEs.
- Backlog after this plan (all pre-existing, deliberately untouched — record §10, handoff §6):
  explicit anchor detach on screen dispose for the 3 AR screens; "height" wording on
  wall-anchored shapes; the cluttered-scene `autoFitQuad` null case (handoff §16); measuring the
  real minimum warm-up delay instead of the 2 s guess.
</content>
