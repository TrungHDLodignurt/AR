# feature-video module-wrapping pattern — extraction report

Read-only study. Nothing modified.

Sources read:
- Module: `/Users/admin/ahndroidne/StudioProjects/ADA903-AI-ProfilePhoto/feature-video/**`
- Host: `.../ADA903-AI-ProfilePhoto/app/src/main/java/com/aiprofile/.../{App.kt, di/VideoHostModule.kt, di/AppSongGenerationGate.kt, ui/feature/main/MainActivity.kt}`, `settings.gradle.kts`
- Shared kernel: `.../ADA903-AI-ProfilePhoto/core/src/main/java/com/aiprofile/.../ui/{common/base,theme,common/components}`
- Skill: `~/.claude/skills/trung-apply-feature-video/SKILL.md` + all 6 `references/*.md`
- Target: `/Users/admin/ahndroidne/StudioProjects/ar-tape-measure/{settings.gradle.kts, app/**, ar-measure-*/**}`

Root package abbreviated below as `<root>` = `com.aiprofile.aiheadshot.aiimagegenerator.aiphotogenerator`.

---

## 1. Entry point shape

**One Activity with a `start()` helper. Not a composable, not a factory.**

`<root>.ui.feature.video_maker.VideoMakerActivity`
File: `feature-video/src/main/java/com/aiprofile/.../ui/feature/video_maker/VideoMakerActivity.kt`

```kotlin
class VideoMakerActivity : BaseComposeActivity() {
    override fun shouldIgnoreSwipeUpAction() = true
    @Composable override fun SetContent() { /* koinViewModel + koinInject + VideoMakerScreen */ }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, VideoMakerActivity::class.java)
        fun start(context: Context) = context.startActivity(newIntent(context))
        fun backToVideoMaker(context: Context)   // CLEAR_TOP|SINGLE_TOP re-entry
    }
}
```

Literal host call site — `app/.../ui/feature/main/MainActivity.kt:264`:

```kotlin
onVideoClick = {
    VideoMakerActivity.start(this@MainActivity)
},
```

That is the **entire** UI trigger. One line, no params.

- **Declared in the module's own manifest** → auto-merges. `feature-video/src/main/AndroidManifest.xml` declares all 6 activities with fully-qualified `android:name` (necessary because the class packages are `<root>.ui.feature.video_maker.*` while the module `namespace` is `<root>.feature.video` — they deliberately do NOT match; the manifest carries an inline comment saying so). Host manifest has **zero** video entries.
- **Inputs in:** none. The feature is self-configuring; everything it needs comes from Koin (see §4/§6), not from Intent extras. `start(context)` takes only a Context.
- **Results out:** none. The feature never returns anything to the host. Exit = `finish()`. `Effect.NavigateToHome -> finish()`.
- Intent extras + `setResult` exist only for **intra-module** hops (see §2).
- Every screen's helper is the same shape: `newIntent(...)` (pure) + `start(...)` (fire). `newIntent` is exposed separately precisely so a caller that needs a result can feed it to a launcher.

## 2. Internal navigation

**Nested Activities, one per screen. No NavHost, no sealed screen-state router.** The Android back stack is the nav model, so the module owns nothing and can be exited from any depth.

Flow: `VideoMakerActivity` → `MediaPickerActivity` → `ArrangeActivity` → `EditVideoActivity` → `VideoResultActivity`, with `GenerateMusicActivity` as a side-branch off the editor.

Payload between screens = **Parcelable domain entity as an Intent extra**:
- `ArrangeActivity.newIntent(context, uris: ArrayList<String>)` — `EXTRA_URIS`
- `EditVideoActivity.newIntent(context, project: VideoProject)` — `EXTRA_PROJECT`
- `VideoResultActivity.newIntent(context, project: VideoProject)` — `EXTRA_PROJECT`

`VideoProject` and `AudioTrack` are `@Parcelize @Immutable data class` (`domain/entity/`).

Result-back **only** where a screen genuinely produces a value — `GenerateMusicActivity` → editor:
```kotlin
// GenerateMusicActivity.kt:46
setResult(RESULT_OK, Intent().putExtra(EXTRA_TRACK_ID, effect.trackId)); finish()
// EditVideoScreen.kt:92 — consumed with a Compose launcher
val generateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
        val id = result.data?.getStringExtra(GenerateMusicActivity.EXTRA_TRACK_ID)
        if (id != null) songGate.onGenerated()          // host quota advanced here
        viewModel.processIntent(EditVideoContract.Intent.ReloadGenerated(id))
    }
}
```

Exit to host: each screen `finish()`es itself. Landing screen's `Effect.NavigateToHome -> finish()` pops back to whatever launched it. `backToVideoMaker()` collapses the project sub-flow without recreating the landing screen.

**`MediaPickerActivity` is a shim, not a screen** — worth noting: it exists only to own the `PickVisualMedia` grant, copy the picked clip into `cacheDir` (`copyToCache`) so downstream activities + the long-running Transformer keep read access, then `finish()`. It renders only a spinner. That's a deliberate "invisible Activity as a permission/URI-lifetime scope".

## 3. UI ownership + theming

**Module consumes a theme it does not own — but the host does not wrap anything either.** Theming is injected via the shared `:core` base class.

- `feature-video` has **no** theme/color/typography files at all. Its screens are plain composables assuming `MaterialTheme` is already installed.
- Every module Activity extends `<root>.ui.common.base.BaseComposeActivity` (in `:core`), whose `onCreate` does:
  ```kotlin
  enableEdgeToEdge()
  setContent {
      ADA903AIProfilePhotoTheme {                       // core/ui/theme/Theme.kt
          CompositionLocalProvider(LocalToastManager provides toastManager) {
              LocaleProvider(localeManager) { Box { SetContent(); ToastHost(...) } }
          }
      }
  }
  ```
- Consequence: the host wraps **nothing**. Theme, edge-to-edge, locale override (`attachBaseContext` → `localeManager.updateLocale`), toast host and the swipe-up-suppression hack all arrive for free from the base class. Subclasses only implement `@Composable fun SetContent()`.
- Cost: `:core` is a hard dependency (`implementation(project(":core"))`), and the theme belongs to the app, not the feature. This is the biggest non-portable part of the pattern — it's why the skill needs a whole `references/core-kernel-contract.md`.
- Colours the module uses come from `AppColor` (`core/ui/theme/Color.kt`, an `object`, not a MaterialTheme role) — so it's *half* design-token, half MaterialTheme.

## 4. DI

**Koin, two modules, host loads both explicitly. The module does NOT self-register.**

Module side — `feature-video/.../feature/video/di/VideoModule.kt`:
```kotlin
@UnstableApi
val videoModule = module {
    single { AudioCache(androidContext()) }
    single { SlideshowVideoRenderer(androidContext(), get()) }
    single { MusicProvider(musicConfigJson = get<MusicConfigProvider>()::musicConfigJson) }
    single<SongGeneratorService> { /* Retrofit built on SongApiConfig */ }
    single<MusicGenerator> { RetrofitMusicGenerator(get()) }
    single<GeneratedMusicStore> { JsonGeneratedMusicStore(androidContext()) }
    single<VideoReportSubmitter> { RetrofitVideoReportSubmitter(get()) }
    viewModel { VideoMakerViewModel() }
    viewModel { ArrangeViewModel() }
    viewModel { EditVideoViewModel(get(), get(), get(), get()) }
    viewModel { GenerateMusicViewModel(get(), get(), get()) }
    viewModel { EditGeneratedSongViewModel(androidContext(), get()) }
    viewModel { VideoResultViewModel(get(), get()) }
}
```
So the module owns **all its own singles and ViewModels**. It declares the ports as `fun interface`/`interface`/config-class in the *same file*, but does not implement them.

Host side, verbatim — `app/.../App.kt:75`:
```kotlin
modules(appModule, networkModule, adsModule, videoModule, videoHostModule)
```
with imports at `App.kt:23-24`:
```kotlin
import com.aiprofile...aiphotogenerator.di.videoHostModule
import com.aiprofile...aiphotogenerator.feature.video.di.videoModule
```

No `loadKoinModules`, no initializer, no ContentProvider, no lazy first-entry registration. Screens resolve with `koinViewModel()` / `koinInject()` from `org.koin.compose.*`. `VideoResultViewModel` resolves the *host-registered* `FileUtils` at runtime — same graph, so it just works.

**One shared graph is load-bearing:** module singles and host singles resolve each other freely. That's the simplification that makes "one trigger point" possible, and also the coupling risk.

## 5. Config/params surface

**No config data class, no builder, no params at the call site.** All configuration is Koin definitions in one host file. Two of them are transport-config value classes (declared by the module):

```kotlin
class SongApiConfig(val okHttpClient: OkHttpClient, val baseUrl: String)

class ReportApiConfig(
    val okHttpClient: OkHttpClient,
    val baseUrl: String,
    val venture: String,
    val appCode: String,
    val email: () -> String,     // lambda, read fresh per call (remote config can change it)
)
```

Everything else that a "config object" would normally hold is instead a **behaviour port** (§6), so remote-config values are pulled lazily rather than snapshotted at startup. Concrete gating inputs the host feeds through:
- `enable_generate_song` → `SongGenerationGate.isFeatureEnabled()`
- `limit_sub_generate_song_per_day` → inside `AppSongGenerationGate.precheck()`
- `music_changing_config` (JSON) → `MusicConfigProvider.musicConfigJson()`
- paywall placements `generate_song`, `edit_video` → `SongGenerationGate` / `PremiumStatusProvider.isVideoPaywallEnabled()`
- `BuildConfig.SONG_BASE_URL`, `BuildConfig.REPORT_BASE_URL`, `venture`/`appCode` literals

Note the deliberate choice: `email: () -> String` and `isPremium(): StateFlow<Boolean>` are **suppliers**, not values. Nothing is captured at wiring time.

## 6. Host-provided ports

All declared in one file, `feature-video/.../feature/video/di/VideoModule.kt` (ports live next to the Koin module on purpose — one file to read for the whole contract).

| Port | Kind | Signature | Host impl (ADA903) |
|---|---|---|---|
| `MusicConfigProvider` | `fun interface` | `fun musicConfigJson(): String` | `MusicConfigProvider { remoteUi.musicChangingConfig }` |
| `SettingsNavigator` | `fun interface` | `fun openSettings(context: Context)` | `SettingsNavigator { ctx -> SettingsActivity.start(ctx) }` |
| `PremiumStatusProvider` | `interface` | `fun isPremium(): StateFlow<Boolean>` / `fun isVideoPaywallEnabled(): Boolean` | anonymous `object` over `SubscriptionManager` |
| `SongGenerationGate` | `interface` | `isFeatureEnabled(): Boolean` / `precheck(): SongGateResult` / `openPaywall(Context)` / `onGenerated()` | `AppSongGenerationGate(prefs)` |
| `SongApiConfig` | class | `(OkHttpClient, baseUrl)` | `SongApiConfig(get<OkHttpClient>(named("signed")), BuildConfig.SONG_BASE_URL)` |
| `ReportApiConfig` | class | see §5 | `ReportApiConfig(get(named("base")), BuildConfig.REPORT_BASE_URL, "vsl", "ADA903", { remoteLogic.emailFeedback })` |
| `VideoReportSubmitter` | `fun interface` | `suspend fun submit(reason, resultUrl?, content?): Result<Unit>` | **module-supplied by default** (`RetrofitVideoReportSubmitter`); host may override with a thin adapter over an existing `ReportRepository` |

`enum class SongGateResult { ALLOWED, NEED_SUBSCRIPTION, DAILY_LIMIT_REACHED }` — module-owned vocabulary, host maps its own policy onto it. Note `openPaywall` is on the *gate*, i.e. the module never knows what a paywall is.

Injection: all via Koin `single`, consumed with `koinInject<T>()` inside composables (`EditVideoScreen.kt:84-85`) or `koinInject()` in `SetContent()` (`VideoMakerActivity.kt:24-25`). Host implementations live in exactly **two** files:
- `app/.../di/VideoHostModule.kt` (49 lines, all 6 definitions)
- `app/.../di/AppSongGenerationGate.kt` (the one port with real policy logic)

The file header states the porting contract explicitly: *"Porting the video feature to another app = copy this file and adjust the values."*

## 7. Resources + collision safety

- **No `resourcePrefix`.** Grepped the whole ADA903 repo: zero occurrences in any `build.gradle.kts`. Collision avoidance is by naming convention only — 74 strings, all `video_*` / `report_*` / `music_*`-ish; 14 drawables `ic_*` (which **could** collide with a host's `ic_back`, `ic_close`, `ic_play` — AAPT resolves silently in favour of the app, so a host with its own `ic_back` will silently retheme the module). This is the weakest part of the pattern; **ar-measure already does better** (`resourcePrefix = "armeasure_"` in both feature modules).
- Localisation ships with the module: `res/values/` + 10 `values-{ar,de,es,fr,hi,ja,nl,pt,ru,zh}/strings.xml`.
- `R` class is `<root>.feature.video.R` (from `namespace`), **not** matching the code's package (`<root>.ui.feature.video_maker`). Cross-module refs to `:core` resources go through `core.R`.
- **Manifest: module declares, host declares nothing.** 6 `<activity>` entries with `android:screenOrientation="portrait"`, two with `windowSoftInputMode="adjustResize"`.
- **Permissions: none declared, none requested.** The module deliberately avoids runtime media permission by using the system Photo Picker (`ActivityResultContracts.PickVisualMedia`) — see the comment in `MediaPickerActivity`.
- **FileProvider: none.** Sidestepped: the picker copies into `cacheDir` and passes a `file://` URI between the module's own activities; gallery saving is delegated to the host's `FileUtils.saveVideoToGallery`.
- **R8: module ships `consumer-rules.pro`** wired via `consumerProguardFiles("consumer-rules.pro")` in `defaultConfig`. Keeps Gson DTOs (`data.music.generator.**`, `data.report.**` — no `@SerializedName`), `-keepattributes Signature`, plus a long Media3-Transformer keep block with a fully documented root cause (R8 class-merging forces resolution of the API-31 `LogSessionId`, killing export on Android ≤ 11, release-only, silently). **This is the single best thing in the module** — a keep rule with the on-device evidence (39ms fail vs 322ms success on a CPH2375) written into the comment.

## 8. Layering

Directories: `domain/entity/`, `data/{music,music/generator,video,report}/`, `feature/video/di/`, `ui/feature/video_maker/**`.

- **`domain` is NOT Android-free.** `VideoProject.kt` and `AudioTrack.kt` import `android.os.Parcelable`, `kotlinx.parcelize.Parcelize`, `androidx.compose.runtime.Immutable`, and `AudioTrack` even imports `com.google.gson.annotations.SerializedName`. So `domain/entity` is really "shared immutable models that travel through Intents", not a clean domain layer. Pragmatic, and it's what makes §2's Intent-extra navigation trivially work.
- **No use-cases, no repositories.** ViewModels talk straight to data classes: `EditVideoViewModel(get(), get(), get(), get())` gets `MusicProvider`, `AudioCache`, `SlideshowVideoRenderer`, `GeneratedMusicStore` directly. `MusicGenerator` / `GeneratedMusicStore` / `VideoReportSubmitter` are interfaces with one Retrofit/JSON impl each — the closest thing to a repository, and named for what they do rather than as `*Repository`.
- **MVI, strictly.** Per screen: `XContract.kt` (`object` holding `State : MviState`, `sealed interface Intent : MviIntent`, `sealed interface Effect : MviEffect`), `XViewModel : MviViewModel<S,I,E>()`, `XScreen.kt`, `XActivity.kt`. Base in `:core` (`MviViewModel` with `state: StateFlow<S>`, `stateValue`, `effect: Flow<E>` from a BUFFERED `Channel`, `processIntent`, `updateState { copy(..) }`, `sendEffect`, `createInitialState()`, `handleIntent`).
- **Effects are the navigation channel.** Activities are thin: collect `viewModel.effect` in a `LaunchedEffect`, `when` over it, call `SomeActivity.start(...)` / `finish()` / a port. No navigation logic in ViewModels beyond emitting an Effect. That's the reason the module can be re-hosted — every outward jump is an Effect or a port.
- State hoisting: mixed on purpose. `VideoMakerScreen(showGoPro=, onCreateProject=, ...)` is fully hoisted/dumb; `EditVideoScreen(viewModel)` takes the VM directly and does `koinInject` itself. Small screens hoisted, big ones not.
- Derived state as `State` getters (`EditVideoContract.State.durationSec get() = project.durationSec`) instead of duplicating fields — keeps the single source of truth in `project`.

## 9. Non-obvious bits worth copying (or not)

**Copy:**
1. **Ports declared in the same file as the Koin module.** `feature/video/di/VideoModule.kt` is a single-file readable contract: what the module provides + what it demands. A reader learns the whole integration surface from one file.
2. **One host file per feature, with the porting instruction in its header comment.** `VideoHostModule.kt`'s doc comment literally says copy-this-file-and-change-the-values. That's the mechanism the skill automates.
3. **Suppliers, not values, at the port boundary** (`email: () -> String`, `isPremium(): StateFlow<Boolean>`, `isFeatureEnabled()` called at render time). Remote config changes mid-session without re-wiring anything.
4. **The module owns the outcome vocabulary, the host owns the policy.** `SongGateResult` is module-defined; `precheck()` is host-implemented. The module never learns what "premium" or "paywall" means, yet its UI can branch on all three outcomes.
5. **`newIntent()` exposed alongside `start()`** so a caller that needs a result can drive its own launcher — cost is one extra function, and it's what makes the editor→generate result hop possible without special-casing.
6. **Invisible shim Activity to own a permission/URI grant** (`MediaPickerActivity`): copy-to-cache up front, then the grant's expiry stops mattering. Generalisable to any "system picker feeds a long-running background job" problem.
7. **`consumer-rules.pro` with the evidence in the comment.** Not just the rule — the symptom, the device, the timings, the reason R8 breaks it. This survives the next person's "these keeps look unnecessary, let's trim them".
8. **Manifest-declared activities in the library module** → host gets a nav-free integration. Fully-qualified `android:name` when package ≠ namespace.
9. **Nav via nested Activities + Parcelable extras.** Unfashionable, but it means the module owns no back stack and the host's back button behaves correctly at every depth for free. For a self-contained flow that the host only enters and exits, it's less machinery than a nested NavHost.
10. **Empty `data object State : MviState`** on the landing screen — the shape is kept even when there's no state, so the screen looks like every other one.

**Do NOT copy:**
1. **No `resourcePrefix`.** 14 `ic_*` drawables in a library module is a live collision hazard with silent, wrong-icon failure mode. ar-measure already avoids this — keep it.
2. **The `:core` hard dependency for theming/base-activity.** It is what forces the skill's `core-kernel-contract.md` (8 symbols to diff and possibly copy) and its "MVI base MISMATCH" adaptation branch. Costs the most integration effort of anything in the pattern. Prefer a module-internal theme wrapper with a host-override hook.
3. **`namespace` deliberately diverging from source packages** (`...feature.video` vs `...ui.feature.video_maker`). Forces fully-qualified manifest names and makes `R` non-obvious. No upside found.
4. **Android types in `domain/`.** Fine here because entities *are* the Intent payload; don't inherit it if the layer is meant to stay testable/pure.
5. **`@UnstableApi` on the whole `videoModule` val** — leaks a Media3 opt-in into the host's DI wiring.

### Skill-vs-code drift found

| Claim | Reality |
|---|---|
| SKILL.md:10 + all references: source of truth `/Users/admin/StudioProjects/ADA903-AI-ProfilePhoto` | Actual path is `/Users/admin/ahndroidne/StudioProjects/ADA903-AI-ProfilePhoto`. Every `references/*.md` repeats the stale prefix. Blind apply would fail at step 1. |
| `core-kernel-contract.md`: symbol `AppTypography` in `ui/theme/Type.kt` | Actual symbol is `val Typography` (`Type.kt:42`). Grep for `AppTypography` finds nothing. |
| `core-kernel-contract.md` rule 4: "`AppTypography` references a font family (e.g. IBMPlexSans) — copy the font files" | `Type.kt:16` is `val IBMPlexSans = FontFamily.Default`. No font resources exist to copy; the rule is moot and would send an operator hunting for files that aren't there. |
| Skill is silent on `resourcePrefix` | Module has none; 14 generic `ic_*` drawables can collide with a host. Not covered by any self-check in `troubleshooting.md`. |
| Skill is silent on `VideoMakerActivity.backToVideoMaker()` | Exists and is part of the public companion surface (CLEAR_TOP re-entry). Undocumented. |
| Skill step 8 "Entry point: launch `VideoMakerActivity.start(context)`" | Correct and complete — verified as the only host UI call site (`MainActivity.kt:264`). |
| `host-wiring-templates.md` port table (6 host-supplied + `VideoReportSubmitter` module-supplied) | Matches `VideoModule.kt` exactly. No drift. |
| `host-wiring-templates.md` Koin snippet | Byte-for-byte consistent with `VideoHostModule.kt` and `AppSongGenerationGate.kt`. No drift. |

---

# Applying this to ar-measure

## Current host burden (what the pattern must absorb)

`app/src/main/java/vn/quancua/artapemeasure/MainActivity.kt` (~200 LOC) + `ui/AppTabBar.kt` (~60 LOC) today do:
1. `MaterialTheme(colorScheme = darkColorScheme())` wrapper.
2. CAMERA permission check + `ActivityResultContracts.RequestPermission()` launch.
3. `ArMeasureKit.requestInstall` / `checkAvailability` in `onResume`.
4. The bounded 200ms×15 re-poll `LaunchedEffect` for `ArAvailability.Checking`.
5. `AppTab` enum + `AppTabBar` chrome + `tab` state.
6. `remember { CustomReferenceStore(context) }`.
7. A 5-branch `when(tab)` with availability/permission gating repeated 3× (`ArUnsupported()` / blank / `CameraDenied()`).
8. `ArUnsupported` / `CameraDenied` / `CenteredMessage` composables + `R.string.ar_unsupported_*`.

Items 2–8 all move inside. Item 1 stays (host theme choice), see the caveat below.

## Mapping table

| feature-video construct | ar-measure equivalent | New/changed |
|---|---|---|
| `VideoMakerActivity` (`ui/feature/video_maker/`) + manifest entry + `start()`/`newIntent()` | **`ArMeasureActivity`** in `:ar-measure-ar`, pkg `vn.apero.armeasure.ar.presentation.host`, `companion { fun newIntent(Context): Intent; fun start(Context) }`, declared in `ar-measure-ar/src/main/AndroidManifest.xml` with `android:screenOrientation="portrait"` | NEW `ArMeasureActivity.kt`; EDIT `ar-measure-ar/src/main/AndroidManifest.xml` |
| `VideoMakerScreen` (landing, hoisted params) | **`ArMeasureHostScreen`** — owns the tab state, the availability gate and the store; the composable entry for hosts that want to embed instead of launch | NEW `ArMeasureHostScreen.kt` (same pkg) |
| `AppTab` enum + `AppTabBar` (host-owned chrome) | **`ArMeasureTool` enum + `ArMeasureToolBar`**, both `internal`, moved verbatim from `app/.../ui/AppTabBar.kt` | NEW (internal) in `:ar-measure-ar`; DELETE `app/src/main/java/vn/quancua/artapemeasure/ui/AppTabBar.kt` |
| `MediaPickerActivity` as a permission/grant-owning shim | **CAMERA runtime request** — no shim Activity needed; do it in `ArMeasureActivity` with `registerForActivityResult(RequestPermission())`, expose `cameraGranted` as state to `ArMeasureHostScreen` | inside `ArMeasureActivity.kt` |
| `SongGenerationGate` (host-owned policy, module-owned outcome enum) | **`ArAvailability` stays module-owned; the gate has no host analogue** — ARCore availability is device fact, not host policy. Keep it entirely internal: `ArMeasureKit` becomes `internal`, availability handling + re-poll + `requestInstall` move into `ArMeasureActivity.onResume` + an internal `rememberArAvailability()` | NEW internal `ar-availability-gate.kt`; EDIT `ArMeasureKit.kt` (keep public for now, see caveat) |
| `MusicConfigProvider`, `SettingsNavigator` (host ports) | **`ArMeasureTools` config + optional callbacks** — see §Config below. No remote-config or settings analogue. | NEW `ArMeasureConfig.kt` |
| `PremiumStatusProvider` / `SongGenerationGate.openPaywall` | **no analogue** (no billing in ar-measure). Leave a hole, don't invent one. | — |
| `videoModule` (Koin) | **no Koin in ar-measure** (grepped: zero Koin references, zero DI framework). Replace with plain construction inside the module: `remember { CustomReferenceStore(context) }` moves into `ArMeasureHostScreen`. | inside `ArMeasureHostScreen.kt` |
| `videoHostModule` + `App.kt` `modules(...)` line | **nothing** — host does zero DI wiring. This is a strict improvement over feature-video. | — |
| `BaseComposeActivity` from `:core` (theme/toast/locale injection) | **do NOT copy.** No `:core` in ar-measure and no reason to create one. `ArMeasureActivity : ComponentActivity` + an internal `ArMeasureTheme { }` that defaults to `darkColorScheme()` and can be bypassed | NEW internal `ArMeasureTheme.kt` in `:ar-measure-common` |
| `ArUnsupported` / `CameraDenied` / `CenteredMessage` (host composables) | move into the module as `internal`, with their strings into `ar-measure-ar/src/main/res/values/strings.xml` as `armeasure_ar_unsupported_title` etc. | NEW internal; DELETE from `MainActivity.kt`; DELETE `R.string.ar_unsupported_*` from `app/src/main/res/values/strings.xml` |
| `consumer-rules.pro` + `consumerProguardFiles` | **not needed today** (no Gson/reflection; `app` has `isMinifyEnabled = false`). But add an empty-with-comment `consumer-rules.pro` per module if/when SceneView needs keeps — the AR README §8 already asserts none are required. | (defer) |
| `resourcePrefix` | **already present** in both feature modules. Nothing to do; do not regress it. | — |
| Parcelable `VideoProject` as Intent extra | **`MeasurementResult`** already exists in `:ar-measure-common` as a sealed interface. If results must cross the host boundary via `setResult`, it needs `@Parcelize` + `kotlin-parcelize` on `:ar-measure-common` | EDIT `ar-measure-common/build.gradle.kts` + `MeasurementResult` — **only if** you want result-back (see Open Qs) |
| MVI (`XContract`/`XViewModel`/`MviViewModel`) | **do not adopt.** ar-measure screens are plain composables with hoisted state; a 20-file module doesn't need an MVI kernel. The only state the new host layer holds is `selectedTool` + `availability` + `cameraGranted`. | — |

## Concrete new public API of `:ar-measure-ar` after the change

```kotlin
// vn.apero.armeasure.ar.presentation.host
class ArMeasureActivity : ComponentActivity() {
    companion object {
        fun newIntent(context: Context, config: ArMeasureConfig = ArMeasureConfig()): Intent
        fun start(context: Context, config: ArMeasureConfig = ArMeasureConfig())
    }
}

@Composable
fun ArMeasureHostScreen(
    modifier: Modifier = Modifier,
    config: ArMeasureConfig = ArMeasureConfig(),
    onResult: (MeasurementResult) -> Unit = {},
    onExit: (() -> Unit)? = null,
)
```

`ArMeasureConfig` — the single config data class feature-video never had (it scattered config across 6 Koin ports because it needed *behaviour*; ar-measure needs only *values*, so one Parcelable data class is the right shape here):

```kotlin
@Parcelize
data class ArMeasureConfig(
    val tools: Set<ArTool> = ArTool.entries.toSet(),   // which tabs to show
    val initialTool: ArTool = ArTool.Ruler,
    val unit: LengthUnit = LengthUnit.Metric,
    val showToolBar: Boolean = true,                   // false = single-tool kiosk mode
) : Parcelable

enum class ArTool { Ruler, Photo, Box, Cylinder, Level }
```

Host call site after the change, replacing all of `AppRoot`:
```kotlin
setContent { ArMeasureActivity.start(this) }        // or, embedded:
setContent { MaterialTheme(colorScheme = darkColorScheme()) { ArMeasureHostScreen() } }
```

## Where ar-measure genuinely differs from feature-video

1. **No DI framework.** feature-video's entire ports mechanism is Koin-shaped. ar-measure has no Koin, no Hilt. Do **not** introduce Koin just to copy the pattern — the ports it would carry (paywall, analytics, remote config, settings-nav) all have zero analogue here. Direct construction inside `ArMeasureHostScreen` is the DRY/KISS answer. If a host later needs DI, the composable-with-defaults API already lets them pass in their own instances.
2. **No host ports at all, and that's correct.** feature-video needs 6 because it touches billing, remote config, the host's Settings screen and the host's signed HTTP client. ar-measure touches none of those. The only outward-facing hooks are `onResult` and `onExit` — plain lambdas, no interface needed.
3. **ARCore device gating has no feature-video analogue.** `SongGenerationGate` is host *policy* (a decision the host is entitled to make); `ArAvailability` is device *fact*. Fact belongs inside the module. So unlike feature-video — which pushes the gate out — ar-measure should pull the gate **in**, including the 200ms/3s bounded re-poll and the `requestInstall`→`onResume` dance. That logic is currently duplicated in `MainActivity.kt` and re-documented in `ar-measure-ar/README.md` §5; after the change it exists once, inside the module, and README §5 becomes "nothing to do".
4. **Two independent feature modules, one wrapper.** feature-video is a single module, so this case is new. Options:
   - **(a) Wrapper lives in `:ar-measure-ar`, `:ar-measure-photo` becomes an optional `compileOnly`/reflection dep** — breaks the README's headline promise ("skip `:ar-measure-ar` entirely if you only want photo measuring", "zero ARCore anywhere in the host").
   - **(b) A 4th module `:ar-measure-host`** depending on both, holding `ArMeasureActivity` + `ArMeasureHostScreen` + the tool bar. The 3 existing modules keep their current contracts untouched; a photo-only host still takes `common+photo` and calls `PhotoMeasureScreen` directly.
   - **(c) Two wrappers**: `ArMeasureActivity` in `:ar-measure-ar` (Ruler/Box/Cylinder/Level) and a separate `PhotoMeasureActivity` in `:ar-measure-photo`, with the combined 5-tab picker only in a thin `:app`-level or optional host module.
   **Recommend (b).** It's the only option that gives one trigger point *and* keeps the ARCore-free path intact. Cost: one more `include(":ar-measure-host")`, and the README count goes 3→4.
5. **Theming.** feature-video's theme arrives from a shared `:core` — the pattern's worst part. ar-measure should ship an internal `ArMeasureTheme` (dark scheme, since every screen is a camera overlay) rather than depending on the host's `MaterialTheme`, and rather than creating a `:core`. The host's current `MaterialTheme(colorScheme = darkColorScheme())` in `MainActivity.kt` then becomes redundant and should be deleted with it.
6. **Resources are already prefix-safe.** No work; just don't drop `resourcePrefix` when adding the new strings.
7. **`:app` shrinks to almost nothing.** After the change `MainActivity.kt` is ~15 lines (`onCreate` → `setContent { ArMeasureHostScreen() }` or just launch the module Activity), `ui/AppTabBar.kt` is deleted, and `app/src/main/res/values/strings.xml` loses `ar_unsupported_title`/`ar_unsupported_body`. The camera permission request, the `onResume` availability poll and the `requestCamera` launcher all leave `MainActivity`.

## Files touched, at a glance

**New** (assuming option 4b):
- `ar-measure-host/build.gradle.kts`, `ar-measure-host/src/main/AndroidManifest.xml`, `ar-measure-host/README.md`
- `.../host/ArMeasureActivity.kt`, `ArMeasureHostScreen.kt`, `ArMeasureConfig.kt`, `ar-availability-gate.kt` (internal), `ArMeasureToolBar.kt` (internal), `gate-messages.kt` (internal: unsupported/camera-denied)
- `ar-measure-host/src/main/res/values/strings.xml` (`armeasure_*` keys moved from `:app`)
- `ar-measure-common/.../ArMeasureTheme.kt` (internal-ish; needs `internal` visibility per-module or a `common`-public wrapper)

**Changed:**
- `settings.gradle.kts` → `include(":ar-measure-host")`
- `app/build.gradle.kts` → drop `:ar-measure-ar` + `:ar-measure-photo` direct deps, add `:ar-measure-host`
- `app/src/main/java/vn/quancua/artapemeasure/MainActivity.kt` → gutted to the single trigger
- `app/src/main/res/values/strings.xml` → remove `ar_unsupported_*`
- `ar-measure-ar/README.md` §5 (availability gate) + §4 CAMERA note → "handled by `:ar-measure-host`"
- `ar-measure-photo/README.md` §5 (`CustomReferenceStore` host-constructed, always) → now also constructible by the host wrapper
- `README.md` (root) → module count 3→4, integration section

**Deleted:**
- `app/src/main/java/vn/quancua/artapemeasure/ui/AppTabBar.kt`

---

## Unresolved questions

1. **Option 4a/4b/4c** — is a 4th module acceptable, or is keeping `:ar-measure-photo` ARCore-free no longer a requirement? Everything downstream depends on this answer.
2. **Does anything need `onResult` back to the host?** feature-video returns nothing (`finish()` only). If ar-measure also returns nothing, `MeasurementResult` never needs `@Parcelize` and the Activity needs no `setResult` — significantly simpler. Current `:app` ignores every `onResult` (they're all defaulted).
3. **`ArMeasureKit` — public or internal after the change?** Keeping it public means two ways to gate AR (host-driven and module-driven) and the README has to document both. Making it `internal` is cleaner but is a breaking change to the current 6-symbol public API.
4. **Does `LevelScreen` belong behind the availability gate's UI at all?** It needs no ARCore (gravity sensor only). Under `ArMeasureConfig.tools` it would still be selectable on an `Unsupported` device — confirm that's intended (current `MainActivity` already does exactly this, so probably yes).
5. **`ArMeasureTheme` placement** — `:ar-measure-common` is the only shared module, but Kotlin `internal` doesn't cross module boundaries, so it would have to be public API of `:ar-measure-common` (or duplicated). Duplicating a 10-line theme wrapper in 2 modules may beat widening the common module's API.
6. **Should the skill `trung-apply-ar-measure` (already exists in the catalog) be updated in the same pass?** Its current contract presumably documents the 3-module/5-screen shape and would drift the moment this lands.
7. **Stale path in `trung-apply-feature-video`** — worth fixing (`/Users/admin/StudioProjects/` → `/Users/admin/ahndroidne/StudioProjects/`) plus the `AppTypography`/font-file drift, but that's a separate edit to a user skill and out of scope for this read-only study.
