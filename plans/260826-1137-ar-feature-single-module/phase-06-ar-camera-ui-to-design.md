# Phase 06 — AR camera UI to the design: mode sheet, chrome, terminal state

## Context Links

- [Plan overview](plan.md) · depends on [phase 05](phase-05-one-shared-arcore-session.md),
  [phase 02](phase-02-four-unit-length-and-unit-menu.md), [phase 03](phase-03-undo-redo-stacks.md)
- Design: `amQof` SCR-18 / `M7fxtw` SCR-19 / `N73eN` SCR-20 (byte-identical overlay clones),
  `ebVJf` MeasureModeSheet, `XCFlV` UnitMenu, `d05XD7`/`WY0bd`/`r1PP73` UnitBtn
- UI review: [`ui-ux-designer-260826-1100-ar-measure-wireframe-review.md`](../reports/ui-ux-designer-260826-1100-ar-measure-wireframe-review.md)

## Overview

- **Priority:** P2
- **Status:** pending
- **Effort:** 3.5h

Dress `ArCameraScreen` to the wireframes: the 3-card mode sheet, the blurred pill chrome, the
crosshair, the toast, and a terminal state the design does not have.

## Key Insights

1. **The design's `ModeBtn` occupies exactly the position of today's inert shutter.** Today's
   `MeasureBottomBar` draws a 46dp bordered circle at `CenterEnd` that is deliberately non-functional
   (its KDoc says so). The design puts `b2zhTH` ModeBtn (44×44, `#FFFFFFCC`, `grid-2x2`) at that
   exact slot. So the shutter is **replaced**, not removed — nothing has to be deleted apologetically
   and no button lies. `CaptureBtn`'s icon is `plus`, confirming it commits a point rather than
   capturing a frame.
2. **The design gives the AR screen no undo/redo and no clear**, but decision 11 requires undo+redo
   everywhere. Place them the way the design already does it on SCR-23: an `UndoForwardGroup`
   centred in the top bar (`h63Wd`, `undo-2` + `redo-2`, gap 40). That leaves the top bar as
   Back | undo/redo | UnitBtn, and frees the bottom bar's reserved-but-empty 44×44 left slot
   (`rXzHX`) for **Clear** — which currently sits where `UnitBtn` now goes.
3. **The mode sheet's contrast fails and its state is colour-only.** Selected label is `#8A9A5B` on
   `#FFFFFF` = **3.05:1** (needs 4.5), and selection is signalled *only* by an olive stroke, an olive
   icon and that olive label — no fill, no check. Fix both: darken text-weight olive to `#6E7C42`
   (≈4.6:1 on white), and make selected = `signatureMuted #8A9A5B1F` **fill** + `#8A9A5B` 2px stroke
   + `#6E7C42` label. Fill is the non-colour cue.
4. **The 10px card label is too small and the pill is 70 tall.** Use 12/600 as the UI review
   recommends; the pill's own height already clears 48.
5. **Every camera overlay is 360 wide inside a 354 screen** (`or1Ys`, `ID3nU`, `lKojV`, `UCjJs` and
   their SCR-19/20 clones), all at `x=0`, so the whole 6px hangs off the right — asymmetric chrome,
   left margin 16 vs right 10. This is fallout from a 360×823 → 354×799 resize that did not reflow
   absolutes. **Implement `fillMaxWidth()` with symmetric 16dp padding and ignore the measured
   values.** Same for the sheet, which is at `x = -3` in the mock.
6. **The ARToast maps 1:1 onto the existing `HintBanner`** — `#1A1D1F` @0.88, r16, 12px background
   blur, white 13/500. Adopt the blur and the corner radius; **implement hug height** (the mock's
   fixed 77px around one 16px line renders as a floating slab and pushes the text off-centre).
   The toast also sits at y=596 in the mock, *under* the sheet — reposition above it.
7. **The design's crosshair is a bare 8px white dot with no state.** The shipped code already
   distinguishes on-surface from off-surface (`OverlayFrame.reticleOnSurface`). Adopt the design's
   size and colour but **keep the two variants** — solid dot when a surface is resolved, hollow ring
   when not. Losing that signal would be a regression dressed as a design fix.
8. **The AR branch has no terminal state anywhere in the document** (verified: `amQof` has 8
   children, none of them a result card; the only completed-state artifacts are on the photo
   screens). So `+` currently "leads nowhere" in the mock. Implement the honest minimum rather than
   inventing a save flow: on commit, the measurement label persists on the overlay, an ARToast
   confirms, and undo/redo/clear stay available. **No AR-frame capture to the gallery** — reading the
   `ARSceneView` framebuffer is real work and out of v1 scope; say so in the README.
9. **`font-mono` (`IBM Plex Mono`) is declared in the token set "for measured values only" and is
   used by nothing.** Every measured value in the AR set is Inter. Follow the implementation, not the
   dead token.
10. **The blurred pill chrome (`#FFFFFFCC` + 8px background blur, dark icons) is genuinely good over a
    camera feed** and is worth adopting — but `Modifier.blur` on API < 31 degrades to a no-op on
    background content. Fall back to a solid `#FFFFFFE0` on API < 31 rather than shipping an
    unreadable translucent pill on `minSdk 24`.

## Requirements

**Functional**
> **Superseded 2026-08-27:** the sheet now carries **4** cards on a 2x2 grid — Distance /
> Distance chain / Box / Cylinder — after the chained tool was split from a new
> independent-segment tool. The "no other entries" rule below still stands for the six mock
> entries (Angle, Polyline, Polyline smooth, Square, Poly smooth, Auto-Detection); only the
> card *count* changed. See `plans/reports/brainstormer-260827-1647-ar-distance-single-segment-mode.md`.

- `MeasureModeSheet`: 3 cards — Distance / Box / Cylinder. **No other entries, not even disabled
  ones** (decision 3). Opened by `ModeBtn`, dismissed by its `×` or a scrim tap.
- Top bar: Back (finishes the Activity), undo/redo group, `UnitBtn` opening the phase-02 `UnitMenu`.
- Bottom bar: Clear (left), `CaptureBtn` `+` (centre), `ModeBtn` (right).
- Crosshair with on-surface / off-surface variants.
- ARToast for the 11 existing coaching/tracking states plus the commit confirmation.
- Terminal state as described in insight 8.

**Non-functional**
- **Every tappable target ≥48dp**, overriding the mock: UnitBtn 40→48, BackBtn 40→48, ModeBtn 44→48,
  sheet close 22→48, UnitMenu rows 32→48. Grow the touch target with padding, keep the drawn size.
- **WCAG AA (4.5:1) for all text, computed over an arbitrary live camera image.** Text over the feed
  never relies on the feed — it sits on a pill (`#FFFFFFCC` with dark text, or `#1A1D1F` @0.88 with
  white text), both of which pass regardless of what the camera sees.
- No hardcoded user-facing string in Kotlin. `armeasure_` prefix, English default.
- `ArCameraScreen.kt` stays under ~200 lines — chrome goes in its own files.

## Architecture

Colour tokens, from the design's `SetVariables`, as one internal Kotlin object (the module owns its
own palette; it must not read the host's theme):

| Name | Value | Note |
|---|---|---|
| `BgPrimary` | `#F4F4F2` | |
| `BgSecondary` | `#EAEAE7` | field fills |
| `BgSurface` | `#FFFFFF` | sheets, cards |
| `TextPrimary` | `#1A1D1F` | |
| `TextSecondary` | `#5C6166` | 5.7:1 on white — passes |
| `TextDisabled` | `#9BA1A6` | **decorative only**, 2.34:1 — never for text that must be read |
| `BorderStrong/Soft/Subtle` | `#1A1D1F4D` / `#1A1D1F24` / `#1A1D1F14` | |
| `Signature` | `#8A9A5B` | fills, strokes, icons — **not text** |
| `SignatureText` | `#6E7C42` | **added**: the AA-passing olive for text/labels |
| `SignatureMuted` | `#8A9A5B1F` | badge + selected-pill fill |
| `OnSignature` | `#FFFFFF` | |
| `Error` | `#B4483C` | destructive actions |
| `Scrim` | `#1A1D1FB8` | |
| `ChromeLight` | `#FFFFFFCC` (API<31: `#FFFFFFE0`) | camera pill fill |
| `ChromeDark` | `#1A1D1F` @0.88 | toast + AR measure label |

Radii: `thumbnail 8`, `listItem/input 10`, `button 12`, `card/image 14`, `dialog 20`, sheet top
`[22,22,0,0]`, pill `999`. Spacing: 4 / 8 / 16 / 24 / 32, screen 20, cta 12. Button height 52,
icon button 40 (→48 target), touch target 48.

Component specs (design values, with the corrections applied):

**TopBar** — `fillMaxWidth`, height 64, pad `[12,16]`, `SpaceBetween`, transparent.
Back: 48dp target / 40dp pill, `ChromeLight`, r999, blur 8, `chevron-left` 22 `TextPrimary`.
UndoForwardGroup: centred, gap 24 (not the mock's 40 — 48dp targets need the room), `undo-2` /
`redo-2` 24, `TextPrimary`, disabled → `TextDisabled` at 38% (decorative, so its low ratio is fine).
UnitBtn: 48dp target / 40dp pill, `ChromeLight`, r999, blur 8, current unit `symbol` 13/600
`TextPrimary`.

**Crosshair** — 24×24 box. On-surface: filled 8dp `#FFFFFF` dot with a 1dp `#00000040` outline.
Off-surface: 8dp hollow ring, 1.5dp `#FFFFFFB3`.

**BottomBar** — `fillMaxWidth`, height 87, pad `[0,32]`, `SpaceBetween`, `alignItems center`.
Clear: 48dp, `ChromeLight` pill, label from `strings.xml`, `TextPrimary` 13/600, disabled when
nothing to clear.
CaptureBtn: 77dp, `Signature`, r999, 3dp `#FFFFFF` stroke, `plus` 30 `OnSignature`; disabled state
`Signature` @38% + no stroke.
ModeBtn: 48dp target / 44dp pill, `ChromeLight`, r999, `grid-2x2` 20 `TextPrimary`.

**ARToast** — width `fillMaxWidth` minus 2×32, **hug height**, `ChromeDark`, r16, blur 12,
pad `[10,16]`, text 13/500 `#FFFFFF`, centred. Positioned above the bottom bar and above the sheet.

**MeasureModeSheet** — `fillMaxWidth`, hug height (~178), `BgSurface`, r`[22,22,0,0]`, no stroke
(the mock's `#111111` 1px is a wireframe artefact), vertical gap 16, pad `[12,20,22,20]`.
Handle 40×4 r2 `BorderSoft`. Header: title 16/700 `TextPrimary` + close (48dp target, `x` 22
`TextSecondary`). One row, 3 equal cards, height 70, r999, gap 10:

| State | Fill | Stroke | Icon 22 | Label 12/600 |
|---|---|---|---|---|
| selected | `SignatureMuted` | `Signature` 2dp | `Signature` | `SignatureText` |
| unselected | `BgSurface` | `BorderSoft` 1dp | `TextPrimary` | `TextSecondary` |

Icons: `ruler` / `box` / `cylinder`. The mock's unselected `#111111` 1px stroke is replaced by
`BorderSoft` — a full-black hairline is a wireframe convention, not a design token.

**AR MeasureLabel** — `ChromeDark` pill, r16, white 13/700, hug. Deliberately **not** the mock's
`#EB3232` (`TOKzn`, white-on-red = 4.1:1 at 13px bold, fails AA): red belongs to the photo path where
the user picks the colour (decision 10 — AR gets no colour selection), so AR can use the accessible
dark pill that already matches the ARToast. Endpoint dots keep a 2dp white halo.

## Related Code Files

**Create**
- `AR_feature/.../common/ui/ArMeasureTokens.kt` (the table above; if phase 04 already added a
  palette to `ArMeasureTheme.kt`, extend that instead of adding a second source of truth)
- `AR_feature/.../ar/presentation/camera/MeasureModeSheet.kt`
- `AR_feature/.../ar/presentation/camera/ArCameraChrome.kt` (TopBar, BottomBar, Crosshair, ARToast)

**Modify**
- `.../ar/presentation/camera/ArCameraScreen.kt` — mount the new chrome + sheet, replace the
  temporary swap affordance from phase 05
- `.../ar/presentation/shared/MeasureControls.kt` — `MeasureTopBar`/`MeasureBottomBar`/`ChromePill`
  are superseded; fold what survives into `ArCameraChrome.kt` and delete the file
- `.../ar/presentation/ruler/MeasureOverlay.kt` — label pill → `ChromeDark`, endpoint halo, crosshair
  variants
- `.../ar/presentation/shapes/ShapeOverlay.kt` — same label treatment
- `.../common/ui/UnitMenu.kt` — rows 32→48, selected label → `SignatureText`, check 14→20, add the
  `#00000033` (0,4) blur-16 shadow and `#11111122` 1dp stroke from `XCFlV`
- `.../ar/presentation/host/ArMeasureHub.kt` — migrate its inline colours to `ArMeasureTokens`
- `AR_feature/src/main/res/values/strings.xml` — sheet title, 3 tool labels, Clear, commit
  confirmation, content descriptions for back/undo/redo/mode/capture/close (≈12 strings)

## Implementation Steps

1. Write `ArMeasureTokens.kt` from the table. Include the *reason* `SignatureText` exists (3.05:1)
   and that `TextDisabled` is decoration-only (2.34:1) in the KDoc — otherwise someone will "unify"
   them back.
2. Write `ArCameraChrome.kt`: TopBar, BottomBar, Crosshair, ARToast. Every interactive element gets
   a ≥48dp target via `Modifier.size(48.dp)` on the click surface with the drawn pill inside.
   Implement the blur with an API-31 guard.
3. Write `MeasureModeSheet.kt` — 3 cards only. Add a KDoc line stating that the 6 other entries in
   the original mock are deliberately absent per decision 3, so nobody "restores" them.
4. Mount both in `ArCameraScreen`, wire `ModeBtn` → sheet → `selectTool`, `UnitBtn` → `UnitMenu`,
   undo/redo → phase 03's stack, Clear → `clear()` on the active holder.
5. Update the two overlays' label pill and the crosshair variants.
6. Bring `UnitMenu` up to spec (48dp rows, shadow, stroke, `SignatureText`).
7. Delete `MeasureControls.kt` once nothing references it.
8. Extract the ≈12 new strings; confirm no user-facing literal remains in the AR presentation
   package.
9. Gate.

## Todo List

- [ ] `ArMeasureTokens.kt` incl. `SignatureText #6E7C42` + the "why" KDoc
- [ ] `ArCameraChrome.kt`: TopBar / BottomBar / Crosshair / ARToast, all targets ≥48dp
- [ ] Blur with an API-31 guard, solid fallback below
- [ ] `MeasureModeSheet.kt` — exactly 3 cards, selected = muted fill + 2dp stroke + `SignatureText`
- [ ] Sheet label 12/600 (not the mock's 10)
- [ ] Crosshair keeps on-surface / off-surface variants
- [ ] ARToast hug height, positioned above the sheet
- [ ] AR MeasureLabel → `ChromeDark` pill + white 13/700 + endpoint halo
- [ ] `UnitMenu` rows 48dp, shadow, stroke, accessible selected label
- [ ] Wire ModeBtn / UnitBtn / undo / redo / Clear / Capture
- [ ] Terminal state: persistent label + commit ARToast, no fake save button
- [ ] Delete `MeasureControls.kt`
- [ ] ≈12 new strings; zero user-facing literals left in `ar/presentation/**`
- [ ] Gate: `compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease`
- [ ] On-device (human, real textured surface): measure with each of the 3 tools via the sheet
- [ ] On-device: switch unit mid-measurement, confirm every label re-renders
- [ ] On-device: point at a plain wall and a glossy surface, confirm the crosshair shows the hollow
      variant and `+` stays disabled
- [ ] On-device: screenshot each chrome element over a *bright* camera image and confirm text is
      still legible (the AA requirement is over an arbitrary feed, not over the mock's background)

## Success Criteria

- 92 tests still pass. This phase is UI composition; it adds no pure logic and therefore **no new
  tests** — stated deliberately rather than padded.
- `assembleRelease` green; `resources.txt` still lists every `armeasure_*` string as reachable.
- `git grep -nE 'Text\("|text = "' AR_feature/src/main/java/vn/apero/armeasure/ar` returns only
  glyphs (`+`, `↩`, `↪`, `×`) — no prose.
- `git grep -n 'Angle\|Polyline\|Auto-Detection\|Poly smooth' AR_feature` returns nothing.
- Every interactive node in the AR chrome measures ≥48dp — verify with one `uiautomator dump`,
  grep the bounds, then **delete the dump file**.
- Contrast: each text/background pair in `ArMeasureTokens` documented with its computed ratio; all
  ≥4.5:1 except the two explicitly decorative entries.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| Growing 40/44dp pills to 48dp targets breaks the mock's spacing and the bar looks cramped | medium | keep the drawn pill at its design size, grow only the click surface; verify on-device at 354dp-equivalent width |
| `Modifier.blur` no-ops on API 24–30 → unreadable translucent chrome | **high** | explicit API guard to a solid `#FFFFFFE0`; test on an API-24-ish emulator or reason from the guard |
| Deviating from the mock's `#EB3232` label reads as "not following the design" | medium | documented: 4.1:1 fails AA, AR has no colour picker, and `ChromeDark` already appears in the same design as the ARToast |
| The sheet covers the crosshair, so the user cannot see what they are aiming at while choosing a tool | medium | sheet is 178 tall at the bottom; crosshair is at y≈407 — clear. Confirm on-device |
| Someone adds the 6 out-of-scope tools back as "coming soon" tiles | medium | KDoc + the grep assertion in Success Criteria |
| `TextDisabled #9BA1A6` reused for real text somewhere | medium | KDoc marks it decoration-only; phase 09 audits |

## Security Considerations

- No new permissions, dependencies, storage or network.
- The AR screen still has no export/save path, so no bitmap ever leaves the process in this phase.
- **Do not add** an AR frame-capture button to satisfy the missing terminal state — it would need
  framebuffer access and a gallery write, i.e. a new permission surface, for a feature nobody asked
  for. Out of scope, recorded.
- Content descriptions on every icon-only control (accessibility, and it keeps the strings in
  `strings.xml` where phase 09's audit can see them).

## Next Steps

- Phase 07 starts the photo branch (reference objects).
- Phase 09 audits the string and contrast work done here.

---

## Design update — 2026-08-26 14:06 (supersedes the button-arrangement reasoning above)

The designer rearranged the AR chrome. Verified against the LIVE document via pencil MCP (the
on-disk `.pen` is stale). Three screens now express three states of one screen, consistently:

| Node | State |
|---|---|
| `M7fxtw` SCR-19 | idle — no sheet, no menu |
| `amQof` SCR-18 | `MeasureModeSheet` open (`ebVJf` @0,621, 354x178) |
| `N73eN` SCR-20 | `UnitMenu` open (`XCFlV` @216,160, 132x146, `cm` row checked) |

**What moved:** `ModeBtn` left the BottomBar and is now stacked ABOVE `UnitBtn` inside a new
`ModeUnitStack` frame @300,12 (44x92) in the TopBar. So:

- TopBar: `BackBtn` @16,12 40x40 (`chevron-left`) + `ModeUnitStack` → `ModeBtn` 44x44 (`grid-2x2`)
  at y=0, `UnitBtn` 40x40 (text label, e.g. "cm") at y=52.
- BottomBar: **only** `CaptureBtn` 77x77 @142,5 with a 30x30 `plus` icon. No Spacer, no ModeBtn.
- `Crosshair` @168,395 24x24 with an 8x8 dot; `ARToast` @27,596 290x77.

**The earlier note that "ModeBtn sits at the inert shutter's position so the shutter is replaced" is
now void** — the shutter position is the capture button, and the mode control lives in the TopBar.

### Three defects to implement around (do NOT reproduce the mock literally)

1. **`ARToast` overlaps the sheet.** Toast spans y 596–673; the sheet starts at y 621 → 52px
   overlap, and the toast is still present in `amQof`'s tree. Hide the toast while the sheet is open.
2. **Touch targets under the design's own `size-touchTarget: 48`**: `UnitBtn` 40, `BackBtn` 40,
   `ModeBtn` 44. Implement all three at 48dp minimum (locked decision), accepting a small visual
   divergence from the mock.
3. **`CaptureBtn` is off-centre by 3.5px** — x=142 in a 360-wide bar centres at 180.5, but the screen
   is 354 wide (true centre 177). A consequence of the unresolved 360-vs-354 overlay mismatch. Centre
   it on the real width instead of copying x=142.
