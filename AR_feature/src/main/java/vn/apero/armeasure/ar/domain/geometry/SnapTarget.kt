package vn.apero.armeasure.ar.domain.geometry

/**
 * Which already-placed point the reticle should lock onto, if any — the pure decision behind
 * "snap to existing point".
 *
 * Third-party-free like the rest of this package (see [MeasureMath]'s header): screen positions
 * arrive as plain `(x, y)` pairs, not Compose `Offset`s, so this runs in a plain JVM unit test.
 *
 * ### Why two radii instead of one
 *
 * A single threshold is not a smaller version of this — it is broken. Park the reticle at exactly
 * the threshold and snap toggles **on every frame**: the reticle flickers between its two forms and
 * the haptic tick fires continuously. That is not an edge case. A hand holding a phone moves about
 * ±1.2 dp at 60 Hz, so "parked at the threshold" is the *normal* result of trying to hover near a
 * point. Entering a snap is deliberate; leaving one has to be deliberate too, which means the exit
 * radius must be looser than the entry radius.
 *
 * ### How this differs from the reference app
 *
 * ARuler's "Sticking" (`ARulerActivity.I0()`, radius `45 × density`) takes the **first** vertex
 * inside the radius, in object-creation order, and returns immediately — so with two candidates in
 * range it can lock onto the further one. This takes the nearest, via [nearestIndexWithin].
 *
 * @param positions projected screen positions of the existing points; `null` for any point that did
 *   not project (behind the camera), which is simply not a candidate.
 * @param reticle the aim position — the screen centre, in practice.
 * @param currentlySnapped the index held on the previous frame, which is what makes the hysteresis
 *   below possible; `null` when nothing was held.
 * @param enterPx radius a point must come within to *take* the snap. Also the radius a rival must
 *   beat to steal a held snap.
 * @param releasePx radius the held point must leave before the snap is dropped. Must be ≥ [enterPx].
 * @param excluded indices that may never be snapped to — the open segment's own start, so the user
 *   cannot be helped into measuring a point against itself.
 */
internal fun snapTarget(
    positions: List<Pair<Float, Float>?>,
    reticle: Pair<Float, Float>,
    currentlySnapped: Int?,
    enterPx: Float,
    releasePx: Float,
    excluded: Set<Int> = emptySet(),
): Int? {
    val candidates =
        if (excluded.isEmpty()) positions
        else positions.mapIndexed { i, position -> if (i in excluded) null else position }

    if (currentlySnapped != null && currentlySnapped !in excluded) {
        val held = positions.getOrNull(currentlySnapped)
        if (held != null && withinPx(held, reticle, releasePx)) {
            // The snap is held. Another point can still take it over, but only by earning it on
            // the tight radius — otherwise the lock would slide between neighbours inside the
            // loose radius and the "deliberate exit" this whole function exists for would be lost.
            return nearestIndexWithin(candidates, reticle, enterPx) ?: currentlySnapped
        }
    }

    return nearestIndexWithin(candidates, reticle, enterPx)
}

private fun withinPx(a: Pair<Float, Float>, b: Pair<Float, Float>, radiusPx: Float): Boolean {
    val dx = a.first - b.first
    val dy = a.second - b.second
    return dx * dx + dy * dy <= radiusPx * radiusPx
}
