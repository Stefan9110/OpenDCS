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

/** The SDK's default export interval, which every base point runs at. */
const val BASE_INTERVAL_MINUTES = 5

/**
 * Every point of the grid: per trace its base point, then each axis varied alone around it, in the
 * order hosts, interval, tables, sample fraction, collector, parallelism, heap factor. A setting
 * that equals the base's collapses into it.
 */
fun Grid.points(): List<GridPoint> = traces.flatMap { pointsAround(baseOf(it)) }.distinctBy { it.id }

private fun Grid.baseOf(trace: GridTrace): GridPoint =
    GridPoint(
        trace = trace,
        hosts = trace.hosts.first(),
        exportIntervalMinutes = BASE_INTERVAL_MINUTES,
        tables = TableSet.ALL,
        sampleFraction = 1.0,
        collector = Collector.DEFAULT,
        parallelism = 1,
        heap = HeapChoice.Measure(measureHeapMb),
    )

private fun Grid.pointsAround(base: GridPoint): List<GridPoint> =
    buildList {
        addAll(listOf(base))
        base.trace.hosts.mapTo(this) { base.copy(hosts = it) }
        this@pointsAround.exportIntervalMinutes.mapTo(this) { base.copy(exportIntervalMinutes = it) }
        this@pointsAround.tables.mapTo(this) { base.copy(tables = it) }
        this@pointsAround.sampleFractions.mapTo(this) { base.copy(sampleFraction = it) }
        this@pointsAround.collectors.mapTo(this) { base.copy(collector = it) }
        this@pointsAround.parallelism.mapTo(this) { base.copy(parallelism = it) }
        this@pointsAround.heapFactors.mapTo(this) { base.copy(heap = HeapChoice.Factor(it)) }
    }
