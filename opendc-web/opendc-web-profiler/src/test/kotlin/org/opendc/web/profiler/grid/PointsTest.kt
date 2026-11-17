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

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A grid is only useful if every point can be traced back to the one setting it varies, and if a
 * restart finds the same ids it left behind.
 */
class PointsTest {
    private val surf = GridTrace(name = "surf", path = "traces/surf", hosts = listOf(100, 500))
    private val borg = GridTrace(name = "borg", path = "traces/borg", hosts = listOf(1000))
    private val grid = Grid(traces = listOf(surf, borg))

    private fun GridPoint.isBase() =
        hosts == trace.hosts.first() &&
            exportIntervalMinutes == 5 &&
            tables == TableSet.ALL &&
            sampleFraction == 1.0 &&
            collector == Collector.DEFAULT &&
            parallelism == 1 &&
            heap is HeapChoice.Measure

    @Test
    fun `the base point appears once per trace`() {
        val points = grid.points()

        assertEquals(1, points.count { it.trace == surf && it.isBase() })
        assertEquals(1, points.count { it.trace == borg && it.isBase() })
    }

    @Test
    fun `each axis varies alone around the base`() {
        val points = grid.points()
        val base = points.first { it.trace == surf && it.isBase() }

        for (point in points.filter { it.trace == surf && !it.isBase() }) {
            val differences =
                listOf(
                    point.hosts != base.hosts,
                    point.exportIntervalMinutes != base.exportIntervalMinutes,
                    point.tables != base.tables,
                    point.sampleFraction != base.sampleFraction,
                    point.collector != base.collector,
                    point.parallelism != base.parallelism,
                    point.heap != base.heap,
                ).count { it }
            assertEquals(1, differences, "${point.id} varies more than one setting")
        }
    }

    @Test
    fun `ids are unique and stable`() {
        val first = grid.points().map { it.id }
        val second = Grid(traces = listOf(surf, borg)).points().map { it.id }

        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size)
    }

    @Test
    fun `a heap factor never asks for less than the minimum`() {
        assertEquals(MIN_HEAP_MB, heapMb(HeapChoice.Factor(1.0), predictedMb = 10.0))
        assertEquals(150, heapMb(HeapChoice.Factor(1.5), predictedMb = 100.0))
        assertEquals(16384, heapMb(HeapChoice.Measure(16384), predictedMb = 10.0))
    }

    @Test
    fun `a grid decodes from JSON with defaults`() {
        val decoded = Json.decodeFromString(Grid.serializer(), """{"traces":[{"name":"t","path":"p","hosts":[8]}]}""")

        assertEquals(3, decoded.exportIntervalMinutes.size)
        assertEquals(2, decoded.tables.size)
        assertTrue(decoded.points().any { it.heap is HeapChoice.Factor })
    }
}
