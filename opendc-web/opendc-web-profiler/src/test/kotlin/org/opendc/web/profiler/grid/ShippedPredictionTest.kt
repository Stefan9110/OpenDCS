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
import org.junit.jupiter.api.Test
import org.opendc.web.dispatcher.estimate.EstimatorCoefficients
import org.opendc.web.dispatcher.estimate.TraceExtent
import org.opendc.web.dispatcher.estimate.TraceSizeEstimator
import org.opendc.web.profiler.trace.TraceShape

/** The prediction written next to a measurement must be the one the server would have made for that run. */
class ShippedPredictionTest {
    @Test
    fun `matches the dispatcher's estimate for the same scenario`() {
        val point =
            GridPoint(
                trace = GridTrace(name = "surf", path = "traces/surf", hosts = listOf(100)),
                hosts = 100,
                exportIntervalMinutes = 1,
                tables = TableSet.ALL,
                sampleFraction = 0.5,
                collector = Collector.DEFAULT,
                parallelism = 1,
                heap = HeapChoice.Measure(1024),
            )
        val shape = TraceShape(tasks = 194_917, fragments = 41_787_210, spanSeconds = 3600.0)

        val expected =
            TraceSizeEstimator(EstimatorCoefficients.SHIPPED)
                .estimate(point.scenario(seed = 0), TraceExtent(taskCount = shape.tasks, fragmentCount = shape.fragments))

        assertEquals(expected, shippedPrediction(point, shape))
    }
}
