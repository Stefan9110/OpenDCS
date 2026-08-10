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

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opendc.web.server.ApiTest
import org.opendc.web.server.storage.ObjectStore
import org.opendc.web.server.storage.resultKey
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * The archive is how a run's output leaves the platform. Its entries are laid out the way a local run
 * lays out its own output directory, so what comes out of the zip can be read by the same tooling
 * without anything being moved about first.
 */
@QuarkusTest
class ExperimentArchiveTest {
    @Inject
    lateinit var store: ObjectStore

    private lateinit var projectId: String

    @BeforeEach
    fun seedProject() {
        projectId =
            ApiTest.requestJson()
                .body("""{"name":"Archive ${UUID.randomUUID()}"}""")
                .post("/api/v1/projects")
                .then()
                .statusCode(201)
                .extract()
                .path("id")
    }

    @Test
    fun `lays out the archive the way a local run lays out its output`() {
        val experiment = submitted("Nightly Sweep")
        write(experiment, "0/seed=0/host.parquet", "first")
        write(experiment, "0/seed=1/service.parquet", "second")
        write(experiment, "1/seed=0/host.parquet", "third")

        val entries = archive(experiment)

        assertEquals(
            listOf(
                "nightly-sweep/raw-output/0/seed=0/host.parquet",
                "nightly-sweep/raw-output/0/seed=1/service.parquet",
                "nightly-sweep/raw-output/1/seed=0/host.parquet",
            ),
            entries.keys.toList(),
        )
        assertEquals("first", entries["nightly-sweep/raw-output/0/seed=0/host.parquet"])
    }

    // Names are written by people and end up in every entry path. One that could be read as a
    // directory of its own would unpack somewhere nobody asked for.
    @Test
    fun `a name that reads like a path does not become one`() {
        val experiment = submitted("../../etc/passwd")
        write(experiment, "0/seed=0/host.parquet", "content")

        assertEquals(listOf("etcpasswd/raw-output/0/seed=0/host.parquet"), archive(experiment).keys.toList())
    }

    @Test
    fun `an experiment that has produced nothing has no archive to give`() {
        ApiTest
            .requestJson()
            .get("/api/v1/experiments/${submitted("Empty")}/archive")
            .then()
            .statusCode(404)
    }

    @Test
    fun `an experiment nobody may see reports no archive rather than refusing one`() {
        ApiTest.requestJson().get("/api/v1/experiments/${UUID.randomUUID()}/archive").then().statusCode(404)
    }

    private fun submitted(name: String): String {
        val publicId =
            ApiTest.requestJson()
                .body("""{"projectId":"$projectId","name":${ApiTest.json.encodeToString(name)},"spec":$SPEC}""")
                .post("/api/v1/experiments")
                .then()
                .statusCode(201)
                .extract()
                .path<String>("id")
        ApiTest.requestJson().post("/api/v1/experiments/$publicId/submit").then().statusCode(200)
        return publicId
    }

    private fun write(
        experiment: String,
        path: String,
        content: String,
    ) {
        store.put("${resultKey(UUID.fromString(experiment))}/$path", content.byteInputStream())
    }

    /** The archive, unpacked, so a case can say what is in it rather than how long it was. */
    private fun archive(experiment: String): Map<String, String> {
        val bytes =
            ApiTest
                .requestJson()
                .get("/api/v1/experiments/$experiment/archive")
                .then()
                .statusCode(200)
                .header("Content-Disposition", org.hamcrest.Matchers.containsString(".zip"))
                .extract()
                .asByteArray()
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private companion object {
        const val TOPOLOGY =
            """{"clusters":[{"name":"C0","hosts":[{"name":"H0","count":1,""" +
                """"cpu":{"coreCount":4,"coreSpeed":"2.5 GHz"},"memory":{"size":"16 GiB"}}]}]}"""

        const val WORKLOAD =
            """{"type":"inline","tasks":[{"id":0,"name":"t0","submissionTime":"0 ms","duration":"10 minutes",""" +
                """"cpuCoreCount":1,"cpuCapacity":"1 GHz","memory":"1 GiB",""" +
                """"fragments":[{"duration":"10 minutes","cpuUsage":"1 GHz"}]}]}"""

        const val SPEC = """{"name":"archive","topologies":[$TOPOLOGY],"workloads":[$WORKLOAD],"runs":1}"""
    }
}
