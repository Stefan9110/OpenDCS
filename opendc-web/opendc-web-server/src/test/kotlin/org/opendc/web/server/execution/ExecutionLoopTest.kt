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
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opendc.web.dispatcher.ExitOutcome
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.server.ApiTest
import org.opendc.web.server.model.Execution
import org.opendc.web.server.model.ExecutionState
import org.opendc.web.server.model.Experiment
import org.opendc.web.server.model.RunUnit
import java.util.UUID

/**
 * The queue decides what a machine is asked to run and what happens to work that failed, which is
 * where an experiment silently stops or quietly runs twice. These drive the platform by hand: the
 * scheduler is off in tests, so what a case exercises is decided by the case.
 */
@QuarkusTest
class ExecutionLoopTest {
    @Inject
    lateinit var loop: ExecutionLoop

    @Inject
    lateinit var dispatcher: RecordingDispatcher

    private lateinit var projectId: String

    @BeforeEach
    fun seedProject() {
        dispatcher.forget()
        projectId =
            ApiTest.requestJson()
                .body("""{"name":"Executions ${UUID.randomUUID()}"}""")
                .post("/api/v1/projects")
                .then()
                .statusCode(201)
                .extract()
                .path("id")
    }

    // Running one scenario at a time on a machine that holds twenty is the failure mode this whole
    // model exists to avoid, so a bag that fits is dispatched whole.
    @Test
    fun `runs everything that fits in one execution rather than one scenario at a time`() {
        val experiment = submitted(topologies = 3, runs = 2)

        loop.drain()

        val execution = executionsOf(experiment).single()
        assertEquals(6, execution.units.size, "six runs fit in one slot and should have gone together")
        assertEquals(execution.units.size, execution.parallelism, "a bag runs every unit it holds at once")
        assertTrue(execution.units.all { it.state == ExecutionState.RUNNING })
    }

    @Test
    fun `never hands the same work to the platform twice`() {
        val experiment = submitted()

        loop.drain()
        loop.drain()

        assertEquals(1, executionsOf(experiment).size)
        assertEquals(1, launchedFor(experiment).size)
    }

    @Test
    fun `marks the work done when the platform says the execution succeeded`() {
        val experiment = submitted()
        loop.drain()

        dispatcher.finish(executionsOf(experiment).single().publicId, ExitOutcome(ExitReason.OK, 0, ""))

        assertTrue(unitsOf(experiment).all { it.state == ExecutionState.SUCCEEDED })
    }

    // An out-of-memory kill says the bag was too big, not that the work is impossible.
    @Test
    fun `answers an out-of-memory kill by splitting the work rather than failing it`() {
        val experiment = submitted(topologies = 4)
        loop.drain()
        val first = executionsOf(experiment).single()

        dispatcher.finish(first.publicId, ExitOutcome(ExitReason.OOM, 3, "ran out of memory"))

        val replacements = executionsOf(experiment).filter { it.id != first.id }
        assertTrue(replacements.size > 1, "a bag of four should come back as more than one execution")
        assertEquals(4, replacements.sumOf { it.units.size }, "splitting must not drop work")
        assertTrue(unitsOf(experiment).none { it.state == ExecutionState.FAILED }, "the work has not failed yet")
    }

    // A spec the simulator refused will be refused again however much is thrown at it.
    @Test
    fun `gives up on a failure that no retry could change`() {
        val experiment = submitted()
        loop.drain()

        dispatcher.finish(
            executionsOf(experiment).single().publicId,
            ExitOutcome(ExitReason.SIMULATION_ERROR, 21, "the simulation failed"),
        )

        assertEquals(1, executionsOf(experiment).size, "nothing should have been tried again")
        assertTrue(unitsOf(experiment).all { it.state == ExecutionState.FAILED })
    }

    @Test
    fun `restarting one scenario queues it again without touching the others`() {
        val experiment = submitted(topologies = 2)
        loop.drain()
        dispatcher.finish(executionsOf(experiment).single().publicId, ExitOutcome(ExitReason.OK, 0, ""))
        dispatcher.forget()

        ApiTest.requestJson().post("/api/v1/experiments/${experiment.first}/scenarios/0/retry").then().statusCode(200)
        loop.drain()

        val restarted = executionsOf(experiment).last()
        assertEquals(listOf(0), restarted.units.map { it.scenarioIndex }.distinct())
        assertEquals(1, launchedFor(experiment).size, "only the restarted scenario should have been dispatched")
        assertEquals(
            ExecutionState.SUCCEEDED,
            unitsOf(experiment).single { it.scenarioIndex == 1 }.state,
            "a scenario nobody restarted keeps its result",
        )
    }

    /** An experiment of [topologies] x [runs] units, submitted and waiting for the platform. */
    private fun submitted(
        topologies: Int = 1,
        runs: Int = 1,
    ): Pair<String, Long> {
        val publicId =
            ApiTest.requestJson()
                .body("""{"projectId":"$projectId","name":"Loop","spec":${spec(topologies, runs)}}""")
                .post("/api/v1/experiments")
                .then()
                .statusCode(201)
                .extract()
                .path<String>("id")
        ApiTest.requestJson().post("/api/v1/experiments/$publicId/submit").then().statusCode(200)
        val id = QuarkusTransaction.requiringNew().call { checkNotNull(Experiment.findByPublicId(UUID.fromString(publicId))).id }
        return publicId to id
    }

    private fun executionsOf(experiment: Pair<String, Long>): List<Execution> =
        QuarkusTransaction.requiringNew().call { Execution.findByExperiment(experiment.second) }

    private fun unitsOf(experiment: Pair<String, Long>): List<RunUnit> =
        QuarkusTransaction.requiringNew().call { RunUnit.findByExperiment(experiment.second) }

    /** What the platform was asked to start for this experiment, ignoring other cases' work. */
    private fun launchedFor(experiment: Pair<String, Long>): List<UUID> {
        val ours = executionsOf(experiment).map { it.publicId }.toSet()
        return dispatcher.launched.map { it.executionId }.filter { it in ours }
    }

    private fun spec(
        topologies: Int,
        runs: Int,
    ): String {
        val hosts = (1..topologies).joinToString(",") { count -> TOPOLOGY.format(count) }
        return """{"name":"loop","topologies":[$hosts],"workloads":[$WORKLOAD],"runs":$runs}"""
    }

    private companion object {
        const val TOPOLOGY =
            """{"clusters":[{"name":"C0","hosts":[{"name":"H0","count":%d,""" +
                """"cpu":{"coreCount":4,"coreSpeed":"2.5 GHz"},"memory":{"size":"16 GiB"}}]}]}"""

        const val WORKLOAD =
            """{"type":"inline","tasks":[{"id":0,"name":"t0","submissionTime":"0 ms","duration":"10 minutes",""" +
                """"cpuCoreCount":1,"cpuCapacity":"1 GHz","memory":"1 GiB",""" +
                """"fragments":[{"duration":"10 minutes","cpuUsage":"1 GHz"}]}]}"""
    }
}
