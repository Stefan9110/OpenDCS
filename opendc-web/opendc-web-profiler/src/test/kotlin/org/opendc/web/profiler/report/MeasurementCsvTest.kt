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

package org.opendc.web.profiler.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.dispatcher.estimate.UnitEstimate
import org.opendc.web.launcher.PeakMemory
import org.opendc.web.profiler.grid.Collector
import org.opendc.web.profiler.grid.GridPoint
import org.opendc.web.profiler.grid.GridTrace
import org.opendc.web.profiler.grid.HeapChoice
import org.opendc.web.profiler.grid.TableSet
import org.opendc.web.profiler.probe.ClassBytes
import org.opendc.web.profiler.probe.CodeComponent
import org.opendc.web.profiler.probe.HeapSample
import org.opendc.web.profiler.probe.ProcSummary
import org.opendc.web.profiler.probe.RecordingReading
import org.opendc.web.profiler.probe.RecordingSummary
import org.opendc.web.profiler.probe.RssSample
import org.opendc.web.profiler.trace.TraceShape
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/** The CSV is what every figure reads and what a restart resumes from, so its shape and its gaps must be exact. */
class MeasurementCsvTest {
    @TempDir
    lateinit var dir: Path

    private val point =
        GridPoint(
            trace = GridTrace(name = "surf", path = "traces/surf", hosts = listOf(8)),
            hosts = 8,
            exportIntervalMinutes = 5,
            tables = TableSet.ALL,
            sampleFraction = 1.0,
            collector = Collector.DEFAULT,
            parallelism = 2,
            heap = HeapChoice.Factor(1.5),
        )

    private val sampled =
        ProcessMemory.Sampled(
            ProcSummary(
                rss = listOf(RssSample(0.0, 100.0), RssSample(1.0, 300.0)),
                cpuByThread =
                    mapOf(
                        "ForkJoinPool-1-" to 1.0,
                        "org.opendc.trac" to 1.0,
                        "GC Thread#0" to 1.0,
                        "C2 CompilerThre" to 1.0,
                        "main" to 1.0,
                    ),
                rssPeakMb = 300.0,
            ),
        )

    private fun recording(histogram: List<ClassBytes> = emptyList()) =
        RecordingReading.Read(
            RecordingSummary(
                heapAfterGc = listOf(HeapSample(0.5, 80.0), HeapSample(1.5, 120.0)),
                gcPauseSeconds = 0.2,
                gcCount = 3,
                componentShares = CodeComponent.entries.associateWith { 0.25 },
                histogramAtPeak = histogram,
            ),
        )

    private fun measurement(
        memory: ProcessMemory = sampled,
        peak: PeakMemory = PeakMemory.Measured(residentMb = 310.0, liveHeapMb = 130.0),
        recording: RecordingReading = recording(),
        repeat: Int = 1,
        end: RunEnd = RunEnd.Finished,
    ) = Measurement(
        point = point,
        repeat = repeat,
        shape = TraceShape(tasks = 50, fragments = 1000, spanSeconds = 600.0),
        activeProcessors = 2,
        heapMb = 512,
        predicted = UnitEstimate(peakMemoryMb = 200.0, cpuSeconds = 30.0),
        exitCode = 0,
        end = end,
        wallSeconds = 12.5,
        unitSeconds = listOf(1.5, 2.5),
        memory = memory,
        peak = peak,
        recording = recording,
        outputBytes = 4096,
    )

    private fun cells(row: String): Map<String, String> = MeasurementCsv.HEADER.split(',').zip(row.split(',')).toMap()

    @Test
    fun `the row keeps collecting and writing as one exporting share`() {
        val shares =
            mapOf(
                CodeComponent.LOADING to 0.125,
                CodeComponent.SIMULATING to 0.5,
                CodeComponent.COLLECTING to 0.125,
                CodeComponent.WRITING to 0.25,
                CodeComponent.OTHER to 0.0,
            )
        val summary = (recording() as RecordingReading.Read).summary.copy(componentShares = shares)
        val measurement = measurement(recording = RecordingReading.Read(summary))

        MeasurementCsv.writeRow(dir, measurement)

        val row = cells(Files.readAllLines(dir.resolve("rows/${measurement.stem}.csv"))[1])
        assertEquals("0.375", row.getValue("java_exporting_share"))
        assertEquals("0.5", row.getValue("java_simulating_share"))
        assertEquals(MeasurementCsv.COLUMNS.size, row.size)
    }

    @Test
    fun `a row has one cell per header column`() {
        assertEquals(MeasurementCsv.HEADER.split(',').size, MeasurementCsv.row(measurement()).split(',').size)
    }

    @Test
    fun `unit seconds join with semicolons`() {
        val cells = cells(MeasurementCsv.row(measurement()))

        assertEquals("1.5;2.5", cells.getValue("unit_s"))
        assertEquals("1.5", cells.getValue("heap_factor"))
        assertEquals("OK", cells.getValue("exit_reason"))
        assertEquals("300.0", cells.getValue("rss_peak_mb"))
        assertEquals("120.0", cells.getValue("heap_after_gc_peak_mb"))
    }

    @Test
    fun `unsampled memory leaves its cells empty`() {
        val cells =
            cells(
                MeasurementCsv.row(
                    measurement(memory = ProcessMemory.Unsampled, peak = PeakMemory.Unmeasured, recording = RecordingReading.Absent),
                ),
            )

        for (column in listOf("rss_peak_mb", "cpu_total_s", "vm_hwm_mb", "heap_peak_used_mb", "heap_after_gc_peak_mb", "gc_count")) {
            assertEquals("", cells.getValue(column), column)
        }
        assertEquals("12.5", cells.getValue("wall_s"))
    }

    @Test
    fun `each run gets a row file of its own, and the merged csv is rebuilt from them`() {
        MeasurementCsv.writeRow(dir, measurement(repeat = 1))
        MeasurementCsv.writeRow(dir, measurement(repeat = 2))
        Files.writeString(dir.resolve("rows/${point.id}-3.csv.tmp"), "${point.id},3,surf")

        assertEquals(setOf("${point.id}#1", "${point.id}#2"), MeasurementCsv.doneKeys(dir))
        assertEquals(emptySet<String>(), MeasurementCsv.doneKeys(dir.resolve("absent")))
        val rowFile = Files.readAllLines(dir.resolve("rows/${point.id}-1.csv"))
        assertEquals(listOf(MeasurementCsv.HEADER, MeasurementCsv.row(measurement(repeat = 1))), rowFile)

        MeasurementCsv.merge(dir)
        val merged = Files.readAllLines(dir.resolve(MEASUREMENTS_FILE))
        assertEquals(MeasurementCsv.HEADER, merged.first())
        assertEquals(listOf("1", "2"), merged.drop(1).map { it.split(',')[1] })
    }

    @Test
    fun `a rerun of a key replaces its row file`() {
        MeasurementCsv.writeRow(dir, measurement(repeat = 1))
        MeasurementCsv.writeRow(dir, measurement(repeat = 1))

        assertEquals(1, dir.resolve("rows").listDirectoryEntries().size)
    }

    @Test
    fun `a failed unit and a kill print their own reasons`() {
        assertEquals("UNITS_FAILED", cells(MeasurementCsv.row(measurement(end = RunEnd.UnitsFailed(failed = 1)))).getValue("exit_reason"))
        assertEquals("TIMEOUT", cells(MeasurementCsv.row(measurement(end = RunEnd.TimedOut))).getValue("exit_reason"))
        assertEquals("OOM", cells(MeasurementCsv.row(measurement(end = RunEnd.Exited(ExitReason.OOM)))).getValue("exit_reason"))
    }

    @Test
    fun `point files land under their kind`() {
        PointFiles.write(dir, measurement(recording = recording(listOf(ClassBytes("int[]", 10, 4096)))))
        val stem = "${point.id}-1"

        assertTrue(dir.resolve("timeline/$stem.csv").exists())
        assertTrue(dir.resolve("cpu/$stem.csv").exists())
        assertEquals("thread,group,cpu_s", Files.readAllLines(dir.resolve("cpu/$stem.csv")).first())
        assertTrue(dir.resolve("components/$stem.csv").exists())
        assertTrue(dir.resolve("histogram/$stem.csv").exists())
        assertEquals("t_s,kind,mb", Files.readAllLines(dir.resolve("timeline/$stem.csv")).first())
        assertEquals(5, Files.readAllLines(dir.resolve("timeline/$stem.csv")).size, "header, two rss samples, two heap samples")
    }

    @Test
    fun `an empty histogram writes no file`() {
        PointFiles.write(dir, measurement())

        assertFalse(dir.resolve("histogram/${point.id}-1.csv").exists())
    }
}
