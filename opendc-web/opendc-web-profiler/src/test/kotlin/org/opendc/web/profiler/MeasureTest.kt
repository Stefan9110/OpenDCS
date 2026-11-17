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

package org.opendc.web.profiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.opendc.web.profiler.grid.Grid
import org.opendc.web.profiler.grid.GridTrace
import org.opendc.web.profiler.grid.points
import org.opendc.web.profiler.launch.LauncherImage
import org.opendc.web.profiler.launch.Recordings
import org.opendc.web.profiler.launch.RunSettings
import org.opendc.web.profiler.launch.StubLauncher
import org.opendc.web.profiler.probe.CodeComponent
import org.opendc.web.profiler.report.PointFiles
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.listDirectoryEntries

/** A measurement campaign is long and gets interrupted, so what matters is that it picks up where it stopped. */
class MeasureTest {
    @TempDir
    lateinit var dir: Path

    private val image = LauncherImage(classpath = System.getProperty("java.class.path"), mainClass = StubLauncher::class.java.name)

    private fun grid(hosts: List<Int>) =
        Grid(
            traces = listOf(GridTrace(name = "bb", path = "bitbrains-small", hosts = hosts)),
            exportIntervalMinutes = emptyList(),
            tables = emptyList(),
            sampleFractions = emptyList(),
            collectors = emptyList(),
            parallelism = emptyList(),
            heapFactors = emptyList(),
            measureHeapMb = 256,
        )

    private fun job(
        grid: Grid,
        only: Set<String> = emptySet(),
        maxPoints: Int = Int.MAX_VALUE,
        work: Path = dir.resolve("out/work"),
        settings: RunSettings = RunSettings(timeout = Duration.ofSeconds(30), histogram = false),
    ) = MeasureJob(
        grid = grid,
        tracesRoot = Fixtures.BITBRAINS_SMALL.parent,
        out = dir.resolve("out"),
        work = work,
        image = image,
        settings = settings,
        only = only,
        maxPoints = maxPoints,
    )

    @Test
    fun `components and histograms are rewritten from kept recordings`() {
        val out = dir.resolve("out")
        val kept = RunSettings(timeout = Duration.ofSeconds(30), histogram = false, recordings = Recordings.Keep(out.resolve("recordings")))
        measure(job(grid(listOf(8)), settings = kept))
        val components = out.resolve("components").listDirectoryEntries("*.csv").single()
        Files.delete(components)

        val rewritten = PointFiles.rewrite(out.resolve("recordings"), out)

        assertEquals(1, rewritten)
        val lines = Files.readAllLines(components)
        assertEquals("component,share", lines.first())
        assertEquals(CodeComponent.entries.map { it.name }.toSet(), lines.drop(1).map { it.substringBefore(",") }.toSet())
    }

    @Test
    fun `rows are files of their own and the csv is their merge`() {
        val job = job(grid(hosts = listOf(8, 16)))
        measure(job)

        assertEquals(2, dir.resolve("out/rows").listDirectoryEntries().size)
        assertEquals(3, Files.readAllLines(dir.resolve("out/measurements.csv")).size)
        Files.delete(dir.resolve("out/measurements.csv"))
        assertEquals(0, measure(job))
        assertEquals(3, Files.readAllLines(dir.resolve("out/measurements.csv")).size, "a run that measures nothing still rebuilds the csv")
    }

    @Test
    fun `the work directory may live elsewhere and holds nothing afterwards`() {
        val work = dir.resolve("scratch")

        assertEquals(1, measure(job(grid(hosts = listOf(8)), work = work)))

        assertTrue(!Files.exists(dir.resolve("out/work")))
        assertTrue(!Files.exists(work) || work.listDirectoryEntries().isEmpty(), "the point's directory is removed once read")
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `cpu seconds are kept per thread name`() {
        measure(job(grid(hosts = listOf(8))))

        val cpu = dir.resolve("out/cpu").listDirectoryEntries().single()
        assertEquals("thread,group,cpu_s", Files.readAllLines(cpu).first())
    }

    @Test
    fun `measure writes one row per point and resumes`() {
        val job = job(grid(hosts = listOf(8)))

        assertEquals(1, measure(job))
        assertEquals(0, measure(job))
        assertEquals(2, Files.readAllLines(dir.resolve("out/measurements.csv")).size, "header and one row")
        assertEquals(1, dir.resolve("out/timeline").listDirectoryEntries().size)
        assertTrue(dir.resolve("out/logs").listDirectoryEntries().isNotEmpty())
    }

    @Test
    fun `only restricts to the named ids`() {
        val grid = grid(hosts = listOf(8, 16))
        val first = grid.points().first().id

        assertEquals(1, measure(job(grid, only = setOf(first))))
    }

    @Test
    fun `max points stops early`() {
        val grid = grid(hosts = listOf(8, 16))

        assertEquals(1, measure(job(grid, maxPoints = 1)))
        assertEquals(1, measure(job(grid, maxPoints = 1)))
        assertEquals(0, measure(job(grid, maxPoints = 1)))
    }

    @Test
    fun `an unreadable trace is reported before anything runs`() {
        val grid = Grid(traces = listOf(GridTrace(name = "missing", path = "nowhere", hosts = listOf(8))))

        val inventory = inventory(grid, dir)

        assertTrue(inventory is Inventory.Broken, "$inventory")
        assertEquals("missing", (inventory as Inventory.Broken).trace)
    }
}
