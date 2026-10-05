/*
 * Copyright (c) 2026 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.web.server.results

import org.opendc.web.launcher.MetricPoint
import org.opendc.web.launcher.Reduction
import kotlin.math.floor

/**
 * Averages one metric over the seeds a scenario was run with, aligned on the instant rather than on
 * position so an instant one seed lacks does not shift the others.
 */
fun meanAcrossSeeds(perSeed: List<List<MetricPoint>>): List<MetricPoint> {
    perSeed.singleOrNull()?.let { return it }
    val total = sortedMapOf<Long, DoubleArray>()
    for (points in perSeed) {
        for (point in points) {
            val cell = total.getOrPut(point.t) { DoubleArray(2) }
            cell[SUM] += point.value
            cell[COUNT]++
        }
    }
    return total.map { (t, cell) -> MetricPoint(t, cell[SUM] / cell[COUNT]) }
}

/**
 * Reduces [points] to at most [buckets] of them, folding each bucket the way [fold] says. The
 * frontend folds the same way again for its chart width, so the two must agree.
 */
fun bucket(
    points: List<MetricPoint>,
    buckets: Int,
    fold: Reduction,
): List<MetricPoint> {
    if (buckets <= 0) {
        return emptyList()
    }
    if (points.size <= buckets) {
        return points
    }
    val width = points.size.toDouble() / buckets
    return (0 until buckets).map { index ->
        val from = floor(index * width).toInt()
        val to = if (index == buckets - 1) points.size else floor((index + 1) * width).toInt()
        val inside = points.subList(from, maxOf(to, from + 1))
        MetricPoint(points[from].t, fold.of(inside.map { it.value }))
    }
}

/**
 * How far apart the seeds landed: each seed's series reduced by [fold], then the highest minus the
 * lowest. Zero for a single seed.
 */
fun spreadAcrossSeeds(
    perSeed: List<List<MetricPoint>>,
    fold: Reduction,
): Double {
    val reduced = perSeed.filter { it.isNotEmpty() }.map { points -> fold.of(points.map { it.value }) }
    return if (reduced.size < 2) 0.0 else reduced.max() - reduced.min()
}

private const val SUM = 0
private const val COUNT = 1
