package vn.apero.armeasure.photo.domain.imaging

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grades the auto-fit pipeline on a REAL photo against a ground-truth quad, scored by IoU.
 *
 * **Both assertions are currently disabled, and the fixture's ground truth is known to be wrong.**
 * It was captured by dragging the quad onto the object on-device, but the screen was reflowing between
 * detection and confirmation — quad/segments/homography were display-space at the time, so all three
 * were left about 180px behind on a 2048px-tall photo. They are stored in the bitmap's own pixel grid
 * now, which is what makes that particular corruption impossible rather than merely handled. Measuring per-edge luminance contrast afterwards settled
 * it: the captured quad sits on a real boundary on one side out of four, while the detector's own quad
 * sits on one on all four. Every IoU figure derived from it (0.263, 0.376, 0.43) is meaningless, and
 * so is the conclusion drawn from them that the photo was unwinnable.
 *
 * The harness itself is sound and worth keeping — it runs production code over real pixels in about
 * seven seconds, which is the only way to compare two candidate algorithms on identical input. What it
 * needs is a fresh capture on a current build (bitmap-space coordinates), plus the per-edge
 * contrast check run over any new capture BEFORE it is trusted. That check is what caught this one.
 *
 * Re-enabling: replace `groundTruth` below, delete both `@Ignore`s, and set `BaselineIoU` to whatever
 * the run actually measures.
 */
class RealPhotoAutoFitTest {

    /**
     * A cluttered desk, deliberately adversarial: a long dark TV remote lies immediately above the
     * black phone that IS the reference object, nearly parallel to it and physically longer, so the
     * remote's edges out-vote the phone's in the Hough accumulator.
     *
     * The JPEG is stored at 1542x2048 — the size the app itself decodes this photo to — so the
     * corners need no rescaling and the test sees exactly the pixels production sees.
     */
    private val deskRemoteAndPhone = RealPhotoCase(
        resourceName = "/autofit-samples/desk-remote-and-phone-1542x2048.jpg",
        tapPoint = Vec2(591f, 1165f),
        // "phone" reference object, 150.0 x 70.0 mm.
        targetAspectRatio = 150f / 70f,
        groundTruth = listOf(
            Vec2(361.8f, 873.8f),
            Vec2(1083.7f, 922.8f),
            Vec2(1109.1f, 1249.8f),
            Vec2(329.2f, 1248.1f),
        ),
    )

    /**
     * DISABLED, not deleted: this photo is currently unwinnable, and the measurement that says so is
     * worth keeping. The object's own boundary is largely absent from the pixels — the phone's dark
     * body merges into its dark shadow, so along the ground-truth bottom edge the luminance profile
     * is flat (31-38) across +/-70px and per-channel colour difference is 1/255, and along the left
     * edge the nearest real transition sits 49px away. Edge coverage in the Canny map is 49% / 17% /
     * 0% / 3% for the four sides. No threshold, colour space or line-pairing rule can recover a
     * boundary that is not there, and the candidate ceiling over a 27-point sweep of the Hough
     * suppression knobs never passed 0.43.
     *
     * Re-enable when either the ground truth is corrected to the visible blob boundary or a photo
     * where the reference object is actually distinguishable becomes the gate.
     */
    @org.junit.Ignore("unwinnable on this photo — the object boundary is not in the pixels; see kdoc")
    @Test
    fun `finds the phone under a nearly-parallel longer remote`() {
        val result = detect(deskRemoteAndPhone)
        assertNotNull("no quad at all was produced for ${deskRemoteAndPhone.resourceName}", result.quad)
        val iou = quadIoU(result.quad!!, deskRemoteAndPhone.groundTruth)
        assertTrue(
            "IoU $iou is below the $MinAcceptableIoU gate. quad=${format(result.quad)} " +
                "groundTruth=${format(deskRemoteAndPhone.groundTruth)} " +
                "(edges=${result.edgePixelCount}, lines=${result.lines.size})",
            iou >= MinAcceptableIoU,
        )
    }

    /**
     * Records where the pipeline actually stands, so a change that makes things worse fails loudly
     * even while the real gate above is still red. Raise [BaselineIoU] as it improves — never lower
     * it to make a run pass.
     */
    @org.junit.Ignore("baseline was scored against the invalid ground truth below — see the class kdoc")
    @Test
    fun `does not regress below the recorded baseline`() {
        val result = detect(deskRemoteAndPhone)
        val iou = result.quad?.let { quadIoU(it, deskRemoteAndPhone.groundTruth) } ?: 0f
        assertTrue(
            "IoU regressed to $iou, below the recorded baseline $BaselineIoU. quad=${result.quad?.let(::format)}",
            iou >= BaselineIoU,
        )
    }

    private class RealPhotoCase(
        val resourceName: String,
        val tapPoint: Vec2,
        val targetAspectRatio: Float,
        /** Corners in the stored photo's own pixel space, ordered TL, TR, BR, BL. */
        val groundTruth: List<Vec2>,
    )

    /** The detection quad mapped back into the photo's pixel space, mirroring `autoFitQuad`. */
    private class RealPhotoResult(val quad: List<Vec2>?, val edgePixelCount: Int, val lines: List<HoughLine>)

    private fun detect(case: RealPhotoCase): RealPhotoResult {
        val photo = requireNotNull(ImageIO.read(javaClass.getResourceAsStream(case.resourceName))) {
            "missing test fixture ${case.resourceName}"
        }
        val longSide = maxOf(photo.width, photo.height)
        val scale = if (longSide > DetectionLongSidePx) DetectionLongSidePx.toFloat() / longSide else 1f
        val detectWidth = (photo.width * scale).toInt().coerceAtLeast(1)
        val detectHeight = (photo.height * scale).toInt().coerceAtLeast(1)

        val detection = detectQuadInGrayscale(
            detection = grayscaleScaled(photo, detectWidth, detectHeight),
            tapInDetectionSpace = Vec2(case.tapPoint.x * scale, case.tapPoint.y * scale),
            targetAspectRatio = case.targetAspectRatio,
        )
        return RealPhotoResult(
            quad = detection.quad?.map { Vec2(it.x / scale, it.y / scale) },
            edgePixelCount = detection.edgePixelCount,
            lines = detection.lines,
        )
    }

    /**
     * Same two steps `AutoFitQuad.extractGrayscaleScaled` does on Android — bilinear downscale then
     * Rec. 601 luma. `Graphics2D`'s bilinear filter is not bit-identical to Android's, so an IoU here
     * can differ from the device's by a hair; it is the same algorithm on the same pixels, which is
     * what makes it a usable gate.
     */
    private fun grayscaleScaled(photo: BufferedImage, width: Int, height: Int): GrayscaleImage {
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            drawImage(photo, 0, 0, width, height, null)
            dispose()
        }
        val luminance = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = scaled.getRGB(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                luminance[y * width + x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        return GrayscaleImage(width, height, luminance)
    }

    private fun format(quad: List<Vec2>) =
        quad.joinToString(" ") { "(${it.x.roundToInt()},${it.y.roundToInt()})" }

    private companion object {
        /**
         * What "snapped tight enough for the UI to be trusted" means. 0.85 leaves room for the few
         * pixels of ambiguity in where a rounded phone corner actually ends, while still rejecting
         * anything that is merely overlapping the right object.
         */
        const val MinAcceptableIoU = 0.85f

        /**
         * Measured 2026-08-27 on this harness with the 900px downscale and the size/tightness term.
         * (An earlier 0.42/0.49 reading came from a sign error in [quadIoU] and was never real.)
         */
        const val BaselineIoU = 0.26f
    }
}
