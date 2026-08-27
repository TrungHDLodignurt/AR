# :app's own R8 rules.
#
# This file is intentionally empty. All keep rules this app's dependencies need
# (ARCore, SceneView/Filament) ship as consumer-rules.pro inside those AARs and
# are merged in automatically by R8 — that's the point of consumer rules: any
# host app gets them for free without having to know about them here. No module
# currently ships a consumer-rules.pro of its own; the R8 audit verified none is
# needed — see plans/reports/report-260826-0930-r8-release-hardening-ar-measure-modules.md.
#
# Do not add module-specific keep rules here — they belong in the owning module's
# consumer-rules.pro so every future host app gets them automatically.
