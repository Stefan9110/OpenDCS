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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.opendc.web.profiler.grid.Grid
import org.opendc.web.profiler.grid.heapMb
import org.opendc.web.profiler.grid.points
import org.opendc.web.profiler.grid.shippedPrediction
import org.opendc.web.profiler.launch.LauncherImage
import org.opendc.web.profiler.launch.Recordings
import org.opendc.web.profiler.launch.RunSettings
import org.opendc.web.profiler.report.PointFiles
import org.opendc.web.profiler.trace.TraceReading
import org.opendc.web.profiler.trace.TraceShape
import org.opendc.web.profiler.trace.inspectTrace
import java.io.File
import java.nio.file.Path
import java.time.Duration

fun main(args: Array<String>) =
    ProfilerCommand().subcommands(
        PlanCommand(),
        InspectCommand(),
        MeasureCommand(),
        RecordingsCommand(),
    ).main(args)

class ProfilerCommand : CliktCommand(name = "opendc-profiler") {
    override fun help(context: Context): String = "Measure what the web launcher costs over a grid of traces and settings."

    override fun run() = Unit
}

class PlanCommand : CliktCommand(name = "plan") {
    private val gridFile by option("--grid").path(mustExist = true, canBeDir = false).required()
    private val traces by option("--traces").path(mustExist = true, canBeFile = false).default(Path.of(""))

    override fun help(context: Context): String = "List the points a grid expands to, with what each trace holds, and run nothing."

    override fun run() {
        val grid = readGrid(gridFile)
        val shapes = shapesOf(grid, traces)
        for ((name, shape) in shapes) {
            echo("$name: ${shape.tasks} tasks, ${shape.fragments} fragments, ${shape.spanSeconds.format()} s of simulated time")
        }
        val points = grid.points()
        for (point in points) {
            val predicted = shippedPrediction(point, shapes.getValue(point.trace.name))
            echo(
                "${point.id}  heap ${heapMb(point.heap, predicted.peakMemoryMb)} MB  P=${point.parallelism}" +
                    "  shipped model says ${predicted.cpuSeconds.format()} s and ${predicted.peakMemoryMb.format()} MB",
            )
        }
        echo("${points.size} points, ${grid.repeats} repeat(s) each")
    }
}

class InspectCommand : CliktCommand(name = "inspect") {
    private val dirs by argument().path(mustExist = true, canBeFile = false).multiple(required = true)

    override fun help(context: Context): String = "Print the rows and span of trace directories from their parquet footers."

    override fun run() {
        for (dir in dirs) {
            echo(
                when (val reading = inspectTrace(dir)) {
                    is TraceReading.Readable -> "$dir: ${reading.shape.describe()}"
                    is TraceReading.Missing -> "$dir: no ${reading.table} table"
                    is TraceReading.Unreadable -> "$dir: the ${reading.table} table could not be read: ${reading.message}"
                },
            )
        }
    }
}

class MeasureCommand : CliktCommand(name = "measure") {
    private val gridFile by option("--grid").path(mustExist = true, canBeDir = false).required()
    private val out by option("--out").path(canBeFile = false).required()
    private val work by option("--work", help = "where a point's launcher runs; node-local storage on a cluster").path(canBeFile = false)
        .defaultLazy { out.resolve("work") }
    private val launcherLib by option("--launcher-lib").path(mustExist = true, canBeFile = false).required()
    private val traces by option("--traces").path(mustExist = true, canBeFile = false).default(Path.of(""))
    private val only by option("--only").split(",").default(emptyList())
    private val histogram by option("--histogram").flag()
    private val keepRecordings by option("--keep-recordings", help = "copy each run's recording to <out>/recordings").flag()
    private val maxPoints by option("--max-points").int().default(Int.MAX_VALUE)

    override fun help(context: Context): String = "Run every point of a grid the output does not hold yet, one launcher JVM each."

    override fun run() {
        val grid = readGrid(gridFile)
        shapesOf(grid, traces)
        val job =
            MeasureJob(
                grid = grid,
                tracesRoot = traces,
                out = out,
                work = work,
                image = LauncherImage(classpath = "${launcherLib.toAbsolutePath()}${File.separator}*"),
                settings =
                    RunSettings(
                        timeout = Duration.ofMinutes(grid.pointTimeoutMinutes.toLong()),
                        histogram = histogram,
                        recordings = if (keepRecordings) Recordings.Keep(out.resolve("recordings")) else Recordings.Discard,
                    ),
                only = only.toSet(),
                maxPoints = maxPoints,
            )
        val written = measure(job)
        echo("$written row(s) written under $out")
    }
}

class RecordingsCommand : CliktCommand(name = "recordings") {
    private val recordings by argument(help = "the recordings directory a measure run kept").path(mustExist = true, canBeFile = false)
    private val out by option("--out").path(canBeFile = false).required()

    override fun help(context: Context): String = "Re-read kept recordings and rewrite the components and histogram files they yield."

    override fun run() {
        val rewritten = PointFiles.rewrite(recordings, out)
        echo("$rewritten recording(s) re-read into $out")
    }
}

private fun shapesOf(
    grid: Grid,
    traces: Path,
): Map<String, TraceShape> =
    when (val inventory = inventory(grid, traces)) {
        is Inventory.Complete -> inventory.shapes
        is Inventory.Broken -> throw CliktError(describe(inventory))
    }

private fun TraceShape.describe(): String = "$tasks tasks, $fragments fragments, ${spanSeconds.format()} s of simulated time"

private fun Double.format(): String = "%.0f".format(this)
