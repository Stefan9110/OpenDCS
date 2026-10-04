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

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import org.opendc.sdk.model.serialization.SdkJson
import org.opendc.web.dispatcher.Dispatcher
import org.opendc.web.dispatcher.ExitOutcome
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.dispatcher.PlatformSpan
import org.opendc.web.dispatcher.requeuedBags
import org.opendc.web.dispatcher.retryBags
import org.opendc.web.launcher.PeakMemory
import org.opendc.web.launcher.UnitFailure
import org.opendc.web.launcher.UnitOutcome
import org.opendc.web.server.model.Execution
import org.opendc.web.server.model.ExecutionState
import org.opendc.web.server.model.ExecutionUnit
import org.opendc.web.server.model.NO_EXIT_CODE
import org.opendc.web.server.model.UnitState
import org.opendc.web.server.model.UnitVerdict
import org.opendc.web.server.storage.ObjectStore
import org.opendc.web.server.storage.logKey
import org.opendc.web.server.storage.manifestKey
import org.opendc.web.server.storage.outcomeKey
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/** One run, as its outcome marker is keyed. */
private data class Run(
    val scenarioIndex: Int,
    val seed: Long,
)

/** What has to be read from the store before an execution is settled, gathered under no lock. */
private class Pending(
    val experimentId: UUID,
    val runs: List<Run>,
)

/**
 * Closes executions and decides what becomes of the units they carried.
 *
 * Each unit is settled from its own outcome marker, which its launcher published after the unit's
 * files: a marker certifies that the results under it are whole. A unit without one takes the
 * process's reason. Work a different grant could fix goes round again; anything else ends.
 *
 * The store is read and written outside any transaction, and the database written under the
 * execution's lock, so a remote call never holds a row and a cancel racing the settle is seen.
 */
@ApplicationScoped
class Settlement(
    private val dispatcher: Dispatcher,
    private val planner: ExecutionPlanner,
    private val config: ExecutionConfig,
    private val store: ObjectStore,
) {
    /** Settles the execution behind [executionId] from what the platform reported. Uses up an attempt. */
    fun settle(
        executionId: UUID,
        outcome: ExitOutcome,
    ) = close(executionId, outcome, Attempt.NEXT)

    /**
     * Puts the work of an execution the platform no longer knows back in the queue, at the same
     * attempt: losing it while the server was down says nothing about the work.
     */
    fun requeue(executionId: UUID) = close(executionId, LOST, Attempt.SAME)

    /**
     * Closes [execution], which is still queued and was never handed out, as cancelled. Runs in the
     * caller's transaction, which already holds what it needs.
     */
    fun withdraw(execution: Execution) {
        val now = Instant.now()
        execution.settle(ExecutionState.CANCELLED, WITHDRAWN, now)
        ExecutionUnit.findByExecution(execution.id).forEach { it.record(UnitVerdict.Cancelled) }
    }

    private fun close(
        executionId: UUID,
        outcome: ExitOutcome,
        attempt: Attempt,
    ) {
        val pending = QuarkusTransaction.requiringNew().call<Pending?> { pendingOf(executionId) } ?: return

        if (outcome.logTail.isNotEmpty()) {
            val log = outcome.logTail.encodeToByteArray()
            store.put(logKey(pending.experimentId, executionId), log.inputStream())
        }
        val markers = pending.runs.mapNotNull { run -> marker(pending.experimentId, executionId, run)?.let { run to it } }.toMap()
        store.delete(manifestKey(pending.experimentId, executionId))

        QuarkusTransaction.requiringNew().run { record(executionId, outcome, markers, attempt) }
    }

    private fun pendingOf(executionId: UUID): Pending? {
        val execution = Execution.findByPublicId(executionId)
        if (execution == null) {
            LOG.warn("Ignoring an outcome for an execution this server does not know: {}", executionId)
            return null
        }
        if (execution.state.isTerminal) {
            return null
        }
        return Pending(
            experimentId = execution.experiment.publicId,
            runs = ExecutionUnit.findByExecution(execution.id).map { Run(it.unit.scenarioIndex, it.unit.seed) },
        )
    }

    /** The outcome a launcher certified for [run], if it got as far as certifying one. */
    private fun marker(
        experimentId: UUID,
        executionId: UUID,
        run: Run,
    ): UnitOutcome? {
        val key = outcomeKey(experimentId, executionId, run.scenarioIndex, run.seed)
        if (!store.exists(key)) {
            return null
        }
        return try {
            store.open(key).use { SdkJson.json.decodeFromString(UnitOutcome.serializer(), it.readBytes().decodeToString()) }
        } catch (e: IllegalArgumentException) {
            LOG.warn("The outcome at {} did not decode", key, e)
            null
        }
    }

    private fun record(
        executionId: UUID,
        outcome: ExitOutcome,
        markers: Map<Run, UnitOutcome>,
        attempt: Attempt,
    ) {
        val execution = Execution.lockByPublicId(executionId) ?: return
        if (execution.state.isTerminal) {
            return
        }
        execution.settle(stateFor(outcome.reason), outcome, Instant.now())

        val retries = mutableMapOf<ExitReason, MutableList<ExecutionUnit>>()
        for (row in ExecutionUnit.findByExecution(execution.id)) {
            val unit = row.unit
            val verdict = verdictOf(markers[Run(unit.scenarioIndex, unit.seed)], outcome)
            row.record(verdict)
            // A unit cancelled while it was out stays cancelled, whatever its execution managed.
            if (unit.state != UnitState.CARRIED) {
                continue
            }
            when (verdict) {
                is UnitVerdict.Succeeded -> {
                    unit.state = UnitState.SUCCEEDED
                    // A run that finished got through all of its work, whatever the last report to
                    // reach the server said: the denominator is planned rather than measured.
                    unit.completedTasks = unit.totalTasks
                }
                is UnitVerdict.Failed ->
                    if (verdict.reason.isResourceShaped) {
                        retries.getOrPut(verdict.reason) { mutableListOf() } += row
                    } else {
                        unit.state = UnitState.FAILED
                    }
                UnitVerdict.Cancelled -> unit.state = UnitState.CANCELLED
            }
        }
        for ((reason, group) in retries) {
            retry(execution, reason, group, attempt)
        }
    }

    /** Writes down the executions [group] goes round again in, or ends it where nothing is worth trying. */
    private fun retry(
        execution: Execution,
        reason: ExitReason,
        group: List<ExecutionUnit>,
        attempt: Attempt,
    ) {
        val units = planner.storedEstimates(group)
        val slot = dispatcher.slot()
        val policy = config.toPolicy()
        val bags =
            when (attempt) {
                Attempt.NEXT -> retryBags(units, execution.grant, execution.attempt, reason, slot, policy)
                Attempt.SAME -> requeuedBags(units, execution.grant, slot, policy)
            }
        if (bags.isEmpty()) {
            group.forEach { it.unit.state = UnitState.FAILED }
            return
        }
        val next =
            when (attempt) {
                Attempt.NEXT -> execution.attempt + 1
                Attempt.SAME -> execution.attempt
            }
        for (bag in bags) {
            planner.queue(execution.experiment, bag, group.map { it.unit }, next, dispatcher.name)
        }
    }

    /** Whether going round again counts against the work's attempts. */
    private enum class Attempt { NEXT, SAME }

    private companion object {
        val LOG = LoggerFactory.getLogger(Settlement::class.java)

        val LOST =
            ExitOutcome(
                ExitReason.UNKNOWN,
                NO_EXIT_CODE,
                "lost while the server was down",
                PlatformSpan.NotStarted,
                PeakMemory.Unmeasured,
                "",
            )

        val WITHDRAWN =
            ExitOutcome(
                ExitReason.CANCELLED,
                NO_EXIT_CODE,
                "cancelled before it started",
                PlatformSpan.NotStarted,
                PeakMemory.Unmeasured,
                "",
            )
    }
}

/** What the execution as a whole records: whether the process ended normally, not how its units did. */
private fun stateFor(reason: ExitReason): ExecutionState =
    when (reason) {
        ExitReason.OK -> ExecutionState.SUCCEEDED
        ExitReason.CANCELLED -> ExecutionState.CANCELLED
        else -> ExecutionState.FAILED
    }

/**
 * How one unit ended: from its marker where its launcher certified one, else from the process.
 *
 * A process that ended normally without certifying a unit did not finish that unit, so its silence is
 * an unexplained failure rather than a success.
 */
private fun verdictOf(
    marker: UnitOutcome?,
    outcome: ExitOutcome,
): UnitVerdict =
    when (marker) {
        is UnitOutcome.Succeeded -> UnitVerdict.Succeeded(marker.seconds)
        is UnitOutcome.Failed ->
            when (marker.failure) {
                UnitFailure.INVALID_SPEC -> UnitVerdict.Failed(ExitReason.INVALID_SPEC, marker.message)
                UnitFailure.SIMULATION_ERROR -> UnitVerdict.Failed(ExitReason.SIMULATION_ERROR, marker.message)
                UnitFailure.TRANSFER -> UnitVerdict.Failed(ExitReason.UNKNOWN, marker.message)
            }
        null ->
            when (outcome.reason) {
                ExitReason.CANCELLED -> UnitVerdict.Cancelled
                ExitReason.OK -> UnitVerdict.Failed(ExitReason.UNKNOWN, "ended without reporting this unit")
                else -> UnitVerdict.Failed(outcome.reason, outcome.message)
            }
    }
