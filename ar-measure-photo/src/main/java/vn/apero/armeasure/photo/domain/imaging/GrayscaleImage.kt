package vn.apero.armeasure.photo.domain.imaging

/**
 * A single-channel image, luminance 0f..255f, row-major — the input/output type threaded through
 * the whole edge-detection pipeline (`CannyEdgeDetector.kt`, `HoughTransform.kt`,
 * `QuadFromEdges.kt`). Deliberately has no Android type in it, same reasoning as `Vec2`/`Homography`:
 * the algorithm is what needs to be right, and that's testable with a synthetic image in a plain
 * JVM unit test with no Bitmap, no device.
 */
internal data class GrayscaleImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init { require(pixels.size == width * height) { "pixels must be width*height" } }

    fun get(x: Int, y: Int): Float = pixels[y * width + x]

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height
}

/** 5-tap separable Gaussian blur (kernel 1-4-6-4-1 / 16) — cheap noise reduction before Sobel. */
internal fun gaussianBlur(image: GrayscaleImage): GrayscaleImage {
    val kernel = floatArrayOf(1f, 4f, 6f, 4f, 1f)
    val kernelSum = 16f

    val horizontal = FloatArray(image.width * image.height)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            var sum = 0f
            for (k in -2..2) {
                val sampleX = (x + k).coerceIn(0, image.width - 1)
                sum += image.get(sampleX, y) * kernel[k + 2]
            }
            horizontal[y * image.width + x] = sum / kernelSum
        }
    }
    val horizontalImage = GrayscaleImage(image.width, image.height, horizontal)

    val result = FloatArray(image.width * image.height)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            var sum = 0f
            for (k in -2..2) {
                val sampleY = (y + k).coerceIn(0, image.height - 1)
                sum += horizontalImage.get(x, sampleY) * kernel[k + 2]
            }
            result[y * image.width + x] = sum / kernelSum
        }
    }
    return GrayscaleImage(image.width, image.height, result)
}
