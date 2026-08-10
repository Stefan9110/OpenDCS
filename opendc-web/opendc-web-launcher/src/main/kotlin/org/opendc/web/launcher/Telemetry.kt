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

package org.opendc.web.launcher

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a launcher says how far it has got.
 *
 * Telemetry is never authoritative: what an execution finally did is the platform's account of the
 * process, not anything sent here. A manifest that names nowhere still simulates and still writes
 * its output, which is what makes one runnable by hand.
 */
@Serializable
sealed interface TelemetryTarget {
    @Serializable
    @SerialName("none")
    data object None : TelemetryTarget

    /**
     * @property url The full address to post to, which has to be reachable from wherever the
     *           launcher runs rather than from the server that wrote it.
     * @property token Bearer credential for this execution and no other. It can do nothing but write
     *           progress into the work it was handed.
     */
    @Serializable
    @SerialName("endpoint")
    data class Endpoint(
        val url: String,
        val token: String,
    ) : TelemetryTarget
}

/**
 * Everything the launcher currently knows about its runs.
 *
 * Each post carries the whole picture rather than what changed since the last one. Posts are
 * fire-and-forget, so a report that never arrives has to be corrected by the next one rather than
 * acknowledged, and the launcher is holding the capped series anyway.
 */
@Serializable
data class TelemetryReport(
    val runs: List<RunTelemetry>,
)

/**
 * How one `(scenario, seed)` run is getting on.
 *
 * How much there is to get through is not reported: the platform works that out from the document
 * when the experiment is submitted, so a bar can be drawn before any launcher exists to report to.
 * Sending it from here as well would move the denominator every time a run started, which is a bar
 * running backwards.
 */
@Serializable
data class RunTelemetry(
    val scenarioIndex: Int,
    val seed: Long,
    val completedTasks: Int,
    val series: List<MetricSeries>,
)

@Serializable
data class MetricSeries(
    val metric: String,
    val points: List<MetricPoint>,
)

/** One metric at one instant of simulated time, [t] milliseconds in. */
@Serializable
data class MetricPoint(
    val t: Long,
    val value: Double,
)
