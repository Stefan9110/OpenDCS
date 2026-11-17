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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.opendc.web.dispatcher.EXIT_ON_OUT_OF_MEMORY
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.launcher.EXIT_UNITS_FAILED
import org.opendc.web.profiler.Fixtures
import org.opendc.web.profiler.grid.Collector
import org.opendc.web.profiler.grid.GridPoint
import org.opendc.web.profiler.grid.GridTrace
import org.opendc.web.profiler.grid.HeapChoice
import org.opendc.web.profiler.grid.TableSet
import org.opendc.web.profiler.probe.RecordingReading
import org.opendc.web.profiler.probe.recordingFlags
import org.opendc.web.profiler.report.ProcessMemory
import org.opendc.web.profiler.report.RunEnd
import org.opendc.web.profiler.trace.TraceShape
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * One point is a real child JVM started the way production starts the launcher, so the run has to
 * read everything that JVM leaves behind, and still produce a row when it is killed.
 */
class PointRunTest {
    @TempDir
    lateinit var dir: Path

    private val image = LauncherImage(classpath = System.getProperty("java.class.path"), mainClass = StubLauncher::class.java.name)

    private val shape = TraceShape(tasks = 50, fragments = 276_979, spanSeconds = 600.0)

    private fun point(collector: Collector = Collector.DEFAULT) =
        GridPoint(
            trace = GridTrace(name = "bb", path = "bitbrains-small", hosts = listOf(8)),
            hosts = 8,
            exportIntervalMinutes = 5,
            tables = TableSet.HOST_ONLY,
            sampleFraction = 1.0,
            collector = collector,
            parallelism = 1,
            heap = HeapChoice.Measure(512),
        )

    private fun run(
        settings: RunSettings = RunSettings(timeout = Duration.ofSeconds(30), histogram = false),
        env: Map<String, String> = emptyMap(),
    ) = runPoint(
        point = point(),
        repeat = 1,
        shape = shape,
        traceDir = Fixtures.BITBRAINS_SMALL,
        workDir = dir.resolve("work"),
        image = image,
        settings = settings,
        log = dir.resolve("logs/run.log"),
        env = env,
    )

    @Test
    fun `a clean run records wall, units, the peak report and output bytes`() {
        val measurement = run()

        assertEquals(RunEnd.Finished, measurement.end)
        assertEquals(0, measurement.exitCode)
        assertEquals(listOf(StubLauncher.UNIT_SECONDS), measurement.unitSeconds)
        assertEquals(StubLauncher.PEAK, measurement.peak)
        assertTrue(measurement.outputBytes > 0)
        assertTrue(measurement.wallSeconds > 0.0)
        assertEquals(512, measurement.heapMb)
        val recording = measurement.recording as RecordingReading.Read
        assertTrue(recording.summary.heapAfterGc.isNotEmpty())
        assertTrue(Files.exists(dir.resolve("logs/run.log")))
        assertFalse(Files.exists(dir.resolve("work")), "the workspace is removed once read")
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `memory is sampled on linux`() {
        val memory = run().memory as ProcessMemory.Sampled

        assertTrue(memory.summary.rssPeakMb > 0.0)
    }

    @Test
    fun `exit code three is an out of memory kill`() {
        val measurement = run(env = mapOf("STUB_EXIT" to "3"))

        assertEquals(3, measurement.exitCode)
        assertEquals(RunEnd.Exited(ExitReason.OOM), measurement.end)
    }

    @Test
    fun `a finished run keeps the tail of its log, a killed run all of it`() {
        run(env = mapOf("STUB_LOG_LINES" to "3000"))
        val finished = Files.readAllLines(dir.resolve("logs/run.log"))
        assertTrue(finished.size <= LOG_TAIL_LINES, "${finished.size} lines kept of a finished run")
        assertEquals("stub log line 2999", finished.last())

        val killed =
            run(
                settings = RunSettings(timeout = Duration.ofSeconds(2), histogram = false),
                env = mapOf("STUB_LOG_LINES" to "3000", "STUB_SLEEP_MS" to "20000"),
            )
        assertEquals(RunEnd.TimedOut, killed.end)
        val whole = Files.readAllLines(dir.resolve("logs/run.log"))
        assertTrue(whole.size > LOG_TAIL_LINES, "${whole.size} lines kept of a killed run")
        assertTrue("stub log line 0" in whole, "the start of a killed run's log is what explains it")
    }

    @Test
    fun `the recording is kept under its stem when asked`() {
        val kept = dir.resolve("recordings")

        run(settings = RunSettings(timeout = Duration.ofSeconds(30), histogram = false, recordings = Recordings.Keep(kept)))

        assertTrue(Files.size(kept.resolve("${point().id}-1.jfr")) > 0, kept.toString())
        assertFalse(Files.exists(dir.resolve("work")), "keeping the recording does not keep the workspace")
    }

    @Test
    fun `a launcher whose unit failed is not a finished run`() {
        val measurement = run(env = mapOf("STUB_FAIL_UNITS" to "1", "STUB_EXIT" to EXIT_UNITS_FAILED.toString()))

        assertEquals(RunEnd.UnitsFailed(failed = 1), measurement.end)
        assertEquals(emptyList<Double>(), measurement.unitSeconds)
    }

    @Test
    fun `a run past its timeout is killed and recorded`() {
        val started = System.nanoTime()

        val measurement =
            run(settings = RunSettings(timeout = Duration.ofSeconds(1), histogram = false), env = mapOf("STUB_SLEEP_MS" to "20000"))

        assertEquals(RunEnd.TimedOut, measurement.end)
        assertEquals(emptyList<Double>(), measurement.unitSeconds)
        assertTrue((System.nanoTime() - started) / 1e9 < 10.0, "the launcher was not killed in time")
        assertFalse(Files.exists(dir.resolve("work")), "the workspace is removed after a kill too")
    }

    @Test
    fun `the command mirrors the local dispatcher and adds the point's flags`() {
        val command =
            launcherCommand(
                image,
                heapMb = 512,
                collector = Collector.SERIAL,
                activeProcessors = 1,
                recording = recordingFlags(dir.resolve("r.jfr"), dir.resolve("jfr"), histogram = false),
            )

        assertTrue("-Xmx512m" in command)
        assertTrue(EXIT_ON_OUT_OF_MEMORY in command)
        assertTrue("-XX:+UseSerialGC" in command)
        assertTrue("-XX:ActiveProcessorCount=1" in command)
        assertTrue(command.any { it.startsWith("-XX:StartFlightRecording:") })
        assertTrue("-XX:FlightRecorderOptions:repository=${dir.resolve("jfr").toAbsolutePath()}" in command, command.toString())
        assertEquals(listOf("-cp", image.classpath, image.mainClass), command.takeLast(3))
    }
}
