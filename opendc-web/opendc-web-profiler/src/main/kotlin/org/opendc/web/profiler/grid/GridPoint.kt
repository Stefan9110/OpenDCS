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

package org.opendc.web.profiler.grid

import kotlin.math.ceil

/** The smallest heap a launcher is started with, whatever a prediction says. */
const val MIN_HEAP_MB = 64

/** How a point's heap is chosen: a fixed measurement heap, or a multiple of what the shipped model predicts. */
sealed interface HeapChoice {
    data class Measure(val mb: Int) : HeapChoice

    data class Factor(val factor: Double) : HeapChoice
}

/** One launcher run to measure. */
data class GridPoint(
    val trace: GridTrace,
    val hosts: Int,
    val exportIntervalMinutes: Int,
    val tables: TableSet,
    val sampleFraction: Double,
    val collector: Collector,
    val parallelism: Int,
    val heap: HeapChoice,
) {
    /** Stable across runs, and safe as a file name. */
    val id: String
        get() =
            listOf(
                trace.name,
                "h$hosts",
                "i${exportIntervalMinutes}m",
                tables.name.lowercase(),
                "s$sampleFraction",
                "p$parallelism",
                collector.name.lowercase(),
                heapLabel(heap),
            ).joinToString("-").replace(UNSAFE_IN_FILE_NAMES, "_")
}

fun heapMb(
    choice: HeapChoice,
    predictedMb: Double,
): Int =
    when (choice) {
        is HeapChoice.Measure -> choice.mb
        is HeapChoice.Factor -> ceil(choice.factor * predictedMb).toInt().coerceAtLeast(MIN_HEAP_MB)
    }

private fun heapLabel(choice: HeapChoice): String =
    when (choice) {
        is HeapChoice.Measure -> "measure"
        is HeapChoice.Factor -> "f${choice.factor}"
    }

private val UNSAFE_IN_FILE_NAMES = Regex("[^A-Za-z0-9._-]")
