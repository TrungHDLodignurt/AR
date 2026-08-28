package vn.apero.armeasure.ar.data.arcore

import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.hitTestDepth
import vn.apero.armeasure.ar.domain.geometry.Ray3
import androidx.annotation.StringRes
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.intersectRayPlane
import vn.apero.armeasure.ar.domain.geometry.normalized

/** Where a measured point's 3D position came from. Order is accuracy order, best first. */
internal enum class HitSource(@StringRes val labelRes: Int) {
    Plane(R.string.armeasure_source_plane),
    Depth(R.string.armeasure_source_depth),
    FeaturePoint(R.string.armeasure_source_feature_point),
}

internal fun Pose.toVec3(): Vec3 = Vec3(tx(), ty(), tz())

/**
 * A live surface reading under the reticle. Deliberately does NOT create an anchor: this is
 * resolved every frame to drive the rubber-band line, and anchoring 60 times a second would
 * flood ARCore's anchor budget. [commit] is called once, on tap.
 */
internal class SurfaceSample(
    val position: Vec3,
    val source: HitSource,
    private val hitResult: HitResult?,
    private val pose: Pose,
    /** Set only for a [HitSource.Plane] reading resolved analytically — see [resolveSurface]. */
    private val trackable: Trackable? = null,
) {
    /**
     * The plane's analytic normal (see [analyticNormal]), or null when this reading did not
     * come from a plane.
     *
     * The box/cylinder tools need this at commit time — a rectangle or circle only makes sense
     * drawn flat against the surface it sits on, and the height extrusion only makes sense
     * running perpendicular to it. [MeasuredPoint] never needed this because the plain ruler
     * only ever measures straight-line distance between two points, never a surface's own
     * orientation.
     */
    val planeNormal: Vec3? = (trackable as? Plane)?.analyticNormal()

    /**
     * Turns this reading into a tracked anchor. Call only when the user commits the point.
     *
     * [trackable] takes priority over [hitResult] because a plane reading's [position] may be
     * the analytic ray/plane intersection rather than [hitResult]'s mesh-based hit pose (see
     * [resolveSurface]) — anchoring through the trackable via [pose] places the anchor exactly
     * where the user was shown it, while still tracking that plane as ARCore refines it, same
     * as anchoring through the hit result would.
     */
    fun commit(session: Session): Anchor =
        trackable?.createAnchor(pose) ?: hitResult?.createAnchor() ?: session.createAnchor(pose)

    /**
     * This reading relocated onto [target] — the exact world position of an existing point the
     * reticle has snapped to.
     *
     * Returning a whole sample rather than just drawing the snap is what makes the committed value
     * trustworthy: [position] and [pose] move together, so the anchor created on tap lands on the
     * snapped position and the label, the rubber band and the stored measurement all agree. A
     * snapped point is therefore *identical* to the one it snapped to, which is the entire promise
     * of the feature — "start again from that corner" has to mean that corner, not 4 px away.
     *
     * [hitResult] is dropped: it described where the aim ray met the world, which is no longer
     * where the point goes, and letting [commit] fall back to it would silently undo the snap.
     * [trackable] is kept, so the new anchor still rides the same plane as ARCore refines it.
     */
    fun snappedTo(target: Vec3): SurfaceSample = SurfaceSample(
        position = target,
        source = source,
        hitResult = null,
        pose = Pose(floatArrayOf(target.x, target.y, target.z), pose.rotationQuaternion),
        trackable = trackable,
    )
}

/**
 * The plane's normal for the analytic intersection in [resolveSurface].
 *
 * ARCore's Plane pose always carries the plane normal on its Y axis, which is correct as-is
 * for a horizontal plane. A VERTICAL plane (a wall) is different: its fitted pose can carry a
 * small amount of vertical tilt as ARCore keeps refining the fit, but a wall's true normal
 * never has a vertical component — that tilt is a fitting artefact, not something to measure
 * against. Flattening it back to purely horizontal keeps the intersection point from drifting
 * with that artefact.
 */
private fun Plane.analyticNormal(): Vec3 {
    val axis = centerPose.yAxis
    if (type != Plane.Type.VERTICAL) return Vec3(axis[0], axis[1], axis[2])
    return Vec3(axis[0], 0f, axis[2]).normalized()
}

/**
 * Resolves the surface under ([xPx], [yPx]) — always the screen centre in this app, because
 * the reticle is fixed there and users aim far more precisely with a centred crosshair than
 * with a fingertip.
 *
 * Preference order is *accuracy* order, not convenience order:
 *
 *  1. A detected plane, but only when the point falls INSIDE the plane polygon. This check is
 *     load-bearing: ARCore happily reports a hit on the *infinite extension* of a plane, so
 *     without it, aiming past the edge of a table places a point in mid-air and reports a
 *     confidently wrong distance — no crash, no warning, just a false number.
 *  2. A DepthPoint — geometry ARCore has depth for but has not grown a plane over.
 *  3. The depth image directly, which is what makes a cluttered space measurable rather than
 *     only its flat floor (a sofa, a slope, the lip of a bench).
 *  4. A raw feature point. Noisiest, and labelled as such so the reading is trusted less.
 *
 * Every candidate must additionally satisfy [onRay]: its world position has to project back
 * onto the pixel it was queried from. A hit that fails this is not on the line the user is
 * aiming along, so it sits at the wrong distance — and a point at the wrong distance is still
 * anchored and still drawn correctly, it just is not on the object. It only betrays itself
 * once the camera moves and parallax slides it across the scene, which reads as the anchor
 * having come loose. Depth-map hits are the ones that fail; plane hits round-trip exactly.
 *
 * [aimRay], when supplied, additionally lets a plane candidate be resolved analytically
 * (see [intersectRayPlane]) instead of through the plane hit's mesh-based pose — steadier for
 * the reasons documented there. It is optional because that refinement only applies to plane
 * hits; every other candidate is unaffected.
 */
internal fun resolveSurface(
    frame: Frame,
    xPx: Float,
    yPx: Float,
    allowDepthFallback: Boolean,
    aimRay: Ray3? = null,
    onRay: (Vec3) -> Boolean = { true },
): SurfaceSample? {
    // hitTest throws if the session is not ready for this frame yet.
    val hits = runCatching { frame.hitTest(xPx, yPx) }.getOrNull().orEmpty()

    // Lazy so that a candidate is only built when every better one has been rejected.
    val candidates = sequence {
        hits.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                trackable.trackingState == TrackingState.TRACKING &&
                trackable.isPoseInPolygon(hit.hitPose)
        }?.let { hit ->
            val plane = hit.trackable as Plane
            val analytic = aimRay?.let {
                intersectRayPlane(it, plane.centerPose.toVec3(), plane.analyticNormal())
            }
            val position = analytic ?: hit.hitPose.toVec3()
            val pose = Pose(
                floatArrayOf(position.x, position.y, position.z),
                hit.hitPose.rotationQuaternion,
            )
            yield(SurfaceSample(position, HitSource.Plane, hit, pose, trackable = plane))
        }

        hits.firstOrNull { it.trackable is DepthPoint }?.let {
            yield(SurfaceSample(it.hitPose.toVec3(), HitSource.Depth, it, it.hitPose))
        }

        if (allowDepthFallback) {
            frame.hitTestDepth(xPx, yPx)?.let { depthHit ->
                val pose = Pose(
                    floatArrayOf(depthHit.position.x, depthHit.position.y, depthHit.position.z),
                    floatArrayOf(0f, 0f, 0f, 1f),
                )
                yield(SurfaceSample(pose.toVec3(), HitSource.Depth, null, pose))
            }
        }

        hits.firstOrNull { it.trackable is Point }?.let {
            yield(SurfaceSample(it.hitPose.toVec3(), HitSource.FeaturePoint, it, it.hitPose))
        }
    }

    return candidates.firstOrNull { onRay(it.position) }
}
