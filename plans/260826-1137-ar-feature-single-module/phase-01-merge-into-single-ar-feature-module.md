# Phase 01 — Merge the 3 modules into one `AR_feature`, delete Level

## Context Links

- [Plan overview](plan.md)
- Prior split this reverses in part: [`260825-1745-ar-measure-module-extraction/plan.md`](../260825-1745-ar-measure-module-extraction/plan.md)
- Architecture record whose §1 is being deliberately overridden: [`report-260825-1745-ar-measure-module-architecture-brainstorm.md`](../reports/report-260825-1745-ar-measure-module-architecture-brainstorm.md)
- R8 facts to preserve: [`report-260826-0930-r8-release-hardening-ar-measure-modules.md`](../reports/report-260826-0930-r8-release-hardening-ar-measure-modules.md)

## Overview

- **Priority:** P1 — every other phase is blocked on this.
- **Status:** pending
- **Effort:** 2.5h

Three Gradle library modules become one: `AR_feature/`. Level is deleted outright. Nothing else
changes — no behaviour, no signatures beyond the ones Level owned, no package renames.

## Key Insights

1. **Keep the `common/` `ar/` `photo/` sub-packages exactly as they are.** The merge then needs
   *zero* package renames: `vn.apero.armeasure.ar.presentation.ruler` keeps working verbatim. Only
   the module `namespace` collapses (three values → `vn.apero.armeasure`) and every
   `import vn.apero.armeasure.ar.R` becomes `import vn.apero.armeasure.R`. This turns a scary merge
   into a directory move plus a handful of `R` imports.
2. **The `R` class moves.** Today `namespace = "vn.apero.armeasure.ar"` puts it at
   `vn.apero.armeasure.ar.R`. After the merge it is `vn.apero.armeasure.R`. 14 `stringResource`
   call sites live in `ar/presentation/**` and each has an `import vn.apero.armeasure.ar.R`.
3. **Level ships no tests.** `git ls-files '*Test.kt'` confirms 5 test files, none of them Level's.
   So the 67-test total is unchanged by the deletion — do not "expect fewer tests".
4. **`internal` widens.** It previously separated AR from Photo; now it only hides both from the
   host. This is what later phases need (the shared session, the store CRUD) and is also the cost
   recorded in `plan.md`. Nothing to do here except stop treating the boundary as enforced.
5. **`app/proguard-rules.pro` references a file that does not exist** — its comment points at
   `ar-measure-ar/consumer-rules.pro`, but the R8 audit verified no module ships one. Fix the
   comment while the paths are being touched anyway.
6. `settings.gradle.kts` has no `rootProject.name` conflict; `include(":AR_feature")` with a capital
   letter and an underscore is legal Gradle and does not affect the generated `R` or the namespace.

## Requirements

**Functional**
- One Gradle module at `AR_feature/`, `include(":AR_feature")`, namespace `vn.apero.armeasure`,
  `resourcePrefix = "armeasure_"`.
- `:app` depends on exactly one project: `implementation(project(":AR_feature"))`.
- Level is gone: screen, tab entry, and every reference to it in strings/comments.
- All 67 JVM tests still pass, unchanged.

**Non-functional**
- Pure `git mv` for source moves so history follows the files.
- No file grows past ~200 lines as a result of the merge (nothing is concatenated).
- `assembleRelease` stays green — R8 has never seen a merged module of this shape before.

## Architecture

```
AR_feature/
├── build.gradle.kts            (union of the 3 dependency blocks)
├── .gitignore
├── README.md                   (placeholder; written in phase 10)
└── src/
    ├── main/
    │   ├── AndroidManifest.xml (union: CAMERA, 2 uses-feature, ar meta-data, FileProvider)
    │   ├── java/vn/apero/armeasure/
    │   │   ├── common/{domain,ui}/        (unchanged packages)
    │   │   ├── ar/{data,domain,presentation}/
    │   │   └── photo/{data,domain,presentation}/
    │   └── res/{values/strings.xml, xml/armeasure_file_paths.xml}
    └── test/java/vn/apero/armeasure/{common,ar,photo}/...
```

Dependency block = union of the three, de-duplicated:

```kotlin
dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)
    api(libs.androidx.material3)

    implementation(libs.sceneview.ar)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
```

## Related Code Files

**Create**
- `AR_feature/build.gradle.kts`
- `AR_feature/.gitignore` (copy of any of the three)
- `AR_feature/src/main/AndroidManifest.xml`
- `AR_feature/README.md` — one-line placeholder pointing at phase 10

**Move (git mv, no content change except `R` imports)**
- `ar-measure-common/src/**` → `AR_feature/src/**`
- `ar-measure-ar/src/**` → `AR_feature/src/**`
- `ar-measure-photo/src/**` → `AR_feature/src/**`
- `ar-measure-ar/src/main/res/values/strings.xml` → `AR_feature/src/main/res/values/strings.xml`
- `ar-measure-photo/src/main/res/xml/armeasure_file_paths.xml` → same path under `AR_feature`

**Modify**
- `settings.gradle.kts` — 3 `include` lines → 1
- `app/build.gradle.kts` — 3 project deps → 1
- `app/src/main/java/vn/quancua/artapemeasure/MainActivity.kt` — drop the `LevelScreen` import and
  the `AppTab.Level ->` branch
- `app/src/main/java/vn/quancua/artapemeasure/ui/AppTabBar.kt` — drop `Level` from `AppTab`, fix
  the `/** Bottom tab bar: … */` KDoc
- `app/src/main/res/values/strings.xml` — reword `ar_unsupported_body` (it currently promises "The
  Level tab still works")
- `AR_feature/src/main/AndroidManifest.xml` — the `uses-feature` comment also cites the Level tab
- `app/proguard-rules.pro` — the comment cites a non-existent `ar-measure-ar/consumer-rules.pro`
- 14 files under `ar/presentation/**` — `import vn.apero.armeasure.ar.R` → `vn.apero.armeasure.R`

**Delete**
- `AR_feature/src/main/java/vn/apero/armeasure/ar/presentation/level/LevelScreen.kt` (143 lines)
- `ar-measure-common/`, `ar-measure-ar/`, `ar-measure-photo/` directories, including their three
  `README.md` and three `build.gradle.kts`
- The three modules' `build/` output directories (untracked)

## Implementation Steps

1. `git mv ar-measure-common AR_feature` — this makes the first module the new module and keeps its
   `.gitignore`. Then `git mv` the `src/main/java/vn/apero/armeasure/ar` and `…/photo` trees plus
   the `src/test` trees from the other two into `AR_feature/`.
2. `git mv` the AR module's `res/values/strings.xml` and the photo module's `res/xml/` in.
3. Write `AR_feature/build.gradle.kts` with the union block above, `namespace =
   "vn.apero.armeasure"`, `resourcePrefix = "armeasure_"`, `compileSdk = 36`, `minSdk = 24`,
   `lint { targetSdk = 36 }`, `testOptions { targetSdk = 36 }`, Java 17, `buildFeatures.compose =
   true`. Do **not** add the `kotlin-android` plugin.
4. Write `AR_feature/src/main/AndroidManifest.xml` as the union of the AR and photo manifests
   (CAMERA, both `uses-feature`, the `com.google.ar.core` meta-data, the FileProvider). Reword the
   `required=false` comment: it currently justifies itself with "the Level tab still works" —
   replace with "the Picture Measure path still works".
5. `git rm -r` the three now-empty module directories and their READMEs/build files.
6. Rewrite `settings.gradle.kts` to a single `include(":AR_feature")`.
7. Delete `LevelScreen.kt`.
8. Fix the 14 `R` imports: `rg -l 'import vn.apero.armeasure.ar.R' AR_feature` then a single
   sed-equivalent edit per file.
9. Rewire `:app`: one project dependency, remove the Level import + branch, remove `Level` from
   `AppTab`, reword `ar_unsupported_body`.
10. Fix the `app/proguard-rules.pro` comment to say "no module currently ships a
    `consumer-rules.pro`; the R8 audit verified none is needed — see
    `report-260826-0930-r8-release-hardening-ar-measure-modules.md`".
11. Gate. If mid-move verification is wanted, use `:AR_feature:compileDebugKotlin` — `:app` will not
    compile between steps 5 and 9.

## Todo List

- [ ] `git mv ar-measure-common` → `AR_feature`
- [ ] `git mv` the `ar/` and `photo/` main + test source trees in
- [ ] `git mv` the AR `strings.xml` and photo `res/xml/` in
- [ ] Write `AR_feature/build.gradle.kts` (union deps, one namespace, `resourcePrefix`)
- [ ] Write `AR_feature/src/main/AndroidManifest.xml` (union, Level wording fixed)
- [ ] `git rm -r` the three old module dirs (incl. 3 READMEs, 3 build files)
- [ ] `settings.gradle.kts` → one `include(":AR_feature")`
- [ ] Delete `LevelScreen.kt`
- [ ] Fix 14 × `import vn.apero.armeasure.ar.R` → `vn.apero.armeasure.R`
- [ ] `:app`: one project dep, Level import + branch + enum entry removed
- [ ] Reword `app/.../strings.xml` `ar_unsupported_body`
- [ ] Fix the stale `consumer-rules.pro` reference in `app/proguard-rules.pro`
- [ ] Placeholder `AR_feature/README.md`
- [ ] Gate: `./gradlew clean compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease`

## Success Criteria

- `./gradlew clean compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease` green.
- **67 tests pass** — the same 67 (common 6 + ar 54 + photo 7). Not 66, not 68.
- `git grep -n 'ar-measure-' -- ':!plans'` returns nothing outside plan/report markdown.
- `git grep -in 'level' -- 'AR_feature' 'app'` returns only incidental prose (`lifecycle`,
  "screen-space level", the two `ShapeMathTest` comments) — no `LevelScreen`, no `AppTab.Level`.
- `settings.gradle.kts` has exactly two `include` lines: `:app` and `:AR_feature`.
- `app/build/outputs/mapping/release/` still produced, no `missing_rules.txt`.
- On-device: install the debug build, all 4 remaining tabs open without crashing.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| `R` class import misses one file → compile error | high | that *is* the gate; the compiler names every file |
| Manifest merge conflict from the union manifest | low | both source manifests were already merging into `:app` cleanly; the union is byte-identical content |
| `include(":AR_feature")` rejected for the capital/underscore | low | legal Gradle; verified by the phase gate. If it *does* fail, escalate — do not silently rename (the name is a locked decision) |
| Dropping `api`/`implementation` scope while de-duplicating deps → downstream compile break in `:app` | medium | keep every `api` from the three files as `api`; `libs.androidx.material3` was `api` in two modules, keep it `api` |
| R8 behaves differently on one module than three | low | `assembleRelease` is in the gate; the audit's evidence (AAR-shipped `proguard.txt`, static `R.string.*` reachability) is module-count-independent |
| Losing git history on moved files | medium | `git mv` only; never delete-and-recreate |

## Security Considerations

- The merged manifest now declares `CAMERA` + the AR `uses-feature` entries **unconditionally**,
  including for a host that only wants the photo path. That is a real, user-visible change to the
  permission/Play-Store-signal surface and must be stated in phase 10's README.
- `resourcePrefix = "armeasure_"` must survive the merge or host resource collisions become
  possible. It is one line; verify it is present.
- The FileProvider authority stays `${applicationId}.armeasure.fileprovider` — do not "simplify" it;
  the collision-avoidance reasoning is in the photo module's current manifest comment, carry it over.
- No credentials, keys or signing config are involved. Do not add a signing config to make
  `assembleRelease` installable — the R8 audit used an ad-hoc debug-keystore sign outside Gradle.

## Next Steps

- Unblocks every other phase.
- Phase 02 (`LengthUnit`) and phase 03 (redo stacks) can then proceed in either order.
