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

**Confirmed blind spot found 2026-08-28 (README §16 audit 4, "Resource prefix intact"):** the
documented check is `every <string name= starts with armeasure_` — it only inspects `<string>`.
`res/values/themes.xml` declares `<style name="Theme.ArMeasure">`, unprefixed despite
`resourcePrefix = "armeasure_"`, and it is the style both module Activities use in the manifest.
So the audit passes forever while the invariant named in its own title is violated. Sweep all
resource types, not just strings. Same session: audit 8's stated expected output ("`git grep -c
'rememberEngine\|ARSceneView('` → 1 each") is impossible — `-c` counts matching *lines* for the
combined pattern, so it prints one number per file (3); the underlying invariant was fine but the
documented expectation was never achievable. Audit 9's "only two `::class.java` hits" also
undercounts: `Intent(context, X::class.java)` in `newIntent` companions matches too.

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
