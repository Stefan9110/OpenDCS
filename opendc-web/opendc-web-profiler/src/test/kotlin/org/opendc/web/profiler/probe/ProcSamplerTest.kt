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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * The sampler reads another process's `/proc` while that process is changing underneath it, so
 * what matters is that it parses the kernel's quirks and survives threads coming and going.
 */
class ProcSamplerTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `parses a stat line whose name has spaces and parentheses`() {
        val stat = parseStat("12 (G1 Conc#0 (x)) S 1 1 1 0 -1 4194368 0 0 0 0 7 3 0 0 20 0 1 0 100 0 0")

        assertEquals(12L, stat.tid)
        assertEquals("G1 Conc#0 (x)", stat.comm)
        assertEquals(10L, stat.cpuTicks)
    }

    @Test
    fun `groups thread names`() {
        assertEquals(ThreadGroup.SIMULATION, threadGroupOf("ForkJoinPool-1-"))
        assertEquals(ThreadGroup.OTHER, threadGroupOf("pool-1-thread-2"))
        assertEquals(ThreadGroup.WRITER, threadGroupOf("org.opendc.trac"))
        assertEquals(ThreadGroup.JIT, threadGroupOf("C2 CompilerThre"))
        assertEquals(ThreadGroup.JIT, threadGroupOf("C1 CompilerThre"))
        assertEquals(ThreadGroup.GC, threadGroupOf("GC Thread#0"))
        assertEquals(ThreadGroup.GC, threadGroupOf("G1 Conc#0"))
        assertEquals(ThreadGroup.GC, threadGroupOf("VM Thread"))
        assertEquals(ThreadGroup.OTHER, threadGroupOf("java"))
        assertEquals(ThreadGroup.OTHER, threadGroupOf("JFR Periodic Ta"))
    }

    @Test
    fun `reads VmRSS in kilobytes`() {
        assertEquals(4567L, parseRssKb("Name:\tjava\nVmPeak:\t   100 kB\nVmRSS:\t  4567 kB\nVmHWM:\t  9999 kB\n"))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `samples this process`() {
        val deadline = System.nanoTime() + 600_000_000L
        val spinner = thread(name = "ForkJoinPool-1-worker-9") { while (System.nanoTime() < deadline) Unit }

        val summary =
            ProcSampler.ofProcess(ProcessHandle.current().pid(), periodMillis = 50).use { sampler ->
                sampler.start()
                spinner.join()
                sampler.summary()
            }

        assertTrue(summary.rssPeakMb > 0.0)
        assertTrue(summary.rss.size >= 2, "${summary.rss.size} samples")
        assertTrue(summary.cpuSeconds.getValue(ThreadGroup.SIMULATION) > 0.0)
        assertTrue(summary.cpuByThread.keys.any { it.startsWith("ForkJoinPool") }, summary.cpuByThread.keys.toString())
    }

    @Test
    fun `a status without VmRSS, as a process that has exited leaves, adds no sample`() {
        Files.writeString(dir.resolve("status"), "Name:\tjava\nState:\tZ (zombie)\n")
        val sampler = ProcSampler(dir, periodMillis = 10)

        sampler.sampleOnce()

        assertEquals(0, sampler.summary().rss.size)
    }

    @Test
    fun `a thread that vanishes is skipped`() {
        Files.createDirectories(dir.resolve("task/42"))
        Files.writeString(dir.resolve("status"), "VmRSS:\t 2048 kB\n")

        val sampler = ProcSampler(dir, periodMillis = 10)
        sampler.sampleOnce()
        val summary = sampler.summary()

        assertEquals(1, summary.rss.size)
        assertEquals(2.0, summary.rssPeakMb)
        assertTrue(summary.cpuSeconds.values.all { it == 0.0 })
    }
}
