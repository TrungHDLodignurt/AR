# Phase 11 — Update the `trung-apply-ar-measure` skill for the single-module layout

## Context Links

- [Plan overview](plan.md) · depends on [phase 10](phase-10-single-readme-integration-guide.md)
- Skill: `~/.claude/skills/trung-apply-ar-measure/` — `SKILL.md` (42) + 5 `references/*.md`
  (`catalog-merge.md` 87, `caveats.md` 52, `detection.md` 61, `host-wiring.md` 87,
  `manifest-verification.md` 70) = **399 lines**
- Primary spec after this phase: `AR_feature/README.md` from phase 10

## Overview

- **Priority:** P3 — last, and it is the only phase that edits a file outside the repo.
- **Status:** pending
- **Effort:** 1.5h

The skill automates integrating this module into a host. Its module list, includes, catalog blocks
and API surface all change; its R8 facts and its `assembleRelease` gate do not.

## Key Insights

1. **One whole workflow step disappears.** `SKILL.md` phase 2 is an `AskUserQuestion` offering
   "full / AR-only / photo-only", justified by "photo-only means the host never adds
   `:ar-measure-ar` and therefore never puts ARCore/Filament on its classpath". With one module that
   choice does not exist. Delete the step and renumber — do not leave it as a no-op question.
2. **The layout-independent content is the majority of the value and must survive untouched:** the
   3-bucket version-catalog collision algorithm and its Bucket-C reasoning, the `kotlin`-version flag
   (one Kotlin compiler per build, do not preemptively bump), the whole host-baseline detection
   routine, the "never assume CWD" and "ask the operator where the entry point lives" rules, the
   FileProvider-authority collision check, all 5 caveats including the on-device ARCore-certification
   evidence (POCO X7, Galaxy A07), the R8 fact block, and the mandatory `assembleRelease` gate with
   its `:feature-video` rationale.
3. **`manifest-verification.md` has a real gap that is worth fixing while we are in there:** it
   contains no `assembleRelease` command even though `SKILL.md` phase 7 mandates one. Layout-
   independent bug, cheap fix.
4. **`host-wiring.md` line 53 must go:** `LevelScreen(onClose = …) // no AR-availability gate needed
   — gravity sensor only`. Level is deleted.
5. **The host-wiring story gets dramatically simpler.** The old file hands the host the availability
   gate, the bounded re-poll, per-screen call sites and a `CustomReferenceStore` construction line.
   All of that is now inside the module. What is left is: one `include`, one project dependency, one
   `MEASURE` tab, one `when` branch, and optionally one `ArMeasureConfig.imageSaver` assignment.
6. **Two new things the skill must now cover** that it could not before: the
   `WRITE_EXTERNAL_STORAGE maxSdkVersion="28"` permission (hosts audit their manifests), and the fact
   that the hub is a **tab root** — no bottom bar drawn by the module, no back button — which changes
   the "where does the entry point live" question from "which Activity" to "which tab".
7. **The stale-path bug in the sibling skill is a warning.** `trung-apply-feature-video` has
   `/Users/admin/StudioProjects/...` (missing `ahndroidne/`) repeated in every reference file, so a
   blind apply fails at step 1. Verify every path in *this* skill against the filesystem before
   finishing.
8. **The skill should defer to `AR_feature/README.md`, not duplicate it.** The old skill already did
   this well ("defers to the 3 module READMEs as primary spec"); keep that posture with one README so
   there is one source of truth and the drift surface shrinks.

## Requirements

**Functional**
- `SKILL.md` describes one module, one `include`, one catalog block, one gradle task path, and the
  3-symbol API. The subset question is gone and the phases are renumbered.
- All 5 reference files updated for the single-module layout; no `ar-measure-*` name survives.
- Every claim about the module's API, permissions, entry point and verification matches the shipped
  code and phase 10's README.
- Every filesystem path in the skill is verified to exist.

**Non-functional**
- Do not grow the skill. Removing the subset branching should make it shorter, not longer.
- Keep the `§n`-style citations pointing at phase 10's numbered README sections so a change in one
  place is findable in the other.

## Architecture

Per-file change plan:

**`SKILL.md`** (42 lines)
- L8 "1–3 reusable Gradle library modules" → one module.
- L10 the 3-folder list → `AR_feature/` with its single README.
- L13 "copying folders + includes" (plural) → singular.
- **Delete phase 2 entirely** (the full/AR-only/photo-only question); renumber phases 3–8.
- Phase 3: one `include(":AR_feature")`; keep "do not edit the module's own checked-in
  `build.gradle.kts`" (now singular).
- Phase 4: one catalog block, not "each taken module's".
- Phase 6: manifest verification is now unconditional.
- Phase 7: one gradle task path pair `:AR_feature:compileDebugKotlin` /
  `:AR_feature:testDebugUnitTest`, **102 tests**; keep `assembleRelease` mandatory and its rationale.
- L30 public-API surface → the 3 symbols; `LevelScreen` removed.
- L32 "`resourcePrefix` set inside both feature modules" → one module.
- L33 reference integration → `:app`'s `MainActivity.kt` + its 2-tab demo nav (still valid: `:app`
  survives the merge as the demo host).
- L31 metres-always invariant: **keep**, and add that the display unit is now one of four.
- L34–35 R8 block + revisit trigger: **keep verbatim**.
- Add: the hub is a tab root (no module bottom bar, no back button); the two new permissions; the
  `MeasurementImageSaver` port is optional with a module default.

**`references/detection.md`** (61 lines)
- L12–13: confirm one README exists, not three.
- L34–38: the minSdk-24 floor raise is now unconditional.
- L48: drop the "decides whether phase 5 offers the optional `single { }` line" — there is no
  required DI line at all; the only optional wiring is `ArMeasureConfig.imageSaver`.
- L56: drop "(if photo module taken)".
- L59: drop "before asking phase-2's subset question".
- **Add** a host-baseline item: does the host have a bottom-nav tab enum to add `MEASURE` to? That is
  now the integration's shape-critical question.
- Keep: never-assume-CWD, the whole baseline read, ask-the-operator, the JDK/minSdk flags.

**`references/catalog-merge.md`** (87 lines)
- L6–7: one README block, no base-plus-additional split.
- The dry-run table's per-module annotations → drop the module attributions; the alias set is the
  union in one script.
- L58: "Bucket-A addition (only if `:ar-measure-ar` taken)" → unconditional (`sceneview-ar`).
- L80–82: three gate paths → one.
- Keep: the 3-bucket algorithm, the TOML duplicate-key rule, the Bucket-C literal-name reasoning, the
  whole `kotlin`-version flag, and "re-run the classification against the actual host".

**`references/host-wiring.md`** (87 lines) — the most rewritten file
- L6–10: one `implementation(project(":AR_feature"))`, no conditionals.
- L13: drop "only needed if `:ar-measure-ar` was taken".
- **L53: delete the `LevelScreen` line.**
- L59: drop "(only if `:ar-measure-photo` was taken)".
- Replace the availability-gate + bounded-re-poll snippet with "the module owns this; host work:
  none" and a pointer to README §7. The re-poll code no longer belongs in a host file.
- Replace the 4 per-screen call sites with one `ArMeasureHub()` inside the host's `MEASURE` tab
  branch, and state that the module draws no bottom bar and the hub has no back button.
- Replace the `CustomReferenceStore` section with the reversed decision: the module constructs it in
  `ArPhotoActivity`. **Add** the `ArMeasureConfig.imageSaver` optional override instead.
- Keep: "module declares CAMERA, requests it itself", the `unit`-is-initial-only note (now: unit is
  persisted by the module), and "match the host's existing nav shape, don't invent a third".

**`references/manifest-verification.md`** (70 lines)
- L4 / L11: both blocks merge unconditionally now.
- L27–33: one compile/test command pair, `# if taken` comments gone.
- L51–53: expected grep counts all become an unconditional 1; **add** a count for the two module
  Activities and for `WRITE_EXTERNAL_STORAGE`.
- **Add the missing `:app:assembleRelease` command** (the gap noted in insight 3).
- Keep: the FileProvider-authority collision rationale and per-host grep, the missing-`libs.*`-alias
  and Kotlin-syntax-error diagnostics, `processDebugMainManifest`, the `tools:node` advice, the adb
  block, and the explicit "cannot validate accuracy or gestures".

**`references/caveats.md`** (52 lines) — lightest touch
- L23 and L45–46: "the photo-reference *module* has none of this cost / no such restriction" →
  re-phrase as **screen**-scoped, not module-scoped. The photo *screen* still needs no ARCore and
  still works on an emulator; the *module* now carries the dependency regardless.
- L35: the not-implemented list — reconcile with decision 3 (Angle, Polyline, Polyline smooth,
  Square, Poly smooth, Auto-Detection are out of scope and deliberately absent from the UI).
- Keep everything else: the ARCore-not-deprecated / Sceneform-vs-SceneView correction, the
  certification reality and the two device data points, the deferred backlog, the accuracy honesty,
  and all report citations.

## Related Code Files

**Modify** (all outside the repo, under `~/.claude/skills/trung-apply-ar-measure/`)
- `SKILL.md`
- `references/detection.md`
- `references/catalog-merge.md`
- `references/host-wiring.md`
- `references/manifest-verification.md`
- `references/caveats.md`

No repo file changes. No new reference file — resist adding one; the README is the spec.

## Implementation Steps

1. Read all 6 files as they stand. Do not work from the survey summary alone.
2. Rewrite `SKILL.md` first — it sets the phase numbering every reference file cites.
3. Rewrite `host-wiring.md` (largest delta), then `detection.md`, `catalog-merge.md`,
   `manifest-verification.md`, `caveats.md`.
4. Verify every filesystem path in every file actually exists — including
   `/Users/admin/ahndroidne/StudioProjects/ar-tape-measure` with the `ahndroidne/` segment, and
   `AR_feature/README.md`.
5. Grep the whole skill for the dead vocabulary: `ar-measure-`, `LevelScreen`, `photo-only`,
   `AR-only`, `if taken`, `both feature modules`, `base block`.
6. Cross-check every API/permission/command claim against phase 10's README and against the shipped
   code, not against memory.
7. Dry-run the skill mentally against AIP936's known baseline (Koin, `MainContract.Tab { HOME,
   EXPLORE }`, `aip-bottom-nav.kt`, `isMinifyEnabled = true`) and confirm each step has a concrete
   answer. **Do not touch AIP936.**

## Todo List

- [ ] Read all 6 skill files
- [ ] `SKILL.md`: one module, phase 2 deleted, phases renumbered, 3-symbol API, 102 tests
- [ ] `SKILL.md`: keep the R8 block and the mandatory `assembleRelease` verbatim
- [ ] `SKILL.md`: add tab-root entry point, the 2 permissions, the optional saver port
- [ ] `host-wiring.md`: one dep line, `LevelScreen` line deleted, gate → "host work: none",
      `ArMeasureHub()` in a tab branch, `ArMeasureConfig.imageSaver` replaces the store line
- [ ] `detection.md`: one README, unconditional minSdk floor, new "does the host have a tab enum"
      baseline item, subset references removed
- [ ] `catalog-merge.md`: one block, module attributions dropped, `sceneview-ar` unconditional,
      one gate path; algorithm and `kotlin` flag untouched
- [ ] `manifest-verification.md`: unconditional merges, one task pair, counts for the 2 Activities
      and `WRITE_EXTERNAL_STORAGE`, **`assembleRelease` added**
- [ ] `caveats.md`: photo claims re-scoped from module to screen; out-of-scope tool list reconciled
- [ ] Every path in the skill verified to exist
- [ ] Dead-vocabulary grep clean
- [ ] Mental dry-run against AIP936's baseline, AIP936 unmodified

## Success Criteria

- `grep -rn 'ar-measure-\|LevelScreen\|photo-only\|AR-only\|if taken\|both feature modules\|base block' ~/.claude/skills/trung-apply-ar-measure/`
  returns nothing.
- Every path in the skill resolves on disk (checked, not assumed — the sibling skill's
  missing-`ahndroidne` bug is the precedent).
- `SKILL.md`'s phase numbering is contiguous and every reference file's `phase N` citation matches.
- Total line count is **lower** than 399 — the subset branching removal should net a reduction.
- The public-API list, the permission list, the test count and the gradle task paths match the shipped
  code exactly.
- A mental dry-run against AIP936 reaches the end with no unanswered step.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| Phase renumbering leaves dangling `phase N` citations in reference files | **high** | rewrite `SKILL.md` first, then grep every reference file for `phase ` and fix each |
| A stale path ships and a blind apply fails at step 1 | medium | explicit path-existence check; the sibling skill is the cautionary example |
| The R8 block gets "tidied" and loses its evidence | medium | copy verbatim; its value is the specifics, and the `:feature-video` Media3 story is why the gate exists |
| Editing a user-level skill outside the repo is not version-controlled with this work | certain | note in the completion message which files outside the repo changed, so the user can review them |
| Adding a new reference file to explain the merge | medium | explicitly out of scope: the README is the spec, the skill is the procedure |
| Someone modifies AIP936 during the dry-run | low | dry-run is read-only and AIP936 is out of scope in `plan.md` |

## Security Considerations

- This phase edits files under `~/.claude/skills/`, i.e. outside the repo and outside version
  control. Change only the 6 files listed; do not touch any other skill, agent definition, hook,
  setting or configuration file.
- Do not put any host credential, keystore path, internal URL or API key into the skill.
- The skill must instruct an operator to **request** permissions at use time, never to pre-grant or
  to add permissions to the host's manifest by hand — the module's manifest is the only place they
  belong.
- Keep the existing Security section of `SKILL.md`; do not weaken it.
- The skill must not instruct anyone to add a signing config or to disable R8.

## Next Steps

- Plan complete. Remaining work explicitly deferred elsewhere: integrating into
  `AIP936-AIHomeDesign` (a separate later task), AR frame capture to the gallery, a saved-measurement
  list, and the 6 out-of-scope measuring tools.
