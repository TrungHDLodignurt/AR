# Phase 10 — One `AR_feature/README.md` integration guide

## Context Links

- [Plan overview](plan.md) · depends on [phase 09](phase-09-final-verification-and-audits.md)
- Replaces: `ar-measure-common/README.md` (148), `ar-measure-ar/README.md` (240),
  `ar-measure-photo/README.md` (176) — 564 lines total, all three deleted in phase 01
- Pattern to beat: `~/.claude/skills/trung-apply-feature-video/` needs a whole
  `core-kernel-contract.md` because `:feature-video` depends on the host's `:core`. This module
  depends on nothing of the host's, so the README should be shorter *and* sufficient.

## Overview

- **Priority:** P2
- **Status:** pending
- **Effort:** 1.5h

One README that an operator (or phase 11's skill) can follow end to end with no other document.

## Key Insights

1. **Most of the old 564 lines existed to explain the 3-way split** — "take this one, skip that one",
   three version-catalog blocks where two were "additional keys on top of the base block", three
   `include` lines, three gradle task paths, and a whole conditional story about photo-only hosts
   avoiding ARCore. All of that collapses. Target ~200 lines, not 564.
2. **Several claims in the old READMEs are now false and must not be copied forward.** The headline
   ones: "works with zero ARCore/SceneView anywhere in the host app", "skip this module entirely if
   you only want photo-reference measuring", "a gravity-only Level tool", and
   `LevelScreen(modifier, onClose)` in the public API. Also `ar-measure-photo/README.md` §4's
   conditional about CAMERA only being declared if the AR module was taken — CAMERA is now
   unconditional.
3. **Two decisions were reversed during this work and the README is where that is recorded**, or the
   next reader will trust the old record: (a) `CustomReferenceStore` is now module-constructed inside
   `ArPhotoActivity`, not host-constructed — there is no host to construct it once the module owns
   its own Activity; (b) the module now ships its own theme instead of consuming the host's.
4. **The permission surface grew and a host will notice.** The merged manifest declares `CAMERA`,
   both camera `uses-feature` entries, the ARCore `optional` meta-data, a FileProvider, and now
   `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion="28"`. A photo-only host inherits the AR manifest
   signals too. This deserves its own section, not a footnote.
5. **The R8 section stays almost verbatim** — its facts are layout-independent and it is the single
   best thing the old READMEs contained: verified clean, no `consumer-rules.pro` by design, the AARs
   ship their own `proguard.txt` which AGP merges, `armeasure_*` survive resource shrinking via
   static `R.string.*`, the FileProvider is auto-kept because it is manifest-declared,
   `CustomReferenceStore` uses hand-rolled `org.json` and **not** Gson so no speculative keep rule is
   needed. Add the one revisit trigger: an ARCore/SceneView version bump that drops its
   `proguard.txt`.
6. **`assembleRelease` must be stated as mandatory, with its reason.** A debug-only check cannot
   catch R8 breakage; `:feature-video` shipped a release-only silent failure of exactly that shape.
7. **The AIP936 snippet is documentation only.** Do not modify AIP936 in this or any phase. Its
   current shape: `MainContract.Tab` is `HOME, EXPLORE`, and its bottom nav lives in
   `app/.../ui/feature/main/components/aip-bottom-nav.kt`. The host adds a `MEASURE` entry and one
   `when` branch. No Koin line is needed unless the host wants to override the image saver.
8. **One aside for the host team, not our work:** AIP936's `core/.../utils/FileUtils.kt:101` still
   writes to `Pictures/AI Profile Photo` and names files `AI_Photo_*.jpg` — a leftover from the
   ADA903 clone. We deliberately do **not** depend on that file (taking it is the `:core` coupling
   that forces `feature-video`'s `core-kernel-contract.md`), but the host team should know.

## Requirements

**Functional** — the README must, on its own, be enough to:
- copy the folder in and add one `include` line;
- produce the exact version-catalog additions for a host that has none of them;
- state the build requirements that will fail loudly if unmet;
- list the full public API — 3 symbols — with signatures;
- explain the two manifest-declared Activities and why they are not API;
- state every manifest entry and permission the host inherits;
- explain the availability gate the module owns and what a host must do about it (nothing);
- explain the `MeasurementImageSaver` port, its module default, and that it is a trust boundary;
- give the AIP936-shaped host wiring snippet;
- give the verification commands including `assembleRelease`;
- carry phase 09's deviations-from-the-mock register and audit commands;
- state the known limitations honestly.

**Non-functional**
- ~200 lines. Numbered sections so phase 11's skill can cite `§n`.
- Every code block copy-pasteable, no placeholders except a host package name.
- No claim that was not verified — the old READMEs' credibility came from saying "verified, not
  assumed", and phase 09 re-collected the evidence.

## Architecture

Section plan:

| § | Title | Notes |
|---|---|---|
| — | What this is / when you need it | One module, 4 tools: Distance, Box, Cylinder, Picture Measure. ARCore for the first three; the photo path needs none but **still ships the ARCore dependency** — the honest headline replacing the old "zero ARCore" promise |
| 1 | Copy + include | `AR_feature/` folder, `include(":AR_feature")`, one `implementation(project(":AR_feature"))`. Note the naming deviation and that it must not be renamed |
| 2 | Version catalog | One flat block: the union of what the 3 old blocks listed — `androidx-core-ktx`, `androidx-activity-compose`, `androidx-compose-bom`, `androidx-ui`, `androidx-ui-graphics`, `androidx-material3`, `androidx-lifecycle-runtime-compose`, `sceneview-ar`, `junit`, plus the `android-library` + `kotlin-compose` plugin aliases |
| 3 | Build requirements | compileSdk ≥ 36, minSdk ≥ 24 (raises the host's floor), Java 17, Compose, **AGP 9 built-in Kotlin — applying `kotlin-android` is an error in this repo** |
| 4 | Public API | `ArMeasureHub`, `ArMeasureConfig`, `MeasurementImageSaver`. Everything else `internal`. The two Activities are manifest-declared, not API — do not launch directly |
| 5 | Host wiring | The AIP936 snippet: a `MEASURE` tab in `MainContract.Tab`, one `when` branch → `ArMeasureHub()`, optional `ArMeasureConfig.imageSaver = …` in `Application.onCreate`. **The module never draws a bottom bar and the hub has no back button — it is a tab root** |
| 6 | Manifest + permissions | Everything the host inherits, including that a photo-only host still gets the AR entries. CAMERA requested by the module. `WRITE_EXTERNAL_STORAGE maxSdkVersion=28` requested only at save time on legacy devices |
| 7 | AR availability | Module-owned. The bounded 200ms×15 re-poll, `requestInstall` in `ArCameraActivity.onResume`, AR card hidden on `Unsupported`, Picture Measure always available. **Host work: none** |
| 8 | Units | 4 units, metres-always storage, the documented exception that `cm`/`m`/`in`/`ft` symbols are Kotlin literals while menu labels are resources, and the persisted `UnitPreference` |
| 9 | Saving measurements | The port, the MediaStore default writing to `Pictures/<app label>`, no watermark, the trust-boundary warning, and why we do not use the host's `FileUtils` |
| 10 | Strings + resources | `armeasure_` prefix, English default `values/`, which locales ship is the host's call, no literals in Kotlin |
| 11 | Cold-start warm-up | The 2s one-shot, the `TextureNotSetException` race, the upstream issue, and that the delay is an unmeasured generous margin |
| 12 | One shared ARCore session | One Engine, one `ARSceneView`, the two reverted fixes and why the rules exist. **The most important section for anyone modifying the camera screen** |
| 13 | R8 / ProGuard | Verified clean, no `consumer-rules.pro`, the five pieces of evidence, the one revisit trigger |
| 14 | Verify the integration | `:AR_feature:compileDebugKotlin`, `:AR_feature:testDebugUnitTest` (102 tests), `:app:assembleDebug`, **`:app:assembleRelease` (mandatory)**, merged-manifest greps, the adb smoke, and "gestures are not scriptable — a human at a real textured surface" |
| 15 | Maintenance audits | Phase 09's 16 one-line checks, especially the `ar` ↔ `photo` cross-import grep that `internal` no longer enforces |
| 16 | Deviations from the design mock | Phase 09's register, verbatim, so nobody "fixes" the build back toward the `.pen` |
| — | Known limitations | ARCore device certification (POCO X7 and Galaxy A07 both rejected on-device), thermal cost of `DepthMode.AUTOMATIC` + two-orientation plane finding, accuracy honesty ("a layout tool, not a caliper"), photo path needs the reference object visible in frame, no AR frame capture, `autoFitQuad` falls back to a manual quad, no saved-measurement list |
| — | Notes for the host team | The AIP936 `FileUtils.kt:101` `Pictures/AI Profile Photo` / `AI_Photo_*.jpg` leftover — informational, not our work |

## Related Code Files

**Create**
- `AR_feature/README.md` (replacing phase 01's placeholder)

**Modify**
- `README.md` (repo root) — module count 3 → 1, drop the Level tab from the feature list, update the
  test count to 102, point at `AR_feature/README.md`

**Delete**
- nothing here; the three old READMEs went in phase 01

## Implementation Steps

1. Draft §1–§4 from the shipped `build.gradle.kts`, `settings.gradle.kts` and the phase-09 public-API
   audit — read the files, do not recall them.
2. Draft §5 from the shipped `:app` wiring, which is the in-repo proof of the contract, and translate
   it to AIP936's `MainContract.Tab` shape. Do **not** open or edit AIP936 to do this; the shape is
   already recorded in this plan.
3. Draft §6 from the **merged** manifest in `app/build/intermediates/merged_manifest/`, not from the
   module source — the host inherits what merges, not what we wrote.
4. Carry §7, §8, §11, §13 across from the old READMEs and the reports, correcting the module names
   and dropping the conditionals.
5. Write §12 fresh, quoting §11 of the session-handoff report: what was tried, what failed, the
   near-100% figure. This section exists to stop the next person recreating the Engine.
6. Paste phase 09's deviations register as §16 and its audit list as §15.
7. Update the root `README.md`.
8. Self-check: hand the README to a fresh reader (or a subagent) with no other context and ask them
   to list the steps to integrate. Any question they have to ask is a gap.

## Todo List

- [ ] §1–§4 from the shipped files
- [ ] §5 AIP936 snippet, documentation only, AIP936 untouched
- [ ] §6 from the merged manifest, incl. `WRITE_EXTERNAL_STORAGE maxSdkVersion=28`
- [ ] §7 availability gate — "host work: none"
- [ ] §8 units + the documented literal-symbols exception
- [ ] §9 the saver port + trust boundary + why not the host's `FileUtils`
- [ ] §10 strings policy
- [ ] §11 warm-up
- [ ] §12 one shared session, with the §11-report evidence quoted
- [ ] §13 R8, verbatim facts + revisit trigger
- [ ] §14 verification incl. mandatory `assembleRelease` and the human-gesture caveat
- [ ] §15 the 16 audits
- [ ] §16 the deviations register
- [ ] Known limitations + the AIP936 `FileUtils` aside
- [ ] Root `README.md` updated
- [ ] Fresh-reader self-check
- [ ] Remove all "false after the merge" claims (checklist from the survey)

## Success Criteria

- `AR_feature/README.md` exists, ~200 lines, numbered sections.
- A reader with only this file can integrate: verified by the fresh-reader check producing zero
  blocking questions.
- `git grep -n 'ar-measure-\|LevelScreen\|zero ARCore\|skip this module' AR_feature/README.md`
  returns nothing.
- Every command in §14 has been run in phase 09 and is quoted with its actual expected output
  (including "102 tests").
- Root `README.md` no longer claims 3 modules, 5 tabs, or 67 tests.
- Both reversed decisions (module-constructed store, module-owned theme) are stated explicitly.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| A false claim survives the copy from the old READMEs | **high** | the survey produced an explicit list of now-false claims; treat it as a checklist and tick each |
| The README drifts the moment code changes | certain over time | §15's audits are the drift detector; phase 11 makes the skill defer to this file rather than duplicating it |
| §12 gets summarised and loses the evidence | medium | quote the report; the near-100% figure and the "reverted twice" history are the load-bearing parts |
| Someone edits AIP936 while writing §5 | low | explicitly out of scope in `plan.md` and here |
| 200 lines becomes 600 by copying all three old READMEs | medium | the split-explaining content is what gets cut; if a section only existed to say "take this one, skip that one", delete it |

## Security Considerations

- §6 must list the permission surface completely and accurately. A host reviewing its own manifest
  will find anything omitted, and omitting `WRITE_EXTERNAL_STORAGE` would look like concealment.
- §9 must state that a host-supplied `MeasurementImageSaver` receives the user's photo bitmap, so
  implementing it is a privacy decision.
- Do not put any credential, keystore path, signing instruction or internal URL in the README.
- Do not document how to disable the availability gate or the warm-up — both exist for correctness.
- The ad-hoc debug-keystore signing used for the release smoke test is a *verification* step; if it
  is mentioned at all, mark it clearly as not a build configuration.

## Next Steps

- Phase 11 rewrites the integration skill against this README as its primary spec.
