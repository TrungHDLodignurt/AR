package vn.apero.armeasure.photo.domain.imaging

/**
 * Long side the photo is downscaled to before edge detection.
 *
 * Detection runs on the **whole** photo, not a crop around the tap. A tap-centred window provably
 * cannot work: measured on a real 1542x2048 photo, a 480px window (23% of the long side) sees the
 * middle of the object plus background clutter but not all four of its edges, so no quadrilateral
 * can be formed at all and [quadFromLines] returned null on every tap. Widening the window fixed
 * that but full-resolution Hough took seconds per tap. Downscaling instead sees every edge *and*
 * costs far less: Hough is O(edge pixels x theta steps), so a linear reduction is quadratically
 * less work.
 *
 * 900 rather than a tighter 640: the value has to keep the object's SHORT edge worth voting for. A
 * 15x7 phone photographed to fill ~a quarter of a 1542x2048 frame has a short edge around 190px,
 * which 640 shrinks to ~40px — few edge pixels, few votes, and a longer nearby object (a TV remote)
 * out-votes it. 900 roughly doubles that, and Hough still runs well under a second per tap.
 */
internal const val DetectionLongSidePx = 900

/** What [detectQuadInGrayscale] found, including the intermediate counts callers log or assert on. */
internal class QuadDetection(
    /** Corners in the coordinate space of the grayscale image passed in, or null if nothing plausible. */
    val quad: List<Vec2>?,
    val edgePixelCount: Int,
    val lines: List<HoughLine>,
)

/**
 * The resolution-independent half of the photo auto-fit: Canny -> Hough -> [quadFromLines] over an
 * already-downscaled grayscale image.
 *
 * Split out of `AutoFitQuad.kt` (which owns the `android.graphics.Bitmap` half) so a JVM unit test
 * can drive the *real* pipeline over a *real* photo. Keeping the two in one Bitmap-dependent file
 * meant the only way to measure a tuning change was to build an APK and tap a phone, which is far
 * too slow a loop and gives no way to compare two candidate algorithms on identical input.
 */
internal fun detectQuadInGrayscale(
    detection: GrayscaleImage,
    tapInDetectionSpace: Vec2,
    // long/short of the real object, when known — the constraint that separates the object from the
    // many clutter rectangles a real photo contains. See quadFromLines.
    targetAspectRatio: Float? = null,
    tuning: DetectionTuning = DetectionTuning(),
): QuadDetection {
    val edges = cannyEdges(detection)
    val lines = houghLines(
        edges = edges,
        width = detection.width,
        height = detection.height,
        maxLines = tuning.maxLines,
        suppressThetaDegrees = tuning.suppressThetaDegrees,
        suppressRhoFraction = tuning.suppressRhoFraction,
    )
    val quad = quadFromLines(
        lines = lines,
        point = tapInDetectionSpace,
        imageWidth = detection.width.toFloat(),
        imageHeight = detection.height.toFloat(),
        targetAspectRatio = targetAspectRatio,
    )
    return QuadDetection(quad = quad, edgePixelCount = edges.count { it }, lines = lines)
}

/**
 * The Hough knobs the real-photo tuning loop sweeps, grouped so a test can try a grid of them
 * without every call site growing another argument. Defaults are what production uses.
 */
internal class DetectionTuning(
    val maxLines: Int = 40,
    val suppressThetaDegrees: Float = 10f,
    val suppressRhoFraction: Float = 0.03f,
)
