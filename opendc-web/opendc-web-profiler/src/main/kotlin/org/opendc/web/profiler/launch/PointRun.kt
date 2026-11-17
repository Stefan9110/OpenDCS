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

import kotlinx.serialization.SerializationException
import org.opendc.sdk.model.serialization.SdkJson
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.dispatcher.launcherExitReason
import org.opendc.web.launcher.MANIFEST_URL_VARIABLE
import org.opendc.web.launcher.PeakMemory
import org.opendc.web.launcher.UnitOutcome
import org.opendc.web.launcher.peakMemoryOf
import org.opendc.web.profiler.grid.GridPoint
import org.opendc.web.profiler.grid.heapMb
import org.opendc.web.profiler.grid.shippedPrediction
import org.opendc.web.profiler.probe.ProcSampler
import org.opendc.web.profiler.probe.readRecording
import org.opendc.web.profiler.probe.recordingFlags
import org.opendc.web.profiler.report.Measurement
import org.opendc.web.profiler.report.ProcessMemory
import org.opendc.web.profiler.report.RunEnd
import org.opendc.web.profiler.trace.TraceShape
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.walk

/** Whether a run's recording outlives its workspace. */
sealed interface Recordings {
    data object Discard : Recordings

    /** The dump is copied to `<dir>/<stem>.jfr` when the JVM wrote one. */
    data class Keep(val dir: Path) : Recordings
}

/**
 * @property timeout How long the launcher may run before it is killed and the point recorded as [RunEnd.TimedOut].
 * @property histogram Whether the recording counts objects after each collection, which costs a heap walk per collection.
 * @property recordings Whether each run's recording is kept, for reading by hand what the summary does not carry.
 * @property followParallelism Whether the JVM sees as many processors as the bag has units, as it would in a pod.
 */
data class RunSettings(
    val timeout: Duration,
    val histogram: Boolean,
    val recordings: Recordings = Recordings.Discard,
    val followParallelism: Boolean = true,
)

/**
 * Runs [point] once in a fresh launcher JVM and measures it from outside: the exit, the wall clock,
 * `/proc` while it runs, the recording and the files it published. The launcher's output streams
 * into the workspace while it runs, so a chatty launcher does not write to the output directory
 * on the clock; of it, [log] keeps the last [LOG_TAIL_LINES] lines of a finished run and all of a
 * run that was not. The workspace is removed afterwards whatever happened, and a launcher still
 * alive is killed.
 */
fun runPoint(
    point: GridPoint,
    repeat: Int,
    shape: TraceShape,
    traceDir: Path,
    workDir: Path,
    image: LauncherImage,
    settings: RunSettings,
    log: Path,
    env: Map<String, String> = emptyMap(),
): Measurement {
    val workspace = prepareWorkspace(point, traceDir, workDir)
    try {
        val predicted = shippedPrediction(point, shape)
        val heap = heapMb(point.heap, predicted.peakMemoryMb)
        val processors = if (settings.followParallelism) point.parallelism else Runtime.getRuntime().availableProcessors()
        val recording = recordingFlags(workspace.recording, workspace.recordingRepository, settings.histogram)
        val command = launcherCommand(image, heap, point.collector, processors, recording)
        val startedAt = Instant.now()
        val startNanos = System.nanoTime()
        val process =
            ProcessBuilder(command)
                .directory(workspace.dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(workspace.log.toFile())
                .also {
                    it.environment()[MANIFEST_URL_VARIABLE] = workspace.manifest.toUri().toString()
                    it.environment().putAll(env)
                }.start()
        val sampling = Sampling.of(process.pid())

        try {
            val finished = process.waitFor(settings.timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly().waitFor()
            }
            val wallSeconds = (System.nanoTime() - startNanos) / NANOS_PER_SECOND
            val memory = sampling.stop()
            val unitSeconds = succeededUnits(workspace.outcomes)
            val measurement =
                Measurement(
                    point = point,
                    repeat = repeat,
                    shape = shape,
                    activeProcessors = processors,
                    heapMb = heap,
                    predicted = predicted,
                    exitCode = process.exitValue(),
                    end = runEnd(finished, process.exitValue(), failed = point.parallelism - unitSeconds.size),
                    wallSeconds = wallSeconds,
                    unitSeconds = unitSeconds,
                    memory = memory,
                    peak = peakReport(workspace.peakMemory),
                    recording = readRecording(workspace.recording, workspace.recordingRepository, startedAt),
                    outputBytes = bytesUnder(workspace.outputDir),
                )

            keep(settings.recordings, workspace.recording, measurement.stem)
            publishLog(workspace.log, log, measurement.end)
            return measurement
        } finally {
            if (process.isAlive) {
                process.destroyForcibly().waitFor()
            }
        }
    } finally {
        workspace.dir.toFile().deleteRecursively()
    }
}

private const val NANOS_PER_SECOND = 1e9

/** How much of a finished run's output is worth keeping; a run that failed or was killed keeps all of it. */
const val LOG_TAIL_LINES = 500

private fun publishLog(
    source: Path,
    log: Path,
    end: RunEnd,
) {
    if (!Files.isRegularFile(source)) {
        return
    }
    Files.createDirectories(log.toAbsolutePath().parent)
    if (end != RunEnd.Finished) {
        Files.copy(source, log, StandardCopyOption.REPLACE_EXISTING)
        return
    }
    val tail = ArrayDeque<String>(LOG_TAIL_LINES)
    Files.lines(source).use { lines ->
        lines.forEach { line ->
            if (tail.size == LOG_TAIL_LINES) {
                tail.removeFirst()
            }
            tail.addLast(line)
        }
    }
    Files.write(log, tail)
}

private fun keep(
    recordings: Recordings,
    dump: Path,
    stem: String,
) {
    when (recordings) {
        Recordings.Discard -> {}
        is Recordings.Keep ->
            if (Files.isRegularFile(dump) && Files.size(dump) > 0) {
                Files.createDirectories(recordings.dir)
                Files.copy(dump, recordings.dir.resolve("$stem.jfr"), StandardCopyOption.REPLACE_EXISTING)
            }
    }
}

private fun runEnd(
    finished: Boolean,
    exitCode: Int,
    failed: Int,
): RunEnd {
    if (!finished) {
        return RunEnd.TimedOut
    }

    val reason = launcherExitReason(exitCode)
    return when {
        reason != ExitReason.OK -> RunEnd.Exited(reason)
        failed > 0 -> RunEnd.UnitsFailed(failed)
        else -> RunEnd.Finished
    }
}

/** `/proc` is read where it exists; elsewhere the memory columns stay empty. */
private sealed interface Sampling {
    fun stop(): ProcessMemory

    class Proc(private val sampler: ProcSampler) : Sampling {
        override fun stop(): ProcessMemory {
            sampler.close()
            return ProcessMemory.Sampled(sampler.summary())
        }
    }

    data object None : Sampling {
        override fun stop(): ProcessMemory = ProcessMemory.Unsampled
    }

    companion object {
        fun of(pid: Long): Sampling =
            if (Files.isDirectory(Path.of("/proc", pid.toString()))) {
                Proc(ProcSampler.ofProcess(pid).also { it.start() })
            } else {
                None
            }
    }
}

/** The seconds of every unit that published a success; a missing or unreadable outcome is a unit that did not. */
private fun succeededUnits(outcomes: List<Path>): List<Double> =
    outcomes
        .filter { Files.exists(it) }
        .flatMap(::outcomesIn)
        .filterIsInstance<UnitOutcome.Succeeded>()
        .map { it.seconds }

private fun outcomesIn(file: Path): List<UnitOutcome> =
    try {
        listOf(SdkJson.json.decodeFromString(UnitOutcome.serializer(), Files.readString(file)))
    } catch (e: SerializationException) {
        emptyList()
    }

private fun peakReport(file: Path): PeakMemory =
    try {
        peakMemoryOf(Files.readString(file))
    } catch (e: IOException) {
        PeakMemory.Unmeasured
    }

private fun bytesUnder(dir: Path): Long =
    if (Files.isDirectory(dir)) dir.walk().filter { Files.isRegularFile(it) }.sumOf { Files.size(it) } else 0L
