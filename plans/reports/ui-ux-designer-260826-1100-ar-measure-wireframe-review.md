# AR/Photo Measure Wireframe Review — AIP936

**Date:** 2026-08-26
**Design:** `/Users/admin/Downloads/AIP936-wireframes/aip936-home-design.pen`
**Scope:** review only. No design or source modified.
**Verdict up front:** flow is ~70% sound, chrome patterns are good, **but the `MeasureModeSheet` is the problem** — 9 of its 11 entries are unimplemented, its 3rd row is geometrically clipped out of the sheet, and our two most-invested AR features (Box, Cylinder) do not appear in it at all. Do not build this sheet as drawn.

---

## 0. Source-of-truth caveat (read first)

The `.pen` on disk at `~/Downloads/AIP936-wireframes/` (mtime `09:39`) is **stale**. It contains SCR-01…SCR-20 only, with frames at 360×823. The live document (autosave `~/.pencil/backup/86155aa…`, mtime `11:09`) is what this review covers: all frames 354×799, plus **SCR-21 AR Measure, SCR-22 AR Confirmed, SCR-23 Edit Line, and a SCR-24 AR Adjust that was not in the brief**. `exports/aip936-design.html` (09:47) is also stale.

→ Ask the designer to save. Anyone else reviewing the file on disk will review the wrong design.

Note: the `.pen` is plaintext JSON in this version, and the pencil MCP tools were not in my toolset, so structure/geometry/tokens were read directly from the JSON. Layout and contrast figures below are computed from the flex tree rather than eyeballed from screenshots — more precise, but no visual render was inspected.

---

## 1. UX review, screen by screen

### Design system — good, but the screens violate it
Tokens are well-formed: `space-*` 4/8/16/24/32, `radius-*`, `size-touchTarget: 48`, two fonts (Inter + IBM Plex Mono), a signature olive `#8A9A5B`. The system is not the problem; adherence is.

**Contrast failures (computed, WCAG 2.1 AA):**

| Usage | Pair | Ratio | Verdict |
|---|---|---|---|
| Selected tool label "Distance" 10px/600 | `#8A9A5B` on `#FFFFFF` | **3.05:1** | fail (needs 4.5) — the *selected* item is the least legible |
| "Lưu" primary action SCR-23, 14px/700 | `#8A9A5B` on `#F4F4F2` | **2.78:1** | fail — worst offender, it's the save button |
| SCR-15 subtitle line 2, 16px/600 | `#8A9A5B` on `#F4F4F2` | **2.78:1** | fail |
| BottomNav active label "MEASURE" 10px/600 | `#FFFFFF` on `#8A9A5B` | **3.05:1** | fail |
| "Thêm" / "Lưu" sheet CTA 15px/700 | `#FFFFFF` on `#8A9A5B` | **3.05:1** | fail |
| "15 cm" measure label 13px/700 | `#FFFFFF` on `#EB3232` | **4.1:1** | fail (13px bold ≠ AA "large") |
| "cm" field placeholder, FeatureCard chevron | `#9BA1A6` on `#FFFFFF` | **2.34:1** | fail (also fails 3:1 for UI) |
| Body/secondary text | `#5C6166` on white / `#F4F4F2` | 5.7 / 5.3 | pass |
| ARToast, instruction box | white on `#1A1D1F` / `#1a3a4a` | >10 | pass, good |

The olive signature is the root cause: at 3.05:1 vs white it can carry **icons and fills but not text**. Either darken it (~`#6E7C42` gets ≈4.6:1 on white) or never set text in it.

**Touch targets — design defines `size-touchTarget: 48` then ignores it:**

| Element | Size | Verdict |
|---|---|---|
| Sheet grid pills | 90×70 | pass |
| Auto-Detection cards | 141×68 | pass |
| CaptureBtn `+` | 77×77 | pass |
| ModeBtn | 44×44 | min, ok |
| TopBar Back / Settings | 40×40 | fail (<44) |
| Sheet Close ×, SCR-24 Close/Check | bare 22–24px icons | fail |
| SCR-23 Undo / Redo | 24 icon + 8 pad = 40 | fail |
| SCR-23 "Lưu" | text only ≈30×20 | fail |
| SCR-24 colour dots | 20×20 | fail (5 dots, centres ~60px apart — hit slop is available, just not declared) |
| SCR-24 "cm" badge | 40×36 | fail |
| SCR-15 card pencil | 16×16 | fail, **and nested inside a tappable card** → ambiguous tap |
| SCR-15/16/17 Back chevron | bare 26px | fail |

**Chrome legibility over live camera — this part is right.** `#FFFFFFCC` + 8px background blur pills with dark icons, and the ARToast at `#1A1D1F` 0.88 + 12px blur, are the correct pattern and better than what we ship today. Endpoint dots with a white 2px stroke (halo) likewise. Preserve all three.

### SCR-14 AR Measure (hub)
- Two `c/FeatureCard` refs, both `height: fill_container` inside a `fill_container` column → each card stretches to ≈250px tall. The component is a compact 320×~88 horizontal row (56px badge + 2 text lines + chevron, `alignItems: center`). At 250px it renders as a nearly empty box with a tiny row floating in the middle. **Layout bug, not a style choice** — set the cards to hug and put the slack in a spacer.
- Two dead spacer frames (`gap` 32 + `gap2` 24) stacked after the cards — leftovers.
- Card copy is genuinely good: "Point your camera and measure in real time" vs "Measure objects and distances from a photo you already have". Keep verbatim.
- Screen sits inside a 3-tab host nav (HOME / EXPLORE / MEASURE, MEASURE active). Implies this feature is a **tab of a host app**, which contradicts the planned `AR_feature` single-Activity + `start(context)` entry point. Needs a decision.
- Entirely English while every downstream screen is Vietnamese.

### SCR-15 Reference Object
- **The screen title appears twice, identically**: TopNav `Title` = "Chọn đối tượng tham chiếu" and Subtitle `Line1` = "Chọn đối tượng tham chiếu", stacked ~30px apart. Reads as a copy/paste bug.
- Subtitle splits one sentence across two colours (line 1 `textPrimary`, line 2 olive at 2.78:1). Using the brand colour for a subordinate clause is arbitrary and illegible.
- 6 cards, 2 cols, 176px tall, total ≈696px of 799 — fits with no bottom CTA, so selection must navigate immediately. Fine, but no scroll affordance for when the user has 10 custom refs.
- Built-in dims rounded to **"21 x 30 cm"** (A4 is 210×297) and **"5 x 9 cm"** (payment card is 53.98×85.60). For an app whose accuracy *is* the product, advertising a wrong reference size is a trust bug. It mirrors an existing hardcoded string at `ReferencePickerScreen.kt:85`.
- Two custom entries both named "điện thoại" (7×16 and 7×15). The design accidentally demonstrates the duplicate-name problem — and our store keys by label with no id.
- `info` icon top-right → no info screen or dialog exists anywhere in the file.
- No empty state for "no custom references yet".

### SCR-16 Reference · Add
- Base is byte-for-byte the same tree as SCR-15/SCR-17 (same 6 cards, same strings) + `Scrim` + `AddSheet`. **Confirmed: 15/16/17 are one base screen with overlay variants. That is intentional and correct practice** — keep it, just make the base a component so the three copies can't drift.
- AddSheet asks Name + Chiều dài + Chiều rộng + a unit dropdown. Matches our data model exactly (name + 2 side lengths, no photo). No scope creep — good.
- But: "cm" appears as disabled placeholder text *inside both dimension fields* **and** as a separate `cm ▾` selector in the same row. Three "cm" in one row. Drop the in-field ones.
- Name field has no placeholder/hint at all.
- No validation state, no error state, no empty-name handling.
- Sheet is `y=498`, height 325 → an on-screen keyboard (≈300px) will cover the whole form including the "Thêm" CTA. Needs an ime-aware variant.

### SCR-17 Edit Phone — **broken composition**
SCR-17 contains the base list + a full-screen `Scrim` and **nothing else**. The edit sheet it needs (`fgWMc`, titled "Chỉnh sửa điện thoại", with Tên / Chiều dài / Chiều rộng / **"Xoá đối tượng tham chiếu"** / "Lưu") is a **top-level orphan floating in the canvas gutter at x=8462**, never parented into the frame. So the screen currently renders as a greyed-out list with no dialogue — a dead end.

Also: the orphan sheet is 368×368 while SCR-16's is 360×325, and it has no Close ×, whereas SCR-16's does. Two divergent copies of the same component.

### SCR-18 / 19 / 20 Camera AR
Three full copies of the same camera screen (not component instances) with different overlays: 19 = bare, 18 = + MeasureModeSheet, 20 = + UnitMenu. Same overlay-variant pattern as 15/16/17 — fine — but full duplication invites drift, and drift has already happened (see status bars below).

- **Absolute children are 360 wide inside 354 frames.** `CameraView` 360×823, StatusBar/TopBar/BottomBar all width 360, in a 354×799 clipped frame. The right 6px is cut, so chrome is asymmetric: left margin 16, right margin 10. Same artifact on SCR-16/17 (`Scrim` 360×823) and SCR-21/22/23 (`AR Content Image` **795×348** inside a 354-wide area). All are fallout from a 360×823 → 354×799 resize that didn't reflow absolutes. Re-run the resize.
- **Three different status-bar treatments in one file**: SCR-14 uses `ref o4AeLd`; SCR-15/16/17 use a local frame with time + 4 icons; SCR-18/19/20 use a local frame with time only. Pick one.
- `ARToast` is a fixed 77px tall for one line of 13px text (padding 10/8) — hugely over-tall, will read as a floating slab. Also positioned `y=596`, i.e. *underneath* the sheet on SCR-18, so it's occluded there (probably fine behaviourally, but undesigned).
- **One toast string for the whole AR experience**: "Đưa camera hướng về phía đối tượng cần đo". We ship **11** coaching states (warming up / move to find surface / aim at surface / tap to start / reading unsteady / dragging point + 5 ARCore `TrackingFailureReason` strings: low light, too fast, not enough texture, camera busy, …). The design has no slot for the other 10, and no visual distinction between "coaching" and "error".
- `SettingsBtn` in the TopBar → **no settings screen designed anywhere in this feature**, and none exists in code.
- **`UnitMenu` (SCR-20) has no trigger.** It floats at x=216,y=108 anchored to nothing. The TopBar has only Back and Settings. Whatever opens it is undesigned.
- Crosshair is a bare 8px white dot in a 24px frame — no state variants (no "locked / not locked" distinction). Our reticle already differentiates solid-dot vs hollow-ring on steady-surface lock; the design loses that signal.
- Bottom bar: `Spacer 44 · Capture 77 · ModeBtn 44` with `space_between` at 32px side padding. The Capture is not actually centred (the spacer is 44, the ModeBtn is 44 — it *is* symmetric, fine), sits at y=700–787 → good thumb zone. Good.

### MeasureModeSheet (`ebVJf`) — **the third row is clipped out of the sheet**

Arithmetic from the tree (fixed `height: 353`, `clip: true`, `padding [12,20,22,20]`, `gap 16`):

```
HandleWrap                 4
Header (22px icon)        22
AutoWrap    4+20+8+68+4 =104
GridWrap  8+70+8+70+8+70+8 = 242
3 × top-level gap 16      48
padding 12 + 22           34
------------------------------
content required         454
declared height          353   → 101px overflow, clipped
```

Sheet is pinned to the bottom (446 + 353 = 799 exactly). **The entire third grid row — Poly smooth, Rectangle, Circle — plus part of row 2's gap is invisible.** Either the row is unintentionally hidden, or the sheet has to be ~455px = **57% of the screen**, which for a picker over a live camera is too much.

Other sheet issues:
- Width 360 at `x = -3` inside a 354 frame → 3px bleed each side; the rounded top corners fall outside the viewport.
- **Labels are 10px.** "Polyline smooth" at 10px in a 90px pill will wrap to two lines or truncate; the pill is `justifyContent: center` with a fixed 70px height, so a wrap will push the icon.
- Selected state is signalled *only* by colour (olive 2px stroke + olive icon + olive label at 3.05:1). No fill, no checkmark. Colour-only state fails for colour-blind users and is the least legible element on screen.
- Unselected pills mix `textPrimary` icons with `textSecondary` labels — icon reads louder than its own label.
- **"Rectangle" and "Circle" each appear twice** — once under Auto-Detection (icons `square` / `circle`), once in the grid (icons `rectangle-horizontal` / `circle`). Two entries, same label, no way for the user to know the difference. The grid "Circle" and the auto "Circle" even use the *identical* icon.
- **"Polyline" / "Polyline smooth" / "Poly smooth"** — three near-identical labels. "Poly smooth" (icon `circle-dashed`) is presumably "Polygon smooth" i.e. a closed curved region; as written it looks like a truncation bug. Naming is unfinished.
- **"Square" next to "Rectangle"** is not a meaningful distinction for a measuring tool, and it actively contradicts our design: our Box base is a **deliberate parallelogram, not forced to 90°**.
- 11 tools with no grouping logic beyond "auto vs manual", no descriptions, no disabled/coming-soon state. This is a tool dump, not a picker.
- Sheet + header are English inside an otherwise Vietnamese flow.

### SCR-21 AR Measure / SCR-22 AR Confirmed
Despite the name, these are **not** camera-overlay screens: light `#F4F4F2` background, a dark-teal instruction box, and a device screenshot (`image-14.png`) dropped into a 345px content area at 795×348. Rough placeholders.

- SCR-21 has **no forward action at all**. Content ends at y≈529, leaving ~270px of empty white above the bottom edge, with no CTA. SCR-22 adds a 100×100 check FAB. So the 21→22 transition is "tap the object in the photo" — but **the instruction text is identical on both screens** ("Nhấp vào điện thoại để đánh dấu nó và điều chỉnh kích thước"), so the user gets no confirmation that the state advanced other than a button appearing. The instruction must change on 22 ("Kéo các góc để khớp với …" / "Xác nhận nếu đúng").
- 100×100 FAB with a 66px glyph is oversized (Material FAB is 56, extended 72). Also named `PlusIcon` while carrying a `check` glyph — leftover.
- Frame stroke is `#666666` 2px here vs `#111111` 1px on SCR-14…20 → SCR-21–24 are visibly a later, less-finished batch.

### SCR-23 Edit Line
- Top bar packs Back · Undo · Redo · "Lưu" — **the primary action sits in the top-right, the hardest corner to reach on a 799-tall frame**, while the bottom toolbar carries two secondary tools. Priority is inverted.
- Undo/Redo at 40px hit area, 40px apart, in the top bar; no disabled states shown for either.
- Content Area is `fill_container` wrapping a fixed 345px inner → another large dead band.
- Bottom toolbar has 2 items ("Đoạn thẳng" `minus` icon, "Chỉnh sửa tỉ lệ" `scaling` icon), each `fill_container` with 32px gap → two enormous half-width targets for what are effectively a mode switch and a settings action. Reads unfinished.
- Terminology drift: this screen calls the object "Đoạn thẳng" (line segment); SCR-24 calls it "Chiều dài" (length).

### SCR-24 AR Adjust
- Confirm (`check`) is a bare 24px icon in the **top-right**; the colour picker occupies the bottom 96px. Again inverted priority, and `×` top-left destroys work with no confirmation.
- Layout has a **180px dead band**: magnifier 102–252, photo 256–523, nothing 523–703, colour bar 703–799.
- Magnifier is **pinned top-left permanently**. Ours follows the finger and clamps on-screen — ours is better; don't regress to a static loupe.
- Colour dots 20px; the selected one is signalled by a 3px white stroke — on a light `#F4F4F2` bar, white-on-light is nearly invisible. Use a ring + size bump.
- The `cm` badge is the only plausible unit trigger in the whole design; if that's the intent, it needs to look tappable (chevron) and be ≥44px.
- No unit-conversion feedback, no "measurement locked" state, no delete-line action.

### Language mix — will read as unfinished
Exact split from the string inventory:
- **English:** SCR-14 (hub, all copy), MeasureModeSheet (title, "Auto-Detection", all 11 tool labels).
- **Vietnamese:** SCR-15/16/17 (reference flow), SCR-18/19/20 toast, SCR-21/22 instruction, SCR-23, SCR-24.

This exactly mirrors a code smell we already have: `ar-measure-photo` is hardcoded Vietnamese string literals with no resources; `ar-measure-ar` uses English `strings.xml`. The design has inherited the split rather than resolving it. A user hitting SCR-15 (Vietnamese) → camera → sheet (English) sees a half-translated app. Also, 5 of the AR module's hint strings are hardcoded English in Kotlin, not resources — so even "just translate it" is blocked until those are extracted.

---

## 2. Flow review

Reconstructed:

```
[host nav: MEASURE tab]
SCR-14 hub
 ├─ "AR Measure" card ─────► SCR-19 Camera AR ──ModeBtn──► SCR-18 (camera + sheet)
 │                                │                              │ pick tool
 │                                │◄─────────────────────────────┘
 │                                │  "+" places points  ──►  ??? NOTHING
 │                                └─ SCR-20 (camera + UnitMenu, no trigger)
 └─ "Picture Measure" card ─► SCR-15 reference picker
        ├─ "Thêm đối tượng mới" ─► SCR-16 (scrim + AddSheet)
        ├─ pencil on card ───────► SCR-17 (scrim + NOTHING — sheet orphaned)
        └─ select reference ─────► ??? MISSING photo pick/capture screen
                                    └─► SCR-21 mark reference
                                         └─► SCR-22 confirmed (check)
                                              └─► SCR-23 edit line ⇄ SCR-24 adjust
```

**Dead ends and gaps:**

1. **The AR branch has no terminal state.** No confirmed / result / save screen exists for AR at all. You pick a tool, place points, and then the design stops. SCR-22 "AR Confirmed" is misnamed — it is the *photo* path (photo content + the same instruction as SCR-21).
2. **Missing screen: photo pick/capture.** Between "select reference" and SCR-21 the user must choose camera vs gallery (our code has a `PickPhotoSheet` and a "Chụp ảnh có <ref>" waiting state). Not designed.
3. **SCR-17 is a dead end** (scrim with no sheet — the sheet is an unparented sibling).
4. **UnitMenu has no entry point** (SCR-20).
5. **Settings button has no destination** (SCR-18/19/20).
6. **Info button has no destination** (SCR-15/16/17).
7. **SCR-23 ⇄ SCR-24 relationship is undefined.** 23 is titled "Edit Line" and owns Save; 24 is where endpoints are actually dragged. Two adjacent steps with two different confirm affordances ("Lưu" text top-right vs a `check` icon top-right).
8. **"Lưu" leads nowhere** — no saved-measurements list, gallery, or share screen exists.
9. **Missing states across the board:** camera-permission denied; ARCore unsupported (we ship an `ar_unsupported_body` string for this); the 10 coaching/tracking-failure states; `autoFitQuad` failure → manual 4-corner (our *most likely* photo failure path); photo loading; any error; empty custom-reference list; scroll for a long reference list.
10. Navigationally there is a back affordance on every screen, so nothing is unescapable — the failure mode is branches that stop, not screens you get trapped in.

**SCR-16 vs SCR-17:** confirmed identical base + different overlay. Intentional and good; SCR-17's orphaned sheet is a mechanical slip to fix. Same pattern for SCR-18/19/20, but those are full duplicates rather than instances — make the camera base a component.

---

## 3. Capability gap analysis — `MeasureModeSheet`

Ground truth verified against source. Today we ship 5 tools (AR ruler chain, AR Box, AR Cylinder, photo reference measure, bubble Level[being deleted]) behind a 5-tab bar in one Activity. There is no tool-picker sheet today.

| # | Sheet entry | Status | What exists | Work to ship | New capability? |
|---|---|---|---|---|---|
| 1 | **Distance** (grid, selected) | **Implemented** | AR ruler, `MeasureScreen.kt` → `ArMeasureRulerScreen`. Aim-and-tap-`+`, points draggable, per-segment label via `measureDistanceMeters` | 0 (wire into sheet) | no |
| 2 | **Polyline** (grid) | **Effectively implemented** | Our ruler *is* a chain — `MeasureFrameLoop.kt:131` loops `0..size-2`, one label per segment | ~hours if all that's wanted is a running total label; **it is not a separate tool** | no |
| 3 | **Line & Height** (grid) | **Partial** | The analytic construction-plane ray-cast for height already exists as Box/Cylinder step 3–4 (not a hit test) | 1–2 d: new 2-phase machine reusing `ShapeMath` height cast | no — thin variation |
| 4 | **Angle** (grid) | **Not at all** | Nothing. Only `LevelScreen` gravity tilt + internal Hough `normalizeAngle` | 1–2 d: 3 points → `acos` of edge dot product, arc rendering on the Canvas overlay, degree label. `README` lists Angle as explicitly unimplemented | no — trivial geometry on existing point placement |
| 5 | **Rectangle** (grid, flat) | **Partial** | A rect base exists **only as intermediate state** `ShapePhase.SizingEdgeV`, which must be extruded to finish. Base is a **parallelogram, not forced to 90°** | 1–2 d: terminal phase after edge V, U×V label. **Blocked on area units** if area is expected (m²/ft² — `LengthUnit` cannot express it) | no — but forcing 90° reverses a deliberate decision |
| 6 | **Circle** (grid, flat) | **Partial** | Same: `SizingCircle` is intermediate-only | ~1 d: terminal phase, ⌀ label (+ area → same unit blocker) | no |
| 7 | **Square** (grid) | **Not at all, and contradictory** | — | Redundant with Rectangle; forcing equal sides + right angles **directly contradicts** our deliberate non-90° parallelogram base | **recommend cut** |
| 8 | **Polyline smooth** (grid) | **Not at all** | — | 3–5 d: 3D spline fit (Catmull-Rom/Bézier) + arc-length integration. **Semantically dubious** — a "smooth" length is an interpolation, not a measurement; the number is unfalsifiable and we'd own the accuracy complaint | yes, and **recommend cut** |
| 9 | **Poly smooth** (grid) | **Not at all** | Neither closed polygons nor area exist. (`QuadFromEdges.kt:89` has a shoelace area used only as an internal validity check, never displayed) | 5+ d: close-the-loop UX + curved-region area + an area-unit story that doesn't exist | yes, **recommend cut** |
| 10 | **Rectangle** (Auto-Detection) | **Not at all in live AR** | `AutoFitQuad.kt` (Canny + Hough) runs on a **still bitmap** in the photo module only. No ML, no live detection, and the quad has no 3D | **2–4 weeks, HIGH risk.** Needs: CPU image acquisition from the ARCore frame (YUV→grey), throttled off-thread edge detect + quad fit, back-projection of the 4 image-space corners onto a tracked plane, temporal stabilisation so the highlight doesn't jitter, a confirm gesture, and a "nothing found" state. Canny per-frame at even 320px needs a worker + likely GPU/ML Kit. Accuracy also depends on the quad being coplanar with a tracked plane | **yes — genuinely new capability** |
| 11 | **Circle** (Auto-Detection) | **Not at all**, worse than #10 | — | Hough *circle* is a different transform; a circle in perspective is an **ellipse**, so you need ellipse fitting then back-projection to a plane circle. Strictly harder than #10 | **yes — genuinely new capability** |

### Box and Cylinder: absent. This is the headline problem.

**Neither Box nor Cylinder appears anywhere in the sheet.** "Rectangle", "Circle" and "Square" all read as flat 2D shapes on a plane (`square`, `rectangle-horizontal`, `circle` glyphs; grouped with Distance/Angle/Polyline, all 1D/2D tools). They are **not** the same thing as our 3D volumes:

- Box = 4 taps, parallelogram base + extrusion along the plane normal, result `Box(lengthU, lengthV, height)`, label `"L x W x H"`.
- Cylinder = 3 taps, circle-from-centre + extrusion, result `Cylinder(radius, height)`, label `"⌀D x H"`.
- Neither computes volume today.

Two readings, both bad:
1. *Literal:* the sheet is flat-only → **shipping it deletes Box and Cylinder from the product**, i.e. throws away our two most-invested AR features and replaces them with unbuilt flat variants of their own intermediate states.
2. *Loose:* "Rectangle"/"Circle" are meant to *be* Box/Cylinder → then the labels, icons and grouping are all wrong, the user has no idea a height step is coming, and "Square" is meaningless.

**Recommended question to the designer (verbatim):** *"Are 'Rectangle' and 'Circle' flat shapes measured on a single surface (length × width, diameter), or are they our existing 3D Box and Cylinder — the 4-tap and 3-tap tools that add a height and report L×W×H and ⌀D×H? If they're flat, where do Box and Cylinder live in this design? If they're the 3D tools, they need volume-shaped icons, 3D labels, and 'Square' should go."*

---

## 4. Design-vs-module mismatches beyond the sheet

| Design | Reality | Impact / work |
|---|---|---|
| `UnitMenu`: **cm / m / inch / ft** (4) | `LengthUnit { Metric, Imperial }` (2). `formatMeters()` **always** suffixes `" m"` — 4cm renders `"0.04 m"`. `formatImperial()` is always `feet' inches"` rounded to whole inches | **SCR-24's "15 cm" label is impossible to render today.** Prerequisite for everything: extend to 4 display units, touch `formatLength` + 6 call sites (`MeasureFrameLoop`, `ShapeFrameLoop`, `ShapeMath.formatBox/CylinderDimensions`, `PhotoQuadCanvas`). ~1–2 d. Also needs **persistence** (no DataStore exists; today the m/ft chip is per-screen and resets on tab switch) and the photo path's `toggleUnit()` has no UI at all |
| `ColorPickerBar`: 5 per-line colours | Zero. Every stroke is a private hardcoded literal in 5 files (`MeasureOverlay.kt:17-19`, `ShapeOverlay.kt:41`, `PhotoQuadCanvas.kt:36`, `QuadEditorCanvas.kt:30-32`). Only `drawLabelPill(backgroundColor)` is parameterised | ~2–3 d: colour on the measurement model, thread through overlays, picker UI, persist last-used. **Two extra problems:** (a) `QuadEditorCanvas` uses cyan/yellow **semantically** (long edge / short edge) — user colours would collide; (b) user-picked colours over an arbitrary camera image need a mandatory halo/outline or they vanish. Low product value; recommend deferring |
| SCR-23: Undo **+ Redo** + **"Lưu"** | Undo: yes on AR (`MeasureState.undo()`, `ShapeMeasureState.undo()`), **none on the photo path** (only `resetLine()`). Redo: **does not exist anywhere** — no stack, popped items discarded. Save/export: **does not exist anywhere** — no Room, no DataStore, no file write of results; the shutter button in `MeasureControls.kt:110` is deliberately inert per its own KDoc | Redo ~1 d on the ruler, awkward on the shape phase machine. **Save is undefined product, not undefined code**: a screenshot? a re-openable project? Needs a destination screen that doesn't exist. 1–2 weeks if it means persistence; ~2 d if redefined as "export image to gallery". Note SCR-23 is on the *photo* path, which has no undo at all → 2 of its 3 top-bar actions are fiction |
| TopBar `SettingsBtn` | No settings screen, no DataStore, no SharedPreferences for options (the only prefs store is `CustomReferenceStore`) | Either design it (unit, language, haptics, reset) or drop the button. Note the host app already has SCR-08 Settings — confirm whether this deep-links there |
| SCR-23 **"Chỉnh sửa tỉ lệ"** (edit scale) | **Architecturally blocked today.** Once `confirmReference()` sets a non-null homography, `PhotoQuadCanvas` takes the `else` branch and `QuadEditorCanvas` (the only path to `moveQuadCorner`) becomes unreachable — `moveQuadCorner` is dead code. To change scale you must discard the photo ("Chọn ảnh khác") and start over | 1–2 d: re-enter the quad editor keeping photo + line, recompute homography, re-project the existing line (its image-space coords are preserved, so this is straightforward). **Good idea — fixes a real product gap. Keep it.** |
| SCR-15 pencil + edit sheet with **"Xoá đối tượng tham chiếu"** | `CustomReferenceStore` is `loadAll()` / `add()` only, both `internal`. **No update, no delete, no rename.** A mistyped custom reference is permanent. Stores `label` + `shortSideMm` + `longSideMm` and nothing else — **no id**, so two refs named "điện thoại" (which the design shows!) cannot be addressed distinctly | ~0.5–1 d, but requires a schema change (add stable id) + migration of existing stored JSON. **Keep it — it fixes a genuine defect** |
| SCR-15 built-in dims "21 x 30 cm", "5 x 9 cm" | Code uses correct 210×297 / 53.98×85.60. The rounded strings match a hardcoded label at `ReferencePickerScreen.kt:85` | Show exact dims. Cheap, and it's an accuracy-credibility issue |
| Custom-ref avatar = initials, no photo | Matches implementation exactly (`reference.label.take(2).lowercase()`) | Nothing to do. Good |
| SCR-14 hub = 2 paths | Code has 5 tabs (Measure / Photo / Box / Cylinder / Level) | The 2-card model only works if Box + Cylinder live in the sheet. See §3 |
| SCR-14 sits in a host bottom nav (HOME/EXPLORE/MEASURE) | Plan is one merged `AR_feature` module, single Activity + `start(context)` | Tab vs launched-Activity is an architecture decision the design has silently made |
| 1 toast string | 11 coaching strings driven by `TrackingFailureReason` | Design needs a coaching-state spec, not one static line |
| Bubble Level | Still fully present in code (being deleted) | Correctly absent from the design. No action |

---

## 5. Feasibility verdict + recommended cut

**Verdict:** the *shell* is buildable in ~1–2 weeks on top of what we have (hub, camera chrome, reference flow, tool sheet, photo edit flow). The *sheet contents* are not: 9 of 11 entries are unbuilt, 2 of them (Auto-Detection ×2) are multi-week research-grade work, and 3 more should never be built. Shipping the sheet as drawn means a picker where **2 of 11 tiles work**.

### Recommended v1 sheet — 4 tiles, all real today

Drop the "Auto-Detection" section entirely. One 2×2 grid of what actually works:

```
┌─────────────────────────────────────┐
│              ▁▁▁▁                   │  handle
│  Chế độ đo                      ✕   │  16/700, localised
│                                     │
│  ┌──────────────┐ ┌──────────────┐  │
│  │   ruler      │ │  move-diagonal│  │
│  │  Khoảng cách │ │  Cao & dài    │  │  ← Distance (ships today)
│  │  ✓ selected  │ │               │  │  ← Line & Height (1–2 d)
│  └──────────────┘ └──────────────┘  │
│  ┌──────────────┐ ┌──────────────┐  │
│  │   box-3d     │ │   cylinder    │  │
│  │  Khối hộp    │ │  Khối trụ     │  │  ← Box (ships today)
│  │  L×W×H       │ │  ⌀D×H         │  │  ← Cylinder (ships today)
│  └──────────────┘ └──────────────┘  │
└─────────────────────────────────────┘
```

- 2×2 at 141×88 tiles → sheet ≈ **250px (31% of screen), no clipping**, versus 454px required today.
- 12px labels, not 10px. Add the dimension formula as a 10px sub-label — it teaches the tool and disambiguates 2D from 3D.
- Selected state = olive **fill** + white icon + a check, not olive-on-white text.
- **Delete the standalone "Polyline" tile** — our Distance tool already *is* a polyline. Add a running-total label to Distance instead (~hours) and say so in the sub-label.

### v1.1 (next sprint, ~3–5 d total)
Add **Angle** (1–2 d) and **Rectangle-flat / Circle-flat** (2–3 d) — but only *after* the unit work lands, because flat shapes want area (m²/ft²) and `LengthUnit` can't express it. That makes 7 tiles → a 3+3+1 or 4+3 grid, still ≈340px.

### Hide until built (design them, don't ship them)
Auto-Detection Rectangle, Auto-Detection Circle. If product insists on showing them, they must be visibly gated — a "Beta"/"Sắp có" badge and a disabled style — never a live-looking tile. Do a 3–5 day spike first (still-frame Canny on an ARCore CPU image + back-project to a plane) before committing to a number.

### Cut permanently
- **Square** — redundant with Rectangle and contradicts our deliberate non-90° parallelogram base.
- **Polyline smooth**, **Poly smooth** — produce interpolated numbers a measuring app cannot defend, and we'd own every accuracy complaint.

### Prerequisites, in order
1. **Units first.** 4 display units + persistence. Nothing in this design can render its own labels ("15 cm", "21 x 30 cm") until this lands. ~1–2 d.
2. Localisation decision. Extract `ar-measure-photo`'s hardcoded Vietnamese and the 5 hardcoded English hints in `ShapeMeasureScreen.kt` / `MeasureScreen.kt` to resources, then pick one language for the sheet. Half a day of extraction, then a policy call.
3. Coaching-state spec: one toast component, 11 states, plus a visual distinction between coaching and error.
4. Fix the known open gaps this design will expose: anchor not detached on mid-shape navigation (the sheet makes tool-switching *easy*, so this will fire constantly), `autoFitQuad` → manual-4-corner state undesigned, and the wall-anchored "height" that is geometrically depth (the "Cao & dài" tile makes that label user-visible).

### Defer
Per-line colours, redo, save/export. All three are net-new capability with low measured value, and "Lưu" needs a destination screen that doesn't exist.

---

## 6. What the design gets right — preserve these

1. **Reticle-at-centre + one big capture `+` in the thumb zone** (77px, 3px white ring on olive, y=700–787). This matches our aim-the-phone-don't-tap-the-screen interaction exactly and is the correct model for AR. Do not switch to tap-to-place.
2. **Camera chrome pattern:** 40px pills at `#FFFFFFCC` with 8px *background blur* and dark icons. This is the right answer to "legible over an arbitrary camera image" and is better than our current flat pills. Adopt the blur (and bump to 44px).
3. **ARToast:** `#1A1D1F` @ 0.88 + 12px blur + white 13/500. High contrast, correct treatment for coaching over live camera. Maps 1:1 onto our `HintBanner`. Keep the styling, expand to 11 states.
4. **Endpoint dots with a white 2px stroke.** A halo is exactly what keeps a marker visible over unpredictable imagery. Adopt for the AR overlay too, not just photo.
5. **Persistent instruction box** (SCR-21/22, `#1a3a4a` + white 14px, lineHeight 1.4, centred). For a multi-step flow a stable instruction beats a transient toast. Good instinct — just make the text change per state.
6. **MagnifierLoupe.** We already ship this on the photo path (96dp, 2.5×, red crosshair, follows the finger, clamped). The design validating it is a good sign — but keep *our* finger-following version, not the design's static top-left one.
7. **Reference picker as a 2-col card grid** with dims under the name, and **initials avatars for custom refs** — scannable, matches our data model with zero new storage, and better than our current list. Adopt.
8. **AddSheet asks only name + 2 dimensions.** Exactly our schema. No thumbnail, no scope creep. Rare discipline; keep it.
9. **"Chỉnh sửa tỉ lệ"** — the single best product idea in the file. Fixes a real defect (today you must discard the photo to fix the scale).
10. **Edit + delete for custom references** — likewise fixes a real defect (mistyped refs are currently permanent).
11. **Unit as a visible badge next to the measurement** rather than buried in settings. Correct for a measuring tool.
12. **Hub with two cards and one-line plain-language descriptions.** Most users don't know the difference between AR and photo measuring; "Point your camera and measure in real time" vs "Measure objects and distances from a photo you already have" is genuinely good copy. Keep verbatim (translated).
13. **Base-screen + overlay-variant pattern** (15/16/17, 18/19/20). Good practice — just convert the duplicated bases to component instances.
14. **A real token system** with `size-touchTarget: 48` declared. The system is sound; enforce it.

---

## Unresolved questions

1. **Are "Rectangle" and "Circle" flat 2D shapes on a plane, or are they our existing 3D Box and Cylinder?** If flat: where do Box and Cylinder live? If 3D: the icons, labels and grouping all need to change and "Square" should go. *(Blocks the whole sheet. Answer this first.)*
2. **Is the third grid row (Poly smooth / Rectangle / Circle) meant to be visible?** As drawn the sheet clips ~101px and that row is cut. If it must show, the sheet becomes 57% of the screen — acceptable?
3. **What does "Lưu" save?** A PNG to the gallery, or a re-openable measurement with its points? The second needs a saved-measurements screen that isn't in the design, plus persistence we don't have.
4. **Is "Auto-Detection" expected in the live AR camera, or on a captured still?** On a still it's ~1–2 weeks reusing `AutoFitQuad`; live it's 2–4 weeks of genuinely new capability with real perf and jitter risk.
5. **What opens the `UnitMenu`?** No trigger exists on SCR-18/19/20's TopBar. Is the SCR-24 "cm" badge the intended affordance everywhere?
6. **Where does the `SettingsBtn` go?** New screen, or deep-link to the host app's SCR-08 Settings?
7. **Where does the `info` icon on SCR-15/16/17 go?**
8. **One language, which one?** The reference/photo flow is Vietnamese, the hub and sheet are English. This mirrors an existing code split (photo module hardcoded Vietnamese, AR module English resources) that needs resolving either way.
9. **Is this feature a tab in the host app (as SCR-14's bottom nav implies) or a launched Activity (as the `AR_feature` merge plan implies)?**
10. **SCR-23 vs SCR-24 — what's the actual sequence?** Both are "edit the line", both confirm in the top-right, with different affordances. Is 24 a sub-mode of 23?
11. **Should flat Rectangle/Circle report area?** If yes, `LengthUnit` needs an area concept (m²/ft²) that doesn't exist — scope it before committing.
12. **Should the Rectangle tool force 90°?** Our Box base is a deliberate parallelogram. Forcing right angles is a reversal, not a refinement.
13. **Does the design intend to keep the `+`-button placement model, or tap-to-place?** SCR-21/22's "Nhấp vào điện thoại" (tap the phone) is tap-to-place on the *photo* path, which is correct there — confirming the AR path keeps aim-and-`+`.
14. **Should the designer save the `.pen`?** The on-disk file is 3 screens behind the live document, and the SCR-17 edit sheet is an unparented orphan in the canvas gutter.
