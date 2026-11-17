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

import org.opendc.web.profiler.probe.RecordingReading
import org.opendc.web.profiler.probe.RecordingSummary
import org.opendc.web.profiler.probe.readKeptRecording
import org.opendc.web.profiler.probe.threadGroupOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * The series behind one run, each kind in its own directory under the output: `timeline/` holds
 * resident memory and heap after collection over time, `cpu/` seconds by thread name and group,
 * `components/` the share of Java samples by part of the run, and `histogram/` bytes by class.
 */
object PointFiles {
    fun write(
        dir: Path,
        measurement: Measurement,
    ) {
        val stem = measurement.stem
        val timeline = mutableListOf<String>()
        when (val memory = measurement.memory) {
            is ProcessMemory.Sampled -> {
                timeline += memory.summary.rss.map { "${it.tSeconds},rss,${it.rssMb}" }
                val threads = memory.summary.cpuByThread.entries.sortedByDescending { it.value }
                writeCsv(
                    dir.resolve("cpu/$stem.csv"),
                    "thread,group,cpu_s",
                    threads.map { "${it.key},${threadGroupOf(it.key).name},${it.value}" },
                )
            }
            ProcessMemory.Unsampled -> {}
        }
        when (val recording = measurement.recording) {
            is RecordingReading.Read -> {
                timeline += recording.summary.heapAfterGc.map { "${it.tSeconds},heap_after_gc,${it.usedMb}" }
                writeRecording(dir, stem, recording.summary)
            }
            RecordingReading.Absent -> {}
        }
        if (timeline.isNotEmpty()) {
            writeCsv(dir.resolve("timeline/$stem.csv"), "t_s,kind,mb", timeline)
        }
    }

    /**
     * Re-reads every `<stem>.jfr` a run kept under [recordings] and rewrites the `components/` and `histogram/` files
     * under [dir] from it, for when the reading has been corrected since the run; returns how many were read.
     */
    fun rewrite(
        recordings: Path,
        dir: Path,
    ): Int {
        if (!Files.isDirectory(recordings)) {
            return 0
        }
        val kept =
            Files.list(recordings).use {
                    paths ->
                paths.filter { it.fileName.toString().endsWith(RECORDING_SUFFIX) }.sorted().toList()
            }
        return kept.count { file ->
            when (val reading = readKeptRecording(file)) {
                is RecordingReading.Read -> {
                    writeRecording(dir, file.fileName.toString().removeSuffix(RECORDING_SUFFIX), reading.summary)
                    true
                }
                RecordingReading.Absent -> false
            }
        }
    }

    private const val RECORDING_SUFFIX = ".jfr"

    private fun writeRecording(
        dir: Path,
        stem: String,
        summary: RecordingSummary,
    ) {
        writeCsv(dir.resolve("components/$stem.csv"), "component,share", summary.componentShares.map { "${it.key.name},${it.value}" })
        if (summary.histogramAtPeak.isNotEmpty()) {
            writeCsv(
                dir.resolve("histogram/$stem.csv"),
                "class,count,bytes",
                summary.histogramAtPeak.map { "${it.className},${it.count},${it.bytes}" },
            )
        }
    }

    private fun writeCsv(
        file: Path,
        header: String,
        rows: List<String>,
    ) {
        Files.createDirectories(file.parent)
        Files.write(file, listOf(header) + rows)
    }
}
