---
name: grep-audit-blind-spots
description: This repo (AR_feature module) enforces several invariants via grep instead of the compiler — the grep patterns can miss real violations; check the actual invariant, not just the documented command
metadata:
  type: project
---

`plans/260826-1137-ar-feature-single-module/phase-09-final-verification-and-audits.md` defines
16 "audits", most of them one-line `git grep` commands standing in for invariants the Kotlin
compiler doesn't enforce (no `ar`<->`photo` cross-imports, no hardcoded user-facing strings, one
shared ARCore session, no reflection beyond two known spots, file sizes, etc.).

**Confirmed blind spot found 2026-08-26:** audit #1/#2's grep for hardcoded strings targets
`Text(\s*"`, `text = "`, `label = "` — literal Compose-`Text`-shaped call sites. It does not
match a string passed positionally into a custom drawing helper, e.g.
`drawLabelPill(textMeasurer, "cạnh dài", ...)` inside a `DrawScope` function
(`QuadEditorCanvas.kt`). Two hardcoded Vietnamese labels ("cạnh dài"/"cạnh ngắn", long/short
edge) shipped past that grep for this reason — a real, user-facing violation of the module's
English-only/resource-driven-string decision that the audit had marked "should be empty".

**Why:** the module's invariants are real and worth enforcing, but every one of these audits is
only as good as its regex. An audit "passing" is not the same as the invariant holding.

**How to apply:** when reviewing this module (or judging whether a past "audit passed" claim in
a plan doc is trustworthy), don't just re-run the documented grep — also grep for the underlying
concept more broadly (e.g. any bare Vietnamese-diacritic string literal anywhere in `.kt`, not
just inside the specific call shapes the original author thought of), and manually scan
`Canvas`/`DrawScope` drawing code specifically, since text drawn via `drawText`/`drawLabelPill`-
style helpers is exactly the kind of user-facing string that standard `Text(`-shaped greps miss.
Same caution applies to the inset audit: check every bottom-anchored *and every scrollable*
UI element for `navigationBarsPadding()`, not just the elements a prior session already found —
this session's own review found a `LazyVerticalGrid` (`ReferencePickerScreen.kt`) that had no
inset handling at all, missed because no audit item explicitly named "check scrollable lists too".
