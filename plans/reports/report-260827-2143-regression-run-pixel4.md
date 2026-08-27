# Regression run on the Pixel 4 — MVI refactor

Date: 2026-08-27 | Branch `refactor/mvi-alignment` @ `f65cc57`
Scenario: `plans/260827-1910-mvi-alignment/regression-test-scenario.md`
Device: Pixel 4 (`flame`), serial `99261FFAZ0077C`, 1080x2280. **Joy_4 not attached.**

## Passed — driven on device, observed

| Case | Result |
|---|---|
| Unit tests | 185 / 0 failures / 2 skipped |
| `:app:assembleRelease` with R8 + resource shrinking | passes; APK signs and installs |
| Cold start, crash, ANR | 0 `FATAL EXCEPTION`, 0 `ANR in` across every run below |
| Reference picker -> select built-in -> `am kill` -> relaunch | **restores past the picker showing "Take a photo with Payment card"** — not the A4 default |
| CAMERA revoked -> "Take photo" | system `GrantPermissionsActivity` appears, app survives (pid intact) |
| Camera capture round trip | Google Camera opens, capture returns, app proceeds **with that photo** ("Tap on the Payment card to mark it") rather than re-showing the picker |
| Camera temp file cleanup | `cache/camera-capture/` empty after the capture was decoded |
| AR screen on an ARCore device | camera preview live, hint "Move your phone to find a surface", chrome drawn, no black screen |
| Tool switching | no new `ARCore-Session` lines after two switches, no crash — consistent with the one-shared-session rule |
| README section 12 structural greps | `rememberEngine` 1, `ARSceneView(` 1, exactly one `key(instanceKey)` wrapping the view; `tool` never in a key |

## The frame comparison

Release builds, same device, same conditions. Baseline built from
`feature/photo-measure-accuracy` (pre-MVI) in a throwaway worktree.

| Build | Cold-start frame skips, 3 runs |
|---|---|
| Before the refactor | 42 / 0 / 0 |
| After the refactor | 0 / 0 / 0 |

No regression. **This does not discharge the phase-04 gate.** The whole argument for keeping the
ARCore frame stream out of MVI `State` rests on the Joy_4 having no headroom; a Pixel 4 has plenty,
so this shows only the absence of a gross regression. The AR run above also recorded a single
59-frame skip at screen entry, which is ARCore session startup, not the steady-state tracking cost
the measurement is actually about.

## Not run, and why

- **Everything needing the Joy_4** (6 cases): the unsupported-AR dialog, and the frame budget on a
  device that actually lacks headroom.
- **Steady-state AR frame rate under sustained tracking**: needs a human pointing the camera at a
  textured surface for 30 s with repeatable gestures.
- **The ruler measurement (X1)** — the only check that catches a quad which *looks* right and
  *measures* wrong. Needs a card, a ruler and eyes.
- **`run-as` on a release build** is refused ("package not debuggable"), so the cache check has to
  run on debug. Noted in the scenario.

## Corrections made while running

Two README section 16 audits reported failures that were not failures. An audit that always fails is
an audit nobody runs, so both were rewritten:

- Audit 2's grep cannot tell a KDoc quotation from a UI string. All five hits are comments quoting
  the Vietnamese design. It now says to read the hits, and defines a real failure as a hit on a
  non-comment line.
- Audit 7 demanded no `Log.` anywhere. Six deliberate lines exist in `SegmentQuad` and
  `CameraCapture`, all on silent-fallback paths where their absence is exactly what made two bugs
  undiagnosable. The audit now permits those six and fails if any starts logging user content.

## Unresolved questions

1. The phase-04 frame claim is still unproven. It needs the Joy_4, and until then "keeping the frame
   stream out of State was necessary" remains reasoning, not evidence.
2. No automated coverage exists for the presentation layer, so every case above was proven by driving
   the device once. A silent regression between runs would not be caught.
3. The custom-reference restore path (the A4 fallback bug) was exercised here with a **built-in**
   reference. The structural fix means there is no A4 fallback anywhere, but the specific async
   ordering that caused the original bug involved the custom list, and no custom object existed on
   this device.
