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
import org.opendc.sdk.model.resource.NamedReference
import org.opendc.sdk.model.resource.ResourceReference
import org.opendc.sdk.model.resource.UriReference
import org.opendc.sdk.model.workload.EfficientTraceWorkloadSpec
import org.opendc.sdk.model.workload.InlineWorkloadSpec
import org.opendc.sdk.model.workload.TraceWorkloadSpec
import org.opendc.sdk.model.workload.WorkloadSpec
import org.opendc.trace.conv.TABLE_FRAGMENTS
import org.opendc.trace.conv.TABLE_TASKS
import org.opendc.web.dispatcher.ExecutionSlot
import org.opendc.web.dispatcher.PlannedBag
import org.opendc.web.dispatcher.PlannedUnit
import org.opendc.web.dispatcher.Unfit
import org.opendc.web.dispatcher.estimate.ResourceEstimator
import org.opendc.web.dispatcher.estimate.TraceExtent
import org.opendc.web.dispatcher.estimate.TraceSizeEstimator
import org.opendc.web.dispatcher.estimate.scaledBy
import org.opendc.web.dispatcher.planBags
import org.opendc.web.dispatcher.unfit
import org.opendc.web.launcher.LaunchManifest
import org.opendc.web.launcher.LaunchUnit
import org.opendc.web.launcher.OutputTarget
import org.opendc.web.launcher.StagedInput
import org.opendc.web.launcher.TelemetryTarget
import org.opendc.web.server.model.Execution
import org.opendc.web.server.model.ExecutionState
import org.opendc.web.server.model.ExecutionUnit
import org.opendc.web.server.model.Experiment
import org.opendc.web.server.model.RunUnit
import org.opendc.web.server.model.Trace
import org.opendc.web.server.model.TracePart
import org.opendc.web.server.model.UnitState
import org.opendc.web.server.service.SpecCodec
import org.opendc.web.server.storage.ObjectStore
import org.opendc.web.server.storage.outcomeKey
import org.opendc.web.server.storage.runKey
import org.opendc.web.server.storage.traceKey
import java.time.Duration
import java.time.Instant

/** Where a launcher puts the bytes it is handed, relative to its working directory. */
private const val INPUTS = "inputs"

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
    /** The executions [units] would form on [slot], largest first, from fresh estimates. */
    fun plan(
        experiment: Experiment,
        units: List<RunUnit>,
        slot: ExecutionSlot,
    ): List<PlannedBag> = planBags(estimate(scenariosOf(experiment), units), slot, config.toPolicy())

    /**
     * The scenarios no execution on [slot] can hold, whatever they are packed with: one estimated to
     * run past the slot's time cap, or to need more memory than any grant worth asking for.
     */
    fun unfit(
        scenarios: List<ScenarioSpec>,
        slot: ExecutionSlot,
    ): List<Unfit> {
        val model = estimator()
        return unfit(scenarios.map { planned(model, it, seed = 0) }, slot, config.toPolicy())
    }

    /**
     * What the units [carried] were expected to cost when they last ran, which is where a retry starts.
     * Kept rather than recomputed, so growth after an out-of-memory kill carries into the next attempt.
     */
    fun storedEstimates(carried: List<ExecutionUnit>): List<PlannedUnit> =
        carried.map { PlannedUnit(it.unit.scenarioIndex, it.unit.seed, it.estimatedSeconds, it.estimatedPeakMemoryMb) }

    /**
     * Writes down an execution of [units] shaped as [bag]. It is started by the next pass of the
     * queue, or by this one. Its units are carried from here on and start from nothing.
     */
    fun queue(
        experiment: Experiment,
        bag: PlannedBag,
        units: List<RunUnit>,
        attempt: Int,
        dispatcher: String,
    ): Execution {
        val execution = Execution()
        execution.experiment = experiment
        execution.state = ExecutionState.QUEUED
        execution.attempt = attempt
        execution.dispatcher = dispatcher
        execution.parallelism = bag.grant.parallelism
        execution.heapMb = bag.grant.heapMb
        execution.memoryRequestMb = bag.grant.memoryRequestMb
        execution.timeLimitSeconds = bag.grant.timeLimitSeconds
        execution.estimatedMakespanSeconds = bag.makespanSeconds
        execution.runtimeMultiplier = config.estimator().runtimeMultiplier()
        execution.memoryMultiplier = config.estimator().memoryMultiplier()
        execution.createdAt = Instant.now()
        execution.persist()

        val byRun = units.associateBy { it.scenarioIndex to it.seed }
        for (planned in bag.units) {
            val unit = byRun[planned.scenarioIndex to planned.seed] ?: continue
            unit.state = UnitState.CARRIED
            // The attempt starts over from nothing, so what an earlier one got through is no longer
            // progress towards anything.
            unit.completedTasks = 0
            val row = ExecutionUnit()
            row.execution = execution
            row.unit = unit
            row.estimatedSeconds = planned.cpuSeconds
            row.estimatedPeakMemoryMb = planned.peakMemoryMb
            row.persist()
            execution.carried += row
        }
        return execution
    }

    /**
     * What one launcher process is handed for [execution].
     *
     * Every trace the units name is staged under the launcher's working directory and every reference
     * rewritten to where it was staged, so the launcher never has to know what a name means. Each run
     * publishes each of its files and then its outcome to a URL of its own, signed for [lifetime].
     * [token] is what it reports progress with, and can do nothing else.
     */
    fun manifest(
        execution: Execution,
        token: String,
        lifetime: Duration,
    ): LaunchManifest {
        val experiment = execution.experiment.publicId
        val scenarios = scenariosOf(execution.experiment)
        val staging = Staging(store, lifetime)
        val units =
            ExecutionUnit.findByExecution(execution.id).mapNotNull { row ->
                val unit = row.unit
                val scenario = scenarios[unit.scenarioIndex] ?: return@mapNotNull null
                LaunchUnit(
                    scenario =
                        scenario
                            .mapReferences { staging.stage(it.reference) }
                            .copy(runs = 1, initialSeed = unit.seed.toInt(), id = unit.scenarioIndex),
                    outputs =
                        scenario.exportModel.filesToExport.distinct().map { file ->
                            OutputTarget(
                                file.fileName,
                                store.writeUrl("${runKey(experiment, unit.scenarioIndex, unit.seed)}/${file.fileName}", lifetime),
                            )
                        },
                    outcome = store.writeUrl(outcomeKey(experiment, execution.publicId, unit.scenarioIndex, unit.seed), lifetime),
                )
            }
        return LaunchManifest(
            inputs = staging.inputs,
            units = units,
            parallelism = execution.parallelism,
            telemetry = TelemetryTarget.Endpoint(config.telemetryUrl(), token),
        )
    }

    private fun estimate(
        scenarios: Map<Int, ScenarioSpec>,
        units: List<RunUnit>,
    ): List<PlannedUnit> {
        val model = estimator()
        return units.mapNotNull { unit -> scenarios[unit.scenarioIndex]?.let { planned(model, it, unit.seed) } }
    }

    /** The dispatch estimate, corrected by this deployment's multipliers. */
    private fun estimator(): ResourceEstimator =
        TraceSizeEstimator(config.estimator().toCoefficients())
            .scaledBy(config.estimator().runtimeMultiplier(), config.estimator().memoryMultiplier())

    private fun planned(
        model: ResourceEstimator,
        scenario: ScenarioSpec,
        seed: Long,
    ): PlannedUnit {
        val estimate = model.estimate(scenario, traceExtentOf(scenario.workload))
        return PlannedUnit(scenario.id, seed, estimate.cpuSeconds, estimate.peakMemoryMb)
    }

    private fun scenariosOf(experiment: Experiment): Map<Int, ScenarioSpec> =
        codec.decodeExperiment(codec.parseStored(experiment.spec)).expand().associateBy { it.id }
}

/**
 * The inputs one manifest stages, gathered as its units' references are rewritten.
 *
 * Each trace is staged once however many units name it. A trace of several tables is referred to as
 * the directory holding them, since that is what a reader opens; one of a single table as the file.
 * A reference the deployment does not know is left as it is, for the launcher to refuse.
 */
private class Staging(
    private val store: ObjectStore,
    private val lifetime: Duration,
) {
    private val directories = mutableMapOf<Long, String>()

    val inputs = mutableListOf<StagedInput>()

    fun stage(reference: ResourceReference): ResourceReference {
        if (reference !is NamedReference) {
            return reference
        }
        val trace = Trace.findBySlug(reference.name) ?: return reference
        val directory =
            directories.getOrPut(trace.id) {
                val path = "$INPUTS/${directories.size}"
                for (table in trace.kind.tables) {
                    val key = traceKey(trace.publicId, table)
                    inputs += StagedInput("$path/$table.parquet", store.readUrl(key, lifetime), key)
                }
                path
            }
        val table = trace.kind.tables.singleOrNull()
        return UriReference(if (table == null) directory else "$directory/$table.parquet")
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

        is TraceWorkloadSpec -> storedExtentOf(workload.source)
        is EfficientTraceWorkloadSpec -> storedExtentOf(workload.source)
    }

private fun storedExtentOf(source: ResourceReference): TraceExtent {
    val trace = (source as? NamedReference)?.let { Trace.findBySlug(it.name) }
    val parts = trace?.let { TracePart.findByTrace(it.id).associateBy { part -> part.tableName } }.orEmpty()
    return TraceExtent(
        taskCount = parts[TABLE_TASKS]?.rowCount ?: 0,
        fragmentCount = parts[TABLE_FRAGMENTS]?.rowCount ?: 0,
    )
}
