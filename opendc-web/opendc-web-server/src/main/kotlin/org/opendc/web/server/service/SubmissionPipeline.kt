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
import org.opendc.sdk.model.experiment.expand
import org.opendc.sdk.model.failure.TraceBasedFailureSpec
import org.opendc.sdk.model.resource.NamedReference
import org.opendc.sdk.model.resource.ResourceReference
import org.opendc.sdk.model.workload.TraceWorkloadSpec
import org.opendc.web.dispatcher.PlannedBag
import org.opendc.web.dispatcher.PlannedUnit
import org.opendc.web.dispatcher.packUnits
import org.opendc.web.server.model.BagExecution
import org.opendc.web.server.model.ExecutionState
import org.opendc.web.server.model.ExitReason
import org.opendc.web.server.model.Experiment
import org.opendc.web.server.model.ExperimentResource
import org.opendc.web.server.model.Project
import org.opendc.web.server.model.RunUnit
import org.opendc.web.server.model.TraceKind
import org.opendc.web.server.rest.DocumentIssue
import org.opendc.web.server.rest.conflict
import org.opendc.web.server.rest.invalidDocument
import org.opendc.web.server.rest.toWire
import java.time.Instant
import kotlin.math.ceil

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
    private val estimator: Estimator,
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
            estimate = estimator.estimate(spec.expand()),
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
        val units =
            scenarios.flatMap { scenario ->
                val estimate = estimator.estimateUnit(scenario)
                (0 until scenario.runs).map { run ->
                    PlannedUnit(
                        scenarioIndex = scenario.id,
                        seed = (scenario.initialSeed + run).toLong(),
                        cpuSeconds = estimate.cpuSeconds,
                        peakMemoryMb = estimate.peakMemoryMb,
                    )
                }
            }
        if (units.isEmpty()) {
            throw conflict("This experiment expands to no scenarios")
        }

        extractResources(experiment, spec)
        persistBags(experiment, packUnits(units, estimator.slot()))
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
        for (unit in units.filterNot { it.state.isTerminal }) {
            unit.state = ExecutionState.CANCELLED
        }
        for (bag in BagExecution.findByExperiment(experiment.id).filterNot { it.state.isTerminal }) {
            bag.state = ExecutionState.CANCELLED
            bag.exitReason = ExitReason.CANCELLED
        }
        experiment.updatedAt = Instant.now()
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
     * bags and units are removed by the schema's cascade, so marking them cancelled on the way out
     * would be wasted work, and worse: it dirties rows that point at an experiment being deleted in
     * the same transaction, which fails the flush.
     */
    @Transactional
    fun delete(experiment: Experiment) {
        // dispatcher.cancel(experiment) belongs here once a dispatcher exists.
        experiment.delete()
    }

    private fun applyDraft(
        experiment: Experiment,
        name: String,
        document: JsonElement,
    ) {
        val spec = codec.decodeExperiment(document)
        val canonical = codec.canonical(spec)
        val estimate = estimator.estimate(spec.expand())
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

    private fun persistBags(
        experiment: Experiment,
        bags: List<PlannedBag>,
    ) {
        bags.forEachIndexed { index, planned ->
            val bag = BagExecution()
            bag.experiment = experiment
            bag.bagIndex = index
            bag.state = ExecutionState.QUEUED
            bag.parallelism = planned.parallelism
            bag.estimatedSeconds = planned.estimatedSeconds
            bag.memoryLimitMb = ceil(planned.memoryRequestMb).toInt()
            bag.timeLimitSeconds = planned.timeLimitSeconds
            bag.persist()
            for (planUnit in planned.units) {
                val unit = RunUnit()
                unit.bag = bag
                unit.experiment = experiment
                unit.scenarioIndex = planUnit.scenarioIndex
                unit.seed = planUnit.seed
                unit.state = ExecutionState.QUEUED
                unit.persist()
            }
        }
    }
}
