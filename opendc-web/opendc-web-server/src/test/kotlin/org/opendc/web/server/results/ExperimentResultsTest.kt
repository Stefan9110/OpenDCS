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

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import org.apache.parquet.example.data.simple.SimpleGroup
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.MessageTypeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opendc.trace.parquet.LocalOutputFile
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.launcher.ResultMetric
import org.opendc.web.launcher.TelemetryTarget
import org.opendc.web.launcher.UnitOutcome
import org.opendc.web.server.ApiTest
import org.opendc.web.server.execution.ExecutionLoop
import org.opendc.web.server.execution.RecordingDispatcher
import org.opendc.web.server.execution.ended
import org.opendc.web.server.model.Execution
import org.opendc.web.server.model.Experiment
import org.opendc.web.server.storage.ObjectStore
import org.opendc.web.server.storage.runKey
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** A stopped run is charted from its parquet, so the numbers coming out must be the ones in the files. */
@QuarkusTest
class ExperimentResultsTest {
    @Inject
    lateinit var loop: ExecutionLoop

    @Inject
    lateinit var dispatcher: RecordingDispatcher

    @Inject
    lateinit var store: ObjectStore

    @TempDir
    lateinit var scratch: Path

    private lateinit var projectId: String

    @BeforeEach
    fun seedProject() {
        dispatcher.forget()
        projectId =
            ApiTest.requestJson()
                .body("""{"name":"Results ${UUID.randomUUID()}"}""")
                .post("/api/v1/projects")
                .then()
                .statusCode(201)
                .extract()
                .path("id")
    }

    @Test
    fun `a finished run is charted from its parquet rather than from what it posted`() {
        val experiment = running()
        post(experiment, ResultMetric.HOST_CPU_UTILIZATION, 0.9)
        hostTable(experiment, Row(t = 0, host = "H0", usage = 100.0, capacity = 1000.0, power = 100.0))

        settle(experiment)

        assertEquals(0.1, valueOf(results(experiment), ResultMetric.HOST_CPU_UTILIZATION))
    }

    @Test
    fun `adds the hosts of an instant for power and weighs them by capacity for utilization`() {
        val experiment = running()
        hostTable(
            experiment,
            Row(t = 0, host = "H0", usage = 200.0, capacity = 1000.0, power = 100.0),
            Row(t = 0, host = "H1", usage = 400.0, capacity = 1000.0, power = 200.0),
        )

        settle(experiment)

        val charted = results(experiment)
        assertEquals(300.0, valueOf(charted, ResultMetric.HOST_POWER_DRAW))
        assertEquals(0.3, valueOf(charted, ResultMetric.HOST_CPU_UTILIZATION))
    }

    @Test
    fun `reports the same utilization for two fleets that got through the same work`() {
        val fewLarge = running()
        hostTable(
            fewLarge,
            Row(t = 0, host = "H0", usage = 100.0, capacity = 900.0, power = 100.0),
            Row(t = 0, host = "H1", usage = 8.0, capacity = 100.0, power = 100.0),
        )
        settle(fewLarge)

        val manySmall = running()
        hostTable(
            manySmall,
            Row(t = 0, host = "H0", usage = 54.0, capacity = 500.0, power = 100.0),
            Row(t = 0, host = "H1", usage = 54.0, capacity = 500.0, power = 100.0),
        )
        settle(manySmall)

        // Both fleets used 108 of 1000. An unweighted mean would call the first one 9.6%.
        assertEquals(0.108, valueOf(results(fewLarge), ResultMetric.HOST_CPU_UTILIZATION), 1e-9)
        assertEquals(0.108, valueOf(results(manySmall), ResultMetric.HOST_CPU_UTILIZATION), 1e-9)
    }

    // The writer records the closing instant twice: on the export interval and when the last task ends.
    @Test
    fun `counts an instant the writer recorded twice only once`() {
        val experiment = running()
        hostTable(
            experiment,
            Row(t = 0, host = "H0", usage = 200.0, capacity = 1000.0, power = 100.0),
            Row(t = 0, host = "H1", usage = 400.0, capacity = 1000.0, power = 200.0),
            Row(t = 0, host = "H0", usage = 200.0, capacity = 1000.0, power = 100.0),
            Row(t = 0, host = "H1", usage = 400.0, capacity = 1000.0, power = 200.0),
        )

        settle(experiment)

        assertEquals(300.0, valueOf(results(experiment), ResultMetric.HOST_POWER_DRAW))
    }

    @Test
    fun `reports a finished experiment as complete so a chart stops asking`() {
        val experiment = running()
        hostTable(experiment, Row(t = 0, host = "H0", usage = 500.0, capacity = 1000.0, power = 100.0))

        settle(experiment)

        assertEquals(true, results(experiment).getBoolean("complete"))
    }

    @Test
    fun `forgets what a scenario measured once it has been run again`() {
        val experiment = running()
        hostTable(experiment, Row(t = 0, host = "H0", usage = 100.0, capacity = 1000.0, power = 100.0))
        settle(experiment)
        assertEquals(0.1, valueOf(results(experiment), ResultMetric.HOST_CPU_UTILIZATION))

        ApiTest.requestJson().post("/api/v1/experiments/$experiment/scenarios/0/retry").then().statusCode(200)
        hostTable(experiment, Row(t = 0, host = "H0", usage = 800.0, capacity = 1000.0, power = 100.0))
        loop.drain()
        settle(experiment)

        assertEquals(0.8, valueOf(results(experiment), ResultMetric.HOST_CPU_UTILIZATION))
    }

    @Test
    fun `falls back to what a failed run managed to post`() {
        val experiment = running()
        post(experiment, ResultMetric.HOST_CPU_UTILIZATION, 0.62)

        dispatcher.finish(executionOf(experiment), ended(ExitReason.SIMULATION_ERROR, 21, "it failed"))

        assertEquals(0.62, valueOf(results(experiment), ResultMetric.HOST_CPU_UTILIZATION))
    }

    /** An experiment whose single unit the platform is carrying. */
    private fun running(): String {
        val publicId =
            ApiTest.requestJson()
                .body("""{"projectId":"$projectId","name":"Results","spec":$SPEC}""")
                .post("/api/v1/experiments")
                .then()
                .statusCode(201)
                .extract()
                .path<String>("id")
        ApiTest.requestJson().post("/api/v1/experiments/$publicId/submit").then().statusCode(200)
        loop.drain()
        return publicId
    }

    /** The platform reports the run ended, after its launcher certified it. */
    private fun settle(experiment: String) {
        val execution = executionOf(experiment)
        dispatcher.certify(execution, 0, UnitOutcome.Succeeded(1.0))
        dispatcher.finish(execution, ended(ExitReason.OK))
    }

    private fun executionOf(experiment: String): UUID =
        QuarkusTransaction.requiringNew().call {
            val id = checkNotNull(Experiment.findByPublicId(UUID.fromString(experiment))).id
            Execution.findByExperiment(id).last().publicId
        }

    private fun results(experiment: String): JsonPath =
        ApiTest.requestJson().get("/api/v1/experiments/$experiment/results").then().statusCode(200).extract().jsonPath()

    private fun valueOf(
        charted: JsonPath,
        metric: ResultMetric,
    ): Double = charted.getDouble("scenarios[0].series.find { it.metric == '${metric.id}' }.points[0].value")

    /** Reports one sample as a launcher would, using the token written into its manifest. */
    private fun post(
        experiment: String,
        metric: ResultMetric,
        value: Double,
    ) {
        val target = dispatcher.manifestOf(executionOf(experiment)).telemetry
        check(target is TelemetryTarget.Endpoint)
        ApiTest
            .requestJson()
            .header("Authorization", "Bearer ${target.token}")
            .body(
                """{"runs":[{"scenarioIndex":0,"seed":0,"completedTasks":1,""" +
                    """"series":[{"metric":"${metric.id}","points":[{"t":0,"value":$value}]}]}]}""",
            )
            .post("/api/v1/telemetry")
            .then()
            .statusCode(204)
    }

    /** Writes a host table where the run's launcher would have put one. */
    private fun hostTable(
        experiment: String,
        vararg rows: Row,
    ) {
        val file = scratch.resolve("host-${UUID.randomUUID()}.parquet")
        ExampleParquetWriter.builder(LocalOutputFile(file)).withType(HOST_SCHEMA).build().use { writer ->
            for (row in rows) {
                writer.write(
                    SimpleGroup(HOST_SCHEMA).apply {
                        add("timestamp", row.t)
                        add("host_name", row.host)
                        // Floats, because the simulator writes its measurements as 32-bit floats.
                        add("cpu_usage", row.usage.toFloat())
                        add("cpu_capacity", row.capacity.toFloat())
                        add("power_draw", row.power.toFloat())
                    },
                )
            }
        }
        Files.newInputStream(file).use { store.put("${runKey(UUID.fromString(experiment), 0, 0)}/host.parquet", it) }
    }

    private data class Row(
        val t: Long,
        val host: String,
        val usage: Double,
        val capacity: Double,
        val power: Double,
    )

    private companion object {
        /** The columns of the host table this reads, written the way the simulator writes them. */
        val HOST_SCHEMA: MessageType =
            MessageTypeParser.parseMessageType(
                """
                message host {
                  required int64 timestamp;
                  required binary host_name (STRING);
                  required float cpu_usage;
                  required float cpu_capacity;
                  required float power_draw;
                }
                """.trimIndent(),
            )

        const val TOPOLOGY =
            """{"datacenters":[{"clusters":[{"name":"C0","hosts":[{"name":"H0","count":1,""" +
                """"cpu":{"coreCount":4,"coreSpeed":"2.5 GHz"},"memory":{"size":"16 GiB"}}]}]}]}"""

        const val WORKLOAD =
            """{"type":"inline","tasks":[{"id":0,"submissionTime":"0 ms","duration":"10 minutes",""" +
                """"cpuCoreCount":1,"cpuCapacity":"1 GHz","memory":"1 GiB",""" +
                """"fragments":[{"duration":"10 minutes","cpuUsage":"1 GHz"}]}]}"""

        const val SPEC = """{"name":"results","topologies":[$TOPOLOGY],"workloads":[$WORKLOAD],"runs":1}"""
    }
}
