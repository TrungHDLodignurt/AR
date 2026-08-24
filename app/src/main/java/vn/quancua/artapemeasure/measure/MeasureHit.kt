package vn.quancua.artapemeasure.measure

import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.hitTestDepth

/** Where a measured point's 3D position came from. Order is accuracy order, best first. */
enum class HitSource(val label: String) {
    Plane("plane"),
    Depth("depth map"),
    FeaturePoint("feature point"),
}

fun Pose.toVec3(): Vec3 = Vec3(tx(), ty(), tz())

/**
 * A live surface reading under the reticle. Deliberately does NOT create an anchor: this is
 * resolved every frame to drive the rubber-band line, and anchoring 60 times a second would
 * flood ARCore's anchor budget. [commit] is called once, on tap.
 */
class SurfaceSample(
    val position: Vec3,
    val source: HitSource,
    private val hitResult: HitResult?,
    private val pose: Pose,
) {
    /** Turns this reading into a tracked anchor. Call only when the user commits the point. */
    fun commit(session: Session): Anchor = hitResult?.createAnchor() ?: session.createAnchor(pose)
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
 */
fun resolveSurface(
    frame: Frame,
    xPx: Float,
    yPx: Float,
    allowDepthFallback: Boolean,
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
        }?.let { yield(SurfaceSample(it.hitPose.toVec3(), HitSource.Plane, it, it.hitPose)) }

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
