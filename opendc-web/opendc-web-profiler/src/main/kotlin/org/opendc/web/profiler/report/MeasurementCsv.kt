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

import org.opendc.web.launcher.PeakMemory
import org.opendc.web.profiler.grid.HeapChoice
import org.opendc.web.profiler.probe.CodeComponent
import org.opendc.web.profiler.probe.RecordingReading
import org.opendc.web.profiler.probe.ThreadGroup
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.listDirectoryEntries

const val MEASUREMENTS_FILE = "measurements.csv"

const val ROWS_DIR = "rows"

private const val SECONDS_PER_MINUTE = 60

/**
 * One line per run. Each run writes its line as a file of its own under `rows/`, whole or not at
 * all, so that jobs on several nodes never share a file; `measurements.csv` is their merge, rebuilt
 * from them. A cell is empty when that figure could not be measured.
 */
object MeasurementCsv {
    val COLUMNS =
        listOf(
            "point", "repeat", "trace", "tasks", "fragments", "span_s", "hosts", "export_interval_s", "tables",
            "sample_fraction", "loader", "parallelism", "active_processors", "collector", "heap_mb", "heap_factor",
            "predicted_heap_mb", "predicted_seconds", "exit_code", "exit_reason", "wall_s", "unit_s",
            "cpu_total_s", "cpu_simulation_s", "cpu_writer_s", "cpu_gc_s", "cpu_jit_s", "cpu_other_s", "rss_peak_mb",
            "vm_hwm_mb", "heap_peak_used_mb", "heap_after_gc_peak_mb", "gc_pause_s", "gc_count", "output_bytes",
            "java_loading_share", "java_simulating_share", "java_exporting_share", "java_other_share",
        )

    val HEADER = COLUMNS.joinToString(",")

    fun row(measurement: Measurement): String = cells(measurement).joinToString(",")

    fun writeRow(
        dir: Path,
        measurement: Measurement,
    ) {
        val rows = dir.resolve(ROWS_DIR)
        Files.createDirectories(rows)
        val staging = rows.resolve("${measurement.stem}.csv.tmp")
        Files.writeString(staging, HEADER + "\n" + row(measurement) + "\n")
        Files.move(staging, rows.resolve("${measurement.stem}.csv"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** The `point#repeat` keys of every row file that holds a whole row. */
    fun doneKeys(dir: Path): Set<String> =
        rowFiles(dir)
            .map(::rowOf)
            .filter { it.isNotEmpty() }
            .map { "${it[0]}#${it[1]}" }
            .toSet()

    /** Rebuilds `measurements.csv` from the row files, whole or not at all. */
    fun merge(dir: Path) {
        val lines = listOf(HEADER) + rowFiles(dir).map(::rowOf).filter { it.isNotEmpty() }.map { it.joinToString(",") }
        Files.createDirectories(dir)
        val staging = dir.resolve("$MEASUREMENTS_FILE.tmp")
        Files.write(staging, lines)
        Files.move(staging, dir.resolve(MEASUREMENTS_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun rowFiles(dir: Path): List<Path> {
        val rows = dir.resolve(ROWS_DIR)
        if (!Files.isDirectory(rows)) {
            return emptyList()
        }
        return rows.listDirectoryEntries("*.csv").sortedBy { it.fileName.toString() }
    }

    /** The cells of the row in [file], or none when the file is not a header and one whole row. */
    private fun rowOf(file: Path): List<String> {
        val lines = Files.readAllLines(file)
        if (lines.size < 2 || lines[0] != HEADER) {
            return emptyList()
        }
        val cells = lines[1].split(',')
        return if (cells.size == COLUMNS.size) cells else emptyList()
    }

    private fun cells(m: Measurement): List<String> =
        listOf(
            m.point.id,
            m.repeat.toString(),
            m.point.trace.name,
            m.shape.tasks.toString(),
            m.shape.fragments.toString(),
            m.shape.spanSeconds.cell(),
            m.point.hosts.toString(),
            (m.point.exportIntervalMinutes * SECONDS_PER_MINUTE).toString(),
            m.point.tables.name,
            m.point.sampleFraction.cell(),
            if (m.point.trace.efficient) "efficient-trace" else "trace",
            m.point.parallelism.toString(),
            m.activeProcessors.toString(),
            m.point.collector.name,
            m.heapMb.toString(),
            heapFactorCell(m.point.heap),
            m.predicted.peakMemoryMb.cell(),
            m.predicted.cpuSeconds.cell(),
            m.exitCode.toString(),
            m.end.label,
            m.wallSeconds.cell(),
            m.unitSeconds.joinToString(";") { it.cell() },
        ) + memoryCells(m.memory) + peakCells(m.peak) + recordingCells(m.recording, m.outputBytes)

    private fun heapFactorCell(choice: HeapChoice): String =
        when (choice) {
            is HeapChoice.Measure -> ""
            is HeapChoice.Factor -> choice.factor.cell()
        }

    private fun memoryCells(memory: ProcessMemory): List<String> =
        when (memory) {
            is ProcessMemory.Sampled -> {
                val cpu = memory.summary.cpuSeconds
                listOf(cpu.values.sum().cell()) +
                    listOf(ThreadGroup.SIMULATION, ThreadGroup.WRITER, ThreadGroup.GC, ThreadGroup.JIT, ThreadGroup.OTHER)
                        .map { cpu.getValue(it).cell() } +
                    memory.summary.rssPeakMb.cell()
            }
            ProcessMemory.Unsampled -> List(MEMORY_CELLS) { "" }
        }

    private fun peakCells(peak: PeakMemory): List<String> =
        when (peak) {
            is PeakMemory.Measured -> listOf(peak.residentMb.cell(), peak.liveHeapMb.cell())
            PeakMemory.Unmeasured -> listOf("", "")
        }

    private fun recordingCells(
        recording: RecordingReading,
        outputBytes: Long,
    ): List<String> =
        when (recording) {
            is RecordingReading.Read -> {
                val summary = recording.summary
                listOf(
                    summary.heapAfterGc.maxOfOrNull { it.usedMb }?.cell() ?: "",
                    summary.gcPauseSeconds.cell(),
                    summary.gcCount.toString(),
                    outputBytes.toString(),
                ) + javaShares(summary.componentShares)
            }
            RecordingReading.Absent -> listOf("", "", "", outputBytes.toString()) + List(JAVA_SHARE_COLUMNS) { "" }
        }

    private const val JAVA_SHARE_COLUMNS = 4

    /** The row keeps the export pipeline as one share, so that rows measured before collecting and writing were told apart still merge. */
    private fun javaShares(shares: Map<CodeComponent, Double>): List<String> =
        listOf(
            shares.getValue(CodeComponent.LOADING),
            shares.getValue(CodeComponent.SIMULATING),
            shares.getValue(CodeComponent.COLLECTING) + shares.getValue(CodeComponent.WRITING),
            shares.getValue(CodeComponent.OTHER),
        ).map { it.cell() }

    private fun Double.cell(): String = if (isNaN()) "" else toString()

    private const val MEMORY_CELLS = 7
}
