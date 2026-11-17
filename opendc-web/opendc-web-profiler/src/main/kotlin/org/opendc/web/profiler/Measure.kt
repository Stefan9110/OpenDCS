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

import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.opendc.web.profiler.grid.Grid
import org.opendc.web.profiler.grid.points
import org.opendc.web.profiler.launch.LauncherImage
import org.opendc.web.profiler.launch.RunSettings
import org.opendc.web.profiler.launch.runPoint
import org.opendc.web.profiler.report.MeasurementCsv
import org.opendc.web.profiler.report.PointFiles
import org.opendc.web.profiler.trace.TraceReading
import org.opendc.web.profiler.trace.TraceShape
import org.opendc.web.profiler.trace.inspectTrace
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

fun readGrid(file: Path): Grid = Json.decodeFromString(Grid.serializer(), Files.readString(file))

/** Every trace of a grid read, or the first one that could not be. */
sealed interface Inventory {
    data class Complete(val shapes: Map<String, TraceShape>) : Inventory

    data class Broken(val trace: String, val reading: TraceReading) : Inventory
}

fun inventory(
    grid: Grid,
    tracesRoot: Path,
): Inventory {
    val shapes = mutableMapOf<String, TraceShape>()
    for (trace in grid.traces) {
        when (val reading = inspectTrace(tracesRoot.resolve(trace.path))) {
            is TraceReading.Readable -> shapes[trace.name] = reading.shape
            is TraceReading.Missing, is TraceReading.Unreadable -> return Inventory.Broken(trace.name, reading)
        }
    }
    return Inventory.Complete(shapes)
}

fun describe(broken: Inventory.Broken): String =
    when (val reading = broken.reading) {
        is TraceReading.Readable -> "trace '${broken.trace}' is readable"
        is TraceReading.Missing -> "trace '${broken.trace}' has no ${reading.table} table"
        is TraceReading.Unreadable -> "trace '${broken.trace}': the ${reading.table} table could not be read: ${reading.message}"
    }

/**
 * Which points to run, from where, into which directory, with which launcher.
 *
 * @property work Where a point's launcher runs while it is measured: node-local storage on a
 *   cluster, so that its timings do not pay for a shared file system.
 * @property only When not empty, the point ids to run and nothing else.
 * @property maxPoints How many rows to write before stopping, so a run fits a job's time limit.
 */
data class MeasureJob(
    val grid: Grid,
    val tracesRoot: Path,
    val out: Path,
    val image: LauncherImage,
    val settings: RunSettings,
    val work: Path = out.resolve("work"),
    val only: Set<String> = emptySet(),
    val maxPoints: Int = Int.MAX_VALUE,
)

/**
 * Runs every point and repeat of the grid that has no row yet, writing its row and its files after
 * each, rebuilds the merged CSV however it ends, and returns how many rows it wrote.
 */
fun measure(job: MeasureJob): Int {
    val shapes =
        when (val inventory = inventory(job.grid, job.tracesRoot)) {
            is Inventory.Complete -> inventory.shapes
            is Inventory.Broken -> throw IllegalArgumentException(describe(inventory))
        }
    val done = MeasurementCsv.doneKeys(job.out)
    var written = 0
    try {
        for (point in job.grid.points()) {
            if (job.only.isNotEmpty() && point.id !in job.only) {
                continue
            }
            for (repeat in 1..job.grid.repeats) {
                if (written >= job.maxPoints) {
                    return written
                }
                if ("${point.id}#$repeat" in done) {
                    continue
                }
                logger.info { "Running ${point.id} (repeat $repeat)" }
                val measurement =
                    runPoint(
                        point = point,
                        repeat = repeat,
                        shape = shapes.getValue(point.trace.name),
                        traceDir = job.tracesRoot.resolve(point.trace.path),
                        workDir = job.work.resolve(point.id),
                        image = job.image,
                        settings = job.settings,
                        log = job.out.resolve("logs").resolve("${point.id}-$repeat.log"),
                    )
                MeasurementCsv.writeRow(job.out, measurement)
                PointFiles.write(job.out, measurement)
                written += 1
                logger.info { "${point.id}: ${measurement.end.label} after ${"%.1f".format(measurement.wallSeconds)} s" }
            }
        }
    } finally {
        MeasurementCsv.merge(job.out)
    }
    return written
}
