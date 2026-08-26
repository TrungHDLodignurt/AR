# :app's own R8 rules.
#
# This file is intentionally empty. All keep rules this app's dependencies need
# (ARCore, SceneView/Filament, and this repo's own ar-measure-* modules) ship as
# consumer-rules.pro inside those modules/AARs and are merged in automatically by
# R8 — that's the point of consumer rules: any host app gets them for free without
# having to know about them here. See ar-measure-ar/consumer-rules.pro for what's
# actually needed and why.
#
# Do not add module-specific keep rules here — they belong in the owning module's
# consumer-rules.pro so every future host app gets them automatically.
