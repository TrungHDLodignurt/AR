package vn.apero.armeasure.ar.data.arcore

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Frame
import vn.apero.armeasure.ar.domain.geometry.Ray3
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.minus
import vn.apero.armeasure.ar.domain.geometry.normalized

/**
 * Projects ARCore world-space points to screen pixels.
 *
 * Perspective projection preserves straight lines, so a 3D segment A→B always projects to a
 * straight screen-space segment project(A)→project(B). That is what lets the whole measuring
 * overlay be drawn in a 2D Compose Canvas instead of as Filament geometry: dashed lines and
 * screen-constant label pills are trivial in Canvas and awkward in 3D.
 *
 * Every matrix and vector is a reused scratch buffer. This runs inside `onSessionUpdated`,
 * which is a hot path — allocating there hands the GC work at frame rate.
 */
internal class PoseProjector {

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val inverseViewProjection = FloatArray(16)
    private val source = FloatArray(4)
    private val destination = FloatArray(4)

    /** Call once per frame, before any [project] or [unprojectRay] call. */
    fun update(frame: Frame, near: Float = 0.05f, far: Float = 100f) {
        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, near, far)
        // Combine once per frame rather than twice per projected point.
        Matrix.multiplyMM(viewProjection, 0, projectionMatrix, 0, viewMatrix, 0)
        // Inverted once per frame too, for unprojectRay — screen pixel back to a world ray.
        Matrix.invertM(inverseViewProjection, 0, viewProjection, 0)
    }

    /**
     * @return screen pixel position, or `null` when the point sits behind the camera and has
     *         no meaningful screen position.
     */
    fun project(point: Vec3, widthPx: Int, heightPx: Int): Offset? {
        source[0] = point.x
        source[1] = point.y
        source[2] = point.z
        source[3] = 1f
        Matrix.multiplyMV(destination, 0, viewProjection, 0, source, 0)
        val w = destination[3]
        if (w <= 0f) return null
        // Clip space -> normalised device coords -> pixels. Y flips: NDC is up-positive,
        // screen coordinates are down-positive.
        return Offset(
            x = (destination[0] / w * 0.5f + 0.5f) * widthPx,
            y = (1f - (destination[1] / w * 0.5f + 0.5f)) * heightPx,
        )
    }

    /**
     * The inverse of [project]: turns a screen pixel into a world-space aim ray, by unprojecting
     * it at the near and far clip planes and drawing a line through both.
     *
     * Feeds [intersectRayPlane] so a plane hit can be resolved analytically instead of through
     * ARCore's mesh-based hit test — see that function's doc for why that matters.
     */
    fun unprojectRay(xPx: Float, yPx: Float, widthPx: Int, heightPx: Int): Ray3 {
        // Pixels -> normalised device coords. Inverse of the flip in project().
        val ndcX = (xPx / widthPx) * 2f - 1f
        val ndcY = 1f - (yPx / heightPx) * 2f
        val near = unprojectPoint(ndcX, ndcY, -1f)
        val far = unprojectPoint(ndcX, ndcY, 1f)
        return Ray3(origin = near, direction = (far - near).normalized())
    }

    private fun unprojectPoint(ndcX: Float, ndcY: Float, ndcZ: Float): Vec3 {
        source[0] = ndcX
        source[1] = ndcY
        source[2] = ndcZ
        source[3] = 1f
        Matrix.multiplyMV(destination, 0, inverseViewProjection, 0, source, 0)
        val w = destination[3]
        return Vec3(destination[0] / w, destination[1] / w, destination[2] / w)
    }
}
