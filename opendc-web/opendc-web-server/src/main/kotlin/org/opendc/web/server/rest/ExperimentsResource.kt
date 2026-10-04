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

package org.opendc.web.server.rest

import jakarta.transaction.Transactional
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.server.auth.Download
import org.opendc.web.server.auth.DownloadLink
import org.opendc.web.server.auth.DownloadLinks
import org.opendc.web.server.auth.Identity
import org.opendc.web.server.auth.ProjectPermission
import org.opendc.web.server.auth.experimentFor
import org.opendc.web.server.auth.projectFor
import org.opendc.web.server.model.ExecutionUnit
import org.opendc.web.server.model.ProjectMember
import org.opendc.web.server.model.RunUnit
import org.opendc.web.server.model.UnitState
import org.opendc.web.server.results.ExperimentResults
import org.opendc.web.server.results.ResultsReader
import org.opendc.web.server.results.ScenarioResults
import org.opendc.web.server.service.CostEstimate
import org.opendc.web.server.service.SpecCodec
import org.opendc.web.server.service.SubmissionPipeline
import org.opendc.web.server.service.SubmissionPreview
import org.opendc.web.server.storage.ObjectStore
import java.time.Instant
import org.opendc.web.server.model.Experiment as ExperimentEntity

@Serializable
enum class ExperimentStateWire {
    @SerialName("draft")
    DRAFT,

    @SerialName("queued")
    QUEUED,

    @SerialName("running")
    RUNNING,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("partial")
    PARTIAL,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
enum class RunStateWire {
    @SerialName("queued")
    QUEUED,

    @SerialName("running")
    RUNNING,

    @SerialName("succeeded")
    SUCCEEDED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
enum class ExitReasonWire {
    @SerialName("ok")
    OK,

    @SerialName("simulationError")
    SIMULATION_ERROR,

    @SerialName("invalidSpec")
    INVALID_SPEC,

    @SerialName("oom")
    OOM,

    @SerialName("timeout")
    TIMEOUT,

    @SerialName("walltime")
    WALLTIME,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("rejected")
    REJECTED,

    @SerialName("unknown")
    UNKNOWN,
}

fun ExitReason.toWire(): ExitReasonWire =
    when (this) {
        ExitReason.OK -> ExitReasonWire.OK
        ExitReason.SIMULATION_ERROR -> ExitReasonWire.SIMULATION_ERROR
        ExitReason.INVALID_SPEC -> ExitReasonWire.INVALID_SPEC
        ExitReason.OOM -> ExitReasonWire.OOM
        ExitReason.TIMEOUT -> ExitReasonWire.TIMEOUT
        ExitReason.WALLTIME -> ExitReasonWire.WALLTIME
        ExitReason.CANCELLED -> ExitReasonWire.CANCELLED
        ExitReason.REJECTED -> ExitReasonWire.REJECTED
        ExitReason.UNKNOWN -> ExitReasonWire.UNKNOWN
    }

@Serializable
data class Experiment(
    val id: String,
    val projectId: String,
    val name: String,
    val state: ExperimentStateWire,
    val spec: JsonElement,
    val specHash: String,
    val estimate: CostEstimate,
    val createdAt: String,
    val updatedAt: String,
    val submittedAt: String? = null,
)

@Serializable
data class ProgressReport(
    val completedTasks: Int,
    val totalTasks: Int,
)

@Serializable
data class ExperimentSummary(
    val id: String,
    val name: String,
    val state: ExperimentStateWire,
    val scenarioCount: Int,
    val progress: ProgressReport,
    val createdAt: String,
    val submittedAt: String? = null,
)

@Serializable
data class ExitInfo(
    val exitCode: Int,
    val reason: ExitReasonWire,
    val message: String? = null,
)

@Serializable
data class ScenarioStatus(
    val scenarioIndex: Int,
    val state: RunStateWire,
    val completedTasks: Int,
    val totalTasks: Int,
    val attempt: Int,
    val exitInfo: ExitInfo? = null,
)

@Serializable
data class ExperimentStatus(
    val id: String,
    val name: String,
    val state: ExperimentStateWire,
    val completedTasks: Int,
    val totalTasks: Int,
    val scenarioCount: Int,
    val scenarios: List<ScenarioStatus>,
)

@Serializable
data class CreateExperimentRequest(
    val projectId: String,
    val name: String,
    val spec: JsonElement,
)

@Serializable
data class DraftChange(
    val name: String,
    val spec: JsonElement,
)

@Serializable
data class PreviewRequest(
    val spec: JsonElement,
)

// Far more than a chart has pixels and no more than the launcher itself holds, so a live series
// passes through unfolded and a month of parquet does not arrive a point at a time.
private const val MAX_BUCKETS = 2048

@Path("experiments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ExperimentsResource(
    private val identity: Identity,
    private val codec: SpecCodec,
    private val pipeline: SubmissionPipeline,
    private val results: ResultsReader,
    private val store: ObjectStore,
    private val links: DownloadLinks,
) {
    @GET
    fun list(
        @QueryParam("project") projectId: String?,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int,
    ): List<ExperimentSummary> {
        val experiments =
            if (projectId != null) {
                ExperimentEntity.findByProject(projectFor(identity.currentUser(), projectId, ProjectPermission.READ).project.id)
            } else {
                val memberships = ProjectMember.findByUser(identity.currentUser().id)
                memberships.flatMap { ExperimentEntity.findByProject(it.project.id) }
            }
        val window = experiments.drop(offset).take(limit)
        val unitsByExperiment = unitsFor(window.map { it.id })
        return window.map { experiment ->
            val units = unitsByExperiment[experiment.id].orEmpty()
            ExperimentSummary(
                id = experiment.publicId.toString(),
                name = experiment.name,
                state = experimentState(experiment, units),
                scenarioCount = experiment.scenarioCount,
                progress = ProgressReport(units.sumOf { it.completedTasks }, units.sumOf { it.totalTasks }),
                createdAt = experiment.createdAt.toString(),
                submittedAt = experiment.submittedAt?.toString(),
            )
        }
    }

    @POST
    @Transactional
    fun create(request: CreateExperimentRequest): Response {
        val name = validName(request.name, "Experiment")
        val project = projectFor(identity.currentUser(), request.projectId, ProjectPermission.EDIT).project
        val experiment = pipeline.createDraft(project, name, request.spec, identity.currentUser())
        return Response.status(201).entity(toWire(experiment)).build()
    }

    @GET
    @Path("{id}")
    fun get(
        @PathParam("id") id: String,
    ): Experiment = toWire(readable(id))

    // Every endpoint that writes builds its response inside the same transaction. Letting the
    // service commit and then assembling the reply outside it reads lazy associations with no
    // session, which fails after the write has already landed: the client is told the request
    // failed while the database says otherwise.
    @PUT
    @Path("{id}")
    @Transactional
    fun replace(
        @PathParam("id") id: String,
        change: DraftChange,
    ): Experiment {
        val name = validName(change.name, "Experiment")
        val experiment = editable(id)
        pipeline.replaceDraft(experiment, name, change.spec, identity.currentUser())
        return toWire(experiment)
    }

    @DELETE
    @Path("{id}")
    @Transactional
    fun delete(
        @PathParam("id") id: String,
    ): Response {
        pipeline.delete(editable(id))
        return Response.status(204).build()
    }

    @POST
    @Path("preview")
    fun preview(request: PreviewRequest): SubmissionPreview = pipeline.preview(request.spec, identity.currentUser())

    @POST
    @Path("{id}/submit")
    @Transactional
    fun submit(
        @PathParam("id") id: String,
    ): Experiment {
        val experiment = editable(id)
        pipeline.submit(experiment, identity.currentUser())
        return toWire(experiment)
    }

    @POST
    @Path("{id}/cancel")
    @Transactional
    fun cancel(
        @PathParam("id") id: String,
    ): Experiment {
        val experiment = editable(id)
        pipeline.cancel(experiment)
        return toWire(experiment)
    }

    // The new name is a query parameter rather than a body: it is one optional scalar, and a body
    // that may legitimately be absent is a trap for any client that sends a JSON content type with
    // nothing after it, which is exactly what fetch does when given no body.
    @POST
    @Path("{id}/clone")
    @Transactional
    fun clone(
        @PathParam("id") id: String,
        @QueryParam("name") name: String?,
    ): Response {
        val experiment = editable(id)
        val chosen = name?.let { validName(it, "Experiment") } ?: "${experiment.name} (copy)"
        val clone = pipeline.clone(experiment, chosen, identity.currentUser())
        return Response.status(201).entity(toWire(clone)).build()
    }

    @GET
    @Path("{id}/status")
    fun status(
        @PathParam("id") id: String,
    ): ExperimentStatus {
        val experiment = readable(id)
        val units = RunUnit.findByExperiment(experiment.id)
        val scenarios = scenarioStatuses(units, attemptsOf(experiment.id))
        return ExperimentStatus(
            id = experiment.publicId.toString(),
            name = experiment.name,
            state = experimentState(experiment, units),
            completedTasks = units.sumOf { it.completedTasks },
            totalTasks = units.sumOf { it.totalTasks },
            scenarioCount = experiment.scenarioCount,
            scenarios = scenarios,
        )
    }

    @GET
    @Path("{id}/scenarios/{index}")
    fun scenario(
        @PathParam("id") id: String,
        @PathParam("index") index: Int,
    ): ScenarioStatus {
        val experiment = readable(id)
        val units = RunUnit.findByExperiment(experiment.id).filter { it.scenarioIndex == index }
        if (units.isEmpty()) {
            throw notFound("Scenario")
        }
        return scenarioStatus(index, units, attemptsOf(experiment.id))
    }

    /**
     * Runs one scenario again.
     *
     * A scenario that failed for a reason outside the simulation, or one whose result is doubted, is
     * put back in the queue without the whole experiment having to be resubmitted.
     */
    @POST
    @Path("{id}/scenarios/{index}/retry")
    @Transactional
    fun retryScenario(
        @PathParam("id") id: String,
        @PathParam("index") index: Int,
    ): ScenarioStatus {
        val experiment = editable(id)
        val units = pipeline.retryScenario(experiment, index)
        return scenarioStatus(index, units, attemptsOf(experiment.id))
    }

    @GET
    @Path("{id}/results")
    fun results(
        @PathParam("id") id: String,
        @QueryParam("buckets") @DefaultValue("512") buckets: Int,
    ): ExperimentResults {
        val experiment = readable(id)
        val units = RunUnit.findByExperiment(experiment.id)
        val scenarios = results.read(experiment.publicId, units, buckets.coerceIn(1, MAX_BUCKETS))
        val exportIntervalMs = exportIntervalOf(experiment)
        return ExperimentResults(
            experimentId = experiment.publicId.toString(),
            exportIntervalMs = exportIntervalMs,
            bucketMs = bucketWidth(scenarios, exportIntervalMs),
            complete = !experiment.isDraft && units.isNotEmpty() && units.all { it.state.isTerminal },
            scenarios = scenarios,
        )
    }

    /**
     * Everything the experiment's runs produced, as one zip.
     *
     * The entries are laid out the way a local run of the same experiment lays out its output
     * directory, so an archive can be unpacked next to one and read by the same tooling without
     * anything being moved about first. Streamed rather than assembled, because an experiment of
     * many scenarios is far larger than anything worth holding in memory to send.
     */
    @GET
    @Path("{id}/archive")
    @Produces("application/zip")
    fun archive(
        @PathParam("id") id: String,
    ): Response {
        val experiment = readable(id)
        return archiveResponse(experiment, archiveKeys(experiment, store), store)
    }

    /**
     * A link a browser can follow to the archive, which a plain link could not do while it needs an
     * access token. Refused here, rather than when it is followed, when there is nothing to download.
     */
    @POST
    @Path("{id}/archive/link")
    fun archiveLink(
        @PathParam("id") id: String,
    ): DownloadLink {
        val experiment = readable(id)
        archiveKeys(experiment, store)
        return links.sign(Download.Archive(experiment.publicId.toString()), Instant.now())
    }

    /** How often the simulation samples, taking the finest of the export models the spec offers. */
    private fun exportIntervalOf(experiment: ExperimentEntity): Long =
        codec.decodeExperiment(codec.parseStored(experiment.spec)).exportModels.minOf { it.exportInterval.toMsLong() }

    /**
     * How far apart the points being sent are, read off the longest series rather than worked out
     * from the bucket count: a series shorter than the cap is not folded at all, and reporting a
     * width it does not have would mislabel the axis.
     */
    private fun bucketWidth(
        scenarios: List<ScenarioResults>,
        exportIntervalMs: Long,
    ): Long {
        val longest = scenarios.flatMap { it.series }.maxByOrNull { it.points.size }?.points.orEmpty()
        if (longest.size < 2) {
            return exportIntervalMs
        }
        return (longest.last().t - longest.first().t) / (longest.size - 1)
    }

    private fun readable(id: String): ExperimentEntity = experimentFor(identity.currentUser(), id, ProjectPermission.READ)

    private fun editable(id: String): ExperimentEntity = experimentFor(identity.currentUser(), id, ProjectPermission.EDIT)

    private fun toWire(experiment: ExperimentEntity): Experiment =
        Experiment(
            id = experiment.publicId.toString(),
            projectId = experiment.project.publicId.toString(),
            name = experiment.name,
            state = experimentState(experiment, RunUnit.findByExperiment(experiment.id)),
            spec = codec.parseStored(experiment.spec),
            specHash = experiment.specHash,
            estimate =
                CostEstimate(
                    scenarioCount = experiment.scenarioCount,
                    estimatedSimulationSeconds = experiment.estimatedSimulationSeconds,
                    estimatedBudgetSeconds = experiment.estimatedBudgetSeconds,
                ),
            createdAt = experiment.createdAt.toString(),
            updatedAt = experiment.updatedAt.toString(),
            submittedAt = experiment.submittedAt?.toString(),
        )

    private fun unitsFor(experimentIds: List<Long>): Map<Long, List<RunUnit>> =
        if (experimentIds.isEmpty()) {
            emptyMap()
        } else {
            RunUnit.list("experiment.id in ?1", experimentIds).groupBy { it.experiment.id }
        }

    /**
     * Each unit's latest attempt.
     *
     * A unit that has been retried has a row per execution that carried it, and rows arrive oldest
     * first, so the last write per unit is the attempt that counts.
     */
    private fun attemptsOf(experimentId: Long): Map<Long, ExecutionUnit> =
        ExecutionUnit.findByExperiment(experimentId).associateBy { it.unit.id }

    private fun scenarioStatuses(
        units: List<RunUnit>,
        attempts: Map<Long, ExecutionUnit>,
    ): List<ScenarioStatus> =
        units
            .groupBy { it.scenarioIndex }
            .toSortedMap()
            .map { (index, scenarioUnits) -> scenarioStatus(index, scenarioUnits, attempts) }

    private fun scenarioStatus(
        index: Int,
        units: List<RunUnit>,
        attempts: Map<Long, ExecutionUnit>,
    ): ScenarioStatus {
        val failed = units.firstOrNull { it.state == UnitState.FAILED || it.state == UnitState.CANCELLED }
        return ScenarioStatus(
            scenarioIndex = index,
            state = foldStates(units.map { it.state }),
            completedTasks = units.sumOf { it.completedTasks },
            totalTasks = units.sumOf { it.totalTasks },
            attempt = units.maxOf { attempts[it.id]?.execution?.attempt ?: 1 },
            exitInfo = failed?.let { attempts[it.id] }?.let(::exitInfo),
        )
    }

    /** How a unit's latest attempt ended, for a unit that has ended without succeeding. */
    private fun exitInfo(attempt: ExecutionUnit): ExitInfo? =
        attempt.exitReason?.let { reason ->
            ExitInfo(
                exitCode = attempt.execution.reportedExitCode,
                reason = reason.toWire(),
                message = attempt.exitMessage.ifEmpty { null },
            )
        }

    private fun experimentState(
        experiment: ExperimentEntity,
        units: List<RunUnit>,
    ): ExperimentStateWire {
        if (experiment.isDraft) {
            return ExperimentStateWire.DRAFT
        }
        return when (foldStates(units.map { it.state })) {
            RunStateWire.QUEUED -> ExperimentStateWire.QUEUED
            RunStateWire.RUNNING -> ExperimentStateWire.RUNNING
            RunStateWire.SUCCEEDED -> ExperimentStateWire.SUCCEEDED
            RunStateWire.FAILED ->
                if (units.any { it.state == UnitState.SUCCEEDED }) {
                    ExperimentStateWire.PARTIAL
                } else {
                    ExperimentStateWire.FAILED
                }
            RunStateWire.CANCELLED -> ExperimentStateWire.CANCELLED
        }
    }

    // Mirror of foldExperimentState in the frontend (lib/experiment/status.ts); the two must not
    // drift, which the status tests pin. A carried unit is running as far as a reader is concerned.
    private fun foldStates(states: List<UnitState>): RunStateWire =
        when {
            states.isEmpty() || states.all { it == UnitState.QUEUED } -> RunStateWire.QUEUED
            states.any { !it.isTerminal } -> RunStateWire.RUNNING
            states.all { it == UnitState.SUCCEEDED } -> RunStateWire.SUCCEEDED
            states.all { it == UnitState.CANCELLED } -> RunStateWire.CANCELLED
            else -> RunStateWire.FAILED
        }
}
