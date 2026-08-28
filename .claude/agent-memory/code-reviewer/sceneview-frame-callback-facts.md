---
name: sceneview-frame-callback-facts
description: Three non-obvious facts about io.github.sceneview arsceneview 4.31.0 that decide how to review AR_feature's frame path
metadata:
  type: project
---

Established by unpacking `~/.gradle/caches/.../arsceneview-4.31.0-sources.jar` (2026-08-28 review):

1. **`onSessionUpdated` runs on the MAIN thread**, from `LaunchedEffect { withFrameNanos { ... } }`
   (`ARSceneView.kt:1455-1505`). Not a render thread.
2. **Our per-frame callback is wrapped in `catch (Exception) { Log.e("SceneView", "ARCore session
   update failed") }`** (`ARSceneView.kt:1491-1499`).
3. **Callbacks are held in `AtomicReference`s re-`set` from a `SideEffect` each recomposition**
   (`:805`, `:833`, invoked `:1688`), so a lambda capturing `tool`/`unit` is always fresh — a tool
   swap takes effect with no remount, by construction.
4. `ARSceneView` dispose → `ARCore.destroy()` → `Session.close()` (`ARCore.kt:163-171`). So bumping
   `instanceKey` closes the session under any anchor still held elsewhere.

**Why:** each one flips a review verdict. (1) kills every "thread safety of the frame stream"
concern and makes per-frame allocation a direct UI-frame cost. (2) means a bug in the frame path
presents as a silently frozen overlay, never a crash — device rounds must grep logcat for that
string. (3) is the machine-checkable half of README §12. (4) is why a watchdog remount is the
dangerous anchor-lifetime path.

**How to apply:** cite these instead of re-deriving them, but re-check the version in
`gradle/libs.versions.toml` first — all four are version-specific.
See [[measure-module-conventions]].
