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

package org.opendc.web.server.results

import jakarta.enterprise.context.ApplicationScoped
import org.apache.parquet.example.data.Group
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.api.ReadSupport
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.apache.parquet.io.InputFile
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.opendc.sdk.model.export.OutputFileSpec
import org.opendc.web.launcher.EntityFold
import org.opendc.web.launcher.MetricPoint
import org.opendc.web.launcher.Reading
import org.opendc.web.launcher.ResultMetric
import org.opendc.web.launcher.RowIdentity
import org.opendc.web.launcher.rows
import org.opendc.web.launcher.tableName
import org.opendc.web.server.storage.ObjectStore
import org.opendc.web.server.storage.StoredObjectFile
import java.util.Collections

/** The column every output table is stamped with, holding simulated milliseconds since the run began. */
private const val TIMESTAMP = "timestamp"

/**
 * How many runs are held reduced at once.
 *
 * A reduction is a few thousand doubles and the file behind it never changes, so keeping one is far
 * cheaper than reading a hundred megabytes of parquet again two seconds later. The cap is what stops
 * a server that has served a great many experiments from holding all of them.
 */
private const val MEMOISED_RUNS = 512

/** Nothing has been read yet. Simulated time starts at zero, so it cannot stand in for this. */
private const val NO_INSTANT = Long.MIN_VALUE

/** What a table with one row per instant calls that row. */
private const val THE_ONLY_ROW = "-"

/**
 * Reads the parquet a finished run left behind, as the series a chart is drawn from.
 *
 * This is the canonical account of a run: the samples a launcher posted while it was going are a
 * preview that expires, and these files are what it actually produced. Only the handful of columns
 * the charts name are read, which is the whole point of the format -- a host table of two dozen
 * columns costs three of them here.
 *
 * A run is read once and remembered. Its output is written whole and never touched again, so the
 * only thing that can make a reduction wrong is the run being done over, which is why a retry says
 * so through [forget].
 */
@ApplicationScoped
class ParquetSeries(private val store: ObjectStore) {
    private val memoised =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Map<ResultMetric, List<MetricPoint>>>(MEMOISED_RUNS, LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<ResultMetric, List<MetricPoint>>>): Boolean =
                    size > MEMOISED_RUNS
            },
        )

    /**
     * What the run whose output lies under [prefix] measured.
     *
     * Empty where it wrote nothing, which is what a run that failed before publishing looks like.
     * That answer is remembered too: this is only ever asked about runs that have stopped, so a run
     * with no output now will have none later either.
     */
    fun read(prefix: String): Map<ResultMetric, List<MetricPoint>> {
        memoised[prefix]?.let { return it }
        val reduced =
            OutputFileSpec.entries.fold(
                emptyMap<ResultMetric, List<MetricPoint>>(),
            ) { all, table -> all + readTable(prefix, table) }
        memoised[prefix] = reduced
        return reduced
    }

    /** Forgets every run under [prefix], because what it measured is about to be measured again. */
    fun forget(prefix: String) {
        synchronized(memoised) { memoised.keys.removeIf { it.startsWith(prefix) } }
    }

    private fun readTable(
        prefix: String,
        table: OutputFileSpec,
    ): Map<ResultMetric, List<MetricPoint>> {
        val metrics = ResultMetric.of(table)
        val key = "$prefix/${table.tableName}.parquet"
        if (metrics.isEmpty() || !store.exists(key)) {
            return emptyMap()
        }
        val file = StoredObjectFile(store, key)
        val schema = ParquetFileReader.open(file).use { it.fileMetaData.schema }
        // A deployment's export spec decides which columns are written, so a metric the file has
        // nothing to say about is left out rather than reported as zero.
        val present = metrics.filter { metric -> metric.columns.all { schema.containsField(it) } }
        if (present.isEmpty() || !schema.containsField(TIMESTAMP)) {
            return emptyMap()
        }

        // A table whose identity column the file does not carry is read as though it were a
        // singleton, which is what an old file written before that column existed looks like.
        val identity = (table.rows as? RowIdentity.PerEntity)?.column?.takeIf { schema.containsField(it) }
        val fold = InstantFold(present)
        rows(file, projection(schema, present, identity)).use { rows ->
            var row = rows.read()
            while (row != null) {
                val current = row
                val entity = identity?.let { current.getString(it, 0) } ?: THE_ONLY_ROW
                fold.row(number(current, TIMESTAMP).toLong(), entity) { metric -> reading(current, metric) }
                row = rows.read()
            }
        }
        return fold.finish()
    }

    private fun reading(
        row: Group,
        metric: ResultMetric,
    ): Reading =
        when (val fold = metric.entities) {
            is EntityFold.Each -> Reading(number(row, metric.column))
            is EntityFold.Share -> Reading(number(row, fold.part), number(row, fold.whole))
        }

    private fun projection(
        schema: MessageType,
        metrics: List<ResultMetric>,
        identity: String?,
    ): MessageType {
        val read = (metrics.flatMap { it.columns } + listOfNotNull(identity)).distinct()
        return MessageType(schema.name, listOf(schema.getType(TIMESTAMP)) + read.map { schema.getType(it) })
    }

    private fun rows(
        file: InputFile,
        projection: MessageType,
    ): ParquetReader<Group> =
        object : ParquetReader.Builder<Group>(file) {
            override fun getReadSupport(): ReadSupport<Group> = GroupReadSupport()
        }.set(ReadSupport.PARQUET_READ_SCHEMA, projection.toString()).build()

    private fun number(
        row: Group,
        column: String,
    ): Double =
        when (val type = row.type.getType(column).asPrimitiveType().primitiveTypeName) {
            PrimitiveTypeName.INT32 -> row.getInteger(column, 0).toDouble()
            PrimitiveTypeName.INT64 -> row.getLong(column, 0).toDouble()
            PrimitiveTypeName.FLOAT -> row.getFloat(column, 0).toDouble()
            PrimitiveTypeName.DOUBLE -> row.getDouble(column, 0)
            else -> throw IllegalStateException("$column is a $type, which is not a measurement")
        }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Folds the rows of one table into one value per metric per instant.
 *
 * A topology has many hosts and a chart has one line, so the rows sharing an instant become a single
 * reading: fleet power adds up where fleet utilization averages. Rows are kept by the entity that
 * reported them, because the writer records an instant twice at the end of a run -- once on the
 * export interval and once the moment the last task finishes -- and the second row for a host is that
 * host again rather than another one.
 *
 * Rows arrive in the order they were written, tick by tick, so an instant is complete exactly when a
 * later one begins, which is what keeps this bounded by the number of hosts rather than by the length
 * of the trace.
 */
private class InstantFold(private val metrics: List<ResultMetric>) {
    private val reporting = metrics.associateWith { mutableMapOf<String, Reading>() }
    private val series = metrics.associateWith { mutableListOf<MetricPoint>() }
    private var instant = NO_INSTANT

    fun row(
        timestamp: Long,
        entity: String,
        reading: (ResultMetric) -> Reading,
    ) {
        if (timestamp != instant) {
            close()
            instant = timestamp
        }
        for (metric in metrics) {
            reporting.getValue(metric)[entity] = reading(metric)
        }
    }

    fun finish(): Map<ResultMetric, List<MetricPoint>> {
        close()
        return metrics.associateWith { series.getValue(it).toList() }.filterValues { it.isNotEmpty() }
    }

    private fun close() {
        if (instant == NO_INSTANT) {
            return
        }
        for (metric in metrics) {
            val byEntity = reporting.getValue(metric)
            if (byEntity.isNotEmpty()) {
                series.getValue(metric).add(MetricPoint(instant, metric.fold(byEntity.values)))
                byEntity.clear()
            }
        }
        instant = NO_INSTANT
    }
}
