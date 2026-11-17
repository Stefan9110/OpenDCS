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

import jdk.jfr.Configuration
import jdk.jfr.Recording
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The recording is the only view of the heap between collections and of where Java time goes, so
 * the reader has to find those events, survive a JVM that was killed mid-chunk, and attribute a
 * stack to the right part of the simulator.
 */
class FlightRecordingTest {
    @TempDir
    lateinit var dir: Path

    private val worker = "ForkJoinPool-1-worker-1"

    @Test
    fun `classifies a stack by its first recognised frame`() {
        assertEquals(
            CodeComponent.SIMULATING,
            componentOf(worker, listOf("java.util.ArrayList.add", "org.opendc.simulator.compute.SimHost.run")),
        )
        assertEquals(
            CodeComponent.WRITING,
            componentOf(
                worker,
                listOf("org.apache.parquet.column.impl.ColumnWriterV2.write", "org.opendc.trace.parquet.ParquetDataWriter.run"),
            ),
        )
        assertEquals(
            CodeComponent.LOADING,
            componentOf(
                worker,
                listOf(
                    "org.apache.parquet.hadoop.InternalParquetRecordReader.read",
                    "org.opendc.trace.parquet.LocalParquetReader.read",
                    "org.opendc.sdk.model.workload.loader.ComputeWorkloadLoader.parseFragments",
                ),
            ),
        )
        assertEquals(
            CodeComponent.COLLECTING,
            componentOf(
                worker,
                listOf("org.opendc.sdk.runner.telemetry.ComputeMetricReader.loggState", "org.opendc.simulator.compute.SimHost.run"),
            ),
        )
        assertEquals(CodeComponent.OTHER, componentOf("main", listOf("java.lang.Thread.sleep", "jdk.internal.misc.Unsafe.park")))
    }

    @Test
    fun `telemetry samplers and the launcher's monitor collect, the parquet exporters write`() {
        val sampler = listOf("org.opendc.sdk.runner.telemetry.table.host.HostSampler.sample", "org.opendc.simulator.compute.SimHost.run")
        val exporter =
            listOf(
                "org.opendc.sdk.runner.telemetry.parquet.ParquetMetricExporter.write",
                "org.opendc.sdk.runner.telemetry.ComputeMetricReader.loggState",
            )

        assertEquals(CodeComponent.COLLECTING, componentOf(worker, sampler))
        assertEquals(CodeComponent.COLLECTING, componentOf(worker, listOf("org.opendc.web.launcher.TelemetryMonitor.export")))
        assertEquals(CodeComponent.WRITING, componentOf(worker, exporter))
    }

    @Test
    fun `a writer thread is writing whatever frames it shows, and exporter frames need no Writer in their name`() {
        assertEquals(
            CodeComponent.WRITING,
            componentOf(
                "org.opendc.trace.parquet.exporter.Exporter@6f2b958e",
                listOf(
                    "com.github.luben.zstd.ZstdOutputStream.write",
                    "org.apache.parquet.hadoop.CodecFactory\$HeapBytesCompressor.compress",
                ),
            ),
        )
        assertEquals(
            CodeComponent.WRITING,
            componentOf(
                worker,
                listOf(
                    "org.opendc.trace.parquet.exporter.Exporter\$Companion\$writeSuppFor\$1.write",
                    "org.opendc.trace.parquet.ParquetDataWriter.run",
                ),
            ),
        )
    }

    @Test
    fun `a frame deep in the stack still decides`() {
        val deep = List(40) { "java.util.HashMap.get" } + "org.opendc.simulator.compute.SimHost.run"

        assertEquals(CodeComponent.SIMULATING, componentOf(worker, deep))
    }

    @Test
    fun `reads heap after collection from a recording`() {
        val file = dir.resolve("recording.jfr")
        val startedAt = Instant.now()
        record(file)

        val reading = readRecording(file, dir.resolve("jfr"), startedAt)

        assertTrue(reading is RecordingReading.Read, "$reading")
        val summary = (reading as RecordingReading.Read).summary
        assertTrue(summary.heapAfterGc.isNotEmpty())
        assertTrue(summary.heapAfterGc.all { it.tSeconds >= 0.0 && it.usedMb > 0.0 })
        assertTrue(summary.gcCount >= 1)
    }

    @Test
    fun `a missing file with an empty repository is absent`() {
        assertEquals(RecordingReading.Absent, readRecording(dir.resolve("none.jfr"), dir.resolve("jfr"), Instant.now()))
    }

    @Test
    fun `chunks left in the repository are read when the dump is missing, and a truncated chunk is skipped`() {
        val chunks = dir.resolve("jfr/2026_11_08_12_00_00_4242")
        Files.createDirectories(chunks)
        val startedAt = Instant.now()
        record(chunks.resolve("2026_11_08_12_00_01.jfr"))
        record(chunks.resolve("2026_11_08_12_00_02.jfr"))
        val whole = Files.readAllBytes(chunks.resolve("2026_11_08_12_00_02.jfr"))
        Files.write(chunks.resolve("2026_11_08_12_00_03.jfr"), whole.copyOf(whole.size / 2))

        val reading = readRecording(dir.resolve("recording.jfr"), dir.resolve("jfr"), startedAt)

        assertTrue(reading is RecordingReading.Read, "$reading")
        assertTrue((reading as RecordingReading.Read).summary.gcCount >= 2, "both whole chunks are folded in")
    }

    @Test
    fun `the histogram is taken from the fullest collection that counted objects`() {
        val file = dir.resolve("histogram.jfr")
        val startedAt = Instant.now()
        Recording(Configuration.getConfiguration("profile")).use { recording ->
            recording.enable("jdk.ObjectCountAfterGC")
            recording.start()
            val junk = ArrayList<ByteArray>()
            repeat(64) { junk += ByteArray(1 shl 20) }
            System.gc()
            val churn = arrayOfNulls<ByteArray>(4)
            repeat(400) { churn[it % churn.size] = ByteArray(1 shl 20) }
            Thread.sleep(100)
            recording.stop()
            recording.dump(file)
            assertTrue(junk.isNotEmpty() && churn.isNotEmpty())
        }

        val summary = (readRecording(file, dir.resolve("jfr"), startedAt) as RecordingReading.Read).summary

        assertTrue(summary.histogramAtPeak.isNotEmpty(), "objects were counted, so the peak is picked among counted collections")
        assertTrue(summary.histogramAtPeak.any { it.className == "[B" }, summary.histogramAtPeak.take(5).toString())
    }

    @Test
    fun `the repository and the histogram setting ride on the flags`() {
        val plain = recordingFlags(dir.resolve("recording.jfr"), dir.resolve("jfr"), histogram = false)
        val withHistogram = recordingFlags(dir.resolve("recording.jfr"), dir.resolve("jfr"), histogram = true)
        val start = plain.single { it.startsWith("-XX:StartFlightRecording:") }

        assertEquals("-XX:FlightRecorderOptions:repository=${dir.resolve("jfr").toAbsolutePath()}", plain.first())
        assertTrue("settings=profile" in start)
        assertTrue("dumponexit=true" in start)
        assertFalse("ObjectCountAfterGC" in start)
        assertTrue(withHistogram.any { "jdk.ObjectCountAfterGC#enabled=true" in it })
    }

    private fun record(file: Path) {
        Recording(Configuration.getConfiguration("profile")).use { recording ->
            recording.start()
            val junk = ArrayList<ByteArray>()
            repeat(64) { junk += ByteArray(1 shl 20) }
            System.gc()
            Thread.sleep(100)
            recording.stop()
            recording.dump(file)
            assertTrue(junk.isNotEmpty())
        }
    }
}
