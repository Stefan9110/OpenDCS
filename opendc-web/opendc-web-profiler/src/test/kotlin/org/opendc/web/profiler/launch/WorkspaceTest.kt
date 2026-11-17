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

package org.opendc.web.profiler.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opendc.sdk.model.serialization.SdkJson
import org.opendc.sdk.model.telemetry.OutputFileSpec
import org.opendc.web.launcher.LaunchManifest
import org.opendc.web.launcher.TelemetryTarget
import org.opendc.web.profiler.Fixtures
import org.opendc.web.profiler.grid.Collector
import org.opendc.web.profiler.grid.GridPoint
import org.opendc.web.profiler.grid.GridTrace
import org.opendc.web.profiler.grid.HeapChoice
import org.opendc.web.profiler.grid.STAGED_TRACE
import org.opendc.web.profiler.grid.TableSet
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** The manifest is what the launcher believes; it has to lay the trace out exactly as the server would. */
class WorkspaceTest {
    @TempDir
    lateinit var dir: Path

    private val fixture = Fixtures.BITBRAINS_SMALL

    private fun point(
        parallelism: Int = 1,
        tables: TableSet = TableSet.ALL,
    ) = GridPoint(
        trace = GridTrace(name = "bb", path = "bitbrains-small", hosts = listOf(8)),
        hosts = 8,
        exportIntervalMinutes = 5,
        tables = tables,
        sampleFraction = 1.0,
        collector = Collector.DEFAULT,
        parallelism = parallelism,
        heap = HeapChoice.Measure(512),
    )

    private fun manifestOf(workspace: Workspace): LaunchManifest =
        SdkJson.json.decodeFromString(LaunchManifest.serializer(), Files.readString(workspace.manifest))

    @Test
    fun `the manifest stages both tables as file links from the trace directory`() {
        val workspace = prepareWorkspace(point(), fixture, dir)
        val manifest = manifestOf(workspace)

        assertEquals(setOf("$STAGED_TRACE/tasks.parquet", "$STAGED_TRACE/fragments.parquet"), manifest.inputs.map { it.path }.toSet())
        for (input in manifest.inputs) {
            assertTrue(input.source.startsWith("file:"), input.source)
            assertTrue(Files.exists(Path.of(URI(input.source))), input.source)
        }
        assertEquals(TelemetryTarget.None, manifest.telemetry)
    }

    @Test
    fun `one unit per parallel slot with distinct seeds and file targets`() {
        val workspace = prepareWorkspace(point(parallelism = 3, tables = TableSet.HOST_ONLY), fixture, dir)
        val manifest = manifestOf(workspace)

        assertEquals(3, manifest.parallelism)
        assertEquals(listOf(0, 1, 2), manifest.units.map { it.scenario.initialSeed })
        assertEquals(3, workspace.outcomes.size)
        for (unit in manifest.units) {
            assertTrue(unit.outcome.startsWith("file:"), unit.outcome)
            assertEquals(listOf(OutputFileSpec.HOST.fileName), unit.outputs.map { it.file })
            assertTrue(unit.outputs.all { it.target.startsWith("file:") })
        }
        assertTrue(workspace.outcomes.all { it.startsWith(dir) })
        assertTrue(workspace.recording.startsWith(dir))
        assertTrue(workspace.recordingRepository.startsWith(dir))
    }

    @Test
    fun `a stale workspace is cleared before the manifest is written`() {
        Files.createDirectories(dir.resolve("output/0"))
        Files.writeString(dir.resolve("outcome-0.json"), "left by a killed attempt")
        Files.writeString(dir.resolve("output/0/host.parquet"), "left by a killed attempt")

        val workspace = prepareWorkspace(point(), fixture, dir)

        assertTrue(!Files.exists(workspace.outcomes.single()), "the old outcome would be read as this run's")
        assertTrue(!Files.exists(workspace.outputDir), "the old output would be counted as this run's")
    }
}
