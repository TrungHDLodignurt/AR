package vn.apero.armeasure.ar.presentation.camera.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.domain.geometry.PlaneBasis
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.circleRing
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.domain.geometry.plus
import vn.apero.armeasure.ar.domain.geometry.times
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The surface affordance that replaces SceneView's built-in plane renderer: a small patch of dots
 * lying on the tracked plane, plus the reticle's own ring, both resolved in world space and
 * projected per frame.
 *
 * ### Why we draw this ourselves
 *
 * `planeRenderer = true` trails the library's `assets/textures/plane_renderer.png` (a 293×513
 * square grid) across the plane's **whole extent**, so aiming at a floor paints the floor *and*
 * every wall ARCore has found — it competes with the camera image instead of pointing at the one
 * spot that matters.
 *
 * `PlaneRendererV2` looks like the fix and is not: it genuinely exposes `gridTint`, `gridAlpha`,
 * `surfaceAlpha` and even `scanProgress`/`scanPlaneRadius`, but `ARSceneScope` hands out no
 * `PlaneRendererBase` instance, so none of those parameters is reachable without reflection. The
 * composable only chooses V1 or V2. Verified against `arsceneview-4.31.0.aar`; recorded here so
 * nobody re-derives it.
 *
 * The trade-off taken knowingly: turning the plane renderer off also gives up Filament's plane
 * occlusion. We were never using it — the grid drew over everything anyway — and this whole
 * overlay is a 2D Canvas over the scene by design.
 *
 * ### Why the patch is sized in metres, not pixels
 *
 * [LatticeStepMeters] and [LatticeHalfSpan] fix the patch at ~40 × 40 cm **in the world**. Because
 * the lattice is projected rather than drawn in screen space, perspective compresses the spacing
 * with distance on its own, which is what makes the dots read as painted onto the surface. A patch
 * defined in dp would stay the same size on screen and slide over the floor like a decal.
 */

/** 2.5 cm between neighbouring dots. */
private const val LatticeStepMeters = 0.025f

/** Lattice runs -8..8 on both in-plane axes, giving a ~40 × 40 cm patch. */
private const val LatticeHalfSpan = 8

/** 17 × 17 candidates before the circular mask; the packed buffer never needs more. */
private const val MaxDots = (LatticeHalfSpan * 2 + 1) * (LatticeHalfSpan * 2 + 1)

/** Three floats per dot: x, y, alpha. */
private const val FloatsPerDot = 3

/**
 * Steepens the radial fade so the patch has a soft rim instead of a hard disc edge — squared
 * falloff, then gained back up so the middle stays fully opaque rather than dimming everything.
 */
private const val FalloffGain = 1.5f

/** How far outside the viewport a dot may sit before it is dropped. */
private const val CullMarginPx = 8f

/** Dot radius is tuned for a ~1.9 m reading — a normal measuring distance — then scaled by range. */
private const val ReferenceDistanceMeters = 1.9f
private const val BaseDotRadiusDp = 1.5f
private const val MinDotRadiusDp = 0.6f
private const val MaxDotRadiusDp = 2.2f

/** Dots dim to this once the user has committed a point, so they stop competing with the geometry. */
internal const val MeasuringDotFade = 0.55f

/**
 * The reticle ring's radius, in metres. Small enough to read as a crosshair rather than a target
 * painted on the floor, large enough that its projected ellipse actually shows the surface tilt.
 */
private const val ReticleRingRadiusMeters = 0.03f

/** Enough segments that the projected ellipse has no visible corners at any tilt. */
private const val ReticleRingSegments = 32

/**
 * White, not the brand colour.
 *
 * `ArMeasureTokens.Signature` is `#8A9A5B`, an olive — at a 1.5 dp radius it disappears against a
 * wooden floor, a beige carpet or warm concrete, and a mark this small has no room to spend on
 * contrast. The module already settled this question once for committed endpoints
 * (`EndpointHaloColor`); this is the same answer for the same reason. Brand colour belongs on
 * chrome, not on two hundred sub-pixel marks whose only job is to be legible.
 */
private val DotColor = Color.White

/** Offset a hair down-right of each dot so a white dot survives a white surface too. */
private val DotHalo = Color(0x52000000)
private const val HaloOffsetPx = 0.5f

/** One frame's worth of projected dots, packed flat. */
internal class PlaneDots(
    /** `[x, y, alpha]` triples; only the first [count] entries are meaningful. */
    val packed: FloatArray,
    val count: Int,
    val radiusDp: Float,
) {
    companion object {
        val Empty = PlaneDots(FloatArray(0), 0, BaseDotRadiusDp)
    }
}

/**
 * Builds the dot patch centred on [hit], lying in the plane spanned by [basis].
 *
 * Packed into one `FloatArray` rather than a `List<Offset>` on purpose: at ~200 dots a frame the
 * list would allocate two hundred boxed values every frame, on top of what `buildOverlay` already
 * churns. One array is one allocation.
 *
 * @param fade multiplies every dot's alpha — [MeasuringDotFade] once a measurement is under way.
 */
internal fun buildPlaneDots(
    hit: Vec3,
    basis: PlaneBasis,
    cameraPosition: Vec3,
    projector: PoseProjector,
    viewSize: IntSize,
    fade: Float,
): PlaneDots {
    val width = viewSize.width
    val height = viewSize.height
    val packed = FloatArray(MaxDots * FloatsPerDot)
    var count = 0

    for (i in -LatticeHalfSpan..LatticeHalfSpan) {
        for (j in -LatticeHalfSpan..LatticeHalfSpan) {
            val ring = hypot(i.toFloat(), j.toFloat())
            // Circular mask: a square patch would show its corners and read as a tile.
            if (ring > LatticeHalfSpan) continue

            val world = hit + basis.u * (i * LatticeStepMeters) + basis.v * (j * LatticeStepMeters)
            val screen = projector.project(world, width, height) ?: continue
            if (
                screen.x < -CullMarginPx || screen.x > width + CullMarginPx ||
                screen.y < -CullMarginPx || screen.y > height + CullMarginPx
            ) {
                continue
            }

            val t = 1f - ring / LatticeHalfSpan
            val alpha = min(1f, t * t * FalloffGain) * fade
            // Below this a dot costs a draw call and shows nothing.
            if (alpha <= 0.01f) continue

            val base = count * FloatsPerDot
            packed[base] = screen.x
            packed[base + 1] = screen.y
            packed[base + 2] = alpha
            count++
        }
    }

    // One radius for the whole patch, from one distance measurement: the patch spans 40 cm, so
    // within-patch perspective variation is far smaller than the difference between a reading
    // taken at half a metre and one taken at three.
    val distance = measureDistanceMeters(cameraPosition, hit)
    val radiusDp = (BaseDotRadiusDp * ReferenceDistanceMeters / max(0.3f, distance))
        .coerceIn(MinDotRadiusDp, MaxDotRadiusDp)

    return PlaneDots(packed, count, radiusDp)
}

/**
 * The reticle's ring as screen points: a circle of [ReticleRingRadiusMeters] lying **on** the
 * plane, so its projection is an ellipse that tilts with the surface. That tilt is the signal —
 * a screen-space dot looks identical whether it is on a floor, a wall, or nothing at all.
 *
 * Returns an empty list unless every point projected, so a ring clipped by the near plane falls
 * back to the plain dot rather than drawing as a torn arc.
 */
internal fun buildReticleRing(
    hit: Vec3,
    basis: PlaneBasis,
    projector: PoseProjector,
    viewSize: IntSize,
): List<Offset> {
    val projected = circleRing(hit, basis, ReticleRingRadiusMeters, ReticleRingSegments)
        .mapNotNull { projector.project(it, viewSize.width, viewSize.height) }
    return if (projected.size == ReticleRingSegments) projected else emptyList()
}

/** Draws one frame's dot patch. Call before any measurement graphic — this sits under everything. */
internal fun DrawScope.drawPlaneDots(dots: PlaneDots) {
    if (dots.count == 0) return
    val radius = dots.radiusDp * density
    for (k in 0 until dots.count) {
        val base = k * FloatsPerDot
        val x = dots.packed[base]
        val y = dots.packed[base + 1]
        val alpha = dots.packed[base + 2]
        drawCircle(
            color = DotHalo.copy(alpha = DotHalo.alpha * alpha),
            radius = radius,
            center = Offset(x + HaloOffsetPx, y + HaloOffsetPx),
        )
        drawCircle(color = DotColor.copy(alpha = alpha), radius = radius, center = Offset(x, y))
    }
}
