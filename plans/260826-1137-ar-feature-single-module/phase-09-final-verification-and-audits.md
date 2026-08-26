# Phase 09 — Final verification: string / accessibility / import audits + code review

## Context Links

- [Plan overview](plan.md) · depends on every phase 01–08
- R8 method to re-run: [`report-260826-0930-r8-release-hardening-ar-measure-modules.md`](../reports/report-260826-0930-r8-release-hardening-ar-measure-modules.md)
- UI review whose findings this phase closes out: [`ui-ux-designer-260826-1100-ar-measure-wireframe-review.md`](../reports/ui-ux-designer-260826-1100-ar-measure-wireframe-review.md)

## Overview

- **Priority:** P1 — nothing ships without it.
- **Status:** pending
- **Effort:** 2h

Grep-driven audits for the invariants no compiler enforces, a re-run of the R8 verification method
against the merged module, and a `code-reviewer` pass.

## Key Insights

1. **Three invariants in this plan have no compiler behind them** and will rot silently:
   no user-facing string literals in Kotlin; no `photo` ↔ `ar` cross-imports (the boundary `internal`
   used to enforce and no longer does); and every tappable target ≥48dp. Each needs a grep or a dump,
   and each should be written into phase 10's README as a maintenance check.
2. **The R8 audit's evidence is name-specific.** Its facts about `armeasure_*` string reachability
   and the `…armeasure.fileprovider` authority were gathered against three modules; the module count
   does not matter to R8, but the evidence has to be re-collected once for `AR_feature` so the
   README can keep claiming "verified, not assumed".
3. **Two new risk surfaces have appeared since that audit** and must be re-swept: the MediaStore save
   path and the `UUID`/`org.json` reference store. Neither adds reflection or serialization
   libraries, so the expected answer is still "no keep rules" — but the sweep is what makes that a
   fact rather than a hope.
4. **The design mock's own defects must be recorded as deliberate deviations**, not left implicit.
   Otherwise the next person compares the build to the `.pen`, finds ~18 mismatches, and "fixes" them
   back into contrast failures and 6px overhangs.
5. **On-device AR verification cannot be scripted.** Placing points and drawing 3-tap shapes needs a
   human aiming the phone at a real textured surface. State it; do not let a green build stand in.
6. Screenshots and `uiautomator` dumps are expensive artifacts — take one, grep it, delete it, both
   locally and on the device.

## Requirements

**Functional**
- All audits below pass, or each failure is recorded with a reason.
- `code-reviewer` runs over the whole `AR_feature` module and its findings are addressed or recorded.
- The R8 evidence is re-collected for the merged module.

**Non-functional**
- Every audit is a one-line command a future session can re-run. Put them in phase 10's README.

## Architecture

The audit set:

| # | Invariant | Check |
|---|---|---|
| 1 | No user-facing string literals in Kotlin | `git grep -nE 'Text\(\s*"\|text = "\|label = "\|placeholder = \{ Text\("' AR_feature/src/main` → only glyphs (`+ ↩ ↪ × ✕`) |
| 2 | No Vietnamese literals left | `git grep -nP '"[^"]*[àáảãạăâđèéẻêềếệìíỉòóỏôồốơớùúủưứỳýỵ]' AR_feature/src/main --include=*.kt` → only KDoc/comments citing ARuler |
| 3 | English default locale only | `ls AR_feature/src/main/res/ \| grep values-` → nothing (which locales ship is the host's call) |
| 4 | Resource prefix intact | `grep resourcePrefix AR_feature/build.gradle.kts` → `armeasure_`; every `<string name=` starts with it |
| 5 | No `ar` ↔ `photo` cross-imports | `git grep -n 'import vn.apero.armeasure.ar\.' AR_feature/src/main/java/vn/apero/armeasure/photo` and the mirror → both empty |
| 6 | Public API is 3 symbols | `git grep -nE '^(@Composable\s+)?(fun\|object\|class\|enum class\|sealed interface\|fun interface) ' AR_feature/src/main` filtered for non-`internal` → `ArMeasureHub`, `ArMeasureConfig`, `MeasurementImageSaver` + the 2 manifest Activities |
| 7 | Level fully gone | `git grep -in 'levelscreen\|AppTab.Level' AR_feature app` → empty |
| 8 | Out-of-scope tools absent | `git grep -in 'polyline\|auto-detection\|poly smooth' AR_feature` → empty |
| 9 | Old module names gone | `git grep -n 'ar-measure-' -- ':!plans'` → empty |
| 10 | One Engine, one ARSceneView | `git grep -c 'rememberEngine\|ARSceneView('` → 1 each |
| 11 | No debug logging of user content | `git grep -n 'Log\.' AR_feature/src/main` → empty |
| 12 | 48dp targets | one `uiautomator dump` per screen, grep `bounds`, assert min dimension ≥ 48dp in px; **delete the dump** |
| 13 | Contrast documented | every pair in `ArMeasureTokens` has its computed ratio in a KDoc comment; all ≥4.5:1 except the two entries marked decorative |
| 14 | R8 clean | `assembleRelease`; no `missing_rules.txt`; all `armeasure_*` in `resources.txt`; `mapping.txt` shows `com.google.ar.core.Plane` unrenamed; `ArMeasurePhotoFileProvider` unrenamed |
| 15 | Reflection sweep | grep `AR_feature` for `::class.java`, `Class.forName`, `reflect`, `Gson`, `kotlinx.serialization`, `getIdentifier` → only the two known ARCore `Plane::class.java` class-token lookups |
| 16 | File sizes | no `.kt` over ~200 lines without a stated reason |

Deviations-from-the-mock register (goes into phase 10's README so it survives):

| Mock says | We implement | Why |
|---|---|---|
| overlays 360 wide in 354 frames; sheet at `x=-3`; `fgWMc` 368 | `fillMaxWidth`, symmetric 16dp | resize artefact, 6px right-edge clip |
| selected tool label `#8A9A5B` on white | `#6E7C42` + muted fill | 3.05:1 → 4.6:1, and fill is a non-colour cue |
| "Lưu" bare text `#8A9A5B` 26×17 | 48dp filled button, white on `#6E7C42` | 2.78:1 and far under the touch target |
| SCR-15 subtitle line 2 in `#8A9A5B` | `TextSecondary` | 2.78:1 |
| `TOKzn` label white on `#EB3232` | dark pill on AR; luminance-picked text on photo | 4.1:1; and the photo colour is user-chosen |
| 12 targets 14–44px | all ≥48dp | the design's own `size-touchTarget: 48` |
| sheet label 10px | 12/600 | legibility |
| ARToast fixed 77 tall, y=596 under the sheet | hug height, above the sheet | one 16px line in a 77px box; occlusion |
| hub cards `fill_container` → 237.5 tall | hug (~88) | resize artefact |
| SCR-21 & SCR-22 identical instruction text | two distinct strings | the state advance was unsignalled |
| SCR-15 nav title + subtitle line 1 identical | one of them | duplicate copy |
| three "cm" in the dimension row | one unit selector | redundant |
| A4 "21 x 30 cm", card "5 x 9 cm" | exact dims via `formatLength` | wrong numbers in a measuring app |
| SCR-16/17 two divergent sheets | one sheet, optional edit target | `fgWMc` is an unparented orphan |
| SCR-23 + SCR-24 | one screen | no defined relationship |
| info icon, gear/Settings destination | info dropped; the gear is already the `UnitBtn` | neither has a destination in the document |
| `CheckmarkBtn` glyph 66 in a 100 circle | glyph 44 | oversized vs any Material FAB |
| `font-mono` "for measured values" | Inter | the token is used by nothing |
| stroke `#111111` 1px / `#666666` 2px screen frames | none | wireframe convention, not design |
| no AR terminal state | persistent label + commit toast | the honest minimum; frame capture is out of scope |

## Related Code Files

Read-only across `AR_feature/` and `app/`. Any fix that falls out of an audit is a small edit in the
owning file, not a new file.

## Implementation Steps

1. Run audits 1–11 and 15–16. Fix or record each failure.
2. Run audit 12: for each of hub / AR camera / mode sheet / reference grid / edit sheet / photo
   editor, one `adb shell uiautomator dump`, pull, grep bounds, **delete both copies**. Record any
   node under 48dp with a reason or fix it.
3. Run audit 13 by reading `ArMeasureTokens.kt` — every pair annotated, no un-annotated text colour.
4. Run audit 14: `./gradlew clean :app:assembleRelease`, then inspect
   `app/build/outputs/mapping/release/{configuration,resources,mapping}.txt` for the four facts, and
   confirm no `missing_rules.txt` was produced. Install the release build (sign ad-hoc with the debug
   keystore outside Gradle — **do not** add a signing config to any gradle file) and smoke it.
5. On-device human pass, the parts no script can do:
   - place AR points and read a distance against a tape measure
   - draw a Box and a Cylinder (3–4 taps each) on a real surface
   - swap tools mid-measurement and confirm nothing is lost
   - the full photo flow ending in a saved gallery image
   - a device or emulator with no ARCore: confirm the AR card is hidden and Picture Measure works
6. Delegate to `code-reviewer` over `AR_feature/`. Address findings or record them.
7. Write the deviations register and the audit commands into a scratch note for phase 10 to fold into
   the README.

## Todo List

- [x] Audits 1–11 pass or are recorded (audit 2 confirms the `QuadEditorCanvas` Vietnamese
      edge-label exception; audit 5 confirms the one documented `ArMeasureHub` cross-import)
- [x] Audit 12: one `uiautomator dump` per screen, greped, **both copies deleted** — plus a
      priority sweep beyond the phase file's own scope: 4 window-inset defects found and fixed
      (`ArMeasureHub` header under the status bar; `MeasureModeSheet`, `ReferenceEditSheet` and
      `PhotoMeasureScreen`'s first-time checkmark inside the nav-bar gesture zone; a 5th,
      `ReferencePickerScreen`'s grid, found by the `code-reviewer` pass and fixed the same way)
- [x] Audit 13: every token pair annotated with its ratio (plus `LabelContrastTest.kt` enforces it)
- [x] Audit 14: `assembleRelease`, 4 mapping facts, no `missing_rules.txt`
- [x] Audit 15: reflection sweep clean
- [x] Audit 16: file sizes — 8 files >200 lines, recorded with reasons (see session report), not split
- [x] Release build installed and smoked on the Pixel 6
- [ ] On-device human pass: AR distance vs a tape measure — **blocked**, needs a human aiming at a
      real textured surface
- [ ] On-device human pass: Box + Cylinder on a real surface — **blocked**, same reason
- [ ] On-device human pass: tool swap preserves work — **blocked**, same reason
- [x] On-device human pass: photo flow → saved gallery image — scripted end-to-end (photo picker →
      auto-fit quad → confirm → Save), confirmed a real `armeasure_*.png` landed in
      `Pictures/Tape Measure/` via MediaStore, then deleted the test artifact
- [ ] AR-unavailable device/emulator: AR card hidden, Picture Measure works — **blocked**, no
      non-ARCore device/emulator attached (only the Pixel 6, which supports ARCore, and the other
      team's Joy_4, out of bounds); `rememberArAvailability`'s gate logic is covered by
      `ArSessionStateTest` instead
- [x] `code-reviewer` pass; findings addressed or recorded — see
      `plans/reports/code-reviewer-260826-1723-ar-feature-single-module-review.md`
- [x] Deviations register + audit commands handed to phase 10 (register already in this file;
      audit commands are the ones actually run, recorded in the session report)

## Success Criteria

- **102 tests pass.** No test is skipped, `@Ignore`d, or weakened to make the suite green.
- `./gradlew clean compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease` green from a
  cold cache.
- All 16 audits pass, or each exception has a written reason.
- The full on-device human pass is done and the AR distance agrees with a tape measure to within the
  accuracy the module's README already claims.
- `code-reviewer` reports nothing it classifies as blocking.
- No screenshot, `uiautomator` dump or logcat capture is left on disk or on the device.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| An audit "passes" because the grep pattern was wrong | medium | each grep is written to produce a known non-empty control result first (e.g. audit 1 should find the glyphs), so an empty result is meaningful |
| A failing test is silenced to hit 102 | low | explicitly forbidden here and in the repo rules; the count is stated per phase so a shortfall is visible |
| R8 breakage found only at this point, after 8 phases | low | every phase gate already ran `assembleRelease`, so a regression is attributable to one phase |
| The human on-device pass is skipped because the build is green | medium | it is a checklist item, and phase 05's history (two green-building reverted fixes) is the argument |
| Screenshots accumulate and blow up context | medium | one per question, deleted immediately, per the repo's own rule |
| `code-reviewer` findings turn into a new refactor phase | medium | triage: correctness and security are fixed now, style is recorded |

## Security Considerations

- Confirm the release build ships exactly two permissions: `CAMERA` and `WRITE_EXTERNAL_STORAGE`
  with `maxSdkVersion="28"`. Read the **merged** manifest, not the source.
- Confirm no `intent-filter` on either module Activity (neither should be externally launchable).
- Confirm `allowBackup` is not re-enabled and no debug flag leaks into release.
- Confirm nothing logs user content: no bitmaps, no `Uri`s, no tap coordinates, no measured values.
- Confirm no signing config, keystore path or credential entered any tracked file. Ad-hoc signing for
  the release smoke test happens outside Gradle.
- Confirm the FileProvider authority is still host-namespaced via `${applicationId}` and its
  `armeasure_file_paths.xml` exposes no more than it did before.

## Next Steps

- Phase 10 turns this phase's register and audit list into the README.
- Phase 11 updates the integration skill.
