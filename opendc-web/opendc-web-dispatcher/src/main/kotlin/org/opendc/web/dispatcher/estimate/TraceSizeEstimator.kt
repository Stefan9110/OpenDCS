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

package org.opendc.web.dispatcher.estimate

import org.opendc.sdk.model.experiment.ScenarioSpec
import org.opendc.sdk.model.export.OutputFileSpec
import org.opendc.sdk.model.failure.NoFailureSpec
import org.opendc.sdk.model.topology.TopologySpec
import org.opendc.sdk.model.workload.TraceWorkloadSpec

/** The named terms of [TraceSizeEstimator], kept in config so a deployment can retune them. */
data class EstimatorCoefficients(
    /** What the engine holds before it has loaded anything. */
    val baseMemoryMb: Double,
    /** Per simulated host: its state, and the export row buffered for it each interval. */
    val memoryPerHostMb: Double,
    /** Per million fragments. The heaviest term: every fragment row becomes an object on the heap. */
    val memoryPerMillionFragmentsMb: Double,
    /** Per million tasks, each of which becomes an object holding its own fragment list. */
    val memoryPerMillionTasksMb: Double,
    /** Fixed cost of starting and finishing one run. */
    val baseSeconds: Double,
    /** Per million fragments read off disk and turned into objects, before anything is simulated. */
    val loadSecondsPerMillionFragments: Double,
    /** Per million fragments actually stepped through by the engine. */
    val simulateSecondsPerMillionFragments: Double,
    /** Per million tasks admitted and placed. */
    val secondsPerMillionTasks: Double,
    /** What one host adds to the cost of writing the host table, at the default export interval. */
    val exportCostPerHost: Double,
    /** What injecting failures adds, as a fraction. */
    val failureOverhead: Double,
    /** What checkpointing adds, as a fraction. */
    val checkpointOverhead: Double,
)

/**
 * Estimates a run from the number of rows it has to hold and get through.
 *
 * Sampling reduces runtime but not memory: the whole trace is loaded before it is sampled, so a run
 * over one percent of a trace still holds all of it. Runtime therefore splits into loading, paid on
 * the whole trace, and simulating, paid on the sampled part.
 *
 * Writing results is a multiplier rather than a term, since the host table writes a row per host per
 * interval and vanishes when that table is switched off.
 *
 * The coefficients are the shape of the model, not fitted values; [CalibratedEstimator] corrects for
 * a particular machine.
 */
class TraceSizeEstimator(private val coefficients: EstimatorCoefficients) : ResourceEstimator {
    override fun estimate(
        scenario: ScenarioSpec,
        workload: TraceExtent,
    ): UnitEstimate {
        val hosts = hostCount(scenario.topology)
        val fragments = workload.fragmentCount / MILLION
        val tasks = workload.taskCount / MILLION
        val sampled = sampleFraction(scenario)

        val memory =
            coefficients.baseMemoryMb +
                coefficients.memoryPerHostMb * hosts +
                coefficients.memoryPerMillionFragmentsMb * fragments +
                coefficients.memoryPerMillionTasksMb * tasks

        val seconds =
            coefficients.baseSeconds +
                coefficients.loadSecondsPerMillionFragments * fragments +
                coefficients.simulateSecondsPerMillionFragments * fragments * sampled +
                coefficients.secondsPerMillionTasks * tasks * sampled

        return UnitEstimate(
            peakMemoryMb = memory,
            cpuSeconds = seconds * exportMultiplier(scenario, hosts) * modelMultiplier(scenario),
        )
    }

    /** How much of the trace's work is actually simulated. */
    private fun sampleFraction(scenario: ScenarioSpec): Double =
        when (val workload = scenario.workload) {
            is TraceWorkloadSpec -> workload.sampleFraction.coerceIn(0.0, 1.0)
            else -> 1.0
        }

    /**
     * How much the run is slowed by writing the host table.
     *
     * Measured against the SDK's default interval, so halving the interval doubles the term.
     */
    private fun exportMultiplier(
        scenario: ScenarioSpec,
        hosts: Int,
    ): Double {
        val export = scenario.exportModel
        if (OutputFileSpec.HOST !in export.filesToExport) {
            return 1.0
        }
        val intervalMs = export.exportInterval.toMsLong().coerceAtLeast(1L)
        return 1.0 + coefficients.exportCostPerHost * hosts * (DEFAULT_EXPORT_INTERVAL_MS.toDouble() / intervalMs)
    }

    private fun modelMultiplier(scenario: ScenarioSpec): Double {
        val failures = if (scenario.failureModel == NoFailureSpec) 0.0 else coefficients.failureOverhead
        val checkpoints = if (scenario.checkpointModel == null) 0.0 else coefficients.checkpointOverhead
        return 1.0 + failures + checkpoints
    }

    private fun hostCount(topology: TopologySpec): Int =
        topology.clusters.sumOf { cluster -> cluster.count * cluster.hosts.sumOf { it.count } }

    private companion object {
        const val MILLION = 1_000_000.0

        /** The SDK's own default, which the per-host export term is measured against. */
        const val DEFAULT_EXPORT_INTERVAL_MS = 300_000L
    }
}
