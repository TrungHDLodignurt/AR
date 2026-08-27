# `trung-apply-ar-measure` skill refresh vs `AR_feature` @ feature/photo-measure-accuracy

Date: 2026-08-27 | Scope: docs only, edits confined to `/Users/admin/.claude/skills/trung-apply-ar-measure/`.
No file in `ar-tape-measure` touched (read-only git, no state-changing commands).

Verified against: `AR_feature/README.md` (§2/3/4/6/7/13/14/16), `AR_feature/build.gradle.kts`,
`gradle/libs.versions.toml`, `AR_feature/src/main/AndroidManifest.xml`, live sources
(`MeasureModeSheet.kt`, `ArMeasureHub.kt`, `ArUnsupportedDialog.kt`, `UnitPreference.kt`,
`DraggableHandlesOverlay.kt`, `QuadEditorCanvas.kt`), `plans/reports/report-260827-1725-photo-autofit-rebuild.md`,
`git log main..HEAD`.

## Stale, now fixed

| # | Was | Now | Where |
|---|---|---|---|
| 1 | ML Kit dependency absent from the catalog merge entirely | `mlkitSubjectSegmentation = "16.0.0-beta1"` + `mlkit-subject-segmentation` added as Bucket A, with "don't fix the -beta1 suffix, no stable exists" and "different artifact from any other play-services-mlkit-*" | `catalog-merge.md` (bucket A prose, table row, TOML block) |
| 2 | Merged-manifest list had no `com.google.mlkit.vision.DEPENDENCIES` | Added meta-data + the nested `FILE_PROVIDER_PATHS` meta-data the real manifest declares; added `grep -c 'subject_segment'` expect 1 | `manifest-verification.md` §1, §4 |
| 3 | Test count `102` | `172 tests, 2 skipped`, with why the 2 skip (`RealPhotoAutoFitTest` `@Ignore`d, bad ground truth) and that dropping `src/test/resources/` fails the gate | `SKILL.md` phase 6, `manifest-verification.md` §2 |
| 4 | "3 AR tools", "Distance/Box/Cylinder", "all three sharing one session" | 4 tools — `MeasureTool { Distance, DistanceChain, Box, Cylinder }`, confirmed in the mode sheet as 4 real cards, no disabled placeholders | `SKILL.md` intro + key facts, `host-wiring.md` §4, `caveats.md` §2, `manifest-verification.md` §7 |
| 5 | "ARCore-unavailable devices hide the AR tools" (host-visible, and wrong) | Both cards always show; AR card → `ArUnsupportedDialog` with "Use Picture Measure" + Play Store "Details"; plus the honest note that Details cannot help `UNSUPPORTED_DEVICE_NOT_CAPABLE` | `SKILL.md` key facts, `host-wiring.md` §1, `caveats.md` §1, `manifest-verification.md` §7 (coverage gate now includes the dialog path) |
| 6 | No default unit stated | `LengthUnit.Cm` on first launch (`UnitPreference.DefaultUnit`) | `SKILL.md` key facts |
| 7 | Caveat "auto-fit returns null on cluttered scenes — open gap" (superseded: was a display-space relayout bug + rectangle-vs-quad fit, both fixed) | Replaced with a pointer to the new §8 | `caveats.md` §6 |
| 8 | No caveat on segmentation being beta / GMS-only / unvalidated | New `caveats.md` §8: beta upstream, absent without Play Services (Hough then manual fallback), plausibility bounds reasoned from geometry + synthetic tests only, gate deliberately generous so a right-sized quad on the wrong object passes, rejection is silent, non-GMS device and release-build model download never tested. Old §8 (caliper) renumbered §9; cross-refs updated | `caveats.md`, `catalog-merge.md`, `SKILL.md` phase 7 |
| 9 | R8 section said nothing about ML Kit | Added: call sites reflection-free, GMS AARs ship consumer rules, no keep rule needed, `assembleRelease` passes — but one release-build on-device smoke test is still owed (optional-module download path). Release-APK install step added to the smoke gate | `SKILL.md` key facts, `manifest-verification.md` §5 |
| 10 | `PhotoMeasureScreen.kt:156` line-number citation (now :225, and that file is being edited concurrently) | Cite the `saveSupported` symbol, not the line | `caveats.md` §3, `detection.md` |
| 11 | Copy step said nothing about the new test fixture | Noted the 302 KB `src/test/resources/autofit-samples/` fixture rides along and must not be pruned | `SKILL.md` key facts |
| 12 | Transitively-inherited SceneView manifest entries not mentioned | Added `INTERNET`, `VIBRATE`, `glEsVersion=0x00030000 required="true"` — the one `required="true"` line the integration introduces into a host | `manifest-verification.md` §1 |
| 13 | Frontmatter description listed only ruler/Box/Cylinder + photo | Now names Distance chain and Picture Measure/ML Kit auto-fit; added `do bang anh` trigger | `SKILL.md` frontmatter |
| 14 | Phase-7 caveat summary omitted accuracy + auto-fit honesty | Now lists AR's several-cm-per-point uncalibratable error and the auto-fit caveats, and says plainly the integration is not turnkey | `SKILL.md` phase 7 |

Also corrected in passing: caveats §6 "the other 6 mock-sheet tools (… Polyline …)" — Distance chain now
exists, so the count and the Polyline entry were wrong; replaced with an unnumbered list of what remains
out of scope.

## Deliberately left alone

- Case A/B/C detection logic, the Java-17-vs-Java-11 reasoning, the Kotlin-version flag, the 3-bucket
  catalog algorithm, the AIP936 paper dry-runs (4-file tab change, flavored task names, floating-nav
  inset): all still true against current source; nothing changed under them today.
- Public API "exactly 3 symbols", `resourcePrefix`, FileProvider authority, CAMERA-only permission,
  `MediaStoreImageSaver` API-29+ gap, no-DI, tab-root semantics — re-verified, unchanged.
- `caveats.md` §5 rough edges — all re-verified live: `QuadEditorCanvas.kt` still draws "cạnh dài"/
  "cạnh ngắn" hardcoded, `DragHandle` is still 14.dp radius = 28dp, cache dir still uncleaned,
  glyph icons unchanged.
- Section structure, tone, table style, "paper dry-run, never built there" hedging: kept as-is.
- Did not add anything about the split photo line-drawing screen / SCR-21-22 layout work — that lives on
  `feature/photo-reference-measure`, not on the branch this skill was refreshed against.

## Claimed but not verifiable

1. `SKILL.md`: "re-verified … by the source repo's own **phase-09** final-verification audit". The R8
   evidence itself checks out (`plans/reports/report-260826-0930-r8-release-hardening-ar-measure-modules.md`
   + README §13); a report named "phase-09" does not exist under `plans/reports/`. Left the sentence,
   only its provenance label is unsupported.
2. `caveats.md` §4 / `host-wiring.md`: AIP936's `Aip.sizes.bottomNavOverlayInset = 96.dp` and its
   4-file tab shape — cannot be checked from this repo (that project was not read). Skill already labels
   these paper-only.
3. `detection.md`'s Java-level argument rests on sceneview classfile major 65 / absent
   `org.gradle.jvm.version` — not re-checked today (no artifact unzipped).
4. Merged-manifest `grep -c` expected counts (§4) were not re-run; no release build was performed in
   this session.

## Unresolved questions

1. Should the skill instruct the operator to check whether the host's target market/devices ship Play
   Services at all? Currently only stated as a caveat, not a detection step — deliberate, since there is
   nothing in a host repo to grep for it.
2. Auto-fit rejection is silent (report §"Unresolved" Q2). If that becomes a visible instruction-text
   change, `caveats.md` §8 needs a one-line edit.
3. README §16 lists file-size offenders including `PhotoMeasureScreen.kt` (410) / `PhotoMeasureState.kt`
   (436) — both are being edited concurrently on another branch; the skill deliberately cites no line
   counts, so no drift there, but the README's numbers will go stale.
