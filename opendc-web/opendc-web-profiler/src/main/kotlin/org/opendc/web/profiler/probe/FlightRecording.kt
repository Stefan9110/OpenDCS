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

package org.opendc.web.profiler.probe

import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Which part of a run a sampled Java stack belongs to: loading the trace, simulating, collecting
 * telemetry (the samplers run every interval whether or not a table is written) or writing it out.
 */
enum class CodeComponent { LOADING, SIMULATING, COLLECTING, WRITING, OTHER }

/**
 * A parquet writer thread is writing whatever it shows, since the codec frames under it name no
 * OpenDC package. On any other thread the first frame from the top that belongs to a known part
 * decides, however deep it sits; JDK and library frames never do.
 */
fun componentOf(
    thread: String,
    frames: List<String>,
): CodeComponent =
    if (thread.startsWith(WRITER_THREAD_PREFIX)) {
        CodeComponent.WRITING
    } else {
        frames.map(::componentOfFrame).firstOrNull { it != CodeComponent.OTHER } ?: CodeComponent.OTHER
    }

/** Writer threads are named after the `ParquetDataWriter` that owns them, `org.opendc.trace.parquet.exporter.Exporter@...` in a launcher. */
private const val WRITER_THREAD_PREFIX = "org.opendc.trace.parquet."

private fun componentOfFrame(frame: String): CodeComponent =
    when {
        frame.startsWith("org.opendc.sdk.runner.telemetry.parquet.") -> CodeComponent.WRITING
        frame.startsWith("org.opendc.sdk.runner.telemetry.sink.ParquetSink") -> CodeComponent.WRITING
        frame.startsWith("org.opendc.sdk.runner.telemetry.") -> CodeComponent.COLLECTING
        frame.startsWith("org.opendc.web.launcher.Telemetry") -> CodeComponent.COLLECTING
        frame.startsWith("org.opendc.trace.parquet.exporter.") -> CodeComponent.WRITING
        frame.startsWith("org.opendc.trace.") && "Writer" in frame -> CodeComponent.WRITING
        frame.startsWith("org.opendc.trace.") -> CodeComponent.LOADING
        frame.startsWith("org.opendc.sdk.model.workload.loader.") -> CodeComponent.LOADING
        frame.startsWith("org.opendc.simulator.") || frame.startsWith("org.opendc.sdk.runner.") -> CodeComponent.SIMULATING
        else -> CodeComponent.OTHER
    }

data class HeapSample(
    val tSeconds: Double,
    val usedMb: Double,
)

data class ClassBytes(
    val className: String,
    val count: Long,
    val bytes: Long,
)

/**
 * @property heapAfterGc The heap in use right after each collection, which is the closest a recording gets to live data.
 * @property componentShares Each component's share of the execution samples, summing to one when there were any.
 * @property histogramAtPeak Bytes by class after the fullest collection that counted objects, when any did.
 */
data class RecordingSummary(
    val heapAfterGc: List<HeapSample>,
    val gcPauseSeconds: Double,
    val gcCount: Int,
    val componentShares: Map<CodeComponent, Double>,
    val histogramAtPeak: List<ClassBytes>,
)

sealed interface RecordingReading {
    data class Read(val summary: RecordingSummary) : RecordingReading

    data object Absent : RecordingReading
}

/**
 * The JVM options that record into [dump] with the JDK's `profile` settings and keep the recording's
 * chunks under [repository], so that a killed JVM leaves them where the run can read them and nothing
 * in the system's temporary directory. Objects are counted after each collection when asked.
 */
fun recordingFlags(
    dump: Path,
    repository: Path,
    histogram: Boolean,
): List<String> {
    val options = mutableListOf("filename=${dump.toAbsolutePath()}", "settings=profile", "dumponexit=true")
    if (histogram) {
        options += "jdk.ObjectCountAfterGC#enabled=true"
    }
    return listOf(
        "-XX:FlightRecorderOptions:repository=${repository.toAbsolutePath()}",
        "-XX:StartFlightRecording:" + options.joinToString(","),
    )
}

/** The dump when the JVM wrote one; otherwise whatever chunks it left in [repository], a chunk cut short counted up to the cut. */
fun readRecording(
    dump: Path,
    repository: Path,
    startedAt: Instant,
): RecordingReading {
    val fold = RecordingFold(startedAt)
    if (Files.isRegularFile(dump) && fold.addFile(dump)) {
        return RecordingReading.Read(fold.summary())
    }
    val chunks = chunksIn(repository).count { fold.addFile(it) }
    return if (chunks == 0) RecordingReading.Absent else RecordingReading.Read(fold.summary())
}

/**
 * A dump a run kept, read on its own. Its heap timeline counts from the epoch, since the run's start is gone with it,
 * so only what does not depend on time, the component shares and the histogram, is meaningful.
 */
fun readKeptRecording(file: Path): RecordingReading {
    val fold = RecordingFold(Instant.EPOCH)
    return if (fold.addFile(file)) RecordingReading.Read(fold.summary()) else RecordingReading.Absent
}

private fun chunksIn(repository: Path): List<Path> {
    if (!Files.isDirectory(repository)) {
        return emptyList()
    }
    return Files.walk(repository).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jfr") }.sorted().toList()
    }
}

private class RecordingFold(private val startedAt: Instant) {
    private val heapAfterGc = mutableListOf<Pair<Int, HeapSample>>()
    private var gcPauseSeconds = 0.0
    private var gcCount = 0
    private val samples = CodeComponent.entries.associateWith { 0L }.toMutableMap()
    private val histograms = mutableMapOf<Int, MutableList<ClassBytes>>()

    /** Folds in what can be read of [file]; false when it cannot be opened at all. */
    fun addFile(file: Path): Boolean {
        val recording =
            try {
                RecordingFile(file)
            } catch (e: IOException) {
                return false
            }
        recording.use {
            try {
                while (it.hasMoreEvents()) {
                    add(it.readEvent())
                }
            } catch (e: IOException) {
                return true
            }
        }
        return true
    }

    fun add(event: RecordedEvent) {
        when (event.eventType.name) {
            "jdk.GCHeapSummary" -> if (event.getString("when") == "After GC") heapAfterGc += event.getInt("gcId") to heapSample(event)
            "jdk.GarbageCollection" -> {
                gcCount += 1
                gcPauseSeconds += event.getDuration("sumOfPauses").toNanos() / NANOS_PER_SECOND
            }
            "jdk.ExecutionSample" -> {
                val thread = event.getThread("sampledThread")?.javaName.orEmpty()
                val component = componentOf(thread, frames(event))
                samples[component] = samples.getValue(component) + 1
            }
            "jdk.ObjectCountAfterGC" ->
                histograms.getOrPut(event.getInt("gcId")) { mutableListOf() } +=
                    ClassBytes(event.getClass("objectClass").name, event.getLong("count"), event.getLong("totalSize"))
        }
    }

    fun summary(): RecordingSummary {
        val total = samples.values.sum().toDouble()
        val counted = heapAfterGc.filter { it.first in histograms }
        val fullest = counted.maxByOrNull { it.second.usedMb }?.first ?: histograms.maxByOrNull { it.value.sumOf(ClassBytes::bytes) }?.key
        return RecordingSummary(
            heapAfterGc = heapAfterGc.map { it.second },
            gcPauseSeconds = gcPauseSeconds,
            gcCount = gcCount,
            componentShares = samples.mapValues { if (total == 0.0) 0.0 else it.value / total },
            histogramAtPeak = fullest?.let { histograms.getValue(it) }.orEmpty().sortedByDescending { it.bytes },
        )
    }

    private fun heapSample(event: RecordedEvent): HeapSample {
        val seconds = Duration.between(startedAt, event.startTime).toNanos() / NANOS_PER_SECOND
        return HeapSample(seconds.coerceAtLeast(0.0), event.getLong("heapUsed") / BYTES_PER_MB)
    }

    private fun frames(event: RecordedEvent): List<String> =
        event.stackTrace?.frames.orEmpty().map { "${it.method.type.name}.${it.method.name}" }

    private companion object {
        const val NANOS_PER_SECOND = 1e9
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
