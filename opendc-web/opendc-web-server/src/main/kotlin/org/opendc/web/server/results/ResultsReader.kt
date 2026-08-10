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
import org.opendc.web.server.telemetry.RunKey
import org.opendc.web.server.telemetry.TelemetryStore
import java.util.UUID

/**
 * What an experiment has measured so far, from whichever side of the run holds it.
 *
 * A run that is still going has only posted samples, and they live in the telemetry store until they
 * expire. A run that has stopped has left its parquet behind, which is the account that lasts. Asking
 * the store for a finished run and the parquet for a running one are both wrong, so the unit's own
 * state decides, and a run that stopped without publishing anything falls back to whatever it managed
 * to post.
 */
@ApplicationScoped
class ResultsReader(
    private val telemetry: TelemetryStore,
    private val parquet: ParquetSeries,
) {
    /**
     * The scenarios of [experimentId] that have measured something, folded to at most [buckets]
     * points each.
     *
     * A scenario nothing has been heard from is left out rather than reported empty: the run picker
     * offers what it is given, and offering a scenario with no line to draw is offering nothing.
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
            val landed = parquet.read("${resultKey(experimentId)}/${unit.scenarioIndex}/seed=${unit.seed}")
            if (landed.isNotEmpty()) {
                return landed
            }
        }
        return posted[RunKey(experimentId, unit.scenarioIndex, unit.seed)].orEmpty().mapNotNull { series ->
            // A metric this server does not know is a launcher of another version, which is a reason
            // to leave out one line rather than to refuse the whole chart.
            ResultMetric.byId(series.metric)?.let { it to series.points }
        }.toMap()
    }
}
