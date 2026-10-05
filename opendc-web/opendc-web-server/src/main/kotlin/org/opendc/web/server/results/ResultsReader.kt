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

import jakarta.enterprise.context.ApplicationScoped
import org.opendc.web.launcher.MetricPoint
import org.opendc.web.launcher.MetricSeries
import org.opendc.web.launcher.ResultMetric
import org.opendc.web.server.model.RunUnit
import org.opendc.web.server.storage.resultKey
import org.opendc.web.server.storage.runKey
import org.opendc.web.server.telemetry.RunKey
import org.opendc.web.server.telemetry.TelemetryStore
import java.util.UUID

/**
 * What an experiment has measured so far: the telemetry store for a running unit, its parquet for a
 * stopped one, and the telemetry again for a stopped unit that published nothing.
 */
@ApplicationScoped
class ResultsReader(
    private val telemetry: TelemetryStore,
    private val parquet: ParquetSeries,
) {
    /**
     * The scenarios of [experimentId] that have measured something, folded to at most [buckets]
     * points each. A scenario with nothing to draw is left out so the run picker does not offer it.
     */
    fun read(
        experimentId: UUID,
        units: List<RunUnit>,
        buckets: Int,
    ): List<ScenarioResults> {
        val posted = telemetry.read(units.map { RunKey(experimentId, it.scenarioIndex, it.seed) })
        return units
            .groupBy { it.scenarioIndex }
            .toSortedMap()
            .map { (index, scenarioUnits) -> scenario(experimentId, index, scenarioUnits, posted, buckets) }
            .filter { it.series.isNotEmpty() }
    }

    /** Forgets what a scenario measured, because it is being run again over the same output. */
    fun forget(
        experimentId: UUID,
        units: List<RunUnit>,
    ) {
        units.map { it.scenarioIndex }.distinct().forEach { parquet.forget("${resultKey(experimentId)}/$it/") }
        telemetry.forget(units.map { RunKey(experimentId, it.scenarioIndex, it.seed) })
    }

    private fun scenario(
        experimentId: UUID,
        index: Int,
        units: List<RunUnit>,
        posted: Map<RunKey, List<MetricSeries>>,
        buckets: Int,
    ): ScenarioResults {
        val perSeed = units.map { measured(experimentId, it, posted) }
        return ScenarioResults(
            scenarioIndex = index,
            seeds = units.size,
            complete = units.all { it.state.isTerminal },
            series =
                ResultMetric.entries.mapNotNull { metric ->
                    val seeds = perSeed.mapNotNull { it[metric] }.filter { it.isNotEmpty() }
                    if (seeds.isEmpty()) {
                        null
                    } else {
                        ResultSeries(
                            metric = metric.id,
                            points = bucket(meanAcrossSeeds(seeds), buckets, metric.overTime).map { ResultPoint(it.t, it.value) },
                            spread = spreadAcrossSeeds(seeds, metric.overTime),
                        )
                    }
                },
        )
    }

    private fun measured(
        experimentId: UUID,
        unit: RunUnit,
        posted: Map<RunKey, List<MetricSeries>>,
    ): Map<ResultMetric, List<MetricPoint>> {
        if (unit.state.isTerminal) {
            val landed = parquet.read(runKey(experimentId, unit.scenarioIndex, unit.seed))
            if (landed.isNotEmpty()) {
                return landed
            }
        }
        return posted[RunKey(experimentId, unit.scenarioIndex, unit.seed)].orEmpty().mapNotNull { series ->
            // A metric from a launcher of another version drops one line rather than the whole chart.
            ResultMetric.byId(series.metric)?.let { it to series.points }
        }.toMap()
    }
}
