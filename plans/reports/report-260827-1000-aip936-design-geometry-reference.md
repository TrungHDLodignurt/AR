# Design geometry — read from the LIVE .pen document (authoritative)

All screens are **354 x 799**. Coordinates are relative to each node's parent; `x,y w x h`.
Note: camera overlays are authored 360 wide on a 354 screen (a known design-file inconsistency) —
implementation uses the real screen width, so treat a 6px width difference on those as expected.

## SCR-19 AR camera, idle (M7fxtw)
```
StatusBar        0,0    360x32
CameraView       0,0    360x823
TopBar           0,46   360x116  gap=12
  BackBtn        16,12  40x40    r=999
    BackIcon     9,9    22x22
  ModeUnitStack  300,12 44x92    vertical gap=8 align=center
    ModeBtn      0,0    44x44    align=center r=999
      ModeIcon   12,12  20x20
    UnitBtn      2,52   40x40    align=center r=999
Crosshair        168,395 24x24   align=center
  Dot            8,8    8x8
BottomBar        0,700  360x87   gap=24 align=center
  CaptureBtn     142,5  77x77    align=center r=999
    PlusIcon     24,24  30x30
ARToast          27,596 290x77   gap=16 align=center r=16
```

## MeasureModeSheet (ebVJf) — SCR-18 state
```
Sheet            0,621  354x178  vertical gap=16 r=[22,22,0,0]
  HandleWrap     20,12  314x4
    Handle       137,0  40x4     r=2
  Header         20,32  314x22   align=center
    Close        292,0  22x22
  GridWrap       20,70  314x86   vertical gap=8
    GridRow      14,8   286x70   gap=10
      Distance   0,0    89x70    vertical gap=4 align=center r=999
      Box        99,0   89x70    (same)
      Cylinder   197,0  89x70    (same)
      each Icon  33,16  22x22
```

## UnitMenu (XCFlV) — SCR-20 state
```
UnitMenu         216,160 132x146 vertical gap=2 r=12
  UnitRow-cm     6,6    120x32   gap=8 align=center r=8
    Check        96,9   14x14
  UnitRow-m      6,40   120x32
  UnitRow-inch   6,74   120x32
  UnitRow-ft     6,108  120x32
```

## SCR-21 place quad (TUBkX) / SCR-22 confirmed (DIbuK)
```
StatusBar            0,0    354x62
TopNav               0,62   354x56   gap=12 align=center
  BackIcon           16,16  24x24
Instruction Wrapper  0,118  354x96   vertical align=center
  Instruction Box    12,16  330x64   vertical align=center r=12
Content Area         0,214  354x345  vertical align=center
```
SCR-22 adds:
```
CheckmarkBtn         127,643 100x100 vertical align=center r=999
  PlusIcon           17,17   66x66
```

## SCR-23 edit line (jwRjx)
```
StatusBar            0,0    354x62
TopNav               0,62   354x56   gap=12 align=center
  BackIcon           16,16  24x24
  LeadSpacer         52,28  60x1
  UndoForwardGroup   124,8  104x40   gap=40 align=center
    UndoIcon         8,8    24x24
    ForwardIcon      72,8   24x24
  Spacer             240,28 60x1
Content Area         0,118  354x572  vertical align=center
  Content Area Inner 16,114 322x345  vertical align=center
Bottom Toolbar       0,690  354x109  gap=32 align=center
  LineSegmentBtn     16,16  145x69   vertical gap=6 align=center
    LineIconCircle   49,0   48x48    vertical align=center r=24
      LineIcon       12,12  24x24
  EditScaleBtn       193,16 145x69   (same shape)
```

## ColorPickerBar (M2m5jZ)
```
ColorPickerBar   0,703  354x96  align=center
  RedDot         16,38  20x20
  OrangeDot      72,38  20x20
  YellowDot      129,38 20x20
  GreenDot       185,38 20x20
  PurpleDot      242,38 20x20
  CmBadge        298,30 40x36   vertical align=center r=18
```

## Already handled in an earlier pass (for reference)
- SCR-14 hub: cards **314x238** each, x=20, 18px apart, inside a 354x493 region starting at y=158.
- SCR-15 grid: 2 columns, every cell **150x176** (height fixed), row gap 14, column gap 14, padding 20.
- Add sheet `DimRow`: horizontal, gap 10, **alignItems=end**; Length/Width columns 117 wide
  (Label 16 tall at y=0, Field 43 tall at y=23); **Unit box 67x43 at y=23** so its bottom aligns with
  the Fields' bottoms.

## Conversion note
Design units are dp at a 354dp-wide screen. The Pixel 6 is 1080px / 411dp wide, so **do not compare
raw px to these numbers** — convert (`px / density`, density 2.625 on this device) or compare
*proportions and relative offsets*. What matters is: equal cells are equal, bottoms align, sizes are
in the right ratio, and nothing sits inside a system inset.

## Intentional divergences — NOT defects
1. All UI text is English; the mock is Vietnamese.
2. Touch targets are ≥48dp — the mock's own 40/44 values violate its declared `size-touchTarget: 48`.
3. Chrome is inset from screen edges (status bar / navigation bar / mandatory gesture).
4. Capture button is centred on the real screen width, not the mock's x=142 (which centres in a
   360-wide bar on a 354-wide screen).
5. The AR screen has undo/redo and Clear controls the mock never drew.
6. `in` on the compact unit button vs `inch` in the menu row.
7. No Level tool anywhere — deliberately deleted.

## Known-deferred cosmetic debt — record, do NOT fix
- Bare Unicode glyph icons (`↔ □ ○ ▦ ✎ ⌄ 🗑 ← ×`) instead of real icons; `🗑` renders as colour emoji.
- Dimension labels repeat the unit: "21 cm × 30 cm" rather than "21 × 30 cm".
- `QuadEditorCanvas.kt` still shows Vietnamese "cạnh dài"/"cạnh ngắn".
- The design puts a small `cm` suffix *inside* each Length/Width field; implementation lacks it and
  the user has not decided (arguably redundant next to the unit button).
