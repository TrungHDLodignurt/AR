package vn.apero.armeasure.photo.domain.imaging

/**
 * Turns a segmentation confidence mask into the quad of the one object under [seed].
 *
 * Flood-fills rather than taking every above-threshold pixel: a photo of a desk segments into
 * several subjects, and hulling all of them at once produces a rectangle spanning the reference
 * object AND whatever else was recognised beside it. Only the connected region the user actually
 * tapped is the reference object.
 *
 * Returns null when the tap isn't on any masked region, or when the region is too small to be a real
 * object rather than mask speckle.
 */
internal fun quadFromMask(
    mask: FloatArray,
    width: Int,
    height: Int,
    seed: Vec2,
    confidenceThreshold: Float = 0.5f,
    // Below this share of the image a masked region is speckle, not the thing being measured.
    minAreaFractionOfImage: Float = 0.002f,
): List<Vec2>? {
    if (width <= 0 || height <= 0 || mask.size < width * height) return null
    val start = findSeedIndex(mask, width, height, seed, confidenceThreshold) ?: return null

    val visited = BooleanArray(width * height)
    // Per-row extents rather than every filled pixel: the convex hull only ever uses boundary
    // points, and a full-region point list on a multi-megapixel mask is a needless allocation.
    val rowMin = IntArray(height) { Int.MAX_VALUE }
    val rowMax = IntArray(height) { Int.MIN_VALUE }
    var filled = 0

    val queue = ArrayDeque<Int>()
    queue.addLast(start)
    visited[start] = true
    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        val x = index % width
        val y = index / width
        filled++
        if (x < rowMin[y]) rowMin[y] = x
        if (x > rowMax[y]) rowMax[y] = x

        // 4-connected: 8-connectivity bleeds across the one-pixel diagonal bridges that mask noise
        // leaves between genuinely separate objects.
        if (x > 0) enqueue(index - 1, mask, visited, confidenceThreshold, queue)
        if (x < width - 1) enqueue(index + 1, mask, visited, confidenceThreshold, queue)
        if (y > 0) enqueue(index - width, mask, visited, confidenceThreshold, queue)
        if (y < height - 1) enqueue(index + width, mask, visited, confidenceThreshold, queue)
    }

    if (filled < minAreaFractionOfImage * width * height) return null

    val boundary = mutableListOf<Vec2>()
    for (y in 0 until height) {
        if (rowMax[y] < rowMin[y]) continue
        boundary.add(Vec2(rowMin[y].toFloat(), y.toFloat()))
        boundary.add(Vec2(rowMax[y].toFloat() + 1f, y.toFloat()))
        boundary.add(Vec2(rowMin[y].toFloat(), y.toFloat() + 1f))
        boundary.add(Vec2(rowMax[y].toFloat() + 1f, y.toFloat() + 1f))
    }
    // A general quadrilateral first: a rectangle photographed at an angle projects to a trapezoid,
    // and the homography wants those skewed corners. minAreaRect stays as the fallback for a hull too
    // ragged to fit four sides to — a loose box beats no box.
    return quadFromHull(boundary) ?: minAreaRect(boundary)
}

private fun enqueue(
    index: Int,
    mask: FloatArray,
    visited: BooleanArray,
    threshold: Float,
    queue: ArrayDeque<Int>,
) {
    if (visited[index] || mask[index] < threshold) return
    visited[index] = true
    queue.addLast(index)
}

/**
 * The masked pixel to start filling from.
 *
 * A tap is a rough gesture and the mask boundary is approximate, so a tap that lands a few pixels
 * off the object — or in a hole the model left — must not read as "no object here". Spirals outward
 * from the tap and takes the nearest masked pixel, giving up once the search radius is wider than
 * any plausible aiming error.
 */
private fun findSeedIndex(
    mask: FloatArray,
    width: Int,
    height: Int,
    seed: Vec2,
    threshold: Float,
): Int? {
    val seedX = seed.x.toInt()
    val seedY = seed.y.toInt()
    val maxRadius = (minOf(width, height) * SeedSearchRadiusFraction).toInt().coerceAtLeast(4)
    for (radius in 0..maxRadius) {
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                // Only the ring just added, so nearer pixels always win.
                if (radius > 0 && kotlin.math.abs(dx) != radius && kotlin.math.abs(dy) != radius) continue
                val x = seedX + dx
                val y = seedY + dy
                if (x !in 0 until width || y !in 0 until height) continue
                val index = y * width + x
                if (mask[index] >= threshold) return index
            }
        }
    }
    return null
}

/** How far from the tap to look for the object, as a fraction of the mask's shorter side. */
private const val SeedSearchRadiusFraction = 0.05f
