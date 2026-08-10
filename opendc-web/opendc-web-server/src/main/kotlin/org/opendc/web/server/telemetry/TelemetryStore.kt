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

package org.opendc.web.server.telemetry

import org.opendc.web.launcher.MetricSeries
import java.util.UUID

/** The `(scenario, seed)` run whose samples these are, which is the grain a launcher reports at. */
data class RunKey(
    val experimentId: UUID,
    val scenarioIndex: Int,
    val seed: Long,
) {
    /** What this run is filed under, flat because every read names the runs it wants. */
    override fun toString(): String = "telemetry:$experimentId:$scenarioIndex:$seed"
}

/**
 * The samples of runs that have not finished yet.
 *
 * Everything here is disposable and expires on its own: a run's canonical account is the parquet it
 * writes, and a fact that has to outlive the run belongs in Postgres. Losing all of it costs a live
 * chart its last few minutes and nothing else, which is why the deployment backend may be a cache
 * with persistence switched off.
 */
interface TelemetryStore {
    /** Replaces what [key] has reported. Reports are whole, so there is nothing to append to. */
    fun write(
        key: RunKey,
        series: List<MetricSeries>,
    )

    /** What each of [keys] has reported, leaving out the ones that have reported nothing. */
    fun read(keys: List<RunKey>): Map<RunKey, List<MetricSeries>>

    /** Drops runs whose samples describe an attempt that is being replaced. */
    fun forget(keys: List<RunKey>)
}
