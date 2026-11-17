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

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

/** Who in the launcher process is burning CPU, told apart by the kernel's 15-character thread name. */
enum class ThreadGroup {
    /** The runner's fork-join workers, where loading, the engine and metric collection all run. */
    SIMULATION,

    /** The parquet writer threads behind each exported table. */
    WRITER,
    GC,
    JIT,

    /** Everything else, the launcher's own unit threads among them: they only wait and copy outputs. */
    OTHER,
}

fun threadGroupOf(comm: String): ThreadGroup =
    when {
        comm.startsWith("ForkJoinPool-") -> ThreadGroup.SIMULATION
        comm.startsWith("org.opendc") || "Parquet" in comm -> ThreadGroup.WRITER
        GC_THREAD_PREFIXES.any { comm.startsWith(it) } -> ThreadGroup.GC
        comm.startsWith("C1 Compiler") || comm.startsWith("C2 Compiler") -> ThreadGroup.JIT
        else -> ThreadGroup.OTHER
    }

private val GC_THREAD_PREFIXES = listOf("GC Thread", "G1 ", "VM Thread", "Concurrent", "Parallel GC")

/** One thread's line of `/proc/<pid>/task/<tid>/stat`: its cumulative user plus system time in clock ticks. */
data class ThreadStat(
    val tid: Long,
    val comm: String,
    val cpuTicks: Long,
)

/** The name sits in parentheses and may itself hold spaces and parentheses, so the fields are counted from its end. */
fun parseStat(line: String): ThreadStat {
    val open = line.indexOf('(')
    val close = line.lastIndexOf(')')
    val fields = line.substring(close + 1).trim().split(' ')
    return ThreadStat(
        tid = line.substring(0, open).trim().toLong(),
        comm = line.substring(open + 1, close),
        cpuTicks = fields[UTIME_FIELD].toLong() + fields[STIME_FIELD].toLong(),
    )
}

private const val UTIME_FIELD = 11
private const val STIME_FIELD = 12

/** The `VmRSS` line of `/proc/<pid>/status`, or zero when the file has none. */
fun parseRssKb(status: String): Long =
    status
        .lineSequence()
        .filter { it.startsWith("VmRSS:") }
        .map { it.split(WHITESPACE)[1].toLong() }
        .firstOrNull() ?: 0L

private val WHITESPACE = Regex("\\s+")

data class RssSample(
    val tSeconds: Double,
    val rssMb: Double,
)

/**
 * What the sampler saw of a process: its resident memory over time, and CPU seconds by the kernel's
 * thread name, threads of one name summed, so that a grouping can be redone from the files later.
 */
data class ProcSummary(
    val rss: List<RssSample>,
    val cpuByThread: Map<String, Double>,
    val rssPeakMb: Double,
) {
    val cpuSeconds: Map<ThreadGroup, Double>
        get() {
            val seconds = ThreadGroup.entries.associateWith { 0.0 }.toMutableMap()
            for ((comm, cpu) in cpuByThread) {
                val group = threadGroupOf(comm)
                seconds[group] = seconds.getValue(group) + cpu
            }
            return seconds
        }
}

/**
 * Polls one process's `/proc` directory on a daemon thread. Per-thread CPU counters only grow, so
 * the last reading of each thread is its total, and a thread that is gone keeps the total it had.
 */
class ProcSampler(
    private val root: Path,
    private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
    private val ticksPerSecond: Long = CLOCK_TICKS_PER_SECOND,
) : AutoCloseable {
    private val startedAt = System.nanoTime()
    private val rss = mutableListOf<RssSample>()
    private val latestByThread = mutableMapOf<Long, ThreadStat>()
    private val worker = Thread.ofPlatform().daemon().name("proc-sampler").unstarted(::loop)

    fun start() {
        worker.start()
    }

    @Synchronized
    fun sampleOnce() {
        readOrSkip(root.resolve("status")) { status ->
            val kb = parseRssKb(status)
            if (kb > 0) {
                rss += RssSample((System.nanoTime() - startedAt) / NANOS_PER_SECOND, kb / KB_PER_MB)
            }
        }
        val tasks = root.resolve("task")
        if (!Files.isDirectory(tasks)) {
            return
        }
        for (task in tasks.listDirectoryEntries()) {
            readOrSkip(task.resolve("stat")) { line ->
                val stat = parseStat(line)
                latestByThread[stat.tid] = stat
            }
        }
    }

    @Synchronized
    fun summary(): ProcSummary {
        val byThread = mutableMapOf<String, Double>()
        for (stat in latestByThread.values) {
            byThread.merge(stat.comm, stat.cpuTicks.toDouble() / ticksPerSecond, Double::plus)
        }
        return ProcSummary(rss.toList(), byThread, rss.maxOfOrNull { it.rssMb } ?: 0.0)
    }

    override fun close() {
        worker.interrupt()
        worker.join(periodMillis * 2)
    }

    private fun loop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                sampleOnce()
                Thread.sleep(periodMillis)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private inline fun readOrSkip(
        file: Path,
        read: (String) -> Unit,
    ) {
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                return
            }
        read(text)
    }

    companion object {
        const val DEFAULT_PERIOD_MILLIS = 200L

        /** Linux reports thread times in `USER_HZ` ticks, which is 100 on every build that matters here. */
        const val CLOCK_TICKS_PER_SECOND = 100L
        private const val NANOS_PER_SECOND = 1e9
        private const val KB_PER_MB = 1024.0

        fun ofProcess(
            pid: Long,
            periodMillis: Long = DEFAULT_PERIOD_MILLIS,
        ): ProcSampler = ProcSampler(Path.of("/proc", pid.toString()), periodMillis)
    }
}
