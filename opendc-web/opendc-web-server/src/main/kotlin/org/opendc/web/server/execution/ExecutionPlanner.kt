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

package org.opendc.web.server.execution

import jakarta.enterprise.context.ApplicationScoped
import org.opendc.sdk.model.experiment.ScenarioSpec
import org.opendc.sdk.model.experiment.expand
import org.opendc.sdk.model.failure.TraceBasedFailureSpec
import org.opendc.sdk.model.resource.NamedReference
import org.opendc.sdk.model.resource.ResourceReference
import org.opendc.sdk.model.resource.UriReference
import org.opendc.sdk.model.workload.InlineWorkloadSpec
import org.opendc.sdk.model.workload.TraceWorkloadSpec
import org.opendc.sdk.model.workload.WorkloadSpec
import org.opendc.trace.conv.TABLE_FRAGMENTS
import org.opendc.trace.conv.TABLE_TASKS
import org.opendc.web.dispatcher.ExecutionSlot
import org.opendc.web.dispatcher.PlannedBag
import org.opendc.web.dispatcher.PlannedUnit
import org.opendc.web.dispatcher.estimate.TraceExtent
import org.opendc.web.dispatcher.estimate.TraceSizeEstimator
import org.opendc.web.dispatcher.estimate.scaledBy
import org.opendc.web.dispatcher.planBags
import org.opendc.web.launcher.LaunchManifest
import org.opendc.web.launcher.TelemetryTarget
import org.opendc.web.server.model.Execution
import org.opendc.web.server.model.Experiment
import org.opendc.web.server.model.RunUnit
import org.opendc.web.server.model.Trace
import org.opendc.web.server.model.TracePart
import org.opendc.web.server.service.SpecCodec
import org.opendc.web.server.storage.ObjectStore
import org.opendc.web.server.storage.resultKey
import org.opendc.web.server.storage.traceKey

/**
 * Works out what an experiment's queued units will cost, how they are shaped into executions, and
 * what a launcher is handed to run one.
 *
 * Shaping happens at dispatch rather than at submit, so it sees the slot the dispatcher offers
 * rather than one guessed at when the document was written.
 */
@ApplicationScoped
class ExecutionPlanner(
    private val config: ExecutionConfig,
    private val codec: SpecCodec,
    private val store: ObjectStore,
) {
    /** The executions [units] would form on [slot], largest first. */
    fun plan(
        experiment: Experiment,
        units: List<RunUnit>,
        slot: ExecutionSlot,
    ): List<PlannedBag> = planBags(estimate(scenariosOf(experiment), units), slot, config.toPolicy())

    /**
     * What a failed [execution] amounted to, as the shape a retry starts from.
     *
     * The grant is what the platform gave it, while the units are estimated afresh, so a split
     * re-reads the same model that packed them in the first place.
     */
    fun bagOf(execution: Execution): PlannedBag {
        val units = estimate(scenariosOf(execution.experiment), execution.units)
        return PlannedBag(
            units = units,
            parallelism = execution.parallelism,
            heapMb = execution.heapMb.toDouble(),
            memoryRequestMb = execution.memoryRequestMb.toDouble(),
            timeLimitSeconds = execution.timeLimitSeconds,
            makespanSeconds = execution.estimatedMakespanSeconds,
        )
    }

    /**
     * What one launcher process is handed.
     *
     * Every reference in the scenarios is resolved to a location here, so the launcher never has to
     * know what a name means, and the results go to the store beside the traces they came from.
     * [token] is what it reports progress with, and can do nothing else.
     */
    fun manifest(
        experiment: Experiment,
        units: List<RunUnit>,
        parallelism: Int,
        token: String,
    ): LaunchManifest {
        val scenarios = scenariosOf(experiment)
        return LaunchManifest(
            scenarios =
                units.mapNotNull { unit ->
                    scenarios[unit.scenarioIndex]?.let(::located)?.copy(
                        runs = 1,
                        initialSeed = unit.seed.toInt(),
                        id = unit.scenarioIndex,
                    )
                },
            parallelism = parallelism,
            results = store.locationOf(resultKey(experiment.publicId)),
            telemetry = TelemetryTarget.Endpoint(config.telemetryUrl(), token),
        )
    }

    private fun estimate(
        scenarios: Map<Int, ScenarioSpec>,
        units: List<RunUnit>,
    ): List<PlannedUnit> {
        val estimator =
            TraceSizeEstimator(config.estimator().toCoefficients())
                .scaledBy(config.estimator().runtimeMultiplier(), config.estimator().memoryMultiplier())
        return units.mapNotNull { unit ->
            val scenario = scenarios[unit.scenarioIndex] ?: return@mapNotNull null
            val estimate = estimator.estimate(scenario, traceExtentOf(scenario.workload))
            PlannedUnit(unit.scenarioIndex, unit.seed, estimate.cpuSeconds, estimate.peakMemoryMb)
        }
    }

    private fun scenariosOf(experiment: Experiment): Map<Int, ScenarioSpec> =
        codec.decodeExperiment(codec.parseStored(experiment.spec)).expand().associateBy { it.id }

    /** The same scenario with every reference in it pointing at where its bytes are. */
    private fun located(scenario: ScenarioSpec): ScenarioSpec {
        val workload = scenario.workload
        val failure = scenario.failureModel
        return scenario.copy(
            workload = if (workload is TraceWorkloadSpec) workload.copy(source = located(workload.source)) else workload,
            failureModel = if (failure is TraceBasedFailureSpec) failure.copy(source = located(failure.source)) else failure,
            topology =
                scenario.topology.copy(
                    clusters =
                        scenario.topology.clusters.map { cluster ->
                            val carbon = cluster.powerSource.carbon ?: return@map cluster
                            cluster.copy(powerSource = cluster.powerSource.copy(carbon = located(carbon)))
                        },
                ),
        )
    }

    /**
     * A name the deployment knows becomes the location of its bytes; anything else is left alone.
     *
     * A trace of several tables locates as the directory holding them, since that is what a reader
     * opens; one that is a single file locates as the file.
     */
    private fun located(reference: ResourceReference): ResourceReference {
        if (reference !is NamedReference) {
            return reference
        }
        val trace = Trace.findBySlug(reference.name) ?: return reference
        val table = trace.kind.tables.singleOrNull()
        val key = if (table == null) traceKey(trace.publicId) else traceKey(trace.publicId, table)
        return UriReference(store.locationOf(key))
    }
}

/**
 * How much workload a scenario has to get through.
 *
 * A workload written into the document is counted from the document; a stored trace from what was
 * measured when it was stored. One that was never measured is not guessed at, and estimates for it
 * rest on the model's fixed terms.
 */
fun traceExtentOf(workload: WorkloadSpec): TraceExtent =
    when (workload) {
        is InlineWorkloadSpec ->
            TraceExtent(
                taskCount = workload.tasks.size.toLong(),
                fragmentCount = workload.tasks.sumOf { it.fragments.size }.toLong(),
            )

        is TraceWorkloadSpec -> {
            val trace = (workload.source as? NamedReference)?.let { Trace.findBySlug(it.name) }
            val parts = trace?.let { TracePart.findByTrace(it.id).associateBy { part -> part.tableName } }.orEmpty()
            TraceExtent(
                taskCount = parts[TABLE_TASKS]?.rowCount ?: 0,
                fragmentCount = parts[TABLE_FRAGMENTS]?.rowCount ?: 0,
            )
        }
    }
