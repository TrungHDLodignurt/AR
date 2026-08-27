# Phase 05 — docs + apply skill

Context: [plan.md](plan.md) · depends on 01-04. Status: not started.

## Goal

Nothing claims the old architecture once the new one is in.

## Todo

- [ ] **Rewrite `AR_feature/README.md` §15.** It currently documents plain state holders and argues
      for them. Replace with the MVI shape, the per-frame exception and its reasoning, and the
      `SavedStateHandle` scope. Keep the honest note about what still does not survive process death
- [ ] Delete the `rememberSaveable` inventory from §15 if those patches are gone; if any remain, say
      which and why
- [ ] Update the DI statement in §13/README intro if phase 01 chose Koin — the "zero Koin/Hilt
      references, verified" claim and the R8 keep-rule story both depend on it
- [ ] Update `plans/reports/report-260827-1910-mvi-conversion-risk.md` with what the measurement in
      phase 04 actually found, so the assessment is not left as prediction
- [ ] Refresh `~/.claude/skills/trung-apply-ar-measure/` — it describes the module's wiring, and the
      host now needs to know about ViewModels (and Koin, if chosen)
- [ ] `plans/object_auto_detection_overlay_spec.md` — its status header says "No MVVM, no ViewModel".
      Correct that line; the OpenCV half stays true

## Success criteria

`git grep -ni "state holder"` over `AR_feature/README.md` returns only deliberate historical notes,
and the apply skill's dependency and wiring sections match `build.gradle.kts` exactly.
