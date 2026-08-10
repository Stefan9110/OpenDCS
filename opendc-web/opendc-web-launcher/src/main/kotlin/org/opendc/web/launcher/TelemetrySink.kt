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

package org.opendc.web.launcher

import org.opendc.compute.simulator.telemetry.ComputeMonitor
import org.opendc.compute.simulator.telemetry.OutputFiles
import org.opendc.compute.simulator.telemetry.table.battery.BatteryTableReader
import org.opendc.compute.simulator.telemetry.table.host.HostTableReader
import org.opendc.compute.simulator.telemetry.table.powerSource.PowerSourceTableReader
import org.opendc.compute.simulator.telemetry.table.service.ServiceTableReader
import org.opendc.sdk.runner.sink.OutputSink
import org.opendc.sdk.runner.sink.RunContext
import org.opendc.sdk.runner.sink.SinkResult
import org.opendc.sdk.runner.sink.SinkSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How many points one metric of one run is ever held at.
 *
 * Simulated time is not wall-clock time: a month-long trace exported every five simulated minutes is
 * eight thousand samples that can arrive in a thirty-second burst. Capping here rather than at the
 * server is what keeps a report the same size however long the trace is.
 */
private const val SAMPLE_CAP = 512

/** Nothing has been read yet. Simulated time starts at zero, so it cannot stand in for this. */
private const val NO_INSTANT = Long.MIN_VALUE

/**
 * Watches the runs in this process so their progress can be reported while they are still going.
 *
 * The parquet a run leaves behind is the canonical account of it; this is the preview. It reads the
 * four tables the result charts are drawn from and never the task table, which is per-task and by far
 * the largest.
 */
class TelemetrySink : OutputSink {
    private val runs = CopyOnWriteArrayList<RunSamples>()

    override fun open(context: RunContext): SinkSession {
        val samples = RunSamples(context.scenarioId, context.seed)
        runs += samples
        return object : SinkSession {
            override val monitor: ComputeMonitor = TelemetryMonitor(samples)

            override val tables: Set<OutputFiles> =
                setOf(OutputFiles.HOST, OutputFiles.SERVICE, OutputFiles.POWER_SOURCE, OutputFiles.BATTERY)

            /** Called once the run has finished, which is the last chance to close its final instant. */
            override fun result(): SinkResult? {
                samples.finish()
                return null
            }
        }
    }

    /** Everything measured so far, as it would be reported now. */
    fun report(): TelemetryReport = TelemetryReport(runs.map { it.snapshot() })
}

/** There is one scheduling service, so every row it reports at an instant is the same row again. */
private const val THE_SERVICE = "service"

private class TelemetryMonitor(private val samples: RunSamples) : ComputeMonitor {
    override fun record(reader: HostTableReader) {
        samples.record(
            reader.timestamp.toEpochMilli(),
            reader.hostInfo.name,
            // The work this host got through and what it had to do it with, so the fleet's share is
            // weighted by capacity rather than being an average of per-host shares.
            ResultMetric.HOST_CPU_UTILIZATION to Reading(reader.cpuUsage, reader.cpuCapacity),
            ResultMetric.HOST_POWER_DRAW to Reading(reader.powerDraw),
        )
    }

    override fun record(reader: PowerSourceTableReader) {
        samples.record(
            reader.timestamp.toEpochMilli(),
            reader.powerSourceInfo.name,
            ResultMetric.POWER_SOURCE_POWER_DRAW to Reading(reader.powerDraw),
            ResultMetric.POWER_SOURCE_ENERGY_USAGE to Reading(reader.energyUsage),
            ResultMetric.POWER_SOURCE_CARBON_EMISSION to Reading(reader.carbonEmission),
            ResultMetric.POWER_SOURCE_CARBON_INTENSITY to Reading(reader.carbonEmission, reader.energyUsage),
        )
    }

    override fun record(reader: BatteryTableReader) {
        samples.record(reader.timestamp.toEpochMilli(), reader.batteryInfo.name, ResultMetric.BATTERY_CHARGE to Reading(reader.charge))
    }

    override fun record(reader: ServiceTableReader) {
        samples.progress(reader.tasksCompleted + reader.tasksTerminated)
        samples.record(
            reader.timestamp.toEpochMilli(),
            THE_SERVICE,
            ResultMetric.SERVICE_TASKS_ACTIVE to Reading(reader.tasksActive.toDouble()),
            ResultMetric.SERVICE_TASKS_PENDING to Reading(reader.tasksPending.toDouble()),
            ResultMetric.SERVICE_TASKS_COMPLETED to Reading(reader.tasksCompleted.toDouble()),
            ResultMetric.SERVICE_TASKS_TERMINATED to Reading(reader.tasksTerminated.toDouble()),
            ResultMetric.SERVICE_HOSTS_DOWN to Reading(reader.hostsDown.toDouble()),
        )
    }
}

/**
 * What one `(scenario, seed)` run has reported.
 *
 * Written from the run's own thread and read from the one that posts, so every path takes the lock.
 * The engine hands the same instant over one row at a time -- once per host, once per power source --
 * and the tables of a tick all carry the clock reading it was taken at, so an instant is complete
 * exactly when a later one begins.
 *
 * Rows are kept by the entity that reported them rather than counted, because an instant is reported
 * more than once at the end of a run and the second report of a host is that host again.
 */
private class RunSamples(
    private val scenarioIndex: Int,
    private val seed: Long,
) {
    private val lock = Any()
    private var completedTasks = 0
    private var instant = NO_INSTANT
    private val reporting = ResultMetric.entries.associateWith { mutableMapOf<String, Reading>() }
    private val series = ResultMetric.entries.associateWith { PointBuffer(it.overTime) }

    fun progress(completed: Int) {
        synchronized(lock) { completedTasks = completed }
    }

    fun record(
        timestamp: Long,
        entity: String,
        vararg readings: Pair<ResultMetric, Reading>,
    ) {
        synchronized(lock) {
            if (timestamp != instant) {
                closeInstant()
                instant = timestamp
            }
            for ((metric, reading) in readings) {
                reporting.getValue(metric)[entity] = reading
            }
        }
    }

    /** Closes the instant left open when the run stopped producing rows. */
    fun finish() {
        synchronized(lock) { closeInstant() }
    }

    fun snapshot(): RunTelemetry =
        synchronized(lock) {
            RunTelemetry(
                scenarioIndex = scenarioIndex,
                seed = seed,
                completedTasks = completedTasks,
                series =
                    ResultMetric.entries.mapNotNull { metric ->
                        series.getValue(metric).points().takeIf { it.isNotEmpty() }?.let { MetricSeries(metric.id, it) }
                    },
            )
        }

    /** Folds the entities that reported at the open instant into one value per metric. */
    private fun closeInstant() {
        if (instant == NO_INSTANT) {
            return
        }
        for ((metric, byEntity) in reporting) {
            if (byEntity.isNotEmpty()) {
                series.getValue(metric).add(instant, metric.fold(byEntity.values))
                byEntity.clear()
            }
        }
        instant = NO_INSTANT
    }
}

/**
 * One metric's points, kept under [SAMPLE_CAP] however many instants arrive.
 *
 * Each stored point covers [stride] instants, folded the way the metric asks: a counter carried
 * forward, a gauge averaged, a queue depth taken at its worst. Overflow doubles the stride and pairs
 * the buffer up, which is exact because every point in it covers the same span. The instants
 * accumulated toward the next point are reported as a provisional last point, so a live chart keeps
 * moving between strides.
 */
private class PointBuffer(private val fold: Reduction) {
    private val points = mutableListOf<MetricPoint>()
    private val pending = mutableListOf<Double>()
    private var pendingAt = NO_INSTANT
    private var stride = 1

    fun add(
        timestamp: Long,
        value: Double,
    ) {
        if (pending.isEmpty()) {
            pendingAt = timestamp
        }
        pending += value
        if (pending.size < stride) {
            return
        }
        points += MetricPoint(pendingAt, fold.of(pending))
        pending.clear()
        if (points.size >= SAMPLE_CAP) {
            merge()
        }
    }

    fun points(): List<MetricPoint> = if (pending.isEmpty()) points.toList() else points + MetricPoint(pendingAt, fold.of(pending))

    private fun merge() {
        val merged = points.chunked(2) { pair -> MetricPoint(pair.first().t, fold.of(pair.map { it.value })) }
        points.clear()
        points += merged
        stride *= 2
    }
}
