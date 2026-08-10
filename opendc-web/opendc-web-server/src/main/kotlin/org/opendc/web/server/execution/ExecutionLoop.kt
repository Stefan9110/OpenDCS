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
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.opendc.web.dispatcher.Dispatcher
import org.opendc.web.dispatcher.ExitOutcome
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.dispatcher.LaunchRequest
import org.opendc.web.dispatcher.PlannedBag
import org.opendc.web.dispatcher.retryBags
import org.opendc.web.server.model.Execution
import org.opendc.web.server.model.ExecutionState
import org.opendc.web.server.model.Experiment
import org.opendc.web.server.model.RunUnit
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/**
 * Keeps the platform as busy as the queue and its capacity allow, and records what comes back.
 *
 * The server admits and the platform queues: each pass shapes the oldest outstanding work against
 * the slot the dispatcher offers, starts it if there is room, and otherwise leaves it queued.
 *
 * Transactions are opened by hand rather than declared, because the two things that must not share
 * one are a database write and starting a process: a launch inside the claiming transaction would
 * survive a rollback as a process nothing has a record of.
 */
@ApplicationScoped
class ExecutionLoop(
    private val dispatcher: Dispatcher,
    private val planner: ExecutionPlanner,
    private val config: ExecutionConfig,
) {
    /**
     * Attaches the observer and settles whatever this server left running when it stopped.
     *
     * Recovery asks the platform rather than sweeping for anything that has been quiet too long. An
     * execution it no longer knows is retried, which is safe because a retry writes the same
     * deterministic output as the attempt it replaces.
     */
    fun onStart(
        @Suppress("UNUSED_PARAMETER") @Observes event: StartupEvent,
    ) {
        dispatcher.observe(::settle)

        val live = QuarkusTransaction.requiringNew().call<List<UUID>> { runningIds() }
        if (live.isEmpty()) {
            return
        }
        val alive = dispatcher.reconcile(live)
        QuarkusTransaction.requiringNew().run {
            for (executionId in live - alive) {
                LOG.info("The platform no longer knows execution {}; its work goes round again", executionId)
                settleRow(executionId, LOST)
            }
        }
    }

    @Scheduled(every = "2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun drain() {
        while (true) {
            val request =
                try {
                    QuarkusTransaction.requiringNew().call<LaunchRequest?> { claim() } ?: return
                } catch (e: Exception) {
                    LOG.error("Could not shape queued work", e)
                    return
                }
            try {
                dispatcher.launch(request)
            } catch (e: Exception) {
                LOG.error("Could not launch execution ${request.executionId}", e)
                QuarkusTransaction.requiringNew().run { release(request.executionId) }
                return
            }
        }
    }

    /** Where a platform's report of an execution ending lands. */
    fun settle(
        executionId: UUID,
        exit: ExitOutcome,
    ) {
        QuarkusTransaction.requiringNew().run { settleRow(executionId, exit) }
    }

    /**
     * The next execution to start, marked as running before this returns.
     *
     * One a retry wrote down comes first, since its work is already spoken for; otherwise the
     * oldest submitted units are shaped into a new execution.
     */
    private fun claim(): LaunchRequest? {
        val execution = admissible() ?: return null
        execution.state = ExecutionState.RUNNING
        execution.startedAt = Instant.now()
        execution.units.forEach { it.state = ExecutionState.RUNNING }
        return LaunchRequest(
            executionId = execution.publicId,
            manifest = planner.manifest(execution.experiment, execution.units, execution.parallelism, execution.grantToken()),
            heapMb = execution.heapMb.toDouble(),
            memoryRequestMb = execution.memoryRequestMb.toDouble(),
            timeLimitSeconds = execution.timeLimitSeconds,
        )
    }

    /** An execution the platform would take right now, written down but not yet started. */
    private fun admissible(): Execution? {
        val waiting = Execution.findLive().firstOrNull { it.state == ExecutionState.QUEUED }
        if (waiting != null) {
            return waiting.takeIf { dispatcher.admits(it.parallelism, it.memoryRequestMb.toDouble()) }
        }
        val units = RunUnit.findQueued().groupBy { it.experiment.id }.values.firstOrNull() ?: return null
        val experiment = units.first().experiment
        val bag = planner.plan(experiment, units, dispatcher.slot()).firstOrNull() ?: return null
        if (!dispatcher.admits(bag.parallelism, bag.memoryRequestMb)) {
            return null
        }
        return persist(experiment, bag, unitsOf(bag, units), attempt = 1)
    }

    /** Puts the work back when the platform would not take it. */
    private fun release(executionId: UUID) {
        val execution = Execution.findByPublicId(executionId) ?: return
        execution.units.forEach { it.state = ExecutionState.QUEUED }
        execution.delete()
    }

    private fun runningIds(): List<UUID> = Execution.findLive().filter { it.state == ExecutionState.RUNNING }.map { it.publicId }

    /**
     * Records how an execution ended, and decides whether its work goes round again.
     *
     * A failure that a different grant could fix becomes a fresh execution; one that would fail
     * identically ends its units.
     */
    private fun settleRow(
        executionId: UUID,
        exit: ExitOutcome,
    ) {
        val execution = Execution.findByPublicId(executionId)
        if (execution == null) {
            LOG.warn("Ignoring an outcome for an execution this server does not know: {}", executionId)
            return
        }
        if (execution.state.isTerminal) {
            return
        }
        execution.state = if (exit.reason == ExitReason.OK) ExecutionState.SUCCEEDED else terminalStateFor(exit.reason)
        execution.exitCode = exit.exitCode
        execution.exitReason = exit.reason
        execution.exitMessage = exit.message.ifEmpty { null }
        execution.finishedAt = Instant.now()

        if (exit.reason == ExitReason.OK) {
            // A run that finished got through all of its work, whatever the last report to reach the
            // server said. The denominator is planned rather than measured, so a sampled run would
            // otherwise leave the bar short of the end of a scenario that is over.
            execution.units.forEach {
                it.state = ExecutionState.SUCCEEDED
                it.completedTasks = it.totalTasks
            }
            return
        }
        val retries =
            retryBags(
                planner.bagOf(execution),
                execution.attempt,
                exit.reason,
                dispatcher.slot(),
                config.toPolicy(),
            )
        if (retries.isEmpty()) {
            execution.units.forEach { it.state = terminalStateFor(exit.reason) }
            return
        }
        for (bag in retries) {
            val units = unitsOf(bag, execution.units)
            // The attempt starts over from nothing, so what the one it replaces got through is no
            // longer progress towards anything.
            units.forEach { it.completedTasks = 0 }
            persist(execution.experiment, bag, units, execution.attempt + 1)
        }
    }

    /** Which of [candidates] a planned bag is made of. */
    private fun unitsOf(
        bag: PlannedBag,
        candidates: List<RunUnit>,
    ): List<RunUnit> {
        val planned = bag.units.mapTo(mutableSetOf()) { it.scenarioIndex to it.seed }
        return candidates.filter { (it.scenarioIndex to it.seed) in planned }
    }

    /** Writes down an execution. It is started by the next pass of the queue, or by this one. */
    private fun persist(
        experiment: Experiment,
        bag: PlannedBag,
        units: List<RunUnit>,
        attempt: Int,
    ): Execution {
        val execution = Execution()
        execution.experiment = experiment
        execution.units = units.toMutableList()
        execution.state = ExecutionState.QUEUED
        execution.attempt = attempt
        execution.dispatcher = dispatcher.name
        execution.parallelism = bag.parallelism
        execution.heapMb = ceil(bag.heapMb).toInt()
        execution.memoryRequestMb = ceil(bag.memoryRequestMb).toInt()
        execution.timeLimitSeconds = bag.timeLimitSeconds
        execution.estimatedMakespanSeconds = bag.makespanSeconds
        execution.createdAt = Instant.now()
        execution.persist()
        return execution
    }

    private fun terminalStateFor(reason: ExitReason): ExecutionState =
        if (reason == ExitReason.CANCELLED) ExecutionState.CANCELLED else ExecutionState.FAILED

    private companion object {
        val LOG = LoggerFactory.getLogger(ExecutionLoop::class.java)

        val LOST = ExitOutcome(ExitReason.UNKNOWN, -1, "the platform no longer knows about this execution")
    }
}
