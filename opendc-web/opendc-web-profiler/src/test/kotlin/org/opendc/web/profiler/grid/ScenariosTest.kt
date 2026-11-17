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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opendc.sdk.model.resource.UriReference
import org.opendc.sdk.model.telemetry.OutputFileSpec
import org.opendc.sdk.model.workload.EfficientTraceWorkloadSpec
import org.opendc.sdk.model.workload.TraceWorkloadSpec

/** A point is only measured correctly if the scenario the launcher runs is the point, setting for setting. */
class ScenariosTest {
    private val trace = GridTrace(name = "surf", path = "traces/surf", hosts = listOf(100))

    private fun point(
        hosts: Int = 100,
        interval: Int = 5,
        tables: TableSet = TableSet.ALL,
        sample: Double = 1.0,
        efficient: Boolean = false,
    ) = GridPoint(
        trace = trace.copy(efficient = efficient),
        hosts = hosts,
        exportIntervalMinutes = interval,
        tables = tables,
        sampleFraction = sample,
        collector = Collector.DEFAULT,
        parallelism = 1,
        heap = HeapChoice.Measure(1024),
    )

    @Test
    fun `hosts and export settings follow the point`() {
        val scenario = point(hosts = 42, interval = 30, tables = TableSet.HOST_ONLY, sample = 0.5).scenario(seed = 7)

        val hosts =
            scenario.topology.datacenters.orEmpty().sumOf { dc ->
                dc.clusters.sumOf { cluster -> cluster.count * cluster.hosts.sumOf { it.count } }
            }
        assertEquals(42, hosts)
        assertEquals(30 * 60_000L, scenario.exportModel.exportInterval.toMsLong())
        assertEquals(listOf(OutputFileSpec.HOST), scenario.exportModel.filesToExport)
        val workload = scenario.workload as TraceWorkloadSpec
        assertEquals(0.5, workload.sampleFraction)
        assertEquals(UriReference(STAGED_TRACE), workload.source)
        assertEquals(7, scenario.initialSeed)
        assertEquals(1, scenario.runs)
    }

    @Test
    fun `an efficient trace uses the efficient loader`() {
        assertTrue(point(efficient = true).scenario(seed = 0).workload is EfficientTraceWorkloadSpec)
    }

    @Test
    fun `the scenario validates`() {
        assertEquals(emptyList<Any>(), point().scenario(seed = 0).validate())
    }
}
