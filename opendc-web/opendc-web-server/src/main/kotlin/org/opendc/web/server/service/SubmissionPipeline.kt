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

package org.opendc.web.server.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import org.opendc.sdk.model.experiment.ExperimentSpec
import org.opendc.sdk.model.experiment.ScenarioSpec
import org.opendc.sdk.model.experiment.expand
import org.opendc.sdk.model.failure.TraceBasedFailureSpec
import org.opendc.sdk.model.resource.NamedReference
import org.opendc.sdk.model.resource.ResourceReference
import org.opendc.sdk.model.workload.TraceWorkloadSpec
import org.opendc.web.dispatcher.Dispatcher
import org.opendc.web.dispatcher.estimate.TraceSizeEstimator
import org.opendc.web.server.execution.ExecutionConfig
import org.opendc.web.server.execution.toCoefficients
import org.opendc.web.server.execution.traceExtentOf
import org.opendc.web.server.model.Execution
import org.opendc.web.server.model.ExecutionState
import org.opendc.web.server.model.Experiment
import org.opendc.web.server.model.ExperimentResource
import org.opendc.web.server.model.Project
import org.opendc.web.server.model.RunUnit
import org.opendc.web.server.model.TraceKind
import org.opendc.web.server.rest.DocumentIssue
import org.opendc.web.server.rest.conflict
import org.opendc.web.server.rest.invalidDocument
import org.opendc.web.server.rest.notFound
import org.opendc.web.server.rest.toWire
import java.time.Instant

/** What an experiment is expected to cost its owner. Billing is in simulation seconds, never memory. */
@Serializable
data class CostEstimate(
    val scenarioCount: Int,
    val estimatedSimulationSeconds: Double,
    val estimatedBudgetSeconds: Double,
)

/** What the draft editor needs to know about a spec before anything is persisted. */
@Serializable
data class SubmissionPreview(
    val scenarioCount: Int,
    val estimate: CostEstimate,
    val issues: List<DocumentIssue>,
)

/**
 * The write path for experiments. Drafts must strict-parse (an unknown key is a 400) but may
 * carry validation issues while being edited; submission is the gate that requires a clean
 * validate(), freezes the document forever and turns it into queued execution records.
 */
@ApplicationScoped
class SubmissionPipeline(
    private val codec: SpecCodec,
    private val config: ExecutionConfig,
    private val dispatcher: Dispatcher,
) {
    @Transactional
    fun createDraft(
        project: Project,
        name: String,
        document: JsonElement,
    ): Experiment {
        val experiment = Experiment()
        experiment.project = project
        experiment.createdAt = Instant.now()
        applyDraft(experiment, name, document)
        experiment.persist()
        return experiment
    }

    @Transactional
    fun replaceDraft(
        experiment: Experiment,
        name: String,
        document: JsonElement,
    ) {
        if (!experiment.isDraft) {
            throw conflict("A submitted experiment can no longer be edited")
        }
        applyDraft(experiment, name, document)
    }

    fun preview(document: JsonElement): SubmissionPreview {
        val spec = codec.decodeExperiment(document)
        return SubmissionPreview(
            scenarioCount = spec.expand().size,
            estimate = estimate(spec.expand()),
            issues = spec.validate().toWire(),
        )
    }

    @Transactional
    fun submit(experiment: Experiment) {
        if (!experiment.isDraft) {
            throw conflict("This experiment has already been submitted")
        }
        val spec = codec.decodeExperiment(codec.parseStored(experiment.spec))
        val issues = spec.validate()
        if (issues.isNotEmpty()) {
            throw invalidDocument("The experiment document is invalid", issues.toWire())
        }
        val scenarios = spec.expand()
        if (scenarios.isEmpty()) {
            throw conflict("This experiment expands to no scenarios")
        }

        extractResources(experiment, spec)
        // Only the work is written here. How it is shaped into executions depends on the slot a
        // dispatcher offers and on what earlier attempts did, neither of which is known yet.
        for (scenario in scenarios) {
            for (run in 0 until scenario.runs) {
                val unit = RunUnit()
                unit.experiment = experiment
                unit.scenarioIndex = scenario.id
                unit.seed = (scenario.initialSeed + run).toLong()
                unit.state = ExecutionState.QUEUED
                unit.persist()
            }
        }
        val now = Instant.now()
        experiment.submittedAt = now
        experiment.updatedAt = now
    }

    @Transactional
    fun cancel(experiment: Experiment) {
        if (experiment.isDraft) {
            throw conflict("A draft experiment is not running")
        }
        val units = RunUnit.findByExperiment(experiment.id)
        if (units.all { it.state.isTerminal }) {
            throw conflict("This experiment has already finished")
        }
        stopExecutions(experiment)
        for (unit in units.filterNot { it.state.isTerminal }) {
            unit.state = ExecutionState.CANCELLED
        }
        experiment.updatedAt = Instant.now()
    }

    /**
     * Queues one scenario's runs again.
     *
     * Only a scenario that has stopped can be restarted, and every run of it goes: a scenario is
     * what a reader picked, and half of one running is not a state worth being able to reach. The
     * output is written to the same place as before, so an earlier attempt's results are replaced
     * rather than accumulated.
     */
    @Transactional
    fun retryScenario(
        experiment: Experiment,
        scenarioIndex: Int,
    ): List<RunUnit> {
        if (experiment.isDraft) {
            throw conflict("A draft experiment has nothing to restart")
        }
        val units = RunUnit.findByExperiment(experiment.id).filter { it.scenarioIndex == scenarioIndex }
        if (units.isEmpty()) {
            throw notFound("Scenario")
        }
        if (units.any { !it.state.isTerminal }) {
            throw conflict("This scenario is still running")
        }
        for (unit in units) {
            unit.state = ExecutionState.QUEUED
        }
        experiment.updatedAt = Instant.now()
        return units
    }

    @Transactional
    fun clone(
        experiment: Experiment,
        name: String,
    ): Experiment = createDraft(experiment.project, name, codec.parseStored(experiment.spec))

    /**
     * Removes an experiment whatever state it is in. Submitted experiments are immutable, which
     * governs editing them, not keeping them: an owner may always delete their own work.
     *
     * Stopping the scenarios of a live experiment is a dispatcher call, not a database write. The
     * executions and units are removed by the schema's cascade, so marking them cancelled on the
     * way out would be wasted work, and worse: it dirties rows that point at an experiment being
     * deleted in the same transaction, which fails the flush.
     */
    @Transactional
    fun delete(experiment: Experiment) {
        stopExecutions(experiment)
        experiment.delete()
    }

    /**
     * Kills whatever this experiment still has running.
     *
     * The rows are left as they are: the platform reports each execution ending in its own time,
     * and settling on that rather than on this call is what keeps one account of what happened.
     */
    private fun stopExecutions(experiment: Experiment) {
        for (execution in Execution.findByExperiment(experiment.id).filterNot { it.state.isTerminal }) {
            if (execution.dispatcher == dispatcher.name) {
                dispatcher.cancel(execution.publicId)
            }
        }
    }

    /**
     * What a document will cost its owner, which is a different question from what dispatch needs
     * to know: it reads the same model, but no per-deployment correction. What a user is quoted
     * should not move because a cluster they never chose turned out to be slow.
     */
    private fun estimate(scenarios: List<ScenarioSpec>): CostEstimate {
        val model = TraceSizeEstimator(config.estimator().toCoefficients())
        val seconds =
            scenarios.sumOf { scenario ->
                scenario.runs * model.estimate(scenario, traceExtentOf(scenario.workload)).cpuSeconds
            }
        return CostEstimate(
            scenarioCount = scenarios.size,
            estimatedSimulationSeconds = seconds,
            estimatedBudgetSeconds = seconds,
        )
    }

    private fun applyDraft(
        experiment: Experiment,
        name: String,
        document: JsonElement,
    ) {
        val spec = codec.decodeExperiment(document)
        val canonical = codec.canonical(spec)
        val estimate = estimate(spec.expand())
        experiment.name = name
        experiment.spec = canonical
        experiment.specHash = codec.hash(canonical)
        experiment.scenarioCount = estimate.scenarioCount
        experiment.estimatedSimulationSeconds = estimate.estimatedSimulationSeconds
        experiment.estimatedBudgetSeconds = estimate.estimatedBudgetSeconds
        experiment.updatedAt = Instant.now()
    }

    private fun extractResources(
        experiment: Experiment,
        spec: ExperimentSpec,
    ) {
        val references =
            spec.workloads.filterIsInstance<TraceWorkloadSpec>().map { TraceKind.WORKLOAD to it.source } +
                spec.failureModels.filterIsInstance<TraceBasedFailureSpec>().map { TraceKind.FAILURE to it.source } +
                spec.topologies.flatMap { topology ->
                    topology.clusters.mapNotNull { cluster ->
                        cluster.powerSource.carbon?.let { TraceKind.CARBON to it }
                    }
                }
        for ((kind, reference) in references) {
            val row = ExperimentResource()
            row.experiment = experiment
            row.kind = kind
            row.reference = codec.json.encodeToString<ResourceReference>(reference)
            row.referenceName = (reference as? NamedReference)?.name
            row.persist()
        }
    }
}
